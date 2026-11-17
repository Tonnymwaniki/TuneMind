package com.tunemind.app.data.ingestion

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.tunemind.app.data.local.EventBufferRepository
import com.tunemind.app.data.local.TuneMindDatabase
import com.tunemind.app.domain.event.ObservationEventType
import com.tunemind.app.domain.model.PlaybackObservation
import com.tunemind.app.sync.SyncConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed class SpotifyIngestionSyncResult {
    data class Success(
        val ingestedRecentlyPlayedCount: Int,
        val currentlyPlayingTrack: SpotifyListeningHistoryEntity?,
        val totalCachedTracks: Int
    ) : SpotifyIngestionSyncResult()

    data class Unauthenticated(val message: String) : SpotifyIngestionSyncResult()
    data class TransientFailure(val code: Int?, val reason: String) : SpotifyIngestionSyncResult()
}

sealed class PlaylistTracksLoadResult {
    data class Success(val tracks: List<SpotifyPlaylistTrackEntry>) : PlaylistTracksLoadResult()
    data class Error(val message: String, val isRestrictedBySpotify: Boolean = false) : PlaylistTracksLoadResult()
}

/**
 * End-to-End Spotify OAuth 2.0 Authorization Code + PKCE Repository & Local-First Ingestion Engine.
 *
 * Scopes requested:
 * - `user-read-recently-played`
 * - `user-read-currently-playing`
 * - `user-top-read`
 */
class SpotifyOAuthRepository(context: Context) {

    private val appContext: Context = context.applicationContext
    val tokenManager = SpotifyTokenManager(appContext)
    private val database = TuneMindDatabase.getInstance(appContext)
    private val historyDao: SpotifyListeningHistoryDao = database.spotifyListeningHistoryDao()
    private val libraryDao: SpotifyLibraryDao = database.spotifyLibraryDao()
    private val eventBufferRepo = EventBufferRepository(database.eventQueueDao())
    private val authService: SpotifyAccountsAuthService = SpotifyApiFactory.accountsAuthService
    private val webApiService: SpotifyWebApiService = SpotifyApiFactory.createWebApiService(tokenManager)
    private val authManager = SpotifyAuthManager(appContext)

    private val _isConnectedFlow = MutableStateFlow(tokenManager.isAuthenticated() || authManager.isAuthenticated())
    val isConnectedFlow: StateFlow<Boolean> = _isConnectedFlow.asStateFlow()

    private val _currentlyPlayingLiveState = MutableStateFlow<SpotifyLiveTrackState?>(null)
    val currentlyPlayingLiveState: StateFlow<SpotifyLiveTrackState?> = _currentlyPlayingLiveState.asStateFlow()

    private val _needsLibraryScopeUpgrade = MutableStateFlow(
        (tokenManager.isAuthenticated() || authManager.isAuthenticated()) && !authManager.hasExtendedLibraryScopes()
    )
    val needsLibraryScopeUpgrade: StateFlow<Boolean> = _needsLibraryScopeUpgrade.asStateFlow()

    fun hasExtendedLibraryScopes(): Boolean = authManager.hasExtendedLibraryScopes()

    fun refreshScopeStatus() {
        val authed = tokenManager.isAuthenticated() || authManager.isAuthenticated()
        _isConnectedFlow.value = authed
        _needsLibraryScopeUpgrade.value = authed && !authManager.hasExtendedLibraryScopes()
    }

    /**
     * Local-First reactive streams directly from SQLite Room cache.
     * The UI subscribes to these Flows so screens render in < 2ms without waiting on network calls.
     */
    fun observeLocalRecentlyPlayed(limit: Int = 10_000): Flow<List<SpotifyListeningHistoryEntity>> =
        historyDao.observeRecentHistory(limit)

    fun observeLocalAggregatedTracks(limit: Int = 100): Flow<List<SpotifyTrackAggregate>> =
        historyDao.observeAggregatedTracks(limit)

    fun observeLikedTracks(limit: Int = 150): Flow<List<SpotifyLikedTrackEntity>> =
        libraryDao.observeLikedTracks(limit)

