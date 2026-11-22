package com.tunemind.app.domain.memory

import com.tunemind.app.data.local.CrossAppPlayLogEntity
import com.tunemind.app.data.local.CrossAppTrackEntity
import com.tunemind.app.domain.model.PlaybackObservation
import com.tunemind.app.domain.model.TrackIdentityResolver
import com.tunemind.app.domain.vault.RestingVaultEngine
import java.util.Calendar
import java.util.Locale

data class UnifiedPlayEvent(
    val playId: String,
    val trackKey: String,
    val title: String,
    val artist: String,
    val album: String?,
    val albumArtUrl: String?,
    val spotifyUri: String?,
    val sourcePackage: String,
    val appDisplayName: String,
    val playedAtMs: Long,
    val durationMs: Long,
    val listenedMs: Long,
    val isNowPlaying: Boolean = false,
    val trackId: String = ""
)

data class UnifiedTrackStats(
    val trackKey: String,
    val entryId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val albumArtUrl: String?,
    val spotifyUri: String?,
    val primarySourcePackage: String,
    val primaryAppName: String,
    val verifiedPlayCount: Int,
    val totalListeningMs: Long,
    val durationMs: Long,
    val firstPlayedAtMs: Long,
    val lastPlayedAtMs: Long,
    val freshnessPercent: Int,
    val isCoolingDown: Boolean,
    val sourcesUsed: Set<String>,
    val trackId: String = ""
)

data class AppListeningStats(
    val packageName: String,
    val displayName: String,
    val listeningMs: Long,
    val playCount: Int,
    val uniqueTrackCount: Int,
    val shareFraction: Float,
    val lastActiveAtMs: Long
)

data class ArtistListeningStats(
    val artistName: String,
    val playCount: Int,
    val listeningMs: Long,
    val uniqueTrackCount: Int
)

enum class StatsTimeWindow(val label: String) {
    TODAY("Today"),
    SEVEN_DAYS("7 Days"),
    THIRTY_DAYS("30 Days"),
    ALL_TIME("All Time")
}

data class DailyListeningBucket(
    val label: String,
    val listeningMs: Long,
    val playCount: Int
)

data class ActivityChartBucket(
    val label: String,
    val listeningMs: Long,
    val playCount: Int,
    val isHighlighted: Boolean = false
)

data class WindowedListeningStats(
    val window: StatsTimeWindow,
    val chartTitle: String,
    val chartBuckets: List<ActivityChartBucket>,
    val totalListeningMs: Long,
    val totalVerifiedPlays: Int,
    val uniqueTrackCount: Int,
    val uniqueArtistCount: Int,
    val skippedCount: Int,
    val completionRatePercent: Int,
    val discoveryRatePercent: Int,
    val peakHourOfDay: Int,
    val peakWindowLabel: String,
    val hourlyPlayCounts: List<Int>,
    val appBreakdown: List<AppListeningStats>,
    val topTracks: List<UnifiedTrackStats>,
    val topArtists: List<ArtistListeningStats>,
    val earliestEventMs: Long? = null,
    val latestEventMs: Long? = null,
    val trackedSpanDays: Int = 0,
    val windowRangeLabel: String = "",
    val allTimeMatchesThirtyDays: Boolean = false
)

data class OverallListeningStats(
    val todayListeningMs: Long = 0L,
    val sevenDayListeningMs: Long = 0L,
    val thirtyDayListeningMs: Long = 0L,
    val allTimeListeningMs: Long = 0L,
    val totalVerifiedPlays: Int = 0,
    val uniqueTrackCount: Int = 0,
    val uniqueArtistCount: Int = 0,
    val skippedCount: Int = 0,
    val skippedTimestampsMs: List<Long> = emptyList(),
    val completionRatePercent: Int = 0,
    val discoveryRatePercent: Int = 0,
    val peakHourOfDay: Int = 20,
    val peakWindowLabel: String = "No plays yet",
    val hourlyPlayCounts: List<Int> = List(24) { 0 },
    val dailyBuckets: List<DailyListeningBucket> = emptyList(),
    val appBreakdown: List<AppListeningStats> = emptyList(),
    val topTracks: List<UnifiedTrackStats> = emptyList(),
    val topArtists: List<ArtistListeningStats> = emptyList()
)

data class DeduplicatedListeningSnapshot(
    val unifiedTracks: List<UnifiedTrackStats> = emptyList(),
    val cleanPlayHistory: List<UnifiedPlayEvent> = emptyList(),
    val playCountByTrackKey: Map<String, Int> = emptyMap(),
    val playCountByUri: Map<String, Int> = emptyMap(),
    val playCountByTrackId: Map<String, Int> = emptyMap(),
    val stats: OverallListeningStats = OverallListeningStats()
)

object ListeningPlayDeduplicator {

    private const val MERGE_WINDOW_MS = 240_000L // 4 minutes collapse window
    private const val MIN_REAL_PLAY_DWELL_MS = 20_000L
    private const val QUICK_SKIP_THRESHOLD_MS = 14_000L
    private const val MAX_SESSION_ESTIMATE_MS = 600_000L

    private const val CROSS_APP_MIRROR_WINDOW_MS = 90_000L

    private val RE_WHITESPACE = Regex("\\s+")

    fun normalizeText(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return value.trim()
            .lowercase(Locale.ROOT)
            .replace(RE_WHITESPACE, " ")
    }

    fun canonicalTrackKey(title: String?, artist: String?): String =
        TrackIdentityResolver.buildNormalizedMatchKey(title, artist)

    private fun isUnknownOrBlankArtist(artist: String?): Boolean {
        val clean = normalizeText(artist)
        return clean.isBlank() ||
            clean == "unknown artist" ||
            clean == "unknown" ||
            clean == "your library" ||
            clean == "music app" ||
            clean == "music"
    }

    fun isSameTrack(
        titleA: String?,
        artistA: String?,
        uriA: String?,
        titleB: String?,
        artistB: String?,
        uriB: String?
    ): Boolean {
        val cleanUriA = uriA?.trim()?.takeIf { it.isNotBlank() }
        val cleanUriB = uriB?.trim()?.takeIf { it.isNotBlank() }
        if (cleanUriA != null && cleanUriB != null && cleanUriA.equals(cleanUriB, ignoreCase = true)) {
            return true
        }
        val tA = TrackIdentityResolver.normalizedTitleOnly(titleA)
        val tB = TrackIdentityResolver.normalizedTitleOnly(titleB)
        if (tA.isBlank() || tB.isBlank() || tA != tB) return false

        val aA = canonicalTrackKey(titleA, artistA).substringAfter('|', "")
        val aB = canonicalTrackKey(titleB, artistB).substringAfter('|', "")
        return aA.isBlank() || aB.isBlank() || aA == aB
    }

