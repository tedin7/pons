package io.github.pons

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A newer release on GitHub: its version and the page to download it from. */
data class Update(val version: String, val page: String)

/**
 * Asks GitHub, at most once a day, whether a newer Pons release exists. Runs on the
 * caller's thread (call it off the main thread); any failure just means "no update".
 */
class UpdateCheck(context: Context) {

    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    fun find(current: String = BuildConfig.VERSION_NAME): Update? {
        val now = System.currentTimeMillis()
        val cached = prefs.getString(KEY_VERSION, null)?.let { Update(it, prefs.getString(KEY_PAGE, RELEASES)!!) }
        if (now - prefs.getLong(KEY_CHECKED, 0) < DAY_MS) return cached?.takeIf { newer(it.version, current) }
        val latest = fetch() ?: return cached?.takeIf { newer(it.version, current) }
        prefs.edit().putLong(KEY_CHECKED, now).putString(KEY_VERSION, latest.version).putString(KEY_PAGE, latest.page).apply()
        return latest.takeIf { newer(it.version, current) }
    }

    private fun fetch(): Update? = try {
        val connection = URL(LATEST).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (connection.responseCode != 200) null
            else JSONObject(connection.inputStream.bufferedReader().readText()).let {
                Update(it.getString("tag_name").removePrefix("v"), it.optString("html_url", RELEASES))
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        Log.i("Pons", "Update check failed: ${e.message}")
        null
    }

    companion object {
        private const val LATEST = "https://api.github.com/repos/tedin7/pons/releases/latest"
        private const val RELEASES = "https://github.com/tedin7/pons/releases/latest"
        private const val KEY_CHECKED = "checked_at"
        private const val KEY_VERSION = "latest_version"
        private const val KEY_PAGE = "latest_page"
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val TIMEOUT_MS = 10_000

        /** True when [candidate] ("0.2.0") is a later version than [current] ("0.1.0-dev" counts as 0.1.0). */
        fun newer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
                if (d != 0) return d > 0
            }
            return false
        }
    }
}
