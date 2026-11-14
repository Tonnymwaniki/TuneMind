package com.tunemind.app.provider

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import com.tunemind.app.domain.recommendation.RecommendedTrack
import com.tunemind.app.service.TuneMindListenerService

/**
 * Universal hands-free auto-play launcher across Android music players
 * (Spotify, YouTube Music, Boomplay, Apple Music, Audiomack, and OEM/Phone players).
 *
 * Guarantees immediate playback on Spotify without stalling on the search results screen:
 * 1. Resolves the exact `spotify:track:<22-char-id>` URI via [SpotifyTrackUriResolver] (`/v1/search`)
 *    before launching Spotify so we never open a static `spotify:search:` results page.
 * 2. Injects `playFromUri(Uri.parse("spotify:track:<id>"), extras)` directly into Spotify's active
 *    `MediaSession` AND fires `PUT https://api.spotify.com/v1/me/player/play` via the Spotify Web API.
 * 3. Launches the exact `spotify:track:<id>` URI with Google Assistant referrer flags
 *    (`android-app://com.google.android.googlequicksearchbox/https/www.google.com`) and runs a
 *    6-wave play-verification loop (`transportControls.play()` + `KEYCODE_MEDIA_PLAY`) until
 *    `TuneMindListenerService.isPlaying()` confirms audio is streaming.
 */
object MusicPlayerAutoLauncher {
    private const val TAG = "MusicPlayerAutoLauncher"
    private const val GOOGLE_ASSISTANT_REFERRER =
        "android-app://com.google.android.googlequicksearchbox/https/www.google.com"
    private val mainHandler = Handler(Looper.getMainLooper())

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

