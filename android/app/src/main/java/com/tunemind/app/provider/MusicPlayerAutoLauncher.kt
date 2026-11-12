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

        // 0. IMMEDIATE HARD INTERRUPTION: Silence any currently singing playback
        try {
            val listener = TuneMindListenerService.instance
            listener?.pause()
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        } catch (e: Exception) {
            Log.d(TAG, "Audio pause interruption note: ${e.message}")
        }

        // 1. TOP PRIORITY: Direct Track Deep Link (e.g. spotify:track:<id> or web track URL)
        // This directly loads and starts playback of the exact synced track without searching.
        if (!deepLinkUri.isNullOrBlank() && (deepLinkUri.startsWith("spotify:track:") || deepLinkUri.startsWith("https://open.spotify.com/track/"))) {
            try {
                val uriIntent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLinkUri)).apply {
                    if (targetPkg != null) setPackage(targetPkg)
                    putExtra("android.intent.extra.START_PLAYBACK", true)
                    putExtra("autostart", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(uriIntent)
                scheduleDirectTrackKicker(context, targetPkg, deepLinkUri, track)
                Log.i(TAG, "Launched direct track URI in $targetPkg: $deepLinkUri")
                return PlaybackLaunchResult.DirectPlaybackLaunched(
                    providerId = providerId,
                    providerDisplayName = displayName,
                    trackTitle = track.title,
                    destinationUri = deepLinkUri,
                    message = "Playing '${track.title}' directly in $displayName."
                )
            } catch (e: Exception) {
                Log.w(TAG, "Direct track URI launch failed, falling back: ${e.message}")
            }
        }

        // 2. PRIMARY VOICE SEARCH PLAY: INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH with unstructured voice focus
        val primaryArtist = track.artist.split(",", "&", "feat.", "ft.").firstOrNull()?.trim() ?: track.artist.trim()
        val fullSearchQuery = if (primaryArtist.isNotBlank() && !track.title.contains(primaryArtist, ignoreCase = true)) {
            "${track.title} $primaryArtist".trim()
        } else {
            track.title.trim()
        }

        if (targetPkg != null) {
            try {
                val mediaPlayIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    putExtra(SearchManager.QUERY, fullSearchQuery)
                    // Unstructured focus ("vnd.android.cursor.item/*") instructs Spotify's voice handler
                    // to auto-play the top match immediately instead of displaying a static search list.
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    putExtra("autostart", true)
                    putExtra("android.intent.extra.START_PLAYBACK", true)
                    setPackage(targetPkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(mediaPlayIntent)
                scheduleAutoPlayKicker(context, targetPkg, track, fullSearchQuery)
                return PlaybackLaunchResult.DirectPlaybackLaunched(
                    providerId = providerId,
                    providerDisplayName = displayName,
                    trackTitle = track.title,
                    destinationUri = "intent:$targetPkg",
                    message = "Auto-playing '${track.title}' in $displayName."
                )
            } catch (e: Exception) {
                Log.d(TAG, "Media play from search not accepted by $targetPkg, falling back to deep link: ${e.message}")
            }
        }

        // 3. DEEP LINK WITH SEARCH QUERY
        if (!deepLinkUri.isNullOrBlank()) {
            try {
                val uriIntent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLinkUri)).apply {
                    if (targetPkg != null) setPackage(targetPkg)
                    putExtra("android.intent.extra.START_PLAYBACK", true)
                    putExtra("autostart", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(uriIntent)
                scheduleAutoPlayKicker(context, targetPkg, track, fullSearchQuery)
                return PlaybackLaunchResult.SearchHandoffLaunched(
                    providerId = providerId,
                    providerDisplayName = displayName,
                    query = query,
                    message = "Auto-starting '${track.title}' in $displayName."
                )
            } catch (e: Exception) {
                Log.w(TAG, "Deep link launch failed: ${e.message}")
            }
        }

        return PlaybackLaunchResult.LaunchFailed(
            providerId = providerId,
            reason = "Could not start playback in $displayName."
        )
    }

    private fun scheduleDirectTrackKicker(
        context: Context,
        targetPackage: String?,
        deepLinkUri: String,
        track: RecommendedTrack
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
        }, 450L)

        // Stage 2: Trigger Play transport control + media button after Spotify loads the track screen
        val playDelays = listOf(1000L, 1800L, 2600L)
        for (delay in playDelays) {
            mainHandler.postDelayed({
                try {
                    val listener = TuneMindListenerService.instance
                    if (listener != null && !listener.isPlaying()) {
                        listener.play(targetPackage)
                    }
                    dispatchMediaPlayKey(context, targetPackage)
                } catch (_: Throwable) {}
            }, delay)
        }
    }

    /**
     * Multi-stage auto-play sequence:
     * 1. At 450ms & 1050ms: Dispatches playFromSearch to the active MediaSession so the new track is queued.
     * 2. At 1600ms, 2400ms & 3200ms: Once the player has loaded the new track screen, fires transportControls.play()
     *    and KEYCODE_MEDIA_PLAY so the song starts playing automatically without manual user tap.
     */
    fun scheduleAutoPlayKicker(
        context: Context,
        targetPackage: String?,
        track: RecommendedTrack,
        overrideQuery: String? = null
    ) {
        val primaryArtist = track.artist.split(",", "&", "feat.", "ft.").firstOrNull()?.trim() ?: track.artist.trim()
        val query = overrideQuery ?: if (primaryArtist.isNotBlank() && !track.title.contains(primaryArtist, ignoreCase = true)) {
            "${track.title} $primaryArtist".trim()
        } else {
            track.title.trim()
        }

        // Stage 1: Queue new track via MediaSession playFromSearch
        val searchDelays = listOf(450L, 1050L)
        for (delay in searchDelays) {
            mainHandler.postDelayed({
                try {
                    val listener = TuneMindListenerService.instance
                    if (listener != null) {
                        val extras = android.os.Bundle().apply {
                            putString(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                            putString(android.app.SearchManager.QUERY, query)
                            putBoolean("android.intent.extra.START_PLAYBACK", true)
                            putBoolean("autostart", true)
                        }
                        listener.playFromSearch(targetPackage, query, extras)
                        Log.d(TAG, "Dispatched playFromSearch kicker for '$query' to $targetPackage")
                    }
                } catch (t: Throwable) {
                    Log.d(TAG, "Auto-play search pulse notice: ${t.message}")
                }
            }, delay)
        }

        // Stage 2: Start playback once the new track screen is loaded (1600ms, 2400ms, 3200ms)
        val playDelays = listOf(1600L, 2400L, 3200L)
        for (delay in playDelays) {
            mainHandler.postDelayed({
                try {
                    val listener = TuneMindListenerService.instance
                    if (listener != null && !listener.isPlaying()) {
                        listener.play(targetPackage)
                    }
                    if (listener == null || !listener.isPlaying()) {
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
