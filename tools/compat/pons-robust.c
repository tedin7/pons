/*
 * libpons-robust.so: lets glibc programs use robust futex lists inside an
 * Android app.
 *
 * Android's app seccomp filter answers set_robust_list/get_robust_list with
 * ENOSYS before proot can see them. Steam's IPC engine asks get_robust_list for
 * its thread's list and dereferences the result, so it crashes. We wrap
 * syscall(): when the kernel refuses, we answer get_robust_list with the list
 * glibc already prepared for the thread (glibc registers it with an inline
 * syscall we cannot intercept, but the list itself sits in its thread
 * descriptor). The kernel then cannot clean up a dead thread's robust mutexes,
 * which only matters after a crash.
 *
 * It also keeps a small registry of local TCP connections: Android forbids apps
 * to read /proc/net/tcp and sock_diag, so lsof cannot tell Steam which process
 * owns a connection. Each loopback connect() records "pid ppid local remote" in
 * /tmp/.pons-sockets/<local port>, which pons-lsof answers from.
 *
 * Loaded with LD_PRELOAD for Steam and everything it starts (Steam re-executes
 * itself and runs helpers through shells, so the variable must stay set). Box64
 * would also try to load LD_PRELOAD entries as x86 libraries, so inside Box64
 * the constructor removes this arm64 library from the variable.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <stdio.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>
#include <pthread.h>
#include <stdint.h>
#include <linux/futex.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>

typedef long (*syscall_fn)(long, ...);

static __thread struct robust_list_head *registered_head;
static __thread size_t registered_len;
static __thread struct robust_list_head empty_head;

/* Bytes of glibc's thread descriptor searched for its robust list head. */
#define DESCRIPTOR_SCAN_BYTES 4096

/*
 * Finds glibc's robust list head inside the calling thread's descriptor: an
 * empty list points to itself, followed by a small negative futex_offset and a
 * null list_op_pending.
 */
static struct robust_list_head *glibc_thread_head(void)
{
    uintptr_t base = (uintptr_t)pthread_self();
    for (uintptr_t at = base; at + sizeof(struct robust_list_head) <= base + DESCRIPTOR_SCAN_BYTES;
         at += sizeof(void *)) {
        struct robust_list_head *head = (struct robust_list_head *)at;
        if ((uintptr_t)head->list.next == at && head->futex_offset < 0 &&
            head->futex_offset >= -64 && head->list_op_pending == NULL)
            return head;
    }
    return NULL;
}

static syscall_fn real_syscall(void)
{
    static syscall_fn fn;
    if (!fn)
        fn = (syscall_fn)dlsym(RTLD_NEXT, "syscall");
    return fn;
}

long syscall(long number, ...)
{
    va_list ap;
    long a[6];
    va_start(ap, number);
    for (int i = 0; i < 6; i++)
        a[i] = va_arg(ap, long);
    va_end(ap);

    long result = real_syscall()(number, a[0], a[1], a[2], a[3], a[4], a[5]);
    if (result != -1 || errno != ENOSYS)
        return result;

    if (number == SYS_set_robust_list) {
        registered_head = (struct robust_list_head *)a[0];
        registered_len = (size_t)a[1];
        errno = 0;
        return 0;
    }
    if (number == SYS_get_robust_list && a[0] == 0) {
        struct robust_list_head *head = registered_head;
        size_t len = registered_len;
        if (!head && (head = glibc_thread_head()) != NULL) {
            registered_head = head;
            registered_len = len = sizeof(*head);
        }
        if (!head) {
            empty_head.list.next = &empty_head.list;
            head = &empty_head;
            len = sizeof(empty_head);
        }
        if (a[1]) *(struct robust_list_head **)a[1] = head;
        if (a[2]) *(size_t *)a[2] = len;
        errno = 0;
        return 0;
    }
    return result;
}

#define SOCKET_REGISTRY "/tmp/.pons-sockets"

typedef int (*connect_fn)(int, const struct sockaddr *, socklen_t);

/* Remembers which process owns a loopback TCP connection, for pons-lsof. */
static void register_connection(int fd, const struct sockaddr *to)
{
    if (to->sa_family != AF_INET)
        return;
    const struct sockaddr_in *remote = (const struct sockaddr_in *)to;
    if (ntohl(remote->sin_addr.s_addr) != INADDR_LOOPBACK)
        return;
    int type = 0;
    socklen_t type_len = sizeof(type);
    if (getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &type_len) != 0 || type != SOCK_STREAM)
        return;
    struct sockaddr_in local;
    socklen_t local_len = sizeof(local);
    if (getsockname(fd, (struct sockaddr *)&local, &local_len) != 0)
        return;

    char path[96], line[96];
    mkdir(SOCKET_REGISTRY, 0777);
    snprintf(path, sizeof(path), SOCKET_REGISTRY "/%u", ntohs(local.sin_port));
    int len = snprintf(line, sizeof(line), "%d %d %u %u\n", (int)getpid(), (int)getppid(),
                       ntohs(local.sin_port), ntohs(remote->sin_port));
    int out = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0666);
    if (out < 0)
        return;
    if (write(out, line, (size_t)len) != len) { /* best effort */ }
    close(out);
}

int connect(int fd, const struct sockaddr *addr, socklen_t len)
{
    static connect_fn real_connect;
    if (!real_connect)
        real_connect = (connect_fn)dlsym(RTLD_NEXT, "connect");
    int result = real_connect(fd, addr, len);
    int saved = errno;
    if ((result == 0 || saved == EINPROGRESS) && addr && len >= sizeof(struct sockaddr_in))
        register_connection(fd, addr);
    errno = saved;
    return result;
}

/*
 * Steam's web helper script sets LIBGL_KOPPER_DISABLE=true; Zink then finds no
 * GLX visuals on Xvfb and the web UI cannot open its window. Let the web helper
 * use Mesa's software GL instead (menus only; games keep Zink on the GPU).
 */
__attribute__((constructor)) static void steamwebhelper_gl(void)
{
    if (strcmp(program_invocation_short_name, "steamwebhelper") == 0)
        unsetenv("MESA_LOADER_DRIVER_OVERRIDE");
}

/* Inside Box64, hide this arm64 library from its x86 preload list. */
__attribute__((constructor)) static void drop_from_preload(void)
{
    const char *preload = getenv("LD_PRELOAD");
    if (!preload || strcmp(program_invocation_short_name, "box64") != 0)
        return;
    char *copy = strdup(preload), *out = calloc(1, strlen(preload) + 1);
    if (!copy || !out) {
        free(copy);
        free(out);
        return;
    }
    for (char *tok = strtok(copy, ": "); tok; tok = strtok(NULL, ": ")) {
        if (strstr(tok, "libpons-robust.so"))
            continue;
        if (*out) strcat(out, ":");
        strcat(out, tok);
    }
    if (*out) setenv("LD_PRELOAD", out, 1);
    else unsetenv("LD_PRELOAD");
    free(copy);
    free(out);
}
