package io.github.pinguplay.linux

/** Builds the proot invocation that runs a shell script inside Debian. */
object ProotCommand {

    const val DISPLAY = ":0"
    const val X86_RUNNER = "/usr/local/bin/box64"

    /** GPU and translator settings every Linux program gets. */
    private val GRAPHICS_ENV = listOf(
        "DISPLAY=$DISPLAY",
        "XDG_RUNTIME_DIR=/tmp",
        "VK_ICD_FILENAMES=/opt/pinguplay/mesa/share/vulkan/icd.d/freedreno_icd.aarch64.json",
        "LD_LIBRARY_PATH=/opt/pinguplay/mesa/lib",
        "LIBGL_DRIVERS_PATH=/opt/pinguplay/mesa/lib/dri",
        "__GLX_VENDOR_LIBRARY_NAME=mesa",
        "__EGL_VENDOR_LIBRARY_FILENAMES=/opt/pinguplay/mesa/share/glvnd/egl_vendor.d/50_mesa.json",
        "MESA_LOADER_DRIVER_OVERRIDE=zink",
        "TU_DEBUG=noconform",
        "BOX64_LOG=0",
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
            "PATH=/opt/pinguplay/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
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
