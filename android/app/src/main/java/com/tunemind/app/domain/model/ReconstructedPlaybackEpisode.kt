package com.tunemind.app.domain.model

/** A derived, tentative interval built only from retained raw observations. */
data class ReconstructedPlaybackEpisode(
    val episodeId: String,
    val reconstructionVersion: Int,
    val sourcePackage: String?,
    val observerSessionId: String,
    val firstObservedAtMs: Long,
    val lastObservedAtMs: Long,
    val estimatedActivePlaybackMs: Long?,
    val startBoundary: BoundaryEvidence,
    val endBoundary: BoundaryEvidence,
    val status: EpisodeStatus,
    val rawObservationIds: List<String>,
    val trackAppearances: List<TrackAppearance>,
    val hasObservationGap: Boolean,
    val hasTimestampOrderingAmbiguity: Boolean,
)

data class TrackAppearance(
    val appearanceId: String,
    val sourcePackage: String?,
    val trackId: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val firstObservedAtMs: Long,
    val lastObservedAtMs: Long,
    val identityBasis: TrackIdentityBasis,
    val evidenceStatus: TrackAppearanceEvidence,
    val rawObservationIds: List<String>,
)

enum class BoundaryEvidence { OBSERVED_PLAYING, OBSERVED_STOP, OBSERVED_NON_PLAYING_STATE, OBSERVED_SESSION_DESTROYED, INFERRED_OBSERVER_SESSION_BOUNDARY, GAP_CENSORED, RETENTION_WINDOW_TRUNCATED, UNKNOWN }
enum class EpisodeStatus { OPEN_NO_TERMINAL_STATE, ENDED_BY_OBSERVED_STOP, ENDED_BY_OBSERVED_NON_PLAYING_STATE, ENDED_BY_SESSION_DESTRUCTION, CLOSED_BY_OBSERVER_BOUNDARY, CLOSED_BY_GAP, RETENTION_WINDOW_TRUNCATED }
enum class TrackIdentityBasis { PROVIDER_TRACK_ID, METADATA_FINGERPRINT, INSUFFICIENT }
enum class TrackAppearanceEvidence { SNAPSHOT_ONLY, SAME_PROVIDER_ID, SAME_METADATA_FINGERPRINT, CANDIDATE_CHANGE, IDENTITY_INSUFFICIENT }
