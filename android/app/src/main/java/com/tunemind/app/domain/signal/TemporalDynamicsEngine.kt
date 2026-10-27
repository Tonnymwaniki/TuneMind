package com.tunemind.app.domain.signal

/**
 * Temporal direction of the recurrence signal between two evaluation times.
 */
enum class TemporalDirection {
    INCREASING,
    DECREASING,
    STABLE,
    INSUFFICIENT_DATA,
}

/**
 * Result of comparing the recurrence signal at two evaluation times for one track.
 */
data class TrackDynamicsResult(
    val entryId: String,
    val sourcePackage: String?,
    val providerTrackId: String?,
    val title: String?,
    val artist: String?,
    val currentSignal: Double,
    val previousSignal: Double,
    val signalDelta: Double,
    val changeRatePerDay: Double?,
    val direction: TemporalDirection,
    val currentEligibleCount: Int,
    val previousEligibleCount: Int,
    val comparisonWindowMs: Long,
    val halfLifeMs: Long,
    val notices: List<String>,
)

/**
 * Summary of temporal dynamics across all tracks in a ListeningMemory.
 */
data class TemporalDynamicsSummary(
    val evaluationTimeMs: Long,
    val comparisonWindowMs: Long,
    val halfLifeMs: Long,
    val tolerance: Double,
    val trackDynamics: List<TrackDynamicsResult>,
    val totalTracksEvaluated: Int,
    val increasingCount: Int,
    val decreasingCount: Int,
    val stableCount: Int,
    val insufficientDataCount: Int,
    val notices: List<String>,
)

/**
 * Deterministic temporal dynamics engine.
 *
 * For each identifiable track:
 *   currentSignal  = S(evaluationTimeMs)
 *   previousSignal = S(evaluationTimeMs - comparisonWindowMs)
 *   signalDelta    = currentSignal - previousSignal
 *   changeRatePerDay = signalDelta / comparisonWindowDays
 *
 * Direction classification uses configurable absolute delta tolerance [tolerance]:
 *   signalDelta > tolerance       → INCREASING
 *   signalDelta < -tolerance      → DECREASING
 *   otherwise                     → STABLE (if both points have ≥ 1 eligible appearance)
 *                                   INSUFFICIENT_DATA (otherwise)
 *
 * Defaults:
 *   comparisonWindowMs = 7 days
 *   tolerance          = 0.05
 */
class TemporalDynamicsEngine(
    val comparisonWindowMs: Long = DEFAULT_COMPARISON_WINDOW_MS,
    val tolerance: Double = DEFAULT_TOLERANCE,
) {
    init {
        require(comparisonWindowMs > 0) { "comparisonWindowMs must be positive: $comparisonWindowMs" }
        require(tolerance >= 0.0) { "tolerance must be non-negative: $tolerance" }
        require(tolerance.isFinite()) { "tolerance must be finite" }
    }

    /**
     * Evaluate temporal dynamics for a single track.
     *
     * @param evaluationTimeMs current evaluation timestamp (ms since epoch).
     * @param appearanceTimestampsMs distinct appearance timestamps (ms since epoch).
     *        Duplicates are deduplicated per existing policy.
     * @return [TrackDynamicsResult] with current/previous signals, delta, and direction.
     */
    fun evaluate(
        evaluationTimeMs: Long,
        appearanceTimestampsMs: List<Long>,
    ): TrackDynamicsResult {
        val previousTimeMs = evaluationTimeMs - comparisonWindowMs
        val halfLifeMs = TemporalSignalEngine.DEFAULT_HALF_LIFE_MS
        val engine = TemporalSignalEngine(halfLifeMs)

        val currentResult = engine.evaluate(evaluationTimeMs, appearanceTimestampsMs)
        val previousResult = engine.evaluate(previousTimeMs, appearanceTimestampsMs)

        val currentSignal = currentResult.signal
        val previousSignal = previousResult.signal
        val signalDelta = currentSignal - previousSignal

        val comparisonWindowDays = comparisonWindowMs.toDouble() / (24.0 * 60.0 * 60.0 * 1000.0)
        val changeRatePerDay = if (comparisonWindowDays > 0.0) signalDelta / comparisonWindowDays else null

        val currentHasEvidence = currentResult.eligibleAppearanceCount > 0
        val previousHasEvidence = previousResult.eligibleAppearanceCount > 0

        val direction = when {
            !currentHasEvidence && !previousHasEvidence -> TemporalDirection.INSUFFICIENT_DATA
            signalDelta > tolerance -> TemporalDirection.INCREASING
            signalDelta < -tolerance -> TemporalDirection.DECREASING
            currentHasEvidence && previousHasEvidence -> TemporalDirection.STABLE
            else -> TemporalDirection.INSUFFICIENT_DATA
        }

        val notices = mutableListOf<String>()
        if (!currentHasEvidence) notices.add("No eligible appearances at current evaluation time.")
        if (!previousHasEvidence) notices.add("No eligible appearances at previous evaluation time.")
        if (currentResult.futureAppearanceCount > 0) {
            notices.add("Future-dated appearances excluded from current signal.")
        }
        if (previousResult.futureAppearanceCount > 0) {
            notices.add("Future-dated appearances excluded from previous signal.")
        }

        return TrackDynamicsResult(
            entryId = "",
            sourcePackage = null,
            providerTrackId = null,
            title = null,
            artist = null,
            currentSignal = currentSignal,
            previousSignal = previousSignal,
            signalDelta = signalDelta,
            changeRatePerDay = changeRatePerDay,
            direction = direction,
            currentEligibleCount = currentResult.eligibleAppearanceCount,
            previousEligibleCount = previousResult.eligibleAppearanceCount,
            comparisonWindowMs = comparisonWindowMs,
            halfLifeMs = halfLifeMs,
            notices = notices,
        )
    }

    companion object {
        /** Default comparison window: 7 days in milliseconds. */
        const val DEFAULT_COMPARISON_WINDOW_MS: Long = 7 * 24 * 60 * 60 * 1000L
        /** Default tolerance for direction classification. */
        const val DEFAULT_TOLERANCE: Double = 0.05
    }
}

