package com.tunemind.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

enum class TimePeriod(val id: String, val label: String, val rangeLabel: String) {
    MORNING("morning", "First Light", "05:00 – 11:59"),
    AFTERNOON("afternoon", "Clarity", "12:00 – 16:59"),
    EVENING("evening", "Transition", "17:00 – 20:59"),
    NIGHT("night", "Deep Focus", "21:00 – 04:59")
}

enum class ThemeBehavior(val label: String, val description: String) {
    ADAPTIVE("Adaptive", "Changes automatically with time of day and musical atmosphere."),
    TIME_BASED("Time-based", "Changes only according to time of day."),
    FIXED("Fixed", "Maintains your single selected atmosphere palette.")
}

enum class VisualIntensity(val label: String, val factor: Float) {
    SUBTLE("Subtle", 0.35f),
    BALANCED("Balanced", 0.70f),
    EXPRESSIVE("Expressive", 1.00f)
}

/**
 * Immutable cohesive Adaptive Theme state representing the active atmosphere.
 */
@Immutable
data class AdaptiveThemeState(
    val timePeriod: TimePeriod,
    val activePalette: PaletteColors,
    val musicMood: MusicalMood,
    val musicEnergy: Float, // 0.0f - 1.0f
    val primaryBackground: Color,
    val secondaryBackground: Color,
    val cardSurface: Color,
    val elevatedSurface: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val primaryAccent: Color,
    val secondaryAccent: Color,
    val highlight: Color,
    val border: Color,
    val visualMovement: String,
    val gradientIntensity: Float,
    val animationDurationMs: Int,
    val adaptationEnabled: Boolean,
    val confidence: Float,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    companion object {
        fun defaultForNight(): AdaptiveThemeState {
            val p = TuneMindPalettes.Night
            return AdaptiveThemeState(
                timePeriod = TimePeriod.NIGHT,
                activePalette = p,
                musicMood = MusicalMood.UNKNOWN,
                musicEnergy = 0.5f,
                primaryBackground = p.primaryBackground,
                secondaryBackground = p.secondaryBackground,
                cardSurface = p.cardSurface,
                elevatedSurface = p.elevatedSurface,
                primaryText = p.primaryText,
                secondaryText = p.secondaryText,
                primaryAccent = p.primaryAccent,
                secondaryAccent = p.secondaryAccent,
                highlight = p.highlight,
                border = p.border,
                visualMovement = "Calm and focused",
                gradientIntensity = 0.3f,
                animationDurationMs = 700,
                adaptationEnabled = true,
                confidence = 1.0f,
                lastUpdated = System.currentTimeMillis()
            )
        }
    }
}
