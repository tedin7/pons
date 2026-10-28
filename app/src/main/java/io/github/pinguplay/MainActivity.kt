package io.github.pinguplay

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView

/** Milestone 1: prove that a full Debian runs inside the app. */
class MainActivity : Activity() {

    private lateinit var output: TextView
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setPadding(32, 48, 32, 32)
        }
        scroll = ScrollView(this).apply { addView(output) }
        setContentView(scroll)
        Thread(::startLinux, "pinguplay-linux").start()
    }

    private fun startLinux() {
        try {
            val installer = RootfsInstaller(this)
            if (!installer.isInstalled()) {
                show("Installo Debian (una volta sola)…")
                installer.install { n -> show("  file estratti: $n") }
            }
            show("Avvio Linux…\n")
            val code = LinuxRunner(this, installer.rootfsDir).run(TEST_SCRIPT, ::show)
            show("\nFinito, codice di uscita $code")
        } catch (e: Exception) {
            Log.e(TAG, "Linux start failed", e)
            show("ERRORE: ${e.message}")
        }
    }

    private fun show(line: String) {
        Log.i(TAG, line)
        runOnUiThread {
            output.append(line + "\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    companion object {
        private const val TAG = "Pinguplay"
        private val TEST_SCRIPT = """
            echo "== Sistema =="; uname -srm
            grep PRETTY_NAME /etc/os-release
            echo "== Utente =="; id
            echo "== Scheda video =="; ls -l /dev/kgsl-3d0 2>&1
            echo "== Memoria =="; head -1 /proc/meminfo
            echo "PINGUPLAY-OK"
        """.trimIndent()
    }
}
