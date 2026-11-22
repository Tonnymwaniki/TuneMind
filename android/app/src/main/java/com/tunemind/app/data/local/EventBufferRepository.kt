package com.tunemind.app.data.local

import com.tunemind.app.domain.model.PlaybackObservation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicInteger

/** Local-only persistence with a fixed cap to prevent unbounded telemetry retention. */
class EventBufferRepository(private val dao: EventQueueDao) {
    private val insertsSincePrune = AtomicInteger(0)

    fun observeRecent(): Flow<List<PlaybackObservation>> = dao.observeRecent(LIVE_OBSERVE_LIMIT)
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
        if (insertsSincePrune.incrementAndGet() >= PRUNE_EVERY_N_INSERTS) {
            insertsSincePrune.set(0)
            dao.pruneToLimit(MAX_OBSERVATIONS)
        }
    }

    suspend fun clear() = dao.clearAll()

    companion object {
        const val MAX_OBSERVATIONS = 3_000
        const val LIVE_OBSERVE_LIMIT = 1_500
        private const val PRUNE_EVERY_N_INSERTS = 50
    }
}
