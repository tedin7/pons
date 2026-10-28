package io.github.pons.linux

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Installs [Component]s once, remembering each installed version in a marker file. */
class ComponentInstaller(private val context: Context, private val paths: LinuxPaths) {

    private val markerDir = File(context.filesDir, "components").apply { mkdirs() }

    init {
        // v0.1.0 marked the rootfs with a single file; carry it over.
        val legacy = File(context.filesDir, ".rootfs-${Components.ROOTFS.version}")
        if (legacy.exists()) {
            marker(Components.ROOTFS).writeText(Components.ROOTFS.version)
            legacy.delete()
        }
    }

    fun isInstalled(component: Component): Boolean =
        marker(component).let { it.exists() && it.readText() == component.version }

    fun missing(components: List<Component>): List<Component> = components.filterNot(::isInstalled)

    fun install(component: Component, onStatus: (String) -> Unit) {
        val destination = if (component.intoRootfs) paths.rootfs else paths.rootfs.also {
            if (it.exists()) it.deleteRecursively()
        }
        component.replaces?.let { File(paths.rootfs, it).deleteRecursively() }
        openSource(component, onStatus).use { input ->
            TarXzExtractor.extract(input, destination) { n -> onStatus("${component.id}: $n file") }
        }
        if (!component.intoRootfs) writeNetworkConfig()
        marker(component).writeText(component.version)
    }

    private fun openSource(component: Component, onStatus: (String) -> Unit): InputStream {
        component.assetName?.let { return context.assets.open(it) }
        val url = component.url ?: throw IOException("${component.id} has no source")
        val file = File(context.cacheDir, "${component.id}-${component.version}.tar.xz")
        if (!file.exists() || sha256(file) != component.sha256) {
            download(url, file) { done -> onStatus("${component.id}: ${done / 1_048_576} MB") }
            val actual = sha256(file)
            if (actual != component.sha256) {
                file.delete()
                throw IOException("${component.id}: checksum mismatch ($actual)")
            }
        }
        return file.inputStream()
    }

    private fun download(url: String, target: File, onBytes: (Long) -> Unit) {
        val partial = File(target.path + ".part")
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            if (connection.responseCode != 200) {
                throw IOException("Download failed: HTTP ${connection.responseCode} for $url")
            }
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var total = 0L
                    var lastReport = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        total += read
                        if (total - lastReport > 4_194_304) onBytes(total).also { lastReport = total }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        if (!partial.renameTo(target)) throw IOException("Cannot save $target")
    }

    private fun writeNetworkConfig() {
        val resolv = File(paths.rootfs, "etc/resolv.conf")
        resolv.delete()
        resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        resolv.setReadable(true, false)
    }

    private fun marker(component: Component) = File(markerDir, component.id)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
