package com.tunemind.app.domain.recommendation

import com.tunemind.app.domain.model.ListeningMemory
import com.tunemind.app.domain.model.TrackMemoryEntry
import com.tunemind.app.ui.VaultTrack
import java.util.UUID

/**
 * Intelligent recommendation engine for TuneMind.
 * Evolves across 3 stages:
 *   Stage 1: Evidence-based patterns from real local listening history.
 *   Stage 2: Restored tracks from cooldown vault and adjacent discoveries.
 *   Stage 3: Longitudinal multi-day receptive horizon calibration.
 */
object RecommendationEngine {

    /**
     * Generates a ranked list of personalized recommendations based on observed listening evidence.
     */
    fun generateRecommendations(
        memory: ListeningMemory,
        vaultTracks: List<VaultTrack> = emptyList(),
        preferredProviderId: String = "spotify"
    ): List<RecommendedTrack> {
        val recommendations = mutableListOf<RecommendedTrack>()
        val quarantinedTitles = vaultTracks.map { it.title.lowercase().trim() }.toSet()

        val validCapturedTracks = memory.tracks
            .filter { !it.title.isNullOrBlank() }
            .sortedByDescending { it.distinctAppearanceCount }

        // 1. Familiar Comfort: Tracks played multiple times with high sustained affinity (not in vault)
        val familiarCandidates = validCapturedTracks
            .filter { (it.title?.lowercase()?.trim() ?: "") !in quarantinedTitles }
            .take(3)

        for (track in familiarCandidates) {
            val plays = track.distinctAppearanceCount
            val satietyScore = minOf(95, plays * 22)

            if (satietyScore < 80) {
                recommendations.add(
                    RecommendedTrack(
                        id = "rec-fam-${track.entryId}",
                        title = track.title ?: "Unknown Track",
                        artist = track.artist ?: "Unknown Artist",
                        album = track.album,
                        reason = "You returned to ${track.artist} $plays times with sustained listening. Optimal acoustic familiarity.",
                        confidence = (0.75f + minOf(0.20f, plays * 0.05f)),
                        category = RecommendationCategory.FAMILIAR_RECEPTIVE,
                        suggestedProviderId = preferredProviderId,
                        providerTrackId = track.providerTrackId,
                        observationCountBasis = plays
                    )
                )
            }
        }

        // 2. Cooldown Restored: Vault tracks whose receptor recovery has progressed >= 50%
        val restoredVaultCandidates = vaultTracks
            .filter { it.recoveryProgress >= 0.50f }
            .sortedByDescending { it.recoveryProgress }
            .take(2)

        for (vault in restoredVaultCandidates) {
            val pctRestored = (vault.recoveryProgress * 100).toInt()
            recommendations.add(
                RecommendedTrack(
                    id = "rec-vault-${vault.id}",
                    title = vault.title,
                    artist = vault.artist,
                    reason = "Quarantined for receptor rest. Receptivity is now $pctRestored% restored via biological decay (e^-0.18t).",
                    confidence = 0.90f,
                    category = RecommendationCategory.COOLDOWN_RESTORED,
                    suggestedProviderId = preferredProviderId,
                    observationCountBasis = 1
                )
            )
        }

        // 3. Discovery Recommendations: Derived from observed top artists or curated exploration
        val topArtist = validCapturedTracks.firstOrNull()?.artist
        if (!topArtist.isNullOrBlank()) {
            recommendations.add(
                RecommendedTrack(
                    id = "rec-disc-${UUID.randomUUID()}",
                    title = "Acoustic Horizon Discovery",
                    artist = topArtist,
                    reason = "Discovered based on your active listening to $topArtist. Expands your artist cluster.",
                    confidence = 0.82f,
                    category = RecommendationCategory.EXPLORATION_DISCOVERY,
                    suggestedProviderId = preferredProviderId,
                    observationCountBasis = validCapturedTracks.first().distinctAppearanceCount
                )
            )
        }

        // 4. Fallback Starter Recommendations if the user just installed TuneMind
        if (recommendations.isEmpty()) {
            recommendations.addAll(starterRecommendations(preferredProviderId))
        }

        return recommendations
    }

    private fun starterRecommendations(preferredProviderId: String): List<RecommendedTrack> {
        return listOf(
            RecommendedTrack(
                id = "starter-1",
                title = "Midnight Thoughts",
                artist = "Luna Rivers",
                album = "Atmospheric Sessions",
                genre = "Lo-Fi Atmosphere",
                reason = "Curated acoustic baseline starter with gentle rhythm and high receptivity index.",
                confidence = 0.88f,
                category = RecommendationCategory.EXPLORATION_DISCOVERY,
                suggestedProviderId = preferredProviderId,
                observationCountBasis = 1
            ),
            RecommendedTrack(
                id = "starter-2",
                title = "Sunset Drive",
                artist = "Solar Pulse",
                album = "Neon Horizon",
                genre = "Synthwave • Melodic",
                reason = "Balanced groove anchor designed to test rhythmic pacing during initial onboarding.",
                confidence = 0.85f,
                category = RecommendationCategory.EXPLORATION_DISCOVERY,
                suggestedProviderId = preferredProviderId,
                observationCountBasis = 1
            ),
            RecommendedTrack(
                id = "starter-3",
                title = "Weightless",
                artist = "Marconi Union",
                genre = "Ambient Neurological",
                reason = "Acoustic palate cleanser designed to minimize auditory saturation and test baseline reset.",
                confidence = 0.94f,
                category = RecommendationCategory.PALATE_CLEANSER,
                suggestedProviderId = preferredProviderId,
                observationCountBasis = 1
            )
        )
    }
}
