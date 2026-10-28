package com.tunemind.app.provider

import org.junit.Assert.*
import org.junit.Test

class ProviderModelTest {

    @Test
    fun testTrackMatchDataIntegrity() {
        val match = TrackMatch(
            trackId = "spotify:track:4cOdK2wGLETKBW3PvgPWqT",
            title = "Nevermind",
            artist = "Dennis Lloyd",
            album = "Nevermind",
            providerId = "spotify",
            deepLinkUri = "spotify:track:4cOdK2wGLETKBW3PvgPWqT",
            webFallbackUrl = "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT",
            confidence = 0.95f
        )

        assertEquals("spotify", match.providerId)
        assertEquals("Nevermind", match.title)
        assertTrue(match.isDirectPlayable())
    }

    @Test
    fun testPlaybackLaunchResultVariations() {
        val successDirect = PlaybackLaunchResult.DirectPlaybackStarted(
            providerId = "spotify",
            trackTitle = "Resonance",
            deepLink = "spotify:track:123"
        )
        assertTrue(successDirect.isSuccess)
        assertTrue(successDirect.message.contains("direct playback"))

        val searchHandoff = PlaybackLaunchResult.SearchHandoffOpened(
            providerId = "youtube_music",
            query = "Tycho Awake"
        )
        assertTrue(searchHandoff.isSuccess)
        assertTrue(searchHandoff.message.contains("search"))

        val failed = PlaybackLaunchResult.Failed(
            providerId = "spotify",
            reason = "App not installed and browser unavailable"
        )
        assertFalse(failed.isSuccess)
    }
}
