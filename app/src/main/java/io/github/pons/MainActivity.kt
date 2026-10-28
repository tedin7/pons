package io.github.pons

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.pons.display.DisplayActivity
import io.github.pons.session.DisplaySettings
import io.github.pons.session.LinuxSession
import io.github.pons.session.LinuxSessionService

/** Launcher: pick something to run, watch the Linux log. */
class MainActivity : Activity() {

    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private val listener: (String) -> Unit = { line -> runOnUiThread { append(line) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            PROGRAMS.forEach { (label, program) ->
                addView(Button(context).apply {
                    text = label
                    setOnClickListener { run(program) }
                })
            }
            addView(Button(context).apply {
                text = "Batteria"
                setOnClickListener { askUnrestrictedBattery() }
            })
            addView(Button(context).apply {
                text = "Ferma"
                setOnClickListener { LinuxSessionService.stop(context) }
            })
            addView(Button(context).apply {
                val settings = DisplaySettings(context)
                text = settings.resolution.label
                setOnClickListener { text = settings.nextResolution().label }
            })
            addView(Button(context).apply {
                text = "Schermo"
                setOnClickListener { openDisplay() }
            })
        }
        output = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setPadding(32, 16, 32, 32)
        }
        scroll = ScrollView(this).apply { addView(output) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 48, 0, 0)
            addView(buttons)
            addView(scroll)
        })
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
        output.text = ""
        LinuxSession.log().forEach(::append)
        LinuxSession.addListener(listener)
    }

    override fun onStop() {
        LinuxSession.removeListener(listener)
        super.onStop()
    }

    private fun run(program: String) {
        // Pressing the same program again just brings its screen back: restarting
        // Steam would throw away a login in progress.
        if (LinuxSession.program != program) LinuxSessionService.start(this, program)
        openDisplay()
    }

    /**
     * Games use a lot of memory; with battery optimisation on, OEM memory managers
     * kill the app as soon as it leaves the screen. Ask Android to exempt us.
     */
    private fun askUnrestrictedBattery() {
        val power = getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(packageName)) {
            append("Batteria già senza restrizioni.")
            return
        }
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")))
    }

    private fun openDisplay() = startActivity(Intent(this, DisplayActivity::class.java))

    private fun append(line: String) {
        output.append(line + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    companion object {
        private const val EXTRA_PROGRAM = "program"
        private val PROGRAMS = listOf(
            "Cubo Vulkan" to "vkcube --wsi xcb",
            "Prova grafica" to "pons-bench",
            "Ottimizza traduzione" to "pons-transbench",
            "Steam" to "pons-steam",
            "Crusader Kings III" to "pons-ck3",
        )
    }
}
