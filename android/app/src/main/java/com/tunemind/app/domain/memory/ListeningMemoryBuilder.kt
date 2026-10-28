package com.tunemind.app.domain.memory

import com.tunemind.app.domain.model.ArtistMemorySummary
import com.tunemind.app.domain.model.EpisodeStatus
import com.tunemind.app.domain.model.ListeningMemory
import com.tunemind.app.domain.model.ListeningMemorySummary
import com.tunemind.app.domain.model.MemoryAppearanceEvidence
import com.tunemind.app.domain.model.MemoryDataQualityNotice
import com.tunemind.app.domain.model.MemoryEvidenceQuality
import com.tunemind.app.domain.model.PlaybackObservation
import com.tunemind.app.domain.model.ReconstructedPlaybackEpisode
import com.tunemind.app.domain.model.TrackAppearance
import com.tunemind.app.domain.model.TrackIdentityBasis
import com.tunemind.app.domain.model.TrackMemoryEntry
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/** Pure aggregation of retained raw records and v1 reconstructed appearances. */
class ListeningMemoryBuilder {
    fun build(
        observations: List<PlaybackObservation>,
        episodes: List<ReconstructedPlaybackEpisode>,
        retainedHistoryMayBeIncomplete: Boolean = observations.size >= RETENTION_LIMIT,
    ): ListeningMemory {
        val notices = linkedSetOf<MemoryDataQualityNotice>()
        if (observations.isEmpty()) notices += MemoryDataQualityNotice.NO_RAW_OBSERVATIONS
        else notices += MemoryDataQualityNotice.CALLBACK_HISTORY_LIMITED
        if (observations.size >= RETENTION_LIMIT || retainedHistoryMayBeIncomplete) notices += MemoryDataQualityNotice.RETENTION_LIMIT_REACHED

        val rawById = observations.groupBy { it.eventId }.mapValues { (_, rows) -> rows.minBy { rawCanonical(it) } }
        if (episodes.any { episode ->
                episode.hasObservationGap || episode.hasTimestampOrderingAmbiguity || episode.status !in setOf(EpisodeStatus.ENDED_BY_OBSERVED_STOP, EpisodeStatus.ENDED_BY_OBSERVED_NON_PLAYING_STATE)
            }) notices += MemoryDataQualityNotice.AMBIGUOUS_RECONSTRUCTION
        val candidates = episodes.flatMap { episode ->
            episode.trackAppearances.map { appearance ->
                val withEpisodeSource = if (appearance.sourcePackage == null && episode.sourcePackage != null) appearance.copy(sourcePackage = episode.sourcePackage) else appearance
                Candidate(withEpisodeSource, episode)
            }
        }
        if (candidates.isEmpty() && observations.isNotEmpty()) notices += MemoryDataQualityNotice.NO_RECONSTRUCTED_APPEARANCES
        val uniqueCandidates = candidates.groupBy { it.appearance.appearanceId }.values.map { sameId ->
            if (sameId.map { candidateCanonical(it) }.distinct().size > 1) notices += MemoryDataQualityNotice.AMBIGUOUS_RECONSTRUCTION
            sameId.minBy { candidateCanonical(it) }
        }.sortedWith(compareBy<Candidate> { it.appearance.firstObservedAtMs }.thenBy { it.appearance.appearanceId })

        val groups = uniqueCandidates.groupBy { identityKey(it.appearance) }
        val tracks = groups.entries.map { (key, values) -> aggregateTrack(key, values, retainedHistoryMayBeIncomplete, rawById, notices) }
            .sortedWith(compareBy<TrackMemoryEntry> { normalize(it.artist).orEmpty() }.thenBy { normalize(it.title).orEmpty() }.thenBy { it.entryId })

        if (tracks.any { it.sourcePackage == null }) notices += MemoryDataQualityNotice.MISSING_SOURCE_PACKAGE
        if (tracks.any { it.title == null || it.artist == null || it.album == null }) notices += MemoryDataQualityNotice.MISSING_TRACK_METADATA
        if (tracks.any { it.identityBasis == TrackIdentityBasis.INSUFFICIENT }) notices += MemoryDataQualityNotice.INSUFFICIENT_TRACK_IDENTITY
        if (tracks.any { it.evidenceQuality == MemoryEvidenceQuality.CONFLICTING_METADATA }) notices += MemoryDataQualityNotice.CONFLICTING_METADATA
        if (tracks.any { it.evidenceQuality == MemoryEvidenceQuality.AMBIGUOUS_RECONSTRUCTION }) notices += MemoryDataQualityNotice.AMBIGUOUS_RECONSTRUCTION
        if (tracks.any { it.evidenceQuality == MemoryEvidenceQuality.RAW_EVIDENCE_UNAVAILABLE }) notices += MemoryDataQualityNotice.RAW_EVIDENCE_UNAVAILABLE
        if (tracks.none { !it.artist.isNullOrBlank() && it.identityBasis != TrackIdentityBasis.INSUFFICIENT }) notices += MemoryDataQualityNotice.MISSING_ARTIST_METADATA

        val artists = tracks.asSequence()
            .filter { it.identityBasis != TrackIdentityBasis.INSUFFICIENT && !it.artist.isNullOrBlank() && it.evidenceQuality != MemoryEvidenceQuality.CONFLICTING_METADATA }
            .groupBy { normalize(it.artist) ?: "unknown" }
            .map { (_, artistTracks) ->
                val labels = artistTracks.mapNotNull { it.artist }.distinct().sortedWith(compareBy<String> { it.lowercase(Locale.ROOT) }.thenBy { it })
                ArtistMemorySummary(
                    artistLabel = labels.first(),
                    distinctIdentifiedTrackCount = artistTracks.map { it.entryId }.distinct().size,
                    distinctReconstructedAppearanceCount = artistTracks.flatMap { it.appearances }.map { it.reconstructedAppearanceId }.distinct().size,
                )
            }.sortedBy { normalize(it.artistLabel) }

        val appearanceCount = uniqueCandidates.size
        val identified = tracks.filter { it.identityBasis != TrackIdentityBasis.INSUFFICIENT }
        return ListeningMemory(
            summary = ListeningMemorySummary(
                identifiableTrackCount = identified.size,
                distinctReconstructedAppearanceCount = appearanceCount,
                recurringTrackIdentityCount = identified.count {
                    it.distinctAppearanceCount >= 2 && it.evidenceQuality !in setOf(MemoryEvidenceQuality.CONFLICTING_METADATA, MemoryEvidenceQuality.RAW_EVIDENCE_UNAVAILABLE)
                },
                identifiableArtistSummaryCount = artists.size,
                unidentifiedAppearanceCount = tracks.filter { it.identityBasis == TrackIdentityBasis.INSUFFICIENT }.sumOf { it.distinctAppearanceCount },
                historyMayBeIncomplete = observations.isNotEmpty() || retainedHistoryMayBeIncomplete || episodes.any { episode ->
                    episode.status !in setOf(EpisodeStatus.ENDED_BY_OBSERVED_STOP, EpisodeStatus.ENDED_BY_OBSERVED_NON_PLAYING_STATE) ||
                        episode.hasObservationGap || episode.hasTimestampOrderingAmbiguity || episode.startBoundary.name == "RETENTION_WINDOW_TRUNCATED" || episode.endBoundary.name == "RETENTION_WINDOW_TRUNCATED"
                },
            ),
            tracks = tracks,
            artists = artists,
            notices = notices.toList().sortedBy { it.name },
        )
    }

