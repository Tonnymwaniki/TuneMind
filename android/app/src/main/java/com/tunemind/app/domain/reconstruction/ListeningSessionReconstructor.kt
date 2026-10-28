package com.tunemind.app.domain.reconstruction

import com.tunemind.app.domain.event.ObservationEventType
import com.tunemind.app.domain.model.BoundaryEvidence
import com.tunemind.app.domain.model.EpisodeStatus
import com.tunemind.app.domain.model.PlaybackObservation
import com.tunemind.app.domain.model.ReconstructedPlaybackEpisode
import com.tunemind.app.domain.model.TrackAppearance
import com.tunemind.app.domain.model.TrackAppearanceEvidence
import com.tunemind.app.domain.model.TrackIdentityBasis

/**
 * Deterministic reconstruction over a bounded set of raw records. Wall-clock event time
 * orders observations; event IDs break ties. It cannot recover callback arrival order.
 * Only Android STATE_PLAYING (3) contributes active time. A span is counted only when
 * bounded by a later observation in the same source/observer scope and <= [maxActiveSpanMs].
 */
class ListeningSessionReconstructor(
    private val maxActiveSpanMs: Long = DEFAULT_MAX_ACTIVE_SPAN_MS,
    private val gapBoundaryMs: Long = DEFAULT_GAP_BOUNDARY_MS,
) {
    init { require(maxActiveSpanMs > 0); require(gapBoundaryMs >= maxActiveSpanMs) }

    fun reconstruct(input: List<PlaybackObservation>, retainedWindowMayBeTruncated: Boolean = input.size >= RETENTION_LIMIT): List<ReconstructedPlaybackEpisode> {
        if (input.isEmpty()) return emptyList()
        // Deduplicate identical event IDs deterministically. If conflicting payloads reuse an ID,
        // retain the lexicographically smallest canonical representation and flag ambiguity.
        val conflicts = input.groupBy { it.eventId }.filterValues { rows -> rows.map(::canonical).distinct().size > 1 }.keys
        val records = input.groupBy { it.eventId }.values.map { rows -> rows.minBy(::canonical) }
            .sortedWith(compareBy<PlaybackObservation> { it.eventTimeMs }.thenBy { it.eventId }.thenBy { it.eventType.name })

        val result = mutableListOf<ReconstructedPlaybackEpisode>()
        val byScope = records.groupBy { Scope(it.observerSessionId, it.sourcePackage) }
        byScope.toSortedMap(compareBy<Scope> { it.observerSessionId }.thenBy { it.sourcePackage.orEmpty() }).forEach { (scope, rows) ->
            var builder: Builder? = null
            var previous: PlaybackObservation? = null
            var timestampAmbiguity = false
            val scopeFirstTime = rows.first().eventTimeMs
            val scopeLastTime = rows.last().eventTimeMs
            val otherSessionStarts = records.asSequence()
                .filter { it.sourcePackage == scope.sourcePackage && it.observerSessionId != scope.observerSessionId }
                .groupBy { it.observerSessionId }.values.map { sessionRows -> sessionRows.minOf { it.eventTimeMs } }
            val hasEarlierObserverSession = otherSessionStarts.any { it < scopeFirstTime }
            val hasLaterObserverSession = otherSessionStarts.any { it > scopeLastTime }
            rows.forEach { row ->
                val prior = previous
                if (prior != null && row.eventTimeMs - prior.eventTimeMs > gapBoundaryMs) {
                    builder?.let { it.add(row, conflicts.contains(row.eventId)); close(it, row.eventTimeMs, BoundaryEvidence.GAP_CENSORED, EpisodeStatus.CLOSED_BY_GAP, timestampAmbiguity, result) }
                    builder = null
                }
                val elapsedWentBack = prior?.positionUpdateElapsedRealtimeMs != null && row.positionUpdateElapsedRealtimeMs != null && row.positionUpdateElapsedRealtimeMs < prior.positionUpdateElapsedRealtimeMs
                if (elapsedWentBack) timestampAmbiguity = true
                builder?.let { b ->
                    b.add(row, conflicts.contains(row.eventId))
                    val priorState = b.lastStateCode
                    val dt = row.eventTimeMs - (b.lastStateTimeMs ?: row.eventTimeMs)
                    if (priorState == STATE_PLAYING && row.playbackStateCode != null) {
                        if (dt in 0..maxActiveSpanMs) b.activeMs += dt else if (dt > maxActiveSpanMs) b.hasObservationGap = true
                        if (dt in 0..maxActiveSpanMs) b.hasBoundedPlayingSpan = true
                    }
                    if (row.eventType == ObservationEventType.SESSION_DESTROYED) {
                        close(b, row.eventTimeMs, BoundaryEvidence.OBSERVED_SESSION_DESTROYED, EpisodeStatus.ENDED_BY_SESSION_DESTRUCTION, timestampAmbiguity, result)
                        builder = null
                    } else if (row.playbackStateCode == STATE_STOPPED) {
                        close(b, row.eventTimeMs, BoundaryEvidence.OBSERVED_STOP, EpisodeStatus.ENDED_BY_OBSERVED_STOP, timestampAmbiguity, result)
                        builder = null
                    } else if (row.playbackStateCode == STATE_NONE) {
                        close(b, row.eventTimeMs, BoundaryEvidence.OBSERVED_NON_PLAYING_STATE, EpisodeStatus.ENDED_BY_OBSERVED_NON_PLAYING_STATE, timestampAmbiguity, result)
                        builder = null
                    } else if (row.playbackStateCode == STATE_PLAYING) {
                        b.lastStateCode = STATE_PLAYING
                        b.lastStateTimeMs = row.eventTimeMs
                    } else if (row.playbackStateCode != null && row.playbackStateCode != STATE_PLAYING) {
                        b.lastStateCode = row.playbackStateCode
                        b.lastStateTimeMs = row.eventTimeMs
                    } else if (row.eventType == ObservationEventType.PLAYBACK_STATE_CHANGED) {
                        b.lastStateCode = null
                        b.lastStateTimeMs = row.eventTimeMs
                        b.hasObservationGap = true
                    }
                }
                if (builder == null && row.playbackStateCode == STATE_PLAYING && row.eventType != ObservationEventType.SESSION_DESTROYED) {
                    builder = Builder(scope, row, when { retainedWindowMayBeTruncated -> BoundaryEvidence.RETENTION_WINDOW_TRUNCATED; hasEarlierObserverSession -> BoundaryEvidence.INFERRED_OBSERVER_SESSION_BOUNDARY; else -> BoundaryEvidence.OBSERVED_PLAYING })
                    builder?.let { it.add(row, conflicts.contains(row.eventId)); it.lastStateCode = STATE_PLAYING; it.lastStateTimeMs = row.eventTimeMs }
                } else if (builder == null && row.eventType == ObservationEventType.SESSION_CREATED && row.playbackStateCode == STATE_PLAYING) {
                    builder = Builder(scope, row, BoundaryEvidence.OBSERVED_PLAYING).also { it.add(row, conflicts.contains(row.eventId)); it.lastStateCode = STATE_PLAYING; it.lastStateTimeMs = row.eventTimeMs }
                }
                previous = row
            }
            builder?.let { b ->
                val endBoundary = when { retainedWindowMayBeTruncated -> BoundaryEvidence.RETENTION_WINDOW_TRUNCATED; hasLaterObserverSession -> BoundaryEvidence.INFERRED_OBSERVER_SESSION_BOUNDARY; else -> BoundaryEvidence.UNKNOWN }
                val status = when { retainedWindowMayBeTruncated -> EpisodeStatus.RETENTION_WINDOW_TRUNCATED; hasLaterObserverSession -> EpisodeStatus.CLOSED_BY_OBSERVER_BOUNDARY; else -> EpisodeStatus.OPEN_NO_TERMINAL_STATE }
                close(b, b.lastTime, endBoundary, status, timestampAmbiguity, result)
            }
        }
        return result.sortedWith(compareBy<ReconstructedPlaybackEpisode> { it.firstObservedAtMs }.thenBy { it.episodeId })
    }

    private fun close(b: Builder, at: Long, boundary: BoundaryEvidence, status: EpisodeStatus, timeAmbiguous: Boolean, output: MutableList<ReconstructedPlaybackEpisode>) {
        b.lastTime = maxOf(b.lastTime, at)
        output += ReconstructedPlaybackEpisode(
            episodeId = "episode:${b.scope.observerSessionId}:${b.firstId}", reconstructionVersion = RECONSTRUCTION_VERSION,
            sourcePackage = b.scope.sourcePackage, observerSessionId = b.scope.observerSessionId,
            firstObservedAtMs = b.firstTime, lastObservedAtMs = b.lastTime,
            estimatedActivePlaybackMs = if (b.activeMs > 0 || b.hasBoundedPlayingSpan) b.activeMs else null,
            startBoundary = b.start, endBoundary = boundary, status = status,
            rawObservationIds = b.ids.toList(), trackAppearances = b.tracks.map { it.toModel() },
            hasObservationGap = status == EpisodeStatus.CLOSED_BY_GAP || b.hasObservationGap, hasTimestampOrderingAmbiguity = timeAmbiguous || b.conflict,
        )
    }

    private data class Scope(val observerSessionId: String, val sourcePackage: String?)
    private class Builder(val scope: Scope, first: PlaybackObservation, val start: BoundaryEvidence) {
        val firstId = first.eventId
        val firstTime = first.eventTimeMs
        var lastTime = first.eventTimeMs
        var activeMs = 0L
        var hasBoundedPlayingSpan = false
        var hasObservationGap = false
        var lastStateCode: Int? = null
        var lastStateTimeMs: Long? = null
        var conflict = false
        val ids = linkedSetOf<String>()
        val tracks = mutableListOf<TrackBuilder>()
        fun add(row: PlaybackObservation, idConflict: Boolean) {
            ids += row.eventId; lastTime = maxOf(lastTime, row.eventTimeMs); conflict = conflict || idConflict
            if (hasMetadata(row)) {
                val key = trackKey(row)
                val current = tracks.lastOrNull()
                val sameByFallbackFingerprint = current != null && row.trackId.isNullOrBlank() && current.basis == TrackIdentityBasis.PROVIDER_TRACK_ID && current.matchesFingerprint(row)
                if (current == null || (key != null && current.key != null && key != current.key && !sameByFallbackFingerprint)) {
                    val basis = identity(row)
                    tracks += TrackBuilder(row, key, basis, if (basis == TrackIdentityBasis.INSUFFICIENT) TrackAppearanceEvidence.IDENTITY_INSUFFICIENT else if (current == null) TrackAppearanceEvidence.SNAPSHOT_ONLY else TrackAppearanceEvidence.CANDIDATE_CHANGE)
                } else if (key == null && current.key != null) {
                    tracks += TrackBuilder(row, key, TrackIdentityBasis.INSUFFICIENT, TrackAppearanceEvidence.IDENTITY_INSUFFICIENT)
                } else current.update(row, when { current.basis == TrackIdentityBasis.INSUFFICIENT -> TrackAppearanceEvidence.IDENTITY_INSUFFICIENT; sameByFallbackFingerprint -> TrackAppearanceEvidence.SAME_METADATA_FINGERPRINT; current.basis == TrackIdentityBasis.PROVIDER_TRACK_ID -> TrackAppearanceEvidence.SAME_PROVIDER_ID; else -> TrackAppearanceEvidence.SAME_METADATA_FINGERPRINT })
            }
        }
    }
    private class TrackBuilder(first: PlaybackObservation, val key: String?, val basis: TrackIdentityBasis, var evidence: TrackAppearanceEvidence) {
        val id = "track:${first.eventId}"; val source = first.sourcePackage; var trackId = first.trackId
        var title = first.trackTitle; var artist = first.trackArtist; var album = first.trackAlbum; var duration = first.durationMs
        val start = first.eventTimeMs; var end = first.eventTimeMs; val ids = linkedSetOf(first.eventId)
        fun matchesFingerprint(row: PlaybackObservation): Boolean {
            val t = title?.trim() ?: return false
            val a = artist?.trim() ?: return false
            val rowT = row.trackTitle?.trim() ?: return false
            val rowA = row.trackArtist?.trim() ?: return false
            if (!t.equals(rowT, ignoreCase = true) || !a.equals(rowA, ignoreCase = true)) return false
            val al = album?.trim()
            val rowAl = row.trackAlbum?.trim()
            if (al != null && rowAl != null && !al.equals(rowAl, ignoreCase = true)) return false
            return true
        }
        fun update(row: PlaybackObservation, status: TrackAppearanceEvidence) { ids += row.eventId; end = maxOf(end, row.eventTimeMs); trackId = trackId ?: row.trackId; title = row.trackTitle ?: title; artist = row.trackArtist ?: artist; album = row.trackAlbum ?: album; duration = duration ?: row.durationMs; evidence = status }
        fun toModel() = TrackAppearance(id, source, trackId, title, artist, album, duration, start, end, basis, evidence, ids.toList())
    }

    companion object {
        const val RECONSTRUCTION_VERSION = 1
        const val RETENTION_LIMIT = 500
        const val DEFAULT_MAX_ACTIVE_SPAN_MS = 120_000L
        const val DEFAULT_GAP_BOUNDARY_MS = 300_000L
        const val STATE_NONE = 0; const val STATE_STOPPED = 1; const val STATE_PAUSED = 2; const val STATE_PLAYING = 3
        private fun hasMetadata(o: PlaybackObservation) = o.trackId != null || o.trackTitle != null || o.trackArtist != null || o.trackAlbum != null || o.durationMs != null
        private fun identity(o: PlaybackObservation) = when { !o.trackId.isNullOrBlank() -> TrackIdentityBasis.PROVIDER_TRACK_ID; !o.trackTitle.isNullOrBlank() && !o.trackArtist.isNullOrBlank() -> TrackIdentityBasis.METADATA_FINGERPRINT; else -> TrackIdentityBasis.INSUFFICIENT }
        private fun trackKey(o: PlaybackObservation): String? = when {
            !o.trackId.isNullOrBlank() -> "id:${o.trackId}"
            !o.trackTitle.isNullOrBlank() && !o.trackArtist.isNullOrBlank() -> "meta:${o.trackTitle.trim().lowercase()}|${o.trackArtist.trim().lowercase()}|${o.trackAlbum?.trim()?.lowercase().orEmpty()}"
            else -> null
        }
        private fun canonical(o: PlaybackObservation) = listOf(o.schemaVersion,o.observerSessionId,o.eventType.name,o.eventTimeMs,o.sourcePackage,o.trackId,o.trackTitle,o.trackArtist,o.trackAlbum,o.durationMs,o.playbackStateCode,o.positionMs,o.playbackSpeed,o.positionUpdateElapsedRealtimeMs,o.savedAtMs).joinToString("\u0000")
    }
}
