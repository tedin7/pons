package io.github.pons.linux

/** Builds the proot invocation that runs a shell script inside Debian. */
object ProotCommand {

    const val DISPLAY = ":0"
    const val X86_RUNNER = "/usr/local/bin/box64"

    /** GPU and translator settings every Linux program gets. */
    private val GRAPHICS_ENV = listOf(
        "DISPLAY=$DISPLAY",
        "XDG_RUNTIME_DIR=/tmp",
        "VK_ICD_FILENAMES=/opt/pons/mesa/share/vulkan/icd.d/freedreno_icd.aarch64.json",
        "LD_LIBRARY_PATH=/opt/pons/mesa/lib",
        "LIBGL_DRIVERS_PATH=/opt/pons/mesa/lib/dri",
        "__GLX_VENDOR_LIBRARY_NAME=mesa",
        "__EGL_VENDOR_LIBRARY_FILENAMES=/opt/pons/mesa/share/glvnd/egl_vendor.d/50_mesa.json",
        "MESA_LOADER_DRIVER_OVERRIDE=zink",
        // Xvfb has no DRI3; without this Mesa refuses Zink and falls back to software GL.
        "LIBGL_KOPPER_DRI2=1",
        "TU_DEBUG=noconform",
        // Xvfb has no DRI3, so Vulkan presents through the CPU path; our Mesa patch
        // then hands finished frames straight to the app, skipping the X server.
        "MESA_VK_WSI_DEBUG=sw",
        "PONS_PRESENT=/tmp/pons-present",
        // Ask the app for Android graphics buffers to write frames into (no upload).
        "PONS_AHB=1",
        // Keep Box64's translations across starts even though games get killed.
        "PONS_DYNACACHE_FLUSH=/tmp/pons-dynacache-flush",
        "PONS_DYNACACHE_PERIOD=90",
        // Above the panel's useful rate frames only cost heat the CPU needs.
        "PONS_MAX_FPS=120",
        "BOX64_LOG=0",
        // Started by LinuxSession for every session.
        "PULSE_SERVER=unix:/tmp/pulse-native",
        "DBUS_SESSION_BUS_ADDRESS=unix:path=/tmp/dbus-session",
    )

    fun build(paths: LinuxPaths, script: String): ProcessBuilder {
        val command = listOf(
            "${paths.libDir}/libproot.so",
            "--kill-on-exit",
            "--link2symlink",
            "--sysvipc",
            "-0",
            "-r", paths.rootfs.path,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${paths.tmp.path}:/tmp",
            "-b", "${paths.shm.path}:/dev/shm",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "USER=root",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "PATH=/opt/pons/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games",
            // Where the guest really lives, for libpons-fastpath (file calls
            // without proot stops); programs opt in by preloading it.
            "PONS_FASTPATH_ROOT=${paths.rootfs.path}",
            "PONS_FASTPATH_BINDS=/tmp=${paths.tmp.path}:/dev/shm=${paths.shm.path}",
            // proot stops every brk (heap growth): grow the heap 64 MB at a time.
            // Measured on a simulated game load: 1101 -> 44 stops, read phase 6x faster.
            "GLIBC_TUNABLES=glibc.malloc.top_pad=67108864",
        ) + GRAPHICS_ENV + listOf("/bin/sh", "-c", script)

        return ProcessBuilder(command).redirectErrorStream(true).apply {
            environment()["PROOT_LOADER"] = "${paths.libDir}/libproot-loader.so"
            environment()["PROOT_TMP_DIR"] = paths.tmp.path
            environment()["LD_LIBRARY_PATH"] = paths.libDir
            // Our proot patch hands x86/x86_64 programs to Box64 (no binfmt without root).
            environment()["PROOT_X86_RUNNER"] = X86_RUNNER
        }
    }
}
