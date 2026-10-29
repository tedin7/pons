package io.github.pons.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test

/** Loading screen at the OnePlus Pad 3 size: first-time setup, and a game starting over its artwork. */
class LoadingShot {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(screenHeight = 2400, screenWidth = 3392, xdpi = 420, ydpi = 420,
            density = Density.create(420), orientation = ScreenOrientation.LANDSCAPE),
        theme = "android:Theme.Material.NoActionBar",
    )

    @Test fun setup() {
        val v = LoadingView(paparazzi.context)
        v.setBackdrop(null)
        v.show("Setting up Linux, step 1 of 4", "Downloading the Linux system · 24 of 39 MB", 0.61f, ready = false)
        paparazzi.snapshot(v)
    }

    @Test fun gameStarting() {
        val art = Bitmap.createBitmap(1920, 620, Bitmap.Config.ARGB_8888).also { bmp ->
            val c = Canvas(bmp)
            c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 1920f, 620f, 0xFF8A4B2A.toInt(), 0xFF2B2F45.toInt(), Shader.TileMode.CLAMP) })
            c.drawCircle(1400f, 220f, 120f, Paint().apply { color = 0xFFF2C46B.toInt() })
        }
        val v = LoadingView(paparazzi.context)
        v.setBackdrop(art)
        v.show("Starting Crusader Kings III", "Waiting for your Steam account to sign in…", null, ready = true)
        paparazzi.snapshot(v)
    }
}
