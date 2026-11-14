package com.tunemind.app.provider

import android.content.ContentUris
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import com.tunemind.app.media.ActivePlaybackSnapshot
import com.tunemind.app.service.TuneMindListenerService
import com.tunemind.app.util.PermissionUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

data class LocalAudioMatch(
    val contentUri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long
)

/**
 * Option 3 — Built-In Native TuneMind In-App Audio Engine.
 *
 * Plays local device audio files (via `MediaStore.Audio.Media`) and optional streamable preview URLs
 * 100% natively INSIDE TuneMind using `android.media.MediaPlayer` with full AudioFocus management,
 * hardware wake lock, progress reporting, and Autonomous End-of-Song completion notifications —
 * guaranteeing ZERO external app switching.
 */
object TuneMindInAppPlayer {
    private const val TAG = "TuneMindInAppPlayer"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var mediaPlayer: MediaPlayer? = null
    @Volatile
    private var audioFocusRequest: AudioFocusRequest? = null
    @Volatile
    private var onTrackCompletedListener: ((title: String, artist: String, packageName: String) -> Unit)? = null

    private val _isPlayingFlow = MutableStateFlow(false)
    val isPlayingFlow: StateFlow<Boolean> = _isPlayingFlow.asStateFlow()

    private val _hasActiveTrackFlow = MutableStateFlow(false)
    val hasActiveTrackFlow: StateFlow<Boolean> = _hasActiveTrackFlow.asStateFlow()

    private val _currentTitleFlow = MutableStateFlow<String?>(null)
    val currentTitleFlow: StateFlow<String?> = _currentTitleFlow.asStateFlow()

    private val _currentArtistFlow = MutableStateFlow<String?>(null)
    val currentArtistFlow: StateFlow<String?> = _currentArtistFlow.asStateFlow()

    private val _currentUriFlow = MutableStateFlow<String?>(null)
    val currentUriFlow: StateFlow<String?> = _currentUriFlow.asStateFlow()

    @Volatile
    private var currentAlbumName: String? = null
    @Volatile
    private var currentDurationMs: Long = 0L
    @Volatile
    private var appPackageName: String = "com.tunemind.app"

    private val progressTicker = object : Runnable {
        override fun run() {
            val mp = mediaPlayer
            if (mp != null && _isPlayingFlow.value) {
                runCatching {
                    val pos = mp.currentPosition.toLong().coerceAtLeast(0L)
                    val dur = mp.duration.toLong().coerceAtLeast(currentDurationMs)
                    publishSnapshot(isPlaying = true, positionMs = pos, durationMs = dur)
                }
                mainHandler.postDelayed(this, 900L)
            }
        }
    }

    fun isPlaying(): Boolean = _isPlayingFlow.value && (runCatching { mediaPlayer?.isPlaying == true }.getOrDefault(false))

    fun hasActiveTrack(): Boolean = _hasActiveTrackFlow.value && mediaPlayer != null

    fun setOnTrackCompletedListener(
        listener: ((title: String, artist: String, packageName: String) -> Unit)?
    ) {
        onTrackCompletedListener = listener
    }

    /**
     * Searches the user's phone storage (`MediaStore.Audio.Media.EXTERNAL_CONTENT_URI`)
     * for an audio file matching [title] and optional [artist].
     */
    fun findMatchingLocalAudio(
        context: Context,
        title: String,
        artist: String = ""
    ): LocalAudioMatch? {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) return null
        if (!PermissionUtils.hasAudioStoragePermission(context)) return null

        val normTargetTitle = SpotifyTrackUriResolver.normalizeTitleForExactMatch(cleanTitle)
        val normTargetArtist = SpotifyTrackUriResolver.normalizeTitleForExactMatch(artist)
        if (normTargetTitle.isBlank()) return null

