/*
 * fastpath-check: prints what common file calls return for a set of guest paths.
 * Run once plainly and once with libpons-fastpath.so preloaded, under the
 * same proot: the two outputs must be identical (tools/dev/pp-fastpath-check.sh).
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <unistd.h>

static void show_stat(const char *what, const char *path, int r, const struct stat *st)
{
    if (r == 0)
        printf("%s %s ok mode=%o uid=%u gid=%u size=%lld ino=%llu\n", what, path, st->st_mode,
               st->st_uid, st->st_gid, (long long)st->st_size, (unsigned long long)st->st_ino);
    else
        printf("%s %s err=%d\n", what, path, errno);
}

int main(void)
{
    static const char *const paths[] = {
        "/", "/usr/bin/awk", "/usr/bin/../lib", "/etc/alternatives/awk", "/bin/sh",
        "/lib", "/tmp", "/root", "/nonexistent", "/usr/nonexistent/x", "/etc/os-release/x",
        "/proc/self/exe", "/dev/null", "/usr//share///doc/.", "../../../../etc/hostname",
    };
    char buf[PATH_MAX];
    struct stat st;
    if (chdir("/usr/share/doc") != 0)
        perror("chdir");
    for (size_t i = 0; i < sizeof paths / sizeof paths[0]; i++) {
        const char *p = paths[i];
        int r = stat(p, &st);
        show_stat("stat", p, r, &st);
        r = lstat(p, &st);
        show_stat("lstat", p, r, &st);
        printf("access %s %d\n", p, access(p, R_OK) == 0 ? 0 : errno);
        char *rp = realpath(p, buf);
        printf("realpath %s %s\n", p, rp ? rp : strerror(errno));
        ssize_t n = readlink(p, buf, sizeof buf - 1);
        if (n >= 0) buf[n] = '\0';
        printf("readlink %s %s\n", p, n >= 0 ? buf : strerror(errno));
        int fd = open(p, O_RDONLY);
        printf("open %s %s\n", p, fd >= 0 ? "ok" : strerror(errno));
        if (fd >= 0) close(fd);
    }
    /* Relative to a directory descriptor. */
    int dir = open("/usr", O_RDONLY | O_DIRECTORY);
    int r = fstatat(dir, "bin/awk", &st, 0);
    show_stat("fstatat-dirfd", "bin/awk", r, &st);
    r = fstatat(dir, "bin/awk", &st, AT_SYMLINK_NOFOLLOW);
    show_stat("fstatat-dirfd-nofollow", "bin/awk", r, &st);
    ssize_t n = readlinkat(dir, "bin/awk", buf, sizeof buf - 1);
    if (n >= 0) buf[n] = '\0';
    printf("readlinkat bin/awk %s\n", n >= 0 ? buf : strerror(errno));
    int f = openat(dir, "share/../lib", O_RDONLY | O_DIRECTORY);
    printf("openat-dirfd share/../lib %s\n", f >= 0 ? "ok" : strerror(errno));
    if (f >= 0) close(f);
    close(dir);
    /* Directory listing count and filesystem info. */
    DIR *d = opendir("/etc");
    int count = 0;
    if (d) { while (readdir(d)) count++; closedir(d); }
    printf("opendir /etc %d\n", count);
    struct statvfs vfs;
    printf("statvfs / %s bsize=%lu\n", statvfs("/", &vfs) == 0 ? "ok" : strerror(errno), vfs.f_bsize);
    FILE *fp = fopen("/etc/os-release", "r");
    printf("fopen %s first=%s", fp ? "ok" : strerror(errno), fp && fgets(buf, sizeof buf, fp) ? buf : "\n");
    if (fp) fclose(fp);
    return 0;
}
