package io.github.pinguplay

import android.content.Context
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException

/** Unpacks the bundled Debian image into the app's private storage, once. */
class RootfsInstaller(private val context: Context) {

    val rootfsDir: File = File(context.filesDir, "rootfs")
    private val marker = File(context.filesDir, ".rootfs-$IMAGE_VERSION")

    fun isInstalled(): Boolean = marker.exists()

    fun install(onProgress: (Int) -> Unit) {
        if (rootfsDir.exists()) rootfsDir.deleteRecursively()
        rootfsDir.mkdirs()
        val rootPath = rootfsDir.canonicalPath
        val hardLinks = mutableListOf<Pair<File, File>>()
        var count = 0

        context.assets.open(ASSET_NAME).use { raw ->
            TarArchiveInputStream(XZInputStream(BufferedInputStream(raw, 1 shl 16))).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    val target = safeTarget(rootPath, entry.name)
                    when {
                        entry.isDirectory -> target.mkdirs()
                        entry.isSymbolicLink -> {
                            target.parentFile?.mkdirs()
                            target.delete()
                            Os.symlink(entry.linkName, target.path)
                        }
                        entry.isLink -> hardLinks += target to safeTarget(rootPath, entry.linkName)
                        entry.isFile -> {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { tar.copyTo(it) }
                        }
                    }
                    if (!entry.isSymbolicLink && !entry.isLink) applyMode(target, entry)
                    if (++count % 500 == 0) onProgress(count)
                }
            }
        }
        // Android forbids hard links in app storage, so we copy the original file.
        hardLinks.forEach { (link, original) -> original.copyTo(link, overwrite = true) }
        writeNetworkConfig()
        marker.writeText(IMAGE_VERSION)
        onProgress(count)
    }

    private fun applyMode(file: File, entry: TarArchiveEntry) {
        // Keep the owner able to write and enter directories: we are not real root.
        val mode = (entry.mode and 0x1ff) or if (entry.isDirectory) 0x1c0 else 0x180
        Os.chmod(file.path, mode)
    }

    private fun writeNetworkConfig() {
        val resolv = File(rootfsDir, "etc/resolv.conf")
        resolv.delete()
        resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
    }

    private fun safeTarget(rootPath: String, name: String): File {
        val file = File(rootPath, name)
        val normalized = file.toPath().normalize().toString()
        if (normalized != rootPath && !normalized.startsWith("$rootPath/")) {
            throw IOException("Archive entry escapes rootfs: $name")
        }
        return File(normalized)
    }

    companion object {
        const val ASSET_NAME = "debian-slim.tar.xz"
        const val IMAGE_VERSION = "trixie-20261027"
    }
}
