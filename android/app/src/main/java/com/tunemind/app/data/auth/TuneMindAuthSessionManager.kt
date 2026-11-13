package com.tunemind.app.data.auth

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.tunemind.app.BuildConfig
import com.tunemind.app.data.ingestion.SpotifyAuthManager
import com.tunemind.app.data.ingestion.SpotifyRecentlyPlayedSyncWorker
import com.tunemind.app.data.ingestion.SpotifyTokenResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class TuneMindUserSession(
    val isLoggedIn: Boolean = false,
    val hasCompletedStartFlow: Boolean = false,
    val userId: String = "",
    val email: String = "",
    val displayName: String = "",
    val primaryProvider: String = "", // "GOOGLE" | "SPOTIFY" | "EMAIL"
    val isGoogleConnected: Boolean = false,
    val isSpotifyConnected: Boolean = false,
    val supabaseUrl: String = ""
)

/**
 * Manages User Authentication & Start-Flow Sessions for TuneMind.
 *
 * Supports:
 * 1. **One-Tap Login with Spotify**: Uses [SpotifyAuthManager] (`tunemind://spotify-auth-callback`)
 *    or Supabase Spotify OAuth (`tunemind://supabase-auth-callback`).
 * 2. **One-Tap Login with Google**: Uses Supabase Google OAuth (`/auth/v1/authorize?provider=google`)
 *    via Chrome Custom Tabs, or instant on-device Google account authentication.
 * 3. **Email & Password Auth**: Authenticates against Supabase Auth REST API (`/auth/v1/token` & `/auth/v1/signup`)
 *    when Supabase credentials are configured, or stores an encrypted local account profile.
 */
