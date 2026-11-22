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
import com.tunemind.app.domain.network.BackendEndpointManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class TuneMindUserSession(
    val isLoggedIn: Boolean = false,
    val hasCompletedStartFlow: Boolean = false,
    val hasCompletedAccountOnboarding: Boolean = false,
    val userId: String = "",
    val email: String = "",
    val displayName: String = "",
    val avatarUrl: String = "",
    val primaryProvider: String = "", // "GOOGLE" | "EMAIL" | "GUEST"
    val isGoogleConnected: Boolean = false,
    val isSupabaseAuthenticated: Boolean = false,
    val tasteArchetype: String = "",
    val supabaseUrl: String = "",
    val lastAuthCompletedAtMs: Long = 0L
)

data class SavedAccountProfile(
    val accountKey: String,
    val userId: String,
    val email: String,
    val displayName: String,
    val provider: String, // "GOOGLE" | "EMAIL"
    val avatarUrl: String = "",
    val supabaseAccessToken: String = "",
    val supabaseRefreshToken: String = "",
    val lastActiveAtMs: Long = System.currentTimeMillis()
)

/**
 * Manages User Authentication, Multi-Account Switching, Credentials & Cloud Profiles for TuneMind.
 *
 * Account Providers:
 * - **Google Account**: Supabase Google OAuth (`/auth/v1/authorize?provider=google`) or Android Google Account Picker.
 * - **Email & Password**: Supabase Auth (`/auth/v1/signup`, `/auth/v1/token`, `/auth/v1/user`, `/auth/v1/recover`)
 *   with Encrypted Local Vault fallback when Supabase URL/key is not configured.
 */
class TuneMindAuthSessionManager private constructor(context: Context) {

    private val appContext: Context = context.applicationContext

    private val fastPrefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(FAST_META_PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val prefs: SharedPreferences by lazy {
        createEncryptedPrefs(appContext)
    }

    @Volatile
    private var hasInitializedOnce = false

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val _sessionFlow = MutableStateFlow(TuneMindUserSession())
    val sessionFlow: StateFlow<TuneMindUserSession> = _sessionFlow.asStateFlow()

    private val _savedAccountsFlow = MutableStateFlow<List<SavedAccountProfile>>(emptyList())
    val savedAccountsFlow: StateFlow<List<SavedAccountProfile>> = _savedAccountsFlow.asStateFlow()

    private val _activePartitionKeyFlow = MutableStateFlow(TuneMindAccountStorage.SIGNED_OUT_PARTITION)
    val activePartitionKeyFlow: StateFlow<String> = _activePartitionKeyFlow.asStateFlow()

    private val _lastAuthErrorFlow = MutableStateFlow<String?>(null)
    val lastAuthErrorFlow: StateFlow<String?> = _lastAuthErrorFlow.asStateFlow()

    @Volatile
    private var lastAuthSuccessWallMs: Long = 0L

    fun clearLastAuthError() {
        _lastAuthErrorFlow.value = null
    }

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

    /**
     * Ultra-fast startup path (< 2ms): avoids initializing Android Keystore / EncryptedSharedPreferences
     * on cold launch when fast metadata already exists OR on a brand-new install with no encrypted file yet.
     */
    fun ensureFastInitialized() {
        if (hasInitializedOnce) return
        val fastReady = fastPrefs.getBoolean(KEY_FAST_META_READY, false)
        if (fastReady) {
            applySessionFromStore(fastPrefs)
            hasInitializedOnce = true
            return
        }
        val encryptedXml = java.io.File(appContext.filesDir.parentFile, "shared_prefs/${PREFS_NAME}.xml")
        if (!encryptedXml.exists()) {
            fastPrefs.edit().putBoolean(KEY_FAST_META_READY, true).apply()
            applySessionFromStore(fastPrefs)
            hasInitializedOnce = true
            return
        }
        // Upgrading user: migrate non-secret metadata from EncryptedSharedPreferences once
        refreshSessionState(forceReloadEncrypted = true)
    }

    fun refreshSessionStateIfNeeded() {
        if (!hasInitializedOnce) {
            ensureFastInitialized()
        }
    }

    fun refreshSessionState(forceReloadEncrypted: Boolean = false) {
        if (!forceReloadEncrypted && fastPrefs.getBoolean(KEY_FAST_META_READY, false)) {
            applySessionFromStore(fastPrefs)
            hasInitializedOnce = true
            return
        }
        val current = readCurrentSession(prefs)
        val saved = readSavedAccounts(prefs)
        syncFastMetaFromSession(
            session = current,
            accounts = saved,
            hasSupabaseToken = !(prefs.getString(KEY_SUPABASE_ACCESS_TOKEN, "") ?: "").isBlank(),
            customUrl = prefs.getString(KEY_SUPABASE_URL, null),
            customAnonKey = prefs.getString(KEY_SUPABASE_ANON_KEY, null)
        )
        applyResolvedSession(current, saved)
        hasInitializedOnce = true
    }

    private fun applySessionFromStore(store: SharedPreferences) {
        val current = readCurrentSession(store)
        val saved = readSavedAccounts(store)
        applyResolvedSession(current, saved)
    }

    private fun applyResolvedSession(current: TuneMindUserSession, saved: List<SavedAccountProfile>) {
        val hasExplicit = current.primaryProvider == "GOOGLE" || current.primaryProvider == "EMAIL"
        val partition = if (hasExplicit) {
            TuneMindAccountStorage.activateAccount(appContext, current.userId, current.email)
        } else {
            TuneMindAccountStorage.clearActivePartition(appContext)
        }
        _activePartitionKeyFlow.value = partition
        _sessionFlow.value = current
        _savedAccountsFlow.value = saved
    }

    private fun syncFastMetaFromSession(
        session: TuneMindUserSession,
        accounts: List<SavedAccountProfile>? = null,
        hasSupabaseToken: Boolean? = null,
        customUrl: String? = null,
        customAnonKey: String? = null
    ) {
        val forceReopen = prefs.getBoolean(KEY_FORCE_REOPEN_AUTH, false)
        fastPrefs.edit().apply {
            putBoolean(KEY_FAST_META_READY, true)
            putBoolean(KEY_IS_LOGGED_IN, session.isLoggedIn)
            putBoolean(KEY_HAS_COMPLETED_START_FLOW, session.hasCompletedStartFlow)
            putBoolean(KEY_HAS_SEEN_ACCOUNT_ONBOARDING, session.hasCompletedAccountOnboarding)
            putBoolean(KEY_FORCE_REOPEN_AUTH, forceReopen)
            putBoolean(KEY_GOOGLE_CONNECTED, session.isGoogleConnected)
            putString(KEY_USER_ID, session.userId)
            putString(KEY_USER_EMAIL, session.email)
            putString(KEY_DISPLAY_NAME, session.displayName)
            putString(KEY_AVATAR_URL, session.avatarUrl)
            putString(KEY_TASTE_ARCHETYPE, session.tasteArchetype)
            putString(KEY_PRIMARY_PROVIDER, session.primaryProvider)
            if (hasSupabaseToken != null) {
                putBoolean(KEY_HAS_SUPABASE_TOKEN_FLAG, hasSupabaseToken)
            } else {
                putBoolean(KEY_HAS_SUPABASE_TOKEN_FLAG, session.isSupabaseAuthenticated)
            }
            if (customUrl != null) putString(KEY_SUPABASE_URL, customUrl)
            if (customAnonKey != null) putString(KEY_SUPABASE_ANON_KEY, customAnonKey)
            if (accounts != null) {
                putString(KEY_SAVED_ACCOUNTS_JSON, encodeSavedAccountsJson(accounts, includeTokens = false))
            }
        }.apply()
    }

    fun getAccessToken(): String = prefs.getString(KEY_SUPABASE_ACCESS_TOKEN, "") ?: ""

    private fun isJwtExpiringSoon(jwt: String, bufferSeconds: Long = 300L): Boolean {
        if (jwt.isBlank()) return true
        val parts = jwt.split(".")
        if (parts.size < 2) return false
        return try {
            val payloadBytes = android.util.Base64.decode(
                parts[1],
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
            )
            val json = JSONObject(String(payloadBytes, Charsets.UTF_8))
            val exp = json.optLong("exp", 0L)
            if (exp <= 0L) false else (System.currentTimeMillis() / 1000L) + bufferSeconds >= exp
        } catch (_: Exception) {
            false
        }
    }

    suspend fun ensureFreshAccessToken(): String = withContext(Dispatchers.IO) {
        val currentAccess = prefs.getString(KEY_SUPABASE_ACCESS_TOKEN, "")?.trim().orEmpty()
        val refreshToken = prefs.getString(KEY_SUPABASE_REFRESH_TOKEN, "")?.trim().orEmpty()
        if (currentAccess.isNotBlank() && !isJwtExpiringSoon(currentAccess)) {
            return@withContext currentAccess
        }
        if (refreshToken.isBlank() || !isSupabaseConfigured()) {
            return@withContext currentAccess
        }
        return@withContext runCatching {
            val payload = JSONObject().put("refresh_token", refreshToken)
            val req = Request.Builder()
                .url("${getSupabaseUrl()}/auth/v1/token?grant_type=refresh_token")
                .addHeader("apikey", getSupabaseAnonKey())
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching currentAccess
                val json = JSONObject(resp.body?.string() ?: "{}")
                val newAccess = json.optString("access_token", "").trim()
                val newRefresh = json.optString("refresh_token", refreshToken).trim()
                if (newAccess.isBlank()) return@runCatching currentAccess

                prefs.edit()
                    .putString(KEY_SUPABASE_ACCESS_TOKEN, newAccess)
                    .putString(KEY_SUPABASE_REFRESH_TOKEN, newRefresh)
                    .apply()

                val cur = _sessionFlow.value
                if (cur.email.isNotBlank() && (cur.primaryProvider == "GOOGLE" || cur.primaryProvider == "EMAIL")) {
                    upsertSavedAccount(
                        userId = cur.userId,
                        email = cur.email,
                        displayName = cur.displayName,
                        provider = cur.primaryProvider,
                        avatarUrl = cur.avatarUrl,
                        supabaseAccessToken = newAccess,
                        supabaseRefreshToken = newRefresh
                    )
                }
                refreshSessionState(forceReloadEncrypted = true)
                newAccess
            }
        }.getOrDefault(currentAccess)
    }

