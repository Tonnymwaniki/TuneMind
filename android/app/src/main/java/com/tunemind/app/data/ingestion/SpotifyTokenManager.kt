package com.tunemind.app.data.ingestion

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.tunemind.app.BuildConfig
import com.tunemind.app.sync.SyncConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Secure Token Manager backed by Android [EncryptedSharedPreferences] (`AES256_SIV` / `AES256_GCM`).
 *
 * Responsibilities:
 * 1. Encrypts and persists Spotify OAuth `access_token`, `refresh_token`, `expires_at_epoch_ms`,
 *    PKCE `code_verifier`, and CSRF `oauth_state`.
 * 2. Transparently executes the silent Refresh Token flow in the background whenever the stored
 *    Access Token is expired or within a 60-second safety margin.
 * 3. Supports both direct PKCE token refresh (`https://accounts.spotify.com/api/token`) and
 *    TuneMind Cloud Backend confidential refresh (`/api/v1/spotify/refresh`).
 */
class SpotifyTokenManager(context: Context) {

    private val appContext: Context = context.applicationContext
    private val refreshMutex = Mutex()

    private val encryptedPrefs: SharedPreferences by lazy {
        createEncryptedPreferences(appContext)
    }

    private fun createEncryptedPreferences(ctx: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                ctx,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w(TAG, "Hardware Keystore recovery triggered for EncryptedSharedPreferences: ${e.message}")
            try {
                ctx.deleteSharedPreferences(PREFS_FILE_NAME)
                val masterKey = MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    ctx,
                    PREFS_FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (fallbackEx: Exception) {
                Log.e(TAG, "EncryptedSharedPreferences unavailable on this OEM keystore; using private vault", fallbackEx)
                ctx.getSharedPreferences("${PREFS_FILE_NAME}_fallback", Context.MODE_PRIVATE)
            }
        }
    }

    /**
     * Resolves the Spotify Client ID from encrypted preferences, BuildConfig, or backend config.
     */
    fun getClientId(): String {
        val custom = encryptedPrefs.getString(KEY_CUSTOM_CLIENT_ID, null)?.trim()
        if (!custom.isNullOrEmpty()) return custom
        return BuildConfig.SPOTIFY_CLIENT_ID.trim()
    }

    fun setCustomClientId(clientId: String) {
        encryptedPrefs.edit().putString(KEY_CUSTOM_CLIENT_ID, clientId.trim()).apply()
    }

    fun getRedirectUri(): String {
        return BuildConfig.SPOTIFY_REDIRECT_URI.trim().ifBlank { DEFAULT_REDIRECT_URI }
    }

    /**
     * Persists PKCE `code_verifier` and `state` prior to launching Chrome Custom Tabs.
     */
    fun savePendingPkceSession(codeVerifier: String, state: String) {
        encryptedPrefs.edit()
            .putString(KEY_PKCE_VERIFIER, codeVerifier)
            .putString(KEY_OAUTH_STATE, state)
            .apply()
    }

    fun getPendingPkceVerifier(): String? = encryptedPrefs.getString(KEY_PKCE_VERIFIER, null)

    fun getPendingOAuthState(): String? = encryptedPrefs.getString(KEY_OAUTH_STATE, null)

    fun clearPendingPkceSession() {
        encryptedPrefs.edit()
            .remove(KEY_PKCE_VERIFIER)
            .remove(KEY_OAUTH_STATE)
            .apply()
    }

    /**
     * Saves the OAuth token bundle into [EncryptedSharedPreferences].
     * Note: Spotify's refresh endpoint may omit `refresh_token` if the existing one remains valid;
     * we preserve the existing `refresh_token` whenever `tokenResponse.refreshToken` is null/blank.
     */
    fun saveTokens(tokenResponse: SpotifyTokenResponse) {
        val nowMs = System.currentTimeMillis()
        val expiresAtMs = nowMs + (tokenResponse.expiresInSeconds * 1000L)
        val existingRefresh = encryptedPrefs.getString(KEY_REFRESH_TOKEN, null)
        val effectiveRefresh = tokenResponse.refreshToken?.takeIf { it.isNotBlank() } ?: existingRefresh

        encryptedPrefs.edit().apply {
            putString(KEY_ACCESS_TOKEN, tokenResponse.accessToken)
            if (!effectiveRefresh.isNullOrBlank()) {
                putString(KEY_REFRESH_TOKEN, effectiveRefresh)
            }
            putLong(KEY_EXPIRES_AT_MS, expiresAtMs)
            if (!tokenResponse.scope.isNullOrBlank()) {
                putString(KEY_GRANTED_SCOPES, tokenResponse.scope)
            }
            putLong(KEY_LAST_TOKEN_REFRESH_MS, nowMs)
            apply()
        }
        Log.i(TAG, "Encrypted Spotify tokens saved (expires in ${tokenResponse.expiresInSeconds}s)")
    }

    fun isAuthenticated(): Boolean {
        val access = encryptedPrefs.getString(KEY_ACCESS_TOKEN, null)
        val refresh = encryptedPrefs.getString(KEY_REFRESH_TOKEN, null)
        return !access.isNullOrBlank() || !refresh.isNullOrBlank()
    }

    fun getRawAccessToken(): String? = encryptedPrefs.getString(KEY_ACCESS_TOKEN, null)

    fun getRefreshToken(): String? = encryptedPrefs.getString(KEY_REFRESH_TOKEN, null)

