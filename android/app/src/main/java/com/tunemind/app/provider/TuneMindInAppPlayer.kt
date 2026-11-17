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
import com.tunemind.app.data.local.EventBufferRepository
import com.tunemind.app.data.local.TuneMindDatabase
import com.tunemind.app.domain.event.ObservationEventType
import com.tunemind.app.domain.model.PlaybackObservation
import com.tunemind.app.domain.voice.AgentSettingsStore
import com.tunemind.app.media.ActivePlaybackSnapshot
import com.tunemind.app.service.TuneMindListenerService
import com.tunemind.app.util.PermissionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
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

    private val trackCompletedListeners =
        java.util.concurrent.CopyOnWriteArraySet<(title: String, artist: String, packageName: String) -> Unit>()

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

    private val _positionMsFlow = MutableStateFlow(0L)
    val positionMsFlow: StateFlow<Long> = _positionMsFlow.asStateFlow()

    private val _durationMsFlow = MutableStateFlow(0L)
    val durationMsFlow: StateFlow<Long> = _durationMsFlow.asStateFlow()

    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var cachedAppContext: Context? = null
    @Volatile
    private var currentAlbumName: String? = null
    @Volatile
    private var currentDurationMs: Long = 0L
    @Volatile
    private var appPackageName: String = "com.tunemind.app"
    @Volatile
    private var loopEnabled: Boolean = false
    @Volatile
    private var loopStartMs: Long = 0L
    @Volatile
    private var loopEndMs: Long = 0L

    private val progressTicker = object : Runnable {
        override fun run() {
            val mp = mediaPlayer
            if (mp != null && _isPlayingFlow.value) {
                runCatching {
                    val pos = mp.currentPosition.toLong().coerceAtLeast(0L)
                    val dur = mp.duration.toLong().coerceAtLeast(currentDurationMs)
                    if (loopEnabled && loopEndMs > loopStartMs && pos >= loopEndMs) {
                        mp.seekTo(loopStartMs.toInt())
                    }
                    publishSnapshot(isPlaying = true, positionMs = pos, durationMs = dur)
                }
                mainHandler.postDelayed(this, 600L)
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

    fun addOnTrackCompletedListener(
        listener: (title: String, artist: String, packageName: String) -> Unit
    ) {
        trackCompletedListeners.add(listener)
    }

    fun removeOnTrackCompletedListener(
        listener: (title: String, artist: String, packageName: String) -> Unit
    ) {
        trackCompletedListeners.remove(listener)
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

    /**
     * Queries local device music files from MediaStore for universal search & playback.
     */
    fun queryLocalAudioLibrary(
        context: Context,
        query: String = "",
        limit: Int = 80
    ): List<LocalAudioMatch> {
        if (!PermissionUtils.hasAudioStoragePermission(context)) return emptyList()
        val cleanQuery = query.trim().lowercase()
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
            ) ?: return emptyList()

            val results = mutableListOf<LocalAudioMatch>()
            cursor.use { c ->
                val idIdx = c.getColumnIndex(MediaStore.Audio.Media._ID)
                val titleIdx = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistIdx = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumIdx = c.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val durIdx = c.getColumnIndex(MediaStore.Audio.Media.DURATION)

                while (c.moveToNext() && results.size < limit) {
                    if (idIdx == -1 || titleIdx == -1) continue
                    val fileTitle = c.getString(titleIdx)?.trim().orEmpty()
                    if (fileTitle.isBlank()) continue
                    val fileArtist = if (artistIdx != -1) c.getString(artistIdx)?.trim().orEmpty() else ""
                    val fileAlbum = if (albumIdx != -1) c.getString(albumIdx)?.trim().orEmpty() else ""
                    val durMs = if (durIdx != -1) c.getLong(durIdx).coerceAtLeast(0L) else 0L

                    if (cleanQuery.isNotEmpty()) {
                        val hay = "$fileTitle $fileArtist $fileAlbum".lowercase()
                        if (!hay.contains(cleanQuery)) continue
                    }

                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(idIdx))
                    results.add(LocalAudioMatch(uri, fileTitle, fileArtist, fileAlbum, durMs))
                }
            }
            results
        } catch (e: Exception) {
            Log.d(TAG, "queryLocalAudioLibrary notice: ${e.message}")
            emptyList()
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
        cachedAppContext = appContext
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
                val doneDuration = runCatching { completedMp.duration.toLong() }.getOrDefault(0L)
                    .coerceAtLeast(currentDurationMs)
                _isPlayingFlow.value = false
                mainHandler.removeCallbacks(progressTicker)
                publishSnapshot(
                    isPlaying = false,
                    positionMs = doneDuration,
                    durationMs = doneDuration
                )
                persistInAppObservation(
                    context = appContext,
                    eventType = ObservationEventType.PLAYBACK_STATE_CHANGED,
                    title = doneTitle,
                    artist = doneArtist,
                    album = currentAlbumName,
                    trackUri = sourceTag,
                    durationMs = doneDuration,
                    positionMs = doneDuration,
                    playbackStateCode = 2
                )
                Log.i(TAG, "In-App playback completed: $doneTitle")
                onTrackCompletedListener?.invoke(doneTitle, doneArtist, appPackageName)
                trackCompletedListeners.forEach { listener ->
                    runCatching { listener.invoke(doneTitle, doneArtist, appPackageName) }
                }
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
                persistInAppObservation(
                    context = appContext,
                    eventType = ObservationEventType.METADATA_CHANGED,
                    title = _currentTitleFlow.value ?: title,
                    artist = _currentArtistFlow.value ?: artist,
                    album = currentAlbumName,
                    trackUri = sourceTag,
                    durationMs = currentDurationMs,
                    positionMs = 0L,
                    playbackStateCode = 3
                )
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
            persistInAppObservation(
                context = appContext,
                eventType = ObservationEventType.METADATA_CHANGED,
                title = _currentTitleFlow.value ?: title,
                artist = _currentArtistFlow.value ?: artist,
                album = currentAlbumName,
                trackUri = sourceTag,
                durationMs = currentDurationMs,
                positionMs = 0L,
                playbackStateCode = 3
            )
            mainHandler.removeCallbacks(progressTicker)
            mainHandler.postDelayed(progressTicker, 900L)
            Log.i(TAG, "✅ Started native TuneMind In-App local playback for '$title'")
        }
    }

    fun pause(): Boolean {
        val mp = mediaPlayer ?: return false
        return try {
            val pos = runCatching { mp.currentPosition.toLong().coerceAtLeast(0L) }.getOrDefault(_positionMsFlow.value)
            if (mp.isPlaying) {
                mp.pause()
            }
            _isPlayingFlow.value = false
            mainHandler.removeCallbacks(progressTicker)
            publishSnapshot(
                isPlaying = false,
                positionMs = pos,
                durationMs = currentDurationMs
            )
            cachedAppContext?.let { ctx ->
                _currentTitleFlow.value?.takeIf { it.isNotBlank() }?.let { activeTitle ->
                    persistInAppObservation(
                        context = ctx,
                        eventType = ObservationEventType.PLAYBACK_STATE_CHANGED,
                        title = activeTitle,
                        artist = _currentArtistFlow.value.orEmpty(),
                        album = currentAlbumName,
                        trackUri = _currentUriFlow.value,
                        durationMs = currentDurationMs,
                        positionMs = pos,
                        playbackStateCode = 2
                    )
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun resume(): Boolean {
        val mp = mediaPlayer ?: return false
        return try {
            val pos = runCatching { mp.currentPosition.toLong().coerceAtLeast(0L) }.getOrDefault(_positionMsFlow.value)
            if (!mp.isPlaying) {
                mp.start()
            }
            _isPlayingFlow.value = true
            publishSnapshot(
                isPlaying = true,
                positionMs = pos,
                durationMs = currentDurationMs
            )
            cachedAppContext?.let { ctx ->
                _currentTitleFlow.value?.takeIf { it.isNotBlank() }?.let { activeTitle ->
                    persistInAppObservation(
                        context = ctx,
                        eventType = ObservationEventType.PLAYBACK_STATE_CHANGED,
                        title = activeTitle,
                        artist = _currentArtistFlow.value.orEmpty(),
                        album = currentAlbumName,
                        trackUri = _currentUriFlow.value,
                        durationMs = currentDurationMs,
                        positionMs = pos,
                        playbackStateCode = 3
                    )
                }
            }
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

    fun seekTo(positionMs: Long): Boolean {
        val mp = mediaPlayer ?: return false
        return try {
            val target = positionMs.coerceAtLeast(0L).toInt()
            mp.seekTo(target)
            val playingNow = _isPlayingFlow.value
            publishSnapshot(
                isPlaying = playingNow,
                positionMs = target.toLong(),
                durationMs = currentDurationMs
            )
            cachedAppContext?.let { ctx ->
                _currentTitleFlow.value?.takeIf { it.isNotBlank() }?.let { activeTitle ->
                    persistInAppObservation(
                        context = ctx,
                        eventType = ObservationEventType.PLAYBACK_STATE_CHANGED,
                        title = activeTitle,
                        artist = _currentArtistFlow.value.orEmpty(),
                        album = currentAlbumName,
                        trackUri = _currentUriFlow.value,
                        durationMs = currentDurationMs,
                        positionMs = target.toLong(),
                        playbackStateCode = if (playingNow) 3 else 2
                    )
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun setClipLoop(startMs: Long, endMs: Long, enabled: Boolean) {
        loopStartMs = startMs.coerceAtLeast(0L)
        loopEndMs = maxOf(loopStartMs + 5_000L, endMs)
        loopEnabled = enabled
    }

    fun stop() {
        loopEnabled = false
        val posSnapshot = runCatching { mediaPlayer?.currentPosition?.toLong()?.coerceAtLeast(0L) }
            .getOrNull() ?: _positionMsFlow.value
        val activeTitle = _currentTitleFlow.value?.takeIf { it.isNotBlank() }
        if (activeTitle != null && _hasActiveTrackFlow.value) {
            cachedAppContext?.let { ctx ->
                persistInAppObservation(
                    context = ctx,
                    eventType = ObservationEventType.PLAYBACK_STATE_CHANGED,
                    title = activeTitle,
                    artist = _currentArtistFlow.value.orEmpty(),
                    album = currentAlbumName,
                    trackUri = _currentUriFlow.value,
                    durationMs = currentDurationMs,
                    positionMs = posSnapshot,
                    playbackStateCode = 2
                )
            }
        }
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
            _positionMsFlow.value = 0L
            _durationMsFlow.value = 0L
        }
    }

    private fun persistInAppObservation(
        context: Context,
        eventType: ObservationEventType,
        title: String,
        artist: String,
        album: String?,
        trackUri: String?,
        durationMs: Long,
        positionMs: Long,
        playbackStateCode: Int
    ) {
        val cleanTitle = title.trim().takeIf { it.isNotBlank() } ?: return
        val appCtx = context.applicationContext
        if (AgentSettingsStore.isPrivateSessionEnabled(appCtx)) return
        val pkg = appPackageName.ifBlank { appCtx.packageName }
        val obs = PlaybackObservation(
            eventId = UUID.randomUUID().toString(),
            observerSessionId = "tunemind_inapp_player",
            eventType = eventType,
            eventTimeMs = System.currentTimeMillis(),
            sourcePackage = pkg,
            trackId = trackUri?.takeIf { it.isNotBlank() },
            trackTitle = cleanTitle,
            trackArtist = artist.trim().takeIf { it.isNotBlank() },
            trackAlbum = album?.trim()?.takeIf { it.isNotBlank() },
            durationMs = durationMs.takeIf { it > 0L },
            positionMs = positionMs.coerceAtLeast(0L),
            playbackStateCode = playbackStateCode,
            playbackSpeed = 1.0f
        )
        persistenceScope.launch {
            runCatching {
                EventBufferRepository(TuneMindDatabase.getInstance(appCtx).eventQueueDao()).record(obs)
            }
        }
    }

    private fun publishSnapshot(isPlaying: Boolean, positionMs: Long, durationMs: Long) {
        _positionMsFlow.value = positionMs.coerceAtLeast(0L)
        _durationMsFlow.value = durationMs.coerceAtLeast(0L)
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
            TuneMindListenerService.dispatchPlaybackSnapshot(snap)
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
