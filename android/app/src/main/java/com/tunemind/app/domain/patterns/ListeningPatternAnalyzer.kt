package com.tunemind.app.domain.patterns

import com.tunemind.app.domain.model.AppearanceGapStatus
import com.tunemind.app.domain.model.ComparisonStatus
import com.tunemind.app.domain.model.DailyDurationStatus
import com.tunemind.app.domain.model.DailyListeningRoutine
import com.tunemind.app.domain.model.EpisodeStatus
import com.tunemind.app.domain.model.HourlyListeningRoutine
import com.tunemind.app.domain.model.ListeningMemory
import com.tunemind.app.domain.model.ListeningPatternAnalysis
import com.tunemind.app.domain.model.MemoryEvidenceQuality
import com.tunemind.app.domain.model.PatternComparisonAvailability
import com.tunemind.app.domain.model.PatternCoverage
import com.tunemind.app.domain.model.PatternDataNotice
import com.tunemind.app.domain.model.PatternHistoryStatus
import com.tunemind.app.domain.model.PlaybackObservation
import com.tunemind.app.domain.model.ReconstructedPlaybackEpisode
import com.tunemind.app.domain.model.TrackAppearance
import com.tunemind.app.domain.model.TrackIdentityBasis
import com.tunemind.app.domain.model.TrackRecurrencePattern
import com.tunemind.app.domain.model.ObservedAppearanceGap
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Deterministic, on-demand analysis over retained raw rows and the existing derived layers.
 * Calendar buckets use [zoneId] for every conversion. Repeated local hours at DST fall-back
 * intentionally share a bucket; skipped hours at spring-forward simply have no observations.
 */
