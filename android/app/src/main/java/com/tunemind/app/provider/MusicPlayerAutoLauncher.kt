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
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(uriIntent)
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

        // 2. PRIMARY SEARCH PLAY: INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH with autostart
        val fullSearchQuery = if (track.artist.isNotBlank() && !track.title.contains(track.artist, ignoreCase = true)) {
            "${track.title} ${track.artist}".trim()
        } else {
            track.title.trim()
        }

        if (targetPkg != null) {
            try {
                val mediaPlayIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    putExtra(SearchManager.QUERY, fullSearchQuery)
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
                    putExtra(MediaStore.EXTRA_MEDIA_TITLE, track.title)
                    if (track.artist.isNotBlank()) {
                        putExtra(MediaStore.EXTRA_MEDIA_ARTIST, track.artist)
                    }
                    putExtra("autostart", true)
                    putExtra("android.intent.extra.focus", "vnd.android.cursor.item/audio")
                    putExtra("android.intent.extra.START_PLAYBACK", true)
                    setPackage(targetPkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(mediaPlayIntent)
                scheduleAutoPlayKicker(context, targetPkg, track)
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
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(uriIntent)
                scheduleAutoPlayKicker(context, targetPkg, track)
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

    /**
     * Dispatches targeted playFromSearch commands to the active MediaSession so that the target
     * player searches and begins playing the NEW requested song.
     *
     * IMPORTANT: We NEVER dispatch blind KEYCODE_MEDIA_PLAY or togglePlayback() here because
     * if the player still holds the previous paused track in its queue, KEYCODE_MEDIA_PLAY
     * unpauses that stale song instead of playing the newly synced track!
     */
    fun scheduleAutoPlayKicker(context: Context, targetPackage: String?, track: RecommendedTrack) {
        val query = if (track.artist.isNotBlank() && !track.title.contains(track.artist, ignoreCase = true)) {
            "${track.title} ${track.artist}".trim()
        } else {
            track.title.trim()
        }
        val delays = listOf(500L, 1200L, 2000L)
        for (delay in delays) {
            mainHandler.postDelayed({
                try {
                    val listener = TuneMindListenerService.instance
                    if (listener != null) {
                        val extras = android.os.Bundle().apply {
                            putString(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
                            putString(android.app.SearchManager.QUERY, query)
                            putString(android.provider.MediaStore.EXTRA_MEDIA_TITLE, track.title)
                            if (track.artist.isNotBlank()) {
                                putString(android.provider.MediaStore.EXTRA_MEDIA_ARTIST, track.artist)
                            }
                            putBoolean("android.intent.extra.START_PLAYBACK", true)
                            putBoolean("autostart", true)
                        }
                        listener.playFromSearch(targetPackage, query, extras)
                        Log.d(TAG, "Dispatched playFromSearch kicker for '$query' to $targetPackage")
                    }
                } catch (t: Throwable) {
                    Log.d(TAG, "Auto-play kicker pulse notice: ${t.message}")
                }
            }, delay)
        }
    }
}
