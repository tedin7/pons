/*
 * pinguplay-fastpath: proot's path translation, done inside the program.
 *
 * proot stops a program at every system call that carries a path, rewrites the
 * path and lets it go on: about 40-190 us per call on Android (cross-core
 * wakeups), against 0.3 us without proot. Games open and stat tens of thousands
 * of files while loading, so that stop dominates loading time and heat.
 *
 * This LD_PRELOAD library resolves guest paths itself (rootfs prefix, the /tmp
 * and /dev/shm binds, symlinks resolved inside the guest like proot does) and
 * issues the call with PROOT_FASTPATH_MAGIC in the 6th argument register, which
 * those calls do not use. Our proot patch (tools/proot/0003-fastpath-marker.patch)
 * lets marked calls through without a stop.
 *
 * Anything unusual falls back to the normal libc call, so proot still handles
 * it: /proc, /dev and /sys, symlink loops, very long paths, paths relative to a
 * directory we did not open. Covered: open/openat, stat family, access, readlink,
 * realpath, statfs/statvfs, fopen, opendir; writes that change the tree go
 * through proot as before.
 *
 * Enabled by PINGUPLAY_FASTPATH_ROOT=<host rootfs> and
 * PINGUPLAY_FASTPATH_BINDS=<guest>=<host>[:<guest>=<host>...]; otherwise every
 * function behaves exactly like libc.
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/statvfs.h>
#include <sys/syscall.h>
#include <unistd.h>

#define PROOT_FASTPATH_MAGIC 0x594c50554755494eULL /* "PINGUPLY", same as proot */
#define MAX_BINDS 8
#define MAX_SYMLINKS 40

struct bind {
    char guest[PATH_MAX];
    size_t guest_len;
    char host[PATH_MAX];
};

static int enabled;
static char root[PATH_MAX];
static size_t root_len;
static struct bind binds[MAX_BINDS];
static int nbinds;
static uid_t host_uid = (uid_t)-1;
static gid_t host_gid = (gid_t)-1;
/* Descriptors we opened: proot traces close() too, so we close those ourselves.
 * We also keep their guest path, so calls relative to a directory we opened
 * (openat(dirfd, "name")) need no proot stop either. */
#define MAX_FAST_FD 65536
static unsigned char fast_fds[MAX_FAST_FD / 8];
static char *fd_paths[MAX_FAST_FD];
static __thread char cwd_cache[PATH_MAX];
static __thread int cwd_valid;

/* ---- marked system calls: proot lets these through without a stop ---- */

static long fast_openat(const char *host, int flags, mode_t mode)
{
    return syscall(SYS_openat, AT_FDCWD, host, flags, mode, 0, PROOT_FASTPATH_MAGIC);
}

static long fast_fstatat(const char *host, struct stat *st, int flags)
{
    return syscall(SYS_newfstatat, AT_FDCWD, host, st, flags, 0, PROOT_FASTPATH_MAGIC);
}

static long fast_readlink(const char *host, char *buf, size_t size)
{
    return syscall(SYS_readlinkat, AT_FDCWD, host, buf, size, 0, PROOT_FASTPATH_MAGIC);
}

static long fast_faccessat(const char *host, int mode, int flags)
{
    return syscall(SYS_faccessat2, AT_FDCWD, host, mode, flags, 0, PROOT_FASTPATH_MAGIC);
}

static long fast_fstat(int fd, struct stat *st)
{
    return syscall(SYS_fstat, fd, st, 0, 0, 0, PROOT_FASTPATH_MAGIC);
}

static void set_fd_path(long fd, const char *guest)
{
    char *copy = guest ? strdup(guest) : NULL;
    free(__atomic_exchange_n(&fd_paths[fd], copy, __ATOMIC_ACQ_REL));
}

static void remember_fd(long fd, const char *guest)
{
    if (fd < 0 || fd >= MAX_FAST_FD)
        return;
    __atomic_fetch_or(&fast_fds[fd / 8], (unsigned char)(1u << (fd % 8)), __ATOMIC_RELAXED);
    set_fd_path(fd, guest);
}

