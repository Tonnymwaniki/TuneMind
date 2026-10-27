package com.tunemind.app.domain.research

import com.tunemind.app.domain.model.*
import com.tunemind.app.domain.signal.TemporalSignalEngine

/**
 * Deterministic pattern research engine — recurrence acceleration, reappearance
 * after absence, listening concentration, and evidence quality.
 *
 * All measurements use distinct reconstructed appearances, not raw callbacks.
 * All evaluation timestamps are explicit. No system-clock calls in the engine.
 */

// ─── 1. Recurrence Acceleration ───────────────────────────────────────────────

data class WindowAppearanceCount(
    val windowStartMs: Long,
    val windowEndMs: Long,
    val distinctAppearanceCount: Int,
)

data class RecurrenceAccelerationResult(
    val earlierWindow: WindowAppearanceCount,
    val laterWindow: WindowAppearanceCount,
    val absoluteChange: Int,
    val notices: List<String>,
)

fun computeRecurrenceAcceleration(
    appearanceTimestampsMs: List<Long>,
    evaluationTimeMs: Long,
    windowDurationMs: Long,
): RecurrenceAccelerationResult {
    require(windowDurationMs > 0) { "windowDurationMs must be positive: $windowDurationMs" }
    val originalDistinct = appearanceTimestampsMs.distinct()
    val deduped = originalDistinct.filter { it > 0L && it <= evaluationTimeMs }
    val earlierEnd = evaluationTimeMs - windowDurationMs
    val earlierStart = earlierEnd - windowDurationMs

    val earlier = deduped.count { it in earlierStart until earlierEnd }
    val later = deduped.count { it in earlierEnd until evaluationTimeMs }

    val notices = mutableListOf<String>()
    if (originalDistinct.size != appearanceTimestampsMs.size) {
        notices.add("Duplicate appearance IDs removed.")
    }
    val excluded = appearanceTimestampsMs.distinct().size - deduped.size
    if (excluded > 0) notices.add("$excluded appearance(s) excluded (invalid or future timestamp).")

    return RecurrenceAccelerationResult(
        earlierWindow = WindowAppearanceCount(earlierStart, earlierEnd, earlier),
        laterWindow = WindowAppearanceCount(earlierEnd, evaluationTimeMs, later),
        absoluteChange = later - earlier,
        notices = notices,
    )
}

// ─── 2. Reappearance After Absence ───────────────────────────────────────────

data class ReappearanceAfterGapResult(
    val entryId: String,
    val title: String?,
    val artist: String?,
    val gapMs: Long,
    val earlierAppearanceMs: Long,
    val laterAppearanceMs: Long,
    val minGapMs: Long,
    val hasReappearance: Boolean,
    val notices: List<String>,
)

fun detectReappearanceAfterGap(
    entryId: String,
    title: String?,
    artist: String?,
    appearanceTimestampsMs: List<Long>,
    minGapMs: Long,
): ReappearanceAfterGapResult {
    require(minGapMs > 0) { "minGapMs must be positive: $minGapMs" }
    val valid = appearanceTimestampsMs.distinct().filter { it > 0L }.sorted()

    val notices = mutableListOf<String>()
    if (valid.size != appearanceTimestampsMs.distinct().size) {
        notices.add("Duplicate appearance IDs removed.")
    }
    if (appearanceTimestampsMs.any { it <= 0L }) {
        notices.add("Invalid timestamps excluded.")
    }
    if (valid.any { it > System.currentTimeMillis() }) {
        // Use a reference time for detection; future timestamps excluded from gap calculation
        notices.add("Future timestamps excluded from gap detection.")
    }

    val nonFuture = valid.filter { it <= System.currentTimeMillis() }

    for (i in 1 until nonFuture.size) {
        val gap = nonFuture[i] - nonFuture[i - 1]
        if (gap >= minGapMs) {
            return ReappearanceAfterGapResult(
                entryId = entryId, title = title, artist = artist,
                gapMs = gap,
                earlierAppearanceMs = nonFuture[i - 1],
                laterAppearanceMs = nonFuture[i],
                minGapMs = minGapMs,
                hasReappearance = true,
                notices = notices,
            )
        }
    }

    return ReappearanceAfterGapResult(
        entryId = entryId, title = title, artist = artist,
        gapMs = 0L,
        earlierAppearanceMs = nonFuture.firstOrNull() ?: 0L,
        laterAppearanceMs = nonFuture.lastOrNull() ?: 0L,
        minGapMs = minGapMs,
        hasReappearance = false,
        notices = notices,
    )
}

