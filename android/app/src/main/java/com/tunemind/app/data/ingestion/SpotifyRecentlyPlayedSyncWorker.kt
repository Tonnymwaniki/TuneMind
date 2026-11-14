package com.tunemind.app.data.ingestion

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.tunemind.app.data.local.TuneMindDatabase
import java.util.concurrent.TimeUnit

/**
 * WorkManager [CoroutineWorker] that runs every 15 minutes (and upon OS audio-focus changes)
 * to fetch `GET https://api.spotify.com/v1/me/player/recently-played` via Retrofit,
 * map the tracks to [TrackHistoryTelemetryItem] (and [SpotifyListeningHistoryEntity]),
 * and insert them into [TuneMindDatabase] while ignoring duplicates (`OnConflictStrategy.IGNORE`).
 */
class SpotifyRecentlyPlayedSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val triggerReason = inputData.getString(KEY_TRIGGER_REASON) ?: "PERIODIC_15_MIN"
        Log.i(TAG, "SpotifyRecentlyPlayedSyncWorker started (trigger=$triggerReason)")

        val authManager = SpotifyAuthManager(applicationContext)
        if (!authManager.isAuthenticated()) {
            Log.d(TAG, "Skipping Spotify Web API sync: user has not authenticated Spotify OAuth yet.")
            return Result.success()
        }

        // 1. Obtain a valid (or silently refreshed) Access Token from EncryptedSharedPreferences
        val validAccessToken = authManager.getValidAccessToken()
        if (validAccessToken.isNullOrBlank()) {
            Log.w(TAG, "Spotify Access Token expired and could not be refreshed.")
            return Result.retry()
        }

        // 2. Fetch GET https://api.spotify.com/v1/me/player/recently-played with Authorization: Bearer {token}
        val database = TuneMindDatabase.getInstance(applicationContext)
        val historyDao = database.spotifyListeningHistoryDao()
        val nowMs = System.currentTimeMillis()

        try {
            val response = SpotifyApiFactory.directWebApiService.getRecentlyPlayed(
                authHeader = "Bearer $validAccessToken",
                limit = 50
            )

            if (response.isSuccessful) {
                val items = response.body()?.items.orEmpty()

                // 3. Map fetched items to TrackHistoryTelemetryItem and SpotifyListeningHistoryEntity
                val telemetryItems: List<TrackHistoryTelemetryItem> = items.map { dto ->
                    dto.toTrackHistoryTelemetryItem(nowMs)
                }
                val historyEntities: List<SpotifyListeningHistoryEntity> = items.map { dto ->
                    dto.toHistoryEntity(nowMs)
                }

                // 4. Insert into TuneMindDatabase while ignoring duplicates (OnConflictStrategy.IGNORE)
                if (telemetryItems.isNotEmpty()) {
                    historyDao.insertTelemetryItemsIgnoreDuplicates(telemetryItems)
                    historyDao.insertHistoryIgnoreDuplicates(historyEntities)
                }
            } else if (response.code() in 500..599 || response.code() == 429) {
                Log.w(TAG, "Spotify Web API returned HTTP ${response.code()} — scheduling WorkManager retry.")
                return Result.retry()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Network exception fetching recently-played tracks: ${e.message}")
            return Result.retry()
        }

        // 5. Run full repository sync (currently-playing + EventQueue mirror + Satiety evaluation)
        val repo = SpotifyOAuthRepository.getInstance(applicationContext)
        return when (val syncResult = repo.syncRecentlyPlayedToLocalRoom()) {
            is SpotifyIngestionSyncResult.Success -> {
                Log.i(
                    TAG,
                    "Spotify Web API sync succeeded: ingested=${syncResult.ingestedRecentlyPlayedCount}, " +
                        "totalCached=${syncResult.totalCachedTracks}"
                )
                if (syncResult.totalCachedTracks > 0 &&
                    com.tunemind.app.domain.voice.AgentSettingsStore.isBackgroundAutoPlayEnabled(applicationContext)
                ) {
                    com.tunemind.app.domain.execution.SatietyExecutionForegroundService.triggerSatietyEvaluation(
                        applicationContext
                    )
                }
                Result.success(
                    workDataOf(
                        "ingested_count" to syncResult.ingestedRecentlyPlayedCount,
                        "total_cached_tracks" to syncResult.totalCachedTracks
                    )
                )
            }
            is SpotifyIngestionSyncResult.Unauthenticated -> {
                Log.w(TAG, "Spotify Web API sync unauthenticated: ${syncResult.message}")
                Result.success()
            }
            is SpotifyIngestionSyncResult.TransientFailure -> {
                Log.w(TAG, "Spotify Web API transient error (${syncResult.code}): ${syncResult.reason} — scheduling retry.")
                Result.retry()
            }
        }
    }

    companion object {
        private const val TAG = "SpotifySyncWorker"
        private const val UNIQUE_PERIODIC_WORK_NAME = "tunemind_spotify_recently_played_15m_sync"
        private const val UNIQUE_IMMEDIATE_WORK_NAME = "tunemind_spotify_audio_focus_immediate_sync"
        private const val KEY_TRIGGER_REASON = "trigger_reason"
        private const val DEBOUNCE_WINDOW_MS = 20_000L

        @Volatile
        private var lastImmediateTriggerMs: Long = 0L

        /**
         * Registers a 15-minute PeriodicWorkRequest with WorkManager so TuneMind continuously
         * pulls `/v1/me/player/recently-played` into the local Room database in the background.
         */
        fun schedulePeriodic15MinSync(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val periodicRequest = PeriodicWorkRequestBuilder<SpotifyRecentlyPlayedSyncWorker>(
                    15L, TimeUnit.MINUTES
                )
                    .setConstraints(constraints)
                    .setInputData(workDataOf(KEY_TRIGGER_REASON to "PERIODIC_15_MIN"))
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        1L,
                        TimeUnit.MINUTES
                    )
                    .build()

                WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                    UNIQUE_PERIODIC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    periodicRequest
                )
                Log.i(TAG, "Scheduled 15-minute periodic Spotify Web API sync worker.")
            } catch (e: Exception) {
                Log.w(TAG, "Could not schedule periodic Spotify sync worker: ${e.message}")
            }
        }

        /**
         * Enqueues an immediate one-shot WorkManager sync upon OS audio-focus or playback state changes.
         * Uses a 20-second debounce window to prevent bursty audio focus transitions from spamming the API.
         */
        fun enqueueImmediateOnAudioFocusChange(
            context: Context,
            reason: String = "OS_AUDIO_FOCUS_CHANGED",
            bypassDebounce: Boolean = false
        ) {
            val nowMs = System.currentTimeMillis()
            if (!bypassDebounce && (nowMs - lastImmediateTriggerMs) < DEBOUNCE_WINDOW_MS) {
                return
            }
            lastImmediateTriggerMs = nowMs

            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val immediateRequest = OneTimeWorkRequestBuilder<SpotifyRecentlyPlayedSyncWorker>()
                    .setConstraints(constraints)
                    .setInputData(workDataOf(KEY_TRIGGER_REASON to reason))
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        30L,
                        TimeUnit.SECONDS
                    )
                    .build()

                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    UNIQUE_IMMEDIATE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    immediateRequest
                )
                Log.d(TAG, "Enqueued immediate Spotify Web API sync (reason=$reason)")
            } catch (e: Exception) {
                Log.w(TAG, "Could not enqueue immediate Spotify sync: ${e.message}")
            }
        }
    }
}

