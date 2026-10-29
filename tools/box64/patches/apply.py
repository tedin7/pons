#!/usr/bin/env python3
"""Applies Pons's Box64 changes to a Box64 source tree: apply.py <box64-src>.

0001: (V)CVTSI2SD with a 32-bit source never rounds (every int32 fits exactly in
a double), so skip switching the Arm64 rounding mode around it. Writing FPCR is
serializing on Arm cores; measured on Snapdragon 8 Elite it cost ~11 ns per
conversion, inside loops as common as `x += f(i)`.

0002: save the translation cache (DynaCache) while the program runs. Box64 only
writes it when a library is unmapped or the program exits normally; a game that
is killed (the usual way an Android app stops it) never leaves a cache, so every
start translates everything again: minutes of all cores at full load. A helper
thread now saves it every $PONS_DYNACACHE_PERIOD seconds when there is new
code, and whenever the file $PONS_DYNACACHE_FLUSH changes (touch it before
stopping the game). Saving takes the same lock Box64 already uses at runtime.

0003: diagnostic, off unless $PONS_CALLSTATS=<file> is set. Counts how often
each wrapped native function is called from x86 code and writes "count name"
lines (descending) to <file> every 10 s and at exit. Two paths: the dynarec fast
path (call_n emits a non-atomic counter increment, only when enabled at
translation time, never in relocatable/cached blocks) and the generic int3
bridge handler (atomic add). One shared table keyed by function address. When
unset, no code is generated and nothing is counted. Blocks loaded from DynaCache
carry no counters: run with the cache off.

0005: CMP with a small immediate encodes it in SUBS (see the code below).
0006: MOVUPS/MOVAPS through [base + index] use arm64 register-offset LDR/STR Q.
0007: MOV r32/r64 <-> [base + index (x4/x8)] use register-offset LDR/STR too.
0008: float/double arguments of native calls are not reloaded from memory.
0009: x86 RSP/RBP stay in registers across native calls (no reload from memory).
0010: register-zeroing XORs become MOVI #0, which carries no dependency.
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
// Pons 0002: periodic / on-request DynaCache saving (see tools/box64/patches/apply.py).
#include <pthread.h>
#include <signal.h>
#include <sys/stat.h>
void SerializeAllMapping();
void testAllBlocks();

static void* pons_dynacache_saver(void* arg)
{
    (void)arg;
    const char* flush = getenv("PONS_DYNACACHE_FLUSH");
    const char* period_env = getenv("PONS_DYNACACHE_PERIOD");
    const long period = period_env ? strtol(period_env, NULL, 10) : 0;
    struct stat st;
    long long seen = (flush && stat(flush, &st) == 0) ? (long long)st.st_mtime : 0;
    long waited = 0, ticks = 0;
    /* Pons 0004: PONS_MEMSTATS=1 prints Box64's own allocator usage every 10 s
     * (custommem.c testAllBlocks: "CustomMem: Total ... allocated memory"), to
     * tell Box64's memory from the game's. */
    const int memstats = getenv("PONS_MEMSTATS") != NULL;
    while (!box64_quit) {
        sleep(2);
        waited += 2;
        if (memstats && ++ticks % 5 == 0)
            testAllBlocks();
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

static void pons_start_dynacache_saver(void)
{
    if (!getenv("PONS_MEMSTATS")) {
        if (!BOX64ENV(dynacache) || BOX64ENV(dynacache) == 2)
            return;
        if (!getenv("PONS_DYNACACHE_FLUSH") && !getenv("PONS_DYNACACHE_PERIOD"))
            return;
    }
    // Keep emulated signals away from this host-only thread.
    sigset_t all, old;
    sigfillset(&all);
    pthread_sigmask(SIG_SETMASK, &all, &old);
    pthread_t thread;
    if (pthread_create(&thread, NULL, pons_dynacache_saver, NULL) == 0)
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
code = code.replace(OLD_START, OLD_START + "    pons_start_dynacache_saver();\n", 1)
core.write_text(code)


# ---- 0003: native call statistics ----
STATS = r"""
// Pons 0003: native call statistics (see tools/box64/patches/apply.py).
#define PP_STATS_SIZE 4096
typedef struct { uintptr_t fn; uint64_t count; const char* name; } pp_stat_t;
static pp_stat_t pp_stats[PP_STATS_SIZE];
static pthread_mutex_t pp_stats_mutex = PTHREAD_MUTEX_INITIALIZER;
int pons_callstats_on = 0;
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
uint64_t* pons_callstats_slot(void* fn, void* bridge)
{
    pp_stat_t* e = pp_stats_get((uintptr_t)fn, bridge);
    return e ? &e->count : NULL;
}