/* True (and forgotten) when @fd came from the fast path. */
static int forget_fd(int fd)
{
    if (fd < 0 || fd >= MAX_FAST_FD)
        return 0;
    set_fd_path(fd, NULL);
    unsigned char bit = (unsigned char)(1u << (fd % 8));
    return __atomic_fetch_and(&fast_fds[fd / 8], (unsigned char)~bit, __ATOMIC_RELAXED) & bit;
}

/* proot -0 reports files owned by the app's user as owned by root. */
static void fake_root(struct stat *st)
{
    if (st->st_uid == host_uid)
        st->st_uid = 0;
    if (st->st_gid == host_gid)
        st->st_gid = 0;
}

/* ---- setup ---- */

__attribute__((constructor)) static void fastpath_init(void)
{
    const char *r = getenv("PINGUPLAY_FASTPATH_ROOT");
    const char *b = getenv("PINGUPLAY_FASTPATH_BINDS");
    if (!r || r[0] != '/' || strlen(r) >= sizeof root)
        return;
    strcpy(root, r);
    root_len = strlen(root);
    while (root_len > 1 && root[root_len - 1] == '/')
        root[--root_len] = '\0';

    for (const char *p = b; p && *p && nbinds < MAX_BINDS;) {
        const char *end = strchr(p, ':');
        size_t len = end ? (size_t)(end - p) : strlen(p);
        const char *eq = memchr(p, '=', len);
        if (eq && eq > p && p[0] == '/' && (size_t)(eq - p) < PATH_MAX && len - (eq - p) - 1 < PATH_MAX) {
            struct bind *bd = &binds[nbinds++];
            memcpy(bd->guest, p, eq - p);
            bd->guest[eq - p] = '\0';
            bd->guest_len = eq - p;
            memcpy(bd->host, eq + 1, len - (eq - p) - 1);
            bd->host[len - (eq - p) - 1] = '\0';
        }
        p = end ? end + 1 : NULL;
    }

    /* The rootfs belongs to the app's user: that is whom proot -0 hides. */
    struct stat st;
    if (fast_fstatat(root, &st, 0) != 0)
        return;
    host_uid = st.st_uid;
    host_gid = st.st_gid;
    enabled = 1;
}

/* ---- guest -> host path resolution ---- */

/* Paths proot must keep handling: kernel views whose symlinks need its rewriting. */
static int is_special(const char *guest)
{
    static const char *const special[] = { "/proc", "/dev", "/sys" };
    for (size_t i = 0; i < sizeof special / sizeof special[0]; i++) {
        size_t n = strlen(special[i]);
        if (strncmp(guest, special[i], n) == 0 && (guest[n] == '\0' || guest[n] == '/'))
            return 1;
    }
    return 0;
}

/* Maps a canonical guest path to the host. Returns 0, or -1 when it should go to proot. */
static int map_to_host(const char *guest, char *host)
{
    const struct bind *best = NULL;
    for (int i = 0; i < nbinds; i++) {
        const struct bind *bd = &binds[i];
        if (strncmp(guest, bd->guest, bd->guest_len) == 0
            && (guest[bd->guest_len] == '\0' || guest[bd->guest_len] == '/')
            && (!best || bd->guest_len > best->guest_len))
            best = bd;
    }
    if (best) {
        if (snprintf(host, PATH_MAX, "%s%s", best->host, guest + best->guest_len) >= PATH_MAX)
            return -1;
        return 0;
    }
    if (is_special(guest))
        return -1;
    if (snprintf(host, PATH_MAX, "%s%s", root, guest) >= PATH_MAX)
        return -1;
    return 0;
}

static int guest_cwd(char *out)
{
    if (!cwd_valid) {
        /* getcwd goes through proot, which returns the guest path. */
        if (!getcwd(cwd_cache, sizeof cwd_cache) || cwd_cache[0] != '/')
            return -1;
        cwd_valid = 1;
    }
    strcpy(out, cwd_cache);
    return 0;
}

static int map_to_host(const char *guest, char *host);

/*
 * Guest path of directory @dirfd if we opened it and it is still the same file
 * (the number may have been reused for something else since). Returns 0 or -1.
 */
