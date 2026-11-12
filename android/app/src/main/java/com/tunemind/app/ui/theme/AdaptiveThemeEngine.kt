package com.tunemind.app.ui.theme

import androidx.compose.ui.graphics.Color
import java.util.Calendar

/**
 * Pure, testable Adaptive Theme Engine.
 * Combines Time of Day, Musical Mood, Musical Energy, and Personal Preferences
 * into one coherent AdaptiveThemeState that visibly transforms the app's atmosphere.
 */
object AdaptiveThemeEngine {

    fun resolveTimePeriod(hour: Int, minute: Int = 0): TimePeriod {
        val totalMinutes = hour * 60 + minute
        return when {
            totalMinutes in (5 * 60)..(11 * 60 + 59) -> TimePeriod.MORNING
            totalMinutes in (12 * 60)..(16 * 60 + 59) -> TimePeriod.AFTERNOON
            totalMinutes in (17 * 60)..(20 * 60 + 59) -> TimePeriod.EVENING
            else -> TimePeriod.NIGHT
        }
    }

    fun resolveCurrentTimePeriod(calendar: Calendar = Calendar.getInstance()): TimePeriod {
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        return resolveTimePeriod(hour, minute)
    }

    /**
     * Infer musical mood from track title, artist, genre, or audio features.
     */
    fun resolveTrackMood(
        title: String?,
        artist: String?,
        genre: String? = null,
        energy: Float? = null,
        valence: Float? = null
    ): MusicalMood {
        val combined = "${title ?: ""} ${artist ?: ""} ${genre ?: ""}".lowercase()

        return when {
            combined.contains("adventist") || combined.contains("gospel") || combined.contains("anaweza") ||
                combined.contains("praise") || combined.contains("worship") || combined.contains("choir") ||
                combined.contains("blessing") || combined.contains("uplift") || combined.contains("joy") ||
                combined.contains("hope") || combined.contains("malaika") || combined.contains("sauti sol") ->
                MusicalMood.UPLIFTING

            combined.contains("wizkid") || combined.contains("burna") || combined.contains("rema") ||
                combined.contains("afrobeats") || combined.contains("dance") || combined.contains("essence") ||
                combined.contains("kuna") || combined.contains("party") || combined.contains("vibe") ||
                combined.contains("last last") || combined.contains("energy") ->
                MusicalMood.ENERGETIC

            combined.contains("calm") || combined.contains("peace") || combined.contains("ambient") ||
                combined.contains("acoustic") || combined.contains("sleep") || combined.contains("lofi") ||
                combined.contains("chill") || combined.contains("tems") ->
                MusicalMood.CALM

            combined.contains("weeknd") || combined.contains("starboy") || combined.contains("midnight") ||
                combined.contains("reflect") || combined.contains("night") || combined.contains("soul") ||
                combined.contains("drake") ->
                MusicalMood.REFLECTIVE

            combined.contains("rock") || combined.contains("metal") || combined.contains("workout") ||
                combined.contains("rage") || combined.contains("heavy") || combined.contains("drill") ->
                MusicalMood.INTENSE

            energy != null && energy > 0.75f -> MusicalMood.ENERGETIC
            energy != null && energy < 0.42f -> MusicalMood.CALM
            else -> MusicalMood.UNKNOWN
        }
    }

