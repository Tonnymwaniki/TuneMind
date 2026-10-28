package com.tunemind.app.domain.recommendation

import com.tunemind.app.domain.model.MemoryEvidenceQuality
import com.tunemind.app.domain.model.TrackAppearanceEvidence
import com.tunemind.app.domain.model.TrackIdentityBasis
import com.tunemind.app.domain.model.TrackMemoryEntry
import com.tunemind.app.ui.VaultTrack
import org.junit.Assert.*
import org.junit.Test

class RecommendationEngineTest {

    private fun createTestTrack(
        id: String,
        title: String,
        artist: String,
        distinctPlays: Int
    ): TrackMemoryEntry {
        return TrackMemoryEntry(
            entryId = id,
            sourcePackage = "com.spotify.music",
            providerTrackId = id,
            title = title,
            artist = artist,
            album = "Test Album",
            distinctAppearanceCount = distinctPlays,
            firstObservedAppearanceAtMs = 1000L,
            lastObservedAppearanceAtMs = 2000L,
            supportingObservationIds = emptyList(),
            identityBasis = TrackIdentityBasis.TITLE_AND_ARTIST,
            evidenceQuality = MemoryEvidenceQuality.SUPPORTED_IDENTITY,
            historyMayBeIncomplete = false,
            appearances = emptyList()
        )
    }

    @Test
    fun testGenerateRecommendationsWithEmptyMemoryReturnsStarters() {
        val recommendations = RecommendationEngine.generateRecommendations(
            tracks = emptyList(),
            vaultTracks = emptyList(),
            preferredProviderId = "spotify"
        )

        assertFalse("Recommendations should not be empty", recommendations.isEmpty())
        assertTrue("All recommendations should have titles", recommendations.all { it.title.isNotBlank() })
        assertTrue("All recommendations should have artists", recommendations.all { it.artist.isNotBlank() })
        assertTrue("All recommendations should have non-empty reason explanations", recommendations.all { it.reason.isNotBlank() })
        assertTrue("Target provider should match preferredProviderId", recommendations.all { it.targetProviderId == "spotify" })
    }

    @Test
    fun testQuarantinedTracksAreExcludedFromRecommendations() {
        val tracks = listOf(
            createTestTrack("t-1", "Midnight City", "M83", 5),
            createTestTrack("t-2", "Instant Crush", "Daft Punk", 2)
        )

        val vault = listOf(
            VaultTrack(
                id = "v-1",
                title = "Midnight City",
                artist = "M83",
                quarantinedAtMs = System.currentTimeMillis()
            )
        )

        val recommendations = RecommendationEngine.generateRecommendations(
            tracks = tracks,
            vaultTracks = vault,
            preferredProviderId = "youtube_music"
        )

        // Ensure "Midnight City" is suppressed because it's actively resting in vault
        val midnightCityRec = recommendations.firstOrNull { it.title.equals("Midnight City", ignoreCase = true) }
        assertNull("Quarantined track should not be recommended", midnightCityRec)
        assertTrue("Target provider should be youtube_music", recommendations.all { it.targetProviderId == "youtube_music" })
    }

    @Test
    fun testConfidenceScoresAreBoundedBetweenZeroAndOne() {
        val recommendations = RecommendationEngine.generateRecommendations(
            tracks = emptyList(),
            vaultTracks = emptyList(),
            preferredProviderId = "spotify"
        )

        recommendations.forEach { rec ->
            assertTrue("Confidence score should be >= 0.0", rec.confidence >= 0f)
            assertTrue("Confidence score should be <= 1.0", rec.confidence <= 1f)
        }
    }
}