static int dir_path(int dirfd, char *out)
{
    if (dirfd < 0 || dirfd >= MAX_FAST_FD)
        return -1;
    char *known = __atomic_exchange_n(&fd_paths[dirfd], NULL, __ATOMIC_ACQ_REL);
    if (!known)
        return -1;
    int ok = strlen(known) < PATH_MAX;
    if (ok)
        strcpy(out, known);
    char *other = NULL;
    if (!__atomic_compare_exchange_n(&fd_paths[dirfd], &other, known, 0, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE))
        free(known); /* replaced meanwhile: the newer entry wins */
    if (!ok)
        return -1;
    char host[PATH_MAX];
    struct stat by_fd, by_path;
    if (map_to_host(out, host) < 0 || fast_fstat(dirfd, &by_fd) != 0
        || fast_fstatat(host, &by_path, 0) != 0)
        return -1;
    return (by_fd.st_dev == by_path.st_dev && by_fd.st_ino == by_path.st_ino
            && S_ISDIR(by_fd.st_mode)) ? 0 : -1;
}

/*
 * Resolves @path like the kernel would inside the guest, relative to @dirfd when
 * it is not absolute, following symlinks (the last one too when @follow_last).
 * Writes the host path to @host and, when @guest is not NULL, the canonical guest
 * path. Returns 0, or -1 to let proot handle the call.
 */
static int resolve_at(int dirfd, const char *path, int follow_last, char *host, char *guest)
{
    char todo[PATH_MAX * 2];   /* components still to walk, '/'-separated */
    char done[PATH_MAX];       /* canonical guest path walked so far */
    int links = 0;

    if (!enabled || !path || !path[0])
        return -1;
    if (strlen(path) >= PATH_MAX)
        return -1;
    if (path[0] == '/') {
        strcpy(todo, path);
    } else {
        char base[PATH_MAX];
        if (dirfd == AT_FDCWD ? guest_cwd(base) < 0 : dir_path(dirfd, base) < 0)
            return -1;
        if (snprintf(todo, sizeof todo, "%s/%s", base, path) >= (int)sizeof todo)
            return -1;
    }
    done[0] = '\0';

    char *p = todo;
    while (*p) {
        while (*p == '/')
            p++;
        if (!*p)
            break;
        char *slash = strchr(p, '/');
        size_t len = slash ? (size_t)(slash - p) : strlen(p);
        int last = !slash || slash[strspn(slash, "/")] == '\0';

        if (len == 1 && p[0] == '.') {
            p += len;
            continue;
        }
        if (len == 2 && p[0] == '.' && p[1] == '.') {
            char *cut = strrchr(done, '/');
            if (cut)
                *cut = '\0';
            p += len;
            continue;
        }
        size_t dlen = strlen(done);
        if (dlen + 1 + len >= PATH_MAX)
            return -1;
        done[dlen] = '/';
        memcpy(done + dlen + 1, p, len);
        done[dlen + 1 + len] = '\0';
        p += len;

        if (is_special(done))
            return -1;
        if (last && !follow_last)
            break;

        char h[PATH_MAX];
        struct stat st;
        if (map_to_host(done, h) < 0)
            return -1;
        if (fast_fstatat(h, &st, AT_SYMLINK_NOFOLLOW) != 0) {
            if (errno == ENOENT || errno == ENOTDIR)
                break; /* the kernel reports the same error on the full path */
            return -1;
        }
        if (!S_ISLNK(st.st_mode))
            continue;

        if (++links > MAX_SYMLINKS)
            return -1;
        char target[PATH_MAX];
        long n = fast_readlink(h, target, sizeof target - 1);
        if (n <= 0)
            return -1;
        target[n] = '\0';
        /* link2symlink stores host paths into the rootfs: take them back to the guest. */
        if (strncmp(target, root, root_len) == 0 && target[root_len] == '/')
            memmove(target, target + root_len, strlen(target + root_len) + 1);

        char rest[PATH_MAX * 2];
        if (snprintf(rest, sizeof rest, "%s", p) >= (int)sizeof rest)
            return -1;
        if (target[0] == '/') {
            done[0] = '\0';
        } else {
            char *cut = strrchr(done, '/');
            if (cut)
                *cut = '\0';
        }
        if (snprintf(todo, sizeof todo, "%s/%s", target, rest) >= (int)sizeof todo)
            return -1;
        p = todo;
    }
    if (!done[0])
        strcpy(done, "/");
    /* Keep the rest of the path as given once a component is missing. */
    if (*p) {
        size_t dlen = strlen(done);
        if (dlen + 1 + strlen(p) >= PATH_MAX)
            return -1;
        if (done[dlen - 1] != '/')
            done[dlen++] = '/';
        strcpy(done + dlen, p + strspn(p, "/"));
    }
    if (guest)
        strcpy(guest, done);
    return map_to_host(done, host);
}

