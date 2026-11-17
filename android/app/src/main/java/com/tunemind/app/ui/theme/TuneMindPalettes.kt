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
        name = "Glacier",
        description = "Deep arctic obsidian canvas with crisp glacier sky lighting for morning clarity.",
        primaryBackground = Color(0xFF070D14),
        secondaryBackground = Color(0xFF0B1522),
        cardSurface = Color(0xFF101B29),
        elevatedSurface = Color(0xFF162334),
        primaryText = Color(0xFFFAFAFA),
        secondaryText = Color(0xFF94A3B8),
        primaryAccent = Color(0xFF38BDF8),
        secondaryAccent = Color(0xFF7DD3FC),
        highlight = Color(0xFF38BDF8).copy(alpha = 0.16f),
        border = Color(0xFF1F3248),
        recessedBackground = Color(0xFF04080D),
        copperAccent = Color(0xFF38BDF8)
    )

    val Afternoon = PaletteColors(
        id = "afternoon",
        name = "Emerald",
        description = "Deep forest obsidian canvas with crisp emerald lighting for midday focus.",
        primaryBackground = Color(0xFF07100D),
        secondaryBackground = Color(0xFF0A1813),
        cardSurface = Color(0xFF0F201A),
        elevatedSurface = Color(0xFF152B23),
        primaryText = Color(0xFFFAFAFA),
        secondaryText = Color(0xFF94A3B8),
        primaryAccent = Color(0xFF10B981),
        secondaryAccent = Color(0xFF34D399),
        highlight = Color(0xFF10B981).copy(alpha = 0.16f),
        border = Color(0xFF1E3A2F),
        recessedBackground = Color(0xFF040A08),
        copperAccent = Color(0xFF34D399)
    )

    val Evening = PaletteColors(
        id = "evening",
        name = "Ember",
        description = "Smoked obsidian espresso canvas with warm amber studio lighting for evening unwind.",
        primaryBackground = Color(0xFF110B08),
        secondaryBackground = Color(0xFF18100C),
        cardSurface = Color(0xFF1F1510),
        elevatedSurface = Color(0xFF291C16),
        primaryText = Color(0xFFFAFAFA),
        secondaryText = Color(0xFFA8A29E),
        primaryAccent = Color(0xFFF59E0B),
        secondaryAccent = Color(0xFFFB923C),
        highlight = Color(0xFFF59E0B).copy(alpha = 0.16f),
        border = Color(0xFF38261E),
        recessedBackground = Color(0xFF0A0604),
        copperAccent = Color(0xFFFB923C)
    )

    val Night = PaletteColors(
        id = "night",
        name = "Violet",
        description = "Deep midnight indigo canvas with soft violet studio lighting for late-night flow.",
        primaryBackground = Color(0xFF0B0912),
        secondaryBackground = Color(0xFF110E1C),
        cardSurface = Color(0xFF161224),
        elevatedSurface = Color(0xFF1E1830),
        primaryText = Color(0xFFFAFAFA),
        secondaryText = Color(0xFFA1A1AA),
        primaryAccent = Color(0xFFA78BFA),
        secondaryAccent = Color(0xFF818CF8),
        highlight = Color(0xFFA78BFA).copy(alpha = 0.16f),
        border = Color(0xFF2C2346),
        recessedBackground = Color(0xFF06050A),
        copperAccent = Color(0xFFA78BFA)
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
