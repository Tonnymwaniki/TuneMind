package com.tunemind.app.domain.signal

/**
 * Deterministic exponentially decaying recurrence signal.
 *
 * S_i(t) = Σ_j w_j × 2^(-(t - t_j) / h)
 *
 * Where:
 *   t   = evaluation timestamp (ms since epoch)
 *   t_j = timestamp of a distinct reconstructed appearance (ms since epoch)
 *   h   = configurable half-life (ms)
 *   w_j = evidence weight for that appearance (1.0 for v1)
 *
 * Each eligible distinct appearance contributes at most once.
 * Insufficient-identity appearances are excluded from per-track signals.
 * Duplicate appearance IDs are not double-counted.
 * Future-dated appearances (t_j > t) are excluded from the signal.
 * They are counted in futureAppearanceCount and flagged with a
 * FUTURE_APPEARANCES_PRESENT notice.
 */
class TemporalSignalEngine(
    /** Half-life in milliseconds. Default: 7 days. */
    val halfLifeMs: Long = DEFAULT_HALF_LIFE_MS,
) {
    init {
        require(halfLifeMs > 0) { "halfLifeMs must be positive: $halfLifeMs" }
    }

    /**
     * Evaluate the decayed recurrence signal for a single track.
     *
     * @param evaluationTimeMs evaluation timestamp (ms since epoch).
     * @param appearanceTimestampsMs distinct appearance timestamps (ms since epoch).
     *        Duplicates are deduplicated; only the first occurrence is used.
     * @return [TrackSignalResult] with the signal value and metadata.
     */
    fun evaluate(
        evaluationTimeMs: Long,
        appearanceTimestampsMs: List<Long>,
    ): TrackSignalResult {
        val deduped = appearanceTimestampsMs.distinct()
        val valid = deduped.filter { it > 0L }
        val excludedCount = deduped.size - valid.size

        val futureTimestamps = valid.filter { it > evaluationTimeMs }
        val eligible = valid.filter { it <= evaluationTimeMs }
        val futureCount = futureTimestamps.size

        var signal = 0.0
        for (ts in eligible) {
            val elapsed = evaluationTimeMs - ts
            val exponent = elapsed.toDouble() / halfLifeMs
            signal += Math.pow(2.0, -exponent)
        }

        val eligibleCount = eligible.size
        val latestEligibleMs = if (eligible.isNotEmpty()) eligible.max() else null

        val notices = mutableListOf<SignalNotice>()
        if (eligibleCount == 0) {
            notices.add(SignalNotice.NO_ELIGIBLE_APPEARANCES)
        }
        if (excludedCount > 0) {
            notices.add(SignalNotice.INVALID_TIMESTAMPS_EXCLUDED)
        }
        if (futureCount > 0) {
            notices.add(SignalNotice.FUTURE_APPEARANCES_PRESENT)
        }
        if (deduped.size != appearanceTimestampsMs.size) {
            notices.add(SignalNotice.DUPLICATE_APPEARANCE_IDS_REMOVED)
        }

        return TrackSignalResult(
            signal = signal,
            evaluationTimeMs = evaluationTimeMs,
            halfLifeMs = halfLifeMs,
            eligibleAppearanceCount = eligibleCount,
            futureAppearanceCount = futureCount,
            excludedInvalidTimestampCount = excludedCount,
            latestEligibleAppearanceMs = latestEligibleMs,
            notices = notices,
        )
    }

    companion object {
        /** Default half-life: 7 days in milliseconds. */
        const val DEFAULT_HALF_LIFE_MS: Long = 7 * 24 * 60 * 60 * 1000L
    }
}

/** Result of evaluating the temporal signal for one track. */
data class TrackSignalResult(
    /** The decayed recurrence signal value. */
    val signal: Double,
    /** Evaluation timestamp (ms since epoch). */
    val evaluationTimeMs: Long,
    /** Half-life used (ms). */
    val halfLifeMs: Long,
    /** Number of eligible (non-future, non-excluded) appearances contributing. */
    val eligibleAppearanceCount: Int,
    /** Number of future-dated appearances (excluded from signal). */
    val futureAppearanceCount: Int,
    /** Number of appearances excluded due to invalid (<= 0) timestamps. */
    val excludedInvalidTimestampCount: Int,
    /** Timestamp of the most recent eligible appearance, or null. */
    val latestEligibleAppearanceMs: Long?,
    /** Data-quality notices about the signal computation. */
    val notices: List<SignalNotice>,
)

/** Quality notices for temporal signal computation. */
enum class SignalNotice {
    NO_ELIGIBLE_APPEARANCES,
    INVALID_TIMESTAMPS_EXCLUDED,
    FUTURE_APPEARANCES_PRESENT,
    DUPLICATE_APPEARANCE_IDS_REMOVED,
}

/** Per-track signal result paired with its track identity for display. */
data class TrackSignalEntry(
    val entryId: String,
    val sourcePackage: String?,
    val providerTrackId: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val identityBasis: com.tunemind.app.domain.model.TrackIdentityBasis,
    val evidenceQuality: com.tunemind.app.domain.model.MemoryEvidenceQuality,
    val signal: TrackSignalResult,
)

/** Summary of temporal signal evaluation across all tracks. */
data class TemporalSignalSummary(
    val evaluationTimeMs: Long,
    val halfLifeMs: Long,
    val trackSignals: List<TrackSignalEntry>,
    val totalTracksEvaluated: Int,
    val totalTracksWithSufficientIdentity: Int,
    val notices: List<String>,
)

/** Evaluates temporal signals for all eligible tracks in a ListeningMemory. */
object TemporalSignalEvaluator {
    fun evaluate(
        memory: com.tunemind.app.domain.model.ListeningMemory,
        evaluationTimeMs: Long = System.currentTimeMillis(),
        halfLifeMs: Long = TemporalSignalEngine.DEFAULT_HALF_LIFE_MS,
    ): TemporalSignalSummary {
        val engine = TemporalSignalEngine(halfLifeMs)
        val notices = mutableListOf<String>()
        val trackSignals = mutableListOf<TrackSignalEntry>()

        for (track in memory.tracks) {
            val eligibleAppearances = track.appearances.filter { appearance ->
                appearance.firstObservedAtMs > 0L &&
                    track.identityBasis != com.tunemind.app.domain.model.TrackIdentityBasis.INSUFFICIENT
            }
            val timestamps = eligibleAppearances.map { it.firstObservedAtMs }
            val result = engine.evaluate(evaluationTimeMs, timestamps)
            trackSignals.add(
                TrackSignalEntry(
                    entryId = track.entryId,
                    sourcePackage = track.sourcePackage,
                    providerTrackId = track.providerTrackId,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    identityBasis = track.identityBasis,
                    evidenceQuality = track.evidenceQuality,
                    signal = result,
                )
            )
        }

        val withIdentity = trackSignals.filter {
            it.identityBasis != com.tunemind.app.domain.model.TrackIdentityBasis.INSUFFICIENT
        }
        if (trackSignals.isEmpty()) {
            notices.add("No tracks in memory to evaluate.")
        }
        if (withIdentity.isEmpty() && trackSignals.isNotEmpty()) {
            notices.add("No tracks have sufficient identity for signal evaluation.")
        }

        return TemporalSignalSummary(
            evaluationTimeMs = evaluationTimeMs,
            halfLifeMs = halfLifeMs,
            trackSignals = trackSignals,
            totalTracksEvaluated = trackSignals.size,
            totalTracksWithSufficientIdentity = withIdentity.size,
            notices = notices,
        )
    }
}
