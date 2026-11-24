package com.tunemind.app.ui.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TuneMindErrorMapperTest {

    @Test
    fun mapsMicAndAudioRecordErrorsToAllowMicAction() {
        val mapped = TuneMindErrorMapper.map("AudioRecord start failed", contextHint = "voice")
        assertEquals(TuneMindBannerAction.ALLOW_MIC_PERMISSION, mapped.action)
        assertEquals(BannerSeverity.WARNING, mapped.severity)
        assertEquals("Microphone unavailable", mapped.title)
    }

    @Test
    fun mapsSpotifyTokenExpiryToReconnectAction() {
        val mapped = TuneMindErrorMapper.map("Spotify session expired — reconnect in Settings.")
        assertEquals(TuneMindBannerAction.RECONNECT_SPOTIFY, mapped.action)
        assertEquals("Reconnect Spotify", mapped.title)
    }

    @Test
    fun mapsNetworkTimeoutWithoutLeakingStackTrace() {
        val raw = "java.net.SocketTimeoutException: failed to connect to api.example.com at com.tunemind.Foo.bar(Foo.kt:42)"
        val mapped = TuneMindErrorMapper.map(raw, onRetry = {})
        assertEquals(TuneMindBannerAction.RETRY, mapped.action)
        assertFalse(mapped.message.contains("SocketTimeoutException"))
        assertFalse(mapped.message.contains("com.tunemind"))
    }

    @Test
    fun sanitizesRawTechnicalExceptions() {
        val raw = "NullPointerException at com.tunemind.app.SomeClass(SomeClass.kt:19)"
        val mapped = TuneMindErrorMapper.map(raw, contextHint = "sync")
        assertEquals("Sync paused", mapped.title)
        assertTrue(mapped.message.isNotBlank())
        assertFalse(mapped.message.contains("NullPointerException"))
        assertFalse(mapped.message.contains("com.tunemind"))
    }

    @Test
    fun mapsSpotifyPremiumRequiredToFriendlyFreeTierNotice() {
        val mapped = TuneMindErrorMapper.map("Spotify Premium is required for silent context playback.")
        assertEquals(BannerSeverity.INFO, mapped.severity)
        assertEquals("Spotify Free account detected", mapped.title)
    }

    @Test
    fun mapsExpiredTuneMindAuthSessionToSignInExpiredInsteadOfVoiceDropped() {
        val mapped = TuneMindErrorMapper.map(
            "Session expired or unauthorized — open Profile → Log Out and sign back in.",
            contextHint = "voice"
        )
        assertEquals(BannerSeverity.WARNING, mapped.severity)
        assertEquals("Sign-in expired", mapped.title)
        assertTrue(mapped.message.contains("Log Out"))
    }
}
