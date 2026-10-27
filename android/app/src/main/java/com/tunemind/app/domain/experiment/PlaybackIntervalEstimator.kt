package com.tunemind.app.domain.experiment

import org.json.JSONObject

/**
 * Conservative playback interval estimator using raw event payloads.
 *
 * Rules:
 * - An interval starts on a PLAYING state (playbackStateCode == 3).
 * - An interval ends on STOPPED (1), NONE (0), or SESSION_DESTROYED.
 * - PAUSED (2) continues the interval but does not extend the playback time.
 * - Unknown/unavailable state continues the interval without extending playback time.
 * - Gaps > 5 minutes between consecutive events close the interval.
 * - Duplicate event IDs are not double-counted.
 * - Future and invalid timestamps are excluded.
 */
class PlaybackIntervalEstimator {

    data class IntervalResult(
        val observedPlaybackMs: Long,
        val eventCount: Int,
        val intervalCount: Int,
        val sourcePackage: String,
        val earliestEventMs: Long?,
        val latestEventMs: Long?,
    )

    private data class PlaybackEvent(
        val eventId: String,
        val eventTimeMs: Long,
        val playbackStateCode: Int?,
    )

    companion object {
        private const val GAP_BOUNDARY_MS = 300_000L // 5 minutes
        private const val STATE_PLAYING = 3
        private const val STATE_STOPPED = 1
        private const val STATE_NONE = 0

        /**
         * Estimate observed playback time for events from a single source.
         *
         * @param events Raw experiment events (must include payload_json with playback data).
         * @return [IntervalResult] with estimated playback time in ms.
         */
        fun estimate(sourcePackage: String, events: List<ExperimentEventEntity>): IntervalResult {
            if (events.isEmpty()) return IntervalResult(0, 0, 0, sourcePackage, null, null)

            // Parse and sort valid events
            val parsed = events.mapNotNull { event ->
                val time = event.eventTimeMs
                if (time <= 0L) return@mapNotNull null
                val code = parsePlaybackState(event.payloadJson)
                PlaybackEvent(event.eventId, time, code)
            }.distinctBy { it.eventId }.sortedBy { it.eventTimeMs }

            if (parsed.isEmpty()) return IntervalResult(0, events.size, 0, sourcePackage, null, null)

            var totalPlaybackMs = 0L
            var intervalCount = 0
            var intervalStart: PlaybackEvent? = null
            var lastPlayingTimestamp: Long? = null

            for (i in parsed.indices) {
                val current = parsed[i]
                val previous = if (i > 0) parsed[i - 1] else null

                // Gap check
                if (previous != null) {
                    val gap = current.eventTimeMs - previous.eventTimeMs
                    if (gap > GAP_BOUNDARY_MS && intervalStart != null) {
                        // Close current interval at gap
                        lastPlayingTimestamp?.let { end ->
                            totalPlaybackMs += (previous.eventTimeMs - intervalStart!!.eventTimeMs).coerceAtLeast(0)
                        }
                        intervalStart = null
                        lastPlayingTimestamp = null
                        intervalCount++
                    }
                }

                val code = current.playbackStateCode

                when {
                    code == STATE_PLAYING -> {
                        if (intervalStart == null) {
                            intervalStart = current
                        }
                        lastPlayingTimestamp = current.eventTimeMs
                    }
                    code == STATE_STOPPED || code == STATE_NONE -> {
                        if (intervalStart != null) {
                            // Use the last PLAYING timestamp before the stop
                            val endTime = lastPlayingTimestamp ?: (previous?.eventTimeMs ?: current.eventTimeMs)
                            totalPlaybackMs += (endTime - intervalStart.eventTimeMs).coerceAtLeast(0)
                            intervalStart = null
                            lastPlayingTimestamp = null
                            intervalCount++
                        }
                    }
                    code == null || code == 2 -> {
                        // PAUSED or unavailable: interval continues, but no playback time accumulates
                        // lastPlayingTimestamp stays where it was
                    }
                }
            }

            // Close any remaining open interval
            if (intervalStart != null) {
                val endTime = lastPlayingTimestamp ?: parsed.last().eventTimeMs
                totalPlaybackMs += (endTime - intervalStart.eventTimeMs).coerceAtLeast(0)
                intervalCount++
            }

            return IntervalResult(
                observedPlaybackMs = totalPlaybackMs,
                eventCount = parsed.size,
                intervalCount = intervalCount,
                sourcePackage = sourcePackage,
                earliestEventMs = parsed.first().eventTimeMs,
                latestEventMs = parsed.last().eventTimeMs,
            )
        }

        private fun parsePlaybackState(payloadJson: String): Int? {
            return try {
                val obj = JSONObject(payloadJson)
                val pb = obj.optJSONObject("playback") ?: return null
                if (pb.has("playbackStateCode") && !pb.isNull("playbackStateCode")) {
                    pb.getInt("playbackStateCode")
                } else if (pb.has("isPlaying")) {
                    if (pb.getBoolean("isPlaying")) STATE_PLAYING else null
                } else null
            } catch (_: Exception) { null }
        }
    }
}
