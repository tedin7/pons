package io.github.pinguplay.display

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.FrameLayout
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowInsetsController
import io.github.pinguplay.linux.LinuxPaths
import io.github.pinguplay.session.DisplaySettings
import io.github.pinguplay.session.LinuxSession

/** Full-screen view of the Linux desktop, with touch acting as a mouse. */
class DisplayActivity : Activity() {

    private lateinit var surface: GLSurfaceView
    private lateinit var renderer: FramebufferRenderer
    private lateinit var status: TextView

    private var touched = false

    /**
     * While Linux installs or a program is still starting (its window can take a
     * while to appear), show what is happening instead of a silent black screen.
     * The first touch hides it.
     */
    private val statusUpdater = object : Runnable {
        override fun run() {
            val started = LinuxSession.programStartedAt
            val starting = started != 0L && SystemClock.uptimeMillis() - started < STARTING_MS
            val waiting = !touched && (renderer.shown == null || !LinuxSession.running || starting)
            status.visibility = if (waiting) View.VISIBLE else View.GONE
            if (waiting) {
                status.text = LinuxSession.log().takeLast(STATUS_LINES).joinToString("\n")
                    .ifEmpty { "Avvio di Linux…" }
            }
            status.postDelayed(this, STATUS_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val paths = LinuxPaths(this)
        renderer = FramebufferRenderer(paths.framebuffer, paths.present, DisplaySettings(this).sharpness)
        surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        val mouse = TouchMouse({ LinuxSession.input }, ::toScreen)
        surface.setOnTouchListener { _, event ->
            touched = true
            mouse.onTouch(event)
        }
        val keyboard = SoftKeyboard(this) { LinuxSession.input }
        val keyboardButton = Button(this).apply {
            text = "⌨"
            textSize = 20f
            alpha = 0.6f
            setOnClickListener { keyboard.toggle() }
        }
        status = TextView(this).apply {
            setTextColor(0xFFDDDDDD.toInt())
            setBackgroundColor(0xB0000000.toInt())
            textSize = 16f
            setPadding(48, 48, 48, 48)
        }
        setContentView(FrameLayout(this).apply {
            addView(keyboard.view, FrameLayout.LayoutParams(1, 1))
            addView(surface)
            addView(status, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM))
            addView(keyboardButton, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END))
        })
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        surface.onResume()
        status.post(statusUpdater)
    }

    override fun onPause() {
        status.removeCallbacks(statusUpdater)
        surface.onPause()
        super.onPause()
    }

    /** Maps a touch point on the view to X screen pixels, or null outside the image. */
    private fun toScreen(x: Float, y: Float): Pair<Int, Int>? {
        val area = renderer.shown ?: return null
        val (vx, vyFromBottom, vw, vh) = renderer.viewport.toList()
        if (vw == 0 || vh == 0) return null
        val vyTop = surface.height - vyFromBottom - vh
        val sx = ((x - vx) / vw * area.width).toInt()
        val sy = ((y - vyTop) / vh * area.height).toInt()
        if (sx !in 0 until area.width || sy !in 0 until area.height) return null
        return (area.x + sx) to (area.y + sy)
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private companion object {
        const val STATUS_LINES = 6
        const val STATUS_REFRESH_MS = 500L
        const val STARTING_MS = 45_000L
    }
}