// ─── 3. Listening Concentration ──────────────────────────────────────────────

data class TrackConcentrationEntry(
    val entryId: String,
    val title: String?,
    val artist: String?,
    val appearanceCount: Int,
    val share: Double,
)

data class ConcentrationResult(
    val windowStartMs: Long,
    val windowEndMs: Long,
    val totalIdentifiedAppearanceCount: Int,
    val distinctIdentifiedTrackCount: Int,
    val tracks: List<TrackConcentrationEntry>,
    val normalizedConcentration: Double?,
    val notices: List<String>,
)

fun computeConcentration(
    memory: ListeningMemory,
    evaluationTimeMs: Long,
    windowDurationMs: Long,
): ConcentrationResult {
    require(windowDurationMs > 0) { "windowDurationMs must be positive: $windowDurationMs" }
    val windowStart = evaluationTimeMs - windowDurationMs

    val identifiedTracks = memory.tracks.filter {
        it.identityBasis != TrackIdentityBasis.INSUFFICIENT &&
            it.evidenceQuality != MemoryEvidenceQuality.CONFLICTING_METADATA
    }

    val notices = mutableListOf<String>()
    if (memory.tracks.any { it.identityBasis == TrackIdentityBasis.INSUFFICIENT }) {
        notices.add("Unidentified tracks excluded from concentration.")
    }

    val trackContributions = identifiedTracks.mapNotNull { track ->
        val appearancesInWindow = track.appearances.filter { appearance ->
            appearance.firstObservedAtMs > 0L &&
                appearance.firstObservedAtMs in windowStart..evaluationTimeMs
        }
        if (appearancesInWindow.isEmpty()) return@mapNotNull null
        TrackConcentrationEntry(
            entryId = track.entryId,
            title = track.title,
            artist = track.artist,
            appearanceCount = appearancesInWindow.size,
            share = 0.0, // computed below
        )
    }

    val totalAppearances = trackContributions.sumOf { it.appearanceCount }
    val tracksWithShare = trackContributions.map { entry ->
        val share = if (totalAppearances > 0) entry.appearanceCount.toDouble() / totalAppearances else 0.0
        entry.copy(share = share)
    }.sortedByDescending { it.share }

    // Normalized concentration: sum of squared shares (Herfindahl-Hirschman Index style)
    val normalizedConcentration = if (tracksWithShare.size >= 2) {
        tracksWithShare.sumOf { it.share * it.share }
    } else null

    return ConcentrationResult(
        windowStartMs = windowStart,
        windowEndMs = evaluationTimeMs,
        totalIdentifiedAppearanceCount = totalAppearances,
        distinctIdentifiedTrackCount = tracksWithShare.size,
        tracks = tracksWithShare,
        normalizedConcentration = normalizedConcentration,
        notices = notices,
    )
}

// ─── 4. Evidence Quality ─────────────────────────────────────────────────────

data class EvidenceQualityResult(
    val totalTracksInMemory: Int,
    val identifiedTrackCount: Int,
    val insufficientIdentityCount: Int,
    val conflictingMetadataCount: Int,
    val ambiguousReconstructionCount: Int,
    val rawEvidenceUnavailableCount: Int,
    val historyMayBeIncomplete: Boolean,
    val notices: List<String>,
)

