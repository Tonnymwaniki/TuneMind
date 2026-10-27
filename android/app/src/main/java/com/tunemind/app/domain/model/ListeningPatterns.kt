package com.tunemind.app.domain.model

import java.time.LocalDate

/** On-demand aggregate over retained observations and existing reconstruction/memory output. */
data class ListeningPatternAnalysis(
    val coverage: PatternCoverage,
    val routineByDay: List<DailyListeningRoutine>,
    val routineByHour: List<HourlyListeningRoutine>,
    val trackPatterns: List<TrackRecurrencePattern>,
    val comparisonAvailability: PatternComparisonAvailability,
    val notices: List<PatternDataNotice>,
)

data class PatternCoverage(
    /** Min/max valid event capture timestamps in the supplied retained rows; not lifetime bounds. */
    val windowStartMs: Long?,
    val windowEndMs: Long?,
    val rawObservationCount: Int,
    val usableReconstructedEpisodeCount: Int,
    val usableReconstructedAppearanceCount: Int,
    val identifiableTrackIdentityCount: Int,
    val timeZoneId: String,
    val historyStatus: PatternHistoryStatus,
)

data class DailyListeningRoutine(
    val date: LocalDate,
    /** Count of distinct raw event IDs with valid event times on this local date. */
    val observedMediaSessionEventCount: Int,
    /** Distinct supported reconstructed episodes whose first observed time falls on this date. */
    val reconstructedEpisodeCount: Int,
    /** Existing bounded estimate, attributed only when all episode bounds fall on this date. */
    val estimatedActivePlaybackMs: Long?,
    val durationStatus: DailyDurationStatus,
    val episodeIds: List<String>,
)

data class HourlyListeningRoutine(
    /** Local hour in [0, 23]; repeated daylight-saving hours share one bucket. */
    val localHour: Int,
    val observedMediaSessionEventCount: Int,
)

data class TrackRecurrencePattern(
    val identityId: String,
    val sourcePackage: String?,
    val providerTrackId: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val identityBasis: TrackIdentityBasis,
    val evidenceQuality: MemoryEvidenceQuality,
    /** Distinct appearances eligible for recurrence analysis, never raw callback count. */
    val distinctAppearanceCount: Int,
    val firstObservedAppearanceAtMs: Long?,
    val lastObservedAppearanceAtMs: Long?,
    val recurrenceEligible: Boolean,
    val appearanceIds: List<String>,
    val supportingObservationIds: List<String>,
    val intervals: List<ObservedAppearanceGap>,
)

data class ObservedAppearanceGap(
    val fromAppearanceId: String,
    val toAppearanceId: String,
    /** Difference between first-observed appearance bounds, not complete-play intervals. */
    val gapMs: Long?,
    val status: AppearanceGapStatus,
)

data class PatternComparisonAvailability(
    val status: ComparisonStatus,
    val explanation: String,
)

enum class PatternHistoryStatus { NO_VALID_WINDOW, OBSERVED_WINDOW_ONLY, RETENTION_LIMIT_REACHED }
enum class DailyDurationStatus { NO_RECONSTRUCTED_EPISODE, AVAILABLE_BOUNDED_ESTIMATE, PARTIAL_ESTIMATE, UNAVAILABLE }
enum class AppearanceGapStatus { VALID_OBSERVED_GAP, IDENTICAL_TIMESTAMPS, INVALID_TIMESTAMP, OVERFLOW }
enum class ComparisonStatus { INSUFFICIENT_HISTORY, COVERAGE_UNKNOWN }
enum class PatternDataNotice {
    NO_RAW_OBSERVATIONS,
    NO_SUPPORTED_RECONSTRUCTED_EPISODES,
    NO_IDENTIFIED_TRACKS,
    HISTORY_COVERAGE_NOT_PROVEN,
    RETENTION_LIMIT_REACHED,
    INVALID_OBSERVATION_TIMESTAMP,
    MISSING_SOURCE_PACKAGE,
    AMBIGUOUS_RECONSTRUCTION,
    INSUFFICIENT_TRACK_IDENTITY,
    MISSING_TRACK_METADATA,
    RAW_EVIDENCE_UNAVAILABLE,
    INVALID_OR_UNBOUNDED_DURATION,
    CROSS_DAY_DURATION_NOT_APPORTIONED,
    OVERLAPPING_EPISODE_BOUNDS,
    UNORDERABLE_APPEARANCE_TIMES,
    COMPARISONS_UNAVAILABLE,
}
