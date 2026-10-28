package com.tunemind.app.domain.recommendation

import com.tunemind.app.domain.model.ListeningMemory
import com.tunemind.app.domain.model.TrackIdentityBasis
import com.tunemind.app.domain.model.TrackMemoryEntry
import com.tunemind.app.ui.VaultTrack
import org.junit.Assert.*
import org.junit.Test

class RecommendationEngineTest {

    @Test
    fun testGenerateRecommendationsWithEmptyMemoryReturnsStarters() {
        val memory = ListeningMemory(
            tracks = emptyList(),
            unidentifiedAppearanceCount = 0,
            historyMayBeIncomplete = false
        )
        val recommendations = RecommendationEngine.generateRecommendations(
            memory = memory,
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
        val memory = ListeningMemory(
            tracks = listOf(
                TrackMemoryEntry(
                    entryId = "track-1",
                    title = "Midnight City",
                    artist = "M83",
                    album = "Hurry Up, We're Dreaming",
                    distinctAppearanceCount = 5,
                    firstObservedAppearanceAtMs = 1000L,
                    lastObservedAppearanceAtMs = 2000L,
                    identityBasis = TrackIdentityBasis.TITLE_AND_ARTIST
                ),
                TrackMemoryEntry(
                    entryId = "track-2",
                    title = "Instant Crush",
                    artist = "Daft Punk",
                    album = "Random Access Memories",
                    distinctAppearanceCount = 2,
                    firstObservedAppearanceAtMs = 1500L,
                    lastObservedAppearanceAtMs = 2500L,
                    identityBasis = TrackIdentityBasis.TITLE_AND_ARTIST
                )
            ),
            unidentifiedAppearanceCount = 0,
            historyMayBeIncomplete = false
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
            memory = memory,
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
        val memory = ListeningMemory(
            tracks = emptyList(),
            unidentifiedAppearanceCount = 0,
            historyMayBeIncomplete = false
        )
        val recommendations = RecommendationEngine.generateRecommendations(
            memory = memory,
            vaultTracks = emptyList(),
            preferredProviderId = "spotify"
        )

        recommendations.forEach { rec ->
            assertTrue("Confidence score should be >= 0.0", rec.confidence >= 0f)
            assertTrue("Confidence score should be <= 1.0", rec.confidence <= 1f)
        }
    }
}
