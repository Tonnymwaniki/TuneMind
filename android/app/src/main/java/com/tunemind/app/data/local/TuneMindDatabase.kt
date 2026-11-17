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
import com.tunemind.app.domain.provider.MusicProviderDao
import com.tunemind.app.domain.provider.MusicProviderEntity
import com.tunemind.app.domain.study.StudyDao
import com.tunemind.app.data.ingestion.ArtistProfileCacheEntity
import com.tunemind.app.data.ingestion.SpotifyLibraryDao
import com.tunemind.app.data.ingestion.SpotifyLikedTrackEntity
import com.tunemind.app.data.ingestion.SpotifyListeningHistoryDao
import com.tunemind.app.data.ingestion.SpotifyListeningHistoryEntity
import com.tunemind.app.data.ingestion.SpotifyPlaylistEntity
import com.tunemind.app.data.ingestion.SpotifyPodcastEntity
import com.tunemind.app.data.ingestion.SpotifyVisualDao
import com.tunemind.app.data.ingestion.TrackHistoryTelemetryItem
import com.tunemind.app.data.ingestion.TrackVisualCacheEntity
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
 *   6 — Persistent AI Recommendation Crate (chrono-receptive auto-evolving music discovery).
 *   7 — Index and column constraint alignment for AI Recommendation Crate.
 *   8 — Unique title-artist index, subTier, and reinforcement learning telemetry tracking fields.
 *   9 — Local-first Spotify Web API listening history table (spotify_listening_history).
 *   10 — Spotify TrackHistoryTelemetryItem table (track_history_telemetry).
 *   11 — Spotify Library tables (spotify_liked_tracks, spotify_playlists, spotify_podcasts).
 *   13 — Spotify Visual Metadata caches (spotify_track_visual_cache, spotify_artist_profile_cache).
 *   14 — Canonical trackId column on ai_recommendation_crate.
 */
@Database(
    entities = [
        EventQueueEntity::class,
        StudyEntity::class,
        ExperimentEntity::class,
        ExperimentEventEntity::class,
        AudioDeviceSessionEntity::class,
        VolumeObservationEntity::class,
        MusicProviderEntity::class,
        com.tunemind.app.domain.recommendation.AiRecommendationEntity::class,
        SpotifyListeningHistoryEntity::class,
        TrackHistoryTelemetryItem::class,
        SpotifyLikedTrackEntity::class,
        SpotifyPlaylistEntity::class,
        SpotifyPodcastEntity::class,
        TrackVisualCacheEntity::class,
        ArtistProfileCacheEntity::class
    ],
    version = 15,
    exportSchema = false,
)
abstract class TuneMindDatabase : RoomDatabase() {

    abstract fun eventQueueDao(): EventQueueDao
    abstract fun studyDao(): StudyDao
    abstract fun experimentDao(): ExperimentDao
    abstract fun audioDeviceDao(): AudioDeviceDao
    abstract fun musicProviderDao(): MusicProviderDao
    abstract fun aiRecommendationDao(): com.tunemind.app.domain.recommendation.AiRecommendationDao
    abstract fun spotifyListeningHistoryDao(): SpotifyListeningHistoryDao
    abstract fun spotifyLibraryDao(): SpotifyLibraryDao
    abstract fun spotifyVisualDao(): SpotifyVisualDao

