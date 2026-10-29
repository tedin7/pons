package io.github.pons.display

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log

/**
 * Lowers the game's frame cap (through [setCap], see PresentBuffer.setFpsCap) when the
 * tablet runs hot, and raises it again after it has been cool for a while.
 *
 * Steps: 0 (no override, the game's own cap) -> 30 -> 24 -> 20. Never below 20, never
 * above the game's own cap. Only acts while a game presents frames. Runs on the main
 * looper: one cheap poll every [POLL_MS].
 */
class ThermalGovernor(
    context: Context,
    private val gameIsPresenting: () -> Boolean,
    private val setCap: (Int) -> Unit,
    private val enabled: () -> Boolean = { true },
) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var step = 0
    private var lastStepAt = 0L
    private var coolSince = 0L
    private var running = false

    /** When the current game started presenting frames (0 = not presenting). */
    private var presentingSince = 0L

    /** Latest status from the listener; used when the headroom forecast is NaN. */
    @Volatile private var status = PowerManager.THERMAL_STATUS_NONE

    private val statusListener = PowerManager.OnThermalStatusChangedListener { status = it }

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            tick(System.nanoTime() / 1_000_000L)
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        if (running || !enabled()) return
        running = true
        status = power.currentThermalStatus
        power.addThermalStatusListener(handler::post, statusListener)
        handler.postDelayed(poll, POLL_MS)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(poll)
        power.removeThermalStatusListener(statusListener)
        apply(0, Float.NaN)
    }

    private fun tick(nowMs: Long) {
        if (!gameIsPresenting()) {
            // Game over: the next one starts from its own cap.
            if (step != 0) apply(0, Float.NaN)
            coolSince = 0L
            presentingSince = 0L
            return
        }
        if (presentingSince == 0L) presentingSince = nowMs
        // Loading heats the CPU in bursts and Android's 10 s forecast overreacts
        // (it asked for 24 fps at a 40 C skin on CK3): judge only steady play.
        if (nowMs - presentingSince < WARMUP_MS) return
        val headroom = power.getThermalHeadroom(FORECAST_S)
        val hot = (!headroom.isNaN() && headroom >= HOT_HEADROOM) ||
            status >= PowerManager.THERMAL_STATUS_MODERATE
        val cool = (headroom.isNaN() || headroom < COOL_HEADROOM) &&
            status <= PowerManager.THERMAL_STATUS_LIGHT
        when {
            hot -> {
                coolSince = 0L
                // The forecast reacts slowly: wait between steps before judging again.
                if (step < STEPS.lastIndex && nowMs - lastStepAt >= STEP_DOWN_MS) {
                    apply(step + 1, headroom)
                    lastStepAt = nowMs
                }
            }
            cool && step > 0 -> {
                if (coolSince == 0L) coolSince = nowMs
                if (nowMs - coolSince >= COOL_MS) {
                    apply(step - 1, headroom)
                    coolSince = nowMs
                }
            }
            else -> coolSince = 0L
        }
    }

    private fun apply(newStep: Int, headroom: Float) {
        if (newStep != step) {
            step = newStep
            Log.i("Pons", "Thermal: headroom %.2f status %d -> cap %d"
                .format(headroom, status, STEPS[step]))
        }
        setCap(STEPS[step])
    }

    private companion object {
        val STEPS = intArrayOf(0, 30, 24, 20)
        const val POLL_MS = 2_000L
        const val FORECAST_S = 10
        const val HOT_HEADROOM = 0.85f
        const val COOL_HEADROOM = 0.65f
        const val COOL_MS = 20_000L
        const val STEP_DOWN_MS = 6_000L
        const val WARMUP_MS = 45_000L
    }
}