class ListeningPatternAnalyzer {
    fun analyze(
        observations: List<PlaybackObservation>,
        episodes: List<ReconstructedPlaybackEpisode>,
        memory: ListeningMemory,
        zoneId: ZoneId,
        retainedHistoryMayBeTruncated: Boolean = observations.size >= RETENTION_LIMIT,
    ): ListeningPatternAnalysis {
        val notices = linkedSetOf<PatternDataNotice>()
        if (observations.isEmpty()) notices += PatternDataNotice.NO_RAW_OBSERVATIONS
        notices += PatternDataNotice.HISTORY_COVERAGE_NOT_PROVEN

        val rawGroups = observations.groupBy { it.eventId }
        val rawById = rawGroups.mapValues { (_, rows) -> rows.minBy(::rawCanonical) }
        if (rawGroups.values.any { rows -> rows.map(::rawCanonical).distinct().size > 1 }) {
            notices += PatternDataNotice.AMBIGUOUS_RECONSTRUCTION
        }
        val raw = rawById.values.sortedWith(compareBy<PlaybackObservation> { it.eventTimeMs }.thenBy { it.eventId })
        val validRaw = raw.mapNotNull { observation ->
            localDateTime(observation.eventTimeMs, zoneId).also {
                if (it == null) notices += PatternDataNotice.INVALID_OBSERVATION_TIMESTAMP
            }?.let { observation to it }
        }

        val rawEventCountsByDate = validRaw.groupingBy { it.second.toLocalDate() }.eachCount()
        val rawEventCountsByHour = validRaw.groupingBy { it.second.hour }.eachCount()
        val validTimes = validRaw.map { it.first.eventTimeMs }

        val supportedRawIds = rawById.keys
        val episodeGroups = episodes.groupBy { it.episodeId }
        if (episodeGroups.values.any { rows -> rows.map(::episodeCanonical).distinct().size > 1 }) {
            notices += PatternDataNotice.AMBIGUOUS_RECONSTRUCTION
        }
        val uniqueEpisodes = episodeGroups.values.map { sameId -> sameId.minBy(::episodeCanonical) }
            .sortedWith(compareBy<ReconstructedPlaybackEpisode> { it.firstObservedAtMs }.thenBy { it.episodeId })
        val usableEpisodes = uniqueEpisodes.filter { episode ->
            val hasRawSupport = episode.rawObservationIds.any(supportedRawIds::contains)
            if (!hasRawSupport) notices += PatternDataNotice.RAW_EVIDENCE_UNAVAILABLE
            val validBounds = episode.firstObservedAtMs >= 0L && episode.lastObservedAtMs >= episode.firstObservedAtMs &&
                localDateTime(episode.firstObservedAtMs, zoneId) != null
            if (!validBounds) notices += PatternDataNotice.INVALID_OBSERVATION_TIMESTAMP
            if (episode.hasObservationGap || episode.hasTimestampOrderingAmbiguity || episode.status !in TERMINAL_OR_BOUNDED_STATUSES) {
                notices += PatternDataNotice.AMBIGUOUS_RECONSTRUCTION
            }
            hasRawSupport && validBounds
        }

        val appearanceGroups = usableEpisodes.flatMap { it.trackAppearances }
            .filter { appearance ->
                val hasRawSupport = appearance.rawObservationIds.any(supportedRawIds::contains)
                if (!hasRawSupport) notices += PatternDataNotice.RAW_EVIDENCE_UNAVAILABLE
                hasRawSupport
            }
            .groupBy { it.appearanceId }
        if (appearanceGroups.values.any { rows -> rows.map(::appearanceCanonical).distinct().size > 1 }) {
            notices += PatternDataNotice.AMBIGUOUS_RECONSTRUCTION
        }
        val appearanceById = appearanceGroups.mapValues { (_, sameId) -> sameId.minBy(::appearanceCanonical) }
        if (appearanceById.values.any { it.identityBasis == TrackIdentityBasis.INSUFFICIENT }) {
            notices += PatternDataNotice.INSUFFICIENT_TRACK_IDENTITY
        }
        if (appearanceById.values.any { it.title.isNullOrBlank() || it.artist.isNullOrBlank() || it.album.isNullOrBlank() }) {
            notices += PatternDataNotice.MISSING_TRACK_METADATA
        }

        val trackPatterns = buildTrackPatterns(memory, notices)
        if (trackPatterns.none { it.identityBasis != TrackIdentityBasis.INSUFFICIENT }) {
            notices += PatternDataNotice.NO_IDENTIFIED_TRACKS
        }
        if (trackPatterns.any { it.sourcePackage.isNullOrBlank() }) notices += PatternDataNotice.MISSING_SOURCE_PACKAGE
        if (trackPatterns.any { it.evidenceQuality == MemoryEvidenceQuality.RAW_EVIDENCE_UNAVAILABLE }) {
            notices += PatternDataNotice.RAW_EVIDENCE_UNAVAILABLE
        }
        if (trackPatterns.any { it.evidenceQuality == MemoryEvidenceQuality.AMBIGUOUS_RECONSTRUCTION || it.evidenceQuality == MemoryEvidenceQuality.CONFLICTING_METADATA }) {
            notices += PatternDataNotice.AMBIGUOUS_RECONSTRUCTION
        }

        val daily = buildDailyRoutines(rawEventCountsByDate, usableEpisodes, zoneId, notices)
        val hourly = rawEventCountsByHour.toSortedMap().map { (hour, count) -> HourlyListeningRoutine(hour, count) }
        if (usableEpisodes.isEmpty()) notices += PatternDataNotice.NO_SUPPORTED_RECONSTRUCTED_EPISODES

        val retainedLimitReached = retainedHistoryMayBeTruncated || raw.size >= RETENTION_LIMIT
        if (retainedLimitReached) notices += PatternDataNotice.RETENTION_LIMIT_REACHED
        val comparisonStatus = if (validTimes.mapNotNull { localDateTime(it, zoneId)?.toLocalDate() }.distinct().size < MIN_COMPARISON_DAYS) {
            ComparisonStatus.INSUFFICIENT_HISTORY
        } else {
            // Capture coverage is callback-based and has no denominator; comparable periods cannot
            // be established honestly from the current models, even when many dates are present.
            ComparisonStatus.COVERAGE_UNKNOWN
        }
        notices += PatternDataNotice.COMPARISONS_UNAVAILABLE

        val historyStatus = when {
            validTimes.isEmpty() -> PatternHistoryStatus.NO_VALID_WINDOW
            retainedLimitReached -> PatternHistoryStatus.RETENTION_LIMIT_REACHED
            else -> PatternHistoryStatus.OBSERVED_WINDOW_ONLY
        }
        val comparisonExplanation = when (comparisonStatus) {
            ComparisonStatus.INSUFFICIENT_HISTORY -> "Not enough distinct observed local dates to form two periods."
            ComparisonStatus.COVERAGE_UNKNOWN -> "Unavailable: callback coverage is unknown, so period differences could reflect missing observations."
        }

        return ListeningPatternAnalysis(
            coverage = PatternCoverage(
                windowStartMs = validTimes.minOrNull(),
                windowEndMs = validTimes.maxOrNull(),
                rawObservationCount = raw.size,
                usableReconstructedEpisodeCount = usableEpisodes.size,
                usableReconstructedAppearanceCount = appearanceById.size,
                identifiableTrackIdentityCount = trackPatterns.count { it.identityBasis != TrackIdentityBasis.INSUFFICIENT },
                timeZoneId = zoneId.id,
                historyStatus = historyStatus,
            ),
            routineByDay = daily,
            routineByHour = hourly,
            trackPatterns = trackPatterns,
            comparisonAvailability = PatternComparisonAvailability(comparisonStatus, comparisonExplanation),
            notices = notices.toList().sortedBy { it.name },
        )
    }

