package com.tunemind.app.data.local

import com.tunemind.app.domain.model.PlaybackObservation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Local-only persistence with a fixed cap to prevent unbounded telemetry retention. */
class EventBufferRepository(private val dao: EventQueueDao) {
    fun observeRecent(): Flow<List<PlaybackObservation>> = dao.observeRecent(MAX_OBSERVATIONS)
        .map { rows -> rows.mapNotNull { row -> runCatching {
            PlaybackObservationCodec.decode(row.payloadJson, legacySavedAtMs = row.savedAtMs)
        }.getOrNull() } }

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun record(observation: PlaybackObservation) {
        val persisted = observation.copy(savedAtMs = System.currentTimeMillis())
        dao.insert(
            EventQueueEntity(
                eventId = persisted.eventId,
                payloadJson = PlaybackObservationCodec.encode(persisted),
                eventTimeMs = persisted.eventTimeMs,
                savedAtMs = requireNotNull(persisted.savedAtMs),
            ),
        )
        dao.pruneToLimit(MAX_OBSERVATIONS)
    }

    suspend fun clear() = dao.clearAll()

    companion object {
        const val MAX_OBSERVATIONS = 500
    }
}
