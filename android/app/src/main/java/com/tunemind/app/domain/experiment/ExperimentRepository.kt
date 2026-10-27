package com.tunemind.app.domain.experiment

import android.content.Context
import com.tunemind.app.data.local.PlaybackObservationCodec
import com.tunemind.app.data.local.TuneMindDatabase
import com.tunemind.app.domain.model.PlaybackObservation
import kotlinx.coroutines.flow.Flow

class ExperimentRepository(private val db: TuneMindDatabase, private val appContext: Context? = null) {
    private val dao = db.experimentDao()

    fun observeExperiment(): Flow<ExperimentEntity?> = dao.observeActive()

    suspend fun getExperiment(): ExperimentEntity? = dao.getActive()

    suspend fun start(context: Context? = appContext): ExperimentEntity {
        val now = System.currentTimeMillis()
        val experiment = ExperimentEntity(
            status = ExperimentStatus.ACTIVE.name,
            startDateMs = now,
            endDateMs = now + 7L * 24 * 60 * 60 * 1000,
        )
        dao.upsert(experiment)
        // Schedule completion worker
        context?.let { ExperimentCompletionWorker.schedule(it, experiment) }
        return experiment
    }

    suspend fun complete() {
        val existing = dao.getActive()
        if (existing != null) {
            dao.upsert(existing.copy(status = ExperimentStatus.COMPLETED.name))
        }
    }

    suspend fun reset() {
        val existing = dao.getActive()
        if (existing != null) {
            dao.clearEvents(existing.experimentId)
            dao.upsert(existing.copy(
                status = ExperimentStatus.NOT_STARTED.name,
                startDateMs = null,
                endDateMs = null,
                lastObservationMs = null,
                observationCount = 0,
            ))
        } else {
            dao.upsert(ExperimentEntity(status = ExperimentStatus.NOT_STARTED.name))
        }
    }

    suspend fun recordObservation(observation: PlaybackObservation, experimentId: String) {
        val persisted = observation.copy(savedAtMs = System.currentTimeMillis())
        dao.insertEvent(
            ExperimentEventEntity(
                eventId = persisted.eventId,
                experimentId = experimentId,
                payloadJson = PlaybackObservationCodec.encode(persisted),
                eventTimeMs = persisted.eventTimeMs,
                savedAtMs = requireNotNull(persisted.savedAtMs),
                sourcePackage = persisted.sourcePackage ?: "",
            )
        )
        // Update experiment metadata using the provided experimentId
        val count = dao.eventCount(experimentId)
        val lastTime = dao.lastEventTime(experimentId)
        val exp = dao.getActive()
        if (exp != null && exp.experimentId == experimentId) {
            dao.upsert(exp.copy(observationCount = count, lastObservationMs = lastTime))
        }
    }

    suspend fun getEventTimestamps(experimentId: String): List<Long> {
        return dao.getEventTimestamps(experimentId)
    }

    suspend fun getObservedSources(experimentId: String): List<String> {
        return dao.observedSources(experimentId)
    }

    suspend fun getEventsBySource(experimentId: String, source: String): List<ExperimentEventEntity> {
        return dao.getEventsBySource(experimentId, source)
    }

    suspend fun getSummary(experimentId: String): ExperimentSummary? {
        val exp = dao.getActive() ?: return null
        if (exp.experimentId != experimentId) return null
        val count = dao.eventCount(experimentId)
        val lastTime = dao.lastEventTime(experimentId)
        val sources = dao.observedSources(experimentId)
        return ExperimentSummary(
            experimentId = exp.experimentId,
            status = exp.status,
            startDateMs = exp.startDateMs,
            endDateMs = exp.endDateMs,
            observationCount = count,
            lastObservationMs = lastTime,
            observedSources = sources,
        )
    }
}

data class ExperimentSummary(
    val experimentId: String,
    val status: String,
    val startDateMs: Long?,
    val endDateMs: Long?,
    val observationCount: Int,
    val lastObservationMs: Long?,
    val observedSources: List<String>,
)
