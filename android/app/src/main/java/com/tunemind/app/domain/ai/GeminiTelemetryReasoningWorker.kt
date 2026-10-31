package com.tunemind.app.domain.ai

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tunemind.app.data.local.EventBufferRepository
import com.tunemind.app.data.local.PlaybackObservationCodec
import com.tunemind.app.data.local.TuneMindDatabase
import com.tunemind.app.domain.memory.ListeningMemoryBuilder
import com.tunemind.app.domain.model.PlaybackObservation
import com.tunemind.app.domain.model.TrackIdentityBasis
import com.tunemind.app.domain.reconstruction.ListeningSessionReconstructor
import com.tunemind.app.domain.temporal.TimeSlot
import com.tunemind.app.ui.NowPlayingObservation
import com.tunemind.app.ui.VaultTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Android WorkManager CoroutineWorker that periodically fetches user telemetry events
 * from the Room database and invokes Google Gemini to generate a high-level Reasoning Report.
 */
class GeminiTelemetryReasoningWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val db = TuneMindDatabase.getInstance(applicationContext)
            val rows = db.eventQueueDao().getRecentEvents(120)

            val observations: List<PlaybackObservation> = rows.mapNotNull { row ->
                runCatching {
                    PlaybackObservationCodec.decode(row.payloadJson, legacySavedAtMs = row.savedAtMs)
                }.getOrNull()
            }

            // Reconstruct memory from raw telemetry observations
            val truncated = observations.size >= EventBufferRepository.MAX_OBSERVATIONS
            val episodes = ListeningSessionReconstructor().reconstruct(observations, retainedWindowMayBeTruncated = truncated)
            val memory = ListeningMemoryBuilder().build(observations, episodes, retainedHistoryMayBeIncomplete = truncated)

            val recentTracks = memory.tracks
                .filter { it.identityBasis != TrackIdentityBasis.INSUFFICIENT }
                .sortedByDescending { it.lastObservedAppearanceAtMs }
                .take(8)

            // Detect top source package
            val latestStateObs = observations.firstOrNull { it.playbackStateCode != null }
            val latestTrackMeta = observations.firstOrNull { !it.trackTitle.isNullOrBlank() }
            val topSourcePackage = latestStateObs?.sourcePackage
                ?: latestTrackMeta?.sourcePackage
                ?: "com.spotify.music"
            val topSourceApp = topSourcePackage.substringAfterLast(".").replaceFirstChar { it.uppercase() }

            val isPlayingNow = latestStateObs?.playbackStateCode == 3
            val nowPlaying = if (latestTrackMeta != null || latestStateObs != null) {
                NowPlayingObservation(
                    title = latestTrackMeta?.trackTitle ?: latestStateObs?.trackTitle,
                    artist = latestTrackMeta?.trackArtist ?: latestStateObs?.trackArtist,
                    album = latestTrackMeta?.trackAlbum ?: latestStateObs?.trackAlbum,
                    sourcePackage = topSourcePackage,
                    isPlaying = isPlayingNow,
                    playbackStateCode = latestStateObs?.playbackStateCode,
                    positionMs = latestStateObs?.positionMs ?: 0L,
                    durationMs = latestTrackMeta?.durationMs ?: latestStateObs?.durationMs ?: 0L,
                    speed = latestStateObs?.playbackSpeed ?: 1.0f,
                    updatedAtMs = latestStateObs?.eventTimeMs ?: System.currentTimeMillis()
                )
            } else null

            // Read vault tracks from SharedPreferences
            val vaultPrefs = applicationContext.getSharedPreferences("tunemind_vault_prefs", Context.MODE_PRIVATE)
            val vaultJson = vaultPrefs.getString("vault_tracks_json", null)
            val vaultTracks = mutableListOf<VaultTrack>()
            if (!vaultJson.isNullOrBlank()) {
                try {
                    val array = org.json.JSONArray(vaultJson)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        vaultTracks.add(
                            VaultTrack(
                                id = obj.getString("id"),
                                title = obj.getString("title"),
                                artist = obj.getString("artist"),
                                quarantinedAtMs = obj.getLong("quarantinedAtMs"),
                                totalDays = obj.optDouble("totalDays", 5.0).toFloat(),
                                cooldownDaysRemaining = obj.optDouble("cooldownDaysRemaining", 5.0).toFloat(),
                                recoveryProgress = obj.optDouble("recoveryProgress", 0.1).toFloat(),
                                satietyScore = obj.optInt("satietyScore", 85)
                            )
                        )
                    }
                } catch (_: Exception) {}
            }

            val timeSlot = TimeSlot.currentSlot().label

            GeminiReasoningEngine.generateTelemetryReasoningReport(
                context = applicationContext,
                eventCount = observations.size,
                topSourceApp = topSourceApp,
                recentTracks = recentTracks,
                vaultTracks = vaultTracks,
                nowPlaying = nowPlaying,
                timeSlot = timeSlot
            )

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val PERIODIC_WORK_TAG = "tunemind_gemini_telemetry_reasoning_periodic"
        const val IMMEDIATE_WORK_TAG = "tunemind_gemini_telemetry_reasoning_immediate"

        /**
         * Schedules periodic reasoning generation every 15 minutes.
         */
        fun schedulePeriodic(context: Context) {
            try {
                val periodicRequest = PeriodicWorkRequestBuilder<GeminiTelemetryReasoningWorker>(
                    15, TimeUnit.MINUTES,
                    5, TimeUnit.MINUTES // Flex window
                ).build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    PERIODIC_WORK_TAG,
                    ExistingPeriodicWorkPolicy.KEEP,
                    periodicRequest
                )
            } catch (_: Exception) {}
        }

        /**
         * Enqueues immediate telemetry reasoning generation.
         */
        fun enqueueImmediate(context: Context) {
            try {
                val oneTimeRequest = OneTimeWorkRequestBuilder<GeminiTelemetryReasoningWorker>()
                    .build()

                WorkManager.getInstance(context).enqueueUniqueWork(
                    IMMEDIATE_WORK_TAG,
                    ExistingWorkPolicy.REPLACE,
                    oneTimeRequest
                )
            } catch (_: Exception) {}
        }
    }
}
