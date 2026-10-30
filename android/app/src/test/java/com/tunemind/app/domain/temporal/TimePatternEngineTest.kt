package com.tunemind.app.domain.temporal

import com.tunemind.app.domain.model.TrackIdentityBasis
import com.tunemind.app.domain.model.TrackMemoryEntry
import com.tunemind.app.domain.model.MemoryAppearanceEvidence
import com.tunemind.app.domain.model.TrackAppearanceEvidence
import com.tunemind.app.domain.model.MemoryEvidenceQuality
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class TimePatternEngineTest {

    @Test
    fun testCurrentSlot_isNotNull() {
        val slot = TimeSlot.currentSlot()
        assertNotNull(slot)
        assertTrue(slot.label.isNotBlank())
    }

    @Test
    fun testSlotForHour_returnsCorrectCategory() {
        assertEquals(TimeSlot.MORNING, TimeSlot.slotForHour(9))
        assertEquals(TimeSlot.AFTERNOON, TimeSlot.slotForHour(14))
        assertEquals(TimeSlot.EVENING, TimeSlot.slotForHour(19))
        assertEquals(TimeSlot.NIGHT, TimeSlot.slotForHour(22))
        assertEquals(TimeSlot.LATE_NIGHT, TimeSlot.slotForHour(2))
    }

    @Test
    fun testComputeTemporalProfile() {
        val engine = TimePatternEngine()

        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 10) // Morning
        val morningTime = cal.timeInMillis

        val appearance = MemoryAppearanceEvidence(
            reconstructedAppearanceId = "app-1",
            firstObservedAtMs = morningTime,
            lastObservedAtMs = morningTime + 180_000L,
            supportingObservationIds = listOf("obs-1"),
            evidenceStatus = TrackAppearanceEvidence.DEFINITIVE,
            hasTimestampOrderingAmbiguity = false,
            hasObservationGap = false
        )

        val track = TrackMemoryEntry(
            entryId = "track-1",
            sourcePackage = "com.spotify.music",
            providerTrackId = "spotify:track:123",
            title = "Morning Sunshine",
            artist = "Acoustic Duo",
            album = "Sunrise",
            distinctAppearanceCount = 1,
            firstObservedAppearanceAtMs = morningTime,
            lastObservedAppearanceAtMs = morningTime + 180_000L,
            supportingObservationIds = listOf("obs-1"),
            identityBasis = TrackIdentityBasis.TITLE_AND_ARTIST,
            evidenceQuality = MemoryEvidenceQuality.SUFFICIENT,
            historyMayBeIncomplete = false,
            appearances = listOf(appearance)
        )

        val profile = engine.computeTemporalProfile(listOf(track))
        val morningProfile = profile.firstOrNull { it.slot == TimeSlot.MORNING }
        assertNotNull(morningProfile)
        assertEquals(1, morningProfile?.estimatedSessionCount)
        assertTrue(morningProfile?.topTracks?.contains("Morning Sunshine") == true)
        assertTrue(morningProfile?.topArtists?.contains("Acoustic Duo") == true)
    }
}
