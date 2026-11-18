package com.tunemind.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

enum class TimePeriod(val id: String, val label: String, val rangeLabel: String) {
    MORNING("morning", "Morning", "06:00 – 12:00"),
    AFTERNOON("afternoon", "Midday", "12:00 – 17:00"),
    EVENING("evening", "Evening", "17:00 – 22:00"),
    NIGHT("night", "Night", "22:00 – 06:00")
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
 * Defaults directly to the approved Deep Obsidian Amber visual system.
 */
@Immutable
data class AdaptiveThemeState(
    val timePeriod: TimePeriod = TimePeriod.NIGHT,
    val activePalette: PaletteColors = TuneMindPalettes.Night,
    val musicMood: MusicalMood = MusicalMood.UNKNOWN,
    val musicEnergy: Float = 0.5f,
    val primaryBackground: Color = DeepObsidianAmber.AppBackground,
    val secondaryBackground: Color = DeepObsidianAmber.SecondaryBackground,
    val cardSurface: Color = DeepObsidianAmber.CardBackground,
    val elevatedSurface: Color = DeepObsidianAmber.CardElevated,
    val primaryText: Color = DeepObsidianAmber.TextPrimary,
    val secondaryText: Color = DeepObsidianAmber.TextSecondary,
    val primaryAccent: Color = DeepObsidianAmber.PrimaryAccent,
    val secondaryAccent: Color = DeepObsidianAmber.SecondaryAccent,
    val highlight: Color = DeepObsidianAmber.SurfaceHighlight,
    val border: Color = DeepObsidianAmber.SurfaceBorder,
    val visualMovement: String = "Calm and focused",
    val gradientIntensity: Float = 0.25f,
    val animationDurationMs: Int = 600,
    val adaptationEnabled: Boolean = false,
    val confidence: Float = 1.0f,
    val lastUpdated: Long = System.currentTimeMillis(),
    val recessedBackground: Color = DeepObsidianAmber.RecessedBackground,
    val surfaceHighlight: Color = DeepObsidianAmber.SurfaceHighlight,
    val copperAccent: Color = DeepObsidianAmber.CopperAccent,
    val accentLight: Color = DeepObsidianAmber.AccentLight,
    val accentSoft: Color = DeepObsidianAmber.AccentSoft,
    val textMuted: Color = DeepObsidianAmber.TextMuted,
    val textDisabled: Color = DeepObsidianAmber.TextDisabled,
    val success: Color = DeepObsidianAmber.Success,
    val warning: Color = DeepObsidianAmber.Warning,
    val error: Color = DeepObsidianAmber.Error,
    val info: Color = DeepObsidianAmber.Info
) {
    companion object {
        fun defaultForNight(): AdaptiveThemeState {
            return AdaptiveThemeState()
        }
    }
}
