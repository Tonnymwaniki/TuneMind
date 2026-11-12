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

        val primaryArtist = track.artist.split(",", "&", "feat.", "ft.").firstOrNull()?.trim() ?: track.artist.trim()
        val fullSearchQuery = if (primaryArtist.isNotBlank() && !track.title.contains(primaryArtist, ignoreCase = true)) {
            "${track.title} $primaryArtist".trim()
        } else {
            track.title.trim()
        }

        // Immediately launch direct track or search intent so Spotify loads the new track right away,
        // and only return to TuneMind once playback of the new song is confirmed active!
        val launched = launchIntentAndReturnToTuneMind(
            context = context,
            targetPkg = targetPkg,
            track = track,
            fullSearchQuery = fullSearchQuery,
            deepLinkUri = deepLinkUri,
            previousTitle = previousTitle
        )

        return if (launched) {
            PlaybackLaunchResult.DirectPlaybackLaunched(
                providerId = providerId,
                providerDisplayName = displayName,
                trackTitle = track.title,
                destinationUri = deepLinkUri ?: "intent:$targetPkg",
                message = "Playing '${track.title}' in $displayName."
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
        fullSearchQuery: String,
        deepLinkUri: String?,
        previousTitle: String?
    ): Boolean {
        // 1. Direct Track Deep Link (spotify:track:<id>)
        if (!deepLinkUri.isNullOrBlank() && (deepLinkUri.startsWith("spotify:track:") || deepLinkUri.startsWith("https://open.spotify.com/track/"))) {
            try {
                val uriIntent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLinkUri)).apply {
                    if (targetPkg != null) setPackage(targetPkg)
                    putExtra("android.intent.extra.START_PLAYBACK", true)
                    putExtra("autostart", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }
                context.startActivity(uriIntent)
                scheduleDirectTrackKicker(context, targetPkg, deepLinkUri, track, previousTitle)
                scheduleReturnToTuneMindWhenPlaying(context)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Direct track URI launch failed, falling back: ${e.message}")
            }
        }

        // 2. Voice Search Play (INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
        if (targetPkg != null) {
            try {
                val mediaPlayIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    putExtra(SearchManager.QUERY, fullSearchQuery)
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    putExtra("autostart", true)
                    putExtra("android.intent.extra.START_PLAYBACK", true)
                    setPackage(targetPkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }
                context.startActivity(mediaPlayIntent)
                scheduleAutoPlayKicker(context, targetPkg, track, fullSearchQuery, previousTitle)
                scheduleReturnToTuneMindWhenPlaying(context)
                return true
            } catch (e: Exception) {
                Log.d(TAG, "Media play from search not accepted by $targetPkg, falling back: ${e.message}")
            }
        }

        // 3. Deep Link with Search Query
        if (!deepLinkUri.isNullOrBlank()) {
            try {
                val uriIntent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLinkUri)).apply {
                    if (targetPkg != null) setPackage(targetPkg)
                    putExtra("android.intent.extra.START_PLAYBACK", true)
                    putExtra("autostart", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }
                context.startActivity(uriIntent)
                scheduleAutoPlayKicker(context, targetPkg, track, fullSearchQuery, previousTitle)
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
     * (at 3400ms), so Spotify is never interrupted while loading the track.
     */
    private fun scheduleReturnToTuneMindWhenPlaying(context: Context) {
        mainHandler.postDelayed({
            try {
                val listener = TuneMindListenerService.instance
                if (listener != null && listener.isPlaying()) {
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
        }, 3400L)
    }

    private fun scheduleDirectTrackKicker(
        context: Context,
        targetPackage: String?,
        deepLinkUri: String,
        track: RecommendedTrack,
        previousTitle: String?
    ) {
        val extras = android.os.Bundle().apply {
            putBoolean("android.intent.extra.START_PLAYBACK", true)
            putBoolean("autostart", true)
        }
        // Stage 1: Cue media ID / URI on active MediaSession
        mainHandler.postDelayed({
            try {
                val listener = TuneMindListenerService.instance
                listener?.playFromMediaId(targetPackage, deepLinkUri, extras)
                listener?.playFromUri(targetPackage, Uri.parse(deepLinkUri), extras)
            } catch (_: Throwable) {}
        }, 350L)

        // Stage 2: Trigger Play only if it's the new track; if still stuck on previousTitle, skipNext instead of replaying old track!
        val playDelays = listOf(850L, 1500L, 2300L)
        for (delay in playDelays) {
            mainHandler.postDelayed({
                try {
                    val listener = TuneMindListenerService.instance
                    val activeTitle = listener?.getActiveTitle(targetPackage)?.trim()
                    val isStillOldTrack = !previousTitle.isNullOrBlank() &&
                        activeTitle.equals(previousTitle, ignoreCase = true) &&
                        !previousTitle.equals(track.title.trim(), ignoreCase = true)

                    if (isStillOldTrack) {
                        Log.d(TAG, "Session still on old track '$previousTitle'; triggering skipNext/playFromUri instead of replaying old song")
                        listener?.playFromUri(targetPackage, Uri.parse(deepLinkUri), extras)
                        listener?.skipNext(targetPackage)
                    } else {
                        if (listener != null && !listener.isPlaying()) {
                            listener.play(targetPackage)
                        }
                        if (listener == null || !listener.isPlaying()) {
                            dispatchMediaPlayKey(context, targetPackage)
                        }
                    }
                } catch (_: Throwable) {}
            }, delay)
        }
    }

    fun scheduleAutoPlayKicker(
        context: Context,
        targetPackage: String?,
        track: RecommendedTrack,
        overrideQuery: String? = null,
        previousTitle: String? = null
    ) {
        val primaryArtist = track.artist.split(",", "&", "feat.", "ft.").firstOrNull()?.trim() ?: track.artist.trim()
        val query = overrideQuery ?: if (primaryArtist.isNotBlank() && !track.title.contains(primaryArtist, ignoreCase = true)) {
            "${track.title} $primaryArtist".trim()
        } else {
            track.title.trim()
        }

        val extras = android.os.Bundle().apply {
            putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putString(SearchManager.QUERY, query)
            putBoolean("android.intent.extra.START_PLAYBACK", true)
            putBoolean("autostart", true)
        }

        // Stage 1: Queue new track via MediaSession playFromSearch
        val searchDelays = listOf(350L, 900L)
        for (delay in searchDelays) {
            mainHandler.postDelayed({
                try {
                    val listener = TuneMindListenerService.instance
                    listener?.playFromSearch(targetPackage, query, extras)
                } catch (t: Throwable) {
                    Log.d(TAG, "Auto-play search pulse notice: ${t.message}")
                }
            }, delay)
        }

        // Stage 2: Start playback, guarding against replaying the old song from the top
        val playDelays = listOf(1300L, 2000L, 2800L)
        for (delay in playDelays) {
            mainHandler.postDelayed({
                try {
                    val listener = TuneMindListenerService.instance
                    val activeTitle = listener?.getActiveTitle(targetPackage)?.trim()
                    val isStillOldTrack = !previousTitle.isNullOrBlank() &&
                        activeTitle.equals(previousTitle, ignoreCase = true) &&
                        !previousTitle.equals(track.title.trim(), ignoreCase = true)

                    if (isStillOldTrack) {
                        Log.d(TAG, "Preventing replay of old song '$previousTitle'; dispatching playFromSearch + skipNext")
                        listener?.playFromSearch(targetPackage, query, extras)
                        listener?.skipNext(targetPackage)
                    } else {
                        if (listener != null && !listener.isPlaying()) {
                            listener.play(targetPackage)
                        }
                        if (listener == null || !listener.isPlaying()) {
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
