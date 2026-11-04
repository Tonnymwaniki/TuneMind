package com.tunemind.app.provider

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.MediaStore
import com.tunemind.app.domain.recommendation.RecommendedTrack

/**
 * Registry and orchestrator for all external music providers, unpopular third-party/OEM players,
 * and local phone audio files.
 * Dynamically queries Android system to discover every audio player installed on the user's phone.
 */
class MusicProviderRegistry(
    private val context: Context,
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
) {
    private val staticProviders: List<MusicProvider> = listOf(
        BoomplayMusicProvider(),
        YouTubeMusicProvider(),
        SpotifyMusicProvider(),
        AppleMusicProvider(),
        AudiomackMusicProvider(),
        LocalFilesAudioProvider(),
        GenericMusicProvider()
    )

    /** Returns all static registered providers. */
    fun getAllProviders(): List<MusicProvider> = staticProviders

    /**
     * Dynamically queries Android system to find EVERY music app, audio player,
     * or phone files audio player installed on this phone.
     */
    fun getInstalledProviders(): List<MusicProvider> {
        val result = mutableListOf<MusicProvider>()
        val seenPackages = mutableSetOf<String>()

        // 1. Add known installed streaming services
        for (provider in staticProviders) {
            if (provider.providerId != GenericMusicProvider.PROVIDER_ID &&
                provider.providerId != LocalFilesAudioProvider.PROVIDER_ID &&
                provider.isInstalled(context)
            ) {
                result.add(provider)
                if (provider.packageName != "*") {
                    seenPackages.add(provider.packageName)
                }
            }
        }

        // 2. Discover ANY unpopular, regional, or OEM player installed on the phone
        try {
            val pm = context.packageManager
            val searchIntents = listOf(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC),
                Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH),
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse("content://media/external/audio/media"), "audio/*")
                },
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse("content://media/external/audio/media"), "audio/mpeg")
                }
            )

            for (intent in searchIntents) {
                val matches = pm.queryIntentActivities(intent, 0)
                for (info in matches) {
                    val pkg = info.activityInfo?.packageName ?: continue
                    if (pkg == context.packageName || seenPackages.contains(pkg)) continue
                    seenPackages.add(pkg)

                    val appLabel = runCatching { info.loadLabel(pm).toString() }.getOrNull()
                        ?.takeIf { it.isNotBlank() } ?: pkg

                    result.add(
                        InstalledAppMusicProvider(
                            packageName = pkg,
                            displayName = appLabel,
                            providerId = "pkg_$pkg"
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        // 3. Ensure Local Phone Audio Files option is always present
        if (result.none { it.providerId == LocalFilesAudioProvider.PROVIDER_ID }) {
            result.add(LocalFilesAudioProvider())
        }

        return if (result.isEmpty()) listOf(GenericMusicProvider()) else result
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
        if (saved != null) {
            val p = getProvider(saved)
            if (p.isInstalled(context)) return saved
        }

        // Auto-detect installed streaming players in priority order
        return getInstalledProviders().firstOrNull()?.providerId ?: LocalFilesAudioProvider.PROVIDER_ID
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

    /** Finds a provider by ID or dynamic package or returns the generic fallback. */
    fun getProvider(providerId: String): MusicProvider {
        // Check static providers
        staticProviders.firstOrNull { it.providerId == providerId }?.let { return it }

        // Check dynamic installed providers
        getInstalledProviders().firstOrNull { it.providerId == providerId }?.let { return it }

        // If providerId starts with "pkg_", extract package
        if (providerId.startsWith("pkg_")) {
            val pkg = providerId.removePrefix("pkg_")
            val pm = context.packageManager
            val label = runCatching {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(appInfo).toString()
            }.getOrNull() ?: pkg
            return InstalledAppMusicProvider(packageName = pkg, displayName = label, providerId = providerId)
        }

        return if (providerId == LocalFilesAudioProvider.PROVIDER_ID) LocalFilesAudioProvider() else GenericMusicProvider()
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
