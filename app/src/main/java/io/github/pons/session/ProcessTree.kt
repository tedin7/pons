package io.github.pons.session

import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Stops every Linux process the app started. proot ignores SIGTERM, and killing it
 * alone would leave its traced programs running, so we kill the whole tree below
 * the app process, deepest processes first.
 */
object ProcessTree {

    fun killDescendants(root: Int = Os.getpid()) {
        val parents = readParents()
        val depth = HashMap<Int, Int>()
        fun depthOf(pid: Int): Int {
            depth[pid]?.let { return it }
            var d = 0
            var at = pid
            while (true) {
                val parent = parents[at] ?: return -1
                d++
                if (parent == root) break
                at = parent
                if (d > MAX_DEPTH) return -1
            }
            depth[pid] = d
            return d
        }
        parents.keys
            .filter { it != root && depthOf(it) > 0 }
            .sortedByDescending { depthOf(it) }
            .forEach { pid -> runCatching { Os.kill(pid, OsConstants.SIGKILL) } }
    }

    /** pid -> parent pid for every process we may see (our own uid). */
    private fun readParents(): Map<Int, Int> {
        val result = HashMap<Int, Int>()
        File("/proc").listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            val stat = runCatching { File(dir, "stat").readText() }.getOrNull() ?: return@forEach
            // Fields after the parenthesised command name: state, ppid, ...
            val rest = stat.substringAfterLast(") ").split(' ')
            rest.getOrNull(1)?.toIntOrNull()?.let { result[pid] = it }
        }
        return result
    }

    private const val MAX_DEPTH = 64
}
