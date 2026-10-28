#!/usr/bin/env python3
"""Applies Pinguplay's Box64 changes to a Box64 source tree: apply.py <box64-src>.

0001: (V)CVTSI2SD with a 32-bit source never rounds (every int32 fits exactly in
a double), so skip switching the Arm64 rounding mode around it. Writing FPCR is
serializing on Arm cores; measured on Snapdragon 8 Elite it cost ~11 ns per
conversion, inside loops as common as `x += f(i)`.

0002: save the translation cache (DynaCache) while the program runs. Box64 only
writes it when a library is unmapped or the program exits normally; a game that
is killed (the usual way an Android app stops it) never leaves a cache, so every
start translates everything again: minutes of all cores at full load. A helper
thread now saves it every $PINGUPLAY_DYNACACHE_PERIOD seconds when there is new
code, and whenever the file $PINGUPLAY_DYNACACHE_FLUSH changes (touch it before
stopping the game). Saving takes the same lock Box64 already uses at runtime.

0003: diagnostic, off unless $PINGUPLAY_CALLSTATS=<file> is set. Counts how often
each wrapped native function is called from x86 code and writes "count name"
lines (descending) to <file> every 10 s and at exit. Two paths: the dynarec fast
path (call_n emits a non-atomic counter increment, only when enabled at
translation time, never in relocatable/cached blocks) and the generic int3
bridge handler (atomic add). One shared table keyed by function address. When
unset, no code is generated and nothing is counted. Blocks loaded from DynaCache
carry no counters: run with the cache off.
"""
import pathlib
import sys

src = pathlib.Path(sys.argv[1]) / "src/dynarec/arm64"

OLD_SET = """            if(BOX64ENV(dynarec_fastround)<2) {
                u8 = sse_setround(dyn, ninst, x3, x4, x5);
            }
            if(rex.w) {
                SCVTFDx(d1, ed);
            } else {
                SCVTFDw(d1, ed);
            }
            if(BOX64ENV(dynarec_fastround)<2) {
                x87_restoreround(dyn, ninst, u8);
            }"""
NEW_SET = """            // int32 -> double is always exact: only the 64-bit form can round.
            if(rex.w && BOX64ENV(dynarec_fastround)<2) {
                u8 = sse_setround(dyn, ninst, x3, x4, x5);
            }
            if(rex.w) {
                SCVTFDx(d1, ed);
            } else {
                SCVTFDw(d1, ed);
            }
            if(rex.w && BOX64ENV(dynarec_fastround)<2) {
                x87_restoreround(dyn, ninst, u8);
            }"""

for name, marker in (("dynarec_arm64_f20f.c", 'INST_NAME("CVTSI2SD Gx, Ed");'),
                     ("dynarec_arm64_avx_f2_0f.c", 'INST_NAME("VCVTSI2SD Gx, Vx, Ed");')):
    path = src / name
    code = path.read_text()
    start = code.find(marker)
    if start < 0:
        sys.exit(f"apply.py: {marker} not found in {name}")
    at = code.find(OLD_SET, start)
    if at < 0 or at - start > 400:
        sys.exit(f"apply.py: rounding block for {marker} not found in {name}")
    path.write_text(code[:at] + NEW_SET + code[at + len(OLD_SET):])

core = pathlib.Path(sys.argv[1]) / "src/core.c"
code = core.read_text()
HELPER = r"""
// Pinguplay 0002: periodic / on-request DynaCache saving (see tools/box64/patches/apply.py).
#include <pthread.h>
#include <signal.h>
#include <sys/stat.h>
void SerializeAllMapping();

static void* pinguplay_dynacache_saver(void* arg)
{
    (void)arg;
    const char* flush = getenv("PINGUPLAY_DYNACACHE_FLUSH");
    const char* period_env = getenv("PINGUPLAY_DYNACACHE_PERIOD");
    const long period = period_env ? strtol(period_env, NULL, 10) : 0;
    struct stat st;
    long long seen = (flush && stat(flush, &st) == 0) ? (long long)st.st_mtime : 0;
    long waited = 0;
    while (!box64_quit) {
        sleep(2);
        waited += 2;
        int save = period > 0 && waited >= period;
        if (flush && stat(flush, &st) == 0 && (long long)st.st_mtime != seen) {
            seen = (long long)st.st_mtime;
            save = 1;
        }
        if (save && !box64_quit) {
            SerializeAllMapping();
            waited = 0;
        }
    }
    return NULL;
}

static void pinguplay_start_dynacache_saver(void)
{
    if (!BOX64ENV(dynacache) || BOX64ENV(dynacache) == 2)
        return;
    if (!getenv("PINGUPLAY_DYNACACHE_FLUSH") && !getenv("PINGUPLAY_DYNACACHE_PERIOD"))
        return;
    // Keep emulated signals away from this host-only thread.
    sigset_t all, old;
    sigfillset(&all);
    pthread_sigmask(SIG_SETMASK, &all, &old);
    pthread_t thread;
    if (pthread_create(&thread, NULL, pinguplay_dynacache_saver, NULL) == 0)
        pthread_detach(thread);
    pthread_sigmask(SIG_SETMASK, &old, NULL);
}

int emulate(x64emu_t* emu, elfheader_t* elf_header)
"""
OLD_EMU = "\nint emulate(x64emu_t* emu, elfheader_t* elf_header)\n"
if code.count(OLD_EMU) != 1:
    sys.exit("apply.py: emulate() not found once in core.c")
