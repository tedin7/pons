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

    /** Types one character; the helper adds Shift when the symbol needs it. */
    fun type(keysym: Int) = send("T ${Integer.toHexString(keysym)}")

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
        const val KEY_BACKSPACE = 0xff08
        const val KEY_RETURN = 0xff0d
        const val KEY_TAB = 0xff09
        const val KEY_ESCAPE = 0xff1b

        /** X11 keysym for a Unicode code point (Latin-1 maps directly). */
        fun keysymFor(codePoint: Int): Int = when {
            codePoint == '\n'.code -> KEY_RETURN
            codePoint == '\t'.code -> KEY_TAB
            codePoint in 0x20..0xff -> codePoint
            else -> 0x01000000 or codePoint
        }
    }
}
