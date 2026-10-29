/*
 * bridgeparts: which part of Box64's native-call bridge stops calls from
 * overlapping. Calls exp() in a loop natively (arm64), adding one by one the
 * pieces Box64 wraps around each call from x86 code (see call_n in
 * dynarec_arm64_helper.c), and prints ns per call for each variant, for
 * independent calls (which the CPU can overlap) and chained ones (which it can't).
 *
 *   plain    the call alone
 *   push     + push/pop of the return address on an emulated x86 stack in memory
 *   regs     + RSP/RBP saved to and reloaded from the emulator struct
 *   xmm      + the argument and result going through the emulator struct (q0)
 *   all      everything above, like Box64
 *
 * Usage: bridgeparts. Output: "variant independent_ns chained_ns".
 */
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <time.h>

#define N 20000000

struct emu {
    uint64_t rsp, rbp;
    double xmm0[2];
};

static double now_ns(void)
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1e9 + t.tv_nsec;
}

static uint64_t stack_mem[64];
static volatile double sink;

/* One call wrapped in the selected pieces. 'sp' plays x86 RSP, kept in a
 * register across the loop like Box64 keeps it in x14. */
__attribute__((noinline)) static double call_wrapped(double x, int push, int regs, int xmm, struct emu *e, uint64_t **sp)
{
    if (push) {
        uint64_t *s = *sp;
        __asm__ volatile("str %1, [%0, #-8]!" : "+r"(s) : "r"((uint64_t)0x1234) : "memory");
        *sp = s;
    }
    if (regs) {
        e->rsp = (uint64_t)*sp;
        __asm__ volatile("" ::: "memory");
    }
    if (xmm) {
        e->xmm0[0] = x;
        __asm__ volatile("" ::: "memory");
        x = e->xmm0[0];
    }
    double r = exp(x);
    if (xmm) {
        e->xmm0[0] = r;
        __asm__ volatile("" ::: "memory");
        r = e->xmm0[0];
    }
    if (regs) {
        __asm__ volatile("" ::: "memory");
        *sp = (uint64_t *)e->rsp;
    }
    if (push) {
        uint64_t *s = *sp;
        uint64_t ret;
        __asm__ volatile("ldr %1, [%0], #8" : "+r"(s), "=r"(ret) :: "memory");
        *sp = s;
        sink = (double)ret;
    }
    return r;
}

static void run(const char *name, int push, int regs, int xmm)
{
    struct emu e = {0};
    uint64_t *sp = &stack_mem[32];
    double acc = 0, t = now_ns();
    for (int i = 0; i < N; i++)
        acc += call_wrapped(-i * 1e-8, push, regs, xmm, &e, &sp);
    double independent = (now_ns() - t) / N;
    double x = 0.5;
    t = now_ns();
    for (int i = 0; i < N; i++)
        x = call_wrapped(x * -1e-3, push, regs, xmm, &e, &sp);
    double chained = (now_ns() - t) / N;
    sink = acc + x;
    printf("%-6s %6.2f %6.2f\n", name, independent, chained);
}

int main(void)
{
    run("plain", 0, 0, 0);
    run("push", 1, 0, 0);
    run("regs", 0, 1, 0);
    run("xmm", 0, 0, 1);
    run("all", 1, 1, 1);
    return 0;
}
