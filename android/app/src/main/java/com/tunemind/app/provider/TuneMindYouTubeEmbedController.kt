package com.tunemind.app.provider

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.tunemind.app.domain.event.ObservationEventType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/**
 * Option A — Play-Store- & YouTube-ToS-Compliant Built-In Visible YouTube Player Controller.
 *
 * Audience-gated: activates for users whose target/default music player is YouTube Music
 * (`com.google.android.apps.youtube.music`) or when the user explicitly allows TuneMind
 * to use the built-in YouTube player to stay inside TuneMind without leaving the app.
 *
 * The companion WebView surface (`TuneMindYouTubeExpandedViewport` / `TuneMindYouTubeMiniDock`)
 * MUST remain visible on screen while playing (never 1x1dp, hidden, or background-only).
 */
object TuneMindYouTubeEmbedController {
    private const val TAG = "TuneMindYouTubeEmbed"
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _hasActiveVideoFlow = MutableStateFlow(false)
    val hasActiveVideoFlow: StateFlow<Boolean> = _hasActiveVideoFlow.asStateFlow()

    private val _isPlayingFlow = MutableStateFlow(false)
    val isPlayingFlow: StateFlow<Boolean> = _isPlayingFlow.asStateFlow()

    private val _isBufferingFlow = MutableStateFlow(false)
    val isBufferingFlow: StateFlow<Boolean> = _isBufferingFlow.asStateFlow()

    private val _activeVideoIdFlow = MutableStateFlow<String?>(null)
    val activeVideoIdFlow: StateFlow<String?> = _activeVideoIdFlow.asStateFlow()

    private val _currentTitleFlow = MutableStateFlow<String?>(null)
    val currentTitleFlow: StateFlow<String?> = _currentTitleFlow.asStateFlow()

    private val _currentArtistFlow = MutableStateFlow<String?>(null)
    val currentArtistFlow: StateFlow<String?> = _currentArtistFlow.asStateFlow()

    private val _positionMsFlow = MutableStateFlow(0L)
    val positionMsFlow: StateFlow<Long> = _positionMsFlow.asStateFlow()

    private val _durationMsFlow = MutableStateFlow(0L)
    val durationMsFlow: StateFlow<Long> = _durationMsFlow.asStateFlow()

    private val _fallbackExternalPkgFlow = MutableStateFlow<String?>(YouTubeMusicProvider.YTM_PACKAGE)
    val fallbackExternalPkgFlow: StateFlow<String?> = _fallbackExternalPkgFlow.asStateFlow()

    private val _fallbackExternalNameFlow = MutableStateFlow<String?>("YouTube Music")
    val fallbackExternalNameFlow: StateFlow<String?> = _fallbackExternalNameFlow.asStateFlow()

    private val _statusNoticeFlow = MutableStateFlow<String?>(null)
    val statusNoticeFlow: StateFlow<String?> = _statusNoticeFlow.asStateFlow()

    @Volatile
    var lastStateUpdatedMs: Long = 0L
        private set

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var currentAlbum: String? = null

    @Volatile
    private var previewFallbackUrl: String? = null

    @Volatile
    private var webViewRef: WeakReference<WebView>? = null

    @Volatile
    private var isPlayerReady = false

    @Volatile
    private var pendingLoadVideoId: String? = null

    @Volatile
    private var lastObservedPersistMs: Long = 0L

    fun isPlaying(): Boolean = _hasActiveVideoFlow.value && _isPlayingFlow.value

    fun hasActiveVideo(): Boolean = _hasActiveVideoFlow.value && !_activeVideoIdFlow.value.isNullOrBlank()

    fun buildJsBridge(): Any = YouTubeJsBridge()

    fun attachWebView(webView: WebView) {
        val prev = webViewRef?.get()
        if (prev !== webView) {
            isPlayerReady = false
        }
        webViewRef = WeakReference(webView)
        val pendingId = pendingLoadVideoId ?: _activeVideoIdFlow.value
        if (isPlayerReady && !pendingId.isNullOrBlank()) {
            loadVideoInWebView(pendingId)
        }
    }

    fun detachWebView(webView: WebView) {
        if (webViewRef?.get() === webView) {
            webViewRef = null
            isPlayerReady = false
        }
    }

