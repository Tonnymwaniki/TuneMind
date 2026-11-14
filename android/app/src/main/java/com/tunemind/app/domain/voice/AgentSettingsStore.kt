package com.tunemind.app.domain.voice

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistent preferences governing TuneMind's autonomous agent behaviors:
 * - [autonomousDjEnabled]: Defaults to `false` so TuneMind NEVER interrupts or queues end-of-song
 *   tracks on its own unless the user explicitly enables Autonomous End-of-Song DJ.
 * - [stayInTuneMindEnabled]: Defaults to `true` so Spotify playback is executed via Spotify App Remote /
 *   Web API / MediaSession (or bounces smoothly back into TuneMind) without leaving the app.
 * - [requireApprovalBeforePlay]: Defaults to `true` so TuneMind only plays after user approval or direct play command.
 * - [backgroundAutoPlayEnabled]: Defaults to `false` so background 15-minute WorkManager/audio-focus syncs
 *   NEVER speak or auto-play Spotify while the user is doing other things.
 */
object AgentSettingsStore {
    private const val PREFS_NAME = "tunemind_agent_settings"
    private const val KEY_AUTONOMOUS_DJ = "autonomous_dj_enabled"
    private const val KEY_STAY_IN_TUNEMIND = "stay_in_tunemind_enabled"
    private const val KEY_REQUIRE_APPROVAL = "require_approval_before_play"
    private const val KEY_BACKGROUND_AUTO_PLAY = "background_auto_play_enabled"

    @Volatile
    private var initialized = false

    private val _autonomousDjEnabled = MutableStateFlow(false)
    val autonomousDjEnabled: StateFlow<Boolean> = _autonomousDjEnabled.asStateFlow()

    private val _stayInTuneMindEnabled = MutableStateFlow(true)
    val stayInTuneMindEnabled: StateFlow<Boolean> = _stayInTuneMindEnabled.asStateFlow()

    private val _requireApprovalBeforePlay = MutableStateFlow(true)
    val requireApprovalBeforePlay: StateFlow<Boolean> = _requireApprovalBeforePlay.asStateFlow()

    private val _backgroundAutoPlayEnabled = MutableStateFlow(false)
    val backgroundAutoPlayEnabled: StateFlow<Boolean> = _backgroundAutoPlayEnabled.asStateFlow()

    private fun prefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun ensureLoaded(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val p = prefs(context)
            _autonomousDjEnabled.value = p.getBoolean(KEY_AUTONOMOUS_DJ, false)
            _stayInTuneMindEnabled.value = p.getBoolean(KEY_STAY_IN_TUNEMIND, true)
            _requireApprovalBeforePlay.value = p.getBoolean(KEY_REQUIRE_APPROVAL, true)
            _backgroundAutoPlayEnabled.value = p.getBoolean(KEY_BACKGROUND_AUTO_PLAY, false)
            initialized = true
        }
    }

    fun isAutonomousDjEnabled(context: Context): Boolean {
        ensureLoaded(context)
        return _autonomousDjEnabled.value
    }

    fun setAutonomousDjEnabled(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        prefs(context).edit().putBoolean(KEY_AUTONOMOUS_DJ, enabled).apply()
        _autonomousDjEnabled.value = enabled
    }

    fun isStayInTuneMindEnabled(context: Context): Boolean {
        ensureLoaded(context)
        return _stayInTuneMindEnabled.value
    }

    fun setStayInTuneMindEnabled(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        prefs(context).edit().putBoolean(KEY_STAY_IN_TUNEMIND, enabled).apply()
        _stayInTuneMindEnabled.value = enabled
    }

    fun isRequireApprovalBeforePlay(context: Context): Boolean {
        ensureLoaded(context)
        return _requireApprovalBeforePlay.value
    }

    fun setRequireApprovalBeforePlay(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        prefs(context).edit().putBoolean(KEY_REQUIRE_APPROVAL, enabled).apply()
        _requireApprovalBeforePlay.value = enabled
    }

    fun isBackgroundAutoPlayEnabled(context: Context): Boolean {
        ensureLoaded(context)
        return _backgroundAutoPlayEnabled.value
    }

    fun setBackgroundAutoPlayEnabled(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        prefs(context).edit().putBoolean(KEY_BACKGROUND_AUTO_PLAY, enabled).apply()
        _backgroundAutoPlayEnabled.value = enabled
    }
}