static int resolve(const char *path, int follow_last, char *host)
{
    return resolve_at(AT_FDCWD, path, follow_last, host, NULL);
}

/* ---- the libc functions ---- */

#define REAL(name) \
    static __typeof__(name) *real_##name; \
    if (!real_##name) real_##name = (__typeof__(name) *)dlsym(RTLD_NEXT, #name)

/* Calls that move the guest working directory make the cached one stale. */
int chdir(const char *path)
{
    REAL(chdir);
    cwd_valid = 0;
    return real_chdir(path);
}

int fchdir(int fd)
{
    REAL(fchdir);
    cwd_valid = 0;
    return real_fchdir(fd);
}

static int open_common(int dirfd, const char *path, int flags, mode_t mode, int *handled)
{
    char host[PATH_MAX], guest[PATH_MAX];
    *handled = 0;
    if (resolve_at(dirfd, path, !(flags & O_NOFOLLOW), host, guest) < 0)
        return -1;
    *handled = 1;
    long fd = fast_openat(host, flags, mode);
    remember_fd(fd, guest);
    return (int)fd;
}

static mode_t mode_arg(int flags, va_list ap)
{
    return (flags & (O_CREAT | O_TMPFILE)) ? (mode_t)va_arg(ap, int) : 0;
}

#define DEFINE_OPEN(name)                                                 \
    int name(const char *path, int flags, ...)                            \
    {                                                                     \
        va_list ap;                                                       \
        va_start(ap, flags);                                              \
        mode_t mode = mode_arg(flags, ap);                                \
        va_end(ap);                                                       \
        int handled, fd = open_common(AT_FDCWD, path, flags, mode, &handled); \
        if (handled)                                                      \
            return fd;                                                    \
        REAL(name);                                                       \
        return real_##name(path, flags, mode);                            \
    }
DEFINE_OPEN(open)
DEFINE_OPEN(open64)

#define DEFINE_OPENAT(name)                                               \
    int name(int dirfd, const char *path, int flags, ...)                 \
    {                                                                     \
        va_list ap;                                                       \
        va_start(ap, flags);                                              \
        mode_t mode = mode_arg(flags, ap);                                \
        va_end(ap);                                                       \
        int handled, fd = open_common(dirfd, path, flags, mode, &handled); \
        if (handled)                                                      \
            return fd;                                                    \
        REAL(name);                                                       \
        return real_##name(dirfd, path, flags, mode);                     \
    }
DEFINE_OPENAT(openat)
DEFINE_OPENAT(openat64)

/* Fortified variants (_FORTIFY_SOURCE builds call these). */
int close(int fd)
{
    if (forget_fd(fd))
        return (int)syscall(SYS_close, fd, 0, 0, 0, 0, PROOT_FASTPATH_MAGIC);
    REAL(close);
    return real_close(fd);
}

int __open_2(const char *path, int flags) { return open(path, flags); }
int __open64_2(const char *path, int flags) { return open64(path, flags); }
int __openat_2(int dirfd, const char *path, int flags) { return openat(dirfd, path, flags); }
int __openat64_2(int dirfd, const char *path, int flags) { return openat64(dirfd, path, flags); }

static int stat_common(int dirfd, const char *path, struct stat *st, int flags, int *handled)
{
    char host[PATH_MAX];
    *handled = 0;
    if (flags & AT_EMPTY_PATH)
        return -1;
    if (resolve_at(dirfd, path, !(flags & AT_SYMLINK_NOFOLLOW), host, NULL) < 0)
        return -1;
    *handled = 1;
    int r = (int)fast_fstatat(host, st, flags & (AT_NO_AUTOMOUNT | AT_SYMLINK_NOFOLLOW));
    if (r == 0)
        fake_root(st);
    return r;
}

#define DEFINE_STAT(name, nofollow)                                       \
    int name(const char *path, struct stat *st)                           \
    {                                                                     \
        int handled, r = stat_common(AT_FDCWD, path, st, nofollow, &handled); \
        if (handled)                                                      \
            return r;                                                     \
        REAL(name);                                                       \
        return real_##name(path, st);                                     \
    }
DEFINE_STAT(stat, 0)
DEFINE_STAT(lstat, AT_SYMLINK_NOFOLLOW)

int stat64(const char *path, struct stat64 *st) { return stat(path, (struct stat *)st); }
int lstat64(const char *path, struct stat64 *st) { return lstat(path, (struct stat *)st); }

int fstatat(int dirfd, const char *path, struct stat *st, int flags)
{
    int handled, r = stat_common(dirfd, path, st, flags, &handled);
    if (handled)
        return r;
    REAL(fstatat);
    return real_fstatat(dirfd, path, st, flags);
}

int fstatat64(int dirfd, const char *path, struct stat64 *st, int flags)
{
    return fstatat(dirfd, path, (struct stat *)st, flags);
}

/* Old glibc ABI, still called by programs built before glibc 2.33 (and Box64's wrappers). */
int __xstat(int ver, const char *path, struct stat *st) { (void)ver; return stat(path, st); }
int __xstat64(int ver, const char *path, struct stat64 *st) { (void)ver; return stat64(path, st); }
int __lxstat(int ver, const char *path, struct stat *st) { (void)ver; return lstat(path, st); }
int __lxstat64(int ver, const char *path, struct stat64 *st) { (void)ver; return lstat64(path, st); }
int __fxstatat(int ver, int dirfd, const char *path, struct stat *st, int flags)
{
    (void)ver;
    return fstatat(dirfd, path, st, flags);
}
int __fxstatat64(int ver, int dirfd, const char *path, struct stat64 *st, int flags)
{
    (void)ver;
    return fstatat64(dirfd, path, st, flags);
}

int access(const char *path, int mode)
{
    char host[PATH_MAX];
    if (resolve(path, 1, host) == 0)
        return (int)fast_faccessat(host, mode, 0);
    REAL(access);
    return real_access(path, mode);
}

int faccessat(int dirfd, const char *path, int mode, int flags)
{
    char host[PATH_MAX];
    if (!(flags & AT_EMPTY_PATH)
        && resolve_at(dirfd, path, !(flags & AT_SYMLINK_NOFOLLOW), host, NULL) == 0)
        return (int)fast_faccessat(host, mode, flags);
    REAL(faccessat);
    return real_faccessat(dirfd, path, mode, flags);
}

static ssize_t readlink_common(int dirfd, const char *path, char *buf, size_t size, int *handled)
{
    char host[PATH_MAX];
    *handled = 0;
    if (resolve_at(dirfd, path, 0, host, NULL) == 0) {
        *handled = 1;
        char tmp[PATH_MAX];
        long n = fast_readlink(host, tmp, sizeof tmp - 1);
        if (n < 0)
            return -1;
        tmp[n] = '\0';
        const char *out = tmp;
        if (strncmp(tmp, root, root_len) == 0 && tmp[root_len] == '/')
            out = tmp + root_len;
        size_t len = strlen(out);
        if (len > size)
            len = size;
        memcpy(buf, out, len);
        return (ssize_t)len;
    }
    return -1;
}

ssize_t readlink(const char *path, char *buf, size_t size)
{
    int handled;
    ssize_t n = readlink_common(AT_FDCWD, path, buf, size, &handled);
    if (handled)
        return n;
    REAL(readlink);
    return real_readlink(path, buf, size);
}

ssize_t readlinkat(int dirfd, const char *path, char *buf, size_t size)
{
    int handled;
    ssize_t n = readlink_common(dirfd, path, buf, size, &handled);
    if (handled)
        return n;
    REAL(readlinkat);
    return real_readlinkat(dirfd, path, buf, size);
}

/* fopen/opendir open the file through libc internals proot would trap: open it here. */
static int fopen_flags(const char *mode)
{
    int flags;
    switch (mode[0]) {
    case 'r': flags = 0; break;
    case 'w': flags = O_CREAT | O_TRUNC; break;
    case 'a': flags = O_CREAT | O_APPEND; break;
    default: return -1;
    }
    int plus = strchr(mode, '+') != NULL;
    flags |= plus ? O_RDWR : (mode[0] == 'r' ? O_RDONLY : O_WRONLY);
    if (strchr(mode, 'e'))
        flags |= O_CLOEXEC;
    if (strchr(mode, 'x'))
        flags |= O_EXCL;
    return flags;
}

#define DEFINE_FOPEN(name)                                                \
    FILE *name(const char *path, const char *mode)                        \
    {                                                                     \
        int flags = mode ? fopen_flags(mode) : -1;                        \
        char host[PATH_MAX];                                              \
        if (flags >= 0 && resolve(path, 1, host) == 0) {                  \
            int fd = (int)fast_openat(host, flags, 0666);                 \
            if (fd < 0)                                                   \
                return NULL;                                              \
            FILE *f = fdopen(fd, mode);                                   \
            if (!f)                                                       \
                close(fd);                                                \
            return f;                                                     \
        }                                                                 \
        REAL(name);                                                       \
        return real_##name(path, mode);                                   \
    }
DEFINE_FOPEN(fopen)
DEFINE_FOPEN(fopen64)

DIR *opendir(const char *path)
{
    char host[PATH_MAX], guest[PATH_MAX];
    if (resolve_at(AT_FDCWD, path, 1, host, guest) == 0) {
        int fd = (int)fast_openat(host, O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0);
        if (fd < 0)
            return NULL;
        remember_fd(fd, guest);
        DIR *d = fdopendir(fd);
        if (!d)
            close(fd);
        return d;
    }
    REAL(opendir);
    return real_opendir(path);
}

/* closedir closes through libc internals: forget the descriptor first. */
int closedir(DIR *d)
{
    REAL(closedir);
    forget_fd(dirfd(d));
    return real_closedir(d);
}

/* realpath walks the path with lstat/readlink, one proot stop each: resolve it here. */
char *realpath(const char *path, char *resolved)
{
    char host[PATH_MAX], guest[PATH_MAX];
    struct stat st;
    if (path && resolve_at(AT_FDCWD, path, 1, host, guest) == 0) {
        if (fast_fstatat(host, &st, 0) != 0)
            return NULL; /* errno from the kernel, like realpath */
        if (!resolved)
            return strdup(guest);
        strcpy(resolved, guest);
        return resolved;
    }
    REAL(realpath);
    return real_realpath(path, resolved);
}

char *canonicalize_file_name(const char *path) { return realpath(path, NULL); }

char *__realpath_chk(const char *path, char *resolved, size_t size)
{
    (void)size;
    return realpath(path, resolved);
}

int statfs(const char *path, struct statfs *buf)
{
    char host[PATH_MAX];
    if (resolve(path, 1, host) == 0)
        return (int)syscall(SYS_statfs, host, buf, 0, 0, 0, PROOT_FASTPATH_MAGIC);
    REAL(statfs);
    return real_statfs(path, buf);
}

int statfs64(const char *path, struct statfs64 *buf) { return statfs(path, (struct statfs *)buf); }

int statvfs(const char *path, struct statvfs *buf)
{
    char host[PATH_MAX];
    if (resolve(path, 1, host) == 0) {
        int fd = (int)fast_openat(host, O_PATH | O_CLOEXEC, 0);
        if (fd < 0)
            return -1;
        int r = fstatvfs(fd, buf);
        int saved = errno;
        syscall(SYS_close, fd, 0, 0, 0, 0, PROOT_FASTPATH_MAGIC);
        errno = saved;
        return r;
    }
    REAL(statvfs);
    return real_statvfs(path, buf);
}

int statvfs64(const char *path, struct statvfs64 *buf) { return statvfs(path, (struct statvfs *)buf); }

int euidaccess(const char *path, int mode) { return faccessat(AT_FDCWD, path, mode, AT_EACCESS); }
int eaccess(const char *path, int mode) { return faccessat(AT_FDCWD, path, mode, AT_EACCESS); }