    fun startVideo(
        context: Context,
        videoId: String,
        title: String,
        artist: String = "",
        album: String? = null,
        previewFallback: String? = null,
        fallbackExternalPkg: String? = YouTubeMusicProvider.YTM_PACKAGE,
        fallbackExternalName: String? = "YouTube Music"
    ): Boolean {
        val cleanId = videoId.trim().takeIf { it.isNotBlank() } ?: return false
        val appCtx = context.applicationContext
        appContext = appCtx
        currentAlbum = album
        previewFallbackUrl = previewFallback?.takeIf { it.startsWith("http") }
        _fallbackExternalPkgFlow.value = fallbackExternalPkg ?: YouTubeMusicProvider.YTM_PACKAGE
        _fallbackExternalNameFlow.value = fallbackExternalName?.ifBlank { "YouTube Music" } ?: "YouTube Music"

        // Ensure native MediaPlayer never overlaps our visible YouTube stream.
        runCatching { TuneMindInAppPlayer.stop(clearYouTube = false) }
        TuneMindInAppPlayer.requestExternalEmbeddedAudioFocus(appCtx)

        _statusNoticeFlow.value = null
        _currentTitleFlow.value = title.trim()
        _currentArtistFlow.value = artist.trim()
        _activeVideoIdFlow.value = cleanId
        _hasActiveVideoFlow.value = true
        _isPlayingFlow.value = true
        _isBufferingFlow.value = true
        _positionMsFlow.value = 0L
        _durationMsFlow.value = 0L
        lastStateUpdatedMs = System.currentTimeMillis()
        lastObservedPersistMs = 0L
        pendingLoadVideoId = cleanId

        mainHandler.post {
            if (isPlayerReady) {
                loadVideoInWebView(cleanId)
            }
        }

        val watchUrl = "https://music.youtube.com/watch?v=$cleanId"
        TuneMindInAppPlayer.publishExternalEmbeddedSnapshot(
            context = appCtx,
            title = title.trim(),
            artist = artist.trim(),
            isPlaying = true,
            positionMs = 0L,
            durationMs = 0L
        )
        TuneMindInAppPlayer.recordExternalEmbeddedObservation(
            context = appCtx,
            eventType = ObservationEventType.METADATA_CHANGED,
            title = title.trim(),
            artist = artist.trim(),
            album = album,
            trackUri = watchUrl,
            durationMs = 0L,
            positionMs = 0L,
            playbackStateCode = 3
        )
        Log.i(TAG, "Started built-in visible YouTube playback for '$title' (videoId=$cleanId)")
        return true
    }

    fun pause(): Boolean {
        if (!hasActiveVideo()) return false
        _isPlayingFlow.value = false
        lastStateUpdatedMs = System.currentTimeMillis()
        evaluateJs("window.tmPauseVideo && window.tmPauseVideo();")
        appContext?.let { ctx ->
            TuneMindInAppPlayer.publishExternalEmbeddedSnapshot(
                context = ctx,
                title = _currentTitleFlow.value.orEmpty(),
                artist = _currentArtistFlow.value.orEmpty(),
                isPlaying = false,
                positionMs = _positionMsFlow.value,
                durationMs = _durationMsFlow.value
            )
        }
        return true
    }

    fun resume(): Boolean {
        if (!hasActiveVideo()) return false
        appContext?.let { TuneMindInAppPlayer.requestExternalEmbeddedAudioFocus(it) }
        _isPlayingFlow.value = true
        lastStateUpdatedMs = System.currentTimeMillis()
        evaluateJs("window.tmPlayVideo && window.tmPlayVideo();")
        appContext?.let { ctx ->
            TuneMindInAppPlayer.publishExternalEmbeddedSnapshot(
                context = ctx,
                title = _currentTitleFlow.value.orEmpty(),
                artist = _currentArtistFlow.value.orEmpty(),
                isPlaying = true,
                positionMs = _positionMsFlow.value,
                durationMs = _durationMsFlow.value
            )
        }
        return true
    }

    fun togglePlayback(): Boolean = if (_isPlayingFlow.value) pause() else resume()

