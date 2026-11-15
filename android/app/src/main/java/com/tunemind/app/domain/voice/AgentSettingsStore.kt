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
    private const val KEY_STRICT_NO_APP_SWITCH = "strict_no_app_switch_enabled"
    private const val KEY_PREFER_IN_APP_PLAYER = "prefer_in_app_player_enabled"
    private const val KEY_REQUIRE_APPROVAL = "require_approval_before_play"
    private const val KEY_BACKGROUND_AUTO_PLAY = "background_auto_play_enabled"
    private const val KEY_REPETITION_SENSITIVITY = "repetition_sensitivity"
    private const val KEY_LATE_NIGHT_COMFORT = "late_night_comfort_enabled"
    private const val KEY_PRIVATE_SESSION = "private_session_enabled"
    private const val KEY_MERGE_DUPLICATE_PLAYS = "merge_duplicate_plays_enabled"
    private const val KEY_LOOP_MOMENTS = "loop_moments_enabled"
    private const val KEY_ALERTS_VAULT_RECOVERY = "alerts_vault_recovery_enabled"
    private const val KEY_ALERTS_WEEKLY_RECAP = "alerts_weekly_recap_enabled"
    private const val KEY_ALERTS_HEARING = "alerts_hearing_enabled"
    private const val KEY_ALERTS_SYNC_STATUS = "alerts_sync_status_enabled"

    @Volatile
    private var initialized = false

    private val _autonomousDjEnabled = MutableStateFlow(false)
    val autonomousDjEnabled: StateFlow<Boolean> = _autonomousDjEnabled.asStateFlow()

    private val _stayInTuneMindEnabled = MutableStateFlow(true)
    val stayInTuneMindEnabled: StateFlow<Boolean> = _stayInTuneMindEnabled.asStateFlow()

    private val _strictNoAppSwitchEnabled = MutableStateFlow(false)
    val strictNoAppSwitchEnabled: StateFlow<Boolean> = _strictNoAppSwitchEnabled.asStateFlow()

    private val _preferInAppPlayerEnabled = MutableStateFlow(true)
    val preferInAppPlayerEnabled: StateFlow<Boolean> = _preferInAppPlayerEnabled.asStateFlow()

    private val _requireApprovalBeforePlay = MutableStateFlow(true)
    val requireApprovalBeforePlay: StateFlow<Boolean> = _requireApprovalBeforePlay.asStateFlow()

    private val _backgroundAutoPlayEnabled = MutableStateFlow(false)
    val backgroundAutoPlayEnabled: StateFlow<Boolean> = _backgroundAutoPlayEnabled.asStateFlow()

    private val _repetitionSensitivity = MutableStateFlow(0.75f)
    val repetitionSensitivity: StateFlow<Float> = _repetitionSensitivity.asStateFlow()

    private val _lateNightComfortEnabled = MutableStateFlow(true)
    val lateNightComfortEnabled: StateFlow<Boolean> = _lateNightComfortEnabled.asStateFlow()

    private val _privateSessionEnabled = MutableStateFlow(false)
    val privateSessionEnabled: StateFlow<Boolean> = _privateSessionEnabled.asStateFlow()

    private val _mergeDuplicatePlaysEnabled = MutableStateFlow(true)
    val mergeDuplicatePlaysEnabled: StateFlow<Boolean> = _mergeDuplicatePlaysEnabled.asStateFlow()

    private val _loopMomentsEnabled = MutableStateFlow(false)
    val loopMomentsEnabled: StateFlow<Boolean> = _loopMomentsEnabled.asStateFlow()

    private val _vaultRecoveryAlertsEnabled = MutableStateFlow(true)
    val vaultRecoveryAlertsEnabled: StateFlow<Boolean> = _vaultRecoveryAlertsEnabled.asStateFlow()

    private val _weeklyRecapAlertsEnabled = MutableStateFlow(true)
    val weeklyRecapAlertsEnabled: StateFlow<Boolean> = _weeklyRecapAlertsEnabled.asStateFlow()

    private val _hearingAlertsEnabled = MutableStateFlow(true)
    val hearingAlertsEnabled: StateFlow<Boolean> = _hearingAlertsEnabled.asStateFlow()

    private val _syncStatusAlertsEnabled = MutableStateFlow(true)
    val syncStatusAlertsEnabled: StateFlow<Boolean> = _syncStatusAlertsEnabled.asStateFlow()

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
            _strictNoAppSwitchEnabled.value = p.getBoolean(KEY_STRICT_NO_APP_SWITCH, false)
            _preferInAppPlayerEnabled.value = p.getBoolean(KEY_PREFER_IN_APP_PLAYER, true)
            _requireApprovalBeforePlay.value = p.getBoolean(KEY_REQUIRE_APPROVAL, true)
            _backgroundAutoPlayEnabled.value = p.getBoolean(KEY_BACKGROUND_AUTO_PLAY, false)
            _repetitionSensitivity.value = p.getFloat(KEY_REPETITION_SENSITIVITY, 0.75f).coerceIn(0.2f, 1.0f)
            _lateNightComfortEnabled.value = p.getBoolean(KEY_LATE_NIGHT_COMFORT, true)
            _privateSessionEnabled.value = p.getBoolean(KEY_PRIVATE_SESSION, false)
            _mergeDuplicatePlaysEnabled.value = p.getBoolean(KEY_MERGE_DUPLICATE_PLAYS, true)
            _loopMomentsEnabled.value = p.getBoolean(KEY_LOOP_MOMENTS, false)
            _vaultRecoveryAlertsEnabled.value = p.getBoolean(KEY_ALERTS_VAULT_RECOVERY, true)
            _weeklyRecapAlertsEnabled.value = p.getBoolean(KEY_ALERTS_WEEKLY_RECAP, true)
            _hearingAlertsEnabled.value = p.getBoolean(KEY_ALERTS_HEARING, true)
            _syncStatusAlertsEnabled.value = p.getBoolean(KEY_ALERTS_SYNC_STATUS, true)
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

    fun isStrictNoAppSwitchEnabled(context: Context): Boolean {
        ensureLoaded(context)
        return _strictNoAppSwitchEnabled.value
    }

    fun setStrictNoAppSwitchEnabled(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        prefs(context).edit().putBoolean(KEY_STRICT_NO_APP_SWITCH, enabled).apply()
        _strictNoAppSwitchEnabled.value = enabled
    }

    fun isPreferInAppPlayerEnabled(context: Context): Boolean {
        ensureLoaded(context)
        return _preferInAppPlayerEnabled.value
    }

    fun setPreferInAppPlayerEnabled(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        prefs(context).edit().putBoolean(KEY_PREFER_IN_APP_PLAYER, enabled).apply()
        _preferInAppPlayerEnabled.value = enabled
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

    fun getRepetitionSensitivity(context: Context): Float {
        ensureLoaded(context)
        return _repetitionSensitivity.value
    }

    fun setRepetitionSensitivity(context: Context, value: Float) {
        ensureLoaded(context)
        val clamped = value.coerceIn(0.2f, 1.0f)
        prefs(context).edit().putFloat(KEY_REPETITION_SENSITIVITY, clamped).apply()
        _repetitionSensitivity.value = clamped
    }

    fun isLateNightComfortEnabled(context: Context): Boolean {
        ensureLoaded(context)
        return _lateNightComfortEnabled.value
    }

    fun setLateNightComfortEnabled(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        prefs(context).edit().putBoolean(KEY_LATE_NIGHT_COMFORT, enabled).apply()
        _lateNightComfortEnabled.value = enabled
    }

    fun isPrivateSessionEnabled(context: Context): Boolean {
        ensureLoaded(context)
        return _privateSessionEnabled.value
    }

    fun setPrivateSessionEnabled(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        prefs(context).edit().putBoolean(KEY_PRIVATE_SESSION, enabled).apply()
        _privateSessionEnabled.value = enabled
    }

    fun isMergeDuplicatePlaysEnabled(context: Context): Boolean {
        ensureLoaded(context)
        return _mergeDuplicatePlaysEnabled.value
    }

    fun setMergeDuplicatePlaysEnabled(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        prefs(context).edit().putBoolean(KEY_MERGE_DUPLICATE_PLAYS, enabled).apply()
        _mergeDuplicatePlaysEnabled.value = enabled
    }

    fun isLoopMomentsEnabled(context: Context): Boolean {
        ensureLoaded(context)
        return _loopMomentsEnabled.value
    }

    fun setLoopMomentsEnabled(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        prefs(context).edit().putBoolean(KEY_LOOP_MOMENTS, enabled).apply()
        _loopMomentsEnabled.value = enabled
    }

    fun isVaultRecoveryAlertsEnabled(context: Context): Boolean {
        ensureLoaded(context)
        return _vaultRecoveryAlertsEnabled.value
    }

    fun setVaultRecoveryAlertsEnabled(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        prefs(context).edit().putBoolean(KEY_ALERTS_VAULT_RECOVERY, enabled).apply()
        _vaultRecoveryAlertsEnabled.value = enabled
    }

    fun isWeeklyRecapAlertsEnabled(context: Context): Boolean {
        ensureLoaded(context)
        return _weeklyRecapAlertsEnabled.value
    }

    fun setWeeklyRecapAlertsEnabled(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        prefs(context).edit().putBoolean(KEY_ALERTS_WEEKLY_RECAP, enabled).apply()
        _weeklyRecapAlertsEnabled.value = enabled
    }

    fun isHearingAlertsEnabled(context: Context): Boolean {
        ensureLoaded(context)
        return _hearingAlertsEnabled.value
    }

    fun setHearingAlertsEnabled(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        prefs(context).edit().putBoolean(KEY_ALERTS_HEARING, enabled).apply()
        _hearingAlertsEnabled.value = enabled
    }

    fun isSyncStatusAlertsEnabled(context: Context): Boolean {
        ensureLoaded(context)
        return _syncStatusAlertsEnabled.value
    }

    fun setSyncStatusAlertsEnabled(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        prefs(context).edit().putBoolean(KEY_ALERTS_SYNC_STATUS, enabled).apply()
        _syncStatusAlertsEnabled.value = enabled
    }
}
