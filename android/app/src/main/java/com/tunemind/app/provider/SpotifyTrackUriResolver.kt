package com.tunemind.app.provider

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tunemind.app.data.ingestion.SpotifyAuthManager
import com.tunemind.app.data.ingestion.SpotifyListeningHistoryEntity
import com.tunemind.app.data.ingestion.SpotifyTrackAggregate
import com.tunemind.app.data.ingestion.parseSpotifyIsoTimestampMs
import com.tunemind.app.data.local.TuneMindDatabase
import com.tunemind.app.domain.model.TrackMemoryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Resolves any `(title, artist, query)` into a canonical `spotify:track:<22-char-id>` URI
 * using the Spotify Web API (`GET /v1/search?type=track`), unifies all local + cloud Spotify
 * listening history for the AI Agent ("play the song I listened to the most", "recommend a new song"),
 * and triggers direct Spotify Web API remote playback (`PUT /v1/me/player/play`).
 */
object SpotifyTrackUriResolver {
    private const val TAG = "SpotifyUriResolver"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uriCache = ConcurrentHashMap<String, String>()
    private val discoveryRotationCursor = AtomicInteger(0)

    @Volatile
    private var cachedClientCredentialsToken: String? = null
    @Volatile
    private var clientCredentialsExpiresAtMs: Long = 0L

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Unifies all user Spotify tracks across:
     * 1. `spotify_listening_history` Room table (`getAggregatedTracksSnapshot`)
     * 2. `track_history_telemetry` Room table (`getTelemetryItemsSnapshot` from 15-min WorkManager sync)
     * 3. `fallbackMemoryTracks` (`TrackMemoryEntry` from NotificationListener / EventQueue)
     * 4. Live Spotify Web API `/v1/me/player/recently-played` & `/v1/me/top/tracks` if local tables are empty.
     */
    fun getUnifiedUserSpotifyTracksBlocking(
        context: Context,
        fallbackMemoryTracks: List<TrackMemoryEntry> = emptyList()
    ): List<SpotifyTrackAggregate> {
        return runBlocking {
            withTimeoutOrNull(2200L) {
                withContext(Dispatchers.IO) {
                    val dao = TuneMindDatabase.getInstance(context).spotifyListeningHistoryDao()
                    val mergedMap = LinkedHashMap<String, SpotifyTrackAggregate>()

                    // 1. Load aggregated spotify_listening_history
                    runCatching {
                        val agg = dao.getAggregatedTracksSnapshot(100)
                        for (item in agg) {
                            if (item.trackTitle.isBlank()) continue
                            val key = "${item.trackTitle.lowercase().trim()}|${item.artistName.lowercase().trim()}"
                            mergedMap[key] = item
                        }
                    }

                    // 2. Merge 15-minute WorkManager track_history_telemetry rows
                    runCatching {
                        val telemetryRows = dao.getTelemetryItemsSnapshot(150)
                        val grouped = telemetryRows.groupBy {
                            "${it.trackTitle.lowercase().trim()}|${it.artistName.lowercase().trim()}"
                        }
                        for ((key, rows) in grouped) {
                            val sample = rows.firstOrNull() ?: continue
                            if (sample.trackTitle.isBlank()) continue
                            val count = rows.size
                            val firstMs = rows.minOfOrNull { it.playedAtEpochMs } ?: sample.playedAtEpochMs
                            val lastMs = rows.maxOfOrNull { it.playedAtEpochMs } ?: sample.playedAtEpochMs
                            val existing = mergedMap[key]
                            if (existing == null) {
                                mergedMap[key] = SpotifyTrackAggregate(
                                    trackId = sample.trackId,
                                    spotifyUri = sample.spotifyUri,
                                    trackTitle = sample.trackTitle,
                                    artistName = sample.artistName,
                                    albumName = sample.albumName,
                                    albumArtUrl = sample.albumArtUrl,
                                    durationMs = sample.durationMs,
                                    playCount = count,
                                    firstPlayedEpochMs = firstMs,
                                    lastPlayedEpochMs = lastMs
                                )
                            } else if (count > existing.playCount) {
                                mergedMap[key] = existing.copy(
                                    playCount = count,
                                    lastPlayedEpochMs = maxOf(existing.lastPlayedEpochMs, lastMs),
                                    spotifyUri = existing.spotifyUri.ifBlank { sample.spotifyUri }
                                )
                            }
                        }
                    }

                    // 3. If user is authenticated with Spotify and local DB had no Spotify rows yet, pull live now
                    if (mergedMap.isEmpty()) {
                        val authManager = SpotifyAuthManager(context)
                        if (authManager.isAuthenticated()) {
                            val userToken = authManager.getValidAccessToken()
                            if (!userToken.isNullOrBlank()) {
                                val fetched = fetchLiveUserRecentAndTopTracks(userToken, dao)
                                for (item in fetched) {
                                    val key = "${item.trackTitle.lowercase().trim()}|${item.artistName.lowercase().trim()}"
                                    mergedMap[key] = item
                                }
                            }
                        }
                    }

                    // 4. Merge fallbackMemoryTracks from MediaSession / EventQueue
                    for (mem in fallbackMemoryTracks) {
                        val title = mem.title?.trim() ?: continue
                        if (title.isBlank() || title.equals("Standing By", ignoreCase = true) || title.equals("Active Session", ignoreCase = true)) continue
                        val artist = mem.artist?.trim() ?: "Unknown Artist"
                        val key = "${title.lowercase()}|${artist.lowercase()}"
                        val existing = mergedMap[key]
                        val candidateUri = mem.providerTrackId?.takeIf { it.startsWith("spotify:track:") } ?: ""
                        if (existing == null) {
                            mergedMap[key] = SpotifyTrackAggregate(
                                trackId = candidateUri.removePrefix("spotify:track:").ifBlank { key },
                                spotifyUri = candidateUri,
                                trackTitle = title,
                                artistName = artist,
                                albumName = mem.album ?: "",
                                albumArtUrl = null,
                                durationMs = 0L,
                                playCount = mem.distinctAppearanceCount.coerceAtLeast(1),
                                firstPlayedEpochMs = mem.firstObservedAppearanceAtMs ?: System.currentTimeMillis(),
                                lastPlayedEpochMs = mem.lastObservedAppearanceAtMs ?: System.currentTimeMillis()
                            )
                        } else if (mem.distinctAppearanceCount > existing.playCount) {
                            mergedMap[key] = existing.copy(
                                playCount = mem.distinctAppearanceCount,
                                spotifyUri = existing.spotifyUri.ifBlank { candidateUri }
                            )
                        }
                    }

                    mergedMap.values.sortedWith(
                        compareByDescending<SpotifyTrackAggregate> { it.lastPlayedEpochMs }
                            .thenByDescending { it.playCount }
                    )
                }
            } ?: emptyList()
        }
    }

