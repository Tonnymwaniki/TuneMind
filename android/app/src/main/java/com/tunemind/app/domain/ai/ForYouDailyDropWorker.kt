package com.tunemind.app.domain.ai

import android.content.Context
import android.media.AudioManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tunemind.app.data.auth.TuneMindAuthSessionManager
import com.tunemind.app.data.local.EventBufferRepository
import com.tunemind.app.data.local.PlaybackObservationCodec
import com.tunemind.app.data.local.TuneMindDatabase
import com.tunemind.app.domain.memory.ListeningMemoryBuilder
import com.tunemind.app.domain.model.PlaybackObservation
import com.tunemind.app.domain.model.TrackIdentityResolver
import com.tunemind.app.domain.reconstruction.ListeningSessionReconstructor
import com.tunemind.app.domain.temporal.TimeSlot
import com.tunemind.app.provider.MusicProviderRegistry
import com.tunemind.app.ui.VaultTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Silent 12:00 PM Local-Time Background Drop Sync Worker.
 *
 * Pre-generates and stores today's 12 genre picks + 3 completely new-genre wildcards
 * inside Room once per daily cycle so when the user opens the app, picks are ready
 * immediately without loading delays or repeated paid AI API calls.
 */
class ForYouDailyDropWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val db = TuneMindDatabase.getInstance(applicationContext)
            val dao = db.aiRecommendationDao()
            val todayNoonKey = NoonDropSchedule.getNoonCycleKey()

            // Cost guard: never re-query backend if today's 12:00 PM cycle is already cached in Room
            val existingToday = dao.getByDropDateSnapshot(todayNoonKey)
            if (existingToday.size >= 10) {
                scheduleNextNoonDrop(applicationContext, replaceExisting = true)
                return@withContext Result.success()
            }

            val rows = db.eventQueueDao().getRecentEvents(150)
            val observations: List<PlaybackObservation> = rows.mapNotNull { row ->
                runCatching {
                    PlaybackObservationCodec.decode(row.payloadJson, legacySavedAtMs = row.savedAtMs)
                }.getOrNull()
            }
            val truncated = observations.size >= EventBufferRepository.MAX_OBSERVATIONS
            val episodes = ListeningSessionReconstructor().reconstruct(observations, retainedWindowMayBeTruncated = truncated)
            val memory = ListeningMemoryBuilder().build(observations, episodes, retainedHistoryMayBeIncomplete = truncated)
            val recentTracks = memory.tracks.take(24)

            // Read Vault tracks so already-resting tracks are excluded
            val vaultPrefs = applicationContext.getSharedPreferences("tunemind_vault_prefs", Context.MODE_PRIVATE)
            val vaultJson = vaultPrefs.getString("vault_items", null) ?: vaultPrefs.getString("vault_tracks_json", null)
            val vaultTracks = mutableListOf<VaultTrack>()
            if (!vaultJson.isNullOrBlank()) {
                runCatching {
                    val array = org.json.JSONArray(vaultJson)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        vaultTracks.add(
                            VaultTrack(
                                id = obj.optString("id"),
                                title = obj.optString("title"),
                                artist = obj.optString("artist"),
                                quarantinedAtMs = obj.optLong("quarantinedAtMs"),
                                totalDays = obj.optDouble("totalDays", 5.0).toFloat(),
                                cooldownDaysRemaining = obj.optDouble("cooldownDaysRemaining", 5.0).toFloat(),
                                recoveryProgress = obj.optDouble("recoveryProgress", 0.1).toFloat(),
                                satietyScore = obj.optInt("satietyScore", 85)
                            )
                        )
                    }
                }
            }

            val knownFromCrate = dao.getAllKnownTitles()
            val historyTitles = recentTracks.mapNotNull { it.title }
            val vaultTitles = vaultTracks.map { it.title }
            val combinedExclusions = (knownFromCrate + historyTitles + vaultTitles).distinct()

            val playedSignals = dao.getPositiveFeedbackSignals()
            val dismissedSignals = dao.getDismissedSignals()
            val previousOutcomes = dao.getPreviousDropsForEvaluation(todayNoonKey, 30)

            val slot = TimeSlot.currentSlot()
            val prefProvider = MusicProviderRegistry.getInstance(applicationContext).getPreferredProviderId()
            val authSession = TuneMindAuthSessionManager.getInstance(applicationContext).sessionFlow.value
            val currentPlays = recentTracks.firstOrNull()?.distinctAppearanceCount?.coerceAtLeast(0) ?: 0
            val satiety = if (currentPlays <= 0) 15 else minOf(95, maxOf(18, currentPlays * 22))
            val avgVol = runCatching {
                val am = applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val cur = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                val max = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
                if (max > 0) (cur.toFloat() / max.toFloat()).coerceIn(0.1f, 1.0f) else null
            }.getOrNull()

            val newRecs = ClaudeReasoningEngine.generateChronoCrateRecommendations(
                context = applicationContext,
                recentTracks = recentTracks,
                vaultTracks = vaultTracks,
                timeSlotKey = slot.name,
                timeSlotLabel = slot.label,
                satietyPercent = satiety,
                preferredProviderId = prefProvider,
                userTasteAnchors = emptyList(),
                nowPlayingArtist = recentTracks.firstOrNull()?.artist,
                excludeTitles = combinedExclusions,
                playedSignals = playedSignals,
                dismissedSignals = dismissedSignals,
                userId = authSession.userId,
                userEmail = authSession.email,
                avgVolumeRatio = avgVol,
                yesterdayOutcomes = previousOutcomes,
                forceRefresh = false
            )

            if (newRecs.isNotEmpty()) {
                val stamped = newRecs.map { rec ->
                    val canonicalId = TrackIdentityResolver.resolveCanonicalTrackId(
                        trackId = rec.trackId,
                        spotifyUri = rec.spotifyUri,
                        title = rec.title,
                        artist = rec.artist
                    )
                    rec.copy(dropDateKey = todayNoonKey, trackId = canonicalId)
                }
                dao.insertAll(stamped)
                // Prune older un-saved recommendations > 14 days so DB stays lean while keeping recent exclusions
                val fourteenDaysAgoMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(14)
                dao.pruneOldUnsaved(fourteenDaysAgoMs)
            }

            // Always schedule tomorrow's 12:00 PM drop in background
            scheduleNextNoonDrop(applicationContext, replaceExisting = true)
            Result.success()
        } catch (_: Throwable) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_NOON_DROP_WORK = "tunemind_for_you_noon_drop_oneshot"
        private const val UNIQUE_DAILY_BACKUP_WORK = "tunemind_for_you_daily_backup_periodic"

        fun computeDelayUntilNextNoonMs(now: Calendar = Calendar.getInstance()): Long {
            val nextNoon = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 12)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 10)
                set(Calendar.MILLISECOND, 0)
            }
            if (!nextNoon.after(now)) {
                nextNoon.add(Calendar.DAY_OF_YEAR, 1)
            }
            return (nextNoon.timeInMillis - now.timeInMillis).coerceAtLeast(60_000L)
        }

        fun scheduleNextNoonDrop(context: Context, replaceExisting: Boolean = false) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val delayMs = computeDelayUntilNextNoonMs()
            val noonOneShot = OneTimeWorkRequestBuilder<ForYouDailyDropWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_NOON_DROP_WORK,
                if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                noonOneShot
            )

            // Secondary 24h periodic safeguard aligned near noon in case OEM kills exact timer
            val periodicSafeguard = PeriodicWorkRequestBuilder<ForYouDailyDropWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_DAILY_BACKUP_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicSafeguard
            )
        }
    }
}
