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
    // 1. CHASSIS & DEPTH FOUNDATION (True Inky Carbon Obsidian)
    // ─────────────────────────────────────────────────────────────
    val AppBackground         = Color(0xFF0D0F14) // Deep studio chassis black
    val SecondaryBackground   = Color(0xFF131720) // Sub-layer chassis slate
    val RecessedBackground    = Color(0xFF090B0E) // Recessed meter & well depth

    // ─────────────────────────────────────────────────────────────
    // 2. LAYERED SURFACES (Deep Obsidian Slate with High-Contrast Elevation)
    // ─────────────────────────────────────────────────────────────
    val CardBackground        = Color(0xFF181C26) // Standard card surface
    val CardElevated          = Color(0xFF222736) // Interactive elevated deck
    val SurfaceHighlight      = Color(0xFF2D3548) // Active hover / selected tint
    val SurfaceBorder         = Color(0xFF38435C) // Hairline metallic chassis border

    // ─────────────────────────────────────────────────────────────
    // 3. BRAND SOUL ACCENTS (Locked Signature Deep Obsidian Amber)
    // ─────────────────────────────────────────────────────────────
    val BrandSoulAmber        = Color(0xFF652814) // The foundational amber soul
    val PrimaryAccent         = Color(0xFFD48344) // Luminous warm amber
    val SecondaryAccent       = Color(0xFFE8A65D) // Warm acoustic gold
    val AccentLight           = Color(0xFFF5B773) // High-exposure amber glow
    val AccentSoft            = Color(0xFFFBE2C5) // Cream amber highlight
    val CopperAccent          = Color(0xFF9E4B28) // Burnished copper metallic

    // ─────────────────────────────────────────────────────────────
    // 4. TYPOGRAPHY TOKENS (Crisp Optic Clarity on Carbon Obsidian)
    // ─────────────────────────────────────────────────────────────
    val TextPrimary           = Color(0xFFF5F7FA) // Crisp ivory white (maximum contrast)
    val TextSecondary         = Color(0xFFB0B7C6) // Cool slate silver
    val TextMuted             = Color(0xFF758095) // Precision engineering label grey
    val TextDisabled          = Color(0xFF4A5468) // Subdued indicator

    // ─────────────────────────────────────────────────────────────
    // 5. FEATURE DOMAIN ACCENTS (Creative Distinction Across Modules)
    // ─────────────────────────────────────────────────────────────
    // Live Spectrum & Equalizer
    val EQBassCyan            = Color(0xFF00E5FF) // Sub-bass & low frequencies (20-250Hz)
    val EQMidAmber            = Color(0xFFE8A65D) // Warm midrange (250-4000Hz)
    val EQHighRose            = Color(0xFFFF5252) // Harmonic highs & transients (4k-16kHz)

    // Cooldown Vault (Cryogenic Preservation Aesthetic)
    val CryoCyan              = Color(0xFF00F0FF) // Chilled liquid cryo blue
    val GlacialBlue           = Color(0xFF4E95FF) // Restorative frozen blue
    val FrostDeep             = Color(0xFF101B2B) // Cryo-chamber well

    // Satiety Lab & Song Fatigue (Thermal Burnout & Overplay Alerts)
    val SatietyEmber          = Color(0xFFFF5757) // High fatigue critical alert
    val FatigueCoral          = Color(0xFFFF8C42) // Mid fatigue warning glow
    val ThermalWarning        = Color(0xFFFFB74D) // Early repetition advisory

    // Acoustic Genome & Neural Intelligence (Continuous AI Brain)
    val NeuralViolet          = Color(0xFFA855F7) // High-dimensional feature vector
    val ElectricViolet        = Color(0xFF7C3AED) // Deep latent space cluster
    val GenomeIndigo          = Color(0xFF4F46E5) // Neural memory synapse

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
