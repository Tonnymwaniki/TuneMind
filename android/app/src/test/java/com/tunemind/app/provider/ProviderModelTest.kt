package com.tunemind.app.provider

import org.junit.Assert.*
import org.junit.Test

class ProviderModelTest {

    @Test
    fun testTrackMatchDataIntegrity() {
        val match = TrackMatch(
            providerId = "spotify",
            providerDisplayName = "Spotify",
            title = "Nevermind",
            artist = "Dennis Lloyd",
            providerTrackId = "4cOdK2wGLETKBW3PvgPWqT",
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
        val successDirect = PlaybackLaunchResult.DirectPlaybackLaunched(
            providerId = "spotify",
            providerDisplayName = "Spotify",
            trackTitle = "Resonance",
            destinationUri = "spotify:track:123",
            message = "Starting Resonance on Spotify (direct playback)"
        )
        assertTrue(successDirect.isSuccess)
        assertTrue(successDirect.message.contains("direct playback"))

        val searchHandoff = PlaybackLaunchResult.SearchHandoffLaunched(
            providerId = "youtube_music",
            providerDisplayName = "YouTube Music",
            query = "Tycho Awake",
            message = "Opening YouTube Music search for \"Tycho Awake\""
        )
        assertTrue(searchHandoff.isSuccess)
        assertTrue(searchHandoff.message.contains("search"))

        val failed = PlaybackLaunchResult.LaunchFailed(
            providerId = "spotify",
            reason = "App not installed and browser unavailable"
        )
        assertFalse(failed.isSuccess)
        assertEquals("App not installed and browser unavailable", failed.message)
    }
}
