package io.github.pinguplay.linux

import android.content.Context
import java.io.File

/** Where the Linux environment lives inside the app's private storage. */
class LinuxPaths(context: Context) {
    val libDir: String = context.applicationInfo.nativeLibraryDir
    val rootfs: File = File(context.filesDir, "rootfs")

    /** Shared with Linux as /tmp: X11 socket and the Xvfb framebuffer live here. */
    val tmp: File = File(context.cacheDir, "proot-tmp").apply { mkdirs() }

    /** Linux /dev/shm: Steam and games talk through shared-memory files here. */
    val shm: File = File(context.cacheDir, "shm").apply { mkdirs() }

    val framebuffer: File get() = File(tmp, "Xvfb_screen0")

    /** PulseAudio's output, raw 48 kHz stereo s16le (module-pipe-sink), as /tmp/pinguplay-audio. */
    val audioFifo: File get() = File(tmp, "pinguplay-audio")

    /** Finished game frames from our Mesa (direct present), as /tmp/pinguplay-present. */
    val present: File get() = File(tmp, "pinguplay-present")
}