    fun seekTo(positionMs: Long): Boolean {
        if (!hasActiveVideo()) return false
        val clamped = positionMs.coerceAtLeast(0L)
        val seconds = clamped / 1000.0
        _positionMsFlow.value = clamped
        lastStateUpdatedMs = System.currentTimeMillis()
        evaluateJs("window.tmSeekTo && window.tmSeekTo($seconds);")
        appContext?.let { ctx ->
            TuneMindInAppPlayer.publishExternalEmbeddedSnapshot(
                context = ctx,
                title = _currentTitleFlow.value.orEmpty(),
                artist = _currentArtistFlow.value.orEmpty(),
                isPlaying = _isPlayingFlow.value,
                positionMs = clamped,
                durationMs = _durationMsFlow.value
            )
        }
        return true
    }

    fun stop(clearMetadata: Boolean = true) {
        if (!_hasActiveVideoFlow.value && !clearMetadata) return
        val activeTitle = _currentTitleFlow.value?.takeIf { it.isNotBlank() }
        val activeId = _activeVideoIdFlow.value
        val posSnapshot = _positionMsFlow.value
        val durSnapshot = _durationMsFlow.value
        if (activeTitle != null && _hasActiveVideoFlow.value) {
            appContext?.let { ctx ->
                TuneMindInAppPlayer.recordExternalEmbeddedObservation(
                    context = ctx,
                    eventType = ObservationEventType.PLAYBACK_STATE_CHANGED,
                    title = activeTitle,
                    artist = _currentArtistFlow.value.orEmpty(),
                    album = currentAlbum,
                    trackUri = activeId?.let { "https://music.youtube.com/watch?v=$it" },
                    durationMs = durSnapshot,
                    positionMs = posSnapshot,
                    playbackStateCode = 2
                )
            }
        }
        _isPlayingFlow.value = false
        _isBufferingFlow.value = false
        pendingLoadVideoId = null
        evaluateJs("window.tmStopVideo && window.tmStopVideo();")
        if (clearMetadata) {
            _hasActiveVideoFlow.value = false
            _activeVideoIdFlow.value = null
            _currentTitleFlow.value = null
            _currentArtistFlow.value = null
            _positionMsFlow.value = 0L
            _durationMsFlow.value = 0L
            currentAlbum = null
            previewFallbackUrl = null
        }
    }

    fun clearIfNotPlaying() {
        if (!isPlaying() && _hasActiveVideoFlow.value) {
            stop(clearMetadata = true)
        }
    }

    private fun loadVideoInWebView(videoId: String) {
        pendingLoadVideoId = null
        val escaped = videoId.replace("'", "")
        evaluateJs("window.tmLoadVideo && window.tmLoadVideo('$escaped');")
    }

    private fun evaluateJs(script: String) {
        mainHandler.post {
            val wv = webViewRef?.get() ?: return@post
            runCatching { wv.evaluateJavascript(script, null) }
        }
    }

    private class YouTubeJsBridge {
        @JavascriptInterface
        fun onPlayerReady() {
            mainHandler.post {
                isPlayerReady = true
                _statusNoticeFlow.value = null
                val targetId = pendingLoadVideoId ?: _activeVideoIdFlow.value
                if (!targetId.isNullOrBlank()) {
                    loadVideoInWebView(targetId)
                }
            }
        }

