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

        // 1. PRIMARY: INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH with autostart
        var launched = false
        if (targetPkg != null) {
            try {
                val mediaPlayIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    putExtra(SearchManager.QUERY, "${track.artist} ${track.title}".trim())
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
                    putExtra(MediaStore.EXTRA_MEDIA_TITLE, track.title)
                    putExtra(MediaStore.EXTRA_MEDIA_ARTIST, track.artist)
                    putExtra("autostart", true)
                    putExtra("android.intent.extra.focus", "vnd.android.cursor.item/audio")
                    putExtra("android.intent.extra.START_PLAYBACK", true)
                    setPackage(targetPkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(mediaPlayIntent)
                launched = true
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

        // 2. DEEP LINK WITH AUTO-PLAY KICKER
        if (!deepLinkUri.isNullOrBlank()) {
            try {
                val uriIntent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLinkUri)).apply {
                    if (targetPkg != null) setPackage(targetPkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(uriIntent)
                launched = true
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
     * Schedules multi-stage background signals (400ms, 1000ms, 1800ms, 2600ms) to ensure
     * that once the target music player gains foreground focus, playback starts immediately.
     */
    fun scheduleAutoPlayKicker(context: Context, targetPackage: String?, track: RecommendedTrack) {
        val query = "${track.artist} ${track.title}".trim()
        val delays = listOf(400L, 1000L, 1800L, 2600L)
        for (delay in delays) {
            mainHandler.postDelayed({
                try {
                    // 1. Try via NotificationListener active media controller with audio focus bundle
                    val listener = TuneMindListenerService.instance
                    if (listener != null) {
                        val extras = android.os.Bundle().apply {
                            putString(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
                            putString(android.app.SearchManager.QUERY, query)
                            putString(android.provider.MediaStore.EXTRA_MEDIA_TITLE, track.title)
                            putString(android.provider.MediaStore.EXTRA_MEDIA_ARTIST, track.artist)
                            putBoolean("android.intent.extra.START_PLAYBACK", true)
                            putBoolean("autostart", true)
                        }
                        listener.playFromSearch(targetPackage, query, extras)
                        if (!listener.isPlaying()) {
                            listener.togglePlayback()
                        }
                    }

                    // 2. Targeted Media Button broadcast directly to package (e.g. Spotify background receiver)
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

                    // 3. Hardware media play signal
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                    audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
                } catch (t: Throwable) {
                    Log.d(TAG, "Auto-play kicker pulse notice: ${t.message}")
                }
            }, delay)
        }
    }
}
