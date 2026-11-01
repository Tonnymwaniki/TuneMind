package com.tunemind.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * TuneMind Adaptive Personal Atmosphere — Foundational Color Definitions.
 * Strict implementation of the exact specification palettes.
 */
object TuneMindColors {

    // ─────────────────────────────────────────────────────────────
    // COMBINATION 1: OBSIDIAN TUBE (Deep Charcoal + Nixie Amber + Acid Lime)
    // ─────────────────────────────────────────────────────────────

    // A. MORNING — FIRST LIGHT (05:00 - 11:59)
    val MorningPrimaryBackground   = Color(0xFF090A0D)
    val MorningSecondaryBackground = Color(0xFF121419)
    val MorningCardSurface         = Color(0xFF191B22)
    val MorningElevatedSurface     = Color(0xFF22252E)
    val MorningPrimaryText         = Color(0xFFF3F4F6)
    val MorningSecondaryText       = Color(0xFF9096A5)
    val MorningPrimaryAccent       = Color(0xFFFFA800) // Nixie Amber-Gold
    val MorningSecondaryAccent     = Color(0xFFD4F01A) // Acid Lime / Electric Citron
    val MorningHighlight           = Color(0xFFFFE277) // Radiant Warm Glow
    val MorningBorder              = Color(0xFF2B2E3A)

    // B. AFTERNOON — CLARITY (12:00 - 16:59)
    val AfternoonPrimaryBackground   = Color(0xFF08090C)
    val AfternoonSecondaryBackground = Color(0xFF111318)
    val AfternoonCardSurface         = Color(0xFF181A21)
    val AfternoonElevatedSurface     = Color(0xFF21242D)
    val AfternoonPrimaryText         = Color(0xFFF3F4F6)
    val AfternoonSecondaryText       = Color(0xFF9096A5)
    val AfternoonPrimaryAccent       = Color(0xFFFFA800) // Nixie Amber-Gold
    val AfternoonSecondaryAccent     = Color(0xFFD4F01A) // Acid Lime
    val AfternoonHighlight           = Color(0xFFFFE277)
    val AfternoonBorder              = Color(0xFF292C37)

    // C. EVENING — TRANSITION (17:00 - 20:59)
    val EveningPrimaryBackground   = Color(0xFF09090C)
    val EveningSecondaryBackground = Color(0xFF121318)
    val EveningCardSurface         = Color(0xFF191A22)
    val EveningElevatedSurface     = Color(0xFF22242F)
    val EveningPrimaryText         = Color(0xFFF3F4F6)
    val EveningSecondaryText       = Color(0xFF9499A8)
    val EveningPrimaryAccent       = Color(0xFFFF9400) // Deep Sunset Nixie Amber
    val EveningSecondaryAccent     = Color(0xFFD4F01A) // Acid Lime
    val EveningHighlight           = Color(0xFFFFD466)
    val EveningBorder              = Color(0xFF2B2D3A)

    // D. NIGHT — DEEP FOCUS (21:00 - 04:59)
    val NightPrimaryBackground   = Color(0xFF07080A)
    val NightSecondaryBackground = Color(0xFF0F1014)
    val NightCardSurface         = Color(0xFF16171F)
    val NightElevatedSurface     = Color(0xFF1F202B)
    val NightPrimaryText         = Color(0xFFF3F4F6)
    val NightSecondaryText       = Color(0xFF888E9E)
    val NightPrimaryAccent       = Color(0xFFFFA800) // Glowing Vacuum Nixie Amber
    val NightSecondaryAccent     = Color(0xFFD4F01A) // Precision Acid Lime
    val NightHighlight           = Color(0xFFFFE277)
    val NightBorder              = Color(0xFF262835)

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