    /**
     * Composes the final AdaptiveThemeState by combining the active Circadian or Fixed palette
     * with live Song Mood & Energy blending.
     */
    fun composeTheme(
        preferences: ThemePreferences,
        calendar: Calendar = Calendar.getInstance(),
        trackTitle: String? = null,
        trackArtist: String? = null,
        trackEnergy: Float = 0.5f,
        trackMood: MusicalMood? = null
    ): AdaptiveThemeState {
        val timePeriod = resolveCurrentTimePeriod(calendar)
        val basePalette = when (preferences.behavior) {
            ThemeBehavior.FIXED -> TuneMindPalettes.forId(preferences.fixedPaletteId)
            ThemeBehavior.TIME_BASED, ThemeBehavior.ADAPTIVE -> when (timePeriod) {
                TimePeriod.MORNING -> TuneMindPalettes.Morning
                TimePeriod.AFTERNOON -> TuneMindPalettes.Afternoon
                TimePeriod.EVENING -> TuneMindPalettes.Evening
                TimePeriod.NIGHT -> TuneMindPalettes.Night
            }
        }

        val resolvedMood = if (preferences.behavior == ThemeBehavior.ADAPTIVE && preferences.enableMoodAdaptation) {
            trackMood ?: resolveTrackMood(trackTitle, trackArtist, energy = trackEnergy)
        } else {
            MusicalMood.UNKNOWN
        }

        val moodProfile = TuneMindPalettes.MoodProfiles[resolvedMood]
        val blendFactor = if (moodProfile != null && preferences.behavior == ThemeBehavior.ADAPTIVE && preferences.enableMoodAdaptation) {
            (preferences.visualIntensity.factor * 0.65f).coerceIn(0.25f, 0.75f)
        } else {
            0.0f
        }

        val primaryBackground = if (blendFactor > 0f && moodProfile != null) {
            blendColors(basePalette.primaryBackground, moodProfile.backgroundTint, blendFactor * 0.6f)
        } else {
            basePalette.primaryBackground
        }

        val secondaryBackground = if (blendFactor > 0f && moodProfile != null) {
            blendColors(basePalette.secondaryBackground, moodProfile.backgroundTint, blendFactor * 0.5f)
        } else {
            basePalette.secondaryBackground
        }

        val cardSurface = if (blendFactor > 0f && moodProfile != null) {
            blendColors(basePalette.cardSurface, moodProfile.moodAccent.copy(alpha = 0.14f), 0.25f)
        } else {
            basePalette.cardSurface
        }

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

        val border = blendColors(basePalette.border, primaryAccent, 0.35f)
        val highlight = primaryAccent.copy(alpha = 0.18f)

        val energyBoost = if (preferences.enableEnergyAdaptation) {
            ((trackEnergy - 0.5f) * 0.20f).coerceIn(-0.1f, 0.15f)
        } else 0f

        val gradientIntensity = ((moodProfile?.gradientIntensity ?: 0.38f) + energyBoost)
            .coerceIn(0.20f, 0.85f)

        val movement = moodProfile?.visualMovement ?: basePalette.name
        val animDuration = if (preferences.reducedMotion) 200 else 750

        return AdaptiveThemeState(
            timePeriod = timePeriod,
            activePalette = basePalette,
            musicMood = resolvedMood,
            musicEnergy = trackEnergy,
            primaryBackground = primaryBackground,
            secondaryBackground = secondaryBackground,
            cardSurface = cardSurface,
            elevatedSurface = basePalette.elevatedSurface,
            primaryText = basePalette.primaryText,
            secondaryText = basePalette.secondaryText,
            primaryAccent = primaryAccent,
            secondaryAccent = secondaryAccent,
            highlight = highlight,
            border = border,
            visualMovement = movement,
            gradientIntensity = gradientIntensity,
            animationDurationMs = animDuration,
            adaptationEnabled = preferences.behavior != ThemeBehavior.FIXED,
            confidence = 1.0f,
            lastUpdated = System.currentTimeMillis(),
            recessedBackground = basePalette.recessedBackground,
            surfaceHighlight = highlight,
            copperAccent = basePalette.copperAccent,
            accentLight = primaryAccent,
            accentSoft = primaryAccent.copy(alpha = 0.15f),
            textMuted = basePalette.secondaryText.copy(alpha = 0.7f),
            textDisabled = basePalette.secondaryText.copy(alpha = 0.4f),
            success = Color(0xFF10B981),
            warning = Color(0xFFFFB000),
            error = Color(0xFFFF2A55),
            info = Color(0xFF00E5FF)
        )
    }

    fun blendColors(color1: Color, color2: Color, ratio: Float): Color {
        val clamped = ratio.coerceIn(0.0f, 1.0f)
        val r = color1.red + (color2.red - color1.red) * clamped
        val g = color1.green + (color2.green - color1.green) * clamped
        val b = color1.blue + (color2.blue - color1.blue) * clamped
        val a = color1.alpha + (color2.alpha - color1.alpha) * clamped
        return Color(r, g, b, a)
    }
}
