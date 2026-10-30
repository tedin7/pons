package io.github.pons.display

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import io.github.pons.linux.GameLibrary
import io.github.pons.linux.LinuxPaths
import io.github.pons.session.DisplaySettings
import io.github.pons.session.LinuxSession
import io.github.pons.ui.LoadingView

/** Full-screen view of the Linux desktop, with touch acting as a mouse. */
class DisplayActivity : Activity() {

    private lateinit var surface: GLSurfaceView
    private lateinit var renderer: FramebufferRenderer
    private lateinit var loading: LoadingView

    private lateinit var thermal: ThermalGovernor
    private var dismissed = false
    private var vsyncCount = 0L

    /**
     * Each vsync: redraw when the game published a new frame; when no game presents,
     * refresh the desktop every other vsync (it has no frame counter to watch).
     */
    private val vsyncDriver = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            vsyncCount++
            val desktopTick = !renderer.gameIsPresenting() && vsyncCount % DESKTOP_VSYNC_DIVIDER == 0L
            if (renderer.hasNewGameFrame() || desktopTick) surface.requestRender()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * While Linux installs or a program is still starting (its window can take a
     * while to appear), show the loading screen instead of a silent black screen.
     * Once the Linux screen has something on it, a tap dismisses it.
     */
    private val statusUpdater = object : Runnable {
        override fun run() {
            val stage = LinuxSession.stage
            val started = LinuxSession.programStartedAt
            val starting = started != 0L && SystemClock.uptimeMillis() - started < STARTING_MS
            val ready = renderer.shown != null && LinuxSession.running
            val waiting = !dismissed && (!ready || starting || stage.failed)
            loading.visibility = if (waiting) View.VISIBLE else View.GONE
            if (waiting) {
                val name = intent.getStringExtra(EXTRA_TITLE) ?: "the program"
                loading.show(stage.title.ifEmpty { "Starting $name" }, stage.detail, stage.fraction, ready, stage.failed)
            }
            loading.postDelayed(this, STATUS_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AhbFrames.startServer()
        val paths = LinuxPaths(this)
        renderer = FramebufferRenderer(paths.framebuffer, paths.present, DisplaySettings(this).sharpness)
        thermal = ThermalGovernor(
            this, renderer::gameIsPresenting, renderer::setFpsCap,
            enabled = { DisplaySettings(this).thermalGovernor })
        surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            // Draw only when there is something new (see vsyncDriver): redrawing the
            // upscaled screen at the panel's 144 Hz for a 30 fps game only made heat.
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
        val mouse = TouchMouse({ LinuxSession.input }, ::toScreen)
        val desktop = DesktopInput({ LinuxSession.input }, ::toScreen).also { desktopInput = it }
        // A real mouse or touchpad: pointer, buttons and wheel as on a PC; fingers: TouchMouse.
        surface.setOnTouchListener { _, event -> desktop.onMotion(event) || mouse.onTouch(event) }
        surface.setOnGenericMotionListener { _, event -> desktop.onMotion(event) }
        val keyboard = SoftKeyboard(this) { LinuxSession.input }
        val keyboardButton = Button(this).apply {
            text = "⌨"
            textSize = 20f
            alpha = 0.6f
            setOnClickListener { keyboard.toggle() }
        }
        loading = LoadingView(this).apply {
            setBackdrop(null)
            setOnClickListener {
                when {
                    LinuxSession.stage.failed -> finish()
                    renderer.shown != null && LinuxSession.running -> { dismissed = true; visibility = View.GONE }
                }
            }
        }
        intent.getStringExtra(EXTRA_APP_ID)?.let { appId ->
            Thread({
                val library = GameLibrary(paths)
                val art = library.games().firstOrNull { it.appId == appId }
                    ?.let { library.art(it, resources.displayMetrics.widthPixels) }
                runOnUiThread { loading.setBackdrop(art) }
            }, "pons-backdrop").start()
        }
        setContentView(FrameLayout(this).apply {
            addView(keyboard.view, FrameLayout.LayoutParams(1, 1))
            addView(surface)
            addView(keyboardButton, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END))
        })
        addContentView(loading, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        hideSystemBars()
        // 30 fps games pace evenly at 60 Hz (each frame shown for two refreshes);
        // at 144 Hz frames alternate between 4 and 5 refreshes and look jerky.
        window.attributes = window.attributes.apply { preferredRefreshRate = PREFERRED_REFRESH_HZ }
    }

    override fun onResume() {
        super.onResume()
        // The screen can come back from the menu or another app with the bars
        // (and the tablet taskbar) showing over the game: hide them every time.
        hideSystemBars()
        surface.onResume()
        loading.post(statusUpdater)
        Choreographer.getInstance().postFrameCallback(vsyncDriver)
        thermal.start()
    }

    override fun onPause() {
        thermal.stop()
        loading.removeCallbacks(statusUpdater)
        Choreographer.getInstance().removeFrameCallback(vsyncDriver)
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

    private var desktopInput: DesktopInput? = null

    /** Hardware keyboard keys go to the game as key presses and releases. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        desktopInput?.onKey(event) == true || super.dispatchKeyEvent(event)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        /** Name shown while the program starts ("Starting Steam"). */
        const val EXTRA_TITLE = "title"
        /** Steam app id: its artwork becomes the loading screen's background. */
        const val EXTRA_APP_ID = "app_id"
        private const val STATUS_REFRESH_MS = 250L
        private const val STARTING_MS = 45_000L
        private const val DESKTOP_VSYNC_DIVIDER = 2L
        private const val PREFERRED_REFRESH_HZ = 60f
    }
}