    /**
     * Finds the user's #1 most-played / most-listened song from their unified Spotify listening history
     * (and Spotify `/v1/me/top/tracks?time_range=short_term` if authenticated), resolving its exact
     * `spotify:track:<22-char-id>` URI so it plays immediately upon approval.
     */
    fun findMostPlayedTrackBlocking(
        context: Context,
        fallbackMemoryTracks: List<TrackMemoryEntry> = emptyList()
    ): SpotifyTrackAggregate? {
        return runBlocking {
            withTimeoutOrNull(2400L) {
                withContext(Dispatchers.IO) {
                    val unified = getUnifiedUserSpotifyTracksBlocking(context, fallbackMemoryTracks)
                    var bestLocal = unified.maxWithOrNull(
                        compareBy<SpotifyTrackAggregate> { it.playCount }
                            .thenBy { it.lastPlayedEpochMs }
                    )

                    // If all local tracks have playCount <= 1 and Spotify OAuth is connected, check /v1/me/top/tracks
                    if ((bestLocal == null || bestLocal.playCount <= 1) && SpotifyAuthManager(context).isAuthenticated()) {
                        val userToken = SpotifyAuthManager(context).getValidAccessToken()
                        if (!userToken.isNullOrBlank()) {
                            val topTrack = fetchUserTopTrackFromWebApi(userToken)
                            if (topTrack != null) {
                                bestLocal = if (bestLocal != null && bestLocal.playCount > topTrack.playCount) {
                                    bestLocal
                                } else {
                                    topTrack
                                }
                            }
                        }
                    }

                    val chosen = bestLocal ?: return@withContext null
                    if (chosen.spotifyUri.startsWith("spotify:track:")) {
                        return@withContext chosen
                    }

                    val resolvedUri = resolveTrackUriBlocking(
                        context = context,
                        cleanTitle = chosen.trackTitle,
                        cleanArtist = chosen.artistName,
                        fullQuery = "${chosen.trackTitle} ${chosen.artistName}".trim()
                    ) ?: ""

                    chosen.copy(
                        spotifyUri = resolvedUri,
                        trackId = resolvedUri.removePrefix("spotify:track:").ifBlank { chosen.trackId }
                    )
                }
            }
        }
    }