    private fun readCurrentSession(store: SharedPreferences = prefs): TuneMindUserSession {
        val googleConnected = store.getBoolean(KEY_GOOGLE_CONNECTED, false)
        val email = store.getString(KEY_USER_EMAIL, "") ?: ""
        val storedProvider = store.getString(KEY_PRIMARY_PROVIDER, "") ?: ""
        val sanitizedProvider = when {
            storedProvider.equals("GOOGLE", ignoreCase = true) || googleConnected -> "GOOGLE"
            storedProvider.equals("EMAIL", ignoreCase = true) || email.isNotBlank() -> "EMAIL"
            else -> ""
        }
        val forceReopenAuth = store.getBoolean(KEY_FORCE_REOPEN_AUTH, false)
        val hasExplicitAccount = sanitizedProvider == "GOOGLE" || sanitizedProvider == "EMAIL"
        val explicitLoggedIn = hasExplicitAccount &&
            (store.getBoolean(KEY_IS_LOGGED_IN, false) || googleConnected || email.isNotBlank())
        val completedStart = hasExplicitAccount && store.getBoolean(KEY_HAS_COMPLETED_START_FLOW, false)
        val seenAccountOnboarding = !forceReopenAuth && hasExplicitAccount && (
            store.getBoolean(KEY_HAS_SEEN_ACCOUNT_ONBOARDING, false) ||
                googleConnected ||
                email.isNotBlank()
            )
        val name = store.getString(KEY_DISPLAY_NAME, "")?.ifBlank {
            if (email.isNotBlank()) email.substringBefore("@") else "TuneMind Listener"
        } ?: "TuneMind Listener"
        val hasSupabaseToken = if (store === fastPrefs) {
            store.getBoolean(KEY_HAS_SUPABASE_TOKEN_FLAG, false)
        } else {
            (store.getString(KEY_SUPABASE_ACCESS_TOKEN, "") ?: "").isNotBlank()
        }

        return TuneMindUserSession(
            isLoggedIn = explicitLoggedIn,
            hasCompletedStartFlow = completedStart,
            hasCompletedAccountOnboarding = seenAccountOnboarding,
            userId = store.getString(KEY_USER_ID, "") ?: "",
            email = email,
            displayName = name,
            avatarUrl = store.getString(KEY_AVATAR_URL, "") ?: "",
            primaryProvider = sanitizedProvider,
            isGoogleConnected = googleConnected,
            isSupabaseAuthenticated = hasSupabaseToken && isSupabaseConfiguredFrom(store),
            tasteArchetype = store.getString(KEY_TASTE_ARCHETYPE, "") ?: "",
            supabaseUrl = getSupabaseUrlFrom(store),
            lastAuthCompletedAtMs = lastAuthSuccessWallMs
        )
    }

