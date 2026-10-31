package com.tunemind.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.util.Calendar
import java.util.TimeZone

/**
 * Pure, testable Adaptive Theme Engine.
 * Combines Time of Day, Musical Mood, Musical Energy, and Personal Preferences
 * into one coherent AdaptiveThemeState.
 */
object AdaptiveThemeEngine {

    /**
     * Resolves the active TimePeriod based on hour (0-23) and minute (0-59).
     * Morning:   05:00 – 11:59
     * Afternoon: 12:00 – 16:59
     * Evening:   17:00 – 20:59
     * Night:     21:00 – 04:59
     */
    fun resolveTimePeriod(hour: Int, minute: Int = 0): TimePeriod {
        val totalMinutes = hour * 60 + minute
        return when {
            totalMinutes in (5 * 60)..(11 * 60 + 59) -> TimePeriod.MORNING
            totalMinutes in (12 * 60)..(16 * 60 + 59) -> TimePeriod.AFTERNOON
            totalMinutes in (17 * 60)..(20 * 60 + 59) -> TimePeriod.EVENING
            else -> TimePeriod.NIGHT
        }
    }

    /**
     * Resolves the current TimePeriod from the device's local calendar & time zone.
     */
    fun resolveCurrentTimePeriod(calendar: Calendar = Calendar.getInstance()): TimePeriod {
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        return resolveTimePeriod(hour, minute)
    }

    /**
     * Infer or extract reliable musical mood from track metadata and features.
     * Only returns a mood if there is genuine signal. Otherwise UNKNOWN.
     */
    fun resolveTrackMood(
        title: String?,
        artist: String?,
        genre: String? = null,
        energy: Float? = null,
        valence: Float? = null
    ): MusicalMood {
        // If quantitative audio features exist:
        if (energy != null && valence != null) {
            return when {
                energy > 0.7f && valence > 0.6f -> MusicalMood.ENERGETIC
                energy > 0.7f && valence <= 0.6f -> MusicalMood.INTENSE
                energy <= 0.4f && valence > 0.5f -> MusicalMood.CALM
                energy <= 0.45f && valence <= 0.5f -> MusicalMood.REFLECTIVE
                valence > 0.7f -> MusicalMood.UPLIFTING
                else -> MusicalMood.UNKNOWN
            }
        }

        // Semantic heuristic from title / artist / genre (without fabricating)
        val combined = "${title ?: ""} ${artist ?: ""} ${genre ?: ""}".lowercase()
        return when {
            combined.contains("calm") || combined.contains("peace") || combined.contains("ambient") ||
                combined.contains("acoustic") || combined.contains("sleep") || combined.contains("meditat") ||
                combined.contains("chill") -> MusicalMood.CALM

            combined.contains("energy") || combined.contains("dance") || combined.contains("electronic") ||
                combined.contains("upbeat") || combined.contains("running") || combined.contains("workout") ||
                combined.contains("party") -> MusicalMood.ENERGETIC

            combined.contains("reflect") || combined.contains("nostalg") || combined.contains("midnight") ||
                combined.contains("memory") || combined.contains("piano") || combined.contains("melancholy") ||
                combined.contains("solitude") -> MusicalMood.REFLECTIVE

            combined.contains("uplift") || combined.contains("joy") || combined.contains("sunshine") ||
                combined.contains("happy") || combined.contains("bright") || combined.contains("hope") ||
                combined.contains("inspire") -> MusicalMood.UPLIFTING

            combined.contains("metal") || combined.contains("rock") || combined.contains("intense") ||
                combined.contains("hyper") || combined.contains("rage") || combined.contains("heavy") -> MusicalMood.INTENSE

            else -> MusicalMood.UNKNOWN
        }
    }

