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

    @Test
    fun testBuiltInYouTubeTierIsAudienceGatedToYouTubeMusicOnly() {
        // Option A activates when target/default is YouTube Music and in-app playback is preferred/strict
        assertTrue(
            MusicPlayerAutoLauncher.shouldActivateBuiltInYouTubeTier(
                targetPkg = YouTubeMusicProvider.YTM_PACKAGE,
                providerId = YouTubeMusicProvider.PROVIDER_ID,
                ytmVideoId = "dQw4w9WgXcQ",
                preferInAppPlayer = true,
                strictNoSwitch = false
            )
        )
        assertTrue(
            MusicPlayerAutoLauncher.shouldActivateBuiltInYouTubeTier(
                targetPkg = YouTubeMusicProvider.YTM_PACKAGE,
                providerId = "youtube_music",
                ytmVideoId = "abc123",
                preferInAppPlayer = false,
                strictNoSwitch = true
            )
        )

        // Never silently hijacks Spotify, Boomplay, or Audiomack users into YouTube without permission
        assertFalse(
            MusicPlayerAutoLauncher.shouldActivateBuiltInYouTubeTier(
                targetPkg = "com.spotify.music",
                providerId = "spotify",
                ytmVideoId = "abc123",
                preferInAppPlayer = true,
                strictNoSwitch = true
            )
        )
        assertFalse(
            MusicPlayerAutoLauncher.shouldActivateBuiltInYouTubeTier(
                targetPkg = "com.afmobi.boomplaymusic",
                providerId = "boomplay",
                ytmVideoId = "abc123",
                preferInAppPlayer = true,
                strictNoSwitch = true
            )
        )
        assertFalse(
            MusicPlayerAutoLauncher.shouldActivateBuiltInYouTubeTier(
                targetPkg = "com.audiomack",
                providerId = "audiomack",
                ytmVideoId = "abc123",
                preferInAppPlayer = true,
                strictNoSwitch = false
            )
        )
        // Missing videoId or disabled both in-app & strict mode -> do not activate
        assertFalse(
            MusicPlayerAutoLauncher.shouldActivateBuiltInYouTubeTier(
                targetPkg = YouTubeMusicProvider.YTM_PACKAGE,
                providerId = "youtube_music",
                ytmVideoId = null,
                preferInAppPlayer = true,
                strictNoSwitch = true
            )
        )
        assertFalse(
            MusicPlayerAutoLauncher.shouldActivateBuiltInYouTubeTier(
                targetPkg = YouTubeMusicProvider.YTM_PACKAGE,
                providerId = "youtube_music",
                ytmVideoId = "abc123",
                preferInAppPlayer = false,
                strictNoSwitch = false
            )
        )
    }

    @Test
    fun testSpotifyTrackUriExtractionFromSonglinkOrUri() {
        val fromUrl = MusicPlayerAutoLauncher.extractSpotifyTrackUri(
            candidateUri = "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT?si=abc123"
        )
        assertEquals("spotify:track:4cOdK2wGLETKBW3PvgPWqT", fromUrl)

        val fromResolved = MusicPlayerAutoLauncher.extractSpotifyTrackUri(
            candidateUri = "https://www.deezer.com/track/12345",
            resolvedLinks = ResolvedPlayableLinks(
                canonicalTrackKey = "k",
                trackId = "t",
                cleanTitle = "Song",
                cleanArtist = "Artist",
                spotifyUri = "spotify:track:4iV5W9uYEdYUVa79Axb7Rh"
            )
        )
        assertEquals("spotify:track:4iV5W9uYEdYUVa79Axb7Rh", fromResolved)

        val searchOnly = MusicPlayerAutoLauncher.extractSpotifyTrackUri(
            candidateUri = "https://open.spotify.com/search/Calm%20Down"
        )
        assertNull(searchOnly)
    }
}