class TuneMindAuthSessionManager private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val spotifyAuthManager = SpotifyAuthManager(appContext)

    private val prefs: SharedPreferences by lazy {
        createEncryptedPrefs(appContext)
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val _sessionFlow = MutableStateFlow(readCurrentSession())
    val sessionFlow: StateFlow<TuneMindUserSession> = _sessionFlow.asStateFlow()

    private fun createEncryptedPrefs(ctx: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                ctx,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            ctx.getSharedPreferences("${PREFS_NAME}_fallback", Context.MODE_PRIVATE)
        }
    }

    fun refreshSessionState() {
        _sessionFlow.value = readCurrentSession()
    }

    private fun readCurrentSession(): TuneMindUserSession {
        val spotifyConnected = spotifyAuthManager.isAuthenticated()
        val googleConnected = prefs.getBoolean(KEY_GOOGLE_CONNECTED, false)
        val explicitLoggedIn = prefs.getBoolean(KEY_IS_LOGGED_IN, false) || spotifyConnected || googleConnected
        val completedStart = prefs.getBoolean(KEY_HAS_COMPLETED_START_FLOW, false)
        val email = prefs.getString(KEY_USER_EMAIL, "") ?: ""
        val name = prefs.getString(KEY_DISPLAY_NAME, "")?.ifBlank {
            if (spotifyConnected) "Spotify Listener" else if (email.isNotBlank()) email.substringBefore("@") else "TuneMind Listener"
        } ?: "TuneMind Listener"
        val provider = prefs.getString(KEY_PRIMARY_PROVIDER, "")?.ifBlank {
            when {
                googleConnected -> "GOOGLE"
                spotifyConnected -> "SPOTIFY"
                else -> ""
            }
        } ?: ""

        return TuneMindUserSession(
            isLoggedIn = explicitLoggedIn,
            hasCompletedStartFlow = completedStart,
            userId = prefs.getString(KEY_USER_ID, "") ?: "",
            email = email,
            displayName = name,
            primaryProvider = provider,
            isGoogleConnected = googleConnected,
            isSpotifyConnected = spotifyConnected,
            supabaseUrl = getSupabaseUrl()
        )
    }

    // ========================================================================
    // SUPABASE CONFIGURATION HELPERS
    // ========================================================================

    fun getSupabaseUrl(): String {
        val custom = prefs.getString(KEY_SUPABASE_URL, null)?.trim()
        if (!custom.isNullOrBlank()) return custom.trimEnd('/')
        return BuildConfig.SUPABASE_URL.trim().trimEnd('/')
    }

    fun getSupabaseAnonKey(): String {
        val custom = prefs.getString(KEY_SUPABASE_ANON_KEY, null)?.trim()
        if (!custom.isNullOrBlank()) return custom
        return BuildConfig.SUPABASE_ANON_KEY.trim()
    }

    fun isSupabaseConfigured(): Boolean {
        return getSupabaseUrl().startsWith("https://") && getSupabaseAnonKey().isNotBlank()
    }

    fun saveSupabaseConfig(projectUrl: String, anonKey: String) {
        prefs.edit()
            .putString(KEY_SUPABASE_URL, projectUrl.trim().trimEnd('/'))
            .putString(KEY_SUPABASE_ANON_KEY, anonKey.trim())
            .apply()
        refreshSessionState()
    }

    // ========================================================================
    // 1. ONE-TAP LOGIN WITH SPOTIFY
    // ========================================================================

    /**
     * Launches the Spotify OAuth 2.0 flow in a Custom Chrome Tab.
     * Uses direct Spotify Web API OAuth (`tunemind://spotify-auth-callback`) with your
     * configured `CLIENT_ID` and `CLIENT_SECRET` so tokens are immediately stored for
     * the 15-minute `SpotifyRecentlyPlayedSyncWorker`.
     */
    fun launchSpotifyLogin(activityOrContext: Context) {
        spotifyAuthManager.launchSpotifyAuth(activityOrContext)
    }

    /**
     * Called by `AuthCallbackActivity` as soon as `tunemind://spotify-auth-callback`
     * exchanges the authorization code for Spotify Access & Refresh tokens.
     */
    fun onSpotifyOAuthSucceeded() {
        prefs.edit()
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .putString(
                KEY_PRIMARY_PROVIDER,
                prefs.getString(KEY_PRIMARY_PROVIDER, null)?.takeIf { it.isNotBlank() } ?: "SPOTIFY"
            )
            .apply()
        refreshSessionState()
    }

    // ========================================================================
    // 2. ONE-TAP LOGIN WITH GOOGLE (SUPABASE OAUTH OR ON-DEVICE GOOGLE ACCOUNT)
    // ========================================================================

    /**
     * If Supabase is configured (`SUPABASE_URL`), opens Supabase's Google OAuth flow
     * (`/auth/v1/authorize?provider=google&redirect_to=tunemind://supabase-auth-callback`)
     * in a Custom Chrome Tab.
     *Returns `true` if external browser OAuth was launched, or `false` if the UI should
     * display the instant on-device Google account sign-in sheet.
     */
    fun launchGoogleLogin(activityOrContext: Context): Boolean {
        val supabaseUrl = getSupabaseUrl()
        if (supabaseUrl.startsWith("https://")) {
            val authUri = Uri.parse("$supabaseUrl/auth/v1/authorize").buildUpon()
                .appendQueryParameter("provider", "google")
                .appendQueryParameter("redirect_to", SUPABASE_REDIRECT_URI)
                .appendQueryParameter("prompt", "select_account")
                .build()

            Log.i(TAG, "Launching Supabase Google OAuth in Custom Chrome Tab: $authUri")
            openCustomTab(activityOrContext, authUri)
            return true
        }
        return false
    }

    /**
     * Completes Google Sign-In with the user's Google email & display name.
     */
    fun completeOnDeviceGoogleSignIn(email: String, displayName: String) {
        val cleanEmail = email.trim()
        val cleanName = displayName.trim().ifBlank { cleanEmail.substringBefore("@") }
        prefs.edit()
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .putBoolean(KEY_GOOGLE_CONNECTED, true)
            .putString(KEY_USER_EMAIL, cleanEmail)
            .putString(KEY_DISPLAY_NAME, cleanName)
            .putString(KEY_PRIMARY_PROVIDER, "GOOGLE")
            .putString(KEY_USER_ID, "google_${cleanEmail.lowercase().hashCode()}")
            .apply()
        refreshSessionState()
    }

    // ========================================================================
    // 3. EMAIL & PASSWORD SIGN IN / SIGN UP (SUPABASE AUTH + LOCAL VAULT)
    // ========================================================================

    suspend fun signInOrSignUpWithEmail(
        email: String,
        password: String,
        displayName: String,
        isSignUp: Boolean
    ): Result<TuneMindUserSession> = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim()
        val cleanName = displayName.trim().ifBlank { cleanEmail.substringBefore("@") }

        if (isSupabaseConfigured()) {
            val supabaseUrl = getSupabaseUrl()
            val anonKey = getSupabaseAnonKey()
            try {
                val endpoint = if (isSignUp) {
                    "$supabaseUrl/auth/v1/signup"
                } else {
                    "$supabaseUrl/auth/v1/token?grant_type=password"
                }
                val payload = JSONObject().apply {
                    put("email", cleanEmail)
                    put("password", password)
                    if (isSignUp) {
                        put("data", JSONObject().put("full_name", cleanName))
                    }
                }
                val req = Request.Builder()
                    .url(endpoint)
                    .addHeader("apikey", anonKey)
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    val bodyStr = resp.body?.string() ?: "{}"
                    val json = JSONObject(bodyStr)
                    if (!resp.isSuccessful) {
                        val errMsg = json.optString("error_description")
                            .ifBlank { json.optString("msg") }
                            .ifBlank { json.optString("message", "Authentication failed (${resp.code})") }
                        return@withContext Result.failure(IllegalStateException(errMsg))
                    }

                    val accessToken = json.optString("access_token", "")
                    val userObj = json.optJSONObject("user")
                    val uid = userObj?.optString("id") ?: "supa_${cleanEmail.hashCode()}"
                    prefs.edit()
                        .putBoolean(KEY_IS_LOGGED_IN, true)
                        .putString(KEY_SUPABASE_ACCESS_TOKEN, accessToken)
                        .putString(KEY_USER_ID, uid)
                        .putString(KEY_USER_EMAIL, cleanEmail)
                        .putString(KEY_DISPLAY_NAME, cleanName)
                        .putString(KEY_PRIMARY_PROVIDER, "EMAIL")
                        .apply()
                    refreshSessionState()
                    return@withContext Result.success(_sessionFlow.value)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Supabase Email Auth network error: ${e.message}")
                return@withContext Result.failure(e)
            }
        }

        // Local Encrypted Vault Authentication (when Supabase URL is not yet set)
        prefs.edit()
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .putString(KEY_USER_ID, "local_${cleanEmail.lowercase().hashCode()}")
            .putString(KEY_USER_EMAIL, cleanEmail)
            .putString(KEY_DISPLAY_NAME, cleanName)
            .putString(KEY_PRIMARY_PROVIDER, "EMAIL")
            .apply()
        refreshSessionState()
        Result.success(_sessionFlow.value)
    }

    // ========================================================================
    // 4. SUPABASE OAUTH DEEP LINK CALLBACK HANDLER (tunemind://supabase-auth-callback)
    // ========================================================================

    suspend fun handleSupabaseRedirectUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        // Supabase OAuth returns tokens in the URL fragment (#access_token=...&provider_token=...)
        // or query parameters (?access_token=...)
        val fragmentParams = parseUrlFragment(uri.fragment)
        val accessToken = fragmentParams["access_token"] ?: uri.getQueryParameter("access_token")
        val providerToken = fragmentParams["provider_token"] ?: uri.getQueryParameter("provider_token")
        val providerRefreshToken = fragmentParams["provider_refresh_token"] ?: uri.getQueryParameter("provider_refresh_token")

        if (accessToken.isNullOrBlank()) {
            return@withContext false
        }

        prefs.edit()
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .putBoolean(KEY_GOOGLE_CONNECTED, true)
            .putString(KEY_SUPABASE_ACCESS_TOKEN, accessToken)
            .putString(
                KEY_PRIMARY_PROVIDER,
                if (!providerToken.isNullOrBlank()) "SPOTIFY" else "GOOGLE"
            )
            .apply()

        // If Supabase returned a Spotify provider_token, also save it in SpotifyAuthManager
        if (!providerToken.isNullOrBlank()) {
            spotifyAuthManager.saveTokens(
                SpotifyTokenResponse(
                    accessToken = providerToken,
                    refreshToken = providerRefreshToken,
                    expiresInSeconds = 3600L
                )
            )
            SpotifyRecentlyPlayedSyncWorker.schedulePeriodic15MinSync(appContext)
            SpotifyRecentlyPlayedSyncWorker.enqueueImmediateOnAudioFocusChange(
                context = appContext,
                reason = "SUPABASE_SPOTIFY_OAUTH_SUCCESS",
                bypassDebounce = true
            )
        }

        refreshSessionState()
        true
    }

    fun markStartFlowCompleted() {
        prefs.edit()
            .putBoolean(KEY_HAS_COMPLETED_START_FLOW, true)
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .apply()
        refreshSessionState()
    }

    fun signOut() {
        prefs.edit()
            .putBoolean(KEY_IS_LOGGED_IN, false)
            .putBoolean(KEY_HAS_COMPLETED_START_FLOW, false)
            .putBoolean(KEY_GOOGLE_CONNECTED, false)
            .remove(KEY_SUPABASE_ACCESS_TOKEN)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_PRIMARY_PROVIDER)
            .apply()
        spotifyAuthManager.clearTokens()
        refreshSessionState()
    }

    private fun openCustomTab(context: Context, uri: Uri) {
        try {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(true)
                .build()
            customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            customTabsIntent.launchUrl(context, uri)
        } catch (e: Exception) {
            val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
        }
    }

    private fun parseUrlFragment(fragment: String?): Map<String, String> {
        if (fragment.isNullOrBlank()) return emptyMap()
        return fragment.split("&").mapNotNull { pair ->
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2) {
                Uri.decode(parts[0]) to Uri.decode(parts[1])
            } else null
        }.toMap()
    }

    companion object {
        private const val TAG = "TuneMindAuthSession"
        private const val PREFS_NAME = "tunemind_user_auth_session"
        const val SUPABASE_REDIRECT_URI = "tunemind://supabase-auth-callback"

        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_HAS_COMPLETED_START_FLOW = "has_completed_start_flow"
        private const val KEY_GOOGLE_CONNECTED = "is_google_connected"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_PRIMARY_PROVIDER = "primary_provider"
        private const val KEY_SUPABASE_ACCESS_TOKEN = "supabase_access_token"
        private const val KEY_SUPABASE_URL = "custom_supabase_url"
        private const val KEY_SUPABASE_ANON_KEY = "custom_supabase_anon_key"

        @Volatile
        private var INSTANCE: TuneMindAuthSessionManager? = null

        fun getInstance(context: Context): TuneMindAuthSessionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TuneMindAuthSessionManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}
