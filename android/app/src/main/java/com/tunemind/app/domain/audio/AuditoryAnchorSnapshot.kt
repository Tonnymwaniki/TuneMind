package com.tunemind.app.domain.audio

/**
 * Auditory anchor snapshot — a data contract for acoustic features extracted
 * from an audio segment, when audio access becomes available.
 *
 * CURRENT STATUS: UNAVAILABLE. TuneMind is a passive media-session metadata
 * observer. It does not have access to raw audio samples, PCM buffers, audio
 * files, microphone input, or any form of acoustic signal.
 *
 * This model defines the structure that auditory features would use once a
 * legitimate, supported audio-capture path is available (e.g. MediaProjection
 * with CAPTURE_MEDIA_OUTPUT on Android 10+, or direct access to cached audio
 * files through a media app's ContentProvider).
 *
 * All feature fields are nullable and default to null. Every feature must
 * include provenance information describing the audio segment and extraction
 * method that produced it.
 *
 * @property availability Current availability of auditory evidence.
 * @property provenanceMs Timestamp (ms since epoch) of the audio segment that was analysed.
 * @property provenanceSource Description of the audio source and extraction method.
 * @property rhythmBpm Estimated tempo in beats per minute, if extractable.
 * @property rhythmConfidence Confidence in the tempo estimate (0.0–1.0).
 * @property melodyVocalPresence Estimated probability that vocal content is present (0.0–1.0).
 * @property structuralComplexity Estimated structural complexity (0.0–1.0).
 * @property spectralCentroidHz Weighted mean of the frequency spectrum in Hz.
 * @property spectralRolloffHz Frequency below which a given percentage of spectral energy lies.
 * @property zeroCrossingRate Rate of sign changes in the audio signal.
 * @property energyRms Root-mean-square energy of the audio segment.
 * @property version Schema version for the auditory anchor model.
 */
data class AuditoryAnchorSnapshot(
    val availability: AuditoryAvailability,
    val provenanceMs: Long? = null,
    val provenanceSource: String? = null,
    val rhythmBpm: Double? = null,
    val rhythmConfidence: Double? = null,
    val melodyVocalPresence: Double? = null,
    val structuralComplexity: Double? = null,
    val spectralCentroidHz: Double? = null,
    val spectralRolloffHz: Double? = null,
    val zeroCrossingRate: Double? = null,
    val energyRms: Double? = null,
    val version: Int = AUDITORY_ANCHOR_VERSION,
) {
    companion object {
        const val AUDITORY_ANCHOR_VERSION = 1

        /** Pre-built instance representing unavailable auditory evidence. */
        val unavailable = AuditoryAnchorSnapshot(
            availability = AuditoryAvailability.UNAVAILABLE,
            provenanceSource = "TuneMind does not have access to raw audio samples. " +
                "See AuditoryAnchorSnapshot KDoc for details.",
        )

        /** Pre-built instance representing insufficient data for extraction. */
        val insufficient = AuditoryAnchorSnapshot(
            availability = AuditoryAvailability.INSUFFICIENT_DATA,
        )
    }
}

/** Availability of auditory evidence for a given observation. */
enum class AuditoryAvailability {
    /** Audio samples are available and feature extraction was attempted. */
    AVAILABLE,
    /** No audio source is configured or accessible. */
    UNAVAILABLE,
    /** Audio samples exist but are insufficient for reliable feature extraction. */
    INSUFFICIENT_DATA,
}

/**
 * Auditory feature provenance — describes how a feature was extracted.
 *
 * @property audioSource Description of the audio source (e.g. "MediaProjection capture",
 *                        "local file: /storage/emulated/0/Music/song.mp3").
 * @property extractorName Name or identifier of the extraction algorithm.
 * @property extractorVersion Version of the extraction algorithm.
 * @property segmentStartMs Start of the analysed audio segment (ms from track start).
 * @property segmentDurationMs Duration of the analysed audio segment (ms).
 * @property sampleRateHz Sample rate of the analysed audio, if available.
 */
data class AuditoryFeatureProvenance(
    val audioSource: String,
    val extractorName: String,
    val extractorVersion: String,
    val segmentStartMs: Long? = null,
    val segmentDurationMs: Long? = null,
    val sampleRateHz: Int? = null,
)
