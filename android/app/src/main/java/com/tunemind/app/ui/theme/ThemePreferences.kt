package com.tunemind.app.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable

@Immutable
data class ThemePreferences(
    val behavior: ThemeBehavior = ThemeBehavior.ADAPTIVE,
    val fixedPaletteId: String = "night",
    val enableMoodAdaptation: Boolean = true,
    val enableEnergyAdaptation: Boolean = true,
    val visualIntensity: VisualIntensity = VisualIntensity.BALANCED,
    val customAccentHex: String? = null,
    val reducedMotion: Boolean = false,
    val highContrast: Boolean = false,
    // Personal Visual Intelligence (Explicit vs Inferred separation)
    val observedWarmCoolBias: Float = 0.0f, // -1.0 (cool) to +1.0 (warm)
    val confirmedPreferredAccent: String? = null
)

class ThemePreferencesRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadPreferences(): ThemePreferences {
        val behaviorStr = prefs.getString(KEY_BEHAVIOR, ThemeBehavior.ADAPTIVE.name)
        val behavior = runCatching { ThemeBehavior.valueOf(behaviorStr ?: ThemeBehavior.ADAPTIVE.name) }
            .getOrDefault(ThemeBehavior.ADAPTIVE)

        val intensityStr = prefs.getString(KEY_INTENSITY, VisualIntensity.BALANCED.name)
        val intensity = runCatching { VisualIntensity.valueOf(intensityStr ?: VisualIntensity.BALANCED.name) }
            .getOrDefault(VisualIntensity.BALANCED)

        return ThemePreferences(
            behavior = behavior,
            fixedPaletteId = prefs.getString(KEY_FIXED_PALETTE, "night") ?: "night",
            enableMoodAdaptation = prefs.getBoolean(KEY_ENABLE_MOOD, true),
            enableEnergyAdaptation = prefs.getBoolean(KEY_ENABLE_ENERGY, true),
            visualIntensity = intensity,
            customAccentHex = prefs.getString(KEY_CUSTOM_ACCENT, null),
            reducedMotion = prefs.getBoolean(KEY_REDUCED_MOTION, false),
            highContrast = prefs.getBoolean(KEY_HIGH_CONTRAST, false),
            observedWarmCoolBias = prefs.getFloat(KEY_WARM_COOL_BIAS, 0.0f),
            confirmedPreferredAccent = prefs.getString(KEY_CONFIRMED_ACCENT, null)
        )
    }

    fun savePreferences(p: ThemePreferences) {
        prefs.edit()
            .putString(KEY_BEHAVIOR, p.behavior.name)
            .putString(KEY_FIXED_PALETTE, p.fixedPaletteId)
            .putBoolean(KEY_ENABLE_MOOD, p.enableMoodAdaptation)
            .putBoolean(KEY_ENABLE_ENERGY, p.enableEnergyAdaptation)
            .putString(KEY_INTENSITY, p.visualIntensity.name)
            .putString(KEY_CUSTOM_ACCENT, p.customAccentHex)
            .putBoolean(KEY_REDUCED_MOTION, p.reducedMotion)
            .putBoolean(KEY_HIGH_CONTRAST, p.highContrast)
            .putFloat(KEY_WARM_COOL_BIAS, p.observedWarmCoolBias)
            .putString(KEY_CONFIRMED_ACCENT, p.confirmedPreferredAccent)
            .apply()
    }

    fun resetToDefaults(): ThemePreferences {
        prefs.edit().clear().apply()
        return ThemePreferences()
    }

    fun recordObservedAccentInteraction(isWarm: Boolean) {
        val current = prefs.getFloat(KEY_WARM_COOL_BIAS, 0.0f)
        val delta = if (isWarm) 0.05f else -0.05f
        val updated = (current + delta).coerceIn(-1.0f, 1.0f)
        prefs.edit().putFloat(KEY_WARM_COOL_BIAS, updated).apply()
    }

    companion object {
        private const val PREFS_NAME = "tunemind_theme_prefs"
        private const val KEY_BEHAVIOR = "theme_behavior"
        private const val KEY_FIXED_PALETTE = "fixed_palette_id"
        private const val KEY_ENABLE_MOOD = "enable_mood_adaptation"
        private const val KEY_ENABLE_ENERGY = "enable_energy_adaptation"
        private const val KEY_INTENSITY = "visual_intensity"
        private const val KEY_CUSTOM_ACCENT = "custom_accent_hex"
        private const val KEY_REDUCED_MOTION = "reduced_motion"
        private const val KEY_HIGH_CONTRAST = "high_contrast"
        private const val KEY_WARM_COOL_BIAS = "observed_warm_cool_bias"
        private const val KEY_CONFIRMED_ACCENT = "confirmed_preferred_accent"
    }
}
