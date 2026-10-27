package com.tunemind.app.domain.audio

import org.junit.Assert.*
import org.junit.Test

class AuditoryAnchorSnapshotTest {

    @Test
    fun `unavailable instance has correct availability`() {
        val snapshot = AuditoryAnchorSnapshot.unavailable
        assertEquals(AuditoryAvailability.UNAVAILABLE, snapshot.availability)
        assertNotNull(snapshot.provenanceSource)
    }

    @Test
    fun `insufficient instance has correct availability`() {
        val snapshot = AuditoryAnchorSnapshot.insufficient
        assertEquals(AuditoryAvailability.INSUFFICIENT_DATA, snapshot.availability)
    }

    @Test
    fun `all feature fields default to null`() {
        val snapshot = AuditoryAnchorSnapshot.unavailable
        assertNull(snapshot.rhythmBpm)
        assertNull(snapshot.rhythmConfidence)
        assertNull(snapshot.melodyVocalPresence)
        assertNull(snapshot.structuralComplexity)
        assertNull(snapshot.spectralCentroidHz)
        assertNull(snapshot.spectralRolloffHz)
        assertNull(snapshot.zeroCrossingRate)
        assertNull(snapshot.energyRms)
    }

    @Test
    fun `version is set correctly`() {
        assertEquals(1, AuditoryAnchorSnapshot.AUDITORY_ANCHOR_VERSION)
        assertEquals(1, AuditoryAnchorSnapshot.unavailable.version)
    }

    @Test
    fun `custom snapshot preserves provided values`() {
        val snapshot = AuditoryAnchorSnapshot(
            availability = AuditoryAvailability.AVAILABLE,
            provenanceMs = 1_000_000_000_000L,
            provenanceSource = "Test source",
            rhythmBpm = 120.0,
            rhythmConfidence = 0.85,
            version = 1,
        )
        assertEquals(AuditoryAvailability.AVAILABLE, snapshot.availability)
        assertEquals(1_000_000_000_000L, snapshot.provenanceMs)
        assertEquals(120.0, snapshot.rhythmBpm!!, 1e-9)
        assertEquals(0.85, snapshot.rhythmConfidence!!, 1e-9)
    }

    @Test
    fun `provenance stores extraction metadata`() {
        val provenance = AuditoryFeatureProvenance(
            audioSource = "MediaProjection capture",
            extractorName = "LibrosaPort",
            extractorVersion = "0.1.0",
            segmentStartMs = 30_000L,
            segmentDurationMs = 10_000L,
            sampleRateHz = 22050,
        )
        assertEquals("MediaProjection capture", provenance.audioSource)
        assertEquals(22050, provenance.sampleRateHz)
    }

    @Test
    fun `unavailable snapshot provenance source explains limitation`() {
        val snapshot = AuditoryAnchorSnapshot.unavailable
        assertTrue(snapshot.provenanceSource!!.contains("does not have access"))
    }
}