    fun observePlaylists(limit: Int = 60): Flow<List<SpotifyPlaylistEntity>> =
        libraryDao.observePlaylists(limit)

    fun observePodcasts(limit: Int = 100): Flow<List<SpotifyPodcastEntity>> =
        libraryDao.observePodcasts(limit)

    // ========================================================================
    // 1. CHROME CUSTOM TABS OAUTH 2.0 AUTHORIZATION CODE + PKCE INTENT FLOW
    // ========================================================================

    /**
     * Launches the Spotify OAuth 2.0 Authorization Code flow inside a Custom Chrome Tab.
     * Requests scopes including library, playlists, and podcast playback positions.
     */
    fun launchAuthorizationFlow(activityOrContext: Context, overrideClientId: String? = null) {
        if (!overrideClientId.isNullOrBlank()) {
            tokenManager.setCustomClientId(overrideClientId.trim())
        }
        authManager.launchSpotifyAuth(activityOrContext)
    }

    /**
     * Inspects an incoming Activity Intent for `tunemind://spotify-auth-callback?code=...`
     * (or `tunemind://spotify-callback?code=...`), exchanges the Authorization Code for an
     * Access Token + Refresh Token, stores them in [EncryptedSharedPreferences], and kicks off an immediate Room sync.
     */
    suspend fun handleAuthRedirectIntent(intent: Intent?): Boolean = withContext(Dispatchers.IO) {
        val data: Uri = intent?.data ?: return@withContext false
        if (data.scheme != "tunemind" ||
            (data.host != "spotify-auth-callback" && data.host != "spotify-callback")
        ) {
            return@withContext false
        }

        val error = data.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            Log.e(TAG, "Spotify OAuth authorization declined or errored: $error")
            tokenManager.clearPendingPkceSession()
            return@withContext false
        }

        val authorizationCode = data.getQueryParameter("code")
        if (authorizationCode.isNullOrBlank()) {
            Log.w(TAG, "Spotify callback URI did not contain an authorization code.")
            return@withContext false
        }

        val codeVerifier = tokenManager.getPendingPkceVerifier() ?: ""
        val exchanged = exchangeCodeForTokens(authorizationCode, codeVerifier)
        tokenManager.clearPendingPkceSession()

