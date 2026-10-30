package io.github.pons.display

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * A real mouse, touchpad or keyboard behaves as on a PC: the pointer follows the
 * mouse without clicking, every button (left, right, middle, back, forward) and the
 * wheel (both axes) reach the game, and keys are pressed and released as keys, so
 * holding one, Shift+click or WASD work. Touch stays with [TouchMouse].
 */
class DesktopInput(
    private val input: () -> InputBridge?,
    private val toScreen: (Float, Float) -> Pair<Int, Int>?,
) {
    private var scrollV = 0f
    private var scrollH = 0f

    /** Mouse motion, hover, wheel and buttons; true when handled. */
    fun onMotion(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE) && !event.isFromSource(InputDevice.SOURCE_TOUCHPAD)) return false
        val bridge = input() ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP -> moveTo(bridge, event)
            MotionEvent.ACTION_BUTTON_PRESS -> {
                moveTo(bridge, event)
                buttonFor(event.actionButton)?.let { bridge.button(it, true) }
            }
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                moveTo(bridge, event)
                buttonFor(event.actionButton)?.let { bridge.button(it, false) }
            }
            MotionEvent.ACTION_SCROLL -> {
                moveTo(bridge, event)
                // Touchpads send fractions; one wheel click per whole notch.
                scrollV += event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                scrollH += event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                while (scrollV >= 1f) { bridge.click(InputBridge.WHEEL_UP); scrollV -= 1f }
                while (scrollV <= -1f) { bridge.click(InputBridge.WHEEL_DOWN); scrollV += 1f }
                while (scrollH >= 1f) { bridge.click(InputBridge.WHEEL_RIGHT); scrollH -= 1f }
                while (scrollH <= -1f) { bridge.click(InputBridge.WHEEL_LEFT); scrollH += 1f }
            }
        }
        return true
    }

    /** Hardware keys (the on-screen keyboard goes through SoftKeyboard); true when sent. */
    fun onKey(event: KeyEvent): Boolean {
        // A mouse's right button arrives as BACK: the game gets it as a click instead.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.isFromSource(InputDevice.SOURCE_MOUSE)) return true
        val sym = keysymFor(event) ?: return false
        if (event.repeatCount > 0) return true // X repeats held keys itself
        val bridge = input() ?: return true
        when (event.action) {
            KeyEvent.ACTION_DOWN -> bridge.key(sym, true)
            KeyEvent.ACTION_UP -> bridge.key(sym, false)
        }
        return true
    }

    private fun moveTo(bridge: InputBridge, event: MotionEvent) {
        toScreen(event.x, event.y)?.let { (x, y) -> bridge.move(x, y) }
    }

    private fun buttonFor(actionButton: Int): Int? = when (actionButton) {
        MotionEvent.BUTTON_PRIMARY -> InputBridge.LEFT
        MotionEvent.BUTTON_SECONDARY -> InputBridge.RIGHT
        MotionEvent.BUTTON_TERTIARY -> InputBridge.MIDDLE
        MotionEvent.BUTTON_BACK -> InputBridge.BUTTON_BACK
        MotionEvent.BUTTON_FORWARD -> InputBridge.BUTTON_FORWARD
        else -> null
    }

    companion object {
        /** X11 keysym of the physical key, unshifted (X adds Shift from its own state). */
        fun keysymFor(event: KeyEvent): Int? {
            SPECIAL[event.keyCode]?.let { return it }
            if (event.keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) return 0xffbe + (event.keyCode - KeyEvent.KEYCODE_F1)
            if (event.keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9) return 0xffb0 + (event.keyCode - KeyEvent.KEYCODE_NUMPAD_0)
            val char = event.getUnicodeChar(0)
            return if (char > 0) InputBridge.keysymFor(char) else null
        }

        private val SPECIAL = mapOf(
            KeyEvent.KEYCODE_ESCAPE to 0xff1b, KeyEvent.KEYCODE_ENTER to 0xff0d, KeyEvent.KEYCODE_NUMPAD_ENTER to 0xff8d,
            KeyEvent.KEYCODE_TAB to 0xff09, KeyEvent.KEYCODE_DEL to 0xff08, KeyEvent.KEYCODE_FORWARD_DEL to 0xffff,
            KeyEvent.KEYCODE_SPACE to 0x20, KeyEvent.KEYCODE_INSERT to 0xff63,
            KeyEvent.KEYCODE_DPAD_LEFT to 0xff51, KeyEvent.KEYCODE_DPAD_UP to 0xff52,
            KeyEvent.KEYCODE_DPAD_RIGHT to 0xff53, KeyEvent.KEYCODE_DPAD_DOWN to 0xff54,
            KeyEvent.KEYCODE_MOVE_HOME to 0xff50, KeyEvent.KEYCODE_MOVE_END to 0xff57,
            KeyEvent.KEYCODE_PAGE_UP to 0xff55, KeyEvent.KEYCODE_PAGE_DOWN to 0xff56,
            KeyEvent.KEYCODE_SHIFT_LEFT to 0xffe1, KeyEvent.KEYCODE_SHIFT_RIGHT to 0xffe2,
            KeyEvent.KEYCODE_CTRL_LEFT to 0xffe3, KeyEvent.KEYCODE_CTRL_RIGHT to 0xffe4,
            KeyEvent.KEYCODE_ALT_LEFT to 0xffe9, KeyEvent.KEYCODE_ALT_RIGHT to 0xffea,
            KeyEvent.KEYCODE_META_LEFT to 0xffeb, KeyEvent.KEYCODE_META_RIGHT to 0xffec,
            KeyEvent.KEYCODE_CAPS_LOCK to 0xffe5,
        )
    }
}
