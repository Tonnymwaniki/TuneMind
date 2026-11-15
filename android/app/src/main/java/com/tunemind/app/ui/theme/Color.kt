package com.tunemind.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * TUNEMIND — TRUE CARBON OBSIDIAN & DEEP AMBER SOUL
 * Hardware-grade Acoustic Design System
 *
 * Combines inky Carbon Obsidian chassis depth (#0D0F14) with the locked
 * Deep Obsidian Amber soul (#652814 / #D48344) and creative feature domain accents.
 */
object DeepObsidianAmber {
    // ─────────────────────────────────────────────────────────────
    // 1. CHASSIS & DEPTH FOUNDATION (Pitch Black & Dark Indigo)
    // ─────────────────────────────────────────────────────────────
    val AppBackground         = Color(0xFF0D0D0D) // Pitch Black canvas
    val SecondaryBackground   = Color(0xFF1A0B2E) // Dark Indigo gradient depth
    val RecessedBackground    = Color(0xFF080510) // Recessed well depth

    // ─────────────────────────────────────────────────────────────
    // 2. LAYERED SURFACES (Indigo-Tinted Dark Surfaces)
    // ─────────────────────────────────────────────────────────────
    val CardBackground        = Color(0xFF140D24) // Standard card surface
    val CardElevated          = Color(0xFF1E1338) // Interactive elevated deck
    val SurfaceHighlight      = Color(0xFF2B1A4E) // Active hover / selected tint
    val SurfaceBorder         = Color(0xFF3A1F66) // Hairline luminous indigo border

    // ─────────────────────────────────────────────────────────────
    // 3. BRAND SOUL ACCENTS (Cyan Soundwaves & Electric Purple Neural AI)
    // ─────────────────────────────────────────────────────────────
    val BrandSoulAmber        = Color(0xFF1A0B2E) // Deep Dark Indigo anchor
    val PrimaryAccent         = Color(0xFF00F0FF) // Cyan music soundwaves
    val SecondaryAccent       = Color(0xFF8A2BE2) // Electric Purple Neural AI
    val AccentLight           = Color(0xFF67F7FF) // Luminous cyan highlight
    val AccentSoft            = Color(0xFFD8B4FE) // Soft neural violet tint
    val CopperAccent          = Color(0xFF8A2BE2) // Electric Purple secondary

    // ─────────────────────────────────────────────────────────────
    // 4. TYPOGRAPHY TOKENS (Crisp Optic Clarity)
    // ─────────────────────────────────────────────────────────────
    val TextPrimary           = Color(0xFFF8FAFC) // Crisp ivory white
    val TextSecondary         = Color(0xFFC4B5FD) // Soft indigo silver
    val TextMuted             = Color(0xFF8B93A7) // Muted slate label grey
    val TextDisabled          = Color(0xFF4A5468) // Subdued indicator

    // ─────────────────────────────────────────────────────────────
    // 5. FEATURE DOMAIN ACCENTS (Creative Distinction Across Modules)
    // ─────────────────────────────────────────────────────────────
    // Live Spectrum & Equalizer
    val EQBassCyan            = Color(0xFF00F0FF) // Cyan soundwave glow
    val EQMidAmber            = Color(0xFF8A2BE2) // Electric purple mids
    val EQHighRose            = Color(0xFFFF5252) // Harmonic highs & transients (4k-16kHz)

    // Cooldown Vault (Cryogenic Preservation Aesthetic)
    val CryoCyan              = Color(0xFF00F0FF) // Chilled liquid cryo cyan
    val GlacialBlue           = Color(0xFF4E95FF) // Restorative frozen blue
    val FrostDeep             = Color(0xFF101B2B) // Cryo-chamber well

    // Satiety Lab & Song Fatigue (Thermal Burnout & Overplay Alerts)
    val SatietyEmber          = Color(0xFFFF5757) // High fatigue critical alert
    val FatigueCoral          = Color(0xFFFF8C42) // Mid fatigue warning glow
    val ThermalWarning        = Color(0xFFFFB74D) // Early repetition advisory

    // Acoustic Genome & Neural Intelligence (Continuous AI Brain)
    val NeuralViolet          = Color(0xFF8A2BE2) // Electric Purple neural circuit
    val ElectricViolet        = Color(0xFF7C3AED) // Deep latent space cluster
    val GenomeIndigo          = Color(0xFF1A0B2E) // Dark Indigo memory synapse

    // Hearing Safety & Decibel Headroom
    val DecibelSafe           = Color(0xFF10B981) // Studio green (<75dB safe level)
    val DecibelCaution        = Color(0xFFF59E0B) // Amber caution (75-85dB)
    val DecibelPeak           = Color(0xFFEF4444) // Studio red alert (>85dB peak)

    // Circadian Rhythm Waves
    val CircadianMorning      = Color(0xFFFBBF24) // Morning gold
    val CircadianAfternoon    = Color(0xFF38BDF8) // Daylight azure
    val CircadianEvening      = Color(0xFF818CF8) // Twilight periwinkle
    val CircadianNight        = Color(0xFF6366F1) // Midnight indigo

    // Standard Functional Status
    val Success               = Color(0xFF10B981) // Studio Emerald
    val Warning               = Color(0xFFF59E0B) // Gold Amber
    val Error                 = Color(0xFFFF5757) // Signal Red
    val Info                  = Color(0xFF38BDF8) // Digital Cyan
}

/**
 * Centralized theme color bridge for period and mood adaptations.
 */
object TuneMindColors {

    // ─────────────────────────────────────────────────────────────
    // ADAPTIVE PERIOD BRIDGES
    // ─────────────────────────────────────────────────────────────
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
    // VIBRANT MUSICAL MOOD ACCENTS (Rich, distinct color energy)
    // ─────────────────────────────────────────────────────────────
    val MoodCalmAccent          = Color(0xFF4ECDC4) // Glacier Mint
    val MoodCalmSecondaryAccent = Color(0xFF26A69A)

    val MoodEnergeticAccent          = Color(0xFFFF8C42) // Solar Coral
    val MoodEnergeticSecondaryAccent = Color(0xFFFF5252)

    val MoodReflectiveAccent          = Color(0xFFA855F7) // Deep Violet
    val MoodReflectiveSecondaryAccent = Color(0xFF6366F1)

    val MoodUpliftingAccent          = Color(0xFFFBBF24) // Radiant Sun
    val MoodUpliftingSecondaryAccent = Color(0xFFF59E0B)

    val MoodIntenseAccent          = Color(0xFFFF5252) // High Octane Crimson
    val MoodIntenseSecondaryAccent = Color(0xFFFF8C42)

    // Status & Utility Colors
    val SafetyGreen = DeepObsidianAmber.DecibelSafe
    val SafetyAmber = DeepObsidianAmber.DecibelCaution
    val SafetyRed   = DeepObsidianAmber.DecibelPeak
    val BrandIndigo = DeepObsidianAmber.NeuralViolet
}