    private fun buildDailyRoutines(
        rawCounts: Map<LocalDate, Int>,
        episodes: List<ReconstructedPlaybackEpisode>,
        zoneId: ZoneId,
        notices: MutableSet<PatternDataNotice>,
    ): List<DailyListeningRoutine> {
        val episodesByDate = episodes
            .mapNotNull { ep -> localDateTime(ep.firstObservedAtMs, zoneId)?.toLocalDate()?.let { date -> date to ep } }
            .groupBy({ it.first }, { it.second })
        return (rawCounts.keys + episodesByDate.keys).distinct().sorted().map { date ->
            val dayEpisodes = episodesByDate[date].orEmpty().sortedBy { it.episodeId }
            var durationStatus = DailyDurationStatus.NO_RECONSTRUCTED_EPISODE
            var duration: Long? = null
            if (dayEpisodes.isNotEmpty()) {
                val allSameDayAndOrdered = dayEpisodes.all { episode ->
                    val first = localDateTime(episode.firstObservedAtMs, zoneId)?.toLocalDate()
                    val last = localDateTime(episode.lastObservedAtMs, zoneId)?.toLocalDate()
                    first == date && last == date && episode.lastObservedAtMs >= episode.firstObservedAtMs
                }
                val estimates = dayEpisodes.mapNotNull { episode ->
                    episode.estimatedActivePlaybackMs?.takeIf { it >= 0L && !episode.hasTimestampOrderingAmbiguity }
                        ?.let { episode to it }
                }
                if (dayEpisodes.any { it.estimatedActivePlaybackMs == null || it.estimatedActivePlaybackMs < 0L || it.hasTimestampOrderingAmbiguity }) {
                    notices += PatternDataNotice.INVALID_OR_UNBOUNDED_DURATION
                }
                if (!allSameDayAndOrdered) notices += PatternDataNotice.CROSS_DAY_DURATION_NOT_APPORTIONED

                val overlap = allSameDayAndOrdered && hasOverlappingEpisodeBounds(dayEpisodes)
                if (overlap) notices += PatternDataNotice.OVERLAPPING_EPISODE_BOUNDS
                if (overlap) {
                    durationStatus = DailyDurationStatus.UNAVAILABLE
                } else if (allSameDayAndOrdered && estimates.isNotEmpty()) {
                    val total = runCatching { estimates.fold(0L) { sum, (_, value) -> Math.addExact(sum, value) } }.getOrNull()
                    if (total == null) {
                        notices += PatternDataNotice.INVALID_OR_UNBOUNDED_DURATION
                        durationStatus = DailyDurationStatus.UNAVAILABLE
                    } else {
                        duration = total
                        val fullyBoundedEpisodes = estimates.size == dayEpisodes.size && dayEpisodes.all {
                            !it.hasObservationGap && !it.hasTimestampOrderingAmbiguity &&
                                it.startBoundary == com.tunemind.app.domain.model.BoundaryEvidence.OBSERVED_PLAYING &&
                                it.endBoundary in setOf(
                                    com.tunemind.app.domain.model.BoundaryEvidence.OBSERVED_STOP,
                                    com.tunemind.app.domain.model.BoundaryEvidence.OBSERVED_NON_PLAYING_STATE,
                                )
                        }
                        durationStatus = if (fullyBoundedEpisodes) {
                            DailyDurationStatus.AVAILABLE_BOUNDED_ESTIMATE
                        } else {
                            DailyDurationStatus.PARTIAL_ESTIMATE
                        }
                    }
                } else {
                    durationStatus = DailyDurationStatus.UNAVAILABLE
                }
            }
            DailyListeningRoutine(
                date = date,
                observedMediaSessionEventCount = rawCounts[date] ?: 0,
                reconstructedEpisodeCount = dayEpisodes.size,
                estimatedActivePlaybackMs = duration,
                durationStatus = durationStatus,
                episodeIds = dayEpisodes.map { it.episodeId },
            )
        }
    }

