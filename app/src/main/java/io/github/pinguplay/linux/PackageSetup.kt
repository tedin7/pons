package io.github.pinguplay.linux

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * Debian packages the environment needs on top of the slim rootfs, installed with apt
 * on first start. Bump [VERSION] whenever [PACKAGES] changes.
 */
class PackageSetup(context: Context, private val paths: LinuxPaths) {

    private val marker = File(context.filesDir, "components/packages")

    fun isDone(): Boolean = marker.exists() && marker.readText() == VERSION

    fun run(onLine: (String) -> Unit) {
        val script = """
            set -e
            export DEBIAN_FRONTEND=noninteractive
            APT="apt-get -q -o APT::Sandbox::User=root -o Dpkg::Use-Pty=0"
            ${'$'}APT update
            ${'$'}APT install -y --no-install-recommends ${PACKAGES.joinToString(" ")}
            ${'$'}APT clean
        """.trimIndent()
        val process = ProotCommand.build(paths, script).start()
        process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
        val code = process.waitFor()
        if (code != 0) throw IOException("Installazione pacchetti fallita (codice $code)")
        marker.writeText(VERSION)
    }

    companion object {
        const val VERSION = "1"
        val PACKAGES = listOf(
            // Screen, GPU and test tools.
            "xvfb", "xz-utils", "ca-certificates", "curl", "unzip", "libgl1", "libegl1",
            "libglx-mesa0", "libvulkan1", "vulkan-tools", "mesa-utils", "x11-utils",
            "fonts-dejavu-core",
            // Libraries the arm64 Steam client and its web view link against.
            "libxtst6", "libxrandr2", "libxcursor1", "libxi6", "libxinerama1", "libxcomposite1",
            "libxdamage1", "libxrender1", "libxfixes3", "libxss1", "libxkbcommon0",
            "libxshmfence1", "libglib2.0-0t64", "libgtk2.0-0t64", "libgdk-pixbuf-2.0-0",
            "libpipewire-0.3-0t64", "libopenal1", "libpulse0", "libnm0", "libudev1",
            "libibus-1.0-5", "libsdl3-0", "libnss3", "libnspr4", "libatk1.0-0t64",
            "libatk-bridge2.0-0t64", "libcups2t64", "libdrm2", "libgbm1", "libasound2t64",
            "libdbus-1-3",
        )
    }
}
