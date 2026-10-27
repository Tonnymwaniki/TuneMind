package com.tunemind.app.domain.snapshot

import com.tunemind.app.domain.model.*
import com.tunemind.app.domain.signal.*
import com.tunemind.app.domain.research.*
import com.tunemind.app.domain.coverage.*

/**
 * Unified listening-intelligence snapshot — composes outputs from all existing
 * engines into one immutable result.
 *
 * All derived values are traceable to source observations or reconstructed
 * appearances. No system-clock calls in the core domain layer.
 */

// ─── Per-track snapshot ──────────────────────────────────────────────────────

data class TrackSnapshot(
    val entryId: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val sourcePackage: String?,
    val identityBasis: TrackIdentityBasis,
    val evidenceQuality: MemoryEvidenceQuality,
    val distinctAppearanceCount: Int,
    val firstObservedMs: Long?,
    val lastObservedMs: Long?,
    val recurrenceSignal: Double,
    val eligibleAppearanceCount: Int,
    val futureAppearanceCount: Int,
    val temporalDirection: TemporalDirection,
    val signalDelta: Double,
    val changeRatePerDay: Double?,
    val hasReappearanceAfterGap: Boolean,
    val reappearanceGapMs: Long?,
    val concentrationShare: Double?,
    val notices: List<String>,
)

// ─── Unified snapshot ────────────────────────────────────────────────────────

data class ListeningIntelligenceSnapshot(
    val evaluationTimeMs: Long,
    val halfLifeMs: Long,
    val comparisonWindowMs: Long,
    val reappearanceMinGapMs: Long,
    val accelerationWindowDurationMs: Long,
    val concentrationWindowDurationMs: Long,
    val totalTracksInMemory: Int,
    val identifiedTrackCount: Int,
    val tracks: List<TrackSnapshot>,
    val recurrenceAccelerationAbsoluteChange: Int,
    val recurrenceAccelerationEarlierCount: Int,
    val recurrenceAccelerationLaterCount: Int,
    val reappearanceAfterGapCount: Int,
    val concentrationIdentifiedAppearanceCount: Int,
    val concentrationDistinctTrackCount: Int,
    val normalizedConcentration: Double?,
    val evidenceQuality: EvidenceQualityResult,
    val historyMayBeIncomplete: Boolean,
    val coverage: CoverageAssessment,
    val notices: List<String>,
)

// ─── Builder ─────────────────────────────────────────────────────────────────

object ListeningIntelligenceBuilder {

    /** Default configuration. */
    const val DEFAULT_HALF_LIFE_MS: Long = TemporalSignalEngine.DEFAULT_HALF_LIFE_MS
    const val DEFAULT_COMPARISON_WINDOW_MS: Long = TemporalDynamicsEngine.DEFAULT_COMPARISON_WINDOW_MS
    const val DEFAULT_REAPPEARANCE_MIN_GAP_MS: Long = 7 * 24 * 60 * 60 * 1000L
    const val DEFAULT_ACCELERATION_WINDOW_MS: Long = 7 * 24 * 60 * 60 * 1000L
    const val DEFAULT_CONCENTRATION_WINDOW_MS: Long = 7 * 24 * 60 * 60 * 1000L