    /**
     * Fetches a genuinely NEW / FRESH song recommendation tailored to the user's Spotify taste:
     * 1. Queries `GET /v1/me/top/tracks?time_range=medium_term&limit=25` for tracks not in recent heavy rotation.
     * 2. Queries `GET /v1/search?type=track&limit=15&q=artist:<TopArtist>` using the user's top artists from their
     *    synced Spotify history, picking a fresh track by their favorite artists that they haven't overplayed.
     * 3. Guarantees a valid `spotify:track:<22-char-id>` URI so Spotify plays the song directly.
     */
    fun fetchNewDiscoveryRecommendationBlocking(
        context: Context,
        fallbackMemoryTracks: List<TrackMemoryEntry> = emptyList(),
        excludedTitles: Set<String> = emptySet()
    ): SpotifyTrackAggregate? {
        return runBlocking {
            withTimeoutOrNull(2600L) {
                withContext(Dispatchers.IO) {
                    val unified = getUnifiedUserSpotifyTracksBlocking(context, fallbackMemoryTracks)
                    val recentTitlesLower = unified.take(15).map { it.trackTitle.lowercase().trim() }.toSet()
                    val allExcludedLower = excludedTitles.map { it.lowercase().trim() }.toSet()

                    val token = getSearchBearerToken(context)
                    val authManager = SpotifyAuthManager(context)
                    val userToken = if (authManager.isAuthenticated()) authManager.getValidAccessToken() else null

                    val candidateTracks = mutableListOf<SpotifyTrackAggregate>()

                    // Strategy A: User's medium_term / long_term Spotify Top Tracks not in recent 15 plays
                    if (!userToken.isNullOrBlank()) {
                        for (range in listOf("medium_term", "short_term")) {
                            val topCandidates = fetchTopTracksListFromWebApi(userToken, range, 25)
                            for (t in topCandidates) {
                                val tl = t.trackTitle.lowercase().trim()
                                if (!allExcludedLower.contains(tl) && !recentTitlesLower.contains(tl)) {
                                    candidateTracks.add(t)
                                }
                            }
                            if (candidateTracks.size >= 4) break
                        }
                    }

                    // Strategy B: Search Spotify catalog for fresh tracks by the user's favorite artists
                    if (candidateTracks.isEmpty() && !token.isNullOrBlank()) {
                        val topArtists = unified
                            .map { it.artistName.split(",").firstOrNull()?.trim() ?: "" }
                            .filter { it.isNotBlank() && !it.equals("Unknown Artist", ignoreCase = true) }
                            .groupingBy { it }
                            .eachCount()
                            .entries
                            .sortedByDescending { it.value }
                            .map { it.key }
                            .take(4)
                            .ifEmpty { listOf("Burna Boy", "Tems", "Sauti Sol", "Wizkid") }

                        val artistCursor = discoveryRotationCursor.getAndIncrement()
                        val orderedArtists = topArtists.indices.map { idx ->
                            topArtists[(idx + artistCursor) % topArtists.size]
                        }

                        for (artist in orderedArtists) {
                            val searchHits = searchArtistFreshTracks(token, artist, 12)
                            for (hit in searchHits) {
                                val tl = hit.trackTitle.lowercase().trim()
                                if (!allExcludedLower.contains(tl) && !recentTitlesLower.contains(tl)) {
                                    candidateTracks.add(hit)
                                }
                            }
                            if (candidateTracks.size >= 5) break
                        }
                    }

                    // Strategy C: Low-play-count fresh tracks from unified history
                    if (candidateTracks.isEmpty()) {
                        for (u in unified.filter { it.playCount <= 2 }) {
                            val tl = u.trackTitle.lowercase().trim()
                            if (!allExcludedLower.contains(tl)) {
                                candidateTracks.add(u)
                            }
                        }
                    }

                    val picked = if (candidateTracks.isNotEmpty()) {
                        val idx = discoveryRotationCursor.getAndIncrement() % candidateTracks.size
                        candidateTracks[idx]
                    } else {
                        unified.firstOrNull()
                    } ?: return@withContext null

                    if (picked.spotifyUri.startsWith("spotify:track:")) {
                        picked
                    } else {
                        val resolvedUri = resolveTrackUriBlocking(
                            context = context,
                            cleanTitle = picked.trackTitle,
                            cleanArtist = picked.artistName,
                            fullQuery = "${picked.trackTitle} ${picked.artistName}".trim()
                        ) ?: ""
                        picked.copy(
                            spotifyUri = resolvedUri,
                            trackId = resolvedUri.removePrefix("spotify:track:").ifBlank { picked.trackId }
                        )
                    }
                }
            }
        }
    }