    private fun aggregateTrack(
        key: String,
        candidates: List<Candidate>,
        retainedHistoryMayBeIncomplete: Boolean,
        rawById: Map<String, PlaybackObservation>,
        notices: MutableSet<MemoryDataQualityNotice>,
    ): TrackMemoryEntry {
        val appearances = candidates.map { candidate ->
            val a = candidate.appearance
            val supporting = a.rawObservationIds.filter { id ->
                val raw = rawById[id] ?: return@filter false
                raw.sourcePackage == null || a.sourcePackage == null || normalize(raw.sourcePackage) == normalize(a.sourcePackage)
            }.distinct().sorted()
            MemoryAppearanceEvidence(
                reconstructedAppearanceId = a.appearanceId,
                firstObservedAtMs = a.firstObservedAtMs,
                lastObservedAtMs = a.lastObservedAtMs,
                supportingObservationIds = supporting,
                evidenceStatus = a.evidenceStatus,
                hasTimestampOrderingAmbiguity = candidate.episode.hasTimestampOrderingAmbiguity,
                hasObservationGap = candidate.episode.hasObservationGap,
            )
        }.sortedWith(compareBy<MemoryAppearanceEvidence> { it.firstObservedAtMs }.thenBy { it.reconstructedAppearanceId })
        val representative = candidates.minBy { candidateCanonical(it) }.appearance
        val source = stableValue(candidates.map { it.appearance.sourcePackage })
        val trackId = stableValue(candidates.map { it.appearance.trackId })
        val title = stableValue(candidates.map { it.appearance.title })
        val artist = stableValue(candidates.map { it.appearance.artist })
        val album = stableValue(candidates.map { it.appearance.album })
        val hasConflicts = listOf(candidates.map { it.appearance.sourcePackage }, candidates.map { it.appearance.trackId }, candidates.map { it.appearance.title }, candidates.map { it.appearance.artist }, candidates.map { it.appearance.album })
            .any { values -> normalizedDistinct(values).size > 1 }
        val unavailableRawEvidence = candidates.any { candidate ->
            candidate.appearance.rawObservationIds.isEmpty() || appearances.firstOrNull { it.reconstructedAppearanceId == candidate.appearance.appearanceId }?.supportingObservationIds.isNullOrEmpty() ||
                candidate.appearance.rawObservationIds.any { id ->
                    val raw = rawById[id]
                    raw == null || (raw.sourcePackage != null && candidate.appearance.sourcePackage != null && normalize(raw.sourcePackage) != normalize(candidate.appearance.sourcePackage))
                }
        }
        if (unavailableRawEvidence) notices += MemoryDataQualityNotice.RAW_EVIDENCE_UNAVAILABLE
        val ambiguousReconstruction = candidates.any { it.episode.hasTimestampOrderingAmbiguity || it.episode.hasObservationGap || it.episode.status !in setOf(EpisodeStatus.ENDED_BY_OBSERVED_STOP, EpisodeStatus.ENDED_BY_OBSERVED_NON_PLAYING_STATE) }
        val identityBasis = when {
            key.startsWith("provider|") -> TrackIdentityBasis.PROVIDER_TRACK_ID
            key.startsWith("metadata|") -> TrackIdentityBasis.METADATA_FINGERPRINT
            else -> TrackIdentityBasis.INSUFFICIENT
        }
        val quality = when {
            unavailableRawEvidence -> MemoryEvidenceQuality.RAW_EVIDENCE_UNAVAILABLE
            hasConflicts -> MemoryEvidenceQuality.CONFLICTING_METADATA
            ambiguousReconstruction -> MemoryEvidenceQuality.AMBIGUOUS_RECONSTRUCTION
            identityBasis == TrackIdentityBasis.INSUFFICIENT -> MemoryEvidenceQuality.INSUFFICIENT_IDENTITY
            else -> MemoryEvidenceQuality.SUPPORTED_IDENTITY
        }
        val entryKey = key.ifEmpty { "unknown|${representative.appearanceId}" }
        return TrackMemoryEntry(
            entryId = "memory:${sha256(entryKey)}",
            sourcePackage = source,
            providerTrackId = trackId,
            title = title,
            artist = artist,
            album = album,
            distinctAppearanceCount = appearances.map { it.reconstructedAppearanceId }.distinct().size,
            firstObservedAppearanceAtMs = appearances.minOfOrNull { it.firstObservedAtMs },
            lastObservedAppearanceAtMs = appearances.maxOfOrNull { it.lastObservedAtMs },
            supportingObservationIds = appearances.flatMap { it.supportingObservationIds }.distinct().sorted(),
            identityBasis = identityBasis,
            evidenceQuality = quality,
            historyMayBeIncomplete = retainedHistoryMayBeIncomplete || candidates.isNotEmpty(),
            appearances = appearances,
        )
    }

