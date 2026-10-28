/*
 * Pons direct present.
 *
 * Instead of streaming every frame to the X server through its socket, software
 * presents write the frame into a triple-buffered file that the Pons Android
 * app maps and shows (PONS_PRESENT=<path>). The writer always fills a buffer
 * that is neither the published one nor the one the app is reading, then publishes
 * it, so the app never shows a half-written frame. PONS_MAX_FPS caps the rate
 * to spare power and heat for the game itself.
 *
 * File layout: struct pons_present_header, then `buffers` frames of
 * stride * height bytes (32-bit BGRX). Shared with the app's PresentBuffer.kt.
 *
 * Zero-upload mode (PONS_AHB=1): the app owns three CPU-writable Android
 * AHardwareBuffers and hands us their dma-buf fds over the abstract unix socket
 * "pons-ahb". The file then is version 2 and holds only the header; frames
 * are written straight into the buffers, so the app never uploads pixels. Any
 * failure falls back to the version 1 file.
 *
 * SPDX-License-Identifier: MIT
 */
#ifndef WSI_PONS_FB_H
#define WSI_PONS_FB_H

#include <errno.h>
#include <fcntl.h>
#include <linux/dma-buf.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stddef.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define PONS_PRESENT_MAGIC 0x53525050u /* "PPRS" little endian */
#define PONS_PRESENT_VERSION 1u
#define PONS_PRESENT_VERSION_AHB 2u
#define PONS_PRESENT_BUFFERS 3u
#define PONS_PRESENT_NONE 0xffffffffu

struct pons_present_header {
   uint32_t magic;
   uint32_t version;
   uint32_t width;
   uint32_t height;
   uint32_t stride;
   uint32_t buffers;
   _Atomic uint32_t front;   /* last complete frame, written by Mesa */
   _Atomic uint32_t reading; /* frame the app is uploading, or NONE */
   _Atomic uint64_t sequence;
   int32_t x;                /* window origin on the X screen */
   int32_t y;
   /* Frame pacing for benchmarks: frames published more than 50 / 100 ms after
    * the previous one. Averages hide these; the eye does not. */
   _Atomic uint32_t late_50ms;
   _Atomic uint32_t late_100ms;
   uint8_t reserved[8];
};

_Static_assert(sizeof(struct pons_present_header) == 64, "header is 64 bytes");

struct pons_present {
   pthread_mutex_t lock;
   struct pons_present_header *header;
   size_t map_size;
   /* Version 2 only: dma-buf fds and CPU mappings of the app's buffers. */
   int ahb_fd[PONS_PRESENT_BUFFERS];
   uint8_t *ahb_map[PONS_PRESENT_BUFFERS];
   size_t ahb_size;
   uint64_t frame_interval_ns;
   uint64_t last_frame_ns;
   uint64_t last_publish_ns;
};

static struct pons_present pons_present_state = {
   .lock = PTHREAD_MUTEX_INITIALIZER,
   .ahb_fd = { -1, -1, -1 },
};

