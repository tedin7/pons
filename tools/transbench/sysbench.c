/*
 * sysbench: cost of one system call, in ns, as a program inside Pons sees it.
 * Built for arm64 (native) and x86_64 (through Box64), so the suite separates what
 * proot adds (path calls stop the process for translation) from what Box64 adds.
 *
 *   getpid     no path: proot's seccomp filter lets it through, the baseline
 *   stat       path lookup: proot stops the process and rewrites the path
 *   open_close path open plus a close
 *   read_4k    reading an open file: no path, data copy only
 *
 * A static arm64 copy also runs outside proot, for comparison.
 *
 * Output: "name ns_per_call" lines.
 */
#define _GNU_SOURCE
#include <fcntl.h>
#include <stdio.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

static double now_ns(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1e9 + ts.tv_nsec;
}

#define MEASURE(name, count, body)                                   \
    do {                                                             \
        double best = 1e30;                                          \
        for (int round = 0; round < 3; round++) {                    \
            double start = now_ns();                                 \
            for (int i = 0; i < (count); i++) { body; }              \
            double per = (now_ns() - start) / (count);               \
            if (per < best) best = per;                              \
        }                                                            \
        printf("%s %.0f\n", name, best);                             \
    } while (0)

int main(void)
{
    struct stat st;
    char buf[4096];
    const char *path = "/usr/bin";
    const char *file = "/etc/os-release";

    MEASURE("getpid", 200000, syscall(SYS_getpid));
    MEASURE("stat", 20000, stat(path, &st));
    MEASURE("open_close", 20000, { int fd = open(file, O_RDONLY); if (fd >= 0) close(fd); });
    int fd = open(file, O_RDONLY);
    if (fd < 0) { perror(file); return 1; }
    MEASURE("read_4k", 100000, pread(fd, buf, sizeof buf, 0));
    close(fd);
    return 0;
}
