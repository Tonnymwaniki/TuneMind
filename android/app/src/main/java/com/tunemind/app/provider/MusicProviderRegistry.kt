package com.tunemind.app.provider

import android.content.Context
import android.content.SharedPreferences
import com.tunemind.app.domain.recommendation.RecommendedTrack

/**
 * Registry and orchestrator for all supported external music providers.
 * Detects installed players, tracks user preferences, supports player selection,
 * and routes recommendation playback with background fast-path.
 */
class MusicProviderRegistry(
    private val context: Context,
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
) {
    private val providers: List<MusicProvider> = listOf(
        BoomplayMusicProvider(),
        YouTubeMusicProvider(),
        SpotifyMusicProvider(),
        AppleMusicProvider(),
        AudiomackMusicProvider(),
        GenericMusicProvider()
    )

    /** Returns all registered providers. */
    fun getAllProviders(): List<MusicProvider> = providers

    /** Returns all providers currently installed on the user's phone (plus Generic fallback). */
    fun getInstalledProviders(): List<MusicProvider> {
        val installed = providers.filter { it.isInstalled(context) && it.providerId != GenericMusicProvider.PROVIDER_ID }
        return if (installed.isEmpty()) {
            listOf(GenericMusicProvider())
        } else {
            installed
        }
    }

    /** True if the user has explicitly selected a default player. */
    fun hasExplicitDefault(): Boolean = prefs.contains(KEY_PREFERRED_PROVIDER)

    /**
     * True if TuneMind should prompt the user to choose their music player before playing.
     * Defaults to true if the user hasn't explicitly set a default player.
     */
    fun isAlwaysAskEnabled(): Boolean {
        return prefs.getBoolean(KEY_ALWAYS_ASK, !hasExplicitDefault())
    }

    /** Toggles the 'Always ask before playing' preference. */
    fun setAlwaysAskEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ALWAYS_ASK, enabled).apply()
    }

    /** Returns the user's preferred music provider ID. */
    fun getPreferredProviderId(): String {
        val saved = prefs.getString(KEY_PREFERRED_PROVIDER, null)
        if (saved != null && getProvider(saved).isInstalled(context)) {
            return saved
        }

        // Auto-detect installed streaming players in priority order
        return getInstalledProviders().firstOrNull()?.providerId ?: GenericMusicProvider.PROVIDER_ID
    }

    /** Updates the user's preferred music provider ID. */
    fun setPreferredProviderId(providerId: String) {
        prefs.edit()
            .putString(KEY_PREFERRED_PROVIDER, providerId)
            .putBoolean(KEY_ALWAYS_ASK, false)
            .apply()
    }

    /** Saves player choice and optionally remembers as permanent default. */
    fun selectPlayer(providerId: String, rememberAsDefault: Boolean) {
        if (rememberAsDefault) {
            setPreferredProviderId(providerId)
        }
    }

    /** Resets the default player choice so the dialog prompts again. */
    fun resetDefaultPlayer() {
        prefs.edit()
            .remove(KEY_PREFERRED_PROVIDER)
            .putBoolean(KEY_ALWAYS_ASK, true)
            .apply()
    }

    /** Finds a provider by ID or returns the generic fallback. */
    fun getProvider(providerId: String): MusicProvider {
        return providers.firstOrNull { it.providerId == providerId } ?: GenericMusicProvider()
    }

    /**
     * Resolves a recommended track and launches it through the requested or preferred provider.
     * Respects user preference, installed apps, and provider capabilities.
     */
    fun launchRecommendation(track: RecommendedTrack, explicitProviderId: String? = null): PlaybackLaunchResult {
        val targetProviderId = explicitProviderId ?: getPreferredProviderId()
        val provider = getProvider(targetProviderId)
        val match = provider.resolveTrack(track)
        return provider.launchPlayback(context, match)
    }

    companion object {
        private const val PREFS_NAME = "tunemind_provider_prefs"
        const val KEY_PREFERRED_PROVIDER = "preferred_music_provider"
        const val KEY_ALWAYS_ASK = "always_ask_music_player"

        @Volatile
        private var INSTANCE: MusicProviderRegistry? = null

        fun getInstance(context: Context): MusicProviderRegistry {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MusicProviderRegistry(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
