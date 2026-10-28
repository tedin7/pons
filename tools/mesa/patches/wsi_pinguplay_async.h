/*
 * Pinguplay asynchronous software present.
 *
 * On X servers without DRI3 (Xvfb), Mesa's software present path waits in the
 * application's vkQueuePresentKHR for the GPU to finish the frame before handing
 * it to the X11 queue thread. The CPU and the GPU then take turns instead of
 * overlapping. With PINGUPLAY_PRESENT set (and PINGUPLAY_PRESENT_ASYNC not "0"),
 * the wait moves to the X11 queue thread, right before it reads the frame.
 *
 * SPDX-License-Identifier: MIT
 */
#ifndef WSI_PINGUPLAY_ASYNC_H
#define WSI_PINGUPLAY_ASYNC_H

#include <stdbool.h>
#include <stdlib.h>
#include <string.h>

static inline bool
pinguplay_async_present(void)
{
   const char *present = getenv("PINGUPLAY_PRESENT");
   const char *async = getenv("PINGUPLAY_PRESENT_ASYNC");
   return present && present[0] && !(async && strcmp(async, "0") == 0);
}

#endif /* WSI_PINGUPLAY_ASYNC_H */
