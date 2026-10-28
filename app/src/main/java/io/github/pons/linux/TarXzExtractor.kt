package io.github.pons.linux

import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Extracts a .tar.xz into a directory, refusing entries that escape it. */
object TarXzExtractor {

    fun extract(source: InputStream, destination: File, onProgress: (Int) -> Unit = {}) {
        destination.mkdirs()
        val rootPath = destination.canonicalPath
        val hardLinks = mutableListOf<Pair<File, File>>()
        var count = 0

        TarArchiveInputStream(XZInputStream(BufferedInputStream(source, 1 shl 16))).use { tar ->
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
                        target.delete()
                        target.outputStream().use { tar.copyTo(it) }
                    }
                }
                if (!entry.isSymbolicLink && !entry.isLink) applyMode(target, entry)
                if (++count % 500 == 0) onProgress(count)
            }
        }
        // Android forbids hard links in app storage, so we copy the original file.
        hardLinks.forEach { (link, original) -> original.copyTo(link, overwrite = true) }
        onProgress(count)
    }

    private fun applyMode(file: File, entry: TarArchiveEntry) {
        if (!file.exists()) return
        // Keep the owner able to write and enter directories: we are not real root.
        val mode = (entry.mode and 0x1ff) or if (entry.isDirectory) 0x1c0 else 0x180
        Os.chmod(file.path, mode)
    }

    private fun safeTarget(rootPath: String, name: String): File {
        val normalized = File(rootPath, name).toPath().normalize().toString()
        if (normalized != rootPath && !normalized.startsWith("$rootPath/")) {
            throw IOException("Archive entry escapes destination: $name")
        }
        return File(normalized)
    }
}
