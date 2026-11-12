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
    val primaryBackground: Color,
    val secondaryBackground: Color,
    val cardSurface: Color,
    val elevatedSurface: Color,
    val primaryText: Color = Color(0xFFF8FAFC),
    val secondaryText: Color = Color(0xFF94A3B8),
    val primaryAccent: Color,
    val secondaryAccent: Color,
    val highlight: Color,
    val border: Color,
    val recessedBackground: Color = Color(0xFF040508),
    val copperAccent: Color = Color(0xFFE07A5F)
)

@Immutable
data class MoodProfile(
    val mood: MusicalMood,
    val moodAccent: Color,
    val secondaryAccent: Color,
    val backgroundTint: Color,
    val visualMovement: String,
    val gradientIntensity: Float,
    val animationSpeedFactor: Float
)

enum class MusicalMood(val label: String) {
    CALM("Calm Sanctuary"),
    ENERGETIC("Electric Groove"),
    REFLECTIVE("Midnight Reflective"),
    UPLIFTING("Golden Uplift"),
    INTENSE("High-Voltage Drive"),
    UNKNOWN("Circadian Flow")
}

object TuneMindPalettes {

    val Morning = PaletteColors(
        id = "morning",
        name = "Glacier Dawn",
        description = "Crisp arctic sapphire canvas with glacier cyan & sky pulse accents for morning clarity.",
        primaryBackground = Color(0xFF05101F),
        secondaryBackground = Color(0xFF0A1B32),
        cardSurface = Color(0xFF102442),
        elevatedSurface = Color(0xFF163056),
        primaryText = Color(0xFFF8FAFC),
        secondaryText = Color(0xFF93C5FD),
        primaryAccent = Color(0xFF00E5FF),
        secondaryAccent = Color(0xFF38BDF8),
        highlight = Color(0xFF00E5FF).copy(alpha = 0.18f),
        border = Color(0xFF1E3A5F),
        recessedBackground = Color(0xFF030912),
        copperAccent = Color(0xFF38BDF8)
    )

    val Afternoon = PaletteColors(
        id = "afternoon",
        name = "Emerald Velocity",
        description = "Bioluminescent deep forest obsidian with dopamine emerald & cyan energy.",
        primaryBackground = Color(0xFF04130E),
        secondaryBackground = Color(0xFF092118),
        cardSurface = Color(0xFF0E2E22),
        elevatedSurface = Color(0xFF153E2F),
        primaryText = Color(0xFFF8FAFC),
        secondaryText = Color(0xFFA7F3D0),
        primaryAccent = Color(0xFF10B981),
        secondaryAccent = Color(0xFF00E5FF),
        highlight = Color(0xFF10B981).copy(alpha = 0.18f),
        border = Color(0xFF1B4D3E),
        recessedBackground = Color(0xFF020B08),
        copperAccent = Color(0xFF34D399)
    )

    val Evening = PaletteColors(
        id = "evening",
        name = "Solar Ember",
        description = "Warm volcanic dusk canvas with liquid amber, gold, and sunset coral.",
        primaryBackground = Color(0xFF160A07),
        secondaryBackground = Color(0xFF24110C),
        cardSurface = Color(0xFF321811),
        elevatedSurface = Color(0xFF422118),
        primaryText = Color(0xFFFFFBEB),
        secondaryText = Color(0xFFFDE68A),
        primaryAccent = Color(0xFFFFB000),
        secondaryAccent = Color(0xFFFF5E36),
        highlight = Color(0xFFFFB000).copy(alpha = 0.18f),
        border = Color(0xFF5C2D1E),
        recessedBackground = Color(0xFF0E0504),
        copperAccent = Color(0xFFFF7A45)
    )

    val Night = PaletteColors(
        id = "night",
        name = "Synaptic Violet",
        description = "Deep cosmic indigo-violet with neural purple and cryo-cyan bioluminescence.",
        primaryBackground = Color(0xFF080516),
        secondaryBackground = Color(0xFF120B29),
        cardSurface = Color(0xFF1B113A),
        elevatedSurface = Color(0xFF261850),
        primaryText = Color(0xFFF8FAFC),
        secondaryText = Color(0xFFD8B4FE),
        primaryAccent = Color(0xFFA855F7),
        secondaryAccent = Color(0xFF00E5FF),
        highlight = Color(0xFFA855F7).copy(alpha = 0.18f),
        border = Color(0xFF3B2370),
        recessedBackground = Color(0xFF04020C),
        copperAccent = Color(0xFFC084FC)
    )

    val AllPalettes = listOf(Morning, Afternoon, Evening, Night)

    fun forId(id: String): PaletteColors = when (id.lowercase()) {
        "morning" -> Morning
        "afternoon", "midday" -> Afternoon
        "evening" -> Evening
        "night" -> Night
        else -> Night
    }

    val MoodProfiles: Map<MusicalMood, MoodProfile> = mapOf(
        MusicalMood.CALM to MoodProfile(
            mood = MusicalMood.CALM,
            moodAccent = Color(0xFF00E5FF),
            secondaryAccent = Color(0xFF38BDF8),
            backgroundTint = Color(0xFF061826),
            visualMovement = "Slow & Weightless",
            gradientIntensity = 0.35f,
            animationSpeedFactor = 0.85f
        ),
        MusicalMood.ENERGETIC to MoodProfile(
            mood = MusicalMood.ENERGETIC,
            moodAccent = Color(0xFF10B981),
            secondaryAccent = Color(0xFFFFB000),
            backgroundTint = Color(0xFF062019),
            visualMovement = "Kinetic Groove",
            gradientIntensity = 0.50f,
            animationSpeedFactor = 1.20f
        ),
        MusicalMood.REFLECTIVE to MoodProfile(
            mood = MusicalMood.REFLECTIVE,
            moodAccent = Color(0xFFA855F7),
            secondaryAccent = Color(0xFF6366F1),
            backgroundTint = Color(0xFF120926),
            visualMovement = "Nocturnal Depth",
            gradientIntensity = 0.40f,
            animationSpeedFactor = 0.90f
        ),
        MusicalMood.UPLIFTING to MoodProfile(
            mood = MusicalMood.UPLIFTING,
            moodAccent = Color(0xFFFFB000),
            secondaryAccent = Color(0xFF00E5FF),
            backgroundTint = Color(0xFF1C1206),
            visualMovement = "Radiant Horizon",
            gradientIntensity = 0.45f,
            animationSpeedFactor = 1.10f
        ),
        MusicalMood.INTENSE to MoodProfile(
            mood = MusicalMood.INTENSE,
            moodAccent = Color(0xFFFF2A55),
            secondaryAccent = Color(0xFFFFB000),
            backgroundTint = Color(0xFF220710),
            visualMovement = "High-Voltage Pulse",
            gradientIntensity = 0.55f,
            animationSpeedFactor = 1.25f
        )
    )
}
