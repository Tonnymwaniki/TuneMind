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

    @Test
    fun testStrictNeverLeaveFallsBackToFullInAppYouTubeBeforePreview() {
        assertTrue(
            MusicPlayerAutoLauncher.shouldFallbackToBuiltInYouTubeInStrictMode(
                strictNoSwitch = true,
                ytmVideoId = "WcIcVapfqXw"
            )
        )
        assertFalse(
            MusicPlayerAutoLauncher.shouldFallbackToBuiltInYouTubeInStrictMode(
                strictNoSwitch = false,
                ytmVideoId = "WcIcVapfqXw"
            )
        )
        assertFalse(
            MusicPlayerAutoLauncher.shouldFallbackToBuiltInYouTubeInStrictMode(
                strictNoSwitch = true,
                ytmVideoId = null
            )
        )
        assertEquals(
            "WcIcVapfqXw",
            UniversalTrackResolver.extractYouTubeVideoIdFromUrl("https://music.youtube.com/watch?v=WcIcVapfqXw&feature=share")
        )
        assertEquals(
            "dQw4w9WgXcQ",
            UniversalTrackResolver.extractYouTubeVideoIdFromUrl("https://youtu.be/dQw4w9WgXcQ")
        )
    }

    @Test
    fun testStalledRecoveryBannerSuppressedWhenExternalPlayerIsActivelyPlaying() {
        val attempt = MusicPlayerAutoLauncher.StalledPlaybackAttempt(
            title = "Calm Down",
            artist = "Rema",
            targetPackage = "com.spotify.music",
            playerDisplayName = "Spotify",
            ytmVideoId = null
        )
        // False alarm prevented: when Spotify is actually playing or buffering, never show "didn't auto-play".
        assertFalse(
            MusicPlayerAutoLauncher.shouldShowStalledRecoveryForAttempt(
                attempt = attempt,
                isTargetPlayingOrBuffering = true
            )
        )
        // Genuine stall: Spotify remained paused/on Search, show recovery option cleanly.
        assertTrue(
            MusicPlayerAutoLauncher.shouldShowStalledRecoveryForAttempt(
                attempt = attempt,
                isTargetPlayingOrBuffering = false
            )
        )
        assertFalse(
            MusicPlayerAutoLauncher.shouldShowStalledRecoveryForAttempt(
                attempt = null,
                isTargetPlayingOrBuffering = false
            )
        )
    }

    @Test
    fun testAgentToolCallAndOsCommandPreserveCrossAppPlayableFields() {
        val toolCall = com.tunemind.app.domain.execution.AgentToolCallPayload(
            action = "LIKE_TRACK",
            track_title = "Calm Down",
            track_artist = "Rema",
            track_id = "4iV5W9uYEdYUVa79Axb7Rh",
            spotify_uri = "spotify:track:4iV5W9uYEdYUVa79Axb7Rh",
            ytm_video_id = "WcIcVapfqXw",
            deezer_url = "https://www.deezer.com/track/12345",
            target_player = "com.spotify.music",
            playlist_name = "Late Night",
            new_playlist_name = "Midnight Vibes"
        )
        assertEquals("LIKE_TRACK", toolCall.action)
        assertEquals("spotify:track:4iV5W9uYEdYUVa79Axb7Rh", toolCall.spotify_uri)
        assertEquals("WcIcVapfqXw", toolCall.ytm_video_id)
        assertEquals("Midnight Vibes", toolCall.new_playlist_name)

        val osCmd = com.tunemind.app.domain.execution.OsExecutionCommand(
            intent = "OS_MEDIA_NEXT_AND_DEEP_LINK",
            target_player = "com.spotify.music",
            search_query = "Calm Down Rema",
            track_title = "Calm Down",
            track_artist = "Rema",
            track_id = "4iV5W9uYEdYUVa79Axb7Rh",
            spotify_uri = "spotify:track:4iV5W9uYEdYUVa79Axb7Rh",
            ytm_video_id = "WcIcVapfqXw",
            deezer_url = "https://www.deezer.com/track/12345"
        )
        assertEquals("OS_MEDIA_NEXT_AND_DEEP_LINK", osCmd.intent)
        assertEquals("spotify:track:4iV5W9uYEdYUVa79Axb7Rh", osCmd.spotify_uri)
        assertEquals("WcIcVapfqXw", osCmd.ytm_video_id)
    }

    @Test
    fun testSpotifyPremiumConnectPkceAndAuthorizeUrl() {
        // RFC 7636 Appendix B known test vector for S256 code challenge
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        assertEquals(expectedChallenge, SpotifyPremiumConnectManager.generateCodeChallenge(verifier))

        assertTrue(SpotifyPremiumConnectManager.isValidClientIdFormat("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6"))
        assertFalse(SpotifyPremiumConnectManager.isValidClientIdFormat(""))
        assertFalse(SpotifyPremiumConnectManager.isValidClientIdFormat("short"))
        assertFalse(SpotifyPremiumConnectManager.isValidClientIdFormat("has spaces inside id 123456"))

        // Effective Client ID: personal BYOC overrides app default; falls back to app default for 1-Tap consumer connect
        assertEquals(
            "11112222333344445555666677778888",
            SpotifyPremiumConnectManager.resolveEffectiveClientId(
                customClientId = "11112222333344445555666677778888",
                appDefaultClientId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6"
            )
        )
        assertEquals(
            "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6",
            SpotifyPremiumConnectManager.resolveEffectiveClientId(
                customClientId = "",
                appDefaultClientId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6"
            )
        )
        assertEquals(
            "",
            SpotifyPremiumConnectManager.resolveEffectiveClientId(
                customClientId = "bad",
                appDefaultClientId = ""
            )
        )

        val authUrl = SpotifyPremiumConnectManager.buildAuthorizeUrl(
            clientId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6",
            codeChallenge = expectedChallenge,
            state = "state123"
        )
        assertTrue(authUrl.startsWith("https://accounts.spotify.com/authorize?"))
        assertTrue(authUrl.contains("client_id=a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6"))
        assertTrue(authUrl.contains("response_type=code"))
        assertTrue(authUrl.contains("code_challenge_method=S256"))
        assertTrue(authUrl.contains("code_challenge=$expectedChallenge"))
        assertTrue(authUrl.contains("redirect_uri=tunemind%3A%2F%2Fspotify-callback"))
        assertTrue(authUrl.contains("user-modify-playback-state"))
        assertTrue(authUrl.contains("user-library-modify"))
    }

    @Test
    fun testSpotifyPremiumConnectTierRouting() {
        assertTrue(
            MusicPlayerAutoLauncher.shouldTrySpotifyPremiumConnect(
                targetPkg = "com.spotify.music",
                providerId = "spotify",
                stayInTuneMind = true,
                strictNoSwitch = false,
                isSpotifyPremiumConnected = true
            )
        )
        assertTrue(
            MusicPlayerAutoLauncher.shouldTrySpotifyPremiumConnect(
                targetPkg = "com.spotify.lite",
                providerId = "spotify",
                stayInTuneMind = false,
                strictNoSwitch = true,
                isSpotifyPremiumConnected = true
            )
        )
        assertFalse(
            MusicPlayerAutoLauncher.shouldTrySpotifyPremiumConnect(
                targetPkg = "com.spotify.music",
                providerId = "spotify",
                stayInTuneMind = true,
                strictNoSwitch = true,
                isSpotifyPremiumConnected = false
            )
        )
        assertFalse(
            MusicPlayerAutoLauncher.shouldTrySpotifyPremiumConnect(
                targetPkg = "com.afmobi.boomplaymusic",
                providerId = "boomplay",
                stayInTuneMind = true,
                strictNoSwitch = true,
                isSpotifyPremiumConnected = true
            )
        )
    }
}
