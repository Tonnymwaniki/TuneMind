package com.tunemind.app.domain.model

data class ListeningMemory(
    val summary: ListeningMemorySummary,
    val tracks: List<TrackMemoryEntry>,
    val artists: List<ArtistMemorySummary>,
    val notices: List<MemoryDataQualityNotice>,
)

data class ListeningMemorySummary(
    val identifiableTrackCount: Int,
    val distinctReconstructedAppearanceCount: Int,
    val recurringTrackIdentityCount: Int,
    val identifiableArtistSummaryCount: Int,
    val unidentifiedAppearanceCount: Int,
    val historyMayBeIncomplete: Boolean,
)

data class TrackMemoryEntry(
    val entryId: String,
    val sourcePackage: String?,
    val providerTrackId: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val distinctAppearanceCount: Int,
    val firstObservedAppearanceAtMs: Long?,
    val lastObservedAppearanceAtMs: Long?,
    val supportingObservationIds: List<String>,
    val identityBasis: TrackIdentityBasis,
    val evidenceQuality: MemoryEvidenceQuality,
    val historyMayBeIncomplete: Boolean,
    val appearances: List<MemoryAppearanceEvidence>,
)

data class MemoryAppearanceEvidence(
    val reconstructedAppearanceId: String,
    val firstObservedAtMs: Long,
    val lastObservedAtMs: Long,
    val supportingObservationIds: List<String>,
    val evidenceStatus: TrackAppearanceEvidence,
    val hasTimestampOrderingAmbiguity: Boolean,
    val hasObservationGap: Boolean,
)

data class ArtistMemorySummary(
    val artistLabel: String,
    val distinctIdentifiedTrackCount: Int,
    val distinctReconstructedAppearanceCount: Int,
)

enum class MemoryEvidenceQuality { SUPPORTED_IDENTITY, INSUFFICIENT_IDENTITY, CONFLICTING_METADATA, AMBIGUOUS_RECONSTRUCTION, RAW_EVIDENCE_UNAVAILABLE }
enum class MemoryDataQualityNotice { NO_RAW_OBSERVATIONS, NO_RECONSTRUCTED_APPEARANCES, CALLBACK_HISTORY_LIMITED, RETENTION_LIMIT_REACHED, MISSING_SOURCE_PACKAGE, MISSING_TRACK_METADATA, MISSING_ARTIST_METADATA, INSUFFICIENT_TRACK_IDENTITY, CONFLICTING_METADATA, AMBIGUOUS_RECONSTRUCTION, RAW_EVIDENCE_UNAVAILABLE }
