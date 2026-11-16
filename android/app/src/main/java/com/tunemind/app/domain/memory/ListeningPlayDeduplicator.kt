package com.tunemind.app.domain.memory

import com.tunemind.app.data.ingestion.SpotifyListeningHistoryEntity
import com.tunemind.app.domain.model.PlaybackObservation
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
    val isNowPlaying: Boolean = false
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
    val sourcesUsed: Set<String>
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

data class DailyListeningBucket(
    val label: String,
    val listeningMs: Long,
    val playCount: Int
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
    val stats: OverallListeningStats = OverallListeningStats()
)

object ListeningPlayDeduplicator {

    private const val MERGE_WINDOW_MS = 240_000L // 4 minutes collapse window
    private const val MIN_REAL_PLAY_DWELL_MS = 20_000L
    private const val QUICK_SKIP_THRESHOLD_MS = 14_000L
    private const val MAX_SESSION_ESTIMATE_MS = 420_000L

    fun normalizeText(value: String?): String =
        value?.trim()
            ?.lowercase(Locale.ROOT)
            ?.replace(Regex("\\s+"), " ")
            .orEmpty()

    fun canonicalTrackKey(title: String?, artist: String?): String {
        val cleanTitle = normalizeText(title)
        val cleanArtist = normalizeText(artist)
            .takeIf { it.isNotBlank() && it != "unknown artist" }
            .orEmpty()
        return "$cleanTitle|$cleanArtist"
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
        val tA = normalizeText(titleA)
        val tB = normalizeText(titleB)
        if (tA.isBlank() || tB.isBlank() || tA != tB) return false

        val aA = normalizeText(artistA).takeIf { it.isNotBlank() && it != "unknown artist" }
        val aB = normalizeText(artistB).takeIf { it.isNotBlank() && it != "unknown artist" }
        return aA == null || aB == null || aA == aB
    }

