package io.github.pons.linux

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** A game Steam has fully installed inside the Linux tree. */
data class InstalledGame(val appId: String, val name: String, val lastPlayed: Long, val art: File?)

/**
 * Reads the user's Steam library from the Linux tree: one appmanifest_<id>.acf per
 * installed app, artwork from Steam's own library cache. Nothing is hard-coded per game.
 */
class GameLibrary(private val paths: LinuxPaths) {

    private val steam get() = File(paths.rootfs, "root/.local/share/Steam")

    /** Installed games, most recently played first; Steam's runtimes and tools left out. */
    fun games(): List<InstalledGame> {
        val manifests = File(steam, "steamapps").listFiles { f -> f.name.matches(MANIFEST) } ?: return emptyList()
        return manifests.mapNotNull(::read).sortedWith(compareByDescending<InstalledGame> { it.lastPlayed }.thenBy { it.name })
    }

    /** Artwork decoded to about [width] pixels wide, or null if Steam has none cached. */
    fun art(game: InstalledGame, width: Int): Bitmap? {
        val file = game.art ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun read(manifest: File): InstalledGame? {
        val text = runCatching { manifest.readText() }.getOrNull() ?: return null
        fun field(key: String) = Regex("\"$key\"\\s+\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
        val appId = field("appid") ?: return null
        val name = field("name") ?: return null
        val fullyInstalled = (field("StateFlags")?.toIntOrNull() ?: 0) and STATE_FULLY_INSTALLED != 0
        if (!fullyInstalled || appId in TOOL_IDS || TOOL_NAMES.any { name.startsWith(it) }) return null
        return InstalledGame(appId, name, field("LastPlayed")?.toLongOrNull() ?: 0, artFor(appId))
    }

    /**
     * Steam's library cache: older clients keep flat "<id>_library_hero.jpg" files, newer
     * ones a folder per app (sometimes with hashed subfolders). Wide art suits the cards.
     */
    private fun artFor(appId: String): File? {
        val cache = File(steam, "appcache/librarycache")
        val inFolder = File(cache, appId).walkTopDown().maxDepth(2).filter { it.isFile }.toList()
        for (kind in ART_KINDS) {
            inFolder.firstOrNull { it.nameWithoutExtension == kind }?.let { return it }
            listOf("jpg", "png").map { File(cache, "${appId}_$kind.$it") }.firstOrNull { it.isFile }?.let { return it }
        }
        return null
    }

    private companion object {
        val MANIFEST = Regex("appmanifest_\\d+\\.acf")
        const val STATE_FULLY_INSTALLED = 4
        val ART_KINDS = listOf("library_hero", "header", "library_600x900")
        // Steamworks redistributables and the Steam Linux Runtime containers.
        val TOOL_IDS = setOf("228980", "1070560", "1391110", "1628350", "1826330")
        val TOOL_NAMES = listOf("Steam Linux Runtime", "Proton", "Steamworks Common")
    }
}
