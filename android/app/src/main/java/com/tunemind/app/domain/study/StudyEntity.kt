package com.tunemind.app.domain.study

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "listening_study")
data class StudyEntity(
    @PrimaryKey val studyId: String = "default",
    val status: String = "NOT_STARTED",
    val startDateMs: Long? = null,
    val endDateMs: Long? = null,
    val completedDays: Int = 0,
    val totalDays: Int = 7,
)

enum class StudyStatus { NOT_STARTED, ACTIVE, COMPLETED, INTERRUPTED }
