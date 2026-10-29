package io.github.pons.linux

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * Debian packages installed with apt on top of the slim rootfs, in groups so a
 * user only downloads what they use: the screen first, Steam's libraries only
 * when they open Steam. Bump a group's version whenever its list changes.
 */
class PackageSetup(context: Context, private val paths: LinuxPaths) {

    enum class Group(val version: String, val packages: List<String>) {
        SCREEN("2", listOf(
            "xvfb", "xz-utils", "ca-certificates", "curl", "unzip", "libgl1", "libegl1",
            "libglx-mesa0", "libvulkan1", "vulkan-tools", "x11-utils", "libxtst6",
            "fonts-dejavu-core",
        )),
        // Sound (PulseAudio piped to the app) and a session bus for desktop programs.
        AUDIO("1", listOf("pulseaudio", "pulseaudio-utils", "dbus-daemon", "dbus-bin")),
        BENCHMARK("1", listOf("vkmark", "glmark2-x11", "mesa-utils", "mangohud")),
        // Libraries the arm64 Steam client and its web view link against.
        STEAM("3", listOf(
            "libxrandr2", "libxcursor1", "libxi6", "libxinerama1", "libxcomposite1",
            "libxdamage1", "libxrender1", "libxfixes3", "libxss1", "libxkbcommon0",
            "libxshmfence1", "libglib2.0-0t64", "libgtk2.0-0t64", "libgdk-pixbuf-2.0-0",
            "libpipewire-0.3-0t64", "libopenal1", "libpulse0", "libnm0", "libudev1",
            "libibus-1.0-5", "libsdl3-0", "libnss3", "libnspr4", "libatk1.0-0t64",
            "libatk-bridge2.0-0t64", "libcups2t64", "libdrm2", "libgbm1", "libasound2t64",
            "libdbus-1-3", "mangohud", "libva2", "libvdpau1", "libavcodec61", "libavutil59",
            "lsof", "pciutils",
        )),
    }

    private val markerDir = File(context.filesDir, "components")

    fun isDone(group: Group): Boolean =
        marker(group).let { it.exists() && it.readText() == group.version }

    /** Installs [group]; the package lists are only downloaded when apt needs them. */
    fun install(group: Group, onLine: (String) -> Unit) {
        val packages = group.packages.joinToString(" ")
        val script = """
            export DEBIAN_FRONTEND=noninteractive
            APT="apt-get -q -o APT::Sandbox::User=root -o Dpkg::Use-Pty=0"
            ${'$'}APT install -y --no-install-recommends $packages 2>/dev/null || {
                ${'$'}APT update && ${'$'}APT install -y --no-install-recommends $packages
            } || exit 1
            ${'$'}APT clean
        """.trimIndent()
        val process = ProotCommand.build(paths, script).start()
        process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
        val code = process.waitFor()
        if (code != 0) throw IOException("Package installation failed (exit code $code)")
        marker(group).writeText(group.version)
    }

    private fun marker(group: Group) = File(markerDir, "packages-${group.name.lowercase()}")

    companion object {
        /** Groups a program needs on top of [Group.SCREEN]. */
        fun groupsFor(program: String): List<Group> = buildList {
            add(Group.SCREEN)
            add(Group.AUDIO)
            if ("pons-bench" in program) add(Group.BENCHMARK)
            if ("pons-steam" in program || "pons-game" in program || "pons-ck3" in program) add(Group.STEAM)
        }
    }
}
