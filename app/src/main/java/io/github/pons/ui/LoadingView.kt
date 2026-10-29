package io.github.pons.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import io.github.pons.R
import io.github.pons.ui.Look.dp
import io.github.pons.ui.Look.text

/**
 * Full-screen loading screen shown over the Linux display while Linux installs or a
 * program starts: the game's artwork (or the logo) behind, what is happening, a
 * progress bar, and the program's latest line.
 */
class LoadingView(context: Context) : FrameLayout(context) {

    private val backdrop = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        alpha = 0.8f
    }
    /** Without artwork: a warm glow and a large faint emblem instead of a black screen. */
    private val glow = View(context).apply {
        background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = context.dp(520).toFloat()
            colors = intArrayOf((Look.ACCENT and 0xFFFFFF) or 0x2E000000, 0x00000000)
        }
    }
    private val watermark = ImageView(context).apply {
        setImageResource(R.drawable.pons_emblem)
        alpha = 0.05f
        rotation = -8f
        adjustViewBounds = true
    }
    private val title = context.text("", 30f, Look.TEXT, bold = true).apply { gravity = Gravity.CENTER }
    private val detail = context.text("", 17f, Look.MUTED).apply {
        gravity = Gravity.CENTER
        setPadding(0, context.dp(10), 0, 0)
    }
    private val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = PROGRESS_STEPS
        progressTintList = ColorStateList.valueOf(Look.ACCENT)
        indeterminateTintList = ColorStateList.valueOf(Look.ACCENT)
        progressBackgroundTintList = ColorStateList.valueOf(Look.SURFACE_HIGH)
        // The platform bar is a 4 dp line; thicker reads better across a room.
        scaleY = 2.5f
    }
    private val percent = context.text("", 14f, Look.MUTED).apply { gravity = Gravity.END }
    private val hint = context.text("Tap to show the screen", 15f, Look.TEXT, bold = true).apply {
        gravity = Gravity.CENTER
        visibility = INVISIBLE
    }

    init {
        setBackgroundColor(Look.BACKGROUND)
        isClickable = true
        addView(glow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(watermark, LayoutParams(context.dp(900), LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = -context.dp(160)
            bottomMargin = -context.dp(60)
        })
        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // Light at the top, dark around the text and at the bottom, so it reads over any artwork.
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x22000000, (Look.BACKGROUND and 0xFFFFFF) or 0xB0000000.toInt(), Look.BACKGROUND))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ImageView(context).apply {
                setImageResource(R.drawable.pons_emblem)
                adjustViewBounds = true
            }, LinearLayout.LayoutParams(context.dp(112), LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(28) })
            addView(title, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(detail, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(bar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(10)).apply { topMargin = context.dp(32) })
            addView(percent, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8) })
        }, LayoutParams(context.dp(560), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(hint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            bottomMargin = context.dp(48)
        })
    }

    fun setBackdrop(art: Bitmap?) {
        backdrop.setImageBitmap(art)
        backdrop.visibility = if (art != null) VISIBLE else GONE
        glow.visibility = if (art != null) GONE else VISIBLE
        watermark.visibility = glow.visibility
    }

    /**
     * [fraction] null shows a moving bar (no measure); [ready] shows the tap hint;
     * [failed] colours the title as an error and stops the bar.
     */
    fun show(title: String, detail: String, fraction: Float?, ready: Boolean, failed: Boolean = false) {
        this.title.text = title
        this.title.setTextColor(if (failed) Look.DANGER else Look.TEXT)
        this.detail.text = detail
        bar.visibility = if (failed) INVISIBLE else VISIBLE
        bar.isIndeterminate = fraction == null && !failed
        if (fraction != null) bar.progress = (fraction.coerceIn(0f, 1f) * PROGRESS_STEPS).toInt()
        percent.text = if (fraction != null && !failed) "${(fraction * 100).toInt().coerceIn(0, 100)} %" else ""
        hint.visibility = if (ready || failed) VISIBLE else INVISIBLE
        hint.text = if (failed) "Tap to go back" else "Tap to show the screen"
    }

    private companion object {
        const val PROGRESS_STEPS = 1000
    }
}
