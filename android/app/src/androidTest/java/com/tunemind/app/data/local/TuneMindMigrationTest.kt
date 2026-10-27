package com.tunemind.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TuneMindMigrationTest {
    @Test
    fun migratesV1RowsAndPreservesPayloadAndOrdering() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v1-test.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        val legacyDb = SQLiteDatabase.openOrCreateDatabase(path, null)
        legacyDb.execSQL("""CREATE TABLE event_queue (
            observation_id TEXT NOT NULL PRIMARY KEY,
            payload_json TEXT NOT NULL,
            synced INTEGER NOT NULL,
            created_at INTEGER NOT NULL
        )""")
        legacyDb.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        legacyDb.execSQL("INSERT INTO room_master_table (id, identity_hash) VALUES(42, '042fe70b79e0fe03582338af89d1a061')")
        legacyDb.execSQL("""INSERT INTO event_queue VALUES (
            'legacy-id',
            '{"observationId":"legacy-id","sessionId":"legacy-session","mediaSource":null,"eventType":"METADATA_CHANGED","observedAt":100,"elapsedRealtimeMs":null,"trackTitle":"Legacy","trackArtist":null,"trackAlbum":null,"positionMs":null,"isPlaying":null,"playbackSpeed":null}',
            0,
            100
        )""")
        legacyDb.version = 1
        legacyDb.close()

        val migrated = Room.databaseBuilder(context, TuneMindDatabase::class.java, name)
            .addMigrations(TuneMindDatabase.MIGRATION_1_2)
            .build()
        try {
            val row = migrated.eventQueueDao().observeRecent(10).first().single()
            assertEquals("legacy-id", row.eventId)
            assertEquals(1L, row.sequenceNumber)
            assertEquals(100L, row.eventTimeMs)
            assertEquals(100L, row.savedAtMs) // Legacy had no save timestamp; old created_at is fallback.
            assertEquals("Legacy", PlaybackObservationCodec.decode(row.payloadJson, row.savedAtMs).trackTitle)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
