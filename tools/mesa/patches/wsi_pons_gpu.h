/*
 * Pons GPU copy of finished frames into the app's AHardwareBuffers.
 *
 * Today a software present is: the GPU copies the tiled swapchain image into a
 * host buffer (Mesa's buffer blit), then the CPU memcpy's that into the
 * AHardwareBuffer (wsi_pons_fb.h). With PONS_GPU_COPY=1 and the zero-upload
 * mode (PONS_AHB=1) the three AHardwareBuffer dma-bufs are imported as
 * VkDeviceMemory bound to transfer-destination VkBuffers, and the copy that
 * Mesa already submits at vkQueuePresentKHR is replaced by one that writes the
 * image straight into the chosen buffer (vkCmdCopyImageToBuffer with
 * bufferRowLength = the buffer's stride). Same submission, same fence the
 * present thread already waits on, so no queue is touched from another thread.
 *
 * Choice: reuse Mesa's swapchain (device-local image + buffer blit, which the
 * sw path already is) and only swap the destination command buffer, instead of
 * changing how swapchain images are configured. The swapchain is untouched, so
 * the CPU path stays available frame by frame.
 *
 * Fallback is per frame and silent: any failure to import or to reserve a slot
 * leaves Mesa's own host blit in the submission and the present thread does
 * the CPU memcpy. Logs once to stderr which path is active.
 *
 * Included by wsi_common_x11.c only; the single hook in wsi_common.c is the
 * pons_blit_cmd callback on the swapchain.
 *
 * SPDX-License-Identifier: MIT
 */
#ifndef WSI_PONS_GPU_H
#define WSI_PONS_GPU_H

#include <strings.h>
#include "wsi_pons_fb.h"

#define PONS_GPU_MAX_IMAGES 16

struct pons_gpu {
   pthread_mutex_t lock;
   bool failed;             /* gave up for this process */
   bool logged_active;
   bool logged_unavailable;
   struct wsi_swapchain *chain;
   VkBuffer buffer[PONS_PRESENT_BUFFERS];
   VkDeviceMemory memory[PONS_PRESENT_BUFFERS];
   uint32_t stride_px;
   uint32_t width, height;
   VkCommandPool pool;
   uint32_t pool_family;
   VkCommandBuffer cmd[PONS_GPU_MAX_IMAGES];
   uint32_t cmd_used;       /* bit per image index that ever had a cmd submitted */
   int8_t image_target[PONS_GPU_MAX_IMAGES]; /* slot reserved for that image, or -1 */
};