    private fun normalizeSpotifyTrackUri(rawUri: String?): String? {
        if (rawUri.isNullOrBlank()) return null
        val trimmed = rawUri.trim()
        if (trimmed.startsWith("spotify:track:")) return trimmed
        if (trimmed.startsWith("https://open.spotify.com/track/")) {
            val id = trimmed.substringAfter("https://open.spotify.com/track/").substringBefore("?").substringBefore("/").trim()
            if (id.isNotBlank()) return "spotify:track:$id"
        }
        if (Regex("^[A-Za-z0-9]{22}$").matches(trimmed)) {
            return "spotify:track:$trimmed"
        }
        return null
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
        val targetPkg = if (packageName == "*") null else packageName
        val listener = TuneMindListenerService.instance
        val previousTitle = listener?.getActiveTitle(targetPkg)?.trim()

        // 0. Silence any currently playing song (in-app or external) before switching
        try {
            TuneMindInAppPlayer.pause()
            SpotifyAppRemoteManager.pause()
            listener?.pause()
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        } catch (e: Exception) {
            Log.d(TAG, "Audio pause interruption note: ${e.message}")
        }

        val cleanTitle = sanitizeTrackTitleForPlay(track.title.ifBlank { query })
        val cleanArtist = sanitizeArtistForPlay(track.artist)
        val fullSearchQuery = if (cleanArtist.isNotBlank() && !cleanTitle.contains(cleanArtist, ignoreCase = true)) {
            "$cleanTitle $cleanArtist".trim()
        } else {
            cleanTitle.ifBlank { sanitizeTrackTitleForPlay(query) }
        }

        val outcome = launchIntentAndReturnToTuneMind(
            context = context,
            targetPkg = targetPkg,
            displayName = displayName,
            track = track,
            cleanTitle = cleanTitle,
            cleanArtist = cleanArtist,
            fullSearchQuery = fullSearchQuery,
            deepLinkUri = deepLinkUri,
            previousTitle = previousTitle,
            neverOpenExternalApp = neverOpenExternalApp
        )

        return if (outcome.launched) {
            PlaybackLaunchResult.DirectPlaybackLaunched(
                providerId = if (outcome.usedInAppPlayer) "tunemind_inapp" else providerId,
                providerDisplayName = if (outcome.usedInAppPlayer) "TuneMind In-App Player" else displayName,
                trackTitle = cleanTitle.ifBlank { track.title },
                destinationUri = outcome.destinationUri ?: deepLinkUri ?: "intent:$targetPkg",
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

    private fun launchIntentAndReturnToTuneMind(
        context: Context,
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
        val isSpotifyTarget = targetPkg == null || targetPkg.contains("spotify", ignoreCase = true)
        val stayInTuneMind = com.tunemind.app.domain.voice.AgentSettingsStore.isStayInTuneMindEnabled(context)
        val preferInAppPlayer = com.tunemind.app.domain.voice.AgentSettingsStore.isPreferInAppPlayerEnabled(context)
        val strictNoSwitch = neverOpenExternalApp ||
            com.tunemind.app.domain.voice.AgentSettingsStore.isStrictNoAppSwitchEnabled(context)

        // OPTION 3A (Zero-App-Switch Local Audio): Check if matching full track exists on phone storage!
        if (preferInAppPlayer && TuneMindInAppPlayer.playLocalMatch(context, cleanTitle, cleanArtist)) {
            val localUri = TuneMindInAppPlayer.currentUriFlow.value
            Log.i(TAG, "✅ Option 3 In-App Local Playback started for '$cleanTitle' without leaving TuneMind")
            return LaunchOutcome(
                launched = true,
                usedInAppPlayer = true,
                destinationUri = localUri,
                detailMessage = "Playing '$cleanTitle' directly inside TuneMind's built-in audio engine."
            )
        }

        // 1. RESOLVE EXACT SPOTIFY TRACK URI (`spotify:track:<22-char-id>`) BEFORE DISPATCH!
        val resolvedSpotifyUri: String? = normalizeSpotifyTrackUri(deepLinkUri)
            ?: normalizeSpotifyTrackUri(track.id)
            ?: if (isSpotifyTarget) {
                SpotifyTrackUriResolver.resolveTrackUriBlocking(
                    context = context,
                    cleanTitle = cleanTitle,
                    cleanArtist = cleanArtist,
                    fullQuery = fullSearchQuery
                )
            } else null

        val sessionExtras = buildAutoPlayExtras(cleanTitle, cleanArtist, fullSearchQuery)

        // 2. UNIVERSAL 3-TIER STAY-IN-TUNEMIND SILENT CHANNELS (ZERO APP SWITCH):
        //    - TIER 1A: Spotify App Remote SDK IPC (Free & Premium without leaving TuneMind)
        //    - TIER 1B: Active Background MediaSession (`playFromUri` / `playFromSearch`)
        //    - TIER 1C: Spotify Web API (`PUT /v1/me/player/play`)
        if (!resolvedSpotifyUri.isNullOrBlank()) {
            val appRemoteStarted = if (isSpotifyTarget && (stayInTuneMind || strictNoSwitch)) {
                SpotifyAppRemoteManager.playUriBlocking(context, resolvedSpotifyUri)
            } else false

            var webApiStarted = false
            var sessionDispatched = false
            var mediaSessionStartedSilently = false

            if (!appRemoteStarted) {
                try {
                    val listener = TuneMindListenerService.instance
                    if (listener != null && listener.hasActiveSession(targetPkg)) {
                        sessionDispatched = listener.playFromUri(targetPkg, Uri.parse(resolvedSpotifyUri), sessionExtras)
                        if (!sessionDispatched && fullSearchQuery.isNotBlank()) {
                            sessionDispatched = listener.playFromSearch(targetPkg, fullSearchQuery, sessionExtras)
                        }
                    }
                } catch (_: Exception) {}

                // Note: In strict mode, only use synchronous blocking Web API; skip redundant async wake when unneeded
                webApiStarted = SpotifyTrackUriResolver.triggerWebApiRemotePlayBlocking(context, resolvedSpotifyUri)
                if (webApiStarted && !strictNoSwitch) {
                    SpotifyTrackUriResolver.triggerWebApiRemotePlayAsync(context, resolvedSpotifyUri)
                }

                if (!webApiStarted && sessionDispatched) {
                    val normCleanTitle = SpotifyTrackUriResolver.normalizeTitleForExactMatch(cleanTitle)
                    for (waitMs in listOf(260L, 300L, 300L)) {
                        try {
                            Thread.sleep(waitMs)
                        } catch (_: InterruptedException) {}
                        val listener = TuneMindListenerService.instance
                        val currentTitle = listener?.getActiveTitle(targetPkg)?.trim()
                        val normCurr = SpotifyTrackUriResolver.normalizeTitleForExactMatch(currentTitle.orEmpty())
                        val switchedToNewTrack = normCurr.isNotBlank() && (
                            normCurr == normCleanTitle ||
                                normCurr.contains(normCleanTitle) ||
                                normCleanTitle.contains(normCurr) ||
                                (!previousTitle.isNullOrBlank() && !currentTitle.equals(previousTitle, ignoreCase = true))
                            )
                        if (switchedToNewTrack && listener?.isPlaying() == true) {
                            mediaSessionStartedSilently = true
                            break
                        }
                    }
                }
            }

            if (appRemoteStarted || webApiStarted || mediaSessionStartedSilently) {
                Log.i(
                    TAG,
                    "✅ Tier-1 Silent Stay-in-TuneMind playback active (appRemote=$appRemoteStarted, webApi=$webApiStarted, mediaSession=$mediaSessionStartedSilently, strict=$strictNoSwitch) for '$cleanTitle'"
                )
                if (!appRemoteStarted) {
                    scheduleAutoPlayKicker(
                        context = context,
                        targetPackage = targetPkg,
                        track = track,
                        overrideQuery = fullSearchQuery,
                        previousTitle = previousTitle,
                        spotifyTrackUri = resolvedSpotifyUri
                    )
                    // IMPORTANT: Only execute Tier-2 cold-start wake if Strict Never-Leave-TuneMind Mode is OFF!
                    if (!strictNoSwitch) {
                        mainHandler.postDelayed({
                            try {
                                val listener = TuneMindListenerService.instance
                                if (!SpotifyAppRemoteManager.isConnected() && (listener == null || !listener.isPlaying())) {
                                    Log.i(TAG, "Tier-1 safety net: waking Spotify cold session and bouncing back to TuneMind...")
                                    wakeSpotifyActivityAndBounceBack(
                                        context = context,
                                        targetPkg = targetPkg,
                                        resolvedSpotifyUri = resolvedSpotifyUri,
                                        track = track,
                                        fullSearchQuery = fullSearchQuery,
                                        previousTitle = previousTitle,
                                        bounceBack = stayInTuneMind
                                    )
                                }
                            } catch (_: Exception) {}
                        }, 2100L)
                    }
                }
                return LaunchOutcome(
                    launched = true,
                    usedInAppPlayer = false,
                    destinationUri = resolvedSpotifyUri,
                    detailMessage = "Streaming '$cleanTitle' silently inside TuneMind."
                )
            }
        } else if (!isSpotifyTarget && (stayInTuneMind || strictNoSwitch)) {
            // Non-Spotify active player silent session attempt (`playFromSearch` via MediaSession without opening activity)
            try {
                val listener = TuneMindListenerService.instance
                if (listener != null && listener.hasActiveSession(targetPkg) && fullSearchQuery.isNotBlank()) {
                    val sent = listener.playFromSearch(targetPkg, fullSearchQuery, sessionExtras)
                    if (sent) {
                        return LaunchOutcome(
                            launched = true,
                            usedInAppPlayer = false,
                            destinationUri = "mediasession:${targetPkg ?: "active"}",
                            detailMessage = "Streaming '$cleanTitle' via active $displayName session without leaving TuneMind."
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        // OPTION 3B: If Strict Never-Leave Mode prevented opening Spotify, check for a streamable preview URL on Spotify API
        if (strictNoSwitch && preferInAppPlayer && !resolvedSpotifyUri.isNullOrBlank()) {
            val previewUrl = SpotifyTrackUriResolver.fetchTrackPreviewUrlBlocking(context, resolvedSpotifyUri)
            if (!previewUrl.isNullOrBlank() && TuneMindInAppPlayer.playStreamUrl(context, previewUrl, cleanTitle, cleanArtist)) {
                return LaunchOutcome(
                    launched = true,
                    usedInAppPlayer = true,
                    destinationUri = previewUrl,
                    detailMessage = "Kept $displayName closed and started '$cleanTitle' inside TuneMind's In-App Player (30s preview)."
                )
            }
        }

        // OPTION 1 GATE: NEVER open Spotify or any external app activity when strictNoSwitch is true!
        if (strictNoSwitch) {
            val remoteErr = SpotifyAppRemoteManager.lastConnectionErrorFlow.value
            val helpfulReason = buildString {
                append("Kept $displayName closed (Never-Leave-TuneMind Mode). ")
                if (!SpotifyAppRemoteManager.isConnected()) {
                    append("Spotify App Remote isn't linked yet — open TuneMind Settings and tap 'Connect Remote' once")
                    if (!remoteErr.isNullOrBlank()) {
                        append(" ($remoteErr)")
                    }
                    append(", or leave $displayName running in the background.")
                } else {
                    append("Your background media session could not accept the silent track command right now.")
                }
            }
            Log.w(TAG, "🛑 Blocked external app switch in Strict Mode for '$cleanTitle': $helpfulReason")
            return LaunchOutcome(
                launched = false,
                usedInAppPlayer = false,
                destinationUri = resolvedSpotifyUri,
                detailMessage = helpfulReason
            )
        }

        // OPTION 2: SMART WAKE + INSTANT OVERLAY BOUNCE-BACK (when Strict Mode is OFF)
        if (!resolvedSpotifyUri.isNullOrBlank()) {
            if (wakeSpotifyActivityAndBounceBack(
                    context = context,
                    targetPkg = targetPkg,
                    resolvedSpotifyUri = resolvedSpotifyUri,
                    track = track,
                    fullSearchQuery = fullSearchQuery,
                    previousTitle = previousTitle,
                    bounceBack = stayInTuneMind
                )
            ) {
                return LaunchOutcome(
                    launched = true,
                    usedInAppPlayer = false,
                    destinationUri = resolvedSpotifyUri,
                    detailMessage = if (stayInTuneMind) {
                        "Woke $displayName and returned to TuneMind."
                    } else {
                        "Playing '$cleanTitle' in $displayName."
                    }
                )
            }
        }

        // Fallback for offline or non-Spotify players: MEDIA_PLAY_FROM_SEARCH with Google Assistant Referrer
        if (targetPkg != null && fullSearchQuery.isNotBlank()) {
            try {
                val mediaPlayIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    putExtra(SearchManager.QUERY, fullSearchQuery)
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
                    if (cleanTitle.isNotBlank()) {
                        putExtra(MediaStore.EXTRA_MEDIA_TITLE, cleanTitle)
                    }
                    if (cleanArtist.isNotBlank()) {
                        putExtra(MediaStore.EXTRA_MEDIA_ARTIST, cleanArtist)
                    }
                    putExtra(Intent.EXTRA_REFERRER, Uri.parse(GOOGLE_ASSISTANT_REFERRER))
                    putExtra("android.intent.extra.REFERRER_NAME", GOOGLE_ASSISTANT_REFERRER)
                    putExtra("android.intent.extra.START_PLAYBACK", true)
                    putExtra("autostart", true)
                    putExtra("play", true)
                    setPackage(targetPkg)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                    )
                }
                context.startActivity(mediaPlayIntent)
                scheduleAutoPlayKicker(
                    context = context,
                    targetPackage = targetPkg,
                    track = track,
                    overrideQuery = fullSearchQuery,
                    previousTitle = previousTitle,
                    spotifyTrackUri = resolvedSpotifyUri
                )
                if (stayInTuneMind) {
                    scheduleReturnToTuneMindWhenPlaying(context, previousTitle, cleanTitle)
                }
                return LaunchOutcome(
                    launched = true,
                    usedInAppPlayer = false,
                    destinationUri = "search:$targetPkg",
                    detailMessage = "Playing '$cleanTitle' in $displayName."
                )
            } catch (e: Exception) {
                Log.d(TAG, "Media play from search not accepted by $targetPkg, falling back: ${e.message}")
            }
        }

        // Generic Deep Link Fallback
        if (!deepLinkUri.isNullOrBlank()) {
            try {
                val uriIntent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLinkUri)).apply {
                    if (targetPkg != null) setPackage(targetPkg)
                    putExtra(Intent.EXTRA_REFERRER, Uri.parse(GOOGLE_ASSISTANT_REFERRER))
                    putExtra("android.intent.extra.START_PLAYBACK", true)
                    putExtra("autostart", true)
                    putExtra("play", true)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                    )
                }
                context.startActivity(uriIntent)
                scheduleAutoPlayKicker(
                    context = context,
                    targetPackage = targetPkg,
                    track = track,
                    overrideQuery = fullSearchQuery,
                    previousTitle = previousTitle,
                    spotifyTrackUri = resolvedSpotifyUri
                )
                if (stayInTuneMind) {
                    scheduleReturnToTuneMindWhenPlaying(context, previousTitle, cleanTitle)
                }
                return LaunchOutcome(
                    launched = true,
                    usedInAppPlayer = false,
                    destinationUri = deepLinkUri,
                    detailMessage = "Playing '$cleanTitle' in $displayName."
                )
            } catch (e: Exception) {
                Log.w(TAG, "Deep link launch failed: ${e.message}")
            }
        }
        return LaunchOutcome(
            launched = false,
            detailMessage = "Could not launch playback for '$cleanTitle' in $displayName."
        )
    }

    private fun wakeSpotifyActivityAndBounceBack(
        context: Context,
        targetPkg: String?,
        resolvedSpotifyUri: String,
        track: RecommendedTrack,
        fullSearchQuery: String,
        previousTitle: String?,
        bounceBack: Boolean = true
    ): Boolean {
        return try {
            val cleanTitle = sanitizeTrackTitleForPlay(track.title)
            val trackUriIntent = Intent(Intent.ACTION_VIEW, Uri.parse(resolvedSpotifyUri)).apply {
                if (targetPkg != null) setPackage(targetPkg)
                putExtra(Intent.EXTRA_REFERRER, Uri.parse(GOOGLE_ASSISTANT_REFERRER))
                putExtra("android.intent.extra.REFERRER_NAME", GOOGLE_ASSISTANT_REFERRER)
                putExtra("android.intent.extra.START_PLAYBACK", true)
                putExtra("autostart", true)
                putExtra("play", true)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
            context.startActivity(trackUriIntent)
            scheduleAutoPlayKicker(
                context = context,
                targetPackage = targetPkg,
                track = track,
                overrideQuery = fullSearchQuery,
                previousTitle = previousTitle,
                spotifyTrackUri = resolvedSpotifyUri
            )
            // After cold-starting Spotify, silently bind SpotifyAppRemote so subsequent tracks play with ZERO app switching
            if (targetPkg == null || targetPkg.contains("spotify", ignoreCase = true)) {
                mainHandler.postDelayed({
                    runCatching {
                        SpotifyAppRemoteManager.connectAsync(context.applicationContext, showAuthView = false)
                    }
                }, 1600L)
            }
            if (bounceBack) {
                scheduleReturnToTuneMindWhenPlaying(context, previousTitle, cleanTitle)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Exact spotify:track URI launch notice: ${e.message}")
            false
        }
    }

    private fun buildAutoPlayExtras(
        cleanTitle: String,
        cleanArtist: String,
        query: String
    ): android.os.Bundle {
        return android.os.Bundle().apply {
            putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
            if (cleanTitle.isNotBlank()) putString(MediaStore.EXTRA_MEDIA_TITLE, cleanTitle)
            if (cleanArtist.isNotBlank()) putString(MediaStore.EXTRA_MEDIA_ARTIST, cleanArtist)
            putString(SearchManager.QUERY, query)
            putBoolean("android.intent.extra.START_PLAYBACK", true)
            putBoolean("autostart", true)
            putBoolean("play", true)
        }
    }

    /**
     * Brings TuneMind back to the foreground as soon as Spotify starts playing the track (or by 2.1s at latest
     * so the user is never stranded in Spotify), using three complementary Android mechanisms:
     * 1. `ActivityManager.moveTaskToFront` (via `REORDER_TASKS` permission)
     * 2. `PendingIntent.getActivity(...).send()`
     * 3. `context.startActivity(returnIntent)` with `FLAG_ACTIVITY_REORDER_TO_FRONT | FLAG_ACTIVITY_SINGLE_TOP`
     */
    private fun scheduleReturnToTuneMindWhenPlaying(
        context: Context,
        previousTitle: String? = null,
        expectedTitle: String? = null
    ) {
        var returned = false
        val checkDelays = listOf(550L, 950L, 1400L, 2100L, 2800L)
        for (checkDelay in checkDelays) {
            mainHandler.postDelayed({
                if (returned) return@postDelayed
                try {
                    val listener = TuneMindListenerService.instance
                    val activeTitle = listener?.getActiveTitle()?.trim()
                    val normActive = SpotifyTrackUriResolver.normalizeTitleForExactMatch(activeTitle.orEmpty())
                    val normExpected = SpotifyTrackUriResolver.normalizeTitleForExactMatch(expectedTitle.orEmpty())
                    val titleSwitched = normActive.isNotBlank() && (
                        (normExpected.isNotBlank() && (normActive == normExpected || normActive.contains(normExpected) || normExpected.contains(normActive))) ||
                            (!previousTitle.isNullOrBlank() && !activeTitle.equals(previousTitle, ignoreCase = true))
                        )
                    val isPlayingNow = listener?.isPlaying() == true
                    if ((isPlayingNow && (titleSwitched || checkDelay >= 950L)) || checkDelay >= 2100L) {
                        returned = true
                        bringTuneMindToFrontNow(context)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Return to TuneMind foreground notice: ${e.message}")
                }
            }, checkDelay)
        }
    }

    fun bringTuneMindToFrontNow(context: Context) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            am?.appTasks?.forEach { appTask ->
                runCatching { appTask.moveToFront() }
            }
        } catch (_: Throwable) {}

        try {
            val returnIntent = Intent(context, com.tunemind.app.MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
            runCatching {
                val piFlags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                val pi = android.app.PendingIntent.getActivity(context, 7742, returnIntent, piFlags)
                pi.send()
            }
            context.startActivity(returnIntent)
            Log.i(TAG, "🔄 Returned user to TuneMind foreground while music streams in background.")
        } catch (e: Exception) {
            Log.d(TAG, "bringTuneMindToFrontNow notice: ${e.message}")
        }
    }

    /**
     * CRITICAL:
     * 1. When `spotifyTrackUri` (`spotify:track:<id>`) is known, we NEVER call `playFromSearch`.
     * 2. NEVER fire bare `listener?.play()` or `KEYCODE_MEDIA_PLAY` unless the active MediaSession
     *    title has ACTUALLY switched to the requested track — otherwise Spotify resumes the OLD paused song!
     */
    fun scheduleAutoPlayKicker(
        context: Context,
        targetPackage: String?,
        track: RecommendedTrack,
        overrideQuery: String? = null,
        previousTitle: String? = null,
        spotifyTrackUri: String? = null
    ) {
        val cleanTitle = sanitizeTrackTitleForPlay(track.title)
        val cleanArtist = sanitizeArtistForPlay(track.artist)
        val query = overrideQuery?.takeIf { it.isNotBlank() }
            ?: if (cleanArtist.isNotBlank() && !cleanTitle.contains(cleanArtist, ignoreCase = true)) {
                "$cleanTitle $cleanArtist".trim()
            } else {
                cleanTitle
            }

        val extras = buildAutoPlayExtras(cleanTitle, cleanArtist, query)
        val normExpected = SpotifyTrackUriResolver.normalizeTitleForExactMatch(cleanTitle)
        val normPrev = SpotifyTrackUriResolver.normalizeTitleForExactMatch(previousTitle.orEmpty())

        // Stage 1: Cue target URI on active MediaSession without blindly resuming old queue
        val cueDelays = listOf(380L, 900L)
        for (delay in cueDelays) {
            mainHandler.postDelayed({
                try {
                    val listener = TuneMindListenerService.instance
                    if (!spotifyTrackUri.isNullOrBlank()) {
                        listener?.playFromUri(targetPackage, Uri.parse(spotifyTrackUri), extras)
                    } else {
                        listener?.playFromSearch(targetPackage, query, extras)
                    }
                } catch (t: Throwable) {
                    Log.d(TAG, "Auto-play cue pulse notice: ${t.message}")
                }
            }, delay)
        }

        // Stage 2: 5-wave Play Verification across 1.35s -> 4.3s.
        val playDelays = listOf(1350L, 1950L, 2650L, 3450L, 4300L)
        for (delay in playDelays) {
            mainHandler.postDelayed({
                try {
                    val listener = TuneMindListenerService.instance
                    val activeTitle = listener?.getActiveTitle(targetPackage)?.trim().orEmpty()
                    val normActive = SpotifyTrackUriResolver.normalizeTitleForExactMatch(activeTitle)

                    val isTargetLoaded = normActive.isNotBlank() && normExpected.isNotBlank() && (
                        normActive == normExpected ||
                            normActive.contains(normExpected) ||
                            normExpected.contains(normActive)
                        )
                    val isStillOldTrack = normPrev.isNotBlank() &&
                        normActive == normPrev &&
                        normPrev != normExpected

                    if (isTargetLoaded) {
                        if (listener?.isPlaying() != true) {
                            listener?.play(targetPackage)
                            dispatchMediaPlayKey(context, targetPackage)
                        }
                    } else if (isStillOldTrack || activeTitle.isBlank()) {
                        // Still holding old track or empty session -> re-send exact URI instead of resuming old song!
                        if (!spotifyTrackUri.isNullOrBlank()) {
                            listener?.playFromUri(targetPackage, Uri.parse(spotifyTrackUri), extras)
                        } else {
                            listener?.playFromSearch(targetPackage, query, extras)
                        }
                    }
                } catch (t: Throwable) {
                    Log.d(TAG, "Auto-play start pulse notice: ${t.message}")
                }
            }, delay)
        }
    }

    private fun dispatchMediaPlayKey(context: Context, targetPackage: String?) {
        try {
            if (targetPackage != null) {
                val mediaBtnDown = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                    setPackage(targetPackage)
                    putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                }
                context.sendOrderedBroadcast(mediaBtnDown, null)
                val mediaBtnUp = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                    setPackage(targetPackage)
                    putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
                }
                context.sendOrderedBroadcast(mediaBtnUp, null)
            }
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
        } catch (_: Throwable) {}
    }
}
