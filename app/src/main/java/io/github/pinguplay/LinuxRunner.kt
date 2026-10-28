package io.github.pinguplay

import android.content.Context
import java.io.File

/** Runs commands inside the Debian rootfs through proot (no root needed). */
class LinuxRunner(context: Context, private val rootfsDir: File) {

    private val libDir = context.applicationInfo.nativeLibraryDir
    private val tmpDir = File(context.cacheDir, "proot-tmp").apply { mkdirs() }

    fun run(script: String, onLine: (String) -> Unit): Int {
        val command = listOf(
            "$libDir/libproot.so",
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "-r", rootfsDir.path,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${tmpDir.path}:/tmp",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "/bin/sh", "-c", script,
        )
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply {
                environment()["PROOT_LOADER"] = "$libDir/libproot-loader.so"
                environment()["PROOT_TMP_DIR"] = tmpDir.path
                environment()["LD_LIBRARY_PATH"] = libDir
            }
            .start()
        process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
        return process.waitFor()
    }
}