static struct pons_gpu pons_gpu_state = {
   .lock = PTHREAD_MUTEX_INITIALIZER,
   .image_target = { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
};

static bool
pons_gpu_enabled(void)
{
   const char *v = getenv("PONS_GPU_COPY");
   return v && strcmp(v, "1") == 0;
}

/* Caller holds the gpu lock. */
static void
pons_gpu_log_unavailable(const char *reason)
{
   if (pons_gpu_state.logged_unavailable)
      return;
   pons_gpu_state.logged_unavailable = true;
   fprintf(stderr, "pons: gpu copy unavailable: %s, using cpu copy\n", reason);
}

/* Application thread, from vkQueuePresentKHR. Returns the command buffer that
 * copies the image into a reserved AHardwareBuffer slot, or NULL to keep
 * Mesa's host blit for this frame. */
static VkCommandBuffer
pons_gpu_blit_cmd(struct wsi_swapchain *chain, uint32_t image_index,
                  uint32_t queue_family)
{
   struct pons_gpu *g = &pons_gpu_state;
   struct pons_present *p = &pons_present_state;
   const struct wsi_device *wsi = chain->wsi;
   VK_FROM_HANDLE(vk_device, dev, chain->device);
   VkCommandBuffer cmd = VK_NULL_HANDLE;
   const char *fail = NULL;
   uint32_t target = 0;
   bool reserved = false;

   pthread_mutex_lock(&g->lock);
   if (g->chain != chain || image_index >= PONS_GPU_MAX_IMAGES)
      goto out;

   /* A slot reserved by an earlier present of this image that never reached
    * the present thread: give it back. */
   if (g->image_target[image_index] >= 0) {
      pons_gpu_slot_cancel(p, (uint32_t)g->image_target[image_index]);
      g->image_target[image_index] = -1;
   }
   if (!pons_gpu_slot_acquire(p, &target))
      goto out; /* no free slot this frame: CPU path */
   reserved = true;

   if (g->pool == VK_NULL_HANDLE) {
      const VkCommandPoolCreateInfo pool_info = {
         .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
         .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
         .queueFamilyIndex = queue_family,
      };
      if (wsi->CreateCommandPool(chain->device, &pool_info, &chain->alloc,
                                 &g->pool) != VK_SUCCESS) {
         g->pool = VK_NULL_HANDLE;
         fail = "command pool";
         goto out;
      }
      g->pool_family = queue_family;
   } else if (g->pool_family != queue_family) {
      fail = "presenting from another queue family";
      goto out;
   }

   if (g->cmd[image_index] == VK_NULL_HANDLE) {
      const VkCommandBufferAllocateInfo alloc_info = {
         .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
         .commandPool = g->pool,
         .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
         .commandBufferCount = 1,
      };
      if (wsi->AllocateCommandBuffers(chain->device, &alloc_info,
                                      &g->cmd[image_index]) != VK_SUCCESS) {
         g->cmd[image_index] = VK_NULL_HANDLE;
         fail = "command buffer";
         goto out;
      }
   } else if (dev->dispatch_table.ResetCommandBuffer == NULL ||
              dev->dispatch_table.ResetCommandBuffer(g->cmd[image_index], 0) != VK_SUCCESS) {
      fail = "command buffer reset";
      goto out;
   }

   const VkCommandBuffer cb = g->cmd[image_index];
   const struct wsi_image *image = chain->get_wsi_image(chain, image_index);
   const VkCommandBufferBeginInfo begin = {
      .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
      .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
   };
   if (wsi->BeginCommandBuffer(cb, &begin) != VK_SUCCESS) {
      fail = "command buffer begin";
      goto out;
   }

   /* Same barriers as Mesa's wsi_cmd_blit_image_to_buffer. */
   VkImageMemoryBarrier img_barrier = {
      .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
      .srcAccessMask = 0,
      .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT,
      .oldLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
      .newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
      .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
      .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
      .image = image->image,
      .subresourceRange = {
         .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
         .levelCount = 1,
         .layerCount = 1,
      },
   };
   wsi->CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                           VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                           0, NULL, 0, NULL, 1, &img_barrier);

   const VkBufferImageCopy copy = {
      .bufferOffset = 0,
      .bufferRowLength = g->stride_px,
      .bufferImageHeight = 0,
      .imageSubresource = {
         .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
         .layerCount = 1,
      },
      .imageExtent = { g->width, g->height, 1 },
   };
   wsi->CmdCopyImageToBuffer(cb, image->image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                             g->buffer[target], 1, &copy);

   img_barrier.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
   img_barrier.dstAccessMask = 0;
   img_barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
   img_barrier.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
   const VkBufferMemoryBarrier buf_barrier = {
      .sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
      .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
      .dstAccessMask = VK_ACCESS_HOST_READ_BIT | VK_ACCESS_MEMORY_READ_BIT,
      .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
      .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
      .buffer = g->buffer[target],
      .offset = 0,
      .size = VK_WHOLE_SIZE,
   };
   wsi->CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT,
                           VK_PIPELINE_STAGE_HOST_BIT, 0,
                           0, NULL, 1, &buf_barrier, 1, &img_barrier);
   if (wsi->EndCommandBuffer(cb) != VK_SUCCESS) {
      fail = "command buffer end";
      goto out;
   }

   g->image_target[image_index] = (int8_t)target;
   g->cmd_used |= 1u << image_index;
   cmd = cb;
   reserved = false; /* the present thread owns the reservation now */

out:
   if (reserved)
      pons_gpu_slot_cancel(p, target);
   if (fail) {
      /* Stop using the GPU path; this and later frames use Mesa's host blit. */
      __atomic_store_n(&chain->pons_blit_cmd, NULL, __ATOMIC_RELEASE);
      g->failed = true;
      pons_gpu_log_unavailable(fail);
   }
   pthread_mutex_unlock(&g->lock);
   return cmd;
}

/* Present thread: the slot the GPU copy of this image went to, or -1 when the
 * frame took the CPU path. Clears the record. */
static int
pons_gpu_take_target(struct wsi_swapchain *chain, uint32_t image_index)
{
   struct pons_gpu *g = &pons_gpu_state;
   int target = -1;
   pthread_mutex_lock(&g->lock);
   if (g->chain == chain && image_index < PONS_GPU_MAX_IMAGES) {
      target = g->image_target[image_index];
      g->image_target[image_index] = -1;
   }
   pthread_mutex_unlock(&g->lock);
   return target;
}

