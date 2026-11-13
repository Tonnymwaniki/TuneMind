package com.tunemind.app.domain.execution

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tunemind.app.MainActivity
import com.tunemind.app.data.local.PlaybackObservationCodec
import com.tunemind.app.data.local.TuneMindDatabase
import com.tunemind.app.sync.SyncConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Android Foreground Service that powers the "Reasoning and Execution Branch" of TuneMind:
 * 1. Reads the user's last 50 tracks from the Local-First Room Database (`SpotifyListeningHistoryDao`
 *    built in Script 1, enriched with skip duration telemetry from `EventQueueDao`).
 * 2. Sends the 50-track JSON payload to `POST /api/v1/reasoning/satiety-decision`.
 * 3. Receives the structured JSON decision (`{"state": "fatigued|focused", "action": "change_genre|skip_track|maintain", "reasoning": "..."}`)
 *    and automatically executes `skip_track` or `play_playlist` / `change_genre` via [AgenticToolRegistry]
 *    without requiring the user to open the app.
 */
class SatietyExecutionForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        NotificationTool.ensureChannel(this)
        startForeground(
            NotificationTool.NOTIFICATION_ID,
            buildStandingForegroundNotification("TuneMind Autonomous Satiety Engine Active")
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HANDLE_DECISION_JSON -> {
                val rawJson = intent.getStringExtra(SatietyDecisionReceiver.EXTRA_DECISION_JSON)
                if (!rawJson.isNullOrBlank()) {
                    serviceScope.launch {
                        runCatching {
                            val decision = SatietyDecisionPayload.fromJsonString(rawJson)
                            AgenticToolRegistry.executeDecisionAndTools(applicationContext, decision)
                        }.onFailure {
                            Log.e(TAG, "Error handling decision JSON in ForegroundService", it)
                        }
                    }
                }
            }
            ACTION_EVALUATE_LAST_50_TRACKS, null -> {
                serviceScope.launch {
                    evaluateLocalRoomHistoryAndExecute()
                }
            }
            ACTION_STOP_SERVICE -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    /**
     * Reads the last 50 tracks from the local Room DB (`spotify_listening_history` + `event_queue`),
     * posts them to `/api/v1/reasoning/satiety-decision`, and executes the resulting OS-level commands.
     */
    suspend fun evaluateLocalRoomHistoryAndExecute(): SatietyDecisionPayload? {
        val db = TuneMindDatabase.getInstance(applicationContext)
        val spotifyDao = db.spotifyListeningHistoryDao()
        val eventQueueDao = db.eventQueueDao()

        // 1. Read up to 50 tracks from the Local-First Spotify Room table
        val spotifyRows = runCatching { spotifyDao.getRecentHistorySnapshot(50) }.getOrDefault(emptyList())

        // 2. Also read recent event observations to compute accurate skip durations
        val rawEvents = runCatching { eventQueueDao.getRecentEvents(100) }.getOrDefault(emptyList())
        val decodedObs = rawEvents.mapNotNull { row ->
            runCatching {
                PlaybackObservationCodec.decode(row.payloadJson, legacySavedAtMs = row.savedAtMs)
            }.getOrNull()
        }

        val tracksJsonArray = JSONArray()

        if (spotifyRows.isNotEmpty()) {
            for (index in spotifyRows.indices) {
                val item = spotifyRows[index]
                val nextPlayedAt = spotifyRows.getOrNull(index - 1)?.playedAtEpochMs
                val inferredListenMs = if (nextPlayedAt != null && nextPlayedAt > item.playedAtEpochMs) {
                    (nextPlayedAt - item.playedAtEpochMs).coerceAtMost(item.durationMs.takeIf { it > 0 } ?: 210_000L)
                } else {
                    item.durationMs.takeIf { it > 0 } ?: 180_000L
                }
                val duration = item.durationMs.takeIf { it > 0 } ?: 180_000L
                val wasSkipped = inferredListenMs < (duration * 0.25).toLong().coerceAtMost(35_000L)

                tracksJsonArray.put(
                    JSONObject().apply {
                        put("track_id", item.trackId)
                        put("spotify_uri", item.spotifyUri)
                        put("track_title", item.trackTitle)
                        put("artist_name", item.artistName)
                        put("album_name", item.albumName)
                        put("duration_ms", duration)
                        put("played_at_epoch_ms", item.playedAtEpochMs)
                        put("listened_duration_ms", inferredListenMs)
                        if (wasSkipped) {
                            put("skip_duration_ms", inferredListenMs)
                        }
                        put("was_skipped", wasSkipped)
                    }
                )
            }
        } else {
            // Fallback to decoded local MediaSession observations if Spotify Web API table is empty
            decodedObs
                .filter { !it.trackTitle.isNullOrBlank() }
                .take(50)
                .forEach { obs ->
                    val duration = obs.durationMs ?: 180_000L
                    val pos = obs.positionMs ?: duration
                    val wasSkipped = pos in 1..29_999L
                    tracksJsonArray.put(
                        JSONObject().apply {
                            put("track_id", obs.trackId ?: obs.eventId)
                            put("spotify_uri", obs.trackId ?: "")
                            put("track_title", obs.trackTitle ?: "Unknown Track")
                            put("artist_name", obs.trackArtist ?: "Unknown Artist")
                            put("duration_ms", duration)
                            put("played_at_epoch_ms", obs.eventTimeMs)
                            put("listened_duration_ms", pos)
                            if (wasSkipped) {
                                put("skip_duration_ms", pos)
                            }
                            put("was_skipped", wasSkipped)
                        }
                    )
                }
        }

        if (tracksJsonArray.length() == 0) {
            Log.d(TAG, "No local Room tracks available yet to evaluate satiety.")
            return null
        }

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val daypart = when (hour) {
            in 6..11 -> "Morning"
            in 12..16 -> "Midday"
            in 17..21 -> "Evening"
            else -> "Late Night"
        }

        val requestPayload = JSONObject().apply {
            put("circadian_daypart", daypart)
            put("tracks", tracksJsonArray)
        }

        val baseUrl = SyncConfig.baseUrl(applicationContext).trimEnd('/')
        val request = Request.Builder()
            .url("$baseUrl/api/v1/reasoning/satiety-decision")
            .post(requestPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return try {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Backend /satiety-decision returned HTTP ${resp.code}")
                    return null
                }
                val rawBody = resp.body?.string() ?: return null
                val decision = SatietyDecisionPayload.fromJsonString(rawBody)
                AgenticToolRegistry.executeDecisionAndTools(applicationContext, decision)
                decision
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to reach /satiety-decision, evaluating local deterministic satiety: ${e.message}")
            null
        }
    }

    private fun buildStandingForegroundNotification(statusText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NotificationTool.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("TuneMind Audio Intelligence")
            .setContentText(statusText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SatietyExecService"
        const val ACTION_EVALUATE_LAST_50_TRACKS = "com.tunemind.app.ACTION_EVALUATE_LAST_50_TRACKS"
        const val ACTION_HANDLE_DECISION_JSON = "com.tunemind.app.ACTION_HANDLE_DECISION_JSON"
        const val ACTION_STOP_SERVICE = "com.tunemind.app.ACTION_STOP_SATIETY_SERVICE"

        fun triggerSatietyEvaluation(context: Context) {
            val intent = Intent(context.applicationContext, SatietyExecutionForegroundService::class.java).apply {
                action = ACTION_EVALUATE_LAST_50_TRACKS
            }
            runCatching {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            }.onFailure {
                Log.w(TAG, "Could not start SatietyExecutionForegroundService: ${it.message}")
            }
        }
    }
}
