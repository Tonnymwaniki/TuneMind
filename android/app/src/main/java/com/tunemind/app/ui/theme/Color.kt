package com.tunemind.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * TUNEMIND — DEEP OBSIDIAN AMBER
 * Permanent Visual Identity System Tokens
 *
 * Implements the approved fixed foundational color palette.
 */
object DeepObsidianAmber {
    // Primary backgrounds
    val AppBackground         = Color(0xFF652814)
    val SecondaryBackground   = Color(0xFF713019)
    val RecessedBackground    = Color(0xFF4F2012)

    // Layered surfaces
    val CardBackground        = Color(0xFF78351E)
    val CardElevated          = Color(0xFF854126)
    val SurfaceHighlight      = Color(0xFF914725)
    val SurfaceBorder         = Color(0xFF985B3C)

    // Gold and copper accents
    val PrimaryAccent         = Color(0xFFC68A46)
    val SecondaryAccent       = Color(0xFFD9A054)
    val AccentLight           = Color(0xFFE9A84F)
    val AccentSoft            = Color(0xFFF3C77D)
    val CopperAccent          = Color(0xFFC2763F)

    // Typography
    val TextPrimary           = Color(0xFFF5E5D0)
    val TextSecondary         = Color(0xFFCFAE91)
    val TextMuted             = Color(0xFFB99578)
    val TextDisabled          = Color(0xFF99745C)

    // Functional colors
    val Success               = Color(0xFF8FAF83)
    val Warning               = Color(0xFFE2B46B)
    val Error                 = Color(0xFFD77C69)
    val Info                  = Color(0xFFB6A0C9)
}

/**
 * Centralized theme color bridge.
 * All palette periods use the approved fixed Deep Obsidian Amber theme.
 */
object TuneMindColors {

    // ─────────────────────────────────────────────────────────────
    // DEEP OBSIDIAN AMBER — FIXED FOUNDATIONAL PALETTE
    // ─────────────────────────────────────────────────────────────

    // A. MORNING (05:00 - 11:59)
    val MorningPrimaryBackground   = DeepObsidianAmber.AppBackground
    val MorningSecondaryBackground = DeepObsidianAmber.SecondaryBackground
    val MorningCardSurface         = DeepObsidianAmber.CardBackground
    val MorningElevatedSurface     = DeepObsidianAmber.CardElevated
    val MorningPrimaryText         = DeepObsidianAmber.TextPrimary
    val MorningSecondaryText       = DeepObsidianAmber.TextSecondary
    val MorningPrimaryAccent       = DeepObsidianAmber.PrimaryAccent
    val MorningSecondaryAccent     = DeepObsidianAmber.SecondaryAccent
    val MorningHighlight           = DeepObsidianAmber.SurfaceHighlight
    val MorningBorder              = DeepObsidianAmber.SurfaceBorder

    // B. AFTERNOON (12:00 - 16:59)
    val AfternoonPrimaryBackground   = DeepObsidianAmber.AppBackground
    val AfternoonSecondaryBackground = DeepObsidianAmber.SecondaryBackground
    val AfternoonCardSurface         = DeepObsidianAmber.CardBackground
    val AfternoonElevatedSurface     = DeepObsidianAmber.CardElevated
    val AfternoonPrimaryText         = DeepObsidianAmber.TextPrimary
    val AfternoonSecondaryText       = DeepObsidianAmber.TextSecondary
    val AfternoonPrimaryAccent       = DeepObsidianAmber.PrimaryAccent
    val AfternoonSecondaryAccent     = DeepObsidianAmber.SecondaryAccent
    val AfternoonHighlight           = DeepObsidianAmber.SurfaceHighlight
    val AfternoonBorder              = DeepObsidianAmber.SurfaceBorder

    // C. EVENING (17:00 - 20:59)
    val EveningPrimaryBackground   = DeepObsidianAmber.AppBackground
    val EveningSecondaryBackground = DeepObsidianAmber.SecondaryBackground
    val EveningCardSurface         = DeepObsidianAmber.CardBackground
    val EveningElevatedSurface     = DeepObsidianAmber.CardElevated
    val EveningPrimaryText         = DeepObsidianAmber.TextPrimary
    val EveningSecondaryText       = DeepObsidianAmber.TextSecondary
    val EveningPrimaryAccent       = DeepObsidianAmber.PrimaryAccent
    val EveningSecondaryAccent     = DeepObsidianAmber.SecondaryAccent
    val EveningHighlight           = DeepObsidianAmber.SurfaceHighlight
    val EveningBorder              = DeepObsidianAmber.SurfaceBorder

    // D. NIGHT (21:00 - 04:59)
    val NightPrimaryBackground   = DeepObsidianAmber.AppBackground
    val NightSecondaryBackground = DeepObsidianAmber.SecondaryBackground
    val NightCardSurface         = DeepObsidianAmber.CardBackground
    val NightElevatedSurface     = DeepObsidianAmber.CardElevated
    val NightPrimaryText         = DeepObsidianAmber.TextPrimary
    val NightSecondaryText       = DeepObsidianAmber.TextSecondary
    val NightPrimaryAccent       = DeepObsidianAmber.PrimaryAccent
    val NightSecondaryAccent     = DeepObsidianAmber.SecondaryAccent
    val NightHighlight           = DeepObsidianAmber.SurfaceHighlight
    val NightBorder              = DeepObsidianAmber.SurfaceBorder

    // ─────────────────────────────────────────────────────────────
    // MUSICAL MOOD ACCENTS (Tuned to harmonize with Deep Amber)
    // ─────────────────────────────────────────────────────────────
    val MoodCalmAccent          = DeepObsidianAmber.AccentSoft
    val MoodCalmSecondaryAccent = DeepObsidianAmber.CopperAccent

    val MoodEnergeticAccent          = DeepObsidianAmber.AccentLight
    val MoodEnergeticSecondaryAccent = DeepObsidianAmber.SecondaryAccent

    val MoodReflectiveAccent          = DeepObsidianAmber.PrimaryAccent
    val MoodReflectiveSecondaryAccent = DeepObsidianAmber.CopperAccent

    val MoodUpliftingAccent          = DeepObsidianAmber.AccentSoft
    val MoodUpliftingSecondaryAccent = DeepObsidianAmber.SecondaryAccent

    val MoodIntenseAccent          = DeepObsidianAmber.CopperAccent
    val MoodIntenseSecondaryAccent = DeepObsidianAmber.AccentLight

    // Status & Utility Colors
    val SafetyGreen = DeepObsidianAmber.Success
    val SafetyAmber = DeepObsidianAmber.Warning
    val SafetyRed   = DeepObsidianAmber.Error
    val BrandIndigo = DeepObsidianAmber.Info
}