static void
pons_gpu_free_buffers(struct wsi_swapchain *chain, VkBuffer *buffer,
                      VkDeviceMemory *memory)
{
   const struct wsi_device *wsi = chain->wsi;
   for (uint32_t i = 0; i < PONS_PRESENT_BUFFERS; i++) {
      if (buffer[i] != VK_NULL_HANDLE)
         wsi->DestroyBuffer(chain->device, buffer[i], &chain->alloc);
      if (memory[i] != VK_NULL_HANDLE)
         wsi->FreeMemory(chain->device, memory[i], &chain->alloc);
      buffer[i] = VK_NULL_HANDLE;
      memory[i] = VK_NULL_HANDLE;
   }
}

/* Imports the three dma-bufs (consuming the fds it imports). NULL on success,
 * else the reason; the caller frees whatever was created. */
static const char *
pons_gpu_import(struct wsi_swapchain *chain, struct pons_gpu_info *info,
                VkBuffer *buffer, VkDeviceMemory *memory)
{
   const struct wsi_device *wsi = chain->wsi;
   VK_FROM_HANDLE(vk_device, dev, chain->device);
   if (dev->dispatch_table.GetMemoryFdPropertiesKHR == NULL)
      return "no vkGetMemoryFdPropertiesKHR";

   const VkDeviceSize size = (VkDeviceSize)info->stride_bytes * info->height;
   for (uint32_t i = 0; i < PONS_PRESENT_BUFFERS; i++) {
      /* The dma-buf must hold the whole image. */
      const off_t fd_size = lseek(info->fd[i], 0, SEEK_END);
      if (fd_size >= 0 && (VkDeviceSize)fd_size < size)
         return "dma-buf smaller than the frame";

      VkMemoryFdPropertiesKHR fd_props = {
         .sType = VK_STRUCTURE_TYPE_MEMORY_FD_PROPERTIES_KHR,
      };
      if (dev->dispatch_table.GetMemoryFdPropertiesKHR(
             chain->device, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT,
             info->fd[i], &fd_props) != VK_SUCCESS)
         return "dma-buf not importable";

      const VkExternalMemoryBufferCreateInfo ext_info = {
         .sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO,
         .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT,
      };
      const VkBufferCreateInfo buf_info = {
         .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
         .pNext = &ext_info,
         .size = size,
         .usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT,
         .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
      };
      if (wsi->CreateBuffer(chain->device, &buf_info, &chain->alloc,
                            &buffer[i]) != VK_SUCCESS) {
         buffer[i] = VK_NULL_HANDLE;
         return "buffer create";
      }

      VkMemoryRequirements reqs;
      wsi->GetBufferMemoryRequirements(chain->device, buffer[i], &reqs);
      const uint32_t types = reqs.memoryTypeBits & fd_props.memoryTypeBits;
      if (types == 0)
         return "no memory type for the dma-buf";
      if (reqs.size < size)
         return "buffer requirements smaller than the frame";

      const VkImportMemoryFdInfoKHR import = {
         .sType = VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR,
         .handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT,
         .fd = info->fd[i],
      };
      const VkMemoryAllocateInfo alloc_info = {
         .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
         .pNext = &import,
         .allocationSize = reqs.size,
         .memoryTypeIndex = (uint32_t)(ffs((int)types) - 1),
      };
      if (wsi->AllocateMemory(chain->device, &alloc_info, &chain->alloc,
                              &memory[i]) != VK_SUCCESS) {
         memory[i] = VK_NULL_HANDLE;
         return "dma-buf import";
      }
      info->fd[i] = -1; /* Vulkan owns the fd now */

      if (wsi->BindBufferMemory(chain->device, buffer[i], memory[i], 0) != VK_SUCCESS)
         return "buffer bind";
   }
   return NULL;
}

/*
 * Present thread, after a CPU frame went out through the AHardwareBuffers:
 * the first time everything is known (fullscreen, version 2, same size),
 * imports the buffers and switches this swapchain to GPU copies.
 */