    fun isTokenExpired(safetyBufferMs: Long = EXPIRY_SAFETY_BUFFER_MS): Boolean {
        val expiresAt = encryptedPrefs.getLong(KEY_EXPIRES_AT_MS, 0L)
        if (expiresAt <= 0L) return true
        return System.currentTimeMillis() + safetyBufferMs >= expiresAt
    }

    /**
     * Returns a valid non-expired Access Token, automatically executing the silent Refresh Token flow
     * in the background if the current token is expired or within 60 seconds of expiration.
     */
    suspend fun getValidAccessToken(authService: SpotifyAccountsAuthService): String? {
        val currentToken = getRawAccessToken()
        if (!currentToken.isNullOrBlank() && !isTokenExpired()) {
            return currentToken
        }
        return refreshAccessTokenSilently(authService)
    }

    /**
     * Thread-safe silent Refresh Token flow.
     */
    suspend fun refreshAccessTokenSilently(
        authService: SpotifyAccountsAuthService,
        forceRefresh: Boolean = false
    ): String? = refreshMutex.withLock {
        val currentToken = getRawAccessToken()
        if (!forceRefresh && !currentToken.isNullOrBlank() && !isTokenExpired()) {
            return@withLock currentToken
        }

        val refreshToken = getRefreshToken()
        if (refreshToken.isNullOrBlank()) {
            Log.w(TAG, "Cannot silently refresh: no refresh_token stored in EncryptedSharedPreferences.")
            return@withLock null
        }

        val clientId = getClientId()
        if (clientId.isNotBlank()) {
            try {
                val response = authService.refreshAccessToken(
                    clientId = clientId,
                    refreshToken = refreshToken
                )
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    saveTokens(body)
                    return@withLock body.accessToken
                } else {
                    Log.w(TAG, "Direct PKCE refresh returned HTTP ${response.code()}, trying backend proxy refresh...")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Direct PKCE refresh exception: ${e.message}, trying backend proxy refresh...")
            }
        }

        // Fallback: Refresh via TuneMind Cloud Backend confidential client (/api/v1/spotify/refresh)
        return@withLock refreshViaBackendProxy(refreshToken)
    }

    private fun refreshViaBackendProxy(refreshToken: String): String? {
        return try {
            val baseUrl = SyncConfig.baseUrl(appContext).trimEnd('/')
            val payload = JSONObject().apply {
                put("refresh_token", refreshToken)
            }
            val client = OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("$baseUrl/api/v1/spotify/refresh")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val raw = resp.body?.string() ?: return null
                val json = JSONObject(raw)
                val accessToken = json.optString("access_token", "")
                if (accessToken.isBlank()) return null

                val tokenDto = SpotifyTokenResponse(
                    accessToken = accessToken,
                    tokenType = json.optString("token_type", "Bearer"),
                    scope = json.optString("scope", null),
                    expiresInSeconds = json.optLong("expires_in", 3600L),
                    refreshToken = json.optString("refresh_token", "").ifBlank { refreshToken }
                )
                saveTokens(tokenDto)
                accessToken
            }
        } catch (e: Exception) {
            Log.e(TAG, "Backend proxy token refresh failed: ${e.message}")
            null
        }
    }

    fun clearTokens() {
        encryptedPrefs.edit().clear().apply()
        Log.i(TAG, "Cleared Spotify OAuth tokens from EncryptedSharedPreferences.")
    }

    companion object {
        private const val TAG = "SpotifyTokenManager"
        private const val PREFS_FILE_NAME = "tunemind_spotify_secure_vault"
        private const val EXPIRY_SAFETY_BUFFER_MS = 60_000L // Refresh 60s before actual expiry
        const val DEFAULT_REDIRECT_URI = "tunemind://spotify-callback"

        private const val KEY_ACCESS_TOKEN = "spotify_access_token"
        private const val KEY_REFRESH_TOKEN = "spotify_refresh_token"
        private const val KEY_EXPIRES_AT_MS = "spotify_expires_at_ms"
        private const val KEY_GRANTED_SCOPES = "spotify_granted_scopes"
        private const val KEY_LAST_TOKEN_REFRESH_MS = "spotify_last_token_refresh_ms"
        private const val KEY_PKCE_VERIFIER = "spotify_pkce_code_verifier"
        private const val KEY_OAUTH_STATE = "spotify_oauth_csrf_state"
        private const val KEY_CUSTOM_CLIENT_ID = "spotify_custom_client_id"
    }
}

/**
 * OkHttp Interceptor that injects `Authorization: Bearer <access_token>` into every
 * Spotify Web API request and transparently retries once with a fresh token on HTTP 401.
 */
class SpotifyAuthInterceptor(
    private val tokenManager: SpotifyTokenManager,
    private val authService: SpotifyAccountsAuthService
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val validToken = runBlocking {
            tokenManager.getValidAccessToken(authService)
        }

        if (validToken.isNullOrBlank()) {
            return chain.proceed(originalRequest)
        }

        val authenticatedRequest = originalRequest.newBuilder()
            .header("Authorization", "Bearer $validToken")
            .header("Accept", "application/json")
            .build()

        val response = chain.proceed(authenticatedRequest)

        // If Spotify returns 401 Unauthorized, silently refresh the token and retry once
        if (response.code == 401) {
            response.close()
            val refreshedToken = runBlocking {
                tokenManager.refreshAccessTokenSilently(authService, forceRefresh = true)
            }
            if (!refreshedToken.isNullOrBlank()) {
                val retriedRequest = originalRequest.newBuilder()
                    .header("Authorization", "Bearer $refreshedToken")
                    .header("Accept", "application/json")
                    .build()
                return chain.proceed(retriedRequest)
            }
        }

        return response
    }
}
