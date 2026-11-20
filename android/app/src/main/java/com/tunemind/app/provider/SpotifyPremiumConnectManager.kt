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
    val isAuthorizing: Boolean = false,
    val connectedAtMs: Long = 0L
) {
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
            "playlist-read-private user-read-private"

    private const val KEY_CLIENT_ID = "client_id"
    private const val KEY_APP_DEFAULT_CLIENT_ID = "app_default_client_id"
    private const val KEY_ACTIVE_AUTH_CLIENT_ID = "active_auth_client_id"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_EXPIRES_AT_MS = "expires_at_ms"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_PRODUCT = "product"
    private const val KEY_IS_PREMIUM = "is_premium"
    private const val KEY_CONNECTED_AT_MS = "connected_at_ms"
    private const val KEY_LAST_DEVICE_NAME = "last_device_name"
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
        val status = readStatusFromPrefs(
            prefs = prefs,
            currentError = _statusFlow.value.lastError,
            isAuthorizing = _statusFlow.value.isAuthorizing
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
            isAuthorizing = isAuthorizing,
            connectedAtMs = prefs.getLong(KEY_CONNECTED_AT_MS, 0L)
        )
    }

    fun isConnected(context: Context): Boolean {
        return ensureLoaded(context).isConnected
    }

    fun isConnectedAndPremium(context: Context): Boolean {
        val status = ensureLoaded(context)
        return status.isConnected && status.isPremium
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
                    val msg = "Spotify token exchange failed (${response.code})${if (errDesc.isNotBlank()) ": $errDesc" else ""}"
                    Log.e(TAG, msg)
                    clearPendingAuth(prefs)
                    _statusFlow.value = readStatusFromPrefs(prefs, currentError = msg, isAuthorizing = false)
                    return false
                }
                JSONObject(bodyStr)
            }

            val accessToken = tokenJson.optString("access_token").trim()
            val refreshToken = tokenJson.optString("refresh_token").trim()
            val expiresInSec = tokenJson.optLong("expires_in", 3600L).coerceAtLeast(60L)
            if (accessToken.isBlank()) {
                val msg = "Spotify returned an empty access token."
                clearPendingAuth(prefs)
                _statusFlow.value = readStatusFromPrefs(prefs, currentError = msg, isAuthorizing = false)
                return false
            }

            val expiresAtMs = System.currentTimeMillis() + (expiresInSec * 1000L)
            val profile = fetchSpotifyProfile(accessToken)
            val displayName = profile?.first?.ifBlank { "Spotify Listener" } ?: "Spotify Listener"
            val product = profile?.second.orEmpty()
            val isPremium = product.equals("premium", ignoreCase = true)

            val activeDevice = if (isPremium) findBestConnectDevice(accessToken) else null

            val editor = prefs.edit()
                .putString(KEY_ACTIVE_AUTH_CLIENT_ID, clientId)
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putLong(KEY_EXPIRES_AT_MS, expiresAtMs)
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

            val warningIfFree = if (!isPremium && product.isNotBlank()) {
                "Connected as $displayName (${product.replaceFirstChar { it.uppercase() }}). Silent Spotify playback requires Spotify Premium, but liking tracks works."
            } else {
                null
            }
            _statusFlow.value = readStatusFromPrefs(prefs, currentError = warningIfFree, isAuthorizing = false)
            Log.i(TAG, "✅ Spotify Direct Connect authorized for '$displayName' (product=$product, premium=$isPremium)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Spotify OAuth callback exchange error: ${e.message}", e)
            clearPendingAuth(prefs)
            _statusFlow.value = readStatusFromPrefs(
                prefs,
                currentError = "Network error completing Spotify sign-in: ${e.message}",
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
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_PRODUCT)
            .remove(KEY_IS_PREMIUM)
            .remove(KEY_CONNECTED_AT_MS)
            .remove(KEY_LAST_DEVICE_NAME)
            .remove(KEY_PENDING_VERIFIER)
            .remove(KEY_PENDING_STATE)
            .apply()
        _statusFlow.value = readStatusFromPrefs(prefs, currentError = null, isAuthorizing = false)
        Log.i(TAG, "Disconnected Spotify Direct Connect session.")
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

    private fun fetchSpotifyProfile(accessToken: String): Pair<String, String>? {
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
                val name = json.optString("display_name").ifBlank { json.optString("id") }
                val product = json.optString("product").trim()
                name to product
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch Spotify /v1/me profile: ${e.message}")
            null
        }
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
                        Log.w(TAG, "Spotify token refresh failed (HTTP ${resp.code}): $bodyStr")
                        if (resp.code == 400 || resp.code == 401) {
                            prefs.edit()
                                .remove(KEY_ACCESS_TOKEN)
                                .remove(KEY_REFRESH_TOKEN)
                                .remove(KEY_EXPIRES_AT_MS)
                                .apply()
                            _statusFlow.value = readStatusFromPrefs(
                                prefs,
                                currentError = "Spotify session expired — reconnect in Settings.",
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

                    // Re-verify profile if product wasn't known yet
                    if (prefs.getString(KEY_PRODUCT, "").isNullOrBlank()) {
                        fetchSpotifyProfile(newToken)?.let { (name, product) ->
                            prefs.edit()
                                .putString(KEY_DISPLAY_NAME, name)
                                .putString(KEY_PRODUCT, product)
                                .putBoolean(KEY_IS_PREMIUM, product.equals("premium", ignoreCase = true))
                                .apply()
                        }
                    }

                    _statusFlow.value = readStatusFromPrefs(prefs, currentError = null, isAuthorizing = false)
                    newToken
                }
            } catch (e: Exception) {
                Log.w(TAG, "Spotify token refresh exception: ${e.message}")
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
            fetchSpotifyProfile(token)?.let { (name, prod) ->
                val prefs = getPrefs(context)
                val verifiedPremium = prod.equals("premium", ignoreCase = true)
                prefs.edit()
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
                        val prefs = getPrefs(context)
                        prefs.edit().putBoolean(KEY_IS_PREMIUM, false).putString(KEY_PRODUCT, "free").apply()
                        _statusFlow.value = readStatusFromPrefs(
                            prefs,
                            currentError = "Spotify Premium is required for silent background control.",
                            isAuthorizing = false
                        )
                        SpotifyPlayResult.PremiumRequired("Spotify Premium is required for silent background playback.")
                    }
                    else -> {
                        SpotifyPlayResult.Failed(message.ifBlank { "Spotify Connect returned HTTP ${resp.code}" })
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
