package io.github.pons.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.pons.R
import io.github.pons.ui.Look.dp
import io.github.pons.ui.Look.panel
import io.github.pons.ui.Look.pill
import io.github.pons.ui.Look.section
import io.github.pons.ui.Look.text

/** Something the launcher can start: a game card or a small tool. */
data class Entry(
    val title: String,
    val subtitle: String,
    val program: String,
    val icon: Int = 0,
    val color: Int = Look.ACCENT,
    val action: String = "Play",
)

/** What the launcher does when touched; the activity supplies it. */
interface LauncherActions {
    fun run(program: String)
    fun openScreen()
    fun stop()
    fun nextResolution(): String
    fun unrestrictBattery()
}

/**
 * The launcher screen: status, game cards, settings and tools side by side, and the
 * technical log folded away at the bottom. Plain views, no theme dependencies.
 */
class LauncherView(
    context: Context,
    private val games: List<Entry>,
    private val tools: List<Entry>,
    private val actions: LauncherActions,
    private val footer: String = "",
) : LinearLayout(context) {

    private class Card(val view: View, val chip: View, val button: TextView, val entry: Entry)

    private val statusDot = View(context)
    private val statusText = context.text("", 14f, Look.MUTED, bold = true)
    private val screenButton = context.pill("Open screen") { actions.openScreen() }
    private val stopButton = context.pill("Stop", fill = Look.DANGER, textColor = Look.TEXT) { actions.stop() }
    private val cards = mutableMapOf<String, Card>()
    private val resolutionValue = context.text("", 16f, Look.TEXT, bold = true)
    private val batteryValue = context.text("", 16f, Look.TEXT, bold = true)
    private val batteryChevron = context.text("›", 26f, Look.MUTED)
    private val logText = context.text("", 12f, Look.MUTED).apply {
        typeface = Typeface.MONOSPACE
        setLineSpacing(0f, 1.15f)
    }
    private val logScroll = ScrollView(context).apply {
        addView(logText)
        visibility = GONE
        setPadding(context.dp(28), 0, context.dp(28), context.dp(16))
    }
    private val logToggle = context.text("", 14f, Look.MUTED, bold = true)

    init {
        orientation = VERTICAL
        setBackgroundColor(Look.BACKGROUND)
        addView(header())
        val body = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(context.dp(32), 0, context.dp(32), context.dp(32))
            addView(context.section("Games"))
            addView(gameRow())
            // Settings and tools side by side on a wide screen, one above the other otherwise.
            if (resources.configuration.screenWidthDp >= WIDE_DP) {
                addView(LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    addView(settingsColumn(), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                    addView(space(20, horizontal = true))
                    addView(toolsColumn(), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                })
            } else {
                addView(settingsColumn())
                addView(toolsColumn())
            }
            if (footer.isNotEmpty()) addView(context.text(footer, 13f, Look.mix(Look.MUTED, Look.BACKGROUND, 0.35f)).apply {
                setPadding(context.dp(4), context.dp(28), 0, 0)
            })
        }
        addView(ScrollView(context).apply { addView(body) }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(logPanel())
    }

    /** Running program (null = idle), battery state, current resolution label. */
    fun show(running: String?, batteryFree: Boolean, resolution: String) {
        val name = (games + tools).firstOrNull { it.program == running }?.title
        statusText.text = if (name != null) "$name is running" else "Ready"
        statusText.setTextColor(if (name != null) Look.TEXT else Look.MUTED)
        (statusDot.background as GradientDrawable).setColor(if (name != null) Look.OK else Look.MUTED)
        stopButton.visibility = if (running != null) VISIBLE else GONE
        screenButton.visibility = if (running != null) VISIBLE else GONE
        cards.forEach { (program, card) ->
            val on = program == running
            card.chip.visibility = if (on) VISIBLE else INVISIBLE
            card.button.text = if (on) "Back to game" else card.entry.action
            card.view.background = cardBackground(card.entry.color, on)
        }
        batteryValue.text = if (batteryFree) "Unrestricted  ✓" else "Restricted: tap to fix"
        batteryValue.setTextColor(if (batteryFree) Look.OK else Look.ACCENT)
        batteryChevron.visibility = if (batteryFree) INVISIBLE else VISIBLE
        resolutionValue.text = resolution
    }

    fun setLog(lines: List<String>) {
        logText.text = lines.joinToString("\n").ifEmpty { "Nothing yet: start a game or a tool." }
        scrollLogDown()
    }

    fun appendLog(line: String) {
        if (logText.text.startsWith("Nothing yet")) logText.text = ""
        logText.append(if (logText.text.isEmpty()) line else "\n$line")
        scrollLogDown()
    }

    private fun scrollLogDown() = logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }

    private fun header() = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(32), context.dp(24), context.dp(32), context.dp(4))
        addView(ImageView(context).apply {
            setImageResource(R.drawable.pons_emblem)
            adjustViewBounds = true
        }, LayoutParams(context.dp(60), LayoutParams.WRAP_CONTENT))
        addView(context.text("Pons", 30f, Look.TEXT, bold = true).apply {
            setPadding(context.dp(16), 0, context.dp(20), 0)
        })
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = context.panel(Look.SURFACE, 16)
            setPadding(context.dp(14), context.dp(8), context.dp(16), context.dp(8))
            statusDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            addView(statusDot, LayoutParams(context.dp(9), context.dp(9)).apply { marginEnd = context.dp(10) })
            addView(statusText)
        })
        addView(space(1, horizontal = true), LayoutParams(0, 1, 1f))
        addView(screenButton)
        addView(space(10, horizontal = true))
        addView(stopButton)
    }

    private fun gameRow() = LinearLayout(context).apply {
        orientation = HORIZONTAL
        games.forEachIndexed { i, game ->
            if (i > 0) addView(space(20, horizontal = true))
            addView(card(game), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun card(game: Entry): View {
        val chip = context.text("●  Running", 13f, Look.OK, bold = true).apply {
            background = context.panel(Look.mix(Look.OK, Look.SURFACE, 0.85f), 14)
            setPadding(context.dp(12), context.dp(6), context.dp(14), context.dp(6))
            visibility = INVISIBLE
        }
        val button = context.pill(game.action, fill = Look.ACCENT, textColor = Look.ON_ACCENT) { actions.run(game.program) }
        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(context.dp(28), context.dp(28), context.dp(28), context.dp(26))
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.TOP
                addView(badge(game.icon, game.color))
                addView(space(1, horizontal = true), LayoutParams(0, 1, 1f))
                addView(chip)
            })
            addView(context.text(game.title, 24f, Look.TEXT, bold = true).apply {
                setPadding(0, context.dp(22), 0, context.dp(6))
            })
            addView(context.text(game.subtitle, 15f, Look.MUTED))
            addView(space(24))
            addView(button, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        // The card: content over a large faint copy of the game's symbol in the corner.
        val view = FrameLayout(context).apply {
            clipToOutline = true
            isClickable = true
            setOnClickListener { actions.run(game.program) }
            addView(ImageView(context).apply {
                setImageResource(game.icon)
                setColorFilter(game.color)
                alpha = 0.08f
            }, FrameLayout.LayoutParams(context.dp(150), context.dp(150), Gravity.END or Gravity.BOTTOM).apply {
                marginEnd = context.dp(22)
                bottomMargin = context.dp(18)
            })
            addView(content)
        }
        cards[game.program] = Card(view, chip, button, game)
        view.background = cardBackground(game.color, false)
        return view
    }

    /** Surface tinted towards the game's colour in one corner; a coloured edge while it runs. */
    private fun cardBackground(color: Int, running: Boolean) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(Look.mix(color, Look.SURFACE, 0.82f), Look.SURFACE, Look.SURFACE),
    ).apply {
        cornerRadius = context.dp(28).toFloat()
        setStroke(context.dp(if (running) 2 else 1), if (running) Look.mix(color, Look.SURFACE, 0.2f) else Look.OUTLINE)
    }

    private fun badge(icon: Int, color: Int) = ImageView(context).apply {
        setImageResource(icon)
        setColorFilter(Look.ON_ACCENT)
        background = context.panel(color, 18)
        val pad = context.dp(15)
        setPadding(pad, pad, pad, pad)
        layoutParams = LayoutParams(context.dp(64), context.dp(64))
    }

    private fun settingsColumn() = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(context.section("Settings"))
        addView(listRow("Game resolution", resolutionValue, context.text("›", 26f, Look.MUTED)) {
            resolutionValue.text = actions.nextResolution()
        })
        addView(space(10))
        addView(listRow("Battery", batteryValue, batteryChevron) { actions.unrestrictBattery() })
    }

    private fun toolsColumn() = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(context.section("Tools"))
        tools.forEachIndexed { i, tool ->
            if (i > 0) addView(space(10))
            addView(listRow(tool.title, context.text(tool.subtitle, 13f, Look.MUTED),
                context.text("›", 26f, Look.MUTED), captionFirst = false) { actions.run(tool.program) })
        }
    }

    /**
     * A rounded row with a chevron on the right: a small grey caption above a bold value
     * (settings), or with [captionFirst] false a bold title above a grey line (tools).
     */
    private fun listRow(caption: String, value: TextView, chevron: TextView, captionFirst: Boolean = true, onClick: () -> Unit) =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = context.panel(Look.SURFACE, 18, clickable = true)
            setPadding(context.dp(22), context.dp(14), context.dp(20), context.dp(14))
            setOnClickListener { onClick() }
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                if (captionFirst) {
                    addView(context.text(caption, 13f, Look.MUTED).apply { setPadding(0, 0, 0, context.dp(3)) })
                    addView(value)
                } else {
                    addView(context.text(caption, 16f, Look.TEXT, bold = true).apply { setPadding(0, 0, 0, context.dp(3)) })
                    addView(value)
                }
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(chevron)
        }

    private fun logPanel() = LinearLayout(context).apply {
        orientation = VERTICAL
        setBackgroundColor(Look.SURFACE)
        logToggle.text = "›  Technical log"
        logToggle.setPadding(context.dp(32), context.dp(16), context.dp(32), context.dp(16))
        logToggle.setOnClickListener {
            val open = logScroll.visibility != VISIBLE
            logScroll.visibility = if (open) VISIBLE else GONE
            logToggle.text = if (open) "⌄  Technical log" else "›  Technical log"
            if (open) scrollLogDown()
        }
        addView(logToggle)
        addView(logScroll, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(260)))
    }

    private companion object {
        const val WIDE_DP = 1000
    }

    private fun space(sizeDp: Int, horizontal: Boolean = false) = FrameLayout(context).apply {
        layoutParams = if (horizontal) LayoutParams(context.dp(sizeDp), 1) else LayoutParams(1, context.dp(sizeDp))
    }
}
