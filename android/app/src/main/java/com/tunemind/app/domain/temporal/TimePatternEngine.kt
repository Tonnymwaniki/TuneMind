package com.tunemind.app.domain.temporal

import com.tunemind.app.domain.model.TrackMemoryEntry
import java.util.Calendar

/**
 * Standard temporal categories for listening patterns.
 */
enum class TimeSlot(val label: String, val startHour: Int, val endHour: Int) {
    EARLY_MORNING("Dawn / Early Morning", 5, 8),
    MORNING("Morning Focus", 8, 12),
    AFTERNOON("Afternoon Receptivity", 12, 17),
    EVENING("Evening Wind-down", 17, 21),
    NIGHT("Night Listening", 21, 24),
    LATE_NIGHT("Late Night Deep Dive", 0, 5);

    companion object {
        fun currentSlot(): TimeSlot {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            return entries.firstOrNull {
                if (it.startHour < it.endHour) {
                    hour in it.startHour until it.endHour
                } else {
                    hour >= it.startHour || hour < it.endHour
                }
            } ?: MORNING
        }

        fun slotForHour(hour: Int): TimeSlot {
            return entries.firstOrNull {
                if (it.startHour < it.endHour) {
                    hour in it.startHour until it.endHour
                } else {
                    hour >= it.startHour || hour < it.endHour
                }
            } ?: MORNING
        }
    }
}

/**
 * Derives temporal listening patterns and time-contextual affinity from historical observations.
 */
class TimePatternEngine {

    data class TemporalAffinity(
        val slot: TimeSlot,
        val topTracks: List<String>,
        val topArtists: List<String>,
        val estimatedSessionCount: Int
    )

    fun computeTemporalProfile(tracks: List<TrackMemoryEntry>): List<TemporalAffinity> {
        val slotCounts = mutableMapOf<TimeSlot, Int>()
        val slotTracks = mutableMapOf<TimeSlot, MutableMap<String, Int>>()
        val slotArtists = mutableMapOf<TimeSlot, MutableMap<String, Int>>()

        TimeSlot.entries.forEach { slot ->
            slotCounts[slot] = 0
            slotTracks[slot] = mutableMapOf()
            slotArtists[slot] = mutableMapOf()
        }

        val cal = Calendar.getInstance()
        tracks.forEach { track ->
            val title = track.title ?: return@forEach
            val artist = track.artist ?: "Unknown Artist"

            track.appearances.forEach { appearance ->
                cal.timeInMillis = appearance.firstObservedAtMs
                val hour = cal.get(Calendar.HOUR_OF_DAY)
                val slot = TimeSlot.slotForHour(hour)

                slotCounts[slot] = (slotCounts[slot] ?: 0) + 1
                val tMap = slotTracks[slot]!!
                tMap[title] = (tMap[title] ?: 0) + 1

                val aMap = slotArtists[slot]!!
                aMap[artist] = (aMap[artist] ?: 0) + 1
            }
        }

        return TimeSlot.entries.map { slot ->
            val topT = slotTracks[slot]!!.entries.sortedByDescending { it.value }.take(3).map { it.key }
            val topA = slotArtists[slot]!!.entries.sortedByDescending { it.value }.take(3).map { it.key }
            TemporalAffinity(
                slot = slot,
                topTracks = topT,
                topArtists = topA,
                estimatedSessionCount = slotCounts[slot] ?: 0
            )
        }
    }
}
