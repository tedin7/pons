package io.github.pinguplay.session

import android.content.Context
import android.util.Log
import io.github.pinguplay.display.InputBridge
import io.github.pinguplay.linux.ComponentInstaller
import io.github.pinguplay.linux.Components
import io.github.pinguplay.linux.LinuxPaths
import io.github.pinguplay.linux.PackageSetup
import io.github.pinguplay.linux.ProotCommand
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The running Linux desktop: Xvfb (the screen), the input helper, and one program.
 * Lives as long as [LinuxSessionService] keeps the app in the foreground.
 */
object LinuxSession {

    const val SCREEN_WIDTH = 1920
    const val SCREEN_HEIGHT = 1360
    private const val TAG = "Pinguplay"
    private const val MAX_LOG_LINES = 400

    private val processes = CopyOnWriteArrayList<Process>()
    private val logLines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    @Volatile var input: InputBridge? = null
        private set

    @Volatile var running = false
        private set

    fun log(): List<String> = synchronized(logLines) { logLines.toList() }

    fun addListener(listener: (String) -> Unit) = listeners.add(listener)

    fun removeListener(listener: (String) -> Unit) = listeners.remove(listener)

    /** Blocking until the program exits: call from a background thread. */
    fun start(context: Context, program: String) {
        stop()
        val paths = LinuxPaths(context)
        try {
            installMissing(context, paths)
            startScreen(paths)
            startInput(paths)
            running = true
            emit("▶ $program")
            val payload = launch(paths, program, "program")
            val code = payload.waitFor()
            emit("■ uscito con codice $code")
        } catch (e: Exception) {
            Log.e(TAG, "Session failed", e)
            emit("ERRORE: ${e.message}")
        } finally {
            stop()
        }
    }

    /** Safe from any thread; makes a blocked [start] return. */
    fun stop() {
        input?.close()
        input = null
        processes.forEach { it.destroy() }
        processes.clear()
        running = false
    }

    private fun installMissing(context: Context, paths: LinuxPaths) {
        val installer = ComponentInstaller(context, paths)
        installer.missing(Components.ALL).forEach { component ->
            emit("Installo ${component.id}…")
            installer.install(component) { status -> emit("  $status") }
        }
        val packages = PackageSetup(context, paths)
        if (!packages.isDone()) {
            emit("Installo i pacchetti Linux (solo la prima volta)…")
            packages.run { line -> emit("  $line") }
        }
    }

    private fun startScreen(paths: LinuxPaths) {
        File(paths.tmp, ".X11-unix/X0").delete()
        File(paths.tmp, ".X0-lock").delete()
        paths.framebuffer.delete()
        launch(paths,
            "Xvfb ${ProotCommand.DISPLAY} -screen 0 ${SCREEN_WIDTH}x${SCREEN_HEIGHT}x24 " +
                "-fbdir /tmp -nolisten tcp -ac +extension GLX", "xvfb")
        val deadline = System.currentTimeMillis() + 15_000
        while (!File(paths.tmp, ".X11-unix/X0").exists()) {
            check(System.currentTimeMillis() < deadline) { "Lo schermo Linux non è partito" }
            Thread.sleep(100)
        }
    }

    private fun startInput(paths: LinuxPaths) {
        val process = ProotCommand.build(paths, "exec pinguplay-input").start()
        processes += process
        pump(process, "input")
        input = InputBridge(process.outputStream)
    }

    private fun launch(paths: LinuxPaths, script: String, name: String): Process {
        val process = ProotCommand.build(paths, script).start()
        processes += process
        pump(process, name)
        return process
    }

    private fun pump(process: Process, name: String) {
        Thread({
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { emit("[$name] $it") }
                }
            }
        }, "pinguplay-log-$name").start()
    }

    private fun emit(line: String) {
        Log.i(TAG, line)
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        }
        listeners.forEach { it(line) }
    }
}
