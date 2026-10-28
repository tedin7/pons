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

print("pinguplay box64 patches applied")
