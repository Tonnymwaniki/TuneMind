package com.tunemind.app.domain.study

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface StudyDao {
    @Query("SELECT * FROM listening_study WHERE studyId = 'default'")
    fun observe(): Flow<StudyEntity?>

    @Query("SELECT * FROM listening_study WHERE studyId = 'default'")
    suspend fun get(): StudyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(study: StudyEntity)
}