    private fun hasOverlappingEpisodeBounds(episodes: List<ReconstructedPlaybackEpisode>): Boolean {
        val sorted = episodes.sortedWith(compareBy<ReconstructedPlaybackEpisode> { it.firstObservedAtMs }.thenBy { it.episodeId })
        var furthestObservedEnd = Long.MIN_VALUE
        for (episode in sorted) {
            if (episode.firstObservedAtMs < furthestObservedEnd) return true
            furthestObservedEnd = maxOf(furthestObservedEnd, episode.lastObservedAtMs)
        }
        return false
    }

    private fun buildTrackPatterns(
        memory: ListeningMemory,
        notices: MutableSet<PatternDataNotice>,
    ): List<TrackRecurrencePattern> = memory.tracks.sortedBy { it.entryId }.map { track ->
        val appearanceGroups = track.appearances.groupBy { it.reconstructedAppearanceId }
        val conflictingAppearanceEvidence = appearanceGroups.values.any { rows ->
            rows.map(::memoryAppearanceCanonical).distinct().size > 1
        }
        if (conflictingAppearanceEvidence) notices += PatternDataNotice.AMBIGUOUS_RECONSTRUCTION
        val appearances = appearanceGroups.values.map { sameId -> sameId.minBy(::memoryAppearanceCanonical) }
            .sortedWith(compareBy({ it.firstObservedAtMs }, { it.reconstructedAppearanceId }))
        val allAppearanceTimesValid = appearances.all { appearance ->
            val valid = appearance.firstObservedAtMs >= 0L && appearance.lastObservedAtMs >= appearance.firstObservedAtMs
            if (!valid) notices += PatternDataNotice.UNORDERABLE_APPEARANCE_TIMES
            valid
        }
        val identityIsKnown = track.identityBasis != TrackIdentityBasis.INSUFFICIENT
        val recurrenceEligible = identityIsKnown && track.evidenceQuality == MemoryEvidenceQuality.SUPPORTED_IDENTITY &&
            appearances.isNotEmpty() && allAppearanceTimesValid && !conflictingAppearanceEvidence && appearances.all {
                !it.hasTimestampOrderingAmbiguity && !it.hasObservationGap &&
                    it.evidenceStatus in SUPPORTED_APPEARANCE_EVIDENCE
            }
        if (identityIsKnown && !recurrenceEligible) notices += PatternDataNotice.AMBIGUOUS_RECONSTRUCTION
        val orderedValid = appearances.mapNotNull { appearance ->
            if (appearance.firstObservedAtMs < 0L || appearance.lastObservedAtMs < appearance.firstObservedAtMs) {
                notices += PatternDataNotice.UNORDERABLE_APPEARANCE_TIMES
                null
            } else appearance
        }
        val intervals = if (recurrenceEligible) {
            orderedValid.sortedWith(compareBy({ it.firstObservedAtMs }, { it.reconstructedAppearanceId }))
                .zipWithNext()
                .map { (from, to) -> observedGap(from, to, notices) }
        } else emptyList()
        TrackRecurrencePattern(
            identityId = track.entryId,
            sourcePackage = track.sourcePackage,
            providerTrackId = track.providerTrackId,
            title = track.title,
            artist = track.artist,
            album = track.album,
            identityBasis = track.identityBasis,
            evidenceQuality = track.evidenceQuality,
            distinctAppearanceCount = appearances.size,
            firstObservedAppearanceAtMs = orderedValid.minOfOrNull { it.firstObservedAtMs },
            lastObservedAppearanceAtMs = orderedValid.maxOfOrNull { it.lastObservedAtMs },
            recurrenceEligible = recurrenceEligible,
            appearanceIds = appearances.map { it.reconstructedAppearanceId },
            supportingObservationIds = appearances.flatMap { it.supportingObservationIds }.distinct().sorted(),
            intervals = intervals,
        )
    }.also { patterns ->
        if (patterns.any { it.identityBasis == TrackIdentityBasis.INSUFFICIENT }) notices += PatternDataNotice.INSUFFICIENT_TRACK_IDENTITY
        if (patterns.any { it.title.isNullOrBlank() || it.artist.isNullOrBlank() || it.album.isNullOrBlank() }) notices += PatternDataNotice.MISSING_TRACK_METADATA
    }