static uint64_t
pons_now_ns(void)
{
   struct timespec ts;
   clock_gettime(CLOCK_MONOTONIC, &ts);
   return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

/* Unmaps and closes the app's buffers. Caller holds the lock. */
static void
pons_ahb_release(struct pons_present *p)
{
   for (uint32_t i = 0; i < PONS_PRESENT_BUFFERS; i++) {
      if (p->ahb_map[i])
         munmap(p->ahb_map[i], p->ahb_size);
      if (p->ahb_fd[i] >= 0)
         close(p->ahb_fd[i]);
      p->ahb_map[i] = NULL;
      p->ahb_fd[i] = -1;
   }
   p->ahb_size = 0;
}

/*
 * Asks the app for three width x height buffers and maps them. On success the
 * fds and mappings live in `p` and *stride_px is the row pitch in pixels (the
 * app's allocation may be wider than the image). On failure nothing is kept.
 */
static bool
pons_ahb_request(struct pons_present *p, uint32_t width, uint32_t height,
                      uint32_t *stride_px)
{
   int sock = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
   if (sock < 0)
      return false;

   /* Abstract name: sun_path[0] stays NUL, the length excludes the padding. */
   static const char name[] = "pons-ahb";
   struct sockaddr_un addr = { .sun_family = AF_UNIX };
   memcpy(addr.sun_path + 1, name, sizeof(name) - 1);
   const socklen_t alen = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + sizeof(name) - 1);
   /* A stuck app must not freeze the game's first present. */
   const struct timeval tv = { .tv_sec = 2 };
   setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
   setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
   if (connect(sock, (struct sockaddr *)&addr, alen) != 0) {
      close(sock);
      return false;
   }

   char req[64];
   const int rlen = snprintf(req, sizeof(req), "PPAHB1 %u %u\n", width, height);
   if (send(sock, req, (size_t)rlen, MSG_NOSIGNAL) != rlen) {
      close(sock);
      return false;
   }

   char text[64];
   union {
      char buf[CMSG_SPACE(sizeof(int) * PONS_PRESENT_BUFFERS)];
      struct cmsghdr align;
   } ctl;
   memset(&ctl, 0, sizeof(ctl));
   struct iovec iov = { .iov_base = text, .iov_len = sizeof(text) - 1 };
   struct msghdr msg = {
      .msg_iov = &iov, .msg_iovlen = 1,
      .msg_control = ctl.buf, .msg_controllen = sizeof(ctl.buf),
   };
   ssize_t got;
   do {
      got = recvmsg(sock, &msg, MSG_CMSG_CLOEXEC);
   } while (got < 0 && errno == EINTR);
   close(sock);

   int fds[PONS_PRESENT_BUFFERS] = { -1, -1, -1 };
   uint32_t nfds = 0;
   /* Collect every received fd first so a bad reply cannot leak any. */
   for (struct cmsghdr *c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
      if (c->cmsg_level != SOL_SOCKET || c->cmsg_type != SCM_RIGHTS)
         continue;
      const size_t n = (c->cmsg_len - CMSG_LEN(0)) / sizeof(int);
      for (size_t i = 0; i < n; i++) {
         int fd;
         memcpy(&fd, CMSG_DATA(c) + i * sizeof(int), sizeof(fd));
         if (nfds < PONS_PRESENT_BUFFERS)
            fds[nfds++] = fd;
         else
            close(fd);
      }
   }

   unsigned stride = 0;
   bool ok = got > 0 && !(msg.msg_flags & (MSG_CTRUNC | MSG_TRUNC)) &&
             nfds == PONS_PRESENT_BUFFERS;
   if (ok) {
      text[got] = '\0';
      ok = sscanf(text, "OK %u", &stride) == 1 && stride >= width &&
           stride <= (1u << 20);
   }

   size_t size = 0;
   if (ok) {
      size = (size_t)stride * 4 * height;
      for (uint32_t i = 0; i < nfds && ok; i++) {
         void *m = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fds[i], 0);
         if (m == MAP_FAILED) {
            ok = false;
         } else {
            p->ahb_map[i] = m;
            p->ahb_fd[i] = fds[i];
            fds[i] = -1;
         }
      }
   }
   for (uint32_t i = 0; i < nfds; i++) {
      if (fds[i] >= 0)
         close(fds[i]);
   }
   if (!ok) {
      p->ahb_size = size;
      pons_ahb_release(p);
      return false;
   }
   p->ahb_size = size;
   *stride_px = stride;
   return true;
}

/* Maps (or remaps after a resize) the present file. Caller holds the lock. */
static bool
pons_present_map(struct pons_present *p, uint32_t width, uint32_t height)
{
   uint32_t stride = width * 4;
   if (p->header && p->header->width == width && p->header->height == height)
      return true;

   const char *path = getenv("PONS_PRESENT");
   if (path == NULL || path[0] == '\0')
      return false;

   if (p->header) {
      munmap(p->header, p->map_size);
      p->header = NULL;
   }
   /* Old buffers go first: the app frees them when it sees the new size. */
   pons_ahb_release(p);

   uint32_t version = PONS_PRESENT_VERSION;
   const char *ahb = getenv("PONS_AHB");
   if (ahb && strcmp(ahb, "1") == 0) {
      uint32_t stride_px;
      if (pons_ahb_request(p, width, height, &stride_px)) {
         version = PONS_PRESENT_VERSION_AHB;
         stride = stride_px * 4;
      }
   }

   const size_t size = sizeof(struct pons_present_header) +
                       (version == PONS_PRESENT_VERSION_AHB ? 0 :
                        (size_t)PONS_PRESENT_BUFFERS * stride * height);
   /* Write a new file and rename it into place, so the app never maps a file
    * whose header and size disagree. */
   char tmp[4096];
   if ((size_t)snprintf(tmp, sizeof(tmp), "%s.%d", path, (int)getpid()) >= sizeof(tmp))
      return false;
   int fd = open(tmp, O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0666);
   if (fd < 0) {
      pons_ahb_release(p);
      return false;
   }
   if (ftruncate(fd, (off_t)size) != 0) {
      close(fd);
      unlink(tmp);
      pons_ahb_release(p);
      return false;
   }
   void *map = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
   close(fd);
   if (map == MAP_FAILED) {
      unlink(tmp);
      pons_ahb_release(p);
      return false;
   }

   struct pons_present_header *h = (struct pons_present_header *)map;
   h->width = width;
   h->height = height;
   h->stride = stride;
   h->buffers = PONS_PRESENT_BUFFERS;
   atomic_store(&h->front, PONS_PRESENT_NONE);
   atomic_store(&h->reading, PONS_PRESENT_NONE);
   atomic_store(&h->sequence, 0);
   h->version = version;
   atomic_thread_fence(memory_order_release);
   h->magic = PONS_PRESENT_MAGIC;

   if (rename(tmp, path) != 0) {
      munmap(map, size);
      unlink(tmp);
      pons_ahb_release(p);
      return false;
   }

   p->header = h;
   p->map_size = size;
   const char *fps = getenv("PONS_MAX_FPS");
   const long max_fps = fps ? strtol(fps, NULL, 10) : 0;
   p->frame_interval_ns = max_fps > 0 ? 1000000000ull / (uint64_t)max_fps : 0;
   return true;
}

