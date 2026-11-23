package com.tunemind.app.provider

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tunemind.app.R
import com.tunemind.app.domain.recommendation.RecommendedTrack
import com.tunemind.app.domain.voice.AgentSettingsStore
import com.tunemind.app.service.TuneMindListenerService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cross-App Hybrid Playback Engine across all Android music players
 * (Boomplay, YouTube Music, Audiomack, Spotify passive, Deezer, SoundCloud,
 * local device audio, and TuneMind's built-in audio engine).
 *
 * Hybrid Tiered Strategy (zero Spotify Developer keys / OAuth / App Remote SDK / BAL overlay hacks):
 * 1A. Local Audio (`MediaStore.Audio.Media`) -> Full track inside [TuneMindInAppPlayer] (zero switch).
 * 1B. Open-Catalog Full Stream (Audius API) -> Full track inside [TuneMindInAppPlayer] (zero switch).
 * 1C. Audience-Gated Visible YouTube Embed -> Full track inside [TuneMindYouTubeEmbedController]
 *     ONLY when target/default is YouTube Music (or explicitly allowed in-app by the user).
 * 2. Active Warm MediaSession -> `playFromSearch(query, extras)` + title-only retry (zero switch).
 * 3. Strict Never-Leave-TuneMind Mode -> 30s iTunes/Deezer preview inside [TuneMindInAppPlayer]
 *    plus metadata for "Open full track in [Player]" (or YouTube in-app prompt).
 * 4. Hybrid Cold Handoff (when strict mode is OFF) -> Exact YouTube Music watch URL / Boomplay /
 *    Audiomack / Spotify passive `MEDIA_PLAY_FROM_SEARCH` intent + a Play-safe Heads-Up
 *    "[Back to TuneMind]" banner that peeks cleanly and updates once playback starts.
 */
object MusicPlayerAutoLauncher {
    private const val TAG = "MusicPlayerAutoLauncher"
    private const val GOOGLE_ASSISTANT_REFERRER =
        "android-app://com.google.android.googlequicksearchbox/https/www.google.com"
    private const val RETURN_CHANNEL_ID = "tunemind_quick_return_banner_v2"
    private const val RETURN_NOTIFICATION_ID = 4409
    const val ACTION_RECOVER_PLAYBACK_IN_TUNEMIND = "com.tunemind.app.ACTION_RECOVER_PLAYBACK_IN_TUNEMIND"
    const val EXTRA_RECOVER_TITLE = "recover_title"
    const val EXTRA_RECOVER_ARTIST = "recover_artist"
    const val EXTRA_RECOVER_YTM_ID = "recover_ytm_id"
    const val EXTRA_RECOVER_PLAYER_NAME = "recover_player_name"

    data class StalledPlaybackAttempt(
        val title: String,
        val artist: String,
        val targetPackage: String?,
        val playerDisplayName: String,
        val ytmVideoId: String?,
        val createdAtMs: Long = System.currentTimeMillis()
    )

    @Volatile
    private var lastStalledPlaybackAttempt: StalledPlaybackAttempt? = null

    fun consumeFreshStalledPlaybackAttempt(maxAgeMs: Long = 90_000L): StalledPlaybackAttempt? {
        val current = lastStalledPlaybackAttempt ?: return null
        lastStalledPlaybackAttempt = null
        return if ((System.currentTimeMillis() - current.createdAtMs) <= maxAgeMs) current else null
    }

    internal fun shouldShowStalledRecoveryForAttempt(
        attempt: StalledPlaybackAttempt?,
        isTargetPlayingOrBuffering: Boolean
    ): Boolean {
        return attempt != null && !isTargetPlayingOrBuffering
    }

    internal fun extractSpotifyTrackUri(
        candidateUri: String?,
        resolvedLinks: ResolvedPlayableLinks? = null
    ): String? {
        val rawCandidates = listOfNotNull(
            resolvedLinks?.spotifyUri,
            resolvedLinks?.spotifyUrl,
            candidateUri
        )
        for (raw in rawCandidates) {
            val clean = raw.trim()
            if (clean.startsWith("spotify:track:", ignoreCase = true)) {
                val id = clean.substringAfter("spotify:track:").substringBefore("?").trim()
                if (Regex("^[A-Za-z0-9]{22}$").matches(id)) return "spotify:track:$id"
            }
            val urlMatch = Regex("/track/([A-Za-z0-9]{22})", RegexOption.IGNORE_CASE).find(clean)
            if (urlMatch != null) {
                return "spotify:track:${urlMatch.groupValues[1]}"
            }
        }
        return null
    }

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    var lastDispatchedTrackTitle: String = ""
        private set

    @Volatile
    var lastDispatchedAtMs: Long = 0L
        private set

    fun wasRecentlyDispatched(title: String?, windowMs: Long = 12_000L): Boolean {
        val clean = title?.trim().orEmpty()
        val last = lastDispatchedTrackTitle.trim()
        if (clean.isBlank() || last.isBlank()) return false
        if ((System.currentTimeMillis() - lastDispatchedAtMs) > windowMs) return false
        return clean.equals(last, ignoreCase = true) ||
            clean.contains(last, ignoreCase = true) ||
            last.contains(clean, ignoreCase = true)
    }

    /**
     * Strips noisy suffixes like ", Tenzi No 22.", "(Official Video)", "[Lyrics]", etc.
     */
    fun sanitizeTrackTitleForPlay(rawTitle: String): String {
        return rawTitle
            .replace(Regex("\\s*\\((official\\s*(music\\s*)?(video|audio|lyric\\s*video|visualizer)|lyrics?|audio|hd|4k|live.*?)\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[(official\\s*(music\\s*)?(video|audio|lyric\\s*video|visualizer)|lyrics?|audio|hd|4k|live.*?)\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex(",\\s*tenzi\\s*no\\.?\\s*\\d+\\.?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+-\\s+(official\\s*(music\\s*)?(video|audio)|lyrics?)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[.\"'`]+$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun sanitizeArtistForPlay(rawArtist: String): String {
        val cleaned = rawArtist.trim()
        if (cleaned.equals("Unknown Artist", ignoreCase = true) || cleaned.equals("Spotify", ignoreCase = true)) {
            return ""
        }
        return cleaned
            .split(Regex("[,&]|feat\\.?|ft\\.?", RegexOption.IGNORE_CASE))
            .firstOrNull()
            ?.replace(Regex("[.\"'`]+$"), "")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?: ""
    }

    /**
     * Opens the full track explicitly in the requested external player (e.g. from the
     * "Open full track in [Player]" button while listening to an in-app 30s preview).
     */
    fun openFullTrackExternally(
        context: Context,
        title: String,
        artist: String,
        packageName: String?,
        displayName: String,
        explicitUrlOrDeepLink: String? = null
    ): PlaybackLaunchResult {
        TuneMindInAppPlayer.pause()
        TuneMindYouTubeEmbedController.pause()
        val rec = RecommendedTrack(
            id = title,
            title = title,
            artist = artist,
            reason = "User requested full track in $displayName",
            confidence = 1.0f
        )
        val cleanTitle = sanitizeTrackTitleForPlay(title)
        val cleanArtist = sanitizeArtistForPlay(artist)
        val fullQuery = listOf(cleanTitle, cleanArtist).filter { it.isNotBlank() }.joinToString(" ")
        val outcome = launchExternalColdHandoff(
            context = context,
            targetPkg = packageName?.takeIf { it != "*" && it.isNotBlank() },
            displayName = displayName.ifBlank { "your music app" },
            track = rec,
            cleanTitle = cleanTitle,
            cleanArtist = cleanArtist,
            fullSearchQuery = fullQuery,
            deepLinkUri = explicitUrlOrDeepLink,
            previousTitle = null,
            resolvedLinks = UniversalTrackResolver.getCached(cleanTitle, cleanArtist)
        )
        return if (outcome.launched) {
            PlaybackLaunchResult.DirectPlaybackLaunched(
                providerId = packageName ?: "external",
                providerDisplayName = displayName,
                trackTitle = cleanTitle.ifBlank { title },
                destinationUri = outcome.destinationUri ?: "external:$packageName",
                message = outcome.detailMessage ?: "Opened '$cleanTitle' in $displayName."
            )
        } else {
            PlaybackLaunchResult.LaunchFailed(
                providerId = packageName ?: "external",
                reason = outcome.detailMessage ?: "Could not open '$cleanTitle' in $displayName."
            )
        }
    }

    fun launchWithAutoPlay(
        context: Context,
        providerId: String,
        displayName: String,
        packageName: String?,
        track: RecommendedTrack,
        query: String,
        deepLinkUri: String? = null,
        webFallbackUrl: String? = null,
        neverOpenExternalApp: Boolean = false
    ): PlaybackLaunchResult {
        val targetPkg = if (packageName == "*" || packageName.isNullOrBlank()) null else packageName
        val listener = TuneMindListenerService.instance
        val previousTitle = listener?.getActiveTitle(targetPkg)?.trim()

        // 0. Silence any currently playing song before switching
        try {
            TuneMindInAppPlayer.pause()
            TuneMindYouTubeEmbedController.pause()
            listener?.pause()
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        } catch (e: Exception) {
            Log.d(TAG, "Audio pause interruption note: ${e.message}")
        }

        val cleanTitle = sanitizeTrackTitleForPlay(track.title.ifBlank { query })
        val cleanArtist = sanitizeArtistForPlay(track.artist)
        lastDispatchedTrackTitle = cleanTitle.ifBlank { track.title.trim() }
        lastDispatchedAtMs = System.currentTimeMillis()
        val fullSearchQuery = if (cleanArtist.isNotBlank() && !cleanTitle.contains(cleanArtist, ignoreCase = true)) {
            "$cleanTitle $cleanArtist".trim()
        } else {
            cleanTitle.ifBlank { sanitizeTrackTitleForPlay(query) }
        }

        val outcome = launchHybridPlayback(
            context = context,
            providerId = providerId,
            targetPkg = targetPkg,
            displayName = displayName,
            track = track,
            cleanTitle = cleanTitle,
            cleanArtist = cleanArtist,
            fullSearchQuery = fullSearchQuery,
            deepLinkUri = deepLinkUri ?: webFallbackUrl,
            previousTitle = previousTitle,
            neverOpenExternalApp = neverOpenExternalApp
        )

        return if (outcome.launched) {
            PlaybackLaunchResult.DirectPlaybackLaunched(
                providerId = if (outcome.usedInAppPlayer) TuneMindInAppProvider.PROVIDER_ID else providerId,
                providerDisplayName = if (outcome.usedInAppPlayer) "TuneMind In-App Player" else displayName,
                trackTitle = cleanTitle.ifBlank { track.title },
                destinationUri = outcome.destinationUri ?: deepLinkUri ?: "intent:${targetPkg ?: "active"}",
                message = outcome.detailMessage ?: "Playing '${cleanTitle.ifBlank { track.title }}' cleanly."
            )
        } else {
            PlaybackLaunchResult.LaunchFailed(
                providerId = providerId,
                reason = outcome.detailMessage ?: "Could not start playback in $displayName."
            )
        }
    }

    private data class LaunchOutcome(
        val launched: Boolean,
        val usedInAppPlayer: Boolean = false,
        val destinationUri: String? = null,
        val detailMessage: String? = null
    )

    internal fun isYouTubeMusicTarget(targetPkg: String?, providerId: String?): Boolean {
        return targetPkg?.contains("youtube.music", ignoreCase = true) == true ||
            providerId.equals(YouTubeMusicProvider.PROVIDER_ID, ignoreCase = true)
    }

    internal fun shouldActivateBuiltInYouTubeTier(
        targetPkg: String?,
        providerId: String?,
        ytmVideoId: String?,
        preferInAppPlayer: Boolean,
        strictNoSwitch: Boolean
    ): Boolean {
        return (preferInAppPlayer || strictNoSwitch) &&
            isYouTubeMusicTarget(targetPkg, providerId) &&
            !ytmVideoId.isNullOrBlank()
    }

    internal fun shouldFallbackToBuiltInYouTubeInStrictMode(
        strictNoSwitch: Boolean,
        ytmVideoId: String?
    ): Boolean {
        return strictNoSwitch && !ytmVideoId.isNullOrBlank()
    }

    internal fun shouldTrySpotifyPremiumConnect(
        targetPkg: String?,
        providerId: String?,
        stayInTuneMind: Boolean,
        strictNoSwitch: Boolean,
        isSpotifyPremiumConnected: Boolean
    ): Boolean {
        if (!isSpotifyPremiumConnected) return false
        val pkgLower = targetPkg?.trim()?.lowercase().orEmpty()
        val idLower = providerId?.trim()?.lowercase().orEmpty()
        val isExplicitOtherExternalApp =
            pkgLower.contains("boomplay") || idLower == "boomplay" ||
                pkgLower.contains("audiomack") || idLower == "audiomack" ||
                pkgLower.contains("youtube") || idLower == "youtube_music" ||
                pkgLower.contains("deezer") || idLower == "deezer" ||
                pkgLower.contains("soundcloud") || idLower == "soundcloud"
        if (isExplicitOtherExternalApp) return false
        val isSpotifyOrInAppTarget = pkgLower.contains("spotify") ||
            idLower == "spotify" ||
            pkgLower.isBlank() ||
            pkgLower == "tunemind_inapp" ||
            pkgLower == "com.tunemind.app" ||
            pkgLower == "unknown_player" ||
            idLower == "tunemind_inapp" ||
            idLower.isBlank()
        return isSpotifyOrInAppTarget && (stayInTuneMind || strictNoSwitch || pkgLower.contains("spotify") || idLower == "spotify" || idLower == "tunemind_inapp")
    }

    private fun launchHybridPlayback(
        context: Context,
        providerId: String,
        targetPkg: String?,
        displayName: String,
        track: RecommendedTrack,
        cleanTitle: String,
        cleanArtist: String,
        fullSearchQuery: String,
        deepLinkUri: String?,
        previousTitle: String?,
        neverOpenExternalApp: Boolean
    ): LaunchOutcome {
        val stayInTuneMind = AgentSettingsStore.isStayInTuneMindEnabled(context)
        val preferInAppPlayer = AgentSettingsStore.isPreferInAppPlayerEnabled(context)
        val strictNoSwitch = neverOpenExternalApp ||
            AgentSettingsStore.isStrictNoAppSwitchEnabled(context)
        val isYtmTarget = isYouTubeMusicTarget(targetPkg, providerId)

        // TIER 1S: Spotify Premium Direct Connect (1-Tap / PKCE)
        // Plays the exact full Spotify track silently in the background with ZERO app switch.
        val spotifyPremiumReady = SpotifyPremiumConnectManager.isConnectedAndPremium(context)
        val spotifyInstalled = SpotifyMusicProvider().isInstalled(context)
        val isSpotifyTarget = targetPkg.equals(SpotifyMusicProvider.SPOTIFY_PACKAGE, ignoreCase = true) ||
            providerId.equals(SpotifyMusicProvider.PROVIDER_ID, ignoreCase = true)
        val isSpotifyFreeSmartMode = !spotifyPremiumReady &&
            spotifyInstalled &&
            AgentSettingsStore.isSpotifyFreeOpenInSpotifyEnabled(context) &&
            (SpotifyPremiumConnectManager.isConnectedAsFreeTier(context) || isSpotifyTarget)

        var spotifyResolvedUriFromConnect: String? = null
        var spotifyHadNoActiveDevice = false
        if (shouldTrySpotifyPremiumConnect(
                targetPkg = targetPkg,
                providerId = providerId,
                stayInTuneMind = stayInTuneMind,
                strictNoSwitch = strictNoSwitch,
                isSpotifyPremiumConnected = spotifyPremiumReady
            )
        ) {
            val candidateSpotifyUri = extractSpotifyTrackUri(deepLinkUri)
            when (
                val connectRes = SpotifyPremiumConnectManager.playTrackSilently(
                    context = context,
                    title = cleanTitle.ifBlank { track.title },
                    artist = cleanArtist.ifBlank { track.artist },
                    query = fullSearchQuery,
                    spotifyUri = candidateSpotifyUri
                )
            ) {
                is SpotifyPlayResult.Success -> {
                    Log.i(TAG, "✅ Tier-1S Spotify Premium Direct Connect started '${cleanTitle}' on ${connectRes.deviceName}")
                    return LaunchOutcome(
                        launched = true,
                        usedInAppPlayer = false,
                        destinationUri = connectRes.trackUri,
                        detailMessage = "Playing '${cleanTitle.ifBlank { track.title }}' silently on Spotify (${connectRes.deviceName}) without leaving TuneMind."
                    )
                }
                is SpotifyPlayResult.NoActiveDevice -> {
                    spotifyHadNoActiveDevice = true
                    spotifyResolvedUriFromConnect = connectRes.trackUri
                    Log.w(TAG, "Tier-1S Spotify Connect (after silent cold-wake): ${connectRes.message} — staying in TuneMind to try in-app full-song tiers first.")
                }
                is SpotifyPlayResult.PremiumRequired -> {
                    Log.w(TAG, "Tier-1S Spotify Connect requires Premium: ${connectRes.message}")
                }
                is SpotifyPlayResult.Failed -> {
                    Log.w(TAG, "Tier-1S Spotify Connect attempt fell through: ${connectRes.reason}")
                }
                SpotifyPlayResult.NotConnected -> Unit
            }
        }

        // TIER 1A: Local Audio Match inside TuneMind (skipped when user explicitly chose Spotify)
        if (!isSpotifyTarget && (preferInAppPlayer || stayInTuneMind || strictNoSwitch) && TuneMindInAppPlayer.playLocalMatch(context, cleanTitle, cleanArtist)) {
            val localUri = TuneMindInAppPlayer.currentUriFlow.value
            Log.i(TAG, "✅ Tier-1A In-App Local Playback started for '$cleanTitle'")
            return LaunchOutcome(
                launched = true,
                usedInAppPlayer = true,
                destinationUri = localUri,
                detailMessage = "Playing '$cleanTitle' from phone audio inside TuneMind."
            )
        }

        // Resolve Open Catalog Links (Audius stream, YouTube Music videoId)
        val resolvedLinks = UniversalTrackResolver.resolveTrackBlocking(
            context = context,
            cleanTitle = cleanTitle,
            cleanArtist = cleanArtist,
            fullQuery = fullSearchQuery
        )

        // TIER 1B: Open-Catalog Full Track Stream via Audius (skipped when user explicitly chose Spotify)
        val audiusStream = resolvedLinks?.audiusStreamUrl?.takeIf { it.startsWith("http") }
        if (!isSpotifyTarget && (preferInAppPlayer || stayInTuneMind || strictNoSwitch) && audiusStream != null) {
            if (TuneMindInAppPlayer.playStreamUrl(
                    context = context,
                    streamUrl = audiusStream,
                    title = cleanTitle.ifBlank { track.title },
                    artist = cleanArtist.ifBlank { track.artist },
                    album = resolvedLinks.albumName,
                    expectedDurationMs = 0L,
                    isPreview = false
                )
            ) {
                Log.i(TAG, "✅ Tier-1B Audius full stream started inside TuneMind for '$cleanTitle'")
                return LaunchOutcome(
                    launched = true,
                    usedInAppPlayer = true,
                    destinationUri = audiusStream,
                    detailMessage = "Streaming '$cleanTitle' inside TuneMind without leaving the app."
                )
            }
        }

        // TIER 1C (Option A — Audience-Gated): Visible Official YouTube IFrame Player inside TuneMind
        val ytmVideoId = resolvedLinks?.ytmVideoId?.takeIf { it.isNotBlank() }
            ?: track.providerTrackId?.trim()?.takeIf { Regex("^[A-Za-z0-9_-]{11}$").matches(it) }
        if (!isSpotifyTarget && shouldActivateBuiltInYouTubeTier(targetPkg, providerId, ytmVideoId, preferInAppPlayer, strictNoSwitch)) {
            val started = TuneMindYouTubeEmbedController.startVideo(
                context = context,
                videoId = ytmVideoId!!,
                title = cleanTitle.ifBlank { track.title },
                artist = cleanArtist.ifBlank { track.artist },
                album = resolvedLinks?.albumName,
                previewFallback = null,
                fallbackExternalPkg = targetPkg ?: YouTubeMusicProvider.YTM_PACKAGE,
                fallbackExternalName = displayName
            )
            if (started) {
                Log.i(TAG, "✅ Tier-1C Built-In Visible YouTube Player started for '$cleanTitle' ($ytmVideoId)")
                return LaunchOutcome(
                    launched = true,
                    usedInAppPlayer = true,
                    destinationUri = "https://music.youtube.com/watch?v=$ytmVideoId",
                    detailMessage = "Playing '$cleanTitle' inside TuneMind's built-in YouTube player."
                )
            }
        }

        // TIER 2: Warm Active MediaSession (boomplay / youtube music / audiomack / spotify passive / OEM)
        val exactSpotifyUri = if (isSpotifyTarget) {
            spotifyResolvedUriFromConnect ?: extractSpotifyTrackUri(deepLinkUri, resolvedLinks)
        } else null
        if ((stayInTuneMind || strictNoSwitch || isSpotifyTarget) &&
            tryWarmMediaSessionSwitch(
                targetPkg = targetPkg,
                displayName = displayName,
                cleanTitle = cleanTitle,
                cleanArtist = cleanArtist,
                fullSearchQuery = fullSearchQuery,
                previousTitle = previousTitle,
                spotifyUri = exactSpotifyUri
            )
        ) {
            return LaunchOutcome(
                launched = true,
                usedInAppPlayer = false,
                destinationUri = exactSpotifyUri ?: "mediasession:${targetPkg ?: "active"}",
                detailMessage = "Playing '$cleanTitle' via active $displayName session without leaving TuneMind."
            )
        }

        // TIER 3 (Strict Never-Leave Mode or Non-Spotify Stay-In-TuneMind Mode):
        if (strictNoSwitch || (!isSpotifyTarget && (stayInTuneMind || isSpotifyFreeSmartMode))) {
            if (!isSpotifyTarget && ytmVideoId != null && (strictNoSwitch || preferInAppPlayer || stayInTuneMind)) {
                val startedFullInApp = TuneMindYouTubeEmbedController.startVideo(
                    context = context,
                    videoId = ytmVideoId,
                    title = cleanTitle.ifBlank { track.title },
                    artist = cleanArtist.ifBlank { track.artist },
                    album = resolvedLinks?.albumName,
                    previewFallback = null,
                    fallbackExternalPkg = targetPkg,
                    fallbackExternalName = displayName
                )
                if (startedFullInApp) {
                    Log.i(TAG, "✅ Tier-3 Full In-App Playback started via Built-In YouTube Player for '$cleanTitle' ($ytmVideoId)")
                    return LaunchOutcome(
                        launched = true,
                        usedInAppPlayer = true,
                        destinationUri = spotifyResolvedUriFromConnect ?: "https://music.youtube.com/watch?v=$ytmVideoId",
                        detailMessage = "Playing full track '$cleanTitle' inside TuneMind without leaving the app."
                    )
                }
            }

            if (strictNoSwitch) {
                val ytmInstalled = !isYtmTarget && YouTubeMusicProvider().isInstalled(context)
                val failHint = if (isSpotifyTarget) {
                    "Strict Never-Leave mode kept Spotify closed. Start a song in Spotify once or turn off Strict mode so TuneMind can wake Spotify automatically."
                } else if (ytmInstalled) {
                    "Kept $displayName closed without leaving TuneMind. Say 'use YouTube inside TuneMind' or start a song in $displayName once to warm its background session."
                } else {
                    "Kept $displayName closed without leaving TuneMind. Open $displayName once to warm its background session, or turn off Strict mode in Settings."
                }
                return LaunchOutcome(
                    launched = false,
                    usedInAppPlayer = false,
                    destinationUri = spotifyResolvedUriFromConnect,
                    detailMessage = failHint
                )
            }
        }

        // TIER 4 (Spotify Cold-Wake + Auto-Return OR External Player Cold Handoff):
        // When user chose Spotify and silent background Connect/MediaSession could not wake it,
        // launch exact Spotify URI directly with autoReturnAfterPlay so Spotify ALWAYS wakes & plays!
        val enrichedSpotifyUri = if (isSpotifyTarget && SpotifyPremiumConnectManager.isConnected(context)) {
            spotifyResolvedUriFromConnect
                ?: extractSpotifyTrackUri(deepLinkUri, resolvedLinks)
                ?: SpotifyPremiumConnectManager.searchExactTrackUri(
                    context = context,
                    title = cleanTitle.ifBlank { track.title },
                    artist = cleanArtist.ifBlank { track.artist },
                    query = fullSearchQuery
                )
        } else {
            deepLinkUri
        }
        return launchExternalColdHandoff(
            context = context,
            targetPkg = targetPkg,
            displayName = displayName,
            track = track,
            cleanTitle = cleanTitle,
            cleanArtist = cleanArtist,
            fullSearchQuery = fullSearchQuery,
            deepLinkUri = enrichedSpotifyUri ?: deepLinkUri,
            previousTitle = previousTitle,
            resolvedLinks = resolvedLinks,
            autoReturnAfterPlay = isSpotifyTarget && !strictNoSwitch
        )
    }

    private fun tryWarmMediaSessionSwitch(
        targetPkg: String?,
        displayName: String,
        cleanTitle: String,
        cleanArtist: String,
        fullSearchQuery: String,
        previousTitle: String?,
        spotifyUri: String? = null
    ): Boolean {
        return try {
            val listener = TuneMindListenerService.instance ?: return false
            if (fullSearchQuery.isBlank() || !listener.hasActiveSession(targetPkg)) return false
            val sessionExtras = buildAutoPlayExtras(cleanTitle, cleanArtist, fullSearchQuery)
            val normExpected = UniversalTrackResolver.normalizeTitleForExactMatch(cleanTitle)

            // When a warm Spotify session exists and we resolved spotify:track:<id>, playFromUri hits the exact song.
            if (!spotifyUri.isNullOrBlank()) {
                val uriSent = runCatching {
                    listener.playFromUri(targetPkg, Uri.parse(spotifyUri), sessionExtras)
                }.getOrDefault(false)
                if (uriSent) {
                    var nudgedUriPlay = false
                    for (poll in 0 until 3) {
                        val waitMs = when (poll) {
                            0 -> 550L
                            1 -> 650L
                            else -> 750L
                        }
                        try {
                            Thread.sleep(waitMs)
                        } catch (_: InterruptedException) {}
                        val uriActiveTitle = listener.getActiveTitle(targetPkg)?.trim().orEmpty()
                        val normUriActive = UniversalTrackResolver.normalizeTitleForExactMatch(uriActiveTitle)
                        val uriSwitched = normUriActive.isNotBlank() && (
                            normUriActive == normExpected ||
                                (normExpected.length >= 4 && (normUriActive.contains(normExpected) || normExpected.contains(normUriActive))) ||
                                (!previousTitle.isNullOrBlank() && !uriActiveTitle.equals(previousTitle, ignoreCase = true))
                            )
                        if (uriSwitched) {
                            if (listener.isPlayingOrBuffering(targetPkg)) {
                                Log.i(TAG, "✅ Tier-2 Warm MediaSession playFromUri switched for '$cleanTitle' in $displayName")
                                return true
                            } else if (!nudgedUriPlay) {
                                nudgedUriPlay = true
                                listener.play(targetPkg)
                            }
                        }
                    }
                }
            }

            val sent = listener.playFromSearch(targetPkg, fullSearchQuery, sessionExtras)
            if (!sent) return false

            var nudgedPlay = false
            var retriedTitleOnly = false

            for (attempt in 0 until 3) {
                val waitMs = when (attempt) {
                    0 -> 550L
                    1 -> 650L
                    else -> 700L
                }
                try {
                    Thread.sleep(waitMs)
                } catch (_: InterruptedException) {}

                val activeTitle = listener.getActiveTitle(targetPkg)?.trim().orEmpty()
                val normActive = UniversalTrackResolver.normalizeTitleForExactMatch(activeTitle)
                val switched = normActive.isNotBlank() && (
                    normActive == normExpected ||
                        (normExpected.length >= 4 && (normActive.contains(normExpected) || normExpected.contains(normActive))) ||
                        (!previousTitle.isNullOrBlank() && !activeTitle.equals(previousTitle, ignoreCase = true))
                    )
                if (switched) {
                    if (listener.isPlayingOrBuffering(targetPkg)) {
                        Log.i(TAG, "✅ Tier-2 Warm MediaSession switched silently for '$cleanTitle' in $displayName")
                        return true
                    } else if (!nudgedPlay) {
                        nudgedPlay = true
                        listener.play(targetPkg)
                    }
                } else if (!retriedTitleOnly && cleanTitle.isNotBlank() && cleanArtist.isNotBlank()) {
                    retriedTitleOnly = true
                    listener.playFromSearch(
                        targetPkg,
                        cleanTitle,
                        buildAutoPlayExtras(cleanTitle, "", cleanTitle)
                    )
                }
            }
            false
        } catch (e: Exception) {
            Log.d(TAG, "Warm MediaSession playFromSearch notice: ${e.message}")
            false
        }
    }

    fun canAutoReturnToTuneMind(context: Context): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            Settings.canDrawOverlays(context) &&
            AgentSettingsStore.isAutoReturnAfterSpotifyWakeEnabled(context)
    }

    fun bringTuneMindToForegroundIfAllowed(context: Context, delayMs: Long = 380L) {
        if (!canAutoReturnToTuneMind(context)) return
        val appCtx = context.applicationContext
        mainHandler.postDelayed({
            runCatching {
                val returnIntent = Intent(appCtx, com.tunemind.app.MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("returned_from_spotify_wake", true)
                }
                appCtx.startActivity(returnIntent)
                Log.i(TAG, "✅ Auto-returned to TuneMind after Spotify cold-wake playback confirmed")
            }.onFailure { err ->
                Log.d(TAG, "Auto-return note: ${err.message}")
            }
        }, delayMs)
    }

    fun scheduleSpotifyPlaylistWakeKicker(
        context: Context,
        spotifyPlaylistId: String,
        playlistName: String
    ) {
        val cleanId = spotifyPlaylistId.trim()
        if (cleanId.isBlank()) return
        val appCtx = context.applicationContext
        val autoReturn = canAutoReturnToTuneMind(appCtx)
        postReturnToTuneMindNotification(appCtx, playlistName.ifBlank { "Spotify Playlist" }, "Spotify")

        Thread {
            val delays = listOf(1000L, 1000L, 1200L, 1400L)
            for (waitMs in delays) {
                runCatching { Thread.sleep(waitMs) }
                val listener = TuneMindListenerService.instance
                if (listener?.isPlayingOrBuffering(SpotifyMusicProvider.SPOTIFY_PACKAGE) == true) {
                    postReturnToTuneMindNotification(
                        context = appCtx,
                        trackTitle = playlistName.ifBlank { "Spotify Playlist" },
                        playerDisplayName = "Spotify",
                        confirmedPlaying = true,
                        silentUpdate = true
                    )
                    if (autoReturn) bringTuneMindToForegroundIfAllowed(appCtx)
                    return@Thread
                }
                val res = SpotifyPremiumConnectManager.playPlaylistContextSilently(appCtx, cleanId)
                if (res is SpotifyPlayResult.Success) {
                    postReturnToTuneMindNotification(
                        context = appCtx,
                        trackTitle = playlistName.ifBlank { "Spotify Playlist" },
                        playerDisplayName = "Spotify",
                        confirmedPlaying = true,
                        silentUpdate = true
                    )
                    if (autoReturn) bringTuneMindToForegroundIfAllowed(appCtx)
                    return@Thread
                }
            }
        }.start()
    }

    private fun launchExternalColdHandoff(
        context: Context,
        targetPkg: String?,
        displayName: String,
        track: RecommendedTrack,
        cleanTitle: String,
        cleanArtist: String,
        fullSearchQuery: String,
        deepLinkUri: String?,
        previousTitle: String?,
        resolvedLinks: ResolvedPlayableLinks?,
        autoReturnAfterPlay: Boolean = false
    ): LaunchOutcome {
        lastStalledPlaybackAttempt = null
        val isYtmTarget = targetPkg?.contains("youtube.music", ignoreCase = true) == true
        val isSpotifyTarget = targetPkg?.contains("spotify", ignoreCase = true) == true
        val effectiveAutoReturn = autoReturnAfterPlay || (isSpotifyTarget && canAutoReturnToTuneMind(context))
        val exactSpotifyUri = if (isSpotifyTarget) extractSpotifyTrackUri(deepLinkUri, resolvedLinks) else null
        val precisionWatchUrl = if (isYtmTarget && !resolvedLinks?.ytmVideoId.isNullOrBlank()) {
            "https://music.youtube.com/watch?v=${resolvedLinks!!.ytmVideoId}"
        } else null

        // A. Exact YouTube Music watch URL if resolved
        if (!precisionWatchUrl.isNullOrBlank()) {
            try {
                val ytmIntent = Intent(Intent.ACTION_VIEW, Uri.parse(precisionWatchUrl)).apply {
                    if (targetPkg != null) setPackage(targetPkg)
                    putExtra(Intent.EXTRA_REFERRER, Uri.parse(GOOGLE_ASSISTANT_REFERRER))
                    putExtra("android.intent.extra.START_PLAYBACK", true)
                    putExtra("autostart", true)
                    putExtra("play", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(ytmIntent)
                scheduleAutoPlayKicker(
                    context = context,
                    targetPackage = targetPkg,
                    displayName = displayName,
                    track = track,
                    overrideQuery = fullSearchQuery,
                    previousTitle = previousTitle,
                    spotifyUri = null,
                    ytmVideoId = resolvedLinks?.ytmVideoId,
                    autoReturnOnPlay = false
                )
                postReturnToTuneMindNotification(context, cleanTitle.ifBlank { track.title }, displayName)
                return LaunchOutcome(
                    launched = true,
                    usedInAppPlayer = false,
                    destinationUri = precisionWatchUrl,
                    detailMessage = "Opened exact track '$cleanTitle' in $displayName."
                )
            } catch (e: Exception) {
                Log.d(TAG, "Precision YTM watch link failed, trying search intent: ${e.message}")
            }
        }

        // A2. Exact Spotify Track URI (`spotify:track:<id>`) before generic search UI so Spotify
        // opens the exact track instead of getting stranded on search results.
        if (isSpotifyTarget && !exactSpotifyUri.isNullOrBlank()) {
            try {
                val spotifyTrackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(exactSpotifyUri)).apply {
                    if (targetPkg != null) setPackage(targetPkg)
                    putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://${context.packageName}"))
                    putExtra("android.intent.extra.START_PLAYBACK", true)
                    putExtra("autostart", true)
                    putExtra("play", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(spotifyTrackIntent)
                scheduleAutoPlayKicker(
                    context = context,
                    targetPackage = targetPkg,
                    displayName = displayName,
                    track = track,
                    overrideQuery = fullSearchQuery,
                    previousTitle = previousTitle,
                    spotifyUri = exactSpotifyUri,
                    ytmVideoId = resolvedLinks?.ytmVideoId,
                    autoReturnOnPlay = effectiveAutoReturn
                )
                postReturnToTuneMindNotification(context, cleanTitle.ifBlank { track.title }, displayName)
                val msg = if (effectiveAutoReturn) {
                    "Waking Spotify to play '$cleanTitle' and returning automatically to TuneMind."
                } else {
                    "Woke Spotify to play '$cleanTitle' directly."
                }
                return LaunchOutcome(
                    launched = true,
                    usedInAppPlayer = false,
                    destinationUri = exactSpotifyUri,
                    detailMessage = msg
                )
            } catch (e: Exception) {
                Log.d(TAG, "Exact Spotify track URI launch failed, trying MEDIA_PLAY_FROM_SEARCH: ${e.message}")
            }
        }

        // B. Standard Android MEDIA_PLAY_FROM_SEARCH for Boomplay / Spotify fallback / Audiomack / YTM / OEM players
        if (targetPkg != null && fullSearchQuery.isNotBlank()) {
            try {
                val mediaPlayIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    putExtra(SearchManager.QUERY, fullSearchQuery)
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
                    if (cleanTitle.isNotBlank()) putExtra(MediaStore.EXTRA_MEDIA_TITLE, cleanTitle)
                    if (cleanArtist.isNotBlank()) putExtra(MediaStore.EXTRA_MEDIA_ARTIST, cleanArtist)
                    if (!exactSpotifyUri.isNullOrBlank()) putExtra("uri", exactSpotifyUri)
                    putExtra(Intent.EXTRA_REFERRER, Uri.parse(GOOGLE_ASSISTANT_REFERRER))
                    putExtra("android.intent.extra.REFERRER_NAME", GOOGLE_ASSISTANT_REFERRER)
                    putExtra("android.intent.extra.START_PLAYBACK", true)
                    putExtra("autostart", true)
                    putExtra("play", true)
                    setPackage(targetPkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(mediaPlayIntent)
                scheduleAutoPlayKicker(
                    context = context,
                    targetPackage = targetPkg,
                    displayName = displayName,
                    track = track,
                    overrideQuery = fullSearchQuery,
                    previousTitle = previousTitle,
                    spotifyUri = exactSpotifyUri,
                    ytmVideoId = resolvedLinks?.ytmVideoId,
                    autoReturnOnPlay = effectiveAutoReturn
                )
                postReturnToTuneMindNotification(context, cleanTitle.ifBlank { track.title }, displayName)
                val honestMsg = if (isSpotifyTarget && exactSpotifyUri == null) {
                    "Opened Spotify search for '$cleanTitle' — tap Play if Spotify pauses on search."
                } else {
                    "Playing '$cleanTitle' in $displayName."
                }
                return LaunchOutcome(
                    launched = true,
                    usedInAppPlayer = false,
                    destinationUri = "search:$targetPkg",
                    detailMessage = honestMsg
                )
            } catch (e: Exception) {
                Log.d(TAG, "MEDIA_PLAY_FROM_SEARCH not accepted by $targetPkg: ${e.message}")
            }
        }

        // C. Explicit Deep Link / Audiomack / Deezer / Web Search Fallback
        val isAudiomackTarget = targetPkg?.contains("audiomack", ignoreCase = true) == true
        val fallbackLink = exactSpotifyUri
            ?: (if (isAudiomackTarget) resolvedLinks?.audiomackUrl else null)
            ?: deepLinkUri
            ?: resolvedLinks?.deezerUrl
        if (!fallbackLink.isNullOrBlank()) {
            try {
                val uriIntent = Intent(Intent.ACTION_VIEW, Uri.parse(fallbackLink)).apply {
                    if (targetPkg != null && !fallbackLink.startsWith("https://www.deezer.com")) {
                        setPackage(targetPkg)
                    }
                    putExtra(Intent.EXTRA_REFERRER, Uri.parse(GOOGLE_ASSISTANT_REFERRER))
                    putExtra("android.intent.extra.START_PLAYBACK", true)
                    putExtra("autostart", true)
                    putExtra("play", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(uriIntent)
                scheduleAutoPlayKicker(
                    context = context,
                    targetPackage = targetPkg,
                    displayName = displayName,
                    track = track,
                    overrideQuery = fullSearchQuery,
                    previousTitle = previousTitle,
                    spotifyUri = exactSpotifyUri,
                    ytmVideoId = resolvedLinks?.ytmVideoId,
                    autoReturnOnPlay = effectiveAutoReturn
                )
                postReturnToTuneMindNotification(context, cleanTitle.ifBlank { track.title }, displayName)
                return LaunchOutcome(
                    launched = true,
                    usedInAppPlayer = false,
                    destinationUri = fallbackLink,
                    detailMessage = "Opened '$cleanTitle' in $displayName."
                )
            } catch (e: Exception) {
                Log.w(TAG, "Deep link handoff failed: ${e.message}")
            }
        }

        return LaunchOutcome(
            launched = false,
            detailMessage = "Could not launch playback for '$cleanTitle' in $displayName."
        )
    }

    fun cancelReturnNotification(context: Context) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(RETURN_NOTIFICATION_ID)
        }
    }

    /**
     * Play-safe high-priority Heads-Up banner shown after launching an external player so users
     * of Spotify, Boomplay, Audiomack, Deezer, or any Android player get a prominent floating peek
     * banner with a 1-tap "Back to TuneMind" action right at the top of the screen (zero BAL/overlay risks).
     */
    private fun postReturnToTuneMindNotification(
        context: Context,
        trackTitle: String,
        playerDisplayName: String,
        confirmedPlaying: Boolean = false,
        silentUpdate: Boolean = false
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) return
            }

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    RETURN_CHANNEL_ID,
                    "Quick Return to TuneMind",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Floating Heads-Up return pill when TuneMind starts a song in Spotify, Boomplay, Audiomack, or other players."
                    val attrs = AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build()
                    setSound(null, attrs)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0L, 18L)
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(channel)
            }

            val returnIntent = Intent(context, com.tunemind.app.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pi = PendingIntent.getActivity(
                context,
                RETURN_NOTIFICATION_ID,
                returnIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val titleText = if (confirmedPlaying) {
                "Now playing '$trackTitle' in $playerDisplayName"
            } else {
                "Starting '$trackTitle' in $playerDisplayName"
            }
            val bodyText = if (confirmedPlaying) {
                "Tap Back to TuneMind — $playerDisplayName stays playing"
            } else {
                "Tap to return to TuneMind anytime"
            }

            val builder = NotificationCompat.Builder(context, RETURN_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(titleText)
                .setContentText(bodyText)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(silentUpdate)
                .setAutoCancel(true)
                .setTimeoutAfter(28_000L)
                .setContentIntent(pi)
                .addAction(
                    R.drawable.ic_launcher_foreground,
                    "Back to TuneMind",
                    pi
                )

            if (!silentUpdate) {
                builder.setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            }

            nm.notify(RETURN_NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            Log.d(TAG, "Return notification skipped: ${e.message}")
        }
    }

    private fun postStalledPlaybackRecoveryNotification(
        context: Context,
        targetPackage: String?,
        trackTitle: String,
        trackArtist: String,
        playerDisplayName: String,
        ytmVideoId: String?
    ) {
        lastStalledPlaybackAttempt = StalledPlaybackAttempt(
            title = trackTitle,
            artist = trackArtist,
            targetPackage = targetPackage,
            playerDisplayName = playerDisplayName,
            ytmVideoId = ytmVideoId
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) return
            }

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val returnIntent = Intent(context, com.tunemind.app.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val backPi = PendingIntent.getActivity(
                context,
                RETURN_NOTIFICATION_ID,
                returnIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val recoverIntent = Intent(context, com.tunemind.app.MainActivity::class.java).apply {
                action = ACTION_RECOVER_PLAYBACK_IN_TUNEMIND
                putExtra(EXTRA_RECOVER_TITLE, trackTitle)
                putExtra(EXTRA_RECOVER_ARTIST, trackArtist)
                putExtra(EXTRA_RECOVER_YTM_ID, ytmVideoId)
                putExtra(EXTRA_RECOVER_PLAYER_NAME, playerDisplayName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val recoverPi = PendingIntent.getActivity(
                context,
                RETURN_NOTIFICATION_ID + 1,
                recoverIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, RETURN_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("$playerDisplayName paused on '$trackTitle'")
                .setContentText("Tap Play in $playerDisplayName, or use 'Play in TuneMind' below.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setTimeoutAfter(35_000L)
                .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
                // Tapping the banner itself ALWAYS returns to TuneMind cleanly without stopping Spotify!
                .setContentIntent(backPi)
                .addAction(
                    R.drawable.ic_launcher_foreground,
                    "Play in TuneMind",
                    recoverPi
                )
                .addAction(
                    R.drawable.ic_launcher_foreground,
                    "Back to TuneMind",
                    backPi
                )
                .build()

            nm.notify(RETURN_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.d(TAG, "Recovery notification skipped: ${e.message}")
        }
    }

    private fun buildAutoPlayExtras(
        cleanTitle: String,
        cleanArtist: String,
        query: String
    ): Bundle {
        return Bundle().apply {
            putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
            if (cleanTitle.isNotBlank()) putString(MediaStore.EXTRA_MEDIA_TITLE, cleanTitle)
            if (cleanArtist.isNotBlank()) putString(MediaStore.EXTRA_MEDIA_ARTIST, cleanArtist)
            putString(SearchManager.QUERY, query)
            putBoolean("android.intent.extra.START_PLAYBACK", true)
            putBoolean("autostart", true)
            putBoolean("play", true)
        }
    }

    /**
     * Extended post-launch verification kicker & stalled-playback watchdog:
     * Retries `playFromUri` / `playFromSearch` + `play()` across cold boot (500ms-4000ms),
     * updates the Heads-Up Return banner once playback starts cleanly, and if the external
     * app still stalled on search/paused at 4800ms, offers instant one-tap recovery back in TuneMind.
     */
    fun scheduleAutoPlayKicker(
        context: Context,
        targetPackage: String?,
        displayName: String,
        track: RecommendedTrack,
        overrideQuery: String? = null,
        previousTitle: String? = null,
        spotifyUri: String? = null,
        ytmVideoId: String? = null,
        autoReturnOnPlay: Boolean = false
    ) {
        val appCtx = context.applicationContext
        val cleanTitle = sanitizeTrackTitleForPlay(track.title)
        val cleanArtist = sanitizeArtistForPlay(track.artist)
        val query = overrideQuery?.takeIf { it.isNotBlank() }
            ?: listOf(cleanTitle, cleanArtist).filter { it.isNotBlank() }.joinToString(" ")

        val extras = buildAutoPlayExtras(cleanTitle, cleanArtist, query)
        val normExpected = UniversalTrackResolver.normalizeTitleForExactMatch(cleanTitle)
        val normPrev = UniversalTrackResolver.normalizeTitleForExactMatch(previousTitle.orEmpty())
        val confirmedReturnNotified = AtomicBoolean(false)
        val cleanDisplayTitle = cleanTitle.ifBlank { track.title }
        val isSpotifyTarget = targetPackage?.contains("spotify", ignoreCase = true) == true
        val shouldAutoReturn = autoReturnOnPlay && canAutoReturnToTuneMind(appCtx)

        fun onConfirmedPlaying() {
            lastStalledPlaybackAttempt = null
            if (confirmedReturnNotified.compareAndSet(false, true)) {
                postReturnToTuneMindNotification(
                    context = appCtx,
                    trackTitle = cleanDisplayTitle,
                    playerDisplayName = displayName,
                    confirmedPlaying = true,
                    silentUpdate = true
                )
                if (shouldAutoReturn) {
                    bringTuneMindToForegroundIfAllowed(appCtx)
                }
            }
        }

        lateinit var reactiveListener: (com.tunemind.app.media.ActivePlaybackSnapshot) -> Unit
        reactiveListener = { snap ->
            val pkgMatches = targetPackage.isNullOrBlank() ||
                snap.packageName.equals(targetPackage, ignoreCase = true)
            val normSnap = UniversalTrackResolver.normalizeTitleForExactMatch(snap.title)
            val snapTitleMatches = normSnap.isNotBlank() && (
                (normExpected.isNotBlank() && (normSnap == normExpected || (normExpected.length >= 4 && (normSnap.contains(normExpected) || normExpected.contains(normSnap))))) ||
                    (normPrev.isNotBlank() && normSnap != normPrev)
                )
            val isActiveState = snap.isPlaying || snap.playbackStateCode == 3 || snap.playbackStateCode == 6
            if (pkgMatches && snapTitleMatches && isActiveState) {
                onConfirmedPlaying()
                TuneMindListenerService.removePlaybackSnapshotListener(reactiveListener)
            }
        }
        TuneMindListenerService.addPlaybackSnapshotListener(reactiveListener)

        // Spotify Premium Post-UI-Wake Connect Kicker:
        // The millisecond Spotify finishes cold boot and registers the phone on Connect,
        // blast PUT /v1/me/player/play for the exact track so Spotify never pauses on UI open.
        if (isSpotifyTarget && SpotifyPremiumConnectManager.isConnectedAndPremium(appCtx)) {
            Thread {
                for (waitMs in listOf(850L, 900L, 1050L, 1150L)) {
                    if (confirmedReturnNotified.get()) break
                    runCatching { Thread.sleep(waitMs) }
                    if (confirmedReturnNotified.get()) break
                    val res = SpotifyPremiumConnectManager.tryConnectPlayAfterUiWake(
                        context = appCtx,
                        spotifyUri = spotifyUri,
                        title = cleanTitle.ifBlank { track.title },
                        artist = cleanArtist.ifBlank { track.artist },
                        query = query
                    )
                    if (res is SpotifyPlayResult.Success) {
                        Log.i(TAG, "✅ Post-wake Spotify Connect kicker started '$cleanDisplayTitle' on ${res.deviceName}")
                        onConfirmedPlaying()
                        TuneMindListenerService.removePlaybackSnapshotListener(reactiveListener)
                        break
                    }
                }
            }.start()
        }

        for ((index, delay) in listOf(550L, 1250L, 2100L, 3100L).withIndex()) {
            mainHandler.postDelayed({
                try {
                    if (confirmedReturnNotified.get()) return@postDelayed
                    val listener = TuneMindListenerService.instance ?: return@postDelayed
                    val curTitle = listener.getActiveTitle(targetPackage)?.trim().orEmpty()
                    val normCur = UniversalTrackResolver.normalizeTitleForExactMatch(curTitle)
                    val alreadyTarget = normCur.isNotBlank() && normExpected.isNotBlank() && (
                        normCur == normExpected ||
                            (normExpected.length >= 4 && (normCur.contains(normExpected) || normExpected.contains(normCur)))
                        )
                    if (!alreadyTarget) {
                        if (!spotifyUri.isNullOrBlank() && index % 2 == 0) {
                            listener.playFromUri(targetPackage, Uri.parse(spotifyUri), extras)
                        } else if (query.isNotBlank()) {
                            val retryQuery = if (index == 3 && cleanTitle.isNotBlank()) cleanTitle else query
                            listener.playFromSearch(targetPackage, retryQuery, extras)
                        }
                    }
                } catch (_: Exception) {}
            }, delay)
        }

        val audioManager = appCtx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        for (delay in listOf(1400L, 2250L, 3250L, 4200L, 5200L)) {
            mainHandler.postDelayed({
                try {
                    val listener = TuneMindListenerService.instance
                    val curTitle = listener?.getActiveTitle(targetPackage)?.trim().orEmpty()
                    val normCur = UniversalTrackResolver.normalizeTitleForExactMatch(curTitle)
                    val titleMatchesNewSong = normCur.isNotBlank() && (
                        (normExpected.isNotBlank() && (normCur == normExpected || (normExpected.length >= 4 && (normCur.contains(normExpected) || normExpected.contains(normCur))))) ||
                            (normPrev.isNotBlank() && normCur != normPrev && delay >= 1400L)
                        )
                    val playingOrBufferingTarget = listener?.isPlayingOrBuffering(targetPackage) == true
                    if (titleMatchesNewSong && playingOrBufferingTarget) {
                        onConfirmedPlaying()
                        TuneMindListenerService.removePlaybackSnapshotListener(reactiveListener)
                    } else if (titleMatchesNewSong || (delay >= 2250L && listener?.hasActiveSession(targetPackage) == true && !playingOrBufferingTarget)) {
                        listener?.play(targetPackage)
                        audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                        audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Auto-play verification kicker notice: ${e.message}")
                }
            }, delay)
        }

        mainHandler.postDelayed({
            try {
                if (confirmedReturnNotified.get()) return@postDelayed
                val listener = TuneMindListenerService.instance
                if (listener?.isPlayingOrBuffering(targetPackage) == true) {
                    onConfirmedPlaying()
                    TuneMindListenerService.removePlaybackSnapshotListener(reactiveListener)
                    return@postDelayed
                }
                Log.w(TAG, "$displayName did not confirm playback for '$cleanTitle' within 6.2s; offering TuneMind recovery.")
                postStalledPlaybackRecoveryNotification(
                    context = appCtx,
                    targetPackage = targetPackage,
                    trackTitle = cleanDisplayTitle,
                    trackArtist = cleanArtist.ifBlank { track.artist },
                    playerDisplayName = displayName,
                    ytmVideoId = ytmVideoId
                )
            } catch (_: Exception) {}
        }, 6200L)

        mainHandler.postDelayed({
            TuneMindListenerService.removePlaybackSnapshotListener(reactiveListener)
        }, 12_000L)
    }
}
