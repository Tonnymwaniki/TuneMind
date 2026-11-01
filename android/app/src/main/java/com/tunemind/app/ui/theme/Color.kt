package com.tunemind.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * TuneMind Adaptive Personal Atmosphere — Foundational Color Definitions.
 * Strict implementation of the exact specification palettes.
 */
object TuneMindColors {

    // ─────────────────────────────────────────────────────────────
    // LIGHT OP-1 FIELD STUDIO (Porcelain Chalk + Nixie Amber + Acid Lime)
    // ─────────────────────────────────────────────────────────────

    // A. MORNING — FIRST LIGHT (05:00 - 11:59)
    val MorningPrimaryBackground   = Color(0xFFF7F8FA) // Clean light porcelain chalk
    val MorningSecondaryBackground = Color(0xFFEDEFF4) // Light frosted aluminum
    val MorningCardSurface         = Color(0xFFFFFFFF) // Crisp studio white card
    val MorningElevatedSurface     = Color(0xFFF1F3F7) // Elevated tactile pad
    val MorningPrimaryText         = Color(0xFF0F172A) // Crisp dark industrial ink
    val MorningSecondaryText       = Color(0xFF64748B) // Engineering steel gray
    val MorningPrimaryAccent       = Color(0xFFF59E0B) // Nixie Amber-Gold
    val MorningSecondaryAccent     = Color(0xFF84CC16) // Electric Acid Lime
    val MorningHighlight           = Color(0xFFFEF3C7) // Soft amber champagne glow
    val MorningBorder              = Color(0xFFD6DBE5) // Clean 1px micro-border

    // B. AFTERNOON — CLARITY (12:00 - 16:59)
    val AfternoonPrimaryBackground   = Color(0xFFF5F6F9)
    val AfternoonSecondaryBackground = Color(0xFFEBECEF)
    val AfternoonCardSurface         = Color(0xFFFFFFFF)
    val AfternoonElevatedSurface     = Color(0xFFF0F2F5)
    val AfternoonPrimaryText         = Color(0xFF0F172A)
    val AfternoonSecondaryText       = Color(0xFF64748B)
    val AfternoonPrimaryAccent       = Color(0xFFF59E0B) // Nixie Amber-Gold
    val AfternoonSecondaryAccent     = Color(0xFF84CC16) // Electric Acid Lime
    val AfternoonHighlight           = Color(0xFFFEF3C7)
    val AfternoonBorder              = Color(0xFFD4DAE4)

    // C. EVENING — TRANSITION (17:00 - 20:59)
    val EveningPrimaryBackground   = Color(0xFFF6F5F8)
    val EveningSecondaryBackground = Color(0xFFECEBF0)
    val EveningCardSurface         = Color(0xFFFFFFFF)
    val EveningElevatedSurface     = Color(0xFFF1F0F5)
    val EveningPrimaryText         = Color(0xFF0F172A)
    val EveningSecondaryText       = Color(0xFF64748B)
    val EveningPrimaryAccent       = Color(0xFFEA580C) // Sunset Nixie Amber
    val EveningSecondaryAccent     = Color(0xFF84CC16) // Electric Acid Lime
    val EveningHighlight           = Color(0xFFFFEDD5)
    val EveningBorder              = Color(0xFFD8D6E2)

    // D. NIGHT — DEEP FOCUS (21:00 - 04:59)
    val NightPrimaryBackground   = Color(0xFFF4F5F8)
    val NightSecondaryBackground = Color(0xFFE9EBF1)
    val NightCardSurface         = Color(0xFFFFFFFF)
    val NightElevatedSurface     = Color(0xFFEFF1F5)
    val NightPrimaryText         = Color(0xFF0F172A)
    val NightSecondaryText       = Color(0xFF64748B)
    val NightPrimaryAccent       = Color(0xFFF59E0B) // Nixie Amber-Gold
    val NightSecondaryAccent     = Color(0xFF84CC16) // Electric Acid Lime
    val NightHighlight           = Color(0xFFFEF3C7)
    val NightBorder              = Color(0xFFD4D9E2)

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
