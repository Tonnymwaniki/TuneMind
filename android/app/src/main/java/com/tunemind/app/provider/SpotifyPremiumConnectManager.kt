package com.tunemind.app.provider

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.tunemind.app.BuildConfig
import com.tunemind.app.domain.network.BackendEndpointManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

enum class SpotifyErrorHintType {
    NONE,
    REDIRECT_URI,
    DEV_MODE_ALLOWLIST,
    NO_ACTIVE_DEVICE,
    INVALID_CLIENT,
    NETWORK,
    REVOKED
}

data class SpotifyPremiumStatus(
    val isConnected: Boolean = false,
    val isPremium: Boolean = false,
    val product: String = "",
    val displayName: String = "",
    val clientId: String = "",
    val appDefaultClientId: String = "",
    val usesCustomClientId: Boolean = false,
    val lastDeviceName: String = "",
    val lastError: String? = null,
    val errorHintType: SpotifyErrorHintType = SpotifyErrorHintType.NONE,
    val isAuthorizing: Boolean = false,
    val connectedAtMs: Long = 0L,
    val cloudBackedUp: Boolean = false,
    val lastCloudSyncMs: Long = 0L
) {
    val isFreeTier: Boolean
        get() = isConnected && !isPremium

    val accountTierLabel: String
        get() = when {
            isConnected && isPremium -> "PREMIUM_CONNECTED"
            isConnected -> "FREE_CONNECTED"
            else -> "DISCONNECTED"
        }

    val hasAppDefaultClientId: Boolean
        get() = SpotifyPremiumConnectManager.isValidClientIdFormat(appDefaultClientId)

    val effectiveClientId: String
        get() = SpotifyPremiumConnectManager.resolveEffectiveClientId(clientId, appDefaultClientId)

    val isReadyForOneTapConnect: Boolean
        get() = effectiveClientId.isNotBlank()
}

data class SpotifyDeviceCheckResult(
    val hasActiveDevice: Boolean,
    val deviceName: String = "",
    val deviceType: String = "",
    val message: String = ""
)

sealed class SpotifyPlayResult {
    data class Success(val trackUri: String, val deviceName: String) : SpotifyPlayResult()
    data class NoActiveDevice(val message: String, val trackUri: String? = null) : SpotifyPlayResult()
    data class PremiumRequired(val message: String) : SpotifyPlayResult()
    object NotConnected : SpotifyPlayResult()
    data class Failed(val reason: String) : SpotifyPlayResult()
}

data class SpotifySyncedTrack(
    val spotifyId: String,
    val spotifyUri: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    val albumArtUrl: String? = null,
    val previewUrl: String? = null,
    val popularity: Int = 0,
    val addedAtMs: Long = 0L
)

data class SpotifySyncedArtist(
    val spotifyId: String,
    val name: String,
    val imageUrl: String? = null,
    val genres: List<String> = emptyList(),
    val popularity: Int = 0,
    val followersTotal: Long = 0L
)

data class SpotifySyncedPlaylist(
    val spotifyId: String,
    val name: String,
    val description: String = "",
    val ownerName: String = "",
    val imageUrl: String? = null,
    val totalTracks: Int = 0,
    val isOwnedOrCollaborative: Boolean = true,
    val tracks: List<SpotifySyncedTrack> = emptyList()
)

data class SpotifyExportPlaylistResult(
    val success: Boolean,
    val spotifyPlaylistId: String? = null,
    val spotifyUrl: String? = null,
    val addedCount: Int = 0,
    val totalCount: Int = 0,
    val message: String = ""
)

/**
 * Spotify Premium Direct Connect manager (OAuth 2.0 Authorization Code + PKCE).
 *
 * Supports two seamless connection modes:
 * 1. **1-Tap Consumer Connect**: Uses TuneMind's configured public `SPOTIFY_CLIENT_ID`
 *    (bundled via `BuildConfig.SPOTIFY_CLIENT_ID` or fetched from `/api/v1/spotify/client-config`)
 *    so everyday listeners simply tap "Connect Spotify" with zero dashboard setup.
 * 2. **Guided Bring-Your-Own Client ID (BYOC)**: Lets any user connect their own free
 *    Spotify Developer Client ID via an in-app 3-step setup guide with 1-tap redirect URI copy.
 *
 * Unlocks:
 * - Silent full-track Spotify playback without leaving TuneMind (`PUT /v1/me/player/play`)
 * - Direct queueing into the user's real Spotify queue (`POST /v1/me/player/queue`)
 * - Saving liked tracks straight to Spotify Liked Songs (`PUT /v1/me/tracks`)
 * - Reliable Spotify Connect transport fallback (pause, resume, skip, volume)
 *
 * Zero client_secret is ever required or stored thanks to PKCE (S256).
 */
object SpotifyPremiumConnectManager {
    private const val TAG = "SpotifyPremiumConnect"
    private const val PREFS_NAME = "tunemind_spotify_premium_connect_v1"

    const val REDIRECT_URI = "tunemind://spotify-callback"
    const val AUTHORIZE_ENDPOINT = "https://accounts.spotify.com/authorize"
    const val TOKEN_ENDPOINT = "https://accounts.spotify.com/api/token"
    const val DEVELOPER_DASHBOARD_URL = "https://developer.spotify.com/dashboard"
    private const val API_BASE = "https://api.spotify.com/v1"

    const val SCOPES =
        "user-modify-playback-state user-read-playback-state user-read-currently-playing " +
            "user-library-modify user-library-read playlist-modify-public playlist-modify-private " +
            "playlist-read-private playlist-read-collaborative user-read-private " +
            "user-top-read user-read-recently-played"

    private const val KEY_CLIENT_ID = "client_id"
    private const val KEY_APP_DEFAULT_CLIENT_ID = "app_default_client_id"
    private const val KEY_ACTIVE_AUTH_CLIENT_ID = "active_auth_client_id"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_EXPIRES_AT_MS = "expires_at_ms"
    private const val KEY_SPOTIFY_USER_ID = "spotify_user_id"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_PRODUCT = "product"
    private const val KEY_IS_PREMIUM = "is_premium"
    private const val KEY_CONNECTED_AT_MS = "connected_at_ms"
    private const val KEY_LAST_DEVICE_NAME = "last_device_name"
    private const val KEY_CLOUD_SYNCED_MS = "cloud_synced_ms"
    private const val KEY_PENDING_VERIFIER = "pending_pkce_verifier"
    private const val KEY_PENDING_STATE = "pending_oauth_state"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val EMPTY_BODY = ByteArray(0).toRequestBody(null)

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    private val tokenRefreshLock = Any()

    private val _statusFlow = MutableStateFlow(SpotifyPremiumStatus())
    val statusFlow: StateFlow<SpotifyPremiumStatus> = _statusFlow.asStateFlow()

