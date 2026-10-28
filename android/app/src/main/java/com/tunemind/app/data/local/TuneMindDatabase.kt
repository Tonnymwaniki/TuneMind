package com.tunemind.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tunemind.app.domain.experiment.ExperimentDao
import com.tunemind.app.domain.experiment.ExperimentEntity
import com.tunemind.app.domain.experiment.ExperimentEventEntity
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
 */
@Database(
    entities = [EventQueueEntity::class, StudyEntity::class, ExperimentEntity::class, ExperimentEventEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class TuneMindDatabase : RoomDatabase() {

    abstract fun eventQueueDao(): EventQueueDao
    abstract fun studyDao(): StudyDao
    abstract fun experimentDao(): ExperimentDao

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
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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
    }
}
