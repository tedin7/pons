package io.github.pinguplay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.pinguplay.display.DisplayActivity
import io.github.pinguplay.session.DisplaySettings
import io.github.pinguplay.session.LinuxSession
import io.github.pinguplay.session.LinuxSessionService

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
        LinuxSessionService.start(this, program)
        openDisplay()
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
            "Prova grafica" to "pinguplay-bench",
            "Ottimizza traduzione" to "pinguplay-transbench",
            "Steam" to "pinguplay-steam",
        )
    }
}