    private fun mirrorIdentityToUserPrefs(displayName: String, email: String, archetype: String? = null) {
        val userPrefs = TuneMindAccountStorage.getUserPrefs(appContext, "tunemind_user_prefs")
        val editor = userPrefs.edit()
        if (displayName.isNotBlank()) editor.putString("user_name", displayName)
        if (email.isNotBlank()) editor.putString("user_email", email)
        if (!archetype.isNullOrBlank()) editor.putString("user_archetype", archetype)
        editor.apply()
    }

    private fun resetVolatileRecorderCachesForAccountSwitch() {
        runCatching {
            com.tunemind.app.data.local.CrossAppPlayRecorder.getInstance(appContext).resetInMemoryState()
        }
        runCatching {
            com.tunemind.app.domain.voice.AgentSettingsStore.reloadForActiveAccount(appContext)
        }
    }

    // ========================================================================
    // SAVED ACCOUNTS ROSTER (FOR REAL SWITCH ACCOUNTS)
    // ========================================================================

    private fun readSavedAccounts(store: SharedPreferences = prefs): List<SavedAccountProfile> {
        val raw = store.getString(KEY_SAVED_ACCOUNTS_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<SavedAccountProfile>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val email = obj.optString("email").trim()
                val provider = obj.optString("provider").trim()
                if (email.isBlank() || (provider != "GOOGLE" && provider != "EMAIL")) continue
                list.add(
                    SavedAccountProfile(
                        accountKey = obj.optString("accountKey").ifBlank { "${provider}:${email.lowercase()}" },
                        userId = obj.optString("userId"),
                        email = email,
                        displayName = obj.optString("displayName").ifBlank { email.substringBefore("@") },
                        provider = provider,
                        avatarUrl = obj.optString("avatarUrl"),
                        supabaseAccessToken = obj.optString("supabaseAccessToken"),
                        supabaseRefreshToken = obj.optString("supabaseRefreshToken"),
                        lastActiveAtMs = obj.optLong("lastActiveAtMs", System.currentTimeMillis())
                    )
                )
            }
            list.sortedByDescending { it.lastActiveAtMs }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading saved accounts: ${e.message}")
            emptyList()
        }
    }

    private fun encodeSavedAccountsJson(accounts: List<SavedAccountProfile>, includeTokens: Boolean): String {
        val arr = JSONArray()
        accounts.sortedByDescending { it.lastActiveAtMs }.take(8).forEach { acc ->
            arr.put(
                JSONObject().apply {
                    put("accountKey", acc.accountKey)
                    put("userId", acc.userId)
                    put("email", acc.email)
                    put("displayName", acc.displayName)
                    put("provider", acc.provider)
                    put("avatarUrl", acc.avatarUrl)
                    put("supabaseAccessToken", if (includeTokens) acc.supabaseAccessToken else "")
                    put("supabaseRefreshToken", if (includeTokens) acc.supabaseRefreshToken else "")
                    put("lastActiveAtMs", acc.lastActiveAtMs)
                }
            )
        }
        return arr.toString()
    }

    private fun writeSavedAccounts(accounts: List<SavedAccountProfile>) {
        val sorted = accounts.sortedByDescending { it.lastActiveAtMs }.take(8)
        prefs.edit().putString(KEY_SAVED_ACCOUNTS_JSON, encodeSavedAccountsJson(sorted, includeTokens = true)).apply()
        fastPrefs.edit()
            .putBoolean(KEY_FAST_META_READY, true)
            .putString(KEY_SAVED_ACCOUNTS_JSON, encodeSavedAccountsJson(sorted, includeTokens = false))
            .apply()
        _savedAccountsFlow.value = readSavedAccounts(prefs)
    }

    private fun upsertSavedAccount(
        userId: String,
        email: String,
        displayName: String,
        provider: String,
        avatarUrl: String = "",
        supabaseAccessToken: String = "",
        supabaseRefreshToken: String = ""
    ) {
        val cleanEmail = email.trim()
        if (cleanEmail.isBlank() || (provider != "GOOGLE" && provider != "EMAIL")) return
        val accountKey = "${provider}:${cleanEmail.lowercase()}"
        val existing = readSavedAccounts().filterNot {
            it.accountKey.equals(accountKey, ignoreCase = true) ||
                it.email.equals(cleanEmail, ignoreCase = true) && it.provider == provider
        }
        val prevMatch = readSavedAccounts().firstOrNull { it.accountKey.equals(accountKey, ignoreCase = true) }
        val updated = SavedAccountProfile(
            accountKey = accountKey,
            userId = userId.ifBlank { prevMatch?.userId ?: "${provider.lowercase()}_${cleanEmail.lowercase().hashCode()}" },
            email = cleanEmail,
            displayName = displayName.ifBlank { prevMatch?.displayName ?: cleanEmail.substringBefore("@") },
            provider = provider,
            avatarUrl = avatarUrl.ifBlank { prevMatch?.avatarUrl ?: "" },
            supabaseAccessToken = supabaseAccessToken.ifBlank { prevMatch?.supabaseAccessToken ?: "" },
            supabaseRefreshToken = supabaseRefreshToken.ifBlank { prevMatch?.supabaseRefreshToken ?: "" },
            lastActiveAtMs = System.currentTimeMillis()
        )
        writeSavedAccounts(listOf(updated) + existing)
    }

    suspend fun switchToAccount(account: SavedAccountProfile): Result<TuneMindUserSession> = withContext(Dispatchers.IO) {
        val resolvedAccount = if (account.supabaseAccessToken.isBlank()) {
            readSavedAccounts(prefs).firstOrNull { it.accountKey.equals(account.accountKey, ignoreCase = true) } ?: account
        } else account
        val isGoogle = resolvedAccount.provider.equals("GOOGLE", ignoreCase = true)
        val partition = TuneMindAccountStorage.activateAccount(appContext, resolvedAccount.userId, resolvedAccount.email)
        prefs.edit()
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .putBoolean(KEY_HAS_COMPLETED_START_FLOW, true)
            .putBoolean(KEY_HAS_SEEN_ACCOUNT_ONBOARDING, true)
            .putBoolean(KEY_FORCE_REOPEN_AUTH, false)
            .putBoolean(KEY_GOOGLE_CONNECTED, isGoogle)
            .putString(KEY_USER_ID, resolvedAccount.userId)
            .putString(KEY_USER_EMAIL, resolvedAccount.email)
            .putString(KEY_DISPLAY_NAME, resolvedAccount.displayName)
            .putString(KEY_AVATAR_URL, resolvedAccount.avatarUrl)
            .putString(KEY_PRIMARY_PROVIDER, if (isGoogle) "GOOGLE" else "EMAIL")
            .putString(KEY_SUPABASE_ACCESS_TOKEN, resolvedAccount.supabaseAccessToken)
            .putString(KEY_SUPABASE_REFRESH_TOKEN, resolvedAccount.supabaseRefreshToken)
            .apply()

        upsertSavedAccount(
            userId = resolvedAccount.userId,
            email = resolvedAccount.email,
            displayName = resolvedAccount.displayName,
            provider = resolvedAccount.provider,
            avatarUrl = resolvedAccount.avatarUrl,
            supabaseAccessToken = resolvedAccount.supabaseAccessToken,
            supabaseRefreshToken = resolvedAccount.supabaseRefreshToken
        )
        mirrorIdentityToUserPrefs(resolvedAccount.displayName, resolvedAccount.email)
        resetVolatileRecorderCachesForAccountSwitch()

        if (resolvedAccount.supabaseAccessToken.isNotBlank() && isSupabaseConfigured()) {
            ensureFreshAccessToken()
            syncProfileFromSupabase()
        }

        lastAuthSuccessWallMs = System.currentTimeMillis()
        _lastAuthErrorFlow.value = null
        _activePartitionKeyFlow.value = partition
        refreshSessionState(forceReloadEncrypted = true)
        Result.success(_sessionFlow.value)
    }

    fun removeSavedAccount(accountKey: String) {
        val remaining = readSavedAccounts(prefs).filterNot { it.accountKey == accountKey }
        writeSavedAccounts(remaining)
        val current = _sessionFlow.value
        val activeKey = if (current.email.isNotBlank() && (current.primaryProvider == "GOOGLE" || current.primaryProvider == "EMAIL")) {
            "${current.primaryProvider}:${current.email.lowercase()}"
        } else {
            ""
        }
        if (activeKey == accountKey) {
            signOut(removeFromSavedRoster = true)
        } else {
            refreshSessionState(forceReloadEncrypted = true)
        }
    }

    // ========================================================================
    // SUPABASE CONFIGURATION HELPERS
    // ========================================================================

    private fun getSupabaseUrlFrom(store: SharedPreferences): String {
        val custom = store.getString(KEY_SUPABASE_URL, null)?.trim()
        if (!custom.isNullOrBlank()) return custom.trimEnd('/')
        return BuildConfig.SUPABASE_URL.trim().trimEnd('/')
    }

    private fun getSupabaseAnonKeyFrom(store: SharedPreferences): String {
        val custom = store.getString(KEY_SUPABASE_ANON_KEY, null)?.trim()
        if (!custom.isNullOrBlank()) return custom
        return BuildConfig.SUPABASE_ANON_KEY.trim()
    }

    private fun isSupabaseConfiguredFrom(store: SharedPreferences): Boolean {
        return getSupabaseUrlFrom(store).startsWith("https://") && getSupabaseAnonKeyFrom(store).isNotBlank()
    }

    fun getSupabaseUrl(): String {
        val fastUrl = fastPrefs.getString(KEY_SUPABASE_URL, null)?.trim()
        if (!fastUrl.isNullOrBlank()) return fastUrl.trimEnd('/')
        if (fastPrefs.getBoolean(KEY_FAST_META_READY, false)) {
            return BuildConfig.SUPABASE_URL.trim().trimEnd('/')
        }
        return getSupabaseUrlFrom(prefs)
    }

    fun getSupabaseAnonKey(): String {
        val fastKey = fastPrefs.getString(KEY_SUPABASE_ANON_KEY, null)?.trim()
        if (!fastKey.isNullOrBlank()) return fastKey
        if (fastPrefs.getBoolean(KEY_FAST_META_READY, false)) {
            return BuildConfig.SUPABASE_ANON_KEY.trim()
        }
        return getSupabaseAnonKeyFrom(prefs)
    }

    fun isSupabaseConfigured(): Boolean {
        return getSupabaseUrl().startsWith("https://") && getSupabaseAnonKey().isNotBlank()
    }

    fun saveSupabaseConfig(projectUrl: String, anonKey: String) {
        prefs.edit()
            .putString(KEY_SUPABASE_URL, projectUrl.trim().trimEnd('/'))
            .putString(KEY_SUPABASE_ANON_KEY, anonKey.trim())
            .apply()
        fastPrefs.edit()
            .putString(KEY_SUPABASE_URL, projectUrl.trim().trimEnd('/'))
            .putString(KEY_SUPABASE_ANON_KEY, anonKey.trim())
            .apply()
        refreshSessionState(forceReloadEncrypted = true)
    }

    // ========================================================================
    // GOOGLE ACCOUNT SIGN-IN (SUPABASE OAUTH OR ON-DEVICE GOOGLE PICKER)
    // ========================================================================

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

    fun completeOnDeviceGoogleSignIn(email: String, displayName: String) {
        val cleanEmail = email.trim()
        val cleanName = displayName.trim().ifBlank { cleanEmail.substringBefore("@") }
        val uid = "google_${java.security.MessageDigest.getInstance("SHA-256").digest(cleanEmail.lowercase().toByteArray()).take(6).joinToString("") { "%02x".format(it) }}"
        TuneMindAccountStorage.activateAccount(appContext, uid, cleanEmail)
        prefs.edit()
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .putBoolean(KEY_GOOGLE_CONNECTED, true)
            .putBoolean(KEY_HAS_SEEN_ACCOUNT_ONBOARDING, true)
            .putBoolean(KEY_FORCE_REOPEN_AUTH, false)
            .putString(KEY_USER_EMAIL, cleanEmail)
            .putString(KEY_DISPLAY_NAME, cleanName)
            .putString(KEY_PRIMARY_PROVIDER, "GOOGLE")
            .putString(KEY_USER_ID, uid)
            .remove(KEY_SUPABASE_ACCESS_TOKEN)
            .remove(KEY_SUPABASE_REFRESH_TOKEN)
            .apply()
        upsertSavedAccount(
            userId = uid,
            email = cleanEmail,
            displayName = cleanName,
            provider = "GOOGLE"
        )
        mirrorIdentityToUserPrefs(cleanName, cleanEmail)
        resetVolatileRecorderCachesForAccountSwitch()
        lastAuthSuccessWallMs = System.currentTimeMillis()
        _lastAuthErrorFlow.value = null
        refreshSessionState(forceReloadEncrypted = true)
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
        _lastAuthErrorFlow.value = null

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
                    val refreshToken = json.optString("refresh_token", "")
                    val userObj = json.optJSONObject("user") ?: json
                    val uid = userObj.optString("id").ifBlank { "supa_${cleanEmail.lowercase().hashCode()}" }
                    val meta = userObj.optJSONObject("user_metadata")
                    val resolvedName = meta?.optString("full_name")?.takeIf { it.isNotBlank() } ?: cleanName

                    if (isSignUp && accessToken.isBlank()) {
                        return@withContext Result.failure(
                            IllegalStateException("Verification email sent to $cleanEmail. Confirm your inbox, then log in.")
                        )
                    }

                    TuneMindAccountStorage.activateAccount(appContext, uid, cleanEmail)
                    prefs.edit()
                        .putBoolean(KEY_IS_LOGGED_IN, true)
                        .putBoolean(KEY_GOOGLE_CONNECTED, false)
                        .putBoolean(KEY_HAS_SEEN_ACCOUNT_ONBOARDING, true)
                        .putBoolean(KEY_FORCE_REOPEN_AUTH, false)
                        .putString(KEY_SUPABASE_ACCESS_TOKEN, accessToken)
                        .putString(KEY_SUPABASE_REFRESH_TOKEN, refreshToken)
                        .putString(KEY_USER_ID, uid)
                        .putString(KEY_USER_EMAIL, cleanEmail)
                        .putString(KEY_DISPLAY_NAME, resolvedName)
                        .putString(KEY_PRIMARY_PROVIDER, "EMAIL")
                        .apply()

                    upsertSavedAccount(
                        userId = uid,
                        email = cleanEmail,
                        displayName = resolvedName,
                        provider = "EMAIL",
                        supabaseAccessToken = accessToken,
                        supabaseRefreshToken = refreshToken
                    )
                    mirrorIdentityToUserPrefs(resolvedName, cleanEmail)
                    resetVolatileRecorderCachesForAccountSwitch()
                    upsertSupabaseProfileRow(
                        userId = uid,
                        accessToken = accessToken,
                        email = cleanEmail,
                        displayName = resolvedName,
                        provider = "EMAIL"
                    )
                    syncProfileFromSupabase()
                    lastAuthSuccessWallMs = System.currentTimeMillis()
                    _lastAuthErrorFlow.value = null
                    refreshSessionState(forceReloadEncrypted = true)
                    return@withContext Result.success(_sessionFlow.value)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Supabase Email Auth network error: ${e.message}")
                return@withContext Result.failure(e)
            }
        }

        // Local Encrypted Vault Authentication (when Supabase URL/Key is not configured)
        val emailDigest = sha256Hex(cleanEmail.lowercase())
        val hashKey = "local_vault_pwd_hash_$emailDigest"
        val saltKey = "local_vault_pwd_salt_$emailDigest"
        val nameKey = "local_vault_display_name_$emailDigest"
        val existingHash = prefs.getString(hashKey, null)
        val existingSaved = readSavedAccounts().firstOrNull {
            it.email.equals(cleanEmail, ignoreCase = true) && it.provider == "EMAIL"
        }

        if (isSignUp) {
            if (!existingHash.isNullOrBlank() || existingSaved != null) {
                return@withContext Result.failure(
                    IllegalStateException("An account for $cleanEmail already exists. Please log in instead.")
                )
            }
            val salt = java.util.UUID.randomUUID().toString()
            val pwdHash = sha256Hex("$salt:$password")
            prefs.edit()
                .putString(saltKey, salt)
                .putString(hashKey, pwdHash)
                .putString(nameKey, cleanName)
                .apply()
        } else {
            if (!existingHash.isNullOrBlank()) {
                val salt = prefs.getString(saltKey, "") ?: ""
                val candidateHash = sha256Hex("$salt:$password")
                if (candidateHash != existingHash) {
                    return@withContext Result.failure(
                        IllegalStateException("Incorrect email or password.")
                    )
                }
            } else if (existingSaved == null) {
                return@withContext Result.failure(
                    IllegalStateException("No account found for $cleanEmail. Tap \"Create one\" below to register.")
                )
            } else {
                // Legacy saved account without stored local hash: store hash cleanly now
                val salt = java.util.UUID.randomUUID().toString()
                val pwdHash = sha256Hex("$salt:$password")
                prefs.edit()
                    .putString(saltKey, salt)
                    .putString(hashKey, pwdHash)
                    .putString(nameKey, existingSaved.displayName)
                    .apply()
            }
        }

        val resolvedLocalName = if (isSignUp) {
            cleanName
        } else {
            prefs.getString(nameKey, null)?.takeIf { it.isNotBlank() }
                ?: existingSaved?.displayName
                ?: cleanName
        }
        val localUid = existingSaved?.userId?.takeIf { it.isNotBlank() }
            ?: "local_${emailDigest.take(12)}"
        TuneMindAccountStorage.activateAccount(appContext, localUid, cleanEmail)
        prefs.edit()
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .putBoolean(KEY_GOOGLE_CONNECTED, false)
            .putBoolean(KEY_HAS_SEEN_ACCOUNT_ONBOARDING, true)
            .putBoolean(KEY_FORCE_REOPEN_AUTH, false)
            .putString(KEY_USER_ID, localUid)
            .putString(KEY_USER_EMAIL, cleanEmail)
            .putString(KEY_DISPLAY_NAME, resolvedLocalName)
            .putString(KEY_PRIMARY_PROVIDER, "EMAIL")
            .remove(KEY_SUPABASE_ACCESS_TOKEN)
            .remove(KEY_SUPABASE_REFRESH_TOKEN)
            .apply()
        upsertSavedAccount(
            userId = localUid,
            email = cleanEmail,
            displayName = resolvedLocalName,
            provider = "EMAIL"
        )
        mirrorIdentityToUserPrefs(resolvedLocalName, cleanEmail)
        resetVolatileRecorderCachesForAccountSwitch()
        lastAuthSuccessWallMs = System.currentTimeMillis()
        _lastAuthErrorFlow.value = null
        refreshSessionState(forceReloadEncrypted = true)
        Result.success(_sessionFlow.value)
    }

    private fun sha256Hex(value: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ========================================================================
    // 4. SUPABASE OAUTH DEEP LINK CALLBACK HANDLER (tunemind://supabase-auth-callback)
    // ========================================================================

    suspend fun handleSupabaseRedirectUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val fragmentParams = parseUrlFragment(uri.fragment)
        val oauthError = fragmentParams["error_description"]
            ?: uri.getQueryParameter("error_description")
            ?: fragmentParams["error"]
            ?: uri.getQueryParameter("error")
        if (!oauthError.isNullOrBlank()) {
            _lastAuthErrorFlow.value = Uri.decode(oauthError).replace('+', ' ').trim()
            return@withContext false
        }

        val accessToken = fragmentParams["access_token"] ?: uri.getQueryParameter("access_token")
        val refreshToken = fragmentParams["refresh_token"] ?: uri.getQueryParameter("refresh_token") ?: ""
        val providerToken = fragmentParams["provider_token"] ?: uri.getQueryParameter("provider_token")
        val providerRefreshToken = fragmentParams["provider_refresh_token"] ?: uri.getQueryParameter("provider_refresh_token")

        if (accessToken.isNullOrBlank()) {
            _lastAuthErrorFlow.value = "Google sign-in did not return an access token. Ensure 'tunemind://supabase-auth-callback' is in Supabase Redirect URLs."
            return@withContext false
        }

        var uid = ""
        var email = ""
        var displayName = ""
        var avatarUrl = ""

        if (isSupabaseConfigured()) {
            runCatching {
                val req = Request.Builder()
                    .url("${getSupabaseUrl()}/auth/v1/user")
                    .addHeader("apikey", getSupabaseAnonKey())
                    .addHeader("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val userJson = JSONObject(resp.body?.string() ?: "{}")
                        uid = userJson.optString("id")
                        email = userJson.optString("email")
                        val meta = userJson.optJSONObject("user_metadata")
                        displayName = meta?.optString("full_name")
                            ?.ifBlank { meta.optString("name") }
                            ?.ifBlank { email.substringBefore("@") }
                            ?: email.substringBefore("@")
                        avatarUrl = meta?.optString("avatar_url")
                            ?.ifBlank { meta.optString("picture") }
                            ?: ""
                    }
                }
            }.onFailure {
                Log.w(TAG, "Failed fetching /auth/v1/user after OAuth redirect: ${it.message}")
            }
        }

        val finalName = displayName.ifBlank { email.substringBefore("@").ifBlank { "Google Listener" } }
        val finalUid = uid.ifBlank { "google_${sha256Hex(email.ifBlank { "oauth" }.lowercase()).take(12)}" }
        TuneMindAccountStorage.activateAccount(appContext, finalUid, email)

        prefs.edit()
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .putBoolean(KEY_HAS_SEEN_ACCOUNT_ONBOARDING, true)
            .putBoolean(KEY_FORCE_REOPEN_AUTH, false)
            .putBoolean(KEY_GOOGLE_CONNECTED, true)
            .putString(KEY_SUPABASE_ACCESS_TOKEN, accessToken)
            .putString(KEY_SUPABASE_REFRESH_TOKEN, refreshToken)
            .putString(KEY_PRIMARY_PROVIDER, "GOOGLE")
            .apply {
                if (finalUid.isNotBlank()) putString(KEY_USER_ID, finalUid)
                if (email.isNotBlank()) putString(KEY_USER_EMAIL, email)
                if (finalName.isNotBlank()) putString(KEY_DISPLAY_NAME, finalName)
                if (avatarUrl.isNotBlank()) putString(KEY_AVATAR_URL, avatarUrl)
            }
            .apply()

        if (email.isNotBlank()) {
            upsertSavedAccount(
                userId = finalUid,
                email = email,
                displayName = finalName,
                provider = "GOOGLE",
                avatarUrl = avatarUrl,
                supabaseAccessToken = accessToken,
                supabaseRefreshToken = refreshToken
            )
            mirrorIdentityToUserPrefs(finalName, email)
        }
        resetVolatileRecorderCachesForAccountSwitch()

        syncProfileFromSupabase()
        lastAuthSuccessWallMs = System.currentTimeMillis()
        _lastAuthErrorFlow.value = null
        refreshSessionState(forceReloadEncrypted = true)
        true
    }

    // ========================================================================
    // 5. PROFILE SYNC, EDIT PROFILE, PASSWORD & CREDENTIALS (SUPABASE)
    // ========================================================================

    suspend fun syncProfileFromSupabase(): Boolean = withContext(Dispatchers.IO) {
        val accessToken = prefs.getString(KEY_SUPABASE_ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return@withContext false
        val uid = prefs.getString(KEY_USER_ID, null)?.takeIf { it.isNotBlank() } ?: return@withContext false
        if (!isSupabaseConfigured()) return@withContext false

        return@withContext runCatching {
            val req = Request.Builder()
                .url("${getSupabaseUrl()}/rest/v1/profiles?id=eq.$uid&select=*")
                .addHeader("apikey", getSupabaseAnonKey())
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching false
                val arr = JSONArray(resp.body?.string() ?: "[]")
                val row = arr.optJSONObject(0) ?: return@runCatching false
                val remoteName = row.optString("display_name").trim()
                val remoteEmail = row.optString("email").trim()
                val remoteArchetype = row.optString("taste_archetype").trim()
                val remoteAvatar = row.optString("avatar_url").trim()

                prefs.edit().apply {
                    if (remoteName.isNotBlank()) putString(KEY_DISPLAY_NAME, remoteName)
                    if (remoteEmail.isNotBlank()) putString(KEY_USER_EMAIL, remoteEmail)
                    if (remoteArchetype.isNotBlank()) putString(KEY_TASTE_ARCHETYPE, remoteArchetype)
                    if (remoteAvatar.isNotBlank()) putString(KEY_AVATAR_URL, remoteAvatar)
                }.apply()

                if (remoteEmail.isNotBlank() && remoteName.isNotBlank()) {
                    mirrorIdentityToUserPrefs(remoteName, remoteEmail, remoteArchetype.ifBlank { null })
                }
                refreshSessionState(forceReloadEncrypted = true)
                true
            }
        }.getOrDefault(false)
    }

    private fun upsertSupabaseProfileRow(
        userId: String,
        accessToken: String,
        email: String,
        displayName: String,
        provider: String,
        tasteArchetype: String? = null,
        privateSessionEnabled: Boolean? = null,
        mergeDuplicatePlays: Boolean? = null,
        preferredTargetPlayer: String? = null
    ): Boolean {
        if (!isSupabaseConfigured() || accessToken.isBlank() || userId.isBlank()) return false
        return runCatching {
            val payload = JSONObject().apply {
                put("id", userId)
                if (email.isNotBlank()) put("email", email)
                if (displayName.isNotBlank()) put("display_name", displayName)
                if (provider == "GOOGLE" || provider == "EMAIL") {
                    put("primary_provider", provider)
                    put("google_connected", provider == "GOOGLE")
                }
                put("spotify_connected", false)
                tasteArchetype?.takeIf { it.isNotBlank() }?.let { put("taste_archetype", it) }
                privateSessionEnabled?.let { put("private_session_enabled", it) }
                mergeDuplicatePlays?.let { put("merge_duplicate_plays", it) }
                preferredTargetPlayer?.let { put("preferred_target_player", it) }
            }
            val req = Request.Builder()
                .url("${getSupabaseUrl()}/rest/v1/profiles")
                .addHeader("apikey", getSupabaseAnonKey())
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun updateProfileAndPreferences(
        displayName: String,
        tasteArchetype: String,
        privateSessionEnabled: Boolean? = null,
        mergeDuplicatePlays: Boolean? = null,
        preferredTargetPlayer: String? = null
    ): Result<TuneMindUserSession> = withContext(Dispatchers.IO) {
        val cleanName = displayName.trim().ifBlank { "TuneMind Listener" }
        val cleanArchetype = tasteArchetype.trim()
        val current = _sessionFlow.value

        prefs.edit()
            .putString(KEY_DISPLAY_NAME, cleanName)
            .apply {
                if (cleanArchetype.isNotBlank()) putString(KEY_TASTE_ARCHETYPE, cleanArchetype)
            }
            .apply()

        mirrorIdentityToUserPrefs(cleanName, current.email, cleanArchetype.ifBlank { null })

        if (current.email.isNotBlank() && (current.primaryProvider == "GOOGLE" || current.primaryProvider == "EMAIL")) {
            upsertSavedAccount(
                userId = current.userId,
                email = current.email,
                displayName = cleanName,
                provider = current.primaryProvider
            )
        }

        val accessToken = prefs.getString(KEY_SUPABASE_ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() }
        if (accessToken != null && isSupabaseConfigured()) {
            runCatching {
                val authPayload = JSONObject().put("data", JSONObject().put("full_name", cleanName))
                val authReq = Request.Builder()
                    .url("${getSupabaseUrl()}/auth/v1/user")
                    .addHeader("apikey", getSupabaseAnonKey())
                    .addHeader("Authorization", "Bearer $accessToken")
                    .addHeader("Content-Type", "application/json")
                    .put(authPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                httpClient.newCall(authReq).execute().close()
            }

            upsertSupabaseProfileRow(
                userId = current.userId,
                accessToken = accessToken,
                email = current.email,
                displayName = cleanName,
                provider = current.primaryProvider,
                tasteArchetype = cleanArchetype,
                privateSessionEnabled = privateSessionEnabled,
                mergeDuplicatePlays = mergeDuplicatePlays,
                preferredTargetPlayer = preferredTargetPlayer
            )
        }

        refreshSessionState(forceReloadEncrypted = true)
        Result.success(_sessionFlow.value)
    }

    suspend fun updatePassword(newPassword: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (newPassword.length < 6) {
            return@withContext Result.failure(IllegalArgumentException("Password must be at least 6 characters."))
        }
        val accessToken = prefs.getString(KEY_SUPABASE_ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() }
        if (!isSupabaseConfigured() || accessToken == null) {
            return@withContext Result.failure(
                IllegalStateException("Cloud password update requires an active Supabase Email session.")
            )
        }
        return@withContext try {
            val payload = JSONObject().put("password", newPassword)
            val req = Request.Builder()
                .url("${getSupabaseUrl()}/auth/v1/user")
                .addHeader("apikey", getSupabaseAnonKey())
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .put(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val json = runCatching { JSONObject(resp.body?.string() ?: "{}") }.getOrNull()
                    val msg = json?.optString("msg")
                        ?.ifBlank { json.optString("message") }
                        ?.ifBlank { "Password update failed (${resp.code})" }
                        ?: "Password update failed (${resp.code})"
                    Result.failure(IllegalStateException(msg))
                } else {
                    Result.success(Unit)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendPasswordResetEmail(email: String): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim()
        if (!cleanEmail.contains("@")) {
            return@withContext Result.failure(IllegalArgumentException("No valid email address on this account."))
        }
        if (!isSupabaseConfigured()) {
            return@withContext Result.failure(
                IllegalStateException("Supabase is not configured in BuildConfig yet.")
            )
        }
        return@withContext try {
            val payload = JSONObject().put("email", cleanEmail)
            val req = Request.Builder()
                .url("${getSupabaseUrl()}/auth/v1/recover")
                .addHeader("apikey", getSupabaseAnonKey())
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Result.failure(IllegalStateException("Could not send reset email (${resp.code})"))
                } else {
                    Result.success(Unit)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ========================================================================
    // 6. START FLOW, LOG OUT & DELETE ACCOUNT
    // ========================================================================

    fun markAccountOnboardingCompleted() {
        prefs.edit()
            .putBoolean(KEY_HAS_SEEN_ACCOUNT_ONBOARDING, true)
            .putBoolean(KEY_FORCE_REOPEN_AUTH, false)
            .apply()
        fastPrefs.edit()
            .putBoolean(KEY_FAST_META_READY, true)
            .putBoolean(KEY_HAS_SEEN_ACCOUNT_ONBOARDING, true)
            .putBoolean(KEY_FORCE_REOPEN_AUTH, false)
            .apply()
        refreshSessionState(forceReloadEncrypted = false)
    }

    fun reopenAccountOnboarding() {
        prefs.edit()
            .putBoolean(KEY_HAS_SEEN_ACCOUNT_ONBOARDING, false)
            .putBoolean(KEY_FORCE_REOPEN_AUTH, true)
            .apply()
        fastPrefs.edit()
            .putBoolean(KEY_FAST_META_READY, true)
            .putBoolean(KEY_HAS_SEEN_ACCOUNT_ONBOARDING, false)
            .putBoolean(KEY_FORCE_REOPEN_AUTH, true)
            .apply()
        refreshSessionState(forceReloadEncrypted = false)
    }

    fun markStartFlowCompleted() {
        prefs.edit()
            .putBoolean(KEY_HAS_COMPLETED_START_FLOW, true)
            .putBoolean(KEY_HAS_SEEN_ACCOUNT_ONBOARDING, true)
            .putBoolean(KEY_FORCE_REOPEN_AUTH, false)
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .apply()
        fastPrefs.edit()
            .putBoolean(KEY_FAST_META_READY, true)
            .putBoolean(KEY_HAS_COMPLETED_START_FLOW, true)
            .putBoolean(KEY_HAS_SEEN_ACCOUNT_ONBOARDING, true)
            .putBoolean(KEY_FORCE_REOPEN_AUTH, false)
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .apply()
        refreshSessionState(forceReloadEncrypted = false)
    }

    suspend fun signOutRemoteAndLocal(
        removeFromSavedRoster: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val accessToken = prefs.getString(KEY_SUPABASE_ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() }
        if (accessToken != null && isSupabaseConfigured()) {
            runCatching {
                val req = Request.Builder()
                    .url("${getSupabaseUrl()}/auth/v1/logout")
                    .addHeader("apikey", getSupabaseAnonKey())
                    .addHeader("Authorization", "Bearer $accessToken")
                    .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().close()
            }
        }
        signOut(removeFromSavedRoster = removeFromSavedRoster)
    }

    fun signOut(removeFromSavedRoster: Boolean = false) {
        val currentEmail = prefs.getString(KEY_USER_EMAIL, "") ?: ""
        val currentUserId = prefs.getString(KEY_USER_ID, "") ?: ""
        val currentProvider = prefs.getString(KEY_PRIMARY_PROVIDER, "") ?: ""
        val oldPartition = TuneMindAccountStorage.computePartitionKey(currentUserId, currentEmail)
        if (removeFromSavedRoster && currentEmail.isNotBlank()) {
            val activeKey = "${currentProvider}:${currentEmail.lowercase()}"
            writeSavedAccounts(readSavedAccounts(prefs).filterNot {
                it.accountKey.equals(activeKey, ignoreCase = true) ||
                    (it.email.equals(currentEmail, ignoreCase = true) && it.provider == currentProvider)
            })
        }

        prefs.edit()
            .putBoolean(KEY_IS_LOGGED_IN, false)
            .putBoolean(KEY_HAS_COMPLETED_START_FLOW, false)
            .putBoolean(KEY_HAS_SEEN_ACCOUNT_ONBOARDING, false)
            .putBoolean(KEY_FORCE_REOPEN_AUTH, false)
            .putBoolean(KEY_GOOGLE_CONNECTED, false)
            .remove(KEY_SUPABASE_ACCESS_TOKEN)
            .remove(KEY_SUPABASE_REFRESH_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_AVATAR_URL)
            .remove(KEY_TASTE_ARCHETYPE)
            .remove(KEY_PRIMARY_PROVIDER)
            .apply()

        TuneMindAccountStorage.clearActivePartition(appContext)
        if (removeFromSavedRoster) {
            TuneMindAccountStorage.wipeAccountStorage(appContext, oldPartition)
        }
        resetVolatileRecorderCachesForAccountSwitch()
        refreshSessionState(forceReloadEncrypted = true)
    }

    suspend fun deleteProfileAndSignOut(onWipeLocalAppData: suspend () -> Unit) = withContext(Dispatchers.IO) {
        val accessToken = ensureFreshAccessToken().ifBlank { null }
        val uid = prefs.getString(KEY_USER_ID, null)?.takeIf { it.isNotBlank() }
        val email = prefs.getString(KEY_USER_EMAIL, null)?.takeIf { it.isNotBlank() }
        val partitionToWipe = TuneMindAccountStorage.computePartitionKey(uid, email)

        // 1. Delete cloud account, intelligence records & auth user via hardened backend endpoint
        if (accessToken != null) {
            val baseUrl = BackendEndpointManager.getActiveHttpBaseUrl(appContext).trimEnd('/')
            if (baseUrl.isNotBlank()) {
                runCatching {
                    val backendReq = Request.Builder()
                        .url("$baseUrl/api/v1/account")
                        .addHeader("Authorization", "Bearer $accessToken")
                        .delete()
                        .build()
                    httpClient.newCall(backendReq).execute().close()
                }.onFailure {
                    Log.w(TAG, "Backend DELETE /api/v1/account fallback triggered", it)
                }
            }
        }

        // 2. Direct Supabase REST profile cleanup fallback
        if (accessToken != null && uid != null && isSupabaseConfigured()) {
            runCatching {
                val req = Request.Builder()
                    .url("${getSupabaseUrl()}/rest/v1/profiles?id=eq.$uid")
                    .addHeader("apikey", getSupabaseAnonKey())
                    .addHeader("Authorization", "Bearer $accessToken")
                    .delete()
                    .build()
                httpClient.newCall(req).execute().close()
            }
        }

        if (!email.isNullOrBlank()) {
            val digest = sha256Hex(email.lowercase())
            prefs.edit()
                .remove("local_vault_pwd_hash_$digest")
                .remove("local_vault_pwd_salt_$digest")
                .remove("local_vault_display_name_$digest")
                .apply()
        }

        onWipeLocalAppData()
        TuneMindAccountStorage.wipeAccountStorage(appContext, partitionToWipe)
        signOutRemoteAndLocal(removeFromSavedRoster = true)
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
        private const val FAST_META_PREFS_NAME = "tunemind_auth_fast_meta"
        const val SUPABASE_REDIRECT_URI = "tunemind://supabase-auth-callback"

        private const val KEY_FAST_META_READY = "fast_meta_ready_v1"
        private const val KEY_HAS_SUPABASE_TOKEN_FLAG = "has_supabase_token_flag"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_HAS_COMPLETED_START_FLOW = "has_completed_start_flow"
        private const val KEY_HAS_SEEN_ACCOUNT_ONBOARDING = "has_seen_account_onboarding_v3"
        private const val KEY_FORCE_REOPEN_AUTH = "force_reopen_account_auth"
        private const val KEY_GOOGLE_CONNECTED = "is_google_connected"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_AVATAR_URL = "avatar_url"
        private const val KEY_TASTE_ARCHETYPE = "taste_archetype"
        private const val KEY_PRIMARY_PROVIDER = "primary_provider"
        private const val KEY_SUPABASE_ACCESS_TOKEN = "supabase_access_token"
        private const val KEY_SUPABASE_REFRESH_TOKEN = "supabase_refresh_token"
        private const val KEY_SAVED_ACCOUNTS_JSON = "saved_accounts_roster_v1"
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
