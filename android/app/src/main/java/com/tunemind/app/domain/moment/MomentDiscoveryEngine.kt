package com.tunemind.app.domain.moment

import com.tunemind.app.domain.model.PlaybackObservation
import java.util.UUID
import kotlin.math.abs

/**
 * Discovers candidate musical moments (repeatedly enjoyed sections of songs)
 * from observed playback positions and seek patterns.
 */
class MomentDiscoveryEngine(
    private val dao: MusicalMomentDao
) {
    private val trackPositionHistory = mutableMapOf<String, MutableList<Pair<Long, Long>>>() // "title|artist" -> list of (timestampMs, positionMs)

    suspend fun processObservation(obs: PlaybackObservation) {
        val title = obs.trackTitle ?: return
        val artist = obs.trackArtist ?: "Unknown Artist"
        val pos = obs.positionMs ?: return
        if (pos <= 0L) return

        val key = "$title|$artist"
        val list = trackPositionHistory.getOrPut(key) { mutableListOf() }
        val now = obs.eventTimeMs

        // Check if there was a backward seek (jump backward in position while time moved forward)
        val last = list.lastOrNull()
        if (last != null) {
            val (lastTime, lastPos) = last
            val timeDelta = now - lastTime
            val posDelta = pos - lastPos

            // If time progressed by 1-5 seconds, but playback position jumped backwards by > 15 seconds:
            if (timeDelta in 500..8_000 && posDelta < -15_000) {
                // Potential replay loop / seek back candidate
                val candidateStart = maxOf(0L, pos - 5_000L) // start slightly before landing
                val candidateEnd = lastPos // up to where they jumped back from
                val duration = candidateEnd - candidateStart

                if (duration in 10_000..90_000) { // between 10s and 90s is ideal musical moment length
                    val existing = dao.getForTrack(title, artist)
                    val isDuplicate = existing.any { abs(it.startPositionMs - candidateStart) < 10_000 }

                    if (!isDuplicate) {
                        dao.insertOrUpdate(
                            MusicalMomentEntity(
                                momentId = UUID.randomUUID().toString(),
                                trackTitle = title,
                                trackArtist = artist,
                                trackAlbum = obs.trackAlbum,
                                sourcePackage = obs.sourcePackage,
                                startPositionMs = candidateStart,
                                endPositionMs = candidateEnd,
                                preferredPlaybackDurationMs = duration,
                                discoveryMethod = "SEEK_BACK_PATTERN",
                                confidence = 0.85f,
                                userConfirmed = false, // Candidate waiting for user confirmation
                                customName = "Favorite Section (${formatMs(candidateStart)} - ${formatMs(candidateEnd)})"
                            )
                        )
                    }
                }
            }
        }

        list.add(Pair(now, pos))
        // Keep list bounded to last 50 observations per track
        if (list.size > 50) {
            list.removeAt(0)
        }
    }

    private fun formatMs(ms: Long): String {
        val sec = (ms / 1000) % 60
        val min = (ms / 1000) / 60
        return String.format(java.util.Locale.US, "%d:%02d", min, sec)
    }
}
