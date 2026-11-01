package com.tunemind.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Cohesive palette model representing the core surface, text, and accent tokens.
 */
@Immutable
data class PaletteColors(
    val id: String,
    val name: String,
    val description: String,
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
    val recessedBackground: Color = DeepObsidianAmber.RecessedBackground,
    val copperAccent: Color = DeepObsidianAmber.CopperAccent
)

@Immutable
data class MoodProfile(
    val mood: MusicalMood,
    val moodAccent: Color,
    val secondaryAccent: Color,
    val visualMovement: String,
    val gradientIntensity: Float, // 0.0f - 1.0f
    val animationSpeedFactor: Float // 1.0f is standard
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
        name = "Deep Obsidian Amber (Dawn)",
        description = "Deep concentrated reddish-amber foundation with copper and gold accents.",
        primaryBackground = DeepObsidianAmber.AppBackground,
        secondaryBackground = DeepObsidianAmber.SecondaryBackground,
        cardSurface = DeepObsidianAmber.CardBackground,
        elevatedSurface = DeepObsidianAmber.CardElevated,
        primaryText = DeepObsidianAmber.TextPrimary,
        secondaryText = DeepObsidianAmber.TextSecondary,
        primaryAccent = DeepObsidianAmber.PrimaryAccent,
        secondaryAccent = DeepObsidianAmber.SecondaryAccent,
        highlight = DeepObsidianAmber.SurfaceHighlight,
        border = DeepObsidianAmber.SurfaceBorder
    )

    val Afternoon = PaletteColors(
        id = "afternoon",
        name = "Deep Obsidian Amber (Noon)",
        description = "Deep concentrated reddish-amber foundation with copper and gold accents.",
        primaryBackground = DeepObsidianAmber.AppBackground,
        secondaryBackground = DeepObsidianAmber.SecondaryBackground,
        cardSurface = DeepObsidianAmber.CardBackground,
        elevatedSurface = DeepObsidianAmber.CardElevated,
        primaryText = DeepObsidianAmber.TextPrimary,
        secondaryText = DeepObsidianAmber.TextSecondary,
        primaryAccent = DeepObsidianAmber.PrimaryAccent,
        secondaryAccent = DeepObsidianAmber.SecondaryAccent,
        highlight = DeepObsidianAmber.SurfaceHighlight,
        border = DeepObsidianAmber.SurfaceBorder
    )

    val Evening = PaletteColors(
        id = "evening",
        name = "Deep Obsidian Amber (Dusk)",
        description = "Deep concentrated reddish-amber foundation with copper and gold accents.",
        primaryBackground = DeepObsidianAmber.AppBackground,
        secondaryBackground = DeepObsidianAmber.SecondaryBackground,
        cardSurface = DeepObsidianAmber.CardBackground,
        elevatedSurface = DeepObsidianAmber.CardElevated,
        primaryText = DeepObsidianAmber.TextPrimary,
        secondaryText = DeepObsidianAmber.TextSecondary,
        primaryAccent = DeepObsidianAmber.PrimaryAccent,
        secondaryAccent = DeepObsidianAmber.SecondaryAccent,
        highlight = DeepObsidianAmber.SurfaceHighlight,
        border = DeepObsidianAmber.SurfaceBorder
    )

    val Night = PaletteColors(
        id = "night",
        name = "Deep Obsidian Amber",
        description = "Deep concentrated reddish-amber foundation with copper and gold accents.",
        primaryBackground = DeepObsidianAmber.AppBackground,
        secondaryBackground = DeepObsidianAmber.SecondaryBackground,
        cardSurface = DeepObsidianAmber.CardBackground,
        elevatedSurface = DeepObsidianAmber.CardElevated,
        primaryText = DeepObsidianAmber.TextPrimary,
        secondaryText = DeepObsidianAmber.TextSecondary,
        primaryAccent = DeepObsidianAmber.PrimaryAccent,
        secondaryAccent = DeepObsidianAmber.SecondaryAccent,
        highlight = DeepObsidianAmber.SurfaceHighlight,
        border = DeepObsidianAmber.SurfaceBorder
    )

    val AllPalettes = listOf(Night, Morning, Afternoon, Evening)

    fun forId(id: String): PaletteColors = Night

    val MoodProfiles: Map<MusicalMood, MoodProfile> = mapOf(
        MusicalMood.CALM to MoodProfile(
            mood = MusicalMood.CALM,
            moodAccent = TuneMindColors.MoodCalmAccent,
            secondaryAccent = TuneMindColors.MoodCalmSecondaryAccent,
            visualMovement = "Slow and fluid",
            gradientIntensity = 0.25f,
            animationSpeedFactor = 0.85f
        ),
        MusicalMood.ENERGETIC to MoodProfile(
            mood = MusicalMood.ENERGETIC,
            moodAccent = TuneMindColors.MoodEnergeticAccent,
            secondaryAccent = TuneMindColors.MoodEnergeticSecondaryAccent,
            visualMovement = "Dynamic warmth",
            gradientIntensity = 0.35f,
            animationSpeedFactor = 1.15f
        ),
        MusicalMood.REFLECTIVE to MoodProfile(
            mood = MusicalMood.REFLECTIVE,
            moodAccent = TuneMindColors.MoodReflectiveAccent,
            secondaryAccent = TuneMindColors.MoodReflectiveSecondaryAccent,
            visualMovement = "Subtle deep focus",
            gradientIntensity = 0.25f,
            animationSpeedFactor = 0.9f
        ),
        MusicalMood.UPLIFTING to MoodProfile(
            mood = MusicalMood.UPLIFTING,
            moodAccent = TuneMindColors.MoodUpliftingAccent,
            secondaryAccent = TuneMindColors.MoodUpliftingSecondaryAccent,
            visualMovement = "Warm and fluid",
            gradientIntensity = 0.30f,
            animationSpeedFactor = 1.05f
        ),
        MusicalMood.INTENSE to MoodProfile(
            mood = MusicalMood.INTENSE,
            moodAccent = TuneMindColors.MoodIntenseAccent,
            secondaryAccent = TuneMindColors.MoodIntenseSecondaryAccent,
            visualMovement = "Rich controlled presence",
            gradientIntensity = 0.35f,
            animationSpeedFactor = 1.15f
        )
    )
}