    private fun observedGap(
        from: com.tunemind.app.domain.model.MemoryAppearanceEvidence,
        to: com.tunemind.app.domain.model.MemoryAppearanceEvidence,
        notices: MutableSet<PatternDataNotice>,
    ): ObservedAppearanceGap {
        val gap = try {
            Math.subtractExact(to.firstObservedAtMs, from.firstObservedAtMs)
        } catch (_: ArithmeticException) {
            notices += PatternDataNotice.UNORDERABLE_APPEARANCE_TIMES
            null
        }
        val status = when {
            gap == null -> AppearanceGapStatus.OVERFLOW
            gap <= 0L -> {
                notices += PatternDataNotice.UNORDERABLE_APPEARANCE_TIMES
                if (gap == 0L) AppearanceGapStatus.IDENTICAL_TIMESTAMPS else AppearanceGapStatus.INVALID_TIMESTAMP
            }
            else -> AppearanceGapStatus.VALID_OBSERVED_GAP
        }
        return ObservedAppearanceGap(from.reconstructedAppearanceId, to.reconstructedAppearanceId, gap?.takeIf { it > 0L }, status)
    }

    private fun localDateTime(timestampMs: Long, zoneId: ZoneId) = runCatching {
        if (timestampMs < 0L) return@runCatching null
        Instant.ofEpochMilli(timestampMs).atZone(zoneId)
    }.getOrNull()

    private fun rawCanonical(o: PlaybackObservation) = listOf(
        o.schemaVersion, o.observerSessionId, o.eventType.name, o.eventTimeMs, o.sourcePackage, o.trackId,
        o.trackTitle, o.trackArtist, o.trackAlbum, o.durationMs, o.playbackStateCode, o.positionMs,
        o.playbackSpeed, o.positionUpdateElapsedRealtimeMs, o.savedAtMs,
    ).joinToString("\u0000")

    private fun episodeCanonical(e: ReconstructedPlaybackEpisode) = listOf(
        e.reconstructionVersion, e.sourcePackage, e.observerSessionId, e.firstObservedAtMs, e.lastObservedAtMs,
        e.estimatedActivePlaybackMs, e.startBoundary.name, e.endBoundary.name, e.status.name,
        e.rawObservationIds.sorted().joinToString(","), e.trackAppearances.map(::appearanceCanonical).sorted().joinToString(";"),
        e.hasObservationGap, e.hasTimestampOrderingAmbiguity,
    ).joinToString("\u0000")

    private fun appearanceCanonical(a: TrackAppearance) = listOf(
        a.sourcePackage, a.trackId, a.title, a.artist, a.album, a.durationMs, a.firstObservedAtMs,
        a.lastObservedAtMs, a.identityBasis.name, a.evidenceStatus.name, a.rawObservationIds.sorted().joinToString(","),
    ).joinToString("\u0000")

    private fun memoryAppearanceCanonical(a: com.tunemind.app.domain.model.MemoryAppearanceEvidence) = listOf(
        a.firstObservedAtMs, a.lastObservedAtMs, a.supportingObservationIds.sorted().joinToString(","),
        a.evidenceStatus.name, a.hasTimestampOrderingAmbiguity, a.hasObservationGap,
    ).joinToString("\u0000")

    companion object {
        const val RETENTION_LIMIT = 500
        private const val MIN_COMPARISON_DAYS = 2
        private val TERMINAL_OR_BOUNDED_STATUSES = setOf(
            EpisodeStatus.ENDED_BY_OBSERVED_STOP,
            EpisodeStatus.ENDED_BY_OBSERVED_NON_PLAYING_STATE,
            EpisodeStatus.ENDED_BY_SESSION_DESTRUCTION,
        )
        private val SUPPORTED_APPEARANCE_EVIDENCE = setOf(
            com.tunemind.app.domain.model.TrackAppearanceEvidence.SNAPSHOT_ONLY,
            com.tunemind.app.domain.model.TrackAppearanceEvidence.SAME_PROVIDER_ID,
            com.tunemind.app.domain.model.TrackAppearanceEvidence.SAME_METADATA_FINGERPRINT,
        )
    }
}
