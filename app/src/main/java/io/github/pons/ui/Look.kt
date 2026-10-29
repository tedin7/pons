package io.github.pons.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.TextView

/** Colours and small view builders shared by the launcher screens (dark, warm accent). */
object Look {
    const val BACKGROUND = 0xFF0F1115.toInt()
    const val SURFACE = 0xFF1A1D24.toInt()
    const val SURFACE_HIGH = 0xFF242833.toInt()
    const val OUTLINE = 0xFF2E3340.toInt()
    const val TEXT = 0xFFECEFF4.toInt()
    const val MUTED = 0xFF9AA3B2.toInt()
    const val ACCENT = 0xFFE0A33A.toInt()
    const val ON_ACCENT = 0xFF1B1405.toInt()
    const val OK = 0xFF5BC28A.toInt()
    const val DANGER = 0xFFE06C5B.toInt()
    private const val RIPPLE = 0x33FFFFFF

    /** [a] blended towards [b]: t = 0 gives a, t = 1 gives b. */
    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = ((a shr shift and 0xFF) * (1 - t) + (b shr shift and 0xFF) * t).toInt() shl shift
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }

    fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Rounded rectangle; with [clickable] it also shows the touch ripple. */
    fun Context.panel(color: Int, radiusDp: Int = 20, stroke: Int? = null, clickable: Boolean = false) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            stroke?.let { setStroke(dp(1), it) }
        }.let { shape ->
            if (clickable) RippleDrawable(ColorStateList.valueOf(RIPPLE), shape, null) else shape
        }

    fun Context.text(value: String, sizeSp: Float, color: Int = TEXT, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            // OEM system fonts (OnePlus Sans) ignore bold styles and weights: embolden the glyphs.
            if (bold) paint.isFakeBoldText = true
        }

    /** Small all-caps heading above a group. */
    fun Context.section(value: String) = text(value.uppercase(), 12f, MUTED, bold = true).apply {
        letterSpacing = 0.12f
        setPadding(dp(4), dp(28), 0, dp(10))
    }

    /** Pill-shaped button: filled with [fill], or outlined when [fill] is null. */
    fun Context.pill(label: String, fill: Int? = null, textColor: Int = TEXT, onClick: (View) -> Unit) =
        text(label, 15f, textColor, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(20), 0, dp(20), 0)
            background = panel(fill ?: SURFACE, 22, stroke = if (fill == null) OUTLINE else null, clickable = true)
            setOnClickListener(onClick)
        }
}
