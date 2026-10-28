package com.tunemind.app.provider

import android.content.Context
import android.content.SharedPreferences
import com.tunemind.app.domain.recommendation.RecommendedTrack

/**
 * Registry and orchestrator for all supported external music providers.
 * Detects installed players, tracks user preferences, and routes recommendation playback.
 */
class MusicProviderRegistry(
    private val context: Context,
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
) {
    private val providers: List<MusicProvider> = listOf(
        SpotifyMusicProvider(),
        YouTubeMusicProvider(),
        GenericMusicProvider()
    )

    /** Returns all registered providers. */
    fun getAllProviders(): List<MusicProvider> = providers

    /** Returns all providers currently installed on the user's phone. */
    fun getInstalledProviders(): List<MusicProvider> = providers.filter { it.isInstalled(context) }

    /** Returns the user's preferred music provider ID (default is Spotify if installed, else first available). */
    fun getPreferredProviderId(): String {
        val saved = prefs.getString(KEY_PREFERRED_PROVIDER, null)
        if (saved != null) return saved

        return if (SpotifyMusicProvider().isInstalled(context)) {
            SpotifyMusicProvider.PROVIDER_ID
        } else if (YouTubeMusicProvider().isInstalled(context)) {
            YouTubeMusicProvider.PROVIDER_ID
        } else {
            GenericMusicProvider.PROVIDER_ID
        }
    }

    /** Updates the user's preferred music provider ID. */
    fun setPreferredProviderId(providerId: String) {
        prefs.edit().putString(KEY_PREFERRED_PROVIDER, providerId).apply()
    }

    /** Finds a provider by ID or returns the generic fallback. */
    fun getProvider(providerId: String): MusicProvider {
        return providers.firstOrNull { it.providerId == providerId } ?: GenericMusicProvider()
    }

    /**
     * Resolves a recommended track and launches it through the best available provider.
     * Respects user preference, installed apps, and provider capabilities.
     */
    fun launchRecommendation(track: RecommendedTrack, explicitProviderId: String? = null): PlaybackLaunchResult {
        val targetProviderId = explicitProviderId
            ?: track.suggestedProviderId?.takeIf { getProvider(it).isInstalled(context) }
            ?: getPreferredProviderId()

        val provider = getProvider(targetProviderId)
        val match = provider.resolveTrack(track)
        return provider.launchPlayback(context, match)
    }

    companion object {
        private const val PREFS_NAME = "tunemind_provider_prefs"
        private const val KEY_PREFERRED_PROVIDER = "preferred_music_provider"

        @Volatile
        private var INSTANCE: MusicProviderRegistry? = null

        fun getInstance(context: Context): MusicProviderRegistry {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MusicProviderRegistry(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