    private fun groupEventsByCanonicalTrack(events: List<UnifiedPlayEvent>): Map<String, List<UnifiedPlayEvent>> {
        if (events.isEmpty()) return emptyMap()
        val knownKeysByTitle = mutableMapOf<String, String>()
        // First register strongest known-artist canonical key per normalized title
        val byNormKey = events.groupBy { canonicalTrackKey(it.title, it.artist) }
        for ((normKey, group) in byNormKey) {
            val normTitle = normKey.substringBefore('|', "")
            val normArtist = normKey.substringAfter('|', "")
            if (normTitle.isNotBlank() && normArtist.isNotBlank()) {
                val existingBest = knownKeysByTitle[normTitle]
                if (existingBest == null) {
                    knownKeysByTitle[normTitle] = normKey
                } else {
                    val existingCount = byNormKey[existingBest]?.size ?: 0
                    if (group.size > existingCount) {
                        knownKeysByTitle[normTitle] = normKey
                    }
                }
            }
        }
        return events.groupBy { ev ->
            val normKey = canonicalTrackKey(ev.title, ev.artist)
            val normTitle = normKey.substringBefore('|', "")
            val normArtist = normKey.substringAfter('|', "")
            if (normArtist.isBlank() && normTitle.isNotBlank()) {
                knownKeysByTitle[normTitle] ?: normKey
            } else {
                normKey
            }
        }
    }

    fun resolveAppDisplayName(packageName: String?): String {
        val pkg = packageName?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when {
            pkg.contains("spotify") -> "Spotify"
            pkg.contains("youtube.music") -> "YouTube Music"
            pkg.contains("boomplay") -> "Boomplay"
            pkg.contains("audiomack") -> "Audiomack"
            pkg.contains("soundcloud") -> "SoundCloud"
            pkg.contains("deezer") -> "Deezer"
            pkg.contains("tidal") -> "Tidal"
            pkg.contains("amazon.mp3") -> "Amazon Music"
            pkg.contains("sec.android.app.music") -> "Samsung Music"
            pkg.contains("miui.player") -> "Xiaomi Music"
            pkg.contains("huawei.music") -> "Huawei Music"
            pkg.contains("larkplayer") -> "Lark Player"
            pkg.contains("anghami") -> "Anghami"
            pkg.contains("jio.media") -> "JioSaavn"
            pkg.contains("gaana") -> "Gaana"
            pkg.contains("tunemind") -> "TuneMind"
            pkg.contains("phonograph") || pkg.contains("retro") || pkg.contains("vlc") ||
                pkg.contains("musicolet") || pkg.contains("maxmpz") || pkg.contains("piyush") -> "Phone Audio"
            pkg.isBlank() || pkg == "unknown_player" -> "Music App"
            else -> packageName!!.substringAfterLast(".").replaceFirstChar { it.uppercase() }
        }
    }

    fun isExcludedBrowserOrWeb(pkg: String?, title: String?, artist: String?): Boolean {
        val p = pkg?.lowercase(Locale.ROOT).orEmpty()
        val t = title?.lowercase(Locale.ROOT).orEmpty()
        val a = artist?.lowercase(Locale.ROOT).orEmpty()
        return p.contains("chrome") || p.contains("browser") || p.contains("chromium") ||
            p.contains("webview") || p.contains("firefox") || p.contains("opera") ||
            p.contains("edge") || p.contains("brave") || p.contains("searchbox") ||
            t.startsWith("http") || t.contains(".com/") || t == "new tab" ||
            a.startsWith("http") || a.contains(".com/")
    }

    private data class CandidateSegment(
        var key: String,
        var trackId: String?,
        var title: String,
        var artist: String,
        var album: String?,
        var spotifyUri: String?,
        val sourcePackage: String,
        val startMs: Long,
        var endMs: Long,
        var durationMs: Long,
        var maxObservedPositionMs: Long,
        var activeDwellMs: Long,
        var lastPlaybackStateCode: Int? = null,
        var hadPauseGap: Boolean = false,
        var hadTransitionOut: Boolean = false
    )