        return try {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION
            )
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            ) ?: return null

            var exactMatch: LocalAudioMatch? = null
            var containsMatch: LocalAudioMatch? = null

            cursor.use { c ->
                val idIdx = c.getColumnIndex(MediaStore.Audio.Media._ID)
                val titleIdx = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistIdx = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumIdx = c.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val durIdx = c.getColumnIndex(MediaStore.Audio.Media.DURATION)

                while (c.moveToNext()) {
                    if (idIdx == -1 || titleIdx == -1) continue
                    val fileTitle = c.getString(titleIdx)?.trim().orEmpty()
                    if (fileTitle.isBlank()) continue
                    val fileArtist = if (artistIdx != -1) c.getString(artistIdx)?.trim().orEmpty() else ""
                    val fileAlbum = if (albumIdx != -1) c.getString(albumIdx)?.trim().orEmpty() else ""
                    val durMs = if (durIdx != -1) c.getLong(durIdx).coerceAtLeast(0L) else 0L

                    val normFileTitle = SpotifyTrackUriResolver.normalizeTitleForExactMatch(fileTitle)
                    val normFileArtist = SpotifyTrackUriResolver.normalizeTitleForExactMatch(fileArtist)

                    val artistOk = normTargetArtist.isBlank() ||
                        normTargetArtist == "unknown artist" ||
                        normFileArtist == normTargetArtist ||
                        normFileArtist.contains(normTargetArtist) ||
                        normTargetArtist.contains(normFileArtist)

                    if (normFileTitle == normTargetTitle && artistOk) {
                        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(idIdx))
                        exactMatch = LocalAudioMatch(uri, fileTitle, fileArtist, fileAlbum, durMs)
                        break
                    }

                    if (containsMatch == null &&
                        normTargetTitle.length >= 4 &&
                        (normFileTitle.contains(normTargetTitle) || normTargetTitle.contains(normFileTitle)) &&
                        artistOk
                    ) {
                        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(idIdx))
                        containsMatch = LocalAudioMatch(uri, fileTitle, fileArtist, fileAlbum, durMs)
                    }
                }
            }

            exactMatch ?: containsMatch
        } catch (e: Exception) {
            Log.d(TAG, "Local audio search notice: ${e.message}")
            null
        }
    }

    fun playLocalMatch(context: Context, title: String, artist: String = ""): Boolean {
        val match = findMatchingLocalAudio(context, title, artist) ?: return false
        return playLocalUri(
            context = context,
            uri = match.contentUri,
            title = match.title,
            artist = match.artist.ifBlank { artist },
            album = match.album.ifBlank { null },
            expectedDurationMs = match.durationMs
        )
    }

    fun playLocalUri(
        context: Context,
        uri: Uri,
        title: String,
        artist: String = "",
        album: String? = null,
        expectedDurationMs: Long = 0L
    ): Boolean {
        val appContext = context.applicationContext
        appPackageName = appContext.packageName
        return try {
            prepareAndStartPlayer(
                appContext = appContext,
                title = title,
                artist = artist,
                album = album,
                sourceTag = uri.toString(),
                expectedDurationMs = expectedDurationMs,
                isAsyncRemote = false
            ) { mp ->
                mp.setDataSource(appContext, uri)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "playLocalUri failed for $title: ${e.message}")
            false
        }
    }

    fun playStreamUrl(
        context: Context,
        streamUrl: String,
        title: String,
        artist: String = "",
        album: String? = null
    ): Boolean {
        if (!streamUrl.startsWith("http")) return false
        val appContext = context.applicationContext
        appPackageName = appContext.packageName
        return try {
            prepareAndStartPlayer(
                appContext = appContext,
                title = title,
                artist = artist,
                album = album,
                sourceTag = streamUrl,
                expectedDurationMs = 30_000L,
                isAsyncRemote = true
            ) { mp ->
                mp.setDataSource(streamUrl)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "playStreamUrl failed for $title: ${e.message}")
            false
        }
    }

    private fun prepareAndStartPlayer(
        appContext: Context,
        title: String,
        artist: String,
        album: String?,
        sourceTag: String,
        expectedDurationMs: Long,
        isAsyncRemote: Boolean,
        configureSource: (MediaPlayer) -> Unit
    ) {
        stopInternal(resetTrackMeta = false)

        _currentTitleFlow.value = title.trim()
        _currentArtistFlow.value = artist.trim()
        _currentUriFlow.value = sourceTag
        _hasActiveTrackFlow.value = true
        currentAlbumName = album
        currentDurationMs = expectedDurationMs

        requestMediaAudioFocus(appContext)

        val mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setWakeMode(appContext, PowerManager.PARTIAL_WAKE_LOCK)
            configureSource(this)

            setOnCompletionListener { completedMp ->
                val doneTitle = _currentTitleFlow.value ?: title
                val doneArtist = _currentArtistFlow.value ?: artist
                _isPlayingFlow.value = false
                mainHandler.removeCallbacks(progressTicker)
                publishSnapshot(
                    isPlaying = false,
                    positionMs = completedMp.duration.toLong().coerceAtLeast(currentDurationMs),
                    durationMs = completedMp.duration.toLong().coerceAtLeast(currentDurationMs)
                )
                Log.i(TAG, "In-App playback completed: $doneTitle")
                onTrackCompletedListener?.invoke(doneTitle, doneArtist, appPackageName)
            }

            setOnErrorListener { failedMp, what, extra ->
                Log.w(TAG, "In-App MediaPlayer error what=$what extra=$extra for $title")
                _isPlayingFlow.value = false
                _hasActiveTrackFlow.value = false
                mainHandler.removeCallbacks(progressTicker)
                runCatching { failedMp.release() }
                if (mediaPlayer === failedMp) mediaPlayer = null
                true
            }
        }

        mediaPlayer = mp

        if (isAsyncRemote) {
            mp.setOnPreparedListener { preparedMp ->
                currentDurationMs = preparedMp.duration.toLong().coerceAtLeast(expectedDurationMs)
                preparedMp.start()
                _isPlayingFlow.value = true
                publishSnapshot(isPlaying = true, positionMs = 0L, durationMs = currentDurationMs)
                mainHandler.removeCallbacks(progressTicker)
                mainHandler.postDelayed(progressTicker, 900L)
                Log.i(TAG, "✅ Started native TuneMind In-App stream for '$title'")
            }
            ioExecutor.execute {
                runCatching { mp.prepareAsync() }
                    .onFailure { err ->
                        Log.w(TAG, "prepareAsync error: ${err.message}")
                        mainHandler.post { stop() }
                    }
            }
        } else {
            mp.prepare()
            currentDurationMs = mp.duration.toLong().coerceAtLeast(expectedDurationMs)
            mp.start()
            _isPlayingFlow.value = true
            publishSnapshot(isPlaying = true, positionMs = 0L, durationMs = currentDurationMs)
            mainHandler.removeCallbacks(progressTicker)
            mainHandler.postDelayed(progressTicker, 900L)
            Log.i(TAG, "✅ Started native TuneMind In-App local playback for '$title'")
        }
    }

    fun pause(): Boolean {
        val mp = mediaPlayer ?: return false
        return try {
            if (mp.isPlaying) {
                mp.pause()
            }
            _isPlayingFlow.value = false
            mainHandler.removeCallbacks(progressTicker)
            publishSnapshot(
                isPlaying = false,
                positionMs = runCatching { mp.currentPosition.toLong() }.getOrDefault(0L),
                durationMs = currentDurationMs
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    fun resume(): Boolean {
        val mp = mediaPlayer ?: return false
        return try {
            if (!mp.isPlaying) {
                mp.start()
            }
            _isPlayingFlow.value = true
            publishSnapshot(
                isPlaying = true,
                positionMs = runCatching { mp.currentPosition.toLong() }.getOrDefault(0L),
                durationMs = currentDurationMs
            )
            mainHandler.removeCallbacks(progressTicker)
            mainHandler.postDelayed(progressTicker, 900L)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun togglePlayback(): Boolean {
        return if (isPlaying()) pause() else resume()
    }

    fun stop() {
        stopInternal(resetTrackMeta = true)
    }

    private fun stopInternal(resetTrackMeta: Boolean) {
        mainHandler.removeCallbacks(progressTicker)
        _isPlayingFlow.value = false
        try {
            mediaPlayer?.let { mp ->
                if (runCatching { mp.isPlaying }.getOrDefault(false)) {
                    runCatching { mp.stop() }
                }
                runCatching { mp.reset() }
                runCatching { mp.release() }
            }
        } catch (_: Throwable) {}
        mediaPlayer = null
        if (resetTrackMeta) {
            _hasActiveTrackFlow.value = false
            _currentTitleFlow.value = null
            _currentArtistFlow.value = null
            _currentUriFlow.value = null
            currentAlbumName = null
            currentDurationMs = 0L
        }
    }

    private fun publishSnapshot(isPlaying: Boolean, positionMs: Long, durationMs: Long) {
        val title = _currentTitleFlow.value?.takeIf { it.isNotBlank() } ?: return
        val artist = _currentArtistFlow.value.orEmpty()
        val snap = ActivePlaybackSnapshot(
            packageName = appPackageName,
            title = title,
            artist = artist,
            isPlaying = isPlaying,
            positionMs = positionMs,
            durationMs = durationMs,
            updatedAtElapsedMs = android.os.SystemClock.elapsedRealtime()
        )
        mainHandler.post {
            TuneMindListenerService.onPlaybackSnapshotUpdated?.invoke(snap)
        }
    }

    private fun requestMediaAudioFocus(appContext: Context) {
        try {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val sysPrefs = appContext.getSharedPreferences("tunemind_system_prefs", Context.MODE_PRIVATE)
            val pauseOnLoss = sysPrefs.getString("audio_focus_behavior", "DUCK") == "PAUSE"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { runCatching { am.abandonAudioFocusRequest(it) } }
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setWillPauseWhenDucked(pauseOnLoss)
                    .setOnAudioFocusChangeListener { change ->
                        when (change) {
                            AudioManager.AUDIOFOCUS_LOSS -> pause()
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                                if (pauseOnLoss) {
                                    pause()
                                } else {
                                    runCatching { mediaPlayer?.setVolume(0.28f, 0.28f) }
                                }
                            }
                            AudioManager.AUDIOFOCUS_GAIN -> {
                                runCatching { mediaPlayer?.setVolume(1.0f, 1.0f) }
                            }
                        }
                    }
                    .build()
                audioFocusRequest = req
                am.requestAudioFocus(req)
            }
        } catch (e: Exception) {
            Log.d(TAG, "AudioFocus request notice: ${e.message}")
        }
    }
}
