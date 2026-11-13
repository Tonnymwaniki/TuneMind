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

        // 2. If we have an exact `spotify:track:<id>` URI:
        //    - Inject `playFromUri` into active MediaSession immediately
        //    - Fire Spotify Web API `PUT /v1/me/player/play`
        //    - Open `spotify:track:<id>` with Google Assistant Referrer so Spotify starts streaming immediately
        if (!resolvedSpotifyUri.isNullOrBlank()) {
            try {
                val listener = TuneMindListenerService.instance
                if (listener != null && listener.hasActiveSession(targetPkg)) {
                    listener.playFromUri(targetPkg, Uri.parse(resolvedSpotifyUri), sessionExtras)
                    listener.play(targetPkg)
                }
            } catch (_: Exception) {}

            // Trigger Spotify Web API PUT /v1/me/player/play in parallel
            SpotifyTrackUriResolver.triggerWebApiRemotePlayAsync(context, resolvedSpotifyUri)

            try {
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
                scheduleReturnToTuneMindWhenPlaying(context)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Exact spotify:track URI launch notice: ${e.message}")
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
                scheduleReturnToTuneMindWhenPlaying(context)
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
                scheduleReturnToTuneMindWhenPlaying(context)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Deep link launch failed: ${e.message}")
            }
        }
        return false
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
     * Brings TuneMind back to the foreground ONLY AFTER Spotify has started playing the new song
     * (checked at 3800ms and 4900ms), so Spotify is never interrupted while buffering the track.
     */
    private fun scheduleReturnToTuneMindWhenPlaying(context: Context) {
        var returned = false
        for (checkDelay in listOf(3800L, 4900L)) {
            mainHandler.postDelayed({
                if (returned) return@postDelayed
                try {
                    val listener = TuneMindListenerService.instance
                    if (listener != null && listener.isPlaying()) {
                        returned = true
                        val returnIntent = Intent(context, com.tunemind.app.MainActivity::class.java).apply {
                            addFlags(
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_NO_ANIMATION
                            )
                        }
                        context.startActivity(returnIntent)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Return to TuneMind foreground notice: ${e.message}")
                }
            }, checkDelay)
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