code = code.replace(OLD_EMU, HELPER, 1)
OLD_START = "    atexit(endBox64);\n    loadProtectionFromMap();\n"
if code.count(OLD_START) != 1:
    sys.exit("apply.py: emulate() start not found in core.c")
code = code.replace(OLD_START, OLD_START + "    pinguplay_start_dynacache_saver();\n", 1)
core.write_text(code)


# ---- 0003: native call statistics ----
STATS = r"""
// Pinguplay 0003: native call statistics (see tools/box64/patches/apply.py).
#define PP_STATS_SIZE 4096
typedef struct { uintptr_t fn; uint64_t count; const char* name; } pp_stat_t;
static pp_stat_t pp_stats[PP_STATS_SIZE];
static pthread_mutex_t pp_stats_mutex = PTHREAD_MUTEX_INITIALIZER;
int pinguplay_callstats_on = 0;
static const char* pp_stats_file = NULL;

static pp_stat_t* pp_stats_find(uintptr_t fn)
{
    unsigned h = (unsigned)(((uint64_t)(fn >> 2) * 0x9E3779B97F4A7C15ULL) >> 52);
    for (int i = 0; i < PP_STATS_SIZE; ++i) {
        pp_stat_t* e = &pp_stats[(h + i) & (PP_STATS_SIZE - 1)];
        uintptr_t k = __atomic_load_n(&e->fn, __ATOMIC_ACQUIRE);
        if (k == fn)
            return e;
        if (!k)
            return NULL;
    }
    return NULL;
}

// Returns the slot for a native function, creating it if needed (NULL if the table is full).
static pp_stat_t* pp_stats_get(uintptr_t fn, void* bridge)
{
    pp_stat_t* e = pp_stats_find(fn);
    if (e)
        return e;
    pthread_mutex_lock(&pp_stats_mutex);
    e = pp_stats_find(fn);
    if (!e) {
        unsigned h = (unsigned)(((uint64_t)(fn >> 2) * 0x9E3779B97F4A7C15ULL) >> 52);
        for (int i = 0; i < PP_STATS_SIZE; ++i) {
            pp_stat_t* c = &pp_stats[(h + i) & (PP_STATS_SIZE - 1)];
            if (!c->fn) {
                const char* n = (bridge ? GetBridgeName(bridge) : NULL);
                if (!n)
                    n = GetNativeName((void*)fn, 1);
                c->name = strdup(n ? n : "?");
                __atomic_store_n(&c->fn, fn, __ATOMIC_RELEASE);
                e = c;
                break;
            }
        }
    }
    pthread_mutex_unlock(&pp_stats_mutex);
    return e;
}

// Address of the 64-bit counter for dynarec-emitted increments (NULL: do not count).
uint64_t* pinguplay_callstats_slot(void* fn, void* bridge)
{
    pp_stat_t* e = pp_stats_get((uintptr_t)fn, bridge);
    return e ? &e->count : NULL;
}

void pinguplay_callstats_count(void* fn, void* bridge)
{
    pp_stat_t* e = pp_stats_get((uintptr_t)fn, bridge);
    if (e)
        __atomic_fetch_add(&e->count, 1, __ATOMIC_RELAXED);
}

static int pp_stats_cmp(const void* a, const void* b)
{
    const pp_stat_t* x = a;
    const pp_stat_t* y = b;
    return (x->count < y->count) - (x->count > y->count);
}

static void pp_stats_dump(void)
{
    if (!pp_stats_file)
        return;
    static pp_stat_t snap[PP_STATS_SIZE];
    static pthread_mutex_t dump_mutex = PTHREAD_MUTEX_INITIALIZER;
    pthread_mutex_lock(&dump_mutex);
    int n = 0;
    for (int i = 0; i < PP_STATS_SIZE; ++i) {
        uintptr_t k = __atomic_load_n(&pp_stats[i].fn, __ATOMIC_ACQUIRE);
        if (k) {
            snap[n].fn = k;
            snap[n].name = pp_stats[i].name;
            snap[n].count = __atomic_load_n(&pp_stats[i].count, __ATOMIC_RELAXED);
            ++n;
        }
    }
    qsort(snap, n, sizeof(pp_stat_t), pp_stats_cmp);
    char tmp[4096];
    if (snprintf(tmp, sizeof(tmp), "%s.tmp", pp_stats_file) < (int)sizeof(tmp)) {
        FILE* f = fopen(tmp, "w");
        if (f) {
            for (int i = 0; i < n; ++i)
                fprintf(f, "%llu %s\n", (unsigned long long)snap[i].count, snap[i].name ? snap[i].name : "?");
            int ok = fflush(f) == 0;
            fclose(f);
            if (ok)
                rename(tmp, pp_stats_file);
        }
    }
    pthread_mutex_unlock(&dump_mutex);
}

static void* pp_stats_thread(void* arg)
{
    (void)arg;
    long waited = 0;
    while (!box64_quit) {
        sleep(2);
        waited += 2;
        if (waited >= 10) {
            pp_stats_dump();
            waited = 0;
        }
    }
    return NULL;
}

static void pinguplay_start_callstats(void)
{
    const char* file = getenv("PINGUPLAY_CALLSTATS");
    if (!file || !*file)
        return;
    pp_stats_file = file;
    pinguplay_callstats_on = 1;
    atexit(pp_stats_dump);
    sigset_t all, old;
    sigfillset(&all);
    pthread_sigmask(SIG_SETMASK, &all, &old);
    pthread_t thread;
    if (pthread_create(&thread, NULL, pp_stats_thread, NULL) == 0)
        pthread_detach(thread);
    pthread_sigmask(SIG_SETMASK, &old, NULL);
}

int emulate(x64emu_t* emu, elfheader_t* elf_header)
"""
code = core.read_text()
if code.count("\nint emulate(x64emu_t* emu, elfheader_t* elf_header)\n") != 1:
    sys.exit("apply.py: emulate() not found once in core.c (0003)")
