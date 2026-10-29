# Pons

*Pons* is Latin for *bridge*: a bridge between PC games and ARM devices.

Pons plays the **Linux builds of your Steam games** on an Android device with the
device's own GPU. No root, no streaming, no cloud. It runs a full Debian (arm64) inside
an ordinary app, gives it an open-source Vulkan/OpenGL driver for Adreno GPUs, and runs
x86 game binaries through Box64. Games are installed and launched by Valve's native arm64
Steam client, so your library, saves and DRM work the way they do on a Linux PC.

> **Status: experimental.** Developed and measured on a OnePlus Pad 3 (Snapdragon 8 Elite,
> Adreno 830, Android 16). Expect rough edges; measurements below are real runs on that device.

The goal is bigger than one device or one game: make ARM a first-class place to play PC
games, with every layer measured and every improvement published.

## What is new here

Pons stands on proot, Box64, Mesa and Debian. On top of them it adds:

| Idea | What it does | Measured effect |
| --- | --- | --- |
| **Zero-upload frames** | Mesa writes each finished frame straight into Android graphics buffers (`AHardwareBuffer`) that the app samples as textures. | Late frames (> 50 ms) 31/min → 0/min; free memory 411 → 1239 MB |
| **File fast path** | An in-process path translator (`LD_PRELOAD`) marks file syscalls so proot's seccomp filter lets them through without a ptrace stop. | `stat` 161 µs → 2.1 µs, `open`+`close` 231 µs → 6.1 µs |
| **Paced display** | The app redraws only when a new frame arrives and asks the panel for 60 Hz, so 30 fps frames last exactly two refreshes. | ~5× fewer redraws at 30 fps |
| **Async software present** | Mesa's present waits for the GPU on its queue thread instead of the game's render thread. | CK3 map 21 → 37 fps uncapped |
| **x86 without binfmt** | A proot patch treats x86 ELF files like scripts whose interpreter is Box64. | Steam starts x86 games as usual |
| **Translation cache that survives a kill** | Box64 saves its DynaCache while running, not only on a clean exit. | Android stops games with a kill; the cache now persists |
| **Quiet Steam** | Steam's web helper (Chromium) is frozen while a game runs. | ~470 MB more free memory, 3 °C cooler under load |
| **Kernel heap under proot** | proot patch 0004 (`PROOT_NO_HEAP`) leaves `brk` to the kernel instead of proot's emulated heap, which corrupted malloc after heap trims and stopped every `brk`. | Simulated game load: `brk` stops 1101 → 0, read phase 997 → 173 ms, no more malloc corruption |
| **Thermal governor** | Polls Android's thermal headroom and lowers the frame cap (30 → 24 → 20) before the device throttles, ignoring the first 45 s of loading; Mesa re-reads the cap every frame. | CK3 at top speed for 3 min: 30 fps, skin 43 °C, Android thermal status 0 |
| **GPU frame copy** | Imports the Android buffers into Vulkan and copies each frame on the GPU instead of a 7 MB CPU `memcpy` (on by default; `PONS_GPU_COPY=0` turns it off). | CK3 map, 30 fps, no late frames in 3/3 runs; GPU max 60–62 °C and skin 43 °C, against 68 °C and 47 °C in the one CPU-copy run (a second CPU-copy run was stopped for low memory) |
| **Hands-off benchmarks and profiler** | Profiles, a layer suite, a game benchmark and an automatic bottleneck profiler, all over adb, no touch input. | Every number in this file |

## How it works

```
Android app (Kotlin)
 ├─ LinuxSessionService     foreground service: keeps Linux running and online
 ├─ DisplayActivity         draws game frames, touch acts as a mouse
 │   ├─ AhbFrames           allocates AHardwareBuffers, hands their dma-buf fds to Mesa
 │   └─ FramebufferRenderer samples them as textures, upscales (Catmull-Rom + sharpen)
 └─ proot (patched)         runs Debian without root
     └─ Debian trixie arm64
         ├─ Xvfb            the Linux desktop (fallback when no game presents)
         ├─ pons-input      turns touch into X11 mouse/keys (XTEST)
         ├─ Mesa main       Turnip (Vulkan, Android KGSL backend) + Zink (OpenGL)
         ├─ Box64           x86_64 → arm64 translation
         ├─ pons-fastpath   file calls without proot stops
         └─ Steam (arm64)   installs your games and signs them in
```

One frame, end to end:

1. The game (x86_64) runs under Box64; its Vulkan calls go to Turnip on the GPU.
2. Mesa's present copies the finished frame into one of three Android buffers
   (`tools/mesa/patches/wsi_pons_fb.h`) and publishes its index in a 64-byte header,
   together with counters of frames that arrived more than 50/100 ms late.
3. On the next vsync the app samples that buffer as a texture and scales it to the panel.

