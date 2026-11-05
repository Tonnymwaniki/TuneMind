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

        // 1. FAST PATH: Active MediaSession Controller
        try {
            val listener = TuneMindListenerService.instance
            if (listener != null) {
                val sent = listener.playFromSearch(targetPkg, query)
                if (sent) {
                    scheduleAutoPlayKicker(context, targetPkg, query)
                    return PlaybackLaunchResult.DirectPlaybackLaunched(
                        providerId = providerId,
                        providerDisplayName = displayName,
                        trackTitle = track.title,
                        destinationUri = "mediasession:${targetPkg ?: "active"}",
                        message = "Auto-playing '${track.title}' in $displayName."
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fast path media session play failed: ${e.message}")
        }

        // 2. PRIMARY: INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH with autostart
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
                scheduleAutoPlayKicker(context, targetPkg, query)
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

        // 3. DEEP LINK WITH AUTO-PLAY KICKER
        if (!deepLinkUri.isNullOrBlank()) {
            try {
                val uriIntent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLinkUri)).apply {
                    if (targetPkg != null) setPackage(targetPkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(uriIntent)
                launched = true
                scheduleAutoPlayKicker(context, targetPkg, query)
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

        // 4. WEB FALLBACK
        if (!webFallbackUrl.isNullOrBlank()) {
            return try {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webFallbackUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
                PlaybackLaunchResult.SearchHandoffLaunched(
                    providerId = providerId,
                    providerDisplayName = displayName,
                    query = query,
                    message = "Opened '${track.title}' in web player."
                )
            } catch (e: Exception) {
                PlaybackLaunchResult.LaunchFailed(
                    providerId = providerId,
                    reason = "Could not launch playback: ${e.message}"
                )
            }
        }

        return PlaybackLaunchResult.LaunchFailed(
            providerId = providerId,
            reason = "No compatible launch path found for $displayName."
        )
    }

    /**
     * Schedules multi-stage background signals (500ms, 1200ms, 2200ms) to ensure
     * that once the target music player gains foreground focus, playback starts immediately.
     */
    fun scheduleAutoPlayKicker(context: Context, targetPackage: String?, query: String) {
        val delays = listOf(500L, 1200L, 2200L)
        for (delay in delays) {
            mainHandler.postDelayed({
                try {
                    // Try via NotificationListener active media controller
                    val listener = TuneMindListenerService.instance
                    if (listener != null) {
                        listener.playFromSearch(targetPackage, query)
                        val obs = listener.observerOrNull()
                        val isPlaying = obs?.controllers?.values?.any {
                            it.controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
                        } ?: false
                        if (!isPlaying) {
                            obs?.togglePlayback()
                        }
                    }

                    // Also dispatch hardware media key event
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
