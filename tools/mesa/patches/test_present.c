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
    /* GPU copy slot logic: reserve, never reuse front/reading/pending, publish. */
    struct pons_present *p = &pons_present_state;
    uint32_t t1, t2, t3;
    p->gpu_valid = true;
    p->header->front = PONS_PRESENT_NONE;
    p->header->reading = PONS_PRESENT_NONE;
    uint64_t seq = p->header->sequence;
    assert(pons_gpu_slot_acquire(p, &t1) && pons_gpu_slot_acquire(p, &t2) && t1 != t2);
    p->header->reading = 3 - t1 - t2; /* the app holds the third: nothing left */
    assert(!pons_gpu_slot_acquire(p, &t3));
    p->header->reading = PONS_PRESENT_NONE;
    /* CPU frames avoid pending slots */
    assert(pons_fb_present(0, 0, 16, 8, src, 64 * 4));
    assert(p->header->front == 3 - t1 - t2 && p->header->sequence == seq + 1);
    p->header->reading = p->header->front;
    assert(pons_fb_present(0, 0, 16, 8, src, 64 * 4) == true); /* no free slot: dropped */
    assert(p->header->sequence == seq + 1);
    p->header->reading = PONS_PRESENT_NONE;
    /* publishing t1 shows it and frees it as pending */
    assert(pons_fb_publish_target(1, 2, 16, 8, t1));
    assert(p->header->front == t1 && p->header->sequence == seq + 2 && p->header->x == 1);
    assert(!(p->pending & (1u << t1)) && (p->pending & (1u << t2)));
    assert(pons_fb_publish_target(0, 0, 99, 8, t2)); /* wrong size: dropped */
    assert(p->header->front == t1 && p->header->sequence == seq + 2);
    pons_gpu_slot_cancel(p, t2);
    assert(p->pending == 0);
    /* remap invalidates the GPU path */
    assert(pons_fb_present(0, 0, 32, 8, src, 64 * 4));
    assert(!p->gpu_valid && !pons_gpu_slot_acquire(p, &t3));
    puts("present test ok");
    return 0;
}