    fun buildSnapshot(
        rawObservations: List<PlaybackObservation>,
        crossAppTracks: List<CrossAppTrackEntity> = emptyList(),
        crossAppLogs: List<CrossAppPlayLogEntity> = emptyList(),
        nowPlayingTitle: String?,
        nowPlayingArtist: String?,
        nowPlayingSourcePkg: String?,
        isMusicPlayingNow: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        mergeDuplicatePlays: Boolean = true,
        repetitionSensitivity: Float = 0.75f,
        nowPlayingPositionMs: Long = 0L,
        nowPlayingDurationMs: Long = 0L,
        nowPlayingUpdatedAtMs: Long = 0L,
        restoredAtByKey: Map<String, Long> = emptyMap(),
        activeVaultFreshnessByKey: Map<String, Int> = emptyMap()
    ): DeduplicatedListeningSnapshot {
        // 1. Reconstruct genuine plays from raw MediaSession observations (ignoring legacy spotify_web_api_ingestion rows & browser clutter)
        val filteredObs = rawObservations
            .filter {
                it.observerSessionId != "spotify_web_api_ingestion" &&
                    !it.trackTitle.isNullOrBlank() &&
                    !isExcludedBrowserOrWeb(it.sourcePackage, it.trackTitle, it.trackArtist)
            }
            .sortedBy { it.eventTimeMs }

        val segments = mutableListOf<CandidateSegment>()
        val currentBySource = mutableMapOf<String, CandidateSegment>()
        var skippedCount = 0
        val skippedTimestampsMs = mutableListOf<Long>()

        for (obs in filteredObs) {
            val title = obs.trackTitle?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val artist = obs.trackArtist?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown Artist"
            val pkg = obs.sourcePackage?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown_player"
            val key = canonicalTrackKey(title, artist)
            val prev = currentBySource[pkg]

            val obsUri = TrackIdentityResolver.normalizeSpotifyUri(obs.trackId)
            val obsCanonicalId = TrackIdentityResolver.resolveCanonicalTrackId(
                trackId = obs.trackId,
                spotifyUri = obsUri,
                title = title,
                artist = artist
            )
            val obsDur = (obs.durationMs ?: 0L).coerceAtLeast(0L)
            val obsPos = (obs.positionMs ?: 0L).coerceAtLeast(0L)
            val obsState = obs.playbackStateCode

            if (prev != null &&
                isSameTrack(prev.title, prev.artist, prev.spotifyUri, title, artist, obsUri) &&
                (obs.eventTimeMs - prev.endMs) <= MERGE_WINDOW_MS
            ) {
                val delta = (obs.eventTimeMs - prev.endMs).coerceIn(0L, MAX_SESSION_ESTIMATE_MS)
                val wasActivelyPlaying = prev.lastPlaybackStateCode == 3 ||
                    (prev.lastPlaybackStateCode == null && (obsState == 3 || obsPos > prev.maxObservedPositionMs))
                if (wasActivelyPlaying && delta > 0L) {
                    prev.activeDwellMs += delta
                }
                if (obsState == 2 || obsState == 1 || obsState == 0) {
                    prev.hadPauseGap = true
                }
                // Upgrade metadata in-place if initial OS frame arrived without full artist/album/URI
                if (isUnknownOrBlankArtist(prev.artist) && !isUnknownOrBlankArtist(artist)) {
                    prev.artist = artist
                    prev.title = title
                    prev.key = key
                    prev.trackId = obsCanonicalId
                }
                if (prev.album.isNullOrBlank() && !obs.trackAlbum.isNullOrBlank()) {
                    prev.album = obs.trackAlbum.trim()
                }
                if (prev.spotifyUri.isNullOrBlank() && obsUri != null) {
                    prev.spotifyUri = obsUri
                }
                prev.endMs = maxOf(prev.endMs, obs.eventTimeMs)
                if (obsDur > 0L) prev.durationMs = maxOf(prev.durationMs, obsDur)
                prev.maxObservedPositionMs = maxOf(prev.maxObservedPositionMs, obsPos)
                if (obsState != null) {
                    prev.lastPlaybackStateCode = obsState
                }
            } else {
                if (prev != null) {
                    // Close previous track segment cleanly without adding paused idle time
                    val gapSincePrevEnd = (obs.eventTimeMs - prev.endMs).coerceAtLeast(0L)
                    val prevWasPlaying = prev.lastPlaybackStateCode == 3 || prev.lastPlaybackStateCode == null
                    if (prevWasPlaying && gapSincePrevEnd in 1..MAX_SESSION_ESTIMATE_MS && obs.sourcePackage == prev.sourcePackage) {
                        val capDuration = prev.durationMs.takeIf { it > 0L } ?: 210_000L
                        val remainingAllowance = (capDuration - prev.activeDwellMs).coerceAtLeast(0L)
                        prev.activeDwellMs += minOf(gapSincePrevEnd, remainingAllowance)
                    }
                    prev.hadTransitionOut = true
                }
                val newSeg = CandidateSegment(
                    key = key,
                    trackId = obsCanonicalId,
                    title = title,
                    artist = artist,
                    album = obs.trackAlbum?.trim()?.takeIf { it.isNotEmpty() },
                    spotifyUri = obsUri,
                    sourcePackage = pkg,
                    startMs = obs.eventTimeMs,
                    endMs = obs.eventTimeMs,
                    durationMs = obsDur,
                    maxObservedPositionMs = obsPos,
                    activeDwellMs = 0L,
                    lastPlaybackStateCode = obsState,
                    hadPauseGap = (obsState == 2 || obsState == 1 || obsState == 0)
                )
                segments += newSeg
                currentBySource[pkg] = newSeg
            }
        }

        val liveElapsedSinceUpdateMs = if (isMusicPlayingNow && nowPlayingUpdatedAtMs > 0L) {
            (nowMs - nowPlayingUpdatedAtMs).coerceIn(0L, MAX_SESSION_ESTIMATE_MS)
        } else {
            0L
        }
        val liveEffectivePosMs = if (nowPlayingPositionMs > 0L || liveElapsedSinceUpdateMs > 0L) {
            nowPlayingPositionMs.coerceAtLeast(0L) + liveElapsedSinceUpdateMs
        } else {
            0L
        }

        // Convert valid segments into PlayEvents (filtering quick accidental skips)
        val localPlayEvents = mutableListOf<UnifiedPlayEvent>()
        for (seg in segments) {
            val isCurrentlyActiveSeg = !nowPlayingTitle.isNullOrBlank() &&
                isSameTrack(seg.title, seg.artist, seg.spotifyUri, nowPlayingTitle, nowPlayingArtist, null) &&
                (nowMs - seg.endMs) <= 15 * 60_000L &&
                !seg.hadTransitionOut

            val segDuration = maxOf(
                seg.durationMs,
                if (isCurrentlyActiveSeg) nowPlayingDurationMs else 0L
            )
            val finalDuration = segDuration.takeIf { it > 0L } ?: 195_000L

            val liveWallDwellMs = if (isCurrentlyActiveSeg && isMusicPlayingNow && seg.lastPlaybackStateCode != 2 && seg.lastPlaybackStateCode != 1) {
                seg.activeDwellMs + (nowMs - seg.endMs).coerceIn(0L, MAX_SESSION_ESTIMATE_MS)
            } else {
                seg.activeDwellMs
            }

            val estimatedListenedMs = maxOf(
                seg.activeDwellMs,
                seg.maxObservedPositionMs,
                liveWallDwellMs,
                if (isCurrentlyActiveSeg) liveEffectivePosMs else 0L
            )

            val isQuickSkip = seg.hadTransitionOut &&
                estimatedListenedMs in 1 until QUICK_SKIP_THRESHOLD_MS &&
                (seg.durationMs == 0L || estimatedListenedMs < seg.durationMs / 2)

            if (isQuickSkip) {
                skippedCount++
                skippedTimestampsMs.add(seg.startMs)
                continue
            }

            // Require verified playback: never turn a 0ms paused/idle metadata notification into a play!
            val qualifiesAsPlay = if (seg.hadTransitionOut) {
                estimatedListenedMs >= MIN_REAL_PLAY_DWELL_MS ||
                    (seg.durationMs in 1L..45_000L && estimatedListenedMs >= seg.durationMs / 2)
            } else {
                (isCurrentlyActiveSeg && isMusicPlayingNow) ||
                    estimatedListenedMs >= MIN_REAL_PLAY_DWELL_MS ||
                    (seg.durationMs in 1L..45_000L && estimatedListenedMs >= seg.durationMs / 2)
            }

            if (!qualifiesAsPlay) {
                if (isQuickSkip || estimatedListenedMs > 0L) {
                    skippedCount++
                    skippedTimestampsMs.add(seg.startMs)
                }
                continue
            }

            val finalListened = when {
                isCurrentlyActiveSeg -> {
                    maxOf(estimatedListenedMs, if (isMusicPlayingNow) 1_000L else 0L)
                        .coerceAtMost(finalDuration)
                }
                estimatedListenedMs > 0L -> minOf(estimatedListenedMs, finalDuration)
                else -> 0L
            }

            val resolvedTrackId = TrackIdentityResolver.resolveCanonicalTrackId(
                trackId = seg.trackId,
                spotifyUri = seg.spotifyUri,
                title = seg.title,
                artist = seg.artist
            )

            localPlayEvents += UnifiedPlayEvent(
                playId = "local_${seg.sourcePackage}_${seg.key}_${seg.startMs}",
                trackKey = seg.key,
                title = seg.title,
                artist = seg.artist,
                album = seg.album,
                albumArtUrl = null,
                spotifyUri = seg.spotifyUri,
                sourcePackage = seg.sourcePackage,
                appDisplayName = resolveAppDisplayName(seg.sourcePackage),
                playedAtMs = seg.startMs,
                durationMs = finalDuration,
                listenedMs = finalListened,
                isNowPlaying = isCurrentlyActiveSeg && isMusicPlayingNow,
                trackId = resolvedTrackId
            )
        }

        val libraryByKey = crossAppTracks.associateBy { canonicalTrackKey(it.title, it.artist) }
        val libraryByTitleOnly = crossAppTracks
            .filter { !it.albumArtUrl.isNullOrBlank() || it.album.isNotBlank() }
            .associateBy { TrackIdentityResolver.normalizedTitleOnly(it.title) }

        // 2. Seed permanent cross-app play logs across ALL music apps (never pruned!)
        // Use normalized-title bucket index so duplicate merging is O(N) instead of O(N^2) regex scans
        val mergedEvents = mutableListOf<UnifiedPlayEvent>()
        val mergedNormMeta = mutableListOf<Pair<String, String>>() // normTitle to normArtist
        val mergedIndicesByTitle = HashMap<String, MutableList<Int>>(256)
        val mergedIndexByUri = HashMap<String, MutableList<Int>>(64)

        fun findMatchingMergedIndex(
            normTitle: String,
            normArtist: String,
            cleanUri: String?,
            playedAtMs: Long,
            sourcePkg: String
        ): Int {
            if (!mergeDuplicatePlays) return -1
            if (cleanUri != null) {
                val uriIndices = mergedIndexByUri[cleanUri.lowercase(Locale.ROOT)]
                if (uriIndices != null) {
                    for (idx in uriIndices) {
                        val existing = mergedEvents[idx]
                        val diffMs = kotlin.math.abs(existing.playedAtMs - playedAtMs)
                        val sameWindow = if (existing.sourcePackage == sourcePkg) diffMs <= MERGE_WINDOW_MS else diffMs <= CROSS_APP_MIRROR_WINDOW_MS
                        if (sameWindow) return idx
                    }
                }
            }
            if (normTitle.isBlank()) return -1
            val bucket = mergedIndicesByTitle[normTitle] ?: return -1
            for (idx in bucket) {
                val existing = mergedEvents[idx]
                val diffMs = kotlin.math.abs(existing.playedAtMs - playedAtMs)
                val sameWindow = if (existing.sourcePackage == sourcePkg) diffMs <= MERGE_WINDOW_MS else diffMs <= CROSS_APP_MIRROR_WINDOW_MS
                if (!sameWindow) continue
                val existingArtist = mergedNormMeta[idx].second
                if (existingArtist.isBlank() || normArtist.isBlank() || existingArtist == normArtist) {
                    return idx
                }
            }
            return -1
        }

        fun appendMergedEvent(event: UnifiedPlayEvent, normTitle: String, normArtist: String) {
            val idx = mergedEvents.size
            mergedEvents.add(event)
            mergedNormMeta.add(normTitle to normArtist)
            if (normTitle.isNotBlank()) {
                mergedIndicesByTitle.getOrPut(normTitle) { mutableListOf() }.add(idx)
            }
            val cleanUri = event.spotifyUri?.trim()?.takeIf { it.isNotBlank() }?.lowercase(Locale.ROOT)
            if (cleanUri != null) {
                mergedIndexByUri.getOrPut(cleanUri) { mutableListOf() }.add(idx)
            }
        }

        for (row in crossAppLogs.filter { it.title.isNotBlank() }.sortedByDescending { it.playedAtMs }) {
            val key = canonicalTrackKey(row.title, row.artist)
            val normTitle = key.substringBefore('|', "")
            val normArtist = key.substringAfter('|', "")
            val rowPkg = row.sourcePackage.ifBlank { "unknown_player" }
            val existingIdx = findMatchingMergedIndex(
                normTitle = normTitle,
                normArtist = normArtist,
                cleanUri = null,
                playedAtMs = row.playedAtMs,
                sourcePkg = rowPkg
            )

            val durMs = row.trackDurationMs.takeIf { it > 0L } ?: 195_000L
            val libRow = libraryByKey[key] ?: libraryByTitleOnly[normTitle]
            val cleanRowArtist = row.artist.trim().ifBlank { "Unknown Artist" }
            val resolvedId = TrackIdentityResolver.resolveCanonicalTrackId(
                trackId = row.trackId,
                spotifyUri = null,
                title = row.title,
                artist = cleanRowArtist
            )
            if (existingIdx >= 0) {
                val existing = mergedEvents[existingIdx]
                val betterArtist = if (isUnknownOrBlankArtist(existing.artist) && !isUnknownOrBlankArtist(cleanRowArtist)) cleanRowArtist else existing.artist
                val betterTitle = if (isUnknownOrBlankArtist(existing.artist) && !isUnknownOrBlankArtist(cleanRowArtist)) row.title.trim() else existing.title
                val updatedKey = if (betterArtist !== existing.artist || betterTitle !== existing.title) {
                    canonicalTrackKey(betterTitle, betterArtist)
                } else existing.trackKey
                mergedNormMeta[existingIdx] = updatedKey.substringBefore('|', "") to updatedKey.substringAfter('|', "")
                mergedEvents[existingIdx] = existing.copy(
                    trackKey = updatedKey,
                    title = betterTitle,
                    artist = betterArtist,
                    album = existing.album ?: row.album.trim().takeIf { it.isNotEmpty() } ?: libRow?.album?.takeIf { it.isNotBlank() },
                    albumArtUrl = existing.albumArtUrl ?: libRow?.albumArtUrl,
                    listenedMs = maxOf(existing.listenedMs, row.listenedMs.coerceAtLeast(1_000L).coerceAtMost(durMs))
                )
                continue
            }

            appendMergedEvent(
                UnifiedPlayEvent(
                    playId = "calog_${row.playLogId}",
                    trackKey = key,
                    title = row.title.trim(),
                    artist = cleanRowArtist,
                    album = row.album.trim().takeIf { it.isNotEmpty() } ?: libRow?.album?.takeIf { it.isNotBlank() },
                    albumArtUrl = libRow?.albumArtUrl,
                    spotifyUri = null,
                    sourcePackage = rowPkg,
                    appDisplayName = resolveAppDisplayName(rowPkg),
                    playedAtMs = row.playedAtMs,
                    durationMs = durMs,
                    listenedMs = row.listenedMs.coerceAtLeast(1_000L).coerceAtMost(durMs),
                    isNowPlaying = false,
                    trackId = resolvedId
                ),
                normTitle = normTitle,
                normArtist = normArtist
            )
        }

        // Merge live OS event_queue segments without double counting permanent log entries or multi-controller mirrors
        for (localEvent in localPlayEvents) {
            val normTitle = localEvent.trackKey.substringBefore('|', "")
            val normArtist = localEvent.trackKey.substringAfter('|', "")
            val matchingIdx = findMatchingMergedIndex(
                normTitle = normTitle,
                normArtist = normArtist,
                cleanUri = localEvent.spotifyUri,
                playedAtMs = localEvent.playedAtMs,
                sourcePkg = localEvent.sourcePackage
            )
            if (matchingIdx >= 0) {
                val existing = mergedEvents[matchingIdx]
                val betterArtist = if (isUnknownOrBlankArtist(existing.artist) && !isUnknownOrBlankArtist(localEvent.artist)) localEvent.artist else existing.artist
                val betterTitle = if (isUnknownOrBlankArtist(existing.artist) && !isUnknownOrBlankArtist(localEvent.artist)) localEvent.title else existing.title
                val updatedKey = if (betterArtist !== existing.artist || betterTitle !== existing.title) {
                    canonicalTrackKey(betterTitle, betterArtist)
                } else existing.trackKey
                mergedNormMeta[matchingIdx] = updatedKey.substringBefore('|', "") to updatedKey.substringAfter('|', "")
                mergedEvents[matchingIdx] = existing.copy(
                    trackKey = updatedKey,
                    title = betterTitle,
                    artist = betterArtist,
                    album = existing.album ?: localEvent.album,
                    spotifyUri = existing.spotifyUri ?: localEvent.spotifyUri,
                    listenedMs = maxOf(existing.listenedMs, localEvent.listenedMs),
                    durationMs = maxOf(existing.durationMs, localEvent.durationMs),
                    isNowPlaying = existing.isNowPlaying || localEvent.isNowPlaying
                )
            } else {
                appendMergedEvent(localEvent, normTitle = normTitle, normArtist = normArtist)
            }
        }

        // Mark currently playing track cleanly (only ONE live item at top; never duplicate!)
        val finalTimeline = mergedEvents
            .sortedByDescending { it.playedAtMs }
            .toMutableList()

        if (!nowPlayingTitle.isNullOrBlank()) {
            val activeKey = canonicalTrackKey(nowPlayingTitle, nowPlayingArtist)
            val existingRecentIdx = finalTimeline.indexOfFirst { ev ->
                isSameTrack(ev.title, ev.artist, ev.spotifyUri, nowPlayingTitle, nowPlayingArtist, null) &&
                    (nowMs - ev.playedAtMs) <= 15 * 60_000L
            }
            if (existingRecentIdx >= 0) {
                val matched = finalTimeline.removeAt(existingRecentIdx)
                val updatedDur = maxOf(matched.durationMs, nowPlayingDurationMs).coerceAtLeast(1L)
                val updatedListened = maxOf(
                    matched.listenedMs,
                    liveEffectivePosMs.coerceAtMost(updatedDur)
                ).coerceAtMost(updatedDur)
                finalTimeline.add(
                    0,
                    matched.copy(
                        durationMs = updatedDur,
                        listenedMs = updatedListened,
                        isNowPlaying = isMusicPlayingNow
                    )
                )
            } else if (isMusicPlayingNow) {
                val knownMatch = finalTimeline.firstOrNull {
                    isSameTrack(it.title, it.artist, it.spotifyUri, nowPlayingTitle, nowPlayingArtist, null)
                }
                val resolvedDur = maxOf(knownMatch?.durationMs ?: 0L, nowPlayingDurationMs)
                    .takeIf { it > 0L } ?: 195_000L
                val resolvedListened = maxOf(1_000L, liveEffectivePosMs).coerceAtMost(resolvedDur)
                val resolvedLiveIdentity = TrackIdentityResolver.resolveCanonicalIdentity(
                    trackId = knownMatch?.trackId,
                    spotifyUri = knownMatch?.spotifyUri,
                    title = nowPlayingTitle,
                    artist = nowPlayingArtist
                )
                val effPkg = nowPlayingSourcePkg?.takeIf { it.isNotBlank() } ?: "unknown_player"
                finalTimeline.add(
                    0,
                    UnifiedPlayEvent(
                        playId = "live_${activeKey}",
                        trackKey = activeKey,
                        title = nowPlayingTitle.trim(),
                        artist = nowPlayingArtist?.trim()?.ifBlank { "Unknown Artist" } ?: "Unknown Artist",
                        album = knownMatch?.album,
                        albumArtUrl = knownMatch?.albumArtUrl ?: libraryByKey[activeKey]?.albumArtUrl,
                        spotifyUri = resolvedLiveIdentity.spotifyUri,
                        sourcePackage = effPkg,
                        appDisplayName = resolveAppDisplayName(effPkg),
                        playedAtMs = nowMs,
                        durationMs = resolvedDur,
                        listenedMs = resolvedListened,
                        isNowPlaying = true,
                        trackId = resolvedLiveIdentity.trackId
                    )
                )
            }
        }

        // 3. Aggregate per-track strict statistics (SINGLE SOURCE OF TRUTH for Play Counts everywhere)
        val artMap = crossAppTracks
            .filter { !it.albumArtUrl.isNullOrBlank() }
            .associate { canonicalTrackKey(it.title, it.artist) to it.albumArtUrl }
        val artByTitleMap = crossAppTracks
            .filter { !it.albumArtUrl.isNullOrBlank() }
            .associate { TrackIdentityResolver.normalizedTitleOnly(it.title) to it.albumArtUrl }

        val sensitivity = repetitionSensitivity.coerceIn(0.2f, 1.0f)

        val groupedByTrack = groupEventsByCanonicalTrack(finalTimeline)
        val unifiedTracks = groupedByTrack.map { (key, events) ->
            val latest = events.maxByOrNull { it.playedAtMs } ?: events.first()
            val bestNamedEvent = events
                .sortedByDescending { it.playedAtMs }
                .firstOrNull { !isUnknownOrBlankArtist(it.artist) } ?: latest
            val displayTitle = bestNamedEvent.title
            val displayArtist = bestNamedEvent.artist
            val plays = events.size
            val bestUri = events.firstNotNullOfOrNull { it.spotifyUri?.let { uri -> TrackIdentityResolver.normalizeSpotifyUri(uri) } }
            val bestTrackId = TrackIdentityResolver.resolveCanonicalTrackId(
                trackId = events.firstOrNull { it.trackId.isNotBlank() }?.trackId,
                spotifyUri = bestUri,
                title = displayTitle,
                artist = displayArtist
            )
            val normKey = RestingVaultEngine.normalizeTrackKey(displayTitle, displayArtist)
            val restoredAtMs = maxOf(
                restoredAtByKey[bestTrackId] ?: 0L,
                restoredAtByKey[normKey] ?: 0L
            )
            val vaultFreshness = activeVaultFreshnessByKey[bestTrackId]
                ?: activeVaultFreshnessByKey[normKey]
            val ratios = events.map { ev ->
                if (ev.durationMs > 30_000L && ev.listenedMs > 0L) {
                    (ev.listenedMs.toFloat() / ev.durationMs.toFloat()).coerceIn(0.01f, 1.0f)
                } else {
                    1.0f
                }
            }
            val fatigue = RestingVaultEngine.evaluateTrackFatigue(
                playTimestampsMs = events.map { it.playedAtMs },
                listenedRatios = ratios,
                sensitivity = sensitivity,
                lastRestoredAtMs = restoredAtMs,
                nowMs = nowMs
            )
            val isRestingInVault = vaultFreshness != null
            val freshness = vaultFreshness ?: fatigue.freshnessPercent
            val cooling = isRestingInVault || fatigue.shouldAutoQuarantine
            val bestAlbum = events.firstNotNullOfOrNull { it.album }
            val bestArt = events.firstNotNullOfOrNull { it.albumArtUrl }
                ?: artMap[key]
                ?: artByTitleMap[TrackIdentityResolver.normalizedTitleOnly(displayTitle)]
            val bestDur = events.maxOfOrNull { it.durationMs } ?: 195_000L
            val totalListen = events.sumOf { it.listenedMs }

            UnifiedTrackStats(
                trackKey = key,
                entryId = bestUri?.let { "spotify:${it.substringAfterLast(':')}" } ?: "track:$bestTrackId",
                title = displayTitle,
                artist = displayArtist,
                album = bestAlbum,
                albumArtUrl = bestArt,
                spotifyUri = bestUri,
                primarySourcePackage = latest.sourcePackage,
                primaryAppName = latest.appDisplayName,
                verifiedPlayCount = plays,
                totalListeningMs = totalListen,
                durationMs = bestDur,
                firstPlayedAtMs = events.minOf { it.playedAtMs },
                lastPlayedAtMs = latest.playedAtMs,
                freshnessPercent = freshness,
                isCoolingDown = cooling,
                sourcesUsed = events.map { it.sourcePackage }.toSet(),
                trackId = bestTrackId
            )
        }.sortedByDescending { it.lastPlayedAtMs }

        val countByKey = buildMap {
            for (track in unifiedTracks) {
                put(track.trackKey, maxOf(get(track.trackKey) ?: 0, track.verifiedPlayCount))
                val normMatch = TrackIdentityResolver.buildNormalizedMatchKey(track.title, track.artist)
                if (normMatch.isNotBlank() && normMatch != "|") {
                    put(normMatch, maxOf(get(normMatch) ?: 0, track.verifiedPlayCount))
                }
                val rawLower = "${normalizeText(track.title)}|${normalizeText(track.artist)}"
                if (rawLower.isNotBlank() && rawLower != "|") {
                    put(rawLower, maxOf(get(rawLower) ?: 0, track.verifiedPlayCount))
                }
            }
        }
        val countByUri = unifiedTracks.mapNotNull { track ->
            track.spotifyUri?.let { it.lowercase(Locale.ROOT) to track.verifiedPlayCount }
        }.toMap()
        val countByTrackId = buildMap {
            for (track in unifiedTracks) {
                put(track.trackId, maxOf(get(track.trackId) ?: 0, track.verifiedPlayCount))
                val detId = TrackIdentityResolver.buildDeterministicTrackId(track.title, track.artist)
                put(detId, maxOf(get(detId) ?: 0, track.verifiedPlayCount))
            }
        }

        // 4. Compute Cross-App Stats & Habit Analytics
        val calStartOfToday = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val sevenDaysStart = calStartOfToday - 6L * 86_400_000L
        val thirtyDaysStart = calStartOfToday - 29L * 86_400_000L

        val todayMs = finalTimeline.filter { it.playedAtMs >= calStartOfToday }.sumOf { it.listenedMs }
        val sevenDayMs = finalTimeline.filter { it.playedAtMs >= sevenDaysStart }.sumOf { it.listenedMs }
        val thirtyDayMs = finalTimeline.filter { it.playedAtMs >= thirtyDaysStart }.sumOf { it.listenedMs }
        val allTimeMs = finalTimeline.sumOf { it.listenedMs }

        val hourlyCounts = IntArray(24)
        val cal = Calendar.getInstance()
        for (ev in finalTimeline) {
            cal.timeInMillis = ev.playedAtMs
            val hr = cal.get(Calendar.HOUR_OF_DAY).coerceIn(0, 23)
            hourlyCounts[hr]++
        }
        val hasHourlyActivity = hourlyCounts.any { it > 0 }
        val peakHour = if (hasHourlyActivity) {
            hourlyCounts.indices.maxByOrNull { hourlyCounts[it] } ?: 20
        } else {
            Calendar.getInstance().apply { timeInMillis = nowMs }.get(Calendar.HOUR_OF_DAY)
        }
        val peakWindowLabel = if (!hasHourlyActivity) {
            "No plays yet"
        } else when (peakHour) {
            in 5..11 -> "Morning (${formatHour(peakHour)})"
            in 12..16 -> "Afternoon (${formatHour(peakHour)})"
            in 17..21 -> "Evening (${formatHour(peakHour)})"
            else -> "Late Night (${formatHour(peakHour)})"
        }

        // Last 7 days daily bar buckets
        val dailyBuckets = mutableListOf<DailyListeningBucket>()
        val dayCal = Calendar.getInstance()
        val dayFmt = java.text.SimpleDateFormat("EEE", Locale.getDefault())
        for (offset in 6 downTo 0) {
            dayCal.timeInMillis = calStartOfToday - offset * 86_400_000L
            val dStart = dayCal.timeInMillis
            val dEnd = dStart + 86_400_000L
            val dayEvents = finalTimeline.filter { it.playedAtMs in dStart until dEnd }
            dailyBuckets += DailyListeningBucket(
                label = if (offset == 0) "Today" else dayFmt.format(dayCal.time),
                listeningMs = dayEvents.sumOf { it.listenedMs },
                playCount = dayEvents.size
            )
        }

        // Cross-app breakdown across ALL observed apps
        val appGroups = finalTimeline.groupBy { it.sourcePackage }
        val totalTimelineListenMs = allTimeMs.coerceAtLeast(1L)
        val appBreakdown = appGroups.map { (pkg, events) ->
            val appMs = events.sumOf { it.listenedMs }
            AppListeningStats(
                packageName = pkg,
                displayName = resolveAppDisplayName(pkg),
                listeningMs = appMs,
                playCount = events.size,
                uniqueTrackCount = events.map { it.trackKey }.distinct().size,
                shareFraction = (appMs.toFloat() / totalTimelineListenMs.toFloat()).coerceIn(0f, 1f),
                lastActiveAtMs = events.maxOf { it.playedAtMs }
            )
        }.sortedByDescending { it.listeningMs }

        // Top artists
        val artistStats = finalTimeline
            .filter { it.artist.isNotBlank() && !it.artist.equals("Unknown Artist", ignoreCase = true) }
            .groupBy { normalizeText(it.artist) }
            .map { (_, events) ->
                ArtistListeningStats(
                    artistName = events.first().artist,
                    playCount = events.size,
                    listeningMs = events.sumOf { it.listenedMs },
                    uniqueTrackCount = events.map { it.trackKey }.distinct().size
                )
            }
            .sortedWith(compareByDescending<ArtistListeningStats> { it.playCount }.thenByDescending { it.listeningMs })

        val totalPlays = finalTimeline.size
        val totalAttempts = (totalPlays + skippedCount).coerceAtLeast(1)
        val completionPct = if (totalPlays == 0) 0 else ((totalPlays.toFloat() / totalAttempts.toFloat()) * 100f).toInt().coerceIn(40, 100)
        val freshPlays = unifiedTracks.count { it.verifiedPlayCount <= 2 }
        val discoveryPct = if (unifiedTracks.isEmpty()) 0 else ((freshPlays.toFloat() / unifiedTracks.size) * 100f).toInt().coerceIn(10, 100)

        val overallStats = OverallListeningStats(
            todayListeningMs = todayMs,
            sevenDayListeningMs = sevenDayMs,
            thirtyDayListeningMs = thirtyDayMs,
            allTimeListeningMs = allTimeMs,
            totalVerifiedPlays = totalPlays,
            uniqueTrackCount = unifiedTracks.size,
            uniqueArtistCount = artistStats.size,
            skippedCount = skippedCount,
            skippedTimestampsMs = skippedTimestampsMs,
            completionRatePercent = completionPct,
            discoveryRatePercent = discoveryPct,
            peakHourOfDay = peakHour,
            peakWindowLabel = peakWindowLabel,
            hourlyPlayCounts = hourlyCounts.toList(),
            dailyBuckets = dailyBuckets,
            appBreakdown = appBreakdown,
            topTracks = unifiedTracks.sortedWith(
                compareByDescending<UnifiedTrackStats> { it.verifiedPlayCount }
                    .thenByDescending { it.totalListeningMs }
            ),
            topArtists = artistStats
        )

        return DeduplicatedListeningSnapshot(
            unifiedTracks = unifiedTracks,
            cleanPlayHistory = finalTimeline,
            playCountByTrackKey = countByKey,
            playCountByUri = countByUri,
            playCountByTrackId = countByTrackId,
            stats = overallStats
        )
    }