/**
 * OS-Level Audio Focus & Playback Configuration Monitor that triggers
 * [SpotifyRecentlyPlayedSyncWorker.enqueueImmediateOnAudioFocusChange] whenever active media
 * playback or audio focus shifts on the Android device.
 */
object AudioFocusIngestionMonitor {
    private const val TAG = "AudioFocusIngestion"
    private var isRegistered = false
    private var lastWasMediaActive = false
    private val mainHandler = Handler(Looper.getMainLooper())

    fun startMonitoring(context: Context) {
        if (isRegistered) return
        val appContext = context.applicationContext
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

        try {
            audioManager.registerAudioPlaybackCallback(
                object : AudioManager.AudioPlaybackCallback() {
                    override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                        super.onPlaybackConfigChanged(configs)
                        val isMediaActive = configs?.any { cfg ->
                            val usage = cfg.audioAttributes?.usage
                            usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME
                        } == true

                        if (isMediaActive != lastWasMediaActive) {
                            lastWasMediaActive = isMediaActive
                            val reason = if (isMediaActive) "AUDIO_FOCUS_MEDIA_GAINED" else "AUDIO_FOCUS_MEDIA_RELEASED"
                            SpotifyRecentlyPlayedSyncWorker.enqueueImmediateOnAudioFocusChange(
                                context = appContext,
                                reason = reason
                            )
                        }
                    }
                },
                mainHandler
            )
            isRegistered = true
            Log.i(TAG, "Registered OS-level AudioPlaybackCallback for Spotify Web API Data Ingestion.")
        } catch (e: Exception) {
            Log.w(TAG, "AudioPlaybackCallback registration notice: ${e.message}")
        }
    }

    /**
     * Creates an [AudioManager.OnAudioFocusChangeListener] that also triggers immediate Spotify Web API
     * ingestion whenever audio focus shifts (e.g. `AUDIOFOCUS_GAIN`, `AUDIOFOCUS_LOSS`, `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`).
     */
    fun createAudioFocusChangeListener(context: Context): AudioManager.OnAudioFocusChangeListener {
        val appContext = context.applicationContext
        return AudioManager.OnAudioFocusChangeListener { focusChange ->
            val reason = when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> "AUDIOFOCUS_GAIN"
                AudioManager.AUDIOFOCUS_LOSS -> "AUDIOFOCUS_LOSS"
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "AUDIOFOCUS_LOSS_TRANSIENT"
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "AUDIOFOCUS_DUCK"
                else -> "AUDIOFOCUS_CHANGE_$focusChange"
            }
            SpotifyRecentlyPlayedSyncWorker.enqueueImmediateOnAudioFocusChange(
                context = appContext,
                reason = reason
            )
        }
    }
}
