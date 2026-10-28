package io.github.pinguplay.display

import java.io.OutputStream
import java.util.concurrent.Executors

/** Sends pointer and key commands to `pinguplay-input` running inside Linux. */
class InputBridge(private val sink: OutputStream) {

    private val writer = Executors.newSingleThreadExecutor { Thread(it, "pinguplay-input") }

    fun move(x: Int, y: Int) = send("M $x $y")

    fun button(button: Int, pressed: Boolean) = send("B $button ${if (pressed) 1 else 0}")

    fun click(button: Int) {
        button(button, true)
        button(button, false)
    }

    fun key(keysym: Int, pressed: Boolean) =
        send("K ${Integer.toHexString(keysym)} ${if (pressed) 1 else 0}")

    fun close() {
        writer.shutdown()
        runCatching { sink.close() }
    }

    private fun send(line: String) {
        if (writer.isShutdown) return
        writer.execute {
            runCatching {
                sink.write("$line\n".toByteArray())
                sink.flush()
            }
        }
    }

    companion object {
        const val LEFT = 1
        const val MIDDLE = 2
        const val RIGHT = 3
        const val WHEEL_UP = 4
        const val WHEEL_DOWN = 5
    }
}
