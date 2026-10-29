package io.github.pons

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowInsets
import io.github.pons.display.DisplayActivity
import io.github.pons.linux.Components
import io.github.pons.session.DisplaySettings
import io.github.pons.session.LinuxSession
import io.github.pons.session.LinuxSessionService
import io.github.pons.ui.Entry
import io.github.pons.ui.LauncherActions
import io.github.pons.ui.LauncherView
import io.github.pons.ui.Look

/** Launcher: pick something to run, see what is running, read the Linux log. */
class MainActivity : Activity(), LauncherActions {

    private lateinit var screen: LauncherView
    private lateinit var settings: DisplaySettings
    private val listener: (String) -> Unit = { line -> runOnUiThread { screen.appendLog(line); refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        settings = DisplaySettings(this)
        screen = LauncherView(this, GAMES, TOOLS, this, footer())
        // Android 15 draws apps edge to edge: keep the content clear of the system bars.
        screen.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(screen)
    }

    override fun onResume() {
        super.onResume()
        // Debug builds only: `am start --es program "<command>"` runs a program, for tests.
        val program = intent.getStringExtra(EXTRA_PROGRAM)
        if (BuildConfig.DEBUG && program != null) {
            intent.removeExtra(EXTRA_PROGRAM)
            run(program)
        }
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        intent = newIntent
    }

    override fun onStart() {
        super.onStart()
        screen.setLog(LinuxSession.log())
        refresh()
        LinuxSession.addListener(listener)
    }

    override fun onStop() {
        LinuxSession.removeListener(listener)
        super.onStop()
    }

    /** Version line: the app and the translator / driver it installs. */
    private fun footer() = "Pons ${BuildConfig.VERSION_NAME}  ·  Box64 ${Components.BOX64.version.substringAfterLast('-')}  ·  " +
        "Mesa Turnip ${Components.MESA.version.substringAfterLast('-')}"

    private fun refresh() {
        val power = getSystemService(PowerManager::class.java)
        screen.show(LinuxSession.program.takeIf { LinuxSession.running },
            power.isIgnoringBatteryOptimizations(packageName), settings.resolution.label)
    }

    override fun run(program: String) {
        // Pressing the same program again just brings its screen back: restarting
        // Steam would throw away a login in progress.
        if (LinuxSession.program != program) LinuxSessionService.start(this, program)
        openDisplay()
    }

    /**
     * Games use a lot of memory; with battery optimisation on, OEM memory managers
     * kill the app as soon as it leaves the screen. Ask Android to exempt us.
     */
    override fun unrestrictBattery() {
        val power = getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(packageName)) return
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")))
    }

    override fun openScreen() = openDisplay()

    override fun stop() {
        LinuxSessionService.stop(this)
        screen.postDelayed(::refresh, 500)
    }

    override fun nextResolution(): String = settings.nextResolution().label

    private fun openDisplay() = startActivity(Intent(this, DisplayActivity::class.java))

    companion object {
        private const val EXTRA_PROGRAM = "program"
        private val GAMES = listOf(
            Entry("Crusader Kings III", "Medieval grand strategy, Linux version", "pons-ck3", R.drawable.ic_crown, Look.ACCENT),
            Entry("Steam", "Sign in, download and update games", "pons-steam", R.drawable.ic_download, 0xFF7FA7E8.toInt(), action = "Open"),
        )
        private val TOOLS = listOf(
            Entry("Vulkan cube", "Check that graphics work", "vkcube --wsi xcb"),
            Entry("Graphics benchmark", "Measure how fast the tablet is", "pons-bench"),
            Entry("Tune translation", "Find the best Box64 settings", "pons-transbench"),
        )
    }
}
