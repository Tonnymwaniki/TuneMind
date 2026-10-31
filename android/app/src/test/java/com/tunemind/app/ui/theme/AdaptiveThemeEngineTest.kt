package com.tunemind.app.ui.theme

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class AdaptiveThemeEngineTest {

    @Test
    fun testMorningPeriodResolution() {
        assertEquals(TimePeriod.MORNING, AdaptiveThemeEngine.resolveTimePeriod(5, 0))
        assertEquals(TimePeriod.MORNING, AdaptiveThemeEngine.resolveTimePeriod(8, 30))
        assertEquals(TimePeriod.MORNING, AdaptiveThemeEngine.resolveTimePeriod(11, 59))
    }

    @Test
    fun testAfternoonPeriodResolution() {
        assertEquals(TimePeriod.AFTERNOON, AdaptiveThemeEngine.resolveTimePeriod(12, 0))
        assertEquals(TimePeriod.AFTERNOON, AdaptiveThemeEngine.resolveTimePeriod(14, 15))
        assertEquals(TimePeriod.AFTERNOON, AdaptiveThemeEngine.resolveTimePeriod(16, 59))
    }

    @Test
    fun testEveningPeriodResolution() {
        assertEquals(TimePeriod.EVENING, AdaptiveThemeEngine.resolveTimePeriod(17, 0))
        assertEquals(TimePeriod.EVENING, AdaptiveThemeEngine.resolveTimePeriod(19, 45))
        assertEquals(TimePeriod.EVENING, AdaptiveThemeEngine.resolveTimePeriod(20, 59))
    }

    @Test
    fun testNightPeriodResolution() {
        assertEquals(TimePeriod.NIGHT, AdaptiveThemeEngine.resolveTimePeriod(21, 0))
        assertEquals(TimePeriod.NIGHT, AdaptiveThemeEngine.resolveTimePeriod(23, 59))
        assertEquals(TimePeriod.NIGHT, AdaptiveThemeEngine.resolveTimePeriod(0, 0))
        assertEquals(TimePeriod.NIGHT, AdaptiveThemeEngine.resolveTimePeriod(3, 30))
        assertEquals(TimePeriod.NIGHT, AdaptiveThemeEngine.resolveTimePeriod(4, 59))
    }

    @Test
    fun testPaletteHexValuesMatchSpecification() {
        // Morning — First Light
        assertEquals(0xFF111C2C, TuneMindColors.MorningPrimaryBackground.value.toLong() shr 32 or (TuneMindColors.MorningPrimaryBackground.value.toLong() and 0xFFFFFFFFL))
        assertEquals(0xFFE5B879, TuneMindColors.MorningPrimaryAccent.value.toLong() shr 32 or (TuneMindColors.MorningPrimaryAccent.value.toLong() and 0xFFFFFFFFL))

        // Afternoon — Clarity
        assertEquals(0xFF142235, TuneMindColors.AfternoonPrimaryBackground.value.toLong() shr 32 or (TuneMindColors.AfternoonPrimaryBackground.value.toLong() and 0xFFFFFFFFL))
        assertEquals(0xFF83B9BD, TuneMindColors.AfternoonPrimaryAccent.value.toLong() shr 32 or (TuneMindColors.AfternoonPrimaryAccent.value.toLong() and 0xFFFFFFFFL))

        // Evening — Transition
        assertEquals(0xFF151A2A, TuneMindColors.EveningPrimaryBackground.value.toLong() shr 32 or (TuneMindColors.EveningPrimaryBackground.value.toLong() and 0xFFFFFFFFL))
        assertEquals(0xFFD99A69, TuneMindColors.EveningPrimaryAccent.value.toLong() shr 32 or (TuneMindColors.EveningPrimaryAccent.value.toLong() and 0xFFFFFFFFL))

        // Night — Deep Focus
        assertEquals(0xFF0C1220, TuneMindColors.NightPrimaryBackground.value.toLong() shr 32 or (TuneMindColors.NightPrimaryBackground.value.toLong() and 0xFFFFFFFFL))
        assertEquals(0xFFA997D8, TuneMindColors.NightPrimaryAccent.value.toLong() shr 32 or (TuneMindColors.NightPrimaryAccent.value.toLong() and 0xFFFFFFFFL))
    }

    @Test
    fun testMoodExtractionFromFeatures() {
        val energetic = AdaptiveThemeEngine.resolveTrackMood("Track", "Artist", energy = 0.85f, valence = 0.75f)
        assertEquals(MusicalMood.ENERGETIC, energetic)

        val calm = AdaptiveThemeEngine.resolveTrackMood("Track", "Artist", energy = 0.30f, valence = 0.60f)
        assertEquals(MusicalMood.CALM, calm)

        val reflective = AdaptiveThemeEngine.resolveTrackMood("Track", "Artist", energy = 0.35f, valence = 0.40f)
        assertEquals(MusicalMood.REFLECTIVE, reflective)

        val intense = AdaptiveThemeEngine.resolveTrackMood("Track", "Artist", energy = 0.90f, valence = 0.30f)
        assertEquals(MusicalMood.INTENSE, intense)
    }

    @Test
    fun testMoodExtractionSemanticFallbackWithoutFabrication() {
        val calm = AdaptiveThemeEngine.resolveTrackMood("Weightless (Ambient Sleep)", "Marconi Union")
        assertEquals(MusicalMood.CALM, calm)

        val energetic = AdaptiveThemeEngine.resolveTrackMood("Electronic Dance Workout", "Club Beats")
        assertEquals(MusicalMood.ENERGETIC, energetic)

        val unknown = AdaptiveThemeEngine.resolveTrackMood("Track 4", "Unknown Artist")
        assertEquals(MusicalMood.UNKNOWN, unknown)
    }

    @Test
    fun testFixedThemeOverridesTimeOfDay() {
        val calMorning = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 0)
        }
        val prefs = ThemePreferences(
            behavior = ThemeBehavior.FIXED,
            fixedPaletteId = "night"
        )
        val state = AdaptiveThemeEngine.composeTheme(prefs, calendar = calMorning)
        assertEquals(TuneMindPalettes.Night.id, state.activePalette.id)
    }

    @Test
    fun testAdaptiveThemeAppliesMorningAtEightAm() {
        val calMorning = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 0)
        }
        val prefs = ThemePreferences(behavior = ThemeBehavior.ADAPTIVE)
        val state = AdaptiveThemeEngine.composeTheme(prefs, calendar = calMorning)
        assertEquals(TimePeriod.MORNING, state.timePeriod)
        assertEquals(TuneMindPalettes.Morning.id, state.activePalette.id)
    }

    @Test
    fun testReducedMotionDisablesTransitionDuration() {
        val prefs = ThemePreferences(reducedMotion = true)
        val state = AdaptiveThemeEngine.composeTheme(prefs)
        assertEquals(0, state.animationDurationMs)
    }

    @Test
    fun testHighContrastIncreasesTextBrightness() {
        val prefs = ThemePreferences(highContrast = true)
        val state = AdaptiveThemeEngine.composeTheme(prefs)
        assertEquals(androidx.compose.ui.graphics.Color.White, state.primaryText)
    }
}