        @JavascriptInterface
        fun onStateChange(stateCode: Int) {
            mainHandler.post {
                val now = System.currentTimeMillis()
                lastStateUpdatedMs = now
                // YouTube IFrame states: -1 unstarted, 0 ended, 1 playing, 2 paused, 3 buffering, 5 cued
                when (stateCode) {
                    1 -> {
                        _isPlayingFlow.value = true
                        _isBufferingFlow.value = false
                        _statusNoticeFlow.value = null
                    }
                    2 -> {
                        _isPlayingFlow.value = false
                        _isBufferingFlow.value = false
                    }
                    3 -> {
                        _isBufferingFlow.value = true
                    }
                    0 -> {
                        _isPlayingFlow.value = false
                        _isBufferingFlow.value = false
                        val doneTitle = _currentTitleFlow.value.orEmpty()
                        val doneArtist = _currentArtistFlow.value.orEmpty()
                        val ctx = appContext
                        if (ctx != null && doneTitle.isNotBlank()) {
                            val dur = _durationMsFlow.value
                            val vid = _activeVideoIdFlow.value
                            TuneMindInAppPlayer.publishExternalEmbeddedSnapshot(
                                context = ctx,
                                title = doneTitle,
                                artist = doneArtist,
                                isPlaying = false,
                                positionMs = dur,
                                durationMs = dur
                            )
                            TuneMindInAppPlayer.recordExternalEmbeddedObservation(
                                context = ctx,
                                eventType = ObservationEventType.PLAYBACK_STATE_CHANGED,
                                title = doneTitle,
                                artist = doneArtist,
                                album = currentAlbum,
                                trackUri = vid?.let { "https://music.youtube.com/watch?v=$it" },
                                durationMs = dur,
                                positionMs = dur,
                                playbackStateCode = 2
                            )
                            TuneMindInAppPlayer.dispatchExternalTrackCompleted(
                                title = doneTitle,
                                artist = doneArtist,
                                packageName = ctx.packageName
                            )
                        }
                    }
                }
                val ctx = appContext
                val title = _currentTitleFlow.value?.takeIf { it.isNotBlank() }
                if (ctx != null && title != null && (stateCode == 1 || stateCode == 2)) {
                    TuneMindInAppPlayer.publishExternalEmbeddedSnapshot(
                        context = ctx,
                        title = title,
                        artist = _currentArtistFlow.value.orEmpty(),
                        isPlaying = stateCode == 1,
                        positionMs = _positionMsFlow.value,
                        durationMs = _durationMsFlow.value
                    )
                    if (now - lastObservedPersistMs >= 4_000L) {
                        lastObservedPersistMs = now
                        TuneMindInAppPlayer.recordExternalEmbeddedObservation(
                            context = ctx,
                            eventType = ObservationEventType.PLAYBACK_STATE_CHANGED,
                            title = title,
                            artist = _currentArtistFlow.value.orEmpty(),
                            album = currentAlbum,
                            trackUri = _activeVideoIdFlow.value?.let { "https://music.youtube.com/watch?v=$it" },
                            durationMs = _durationMsFlow.value,
                            positionMs = _positionMsFlow.value,
                            playbackStateCode = if (stateCode == 1) 3 else 2
                        )
                    }
                }
            }
        }

        @JavascriptInterface
        fun onProgress(currentSec: Double, durationSec: Double) {
            mainHandler.post {
                val posMs = (currentSec * 1000.0).toLong().coerceAtLeast(0L)
                val durMs = (durationSec * 1000.0).toLong().coerceAtLeast(0L)
                _positionMsFlow.value = posMs
                if (durMs > 0L) _durationMsFlow.value = durMs
                lastStateUpdatedMs = System.currentTimeMillis()
                val ctx = appContext
                val title = _currentTitleFlow.value?.takeIf { it.isNotBlank() }
                if (ctx != null && title != null) {
                    TuneMindInAppPlayer.publishExternalEmbeddedSnapshot(
                        context = ctx,
                        title = title,
                        artist = _currentArtistFlow.value.orEmpty(),
                        isPlaying = _isPlayingFlow.value,
                        positionMs = posMs,
                        durationMs = if (durMs > 0L) durMs else _durationMsFlow.value
                    )
                }
            }
        }

