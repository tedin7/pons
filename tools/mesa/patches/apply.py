#!/usr/bin/env python3
"""Applies Pons's Mesa changes to a Mesa source tree: apply.py <mesa-src>."""
import pathlib
import shutil
import sys

src = pathlib.Path(sys.argv[1])
here = pathlib.Path(__file__).resolve().parent
wsi = src / "src/vulkan/wsi"
shutil.copy(here / "wsi_pons_fb.h", wsi / "wsi_pons_fb.h")
shutil.copy(here / "wsi_pons_async.h", wsi / "wsi_pons_async.h")
shutil.copy(here / "wsi_pons_gpu.h", wsi / "wsi_pons_gpu.h")

x11 = wsi / "wsi_common_x11.c"
code = x11.read_text()


def replace_once(old: str, new: str) -> None:
    global code
    if code.count(old) != 1:
        sys.exit(f"apply.py: expected exactly one match for:\n{old}")
    code = code.replace(old, new)


replace_once('#include "wsi_common_queue.h"\n',
             '#include "wsi_common_queue.h"\n#include "wsi_pons_fb.h"\n#include "wsi_pons_async.h"\n#include "wsi_pons_gpu.h"\n')

replace_once("""   bool                                         has_mit_shm;
   bool                                         has_async_may_tear;""",
             """   bool                                         has_mit_shm;
   bool                                         has_async_may_tear;
   /* Pons direct present: window origin on the root window, looked up once. */
   bool                                         pons_origin_known;
   bool                                         pons_fullscreen;
   int16_t                                      pons_x;
   int16_t                                      pons_y;""")

replace_once("""/**
 * Send image to X server unaccelerated (software drivers).
 */
static VkResult
x11_present_to_x11_sw(""", """/**
 * Pons: write the frame straight into Xvfb's framebuffer file.
 * Returns false when not available, so the frame goes through the socket.
 */
static bool
x11_present_pons(struct x11_swapchain *chain, uint32_t image_index)
{
   if (!chain->pons_origin_known) {
      const xcb_setup_t *setup = xcb_get_setup(chain->conn);
      xcb_screen_t *screen = xcb_setup_roots_iterator(setup).data;
      if (screen == NULL)
         return false;
      /* Only full-screen windows (games) bypass the X server; dialogs and
       * launchers stay ordinary windows on the desktop the app also shows. */
      chain->pons_fullscreen =
         chain->extent.width == screen->width_in_pixels &&
         chain->extent.height == screen->height_in_pixels;
      xcb_translate_coordinates_reply_t *origin = xcb_translate_coordinates_reply(
         chain->conn, xcb_translate_coordinates(chain->conn, chain->window, screen->root, 0, 0),
         NULL);
      if (origin == NULL)
         return false;
      chain->pons_x = origin->dst_x;
      chain->pons_y = origin->dst_y;
      chain->pons_origin_known = true;
      free(origin);
   }
   if (!chain->pons_fullscreen || chain->pons_x != 0 || chain->pons_y != 0)
      return false;
   /* GPU copy (PONS_GPU_COPY=1): the frame already sits in the app's buffer. */
   const int gpu_target = pons_gpu_take_target(&chain->base, image_index);
   if (gpu_target >= 0) {
      return pons_fb_publish_target(chain->pons_x, chain->pons_y,
                                    chain->extent.width, chain->extent.height,
                                    (uint32_t)gpu_target);
   }
   const struct x11_image *image = &chain->images[image_index];
   const bool ok = pons_fb_present(chain->pons_x, chain->pons_y,
                                   chain->extent.width, chain->extent.height,
                                   (const uint8_t *)image->base.cpu_map,
                                   image->base.row_pitches[0]);
   if (ok)
      pons_gpu_try_activate(&chain->base);
   return ok;
}

/**
 * Send image to X server unaccelerated (software drivers).
 */
static VkResult
x11_present_to_x11_sw(""")

