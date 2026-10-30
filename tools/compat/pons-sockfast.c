/*
 * Socket messages without proot stops, linked into libpons-robust.so (Steam)
 * and libpons-fastpath.so (games).
 *
 * proot stops every sendto/recvfrom/recvmsg because a few of them belong to its
 * fake netlink sockets. In a game that is thousands of stops per second, almost
 * all of them Steam and the game talking over Unix sockets. Here the calls go
 * out as sendmsg/recvmsg carrying PROOT_FASTPATH_MAGIC in the 6th argument
 * (unused by both), which Pons' proot lets through without a stop
 * (tools/proot/0003-fastpath-marker.patch). Outside that proot the marker is
 * ignored, so the calls behave as before.
 *
 * Calls stay with proot when proot has to see them: netlink sockets this
 * process created (fake or real), sends with a destination address (a Unix
 * socket path proot translates) and sends with control data (proot's fake root
 * rewrites the uid in SCM_CREDENTIALS, or D-Bus refuses the peer). PONS_SOCKFAST=0 turns this off.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <unistd.h>

/* "PONSPATH", shared with proot's seccomp filter and pons-fastpath.c. */
#define PROOT_FASTPATH_MAGIC 0x48544150534e4f50L

/* Netlink fds are rare and low; anything past this goes to proot. */
#define MAX_TRACKED_FD 4096

static int enabled = 1;
static unsigned char netlink_fd[MAX_TRACKED_FD / 8];

__attribute__((constructor)) static void sockfast_init(void)
{
    const char *off = getenv("PONS_SOCKFAST");
    enabled = !(off && strcmp(off, "0") == 0);
}

static int is_netlink(int fd)
{
    return fd < 0 || fd >= MAX_TRACKED_FD || (netlink_fd[fd / 8] >> (fd % 8)) & 1;
}

static void mark_netlink(int fd, int on)
{
    if (fd < 0 || fd >= MAX_TRACKED_FD)
        return;
    if (on)
        __atomic_fetch_or(&netlink_fd[fd / 8], (unsigned char)(1u << (fd % 8)), __ATOMIC_RELAXED);
    else
        __atomic_fetch_and(&netlink_fd[fd / 8], (unsigned char)~(1u << (fd % 8)), __ATOMIC_RELAXED);
}

static int fast(int fd)
{
    return enabled && !is_netlink(fd);
}

static void *next(const char *name)
{
    return dlsym(RTLD_NEXT, name);
}

int socket(int domain, int type, int protocol)
{
    static int (*real)(int, int, int);
    if (!real)
        real = (int (*)(int, int, int))next("socket");
    int fd = real(domain, type, protocol);
    if (fd >= 0)
        mark_netlink(fd, domain == AF_NETLINK);
    return fd;
}

/* Called before the fd closes: another thread may get the number back at once. */
__attribute__((visibility("hidden"))) void pons_sockfast_closing(int fd)
{
    mark_netlink(fd, 0);
}

/* pons-fastpath.c has its own close(), which calls the function above. */
#ifndef PONS_SOCKFAST_NO_CLOSE
int close(int fd)
{
    static int (*real)(int);
    if (!real)
        real = (int (*)(int))next("close");
    pons_sockfast_closing(fd);
    return real(fd);
}
#endif

static ssize_t marked_recvmsg(int fd, struct msghdr *msg, int flags)
{
    return syscall(SYS_recvmsg, fd, msg, flags, 0L, 0L, PROOT_FASTPATH_MAGIC);
}

static ssize_t marked_sendmsg(int fd, const struct msghdr *msg, int flags)
{
    return syscall(SYS_sendmsg, fd, msg, flags, 0L, 0L, PROOT_FASTPATH_MAGIC);
}

ssize_t recvmsg(int fd, struct msghdr *msg, int flags)
{
    static ssize_t (*real)(int, struct msghdr *, int);
    if (fast(fd))
        return marked_recvmsg(fd, msg, flags);
    if (!real)
        real = (ssize_t (*)(int, struct msghdr *, int))next("recvmsg");
    return real(fd, msg, flags);
}

ssize_t recvfrom(int fd, void *buf, size_t len, int flags, struct sockaddr *from, socklen_t *from_len)
{
    static ssize_t (*real)(int, void *, size_t, int, struct sockaddr *, socklen_t *);
    if (fast(fd) && (from == NULL || from_len != NULL)) {
        struct iovec iov = { .iov_base = buf, .iov_len = len };
        struct msghdr msg = {
            .msg_name = from, .msg_namelen = from ? *from_len : 0, .msg_iov = &iov, .msg_iovlen = 1,
        };
        ssize_t got = marked_recvmsg(fd, &msg, flags);
        if (got >= 0 && from)
            *from_len = msg.msg_namelen;
        return got;
    }
    if (!real)
        real = (ssize_t (*)(int, void *, size_t, int, struct sockaddr *, socklen_t *))next("recvfrom");
    return real(fd, buf, len, flags, from, from_len);
}

ssize_t recv(int fd, void *buf, size_t len, int flags)
{
    return recvfrom(fd, buf, len, flags, NULL, NULL);
}

ssize_t sendmsg(int fd, const struct msghdr *msg, int flags)
{
    static ssize_t (*real)(int, const struct msghdr *, int);
    if (fast(fd) && msg && msg->msg_name == NULL && msg->msg_controllen == 0)
        return marked_sendmsg(fd, msg, flags);
    if (!real)
        real = (ssize_t (*)(int, const struct msghdr *, int))next("sendmsg");
    return real(fd, msg, flags);
}

ssize_t sendto(int fd, const void *buf, size_t len, int flags, const struct sockaddr *to, socklen_t to_len)
{
    static ssize_t (*real)(int, const void *, size_t, int, const struct sockaddr *, socklen_t);
    if (fast(fd) && to == NULL) {
        struct iovec iov = { .iov_base = (void *)buf, .iov_len = len };
        struct msghdr msg = { .msg_iov = &iov, .msg_iovlen = 1 };
        return marked_sendmsg(fd, &msg, flags);
    }
    if (!real)
        real = (ssize_t (*)(int, const void *, size_t, int, const struct sockaddr *, socklen_t))next("sendto");
    return real(fd, buf, len, flags, to, to_len);
}

ssize_t send(int fd, const void *buf, size_t len, int flags)
{
    return sendto(fd, buf, len, flags, NULL, 0);
}