static void
pons_gpu_try_activate(struct wsi_swapchain *chain)
{
   struct pons_gpu *g = &pons_gpu_state;
   if (!pons_gpu_enabled())
      return;

   pthread_mutex_lock(&g->lock);
   const bool skip = g->failed || g->chain != NULL;
   pthread_mutex_unlock(&g->lock);
   if (skip)
      return; /* already active (maybe on another swapchain) or given up */

   struct pons_gpu_info info;
   const char *fail = NULL;
   VkBuffer buffer[PONS_PRESENT_BUFFERS] = { VK_NULL_HANDLE };
   VkDeviceMemory memory[PONS_PRESENT_BUFFERS] = { VK_NULL_HANDLE };

   if (!pons_gpu_info_get(&info)) {
      fail = "not in zero-upload (PONS_AHB) mode";
   } else if (!chain->wsi->sw) {
      fail = "not a software present";
   } else if (chain->blit.type != WSI_SWAPCHAIN_BUFFER_BLIT || chain->blit.queue != NULL) {
      fail = "swapchain does not use Mesa's buffer blit";
   } else if (chain->image_count > PONS_GPU_MAX_IMAGES) {
      fail = "too many swapchain images";
   } else if (vk_format_get_blocksize(chain->image_info.create.format) != 4 ||
              chain->image_info.create.extent.width != info.width ||
              chain->image_info.create.extent.height != info.height ||
              info.stride_bytes % 4 != 0 || info.stride_bytes < info.width * 4) {
      fail = "image format or size does not match the buffers";
   } else {
      fail = pons_gpu_import(chain, &info, buffer, memory);
   }
   for (uint32_t i = 0; i < PONS_PRESENT_BUFFERS; i++) {
      if (info.fd[i] >= 0)
         close(info.fd[i]);
   }

   if (fail == NULL) {
      pthread_mutex_lock(&g->lock);
      g->chain = chain;
      memcpy(g->buffer, buffer, sizeof(buffer));
      memcpy(g->memory, memory, sizeof(memory));
      g->stride_px = info.stride_bytes / 4;
      g->width = info.width;
      g->height = info.height;
      pthread_mutex_unlock(&g->lock);
      if (pons_gpu_enable(info.generation)) {
         __atomic_store_n(&chain->pons_blit_cmd, pons_gpu_blit_cmd, __ATOMIC_RELEASE);
         pthread_mutex_lock(&g->lock);
         if (!g->logged_active) {
            g->logged_active = true;
            fprintf(stderr, "pons: gpu copy active\n");
         }
         pthread_mutex_unlock(&g->lock);
         return;
      }
      /* The buffers changed while importing: undo, a later frame retries. */
      pthread_mutex_lock(&g->lock);
      g->chain = NULL;
      pthread_mutex_unlock(&g->lock);
      pons_gpu_free_buffers(chain, buffer, memory);
      return;
   }

   pons_gpu_free_buffers(chain, buffer, memory);
   pthread_mutex_lock(&g->lock);
   g->failed = true;
   pons_gpu_log_unavailable(fail);
   pthread_mutex_unlock(&g->lock);
}

/* Swapchain destruction: wait for our copies, free the imports. */
static void
pons_gpu_release(struct wsi_swapchain *chain)
{
   struct pons_gpu *g = &pons_gpu_state;
   const struct wsi_device *wsi = chain->wsi;

   pthread_mutex_lock(&g->lock);
   if (g->chain != chain) {
      pthread_mutex_unlock(&g->lock);
      return;
   }
   __atomic_store_n(&chain->pons_blit_cmd, NULL, __ATOMIC_RELEASE);
   pons_gpu_invalidate(&pons_present_state);

   for (uint32_t i = 0; i < PONS_GPU_MAX_IMAGES; i++) {
      if ((g->cmd_used & (1u << i)) && chain->fences && chain->fences[i] != VK_NULL_HANDLE)
         wsi->WaitForFences(chain->device, 1, &chain->fences[i], true, 1000000000ull);
   }
   if (g->pool != VK_NULL_HANDLE)
      wsi->DestroyCommandPool(chain->device, g->pool, &chain->alloc); /* frees its buffers */
   pons_gpu_free_buffers(chain, g->buffer, g->memory);

   g->pool = VK_NULL_HANDLE;
   g->cmd_used = 0;
   for (uint32_t i = 0; i < PONS_GPU_MAX_IMAGES; i++) {
      g->cmd[i] = VK_NULL_HANDLE;
      g->image_target[i] = -1;
   }
   g->chain = NULL;
   pthread_mutex_unlock(&g->lock);
}

#endif /* WSI_PONS_GPU_H */