        if (exchanged) {
            refreshScopeStatus()
            // Schedule 15-minute periodic WorkManager sync + trigger immediate first sync into Room
            SpotifyRecentlyPlayedSyncWorker.schedulePeriodic15MinSync(appContext)
            syncRecentlyPlayedToLocalRoom()
            syncFullSpotifyLibraryToLocalRoom(forcePlaylistTracks = true)
            runCatching {
                val visualRepo = SpotifyVisualArtworkRepository.getInstance(appContext)
                visualRepo.syncUserProfileAndTasteIntelligence(force = true)
                visualRepo.syncFollowedNewReleases(force = true)
            }
        }
        exchanged
    }

    private suspend fun exchangeCodeForTokens(code: String, codeVerifier: String): Boolean {
        // 1. Primary: Direct Basic Auth (CLIENT_ID:CLIENT_SECRET) exchange via https://accounts.spotify.com/api/token
        if (authManager.exchangeCodeForTokens(code)) {
            return true
        }

        val clientId = tokenManager.getClientId()
        val redirectUri = tokenManager.getRedirectUri()

        if (clientId.isNotBlank() && codeVerifier.isNotBlank()) {
            try {
                val response = authService.exchangeAuthorizationCode(
                    clientId = clientId,
                    code = code,
                    redirectUri = redirectUri,
                    codeVerifier = codeVerifier
                )
                if (response.isSuccessful && response.body() != null) {
                    tokenManager.saveTokens(response.body()!!)
                    return true
                } else {
                    Log.w(TAG, "Direct PKCE token exchange returned HTTP ${response.code()}, trying backend exchange...")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Direct PKCE token exchange failed: ${e.message}, trying backend exchange...")
            }
        }

        // Fallback: Exchange via TuneMind Cloud Backend (/api/v1/spotify/token)
        return try {
            val baseUrl = SyncConfig.baseUrl(appContext).trimEnd('/')
            val payload = JSONObject().apply {
                put("code", code)
                put("redirect_uri", redirectUri)
                put("code_verifier", codeVerifier)
            }
            val client = OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("$baseUrl/api/v1/spotify/token")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val raw = resp.body?.string() ?: return false
                val json = JSONObject(raw)
                val accessToken = json.optString("access_token", "")
                if (accessToken.isBlank()) return false

                tokenManager.saveTokens(
                    SpotifyTokenResponse(
                        accessToken = accessToken,
                        tokenType = json.optString("token_type", "Bearer"),
                        scope = json.optString("scope", REQUIRED_SPOTIFY_SCOPES),
                        expiresInSeconds = json.optLong("expires_in", 3600L),
                        refreshToken = json.optString("refresh_token", null)
                    )
                )
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Backend token exchange failed: ${e.message}")
            false
        }
    }

    // ========================================================================
    // 2. LOCAL-FIRST ROOM INGESTION ENGINE (/v1/me/player/recently-played)
    // ========================================================================

    /**
     * Fetches the user's recently played tracks and live currently-playing state from Spotify Web API.
     *
     * CRITICAL ANTI-DUPLICATE DESIGN:
     * - Legacy `CURRENTLY_PLAYING` polling rows in Room are purged (`deletePollingDuplicates()`).
     * - Live currently-playing state updates ONLY [currentlyPlayingLiveState] in memory, never
     *   polluting `spotify_listening_history` or `event_queue` on every poll.
     * - Only verified `RECENTLY_PLAYED` completions are stored in `spotify_listening_history`.
     */
    suspend fun syncRecentlyPlayedToLocalRoom(): SpotifyIngestionSyncResult = withContext(Dispatchers.IO) {
        if (!tokenManager.isAuthenticated() && !authManager.isAuthenticated()) {
            _isConnectedFlow.value = false
            return@withContext SpotifyIngestionSyncResult.Unauthenticated("Spotify OAuth not yet connected.")
        }

        val validToken = authManager.getValidAccessToken() ?: tokenManager.getValidAccessToken(authService)
        if (validToken.isNullOrBlank()) {
            return@withContext SpotifyIngestionSyncResult.Unauthenticated("Access token expired and could not be refreshed.")
        }
        refreshScopeStatus()

        // Purge any legacy CURRENTLY_PLAYING poll duplicates from earlier app versions
        runCatching { historyDao.deletePollingDuplicates() }

        val bearerHeader = "Bearer $validToken"
        val nowMs = System.currentTimeMillis()
        val telemetryItemsToInsert = mutableListOf<TrackHistoryTelemetryItem>()
        val entitiesToInsert = mutableListOf<SpotifyListeningHistoryEntity>()

        // A. Fetch GET https://api.spotify.com/v1/me/player/recently-played (up to 50 most recent tracks)
        try {
            val latestCursorMs = historyDao.getLatestPlayedEpochMs()
            val response = webApiService.getRecentlyPlayed(
                authHeader = bearerHeader,
                limit = 50,
                afterEpochMs = latestCursorMs
            )

            if (response.isSuccessful) {
                val items = response.body()?.items.orEmpty()
                for (dto in items) {
                    telemetryItemsToInsert.add(dto.toTrackHistoryTelemetryItem(nowMs))
                    entitiesToInsert.add(dto.toHistoryEntity(nowMs))
                }
                if (items.isNotEmpty()) {
                    historyDao.deleteSyntheticTopTracks()
                }
                Log.i(TAG, "Fetched ${items.size} recently-played tracks from Spotify Web API (after=$latestCursorMs)")
            } else if (response.code() in 500..599 || response.code() == 429) {
                return@withContext SpotifyIngestionSyncResult.TransientFailure(
                    code = response.code(),
                    reason = "Spotify Web API temporary error HTTP ${response.code()}"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching /v1/me/player/recently-played: ${e.message}")
            return@withContext SpotifyIngestionSyncResult.TransientFailure(
                code = null,
                reason = e.message ?: "Network exception"
            )
        }

        // B. Fetch /v1/me/player/currently-playing?additional_types=track,episode
        // Update ONLY reactive in-memory live state — never write repeated polling rows into play history!
        var liveHistoryEntity: SpotifyListeningHistoryEntity? = null
        try {
            val currentResp = webApiService.getCurrentlyPlaying(
                authHeader = bearerHeader,
                additionalTypes = "track,episode"
            )
            if (currentResp.isSuccessful && currentResp.code() == 200) {
                val body = currentResp.body()
                val liveState = body?.toLiveTrackState(nowMs)
                _currentlyPlayingLiveState.value = liveState
                val trackDto = body?.item
                if (trackDto != null && trackDto.name.isNotBlank()) {
                    val stamp = if (body.timestampMs > 0L) body.timestampMs else nowMs
                    liveHistoryEntity = trackDto.toHistoryEntity(
                        playedAtMs = stamp,
                        ingestionSource = "LIVE_SNAPSHOT",
                        contextUri = body.context?.uri
                    )
                }
            } else if (currentResp.code() == 204) {
                _currentlyPlayingLiveState.value = _currentlyPlayingLiveState.value?.copy(isPlaying = false)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Currently-playing fetch note: ${e.message}")
        }

        // C. If user truly has zero real RECENTLY_PLAYED tracks, seed once from /v1/me/top/tracks
        try {
            val existingReal = historyDao.getRecentHistorySnapshot(limit = 10)
            if (existingReal.isEmpty() && entitiesToInsert.isEmpty()) {
                val topResp = webApiService.getTopTracks(authHeader = bearerHeader, timeRange = "short_term", limit = 20)
                if (topResp.isSuccessful) {
                    val topItems = topResp.body()?.items.orEmpty()
                    topItems.forEachIndexed { idx, trackDto ->
                        val syntheticTimestamp = nowMs - ((idx + 1) * 3_600_000L)
                        entitiesToInsert.add(
                            trackDto.toHistoryEntity(
                                playedAtMs = syntheticTimestamp,
                                ingestionSource = "TOP_TRACKS"
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Top-tracks bootstrap note: ${e.message}")
        }

        // D. Insert into Local-First Room Database while ignoring duplicates
        if (telemetryItemsToInsert.isNotEmpty()) {
            historyDao.insertTelemetryItemsIgnoreDuplicates(telemetryItemsToInsert)
        }
        val recentlyPlayedEntities = entitiesToInsert.filter { it.ingestionSource == "RECENTLY_PLAYED" }
        if (entitiesToInsert.isNotEmpty()) {
            historyDao.insertHistoryIgnoreDuplicates(entitiesToInsert)
        }
        if (recentlyPlayedEntities.isNotEmpty()) {
            mirrorIntoEventQueueForUnifiedIntelligence(recentlyPlayedEntities)
        }

        // Retain up to 5 years of Spotify play history locally so All-Time stats remain distinct from 30 Days
        val retentionCutoffMs = nowMs - (1825L * 24L * 3600L * 1000L)
        historyDao.pruneHistoryOlderThan(retentionCutoffMs)

        // E. Push synced Spotify tracks to Live Supabase Taste Genome for long-term intelligence growth
        com.tunemind.app.data.supabase.SupabaseIntelligenceSyncManager.syncSpotifyHistoryToSupabase(appContext)

        val totalCached = historyDao.getAggregatedTracksSnapshot(limit = 100).size
        return@withContext SpotifyIngestionSyncResult.Success(
            ingestedRecentlyPlayedCount = recentlyPlayedEntities.size,
            currentlyPlayingTrack = liveHistoryEntity,
            totalCachedTracks = totalCached
        )
    }

    /**
     * Fetches Liked Songs (`/v1/me/tracks`), Playlists (`/v1/me/playlists` + tracks),
     * and Podcasts (`/v1/me/shows`, `/v1/me/episodes`) into local Room tables.
     */
    suspend fun syncFullSpotifyLibraryToLocalRoom(
        forcePlaylistTracks: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        if (!tokenManager.isAuthenticated() && !authManager.isAuthenticated()) {
            return@withContext false
        }
        val validToken = authManager.getValidAccessToken() ?: tokenManager.getValidAccessToken(authService)
        if (validToken.isNullOrBlank()) return@withContext false

        val bearerHeader = "Bearer $validToken"
        val nowMs = System.currentTimeMillis()
        var hitPermissionForbidden = false

        // 1. Liked Songs (up to 100 most recently liked)
        try {
            val likedEntities = mutableListOf<SpotifyLikedTrackEntity>()
            val page1 = webApiService.getSavedTracks(authHeader = bearerHeader, limit = 50, offset = 0)
            if (page1.isSuccessful) {
                val body1 = page1.body()
                body1?.items.orEmpty().mapNotNullTo(likedEntities) { it.toLikedTrackEntity(nowMs) }
                if ((body1?.total ?: 0) > 50) {
                    val page2 = webApiService.getSavedTracks(authHeader = bearerHeader, limit = 50, offset = 50)
                    if (page2.isSuccessful) {
                        page2.body()?.items.orEmpty().mapNotNullTo(likedEntities) { it.toLikedTrackEntity(nowMs) }
                    }
                }
                if (likedEntities.isNotEmpty()) {
                    libraryDao.clearLikedTracks()
                    libraryDao.upsertLikedTracks(likedEntities)
                }
            } else if (page1.code() == 403 || page1.code() == 401) {
                hitPermissionForbidden = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Liked tracks sync note: ${e.message}")
        }

        // 2. Playlists (+ tracks for top 10 playlists, preserving cached tracks on partial/background sync)
        try {
            val existingById = runCatching {
                libraryDao.getAllPlaylistsSnapshot().associateBy { it.playlistId }
            }.getOrDefault(emptyMap())
            val plResp = webApiService.getUserPlaylists(authHeader = bearerHeader, limit = 30, offset = 0)
            if (plResp.isSuccessful) {
                val rawPlaylists = plResp.body()?.items.orEmpty()
                val playlistEntities = mutableListOf<SpotifyPlaylistEntity>()
                rawPlaylists.forEachIndexed { idx, pDto ->
                    val existing = existingById[pDto.id]
                    val cachedTracks = existing?.decodeTracks().orEmpty()
                    val tracksList = if (forcePlaylistTracks && idx < 10 && pDto.id.isNotBlank()) {
                        when (val res = fetchPlaylistTracksDetailed(pDto.id, bearerHeader)) {
                            is PlaylistTracksLoadResult.Success -> res.tracks.ifEmpty { cachedTracks }
                            is PlaylistTracksLoadResult.Error -> cachedTracks
                        }
                    } else {
                        cachedTracks
                    }
                    pDto.toPlaylistEntity(
                        tracks = tracksList,
                        nowMs = nowMs,
                        existingTrackCount = existing?.trackCount ?: 0
                    )?.let { entity ->
                        playlistEntities.add(entity)
                    }
                }
                if (playlistEntities.isNotEmpty()) {
                    libraryDao.upsertPlaylists(playlistEntities)
                }
            } else if (plResp.code() == 403 || plResp.code() == 401) {
                hitPermissionForbidden = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Playlists sync note: ${e.message}")
        }

        // 3. Podcasts (Saved Shows + Saved / Latest Episodes)
        try {
            val podcastEntities = mutableListOf<SpotifyPodcastEntity>()
            val showsResp = webApiService.getSavedShows(authHeader = bearerHeader, limit = 25)
            val savedShows = if (showsResp.isSuccessful) showsResp.body()?.items.orEmpty() else emptyList()
            if (showsResp.code() == 403) hitPermissionForbidden = true

            for (showItem in savedShows) {
                val show = showItem.show ?: continue
                if (show.id.isBlank() || show.name.isBlank()) continue
                podcastEntities.add(
                    SpotifyPodcastEntity(
                        itemId = "show_${show.id}",
                        showId = show.id,
                        showName = show.name.trim(),
                        publisher = show.publisher?.trim().orEmpty(),
                        episodeTitle = "${show.totalEpisodes} episodes",
                        description = show.description?.trim().orEmpty(),
                        uri = show.uri.ifBlank { "spotify:show:${show.id}" },
                        imageUrl = show.images?.firstOrNull()?.url,
                        durationMs = 0L,
                        resumePositionMs = 0L,
                        fullyPlayed = false,
                        releaseDate = "",
                        totalEpisodes = show.totalEpisodes,
                        isShowSummary = true,
                        addedAtEpochMs = parseSpotifyIsoTimestampMs(showItem.addedAtIso)
                    )
                )
            }

            val epResp = webApiService.getSavedEpisodes(authHeader = bearerHeader, limit = 30)
            val savedEpisodes = if (epResp.isSuccessful) epResp.body()?.items.orEmpty() else emptyList()
            for (epItem in savedEpisodes) {
                val ep = epItem.episode ?: continue
                if (ep.id.isBlank() || ep.name.isBlank()) continue
                podcastEntities.add(
                    SpotifyPodcastEntity(
                        itemId = "ep_${ep.id}",
                        showId = ep.show?.id ?: "",
                        showName = ep.show?.name?.trim()?.ifBlank { "Podcast" } ?: "Podcast",
                        publisher = ep.show?.publisher?.trim().orEmpty(),
                        episodeTitle = ep.name.trim(),
                        description = ep.description?.trim().orEmpty(),
                        uri = ep.uri.ifBlank { "spotify:episode:${ep.id}" },
                        imageUrl = ep.images?.firstOrNull()?.url ?: ep.show?.images?.firstOrNull()?.url,
                        durationMs = ep.durationMs,
                        resumePositionMs = ep.resumePoint?.resumePositionMs ?: 0L,
                        fullyPlayed = ep.resumePoint?.fullyPlayed == true,
                        releaseDate = ep.releaseDate.orEmpty(),
                        totalEpisodes = ep.show?.totalEpisodes ?: 0,
                        isShowSummary = false,
                        addedAtEpochMs = parseSpotifyIsoTimestampMs(epItem.addedAtIso)
                    )
                )
            }

            // If user follows shows but hasn't saved individual episodes, pull latest 4 episodes per top show
            if (savedEpisodes.isEmpty() && savedShows.isNotEmpty()) {
                for (showItem in savedShows.take(4)) {
                    val show = showItem.show ?: continue
                    if (show.id.isBlank()) continue
                    try {
                        val showEpResp = webApiService.getShowEpisodes(showId = show.id, authHeader = bearerHeader, limit = 5)
                        if (showEpResp.isSuccessful) {
                            showEpResp.body()?.items.orEmpty().forEach { ep ->
                                if (ep.id.isNotBlank() && ep.name.isNotBlank()) {
                                    podcastEntities.add(
                                        SpotifyPodcastEntity(
                                            itemId = "ep_${ep.id}",
                                            showId = show.id,
                                            showName = show.name.trim(),
                                            publisher = show.publisher?.trim().orEmpty(),
                                            episodeTitle = ep.name.trim(),
                                            description = ep.description?.trim().orEmpty(),
                                            uri = ep.uri.ifBlank { "spotify:episode:${ep.id}" },
                                            imageUrl = ep.images?.firstOrNull()?.url ?: show.images?.firstOrNull()?.url,
                                            durationMs = ep.durationMs,
                                            resumePositionMs = ep.resumePoint?.resumePositionMs ?: 0L,
                                            fullyPlayed = ep.resumePoint?.fullyPlayed == true,
                                            releaseDate = ep.releaseDate.orEmpty(),
                                            totalEpisodes = show.totalEpisodes,
                                            isShowSummary = false,
                                            addedAtEpochMs = nowMs
                                        )
                                    )
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            if (podcastEntities.isNotEmpty()) {
                libraryDao.clearPodcasts()
                libraryDao.upsertPodcasts(podcastEntities)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Podcasts sync note: ${e.message}")
        }

        runCatching {
            val visualRepo = SpotifyVisualArtworkRepository.getInstance(appContext)
            visualRepo.syncUserProfileAndTasteIntelligence(force = false)
            visualRepo.syncFollowedNewReleases(force = false)
        }

        _needsLibraryScopeUpgrade.value = hitPermissionForbidden || !authManager.hasExtendedLibraryScopes()
        !hitPermissionForbidden
    }

    suspend fun loadPlaylistTracksIfNeeded(playlistId: String): List<SpotifyPlaylistTrackEntry> {
        return when (val res = loadPlaylistTracksDetailed(playlistId)) {
            is PlaylistTracksLoadResult.Success -> res.tracks
            is PlaylistTracksLoadResult.Error -> emptyList()
        }
    }

    suspend fun loadPlaylistTracksDetailed(playlistId: String): PlaylistTracksLoadResult =
        withContext(Dispatchers.IO) {
            var validToken = authManager.getValidAccessToken() ?: tokenManager.getValidAccessToken(authService)
                ?: return@withContext PlaylistTracksLoadResult.Error(
                    "Connect Spotify to view songs inside this playlist."
                )

            var result = fetchPlaylistTracksDetailed(playlistId, "Bearer $validToken")
            if (result is PlaylistTracksLoadResult.Error && result.message.contains("expired", ignoreCase = true)) {
                val refreshed = authManager.refreshAccessTokenIfNeeded(forceRefresh = true)
                    ?: tokenManager.refreshAccessTokenSilently(authService, forceRefresh = true)
                if (!refreshed.isNullOrBlank()) {
                    validToken = refreshed
                    result = fetchPlaylistTracksDetailed(playlistId, "Bearer $validToken")
                }
            }

            if (result is PlaylistTracksLoadResult.Success && result.tracks.isNotEmpty()) {
                libraryDao.updatePlaylistTracks(
                    playlistId = playlistId,
                    tracksJson = encodePlaylistTracksJson(result.tracks),
                    trackCount = result.tracks.size
                )
            }
            result
        }

    private suspend fun fetchPlaylistTracksDetailed(
        playlistId: String,
        bearerHeader: String
    ): PlaylistTracksLoadResult {
        return try {
            var usedItemsEndpoint = true
            var activeResp = webApiService.getPlaylistItems(
                playlistId = playlistId,
                authHeader = bearerHeader,
                limit = 50,
                offset = 0
            )
            if (!activeResp.isSuccessful) {
                val itemsCode = activeResp.code()
                Log.w(TAG, "GET /v1/playlists/$playlistId/items returned HTTP $itemsCode; trying /tracks fallback")
                val legacyResp = webApiService.getPlaylistTracks(
                    playlistId = playlistId,
                    authHeader = bearerHeader,
                    limit = 50,
                    offset = 0
                )
                if (legacyResp.isSuccessful) {
                    usedItemsEndpoint = false
                    activeResp = legacyResp
                } else {
                    val code = legacyResp.code()
                    Log.w(TAG, "GET /v1/playlists/$playlistId/tracks also returned HTTP $code")
                    return when {
                        code == 401 || itemsCode == 401 -> PlaylistTracksLoadResult.Error(
                            "Spotify session expired. Tap Sync or reconnect Spotify to reload."
                        )
                        code == 403 || itemsCode == 403 -> {
                            if (!authManager.hasExtendedLibraryScopes()) {
                                _needsLibraryScopeUpgrade.value = true
                                PlaylistTracksLoadResult.Error(
                                    "Spotify playlist permission update required. Tap 'Update Spotify Permissions' above."
                                )
                            } else {
                                PlaylistTracksLoadResult.Error(
                                    "Spotify restricts reading songs inside playlists you don't own (such as editorial mixes) in Development Mode. You can still play this playlist directly.",
                                    isRestrictedBySpotify = true
                                )
                            }
                        }
                        else -> PlaylistTracksLoadResult.Error(
                            "Could not load playlist songs from Spotify (HTTP $code). Tap Retry."
                        )
                    }
                }
            }

            val page1Body = activeResp.body()
            val allEntries = parsePlaylistItems(page1Body?.items.orEmpty()).toMutableList()

            val totalReported = page1Body?.total ?: allEntries.size
            if (totalReported > 50 || !page1Body?.next.isNullOrBlank()) {
                val page2Resp = if (usedItemsEndpoint) {
                    webApiService.getPlaylistItems(playlistId = playlistId, authHeader = bearerHeader, limit = 50, offset = 50)
                } else {
                    webApiService.getPlaylistTracks(playlistId = playlistId, authHeader = bearerHeader, limit = 50, offset = 50)
                }
                if (page2Resp.isSuccessful) {
                    allEntries.addAll(parsePlaylistItems(page2Resp.body()?.items.orEmpty()))
                }
            }

            PlaylistTracksLoadResult.Success(allEntries)
        } catch (e: Exception) {
            Log.w(TAG, "fetchPlaylistTracksDetailed error for $playlistId: ${e.message}")
            PlaylistTracksLoadResult.Error("Network issue while loading playlist songs. Tap Retry.")
        }
    }

    private fun parsePlaylistItems(items: List<SpotifyPlaylistTrackItemDto>): List<SpotifyPlaylistTrackEntry> {
        return items.mapNotNull { item ->
            val t = item.resolvedMedia() ?: return@mapNotNull null
            if (t.name.isBlank()) return@mapNotNull null
            val artist = t.artists.firstOrNull()?.name?.takeIf { it.isNotBlank() }
                ?: t.show?.name?.takeIf { it.isNotBlank() }
                ?: t.show?.publisher?.takeIf { it.isNotBlank() }
                ?: "Unknown Artist"
            val album = t.album?.name?.trim()?.takeIf { it.isNotBlank() }
                ?: t.show?.publisher?.trim().orEmpty()
            val artUrl = t.album?.images?.firstOrNull()?.url
                ?: t.images?.firstOrNull()?.url
                ?: t.show?.images?.firstOrNull()?.url
            val id = t.id.orEmpty().ifBlank { "${t.name.lowercase()}_${artist.lowercase()}" }
            val fallbackUri = if (t.show != null && t.artists.isEmpty()) "spotify:episode:$id" else "spotify:track:$id"
            SpotifyPlaylistTrackEntry(
                trackId = id,
                title = t.name.trim(),
                artist = artist.trim(),
                album = album,
                spotifyUri = t.uri.ifBlank { fallbackUri },
                durationMs = t.durationMs,
                albumArtUrl = artUrl
            )
        }
    }

    /**
     * Bridges verified RECENTLY_PLAYED Spotify Web API rows into `event_queue`
     * without adding polling noise.
     */
    private suspend fun mirrorIntoEventQueueForUnifiedIntelligence(
        entities: List<SpotifyListeningHistoryEntity>
    ) {
        for (entity in entities) {
            try {
                val obs = PlaybackObservation(
                    eventId = "spotify_api_${entity.historyId}",
                    observerSessionId = "spotify_web_api_ingestion",
                    eventType = ObservationEventType.METADATA_CHANGED,
                    eventTimeMs = entity.playedAtEpochMs,
                    sourcePackage = "com.spotify.music",
                    trackId = entity.spotifyUri,
                    trackTitle = entity.trackTitle,
                    trackArtist = entity.artistName,
                    trackAlbum = entity.albumName.ifBlank { null },
                    durationMs = entity.durationMs,
                    positionMs = 0L,
                    playbackStateCode = 2,
                    playbackSpeed = 1.0f
                )
                eventBufferRepo.record(obs)
            } catch (_: Exception) {
            }
        }
    }

    // ========================================================================
    // 3. RFC 7636 PKCE CRYPTOGRAPHIC HELPERS (S256)
    // ========================================================================

    private fun generatePkceCodeVerifier(): String {
        val randomBytes = ByteArray(64)
        SecureRandom().nextBytes(randomBytes)
        return Base64.encodeToString(
            randomBytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun derivePkceCodeChallengeS256(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(
            hash,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    companion object {
        private const val TAG = "SpotifyOAuthRepo"
        private const val SPOTIFY_AUTHORIZE_ENDPOINT = "https://accounts.spotify.com/authorize"

        /**
         * Required Spotify OAuth 2.0 scopes for TuneMind's Data Ingestion & Execution Branch.
         */
        const val REQUIRED_SPOTIFY_SCOPES = SpotifyAuthManager.SCOPES

        @Volatile
        private var INSTANCE: SpotifyOAuthRepository? = null

        fun getInstance(context: Context): SpotifyOAuthRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SpotifyOAuthRepository(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}