/* Sleeps so frames are at least frame_interval_ns apart. */
static void
pons_present_pace(struct pons_present *p)
{
   if (p->frame_interval_ns == 0)
      return;
   const uint64_t now = pons_now_ns();
   const uint64_t due = p->last_frame_ns + p->frame_interval_ns;
   if (p->last_frame_ns != 0 && now < due) {
      const uint64_t wait = due - now;
      struct timespec ts = { (time_t)(wait / 1000000000ull), (long)(wait % 1000000000ull) };
      nanosleep(&ts, NULL);
      p->last_frame_ns = due;
   } else {
      p->last_frame_ns = now;
   }
}

/*
 * Publishes a 32-bit BGRX frame whose top-left corner sits at (x, y) on the X
 * screen. Returns false when direct present is unavailable, so the caller sends
 * the frame to the X server as usual.
 */
static bool
pons_fb_present(int32_t x, int32_t y, uint32_t width, uint32_t height,
                     const uint8_t *src, uint32_t src_stride)
{
   struct pons_present *p = &pons_present_state;
   if (src == NULL || width == 0 || height == 0)
      return false;

   pthread_mutex_lock(&p->lock);
   if (!pons_present_map(p, width, height)) {
      pthread_mutex_unlock(&p->lock);
      return false;
   }

   struct pons_present_header *h = p->header;
   const uint32_t front = atomic_load_explicit(&h->front, memory_order_relaxed);
   const uint32_t reading = atomic_load_explicit(&h->reading, memory_order_acquire);
   uint32_t target = 0;
   while (target == front || target == reading)
      target++;

   const bool ahb = h->version == PONS_PRESENT_VERSION_AHB;
   uint8_t *dst = ahb ? p->ahb_map[target]
                      : (uint8_t *)h + sizeof(*h) + (size_t)target * h->stride * h->height;
   /* The buffers are dma-bufs the GPU also touches: bracket CPU writes so the
    * kernel flushes caches. Fails harmlessly on anything that is not a dma-buf. */
   struct dma_buf_sync sync = { .flags = DMA_BUF_SYNC_START | DMA_BUF_SYNC_WRITE };
   if (ahb)
      ioctl(p->ahb_fd[target], DMA_BUF_IOCTL_SYNC, &sync);
   const size_t row_bytes = (size_t)width * 4;
   if (src_stride == h->stride) {
      memcpy(dst, src, row_bytes * height);
   } else {
      for (uint32_t row = 0; row < height; row++)
         memcpy(dst + (size_t)row * h->stride, src + (size_t)row * src_stride, row_bytes);
   }
   if (ahb) {
      sync.flags = DMA_BUF_SYNC_END | DMA_BUF_SYNC_WRITE;
      ioctl(p->ahb_fd[target], DMA_BUF_IOCTL_SYNC, &sync);
   }

   pons_present_pace(p);
   h->x = x;
   h->y = y;
   atomic_store_explicit(&h->front, target, memory_order_release);
   atomic_fetch_add_explicit(&h->sequence, 1, memory_order_release);
   const uint64_t published = pons_now_ns();
   if (p->last_publish_ns != 0) {
      const uint64_t gap = published - p->last_publish_ns;
      if (gap > 50000000ull)
         atomic_fetch_add_explicit(&h->late_50ms, 1, memory_order_relaxed);
      if (gap > 100000000ull)
         atomic_fetch_add_explicit(&h->late_100ms, 1, memory_order_relaxed);
   }
   p->last_publish_ns = published;
   pthread_mutex_unlock(&p->lock);
   return true;
}

#endif /* WSI_PONS_FB_H */
