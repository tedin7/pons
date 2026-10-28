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
| **Bigger heap steps** | proot stops every `brk`; glibc grows the heap 64 MB at a time (`glibc.malloc.top_pad`). | Simulated game load: `brk` stops 1101 → 44, file-read phase 997 → 158 ms |
| **Thermal governor** | Polls Android's thermal headroom and lowers the frame cap (30 → 24 → 20) before the device throttles; Mesa re-reads the cap every frame. | Not yet measured on a long session |
| **GPU frame copy** (experimental, `PONS_GPU_COPY=1`) | Imports the Android buffers into Vulkan and copies the frame on the GPU instead of a 7 MB CPU `memcpy`. | Not yet tested on the device |
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

| | proot stops | wall | read phase |
| --- | --- | --- | --- |
| proot only | tens of thousands | — | — |
| + file fast path | 2 815 (1 101 of them `brk`) | 1.5 s | 997 ms |
| + 64 MB heap steps | 1 758 (mostly Box64 loading its libraries) | 0.8 s | 158 ms |

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
| Box64 vs native, math library calls | 9 % (each native call costs ~10 ns of bridge; see below) |
| int→double conversion loop, Box64 upstream → patched | 16.9 → 5.9 ns |

**Crusader Kings III** (x86_64 Linux build, 1600×1132, 30 fps cap):

| Scene | fps | late frames > 50 ms | skin temperature |
| --- | --- | --- | --- |
| map, paused | 30.0 | 0 / min | ≤ 55 °C |
| observer mode, speed 5 (heaviest) | 30.0 | 0 / min | 48 °C |

Loading a save takes ~130 s. Measured per process: the game 235 s user + 150 s system
CPU, proot 13 s + 75 s. The file fast path targets exactly that system time.

## Open problems

- **proot and heap trimming.** Freeing large heap blocks with the fast path loaded made
  glibc trim the heap and then report corruption under proot's emulated `brk`; the fast
  path now uses `mmap` for its own buffers and the large heap pad also avoids trims.
  The root cause in proot is not yet understood.
- **Native-call bridge in Box64.** A call from x86 code into a native library costs
  ~10 ns of register spills and stack bookkeeping, and consecutive calls can no longer
  overlap (`exp`: 4 ns native, 23 ns translated). Box64 patch 0003 counts native calls
  per function (`PONS_CALLSTATS=<file>`) to find out how much real games pay.
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