        @JavascriptInterface
        fun onError(errorCode: Int) {
            mainHandler.post {
                Log.w(TAG, "YouTube IFrame reported embed error code=$errorCode")
                val ctx = appContext
                val title = _currentTitleFlow.value.orEmpty()
                val artist = _currentArtistFlow.value.orEmpty()
                val album = currentAlbum
                val fallbackPreview = previewFallbackUrl
                val fallbackPkg = _fallbackExternalPkgFlow.value ?: YouTubeMusicProvider.YTM_PACKAGE
                val fallbackName = _fallbackExternalNameFlow.value ?: "YouTube Music"

                stop(clearMetadata = true)
                val stayLocked = ctx != null && (
                    com.tunemind.app.domain.voice.AgentSettingsStore.isStayInTuneMindEnabled(ctx) ||
                        com.tunemind.app.domain.voice.AgentSettingsStore.isStrictNoAppSwitchEnabled(ctx)
                    )

                fun playInAppPreviewFallbackOnEmbedError() {
                    _statusNoticeFlow.value = if (!fallbackPreview.isNullOrBlank() && ctx != null && title.isNotBlank()) {
                        "YouTube blocked in-app embedding for '$title' (code $errorCode) — playing 30s preview inside TuneMind instead."
                    } else {
                        "Kept inside TuneMind — YouTube blocked embedding for '$title' (code $errorCode). Tap 'Open full track in $fallbackName' if you want to switch apps."
                    }
                    if (ctx != null && !fallbackPreview.isNullOrBlank() && title.isNotBlank()) {
                        TuneMindInAppPlayer.playStreamUrl(
                            context = ctx,
                            streamUrl = fallbackPreview,
                            title = title,
                            artist = artist,
                            album = album,
                            expectedDurationMs = 30_000L,
                            isPreview = true,
                            fallbackExternalPkg = fallbackPkg,
                            fallbackExternalName = fallbackName
                        )
                    }
                }

                if (ctx != null && title.isNotBlank() && SpotifyPremiumConnectManager.isConnectedAndPremium(ctx)) {
                    Thread {
                        val spotifyRes = SpotifyPremiumConnectManager.playTrackSilently(
                            context = ctx,
                            title = title,
                            artist = artist
                        )
                        if (spotifyRes is SpotifyPlayResult.Success) {
                            mainHandler.post {
                                _statusNoticeFlow.value =
                                    "Playing full track '$title' silently on Spotify (${spotifyRes.deviceName})."
                            }
                        } else if (!stayLocked && SpotifyMusicProvider().isInstalled(ctx)) {
                            val trackUri = (spotifyRes as? SpotifyPlayResult.NoActiveDevice)?.trackUri
                            val rec = com.tunemind.app.domain.recommendation.RecommendedTrack(
                                id = trackUri ?: title,
                                title = title,
                                artist = artist,
                                reason = "Opening full track in Spotify",
                                confidence = 0.95f,
                                spotifyUri = trackUri
                            )
                            MusicPlayerAutoLauncher.launchWithAutoPlay(
                                context = ctx,
                                providerId = SpotifyMusicProvider.PROVIDER_ID,
                                displayName = "Spotify",
                                packageName = SpotifyMusicProvider.SPOTIFY_PACKAGE,
                                track = rec,
                                query = "$artist $title".trim(),
                                deepLinkUri = trackUri,
                                neverOpenExternalApp = false
                            )
                            mainHandler.post {
                                _statusNoticeFlow.value = "Opened full track '$title' in Spotify."
                            }
                        } else {
                            mainHandler.post {
                                playInAppPreviewFallbackOnEmbedError()
                            }
                        }
                    }.start()
                    return@post
                }

                // Spotify Free Smart Mode (only when Stay-in-TuneMind and Never-Leave Mode allow external switching):
                val preferSpotifyHandoff = ctx != null &&
                    !stayLocked &&
                    title.isNotBlank() &&
                    SpotifyMusicProvider().isInstalled(ctx) &&
                    com.tunemind.app.domain.voice.AgentSettingsStore.isSpotifyFreeOpenInSpotifyEnabled(ctx) &&
                    (fallbackPkg == SpotifyMusicProvider.SPOTIFY_PACKAGE || SpotifyPremiumConnectManager.isConnectedAsFreeTier(ctx))

                if (preferSpotifyHandoff && ctx != null) {
                    _statusNoticeFlow.value = "Opening full track '$title' in Spotify..."
                    Thread {
                        val exactUri = if (SpotifyPremiumConnectManager.isConnected(ctx)) {
                            SpotifyPremiumConnectManager.searchExactTrackUri(ctx, title, artist)
                        } else null
                        val rec = com.tunemind.app.domain.recommendation.RecommendedTrack(
                            id = exactUri ?: title,
                            title = title,
                            artist = artist,
                            reason = "Opening full track in Spotify instead of 30s preview",
                            confidence = 0.95f,
                            spotifyUri = exactUri
                        )
                        MusicPlayerAutoLauncher.launchWithAutoPlay(
                            context = ctx,
                            providerId = SpotifyMusicProvider.PROVIDER_ID,
                            displayName = "Spotify",
                            packageName = SpotifyMusicProvider.SPOTIFY_PACKAGE,
                            track = rec,
                            query = "$artist $title".trim(),
                            deepLinkUri = exactUri,
                            neverOpenExternalApp = false
                        )
                    }.start()
                    return@post
                }

                playInAppPreviewFallbackOnEmbedError()
            }
        }
    }
}
