package com.tunemind.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * TuneMind Adaptive Personal Atmosphere — Foundational Color Definitions.
 * Strict implementation of the exact specification palettes.
 */
object TuneMindColors {

    // ─────────────────────────────────────────────────────────────
    // ABLETON STUDIO SLATE (Neutral Anodized Steel + Amber OLED)
    // Mid-tone balance: neither blinding bright nor suffocating dark.
    // ─────────────────────────────────────────────────────────────

    // A. MORNING — FIRST LIGHT (05:00 - 11:59)
    val MorningPrimaryBackground   = Color(0xFF474D58) // Mid-tone neutral morning steel
    val MorningSecondaryBackground = Color(0xFF3B404A) // Recessed machined groove well
    val MorningCardSurface         = Color(0xFF555C6A) // Elevated brushed aluminum plate
    val MorningElevatedSurface     = Color(0xFF616979) // Soft tactile pad
    val MorningPrimaryText         = Color(0xFFF8FAFC) // Crisp studio silver-white
    val MorningSecondaryText       = Color(0xFFAFB8C7) // Muted laser-etched aluminum
    val MorningPrimaryAccent       = Color(0xFFF59E0B) // Tactile Warm Amber OLED
    val MorningSecondaryAccent     = Color(0xFF10B981) // Precision emerald level indicator
    val MorningHighlight           = Color(0xFFFEF3C7) // Soft amber halo
    val MorningBorder              = Color(0xFF666E7F) // Precision 1px hairline border

    // B. AFTERNOON — CLARITY (12:00 - 16:59)
    val AfternoonPrimaryBackground   = Color(0xFF434852) // Balanced anodized studio steel
    val AfternoonSecondaryBackground = Color(0xFF373C45) // Recessed console well
    val AfternoonCardSurface         = Color(0xFF515764) // Elevated aluminum plate card
    val AfternoonElevatedSurface     = Color(0xFF5D6473) // Tactile pad
    val AfternoonPrimaryText         = Color(0xFFF3F4F6) // Crisp studio silver-white
    val AfternoonSecondaryText       = Color(0xFFA9B2C0) // Muted laser-etched aluminum
    val AfternoonPrimaryAccent       = Color(0xFFF59E0B) // Warm Amber OLED dial
    val AfternoonSecondaryAccent     = Color(0xFF10B981) // Precision emerald level indicator
    val AfternoonHighlight           = Color(0xFFFDE68A) // Soft amber halo
    val AfternoonBorder              = Color(0xFF626A7A) // Clean hairline border

    // C. EVENING — TRANSITION (17:00 - 20:59)
    val EveningPrimaryBackground   = Color(0xFF454752) // Twilight-tinted studio steel
    val EveningSecondaryBackground = Color(0xFF393B44) // Recessed console well
    val EveningCardSurface         = Color(0xFF535564) // Studio slate card
    val EveningElevatedSurface     = Color(0xFF5F6172) // Elevated pad
    val EveningPrimaryText         = Color(0xFFF3F4F6) // Studio silver-white
    val EveningSecondaryText       = Color(0xFFAAAEC0) // Muted slate text
    val EveningPrimaryAccent       = Color(0xFFEA580C) // Sunset Amber-Orange OLED
    val EveningSecondaryAccent     = Color(0xFF10B981) // Emerald indicator
    val EveningHighlight           = Color(0xFFFFEDD5) // Warm sunset glow
    val EveningBorder              = Color(0xFF656778) // Clean hairline border

    // D. NIGHT — DEEP FOCUS (21:00 - 04:59)
    val NightPrimaryBackground   = Color(0xFF3E434D) // Mellow fatigue-free mid-slate
    val NightSecondaryBackground = Color(0xFF333740) // Deep console well
    val NightCardSurface         = Color(0xFF4C525E) // Studio aluminum plate card
    val NightElevatedSurface     = Color(0xFF575E6C) // Tactile pad
    val NightPrimaryText         = Color(0xFFF1F5F9) // Silver-white text
    val NightSecondaryText       = Color(0xFFA3ACBA) // Muted slate text
    val NightPrimaryAccent       = Color(0xFFF59E0B) // Tactile Warm Amber OLED
    val NightSecondaryAccent     = Color(0xFF10B981) // Emerald indicator
    val NightHighlight           = Color(0xFFFDE68A) // Soft amber halo
    val NightBorder              = Color(0xFF5D6473) // Hairline border

    // ─────────────────────────────────────────────────────────────
    // MUSICAL MOOD PROFILES
    // ─────────────────────────────────────────────────────────────
    // Calm / Peaceful
    val MoodCalmAccent          = Color(0xFF8FAFC8)
    val MoodCalmSecondaryAccent = Color(0xFFA9B8D0)

    // Energetic
    val MoodEnergeticAccent          = Color(0xFFE39A55)
    val MoodEnergeticSecondaryAccent = Color(0xFFD96F62)

    // Reflective
    val MoodReflectiveAccent          = Color(0xFF9889C7)
    val MoodReflectiveSecondaryAccent = Color(0xFF7889B8)

    // Uplifting
    val MoodUpliftingAccent          = Color(0xFFE2BD73)
    val MoodUpliftingSecondaryAccent = Color(0xFF82B9B1)

    // Intense
    val MoodIntenseAccent          = Color(0xFFCF7778)
    val MoodIntenseSecondaryAccent = Color(0xFFD69A69)

    // Status & Utility Colors
    val SafetyGreen = Color(0xFF10B981)
    val SafetyAmber = Color(0xFFF59E0B)
    val SafetyRed   = Color(0xFFEF4444)
    val BrandIndigo = Color(0xFF6366F1)
}
