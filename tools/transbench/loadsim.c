/*
 * loadsim: what a game does while it loads, without the game.
 *
 *   scan   walk a directory tree with opendir/readdir and stat every entry
 *          (asset discovery)
 *   read   open, read (up to 64 KiB) and close every regular file found
 *   parse  tokenize the bytes on N threads, with some math per token
 *          (script and data parsing; engines call libm a lot here)
 *
 * Built for x86_64 (through Box64) and arm64 (native), so the profiler can tell
 * which layer slows each phase down: proot (file calls), Box64 (translation and
 * calls into native libraries) or the device itself.
 *
 * Usage: loadsim [dir] [max files] [threads]   Output: "phase ms count" lines.
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <fcntl.h>
#include <math.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define READ_MAX (64 * 1024)

struct file {
    char *path;
    size_t size;
    unsigned char *data;
};

static struct file *files;
static size_t nfiles, cap_files, max_files;
static size_t next_job;
static pthread_mutex_t job_lock = PTHREAD_MUTEX_INITIALIZER;
static uint64_t tokens_total;
static double math_total;

static double now_ms(void)
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1e3 + t.tv_nsec / 1e6;
}

static size_t stats;

static void scan(const char *dir)
{
    DIR *d = opendir(dir);
    if (!d)
        return;
    struct dirent *e;
    while ((e = readdir(d)) && nfiles < max_files) {
        if (e->d_name[0] == '.')
            continue;
        char path[4096];
        if (snprintf(path, sizeof path, "%s/%s", dir, e->d_name) >= (int)sizeof path)
            continue;
        struct stat st;
        stats++;
        if (lstat(path, &st) != 0)
            continue;
        if (S_ISDIR(st.st_mode)) {
            scan(path);
        } else if (S_ISREG(st.st_mode)) {
            if (nfiles == cap_files) {
                cap_files = cap_files ? cap_files * 2 : 1024;
                files = realloc(files, cap_files * sizeof *files);
            }
            files[nfiles++] = (struct file){ strdup(path), (size_t)st.st_size, NULL };
        }
    }
    closedir(d);
}

static size_t read_all(void)
{
    size_t bytes = 0;
    for (size_t i = 0; i < nfiles; i++) {
        int fd = open(files[i].path, O_RDONLY | O_CLOEXEC);
        if (fd < 0)
            continue;
        size_t want = files[i].size < READ_MAX ? files[i].size : READ_MAX;
        files[i].data = malloc(want + 1);
        ssize_t n = read(fd, files[i].data, want);
        close(fd);
        files[i].size = n > 0 ? (size_t)n : 0;
        bytes += files[i].size;
    }
    return bytes;
}

/* Tokens are runs of letters/digits; numeric tokens go through some math. */
static void *parse_worker(void *arg)
{
    (void)arg;
    uint64_t tokens = 0;
    double acc = 0;
    for (;;) {
        pthread_mutex_lock(&job_lock);
        size_t i = next_job++;
        pthread_mutex_unlock(&job_lock);
        if (i >= nfiles)
            break;
        const unsigned char *p = files[i].data;
        size_t n = files[i].size;
        if (!p)
            continue;
        size_t len = 0;
        uint32_t hash = 2166136261u;
        for (size_t k = 0; k <= n; k++) {
            int c = k < n ? p[k] : ' ';
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_') {
                hash = (hash ^ (uint32_t)c) * 16777619u;
                len++;
            } else if (len) {
                tokens++;
                if (hash % 4 == 0)
                    acc += sqrt((double)(hash & 0xffff)) * exp(-(double)len * 0.01) + sin(hash * 1e-6);
                len = 0;
                hash = 2166136261u;
            }
        }
    }
    pthread_mutex_lock(&job_lock);
    tokens_total += tokens;
    math_total += acc;
    pthread_mutex_unlock(&job_lock);
    return NULL;
}

int main(int argc, char **argv)
{
    const char *dir = argc > 1 ? argv[1] : "/usr/share";
    max_files = argc > 2 ? strtoul(argv[2], NULL, 10) : 20000;
    int threads = argc > 3 ? atoi(argv[3]) : 4;
    if (threads < 1 || threads > 64)
        threads = 4;

    double t = now_ms();
    scan(dir);
    printf("scan %.1f %zu\n", now_ms() - t, stats);

    t = now_ms();
    size_t bytes = read_all();
    printf("read %.1f %zu\n", now_ms() - t, bytes);

    t = now_ms();
    pthread_t tid[64];
    for (int i = 0; i < threads; i++)
        pthread_create(&tid[i], NULL, parse_worker, NULL);
    for (int i = 0; i < threads; i++)
        pthread_join(tid[i], NULL);
    printf("parse %.1f %llu\n", now_ms() - t, (unsigned long long)tokens_total);
    fprintf(stderr, "files %zu checksum %.3f\n", nfiles, math_total);
    return 0;
}
