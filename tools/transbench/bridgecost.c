/* bridgecost: nanoseconds per call into the C library, to isolate Box64's
 * x86 -> native bridge cost. Built with -fno-builtin so calls are real calls.
 * Output: "name ns" lines (the stderr checksum keeps the loops from being removed). */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>

#define CALLS 20000000

static double now_ns(void)
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1e9 + t.tv_nsec;
}

int main(void)
{
    double acc = 0, t;
    double (*volatile indirect)(double) = fabs;

    t = now_ns();
    for (int i = 0; i < CALLS; i++) acc += fabs(i - 5.0);
    printf("fabs_direct %.2f ns\n", (now_ns() - t) / CALLS);

    t = now_ns();
    for (int i = 0; i < CALLS; i++) acc += indirect(i - 5.0);
    printf("fabs_pointer %.2f ns\n", (now_ns() - t) / CALLS);

    t = now_ns();
    for (int i = 0; i < CALLS; i++) acc += exp(-i * 1e-8);
    printf("exp_direct %.2f ns\n", (now_ns() - t) / CALLS);

    /* Each call waits for the previous one: shows whether calls can overlap. */
    double x = 0.5;
    t = now_ns();
    for (int i = 0; i < CALLS; i++) x = exp(x * -1e-3);
    printf("exp_chained %.2f ns\n", (now_ns() - t) / CALLS);
    acc += x;

    t = now_ns();
    for (int i = 0; i < CALLS; i++) acc += abs(i - 7);
    printf("abs_direct %.2f ns\n", (now_ns() - t) / CALLS);

    fprintf(stderr, "%f\n", acc);
    return 0;
}