One file call, end to end (`tools/compat/pons-fastpath.c`):

1. The game calls `stat("/usr/share/game/data.pak")`.
2. The preloaded library resolves the guest path the way proot would (rootfs prefix,
   `/tmp` and `/dev/shm` binds, symlinks resolved inside the guest, fake root owner).
3. It issues the syscall with a 64-bit marker in the unused sixth argument register.
   Our proot patch (`tools/proot/0003-fastpath-marker.patch`) allows marked calls in its
   seccomp filter, so the kernel serves them directly. Anything unusual (`/proc`, `/dev`,
   unknown directory descriptors) still goes through proot unchanged.

`tools/dev/pp-fastpath-check.sh` runs ~90 tricky file calls with and without the
fast path and diffs the results; they must be identical.

## Games are profiles

A game is a small text file, `tools/input/games/<id>.conf`: Steam app id, folder,
program, frame cap, the log line that means "ready", how to reset to a known save,
and benchmark scenes. `pons-game <id>` launches any profile; `pp-bench.sh <id>:<scene>`
benchmarks it. Adding a game means adding a profile, not code.

## Measuring

| Script | Needs the screen | What it measures |
| --- | --- | --- |
| `tools/dev/pp-suite.sh` | no | Box64 speed vs native per workload, cost of a call into native libraries, syscall cost with/without proot and the fast path |
| `tools/dev/pp-bench.sh <game>:<scene> [s]` | yes | fps, late frames, CPU/GPU/skin temperature (thermal HAL), Android thermal status, free memory, CPU time spent loading per process |
| `tools/dev/pp-bench-loop.sh <n> ...` | yes | the same, repeated |
| `tools/dev/pp-fastpath-check.sh` | no | correctness of the file fast path |
| `tools/dev/pp-prof.sh <label> [cmd]` | no | **automatic bottleneck ranking** for any workload (default: `loadsim`) |

### The bottleneck profiler

`pp-prof.sh` needs no game, no Steam and no app screen, and takes under a minute:

1. it measures what one proot stop and one Box64 native call cost on the device right now;
2. it runs the workload with proot's per-syscall stop counters (`PROOT_SYSCALL_STATS`) and
   Box64's per-function native-call counters (`PONS_CALLSTATS`) on;
3. it samples every thread's user/system CPU, page faults and context switches from
   *outside* proot (so sampling adds no stops), plus device temperatures;
4. it turns counts into estimated seconds and prints the biggest bottleneck first.

The default workload, `loadsim`, does what a game does while loading: it walks a
directory tree, stats and reads every file, and parses the bytes on four threads with
math calls. It is built for x86_64 (through Box64) and arm64 to compare the two.

Example (x86_64 `loadsim` over `/usr`, 14 207 entries, 183 MB):

| | proot stops | wall | scan phase | read phase |
| --- | --- | --- | --- | --- |
| proot only | 46 662 | 8.3 s | 4 739 ms | 2 983 ms |
| + file fast path | 2 815 (1 101 of them `brk`) | 1.5 s | 112 ms | 997 ms |
| + file fast path + kernel heap (patch 0004) | 1 714 (Box64 loading its libraries) | **0.7 s** | 96 ms | 173 ms |

The profiler's per-stop cost comes from a `stat` microbenchmark and over-estimates the
total (it reported 139 % of wall on the first row); read it as a ranking, not a budget.

The game benchmark cools the device down first, restores the same save, waits for the
game's own "ready" log line, sends scripted keys if the scene needs them, then samples
every second on the device.

## Results (OnePlus Pad 3, Snapdragon 8 Elite, Adreno 830)

| Test | Result |
| --- | --- |
| Vulkan driver | Mesa 27.0-devel Turnip: "Adreno (TM) 830v1", Vulkan 1.4 |
| OpenGL | Zink on Turnip: OpenGL 4.6 |
| vkcube 1600×1132, X server present → Pons direct present | 74 → 352 fps |
| Neverball 1600×1132, native arm64 / x86_64 via Box64 | 78–95 / 80–149 fps (GPU-bound) |
| Box64 vs native, integer / pathfinding / matrix workloads | 72–97 % |
| Box64 vs native, math library calls (transbench libm) | 14 % → **75 %** with patch 0010 (see below) |
| Translation inflation (arm64 instructions via Box64 / native), upstream → Pons patches 0005-0007 | matrix 1.98 → **1.50**, pathfinding 1.64 → 1.60, economy 0.89 → 0.88 (qemu instruction count) |
| Box64 before → after patches 0005-0007, on the tablet (median of 7 interleaved runs, cores 6-7) | matrix 22.6 → **20.1 ms (−11 %)**, pathfinding 116.8 → **104.6 ms (−10 %)**, economy 62.9 → **54.9 ms (−13 %)**, libm 248 → 251 ms (unchanged) |
| int→double conversion loop, Box64 upstream → patched | 16.9 → 5.9 ns |

