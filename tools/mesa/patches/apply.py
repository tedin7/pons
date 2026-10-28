#!/usr/bin/env python3
"""Applies Pinguplay's Mesa changes to a Mesa source tree: apply.py <mesa-src>."""
import pathlib
import shutil
import sys

src = pathlib.Path(sys.argv[1])
here = pathlib.Path(__file__).resolve().parent
wsi = src / "src/vulkan/wsi"
shutil.copy(here / "wsi_pinguplay_fb.h", wsi / "wsi_pinguplay_fb.h")

x11 = wsi / "wsi_common_x11.c"
code = x11.read_text()


def replace_once(old: str, new: str) -> None:
    global code
    if code.count(old) != 1:
        sys.exit(f"apply.py: expected exactly one match for:\n{old}")
    code = code.replace(old, new)


replace_once('#include "wsi_common_queue.h"\n',
             '#include "wsi_common_queue.h"\n#include "wsi_pinguplay_fb.h"\n')

replace_once("""   bool                                         has_mit_shm;
   bool                                         has_async_may_tear;""",
             """   bool                                         has_mit_shm;
   bool                                         has_async_may_tear;
   /* Pinguplay direct present: window origin on the root window, looked up once. */
   bool                                         pinguplay_origin_known;
   int16_t                                      pinguplay_x;
   int16_t                                      pinguplay_y;""")

replace_once("""/**
 * Send image to X server unaccelerated (software drivers).
 */
static VkResult
x11_present_to_x11_sw(""", """/**
 * Pinguplay: write the frame straight into Xvfb's framebuffer file.
 * Returns false when not available, so the frame goes through the socket.
 */
static bool
x11_present_pinguplay(struct x11_swapchain *chain, struct x11_image *image)
{
   if (!chain->pinguplay_origin_known) {
      const xcb_setup_t *setup = xcb_get_setup(chain->conn);
      xcb_screen_t *screen = xcb_setup_roots_iterator(setup).data;
      if (screen == NULL)
         return false;
      xcb_translate_coordinates_reply_t *origin = xcb_translate_coordinates_reply(
         chain->conn, xcb_translate_coordinates(chain->conn, chain->window, screen->root, 0, 0),
         NULL);
      if (origin == NULL)
         return false;
      chain->pinguplay_x = origin->dst_x;
      chain->pinguplay_y = origin->dst_y;
      chain->pinguplay_origin_known = true;
      free(origin);
   }
   return pinguplay_fb_present(chain->pinguplay_x, chain->pinguplay_y,
                               chain->extent.width, chain->extent.height,
                               (const uint8_t *)image->base.cpu_map,
                               image->base.row_pitches[0]);
}

/**
 * Send image to X server unaccelerated (software drivers).
 */
static VkResult
x11_present_to_x11_sw(""")

replace_once("""   uint64_t max_req_len = xcb_get_maximum_request_length(chain->conn);

   if (image->rectangle_count > 0) {""", """   uint64_t max_req_len = xcb_get_maximum_request_length(chain->conn);

   if (x11_present_pinguplay(chain, image)) {
      /* Written straight into the screen the app shows. */
   } else if (image->rectangle_count > 0) {""")

x11.write_text(code)
print("pinguplay patches applied")
