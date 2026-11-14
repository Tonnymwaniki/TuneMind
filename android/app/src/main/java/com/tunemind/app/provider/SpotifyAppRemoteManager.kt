package com.tunemind.app.provider

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.AuthenticationFailedException
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.OfflineModeException
import com.spotify.android.appremote.api.error.UnsupportedFeatureVersionException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.PlayerState
import com.tunemind.app.data.ingestion.SpotifyAuthManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages the official Spotify Android App Remote SDK (`spotify-app-remote-release-0.8.0.aar`).
 *
 * Enables silent, zero-app-switch playback control (`play(uri)`, `pause()`, `resume()`,
 * `skipNext()`, `skipPrevious()`, and real-time `subscribeToPlayerState()`) directly from
 * inside TuneMind for BOTH Spotify Premium and Spotify Free users when the Spotify Android app
 * is installed.
 */
object SpotifyAppRemoteManager {
    private const val TAG = "SpotifyAppRemoteMgr"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var spotifyAppRemote: SpotifyAppRemote? = null

    @Volatile
    private var playerStateSubscription: Subscription<PlayerState>? = null

    @Volatile
    private var playerStateListener: ((
        title: String,
        artist: String,
        album: String?,
        uri: String?,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long
    ) -> Unit)? = null

    @Volatile
    private var lastSilentConnectAttemptMs: Long = 0L

    @Volatile
    private var activeWatchdogRunnable: Runnable? = null

    private val isConnecting = AtomicBoolean(false)

    private val _isConnectedFlow = MutableStateFlow(false)
    val isConnectedFlow: StateFlow<Boolean> = _isConnectedFlow.asStateFlow()

    private val _isConnectingFlow = MutableStateFlow(false)
    val isConnectingFlow: StateFlow<Boolean> = _isConnectingFlow.asStateFlow()

    private val _lastConnectionErrorFlow = MutableStateFlow<String?>(null)
    val lastConnectionErrorFlow: StateFlow<String?> = _lastConnectionErrorFlow.asStateFlow()

