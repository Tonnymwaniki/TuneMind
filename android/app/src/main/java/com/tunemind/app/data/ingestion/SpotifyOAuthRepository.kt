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
    private val eventBufferRepo = EventBufferRepository(database.eventQueueDao())
    private val authService: SpotifyAccountsAuthService = SpotifyApiFactory.accountsAuthService
    private val webApiService: SpotifyWebApiService = SpotifyApiFactory.createWebApiService(tokenManager)

    private val _isConnectedFlow = MutableStateFlow(tokenManager.isAuthenticated())
    val isConnectedFlow: StateFlow<Boolean> = _isConnectedFlow.asStateFlow()

    /**
     * Local-First reactive streams directly from SQLite Room cache.
     * The UI subscribes to these Flows so screens render in < 2ms without waiting on network calls.
     */
    fun observeLocalRecentlyPlayed(limit: Int = 100): Flow<List<SpotifyListeningHistoryEntity>> =
        historyDao.observeRecentHistory(limit)

    fun observeLocalAggregatedTracks(limit: Int = 60): Flow<List<SpotifyTrackAggregate>> =
        historyDao.observeAggregatedTracks(limit)

    // ========================================================================
    // 1. CHROME CUSTOM TABS OAUTH 2.0 AUTHORIZATION CODE + PKCE INTENT FLOW
    // ========================================================================

    private val authManager = SpotifyAuthManager(appContext)

    /**
     * Launches the Spotify OAuth 2.0 Authorization Code flow inside a Custom Chrome Tab.
     * Requests scopes: `user-read-recently-played user-read-currently-playing user-top-read user-modify-playback-state`.
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
            _isConnectedFlow.value = true
            // Schedule 15-minute periodic WorkManager sync + trigger immediate first sync into Room
            SpotifyRecentlyPlayedSyncWorker.schedulePeriodic15MinSync(appContext)
            syncRecentlyPlayedToLocalRoom()
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
     * Fetches the user's recently played tracks and currently playing track from the Spotify Web API
     * and persists them directly into the local Android Room Database (`track_history_telemetry`,
     * `spotify_listening_history`, and `event_queue`) while ignoring duplicates (`OnConflictStrategy.IGNORE`).
     */
    suspend fun syncRecentlyPlayedToLocalRoom(): SpotifyIngestionSyncResult = withContext(Dispatchers.IO) {
        if (!tokenManager.isAuthenticated()) {
            _isConnectedFlow.value = false
            return@withContext SpotifyIngestionSyncResult.Unauthenticated("Spotify OAuth not yet connected.")
        }

        val validToken = authManager.getValidAccessToken() ?: tokenManager.getValidAccessToken(authService)
        if (validToken.isNullOrBlank()) {
            return@withContext SpotifyIngestionSyncResult.Unauthenticated("Access token expired and could not be refreshed.")
        }
        _isConnectedFlow.value = true

        val bearerHeader = "Bearer $validToken"
        val nowMs = System.currentTimeMillis()
        val telemetryItemsToInsert = mutableListOf<TrackHistoryTelemetryItem>()
        val entitiesToInsert = mutableListOf<SpotifyListeningHistoryEntity>()
        var currentlyPlayingEntity: SpotifyListeningHistoryEntity? = null

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

        // B. Fetch /v1/me/player/currently-playing
        try {
            val currentResp = webApiService.getCurrentlyPlaying(authHeader = bearerHeader)
            if (currentResp.isSuccessful && currentResp.code() == 200) {
                val body = currentResp.body()
                val trackDto = body?.item
                if (trackDto != null) {
                    val stamp = if ((body.timestampMs) > 0L) body.timestampMs else nowMs
                    val entity = trackDto.toHistoryEntity(
                        playedAtMs = stamp,
                        ingestionSource = "CURRENTLY_PLAYING",
                        contextUri = body.context?.uri
                    )
                    currentlyPlayingEntity = entity
                    entitiesToInsert.add(entity)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Currently-playing fetch note: ${e.message}")
        }

        // C. If local Room cache is still sparse (< 10 tracks), seed from /v1/me/top/tracks
        try {
            val existingSnapshot = historyDao.getRecentHistorySnapshot(limit = 10)
            if (existingSnapshot.size + entitiesToInsert.size < 10) {
                val topResp = webApiService.getTopTracks(authHeader = bearerHeader, timeRange = "short_term", limit = 25)
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

        // D. Insert into Local-First Room Database while ignoring duplicates (OnConflictStrategy.IGNORE)
        if (telemetryItemsToInsert.isNotEmpty()) {
            historyDao.insertTelemetryItemsIgnoreDuplicates(telemetryItemsToInsert)
        }
        if (entitiesToInsert.isNotEmpty()) {
            historyDao.insertHistoryIgnoreDuplicates(entitiesToInsert)
            mirrorIntoEventQueueForUnifiedIntelligence(entitiesToInsert)
        }

        // Keep local history lean (prune records older than 90 days)
        val ninetyDaysAgoMs = nowMs - (90L * 24L * 3600L * 1000L)
        historyDao.pruneHistoryOlderThan(ninetyDaysAgoMs)

        val totalCached = historyDao.getAggregatedTracksSnapshot(limit = 60).size
        return@withContext SpotifyIngestionSyncResult.Success(
            ingestedRecentlyPlayedCount = entitiesToInsert.size,
            currentlyPlayingTrack = currentlyPlayingEntity,
            totalCachedTracks = totalCached
        )
    }

    /**
     * Bridges newly ingested Spotify Web API rows into `event_queue` so all existing
     * TuneMind screens, Dopamine Capsule, and Claude Sonnet telemetry immediately include them.
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
                    playbackStateCode = if (entity.ingestionSource == "CURRENTLY_PLAYING") 3 else 2,
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