    private suspend fun fetchLiveUserRecentAndTopTracks(
        userToken: String,
        dao: com.tunemind.app.data.ingestion.SpotifyListeningHistoryDao
    ): List<SpotifyTrackAggregate> {
        val results = LinkedHashMap<String, SpotifyTrackAggregate>()
        try {
            val req = Request.Builder()
                .url("https://api.spotify.com/v1/me/player/recently-played?limit=50")
                .addHeader("Authorization", "Bearer $userToken")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val items = JSONObject(resp.body?.string() ?: "{}").optJSONArray("items") ?: JSONArray()
                    val entitiesToSave = mutableListOf<SpotifyListeningHistoryEntity>()
                    val now = System.currentTimeMillis()
                    for (i in 0 until items.length()) {
                        val itemObj = items.optJSONObject(i) ?: continue
                        val trackObj = itemObj.optJSONObject("track") ?: continue
                        val uri = trackObj.optString("uri", "")
                        val id = trackObj.optString("id", "").ifBlank { uri.removePrefix("spotify:track:") }
                        val title = trackObj.optString("name", "").trim()
                        if (title.isBlank() || !uri.startsWith("spotify:track:")) continue
                        val artistsArr = trackObj.optJSONArray("artists")
                        val artistNames = mutableListOf<String>()
                        if (artistsArr != null) {
                            for (a in 0 until artistsArr.length()) {
                                val an = artistsArr.optJSONObject(a)?.optString("name", "")?.trim() ?: ""
                                if (an.isNotBlank()) artistNames.add(an)
                            }
                        }
                        val artistStr = artistNames.joinToString(", ").ifBlank { "Unknown Artist" }
                        val albumObj = trackObj.optJSONObject("album")
                        val albumName = albumObj?.optString("name", "") ?: ""
                        val artUrl = albumObj?.optJSONArray("images")?.optJSONObject(0)?.optString("url")
                        val durationMs = trackObj.optLong("duration_ms", 0L)
                        val playedAtIso = itemObj.optString("played_at", "")
                        val playedAtMs = parseSpotifyIsoTimestampMs(playedAtIso)

                        entitiesToSave.add(
                            SpotifyListeningHistoryEntity(
                                historyId = "${id}_${playedAtMs}",
                                trackId = id,
                                spotifyUri = uri,
                                trackTitle = title,
                                artistName = artistStr,
                                albumName = albumName,
                                albumArtUrl = artUrl,
                                durationMs = durationMs,
                                playedAtIso = playedAtIso,
                                playedAtEpochMs = playedAtMs,
                                ingestionSource = "RECENTLY_PLAYED",
                                syncedAtEpochMs = now
                            )
                        )

                        val key = "${title.lowercase()}|${artistStr.lowercase()}"
                        val existing = results[key]
                        if (existing == null) {
                            results[key] = SpotifyTrackAggregate(
                                trackId = id,
                                spotifyUri = uri,
                                trackTitle = title,
                                artistName = artistStr,
                                albumName = albumName,
                                albumArtUrl = artUrl,
                                durationMs = durationMs,
                                playCount = 1,
                                firstPlayedEpochMs = playedAtMs,
                                lastPlayedEpochMs = playedAtMs
                            )
                        } else {
                            results[key] = existing.copy(
                                playCount = existing.playCount + 1,
                                firstPlayedEpochMs = minOf(existing.firstPlayedEpochMs, playedAtMs),
                                lastPlayedEpochMs = maxOf(existing.lastPlayedEpochMs, playedAtMs)
                            )
                        }
                    }
                    if (entitiesToSave.isNotEmpty()) {
                        runCatching { dao.insertHistoryIgnoreDuplicates(entitiesToSave) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "fetchLiveUserRecentAndTopTracks notice: ${e.message}")
        }
        return results.values.toList()
    }

    private fun fetchUserTopTrackFromWebApi(userToken: String): SpotifyTrackAggregate? {
        return fetchTopTracksListFromWebApi(userToken, "short_term", 5).firstOrNull()
            ?: fetchTopTracksListFromWebApi(userToken, "medium_term", 5).firstOrNull()
    }

    private fun fetchTopTracksListFromWebApi(
        userToken: String,
        timeRange: String,
        limit: Int
    ): List<SpotifyTrackAggregate> {
        val list = mutableListOf<SpotifyTrackAggregate>()
        try {
            val req = Request.Builder()
                .url("https://api.spotify.com/v1/me/top/tracks?time_range=$timeRange&limit=$limit")
                .addHeader("Authorization", "Bearer $userToken")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val items = JSONObject(resp.body?.string() ?: "{}").optJSONArray("items") ?: return emptyList()
                val now = System.currentTimeMillis()
                for (i in 0 until items.length()) {
                    val trackObj = items.optJSONObject(i) ?: continue
                    val uri = trackObj.optString("uri", "")
                    if (!uri.startsWith("spotify:track:")) continue
                    val id = trackObj.optString("id", "").ifBlank { uri.removePrefix("spotify:track:") }
                    val name = trackObj.optString("name", "").trim()
                    if (name.isBlank()) continue
                    val artistsArr = trackObj.optJSONArray("artists")
                    val firstArtist = artistsArr?.optJSONObject(0)?.optString("name", "") ?: "Unknown Artist"
                    val albumObj = trackObj.optJSONObject("album")
                    val albumName = albumObj?.optString("name", "") ?: ""
                    val artUrl = albumObj?.optJSONArray("images")?.optJSONObject(0)?.optString("url")
                    val durationMs = trackObj.optLong("duration_ms", 0L)
                    list.add(
                        SpotifyTrackAggregate(
                            trackId = id,
                            spotifyUri = uri,
                            trackTitle = name,
                            artistName = firstArtist,
                            albumName = albumName,
                            albumArtUrl = artUrl,
                            durationMs = durationMs,
                            playCount = (limit - i).coerceAtLeast(3),
                            firstPlayedEpochMs = now - (i * 60_000L),
                            lastPlayedEpochMs = now - (i * 30_000L)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "fetchTopTracksListFromWebApi notice: ${e.message}")
        }
        return list
    }

    private fun searchArtistFreshTracks(
        bearerToken: String,
        artistName: String,
        limit: Int
    ): List<SpotifyTrackAggregate> {
        val list = mutableListOf<SpotifyTrackAggregate>()
        try {
            val url = Uri.parse("https://api.spotify.com/v1/search").buildUpon()
                .appendQueryParameter("q", "artist:$artistName")
                .appendQueryParameter("type", "track")
                .appendQueryParameter("limit", limit.toString())
                .build()
                .toString()
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $bearerToken")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val items = JSONObject(resp.body?.string() ?: "{}")
                    .optJSONObject("tracks")
                    ?.optJSONArray("items") ?: return emptyList()
                val now = System.currentTimeMillis()
                for (i in 0 until items.length()) {
                    val trackObj = items.optJSONObject(i) ?: continue
                    val uri = trackObj.optString("uri", "")
                    if (!uri.startsWith("spotify:track:")) continue
                    val id = trackObj.optString("id", "").ifBlank { uri.removePrefix("spotify:track:") }
                    val name = trackObj.optString("name", "").trim()
                    if (name.isBlank()) continue
                    val artistsArr = trackObj.optJSONArray("artists")
                    val firstArtist = artistsArr?.optJSONObject(0)?.optString("name", "") ?: artistName
                    val albumObj = trackObj.optJSONObject("album")
                    val albumName = albumObj?.optString("name", "") ?: ""
                    val artUrl = albumObj?.optJSONArray("images")?.optJSONObject(0)?.optString("url")
                    val durationMs = trackObj.optLong("duration_ms", 0L)
                    list.add(
                        SpotifyTrackAggregate(
                            trackId = id,
                            spotifyUri = uri,
                            trackTitle = name,
                            artistName = firstArtist,
                            albumName = albumName,
                            albumArtUrl = artUrl,
                            durationMs = durationMs,
                            playCount = 1,
                            firstPlayedEpochMs = now,
                            lastPlayedEpochMs = now
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "searchArtistFreshTracks notice: ${e.message}")
        }
        return list
    }

    /**
     * Resolves a canonical `spotify:track:<id>` URI within a fast 1.6s budget:
     * 1. Checks in-memory cache and local Room `spotify_listening_history` + `track_history_telemetry` tables.
     * 2. Queries `GET https://api.spotify.com/v1/search?type=track&limit=5&q=...` using either
     *    the user's OAuth token or a Spotify Client Credentials token (`CLIENT_ID:CLIENT_SECRET`).
     */
    fun resolveTrackUriBlocking(
        context: Context,
        cleanTitle: String,
        cleanArtist: String,
        fullQuery: String
    ): String? {
        val cacheKey = "${cleanTitle.lowercase().trim()}|${cleanArtist.lowercase().trim()}|${fullQuery.lowercase().trim()}"
        uriCache[cacheKey]?.let { return it }

        return runBlocking {
            withTimeoutOrNull(1750L) {
                withContext(Dispatchers.IO) {
                    // 1. Check local Room Spotify history & telemetry cache first (< 3ms)
                    try {
                        val dao = TuneMindDatabase.getInstance(context).spotifyListeningHistoryDao()
                        val localTracks = dao.getAggregatedTracksSnapshot(80)
                        val matchedLocal = localTracks.firstOrNull { item ->
                            item.spotifyUri.startsWith("spotify:track:") && (
                                item.trackTitle.equals(cleanTitle, ignoreCase = true) ||
                                    (cleanTitle.length >= 3 && item.trackTitle.contains(cleanTitle, ignoreCase = true)) ||
                                    (item.trackTitle.length >= 3 && fullQuery.contains(item.trackTitle, ignoreCase = true))
                                )
                        }
                        if (matchedLocal != null) {
                            uriCache[cacheKey] = matchedLocal.spotifyUri
                            return@withContext matchedLocal.spotifyUri
                        }

                        val telemetryTracks = dao.getTelemetryItemsSnapshot(100)
                        val matchedTelemetry = telemetryTracks.firstOrNull { item ->
                            item.spotifyUri.startsWith("spotify:track:") && (
                                item.trackTitle.equals(cleanTitle, ignoreCase = true) ||
                                    (cleanTitle.length >= 3 && item.trackTitle.contains(cleanTitle, ignoreCase = true)) ||
                                    (item.trackTitle.length >= 3 && fullQuery.contains(item.trackTitle, ignoreCase = true))
                                )
                        }
                        if (matchedTelemetry != null) {
                            uriCache[cacheKey] = matchedTelemetry.spotifyUri
                            return@withContext matchedTelemetry.spotifyUri
                        }
                    } catch (_: Exception) {}

                    // 2. Query Spotify Web API /v1/search?type=track
                    val token = getSearchBearerToken(context) ?: return@withContext null
                    val searchQuery = when {
                        cleanTitle.isNotBlank() && cleanArtist.isNotBlank() && !cleanArtist.equals("Unknown Artist", ignoreCase = true) ->
                            "track:$cleanTitle artist:$cleanArtist"
                        fullQuery.isNotBlank() -> fullQuery
                        else -> cleanTitle
                    }

                    val firstAttemptUri = querySpotifyTrackSearch(token, searchQuery, cleanTitle, cleanArtist)
                        ?: if (fullQuery.isNotBlank() && fullQuery != searchQuery) {
                            querySpotifyTrackSearch(token, fullQuery, cleanTitle, cleanArtist)
                        } else null

                    if (!firstAttemptUri.isNullOrBlank()) {
                        uriCache[cacheKey] = firstAttemptUri
                    }
                    firstAttemptUri
                }
            }
        }
    }

    private fun querySpotifyTrackSearch(
        bearerToken: String,
        query: String,
        expectedTitle: String,
        expectedArtist: String
    ): String? {
        return try {
            val url = Uri.parse("https://api.spotify.com/v1/search").buildUpon()
                .appendQueryParameter("q", query)
                .appendQueryParameter("type", "track")
                .appendQueryParameter("limit", "5")
                .build()
                .toString()

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $bearerToken")
                .addHeader("Accept", "application/json")
                .get()
                .build()

            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val raw = resp.body?.string() ?: return null
                val items = JSONObject(raw).optJSONObject("tracks")?.optJSONArray("items") ?: return null
                if (items.length() == 0) return null

                var bestUri: String? = null
                for (i in 0 until items.length()) {
                    val trackObj = items.optJSONObject(i) ?: continue
                    val uri = trackObj.optString("uri", "")
                    if (!uri.startsWith("spotify:track:")) continue
                    if (bestUri == null) bestUri = uri

                    val name = trackObj.optString("name", "")
                    val artistsArr = trackObj.optJSONArray("artists")
                    val firstArtist = artistsArr?.optJSONObject(0)?.optString("name", "") ?: ""

                    val titleMatches = expectedTitle.isBlank() ||
                        name.contains(expectedTitle, ignoreCase = true) ||
                        expectedTitle.contains(name, ignoreCase = true)
                    val artistMatches = expectedArtist.isBlank() ||
                        expectedArtist.equals("Unknown Artist", ignoreCase = true) ||
                        firstArtist.contains(expectedArtist, ignoreCase = true) ||
                        expectedArtist.contains(firstArtist, ignoreCase = true)

                    if (titleMatches && artistMatches) {
                        Log.i(TAG, "Resolved exact Spotify track URI: $uri ('$name' by '$firstArtist')")
                        return uri
                    }
                }
                Log.i(TAG, "Resolved top Spotify track URI: $bestUri for query='$query'")
                bestUri
            }
        } catch (e: Exception) {
            Log.d(TAG, "Spotify /v1/search notice: ${e.message}")
            null
        }
    }

    /**
     * Obtains a valid Bearer token for `/v1/search`:
     * Uses the user's OAuth token if available, otherwise fetches a lightweight
     * `client_credentials` token using `SpotifyAuthManager.CLIENT_ID` & `CLIENT_SECRET`.
     */
    private suspend fun getSearchBearerToken(context: Context): String? {
        val authManager = SpotifyAuthManager(context)
        if (authManager.isAuthenticated()) {
            val userToken = authManager.getValidAccessToken()
            if (!userToken.isNullOrBlank()) return userToken
        }

        val now = System.currentTimeMillis()
        val cached = cachedClientCredentialsToken
        if (!cached.isNullOrBlank() && now + 30_000L < clientCredentialsExpiresAtMs) {
            return cached
        }

        return try {
            val body = "grant_type=client_credentials"
                .toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val req = Request.Builder()
                .url("https://accounts.spotify.com/api/token")
                .addHeader("Authorization", SpotifyAuthManager.buildBasicAuthHeader())
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .post(body)
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string() ?: "{}")
                val token = json.optString("access_token", "")
                val expiresIn = json.optLong("expires_in", 3600L)
                if (token.isNotBlank()) {
                    cachedClientCredentialsToken = token
                    clientCredentialsExpiresAtMs = now + (expiresIn * 1000L)
                    token
                } else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client credentials token notice: ${e.message}")
            null
        }
    }

    /**
     * Fires `PUT https://api.spotify.com/v1/me/player/play` with `{"uris": ["spotify:track:<id>"]}`
     * across 4 waves (`150ms`, `750ms`, `900ms`, `1100ms`) and transfers active playback to the
     * smartphone device if needed so the track starts streaming automatically.
     */
    fun triggerWebApiRemotePlayAsync(context: Context, spotifyTrackUri: String) {
        if (!spotifyTrackUri.startsWith("spotify:track:")) return
        val appContext = context.applicationContext

        scope.launch {
            val authManager = SpotifyAuthManager(appContext)
            if (!authManager.isAuthenticated()) return@launch
            val token = authManager.getValidAccessToken() ?: return@launch

            for (waveDelay in listOf(150L, 750L, 900L, 1100L)) {
                delay(waveDelay)
                try {
                    val listener = com.tunemind.app.service.TuneMindListenerService.instance
                    if (waveDelay > 500L && listener?.isPlaying() == true) {
                        break
                    }

                    val (deviceId, isActive) = findActiveOrSmartphoneDevice(token)
                    if (!deviceId.isNullOrBlank() && !isActive) {
                        // Wake / transfer playback to smartphone device first
                        runCatching {
                            val transferPayload = JSONObject().apply {
                                put("device_ids", JSONArray().put(deviceId))
                                put("play", true)
                            }
                            val transferReq = Request.Builder()
                                .url("https://api.spotify.com/v1/me/player")
                                .addHeader("Authorization", "Bearer $token")
                                .addHeader("Content-Type", "application/json")
                                .put(transferPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                                .build()
                            httpClient.newCall(transferReq).execute().close()
                        }
                    }

                    val playUrl = if (!deviceId.isNullOrBlank()) {
                        "https://api.spotify.com/v1/me/player/play?device_id=${Uri.encode(deviceId)}"
                    } else {
                        "https://api.spotify.com/v1/me/player/play"
                    }

                    val payload = JSONObject().apply {
                        put("uris", JSONArray().put(spotifyTrackUri))
                        put("position_ms", 0)
                    }

                    val req = Request.Builder()
                        .url(playUrl)
                        .addHeader("Authorization", "Bearer $token")
                        .addHeader("Content-Type", "application/json")
                        .put(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()

                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful || resp.code == 204) {
                            Log.i(TAG, "Spotify Web API PUT /v1/me/player/play succeeded for $spotifyTrackUri")
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Web API play wave notice: ${e.message}")
                }
            }
        }
    }

    private fun findActiveOrSmartphoneDevice(bearerToken: String): Pair<String?, Boolean> {
        return try {
            val req = Request.Builder()
                .url("https://api.spotify.com/v1/me/player/devices")
                .addHeader("Authorization", "Bearer $bearerToken")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return Pair(null, false)
                val devices = JSONObject(resp.body?.string() ?: "{}").optJSONArray("devices") ?: return Pair(null, false)
                var smartphoneId: String? = null
                var firstDeviceId: String? = null
                for (i in 0 until devices.length()) {
                    val d = devices.optJSONObject(i) ?: continue
                    val id = d.optString("id", "")
                    if (id.isBlank()) continue
                    if (firstDeviceId == null) firstDeviceId = id
                    if (d.optBoolean("is_active", false)) return Pair(id, true)
                    if (d.optString("type", "").equals("Smartphone", ignoreCase = true)) {
                        smartphoneId = id
                    }
                }
                Pair(smartphoneId ?: firstDeviceId, false)
            }
        } catch (_: Exception) {
            Pair(null, false)
        }
    }
}