    fun buildWindowedStats(
        cleanPlayHistory: List<UnifiedPlayEvent>,
        overallStats: OverallListeningStats,
        window: StatsTimeWindow,
        nowMs: Long = System.currentTimeMillis(),
        repetitionSensitivity: Float = 0.75f,
        restoredAtByKey: Map<String, Long> = emptyMap(),
        activeVaultFreshnessByKey: Map<String, Int> = emptyMap()
    ): WindowedListeningStats {
        val calStartOfToday = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val cutoffMs = when (window) {
            StatsTimeWindow.TODAY -> calStartOfToday
            StatsTimeWindow.SEVEN_DAYS -> calStartOfToday - 6L * 86_400_000L
            StatsTimeWindow.THIRTY_DAYS -> calStartOfToday - 29L * 86_400_000L
            StatsTimeWindow.ALL_TIME -> 0L
        }

        val filteredEvents = if (window == StatsTimeWindow.ALL_TIME) {
            cleanPlayHistory
        } else {
            cleanPlayHistory.filter { it.playedAtMs >= cutoffMs }
        }

        val totalListenMs = filteredEvents.sumOf { it.listenedMs }
        val totalPlays = filteredEvents.size
        val windowSkips = if (window == StatsTimeWindow.ALL_TIME) {
            overallStats.skippedCount
        } else {
            overallStats.skippedTimestampsMs.count { it >= cutoffMs }
        }

        val hourlyCounts = IntArray(24)
        val hrCal = Calendar.getInstance()
        for (ev in filteredEvents) {
            hrCal.timeInMillis = ev.playedAtMs
            val hr = hrCal.get(Calendar.HOUR_OF_DAY).coerceIn(0, 23)
            hourlyCounts[hr]++
        }
        val hasHourlyActivity = hourlyCounts.any { it > 0 }
        val peakHour = if (hasHourlyActivity) {
            hourlyCounts.indices.maxByOrNull { hourlyCounts[it] } ?: 20
        } else {
            Calendar.getInstance().apply { timeInMillis = nowMs }.get(Calendar.HOUR_OF_DAY)
        }
        val peakWindowLabel = if (!hasHourlyActivity) {
            "No plays yet"
        } else when (peakHour) {
            in 5..11 -> "Morning (${formatHour(peakHour)})"
            in 12..16 -> "Afternoon (${formatHour(peakHour)})"
            in 17..21 -> "Evening (${formatHour(peakHour)})"
            else -> "Late Night (${formatHour(peakHour)})"
        }

        val totalListenSafe = totalListenMs.coerceAtLeast(1L)
        val appBreakdown = filteredEvents
            .groupBy { it.sourcePackage }
            .map { (pkg, events) ->
                val appMs = events.sumOf { it.listenedMs }
                AppListeningStats(
                    packageName = pkg,
                    displayName = resolveAppDisplayName(pkg),
                    listeningMs = appMs,
                    playCount = events.size,
                    uniqueTrackCount = events.map { it.trackKey }.distinct().size,
                    shareFraction = (appMs.toFloat() / totalListenSafe.toFloat()).coerceIn(0f, 1f),
                    lastActiveAtMs = events.maxOf { it.playedAtMs }
                )
            }
            .sortedByDescending { it.listeningMs }

        val topArtists = filteredEvents
            .filter { it.artist.isNotBlank() && !it.artist.equals("Unknown Artist", ignoreCase = true) }
            .groupBy { normalizeText(it.artist) }
            .map { (_, events) ->
                ArtistListeningStats(
                    artistName = events.first().artist,
                    playCount = events.size,
                    listeningMs = events.sumOf { it.listenedMs },
                    uniqueTrackCount = events.map { it.trackKey }.distinct().size
                )
            }
            .sortedWith(compareByDescending<ArtistListeningStats> { it.playCount }.thenByDescending { it.listeningMs })

        val baseTrackByKey = overallStats.topTracks.associateBy { it.trackKey }
        val windowTracks = groupEventsByCanonicalTrack(filteredEvents)
            .map { (key, events) ->
                val latest = events.maxByOrNull { it.playedAtMs } ?: events.first()
                val bestNamedEvent = events
                    .sortedByDescending { it.playedAtMs }
                    .firstOrNull { !isUnknownOrBlankArtist(it.artist) } ?: latest
                val displayTitle = bestNamedEvent.title
                val displayArtist = bestNamedEvent.artist
                val plays = events.size
                val baseTrack = baseTrackByKey[key]
                val bestUri = events.firstNotNullOfOrNull { it.spotifyUri?.let { uri -> TrackIdentityResolver.normalizeSpotifyUri(uri) } } ?: baseTrack?.spotifyUri
                val bestTrackId = TrackIdentityResolver.resolveCanonicalTrackId(
                    trackId = events.firstOrNull { it.trackId.isNotBlank() }?.trackId ?: baseTrack?.trackId,
                    spotifyUri = bestUri,
                    title = displayTitle,
                    artist = displayArtist
                )
                val normKey = RestingVaultEngine.normalizeTrackKey(displayTitle, displayArtist)
                val restoredAtMs = maxOf(
                    restoredAtByKey[bestTrackId] ?: 0L,
                    restoredAtByKey[normKey] ?: 0L
                )
                val vaultFreshness = activeVaultFreshnessByKey[bestTrackId]
                    ?: activeVaultFreshnessByKey[normKey]
                val ratios = events.map { ev ->
                    if (ev.durationMs > 30_000L && ev.listenedMs > 0L) {
                        (ev.listenedMs.toFloat() / ev.durationMs.toFloat()).coerceIn(0.01f, 1.0f)
                    } else {
                        1.0f
                    }
                }
                val fatigue = RestingVaultEngine.evaluateTrackFatigue(
                    playTimestampsMs = events.map { it.playedAtMs },
                    listenedRatios = ratios,
                    sensitivity = repetitionSensitivity,
                    lastRestoredAtMs = restoredAtMs,
                    nowMs = nowMs
                )
                val freshness = vaultFreshness ?: fatigue.freshnessPercent
                val cooling = vaultFreshness != null || fatigue.shouldAutoQuarantine
                val bestAlbum = events.firstNotNullOfOrNull { it.album } ?: baseTrack?.album
                val bestArt = events.firstNotNullOfOrNull { it.albumArtUrl } ?: baseTrack?.albumArtUrl
                UnifiedTrackStats(
                    trackKey = key,
                    entryId = bestUri?.let { "spotify:${it.substringAfterLast(':')}" } ?: "track:$bestTrackId",
                    title = displayTitle,
                    artist = displayArtist,
                    album = bestAlbum,
                    albumArtUrl = bestArt,
                    spotifyUri = bestUri,
                    primarySourcePackage = latest.sourcePackage,
                    primaryAppName = latest.appDisplayName,
                    verifiedPlayCount = plays,
                    totalListeningMs = events.sumOf { it.listenedMs },
                    durationMs = events.maxOfOrNull { it.durationMs } ?: baseTrack?.durationMs ?: 195_000L,
                    firstPlayedAtMs = events.minOf { it.playedAtMs },
                    lastPlayedAtMs = latest.playedAtMs,
                    freshnessPercent = freshness,
                    isCoolingDown = cooling,
                    sourcesUsed = events.map { it.sourcePackage }.toSet(),
                    trackId = bestTrackId
                )
            }
            .sortedWith(
                compareByDescending<UnifiedTrackStats> { it.verifiedPlayCount }
                    .thenByDescending { it.totalListeningMs }
            )

        val completionPct = if (totalPlays == 0) {
            0
        } else {
            ((totalPlays.toFloat() / (totalPlays + windowSkips).coerceAtLeast(1).toFloat()) * 100f)
                .toInt().coerceIn(25, 100)
        }
        val freshTracks = windowTracks.count { it.verifiedPlayCount <= 2 }
        val discoveryPct = if (windowTracks.isEmpty()) {
            0
        } else {
            ((freshTracks.toFloat() / windowTracks.size.toFloat()) * 100f).toInt().coerceIn(0, 100)
        }

        val chartTitle: String
        val chartBuckets: List<ActivityChartBucket> = when (window) {
            StatsTimeWindow.TODAY -> {
                chartTitle = "Today by Time"
                val labels = listOf("12a", "4a", "8a", "12p", "4p", "8p")
                val currentBlock = (Calendar.getInstance().apply { timeInMillis = nowMs }.get(Calendar.HOUR_OF_DAY) / 4).coerceIn(0, 5)
                (0..5).map { idx ->
                    val bStart = calStartOfToday + idx * 4L * 3_600_000L
                    val bEnd = bStart + 4L * 3_600_000L
                    val bEvents = filteredEvents.filter { it.playedAtMs in bStart until bEnd }
                    ActivityChartBucket(
                        label = labels[idx],
                        listeningMs = bEvents.sumOf { it.listenedMs },
                        playCount = bEvents.size,
                        isHighlighted = idx == currentBlock
                    )
                }
            }

            StatsTimeWindow.SEVEN_DAYS -> {
                chartTitle = "Last 7 Days"
                val dayCal = Calendar.getInstance()
                val dayFmt = java.text.SimpleDateFormat("EEE", Locale.getDefault())
                (6 downTo 0).map { offset ->
                    dayCal.timeInMillis = calStartOfToday - offset * 86_400_000L
                    val dStart = dayCal.timeInMillis
                    val dEnd = dStart + 86_400_000L
                    val dayEvents = filteredEvents.filter { it.playedAtMs in dStart until dEnd }
                    ActivityChartBucket(
                        label = if (offset == 0) "Today" else dayFmt.format(dayCal.time),
                        listeningMs = dayEvents.sumOf { it.listenedMs },
                        playCount = dayEvents.size,
                        isHighlighted = offset == 0
                    )
                }
            }

            StatsTimeWindow.THIRTY_DAYS -> {
                chartTitle = "Last 30 Days"
                val dateFmt = java.text.SimpleDateFormat("d MMM", Locale.US)
                val start30 = calStartOfToday - 29L * 86_400_000L
                val bucketCal = Calendar.getInstance()
                (0..5).map { idx ->
                    val bStart = start30 + idx * 5L * 86_400_000L
                    val bEnd = if (idx == 5) {
                        maxOf(calStartOfToday + 86_400_000L, nowMs + 60_000L)
                    } else {
                        start30 + (idx + 1) * 5L * 86_400_000L
                    }
                    bucketCal.timeInMillis = bStart
                    val bEvents = filteredEvents.filter { it.playedAtMs >= bStart && it.playedAtMs < bEnd }
                    ActivityChartBucket(
                        label = if (idx == 5) "Now" else dateFmt.format(bucketCal.time),
                        listeningMs = bEvents.sumOf { it.listenedMs },
                        playCount = bEvents.size,
                        isHighlighted = idx == 5
                    )
                }
            }

            StatsTimeWindow.ALL_TIME -> {
                chartTitle = "All-Time Activity"
                val endRange = maxOf(calStartOfToday + 86_400_000L, nowMs + 60_000L)
                val earliestEventMs = filteredEvents.minOfOrNull { it.playedAtMs } ?: calStartOfToday
                val spanDays = ((endRange - earliestEventMs) / 86_400_000L).coerceAtLeast(1L)
                if (spanDays <= 1L) {
                    val labels = listOf("12a", "4a", "8a", "12p", "4p", "8p")
                    val currentBlock = (Calendar.getInstance().apply { timeInMillis = nowMs }.get(Calendar.HOUR_OF_DAY) / 4).coerceIn(0, 5)
                    (0..5).map { idx ->
                        val bStart = calStartOfToday + idx * 4L * 3_600_000L
                        val bEnd = bStart + 4L * 3_600_000L
                        val bEvents = filteredEvents.filter { it.playedAtMs in bStart until bEnd }
                        ActivityChartBucket(
                            label = labels[idx],
                            listeningMs = bEvents.sumOf { it.listenedMs },
                            playCount = bEvents.size,
                            isHighlighted = idx == currentBlock
                        )
                    }
                } else {
                    val earliestDayStart = Calendar.getInstance().apply {
                        timeInMillis = earliestEventMs
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    val totalSpanMs = (endRange - earliestDayStart).coerceAtLeast(6L * 3_600_000L)
                    val stepMs = totalSpanMs / 6L
                    val fmt = java.text.SimpleDateFormat(if (spanDays > 180L) "MMM yy" else "d MMM", Locale.US)
                    val bucketCal = Calendar.getInstance()
                    (0..5).map { idx ->
                        val bStart = earliestDayStart + idx * stepMs
                        val bEnd = if (idx == 5) endRange else earliestDayStart + (idx + 1) * stepMs
                        bucketCal.timeInMillis = bStart
                        val bEvents = filteredEvents.filter { it.playedAtMs >= bStart && it.playedAtMs < bEnd }
                        ActivityChartBucket(
                            label = if (idx == 5) "Now" else fmt.format(bucketCal.time),
                            listeningMs = bEvents.sumOf { it.listenedMs },
                            playCount = bEvents.size,
                            isHighlighted = idx == 5
                        )
                    }
                }
            }
        }

        val allEarliestMs = cleanPlayHistory.minOfOrNull { it.playedAtMs }
        val allLatestMs = cleanPlayHistory.maxOfOrNull { it.playedAtMs }
        val trackedSpanDays = if (allEarliestMs != null) {
            val earliestDayStart = Calendar.getInstance().apply {
                timeInMillis = allEarliestMs
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            (((calStartOfToday - earliestDayStart) / 86_400_000L).toInt() + 1).coerceAtLeast(1)
        } else 0
        val cutoff30Days = calStartOfToday - 29L * 86_400_000L
        val allTimeMatchesThirtyDays = allEarliestMs != null && allEarliestMs >= cutoff30Days

        val shortDateFmt = java.text.SimpleDateFormat("d MMM", Locale.getDefault())
        val monthYearFmt = java.text.SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        val windowRangeLabel = when (window) {
            StatsTimeWindow.TODAY -> "Today · ${shortDateFmt.format(java.util.Date(nowMs))}"
            StatsTimeWindow.SEVEN_DAYS -> "${shortDateFmt.format(java.util.Date(calStartOfToday - 6L * 86_400_000L))} – Today"
            StatsTimeWindow.THIRTY_DAYS -> "${shortDateFmt.format(java.util.Date(cutoff30Days))} – Today"
            StatsTimeWindow.ALL_TIME -> if (allEarliestMs != null) {
                "Tracked since ${monthYearFmt.format(java.util.Date(allEarliestMs))} (${trackedSpanDays}d)"
            } else {
                "All recorded history"
            }
        }

        return WindowedListeningStats(
            window = window,
            chartTitle = chartTitle,
            chartBuckets = chartBuckets,
            totalListeningMs = totalListenMs,
            totalVerifiedPlays = totalPlays,
            uniqueTrackCount = windowTracks.size,
            uniqueArtistCount = topArtists.size,
            skippedCount = windowSkips,
            completionRatePercent = completionPct,
            discoveryRatePercent = discoveryPct,
            peakHourOfDay = peakHour,
            peakWindowLabel = peakWindowLabel,
            hourlyPlayCounts = hourlyCounts.toList(),
            appBreakdown = appBreakdown,
            topTracks = windowTracks,
            topArtists = topArtists,
            earliestEventMs = allEarliestMs,
            latestEventMs = allLatestMs,
            trackedSpanDays = trackedSpanDays,
            windowRangeLabel = windowRangeLabel,
            allTimeMatchesThirtyDays = allTimeMatchesThirtyDays
        )
    }

    private fun formatHour(hour24: Int): String {
        val amPm = if (hour24 < 12) "AM" else "PM"
        val h12 = when {
            hour24 == 0 -> 12
            hour24 > 12 -> hour24 - 12
            else -> hour24
        }
        return "$h12 $amPm"
    }
}
