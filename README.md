# Pinguplay

Play your Steam library's **Linux** games on an Android device, using the device's real GPU.
No root, no streaming.

Pinguplay runs a full Debian (arm64) inside the app, gives it an open-source Vulkan/OpenGL
driver for Adreno GPUs, and runs PC (x86) game binaries through Box64. Games are installed
and launched by Valve's native arm64 Steam client, so your library, saves and DRM work the
way they do on a Linux PC.

> Status: early prototype. Tested on a OnePlus Pad 3 (Snapdragon 8 Elite, Adreno 830).

## How it works

```
Android app (Kotlin)
 ├─ LinuxSessionService   foreground service: keeps Linux running and online
 ├─ DisplayActivity       draws game frames, touch acts as a mouse
 │   ├─ AhbFrames         allocates Android graphics buffers (AHardwareBuffer) and
 │   │                    hands their dma-buf fds to Mesa over a unix socket
 │   └─ FramebufferRenderer  samples them as GL textures (no upload), upscales with
 │                        Catmull-Rom + sharpening; redraws only on a new frame,
 │                        panel at 60 Hz so 30 fps frames last exactly 2 refreshes
 └─ proot (patched)       runs Debian without root
     └─ Debian trixie arm64
         ├─ Xvfb                  the Linux desktop (fallback when no game presents)
         ├─ pinguplay-input       turns touch into X11 mouse/keys (XTEST)
         ├─ Mesa main Turnip+Zink Vulkan and OpenGL on Adreno (Android KGSL backend);
         │                        full-screen frames go straight into the app's buffers
         ├─ Box64                 runs x86/x86_64 Linux programs on arm64; saves its
         │                        translation cache while running (games get killed)
         └─ Steam (arm64 beta)    signs games in; its web UI is frozen during play
```

One game frame, end to end:

1. The game (x86_64) runs under Box64; its Vulkan calls go to Turnip on the GPU.
2. Mesa's present copies the finished frame into one of three Android buffers
   (`tools/mesa/patches/wsi_pinguplay_fb.h`, header version 2) and publishes its index
   in a 64-byte header file, with counters of frames that came more than 50/100 ms late.
3. The app sees the new sequence number on the next vsync, samples that buffer as a
   texture and scales it to the panel. No pixel crosses the CPU on the app side.

- **x86 games without binfmt.** Linux normally needs root to hand foreign binaries to a
  translator. Our proot patch (`tools/proot/0001-x86-runner.patch`) treats an x86 ELF like
  a script whose interpreter is Box64, so Steam can start games as usual.
- **No executables in app storage.** Android only lets apps execute files from their
  native library folder, so proot ships as `libproot.so`; everything else runs inside it.
- **Small APK.** The Linux parts are downloaded on first start and checked with SHA-256
  (`linux/Component.kt`). The first start also installs a few Debian packages with apt.
  Plan for about 150 MB, plus about 500 MB for Steam. Use Wi-Fi.

## Building

Everything builds on an x86_64 Linux machine with Docker, JDK 17 and the Android SDK.

| Part | Script | Output |
| --- | --- | --- |
| Mesa (Turnip + Zink) | `tools/mesa/build.sh <version> <sha256>` (in its Docker image) | `mesa-<v>-pinguplay.tar.xz` |
| Box64 | `tools/box64/build.sh <tag>` (in its Docker image) | `box64-<tag>-pinguplay.tar.xz` |
| proot (patched) | `tools/proot/build.sh` (needs the NDK) | `libproot.so`, `libproot-loader.so` |
| helpers | `tools/input/build.sh <version>` | `pinguplay-tools-<v>.tar.xz` |
| app | `gradle assembleDebug` | APK |

Publish the `.tar.xz` files where `COMPONENTS_BASE_URL` (in `app/build.gradle.kts`) points,
and update the sizes and checksums in `linux/Component.kt`.

`tools/dev/pp-run.sh` runs a command inside the app's Debian over adb (debug builds only).

## License

Pinguplay is licensed under the GNU GPL v3 (see `LICENSE`). It bundles or downloads
third-party software under its own licenses; see `NOTICE.md`.