    fun resolveAppDisplayName(packageName: String?): String {
        val pkg = packageName?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when {
            pkg.contains("spotify") -> "Spotify"
            pkg.contains("youtube.music") -> "YouTube Music"
            pkg.contains("boomplay") -> "Boomplay"
            pkg.contains("apple") -> "Apple Music"
            pkg.contains("audiomack") -> "Audiomack"
            pkg.contains("soundcloud") -> "SoundCloud"
            pkg.contains("deezer") -> "Deezer"
            pkg.contains("tidal") -> "Tidal"
            pkg.contains("tunemind") -> "TuneMind"
            pkg.contains("phonograph") || pkg.contains("retro") || pkg.contains("vlc") || pkg.contains("musicolet") -> "Local Player"
            pkg.isBlank() -> "Music App"
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
        val key: String,
        val title: String,
        val artist: String,
        val album: String?,
        val spotifyUri: String?,
        val sourcePackage: String,
        val startMs: Long,
        var endMs: Long,
        var durationMs: Long,
        var maxObservedPositionMs: Long,
        var activeDwellMs: Long,
        var hadTransitionOut: Boolean = false
    )

    fun buildSnapshot(
        rawObservations: List<PlaybackObservation>,
        spotifyHistory: List<SpotifyListeningHistoryEntity>,
        nowPlayingTitle: String?,
        nowPlayingArtist: String?,
        nowPlayingSourcePkg: String?,
        isMusicPlayingNow: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        mergeDuplicatePlays: Boolean = true,
        repetitionSensitivity: Float = 0.75f
    ): DeduplicatedListeningSnapshot {
        // 1. Reconstruct genuine plays from raw MediaSession observations (ignoring mirrored spotify_web_api_ingestion rows & browser clutter)
        val filteredObs = rawObservations
            .filter {
                it.observerSessionId != "spotify_web_api_ingestion" &&
                    !it.trackTitle.isNullOrBlank() &&
                    !isExcludedBrowserOrWeb(it.sourcePackage, it.trackTitle, it.trackArtist)
            }
            .sortedBy { it.eventTimeMs }

        val segments = mutableListOf<CandidateSegment>()
        var currentBySource = mutableMapOf<String, CandidateSegment>()
        var skippedCount = 0

        for (obs in filteredObs) {
            val title = obs.trackTitle?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val artist = obs.trackArtist?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown Artist"
            val pkg = obs.sourcePackage?.trim()?.takeIf { it.isNotEmpty() } ?: "com.spotify.music"
            val key = canonicalTrackKey(title, artist)
            val prev = currentBySource[pkg]

            val obsUri = obs.trackId?.trim()?.takeIf { it.startsWith("spotify:track:") }
            val obsDur = (obs.durationMs ?: 0L).coerceAtLeast(0L)
            val obsPos = (obs.positionMs ?: 0L).coerceAtLeast(0L)

            if (prev != null &&
                prev.key == key &&
                (obs.eventTimeMs - prev.endMs) <= MERGE_WINDOW_MS
            ) {
                val delta = (obs.eventTimeMs - prev.endMs).coerceIn(0L, 60_000L)
                if (obs.playbackStateCode == 3 || delta > 0L) {
                    prev.activeDwellMs += delta
                }
                prev.endMs = maxOf(prev.endMs, obs.eventTimeMs)
                if (obsDur > 0L) prev.durationMs = maxOf(prev.durationMs, obsDur)
                prev.maxObservedPositionMs = maxOf(prev.maxObservedPositionMs, obsPos)
            } else {
                if (prev != null) {
                    // Close previous track segment cleanly
                    val gapSincePrevEnd = (obs.eventTimeMs - prev.endMs).coerceAtLeast(0L)
                    if (gapSincePrevEnd in 1..MAX_SESSION_ESTIMATE_MS && obs.sourcePackage == prev.sourcePackage) {
                        prev.activeDwellMs += minOf(gapSincePrevEnd, prev.durationMs.takeIf { it > 0L } ?: 210_000L)
                    }
                    prev.hadTransitionOut = true
                }
                val newSeg = CandidateSegment(
                    key = key,
                    title = title,
                    artist = artist,
                    album = obs.trackAlbum?.trim()?.takeIf { it.isNotEmpty() },
                    spotifyUri = obsUri,
                    sourcePackage = pkg,
                    startMs = obs.eventTimeMs,
                    endMs = obs.eventTimeMs,
                    durationMs = obsDur,
                    maxObservedPositionMs = obsPos,
                    activeDwellMs = 0L
                )
                segments += newSeg
                currentBySource[pkg] = newSeg
            }
        }

        // Convert valid segments into PlayEvents (filtering quick accidental skips)
        val localPlayEvents = mutableListOf<UnifiedPlayEvent>()
        for (seg in segments) {
            val estimatedListenedMs = maxOf(seg.activeDwellMs, seg.maxObservedPositionMs)
            val isCurrentlyActiveSeg = !nowPlayingTitle.isNullOrBlank() &&
                isSameTrack(seg.title, seg.artist, seg.spotifyUri, nowPlayingTitle, nowPlayingArtist, null) &&
                (nowMs - seg.endMs) <= 10 * 60_000L

            val isQuickSkip = seg.hadTransitionOut &&
                estimatedListenedMs in 1 until QUICK_SKIP_THRESHOLD_MS &&
                (seg.durationMs == 0L || estimatedListenedMs < seg.durationMs / 2)

            if (isQuickSkip) {
                skippedCount++
                continue
            }

            // Require at least 20s dwell/progress if we saw a short clip, OR if it wasn't cut off quickly / is actively playing
            val qualifiesAsPlay = !seg.hadTransitionOut ||
                estimatedListenedMs >= MIN_REAL_PLAY_DWELL_MS ||
                (seg.durationMs in 1L..45_000L && estimatedListenedMs >= seg.durationMs / 2) ||
                estimatedListenedMs == 0L // single metadata notification without position info counts once

            if (!qualifiesAsPlay && !isCurrentlyActiveSeg) {
                skippedCount++
                continue
            }

            val finalDuration = seg.durationMs.takeIf { it > 0L } ?: 195_000L
            val finalListened = when {
                estimatedListenedMs >= 10_000L -> minOf(estimatedListenedMs, finalDuration)
                else -> minOf(finalDuration, 180_000L)
            }

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
                isNowPlaying = false
            )
        }

        // 2. Gather verified Spotify Web API plays (strictly ignore legacy CURRENTLY_PLAYING poll spam; keep RECENTLY_PLAYED)
        val verifiedSpotifyRows = spotifyHistory
            .filter { it.ingestionSource == "RECENTLY_PLAYED" && it.trackTitle.isNotBlank() }
            .sortedByDescending { it.playedAtEpochMs }

        val mergedEvents = mutableListOf<UnifiedPlayEvent>()

        // Collapse any duplicate Spotify RECENTLY_PLAYED items that fall within 4 mins when mergeDuplicatePlays is enabled
        for (row in verifiedSpotifyRows) {
            val key = canonicalTrackKey(row.trackTitle, row.artistName)
            val alreadyNear = mergeDuplicatePlays && mergedEvents.any { existing ->
                existing.sourcePackage == "com.spotify.music" &&
                    isSameTrack(existing.title, existing.artist, existing.spotifyUri, row.trackTitle, row.artistName, row.spotifyUri) &&
                    kotlin.math.abs(existing.playedAtMs - row.playedAtEpochMs) <= MERGE_WINDOW_MS
            }
            if (alreadyNear) continue

            val durMs = row.durationMs.takeIf { it > 0L } ?: 195_000L
            mergedEvents += UnifiedPlayEvent(
                playId = "sp_${row.historyId}",
                trackKey = key,
                title = row.trackTitle.trim(),
                artist = row.artistName.trim().ifBlank { "Unknown Artist" },
                album = row.albumName.trim().takeIf { it.isNotEmpty() },
                albumArtUrl = row.albumArtUrl,
                spotifyUri = row.spotifyUri.takeIf { it.isNotBlank() },
                sourcePackage = "com.spotify.music",
                appDisplayName = "Spotify",
                playedAtMs = row.playedAtEpochMs,
                durationMs = durMs,
                listenedMs = durMs,
                isNowPlaying = false
            )
        }

        // Fallback: if user ONLY has TOP_TRACKS seed and 0 real plays anywhere yet, include TOP_TRACKS once each cleanly
        if (verifiedSpotifyRows.isEmpty() && localPlayEvents.isEmpty()) {
            spotifyHistory
                .filter { it.ingestionSource == "TOP_TRACKS" && it.trackTitle.isNotBlank() }
                .distinctBy { canonicalTrackKey(it.trackTitle, it.artistName) }
                .forEach { row ->
                    val durMs = row.durationMs.takeIf { it > 0L } ?: 195_000L
                    mergedEvents += UnifiedPlayEvent(
                        playId = "sp_seed_${row.trackId}",
                        trackKey = canonicalTrackKey(row.trackTitle, row.artistName),
                        title = row.trackTitle.trim(),
                        artist = row.artistName.trim().ifBlank { "Unknown Artist" },
                        album = row.albumName.trim().takeIf { it.isNotEmpty() },
                        albumArtUrl = row.albumArtUrl,
                        spotifyUri = row.spotifyUri.takeIf { it.isNotBlank() },
                        sourcePackage = "com.spotify.music",
                        appDisplayName = "Spotify",
                        playedAtMs = row.playedAtEpochMs,
                        durationMs = durMs,
                        listenedMs = durMs,
                        isNowPlaying = false
                    )
                }
        }

        // Merge local device plays without double counting any Spotify play already synced within ±4 minutes
        for (localEvent in localPlayEvents) {
            val matchingIdx = if (mergeDuplicatePlays) {
                mergedEvents.indexOfFirst { existing ->
                    existing.sourcePackage == localEvent.sourcePackage &&
                        isSameTrack(existing.title, existing.artist, existing.spotifyUri, localEvent.title, localEvent.artist, localEvent.spotifyUri) &&
                        kotlin.math.abs(existing.playedAtMs - localEvent.playedAtMs) <= MERGE_WINDOW_MS
                }
            } else -1
            if (matchingIdx >= 0) {
                val existing = mergedEvents[matchingIdx]
                mergedEvents[matchingIdx] = existing.copy(
                    album = existing.album ?: localEvent.album,
                    spotifyUri = existing.spotifyUri ?: localEvent.spotifyUri,
                    listenedMs = maxOf(existing.listenedMs, localEvent.listenedMs)
                )
            } else {
                mergedEvents += localEvent
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
                finalTimeline.add(0, matched.copy(isNowPlaying = isMusicPlayingNow))
            } else if (isMusicPlayingNow) {
                val knownMatch = finalTimeline.firstOrNull {
                    isSameTrack(it.title, it.artist, it.spotifyUri, nowPlayingTitle, nowPlayingArtist, null)
                }
                finalTimeline.add(
                    0,
                    UnifiedPlayEvent(
                        playId = "live_${activeKey}",
                        trackKey = activeKey,
                        title = nowPlayingTitle.trim(),
                        artist = nowPlayingArtist?.trim()?.ifBlank { "Unknown Artist" } ?: "Unknown Artist",
                        album = knownMatch?.album,
                        albumArtUrl = knownMatch?.albumArtUrl,
                        spotifyUri = knownMatch?.spotifyUri,
                        sourcePackage = nowPlayingSourcePkg?.takeIf { it.isNotBlank() } ?: "com.spotify.music",
                        appDisplayName = resolveAppDisplayName(nowPlayingSourcePkg ?: "com.spotify.music"),
                        playedAtMs = nowMs,
                        durationMs = knownMatch?.durationMs ?: 195_000L,
                        listenedMs = 30_000L,
                        isNowPlaying = true
                    )
                )
            }
        }

        // 3. Aggregate per-track strict statistics (SINGLE SOURCE OF TRUTH for Play Counts everywhere)
        val artMap = spotifyHistory
            .filter { !it.albumArtUrl.isNullOrBlank() }
            .associate { canonicalTrackKey(it.trackTitle, it.artistName) to it.albumArtUrl }

        val sensitivity = repetitionSensitivity.coerceIn(0.2f, 1.0f)
        val satietyPerPlay = (12f + sensitivity * 12f).toInt().coerceIn(13, 25)
        val coolingPlayThreshold = when {
            sensitivity >= 0.85f -> 3
            sensitivity >= 0.50f -> 4
            else -> 6
        }

        val groupedByTrack = finalTimeline.groupBy { it.trackKey }
        val unifiedTracks = groupedByTrack.map { (key, events) ->
            val latest = events.maxByOrNull { it.playedAtMs } ?: events.first()
            val plays = events.size.coerceAtLeast(1)
            val satiety = minOf(94, plays * satietyPerPlay)
            val freshness = (100 - satiety).coerceIn(12, 98)
            val bestUri = events.firstNotNullOfOrNull { it.spotifyUri }
            val bestAlbum = events.firstNotNullOfOrNull { it.album }
            val bestArt = events.firstNotNullOfOrNull { it.albumArtUrl } ?: artMap[key]
            val bestDur = events.maxOfOrNull { it.durationMs } ?: 195_000L
            val totalListen = events.sumOf { it.listenedMs }

            UnifiedTrackStats(
                trackKey = key,
                entryId = bestUri?.let { "spotify:${it.substringAfterLast(':')}" } ?: "track:$key",
                title = latest.title,
                artist = latest.artist,
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
                isCoolingDown = plays >= coolingPlayThreshold || freshness < 45,
                sourcesUsed = events.map { it.sourcePackage }.toSet()
            )
        }.sortedByDescending { it.lastPlayedAtMs }

        val countByKey = unifiedTracks.associate { it.trackKey to it.verifiedPlayCount }
        val countByUri = unifiedTracks.mapNotNull { track ->
            track.spotifyUri?.let { it.lowercase(Locale.ROOT) to track.verifiedPlayCount }
        }.toMap()

        // 4. Compute Cross-App Stats & Habit Analytics
        val calStartOfToday = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val sevenDaysAgo = nowMs - 7L * 86_400_000L
        val thirtyDaysAgo = nowMs - 30L * 86_400_000L

        val todayMs = finalTimeline.filter { it.playedAtMs >= calStartOfToday }.sumOf { it.listenedMs }
        val sevenDayMs = finalTimeline.filter { it.playedAtMs >= sevenDaysAgo }.sumOf { it.listenedMs }
        val thirtyDayMs = finalTimeline.filter { it.playedAtMs >= thirtyDaysAgo }.sumOf { it.listenedMs }
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
            stats = overallStats
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
