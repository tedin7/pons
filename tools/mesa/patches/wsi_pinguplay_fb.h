/*
 * Pinguplay direct present.
 *
 * Instead of streaming every frame to the X server through its socket, software
 * presents write the frame into a triple-buffered file that the Pinguplay Android
 * app maps and shows (PINGUPLAY_PRESENT=<path>). The writer always fills a buffer
 * that is neither the published one nor the one the app is reading, then publishes
 * it, so the app never shows a half-written frame. PINGUPLAY_MAX_FPS caps the rate
 * to spare power and heat for the game itself.
 *
 * File layout: struct pinguplay_present_header, then `buffers` frames of
 * stride * height bytes (32-bit BGRX). Shared with the app's PresentBuffer.kt.
 *
 * SPDX-License-Identifier: MIT
 */
#ifndef WSI_PINGUPLAY_FB_H
#define WSI_PINGUPLAY_FB_H

#include <fcntl.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define PINGUPLAY_PRESENT_MAGIC 0x53525050u /* "PPRS" little endian */
#define PINGUPLAY_PRESENT_VERSION 1u
#define PINGUPLAY_PRESENT_BUFFERS 3u
#define PINGUPLAY_PRESENT_NONE 0xffffffffu

struct pinguplay_present_header {
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
   uint8_t reserved[16];
};

_Static_assert(sizeof(struct pinguplay_present_header) == 64, "header is 64 bytes");

struct pinguplay_present {
   pthread_mutex_t lock;
   struct pinguplay_present_header *header;
   size_t map_size;
   uint64_t frame_interval_ns;
   uint64_t last_frame_ns;
};

static struct pinguplay_present pinguplay_present_state = {
   .lock = PTHREAD_MUTEX_INITIALIZER,
};

static uint64_t
pinguplay_now_ns(void)
{
   struct timespec ts;
   clock_gettime(CLOCK_MONOTONIC, &ts);
   return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

/* Maps (or remaps after a resize) the present file. Caller holds the lock. */
static bool
pinguplay_present_map(struct pinguplay_present *p, uint32_t width, uint32_t height)
{
   const uint32_t stride = width * 4;
   if (p->header && p->header->width == width && p->header->height == height)
      return true;

   const char *path = getenv("PINGUPLAY_PRESENT");
   if (path == NULL || path[0] == '\0')
      return false;

   if (p->header) {
      munmap(p->header, p->map_size);
      p->header = NULL;
   }

   const size_t size = sizeof(struct pinguplay_present_header) +
                       (size_t)PINGUPLAY_PRESENT_BUFFERS * stride * height;
   /* Write a new file and rename it into place, so the app never maps a file
    * whose header and size disagree. */
   char tmp[4096];
   if ((size_t)snprintf(tmp, sizeof(tmp), "%s.%d", path, (int)getpid()) >= sizeof(tmp))
      return false;
   int fd = open(tmp, O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0666);
   if (fd < 0)
      return false;
   if (ftruncate(fd, (off_t)size) != 0) {
      close(fd);
      unlink(tmp);
      return false;
   }
   void *map = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
   close(fd);
   if (map == MAP_FAILED) {
      unlink(tmp);
      return false;
   }

   struct pinguplay_present_header *h = (struct pinguplay_present_header *)map;
   h->width = width;
   h->height = height;
   h->stride = stride;
   h->buffers = PINGUPLAY_PRESENT_BUFFERS;
   atomic_store(&h->front, PINGUPLAY_PRESENT_NONE);
   atomic_store(&h->reading, PINGUPLAY_PRESENT_NONE);
   atomic_store(&h->sequence, 0);
   h->version = PINGUPLAY_PRESENT_VERSION;
   atomic_thread_fence(memory_order_release);
   h->magic = PINGUPLAY_PRESENT_MAGIC;

   if (rename(tmp, path) != 0) {
      munmap(map, size);
      unlink(tmp);
      return false;
   }

   p->header = h;
   p->map_size = size;
   const char *fps = getenv("PINGUPLAY_MAX_FPS");
   const long max_fps = fps ? strtol(fps, NULL, 10) : 0;
   p->frame_interval_ns = max_fps > 0 ? 1000000000ull / (uint64_t)max_fps : 0;
   return true;
}

/* Sleeps so frames are at least frame_interval_ns apart. */
static void
pinguplay_present_pace(struct pinguplay_present *p)
{
   if (p->frame_interval_ns == 0)
      return;
   const uint64_t now = pinguplay_now_ns();
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
pinguplay_fb_present(int32_t x, int32_t y, uint32_t width, uint32_t height,
                     const uint8_t *src, uint32_t src_stride)
{
   struct pinguplay_present *p = &pinguplay_present_state;
   if (src == NULL || width == 0 || height == 0)
      return false;

   pthread_mutex_lock(&p->lock);
   if (!pinguplay_present_map(p, width, height)) {
      pthread_mutex_unlock(&p->lock);
      return false;
   }

   struct pinguplay_present_header *h = p->header;
   const uint32_t front = atomic_load_explicit(&h->front, memory_order_relaxed);
   const uint32_t reading = atomic_load_explicit(&h->reading, memory_order_acquire);
   uint32_t target = 0;
   while (target == front || target == reading)
      target++;

   uint8_t *dst = (uint8_t *)h + sizeof(*h) + (size_t)target * h->stride * h->height;
   const size_t row_bytes = (size_t)width * 4;
   if (src_stride == h->stride) {
      memcpy(dst, src, row_bytes * height);
   } else {
      for (uint32_t row = 0; row < height; row++)
         memcpy(dst + (size_t)row * h->stride, src + (size_t)row * src_stride, row_bytes);
   }

   pinguplay_present_pace(p);
   h->x = x;
   h->y = y;
   atomic_store_explicit(&h->front, target, memory_order_release);
   atomic_fetch_add_explicit(&h->sequence, 1, memory_order_release);
   pthread_mutex_unlock(&p->lock);
   return true;
}

#endif /* WSI_PINGUPLAY_FB_H */
