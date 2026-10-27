package com.tunemind.app

import com.tunemind.app.media.CallbackDeduplicator
import com.tunemind.app.media.ObservationIdentity
import java.util.UUID
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationIdentityTest {
    @Test
    fun acceptedObservationsReceiveIndependentRandomIds() {
        val first = ObservationIdentity.newEventId()
        val second = ObservationIdentity.newEventId()
        assertTrue(runCatching { UUID.fromString(first) }.isSuccess)
        assertTrue(runCatching { UUID.fromString(second) }.isSuccess)
        assertNotEquals(first, second)
    }

    @Test
    fun onlyIdenticalSnapshotsInsideTheDeduplicationWindowAreSuppressed() {
        val deduplicator = CallbackDeduplicator(duplicateWindowMs = 1_000L)
        assertTrue(deduplicator.shouldAccept("state-position-100", 10_000L))
        assertTrue(!deduplicator.shouldAccept("state-position-100", 10_500L))
        assertTrue(deduplicator.shouldAccept("state-position-200", 10_600L))
        assertTrue(deduplicator.shouldAccept("state-position-100", 11_100L))
    }
}
