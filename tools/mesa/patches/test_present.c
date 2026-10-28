/* Host test for wsi_pons_fb.h. Run: gcc -std=gnu11 -D_GNU_SOURCE -I. test_present.c -lpthread && ./a.out */
#include <assert.h>
#include <stdio.h>
#include "wsi_pons_fb.h"

int main(void) {
    setenv("PONS_PRESENT", "/tmp/pp-present-test", 1);
    uint8_t src[64 * 4 * 32];
    for (int frame = 1; frame <= 5; frame++) {
        memset(src, frame, sizeof src);
        assert(pons_fb_present(3, 4, 64, 32, src, 64 * 4));
        struct pons_present_header *h = pons_present_state.header;
        assert(h->magic == PONS_PRESENT_MAGIC && h->width == 64 && h->height == 32);
        assert(h->sequence == (uint64_t)frame && h->x == 3 && h->y == 4);
        uint8_t *buf = (uint8_t *)h + 64 + (size_t)h->front * h->stride * h->height;
        assert(buf[0] == frame && buf[64 * 4 * 32 - 1] == frame);
        h->reading = h->front; /* pretend the app reads it: next frame must avoid it */
    }
    /* resize remaps */
    assert(pons_fb_present(0, 0, 16, 8, src, 64 * 4));
    assert(pons_present_state.header->width == 16);
    puts("present test ok");
    return 0;
}