    /**
     * Composes the final AdaptiveThemeState by combining foundational palette,
     * musical mood modifications, energy adjustments, and user preferences.
     */
    fun composeTheme(
        preferences: ThemePreferences,
        calendar: Calendar = Calendar.getInstance(),
        trackTitle: String? = null,
        trackArtist: String? = null,
        trackEnergy: Float = 0.5f,
        trackMood: MusicalMood? = null
    ): AdaptiveThemeState {
        // 1. Determine foundational palette
        val timePeriod = resolveCurrentTimePeriod(calendar)
        val basePalette = when (preferences.behavior) {
            ThemeBehavior.FIXED -> TuneMindPalettes.forId(preferences.fixedPaletteId)
            ThemeBehavior.TIME_BASED -> when (timePeriod) {
                TimePeriod.MORNING -> TuneMindPalettes.Morning
                TimePeriod.AFTERNOON -> TuneMindPalettes.Afternoon
                TimePeriod.EVENING -> TuneMindPalettes.Evening
                TimePeriod.NIGHT -> TuneMindPalettes.Night
            }
            ThemeBehavior.ADAPTIVE -> when (timePeriod) {
                TimePeriod.MORNING -> TuneMindPalettes.Morning
                TimePeriod.AFTERNOON -> TuneMindPalettes.Afternoon
                TimePeriod.EVENING -> TuneMindPalettes.Evening
                TimePeriod.NIGHT -> TuneMindPalettes.Night
            }
        }

        // 2. Resolve mood profile
        val resolvedMood = if (preferences.behavior == ThemeBehavior.ADAPTIVE && preferences.enableMoodAdaptation) {
            trackMood ?: resolveTrackMood(trackTitle, trackArtist)
        } else {
            MusicalMood.UNKNOWN
        }

        val moodProfile = TuneMindPalettes.MoodProfiles[resolvedMood]
        val blendFactor = if (moodProfile != null && preferences.enableMoodAdaptation) {
            preferences.visualIntensity.factor
        } else {
            0.0f
        }

        // 3. Blend Accents with Mood Profile
        val primaryAccent = if (blendFactor > 0f && moodProfile != null) {
            blendColors(basePalette.primaryAccent, moodProfile.moodAccent, blendFactor)
        } else if (preferences.customAccentHex != null) {
            runCatching { Color(android.graphics.Color.parseColor(preferences.customAccentHex)) }
                .getOrDefault(basePalette.primaryAccent)
        } else {
            basePalette.primaryAccent
        }

        val secondaryAccent = if (blendFactor > 0f && moodProfile != null) {
            blendColors(basePalette.secondaryAccent, moodProfile.secondaryAccent, blendFactor)
        } else {
            basePalette.secondaryAccent
        }

        // 4. Subtle background/card gradient depth influenced by energy
        val energyBoost = if (preferences.enableEnergyAdaptation && resolvedMood != MusicalMood.UNKNOWN) {
            ((trackEnergy - 0.5f) * 0.15f).coerceIn(-0.1f, 0.1f)
        } else 0f

        val gradientIntensity = ((moodProfile?.gradientIntensity ?: 0.35f) + energyBoost)
            .coerceIn(0.15f, 0.85f)

        // 5. High contrast accessibility adjustments
        val primaryText = if (preferences.highContrast) Color.White else basePalette.primaryText
        val secondaryText = if (preferences.highContrast) Color(0xFFE2E8F0) else basePalette.secondaryText
        val border = if (preferences.highContrast) {
            blendColors(basePalette.border, primaryAccent, 0.4f)
        } else {
            basePalette.border
        }

        val movement = moodProfile?.visualMovement ?: "Calm and composed"
        val animDuration = if (preferences.reducedMotion) {
            0
        } else {
            val baseAnim = 700 // ms
            val factor = moodProfile?.animationSpeedFactor ?: 1.0f
            (baseAnim / factor).toInt().coerceIn(500, 950)
        }

        return AdaptiveThemeState(
            timePeriod = timePeriod,
            activePalette = basePalette,
            musicMood = resolvedMood,
            musicEnergy = trackEnergy,
            primaryBackground = basePalette.primaryBackground,
            secondaryBackground = basePalette.secondaryBackground,
            cardSurface = basePalette.cardSurface,
            elevatedSurface = basePalette.elevatedSurface,
            primaryText = primaryText,
            secondaryText = secondaryText,
            primaryAccent = primaryAccent,
            secondaryAccent = secondaryAccent,
            highlight = basePalette.highlight,
            border = border,
            visualMovement = movement,
            gradientIntensity = gradientIntensity,
            animationDurationMs = animDuration,
            adaptationEnabled = preferences.behavior == ThemeBehavior.ADAPTIVE,
            confidence = if (resolvedMood != MusicalMood.UNKNOWN) 0.90f else 1.0f,
            lastUpdated = System.currentTimeMillis()
        )
    }

    /**
     * Linearly interpolates two colors in sRGB space.
     */
    fun blendColors(color1: Color, color2: Color, ratio: Float): Color {
        val clamped = ratio.coerceIn(0.0f, 1.0f)
        val r = color1.red + (color2.red - color1.red) * clamped
        val g = color1.green + (color2.green - color1.green) * clamped
        val b = color1.blue + (color2.blue - color1.blue) * clamped
        val a = color1.alpha + (color2.alpha - color1.alpha) * clamped
        return Color(r, g, b, a)
    }
}
