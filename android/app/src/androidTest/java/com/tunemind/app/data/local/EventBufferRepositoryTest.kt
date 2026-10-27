package com.tunemind.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tunemind.app.domain.event.ObservationEventType
import com.tunemind.app.domain.model.PlaybackObservation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventBufferRepositoryTest {
    private lateinit var database: TuneMindDatabase
    private lateinit var repository: EventBufferRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            TuneMindDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = EventBufferRepository(database.eventQueueDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun persistsVersionedObservationAndIgnoresDuplicateEventId() = runBlocking {
        val observation = observation("same-event", eventTimeMs = 300L)
        repository.record(observation)
        repository.record(observation.copy(trackTitle = "changed payload with same ID"))

        assertEquals(1, repository.observeCount().first())
        val saved = repository.observeRecent().first().single()
        assertEquals("Track", saved.trackTitle)
        assertEquals(1, saved.schemaVersion)
        assertNotNull(saved.savedAtMs)
        assertNull(saved.playbackStateCode)
    }

    @Test
    fun databaseSequenceOrdersByAcceptedInsertNotWallClockAndIncreases() = runBlocking {
        repository.record(observation("later-event-time", eventTimeMs = 900L))
        repository.record(observation("earlier-event-time", eventTimeMs = 100L))

        val newestFirst = database.eventQueueDao().observeRecent(10).first()
        assertEquals("earlier-event-time", newestFirst[0].eventId)
        assertEquals("later-event-time", newestFirst[1].eventId)
        assertEquals(true, newestFirst[0].sequenceNumber > newestFirst[1].sequenceNumber)
        assertEquals(listOf(100L, 900L), repository.observeRecent().first().map { it.eventTimeMs })
    }

    @Test
    fun retentionRemainsAtFiveHundredRecords() = runBlocking {
        repeat(EventBufferRepository.MAX_OBSERVATIONS + 1) { index ->
            repository.record(observation("event-$index", eventTimeMs = index.toLong()))
        }
        assertEquals(EventBufferRepository.MAX_OBSERVATIONS, repository.observeCount().first())
    }

    @Test
    fun clearRemovesAllLocalObservations() = runBlocking {
        repository.record(observation("clear-me", 100L))
        repository.clear()
        assertEquals(0, repository.observeCount().first())
    }

    private fun observation(eventId: String, eventTimeMs: Long) = PlaybackObservation(
        eventId = eventId,
        observerSessionId = "local-session",
        eventType = ObservationEventType.METADATA_CHANGED,
        eventTimeMs = eventTimeMs,
        sourcePackage = "com.example.player",
        trackTitle = "Track",
        trackArtist = "Artist",
        playbackStateCode = android.media.session.PlaybackState.STATE_PAUSED,
        positionMs = null,
        playbackSpeed = null,
        positionUpdateElapsedRealtimeMs = null,
    )
}