/** Evaluates temporal dynamics for all tracks in a ListeningMemory. */
object TemporalDynamicsEvaluator {
    fun evaluate(
        memory: com.tunemind.app.domain.model.ListeningMemory,
        evaluationTimeMs: Long = System.currentTimeMillis(),
        comparisonWindowMs: Long = TemporalDynamicsEngine.DEFAULT_COMPARISON_WINDOW_MS,
        tolerance: Double = TemporalDynamicsEngine.DEFAULT_TOLERANCE,
    ): TemporalDynamicsSummary {
        val engine = TemporalDynamicsEngine(comparisonWindowMs, tolerance)
        val trackResults = mutableListOf<TrackDynamicsResult>()
        val halfLifeMs = TemporalSignalEngine.DEFAULT_HALF_LIFE_MS

        for (track in memory.tracks) {
            val eligibleAppearances = track.appearances.filter { appearance ->
                appearance.firstObservedAtMs > 0L &&
                    track.identityBasis != com.tunemind.app.domain.model.TrackIdentityBasis.INSUFFICIENT
            }
            val timestamps = eligibleAppearances.map { it.firstObservedAtMs }
            val result = engine.evaluate(evaluationTimeMs, timestamps)
            trackResults.add(
                result.copy(
                    entryId = track.entryId,
                    sourcePackage = track.sourcePackage,
                    providerTrackId = track.providerTrackId,
                    title = track.title,
                    artist = track.artist,
                )
            )
        }

        val increasing = trackResults.count { it.direction == TemporalDirection.INCREASING }
        val decreasing = trackResults.count { it.direction == TemporalDirection.DECREASING }
        val stable = trackResults.count { it.direction == TemporalDirection.STABLE }
        val insufficient = trackResults.count { it.direction == TemporalDirection.INSUFFICIENT_DATA }

        val notices = mutableListOf<String>()
        if (trackResults.isEmpty()) {
            notices.add("No tracks in memory to evaluate.")
        }

        return TemporalDynamicsSummary(
            evaluationTimeMs = evaluationTimeMs,
            comparisonWindowMs = comparisonWindowMs,
            halfLifeMs = halfLifeMs,
            tolerance = tolerance,
            trackDynamics = trackResults,
            totalTracksEvaluated = trackResults.size,
            increasingCount = increasing,
            decreasingCount = decreasing,
            stableCount = stable,
            insufficientDataCount = insufficient,
            notices = notices,
        )
    }
}