void pons_callstats_count(void* fn, void* bridge)
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

static void pons_start_callstats(void)
{
    const char* file = getenv("PONS_CALLSTATS");
    if (!file || !*file)
        return;
    pp_stats_file = file;
    pons_callstats_on = 1;
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
OLD_S2 = "    pons_start_dynacache_saver();\n"
if code.count(OLD_S2) != 1:
    sys.exit("apply.py: saver start call not found in core.c (0003)")
code = code.replace(OLD_S2, OLD_S2 + "    pons_start_callstats();\n", 1)
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
    'extern int pons_callstats_on;\n'
    'uint64_t* pons_callstats_slot(void* fn, void* bridge);\n', "include anchor (0003)")
patch_once(helper, "    // prepare regs for native call\n    MOVx_REG(0, xRDI);\n",
    """    // Pons 0003: count calls. x3/x4 are free here (args are copied below).
    // Not in relocatable blocks: the slot address would go stale in DynaCache.
    if(pons_callstats_on && !dyn->need_reloc) {
        uint64_t* slot = pons_callstats_slot((void*)*(uintptr_t*)fnc, (void*)((uintptr_t)fnc - 11));
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
    'extern int pons_callstats_on;\n'
    'void pons_callstats_count(void* fn, void* bridge);\n', "include anchor x64int3.c (0003)")
patch_once(int3, "            wrapper_t w = bridge->w;\n            a = F64(addr);\n",
    "            wrapper_t w = bridge->w;\n            a = F64(addr);\n"
    "            if(pons_callstats_on) pons_callstats_count((void*)a, bridge);\n",
    "bridge call (0003)")


# 0005: CMP with a small immediate. Box64 always loaded the immediate into a
# register (MOVZ) and compared registers (SUBS); arm64 SUBS takes a 12-bit
# immediate directly. When the flags are consumed natively (no pending flags, no
# AF) the register is never needed, which is the common "cmp $n, reg; jcc" of
# loops: one instruction instead of two. Same flags, same results otherwise.
arm = pathlib.Path(sys.argv[1]) / "src/dynarec/arm64"
tests = arm / "dynarec_arm64_emit_tests.c"
code = tests.read_text()
CMP_C = """
// Pons 0005: emit CMP32 of s1 against a constant 0 < c < 4096, using s2..s5 as
// scratch. Without pending flags or AF the constant is encoded in SUBS itself.
void emit_cmp32_c(dynarec_arm_t* dyn, int ninst, rex_t rex, int s1, int64_t c, int s2, int s3, int s4, int s5)
{
    int fallback = 0;
    IFX_PENDOR0 { fallback = 1; }
    IFX(X_AF) { fallback = 1; }
    if (fallback) {
        MOV64xw(s2, c);
        emit_cmp32(dyn, ninst, rex, s1, s2, s3, s4, s5);
        return;
    }
    SET_DFNONE();
    SUBSxw_U12(s5, s1, c);   // res = s1 - c
    IFX(X_ZF) {
        IFNATIVE(NF_EQ) {} else {
            CSETw(s4, cEQ);
            BFIw(xFlags, s4, F_ZF, 1);
        }
    }
    IFX(X_CF) {
        // inverted carry
        IFNATIVE(NF_CF) {
            GEN_INVERTED_CARRY();
        } else {
            CSETw(s4, cCC);
            BFIw(xFlags, s4, F_CF, 1);
        }
    }
    IFX(X_OF) {
        IFNATIVE(NF_VF) {} else {
            CSETw(s4, cVS);
            BFIw(xFlags, s4, F_OF, 1);
        }
    }
    IFX(X_SF) {
        IFNATIVE(NF_SF) {} else {
            LSRxw(s3, s5, (rex.w)?63:31);
            BFIw(xFlags, s3, F_SF, 1);
        }
    }
    IFX(X_PF) {
        emit_pf(dyn, ninst, s5, s4);
    }
}

// emit CMP32 instruction, from cmp s1 , 0, using s3 and s4 as scratch"""
OLD_CMP0 = "\n// emit CMP32 instruction, from cmp s1 , 0, using s3 and s4 as scratch"
if code.count(OLD_CMP0) != 1:
    sys.exit("apply.py: emit_cmp32_0 comment not found once in dynarec_arm64_emit_tests.c")
tests.write_text(code.replace(OLD_CMP0, CMP_C, 1))

helper = arm / "dynarec_arm64_helper.h"
code = helper.read_text()
for old, new in (("#define emit_cmp32_0    STEPNAME(emit_cmp32_0)",
                  "#define emit_cmp32_0    STEPNAME(emit_cmp32_0)\n#define emit_cmp32_c    STEPNAME(emit_cmp32_c)"),
                 ("void emit_cmp32_0(dynarec_arm_t* dyn, int ninst, rex_t rex, int s1, int s3, int s4);",
                  "void emit_cmp32_0(dynarec_arm_t* dyn, int ninst, rex_t rex, int s1, int s3, int s4);\n"
                  "void emit_cmp32_c(dynarec_arm_t* dyn, int ninst, rex_t rex, int s1, int64_t c, int s2, int s3, int s4, int s5);")):
    if code.count(old) != 1:
        sys.exit("apply.py: %r not found once in dynarec_arm64_helper.h" % old[:40])
    code = code.replace(old, new, 1)
helper.write_text(code)

op00 = arm / "dynarec_arm64_00.c"
code = op00.read_text()
for reg in ("xRAX", "ed"):
    old = ("            if(i64) {\n"
           "                MOV64xw(x2, i64);\n"
           "                emit_cmp32(dyn, ninst, rex, %s, x2, x3, x4, x5);\n" % reg)
    new = ("            if(i64 > 0 && i64 < 0x1000) {\n"
           "                emit_cmp32_c(dyn, ninst, rex, %s, i64, x2, x3, x4, x5);\n"
           "            } else if(i64) {\n"
           "                MOV64xw(x2, i64);\n"
           "                emit_cmp32(dyn, ninst, rex, %s, x2, x3, x4, x5);\n" % (reg, reg))
    if reg == "ed":
        old = old.replace("            ", "                    ")
        new = new.replace("            ", "                    ")
    if code.count(old) != 1:
        sys.exit("apply.py: CMP call site for %s not found once in dynarec_arm64_00.c" % reg)
    code = code.replace(old, new, 1)
op00.write_text(code)


# 0006: 16-byte SSE moves (MOVUPS/MOVAPS) through [base + index]. Box64 added
# base and index into a scratch register, then loaded or stored through it; arm64
# LDR/STR Q take a register offset. Only the plain case, so nothing else changes:
# no displacement, scale 1, no segment, 64-bit addressing, no 0x67 prefix.
# 0007: the same for MOV r32/r64 <-> memory, where arm64 also scales the index
# by the access size (x4 for 32-bit, x8 for 64-bit), as x86 code often does.
helper = arm / "dynarec_arm64_helper.h"
code = helper.read_text()
SIB_HELPER = """
// Pons 0006/0007: true when the memory operand is [base + index << scale] with no
// displacement, no segment and 64-bit addressing, and the scale is 0 or equals
// size_log2 (what arm64 register-offset LDR/STR of that size can shift by).
// Gives the native base and index registers, and whether the index is shifted.
static inline int pons_sib_regoff(uintptr_t addr, uint8_t nextop, rex_t rex, int size_log2,
                                  uint8_t* base, uint8_t* index, uint8_t* shifted)
{
    if ((nextop & 0xC7) != 0x04 || rex.seg || rex.is32bits || rex.is67)
        return 0;
    uint8_t sib = *(uint8_t*)addr;
    int scale = sib >> 6;
    if ((sib & 7) == 5 || (scale != 0 && scale != size_log2))
        return 0;
    int idx = ((sib >> 3) & 7) + (rex.x << 3);
    if (idx == 4)
        return 0;
    *base = TO_NAT((sib & 7) + (rex.b << 3));
    *index = TO_NAT(idx);
    *shifted = scale != 0;
    return 1;
}

#endif"""
last = code.rstrip().rfind("#endif")
if last < 0:
    sys.exit("apply.py: no closing #endif in dynarec_arm64_helper.h")
code = code[:last] + SIB_HELPER.lstrip("\n") + code[last + len("#endif"):]
helper.write_text(code)

op0f = arm / "dynarec_arm64_0f.c"
code = op0f.read_text()
GETED = "addr = geted(dyn, addr, ninst, nextop, &ed, x1, &fixedaddress, &unscaled, 0xfff<<4, 15, rex, NULL, 0, 0);"
def fused(indent, store):
    lines = [
        "if(pons_sib_regoff(addr, nextop, rex, 4, &ed, &wback, &u8)) {",
        "    addr++; // the SIB byte (scale is 0 here: x86 cannot scale by 16)",
        "    %s(v0, ed, wback);" % ("VSTR128_REG" if store else "VLDR128_REG"),
        "} else {",
        "    " + GETED,
        "    %s(v0, ed, fixedaddress);" % ("VST128" if store else "VLD128"),
        "}",
    ]
    return "\n".join(indent + l for l in lines)
for name, indent, store, tail in (
        ('INST_NAME("MOVUPS Gx,Ex");', " " * 16, False, "VLD128(v0, ed, fixedaddress);   // no alignment issue with ARMv8 NEON :)"),
        ('INST_NAME("MOVAPS Gx,Ex");', " " * 16, False, "VLD128(v0, ed, fixedaddress);"),
        ('INST_NAME("MOVUPS Ex,Gx");', " " * 20, True, "VST128(v0, ed, fixedaddress);"),
        ('INST_NAME("MOVAPS Ex,Gx");', " " * 16, True, "VST128(v0, ed, fixedaddress);")):
    start = code.find(name)
    if start < 0 or code.count(name) != 1:
        sys.exit("apply.py: %s not found once" % name)
    old = indent + GETED + "\n" + indent + tail
    at = code.find(old, start)
    if at < 0 or at - start > 1200:
        sys.exit("apply.py: memory path of %s not found" % name)
    code = code[:at] + fused(indent, store) + code[at + len(old):]
op0f.write_text(code)

op00 = arm / "dynarec_arm64_00.c"
code = op00.read_text()
LOAD_OLD = """                addr = geted(dyn, addr, ninst, nextop, &ed, x2, &fixedaddress, &unscaled, 0xfff<<(2+rex.w), (1<<(2+rex.w))-1, rex, &lock, 0, 0);
                if(!lock && !dyn->insts[ninst].use_lrcpc && BOX64ENV(unity)"""
LOAD_NEW = """                if(!dyn->insts[ninst].use_lrcpc && pons_sib_regoff(addr, nextop, rex, 2+rex.w, &ed, &wback, &u8)) {
                    addr++; // the SIB byte
                    lock = 0;
                    SMREADLOCK(lock);
                    if(!u8) LDRxw_REG(gd, ed, wback);
                    else if(rex.w) LDRx_REG_LSL3(gd, ed, wback);
                    else LDRw_REG_LSL2(gd, ed, wback);
                    break;
                }
                addr = geted(dyn, addr, ninst, nextop, &ed, x2, &fixedaddress, &unscaled, 0xfff<<(2+rex.w), (1<<(2+rex.w))-1, rex, &lock, 0, 0);
                if(!lock && !dyn->insts[ninst].use_lrcpc && BOX64ENV(unity)"""
start = code.find('INST_NAME("MOV Gd, Ed");')
at = code.find(LOAD_OLD, start)
if start < 0 or at < 0 or at - start > 600:
    sys.exit("apply.py: MOV Gd, Ed memory path not found")
code = code[:at] + LOAD_NEW + code[at + len(LOAD_OLD):]
STORE_OLD = """                } else {
                    addr = geted(dyn, addr, ninst, nextop, &ed, x2, &fixedaddress, &unscaled, 0xfff << (2 + rex.w), (1 << (2 + rex.w)) - 1, rex, &lock, 0, 0);"""
STORE_NEW = """                } else if(!dyn->insts[ninst].use_lrcpc && pons_sib_regoff(addr, nextop, rex, 2+rex.w, &ed, &wback, &u8)) {
                    addr++; // the SIB byte
                    lock = 0;
                    if(!u8) STRxw_REG(gd, ed, wback);
                    else if(rex.w) STRx_REG_LSL3(gd, ed, wback);
                    else STRw_REG_LSL2(gd, ed, wback);
                } else {
                    addr = geted(dyn, addr, ninst, nextop, &ed, x2, &fixedaddress, &unscaled, 0xfff << (2 + rex.w), (1 << (2 + rex.w)) - 1, rex, &lock, 0, 0);"""
start = code.find('INST_NAME("MOV Ed, Gd");')
at = code.find(STORE_OLD, start)
if start < 0 or at < 0 or at - start > 1500:
    sys.exit("apply.py: MOV Ed, Gd memory path not found")
code = code[:at] + STORE_NEW + code[at + len(STORE_OLD):]
op00.write_text(code)


# 0008: native calls with float/double arguments (libm and friends). Before such
# a call Box64 stores XMM0-7 to the emulator (sse_purge07cache) and then call_n
# reloads each argument from there, though XMMn lives in Vn and the store left
# it in place: a store-to-load round trip on the critical path of every chained
# math call (~6 ns on the Snapdragon 8 Elite, measured with bridgeparts.c).
# An argument that was cached when the purge ran, in the same instruction, is
# re-attached to its register instead of reloaded.
hc = arm / "dynarec_arm64_helper.c"
code = hc.read_text()
OLD_PURGE = """void sse_purge07cache(dynarec_arm_t* dyn, int ninst, int s1)
{
    int old = -1;
    for (int i=0; i<8; ++i)
        if(dyn->n.ssecache[i].v!=-1) {"""
NEW_PURGE = """void sse_purge07cache(dynarec_arm_t* dyn, int ninst, int s1)
{
    int old = -1;
    pons_held_ninst = ninst;
    pons_held_xmm = 0;
    for (int i=0; i<8; ++i)
        if(dyn->n.ssecache[i].v!=-1) {
            pons_held_xmm |= 1<<i;"""
OLD_ARGS = """        for(int i=0; i<abs(w)-1; ++i) {
            sse_get_reg(dyn, ninst, x3, i, w);
        }"""
NEW_ARGS = """        for(int i=0; i<abs(w)-1; ++i) {
            if(pons_held_ninst==ninst && i<8 && (pons_held_xmm&(1<<i))) {
                // Pons 0008: still in its register, only stored by the purge: no reload
                dyn->n.xmm_used |= 1<<i;
                dyn->n.ssecache[i].reg = fpu_get_reg_xmm(dyn, w?NEON_CACHE_XMMW:NEON_CACHE_XMMR, i);
                dyn->n.ssecache[i].write = w;
            } else {
                sse_get_reg(dyn, ninst, x3, i, w);
            }
        }"""
OLD_CALLN = "\nvoid call_n(dynarec_arm_t* dyn, int ninst, void* fnc, int w)\n"
NEW_CALLN = """
// Pons 0008: XMM registers 0-7 that sse_purge07cache stored but left in their
// fixed V register, and the instruction it ran for (read by call_n).
static __thread int pons_held_ninst = -1;
static __thread uint8_t pons_held_xmm;

void call_n(dynarec_arm_t* dyn, int ninst, void* fnc, int w)
"""
for old, new, what in ((OLD_CALLN, NEW_CALLN, "call_n definition"), (OLD_PURGE, NEW_PURGE, "sse_purge07cache"), (OLD_ARGS, NEW_ARGS, "call_n XMM args")):
    if code.count(old) != 1:
        sys.exit("apply.py: %s not found once in dynarec_arm64_helper.c" % what)
    code = code.replace(old, new, 1)
hc.write_text(code)


# 0009: keep x86 RSP/RBP in registers across native calls. call_n stored them
# to the emulator struct and loaded them back after the call; everything x86
# does next goes through RSP, so the CPU had to wait for that load, which it
# cannot reorder ahead of the callee's own stores: consecutive native calls
# stopped overlapping. They now also go to x20/x21 (x86 R10/R11, which SysV
# lets any callee clobber, and which arm64 callees must preserve) and come back
# from there; the struct copy stays for signal handlers. The eflags reload is
# dropped too: xFlags is x26, also preserved by the callee.
hc = arm / "dynarec_arm64_helper.c"
code = hc.read_text()
for old, new, what in (
        ("    MOVx_REG(x5, xR9);\n    // native call\n",
         "    MOVx_REG(x5, xR9);\n"
         "    // Pons 0009: x86 R10/R11 are scratch across calls; keep RSP/RBP there\n"
         "    MOVx_REG(xR10, xRSP);\n"
         "    MOVx_REG(xR11, xRBP);\n"
         "    // native call\n", "call_n arg moves"),
        ("    #define GO(A, B) LDPx_S7_offset(x##A, x##B, xEmu, offsetof(x64emu_t, regs[_##A]))\n"
         "    GO(RSP, RBP);\n"
         "    #undef GO\n",
         "    MOVx_REG(xRSP, xR10);\n"
         "    MOVx_REG(xRBP, xR11);\n", "call_n RSP/RBP reload"),
        ("    fpu_popcache(dyn, ninst, x3, 1);\n    LDRx_U12(xFlags, xEmu, offsetof(x64emu_t, eflags));\n    NATIVE_RESTORE_X87PC();",
         "    fpu_popcache(dyn, ninst, x3, 1);\n    NATIVE_RESTORE_X87PC();", "call_n eflags reload")):
    if code.count(old) != 1:
        sys.exit("apply.py: %s not found once in dynarec_arm64_helper.c" % what)
    code = code.replace(old, new, 1)
hc.write_text(code)


# 0010: x86 zeroing idioms (PXOR/XORPS/XORPD x,x and friends) were translated
# to EOR Vd, Vn, Vn. x86 cores treat "xor x,x" as dependency-free; on arm64
# EOR of a register with itself still waits for the register's old value. In a
# loop like "pxor xmm0,xmm0; cvtsi2sd ...; call exp" that old value is the
# previous exp() result, so every math call waited for the one before.
# EOR of a register with itself is always 0: emit MOVI Vd, #0 instead (same
# size), which no instruction has to wait for.
em = arm / "arm64_emitter.h"
code = em.read_text()
OLD_EORQ = "#define VEORQ(Vd, Vn, Vm)                   EMIT(VLOGIC_gen(1, 0b00, Vm, Vn, Vd))"
NEW_EORQ = ("// Pons 0010: x ^ x is 0; MOVI does not wait for the old value, EOR does.\n"
            "#define VEORQ(Vd, Vn, Vm)                   do { if ((Vn) == (Vm)) MOVIQ_64(Vd, 0); else EMIT(VLOGIC_gen(1, 0b00, Vm, Vn, Vd)); } while (0)")
if code.count(OLD_EORQ) != 1:
    sys.exit("apply.py: VEORQ definition not found once in arm64_emitter.h")
code = code.replace(OLD_EORQ, NEW_EORQ, 1)
em.write_text(code)


# 0011: the other x86 idioms whose result does not depend on the register's old
# value, translated to arm64 instructions that still wait for it:
#   pcmpeqb/w/d/q x,x  "all ones"   CMEQ Vd, Vn, Vn  -> MOVI Vd, #-1
#   psubb/w/d/q x,x    "zero"       SUB  Vd, Vn, Vn  -> MOVI Vd, #0
#   pxor mm,mm (MMX)   "zero"       EOR  Dd, Dn, Dn  -> MOVI Dd, #0
#   xor r32/r64, same  "zero"       EOR  Wd, Wn, Wn  -> MOV Wd, WZR
# Integer compares only: a value is always equal to itself. Same sizes, so the
# dynarec passes stay consistent. The AVX forms use the same macros.
code = em.read_text()
for old, new in (
    ("#define VEOR(Vd, Vn, Vm)                    EMIT(VLOGIC_gen(0, 0b00, Vm, Vn, Vd))",
     "#define VEOR(Vd, Vn, Vm)                    do { if ((Vn) == (Vm)) MOVI_64(Vd, 0); else EMIT(VLOGIC_gen(0, 0b00, Vm, Vn, Vd)); } while (0)"),
    *[("#define VSUBQ_%d(Vd, Vn, Vm)%s EMIT(ADDSUB_vector(1, 1, 0b%s, Vm, Vn, Vd))" % (b, pad, sz),
       "#define VSUBQ_%d(Vd, Vn, Vm)%s do { if ((Vn) == (Vm)) MOVIQ_64(Vd, 0); else EMIT(ADDSUB_vector(1, 1, 0b%s, Vm, Vn, Vd)); } while (0)" % (b, pad, sz))
      for b, sz, pad in ((8, "00", " " * 16), (16, "01", " " * 15), (32, "10", " " * 15), (64, "11", " " * 15))],
    *[("#define VCMEQ%s_%d(Rd, Rn, Rm)%s EMIT(CMEQ_vector(%d, 1, 0b%s, Rm, Rn, Rd))" % (q, b, pad, int(q == "Q"), sz),
       "#define VCMEQ%s_%d(Rd, Rn, Rm)%s do { if ((Rn) == (Rm)) MOVI%s_64(Rd, 0xff); else EMIT(CMEQ_vector(%d, 1, 0b%s, Rm, Rn, Rd)); } while (0)" % (q, b, pad, q, int(q == "Q"), sz))
      for q, b, sz, pad in (("", 8, "00", " " * 8), ("", 16, "01", " " * 7), ("", 32, "10", " " * 7),
                            ("Q", 8, "00", " " * 7), ("Q", 16, "01", " " * 6), ("Q", 32, "10", " " * 6), ("Q", 64, "11", " " * 6))]):
    if code.count(old) != 1:
        sys.exit("apply.py: %r not found once in arm64_emitter.h" % old)
    code = code.replace(old, new, 1)
em.write_text(code)
lg = arm / "dynarec_arm64_emit_logic.c"
code = lg.read_text()
OLD_XOR = "    EORxw_REG(s1, s1, s2);\n    IFX(X_PEND) {\n        STRxw_U12(s1, xEmu, offsetof(x64emu_t, res));"
NEW_XOR = ("    // Pons 0011: xor r,r is 0, MOV from WZR does not wait for r.\n"
           "    if (s1 == s2) MOVxw_REG(s1, xZR); else EORxw_REG(s1, s1, s2);\n"
           "    IFX(X_PEND) {\n        STRxw_U12(s1, xEmu, offsetof(x64emu_t, res));")
if code.count(OLD_XOR) != 1:
    sys.exit("apply.py: emit_xor32 EOR not found once in dynarec_arm64_emit_logic.c")
lg.write_text(code.replace(OLD_XOR, NEW_XOR, 1))

print("pons box64 patches applied")
