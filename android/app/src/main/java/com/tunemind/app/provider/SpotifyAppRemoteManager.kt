package com.tunemind.app.provider

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
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
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages the official Spotify Android App Remote SDK (`spotify-app-remote-release-0.8.0.aar`).
 *
 * Enables silent, zero-app-switch playback control (`play(uri)`, `pause()`, `resume()`,
 * `skipNext()`, `skipPrevious()`, and real-time `subscribeToPlayerState()`) directly from
 * inside TuneMind for BOTH Spotify Free and Spotify Premium users when the Spotify Android app
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

    @Volatile
    private var cachedSha1Fingerprint: String? = null

    private val isConnecting = AtomicBoolean(false)
    private val connectionGeneration = AtomicLong(0L)

    private val _isConnectedFlow = MutableStateFlow(false)
    val isConnectedFlow: StateFlow<Boolean> = _isConnectedFlow.asStateFlow()

    private val _isConnectingFlow = MutableStateFlow(false)
    val isConnectingFlow: StateFlow<Boolean> = _isConnectingFlow.asStateFlow()

    private val _lastConnectionErrorFlow = MutableStateFlow<String?>(null)
    val lastConnectionErrorFlow: StateFlow<String?> = _lastConnectionErrorFlow.asStateFlow()

    private val _connectionSuccessMessageFlow = MutableStateFlow<String?>(null)
    val connectionSuccessMessageFlow: StateFlow<String?> = _connectionSuccessMessageFlow.asStateFlow()

    private fun findActivity(context: Context): Activity? {
        var ctx: Context? = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    /**
     * Extracts the running APK's SHA-1 signing fingerprint (`AA:BB:CC:...`) so the user can
     * verify or add it in Spotify Developer Dashboard -> App -> Android Packages (`com.tunemind.app`).
     */
    @Suppress("DEPRECATION")
    fun getAppSigningSha1Fingerprint(context: Context): String {
        cachedSha1Fingerprint?.let { return it }
        return try {
            val pm = context.packageManager
            val pkg = context.packageName
            val sigBytes: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                val signers = info.signingInfo?.apkContentsSigners
                    ?: info.signingInfo?.signingCertificateHistory
                signers?.firstOrNull()?.toByteArray()
            } else {
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
                info.signatures?.firstOrNull()?.toByteArray()
            }
            if (sigBytes == null) return "Unavailable"
            val digest = MessageDigest.getInstance("SHA-1").digest(sigBytes)
            val hex = digest.joinToString(":") { "%02X".format(it) }
            cachedSha1Fingerprint = hex
            hex
        } catch (t: Throwable) {
            Log.d(TAG, "SHA-1 lookup notice: ${t.message}")
            "Unavailable"
        }
    }

    private fun formatSpotifyRemoteFailure(context: Context, throwable: Throwable): String {
        val sha1 = getAppSigningSha1Fingerprint(context)
        return when (throwable) {
            is CouldNotFindSpotifyApp ->
                "Spotify app is not installed on this device."
            is NotLoggedInException ->
                "Spotify is not signed in. Open Spotify, log in, then tap Connect Remote again."
            is UserNotAuthorizedException ->
                "Spotify Remote permission was declined. Tap Connect Remote and tap Agree in Spotify."
            is AuthenticationFailedException ->
                "Spotify rejected Remote auth for ${context.packageName} (${throwable.message ?: "SHA-1 mismatch"}). Add SHA-1 $sha1 in Spotify Developer Dashboard -> Android Packages."
            is OfflineModeException ->
                "Spotify is in Offline Mode. Turn off Offline Mode inside Spotify and retry."
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

    fun clearFeedback() {
        _lastConnectionErrorFlow.value = null
        _connectionSuccessMessageFlow.value = null
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
     *
     * Key guarantees:
     * - Uses a monotonic [connectionGeneration] token so silent background attempts NEVER
     *   clobber or overwrite a user-triggered interactive Connect Remote session.
     * - Disconnects any stale dead binder when [showAuthView] is `true`.
     * - Gives interactive auth 16s so Spotify's consent UI never gets cut off prematurely,
     *   while silent background checks fail fast (2.8s) and do NOT pollute UI errors.
     */
    fun connectAsync(
        context: Context,
        showAuthView: Boolean = false,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        if (!isSpotifyInstalled(appContext)) {
            val msg = "Spotify app is not installed on this device."
            if (showAuthView) {
                _lastConnectionErrorFlow.value = msg
                _connectionSuccessMessageFlow.value = null
            }
            _isConnectedFlow.value = false
            _isConnectingFlow.value = false
            isConnecting.set(false)
            onComplete?.invoke(false, msg)
            return
        }

        if (isConnected()) {
            val okMsg = "Connected to Spotify Remote — Silent playback control is active."
            _isConnectedFlow.value = true
            _isConnectingFlow.value = false
            _lastConnectionErrorFlow.value = null
            if (showAuthView) {
                _connectionSuccessMessageFlow.value = okMsg
            }
            isConnecting.set(false)
            onComplete?.invoke(true, okMsg)
            return
        }

        val now = System.currentTimeMillis()
        if (!showAuthView && (now - lastSilentConnectAttemptMs < 3_500L || isConnecting.get())) {
            onComplete?.invoke(false, "Connection in progress")
            return
        }

        val myGeneration = connectionGeneration.incrementAndGet()
        if (!showAuthView) {
            lastSilentConnectAttemptMs = now
        } else {
            _lastConnectionErrorFlow.value = null
            _connectionSuccessMessageFlow.value = null
            activeWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
            // Clear any stale dead binder before an explicit user tap
            runCatching { spotifyAppRemote?.let { SpotifyAppRemote.disconnect(it) } }
            spotifyAppRemote = null
        }

        val connectContext: Context = if (showAuthView) {
            findActivity(context) ?: appContext
        } else {
            appContext
        }

        isConnecting.set(true)
        _isConnectingFlow.value = true
        val settled = AtomicBoolean(false)

        val watchdog = Runnable {
            if (connectionGeneration.get() == myGeneration && settled.compareAndSet(false, true)) {
                isConnecting.set(false)
                _isConnectingFlow.value = false
                _isConnectedFlow.value = false
                if (showAuthView) {
                    val sha1 = getAppSigningSha1Fingerprint(appContext)
                    val msg = "Spotify Remote did not respond in time. Either Spotify is asleep in background (tap 'Wake Spotify' first), or Spotify Developer Dashboard is missing package '${appContext.packageName}' + SHA-1 '$sha1'."
                    _lastConnectionErrorFlow.value = msg
                    _connectionSuccessMessageFlow.value = null
                    Log.w(TAG, "Interactive Spotify App Remote watchdog timeout")
                    onComplete?.invoke(false, msg)
                } else {
                    Log.d(TAG, "Background silent App Remote connect skipped (Spotify sleeping or unauthorized).")
                    onComplete?.invoke(false, null)
                }
            }
        }
        activeWatchdogRunnable = watchdog

        mainHandler.post {
            if (connectionGeneration.get() != myGeneration) return@post
            mainHandler.postDelayed(watchdog, if (showAuthView) 16_000L else 2_800L)
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
                            val okMsg = "Connected to Spotify Remote — Silent in-app playback is now active."
                            _connectionSuccessMessageFlow.value = okMsg
                            Log.i(TAG, "✅ Connected to Spotify App Remote SDK (showAuthView=$showAuthView)")
                            subscribePlayerStateInternal(remote)
                            if (connectionGeneration.get() == myGeneration) {
                                onComplete?.invoke(true, okMsg)
                            }
                        }

                        override fun onFailure(throwable: Throwable) {
                            if (connectionGeneration.get() != myGeneration) return
                            mainHandler.removeCallbacks(watchdog)
                            settled.set(true)
                            isConnecting.set(false)
                            _isConnectingFlow.value = false
                            spotifyAppRemote = null
                            _isConnectedFlow.value = false
                            val msg = formatSpotifyRemoteFailure(appContext, throwable)
                            val isHardAuthError = throwable is AuthenticationFailedException ||
                                throwable is NotLoggedInException ||
                                throwable is UserNotAuthorizedException ||
                                throwable is UnsupportedFeatureVersionException
                            if (showAuthView || isHardAuthError) {
                                _lastConnectionErrorFlow.value = msg
                                _connectionSuccessMessageFlow.value = null
                            }
                            Log.w(TAG, "Spotify App Remote connection failed (showAuthView=$showAuthView): $msg")
                            onComplete?.invoke(false, msg)
                        }
                    }
                )
            } catch (t: Throwable) {
                if (connectionGeneration.get() != myGeneration) return@post
                mainHandler.removeCallbacks(watchdog)
                settled.set(true)
                isConnecting.set(false)
                _isConnectingFlow.value = false
                val msg = formatSpotifyRemoteFailure(appContext, t)
                if (showAuthView) {
                    _lastConnectionErrorFlow.value = msg
                    _connectionSuccessMessageFlow.value = null
                }
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
     * Plays any `spotify:track:`, `spotify:episode:`, `spotify:album:`, or `spotify:playlist:` URI
     * directly via Spotify App Remote without leaving TuneMind.
     * Supports BOTH Spotify Free and Premium!
     *
     * Must be called from a background/IO thread so Main Looper can deliver App Remote binder callbacks
     * cleanly without deadlocking. If accidentally invoked on Main Thread, dispatches asynchronously
     * without blocking.
     */
    fun playUriBlocking(context: Context, spotifyUri: String, timeoutMs: Long = 2200L): Boolean {
        val supported = spotifyUri.startsWith("spotify:track:") ||
            spotifyUri.startsWith("spotify:episode:") ||
            spotifyUri.startsWith("spotify:album:") ||
            spotifyUri.startsWith("spotify:playlist:")
        if (!supported) return false

        val appContext = context.applicationContext
        if (!isSpotifyInstalled(appContext)) return false

        // Safety guard: never deadlock Main Looper with CountDownLatch.await
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val remote = spotifyAppRemote
            if (remote != null && isConnected()) {
                return runCatching {
                    remote.playerApi.play(spotifyUri)
                    true
                }.getOrDefault(false)
            }
            return false
        }

        // Connect silently if not yet connected (fast 1500ms window on worker thread)
        if (!isConnected()) {
            val connectLatch = CountDownLatch(1)
            connectAsync(appContext, showAuthView = false) { _, _ ->
                connectLatch.countDown()
            }
            try {
                connectLatch.await(1500L, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {}
        }

        val remote = spotifyAppRemote
        if (remote == null || !isConnected()) return false

        val playLatch = CountDownLatch(1)
        val succeeded = AtomicBoolean(false)
        try {
            mainHandler.post {
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
                } catch (t: Throwable) {
                    Log.w(TAG, "App Remote mainHandler play threw: ${t.message}")
                    playLatch.countDown()
                }
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
            mainHandler.post { runCatching { remote.playerApi.pause() } }
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun resume(): Boolean {
        val remote = spotifyAppRemote ?: return false
        return try {
            if (!isConnected()) return false
            mainHandler.post { runCatching { remote.playerApi.resume() } }
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun skipNext(): Boolean {
        val remote = spotifyAppRemote ?: return false
        return try {
            if (!isConnected()) return false
            mainHandler.post { runCatching { remote.playerApi.skipNext() } }
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun skipPrev(): Boolean {
        val remote = spotifyAppRemote ?: return false
        return try {
            if (!isConnected()) return false
            mainHandler.post { runCatching { remote.playerApi.skipPrevious() } }
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun disconnect() {
        connectionGeneration.incrementAndGet()
        activeWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        try {
            playerStateSubscription?.cancel()
            playerStateSubscription = null
            spotifyAppRemote?.let { SpotifyAppRemote.disconnect(it) }
        } catch (_: Throwable) {
        } finally {
            spotifyAppRemote = null
            isConnecting.set(false)
            _isConnectingFlow.value = false
            _isConnectedFlow.value = false
            _connectionSuccessMessageFlow.value = null
        }
    }
}
