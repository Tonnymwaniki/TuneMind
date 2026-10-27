package com.tunemind.app.domain.experiment

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "experiment")
data class ExperimentEntity(
    @PrimaryKey val experimentId: String = UUID.randomUUID().toString(),
    val status: String = "NOT_STARTED",
    val startDateMs: Long? = null,
    val endDateMs: Long? = null,
    val lastObservationMs: Long? = null,
    val observationCount: Int = 0,
    val totalDays: Int = 7,
)

@Entity(tableName = "experiment_event")
data class ExperimentEventEntity(
    @PrimaryKey val eventId: String,
    val experimentId: String,
    val payloadJson: String,
    val eventTimeMs: Long,
    val savedAtMs: Long,
    val sourcePackage: String,
)

enum class ExperimentStatus { NOT_STARTED, ACTIVE, COMPLETED, INTERRUPTED }
