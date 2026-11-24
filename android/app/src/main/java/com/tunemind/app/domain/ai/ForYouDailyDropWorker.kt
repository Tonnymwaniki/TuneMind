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
import com.tunemind.app.domain.recommendation.AiRecommendationEntity
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
            if (!isForYouAiEnabled(applicationContext)) {
                return@withContext Result.success()
            }
            val db = TuneMindDatabase.getInstance(applicationContext)
            val dao = db.aiRecommendationDao()
            val todayNoonKey = NoonDropSchedule.getNoonCycleKey()

            // Cost guard: never re-query backend if today's 12:00 PM cycle is already cached in Room
            val existingToday = dao.getByDropDateSnapshot(todayNoonKey)
            if (existingToday.size >= 10) {
                scheduleNextNoonDrop(applicationContext, replaceExisting = true)
                return@withContext Result.success()
            }

            dao.clearInvalidSpotifyUris()

            val rows = db.eventQueueDao().getRecentEvents(400)
            val observations: List<PlaybackObservation> = rows.mapNotNull { row ->
                runCatching {
                    PlaybackObservationCodec.decode(row.payloadJson, legacySavedAtMs = row.savedAtMs)
                }.getOrNull()
            }
            val truncated = observations.size >= EventBufferRepository.MAX_OBSERVATIONS
            val episodes = ListeningSessionReconstructor().reconstruct(observations, retainedWindowMayBeTruncated = truncated)
            val memory = ListeningMemoryBuilder().build(observations, episodes, retainedHistoryMayBeIncomplete = truncated)
            val recentTracks = memory.tracks.take(200)

            // Read Vault tracks so already-resting tracks are excluded
            val vaultPrefs = com.tunemind.app.data.auth.TuneMindAccountStorage.getUserPrefs(applicationContext, "tunemind_vault_prefs")
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

            val crateProjections = dao.getAllIdentityProjections()
            val libTracks = db.crossAppLibraryDao().getAllLibraryTracksOnce()
            val playLogs = db.crossAppLibraryDao().getPlayLogsOnce(1500)

            val combinedTitles = LinkedHashSet<String>()
            val combinedKeys = LinkedHashSet<String>(ClaudeReasoningEngine.getPermanentExcludedKeys(applicationContext))
            val combinedUris = LinkedHashSet<String>()
            val combinedIds = LinkedHashSet<String>()

            crateProjections.forEach { p ->
                if (p.title.isNotBlank()) combinedTitles.add(p.title)
                val mk = TrackIdentityResolver.buildNormalizedMatchKey(p.title, p.artist)
                if (mk.isNotBlank()) combinedKeys.add(mk)
                TrackIdentityResolver.normalizeSpotifyUri(p.spotifyUri)?.let { combinedUris.add(it) }
                if (p.trackId.isNotBlank() && !p.trackId.startsWith("disc:") && !p.trackId.startsWith("trk:")) {
                    combinedIds.add(p.trackId)
                }
            }
            recentTracks.forEach { r ->
                r.title?.takeIf { it.isNotBlank() }?.let { combinedTitles.add(it) }
                val mk = TrackIdentityResolver.buildNormalizedMatchKey(r.title, r.artist)
                if (mk.isNotBlank()) combinedKeys.add(mk)
                TrackIdentityResolver.normalizeSpotifyUri(r.providerTrackId)?.let { combinedUris.add(it) }
                if (r.trackId.isNotBlank()) combinedIds.add(r.trackId)
            }
            libTracks.forEach { t ->
                if (t.title.isNotBlank()) combinedTitles.add(t.title)
                if (t.canonicalTrackKey.isNotBlank()) combinedKeys.add(t.canonicalTrackKey)
                val mk = TrackIdentityResolver.buildNormalizedMatchKey(t.title, t.artist)
                if (mk.isNotBlank()) combinedKeys.add(mk)
                TrackIdentityResolver.normalizeSpotifyUri(t.trackId)?.let { combinedUris.add(it) }
                if (t.trackId.isNotBlank()) combinedIds.add(t.trackId)
            }
            playLogs.forEach { pl ->
                if (pl.title.isNotBlank()) combinedTitles.add(pl.title)
                if (pl.canonicalTrackKey.isNotBlank()) combinedKeys.add(pl.canonicalTrackKey)
                TrackIdentityResolver.normalizeSpotifyUri(pl.trackId)?.let { combinedUris.add(it) }
            }
            vaultTracks.forEach { v ->
                if (v.title.isNotBlank()) combinedTitles.add(v.title)
                val mk = TrackIdentityResolver.buildNormalizedMatchKey(v.title, v.artist)
                if (mk.isNotBlank()) combinedKeys.add(mk)
            }

            val playedSignals = dao.getPositiveFeedbackSignals()
            val dismissedSignals = dao.getDismissedSignals()
            val previousOutcomes = dao.getPreviousDropsForEvaluation(todayNoonKey, 30)

            val visualsMap = db.crossAppLibraryDao().getTrackVisualSnapshot().associateBy { it.trackKey }
            val artistsMap = db.crossAppLibraryDao().getArtistProfileSnapshot().associateBy { it.artistKey }
            val visualRepo = com.tunemind.app.data.ingestion.UniversalMusicVisualRepository.getInstance(applicationContext)
            val tasteSum = visualRepo.computeTasteShiftSummary(libTracks, visualsMap, artistsMap)
            val genreDist = linkedMapOf<String, Int>()
            tasteSum.topGenres.forEach { gw ->
                if (gw.genre.isNotBlank() && gw.count > 0) genreDist[gw.genre] = gw.count
            }
            val artistGenreMap = linkedMapOf<String, String>()
            (tasteSum.risingArtists + tasteSum.consistentArtists).forEach { sa ->
                if (sa.name.isNotBlank() && sa.genres.isNotEmpty()) {
                    artistGenreMap[sa.name.lowercase()] = sa.genres.first()
                }
            }
            val dedupSnap = com.tunemind.app.domain.memory.ListeningPlayDeduplicator.buildSnapshot(
                rawObservations = observations,
                crossAppTracks = libTracks,
                crossAppLogs = playLogs,
                nowPlayingTitle = null,
                nowPlayingArtist = null,
                nowPlayingSourcePkg = null,
                isMusicPlayingNow = false
            )

            val slot = TimeSlot.currentSlot()
            val prefProvider = MusicProviderRegistry.getInstance(applicationContext).getPreferredProviderId()
            val authSession = TuneMindAuthSessionManager.getInstance(applicationContext).sessionFlow.value
            val spotifyToken = runCatching {
                if (com.tunemind.app.provider.SpotifyPremiumConnectManager.isConnected(applicationContext)) {
                    com.tunemind.app.provider.SpotifyPremiumConnectManager.refreshAccessTokenIfNeeded(applicationContext)
                } else null
            }.getOrNull()
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
                excludeTitles = combinedTitles.toList(),
                excludeKeys = combinedKeys.toList(),
                excludedSpotifyUris = combinedUris.toList(),
                excludedTrackIds = combinedIds.toList(),
                spotifyAccessToken = spotifyToken,
                playedSignals = playedSignals,
                dismissedSignals = dismissedSignals,
                userId = authSession.userId,
                userEmail = authSession.email,
                avgVolumeRatio = avgVol,
                yesterdayOutcomes = previousOutcomes,
                forceRefresh = false,
                genreDistribution = genreDist,
                artistGenres = artistGenreMap,
                cleanPlayEvents = dedupSnap.cleanPlayHistory
            )

            if (newRecs.isNotEmpty()) {
                val stamped = newRecs.map { rec ->
                    val canonicalId = TrackIdentityResolver.resolveCanonicalTrackId(
                        trackId = rec.trackId,
                        spotifyUri = rec.spotifyUri,
                        title = rec.title,
                        artist = rec.artist
                    )
                    rec.copy(
                        dropDateKey = todayNoonKey,
                        trackId = canonicalId,
                        spotifyUri = TrackIdentityResolver.normalizeSpotifyUri(rec.spotifyUri)
                    )
                }
                ClaudeReasoningEngine.recordPermanentExcludedRecommendations(applicationContext, stamped)
                dao.insertAll(stamped)
                // Record permanent exclusions of any older rows before pruning old unsaved DB rows
                ClaudeReasoningEngine.recordPermanentExcludedRecommendations(
                    applicationContext,
                    crateProjections.map {
                        AiRecommendationEntity(
                            id = it.id,
                            title = it.title,
                            artist = it.artist,
                            reason = "",
                            spotifyUri = it.spotifyUri,
                            trackId = it.trackId
                        )
                    }
                )
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

        private const val FOR_YOU_PREFS_NAME = "tunemind_for_you_prefs"
        private const val KEY_FOR_YOU_AI_ENABLED = "for_you_ai_enabled"

        fun isForYouAiEnabled(context: Context): Boolean {
            return com.tunemind.app.data.auth.TuneMindAccountStorage
                .getUserPrefs(context.applicationContext, FOR_YOU_PREFS_NAME)
                .getBoolean(KEY_FOR_YOU_AI_ENABLED, true)
        }

        fun setForYouAiEnabled(context: Context, enabled: Boolean) {
            val appCtx = context.applicationContext
            com.tunemind.app.data.auth.TuneMindAccountStorage
                .getUserPrefs(appCtx, FOR_YOU_PREFS_NAME)
                .edit()
                .putBoolean(KEY_FOR_YOU_AI_ENABLED, enabled)
                .apply()
            if (!enabled) {
                WorkManager.getInstance(appCtx).cancelUniqueWork(UNIQUE_NOON_DROP_WORK)
                WorkManager.getInstance(appCtx).cancelUniqueWork(UNIQUE_DAILY_BACKUP_WORK)
            }
        }

        fun scheduleNextNoonDrop(context: Context, replaceExisting: Boolean = false) {
            if (!isForYouAiEnabled(context)) return
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