    fun build(
        memory: ListeningMemory,
        evaluationTimeMs: Long,
        halfLifeMs: Long = DEFAULT_HALF_LIFE_MS,
        comparisonWindowMs: Long = DEFAULT_COMPARISON_WINDOW_MS,
        reappearanceMinGapMs: Long = DEFAULT_REAPPEARANCE_MIN_GAP_MS,
        accelerationWindowDurationMs: Long = DEFAULT_ACCELERATION_WINDOW_MS,
        concentrationWindowDurationMs: Long = DEFAULT_CONCENTRATION_WINDOW_MS,
        /** All valid event timestamps for coverage assessment. */
        allEventTimestampsMs: List<Long> = emptyList(),
        /** Whether OBSERVATION_STOPPED events exist in the record. */
        hasObservationStopEvents: Boolean = false,
        /** Number of distinct observer session IDs. */
        distinctSessionCount: Int = 0,
        /** Number of events with invalid (<= 0) timestamps. */
        invalidEventTimestampCount: Int = 0,
        /** Known observation boundaries. */
        observationBoundaries: List<ObservationBoundary> = emptyList(),
    ): ListeningIntelligenceSnapshot {
        val signalEngine = TemporalSignalEngine(halfLifeMs)
        val dynamicsEngine = TemporalDynamicsEngine(comparisonWindowMs)
        val notices = mutableListOf<String>()

        // Per-track evaluation
        val trackSnapshots = memory.tracks.map { track ->
            val validAppearances = track.appearances.filter { it.firstObservedAtMs > 0L }
            val timestamps = validAppearances.map { it.firstObservedAtMs }

            // Recurrence signal
            val signalResult = signalEngine.evaluate(evaluationTimeMs, timestamps)

            // Temporal dynamics
            val dynamicsResult = dynamicsEngine.evaluate(evaluationTimeMs, timestamps)

            // Reappearance after gap
            val reappearance = detectReappearanceAfterGap(
                entryId = track.entryId,
                title = track.title,
                artist = track.artist,
                appearanceTimestampsMs = timestamps,
                minGapMs = reappearanceMinGapMs,
            )

            // Concentration share (computed later from the full memory)
            TrackSnapshot(
                entryId = track.entryId,
                title = track.title,
                artist = track.artist,
                album = track.album,
                sourcePackage = track.sourcePackage,
                identityBasis = track.identityBasis,
                evidenceQuality = track.evidenceQuality,
                distinctAppearanceCount = track.distinctAppearanceCount,
                firstObservedMs = track.firstObservedAppearanceAtMs,
                lastObservedMs = track.lastObservedAppearanceAtMs,
                recurrenceSignal = signalResult.signal,
                eligibleAppearanceCount = signalResult.eligibleAppearanceCount,
                futureAppearanceCount = signalResult.futureAppearanceCount,
                temporalDirection = dynamicsResult.direction,
                signalDelta = dynamicsResult.signalDelta,
                changeRatePerDay = dynamicsResult.changeRatePerDay,
                hasReappearanceAfterGap = reappearance.hasReappearance,
                reappearanceGapMs = if (reappearance.hasReappearance) reappearance.gapMs else null,
                concentrationShare = null, // filled below
                notices = dynamicsResult.notices,
            )
        }

        // Recurrence acceleration across all identified tracks
        val allTimestamps = memory.tracks
            .filter { it.identityBasis != TrackIdentityBasis.INSUFFICIENT }
            .flatMap { track -> track.appearances.filter { it.firstObservedAtMs > 0L }.map { it.firstObservedAtMs } }
        val acceleration = computeRecurrenceAcceleration(allTimestamps, evaluationTimeMs, accelerationWindowDurationMs)

        // Reappearance count
        val reappearanceCount = trackSnapshots.count { it.hasReappearanceAfterGap }

        // Concentration
        val concentration = computeConcentration(memory, evaluationTimeMs, concentrationWindowDurationMs)

        // Fill per-track concentration shares
        val shareById = concentration.tracks.associate { it.entryId to it.share }
        val trackSnapshotsWithShare = trackSnapshots.map { it.copy(concentrationShare = shareById[it.entryId]) }

        // Evidence quality
        val evidenceQuality = assessEvidenceQuality(memory)

        val identifiedCount = trackSnapshotsWithShare.count { it.identityBasis != TrackIdentityBasis.INSUFFICIENT }
        if (identifiedCount == 0 && trackSnapshotsWithShare.isNotEmpty()) {
            notices.add("No tracks have sufficient identity for signal evaluation.")
        }

        // Coverage assessment
        val coverage = CoverageAssessor.assessFromMemory(
            memory = memory,
            eventTimestampsMs = allEventTimestampsMs,
            hasObservationStop = hasObservationStopEvents,
            distinctSessionIdsCount = distinctSessionCount,
            invalidTimestampCount = invalidEventTimestampCount,
            boundaries = observationBoundaries,
        )

        return ListeningIntelligenceSnapshot(
            evaluationTimeMs = evaluationTimeMs,
            halfLifeMs = halfLifeMs,
            comparisonWindowMs = comparisonWindowMs,
            reappearanceMinGapMs = reappearanceMinGapMs,
            accelerationWindowDurationMs = accelerationWindowDurationMs,
            concentrationWindowDurationMs = concentrationWindowDurationMs,
            totalTracksInMemory = memory.tracks.size,
            identifiedTrackCount = identifiedCount,
            tracks = trackSnapshotsWithShare,
            recurrenceAccelerationAbsoluteChange = acceleration.absoluteChange,
            recurrenceAccelerationEarlierCount = acceleration.earlierWindow.distinctAppearanceCount,
            recurrenceAccelerationLaterCount = acceleration.laterWindow.distinctAppearanceCount,
            reappearanceAfterGapCount = reappearanceCount,
            concentrationIdentifiedAppearanceCount = concentration.totalIdentifiedAppearanceCount,
            concentrationDistinctTrackCount = concentration.distinctIdentifiedTrackCount,
            normalizedConcentration = concentration.normalizedConcentration,
            evidenceQuality = evidenceQuality,
            historyMayBeIncomplete = evidenceQuality.historyMayBeIncomplete,
            coverage = coverage,
            notices = notices,
        )
    }
}
