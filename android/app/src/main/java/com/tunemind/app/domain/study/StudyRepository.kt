package com.tunemind.app.domain.study

import com.tunemind.app.data.local.TuneMindDatabase

class StudyRepository(private val db: TuneMindDatabase) {
    private val dao = db.studyDao()

    fun observeStatus() = dao.observe()

    suspend fun getStatus(): StudyEntity? = dao.get()

    suspend fun start() {
        val now = System.currentTimeMillis()
        dao.upsert(StudyEntity(
            status = StudyStatus.ACTIVE.name,
            startDateMs = now,
            endDateMs = now + 7L * 24 * 60 * 60 * 1000,
        ))
    }

    suspend fun complete() {
        val existing = dao.get()
        if (existing != null) {
            dao.upsert(existing.copy(status = StudyStatus.COMPLETED.name))
        }
    }

    suspend fun reset() {
        dao.upsert(StudyEntity(status = StudyStatus.NOT_STARTED.name))
    }
}
