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
        get() = PRESETS.firstOrNull { it.label == prefs.getString(KEY_RESOLUTION, null) } ?: DEFAULT
        set(value) = prefs.edit().putString(KEY_RESOLUTION, value.label).apply()

    /** 0 = off, 1 = strong. */
    var sharpness: Float
        get() = prefs.getFloat(KEY_SHARPNESS, DEFAULT_SHARPNESS)
        set(value) = prefs.edit().putFloat(KEY_SHARPNESS, value.coerceIn(0f, 1f)).apply()

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
        private const val DEFAULT_SHARPNESS = 0.35f

        // Even sizes close to the tablet's 1.41:1 panel.
        val PRESETS = listOf(
            Resolution("Veloce 1280×904", 1280, 904),
            Resolution("Bilanciata 1600×1132", 1600, 1132),
            Resolution("Qualità 1920×1358", 1920, 1358),
            Resolution("Massima 2400×1696", 2400, 1696),
        )
        val DEFAULT = PRESETS[1]
    }
}
