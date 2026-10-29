package io.github.pons.ui

import android.content.Context
import android.graphics.Bitmap
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
    /** Wide artwork (Steam's library hero or header); null shows [icon] instead. */
    val art: Bitmap? = null,
)

/** What the launcher does when touched; the activity supplies it. */
interface LauncherActions {
    fun run(program: String)
    fun openScreen()
    fun stop()
    fun nextResolution(): String
    fun unrestrictBattery()
    /** Flips "start Steam when Pons opens"; returns the new value. */
    fun toggleSteamAhead(): Boolean
}

/**
 * The launcher screen: status, Steam and the installed games as cards, settings and
 * tools side by side, and the technical log folded away at the bottom. Plain views.
 */
class LauncherView(
    context: Context,
    private val steam: Entry,
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
    private var games: List<Entry> = emptyList()
    private var running: String? = null
    private val gameGrid = LinearLayout(context).apply { orientation = VERTICAL }
    private val resolutionValue = context.text("", 16f, Look.TEXT, bold = true)
    private val batteryValue = context.text("", 16f, Look.TEXT, bold = true)
    private val batteryChevron = context.text("›", 26f, Look.MUTED)
    private val steamAheadValue = context.text("", 16f, Look.TEXT, bold = true)
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
    private val updateBanner = LinearLayout(context).apply { visibility = GONE }

    init {
        orientation = VERTICAL
        setBackgroundColor(Look.BACKGROUND)
        addView(header())
        val body = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(context.dp(32), 0, context.dp(32), context.dp(32))
            addView(updateBanner)
            addView(context.section("Games"))
            addView(gameGrid)
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
        setGames(emptyList())
    }

    /** The installed games, shown after the Steam card; rebuilt when the library changes. */
    fun setGames(list: List<Entry>) {
        games = list
        cards.clear()
        gameGrid.removeAllViews()
        val wide = resources.configuration.screenWidthDp >= WIDE_DP
        val columns = if (wide) 3 else 2
        val cells: List<View> = listOf(card(steam)) + games.map(::card) + listOfNotNull(if (games.isEmpty()) emptyCard() else null)
        cells.chunked(columns).forEachIndexed { r, row ->
            if (r > 0) gameGrid.addView(space(20))
            gameGrid.addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                for (c in 0 until columns) {
                    if (c > 0) addView(space(20, horizontal = true))
                    // Empty cells keep every card the same width; cards share one minimum height.
                    addView(row.getOrNull(c) ?: space(1, horizontal = true), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                }
            })
        }
        refreshCards()
    }

    /**
     * Running program (null = idle), battery state, current resolution label, and
     * whether Steam starts ahead ([steamReady]: it is running and waiting for a game).
     */
    fun show(running: String?, batteryFree: Boolean, resolution: String, steamAhead: Boolean = false, steamReady: Boolean = false) {
        this.running = running
        val name = (listOf(steam) + games + tools).firstOrNull { it.program == running }?.title
        statusText.text = when {
            name != null -> "$name is running"
            steamReady -> "Ready · Steam started"
            else -> "Ready"
        }
        statusText.setTextColor(if (name != null) Look.TEXT else Look.MUTED)
        (statusDot.background as GradientDrawable).setColor(if (name != null) Look.OK else Look.MUTED)
        stopButton.visibility = if (running != null) VISIBLE else GONE
        screenButton.visibility = if (running != null) VISIBLE else GONE
        refreshCards()
        batteryValue.text = if (batteryFree) "Unrestricted  ✓" else "Restricted: tap to fix"
        batteryValue.setTextColor(if (batteryFree) Look.OK else Look.ACCENT)
        batteryChevron.visibility = if (batteryFree) INVISIBLE else VISIBLE
        resolutionValue.text = resolution
        steamAheadValue.text = if (steamAhead) "On: games start faster" else "Off"
    }

    /** A banner above the games: "Pons [version] is available", with a download button. */
    fun showUpdate(version: String, onDownload: () -> Unit) = with(updateBanner) {
        removeAllViews()
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = context.panel(Look.mix(Look.ACCENT, Look.SURFACE, 0.85f), 18, stroke = Look.mix(Look.ACCENT, Look.SURFACE, 0.5f))
        setPadding(context.dp(22), context.dp(12), context.dp(12), context.dp(12))
        addView(context.text("Pons $version is available", 15f, Look.TEXT, bold = true), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(context.pill("Download", fill = Look.ACCENT, textColor = Look.ON_ACCENT) { onDownload() })
        (layoutParams as? LayoutParams)?.topMargin = context.dp(16)
        visibility = VISIBLE
    }

    private fun refreshCards() = cards.forEach { (program, card) ->
        val on = program == running
        card.chip.visibility = if (on) VISIBLE else INVISIBLE
        card.button.text = if (on) "Back to game" else card.entry.action
        card.view.foreground = cardEdge(card.entry.color, on)
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

    private fun card(game: Entry): View {
        val chip = context.text("●  Running", 13f, Look.OK, bold = true).apply {
            background = context.panel(Look.mix(Look.OK, Look.SURFACE, 0.85f), 14)
            setPadding(context.dp(12), context.dp(6), context.dp(14), context.dp(6))
            visibility = INVISIBLE
        }
        val button = context.pill(game.action, fill = Look.ACCENT, textColor = Look.ON_ACCENT) { actions.run(game.program) }
        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(context.dp(24), context.dp(24), context.dp(24), context.dp(24))
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.TOP
                minimumHeight = context.dp(64)
                // With artwork the picture says which game it is; without, a badge does.
                if (game.art == null) addView(badge(game.icon, game.color))
                addView(space(1, horizontal = true), LayoutParams(0, 1, 1f))
                addView(chip)
            })
            addView(context.text(game.title, 22f, Look.TEXT, bold = true).apply {
                setPadding(0, context.dp(20), 0, context.dp(4))
                maxLines = 2
            })
            addView(context.text(game.subtitle, 14f, Look.MUTED).apply { maxLines = 1 })
            addView(space(20))
            addView(button, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        val view = FrameLayout(context).apply {
            clipToOutline = true
            isClickable = true
            minimumHeight = context.dp(270)
            background = cardBackground(game.color)
            setOnClickListener { actions.run(game.program) }
            if (game.art != null) {
                // Steam's artwork, darkened towards the bottom so the text stays readable.
                addView(ImageView(context).apply {
                    setImageBitmap(game.art)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                addView(View(context).apply {
                    background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                        intArrayOf(Look.SURFACE, (Look.SURFACE and 0xFFFFFF) or 0xB0000000.toInt(), 0x22000000))
                }, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            } else {
                // A large faint copy of the symbol in the corner.
                addView(ImageView(context).apply {
                    setImageResource(game.icon)
                    setColorFilter(game.color)
                    alpha = 0.08f
                }, FrameLayout.LayoutParams(context.dp(140), context.dp(140), Gravity.END or Gravity.BOTTOM).apply {
                    marginEnd = context.dp(20)
                    bottomMargin = context.dp(18)
                })
            }
            addView(content, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
        cards[game.program] = Card(view, chip, button, game)
        return view
    }

    /** Shown next to Steam while nothing is installed: says how to get a game. */
    private fun emptyCard() = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(270)
        background = context.panel(Look.BACKGROUND, 28, stroke = Look.OUTLINE)
        setPadding(context.dp(28), context.dp(24), context.dp(28), context.dp(24))
        addView(context.text("No games yet", 20f, Look.TEXT, bold = true))
        addView(context.text("Open Steam, sign in and install a game that has a Linux version. It shows up here.",
            14f, Look.MUTED).apply { setPadding(0, context.dp(8), 0, 0); setLineSpacing(0f, 1.2f) })
    }

    /** Surface tinted towards the game's colour in one corner. */
    private fun cardBackground(color: Int) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(Look.mix(color, Look.SURFACE, 0.82f), Look.SURFACE, Look.SURFACE),
    ).apply { cornerRadius = context.dp(28).toFloat() }

    /** The card's edge, drawn over any artwork: coloured while the game runs. */
    private fun cardEdge(color: Int, running: Boolean) = GradientDrawable().apply {
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
        addView(space(10))
        addView(listRow("Start Steam when Pons opens", steamAheadValue, context.text("›", 26f, Look.MUTED)) {
            actions.toggleSteamAhead()
        })
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
