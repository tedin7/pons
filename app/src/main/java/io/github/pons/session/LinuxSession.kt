package io.github.pons.session

import android.content.Context
import android.util.Log
import io.github.pons.display.InputBridge
import io.github.pons.linux.ComponentInstaller
import io.github.pons.linux.Components
import io.github.pons.linux.LinuxPaths
import io.github.pons.linux.PackageSetup
import io.github.pons.linux.ProotCommand
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The running Linux desktop: Xvfb (the screen), the input helper, and one program.
 * Lives as long as [LinuxSessionService] keeps the app in the foreground.
 */
object LinuxSession {

    private const val TAG = "Pons"
    private const val MAX_LOG_LINES = 400

    private val processes = CopyOnWriteArrayList<Process>()
    private val logLines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    /** Box64 checks the flush file every 2 s; then writing the cache takes a moment. */
    private const val FLUSH_WAIT_MS = 4_000L

    @Volatile var input: InputBridge? = null
        private set

    @Volatile private var audio: AudioBridge? = null

    @Volatile var running = false
        private set

    /** The program the current session runs, null when idle. */
    @Volatile var program: String? = null
        private set

    /** When the current program was launched (uptime ms), 0 while installing or idle. */
    @Volatile var programStartedAt = 0L
        private set

    /** Set by [stop]: errors after it are just the program being torn down. */
    @Volatile private var stopped = false
    @Volatile private var flushFile: File? = null
    @Volatile private var memory: MemoryGuard? = null

    fun log(): List<String> = synchronized(logLines) { logLines.toList() }

    fun addListener(listener: (String) -> Unit) = listeners.add(listener)

    fun removeListener(listener: (String) -> Unit) = listeners.remove(listener)

    /** Blocking until the program exits: call from a background thread. */
    fun start(context: Context, program: String) {
        stop()
        stopped = false
        this.program = program
        val paths = LinuxPaths(context)
        flushFile = paths.dynacacheFlush
        val guard = MemoryGuard(context, ::emit).also { memory = it }
        try {
            installMissing(context, paths, program)
            guard.makeRoom()
            startScreen(paths, DisplaySettings(context).resolution)
            startInput(paths)
            startAudioAndBus(paths)
            running = true
            programStartedAt = android.os.SystemClock.uptimeMillis()
            emit("▶ $program")
            val payload = launch(paths, program, "program")
            guard.watch { stop() }
            val code = payload.waitFor()
            emit("■ uscito con codice $code")
        } catch (e: Exception) {
            Log.e(TAG, "Session failed", e)
            if (!stopped) emit("ERRORE: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            stop()
        }
    }

    /** Safe from any thread; makes a blocked [start] return. */
    fun stop() {
        stopped = true
        memory?.stop()
        memory = null
        input?.close()
        input = null
        audio?.stop()
        audio = null
        saveTranslations()
        processes.forEach { it.destroy() }
        processes.clear()
        ProcessTree.killDescendants()
        running = false
        programStartedAt = 0L
        program = null
    }

    /**
     * Asks Box64 to save its translations before the kill: the next start then
     * loads them instead of translating the whole game again (minutes of every
     * core at full load). Box64 polls the file every 2 s.
     */
    private fun saveTranslations() {
        val file = flushFile ?: return
        if (!running) return
        runCatching {
            file.writeText(System.nanoTime().toString())
            file.setLastModified(System.currentTimeMillis())
            Thread.sleep(FLUSH_WAIT_MS)
        }
    }

    private fun installMissing(context: Context, paths: LinuxPaths, program: String) {
        val installer = ComponentInstaller(context, paths)
        installer.missing(Components.ALL).forEach { component ->
            emit("Installo ${component.id}…")
            installer.install(component) { status -> emit("  $status") }
        }
        val packages = PackageSetup(context, paths)
        PackageSetup.groupsFor(program).filterNot(packages::isDone).forEach { group ->
            emit("Installo i pacchetti Linux: ${group.name.lowercase()} (solo la prima volta)…")
            packages.install(group) { line -> emit("  $line") }
        }
    }

    private fun startScreen(paths: LinuxPaths, resolution: Resolution) {
        File(paths.tmp, ".X11-unix/X0").delete()
        File(paths.tmp, ".X0-lock").delete()
        paths.framebuffer.delete()
        paths.present.delete()
        launch(paths,
            "Xvfb ${ProotCommand.DISPLAY} -screen 0 ${resolution.width}x${resolution.height}x24 " +
                "-fbdir /tmp -nolisten tcp -ac +extension GLX", "xvfb")
        val deadline = System.currentTimeMillis() + 15_000
        while (!File(paths.tmp, ".X11-unix/X0").exists()) {
            check(System.currentTimeMillis() < deadline) { "Lo schermo Linux non è partito" }
            Thread.sleep(100)
        }
    }

    /** PulseAudio piped to Android, plus the D-Bus session bus desktop programs expect. */
    private fun startAudioAndBus(paths: LinuxPaths) {
        File(paths.tmp, "dbus-session").delete()
        File(paths.tmp, "pulse-native").delete()
        paths.audioFifo.delete()
        launch(paths, "exec dbus-daemon --session --nofork --address=unix:path=/tmp/dbus-session", "dbus")
        launch(paths,
            "exec pulseaudio -n --daemonize=no --exit-idle-time=-1 --disallow-exit " +
                "--disable-shm=yes --log-target=stderr " +
                "-L 'module-native-protocol-unix auth-anonymous=1 socket=/tmp/pulse-native' " +
                "-L 'module-pipe-sink file=/tmp/pons-audio format=s16le rate=${AudioBridge.RATE} " +
                "channels=2 sink_name=android sink_properties=device.description=Android' " +
                "-L module-always-sink", "audio")
        audio = AudioBridge(paths.audioFifo).also { it.start() }
    }

    private fun startInput(paths: LinuxPaths) {
        val process = ProotCommand.build(paths, "exec pons-input").start()
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
        }, "pons-log-$name").start()
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