    private fun getPrefs(context: Context): SharedPreferences {
        cachedPrefs?.let { return it }
        synchronized(this) {
            cachedPrefs?.let { return it }
            val appCtx = context.applicationContext
            val created = try {
                val masterKey = MasterKey.Builder(appCtx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    appCtx,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.w(TAG, "EncryptedSharedPreferences unavailable, using private fallback: ${e.message}")
                appCtx.getSharedPreferences("${PREFS_NAME}_fallback", Context.MODE_PRIVATE)
            }
            cachedPrefs = created
            return created
        }
    }

    private fun getBundledBuildClientId(): String {
        return runCatching { BuildConfig.SPOTIFY_CLIENT_ID.trim() }
            .getOrDefault("")
            .takeIf { isValidClientIdFormat(it) }
            .orEmpty()
    }

    internal fun resolveEffectiveClientId(customClientId: String?, appDefaultClientId: String?): String {
        val cleanCustom = customClientId?.trim().orEmpty()
        if (isValidClientIdFormat(cleanCustom)) return cleanCustom
        val cleanDefault = appDefaultClientId?.trim().orEmpty()
        if (isValidClientIdFormat(cleanDefault)) return cleanDefault
        return ""
    }

    fun ensureLoaded(context: Context): SpotifyPremiumStatus {
        val prefs = getPrefs(context)
        val prev = _statusFlow.value
        val status = readStatusFromPrefs(
            prefs = prefs,
            currentError = prev.lastError,
            errorHintType = prev.errorHintType,
            isAuthorizing = prev.isAuthorizing
        )
        _statusFlow.value = status
        return status
    }

    /**
     * Resolves TuneMind's app-default public Spotify Client ID from BuildConfig or the live
     * backend (`/api/v1/spotify/client-config`) so regular users get 1-Tap Spotify Connect.
     * Call from a background/IO thread.
     */
    fun syncAppDefaultClientId(context: Context): String? {
        val prefs = getPrefs(context)
        val bundled = getBundledBuildClientId()
        if (bundled.isNotBlank()) {
            prefs.edit().putString(KEY_APP_DEFAULT_CLIENT_ID, bundled).apply()
            _statusFlow.value = readStatusFromPrefs(prefs, currentError = _statusFlow.value.lastError, isAuthorizing = _statusFlow.value.isAuthorizing)
            return bundled
        }

        val cached = prefs.getString(KEY_APP_DEFAULT_CLIENT_ID, "")?.trim().orEmpty()
        val baseUrl = runCatching { BackendEndpointManager.getActiveHttpBaseUrl(context) }.getOrDefault("")
        if (baseUrl.isBlank()) {
            return cached.takeIf { isValidClientIdFormat(it) }
        }

        return try {
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/v1/spotify/client-config")
                .header("Accept", "application/json")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return cached.takeIf { isValidClientIdFormat(it) }
                }
                val json = JSONObject(resp.body?.string().orEmpty())
                val remoteId = json.optString("client_id").trim()
                if (json.optBoolean("configured", false) && isValidClientIdFormat(remoteId)) {
                    prefs.edit().putString(KEY_APP_DEFAULT_CLIENT_ID, remoteId).apply()
                    _statusFlow.value = readStatusFromPrefs(
                        prefs = prefs,
                        currentError = _statusFlow.value.lastError,
                        isAuthorizing = _statusFlow.value.isAuthorizing
                    )
                    remoteId
                } else {
                    cached.takeIf { isValidClientIdFormat(it) }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Optional backend Spotify client-config lookup skipped: ${e.message}")
            cached.takeIf { isValidClientIdFormat(it) }
        }
    }

    private fun readStatusFromPrefs(
        prefs: SharedPreferences,
        currentError: String? = null,
        errorHintType: SpotifyErrorHintType = SpotifyErrorHintType.NONE,
        isAuthorizing: Boolean = false
    ): SpotifyPremiumStatus {
        val customClientId = prefs.getString(KEY_CLIENT_ID, "")?.trim().orEmpty()
        val storedDefaultId = prefs.getString(KEY_APP_DEFAULT_CLIENT_ID, "")?.trim().orEmpty()
        val appDefaultId = getBundledBuildClientId().ifBlank { storedDefaultId }
        val activeSessionClientId = prefs.getString(KEY_ACTIVE_AUTH_CLIENT_ID, "")?.trim().orEmpty()
            .ifBlank { resolveEffectiveClientId(customClientId, appDefaultId) }

        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, "")?.trim().orEmpty()
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, "")?.trim().orEmpty()
        val hasSession = activeSessionClientId.isNotBlank() && (accessToken.isNotBlank() || refreshToken.isNotBlank())
        val product = prefs.getString(KEY_PRODUCT, "")?.trim().orEmpty()
        val explicitlyFree = product.equals("free", ignoreCase = true) || product.equals("open", ignoreCase = true)
        val isPremium = hasSession && !explicitlyFree && (
            prefs.getBoolean(KEY_IS_PREMIUM, false) ||
                product.equals("premium", ignoreCase = true) ||
                product.isBlank()
            )
        val cloudSyncMs = prefs.getLong(KEY_CLOUD_SYNCED_MS, 0L)
        return SpotifyPremiumStatus(
            isConnected = hasSession,
            isPremium = isPremium,
            product = product,
            displayName = prefs.getString(KEY_DISPLAY_NAME, "")?.trim().orEmpty(),
            clientId = customClientId,
            appDefaultClientId = appDefaultId,
            usesCustomClientId = isValidClientIdFormat(customClientId),
            lastDeviceName = prefs.getString(KEY_LAST_DEVICE_NAME, "")?.trim().orEmpty(),
            lastError = currentError,
            errorHintType = if (currentError.isNullOrBlank()) SpotifyErrorHintType.NONE else errorHintType,
            isAuthorizing = isAuthorizing,
            connectedAtMs = prefs.getLong(KEY_CONNECTED_AT_MS, 0L),
            cloudBackedUp = hasSession && cloudSyncMs > 0L,
            lastCloudSyncMs = cloudSyncMs
        )
    }

    fun isConnected(context: Context): Boolean {
        return ensureLoaded(context).isConnected
    }

    fun isConnectedAndPremium(context: Context): Boolean {
        val status = ensureLoaded(context)
        return status.isConnected && status.isPremium
    }

    fun isConnectedAsFreeTier(context: Context): Boolean {
        val status = ensureLoaded(context)
        return status.isConnected && !status.isPremium
    }

    /**
     * Maps cryptic Spotify OAuth / API errors into plain-English guidance + 1-tap fix hints.
     */
    private fun mapFriendlyError(
        rawMessage: String,
        httpCode: Int = 0
    ): Pair<String, SpotifyErrorHintType> {
        val lower = rawMessage.lowercase()
        return when {
            lower.contains("redirect_uri") || lower.contains("invalid_redirect") -> {
                "Redirect URI mismatch — make sure '$REDIRECT_URI' is added and saved in your Spotify Developer app settings." to SpotifyErrorHintType.REDIRECT_URI
            }
            lower.contains("invalid_client") -> {
                "That Spotify Client ID was not recognized. Double-check the 32-character Client ID from Spotify Dashboard." to SpotifyErrorHintType.INVALID_CLIENT
            }
            httpCode == 403 || lower.contains("user not registered") || lower.contains("developer dashboard") || lower.contains("forbidden") -> {
                "Spotify Dev Mode blocked this account — open Spotify Developer Dashboard → User Management and add your Spotify email, or use your own Client ID." to SpotifyErrorHintType.DEV_MODE_ALLOWLIST
            }
            lower.contains("invalid_grant") -> {
                "Spotify access was revoked or expired. Tap Connect Spotify to relink in one step." to SpotifyErrorHintType.REVOKED
            }
            lower.contains("no_active_device") || httpCode == 404 -> {
                "Spotify is asleep on this phone. Tap 'Wake Spotify' once so TuneMind can play full tracks silently." to SpotifyErrorHintType.NO_ACTIVE_DEVICE
            }
            lower.contains("network") || lower.contains("timeout") || lower.contains("unable to resolve host") -> {
                "Temporary network issue reaching Spotify. Your connection is still saved — try again in a moment." to SpotifyErrorHintType.NETWORK
            }
            else -> {
                rawMessage.ifBlank { "Could not reach Spotify (HTTP $httpCode). Please try again." } to SpotifyErrorHintType.NONE
            }
        }
    }

    /**
     * Uploads the user's Spotify PKCE refresh token & Client ID to TuneMind's backend where it is
     * encrypted at rest (AES-256-GCM) and linked to their authenticated TuneMind account so they
     * never need to reconnect after reinstalling or switching phones.
     * Call from a background/IO thread.
     */
    fun backupConnectionToCloud(context: Context): Boolean {
        return try {
            val appCtx = context.applicationContext
            val authToken = com.tunemind.app.data.auth.TuneMindAuthSessionManager
                .getInstance(appCtx)
                .getAccessToken()
                .trim()
            if (authToken.isBlank()) {
                Log.d(TAG, "Skipping Spotify cloud backup (guest / unauthenticated session).")
                return false
            }

            val prefs = getPrefs(appCtx)
            val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, "")?.trim().orEmpty()
            if (refreshToken.isBlank()) return false

            val activeClientId = prefs.getString(KEY_ACTIVE_AUTH_CLIENT_ID, "")?.trim().orEmpty()
                .ifBlank { prefs.getString(KEY_CLIENT_ID, "")?.trim().orEmpty() }
            val baseUrl = runCatching { BackendEndpointManager.getActiveHttpBaseUrl(appCtx) }.getOrDefault("")
            if (baseUrl.isBlank()) return false

            val payload = JSONObject().apply {
                put("client_id", activeClientId)
                put("refresh_token", refreshToken)
                put("display_name", prefs.getString(KEY_DISPLAY_NAME, "")?.trim().orEmpty())
                put("product", prefs.getString(KEY_PRODUCT, "")?.trim().orEmpty())
                put("last_device_name", prefs.getString(KEY_LAST_DEVICE_NAME, "")?.trim().orEmpty())
            }.toString()

            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/v1/spotify/connection")
                .header("Authorization", "Bearer $authToken")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Spotify encrypted cloud backup returned HTTP ${resp.code}")
                    return false
                }
                val now = System.currentTimeMillis()
                prefs.edit().putLong(KEY_CLOUD_SYNCED_MS, now).apply()
                _statusFlow.value = readStatusFromPrefs(
                    prefs = prefs,
                    currentError = _statusFlow.value.lastError,
                    errorHintType = _statusFlow.value.errorHintType,
                    isAuthorizing = _statusFlow.value.isAuthorizing
                )
                Log.i(TAG, "✅ Encrypted Spotify connection synced to TuneMind cloud.")
                true
            }
        } catch (e: Exception) {
            Log.d(TAG, "Spotify cloud backup skipped: ${e.message}")
            false
        }
    }

    /**
     * Restores the user's encrypted Spotify connection from TuneMind cloud if local tokens are
     * missing (e.g., fresh install, app data cleared, or new phone) and refreshes an access token.
     * Call from a background/IO thread.
     */
    fun restoreConnectionFromCloudIfNeeded(context: Context): Boolean {
        return try {
            val appCtx = context.applicationContext
            val current = ensureLoaded(appCtx)
            if (current.isConnected) {
                if (!current.cloudBackedUp) {
                    backupConnectionToCloud(appCtx)
                }
                return true
            }

            val authToken = com.tunemind.app.data.auth.TuneMindAuthSessionManager
                .getInstance(appCtx)
                .getAccessToken()
                .trim()
            if (authToken.isBlank()) return false

            val baseUrl = runCatching { BackendEndpointManager.getActiveHttpBaseUrl(appCtx) }.getOrDefault("")
            if (baseUrl.isBlank()) return false

            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/v1/spotify/connection")
                .header("Authorization", "Bearer $authToken")
                .header("Accept", "application/json")
                .get()
                .build()

            val restoredConn = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val root = JSONObject(resp.body?.string().orEmpty())
                if (!root.optBoolean("connected", false)) return false
                root.optJSONObject("connection")
            } ?: return false

            val restoredRefresh = restoredConn.optString("refresh_token").trim()
            if (restoredRefresh.isBlank()) return false

            val restoredClientId = restoredConn.optString("client_id").trim()
            val restoredName = restoredConn.optString("display_name").trim().ifBlank { "Spotify Listener" }
            val restoredProduct = restoredConn.optString("product").trim()
            val restoredDevice = restoredConn.optString("last_device_name").trim()
            val isPremium = !restoredProduct.equals("free", ignoreCase = true) &&
                !restoredProduct.equals("open", ignoreCase = true)

            val prefs = getPrefs(appCtx)
            val editor = prefs.edit()
                .putString(KEY_REFRESH_TOKEN, restoredRefresh)
                .putString(KEY_DISPLAY_NAME, restoredName)
                .putString(KEY_PRODUCT, restoredProduct)
                .putBoolean(KEY_IS_PREMIUM, isPremium)
                .putLong(KEY_CONNECTED_AT_MS, System.currentTimeMillis())
                .putLong(KEY_CLOUD_SYNCED_MS, System.currentTimeMillis())
            if (isValidClientIdFormat(restoredClientId)) {
                editor.putString(KEY_ACTIVE_AUTH_CLIENT_ID, restoredClientId)
                val bundledDefault = getBundledBuildClientId().ifBlank {
                    prefs.getString(KEY_APP_DEFAULT_CLIENT_ID, "")?.trim().orEmpty()
                }
                if (restoredClientId != bundledDefault) {
                    editor.putString(KEY_CLIENT_ID, restoredClientId)
                }
            }
            if (restoredDevice.isNotBlank()) {
                editor.putString(KEY_LAST_DEVICE_NAME, restoredDevice)
            }
            editor.apply()

            _statusFlow.value = readStatusFromPrefs(prefs, currentError = null, isAuthorizing = false)
            // Immediately mint a fresh access token so playback is instant on first tap
            val freshToken = refreshAccessTokenIfNeeded(appCtx, forceRefresh = true)
            com.tunemind.app.sync.SyncScheduler.scheduleSpotifyTokenKeepAlive(appCtx)
            Log.i(TAG, "✅ Auto-restored Spotify connection from TuneMind cloud for '$restoredName' (tokenReady=${freshToken != null})")
            freshToken != null || ensureLoaded(appCtx).isConnected
        } catch (e: Exception) {
            Log.d(TAG, "Spotify cloud restore skipped: ${e.message}")
            false
        }
    }

    private fun deleteCloudConnectionAsync(context: Context) {
        val appCtx = context.applicationContext
        Thread {
            try {
                val authToken = com.tunemind.app.data.auth.TuneMindAuthSessionManager
                    .getInstance(appCtx)
                    .getAccessToken()
                    .trim()
                val baseUrl = runCatching { BackendEndpointManager.getActiveHttpBaseUrl(appCtx) }.getOrDefault("")
                if (authToken.isBlank() || baseUrl.isBlank()) return@Thread

                val req = Request.Builder()
                    .url("${baseUrl.trimEnd('/')}/api/v1/spotify/connection")
                    .header("Authorization", "Bearer $authToken")
                    .delete()
                    .build()
                httpClient.newCall(req).execute().close()
                Log.i(TAG, "Removed encrypted Spotify backup from TuneMind cloud.")
            } catch (e: Exception) {
                Log.d(TAG, "Cloud Spotify disconnect notice: ${e.message}")
            }
        }.start()
    }

    fun saveClientId(context: Context, rawClientId: String) {
        val clean = rawClientId.trim()
        val prefs = getPrefs(context)
        prefs.edit().putString(KEY_CLIENT_ID, clean).apply()
        _statusFlow.value = readStatusFromPrefs(prefs, currentError = null, isAuthorizing = false)
    }

    fun clearCustomClientId(context: Context) {
        val prefs = getPrefs(context)
        prefs.edit().remove(KEY_CLIENT_ID).apply()
        _statusFlow.value = readStatusFromPrefs(prefs, currentError = null, isAuthorizing = false)
    }

    internal fun isValidClientIdFormat(rawClientId: String): Boolean {
        val clean = rawClientId.trim()
        return Regex("^[A-Za-z0-9]{16,64}$").matches(clean)
    }

    /**
     * Opens Spotify Developer Dashboard directly from inside Settings so BYOC users never
     * have to hunt for the URL manually.
     */
    fun openSpotifyDeveloperDashboard(context: Context) {
        openExternalUrl(context, DEVELOPER_DASHBOARD_URL)
    }

    /**
     * Launches the installed Spotify app briefly so the phone registers as an active Spotify Connect device.
     */
    fun openSpotifyAppToWakeDevice(context: Context): Boolean {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage("com.spotify.music")
            ?: pm.getLaunchIntentForPackage("com.spotify.lite")
            ?: return false
        return try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not launch Spotify to wake Connect device: ${e.message}")
            false
        }
    }

    private fun openExternalUrl(context: Context, url: String): Result<Unit> {
        return try {
            val parsedUri = Uri.parse(url)
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            if (context !is Activity) {
                customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            customTabsIntent.launchUrl(context, parsedUri)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "CustomTabs launch failed, falling back to browser intent: ${e.message}")
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(browserIntent)
                Result.success(Unit)
            } catch (fallbackErr: Exception) {
                Result.failure(fallbackErr)
            }
        }
    }

    /**
     * Pure JVM Base64URL encoder (RFC 4648 Section 5, no padding) so PKCE works identically
     * on all Android API levels and inside local JVM unit tests.
     */
    internal fun base64UrlEncodeNoPadding(input: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val out = StringBuilder(((input.size + 2) / 3) * 4)
        var i = 0
        while (i < input.size) {
            val b0 = input[i].toInt() and 0xFF
            val hasB1 = i + 1 < input.size
            val hasB2 = i + 2 < input.size
            val b1 = if (hasB1) input[i + 1].toInt() and 0xFF else 0
            val b2 = if (hasB2) input[i + 2].toInt() and 0xFF else 0

            out.append(alphabet[(b0 ushr 2) and 0x3F])
            out.append(alphabet[((b0 shl 4) or (b1 ushr 4)) and 0x3F])
            if (hasB1) {
                out.append(alphabet[((b1 shl 2) or (b2 ushr 6)) and 0x3F])
            }
            if (hasB2) {
                out.append(alphabet[b2 and 0x3F])
            }
            i += 3
        }
        return out.toString()
    }

    internal fun generateCodeVerifier(byteLength: Int = 64): String {
        val bytes = ByteArray(byteLength.coerceIn(32, 96))
        SecureRandom().nextBytes(bytes)
        return base64UrlEncodeNoPadding(bytes)
    }

    internal fun generateCodeChallenge(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        return base64UrlEncodeNoPadding(digest)
    }

    internal fun buildAuthorizeUrl(
        clientId: String,
        codeChallenge: String,
        state: String,
        redirectUri: String = REDIRECT_URI,
        scopes: String = SCOPES
    ): String {
        fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")
        return buildString {
            append(AUTHORIZE_ENDPOINT)
            append("?client_id=").append(enc(clientId.trim()))
            append("&response_type=code")
            append("&redirect_uri=").append(enc(redirectUri))
            append("&code_challenge_method=S256")
            append("&code_challenge=").append(enc(codeChallenge))
            append("&state=").append(enc(state))
            append("&scope=").append(enc(scopes))
            append("&show_dialog=true")
        }
    }

    /**
     * 1-Tap connect using either the user's saved personal Client ID or TuneMind's configured
     * app-default public Client ID.
     */
    fun startDefaultAuthorization(context: Context): Result<Unit> {
        val status = ensureLoaded(context)
        val chosenId = status.effectiveClientId
        if (chosenId.isBlank()) {
            val msg = "No Spotify Client ID configured yet. Tap 'Set Up Spotify Connect' to follow the 60-second setup guide."
            _statusFlow.value = status.copy(lastError = msg, isAuthorizing = false)
            return Result.failure(IllegalStateException(msg))
        }
        return startAuthorization(
            context = context,
            rawClientId = chosenId,
            saveAsCustomClientId = status.usesCustomClientId
        )
    }

    /**
     * Initiates Spotify OAuth 2.0 PKCE login in Chrome Custom Tabs using the specified Client ID.
     */
    fun startAuthorization(
        context: Context,
        rawClientId: String,
        saveAsCustomClientId: Boolean = true
    ): Result<Unit> {
        val cleanClientId = rawClientId.trim()
        if (!isValidClientIdFormat(cleanClientId)) {
            val msg = "Enter a valid Spotify Client ID (32-character alphanumeric ID from Spotify Developer Dashboard)."
            _statusFlow.value = _statusFlow.value.copy(lastError = msg, isAuthorizing = false)
            return Result.failure(IllegalArgumentException(msg))
        }

        val verifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)
        val state = generateCodeVerifier(24)
        val authUrl = buildAuthorizeUrl(
            clientId = cleanClientId,
            codeChallenge = challenge,
            state = state
        )

        val prefs = getPrefs(context)
        val editor = prefs.edit()
            .putString(KEY_ACTIVE_AUTH_CLIENT_ID, cleanClientId)
            .putString(KEY_PENDING_VERIFIER, verifier)
            .putString(KEY_PENDING_STATE, state)
        if (saveAsCustomClientId) {
            editor.putString(KEY_CLIENT_ID, cleanClientId)
        }
        editor.apply()

        _statusFlow.value = readStatusFromPrefs(prefs, currentError = null, isAuthorizing = true)

        val openRes = openExternalUrl(context, authUrl)
        if (openRes.isFailure) {
            val errMsg = "Could not open browser for Spotify sign-in: ${openRes.exceptionOrNull()?.message}"
            _statusFlow.value = readStatusFromPrefs(prefs, currentError = errMsg, isAuthorizing = false)
        }
        return openRes
    }

    /**
     * Completes PKCE OAuth token exchange when `AuthCallbackActivity` receives
     * `tunemind://spotify-callback?code=...&state=...`.
     * Must be invoked on a background/IO thread.
     */
    fun handleCallbackUri(context: Context, uri: Uri): Boolean {
        val prefs = getPrefs(context)
        val oauthError = uri.getQueryParameter("error")
        if (!oauthError.isNullOrBlank()) {
            val friendly = if (oauthError.equals("access_denied", ignoreCase = true)) {
                "Spotify connection was cancelled."
            } else {
                "Spotify authorization error: $oauthError"
            }
            clearPendingAuth(prefs)
            _statusFlow.value = readStatusFromPrefs(prefs, currentError = friendly, isAuthorizing = false)
            return false
        }

        val code = uri.getQueryParameter("code")?.trim().orEmpty()
        val returnedState = uri.getQueryParameter("state")?.trim().orEmpty()
        val expectedState = prefs.getString(KEY_PENDING_STATE, "")?.trim().orEmpty()
        val verifier = prefs.getString(KEY_PENDING_VERIFIER, "")?.trim().orEmpty()
        val clientId = prefs.getString(KEY_ACTIVE_AUTH_CLIENT_ID, "")?.trim().orEmpty()
            .ifBlank {
                resolveEffectiveClientId(
                    prefs.getString(KEY_CLIENT_ID, ""),
                    getBundledBuildClientId().ifBlank { prefs.getString(KEY_APP_DEFAULT_CLIENT_ID, "") }
                )
            }

        if (code.isBlank() || verifier.isBlank() || clientId.isBlank()) {
            val msg = "Spotify sign-in could not be completed. Please try connecting again from Settings."
            clearPendingAuth(prefs)
            _statusFlow.value = readStatusFromPrefs(prefs, currentError = msg, isAuthorizing = false)
            return false
        }

        if (expectedState.isNotBlank() && returnedState != expectedState) {
            val msg = "Spotify OAuth state mismatch. Please retry connecting."
            clearPendingAuth(prefs)
            _statusFlow.value = readStatusFromPrefs(prefs, currentError = msg, isAuthorizing = false)
            return false
        }

        return try {
            val formBody = FormBody.Builder()
                .add("client_id", clientId)
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("code_verifier", verifier)
                .build()

            val tokenRequest = Request.Builder()
                .url(TOKEN_ENDPOINT)
                .post(formBody)
                .header("Accept", "application/json")
                .build()

            val tokenJson = httpClient.newCall(tokenRequest).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val errDesc = runCatching {
                        JSONObject(bodyStr).optString("error_description")
                            .ifBlank { JSONObject(bodyStr).optString("error") }
                    }.getOrDefault("")
                    val (friendlyMsg, hintType) = mapFriendlyError(errDesc.ifBlank { bodyStr }, response.code)
                    Log.e(TAG, "Spotify token exchange failed (${response.code}): $errDesc")
                    clearPendingAuth(prefs)
                    _statusFlow.value = readStatusFromPrefs(
                        prefs = prefs,
                        currentError = friendlyMsg,
                        errorHintType = hintType,
                        isAuthorizing = false
                    )
                    return false
                }
                JSONObject(bodyStr)
            }

            val accessToken = tokenJson.optString("access_token").trim()
            val refreshToken = tokenJson.optString("refresh_token").trim()
            val expiresInSec = tokenJson.optLong("expires_in", 3600L).coerceAtLeast(60L)
            if (accessToken.isBlank()) {
                val msg = "Spotify returned an empty access token. Please retry connecting."
                clearPendingAuth(prefs)
                _statusFlow.value = readStatusFromPrefs(prefs, currentError = msg, isAuthorizing = false)
                return false
            }

            val expiresAtMs = System.currentTimeMillis() + (expiresInSec * 1000L)
            // Retry /v1/me once on transient hiccup so Premium users are never falsely demoted
            val profile = fetchSpotifyProfile(accessToken) ?: run {
                runCatching { Thread.sleep(350L) }
                fetchSpotifyProfile(accessToken)
            }
            val displayName = profile?.second?.ifBlank { "Spotify Listener" }
                ?: prefs.getString(KEY_DISPLAY_NAME, "")?.ifBlank { "Spotify Listener" }
                ?: "Spotify Listener"
            val spotifyUserId = profile?.first?.trim().orEmpty()
                .ifBlank { prefs.getString(KEY_SPOTIFY_USER_ID, "")?.trim().orEmpty() }
            val product = profile?.third?.trim().orEmpty()
                .ifBlank { prefs.getString(KEY_PRODUCT, "")?.trim().orEmpty() }
            val explicitlyFree = product.equals("free", ignoreCase = true) || product.equals("open", ignoreCase = true)
            val isPremium = !explicitlyFree && (product.equals("premium", ignoreCase = true) || product.isBlank())

            val activeDevice = if (isPremium) findBestConnectDevice(accessToken) else null

            val editor = prefs.edit()
                .putString(KEY_ACTIVE_AUTH_CLIENT_ID, clientId)
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putLong(KEY_EXPIRES_AT_MS, expiresAtMs)
                .putString(KEY_SPOTIFY_USER_ID, spotifyUserId)
                .putString(KEY_DISPLAY_NAME, displayName)
                .putString(KEY_PRODUCT, product)
                .putBoolean(KEY_IS_PREMIUM, isPremium)
                .putLong(KEY_CONNECTED_AT_MS, System.currentTimeMillis())
                .remove(KEY_PENDING_VERIFIER)
                .remove(KEY_PENDING_STATE)
            if (refreshToken.isNotBlank()) {
                editor.putString(KEY_REFRESH_TOKEN, refreshToken)
            }
            if (activeDevice != null) {
                editor.putString(KEY_LAST_DEVICE_NAME, activeDevice.second)
            }
            editor.apply()

            // Treat Spotify Free as a clean first-class connected tier (no scary error warning!)
            _statusFlow.value = readStatusFromPrefs(
                prefs = prefs,
                currentError = null,
                errorHintType = SpotifyErrorHintType.NONE,
                isAuthorizing = false
            )
            // Persist encrypted refresh token to TuneMind cloud + schedule periodic keep-alive
            backupConnectionToCloud(context)
            com.tunemind.app.sync.SyncScheduler.scheduleSpotifyTokenKeepAlive(context)
            Log.i(TAG, "✅ Spotify Direct Connect authorized for '$displayName' (product=$product, premium=$isPremium)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Spotify OAuth callback exchange error: ${e.message}", e)
            clearPendingAuth(prefs)
            val (friendly, hint) = mapFriendlyError("Network error completing Spotify sign-in: ${e.message}")
            _statusFlow.value = readStatusFromPrefs(
                prefs = prefs,
                currentError = friendly,
                errorHintType = hint,
                isAuthorizing = false
            )
            false
        }
    }

    private fun clearPendingAuth(prefs: SharedPreferences) {
        prefs.edit()
            .remove(KEY_PENDING_VERIFIER)
            .remove(KEY_PENDING_STATE)
            .apply()
    }

    fun disconnect(context: Context) {
        val prefs = getPrefs(context)
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT_MS)
            .remove(KEY_SPOTIFY_USER_ID)
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_PRODUCT)
            .remove(KEY_IS_PREMIUM)
            .remove(KEY_CONNECTED_AT_MS)
            .remove(KEY_LAST_DEVICE_NAME)
            .remove(KEY_CLOUD_SYNCED_MS)
            .remove(KEY_PENDING_VERIFIER)
            .remove(KEY_PENDING_STATE)
            .apply()
        _statusFlow.value = readStatusFromPrefs(prefs, currentError = null, isAuthorizing = false)
        deleteCloudConnectionAsync(context)
        Log.i(TAG, "Disconnected Spotify Direct Connect session (local + cloud).")
    }

    /**
     * Checks live Spotify Connect devices so the user can immediately verify silent playback readiness.
     * Must be invoked on a background/IO thread.
     */
    fun checkActiveDevice(context: Context): SpotifyDeviceCheckResult {
        val status = ensureLoaded(context)
        if (!status.isConnected) {
            return SpotifyDeviceCheckResult(
                hasActiveDevice = false,
                message = "Spotify is not connected yet."
            )
        }
        val token = refreshAccessTokenIfNeeded(context)
            ?: return SpotifyDeviceCheckResult(
                hasActiveDevice = false,
                message = "Spotify session expired — reconnect in Settings."
            )
        val dev = findBestConnectDeviceWithType(token)
        return if (dev != null) {
            val prefs = getPrefs(context)
            prefs.edit().putString(KEY_LAST_DEVICE_NAME, dev.second).apply()
            _statusFlow.value = readStatusFromPrefs(prefs, currentError = null, isAuthorizing = false)
            SpotifyDeviceCheckResult(
                hasActiveDevice = true,
                deviceName = dev.second,
                deviceType = dev.third,
                message = "Ready for silent playback on ${dev.second}${if (dev.third.isNotBlank()) " (${dev.third})" else ""}."
            )
        } else {
            SpotifyDeviceCheckResult(
                hasActiveDevice = false,
                message = "No active Spotify device found yet. Tap 'Wake Spotify' once so Spotify registers this phone."
            )
        }
    }

    private fun fetchSpotifyProfile(accessToken: String): Triple<String, String, String>? {
        return try {
            val req = Request.Builder()
                .url("$API_BASE/me")
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Spotify /v1/me returned HTTP ${resp.code}")
                    return null
                }
                val json = JSONObject(resp.body?.string().orEmpty())
                val userId = json.optString("id").trim()
                val name = json.optString("display_name").ifBlank { userId }
                val product = json.optString("product").trim()
                Triple(userId, name, product)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch Spotify /v1/me profile: ${e.message}")
            null
        }
    }

    fun getSpotifyUserId(context: Context): String? {
        val prefs = getPrefs(context)
        val cached = prefs.getString(KEY_SPOTIFY_USER_ID, "")?.trim().orEmpty()
        if (cached.isNotBlank()) return cached
        val token = refreshAccessTokenIfNeeded(context) ?: return null
        val profile = fetchSpotifyProfile(token) ?: return null
        if (profile.first.isNotBlank()) {
            prefs.edit().putString(KEY_SPOTIFY_USER_ID, profile.first).apply()
            return profile.first
        }
        return null
    }

    /**
     * Returns a valid Bearer access token, automatically refreshing via PKCE refresh_token if expired.
     */
    fun refreshAccessTokenIfNeeded(context: Context, forceRefresh: Boolean = false): String? {
        synchronized(tokenRefreshLock) {
            val prefs = getPrefs(context)
            val existingToken = prefs.getString(KEY_ACCESS_TOKEN, "")?.trim().orEmpty()
            val expiresAtMs = prefs.getLong(KEY_EXPIRES_AT_MS, 0L)
            if (!forceRefresh && existingToken.isNotBlank() && System.currentTimeMillis() < (expiresAtMs - 60_000L)) {
                return existingToken
            }

            val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, "")?.trim().orEmpty()
            val clientId = prefs.getString(KEY_ACTIVE_AUTH_CLIENT_ID, "")?.trim().orEmpty()
                .ifBlank {
                    resolveEffectiveClientId(
                        prefs.getString(KEY_CLIENT_ID, ""),
                        getBundledBuildClientId().ifBlank { prefs.getString(KEY_APP_DEFAULT_CLIENT_ID, "") }
                    )
                }
            if (refreshToken.isBlank() || clientId.isBlank()) {
                return if (!forceRefresh && existingToken.isNotBlank()) existingToken else null
            }

            return try {
                val formBody = FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refreshToken)
                    .add("client_id", clientId)
                    .build()
                val req = Request.Builder()
                    .url(TOKEN_ENDPOINT)
                    .post(formBody)
                    .header("Accept", "application/json")
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val errCode = runCatching { JSONObject(bodyStr).optString("error").trim() }.getOrDefault("")
                        val errDesc = runCatching { JSONObject(bodyStr).optString("error_description").trim() }.getOrDefault("")
                        Log.w(TAG, "Spotify token refresh failed (HTTP ${resp.code}, error=$errCode): $bodyStr")

                        // CRITICAL: Only wipe stored refresh token when Spotify explicitly reports
                        // invalid_grant (revoked by user). Never wipe on transient network / 429 / 5xx / hiccups!
                        val isExplicitlyRevoked = errCode.equals("invalid_grant", ignoreCase = true) ||
                            errDesc.contains("revoked", ignoreCase = true)
                        if (isExplicitlyRevoked) {
                            prefs.edit()
                                .remove(KEY_ACCESS_TOKEN)
                                .remove(KEY_REFRESH_TOKEN)
                                .remove(KEY_EXPIRES_AT_MS)
                                .apply()
                            _statusFlow.value = readStatusFromPrefs(
                                prefs = prefs,
                                currentError = "Spotify access was revoked or expired. Tap Connect Spotify to relink.",
                                errorHintType = SpotifyErrorHintType.REVOKED,
                                isAuthorizing = false
                            )
                        } else if (errCode.equals("invalid_client", ignoreCase = true)) {
                            _statusFlow.value = readStatusFromPrefs(
                                prefs = prefs,
                                currentError = "Spotify Client ID mismatch — review your Client ID in Settings.",
                                errorHintType = SpotifyErrorHintType.INVALID_CLIENT,
                                isAuthorizing = false
                            )
                        }
                        return null
                    }
                    val json = JSONObject(bodyStr)
                    val newToken = json.optString("access_token").trim()
                    val rotatedRefresh = json.optString("refresh_token").trim()
                    val expiresIn = json.optLong("expires_in", 3600L).coerceAtLeast(60L)
                    if (newToken.isBlank()) return null

                    val editor = prefs.edit()
                        .putString(KEY_ACCESS_TOKEN, newToken)
                        .putLong(KEY_EXPIRES_AT_MS, System.currentTimeMillis() + (expiresIn * 1000L))
                    if (rotatedRefresh.isNotBlank()) {
                        editor.putString(KEY_REFRESH_TOKEN, rotatedRefresh)
                    }
                    editor.apply()

                    // Re-verify profile if product or userId wasn't known yet
                    if (prefs.getString(KEY_PRODUCT, "").isNullOrBlank() || prefs.getString(KEY_SPOTIFY_USER_ID, "").isNullOrBlank()) {
                        fetchSpotifyProfile(newToken)?.let { (uid, name, product) ->
                            val isPrem = product.equals("premium", ignoreCase = true)
                            prefs.edit()
                                .putString(KEY_SPOTIFY_USER_ID, uid)
                                .putString(KEY_DISPLAY_NAME, name)
                                .putString(KEY_PRODUCT, product)
                                .putBoolean(KEY_IS_PREMIUM, isPrem)
                                .apply()
                        }
                    }

                    _statusFlow.value = readStatusFromPrefs(prefs, currentError = null, isAuthorizing = false)

                    // Keep encrypted cloud backup updated whenever Spotify rotates the refresh token
                    if (rotatedRefresh.isNotBlank()) {
                        val appCtx = context.applicationContext
                        Thread { backupConnectionToCloud(appCtx) }.start()
                    }
                    newToken
                }
            } catch (e: Exception) {
                Log.w(TAG, "Spotify token refresh exception (preserving saved session): ${e.message}")
                null
            }
        }
    }

    /**
     * Resolves an exact `spotify:track:<22-char-id>` URI using Spotify's `/v1/search` endpoint.
     */
    fun searchExactTrackUri(
        context: Context,
        title: String,
        artist: String = "",
        query: String = ""
    ): String? {
        val token = refreshAccessTokenIfNeeded(context) ?: return null
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        val searchExpr = when {
            cleanTitle.isNotBlank() && cleanArtist.isNotBlank() -> "track:$cleanTitle artist:$cleanArtist"
            cleanTitle.isNotBlank() -> cleanTitle
            query.isNotBlank() -> query.trim()
            else -> return null
        }

        fun runSearch(expr: String): String? {
            val encoded = URLEncoder.encode(expr, "UTF-8")
            val req = Request.Builder()
                .url("$API_BASE/search?q=$encoded&type=track&limit=5")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .get()
                .build()
            return try {
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    val root = JSONObject(resp.body?.string().orEmpty())
                    val items = root.optJSONObject("tracks")?.optJSONArray("items") ?: return null
                    if (items.length() == 0) return null

                    var bestUri: String? = null
                    var bestScore = -1
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        val uri = item.optString("uri").trim()
                        if (!uri.startsWith("spotify:track:")) continue
                        val itemTitle = item.optString("name").trim()
                        val artistsArr = item.optJSONArray("artists")
                        val itemArtist = buildString {
                            if (artistsArr != null) {
                                for (a in 0 until artistsArr.length()) {
                                    if (a > 0) append(" ")
                                    append(artistsArr.optJSONObject(a)?.optString("name").orEmpty())
                                }
                            }
                        }
                        var score = 10 - i
                        if (cleanTitle.isNotBlank() && itemTitle.equals(cleanTitle, ignoreCase = true)) score += 30
                        else if (cleanTitle.isNotBlank() && itemTitle.contains(cleanTitle, ignoreCase = true)) score += 15
                        if (cleanArtist.isNotBlank() && itemArtist.contains(cleanArtist, ignoreCase = true)) score += 25
                        if (score > bestScore) {
                            bestScore = score
                            bestUri = uri
                        }
                    }
                    bestUri
                }
            } catch (e: Exception) {
                Log.w(TAG, "Spotify search failed for '$expr': ${e.message}")
                null
            }
        }

        return runSearch(searchExpr) ?: run {
            val fallbackExpr = listOf(cleanTitle, cleanArtist).filter { it.isNotBlank() }.joinToString(" ").ifBlank { query.trim() }
            if (fallbackExpr.isNotBlank() && fallbackExpr != searchExpr) runSearch(fallbackExpr) else null
        }
    }

    private fun extractTrackIdFromUri(uri: String?): String? {
        val normalized = MusicPlayerAutoLauncher.extractSpotifyTrackUri(uri) ?: return null
        return normalized.substringAfter("spotify:track:").takeIf { Regex("^[A-Za-z0-9]{22}$").matches(it) }
    }

    private fun findBestConnectDevice(accessToken: String): Pair<String, String>? {
        val dev = findBestConnectDeviceWithType(accessToken) ?: return null
        return dev.first to dev.second
    }

    private fun findBestConnectDeviceWithType(accessToken: String): Triple<String, String, String>? {
        return try {
            val req = Request.Builder()
                .url("$API_BASE/me/player/devices")
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val devices = JSONObject(resp.body?.string().orEmpty()).optJSONArray("devices") ?: return null
                var activeDevice: Triple<String, String, String>? = null
                var smartphoneDevice: Triple<String, String, String>? = null
                var fallbackDevice: Triple<String, String, String>? = null

                for (i in 0 until devices.length()) {
                    val dev = devices.optJSONObject(i) ?: continue
                    if (dev.optBoolean("is_restricted", false)) continue
                    val id = dev.optString("id").trim()
                    if (id.isBlank()) continue
                    val name = dev.optString("name").ifBlank { "Spotify Connect" }
                    val type = dev.optString("type").trim()
                    val isActive = dev.optBoolean("is_active", false)

                    if (isActive && activeDevice == null) {
                        activeDevice = Triple(id, name, type)
                    }
                    if (type.equals("Smartphone", ignoreCase = true) && smartphoneDevice == null) {
                        smartphoneDevice = Triple(id, name, type)
                    }
                    if (fallbackDevice == null) {
                        fallbackDevice = Triple(id, name, type)
                    }
                }
                activeDevice ?: smartphoneDevice ?: fallbackDevice
            }
        } catch (e: Exception) {
            Log.w(TAG, "Spotify device discovery failed: ${e.message}")
            null
        }
    }

    /**
     * Plays a full-length Spotify track silently in the background on the user's active Spotify
     * Connect device WITHOUT leaving TuneMind.
     */
    fun playTrackSilently(
        context: Context,
        title: String,
        artist: String = "",
        query: String = "",
        spotifyUri: String? = null
    ): SpotifyPlayResult {
        val status = ensureLoaded(context)
        if (!status.isConnected) return SpotifyPlayResult.NotConnected
        if (!status.isPremium) {
            return SpotifyPlayResult.PremiumRequired("Spotify Premium is required for silent background playback.")
        }

        var token = refreshAccessTokenIfNeeded(context)
            ?: return SpotifyPlayResult.Failed("Spotify token unavailable — reconnect in Settings.")

        // If /v1/me product was unknown when token was saved, verify it once now.
        if (status.product.isBlank()) {
            fetchSpotifyProfile(token)?.let { (uid, name, prod) ->
                val prefs = getPrefs(context)
                val verifiedPremium = prod.equals("premium", ignoreCase = true)
                prefs.edit()
                    .putString(KEY_SPOTIFY_USER_ID, uid)
                    .putString(KEY_DISPLAY_NAME, name)
                    .putString(KEY_PRODUCT, prod)
                    .putBoolean(KEY_IS_PREMIUM, verifiedPremium)
                    .apply()
                _statusFlow.value = readStatusFromPrefs(prefs, currentError = null, isAuthorizing = false)
                if (prod.equals("free", ignoreCase = true) || prod.equals("open", ignoreCase = true)) {
                    return SpotifyPlayResult.PremiumRequired("Spotify Premium is required for silent background playback.")
                }
            }
        }

        val resolvedUri = MusicPlayerAutoLauncher.extractSpotifyTrackUri(spotifyUri)
            ?: searchExactTrackUri(context, title, artist, query)
            ?: return SpotifyPlayResult.Failed("Could not locate '$title' on Spotify.")

        var device = findBestConnectDevice(token)

        // If Spotify Connect currently reports no awake device, try a silent MediaSession wake pulse first.
        if (device == null) {
            val listener = com.tunemind.app.service.TuneMindListenerService.instance
            val hasWarmSpotify = listener?.hasActiveSession("com.spotify.music") == true ||
                listener?.hasActiveSession("com.spotify.lite") == true
            if (hasWarmSpotify) {
                try {
                    Log.i(TAG, "Spotify Connect idle but MediaSession warm — sending silent wake pulse before Connect play")
                    listener?.play("com.spotify.music")
                    Thread.sleep(850L)
                    device = findBestConnectDevice(token)
                } catch (_: Throwable) {}
            }
        }

        fun executePlay(activeToken: String, targetDeviceId: String?): okhttp3.Response {
            val url = buildString {
                append("$API_BASE/me/player/play")
                if (!targetDeviceId.isNullOrBlank()) {
                    append("?device_id=").append(URLEncoder.encode(targetDeviceId, "UTF-8"))
                }
            }
            val payload = JSONObject().apply {
                put("uris", JSONArray().put(resolvedUri))
            }.toString()
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $activeToken")
                .header("Content-Type", "application/json")
                .put(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            return httpClient.newCall(req).execute()
        }

        return try {
            var response = executePlay(token, device?.first)
            if (response.code == 401) {
                response.close()
                token = refreshAccessTokenIfNeeded(context, forceRefresh = true)
                    ?: return SpotifyPlayResult.Failed("Spotify session expired — reconnect in Settings.")
                device = findBestConnectDevice(token)
                response = executePlay(token, device?.first)
            }

            // If Spotify returned 404 NO_ACTIVE_DEVICE on a stale/missing device_id, retry once after refreshing device list
            if (response.code == 404) {
                response.close()
                val retryListener = com.tunemind.app.service.TuneMindListenerService.instance
                if (retryListener?.hasActiveSession("com.spotify.music") == true) {
                    runCatching {
                        retryListener.play("com.spotify.music")
                        Thread.sleep(750L)
                    }
                }
                device = findBestConnectDevice(token)
                if (device != null) {
                    response = executePlay(token, device.first)
                } else {
                    return SpotifyPlayResult.NoActiveDevice(
                        message = "No active Spotify device found. Open Spotify once so your phone wakes up for silent playback.",
                        trackUri = resolvedUri
                    )
                }
            }

            val deviceName = device?.second ?: "Spotify"
            response.use { resp ->
                if (resp.isSuccessful || resp.code == 204 || resp.code == 202) {
                    val prefs = getPrefs(context)
                    prefs.edit().putString(KEY_LAST_DEVICE_NAME, deviceName).apply()
                    _statusFlow.value = readStatusFromPrefs(prefs, currentError = null, isAuthorizing = false)
                    Log.i(TAG, "✅ Spotify Premium Direct Connect playing '$resolvedUri' silently on '$deviceName'")
                    return SpotifyPlayResult.Success(resolvedUri, deviceName)
                }

                val errBody = resp.body?.string().orEmpty()
                val reason = runCatching {
                    JSONObject(errBody).optJSONObject("error")?.optString("reason").orEmpty()
                }.getOrDefault("")
                val message = runCatching {
                    JSONObject(errBody).optJSONObject("error")?.optString("message").orEmpty()
                }.getOrDefault("")

                Log.w(TAG, "Spotify /v1/me/player/play returned HTTP ${resp.code} (reason=$reason, msg=$message)")
                when {
                    resp.code == 404 || reason.equals("NO_ACTIVE_DEVICE", ignoreCase = true) -> {
                        SpotifyPlayResult.NoActiveDevice(
                            message = "No active Spotify device found. Open Spotify once so your phone wakes up for silent playback.",
                            trackUri = resolvedUri
                        )
                    }
                    resp.code == 403 && reason.equals("PREMIUM_REQUIRED", ignoreCase = true) -> {
                        // Cleanly switch to Spotify Free Smart Mode without scary error banners
                        val prefs = getPrefs(context)
                        prefs.edit().putBoolean(KEY_IS_PREMIUM, false).putString(KEY_PRODUCT, "free").apply()
                        _statusFlow.value = readStatusFromPrefs(
                            prefs = prefs,
                            currentError = null,
                            errorHintType = SpotifyErrorHintType.NONE,
                            isAuthorizing = false
                        )
                        val appCtx = context.applicationContext
                        Thread { backupConnectionToCloud(appCtx) }.start()
                        SpotifyPlayResult.PremiumRequired("Spotify Free Smart Mode active — opening full track in Spotify.")
                    }
                    else -> {
                        val (friendly, _) = mapFriendlyError(message.ifBlank { "Spotify Connect returned HTTP ${resp.code}" }, resp.code)
                        SpotifyPlayResult.Failed(friendly)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Spotify silent play exception: ${e.message}")
            SpotifyPlayResult.Failed("Spotify Connect network error: ${e.message}")
        }
    }

    /**
     * Pushes a track directly into the user's live Spotify queue (`POST /v1/me/player/queue`).
     */
    fun queueTrack(
        context: Context,
        title: String,
        artist: String = "",
        spotifyUri: String? = null
    ): Boolean {
        if (!isConnectedAndPremium(context)) return false
        val token = refreshAccessTokenIfNeeded(context) ?: return false
        val resolvedUri = MusicPlayerAutoLauncher.extractSpotifyTrackUri(spotifyUri)
            ?: searchExactTrackUri(context, title, artist)
            ?: return false
        val deviceId = findBestConnectDevice(token)?.first

        val url = buildString {
            append("$API_BASE/me/player/queue?uri=").append(URLEncoder.encode(resolvedUri, "UTF-8"))
            if (!deviceId.isNullOrBlank()) {
                append("&device_id=").append(URLEncoder.encode(deviceId, "UTF-8"))
            }
        }
        return executeSimpleSpotifyCommand(context, token, "POST", url)
    }

    /**
     * Saves a track directly to the user's Spotify Liked Songs (`PUT /v1/me/tracks`).
     */
    fun likeTrackOnSpotify(
        context: Context,
        title: String,
        artist: String = "",
        spotifyUri: String? = null
    ): Boolean {
        if (!isConnected(context)) return false
        val token = refreshAccessTokenIfNeeded(context) ?: return false
        val resolvedUri = MusicPlayerAutoLauncher.extractSpotifyTrackUri(spotifyUri)
            ?: searchExactTrackUri(context, title, artist)
            ?: return false
        val trackId = extractTrackIdFromUri(resolvedUri) ?: return false

        val bodyJson = JSONObject().put("ids", JSONArray().put(trackId)).toString()
        return executeSimpleSpotifyCommand(
            context = context,
            initialToken = token,
            method = "PUT",
            url = "$API_BASE/me/tracks?ids=$trackId",
            jsonBody = bodyJson
        )
    }

    /**
     * Removes a track from the user's Spotify Liked Songs (`DELETE /v1/me/tracks`).
     */
    fun unlikeTrackOnSpotify(
        context: Context,
        title: String,
        artist: String = "",
        spotifyUri: String? = null
    ): Boolean {
        if (!isConnected(context)) return false
        val token = refreshAccessTokenIfNeeded(context) ?: return false
        val resolvedUri = MusicPlayerAutoLauncher.extractSpotifyTrackUri(spotifyUri)
            ?: searchExactTrackUri(context, title, artist)
            ?: return false
        val trackId = extractTrackIdFromUri(resolvedUri) ?: return false

        val bodyJson = JSONObject().put("ids", JSONArray().put(trackId)).toString()
        return executeSimpleSpotifyCommand(
            context = context,
            initialToken = token,
            method = "DELETE",
            url = "$API_BASE/me/tracks?ids=$trackId",
            jsonBody = bodyJson
        )
    }

    /**
     * Plays an entire Spotify playlist context silently on the user's active Spotify Connect device (Premium).
     */
    fun playPlaylistContextSilently(
        context: Context,
        spotifyPlaylistId: String
    ): SpotifyPlayResult {
        val cleanId = spotifyPlaylistId.trim()
        if (cleanId.isBlank()) return SpotifyPlayResult.Failed("Invalid Spotify playlist ID.")
        val status = ensureLoaded(context)
        if (!status.isConnected) return SpotifyPlayResult.NotConnected
        if (!status.isPremium) {
            return SpotifyPlayResult.PremiumRequired("Spotify Premium is required for silent playlist playback.")
        }
        var token = refreshAccessTokenIfNeeded(context)
            ?: return SpotifyPlayResult.Failed("Spotify token unavailable — reconnect in Settings.")
        val device = findBestConnectDevice(token)
        val contextUri = "spotify:playlist:$cleanId"

        fun executePlay(activeToken: String, targetDeviceId: String?): okhttp3.Response {
            val url = buildString {
                append("$API_BASE/me/player/play")
                if (!targetDeviceId.isNullOrBlank()) {
                    append("?device_id=").append(URLEncoder.encode(targetDeviceId, "UTF-8"))
                }
            }
            val payload = JSONObject().apply {
                put("context_uri", contextUri)
            }.toString()
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $activeToken")
                .header("Content-Type", "application/json")
                .put(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            return httpClient.newCall(req).execute()
        }

        return try {
            var response = executePlay(token, device?.first)
            if (response.code == 401) {
                response.close()
                token = refreshAccessTokenIfNeeded(context, forceRefresh = true)
                    ?: return SpotifyPlayResult.Failed("Spotify session expired.")
                response = executePlay(token, findBestConnectDevice(token)?.first)
            }
            response.use { resp ->
                if (resp.isSuccessful || resp.code == 204 || resp.code == 202) {
                    SpotifyPlayResult.Success(contextUri, device?.second ?: "Spotify")
                } else if (resp.code == 404) {
                    SpotifyPlayResult.NoActiveDevice("No active Spotify device found.", contextUri)
                } else {
                    SpotifyPlayResult.Failed("Spotify playlist playback returned HTTP ${resp.code}")
                }
            }
        } catch (e: Exception) {
            SpotifyPlayResult.Failed("Spotify playlist playback error: ${e.message}")
        }
    }

    // ── FULL SPOTIFY LIBRARY SYNC & 2-WAY PLAYLIST EXPORT (WORKS FOR FREE & PREMIUM!) ────────

    private fun parseIso8601ToMillis(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val clean = raw.trim()
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX"
        )
        for (pattern in formats) {
            try {
                val sdf = java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                val parsed = sdf.parse(clean)
                if (parsed != null) return parsed.time
            } catch (_: Throwable) {}
        }
        return 0L
    }

    private fun parseSpotifyTrackObject(trackObj: JSONObject?, addedAtMs: Long = 0L): SpotifySyncedTrack? {
        if (trackObj == null) return null
        if (trackObj.optBoolean("is_local", false)) return null
        val type = trackObj.optString("type", "track")
        if (type.isNotBlank() && !type.equals("track", ignoreCase = true)) return null

        val title = trackObj.optString("name").trim()
        if (title.isBlank()) return null
        val id = trackObj.optString("id").trim()
        val uri = trackObj.optString("uri").trim().ifBlank {
            if (id.isNotBlank()) "spotify:track:$id" else ""
        }
        if (!uri.startsWith("spotify:track:")) return null

        val artistsArr = trackObj.optJSONArray("artists")
        val artistNames = mutableListOf<String>()
        if (artistsArr != null) {
            for (i in 0 until artistsArr.length()) {
                val n = artistsArr.optJSONObject(i)?.optString("name")?.trim().orEmpty()
                if (n.isNotBlank()) artistNames.add(n)
            }
        }
        val artist = artistNames.joinToString(", ").ifBlank { "Unknown Artist" }
        val albumObj = trackObj.optJSONObject("album")
        val albumName = albumObj?.optString("name")?.trim().orEmpty()
        val imagesArr = albumObj?.optJSONArray("images")
        val albumArtUrl = if (imagesArr != null && imagesArr.length() > 0) {
            imagesArr.optJSONObject(0)?.optString("url")?.trim()?.takeIf { it.isNotBlank() }
        } else null
        val durationMs = trackObj.optLong("duration_ms", 0L).coerceAtLeast(0L)
        val previewUrl = trackObj.optString("preview_url").trim().takeIf { it.isNotBlank() && it != "null" }
        val popularity = trackObj.optInt("popularity", 0).coerceIn(0, 100)

        return SpotifySyncedTrack(
            spotifyId = id.ifBlank { uri.substringAfter("spotify:track:") },
            spotifyUri = uri,
            title = title,
            artist = artist,
            album = albumName,
            durationMs = durationMs,
            albumArtUrl = albumArtUrl,
            previewUrl = previewUrl,
            popularity = popularity,
            addedAtMs = addedAtMs
        )
    }

    private fun executeAuthorizedGetJson(context: Context, url: String): Pair<Int, JSONObject?> {
        var token = refreshAccessTokenIfNeeded(context) ?: return 401 to null
        fun runOnce(activeToken: String): okhttp3.Response {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $activeToken")
                .header("Accept", "application/json")
                .get()
                .build()
            return httpClient.newCall(req).execute()
        }
        return try {
            var resp = runOnce(token)
            if (resp.code == 401) {
                resp.close()
                token = refreshAccessTokenIfNeeded(context, forceRefresh = true) ?: return 401 to null
                resp = runOnce(token)
            }
            resp.use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    Log.w(TAG, "Spotify GET $url returned HTTP ${r.code}")
                    r.code to null
                } else {
                    r.code to runCatching { JSONObject(body) }.getOrNull()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Spotify GET $url failed: ${e.message}")
            0 to null
        }
    }

    /**
     * Fetches the user's Spotify Liked Songs (`GET /v1/me/tracks`, paginated up to [maxTracks]).
     * Works on both Spotify Free and Spotify Premium connected accounts.
     */
    fun fetchLikedTracks(
        context: Context,
        maxTracks: Int = 300,
        onProgress: ((fetchedCount: Int) -> Unit)? = null
    ): List<SpotifySyncedTrack> {
        if (!isConnected(context)) return emptyList()
        val results = mutableListOf<SpotifySyncedTrack>()
        var nextUrl: String? = "$API_BASE/me/tracks?limit=50&offset=0"
        var pages = 0
        while (!nextUrl.isNullOrBlank() && results.size < maxTracks && pages < 10) {
            pages++
            val (_, json) = executeAuthorizedGetJson(context, nextUrl)
            if (json == null) break
            val items = json.optJSONArray("items") ?: break
            for (i in 0 until items.length()) {
                val entry = items.optJSONObject(i) ?: continue
                val addedAt = parseIso8601ToMillis(entry.optString("added_at"))
                val trackObj = entry.optJSONObject("track") ?: entry.optJSONObject("item")
                val parsed = parseSpotifyTrackObject(trackObj, addedAt)
                if (parsed != null) {
                    results.add(parsed)
                    if (results.size >= maxTracks) break
                }
            }
            onProgress?.invoke(results.size)
            val rawNext = json.optString("next").trim()
            nextUrl = rawNext.takeIf { it.startsWith("https://") }
        }
        return results
    }

    /**
     * Fetches the user's Spotify playlists and their tracks (GET /v1/me/playlists + /v1/playlists/{id}/items).
     * Fully compatible with Spotify's Feb 2026 Web API migration (items field & /items path)
     * and works on both Spotify Free and Spotify Premium connected accounts.
     */
    fun fetchUserPlaylistsWithTracks(
        context: Context,
        maxPlaylists: Int = 30,
        maxTracksPerPlaylist: Int = 100,
        onProgress: ((completedPlaylists: Int, totalDiscovered: Int) -> Unit)? = null
    ): List<SpotifySyncedPlaylist> {
        if (!isConnected(context)) return emptyList()
        val currentUserId = getSpotifyUserId(context).orEmpty().trim()
        val playlistMeta = mutableListOf<SpotifySyncedPlaylist>()
        var nextUrl: String? = "$API_BASE/me/playlists?limit=50&offset=0"
        var pages = 0

        while (!nextUrl.isNullOrBlank() && playlistMeta.size < maxPlaylists && pages < 4) {
            pages++
            val (_, json) = executeAuthorizedGetJson(context, nextUrl)
            if (json == null) break
            val items = json.optJSONArray("items") ?: break
            for (i in 0 until items.length()) {
                val pl = items.optJSONObject(i) ?: continue
                val plId = pl.optString("id").trim()
                val plName = pl.optString("name").trim()
                if (plId.isBlank() || plName.isBlank()) continue
                val desc = pl.optString("description").trim()
                val ownerObj = pl.optJSONObject("owner")
                val ownerId = ownerObj?.optString("id")?.trim().orEmpty()
                val ownerName = ownerObj?.optString("display_name")?.trim().orEmpty().ifBlank { ownerId }
                val collaborative = pl.optBoolean("collaborative", false)
                val isOwnedOrCollab = collaborative ||
                    currentUserId.isBlank() ||
                    ownerId.isBlank() ||
                    ownerId.equals(currentUserId, ignoreCase = true)
                val imgs = pl.optJSONArray("images")
                val imageUrl = if (imgs != null && imgs.length() > 0) {
                    imgs.optJSONObject(0)?.optString("url")?.trim()?.takeIf { it.isNotBlank() }
                } else null
                // Feb 2026 Spotify API moved `tracks.total` -> `items.total`
                val countContainer = pl.optJSONObject("items") ?: pl.optJSONObject("tracks")
                val totalTracks = countContainer?.optInt("total", 0) ?: 0
                playlistMeta.add(
                    SpotifySyncedPlaylist(
                        spotifyId = plId,
                        name = plName,
                        description = desc,
                        ownerName = ownerName,
                        imageUrl = imageUrl,
                        totalTracks = totalTracks,
                        isOwnedOrCollaborative = isOwnedOrCollab
                    )
                )
                if (playlistMeta.size >= maxPlaylists) break
            }
            val rawNext = json.optString("next").trim()
            nextUrl = rawNext.takeIf { it.startsWith("https://") }
        }

        val hydrated = mutableListOf<SpotifySyncedPlaylist>()
        playlistMeta.forEachIndexed { idx, pl ->
            val tracks = fetchPlaylistTracks(context, pl.spotifyId, maxTracks = maxTracksPerPlaylist)
            hydrated.add(pl.copy(tracks = tracks, totalTracks = maxOf(pl.totalTracks, tracks.size)))
            onProgress?.invoke(idx + 1, playlistMeta.size)
        }
        return hydrated
    }

    fun fetchPlaylistTracks(
        context: Context,
        spotifyPlaylistId: String,
        maxTracks: Int = 100
    ): List<SpotifySyncedTrack> {
        val cleanId = spotifyPlaylistId.trim()
        if (cleanId.isBlank()) return emptyList()
        val tracks = mutableListOf<SpotifySyncedTrack>()

        fun drainPages(initialUrl: String): Pair<Int, Boolean> {
            var nextUrl: String? = initialUrl
            var pages = 0
            var firstStatusCode = 0
            while (!nextUrl.isNullOrBlank() && tracks.size < maxTracks && pages < 4) {
                pages++
                val (code, json) = executeAuthorizedGetJson(context, nextUrl)
                if (pages == 1) firstStatusCode = code
                if (json == null) return firstStatusCode to (pages > 1)
                val items = json.optJSONArray("items") ?: return firstStatusCode to true
                for (i in 0 until items.length()) {
                    val entry = items.optJSONObject(i) ?: continue
                    val addedAt = parseIso8601ToMillis(entry.optString("added_at"))
                    // Feb 2026 Spotify API renamed `track` -> `item` inside playlist items
                    val trackObj = entry.optJSONObject("item") ?: entry.optJSONObject("track")
                    val parsed = parseSpotifyTrackObject(trackObj, addedAt)
                    if (parsed != null) {
                        tracks.add(parsed)
                        if (tracks.size >= maxTracks) break
                    }
                }
                val rawNext = json.optString("next").trim()
                nextUrl = rawNext.takeIf { it.startsWith("https://") }
            }
            return firstStatusCode to true
        }

        // Primary Feb 2026 endpoint: GET /v1/playlists/{id}/items
        val (primaryCode, primaryOk) = drainPages("$API_BASE/playlists/$cleanId/items?limit=100&offset=0&additional_types=track")
        if (!primaryOk && primaryCode == 404) {
            // Legacy fallback if an older proxy/region still serves /tracks
            drainPages("$API_BASE/playlists/$cleanId/tracks?limit=100&offset=0&additional_types=track")
        }

        // Extra fallback: read embedded items/tracks directly from GET /v1/playlists/{id} if empty
        if (tracks.isEmpty() && primaryCode !in listOf(401, 403)) {
            val (_, detailJson) = executeAuthorizedGetJson(context, "$API_BASE/playlists/$cleanId?additional_types=track")
            val container = detailJson?.optJSONObject("items") ?: detailJson?.optJSONObject("tracks")
            val embedded = container?.optJSONArray("items")
            if (embedded != null) {
                for (i in 0 until embedded.length()) {
                    val entry = embedded.optJSONObject(i) ?: continue
                    val addedAt = parseIso8601ToMillis(entry.optString("added_at"))
                    val trackObj = entry.optJSONObject("item") ?: entry.optJSONObject("track")
                    val parsed = parseSpotifyTrackObject(trackObj, addedAt)
                    if (parsed != null) {
                        tracks.add(parsed)
                        if (tracks.size >= maxTracks) break
                    }
                }
            }
        }
        return tracks
    }

    /**
     * Fetches Spotify Recently Played (`GET /v1/me/player/recently-played`).
     * Gracefully returns empty + `scopeMissing = true` if an older token doesn't have `user-read-recently-played`.
     */
    fun fetchRecentlyPlayedTracks(
        context: Context,
        limit: Int = 50
    ): Pair<List<SpotifySyncedTrack>, Boolean> {
        if (!isConnected(context)) return emptyList<SpotifySyncedTrack>() to false
        val clamped = limit.coerceIn(1, 50)
        val (code, json) = executeAuthorizedGetJson(context, "$API_BASE/me/player/recently-played?limit=$clamped")
        if (code == 403) return emptyList<SpotifySyncedTrack>() to true
        if (json == null) return emptyList<SpotifySyncedTrack>() to false
        val items = json.optJSONArray("items") ?: return emptyList<SpotifySyncedTrack>() to false
        val results = mutableListOf<SpotifySyncedTrack>()
        for (i in 0 until items.length()) {
            val entry = items.optJSONObject(i) ?: continue
            val playedAt = parseIso8601ToMillis(entry.optString("played_at"))
            val parsed = parseSpotifyTrackObject(entry.optJSONObject("track"), playedAt)
            if (parsed != null) results.add(parsed)
        }
        return results to false
    }

    /**
     * Fetches the user's Spotify Top Tracks (`GET /v1/me/top/tracks`).
     * Gracefully returns empty + `scopeMissing = true` if an older token doesn't have `user-top-read`.
     */
    fun fetchTopTracks(
        context: Context,
        limit: Int = 40
    ): Pair<List<SpotifySyncedTrack>, Boolean> {
        if (!isConnected(context)) return emptyList<SpotifySyncedTrack>() to false
        val clamped = limit.coerceIn(1, 50)
        val (code, json) = executeAuthorizedGetJson(context, "$API_BASE/me/top/tracks?limit=$clamped&time_range=medium_term")
        if (code == 403) return emptyList<SpotifySyncedTrack>() to true
        if (json == null) return emptyList<SpotifySyncedTrack>() to false
        val items = json.optJSONArray("items") ?: return emptyList<SpotifySyncedTrack>() to false
        val results = mutableListOf<SpotifySyncedTrack>()
        for (i in 0 until items.length()) {
            val parsed = parseSpotifyTrackObject(items.optJSONObject(i))
            if (parsed != null) results.add(parsed)
        }
        return results to false
    }

    /**
     * Fetches the user's Spotify Top Artists (`GET /v1/me/top/artists`).
     * Gracefully returns empty + `scopeMissing = true` if an older token doesn't have `user-top-read`.
     */
    fun fetchTopArtists(
        context: Context,
        limit: Int = 25
    ): Pair<List<SpotifySyncedArtist>, Boolean> {
        if (!isConnected(context)) return emptyList<SpotifySyncedArtist>() to false
        val clamped = limit.coerceIn(1, 50)
        val (code, json) = executeAuthorizedGetJson(context, "$API_BASE/me/top/artists?limit=$clamped&time_range=medium_term")
        if (code == 403) return emptyList<SpotifySyncedArtist>() to true
        if (json == null) return emptyList<SpotifySyncedArtist>() to false
        val items = json.optJSONArray("items") ?: return emptyList<SpotifySyncedArtist>() to false
        val results = mutableListOf<SpotifySyncedArtist>()
        for (i in 0 until items.length()) {
            val a = items.optJSONObject(i) ?: continue
            val id = a.optString("id").trim()
            val name = a.optString("name").trim()
            if (id.isBlank() || name.isBlank()) continue
            val imgs = a.optJSONArray("images")
            val imageUrl = if (imgs != null && imgs.length() > 0) {
                imgs.optJSONObject(0)?.optString("url")?.trim()?.takeIf { it.isNotBlank() }
            } else null
            val genresArr = a.optJSONArray("genres")
            val genres = mutableListOf<String>()
            if (genresArr != null) {
                for (g in 0 until genresArr.length()) {
                    val gv = genresArr.optString(g).trim()
                    if (gv.isNotBlank()) genres.add(gv)
                }
            }
            val popularity = a.optInt("popularity", 0).coerceIn(0, 100)
            val followers = a.optJSONObject("followers")?.optLong("total", 0L) ?: 0L
            results.add(
                SpotifySyncedArtist(
                    spotifyId = id,
                    name = name,
                    imageUrl = imageUrl,
                    genres = genres,
                    popularity = popularity,
                    followersTotal = followers
                )
            )
        }
        return results to false
    }

    /**
     * Exports any TuneMind playlist straight into the user's real Spotify account!
     * Works for BOTH Spotify Free and Spotify Premium connected users.
     *
     * @param tracks List of Triple(title, artist, optionalExistingExternalUri)
     */
    fun exportPlaylistToSpotify(
        context: Context,
        playlistName: String,
        description: String = "",
        tracks: List<Triple<String, String, String?>>,
        isPublic: Boolean = false
    ): SpotifyExportPlaylistResult {
        if (!isConnected(context)) {
            return SpotifyExportPlaylistResult(
                success = false,
                totalCount = tracks.size,
                message = "Connect Spotify in Settings first to export playlists."
            )
        }
        val token = refreshAccessTokenIfNeeded(context)
            ?: return SpotifyExportPlaylistResult(
                success = false,
                totalCount = tracks.size,
                message = "Spotify session expired — reconnect in Settings."
            )
        val userId = getSpotifyUserId(context)
            ?: return SpotifyExportPlaylistResult(
                success = false,
                totalCount = tracks.size,
                message = "Could not load your Spotify profile ID. Please try again."
            )

        val cleanName = playlistName.trim().ifBlank { "TuneMind Playlist" }
        val cleanDesc = description.trim().ifBlank { "Exported from TuneMind" }

        // 1. Create the playlist in the user's Spotify account (Feb 2026 endpoint: POST /v1/me/playlists)
        val createBody = JSONObject().apply {
            put("name", cleanName)
            put("description", cleanDesc)
            put("public", isPublic)
        }.toString()

        fun buildCreateRequest(url: String): Request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(createBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val (createdPlaylistId, createdSpotifyUrl) = try {
            var createResp = httpClient.newCall(buildCreateRequest("$API_BASE/me/playlists")).execute()
            if (createResp.code == 404) {
                createResp.close()
                createResp = httpClient.newCall(
                    buildCreateRequest("$API_BASE/users/${URLEncoder.encode(userId, "UTF-8")}/playlists")
                ).execute()
            }
            createResp.use { resp ->
                if (!resp.isSuccessful) {
                    val code = resp.code
                    return SpotifyExportPlaylistResult(
                        success = false,
                        totalCount = tracks.size,
                        message = if (code == 403) {
                            "Spotify permission needed to create playlists — reconnect Spotify once in Settings."
                        } else {
                            "Could not create Spotify playlist (HTTP $code)."
                        }
                    )
                }
                val json = JSONObject(resp.body?.string().orEmpty())
                val pid = json.optString("id").trim()
                val url = json.optJSONObject("external_urls")?.optString("spotify")?.trim()
                pid to url
            }
        } catch (e: Exception) {
            return SpotifyExportPlaylistResult(
                success = false,
                totalCount = tracks.size,
                message = "Network error creating Spotify playlist: ${e.message}"
            )
        }

        if (createdPlaylistId.isBlank()) {
            return SpotifyExportPlaylistResult(
                success = false,
                totalCount = tracks.size,
                message = "Spotify did not return a playlist ID."
            )
        }

        // 2. Resolve exact spotify:track:<id> URIs for every song
        val resolvedUris = mutableListOf<String>()
        tracks.forEach { (title, artist, existingUri) ->
            val direct = MusicPlayerAutoLauncher.extractSpotifyTrackUri(existingUri)
            val uri = direct ?: searchExactTrackUri(context, title, artist)
            if (!uri.isNullOrBlank() && uri.startsWith("spotify:track:") && !resolvedUris.contains(uri)) {
                resolvedUris.add(uri)
            }
        }

        // 3. Push tracks in batches of 100 (Feb 2026 endpoint: POST /v1/playlists/{id}/items)
        var addedTotal = 0
        resolvedUris.chunked(100).forEach { batch ->
            val payload = JSONObject().apply {
                put("uris", JSONArray(batch))
            }.toString()
            val okItems = executeSimpleSpotifyCommand(
                context = context,
                initialToken = token,
                method = "POST",
                url = "$API_BASE/playlists/$createdPlaylistId/items",
                jsonBody = payload
            )
            val ok = okItems || executeSimpleSpotifyCommand(
                context = context,
                initialToken = token,
                method = "POST",
                url = "$API_BASE/playlists/$createdPlaylistId/tracks",
                jsonBody = payload
            )
            if (ok) addedTotal += batch.size
        }

        val summary = if (tracks.isEmpty()) {
            "Created \"$cleanName\" in your Spotify account."
        } else {
            "Exported \"$cleanName\" to Spotify ($addedTotal/${tracks.size} songs matched)."
        }
        Log.i(TAG, "✅ $summary (id=$createdPlaylistId)")
        return SpotifyExportPlaylistResult(
            success = true,
            spotifyPlaylistId = createdPlaylistId,
            spotifyUrl = createdSpotifyUrl,
            addedCount = addedTotal,
            totalCount = tracks.size,
            message = summary
        )
    }

    fun pausePlayback(context: Context): Boolean {
        if (!isConnectedAndPremium(context)) return false
        val token = refreshAccessTokenIfNeeded(context) ?: return false
        return executeSimpleSpotifyCommand(context, token, "PUT", "$API_BASE/me/player/pause")
    }

    fun resumePlayback(context: Context): Boolean {
        if (!isConnectedAndPremium(context)) return false
        val token = refreshAccessTokenIfNeeded(context) ?: return false
        return executeSimpleSpotifyCommand(context, token, "PUT", "$API_BASE/me/player/play")
    }

    fun skipNext(context: Context): Boolean {
        if (!isConnectedAndPremium(context)) return false
        val token = refreshAccessTokenIfNeeded(context) ?: return false
        return executeSimpleSpotifyCommand(context, token, "POST", "$API_BASE/me/player/next")
    }

    fun skipPrevious(context: Context): Boolean {
        if (!isConnectedAndPremium(context)) return false
        val token = refreshAccessTokenIfNeeded(context) ?: return false
        return executeSimpleSpotifyCommand(context, token, "POST", "$API_BASE/me/player/previous")
    }

    fun setVolumePercent(context: Context, percent: Int): Boolean {
        if (!isConnectedAndPremium(context)) return false
        val token = refreshAccessTokenIfNeeded(context) ?: return false
        val clamped = percent.coerceIn(0, 100)
        return executeSimpleSpotifyCommand(context, token, "PUT", "$API_BASE/me/player/volume?volume_percent=$clamped")
    }

    private fun executeSimpleSpotifyCommand(
        context: Context,
        initialToken: String,
        method: String,
        url: String,
        jsonBody: String? = null
    ): Boolean {
        fun buildRequest(activeToken: String): Request {
            val builder = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $activeToken")
            val body = jsonBody?.toRequestBody(JSON_MEDIA_TYPE) ?: EMPTY_BODY
            if (jsonBody != null) {
                builder.header("Content-Type", "application/json")
            }
            when (method.uppercase()) {
                "POST" -> builder.post(body)
                "PUT" -> builder.put(body)
                "DELETE" -> builder.delete(body)
                else -> builder.get()
            }
            return builder.build()
        }

        return try {
            var response = httpClient.newCall(buildRequest(initialToken)).execute()
            if (response.code == 401) {
                response.close()
                val refreshed = refreshAccessTokenIfNeeded(context, forceRefresh = true) ?: return false
                response = httpClient.newCall(buildRequest(refreshed)).execute()
            }
            response.use { resp ->
                val ok = resp.isSuccessful || resp.code == 204 || resp.code == 202
                if (!ok) {
                    Log.w(TAG, "Spotify $method $url returned HTTP ${resp.code}")
                }
                ok
            }
        } catch (e: Exception) {
            Log.w(TAG, "Spotify $method $url exception: ${e.message}")
            false
        }
    }
}
