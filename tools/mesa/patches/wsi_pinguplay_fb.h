/*
 * Pinguplay direct present.
 *
 * Xvfb started with -fbdir keeps its screen in a file (XWD format) that the
 * Pinguplay Android app maps and shows. When PINGUPLAY_XVFB_FB points at that
 * file, software presents write their rows straight into it instead of
 * streaming them over the X socket. That saves a full-frame copy, the socket
 * transfer and the X server's CPU time on every frame.
 *
 * SPDX-License-Identifier: MIT
 */
#ifndef WSI_PINGUPLAY_FB_H
#define WSI_PINGUPLAY_FB_H

#include <arpa/inet.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#define PINGUPLAY_XWD_HEADER_FIELDS 25
#define PINGUPLAY_XWD_COLOR_SIZE 12

struct pinguplay_fb {
   uint8_t *pixels;
   int32_t width;
   int32_t height;
   uint32_t stride;
};

static struct pinguplay_fb pinguplay_fb_state;
static pthread_once_t pinguplay_fb_once = PTHREAD_ONCE_INIT;

static void
pinguplay_fb_open(void)
{
   const char *path = getenv("PINGUPLAY_XVFB_FB");
   if (path == NULL || path[0] == '\0')
      return;

   int fd = open(path, O_RDWR | O_CLOEXEC);
   if (fd < 0)
      return;

   struct stat st;
   if (fstat(fd, &st) != 0 || st.st_size < PINGUPLAY_XWD_HEADER_FIELDS * 4) {
      close(fd);
      return;
   }

   uint8_t *map = (uint8_t *)mmap(NULL, st.st_size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
   close(fd);
   if (map == MAP_FAILED)
      return;

   uint32_t header[PINGUPLAY_XWD_HEADER_FIELDS];
   memcpy(header, map, sizeof(header));
   for (unsigned i = 0; i < PINGUPLAY_XWD_HEADER_FIELDS; i++)
      header[i] = ntohl(header[i]);

   const uint32_t header_size = header[0];
   const uint32_t width = header[4];
   const uint32_t height = header[5];
   const uint32_t bits_per_pixel = header[11];
   const uint32_t stride = header[12];
   const uint32_t colors = header[19];
   const uint64_t offset = header_size + (uint64_t)colors * PINGUPLAY_XWD_COLOR_SIZE;

   if (bits_per_pixel != 32 || width == 0 || height == 0 || stride < width * 4 ||
       offset + (uint64_t)stride * height > (uint64_t)st.st_size) {
      munmap(map, st.st_size);
      return;
   }

   pinguplay_fb_state.pixels = map + offset;
   pinguplay_fb_state.width = (int32_t)width;
   pinguplay_fb_state.height = (int32_t)height;
   pinguplay_fb_state.stride = stride;
}

/*
 * Copies a 32-bit BGRX image whose top-left corner sits at (x, y) on the
 * screen, clipped to the screen. Returns false when direct present is not
 * available, so the caller sends the image to the X server as usual.
 */
static bool
pinguplay_fb_present(int32_t x, int32_t y, uint32_t width, uint32_t height,
                     const uint8_t *src, uint32_t src_stride)
{
   pthread_once(&pinguplay_fb_once, pinguplay_fb_open);
   const struct pinguplay_fb *fb = &pinguplay_fb_state;
   if (fb->pixels == NULL || src == NULL)
      return false;

   const int32_t x0 = x > 0 ? x : 0;
   const int32_t y0 = y > 0 ? y : 0;
   const int32_t x1 = x + (int32_t)width < fb->width ? x + (int32_t)width : fb->width;
   const int32_t y1 = y + (int32_t)height < fb->height ? y + (int32_t)height : fb->height;
   if (x1 <= x0 || y1 <= y0)
      return true;

   const size_t row_bytes = (size_t)(x1 - x0) * 4;
   for (int32_t row = y0; row < y1; row++) {
      memcpy(fb->pixels + (size_t)row * fb->stride + (size_t)x0 * 4,
             src + (size_t)(row - y) * src_stride + (size_t)(x0 - x) * 4,
             row_bytes);
   }
   return true;
}

#endif /* WSI_PINGUPLAY_FB_H */
