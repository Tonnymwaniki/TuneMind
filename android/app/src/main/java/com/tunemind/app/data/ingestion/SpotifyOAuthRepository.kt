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

    /**
     * Launches the Spotify OAuth 2.0 Authorization Code + PKCE flow inside Chrome Custom Tabs.
     * Requests scopes: `user-read-recently-played`, `user-read-currently-playing`, `user-top-read`.
     */
    fun launchAuthorizationFlow(activityOrContext: Context, overrideClientId: String? = null) {
        if (!overrideClientId.isNullOrBlank()) {
            tokenManager.setCustomClientId(overrideClientId.trim())
        }
        val clientId = tokenManager.getClientId()
        val redirectUri = tokenManager.getRedirectUri()

        val codeVerifier = generatePkceCodeVerifier()
        val codeChallenge = derivePkceCodeChallengeS256(codeVerifier)
        val csrfState = UUID.randomUUID().toString().replace("-", "")

        tokenManager.savePendingPkceSession(codeVerifier = codeVerifier, state = csrfState)

        val authUri = Uri.parse(SPOTIFY_AUTHORIZE_ENDPOINT).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("state", csrfState)
            .appendQueryParameter("scope", REQUIRED_SPOTIFY_SCOPES)
            .appendQueryParameter("show_dialog", "false")
            .build()

        Log.i(TAG, "Launching Spotify OAuth PKCE flow via Chrome Custom Tabs: scopes=[$REQUIRED_SPOTIFY_SCOPES]")

        try {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(true)
                .build()
            customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            customTabsIntent.launchUrl(activityOrContext, authUri)
        } catch (e: Exception) {
            Log.w(TAG, "Chrome Custom Tabs unavailable, falling back to standard ACTION_VIEW browser intent: ${e.message}")
            val browserIntent = Intent(Intent.ACTION_VIEW, authUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activityOrContext.startActivity(browserIntent)
        }
    }

    /**
     * Inspects an incoming Activity Intent for `tunemind://spotify-callback?code=...&state=...`,
     * exchanges the Authorization Code for an Access Token + Refresh Token, stores them in
     * [EncryptedSharedPreferences], and kicks off an immediate Room sync.
     */
    suspend fun handleAuthRedirectIntent(intent: Intent?): Boolean = withContext(Dispatchers.IO) {
        val data: Uri = intent?.data ?: return@withContext false
        if (data.scheme != "tunemind" || data.host != "spotify-callback") {
            return@withContext false
        }

        val error = data.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            Log.e(TAG, "Spotify OAuth authorization declined or errored: $error")
            tokenManager.clearPendingPkceSession()
            return@withContext false
        }

        val authorizationCode = data.getQueryParameter("code")
        val returnedState = data.getQueryParameter("state")
        if (authorizationCode.isNullOrBlank()) {
            Log.w(TAG, "Spotify callback URI did not contain an authorization code.")
            return@withContext false
        }

        val expectedState = tokenManager.getPendingOAuthState()
        if (!expectedState.isNullOrBlank() && !returnedState.isNullOrBlank() && expectedState != returnedState) {
            Log.e(TAG, "Spotify OAuth CSRF state mismatch! Expected=$expectedState, got=$returnedState")
            tokenManager.clearPendingPkceSession()
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
     * and persists them directly into the local Android Room Database (`spotify_listening_history` + `event_queue`).
     */
    suspend fun syncRecentlyPlayedToLocalRoom(): SpotifyIngestionSyncResult = withContext(Dispatchers.IO) {
        if (!tokenManager.isAuthenticated()) {
            _isConnectedFlow.value = false
            return@withContext SpotifyIngestionSyncResult.Unauthenticated("Spotify OAuth not yet connected.")
        }

        val validToken = tokenManager.getValidAccessToken(authService)
        if (validToken.isNullOrBlank()) {
            return@withContext SpotifyIngestionSyncResult.Unauthenticated("Access token expired and could not be refreshed.")
        }

        val nowMs = System.currentTimeMillis()
        val entitiesToUpsert = mutableListOf<SpotifyListeningHistoryEntity>()
        var currentlyPlayingEntity: SpotifyListeningHistoryEntity? = null

        // A. Fetch /v1/me/player/recently-played (up to 50 most recent tracks)
        try {
            val latestCursorMs = historyDao.getLatestPlayedEpochMs()
            val response = webApiService.getRecentlyPlayed(
                limit = 50,
                afterEpochMs = latestCursorMs
            )

            if (response.isSuccessful) {
                val items = response.body()?.items.orEmpty()
                for (dto in items) {
                    entitiesToUpsert.add(dto.toHistoryEntity(nowMs))
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
            val currentResp = webApiService.getCurrentlyPlaying()
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
                    entitiesToUpsert.add(entity)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Currently-playing fetch note: ${e.message}")
        }

        // C. If local Room cache is still sparse (< 10 tracks), seed from /v1/me/top/tracks
        try {
            val existingSnapshot = historyDao.getRecentHistorySnapshot(limit = 10)
            if (existingSnapshot.size + entitiesToUpsert.size < 10) {
                val topResp = webApiService.getTopTracks(timeRange = "short_term", limit = 25)
                if (topResp.isSuccessful) {
                    val topItems = topResp.body()?.items.orEmpty()
                    topItems.forEachIndexed { idx, trackDto ->
                        val syntheticTimestamp = nowMs - ((idx + 1) * 3_600_000L)
                        entitiesToUpsert.add(
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

        // D. Persist into Local-First Room Database & mirror into EventBufferRepository
        if (entitiesToUpsert.isNotEmpty()) {
            historyDao.upsertPlayHistory(entitiesToUpsert)
            mirrorIntoEventQueueForUnifiedIntelligence(entitiesToUpsert)
        }

        // Keep local history lean (prune records older than 90 days)
        val ninetyDaysAgoMs = nowMs - (90L * 24L * 3600L * 1000L)
        historyDao.pruneHistoryOlderThan(ninetyDaysAgoMs)

        val totalCached = historyDao.getAggregatedTracksSnapshot(limit = 60).size
        return@withContext SpotifyIngestionSyncResult.Success(
            ingestedRecentlyPlayedCount = entitiesToUpsert.size,
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
            runCatching {
                val obs = PlaybackObservation(
                    eventId = "spotify_api_${entity.historyId}",
                    eventType = ObservationEventType.TRACK_CHANGED,
                    eventTimeMs = entity.playedAtEpochMs,
                    sourcePackage = "com.spotify.music",
                    trackId = entity.spotifyUri,
                    trackTitle = entity.trackTitle,
                    trackArtist = entity.artistName,
                    trackAlbum = entity.albumName.ifBlank { null },
                    durationMs = entity.durationMs,
                    positionMs = 0L,
                    playbackStateCode = if (entity.ingestionSource == "CURRENTLY_PLAYING") 3 else 2,
                    playbackSpeed = 1.0f,
                    isShuffling = null,
                    repeatMode = null,
                    observerSessionId = "spotify_web_api_ingestion"
                )
                eventBufferRepo.saveObservation(obs)
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
         * Required Spotify OAuth 2.0 scopes for TuneMind's Data Ingestion Branch.
         */
        const val REQUIRED_SPOTIFY_SCOPES =
            "user-read-recently-played user-read-currently-playing user-top-read"

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