    private fun findActivity(context: Context): Activity? {
        var ctx: Context? = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    private fun formatSpotifyRemoteFailure(throwable: Throwable): String {
        return when (throwable) {
            is CouldNotFindSpotifyApp ->
                "Spotify app is not installed on this device."
            is NotLoggedInException ->
                "Open Spotify and sign in to your account first, then tap Connect Remote again."
            is UserNotAuthorizedException ->
                "Spotify Remote authorization was declined or expired. Tap Connect Remote and tap Agree in Spotify."
            is AuthenticationFailedException ->
                "Spotify rejected Remote auth for com.tunemind.app (${throwable.message ?: "check Dashboard SHA-1"}). Verify this build's SHA-1 fingerprint is added in Spotify Developer Dashboard -> Android Packages."
            is OfflineModeException ->
                "Spotify is set to Offline Mode. Turn off Offline Mode inside Spotify and retry."
            is UnsupportedFeatureVersionException ->
                "Please update the Spotify app from the Play Store to enable App Remote."
            else ->
                throwable.message?.takeIf { it.isNotBlank() } ?: throwable.javaClass.simpleName
        }
    }

    fun isSpotifyInstalled(context: Context): Boolean {
        return try {
            SpotifyAppRemote.isSpotifyInstalled(context.applicationContext)
        } catch (_: Throwable) {
            false
        }
    }

    fun isConnected(): Boolean {
        return try {
            val remote = spotifyAppRemote
            val connected = remote != null && remote.isConnected
            if (_isConnectedFlow.value != connected) {
                _isConnectedFlow.value = connected
            }
            connected
        } catch (_: Throwable) {
            false
        }
    }

    fun setOnPlayerStateListener(
        listener: ((
            title: String,
            artist: String,
            album: String?,
            uri: String?,
            isPlaying: Boolean,
            positionMs: Long,
            durationMs: Long
        ) -> Unit)?
    ) {
        playerStateListener = listener
    }

    /**
     * Connects to the Spotify Android app via App Remote IPC.
     * When [showAuthView] is `true`, uses the unwrapped [Activity] context so Spotify's official
     * OAuth consent sheet can always be presented without silent `ApplicationContext` rejection,
     * and enforces a 7.5-second watchdog timeout so the UI never hangs silently.
     */
    fun connectAsync(
        context: Context,
        showAuthView: Boolean = false,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        if (!isSpotifyInstalled(appContext)) {
            val msg = "Spotify app is not installed on this device."
            _lastConnectionErrorFlow.value = msg
            _isConnectedFlow.value = false
            _isConnectingFlow.value = false
            isConnecting.set(false)
            onComplete?.invoke(false, msg)
            return
        }

        if (isConnected()) {
            _isConnectedFlow.value = true
            _isConnectingFlow.value = false
            _lastConnectionErrorFlow.value = null
            isConnecting.set(false)
            onComplete?.invoke(true, null)
            return
        }

        val now = System.currentTimeMillis()
        if (!showAuthView && now - lastSilentConnectAttemptMs < 3_500L && isConnecting.get()) {
            onComplete?.invoke(false, "Connection in progress")
            return
        }
        if (!showAuthView) {
            lastSilentConnectAttemptMs = now
        } else {
            // Interactive user tap supersedes any stale silent attempt
            _lastConnectionErrorFlow.value = null
            activeWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        }

        // CRITICAL: Spotify App Remote AAR requires a real Activity context when showAuthView=true!
        val connectContext: Context = if (showAuthView) {
            findActivity(context) ?: appContext
        } else {
            appContext
        }

        isConnecting.set(true)
        _isConnectingFlow.value = true
        val settled = AtomicBoolean(false)

        val watchdog = Runnable {
            if (settled.compareAndSet(false, true)) {
                isConnecting.set(false)
                _isConnectingFlow.value = false
                _isConnectedFlow.value = false
                val msg = if (showAuthView) {
                    "Spotify didn't respond within 7s. Tap 'Wake Spotify' below so Spotify runs in memory, ensure Developer Dashboard includes your build SHA-1 for com.tunemind.app, then tap Connect Remote."
                } else {
                    "Spotify Remote service timed out silently in background."
                }
                _lastConnectionErrorFlow.value = msg
                Log.w(TAG, "Spotify App Remote watchdog timeout (showAuthView=$showAuthView)")
                onComplete?.invoke(false, msg)
            }
        }
        activeWatchdogRunnable = watchdog

        mainHandler.post {
            mainHandler.postDelayed(watchdog, if (showAuthView) 7_500L else 3_200L)
            try {
                val connectionParams = ConnectionParams.Builder(SpotifyAuthManager.CLIENT_ID)
                    .setRedirectUri(SpotifyAuthManager.REDIRECT_URI)
                    .showAuthView(showAuthView)
                    .build()

                SpotifyAppRemote.connect(
                    connectContext,
                    connectionParams,
                    object : Connector.ConnectionListener {
                        override fun onConnected(remote: SpotifyAppRemote) {
                            mainHandler.removeCallbacks(watchdog)
                            settled.set(true)
                            isConnecting.set(false)
                            _isConnectingFlow.value = false
                            spotifyAppRemote = remote
                            _isConnectedFlow.value = true
                            _lastConnectionErrorFlow.value = null
                            Log.i(TAG, "✅ Connected to Spotify App Remote SDK (showAuthView=$showAuthView)")
                            subscribePlayerStateInternal(remote)
                            onComplete?.invoke(true, null)
                        }

                        override fun onFailure(throwable: Throwable) {
                            mainHandler.removeCallbacks(watchdog)
                            settled.set(true)
                            isConnecting.set(false)
                            _isConnectingFlow.value = false
                            spotifyAppRemote = null
                            _isConnectedFlow.value = false
                            val msg = formatSpotifyRemoteFailure(throwable)
                            _lastConnectionErrorFlow.value = msg
                            Log.w(TAG, "Spotify App Remote connection failed: $msg")
                            onComplete?.invoke(false, msg)
                        }
                    }
                )
            } catch (t: Throwable) {
                mainHandler.removeCallbacks(watchdog)
                settled.set(true)
                isConnecting.set(false)
                _isConnectingFlow.value = false
                val msg = formatSpotifyRemoteFailure(t)
                _lastConnectionErrorFlow.value = msg
                _isConnectedFlow.value = false
                Log.w(TAG, "Spotify App Remote connect threw exception: $msg")
                onComplete?.invoke(false, msg)
            }
        }
    }

    private fun subscribePlayerStateInternal(remote: SpotifyAppRemote) {
        try {
            playerStateSubscription?.cancel()
            playerStateSubscription = remote.playerApi.subscribeToPlayerState()
                .setEventCallback { state ->
                    val track = state.track ?: return@setEventCallback
                    val title = track.name?.trim().orEmpty()
                    if (title.isBlank()) return@setEventCallback
                    val artist = track.artist?.name?.trim().orEmpty()
                    val album = track.album?.name?.trim()
                    val uri = track.uri
                    val isPlaying = !state.isPaused
                    val posMs = state.playbackPosition.coerceAtLeast(0L)
                    val durMs = track.duration.coerceAtLeast(0L)
                    playerStateListener?.invoke(title, artist, album, uri, isPlaying, posMs, durMs)
                }
        } catch (t: Throwable) {
            Log.d(TAG, "subscribeToPlayerState notice: ${t.message}")
        }
    }

    /**
     * Plays an exact `spotify:track:<id>` URI directly via Spotify App Remote without leaving TuneMind.
     * Supports BOTH Spotify Free and Premium!
     */
    fun playUriBlocking(context: Context, spotifyUri: String, timeoutMs: Long = 1650L): Boolean {
        if (!spotifyUri.startsWith("spotify:track:")) return false
        val appContext = context.applicationContext
        if (!isSpotifyInstalled(appContext)) return false

        // Connect silently if not yet connected (fast 950ms window)
        if (!isConnected()) {
            val connectLatch = CountDownLatch(1)
            connectAsync(appContext, showAuthView = false) { _, _ ->
                connectLatch.countDown()
            }
            try {
                connectLatch.await(950L, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {}
        }

        val remote = spotifyAppRemote
        if (remote == null || !isConnected()) return false

        val playLatch = CountDownLatch(1)
        val succeeded = AtomicBoolean(false)
        try {
            remote.playerApi.play(spotifyUri)
                .setResultCallback {
                    succeeded.set(true)
                    playLatch.countDown()
                }
                .setErrorCallback { err ->
                    Log.w(TAG, "App Remote play($spotifyUri) error: ${err.message}")
                    playLatch.countDown()
                }
            playLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            Log.w(TAG, "App Remote playUriBlocking exception: ${t.message}")
            return false
        }

        if (succeeded.get()) {
            Log.i(TAG, "✅ Stay-in-TuneMind Spotify App Remote playback started for $spotifyUri")
        }
        return succeeded.get()
    }

    fun pause(): Boolean {
        val remote = spotifyAppRemote ?: return false
        return try {
            if (!isConnected()) return false
            remote.playerApi.pause()
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun resume(): Boolean {
        val remote = spotifyAppRemote ?: return false
        return try {
            if (!isConnected()) return false
            remote.playerApi.resume()
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun skipNext(): Boolean {
        val remote = spotifyAppRemote ?: return false
        return try {
            if (!isConnected()) return false
            remote.playerApi.skipNext()
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun skipPrev(): Boolean {
        val remote = spotifyAppRemote ?: return false
        return try {
            if (!isConnected()) return false
            remote.playerApi.skipPrevious()
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun disconnect() {
        try {
            playerStateSubscription?.cancel()
            playerStateSubscription = null
            spotifyAppRemote?.let { SpotifyAppRemote.disconnect(it) }
        } catch (_: Throwable) {
        } finally {
            spotifyAppRemote = null
            _isConnectedFlow.value = false
        }
    }
}
