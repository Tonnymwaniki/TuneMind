package com.tunemind.app.data.local

import android.content.Context
import android.media.session.PlaybackState
import android.util.Log
import com.tunemind.app.data.ingestion.UniversalMusicVisualRepository
import com.tunemind.app.domain.memory.ListeningPlayDeduplicator
import com.tunemind.app.domain.model.PlaybackObservation
import com.tunemind.app.domain.model.TrackIdentityResolver
import com.tunemind.app.domain.voice.AgentSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Permanent Cross-App Listening Recorder.
 *
 * Watches every real playback observation from ALL Android music apps (Boomplay, YouTube Music,
 * Spotify Passive, Audiomack, Deezer, SoundCloud, OEM phone players, and TuneMind In-App) and
 * permanently persists verified listens into `cross_app_track_library` and `cross_app_play_log`.
 *
 * Guarantee: Rows in `cross_app_track_library` & `cross_app_play_log` are NEVER pruned. Even
 * weeks/months later after `event_queue` rotates older raw ticks, every player tab and every
 * song ever played across those apps remains intact permanently.
 */
class CrossAppPlayRecorder private constructor(private val context: Context) {

    private val appContext = context.applicationContext
    private val db: TuneMindDatabase by lazy { TuneMindDatabase.getInstance(appContext) }
    private val dao: CrossAppLibraryDao by lazy { db.crossAppLibraryDao() }
    private val visualRepo: UniversalMusicVisualRepository by lazy { UniversalMusicVisualRepository.getInstance(appContext) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class ActiveSessionState(
        var canonicalKey: String,
        var trackId: String,
        var title: String,
        var artist: String,
        var album: String,
        val sourcePackage: String,
        val startedAtMs: Long,
        var lastEventAtMs: Long,
        var maxPositionMs: Long,
        var durationMs: Long,
        var activeDwellMs: Long,
        var lastStateCode: Int?,
        var flushedAsPlay: Boolean = false
    )

    private val activeByPackage = ConcurrentHashMap<String, ActiveSessionState>()
    private val lastCountedPlayAtByKey = ConcurrentHashMap<String, Long>()
    private val lastAnyPkgPlayAtByKey = ConcurrentHashMap<String, Long>()

    @Volatile
    private var backfillChecked = false

    fun onObservation(obs: PlaybackObservation) {
        if (AgentSettingsStore.isPrivateSessionEnabled(appContext)) return
        if (obs.observerSessionId == "spotify_web_api_ingestion") return
        val title = obs.trackTitle?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val pkg = obs.sourcePackage?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown_player"
        val artist = obs.trackArtist?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown Artist"
        if (ListeningPlayDeduplicator.isExcludedBrowserOrWeb(pkg, title, artist)) return

        val key = TrackIdentityResolver.buildNormalizedMatchKey(title, artist)
        val trackId = TrackIdentityResolver.resolveCanonicalTrackId(
            trackId = obs.trackId,
            spotifyUri = null,
            title = title,
            artist = artist
        )
        val dur = (obs.durationMs ?: 0L).coerceAtLeast(0L)
        val pos = (obs.positionMs ?: 0L).coerceAtLeast(0L)
        val stateCode = obs.playbackStateCode

        val prev = activeByPackage[pkg]
        if (prev != null &&
            ListeningPlayDeduplicator.isSameTrack(prev.title, prev.artist, null, title, artist, null) &&
            (obs.eventTimeMs - prev.lastEventAtMs) <= MERGE_WINDOW_MS
        ) {
            val delta = (obs.eventTimeMs - prev.lastEventAtMs).coerceIn(0L, MAX_DELTA_MS)
            val wasPlaying = prev.lastStateCode == PlaybackState.STATE_PLAYING ||
                (prev.lastStateCode == null && (stateCode == PlaybackState.STATE_PLAYING || pos > prev.maxPositionMs))
            if (wasPlaying && delta > 0L) {
                prev.activeDwellMs += delta
            }
            if ((prev.artist.equals("Unknown Artist", ignoreCase = true) || prev.canonicalKey.endsWith("|")) &&
                !artist.equals("Unknown Artist", ignoreCase = true)
            ) {
                prev.canonicalKey = key
                prev.trackId = trackId
                prev.title = title
                prev.artist = artist
            }
            if (prev.album.isBlank() && !obs.trackAlbum.isNullOrBlank()) {
                prev.album = obs.trackAlbum.trim()
            }
            prev.lastEventAtMs = maxOf(prev.lastEventAtMs, obs.eventTimeMs)
            prev.maxPositionMs = maxOf(prev.maxPositionMs, pos)
            if (dur > 0L) prev.durationMs = maxOf(prev.durationMs, dur)
            if (stateCode != null) prev.lastStateCode = stateCode

            val estimatedListened = maxOf(prev.activeDwellMs, prev.maxPositionMs)
            if (!prev.flushedAsPlay && qualifiesAsVerifiedPlay(estimatedListened, prev.durationMs)) {
                prev.flushedAsPlay = true
                persistVerifiedSessionAsync(prev, isCompletion = false)
            }
        } else {
            if (prev != null) {
                val gap = (obs.eventTimeMs - prev.lastEventAtMs).coerceIn(0L, MAX_DELTA_MS)
                if (prev.lastStateCode == PlaybackState.STATE_PLAYING && gap > 0L) {
                    prev.activeDwellMs += minOf(gap, (prev.durationMs.takeIf { it > 0L } ?: 210_000L))
                }
                val estimatedListened = maxOf(prev.activeDwellMs, prev.maxPositionMs)
                if (!prev.flushedAsPlay && qualifiesAsVerifiedPlay(estimatedListened, prev.durationMs)) {
                    prev.flushedAsPlay = true
                    persistVerifiedSessionAsync(prev, isCompletion = true)
                } else if (prev.flushedAsPlay && estimatedListened > 22_000L) {
                    updateSessionDurationDeltaAsync(prev, estimatedListened)
                }
            }

            activeByPackage[pkg] = ActiveSessionState(
                canonicalKey = key,
                trackId = trackId,
                title = title,
                artist = artist,
                album = obs.trackAlbum?.trim().orEmpty(),
                sourcePackage = pkg,
                startedAtMs = obs.eventTimeMs,
                lastEventAtMs = obs.eventTimeMs,
                maxPositionMs = pos,
                durationMs = dur,
                activeDwellMs = 0L,
                lastStateCode = stateCode,
                flushedAsPlay = false
            )
        }
    }

    /**
     * Records a direct verified play from TuneMind's built-in engine (local file, Audius stream, or preview).
     */
    fun recordDirectPlay(
        title: String,
        artist: String,
        album: String?,
        sourcePackage: String,
        listenedMs: Long,
        durationMs: Long
    ) {
        if (AgentSettingsStore.isPrivateSessionEnabled(appContext)) return
        val cleanTitle = title.trim().takeIf { it.isNotBlank() } ?: return
        val cleanArtist = artist.trim().ifBlank { "Unknown Artist" }
        val key = TrackIdentityResolver.buildNormalizedMatchKey(cleanTitle, cleanArtist)
        val session = ActiveSessionState(
            canonicalKey = key,
            trackId = TrackIdentityResolver.buildDeterministicTrackId(cleanTitle, cleanArtist),
            title = cleanTitle,
            artist = cleanArtist,
            album = album?.trim().orEmpty(),
            sourcePackage = sourcePackage.ifBlank { "com.tunemind.app" },
            startedAtMs = System.currentTimeMillis() - listenedMs.coerceAtLeast(1_000L),
            lastEventAtMs = System.currentTimeMillis(),
            maxPositionMs = listenedMs,
            durationMs = durationMs.coerceAtLeast(listenedMs),
            activeDwellMs = listenedMs,
            lastStateCode = PlaybackState.STATE_PLAYING,
            flushedAsPlay = true
        )
        persistVerifiedSessionAsync(session, isCompletion = true)
    }

    /**
     * One-time backfill when upgrading to Room v18 so historical cross-app OS observations already in
     * `event_queue` seed `cross_app_track_library` & `cross_app_play_log` cleanly.
     */
    fun backfillFromObservationsIfEmpty(observations: List<PlaybackObservation>) {
        if (backfillChecked || observations.isEmpty()) return
        backfillChecked = true
        scope.launch {
            runCatching {
                val existingCount = dao.getTotalLibraryTrackCount()
                if (existingCount > 0) return@launch
                val snapshot = ListeningPlayDeduplicator.buildSnapshot(
                    rawObservations = observations,
                    crossAppTracks = emptyList(),
                    crossAppLogs = emptyList(),
                    nowPlayingTitle = null,
                    nowPlayingArtist = null,
                    nowPlayingSourcePkg = null,
                    isMusicPlayingNow = false,
                    mergeDuplicatePlays = true
                )
                for (ev in snapshot.cleanPlayHistory.sortedBy { it.playedAtMs }) {
                    val key = TrackIdentityResolver.buildNormalizedMatchKey(ev.title, ev.artist)
                    val pkg = ev.sourcePackage.ifBlank { "unknown_player" }
                    val appName = ListeningPlayDeduplicator.resolveAppDisplayName(pkg)
                    dao.insertPlayLog(
                        CrossAppPlayLogEntity(
                            playLogId = ev.playId.ifBlank { UUID.randomUUID().toString() },
                            canonicalTrackKey = key,
                            trackId = ev.trackId,
                            title = ev.title,
                            artist = ev.artist,
                            album = ev.album.orEmpty(),
                            sourcePackage = pkg,
                            sourceAppName = appName,
                            playedAtMs = ev.playedAtMs,
                            listenedMs = ev.listenedMs,
                            trackDurationMs = ev.durationMs,
                            completionRatio = if (ev.durationMs > 0L) (ev.listenedMs.toFloat() / ev.durationMs).coerceIn(0f, 1f) else 1f,
                            wasSkipped = false,
                            syncedToCloud = false
                        )
                    )
                    upsertLibraryMergeSync(
                        key = key,
                        trackId = ev.trackId,
                        title = ev.title,
                        artist = ev.artist,
                        album = ev.album.orEmpty(),
                        sourcePackage = pkg,
                        durationMs = ev.durationMs,
                        listenedMs = ev.listenedMs,
                        playedAtMs = ev.playedAtMs,
                        incrementPlayCount = true
                    )
                }
                if (snapshot.unifiedTracks.isNotEmpty()) {
                    visualRepo.enqueueVisualEnrichment(snapshot.unifiedTracks.map { it.title to it.artist })
                }
                Log.i(TAG, "Backfilled ${snapshot.unifiedTracks.size} permanent cross-app tracks from OS observations.")
            }
        }
    }

    private fun qualifiesAsVerifiedPlay(listenedMs: Long, durationMs: Long): Boolean {
        if (listenedMs >= MIN_VERIFIED_PLAY_MS) return true
        if (durationMs in 5_000L..40_000L && listenedMs >= durationMs / 2L) return true
        return false
    }

    private fun persistVerifiedSessionAsync(session: ActiveSessionState, isCompletion: Boolean) {
        val nowMs = System.currentTimeMillis()
        val dedupeLookupKey = "${session.sourcePackage}::${session.canonicalKey}"
        val anyLookupKey = TrackIdentityResolver.normalizedTitleOnly(session.title)
        val prevCountedAt = lastCountedPlayAtByKey[dedupeLookupKey] ?: 0L
        val prevAnyPkgAt = lastAnyPkgPlayAtByKey[anyLookupKey] ?: 0L
        val shouldIncrementCount = (nowMs - prevCountedAt) > MERGE_WINDOW_MS &&
            (nowMs - prevAnyPkgAt) > CROSS_PKG_MIRROR_WINDOW_MS
        if (shouldIncrementCount) {
            lastCountedPlayAtByKey[dedupeLookupKey] = nowMs
            if (anyLookupKey.isNotBlank()) {
                lastAnyPkgPlayAtByKey[anyLookupKey] = nowMs
            }
        }

        val listenedMs = maxOf(session.activeDwellMs, session.maxPositionMs).coerceAtLeast(MIN_VERIFIED_PLAY_MS)
        val durMs = session.durationMs.takeIf { it > 0L } ?: maxOf(listenedMs, 195_000L)
        val appName = ListeningPlayDeduplicator.resolveAppDisplayName(session.sourcePackage)

        scope.launch {
            runCatching {
                if (shouldIncrementCount) {
                    dao.insertPlayLog(
                        CrossAppPlayLogEntity(
                            playLogId = UUID.randomUUID().toString(),
                            canonicalTrackKey = session.canonicalKey,
                            trackId = session.trackId,
                            title = session.title,
                            artist = session.artist,
                            album = session.album,
                            sourcePackage = session.sourcePackage,
                            sourceAppName = appName,
                            playedAtMs = session.startedAtMs,
                            listenedMs = minOf(listenedMs, durMs),
                            trackDurationMs = durMs,
                            completionRatio = (minOf(listenedMs, durMs).toFloat() / durMs.toFloat()).coerceIn(0f, 1f),
                            wasSkipped = false,
                            syncedToCloud = false
                        )
                    )
                }

                upsertLibraryMergeSync(
                    key = session.canonicalKey,
                    trackId = session.trackId,
                    title = session.title,
                    artist = session.artist,
                    album = session.album,
                    sourcePackage = session.sourcePackage,
                    durationMs = durMs,
                    listenedMs = minOf(listenedMs, durMs),
                    playedAtMs = session.startedAtMs,
                    incrementPlayCount = shouldIncrementCount
                )

                visualRepo.enqueueVisualEnrichment(listOf(session.title to session.artist))
                if (shouldIncrementCount) {
                    com.tunemind.app.data.supabase.SupabaseIntelligenceSyncManager
                        .triggerRelationalTrackCardsSyncAsync(appContext)
                }
                Log.i(TAG, "✅ Permanent library recorded '$session.title' (${appName}) [completion=$isCompletion]")
            }.onFailure { err ->
                Log.w(TAG, "CrossAppPlayRecorder persist warning: ${err.message}")
            }
        }
    }

    private fun updateSessionDurationDeltaAsync(session: ActiveSessionState, totalListenedMs: Long) {
        scope.launch {
            runCatching {
                val existing = dao.getTrackByKey(session.canonicalKey) ?: return@launch
                dao.upsertLibraryTrack(
                    existing.copy(
                        totalListenedMs = maxOf(existing.totalListenedMs, totalListenedMs),
                        lastPlayedAtMs = maxOf(existing.lastPlayedAtMs, session.lastEventAtMs),
                        updatedAtMs = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    private suspend fun upsertLibraryMergeSync(
        key: String,
        trackId: String,
        title: String,
        artist: String,
        album: String,
        sourcePackage: String,
        durationMs: Long,
        listenedMs: Long,
        playedAtMs: Long,
        incrementPlayCount: Boolean
    ) {
        val existing = dao.getTrackByKey(key)
        val mergedPackages = buildSet {
            existing?.sourcePackagesCsv?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.let { addAll(it) }
            add(sourcePackage)
        }.sorted().joinToString(",")

        val nowMs = System.currentTimeMillis()
        val updated = if (existing == null) {
            CrossAppTrackEntity(
                canonicalTrackKey = key,
                trackId = trackId,
                title = title,
                artist = artist,
                album = album,
                genre = "Music",
                durationMs = durationMs,
                primarySourcePackage = sourcePackage,
                sourcePackagesCsv = mergedPackages,
                playCount = if (incrementPlayCount) 1 else 0,
                totalListenedMs = listenedMs,
                firstPlayedAtMs = playedAtMs,
                lastPlayedAtMs = playedAtMs,
                isLiked = false,
                isInVault = false,
                updatedAtMs = nowMs
            )
        } else {
            val shouldUpgradeArtist = existing.artist.equals("Unknown Artist", ignoreCase = true) &&
                !artist.equals("Unknown Artist", ignoreCase = true)
            existing.copy(
                title = if (shouldUpgradeArtist) title else existing.title,
                artist = if (shouldUpgradeArtist) artist else existing.artist,
                album = existing.album.ifBlank { album },
                durationMs = maxOf(existing.durationMs, durationMs),
                primarySourcePackage = sourcePackage,
                sourcePackagesCsv = mergedPackages,
                playCount = existing.playCount + if (incrementPlayCount) 1 else 0,
                totalListenedMs = existing.totalListenedMs + if (incrementPlayCount) listenedMs else 0L,
                firstPlayedAtMs = minOf(existing.firstPlayedAtMs.takeIf { it > 0L } ?: playedAtMs, playedAtMs),
                lastPlayedAtMs = maxOf(existing.lastPlayedAtMs, playedAtMs),
                updatedAtMs = nowMs
            )
        }
        dao.upsertLibraryTrack(updated)
    }

    companion object {
        private const val TAG = "CrossAppPlayRecorder"
        private const val MERGE_WINDOW_MS = 210_000L // 3.5 minutes same-track session collapse
        private const val CROSS_PKG_MIRROR_WINDOW_MS = 90_000L // Prevent simultaneous cross-controller mirror duplicates
        private const val MIN_VERIFIED_PLAY_MS = 18_000L
        private const val MAX_DELTA_MS = 300_000L

        @Volatile
        private var INSTANCE: CrossAppPlayRecorder? = null

        fun getInstance(context: Context): CrossAppPlayRecorder {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CrossAppPlayRecorder(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun resolveFriendlyPlayerName(pkg: String): String {
            return ListeningPlayDeduplicator.resolveAppDisplayName(pkg)
        }
    }
}