**Crusader Kings III** (x86_64 Linux build, 1600×1132, 30 fps cap):

| Scene | fps | late frames > 50 ms | skin temperature |
| --- | --- | --- | --- |
| map, paused | 30.0 | 0 / min | ≤ 55 °C |
| observer mode, speed 5 (heaviest) | 30.0 | 0 / min | 48 °C |

Loading the save, from app start to the map (Steam sign-in included):

| | wall | CK3 CPU (user + system) | proot CPU (user + system) |
| --- | --- | --- | --- |
| before the fast path | ~130 s | 235 s + 150 s | 13 s + 75 s |
| fast path + kernel heap | **75 s** | 142 s + 60 s | 3 s + 16 s |

The game itself reaches "In Game" about 45 s after it starts.

## Open problems

- **Memory.** CK3 + Box64 + Steam use about 7 GB of a 16 GB device. With other apps
  open, the device swapped until it stopped responding (twice, during testing). Pons now
  closes other apps' background processes before a game, stops the game when free
  memory stays under 600 MB (warning at 1 GB), and marks Steam's idle web helper as the
  first thing the kernel may kill.

- **proot's emulated heap** (found and fixed): it resizes an `mmap`ed region in place with
  `mremap`; after glibc trimmed the heap, growth diverged from real `brk` and malloc
  reported corruption (`ls -R`, `loadsim`). Patch 0004 lets the kernel handle `brk`.
- **Math calls in Box64 (solved).** Math-heavy x86 code ran at 9-14 % of native. The
  cause was not the native-call bridge but a false dependency: x86 zeroing idioms
  (`pxor xmm0,xmm0`) were translated to `EOR v0,v0,v0`, which on arm64 waits for the
  old value of v0, i.e. the previous call's result, so no two math calls ever
  overlapped. Patch 0010 emits `MOVI v0.2d,#0` instead: independent `exp` calls
  26.6 → 6.2 ns (native 3.7), transbench libm 214 → 39 ms (75 % of native), threads
  −28 %. Found with `tools/transbench/bridgeparts.c` (a native replica of the
  bridge, piece by piece) and Box64's code dump, after two plausible fixes (0008,
  0009: no argument reload, RSP kept in registers) measured as no gain.
  Patch 0011 does the same for the other idioms whose result ignores the old value:
  `pcmpeqd x,x` (all ones), `psubd x,x`, MMX `pxor`, and `xor eax,eax` on integer
  registers (now `MOV w,wzr`). `tools/transbench/idiombench.c` measures each one
  (ns per iteration, pp10 → pp11 on the tablet): pcmpeqd 17.2 → 2.2, psubd 21.6 → 2.3,
  xor32 10.8 → 1.6. Across the transbench workloads: economy −5 %, threads −3 %,
  pathfinding −2 %, libm and matrix unchanged (same instruction count; matrix is
  bimodal from run to run on both builds); Box64's own tests unchanged (34/36).
- **Copy into the frame buffer.** Mesa still copies each frame (7 MB) into the Android
  buffer; rendering directly into it would remove the copy.
- **Thermal governor.** Use Android's thermal headroom API to lower the frame cap
  before the device throttles.
- **Windows games.** Most of Steam is Windows-only; that needs Wine + DXVK over Box64.

## Building

Everything builds on an x86_64 Linux machine with Docker, JDK 17 and the Android SDK/NDK.

| Part | Script | Output |
| --- | --- | --- |
| Mesa (Turnip + Zink) | `tools/mesa/build.sh git <label>` (in its Docker image) | `mesa-<label>-pons.tar.xz` |
| Box64 | `tools/box64/build.sh <tag>` (in its Docker image) | `box64-<tag>-pons.tar.xz` |
| proot (patched) | `tools/proot/build.sh` (NDK) | `libproot.so`, `libproot-loader.so` |
| helpers, benchmarks, profiles | `tools/input/build.sh <version>` | `pons-tools-<v>.tar.xz` |
| app | `gradle assembleDebug` | APK |

Components are downloaded by the app on first start and checked with SHA-256
(`app/src/main/java/io/github/pons/linux/Component.kt`). Release builds fetch them from
this repository's releases; a debug build can use a local mirror with
`pons.devMirror=http://<host>:<port>/` in `local.properties`.

`tools/dev/pp-run.sh` runs a command inside the app's Debian over adb (debug builds only).

## License

Pons is licensed under the GNU GPL v3 (see `LICENSE`). It bundles or downloads
third-party software under its own licenses; see `NOTICE.md`.

Steam is a trademark of Valve Corporation. Pons is not affiliated with Valve; it
downloads Valve's client from Valve's servers on the user's device and never redistributes it.
Crusader Kings III is a trademark of Paradox Interactive; it is used here only as a test case.