replace_once("""   uint64_t max_req_len = xcb_get_maximum_request_length(chain->conn);

   if (image->rectangle_count > 0) {""", """   uint64_t max_req_len = xcb_get_maximum_request_length(chain->conn);

   if (x11_present_pons(chain, image_index)) {
      /* Written straight into the screen the app shows. */
   } else if (image->rectangle_count > 0) {""")

replace_once("""   assert(!chain->base.image_info.explicit_sync);
   struct x11_image *image = &chain->images[image_index];
""", """   assert(!chain->base.image_info.explicit_sync);
   struct x11_image *image = &chain->images[image_index];

   /* Pons async present: the application thread no longer waited for the
    * GPU, so wait here, in the X11 queue thread, before reading the frame. */
   if (pons_async_present()) {
      chain->base.wsi->WaitForFences(chain->base.device, 1,
                                     &chain->base.fences[image_index], true, UINT64_MAX);
   }
""")

replace_once("""   thrd_join(chain->event_manager, NULL);

   if (!chain->base.image_info.explicit_sync)
      wsi_queue_destroy(&chain->acquire_queue);""", """   thrd_join(chain->event_manager, NULL);

   /* Pons GPU copy: wait for our copies and free the dma-buf imports. */
   pons_gpu_release(&chain->base);

   if (!chain->base.image_info.explicit_sync)
      wsi_queue_destroy(&chain->acquire_queue);""")

x11.write_text(code)

priv = wsi / "wsi_common_private.h"
code = priv.read_text()
replace_once("""   struct wsi_image_info image_info;
   uint32_t image_count;
""", """   struct wsi_image_info image_info;
   uint32_t image_count;

   /* Pons GPU copy: when set, returns the command buffer that copies the image
    * into the app's buffer instead of Mesa's host blit, or NULL for this frame. */
   VkCommandBuffer (*pons_blit_cmd)(struct wsi_swapchain *chain,
                                    uint32_t image_index, uint32_t queue_family);
""")
priv.write_text(code)

common = wsi / "wsi_common.c"
code = common.read_text()
replace_once('#include "wsi_common_private.h"\n',
             '#include "wsi_common_private.h"\n#include "wsi_pons_async.h"\n')
replace_once("""      if (wsi->sw) {
         wsi->WaitForFences(vk_device_to_handle(dev),
                            1, &swapchain->fences[image_index], true, ~0ull);
      }""", """      /* Pons: with async present the X11 queue thread waits instead. */
      if (wsi->sw && !pons_async_present()) {
         wsi->WaitForFences(vk_device_to_handle(dev),
                            1, &swapchain->fences[image_index], true, ~0ull);
      }""")
common.write_text(code)
replace_once("""         if (swapchain->blit.type != WSI_SWAPCHAIN_NO_BLIT) {
            command_buffer_infos[command_buffer_count++] = (VkCommandBufferSubmitInfo) {
               .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO,
               .commandBuffer =
                  image->blit.cmd_buffers[queue->queue_family_index],
            };
         }""", """         if (swapchain->blit.type != WSI_SWAPCHAIN_NO_BLIT) {
            /* Pons GPU copy: a copy straight into the app's buffer replaces
             * the host blit for this frame. */
            VkCommandBuffer (*pons_cmd)(struct wsi_swapchain *, uint32_t, uint32_t) =
               __atomic_load_n(&swapchain->pons_blit_cmd, __ATOMIC_ACQUIRE);
            VkCommandBuffer pons_cb = pons_cmd ?
               pons_cmd(swapchain, image_index, queue->queue_family_index) :
               VK_NULL_HANDLE;
            command_buffer_infos[command_buffer_count++] = (VkCommandBufferSubmitInfo) {
               .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO,
               .commandBuffer = pons_cb != VK_NULL_HANDLE ? pons_cb :
                  image->blit.cmd_buffers[queue->queue_family_index],
            };
         }""")
common.write_text(code)
print("pons patches applied")