code = code.replace("\nint emulate(x64emu_t* emu, elfheader_t* elf_header)\n", STATS, 1)
OLD_S2 = "    pinguplay_start_dynacache_saver();\n"
if code.count(OLD_S2) != 1:
    sys.exit("apply.py: saver start call not found in core.c (0003)")
code = code.replace(OLD_S2, OLD_S2 + "    pinguplay_start_callstats();\n", 1)
core.write_text(code)

def patch_once(path, old, new, what):
    t = path.read_text()
    if t.count(old) != 1:
        sys.exit(f"apply.py: {what} not found once in {path.name}")
    path.write_text(t.replace(old, new, 1))

root = pathlib.Path(sys.argv[1]) / "src"
helper = root / "dynarec/arm64/dynarec_arm64_helper.c"
patch_once(helper, '#include "dynarec_arm64_functions.h"\n',
    '#include "dynarec_arm64_functions.h"\n'
    'extern int pinguplay_callstats_on;\n'
    'uint64_t* pinguplay_callstats_slot(void* fn, void* bridge);\n', "include anchor (0003)")
patch_once(helper, "    // prepare regs for native call\n    MOVx_REG(0, xRDI);\n",
    """    // Pinguplay 0003: count calls. x3/x4 are free here (args are copied below).
    // Not in relocatable blocks: the slot address would go stale in DynaCache.
    if(pinguplay_callstats_on && !dyn->need_reloc) {
        uint64_t* slot = pinguplay_callstats_slot((void*)*(uintptr_t*)fnc, (void*)((uintptr_t)fnc - 11));
        if(slot) {
            MOV64x(x3, (uintptr_t)slot);
            LDRx_U12(x4, x3, 0);
            ADDx_U12(x4, x4, 1);
            STRx_U12(x4, x3, 0);
        }
    }
    // prepare regs for native call
    MOVx_REG(0, xRDI);
""", "call_n arg prep (0003)")
int3 = root / "emu/x64int3.c"
patch_once(int3, '#include "tools/bridge_private.h"\n',
    '#include "tools/bridge_private.h"\n'
    'extern int pinguplay_callstats_on;\n'
    'void pinguplay_callstats_count(void* fn, void* bridge);\n', "include anchor x64int3.c (0003)")
patch_once(int3, "            wrapper_t w = bridge->w;\n            a = F64(addr);\n",
    "            wrapper_t w = bridge->w;\n            a = F64(addr);\n"
    "            if(pinguplay_callstats_on) pinguplay_callstats_count((void*)a, bridge);\n",
    "bridge call (0003)")

print("pinguplay box64 patches applied")
