package io.github.pons.session

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * Keeps a game from running the device out of memory.
 *
 * A game, Box64 and Steam take about 7 GB. Android does not kill them to make room
 * (they belong to the app in front); instead it swaps to compressed memory until the
 * device stops responding — it happened twice in testing and needed a reboot.
 *
 * - [makeRoom] asks Android to drop other apps' background processes before a game.
 * - [watch] checks free memory every 2 s while a session runs: below [WARN_BYTES]
 *   it warns once, below [STOP_BYTES] for three checks in a row it stops the
 *   session, which loses unsaved progress but keeps the device usable.
 */
class MemoryGuard(private val context: Context, private val emit: (String) -> Unit) {

    @Volatile private var running = false
    private var thread: Thread? = null

    fun makeRoom() {
        val am = context.getSystemService(ActivityManager::class.java)
        val before = available(am)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        context.packageManager.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName && KEEP.none(it::startsWith) }
            .forEach { runCatching { am.killBackgroundProcesses(it) } }
        val after = available(am)
        Log.i(TAG, "Memory: ${before / MB} MB free before closing background apps, ${after / MB} MB after")
        emit("Free memory: ${after / MB} MB")
    }

    fun watch(onTooLow: () -> Unit) {
        stop()
        running = true
        thread = Thread({ loop(onTooLow) }, "pons-memory").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun loop(onTooLow: () -> Unit) {
        val am = context.getSystemService(ActivityManager::class.java)
        var warned = false
        var low = 0
        while (running) {
            try {
                Thread.sleep(CHECK_MS)
            } catch (e: InterruptedException) {
                return
            }
            val free = available(am)
            if (free < WARN_BYTES && !warned) {
                warned = true
                emit("Warning: memory almost full (${free / MB} MB). Close other apps.")
                Log.w(TAG, "Memory low: ${free / MB} MB")
            }
            low = if (free < STOP_BYTES) low + 1 else 0
            if (low >= STOP_CHECKS) {
                emit("Out of memory (${free / MB} MB): stopping the game so the tablet does not freeze.")
                Log.w(TAG, "Memory exhausted: ${free / MB} MB, stopping the session")
                running = false
                onTooLow()
                return
            }
        }
    }

    private fun available(am: ActivityManager): Long =
        ActivityManager.MemoryInfo().also(am::getMemoryInfo).availMem

    companion object {
        private const val TAG = "Pons"
        private const val MB = 1024L * 1024
        private const val CHECK_MS = 2_000L
        private const val WARN_BYTES = 1_000L * MB
        private const val STOP_BYTES = 600L * MB
        private const val STOP_CHECKS = 3

        /** Apps that keep the device reachable or that the user works in. */
        private val KEEP = listOf("com.tailscale.", "app.getmoshi.", "com.termux")
    }
}