fun assessEvidenceQuality(memory: ListeningMemory): EvidenceQualityResult {
    val notices = mutableListOf<String>()
    val total = memory.tracks.size
    val identified = memory.tracks.count { it.identityBasis != TrackIdentityBasis.INSUFFICIENT }
    val insufficient = memory.tracks.count { it.identityBasis == TrackIdentityBasis.INSUFFICIENT }
    val conflicting = memory.tracks.count { it.evidenceQuality == MemoryEvidenceQuality.CONFLICTING_METADATA }
    val ambiguous = memory.tracks.count { it.evidenceQuality == MemoryEvidenceQuality.AMBIGUOUS_RECONSTRUCTION }
    val unavailable = memory.tracks.count { it.evidenceQuality == MemoryEvidenceQuality.RAW_EVIDENCE_UNAVAILABLE }
    val incomplete = memory.summary.historyMayBeIncomplete

    if (incomplete) notices.add("Observation history may be incomplete due to retention limits or open episodes.")
    if (insufficient > 0) notices.add("$insufficient track(s) have insufficient identity.")
    if (conflicting > 0) notices.add("$conflicting track(s) have conflicting metadata.")
    if (ambiguous > 0) notices.add("$ambiguous track(s) have ambiguous reconstruction.")
    if (unavailable > 0) notices.add("$unavailable track(s) have unavailable raw evidence.")
    if (memory.notices.isNotEmpty()) {
        notices.addAll(memory.notices.map { it.name.lowercase().replace('_', ' ') })
    }

    return EvidenceQualityResult(
        totalTracksInMemory = total,
        identifiedTrackCount = identified,
        insufficientIdentityCount = insufficient,
        conflictingMetadataCount = conflicting,
        ambiguousReconstructionCount = ambiguous,
        rawEvidenceUnavailableCount = unavailable,
        historyMayBeIncomplete = incomplete,
        notices = notices,
    )
}

// ─── Summary ─────────────────────────────────────────────────────────────────

data class PatternResearchSummary(
    val evaluationTimeMs: Long,
    val recurrenceAcceleration: RecurrenceAccelerationResult,
    val reappearances: List<ReappearanceAfterGapResult>,
    val concentration: ConcentrationResult,
    val evidenceQuality: EvidenceQualityResult,
    val notices: List<String>,
)

object PatternResearchEvaluator {
    fun evaluate(
        memory: ListeningMemory,
        evaluationTimeMs: Long = System.currentTimeMillis(),
        accelerationWindowDurationMs: Long = 7 * 24 * 60 * 60 * 1000L,
        reappearanceMinGapMs: Long = 7 * 24 * 60 * 60 * 1000L,
        concentrationWindowDurationMs: Long = 7 * 24 * 60 * 60 * 1000L,
    ): PatternResearchSummary {
        val notices = mutableListOf<String>()

        // Recurrence acceleration across all identified tracks
        val allTimestamps = memory.tracks
            .filter { it.identityBasis != TrackIdentityBasis.INSUFFICIENT }
            .flatMap { track ->
                track.appearances
                    .filter { it.firstObservedAtMs > 0L }
                    .map { it.firstObservedAtMs }
            }
        val acceleration = computeRecurrenceAcceleration(
            allTimestamps, evaluationTimeMs, accelerationWindowDurationMs,
        )

        // Reappearance after gap per identified track
        val reappearances = memory.tracks
            .filter { it.identityBasis != TrackIdentityBasis.INSUFFICIENT }
            .mapNotNull { track ->
                val timestamps = track.appearances
                    .filter { it.firstObservedAtMs > 0L }
                    .map { it.firstObservedAtMs }
                if (timestamps.size < 2) return@mapNotNull null
                val result = detectReappearanceAfterGap(
                    entryId = track.entryId,
                    title = track.title,
                    artist = track.artist,
                    appearanceTimestampsMs = timestamps,
                    minGapMs = reappearanceMinGapMs,
                )
                if (result.hasReappearance) result else null
            }

        // Concentration
        val concentration = computeConcentration(memory, evaluationTimeMs, concentrationWindowDurationMs)

        // Evidence quality
        val evidenceQuality = assessEvidenceQuality(memory)

        return PatternResearchSummary(
            evaluationTimeMs = evaluationTimeMs,
            recurrenceAcceleration = acceleration,
            reappearances = reappearances,
            concentration = concentration,
            evidenceQuality = evidenceQuality,
            notices = notices,
        )
    }
}
