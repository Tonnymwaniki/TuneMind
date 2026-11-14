package com.tunemind.app.provider

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
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

    private val isConnecting = AtomicBoolean(false)

    private val _isConnectedFlow = MutableStateFlow(false)
    val isConnectedFlow: StateFlow<Boolean> = _isConnectedFlow.asStateFlow()

    private val _lastConnectionErrorFlow = MutableStateFlow<String?>(null)
    val lastConnectionErrorFlow: StateFlow<String?> = _lastConnectionErrorFlow.asStateFlow()

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
     * When [showAuthView] is `false`, connection happens silently without leaving TuneMind
     * (succeeds automatically once the user has authorized Spotify App Remote once).
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
            onComplete?.invoke(false, msg)
            return
        }

        if (isConnected()) {
            _isConnectedFlow.value = true
            _lastConnectionErrorFlow.value = null
            onComplete?.invoke(true, null)
            return
        }

        val now = System.currentTimeMillis()
        if (!showAuthView && now - lastSilentConnectAttemptMs < 4_000L && isConnecting.get()) {
            onComplete?.invoke(false, "Connection in progress")
            return
        }
        if (!showAuthView) {
            lastSilentConnectAttemptMs = now
        }

        isConnecting.set(true)
        mainHandler.post {
            try {
                val connectionParams = ConnectionParams.Builder(SpotifyAuthManager.CLIENT_ID)
                    .setRedirectUri(SpotifyAuthManager.REDIRECT_URI)
                    .showAuthView(showAuthView)
                    .build()

                SpotifyAppRemote.connect(
                    appContext,
                    connectionParams,
                    object : Connector.ConnectionListener {
                        override fun onConnected(remote: SpotifyAppRemote) {
                            isConnecting.set(false)
                            spotifyAppRemote = remote
                            _isConnectedFlow.value = true
                            _lastConnectionErrorFlow.value = null
                            Log.i(TAG, "✅ Connected to Spotify App Remote SDK (showAuthView=$showAuthView)")
                            subscribePlayerStateInternal(remote)
                            onComplete?.invoke(true, null)
                        }

                        override fun onFailure(throwable: Throwable) {
                            isConnecting.set(false)
                            spotifyAppRemote = null
                            _isConnectedFlow.value = false
                            val msg = throwable.message ?: throwable.javaClass.simpleName
                            _lastConnectionErrorFlow.value = msg
                            Log.w(TAG, "Spotify App Remote connection failed: $msg")
                            onComplete?.invoke(false, msg)
                        }
                    }
                )
            } catch (t: Throwable) {
                isConnecting.set(false)
                val msg = t.message ?: t.javaClass.simpleName
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
