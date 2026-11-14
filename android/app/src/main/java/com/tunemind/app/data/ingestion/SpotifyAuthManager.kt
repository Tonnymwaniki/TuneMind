package com.tunemind.app.data.ingestion

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Frictionless Spotify OAuth 2.0 Authorization Code Manager & Encrypted Token Vault.
 *
 * Credentials & Constants:
 * - `CLIENT_ID = "dcfad6acbd594477b0705ecc1e6ace3a"`
 * - `CLIENT_SECRET = "134028eff64c4e2cafa4b1f617463650"`
 * - `REDIRECT_URI = "tunemind://spotify-auth-callback"`
 * - `SCOPES = "user-read-recently-played user-read-currently-playing user-top-read user-modify-playback-state"`
 *
 * Responsibilities:
 * 1. Opens the Spotify authorization URL (`https://accounts.spotify.com/authorize`) in a Chrome Custom Tab.
 * 2. Exchanges the callback `code` for an Access Token and Refresh Token via
 *    `POST https://accounts.spotify.com/api/token` using `Authorization: Basic Base64(CLIENT_ID:CLIENT_SECRET)`.
 * 3. Securely persists `access_token`, `refresh_token`, and `expires_at_ms` inside
 *    AndroidX [EncryptedSharedPreferences] (`AES256_SIV` / `AES256_GCM`).
 * 4. Automatically refreshes expired Access Tokens in the background via [getValidAccessToken].
 */
class SpotifyAuthManager(context: Context) {

    private val appContext: Context = context.applicationContext
    private val authService: SpotifyAccountsAuthService = SpotifyApiFactory.accountsAuthService
    private val refreshMutex = Mutex()

    private val encryptedPrefs: SharedPreferences by lazy {
        createEncryptedPrefs(appContext)
    }