Steam is a trademark of Valve Corporation. Pinguplay is not affiliated with Valve; it
downloads Valve's client from Valve's servers on the user's device and never redistributes it.

## Measurements (OnePlus Pad 3, Snapdragon 8 Elite, Adreno 830)

| Test | Result |
| --- | --- |
| Vulkan driver | Mesa 27.0-devel Turnip: "Adreno (TM) 830v1", Vulkan 1.4 |
| OpenGL | Zink on Turnip: OpenGL 4.6 |
| vkcube 1600×1132, present through the X server | 74 fps |
| vkcube 1600×1132, Pinguplay direct present | 352 fps |
| Neverball, native arm64, 1600×1132 | 78–95 fps |
| Neverball, x86_64 build through Box64, 1600×1132 | 80–149 fps (same as native: GPU-bound) |
| transbench, x86 through Box64 vs native (geomean) | 49% default, 52% tuned |
| loop with int→double conversion, Box64 upstream → patched | 16.9 → 5.9 ns |

Running x86 games outside Steam can hit gaps in Box64's native wrappers; for
Neverball we used Debian's x86 `libjpeg62-turbo` and `libpng16` (with
`BOX64_EMULATED_LIBS=libpng16.so.16`, the wrapper lacks `png_set_longjmp_fn`).

### Crusader Kings III (first run, 2026-10-28)

The Linux x86_64 build runs through Box64 inside Pinguplay, signed in through the arm64
Steam client: main menu at 31 fps at 1600×1132 (Vulkan on Turnip, Adreno 830).
During loading and at the menu the tablet's skin reached 54 °C (Android thermal
status 2 of 6, throttling starts at 60 °C), so a 30 fps cap is recommended.

### Profiling CK3 and async present (2026-10-28)

Profiled on the tablet with `tools/dev/pp-monitor-host.sh` (frames from the present
buffer, per-thread CPU from `top -H`, temperatures from `/sys/class/thermal`):
at 21 fps no game thread was saturated (main 40 %, render 30 %). Mesa's software
present waited for the GPU inside `vkQueuePresentKHR`, so CPU and GPU took turns.
Mesa pp5 moves that wait to the X11 queue thread (`wsi_pinguplay_async.h`):

| CK3 1600×1132, uncapped | pp4 (sync) | pp5 (async) |
| --- | --- | --- |
| main menu | 31 fps | 60 fps (game's own cap) |
| map, paused, event open | 21 fps | 37 fps |
| map, running at speed 5 | — | 41–43 fps |

Uncapped, CPU and GPU sit at 75–77 °C; the 30 fps default of `pinguplay-ck3` is
reached with headroom. At game start, speed 5 advances roughly a month of game time
in a few seconds.

### Zero-upload frames and a hands-off benchmark (2026-10-28)

`tools/dev/pp-bench.sh <label> [seconds] [program]` measures one run over adb without
touching the tablet: it waits for the tablet to cool, restores a pinned CK3 save,
starts the game, waits for the game's own "In Game" log line, then samples every
second on the tablet (frames, late frames, thermal HAL temperatures, free memory),
snapshots `top` halfway, screenshots the end and appends a row to a results table.
`pp-bench-loop.sh <runs> ...` repeats it. The app must stay in front (Android cuts
the network of background apps); the script gives up after 15 s otherwise.

| CK3 map, 30 fps cap, 90 s | file + upload (pp5) | zero-upload (pp7) |
| --- | --- | --- |
| average fps | 29.3 | 30.0 |
| frames late > 50 ms, per minute | 31.2 | 0.0 |
| frames late > 100 ms, per minute | 4.1 | 0.0 |
| lowest free memory | 411 MB | 1239 MB |

With Steam's web helper frozen during play, the map run kept 1704 MB free. Observer
mode at speed 5 (the heaviest case): 30.0 fps, 0.4 late frames per minute, skin
54 °C (it reached 62 °C before the freeze). Loading the save takes 128–145 s, the same
with or without Box64's translation cache, so translation is not what loading waits on.
