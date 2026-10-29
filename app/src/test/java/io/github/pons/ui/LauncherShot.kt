package io.github.pons.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import org.junit.Rule
import org.junit.Test
import io.github.pons.R

/** Launcher screenshots at the OnePlus Pad 3 size (3392x2400, 420 dpi); goldens in src/test/snapshots. */
class LauncherShot {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(screenHeight = 2400, screenWidth = 3392, xdpi = 420, ydpi = 420,
            density = Density.create(420), orientation = ScreenOrientation.LANDSCAPE),
        theme = "android:Theme.Material.NoActionBar",
    )

    private val steam =
        Entry("Steam", "Sign in, download and update games", "pons-steam", R.drawable.ic_download, 0xFF7FA7E8.toInt(), action = "Open")

    /** Stand-in for Steam artwork: a warm sky over dark hills. */
    private fun art(): Bitmap = Bitmap.createBitmap(920, 430, Bitmap.Config.ARGB_8888).also { bmp ->
        val c = Canvas(bmp)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, 430f, 0xFF8A4B2A.toInt(), 0xFF2B2F45.toInt(), Shader.TileMode.CLAMP) })
        c.drawCircle(700f, 150f, 70f, Paint().apply { color = 0xFFF2C46B.toInt() })
        c.drawRect(0f, 300f, 920f, 430f, Paint().apply { color = 0xFF151820.toInt() })
    }

    private fun games() = listOf(
        Entry("Crusader Kings III", "Played 2 days ago", "pons-game 1158310", R.drawable.ic_gamepad, art = art()),
        Entry("A Game Without Artwork", "Installed", "pons-game 42", R.drawable.ic_gamepad),
    )
    private val tools = listOf(
        Entry("Vulkan cube", "Check that graphics work", "vkcube"),
        Entry("Graphics benchmark", "Measure how fast the tablet is", "pons-bench"),
        Entry("Tune translation", "Find the best Box64 settings", "pons-transbench"),
    )
    private val actions = object : LauncherActions {
        override fun run(program: String) {}
        override fun openScreen() {}
        override fun stop() {}
        override fun nextResolution() = ""
        override fun unrestrictBattery() {}
    }

    @Test fun idle() {
        val v = LauncherView(paparazzi.context, steam, tools, actions, "Pons 0.1.0  ·  Box64 pp12  ·  Mesa Turnip pp10")
        v.setGames(games())
        v.show(null, true, "Balanced 1600×1132")
        paparazzi.snapshot(v)
    }

    @Test fun running() {
        val v = LauncherView(paparazzi.context, steam, tools, actions, "Pons 0.1.0  ·  Box64 pp12  ·  Mesa Turnip pp10")
        v.setGames(games())
        v.show("pons-game 1158310", false, "Balanced 1600×1132")
        v.setLog(listOf("[ck3] start", "[ck3] loading"))
        paparazzi.snapshot(v)
    }

    @Test fun portrait() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig(screenHeight = 3392, screenWidth = 2400, xdpi = 420, ydpi = 420,
            density = Density.create(420), orientation = ScreenOrientation.PORTRAIT))
        val v = LauncherView(paparazzi.context, steam, tools, actions, "Pons 0.1.0")
        v.show("pons-steam", true, "Balanced 1600×1132")
        paparazzi.snapshot(v)
    }
}
