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
 * Employs multi-tier auto-start dispatch:
 * 1. Active MediaSession direct transport injection (instant zero-handoff play)
 * 2. MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH with 'autostart' & audio focus flags
 * 3. Deep-link fallback with post-launch delayed Media Play key events to ensure
 *    playback starts automatically without requiring the user to tap play.
 */
object MusicPlayerAutoLauncher {
    private const val TAG = "MusicPlayerAutoLauncher"
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Strips noisy suffixes like ", Tenzi No 22.", "(Official Video)", "[Lyrics]", etc.
     * that cause Spotify's search auto-player to show search results instead of auto-playing.
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

        // 0. IMMEDIATE HARD INTERRUPTION: Silence any currently playing song before switching
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
        val normalizedSpotifyUri = normalizeSpotifyTrackUri(deepLinkUri)

        // 1. PRIMARY AUTO-PLAY ENGINE: Voice Assistant Media Play From Search with specific Audio Entry Focus.
        // Unlike Intent.ACTION_VIEW on spotify:track:<id> (which in Spotify Android often opens the static track card
        // without starting playback), INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH with ENTRY_CONTENT_TYPE + EXTRA_MEDIA_TITLE
        // triggers Spotify's internal voice assistant auto-player so the track streams immediately!
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
                    putExtra("android.intent.extra.START_PLAYBACK", true)
                    putExtra("autostart", true)
                    putExtra("play", true)
                    setPackage(targetPkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }
                context.startActivity(mediaPlayIntent)
                scheduleAutoPlayKicker(
                    context = context,
                    targetPackage = targetPkg,
                    track = track,
                    overrideQuery = fullSearchQuery,
                    previousTitle = previousTitle,
                    spotifyTrackUri = normalizedSpotifyUri
                )
                scheduleReturnToTuneMindWhenPlaying(context)
                return true
            } catch (e: Exception) {
                Log.d(TAG, "Media play from search not accepted by $targetPkg, falling back: ${e.message}")
            }
        }

        // 2. Direct Track Deep Link Fallback (spotify:track:<id>) + aggressive MediaSession kicker
        if (!normalizedSpotifyUri.isNullOrBlank()) {
            try {
                val uriIntent = Intent(Intent.ACTION_VIEW, Uri.parse(normalizedSpotifyUri)).apply {
                    if (targetPkg != null) setPackage(targetPkg)
                    putExtra("android.intent.extra.START_PLAYBACK", true)
                    putExtra("autostart", true)
                    putExtra("play", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }
                context.startActivity(uriIntent)
                scheduleAutoPlayKicker(
                    context = context,
                    targetPackage = targetPkg,
                    track = track,
                    overrideQuery = fullSearchQuery,
                    previousTitle = previousTitle,
                    spotifyTrackUri = normalizedSpotifyUri
                )
                scheduleReturnToTuneMindWhenPlaying(context)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Direct track URI launch failed, falling back: ${e.message}")
            }
        }

        // 3. Generic Deep Link Fallback
        if (!deepLinkUri.isNullOrBlank()) {
            try {
                val uriIntent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLinkUri)).apply {
                    if (targetPkg != null) setPackage(targetPkg)
                    putExtra("android.intent.extra.START_PLAYBACK", true)
                    putExtra("autostart", true)
                    putExtra("play", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }
                context.startActivity(uriIntent)
                scheduleAutoPlayKicker(
                    context = context,
                    targetPackage = targetPkg,
                    track = track,
                    overrideQuery = fullSearchQuery,
                    previousTitle = previousTitle,
                    spotifyTrackUri = normalizedSpotifyUri
                )
                scheduleReturnToTuneMindWhenPlaying(context)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Deep link launch failed: ${e.message}")
            }
        }
        return false
    }

    /**
     * Brings TuneMind back to the foreground ONLY AFTER Spotify has started playing the new song
     * (checked at 3600ms and 4600ms), so Spotify is never interrupted while buffering the track.
     */
    private fun scheduleReturnToTuneMindWhenPlaying(context: Context) {
        var returned = false
        for (checkDelay in listOf(3600L, 4700L)) {
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

        val extras = android.os.Bundle().apply {
            putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
            if (cleanTitle.isNotBlank()) putString(MediaStore.EXTRA_MEDIA_TITLE, cleanTitle)
            if (cleanArtist.isNotBlank()) putString(MediaStore.EXTRA_MEDIA_ARTIST, cleanArtist)
            putString(SearchManager.QUERY, query)
            putBoolean("android.intent.extra.START_PLAYBACK", true)
            putBoolean("autostart", true)
            putBoolean("play", true)
        }

        // Stage 1: Cue new track on active MediaSession via playFromSearch + playFromUri (if URI known)
        val cueDelays = listOf(380L, 880L)
        for (delay in cueDelays) {
            mainHandler.postDelayed({
                try {
                    val listener = TuneMindListenerService.instance
                    if (!spotifyTrackUri.isNullOrBlank()) {
                        listener?.playFromUri(targetPackage, Uri.parse(spotifyTrackUri), extras)
                        listener?.playFromMediaId(targetPackage, spotifyTrackUri, extras)
                    }
                    listener?.playFromSearch(targetPackage, query, extras)
                } catch (t: Throwable) {
                    Log.d(TAG, "Auto-play cue pulse notice: ${t.message}")
                }
            }, delay)
        }

        // Stage 2: Multi-wave Play Verification across 1.35s -> 4.3s.
        // If Spotify opened the track screen or buffered the track without starting playback,
        // this fires transportControls.play() and KEYCODE_MEDIA_PLAY until listener.isPlaying() is true!
        val playDelays = listOf(1350L, 1950L, 2650L, 3450L, 4300L)
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
                        Log.d(TAG, "Session still on old track '$previousTitle'; dispatching playFromSearch/Uri + play")
                        if (!spotifyTrackUri.isNullOrBlank()) {
                            listener?.playFromUri(targetPackage, Uri.parse(spotifyTrackUri), extras)
                        }
                        listener?.playFromSearch(targetPackage, query, extras)
                        listener?.play(targetPackage)
                    } else {
                        if (listener == null || !listener.isPlaying()) {
                            listener?.play(targetPackage)
                            dispatchMediaPlayKey(context, targetPackage)
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
