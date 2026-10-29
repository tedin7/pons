package io.github.pons

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import java.io.File
import android.view.WindowInsets
import io.github.pons.display.DisplayActivity
import io.github.pons.linux.Components
import io.github.pons.linux.ComponentInstaller
import io.github.pons.linux.GameLibrary
import io.github.pons.linux.LinuxPaths
import io.github.pons.linux.PackageSetup
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
    private var games: List<Entry> = emptyList()
    private val listener: (String) -> Unit = { line -> runOnUiThread { screen.appendLog(line); refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        settings = DisplaySettings(this)
        screen = LauncherView(this, STEAM, TOOLS, this, footer())
        // Android 15 draws apps edge to edge: keep the content clear of the system bars.
        screen.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(screen)
        Thread({
            UpdateCheck(this).find()?.let { update ->
                runOnUiThread {
                    screen.showUpdate(update.version) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.page))) }
                }
            }
        }, "pons-update-check").start()
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
        loadLibrary()
        startSteamAhead()
    }

    /**
     * Steam takes ~20 s to start and sign in; start it in the background as soon as
     * Pons opens, once it has been set up and signed in before, so Play is quick.
     */
    private fun startSteamAhead() {
        if (!settings.steamAhead || stoppedByUser || LinuxSessionService.busy() || LinuxSession.program != null) return
        val paths = LinuxPaths(this)
        val signedInBefore = File(paths.rootfs, "root/.local/share/Steam/config/loginusers.vdf").length() > 0
        val ready = ComponentInstaller(this, paths).missing(Components.ALL).isEmpty() &&
            PackageSetup.groupsFor(LinuxSession.STEAM_BACKGROUND).all(PackageSetup(this, paths)::isDone)
        if (signedInBefore && ready) LinuxSessionService.start(this, LinuxSession.STEAM_BACKGROUND)
    }

    /** Reads the installed Steam games (and decodes their artwork) off the main thread. */
    private fun loadLibrary() = Thread({
        val library = GameLibrary(LinuxPaths(this))
        val width = resources.displayMetrics.widthPixels / 2
        val entries = library.games().map { game ->
            val played = if (game.lastPlayed > 0)
                "Played " + DateUtils.getRelativeTimeSpanString(game.lastPlayed * 1000).toString().lowercase()
            else "Installed"
            Entry(game.name, played, "pons-game ${game.appId}", R.drawable.ic_gamepad, Look.ACCENT, art = library.art(game, width))
        }
        runOnUiThread { games = entries; screen.setGames(entries) }
    }, "pons-library").start()

    override fun onStop() {
        LinuxSession.removeListener(listener)
        super.onStop()
    }

    /** Version line: the app and the translator / driver it installs. */
    private fun footer() = "Pons ${BuildConfig.VERSION_NAME}  ·  Box64 ${Components.BOX64.version.substringAfterLast('-')}  ·  " +
        "Mesa Turnip ${Components.MESA.version.substringAfterLast('-')}"

    private fun refresh() {
        val power = getSystemService(PowerManager::class.java)
        val program = LinuxSession.program.takeIf { LinuxSession.running }
        val background = program == LinuxSession.STEAM_BACKGROUND
        screen.show(program.takeUnless { background },
            power.isIgnoringBatteryOptimizations(packageName), settings.resolution.label,
            settings.steamAhead, steamReady = background)
    }

    override fun run(program: String) {
        // Pressing the same program again just brings its screen back: restarting
        // Steam would throw away a login in progress.
        stoppedByUser = false
        if (LinuxSession.program != program) {
            LinuxSession.stage = LinuxSession.Stage("Starting Linux")
            LinuxSessionService.start(this, program)
        }
        openDisplay(program)
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

    override fun openScreen() = openDisplay(LinuxSession.program)

    override fun stop() {
        // Stop means stop: do not bring Steam back on the next return to the launcher.
        stoppedByUser = true
        LinuxSessionService.stop(this)
        screen.postDelayed(::refresh, 500)
    }

    override fun nextResolution(): String = settings.nextResolution().label

    override fun toggleSteamAhead(): Boolean {
        settings.steamAhead = !settings.steamAhead
        if (!settings.steamAhead && LinuxSession.program == LinuxSession.STEAM_BACKGROUND) LinuxSessionService.stop(this)
        refresh()
        return settings.steamAhead
    }

    /** The Linux screen, with the program's name and artwork for its loading screen. */
    private fun openDisplay(program: String?) {
        val entry = (listOf(STEAM) + games + TOOLS).firstOrNull { it.program == program }
        startActivity(Intent(this, DisplayActivity::class.java)
            .putExtra(DisplayActivity.EXTRA_TITLE, entry?.title)
            .putExtra(DisplayActivity.EXTRA_APP_ID, program?.removePrefix("pons-game ")?.takeIf { it.all(Char::isDigit) }))
    }

    companion object {
        /** Set by Stop, cleared by the next Play: Steam is not started ahead meanwhile. */
        private var stoppedByUser = false
        private const val EXTRA_PROGRAM = "program"
        private val STEAM =
            Entry("Steam", "Sign in, download and update games", "pons-steam", R.drawable.ic_download, 0xFF7FA7E8.toInt(), action = "Open")
        private val TOOLS = listOf(
            Entry("Vulkan cube", "Check that graphics work", "vkcube --wsi xcb"),
            Entry("Graphics benchmark", "Measure how fast the tablet is", "pons-bench"),
            Entry("Tune translation", "Find the best Box64 settings", "pons-transbench"),
        )
    }
}
