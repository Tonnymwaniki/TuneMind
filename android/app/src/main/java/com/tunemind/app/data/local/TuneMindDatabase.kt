package com.tunemind.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tunemind.app.domain.audio.AudioDeviceDao
import com.tunemind.app.domain.audio.AudioDeviceSessionEntity
import com.tunemind.app.domain.audio.VolumeObservationEntity
import com.tunemind.app.domain.experiment.ExperimentDao
import com.tunemind.app.domain.experiment.ExperimentEntity
import com.tunemind.app.domain.experiment.ExperimentEventEntity
import com.tunemind.app.domain.moment.MusicalMomentDao
import com.tunemind.app.domain.moment.MusicalMomentEntity
import com.tunemind.app.domain.provider.MusicProviderDao
import com.tunemind.app.domain.provider.MusicProviderEntity
import com.tunemind.app.domain.study.StudyDao
import com.tunemind.app.domain.study.StudyEntity

/**
 * TuneMind local Room database.
 *
 * Version history:
 *   1 — Initial event queue schema.
 *   2 — Versioned observation payloads, save time and local sequence ordering.
 *   3 — Listening study table.
 *   4 — Experiment + experiment_event tables.
 *   5 — Musical moments, audio device sessions, volume observations, and music providers.
 */
@Database(
    entities = [
        EventQueueEntity::class,
        StudyEntity::class,
        ExperimentEntity::class,
        ExperimentEventEntity::class,
        MusicalMomentEntity::class,
        AudioDeviceSessionEntity::class,
        VolumeObservationEntity::class,
        MusicProviderEntity::class
    ],
    version = 5,
    exportSchema = true,
)
abstract class TuneMindDatabase : RoomDatabase() {

    abstract fun eventQueueDao(): EventQueueDao
    abstract fun studyDao(): StudyDao
    abstract fun experimentDao(): ExperimentDao
    abstract fun musicalMomentDao(): MusicalMomentDao
    abstract fun audioDeviceDao(): AudioDeviceDao
    abstract fun musicProviderDao(): MusicProviderDao

    companion object {
        private const val DATABASE_NAME = "tunemind.db"

        @Volatile
        private var INSTANCE: TuneMindDatabase? = null

        fun getInstance(context: Context): TuneMindDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TuneMindDatabase::class.java,
                    DATABASE_NAME,
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE event_queue_v2 (
                        sequence_number INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        event_id TEXT NOT NULL,
                        payload_json TEXT NOT NULL,
                        synced INTEGER NOT NULL,
                        event_time_ms INTEGER NOT NULL,
                        saved_at_ms INTEGER NOT NULL
                    )""".trimIndent(),
                )
                db.execSQL(
                    """INSERT INTO event_queue_v2
                        (event_id, payload_json, synced, event_time_ms, saved_at_ms)
                        SELECT observation_id, payload_json, synced, created_at, created_at
                        FROM event_queue ORDER BY created_at ASC, observation_id ASC""".trimIndent(),
                )
                db.execSQL("DROP TABLE event_queue")
                db.execSQL("ALTER TABLE event_queue_v2 RENAME TO event_queue")
                db.execSQL("CREATE UNIQUE INDEX index_event_queue_event_id ON event_queue(event_id)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS listening_study (studyId TEXT PRIMARY KEY NOT NULL, status TEXT NOT NULL, startDateMs INTEGER, endDateMs INTEGER, completedDays INTEGER NOT NULL DEFAULT 0, totalDays INTEGER NOT NULL DEFAULT 7)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS experiment (experimentId TEXT PRIMARY KEY NOT NULL, status TEXT NOT NULL, startDateMs INTEGER, endDateMs INTEGER, lastObservationMs INTEGER, observationCount INTEGER NOT NULL DEFAULT 0, totalDays INTEGER NOT NULL DEFAULT 7)")
                db.execSQL("CREATE TABLE IF NOT EXISTS experiment_event (eventId TEXT PRIMARY KEY NOT NULL, experimentId TEXT NOT NULL, payloadJson TEXT NOT NULL, eventTimeMs INTEGER NOT NULL, savedAtMs INTEGER NOT NULL, sourcePackage TEXT NOT NULL)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS musical_moments (
                        momentId TEXT PRIMARY KEY NOT NULL,
                        trackTitle TEXT NOT NULL,
                        trackArtist TEXT NOT NULL,
                        trackAlbum TEXT,
                        sourcePackage TEXT,
                        providerTrackId TEXT,
                        startPositionMs INTEGER NOT NULL,
                        endPositionMs INTEGER NOT NULL,
                        preferredPlaybackDurationMs INTEGER NOT NULL,
                        discoveryMethod TEXT NOT NULL DEFAULT 'USER_MARKED',
                        confidence REAL,
                        userConfirmed INTEGER NOT NULL DEFAULT 1,
                        customName TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        lastPlayedAt INTEGER
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_musical_moments_trackTitle_trackArtist ON musical_moments(trackTitle, trackArtist)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_musical_moments_userConfirmed ON musical_moments(userConfirmed)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_musical_moments_createdAt ON musical_moments(createdAt)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS audio_device_sessions (
                        deviceSessionId TEXT PRIMARY KEY NOT NULL,
                        episodeId TEXT,
                        deviceType TEXT NOT NULL,
                        deviceName TEXT,
                        connectionType TEXT NOT NULL,
                        connectedAt INTEGER NOT NULL,
                        disconnectedAt INTEGER,
                        identificationConfidence TEXT NOT NULL DEFAULT 'OBSERVED_HARDWARE'
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audio_device_sessions_deviceType ON audio_device_sessions(deviceType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audio_device_sessions_connectedAt ON audio_device_sessions(connectedAt)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS volume_observations (
                        volumeObservationId TEXT PRIMARY KEY NOT NULL,
                        deviceSessionId TEXT,
                        occurredAt INTEGER NOT NULL,
                        streamType TEXT NOT NULL,
                        volumeIndex INTEGER NOT NULL,
                        maximumVolumeIndex INTEGER NOT NULL,
                        outputDeviceType TEXT,
                        estimatedExposureDb REAL,
                        measurementMethod TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_volume_observations_occurredAt ON volume_observations(occurredAt)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS music_provider_summaries (
                        packageName TEXT PRIMARY KEY NOT NULL,
                        displayName TEXT NOT NULL,
                        firstObservedAt INTEGER NOT NULL,
                        lastObservedAt INTEGER NOT NULL,
                        totalObservedEpisodes INTEGER NOT NULL DEFAULT 1,
                        totalObservedPlaybackMs INTEGER NOT NULL DEFAULT 0,
                        integrationStatus TEXT NOT NULL,
                        capabilitiesJson TEXT NOT NULL
                    )
                """.trimIndent())
            }
        }
    }
}