    private fun identityKey(a: TrackAppearance): String {
        val source = normalize(a.sourcePackage)
        val providerId = a.trackId?.trim()?.takeIf { it.isNotEmpty() }
        if (source != null && providerId != null) return "provider|${encodeParts(source, providerId)}"
        // Strict fallback: source, title, artist, and album must all be present.
        val title = normalize(a.title); val artist = normalize(a.artist); val album = normalize(a.album)
        if (source != null && title != null && artist != null && album != null && providerId == null) return "metadata|${encodeParts(source, title, artist, album)}"
        return "unknown|${a.appearanceId}"
    }

    private data class Candidate(val appearance: TrackAppearance, val episode: ReconstructedPlaybackEpisode)
    private fun normalize(value: String?): String? = value?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
    private fun normalizedDistinct(values: List<String?>): Set<String> = values.mapNotNull(::normalize).toSet()
    private fun stableValue(values: List<String?>): String? {
        val distinct = normalizedDistinct(values)
        if (distinct.size != 1) return null
        return values.filterNotNull().filter { normalize(it) in distinct }.minWithOrNull(compareBy<String> { normalize(it) }.thenBy { it })
    }
    private fun candidateCanonical(c: Candidate): String = canonical(listOf(
        c.appearance.appearanceId, c.appearance.sourcePackage, c.appearance.trackId, c.appearance.title,
        c.appearance.artist, c.appearance.album, c.appearance.durationMs, c.appearance.firstObservedAtMs,
        c.appearance.lastObservedAtMs, c.appearance.identityBasis.name, c.appearance.evidenceStatus.name,
        c.appearance.rawObservationIds.sorted().joinToString(","), c.episode.status.name,
        c.episode.startBoundary.name, c.episode.endBoundary.name, c.episode.hasObservationGap, c.episode.hasTimestampOrderingAmbiguity,
    ))
    private fun rawCanonical(o: PlaybackObservation): String = canonical(listOf(
        o.schemaVersion, o.observerSessionId, o.eventType.name, o.eventTimeMs, o.sourcePackage, o.trackId,
        o.trackTitle, o.trackArtist, o.trackAlbum, o.durationMs, o.playbackStateCode, o.positionMs,
        o.playbackSpeed, o.positionUpdateElapsedRealtimeMs, o.savedAtMs,
    ))
    private fun canonical(parts: List<Any?>): String = parts.joinToString("") { part ->
        val text = part?.toString() ?: return@joinToString "-1:"
        "${text.length}:$text"
    }
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    private fun encodeParts(vararg parts: String): String = parts.joinToString("") { "${it.length}:$it" }

    companion object { const val RETENTION_LIMIT = 500 }
}
