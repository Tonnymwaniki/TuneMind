package com.tunemind.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Cohesive palette model representing the 10 core surface, text, and accent tokens.
 */
@Immutable
data class PaletteColors(
    val id: String,
    val name: String,
    val description: String,
    val primaryBackground: Color,
    val secondaryBackground: Color,
    val cardSurface: Color,
    val elevatedSurface: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val primaryAccent: Color,
    val secondaryAccent: Color,
    val highlight: Color,
    val border: Color
)

@Immutable
data class MoodProfile(
    val mood: MusicalMood,
    val moodAccent: Color,
    val secondaryAccent: Color,
    val visualMovement: String,
    val gradientIntensity: Float, // 0.0f - 1.0f
    val animationSpeedFactor: Float // 1.0f is standard, > 1 faster, < 1 slower
)

enum class MusicalMood(val label: String) {
    CALM("Calm / Peaceful"),
    ENERGETIC("Energetic"),
    REFLECTIVE("Reflective"),
    UPLIFTING("Uplifting"),
    INTENSE("Intense"),
    UNKNOWN("Unclassified")
}

object TuneMindPalettes {

    val Morning = PaletteColors(
        id = "morning",
        name = "First Light",
        description = "Freshness, clarity, and gentle morning energy.",
        primaryBackground = TuneMindColors.MorningPrimaryBackground,
        secondaryBackground = TuneMindColors.MorningSecondaryBackground,
        cardSurface = TuneMindColors.MorningCardSurface,
        elevatedSurface = TuneMindColors.MorningElevatedSurface,
        primaryText = TuneMindColors.MorningPrimaryText,
        secondaryText = TuneMindColors.MorningSecondaryText,
        primaryAccent = TuneMindColors.MorningPrimaryAccent,
        secondaryAccent = TuneMindColors.MorningSecondaryAccent,
        highlight = TuneMindColors.MorningHighlight,
        border = TuneMindColors.MorningBorder
    )

    val Afternoon = PaletteColors(
        id = "afternoon",
        name = "Clarity",
        description = "Clarity, balance, focus, and visual openness.",
        primaryBackground = TuneMindColors.AfternoonPrimaryBackground,
        secondaryBackground = TuneMindColors.AfternoonSecondaryBackground,
        cardSurface = TuneMindColors.AfternoonCardSurface,
        elevatedSurface = TuneMindColors.AfternoonElevatedSurface,
        primaryText = TuneMindColors.AfternoonPrimaryText,
        secondaryText = TuneMindColors.AfternoonSecondaryText,
        primaryAccent = TuneMindColors.AfternoonPrimaryAccent,
        secondaryAccent = TuneMindColors.AfternoonSecondaryAccent,
        highlight = TuneMindColors.AfternoonHighlight,
        border = TuneMindColors.AfternoonBorder
    )

    val Evening = PaletteColors(
        id = "evening",
        name = "Transition",
        description = "Warmth, depth, transition, and intimate atmosphere.",
        primaryBackground = TuneMindColors.EveningPrimaryBackground,
        secondaryBackground = TuneMindColors.EveningSecondaryBackground,
        cardSurface = TuneMindColors.EveningCardSurface,
        elevatedSurface = TuneMindColors.EveningElevatedSurface,
        primaryText = TuneMindColors.EveningPrimaryText,
        secondaryText = TuneMindColors.EveningSecondaryText,
        primaryAccent = TuneMindColors.EveningPrimaryAccent,
        secondaryAccent = TuneMindColors.EveningSecondaryAccent,
        highlight = TuneMindColors.EveningHighlight,
        border = TuneMindColors.EveningBorder
    )

    val Night = PaletteColors(
        id = "night",
        name = "Deep Focus",
        description = "Privacy, calmness, immersion, and reduced visual intensity.",
        primaryBackground = TuneMindColors.NightPrimaryBackground,
        secondaryBackground = TuneMindColors.NightSecondaryBackground,
        cardSurface = TuneMindColors.NightCardSurface,
        elevatedSurface = TuneMindColors.NightElevatedSurface,
        primaryText = TuneMindColors.NightPrimaryText,
        secondaryText = TuneMindColors.NightSecondaryText,
        primaryAccent = TuneMindColors.NightPrimaryAccent,
        secondaryAccent = TuneMindColors.NightSecondaryAccent,
        highlight = TuneMindColors.NightHighlight,
        border = TuneMindColors.NightBorder
    )

    val AllPalettes = listOf(Morning, Afternoon, Evening, Night)

    fun forId(id: String): PaletteColors = when (id.lowercase()) {
        "morning" -> Morning
        "afternoon" -> Afternoon
        "evening" -> Evening
        "night" -> Night
        else -> Night
    }

    val MoodProfiles: Map<MusicalMood, MoodProfile> = mapOf(
        MusicalMood.CALM to MoodProfile(
            mood = MusicalMood.CALM,
            moodAccent = TuneMindColors.MoodCalmAccent,
            secondaryAccent = TuneMindColors.MoodCalmSecondaryAccent,
            visualMovement = "Slow and fluid",
            gradientIntensity = 0.35f,
            animationSpeedFactor = 0.8f
        ),
        MusicalMood.ENERGETIC to MoodProfile(
            mood = MusicalMood.ENERGETIC,
            moodAccent = TuneMindColors.MoodEnergeticAccent,
            secondaryAccent = TuneMindColors.MoodEnergeticSecondaryAccent,
            visualMovement = "More dynamic",
            gradientIntensity = 0.65f,
            animationSpeedFactor = 1.3f
        ),
        MusicalMood.REFLECTIVE to MoodProfile(
            mood = MusicalMood.REFLECTIVE,
            moodAccent = TuneMindColors.MoodReflectiveAccent,
            secondaryAccent = TuneMindColors.MoodReflectiveSecondaryAccent,
            visualMovement = "Subtle",
            gradientIntensity = 0.35f,
            animationSpeedFactor = 0.85f
        ),
        MusicalMood.UPLIFTING to MoodProfile(
            mood = MusicalMood.UPLIFTING,
            moodAccent = TuneMindColors.MoodUpliftingAccent,
            secondaryAccent = TuneMindColors.MoodUpliftingSecondaryAccent,
            visualMovement = "Light and fluid",
            gradientIntensity = 0.55f,
            animationSpeedFactor = 1.1f
        ),
        MusicalMood.INTENSE to MoodProfile(
            mood = MusicalMood.INTENSE,
            moodAccent = TuneMindColors.MoodIntenseAccent,
            secondaryAccent = TuneMindColors.MoodIntenseSecondaryAccent,
            visualMovement = "More pronounced but controlled",
            gradientIntensity = 0.70f,
            animationSpeedFactor = 1.25f
        )
    )
}
