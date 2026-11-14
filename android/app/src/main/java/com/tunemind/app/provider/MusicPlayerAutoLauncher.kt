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
        webFallbackUrl: String? = null
    ): PlaybackLaunchResult {
        val targetPkg = if (packageName == "*") null else packageName
        val listener = TuneMindListenerService.instance
        val previousTitle = listener?.getActiveTitle(targetPkg)?.trim()

        // 0. Silence any currently playing song before switching
        try {
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

        val launched = launchIntentAndReturnToTuneMind(
            context = context,
            targetPkg = targetPkg,
            track = track,
            cleanTitle = cleanTitle,
            cleanArtist = cleanArtist,
            fullSearchQuery = fullSearchQuery,
            deepLinkUri = deepLinkUri,
            previousTitle = previousTitle
        )

        return if (launched) {
            PlaybackLaunchResult.DirectPlaybackLaunched(
                providerId = providerId,
                providerDisplayName = displayName,
                trackTitle = cleanTitle.ifBlank { track.title },
                destinationUri = deepLinkUri ?: "intent:$targetPkg",
                message = "Playing '${cleanTitle.ifBlank { track.title }}' in $displayName."
            )
        } else {
            PlaybackLaunchResult.LaunchFailed(
                providerId = providerId,
                reason = "Could not start playback in $displayName."
            )
        }
    }

    private fun launchIntentAndReturnToTuneMind(
        context: Context,
        targetPkg: String?,
        track: RecommendedTrack,
        cleanTitle: String,
        cleanArtist: String,
        fullSearchQuery: String,
        deepLinkUri: String?,
        previousTitle: String?
    ): Boolean {
        val isSpotifyTarget = targetPkg == null || targetPkg.contains("spotify", ignoreCase = true)

        // 1. RESOLVE EXACT SPOTIFY TRACK URI (`spotify:track:<22-char-id>`) BEFORE LAUNCHING!
        // This prevents Spotify from opening a static `spotify:search:` results screen.
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

        val stayInTuneMind = com.tunemind.app.domain.voice.AgentSettingsStore.isStayInTuneMindEnabled(context)

        // 2. UNIVERSAL 3-TIER STAY-IN-TUNEMIND PLAYBACK (Built for Spotify Free & Premium + Screen Off):
        //    - TIER 1A (Spotify App Remote SDK): Direct IPC play(uri) — works for BOTH Free and Premium without leaving TuneMind!
        //    - TIER 1B (Spotify Web API): `PUT /v1/me/player/play` for Premium cloud-connected devices.
        //    - TIER 1C (MediaSession `playFromUri`): Injects exact track URI into Spotify's active MediaSession.
        //    - TIER 2 (Cold-Start Fallback + Immediate Bounce-Back to TuneMind): Only if all Tier 1 paths fail.
        if (!resolvedSpotifyUri.isNullOrBlank()) {
            // TIER 1A: Spotify App Remote SDK (works for Free + Premium without app switch!)
            val appRemoteStarted = if (isSpotifyTarget && stayInTuneMind) {
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
                    }
                } catch (_: Exception) {}

                // TIER 1B: Direct silent Web API playback (returns true on Spotify Premium when device is reachable)
                webApiStarted = SpotifyTrackUriResolver.triggerWebApiRemotePlayBlocking(context, resolvedSpotifyUri)
                SpotifyTrackUriResolver.triggerWebApiRemotePlayAsync(context, resolvedSpotifyUri)

                // Check if MediaSession already switched to & started playing the target track (never mistake old song!)
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
                    "✅ Tier-1 Silent Stay-in-TuneMind playback active (appRemote=$appRemoteStarted, webApi=$webApiStarted, mediaSession=$mediaSessionStartedSilently) for '$cleanTitle'"
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
                    // Safety net: if Web API returned 204 on a sleeping device that still hasn't started audio after 2100ms,
                    // wake Spotify and bounce back into TuneMind.
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
                return true
            }

            // TIER 2: Cold-Start / Unauthorized Remote Fallback — Wake Spotify & immediately bounce back to TuneMind
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
                return true
            }
        }

        // 3. Fallback when offline or for non-Spotify players: MEDIA_PLAY_FROM_SEARCH with Google Assistant Referrer
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
                return true
            } catch (e: Exception) {
                Log.d(TAG, "Media play from search not accepted by $targetPkg, falling back: ${e.message}")
            }
        }

        // 4. Generic Deep Link Fallback
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
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Deep link launch failed: ${e.message}")
            }
        }
        return false
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
     * Brings TuneMind back to the foreground as soon as Spotify starts playing the track (or by 2.45s at latest
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
        val checkDelays = listOf(850L, 1300L, 1850L, 2450L, 3200L)
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
                    if ((isPlayingNow && (titleSwitched || checkDelay >= 1300L)) || checkDelay >= 2450L) {
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