    private fun createEncryptedPrefs(ctx: Context): SharedPreferences {
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
            Log.w(TAG, "Hardware Keystore recovery for EncryptedSharedPreferences: ${e.message}")
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
                Log.e(TAG, "EncryptedSharedPreferences fallback to private preferences", fallbackEx)
                ctx.getSharedPreferences("${PREFS_FILE_NAME}_fallback", Context.MODE_PRIVATE)
            }
        }
    }

    /**
     * Opens the Spotify Authorization URL in a Chrome Custom Tab (`CustomTabsIntent`).
     */
    fun launchSpotifyAuth(activityOrContext: Context) {
        val state = UUID.randomUUID().toString().replace("-", "")
        encryptedPrefs.edit().putString(KEY_OAUTH_STATE, state).apply()

        val authUri = Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .appendQueryParameter("show_dialog", "false")
            .build()

        Log.i(TAG, "Opening Spotify Authorization URL in Custom Chrome Tab: $authUri")

        try {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(true)
                .build()
            customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            customTabsIntent.launchUrl(activityOrContext, authUri)
        } catch (e: Exception) {
            Log.w(TAG, "Custom Chrome Tab unavailable, falling back to ACTION_VIEW: ${e.message}")
            val browserIntent = Intent(Intent.ACTION_VIEW, authUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activityOrContext.startActivity(browserIntent)
        }
    }

    /**
     * Exchanges the intercepted `code` from `tunemind://spotify-auth-callback?code=...`
     * for an Access Token and Refresh Token via `https://accounts.spotify.com/api/token`.
     */
    suspend fun exchangeCodeForTokens(authorizationCode: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = authService.exchangeAuthorizationCodeWithSecret(
                basicAuthHeader = buildBasicAuthHeader(),
                grantType = "authorization_code",
                code = authorizationCode.trim(),
                redirectUri = REDIRECT_URI
            )

            if (response.isSuccessful && response.body() != null) {
                val tokenResponse = response.body()!!
                saveTokens(tokenResponse)
                Log.i(TAG, "Successfully exchanged Spotify authorization code for Access & Refresh tokens.")
                return@withContext true
            } else {
                val errBody = response.errorBody()?.string()
                Log.e(TAG, "Token exchange failed with HTTP ${response.code()}: $errBody")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception exchanging Spotify authorization code: ${e.message}", e)
            return@withContext false
        }
    }

    /**
     * Persists Access Token, Refresh Token, and Expiration Time (`expires_at_ms`) in
     * [EncryptedSharedPreferences].
     */
    fun saveTokens(tokenResponse: SpotifyTokenResponse) {
        val nowMs = System.currentTimeMillis()
        val expiresAtMs = nowMs + (tokenResponse.expiresInSeconds * 1000L)
        val existingRefreshToken = encryptedPrefs.getString(KEY_REFRESH_TOKEN, null)
        val effectiveRefreshToken = tokenResponse.refreshToken?.takeIf { it.isNotBlank() } ?: existingRefreshToken

        encryptedPrefs.edit().apply {
            putString(KEY_ACCESS_TOKEN, tokenResponse.accessToken)
            if (!effectiveRefreshToken.isNullOrBlank()) {
                putString(KEY_REFRESH_TOKEN, effectiveRefreshToken)
            }
            putLong(KEY_EXPIRES_AT_MS, expiresAtMs)
            putString(KEY_GRANTED_SCOPES, tokenResponse.scope ?: SCOPES)
            putLong(KEY_LAST_TOKEN_REFRESH_MS, nowMs)
            apply()
        }
    }

    /**
     * Returns a valid Access Token. If the stored Access Token is expired (or within 60s of expiry),
     * automatically uses the stored Refresh Token to obtain and persist a new Access Token.
     */
    suspend fun getValidAccessToken(): String? = withContext(Dispatchers.IO) {
        val currentAccess = encryptedPrefs.getString(KEY_ACCESS_TOKEN, null)
        if (!currentAccess.isNullOrBlank() && !isTokenExpired()) {
            return@withContext currentAccess
        }
        return@withContext refreshAccessTokenIfNeeded()
    }

    /**
     * Uses the stored Refresh Token to fetch a new Access Token from `https://accounts.spotify.com/api/token`.
     */
    suspend fun refreshAccessTokenIfNeeded(forceRefresh: Boolean = false): String? = refreshMutex.withLock {
        val currentAccess = encryptedPrefs.getString(KEY_ACCESS_TOKEN, null)
        if (!forceRefresh && !currentAccess.isNullOrBlank() && !isTokenExpired()) {
            return@withLock currentAccess
        }

        val refreshToken = encryptedPrefs.getString(KEY_REFRESH_TOKEN, null)
        if (refreshToken.isNullOrBlank()) {
            Log.w(TAG, "Cannot refresh Spotify token: no refresh_token stored in EncryptedSharedPreferences.")
            return@withLock null
        }

        return@withLock try {
            val response = authService.refreshAccessTokenWithSecret(
                basicAuthHeader = buildBasicAuthHeader(),
                grantType = "refresh_token",
                refreshToken = refreshToken
            )
            if (response.isSuccessful && response.body() != null) {
                val newTokens = response.body()!!
                saveTokens(newTokens)
                Log.i(TAG, "Successfully refreshed Spotify Access Token via Refresh Token.")
                newTokens.accessToken
            } else {
                Log.e(TAG, "Failed to refresh Spotify Access Token: HTTP ${response.code()}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception refreshing Spotify Access Token: ${e.message}", e)
            null
        }
    }

    fun isAuthenticated(): Boolean {
        val access = encryptedPrefs.getString(KEY_ACCESS_TOKEN, null)
        val refresh = encryptedPrefs.getString(KEY_REFRESH_TOKEN, null)
        return !access.isNullOrBlank() || !refresh.isNullOrBlank()
    }

    fun isTokenExpired(safetyBufferMs: Long = EXPIRY_SAFETY_BUFFER_MS): Boolean {
        val expiresAtMs = encryptedPrefs.getLong(KEY_EXPIRES_AT_MS, 0L)
        if (expiresAtMs <= 0L) return true
        return System.currentTimeMillis() + safetyBufferMs >= expiresAtMs
    }

    fun getExpirationTimeMs(): Long = encryptedPrefs.getLong(KEY_EXPIRES_AT_MS, 0L)

    fun clearTokens() {
        encryptedPrefs.edit().clear().apply()
    }

    companion object {
        private const val TAG = "SpotifyAuthManager"

        const val CLIENT_ID = "dcfad6acbd594477b0705ecc1e6ace3a"
        const val CLIENT_SECRET = "134028eff64c4e2cafa4b1f617463650"
        const val REDIRECT_URI = "tunemind://spotify-auth-callback"
        const val SCOPES = "user-read-recently-played user-read-currently-playing user-read-playback-state user-top-read user-library-read user-modify-playback-state app-remote-control"

        private const val AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
        private const val PREFS_FILE_NAME = "tunemind_spotify_secure_vault"
        private const val EXPIRY_SAFETY_BUFFER_MS = 60_000L

        private const val KEY_ACCESS_TOKEN = "spotify_access_token"
        private const val KEY_REFRESH_TOKEN = "spotify_refresh_token"
        private const val KEY_EXPIRES_AT_MS = "spotify_expires_at_ms"
        private const val KEY_GRANTED_SCOPES = "spotify_granted_scopes"
        private const val KEY_LAST_TOKEN_REFRESH_MS = "spotify_last_token_refresh_ms"
        private const val KEY_OAUTH_STATE = "spotify_oauth_csrf_state"

        fun buildBasicAuthHeader(): String {
            val credentials = "$CLIENT_ID:$CLIENT_SECRET"
            val encoded = Base64.encodeToString(credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            return "Basic $encoded"
        }
    }
}
