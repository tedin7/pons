package io.github.pons.display

import android.view.MotionEvent
import kotlin.math.abs

/**
 * Turns touches into a mouse, the way a strategy game wants it:
 * one finger taps or drags with the left button, a long press or a quick two-finger
 * tap is a right click, two fingers moving up or down or pinching turn the wheel
 * (zoom on a map, scroll in a list).
 */
class TouchMouse(
    private val input: () -> InputBridge?,
    private val toScreen: (Float, Float) -> Pair<Int, Int>?,
) {
    private var downTime = 0L
    private var dragging = false
    private var startX = 0f
    private var startY = 0f
    private var twoFingerY = Float.NaN
    private var multiTouch = false
    private var scrollAccumulator = 0f
    private var twoFingerSpan = Float.NaN
    private var twoFingerDownAt = 0L
    private var twoFingerMoved = 0f

    fun onTouch(event: MotionEvent): Boolean {
        val bridge = input() ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = event.eventTime
                startX = event.x
                startY = event.y
                dragging = false
                multiTouch = false
                moveTo(bridge, event.x, event.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (dragging) bridge.button(InputBridge.LEFT, false)
                dragging = false
                multiTouch = true
                twoFingerY = averageY(event)
                twoFingerSpan = span(event)
                twoFingerDownAt = event.eventTime
                twoFingerMoved = 0f
                scrollAccumulator = 0f
            }
            MotionEvent.ACTION_MOVE -> onMove(bridge, event)
            MotionEvent.ACTION_POINTER_UP -> {
                // A quick tap with two fingers that hardly moved: right click.
                if (!twoFingerY.isNaN() && event.eventTime - twoFingerDownAt < TWO_FINGER_TAP_MS && twoFingerMoved < SLOP_PX) {
                    bridge.click(InputBridge.RIGHT)
                }
                twoFingerY = Float.NaN
                twoFingerSpan = Float.NaN
            }
            MotionEvent.ACTION_UP -> onUp(bridge, event)
            MotionEvent.ACTION_CANCEL -> if (dragging) bridge.button(InputBridge.LEFT, false)
        }
        return true
    }

    private fun onMove(bridge: InputBridge, event: MotionEvent) {
        if (multiTouch) {
            if (event.pointerCount < 2 || twoFingerY.isNaN()) return
            val y = averageY(event)
            val s = span(event)
            // Fingers moving up, or spreading apart, zoom in (wheel up).
            val delta = (twoFingerY - y) + (if (twoFingerSpan.isNaN()) 0f else s - twoFingerSpan) * PINCH_WEIGHT
            twoFingerMoved += abs(twoFingerY - y) + (if (twoFingerSpan.isNaN()) 0f else abs(s - twoFingerSpan))
            scrollAccumulator += delta
            twoFingerY = y
            twoFingerSpan = s
            while (abs(scrollAccumulator) > SCROLL_STEP_PX) {
                val up = scrollAccumulator > 0
                bridge.click(if (up) InputBridge.WHEEL_UP else InputBridge.WHEEL_DOWN)
                scrollAccumulator += if (up) -SCROLL_STEP_PX else SCROLL_STEP_PX
            }
            return
        }
        if (!dragging && (abs(event.x - startX) > SLOP_PX || abs(event.y - startY) > SLOP_PX)) {
            dragging = true
            bridge.button(InputBridge.LEFT, true)
        }
        moveTo(bridge, event.x, event.y)
    }

    private fun onUp(bridge: InputBridge, event: MotionEvent) {
        moveTo(bridge, event.x, event.y)
        when {
            dragging -> bridge.button(InputBridge.LEFT, false)
            multiTouch -> Unit
            event.eventTime - downTime >= LONG_PRESS_MS -> bridge.click(InputBridge.RIGHT)
            else -> bridge.click(InputBridge.LEFT)
        }
        dragging = false
        twoFingerY = Float.NaN
    }

    private fun moveTo(bridge: InputBridge, x: Float, y: Float) {
        toScreen(x, y)?.let { (sx, sy) -> bridge.move(sx, sy) }
    }

    private fun span(event: MotionEvent): Float {
        if (event.pointerCount < 2) return Float.NaN
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun averageY(event: MotionEvent): Float =
        (0 until event.pointerCount).map { event.getY(it) }.average().toFloat()

    companion object {
        private const val SLOP_PX = 24f
        private const val SCROLL_STEP_PX = 60f
        private const val LONG_PRESS_MS = 450L
        private const val TWO_FINGER_TAP_MS = 250L
        /** A pinch moves the span about twice as fast as a two-finger slide moves the centre. */
        private const val PINCH_WEIGHT = 0.5f
    }
}
