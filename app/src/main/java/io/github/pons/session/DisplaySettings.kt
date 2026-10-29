package io.github.pons.session

import android.content.Context

/**
 * Resolution Linux renders at. Lower means less work for the GPU and for the screen copy;
 * the app upscales it to the panel with a sharpening filter.
 */
data class Resolution(val label: String, val width: Int, val height: Int)

class DisplaySettings(context: Context) {

    private val prefs = context.getSharedPreferences("display", Context.MODE_PRIVATE)

    var resolution: Resolution
        // Matched by size, so a saved choice survives renamed labels.
        get() = prefs.getString(KEY_RESOLUTION, null)
            ?.let { saved -> PRESETS.firstOrNull { saved.endsWith("${it.width}×${it.height}") } } ?: DEFAULT
        set(value) = prefs.edit().putString(KEY_RESOLUTION, value.label).apply()

    /** 0 = off, 1 = strong. */
    var sharpness: Float
        get() = prefs.getFloat(KEY_SHARPNESS, DEFAULT_SHARPNESS)
        set(value) = prefs.edit().putFloat(KEY_SHARPNESS, value.coerceIn(0f, 1f)).apply()

    /** Start Steam in the background when Pons opens (see LinuxSession.STEAM_BACKGROUND). */
    var steamAhead: Boolean
        get() = prefs.getBoolean(KEY_STEAM_AHEAD, true)
        set(value) = prefs.edit().putBoolean(KEY_STEAM_AHEAD, value).apply()

    /** Debug switch: false turns the thermal governor off. */
    var thermalGovernor: Boolean
        get() = prefs.getBoolean(KEY_THERMAL_GOVERNOR, true)
        set(value) = prefs.edit().putBoolean(KEY_THERMAL_GOVERNOR, value).apply()

    fun nextResolution(): Resolution {
        val next = PRESETS[(PRESETS.indexOf(resolution) + 1) % PRESETS.size]
        resolution = next
        return next
    }

    companion object {
        private const val KEY_RESOLUTION = "resolution"
        private const val KEY_SHARPNESS = "sharpness"
        private const val KEY_THERMAL_GOVERNOR = "thermal_governor"
        private const val KEY_STEAM_AHEAD = "steam_ahead"
        private const val DEFAULT_SHARPNESS = 0.35f

        // Even sizes close to the tablet's 1.41:1 panel.
        val PRESETS = listOf(
            Resolution("Fast 1280×904", 1280, 904),
            Resolution("Balanced 1600×1132", 1600, 1132),
            Resolution("Quality 1920×1358", 1920, 1358),
            Resolution("Maximum 2400×1696", 2400, 1696),
        )
        val DEFAULT = PRESETS[1]
    }
}
