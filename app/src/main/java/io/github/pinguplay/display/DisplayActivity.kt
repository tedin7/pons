package io.github.pinguplay.display

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import io.github.pinguplay.linux.LinuxPaths
import io.github.pinguplay.session.DisplaySettings
import io.github.pinguplay.session.LinuxSession

/** Full-screen view of the Linux desktop, with touch acting as a mouse. */
class DisplayActivity : Activity() {

    private lateinit var surface: GLSurfaceView
    private lateinit var renderer: FramebufferRenderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderer = FramebufferRenderer(LinuxPaths(this).framebuffer, DisplaySettings(this).sharpness)
        surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        val mouse = TouchMouse({ LinuxSession.input }, ::toScreen)
        surface.setOnTouchListener { _, event -> mouse.onTouch(event) }
        setContentView(surface)
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        surface.onResume()
    }

    override fun onPause() {
        surface.onPause()
        super.onPause()
    }

    /** Maps a touch point on the view to Linux screen pixels, or null outside the image. */
    private fun toScreen(x: Float, y: Float): Pair<Int, Int>? {
        val fb = renderer.framebuffer ?: return null
        val (vx, vyFromBottom, vw, vh) = renderer.viewport.toList()
        if (vw == 0 || vh == 0) return null
        val vyTop = surface.height - vyFromBottom - vh
        val sx = ((x - vx) / vw * fb.width).toInt()
        val sy = ((y - vyTop) / vh * fb.height).toInt()
        if (sx !in 0 until fb.width || sy !in 0 until fb.height) return null
        return sx to sy
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
