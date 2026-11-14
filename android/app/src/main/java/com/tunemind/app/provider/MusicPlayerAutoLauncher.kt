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

        // 2. UNIVERSAL 2-TIER STAY-IN-TUNEMIND PLAYBACK (Built for Spotify Premium & Free + Screen Off):
        //    - TIER 1 (Silent Background Play — Zero App Switch):
        //      Injects `playFromUri` into active MediaSession + triggers Spotify Web API `PUT /v1/me/player/play`.
        //      If Spotify accepts Web API play OR active MediaSession starts playing in the background within 850ms,
        //      we DO NOT open Spotify's UI Activity at all — the user stays 100% inside TuneMind!
        //    - TIER 2 (Cold-Start / Free-Tier Wake + Immediate Bounce-Back to TuneMind):
        //      If Spotify's process was cold/asleep or requires an intent wake-up, opens `spotify:track:<id>` with
        //      Google Assistant Referrer, kicks playback, and bounces back into TuneMind as soon as audio starts.
        if (!resolvedSpotifyUri.isNullOrBlank()) {
            var sessionDispatched = false
            try {
                val listener = TuneMindListenerService.instance
                if (listener != null && listener.hasActiveSession(targetPkg)) {
                    sessionDispatched = listener.playFromUri(targetPkg, Uri.parse(resolvedSpotifyUri), sessionExtras)
                    listener.play(targetPkg)
                }
            } catch (_: Exception) {}

            // Attempt direct silent Web API playback first (returns true on Spotify Premium when device is reachable)
            val webApiStarted = SpotifyTrackUriResolver.triggerWebApiRemotePlayBlocking(context, resolvedSpotifyUri)
            // Also fire async reinforcement waves
            SpotifyTrackUriResolver.triggerWebApiRemotePlayAsync(context, resolvedSpotifyUri)

            // Check if MediaSession already started playing the new track in the background (works for active sessions)
            var mediaSessionStartedSilently = false
            if (!webApiStarted && sessionDispatched) {
                for (waitMs in listOf(260L, 300L, 300L)) {
                    try {
                        Thread.sleep(waitMs)
                    } catch (_: InterruptedException) {}
                    val listener = TuneMindListenerService.instance
                    val currentTitle = listener?.getActiveTitle(targetPkg)?.trim()
                    val switchedToNewTrack = !currentTitle.isNullOrBlank() && (
                        currentTitle.contains(cleanTitle, ignoreCase = true) ||
                            cleanTitle.contains(currentTitle, ignoreCase = true) ||
                            (!previousTitle.isNullOrBlank() && !currentTitle.equals(previousTitle, ignoreCase = true))
                        )
                    if (switchedToNewTrack && listener?.isPlaying() == true) {
                        mediaSessionStartedSilently = true
                        break
                    }
                }
            }

            if (webApiStarted || mediaSessionStartedSilently) {
                Log.i(
                    TAG,
                    "✅ Tier-1 Silent Stay-in-TuneMind playback active (webApi=$webApiStarted, mediaSession=$mediaSessionStartedSilently) for '$cleanTitle'"
                )
                scheduleAutoPlayKicker(
                    context = context,
                    targetPackage = targetPkg,
                    track = track,
                    overrideQuery = fullSearchQuery,
                    previousTitle = previousTitle,
                    spotifyTrackUri = resolvedSpotifyUri
                )
                // Safety net: if Web API returned 204 on a sleeping phone device that still hasn't started audio after 1900ms,
                // wake Spotify and immediately return to TuneMind.
                mainHandler.postDelayed({
                    try {
                        val listener = TuneMindListenerService.instance
                        if (listener == null || !listener.isPlaying()) {
                            Log.i(TAG, "Tier-1 safety net: waking Spotify cold session and bouncing back to TuneMind...")
                            wakeSpotifyActivityAndBounceBack(
                                context = context,
                                targetPkg = targetPkg,
                                resolvedSpotifyUri = resolvedSpotifyUri,
                                track = track,
                                fullSearchQuery = fullSearchQuery,
                                previousTitle = previousTitle
                            )
                        }
                    } catch (_: Exception) {}
                }, 1900L)
                return true
            }

            // TIER 2: Cold-Start / Spotify Free Fallback — Wake Spotify & immediately bounce back to TuneMind
            if (wakeSpotifyActivityAndBounceBack(
                    context = context,
                    targetPkg = targetPkg,
                    resolvedSpotifyUri = resolvedSpotifyUri,
                    track = track,
                    fullSearchQuery = fullSearchQuery,
                    previousTitle = previousTitle
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
                scheduleReturnToTuneMindWhenPlaying(context, previousTitle, cleanTitle)
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
                scheduleReturnToTuneMindWhenPlaying(context, previousTitle, cleanTitle)
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
        previousTitle: String?
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
            scheduleReturnToTuneMindWhenPlaying(context, previousTitle, cleanTitle)
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
     * Brings TuneMind back to the foreground as soon as Spotify starts playing the track (or by 2.65s at latest
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
        val checkDelays = listOf(1050L, 1550L, 2100L, 2700L, 3450L, 4300L)
        for (checkDelay in checkDelays) {
            mainHandler.postDelayed({
                if (returned) return@postDelayed
                try {
                    val listener = TuneMindListenerService.instance
                    val activeTitle = listener?.getActiveTitle()?.trim()
                    val titleSwitched = !activeTitle.isNullOrBlank() && (
                        (!expectedTitle.isNullOrBlank() && activeTitle.contains(expectedTitle, ignoreCase = true)) ||
                            (!previousTitle.isNullOrBlank() && !activeTitle.equals(previousTitle, ignoreCase = true))
                        )
                    val isPlayingNow = listener?.isPlaying() == true
                    // Return immediately once playing (after 1.05s) OR unconditionally by 2700ms so user is never left in Spotify
                    if ((isPlayingNow && (titleSwitched || checkDelay >= 1550L)) || checkDelay >= 2700L) {
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
            // 1. Move TuneMind's task to front via ActivityManager (uses REORDER_TASKS permission)
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
            // 2. Dispatch via PendingIntent for Android 12-15 background launch privilege
            runCatching {
                val piFlags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                val pi = android.app.PendingIntent.getActivity(context, 7742, returnIntent, piFlags)
                pi.send()
            }
            // 3. Also call startActivity directly
            context.startActivity(returnIntent)
            Log.i(TAG, "🔄 Returned user to TuneMind foreground while music streams in background.")
        } catch (e: Exception) {
            Log.d(TAG, "bringTuneMindToFrontNow notice: ${e.message}")
        }
    }

    /**
     * CRITICAL: When `spotifyTrackUri` (`spotify:track:<id>`) is known, we NEVER call `playFromSearch`
     * because `playFromSearch` switches Spotify out of the track player and onto a static Search Results screen!
     * Instead, we cue `playFromUri(spotifyTrackUri)` and fire `play()` + `KEYCODE_MEDIA_PLAY` across 6 waves
     * until `listener.isPlaying()` is true.
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

        // Stage 1: Cue track on active MediaSession
        val cueDelays = listOf(350L, 820L)
        for (delay in cueDelays) {
            mainHandler.postDelayed({
                try {
                    val listener = TuneMindListenerService.instance
                    if (!spotifyTrackUri.isNullOrBlank()) {
                        // Use ONLY exact URI transport controls + play() — do NOT call playFromSearch!
                        listener?.playFromUri(targetPackage, Uri.parse(spotifyTrackUri), extras)
                        listener?.play(targetPackage)
                    } else {
                        listener?.playFromSearch(targetPackage, query, extras)
                        listener?.play(targetPackage)
                    }
                } catch (t: Throwable) {
                    Log.d(TAG, "Auto-play cue pulse notice: ${t.message}")
                }
            }, delay)
        }

        // Stage 2: 6-wave Play Verification across 1.25s -> 4.5s.
        // If Spotify opened the track screen and is paused (`!listener.isPlaying()`),
        // fires `listener.play()` and `KEYCODE_MEDIA_PLAY` until playback is actively streaming.
        val playDelays = listOf(1250L, 1800L, 2450L, 3150L, 3850L, 4550L)
        for (delay in playDelays) {
            mainHandler.postDelayed({
                try {
                    val listener = TuneMindListenerService.instance
                    val activeTitle = listener?.getActiveTitle(targetPackage)?.trim()
                    val isStillOldTrack = !previousTitle.isNullOrBlank() &&
                        activeTitle.equals(previousTitle, ignoreCase = true) &&
                        !previousTitle.equals(cleanTitle, ignoreCase = true) &&
                        !previousTitle.equals(track.title.trim(), ignoreCase = true)

                    if (isStillOldTrack) {
                        if (!spotifyTrackUri.isNullOrBlank()) {
                            listener?.playFromUri(targetPackage, Uri.parse(spotifyTrackUri), extras)
                        } else {
                            listener?.playFromSearch(targetPackage, query, extras)
                        }
                        listener?.play(targetPackage)
                        dispatchMediaPlayKey(context, targetPackage)
                    } else if (listener == null || !listener.isPlaying()) {
                        listener?.play(targetPackage)
                        dispatchMediaPlayKey(context, targetPackage)
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
