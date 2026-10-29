/* idiombench: x86 instructions whose result does not depend on the register's
 * old value ("xor x,x" is 0, "pcmpeqd x,x" is all ones). x86 cores know this
 * and start the next loop iteration at once; a translator that maps them to an
 * arm64 instruction reading the register makes each iteration wait for the
 * previous one's long computation. Each test: the idiom, then a slow chain on
 * the same register, then a store (which nothing waits for).
 * x86-64 only. Output: "name ns_per_iteration". */
#include <stdio.h>
#include <time.h>

#define N 20000000

static double now_ns(void)
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1e9 + t.tv_nsec;
}

static volatile unsigned long long sink64[2];
static volatile double sinkd[2] __attribute__((aligned(16)));
static volatile float sinkf;
static volatile int sink32;

#define RUN(name, body, ...)                                         \
    do {                                                              \
        double t = now_ns();                                          \
        for (int i = 0; i < N; i++) __asm__ volatile(body __VA_ARGS__); \
        printf("%-12s %6.2f\n", name, (now_ns() - t) / N);            \
    } while (0)

int main(void)
{
    double three = 3.0;
    /* SSE zero: pxor, then sqrt chain. */
    RUN("pxor",
        "pxor %%xmm1, %%xmm1\n\taddsd %1, %%xmm1\n\t"
        "sqrtsd %%xmm1, %%xmm1\n\tsqrtsd %%xmm1, %%xmm1\n\tsqrtsd %%xmm1, %%xmm1\n\t"
        "movsd %%xmm1, %0",
        : "=m"(sinkd[0]) : "m"(three) : "xmm1");
    /* SSE all ones: pcmpeqd, then sqrt chain on the (NaN) value. */
    RUN("pcmpeqd",
        "pcmpeqd %%xmm1, %%xmm1\n\tpsrlq $2, %%xmm1\n\t"
        "sqrtpd %%xmm1, %%xmm1\n\tsqrtpd %%xmm1, %%xmm1\n\tsqrtpd %%xmm1, %%xmm1\n\t"
        "movapd %%xmm1, %0",
        : "=m"(sinkd) :: "xmm1");
    /* SSE zero by subtraction. */
    RUN("psubd",
        "psubd %%xmm1, %%xmm1\n\taddsd %1, %%xmm1\n\t"
        "sqrtsd %%xmm1, %%xmm1\n\tsqrtsd %%xmm1, %%xmm1\n\tsqrtsd %%xmm1, %%xmm1\n\t"
        "movsd %%xmm1, %0",
        : "=m"(sinkd[0]) : "m"(three) : "xmm1");
    /* Integer zero: xor, then a multiply/divide chain. */
    RUN("xor32",
        "xorl %%ecx, %%ecx\n\taddl $7, %%ecx\n\t"
        "imull %%ecx, %%ecx\n\timull %%ecx, %%ecx\n\timull %%ecx, %%ecx\n\timull %%ecx, %%ecx\n\t"
        "imull %%ecx, %%ecx\n\timull %%ecx, %%ecx\n\timull %%ecx, %%ecx\n\timull %%ecx, %%ecx\n\t"
        "movq %%rcx, %0",
        : "=m"(sink64[0]) :: "rcx", "cc");
    /* Conversions that honour the x86 rounding mode (MXCSR), as compilers emit
     * them for (float)i and lrint(d). A translator that switches the arm64
     * rounding mode around each one pays a pipeline drain every time. */
    RUN("cvtsi2ss",
        "pxor %%xmm1, %%xmm1\n\tcvtsi2ssl %%ecx, %%xmm1\n\taddl $3, %%ecx\n\t"
        "pxor %%xmm2, %%xmm2\n\tcvtsi2ssl %%ecx, %%xmm2\n\taddss %%xmm2, %%xmm1\n\t"
        "movss %%xmm1, %0",
        : "=m"(sinkf) :: "rcx", "xmm1", "xmm2", "cc");
    RUN("cvtsd2si",
        "cvtsd2si %1, %%eax\n\tcvtsd2si %1, %%edx\n\taddl %%edx, %%eax\n\tmovl %%eax, %0",
        : "=m"(sink32) : "x"(three) : "rax", "rdx");
    /* Reference: the same chain with nothing carried between iterations. */
    RUN("mov0",
        "movl $7, %%ecx\n\t"
        "imull %%ecx, %%ecx\n\timull %%ecx, %%ecx\n\timull %%ecx, %%ecx\n\timull %%ecx, %%ecx\n\t"
        "imull %%ecx, %%ecx\n\timull %%ecx, %%ecx\n\timull %%ecx, %%ecx\n\timull %%ecx, %%ecx\n\t"
        "movq %%rcx, %0",
        : "=m"(sink64[0]) :: "rcx", "cc");
    return 0;
}
