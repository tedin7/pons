/*
 * Host test for the zero-upload (PONS_AHB) path of wsi_pons_fb.h.
 * A forked fake server on the abstract socket "pons-ahb" stands in for the app
 * and hands out memfds instead of dma-bufs (DMA_BUF_IOCTL_SYNC fails on them, which
 * must be harmless). Run:
 *   gcc -std=gnu11 -D_GNU_SOURCE -Wall -Wextra -O2 -I. test_ahb.c -o t -lpthread && ./t
 */
#include <assert.h>
#include <stdio.h>
#include <signal.h>
#include <sys/wait.h>
#include "wsi_pons_fb.h"

#define PAD_PX 8 /* the fake app pads rows, like a real AHardwareBuffer */

static pid_t start_server(int *ready_fd, bool reply_no)
{
   int ls = socket(AF_UNIX, SOCK_STREAM, 0);
   struct sockaddr_un a = { .sun_family = AF_UNIX };
   static const char name[] = "pons-ahb";
   memcpy(a.sun_path + 1, name, sizeof(name) - 1);
   assert(bind(ls, (struct sockaddr *)&a, offsetof(struct sockaddr_un, sun_path) + sizeof(name)) == 0);
   assert(listen(ls, 4) == 0);
   (void)ready_fd;
   pid_t pid = fork();
   if (pid) { close(ls); return pid; }
   for (;;) {
      int c = accept(ls, NULL, NULL);
      if (c < 0) _exit(0);
      char line[64] = { 0 };
      unsigned w, h;
      if (recv(c, line, sizeof(line) - 1, 0) <= 0 || sscanf(line, "PPAHB1 %u %u", &w, &h) != 2) {
         close(c);
         continue;
      }
      if (reply_no) {
         send(c, "NO\n", 3, 0);
         close(c);
         continue;
      }
      unsigned stride = w + PAD_PX;
      int fds[3];
      for (int i = 0; i < 3; i++) {
         fds[i] = memfd_create("fake-ahb", 0);
         assert(ftruncate(fds[i], (off_t)stride * 4 * h) == 0);
      }
      char txt[32];
      int n = snprintf(txt, sizeof(txt), "OK %u\n", stride);
      char ctl[CMSG_SPACE(sizeof(fds))] = { 0 };
      struct iovec iov = { txt, (size_t)n };
      struct msghdr m = { .msg_iov = &iov, .msg_iovlen = 1, .msg_control = ctl, .msg_controllen = sizeof(ctl) };
      struct cmsghdr *cm = CMSG_FIRSTHDR(&m);
      cm->cmsg_level = SOL_SOCKET;
      cm->cmsg_type = SCM_RIGHTS;
      cm->cmsg_len = CMSG_LEN(sizeof(fds));
      memcpy(CMSG_DATA(cm), fds, sizeof(fds));
      sendmsg(c, &m, 0);
      for (int i = 0; i < 3; i++) close(fds[i]);
      close(c);
   }
}

int main(void)
{
   const char *path = "/tmp/pp-ahb-test";
   unlink(path);
   setenv("PONS_PRESENT", path, 1);
   setenv("PONS_AHB", "1", 1);
   struct pons_present *p = &pons_present_state;

   /* 1. No server: falls back to version 1 with the pixel area in the file. */
   uint8_t src[64 * 4 * 32];
   memset(src, 7, sizeof src);
   assert(pons_fb_present(0, 0, 64, 32, src, 64 * 4));
   assert(p->header->version == 1 && p->ahb_map[0] == NULL && p->ahb_fd[0] == -1);
   assert(p->map_size == 64 + 3 * 64 * 4 * 32);
   puts("fallback v1 ok");

   /* 2. Server up: version 2, header-only file, pixels in the memfds. */
   int unused;
   pid_t srv = start_server(&unused, false);
   assert(pons_fb_present(0, 0, 16, 8, src, 16 * 4)); /* resize forces a remap */
   struct pons_present_header *h = p->header;
   assert(h->version == 2 && h->buffers == 3 && h->width == 16 && h->height == 8);
   assert(h->stride == (16 + PAD_PX) * 4 && p->map_size == 64);
   struct stat st;
   assert(stat(path, &st) == 0 && st.st_size == 64);
   for (int frame = 1; frame <= 5; frame++) {
      uint8_t f[16 * 4 * 8];
      for (size_t i = 0; i < sizeof f; i++) f[i] = (uint8_t)(frame * 10 + (i % 7));
      assert(pons_fb_present(2, 3, 16, 8, f, 16 * 4));
      assert(h->sequence == (uint64_t)frame + 1 && h->x == 2 && h->y == 3);
      uint32_t fr = h->front;
      assert(fr < 3);
      for (uint32_t row = 0; row < 8; row++)
         assert(memcmp(p->ahb_map[fr] + (size_t)row * h->stride, f + row * 16 * 4, 16 * 4) == 0);
      h->reading = fr; /* the next frame must avoid the one being read */
   }
   uint32_t before = h->front;
   uint8_t g[16 * 4 * 8] = { 0 };
   assert(pons_fb_present(0, 0, 16, 8, g, 16 * 4));
   assert(h->front != before && h->front != h->reading);
   puts("ahb v2 ok");

   /* 3. Resize remaps with fresh buffers and a new stride. */
   assert(pons_fb_present(0, 0, 32, 4, src, 64 * 4));
   h = p->header;
   assert(h->version == 2 && h->width == 32 && h->stride == (32 + PAD_PX) * 4 && h->sequence == 1);
   puts("ahb resize ok");

   kill(srv, SIGKILL);
   waitpid(srv, NULL, 0);

   /* 4. Server answers NO: fall back to version 1. */
   srv = start_server(&unused, true);
   assert(pons_fb_present(0, 0, 20, 4, src, 64 * 4));
   assert(p->header->version == 1 && p->ahb_map[0] == NULL);
   kill(srv, SIGKILL);
   waitpid(srv, NULL, 0);
   unlink(path);
   puts("all ahb tests ok");
   return 0;
}