    companion object {
        private const val DATABASE_NAME = "tunemind.db"

        @Volatile
        private var INSTANCE: TuneMindDatabase? = null

        fun getInstance(context: Context): TuneMindDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: try {
                    Room.databaseBuilder(
                        context.applicationContext,
                        TuneMindDatabase::class.java,
                        DATABASE_NAME,
                    ).addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_5_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15
                    )
                        .fallbackToDestructiveMigration()
                        .build().also { INSTANCE = it }
                } catch (e: Throwable) {
                    android.util.Log.e("TuneMindDB", "Database initialization fallback triggered: ${e.message}")
                    Room.databaseBuilder(
                        context.applicationContext,
                        TuneMindDatabase::class.java,
                        DATABASE_NAME,
                    ).fallbackToDestructiveMigration()
                        .build().also { INSTANCE = it }
                }
            }
        }

        fun resetDatabase(context: Context) {
            try {
                INSTANCE?.close()
                INSTANCE = null
                context.deleteDatabase(DATABASE_NAME)
            } catch (e: Exception) {
                android.util.Log.e("TuneMindDB", "Error resetting database", e)
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

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS ai_recommendation_crate")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS ai_recommendation_crate (
                        id TEXT PRIMARY KEY NOT NULL,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        album TEXT,
                        category TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        timeSlotKey TEXT NOT NULL,
                        timeSlotLabel TEXT NOT NULL,
                        recommendedAtMs INTEGER NOT NULL,
                        suggestedProviderId TEXT NOT NULL,
                        isSaved INTEGER NOT NULL,
                        wasPlayed INTEGER NOT NULL,
                        telemetryBasisSummary TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_recommendation_crate_timeSlotKey ON ai_recommendation_crate(timeSlotKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_recommendation_crate_recommendedAtMs ON ai_recommendation_crate(recommendedAtMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_recommendation_crate_isSaved ON ai_recommendation_crate(isSaved)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_5_6.migrate(db)
            }
        }

        val MIGRATION_5_7 = object : Migration(5, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_5_6.migrate(db)
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS ai_recommendation_crate")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS ai_recommendation_crate (
                        id TEXT PRIMARY KEY NOT NULL,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        album TEXT,
                        category TEXT NOT NULL,
                        subTier TEXT NOT NULL DEFAULT 'CORE_DOPAMINE',
                        reason TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        timeSlotKey TEXT NOT NULL,
                        timeSlotLabel TEXT NOT NULL,
                        recommendedAtMs INTEGER NOT NULL,
                        suggestedProviderId TEXT NOT NULL,
                        isSaved INTEGER NOT NULL,
                        wasPlayed INTEGER NOT NULL,
                        isDismissed INTEGER NOT NULL DEFAULT 0,
                        playCount INTEGER NOT NULL DEFAULT 0,
                        completedListenCount INTEGER NOT NULL DEFAULT 0,
                        lastInteractedAtMs INTEGER,
                        telemetryBasisSummary TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_ai_rec_crate_title_artist ON ai_recommendation_crate(title, artist)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_recommendation_crate_timeSlotKey ON ai_recommendation_crate(timeSlotKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_recommendation_crate_recommendedAtMs ON ai_recommendation_crate(recommendedAtMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_recommendation_crate_isSaved ON ai_recommendation_crate(isSaved)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_recommendation_crate_isDismissed ON ai_recommendation_crate(isDismissed)")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS spotify_listening_history (
                        history_id TEXT PRIMARY KEY NOT NULL,
                        track_id TEXT NOT NULL,
                        spotify_uri TEXT NOT NULL,
                        track_title TEXT NOT NULL,
                        artist_name TEXT NOT NULL,
                        artist_ids_csv TEXT NOT NULL DEFAULT '',
                        album_name TEXT NOT NULL DEFAULT '',
                        album_art_url TEXT,
                        duration_ms INTEGER NOT NULL DEFAULT 0,
                        popularity INTEGER NOT NULL DEFAULT 0,
                        played_at_iso TEXT NOT NULL,
                        played_at_epoch_ms INTEGER NOT NULL,
                        ingestion_source TEXT NOT NULL DEFAULT 'RECENTLY_PLAYED',
                        context_uri TEXT,
                        synced_at_epoch_ms INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_spotify_listening_history_played_at_epoch_ms ON spotify_listening_history(played_at_epoch_ms DESC)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_spotify_listening_history_track_id ON spotify_listening_history(track_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_spotify_listening_history_track_title_artist_name ON spotify_listening_history(track_title, artist_name)")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS track_history_telemetry (
                        telemetry_id TEXT PRIMARY KEY NOT NULL,
                        track_id TEXT NOT NULL,
                        spotify_uri TEXT NOT NULL,
                        track_title TEXT NOT NULL,
                        artist_name TEXT NOT NULL,
                        album_name TEXT NOT NULL DEFAULT '',
                        album_art_url TEXT,
                        duration_ms INTEGER NOT NULL DEFAULT 0,
                        played_at_iso TEXT NOT NULL,
                        played_at_epoch_ms INTEGER NOT NULL,
                        context_uri TEXT,
                        ingested_at_epoch_ms INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_track_history_telemetry_played_at_epoch_ms ON track_history_telemetry(played_at_epoch_ms DESC)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_track_history_telemetry_track_id ON track_history_telemetry(track_id)")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS spotify_liked_tracks (
                        track_id TEXT PRIMARY KEY NOT NULL,
                        spotify_uri TEXT NOT NULL,
                        track_title TEXT NOT NULL,
                        artist_name TEXT NOT NULL,
                        album_name TEXT NOT NULL DEFAULT '',
                        album_art_url TEXT,
                        duration_ms INTEGER NOT NULL DEFAULT 0,
                        added_at_iso TEXT NOT NULL DEFAULT '',
                        added_at_epoch_ms INTEGER NOT NULL DEFAULT 0,
                        synced_at_epoch_ms INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_spotify_liked_tracks_added_at_epoch_ms ON spotify_liked_tracks(added_at_epoch_ms DESC)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS spotify_playlists (
                        playlist_id TEXT PRIMARY KEY NOT NULL,
                        uri TEXT NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        owner_name TEXT NOT NULL DEFAULT '',
                        image_url TEXT,
                        track_count INTEGER NOT NULL DEFAULT 0,
                        tracks_json TEXT NOT NULL DEFAULT '[]',
                        synced_at_epoch_ms INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_spotify_playlists_synced_at_epoch_ms ON spotify_playlists(synced_at_epoch_ms DESC)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS spotify_podcasts (
                        item_id TEXT PRIMARY KEY NOT NULL,
                        show_id TEXT NOT NULL,
                        show_name TEXT NOT NULL,
                        publisher TEXT NOT NULL DEFAULT '',
                        episode_title TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        uri TEXT NOT NULL,
                        image_url TEXT,
                        duration_ms INTEGER NOT NULL DEFAULT 0,
                        resume_position_ms INTEGER NOT NULL DEFAULT 0,
                        fully_played INTEGER NOT NULL DEFAULT 0,
                        release_date TEXT NOT NULL DEFAULT '',
                        total_episodes INTEGER NOT NULL DEFAULT 0,
                        is_show_summary INTEGER NOT NULL DEFAULT 0,
                        added_at_epoch_ms INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_spotify_podcasts_added_at_epoch_ms ON spotify_podcasts(added_at_epoch_ms DESC)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_spotify_podcasts_show_id ON spotify_podcasts(show_id)")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_recommendation_crate ADD COLUMN genreTag TEXT NOT NULL DEFAULT 'Discovery'")
                db.execSQL("ALTER TABLE ai_recommendation_crate ADD COLUMN dropDateKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE ai_recommendation_crate ADD COLUMN matchScorePercent INTEGER NOT NULL DEFAULT 92")
                db.execSQL("ALTER TABLE ai_recommendation_crate ADD COLUMN avgVolumeUsedRatio REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE ai_recommendation_crate ADD COLUMN listenedDurationMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE ai_recommendation_crate ADD COLUMN trackDurationMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE ai_recommendation_crate ADD COLUMN isWildcard INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE ai_recommendation_crate ADD COLUMN spotifyUri TEXT")
                db.execSQL("ALTER TABLE ai_recommendation_crate ADD COLUMN previewUrl TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_recommendation_crate_dropDateKey ON ai_recommendation_crate(dropDateKey)")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS spotify_track_visual_cache (
                        track_key TEXT PRIMARY KEY NOT NULL,
                        spotify_track_id TEXT,
                        spotify_uri TEXT,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        artist_id TEXT,
                        album_name TEXT,
                        album_art_url TEXT,
                        artist_image_url TEXT,
                        genres_csv TEXT NOT NULL,
                        popularity INTEGER NOT NULL,
                        updated_at_ms INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_spotify_track_visual_cache_spotify_uri ON spotify_track_visual_cache(spotify_uri)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_spotify_track_visual_cache_updated_at_ms ON spotify_track_visual_cache(updated_at_ms)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS spotify_artist_profile_cache (
                        artist_key TEXT PRIMARY KEY NOT NULL,
                        artist_id TEXT,
                        artist_name TEXT NOT NULL,
                        image_url TEXT,
                        genres_csv TEXT NOT NULL,
                        popularity INTEGER NOT NULL,
                        followers_total INTEGER NOT NULL,
                        updated_at_ms INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_spotify_artist_profile_cache_artist_id ON spotify_artist_profile_cache(artist_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_spotify_artist_profile_cache_updated_at_ms ON spotify_artist_profile_cache(updated_at_ms)")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_recommendation_crate ADD COLUMN trackId TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS musical_moments")
            }
        }
    }
}
