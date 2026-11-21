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
        TuneMindInAppProvider(),
        BoomplayMusicProvider(),
        YouTubeMusicProvider(),
        AudiomackMusicProvider(),
        SpotifyMusicProvider(),
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

/**
 * Option 3 — Built-In Native TuneMind In-App Audio Provider.
 * Plays local device audio or Spotify preview URLs directly inside TuneMind with zero external app switching.
 */
class TuneMindInAppProvider : MusicProvider {
    override val providerId: String = PROVIDER_ID
    override val displayName: String = "TuneMind In-App Player (Built-In)"
    override val packageName: String = "com.tunemind.app"

    override val supportedCapabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.CAN_DEEP_LINK,
        ProviderCapability.CAN_CONTROL_ACTIVE_SESSION,
        ProviderCapability.CAN_REPORT_PLAYBACK
    )

    override fun isInstalled(context: Context): Boolean = true

    override fun resolveTrack(track: RecommendedTrack): TrackMatch {
        val query = "${track.artist} ${track.title}".trim()
        return TrackMatch(
            providerId = providerId,
            providerDisplayName = displayName,
            title = track.title,
            artist = track.artist,
            providerTrackId = track.providerTrackId,
            deepLinkUri = track.spotifyUri ?: track.providerTrackId,
            webFallbackUrl = null,
            searchQuery = query,
            confidence = track.confidence
        )
    }

    override fun launchPlayback(context: Context, match: TrackMatch): PlaybackLaunchResult {
        // 0. If user has connected Spotify Premium Direct Connect, play the FULL song silently on Spotify first!
        if (SpotifyPremiumConnectManager.isConnectedAndPremium(context)) {
            try {
                TuneMindInAppPlayer.pause()
                TuneMindYouTubeEmbedController.pause()
            } catch (_: Throwable) {}

            when (
                val connectRes = SpotifyPremiumConnectManager.playTrackSilently(
                    context = context,
                    title = match.title,
                    artist = match.artist,
                    query = match.searchQuery,
                    spotifyUri = match.deepLinkUri
                )
            ) {
                is SpotifyPlayResult.Success -> {
                    return PlaybackLaunchResult.DirectPlaybackLaunched(
                        providerId = SpotifyMusicProvider.PROVIDER_ID,
                        providerDisplayName = "Spotify",
                        trackTitle = match.title,
                        destinationUri = connectRes.trackUri,
                        message = "Playing '${match.title}' silently on Spotify (${connectRes.deviceName}) without leaving TuneMind."
                    )
                }
                is SpotifyPlayResult.NoActiveDevice -> {
                    val strictNoSwitch = com.tunemind.app.domain.voice.AgentSettingsStore.isStrictNoAppSwitchEnabled(context)
                    if (!strictNoSwitch || MusicPlayerAutoLauncher.canAutoReturnToTuneMind(context)) {
                        val rec = RecommendedTrack(
                            id = connectRes.trackUri ?: match.title,
                            title = match.title,
                            artist = match.artist,
                            reason = "Waking Spotify for full-track playback",
                            confidence = match.confidence,
                            spotifyUri = connectRes.trackUri
                        )
                        val wakeLaunch = MusicPlayerAutoLauncher.launchWithAutoPlay(
                            context = context,
                            providerId = SpotifyMusicProvider.PROVIDER_ID,
                            displayName = "Spotify",
                            packageName = SpotifyMusicProvider.SPOTIFY_PACKAGE,
                            track = rec,
                            query = match.searchQuery,
                            deepLinkUri = connectRes.trackUri,
                            neverOpenExternalApp = false
                        )
                        if (wakeLaunch.isSuccess) return wakeLaunch
                    }
                }
                else -> Unit
            }
        }

        // 1. Try local device audio file next
        if (TuneMindInAppPlayer.playLocalMatch(context, match.title, match.artist)) {
            val localUri = TuneMindInAppPlayer.currentUriFlow.value ?: "content://media/external/audio/media"
            return PlaybackLaunchResult.DirectPlaybackLaunched(
                providerId = providerId,
                providerDisplayName = displayName,
                trackTitle = match.title,
                destinationUri = localUri,
                message = "Playing '${match.title}' inside TuneMind's built-in audio engine."
            )
        }

        // 2. Try Open-Catalog Full Stream (Audius) or 30s Preview (Deezer/iTunes) inside TuneMind
        val resolved = UniversalTrackResolver.resolveTrackBlocking(
            context = context,
            cleanTitle = match.title,
            cleanArtist = match.artist,
            fullQuery = match.searchQuery
        )

        val audiusStream = resolved?.audiusStreamUrl?.takeIf { it.startsWith("http") }
        if (audiusStream != null && TuneMindInAppPlayer.playStreamUrl(
                context = context,
                streamUrl = audiusStream,
                title = match.title,
                artist = match.artist,
                album = resolved.albumName,
                expectedDurationMs = 0L,
                isPreview = false
            )
        ) {
            return PlaybackLaunchResult.DirectPlaybackLaunched(
                providerId = providerId,
                providerDisplayName = displayName,
                trackTitle = match.title,
                destinationUri = audiusStream,
                message = "Streaming '${match.title}' (full track via Audius) inside TuneMind."
            )
        }

        val ytmVideoId = resolved?.ytmVideoId?.takeIf { it.isNotBlank() }
            ?: match.providerTrackId?.trim()?.takeIf { Regex("^[A-Za-z0-9_-]{11}$").matches(it) }
        val spotifyInstalled = SpotifyMusicProvider().isInstalled(context)
        val preferSpotifyOverPreview = spotifyInstalled &&
            !SpotifyPremiumConnectManager.isConnectedAndPremium(context) &&
            com.tunemind.app.domain.voice.AgentSettingsStore.isSpotifyFreeOpenInSpotifyEnabled(context)

        if (ytmVideoId != null && TuneMindYouTubeEmbedController.startVideo(
                context = context,
                videoId = ytmVideoId,
                title = match.title,
                artist = match.artist,
                album = resolved?.albumName,
                previewFallback = if (preferSpotifyOverPreview) null else resolved?.previewUrl,
                fallbackExternalPkg = if (preferSpotifyOverPreview) SpotifyMusicProvider.SPOTIFY_PACKAGE else YouTubeMusicProvider.YTM_PACKAGE,
                fallbackExternalName = if (preferSpotifyOverPreview) "Spotify" else "YouTube Music"
            )
        ) {
            return PlaybackLaunchResult.DirectPlaybackLaunched(
                providerId = providerId,
                providerDisplayName = displayName,
                trackTitle = match.title,
                destinationUri = "https://music.youtube.com/watch?v=$ytmVideoId",
                message = "Playing full track '${match.title}' inside TuneMind."
            )
        }

        // Spotify Free Smart Mode: never trap users in a 30-second preview if Spotify can play the full song!
        if (preferSpotifyOverPreview) {
            val exactSpotifyUri = MusicPlayerAutoLauncher.extractSpotifyTrackUri(match.deepLinkUri, resolved)
                ?: if (SpotifyPremiumConnectManager.isConnected(context)) {
                    SpotifyPremiumConnectManager.searchExactTrackUri(
                        context = context,
                        title = match.title,
                        artist = match.artist,
                        query = match.searchQuery
                    )
                } else null
            val rec = RecommendedTrack(
                id = exactSpotifyUri ?: resolved?.trackId ?: match.providerTrackId ?: match.title,
                title = match.title,
                artist = match.artist,
                reason = "Opening full track in Spotify instead of 30-second preview",
                confidence = match.confidence,
                spotifyUri = exactSpotifyUri
            )
            val spotifyLaunch = MusicPlayerAutoLauncher.launchWithAutoPlay(
                context = context,
                providerId = SpotifyMusicProvider.PROVIDER_ID,
                displayName = "Spotify",
                packageName = SpotifyMusicProvider.SPOTIFY_PACKAGE,
                track = rec,
                query = match.searchQuery,
                deepLinkUri = exactSpotifyUri ?: match.deepLinkUri,
                neverOpenExternalApp = false
            )
            if (spotifyLaunch.isSuccess) return spotifyLaunch
        }

        val previewUrl = resolved?.previewUrl?.takeIf { it.startsWith("http") }
        if (previewUrl != null && TuneMindInAppPlayer.playStreamUrl(
                context = context,
                streamUrl = previewUrl,
                title = match.title,
                artist = match.artist,
                album = resolved.albumName,
                expectedDurationMs = 30_000L,
                isPreview = true,
                fallbackExternalPkg = if (spotifyInstalled) SpotifyMusicProvider.SPOTIFY_PACKAGE else YouTubeMusicProvider.YTM_PACKAGE,
                fallbackExternalName = if (spotifyInstalled) "Spotify" else "YouTube Music"
            )
        ) {
            return PlaybackLaunchResult.DirectPlaybackLaunched(
                providerId = providerId,
                providerDisplayName = displayName,
                trackTitle = match.title,
                destinationUri = previewUrl,
                message = "Playing '${match.title}' (30s preview) inside TuneMind."
            )
        }

        // 3. Fallback silently via warm active MediaSession (`neverOpenExternalApp = true`)
        val preferredExternal = MusicProviderRegistry.getInstance(context)
            .getInstalledProviders()
            .firstOrNull { it.providerId != PROVIDER_ID && it.providerId != LocalFilesAudioProvider.PROVIDER_ID }
        val fallbackPkg = preferredExternal?.packageName ?: YouTubeMusicProvider.YTM_PACKAGE
        val fallbackName = preferredExternal?.displayName ?: "your active player"

        val rec = RecommendedTrack(
            id = resolved?.trackId ?: match.providerTrackId ?: match.title,
            title = match.title,
            artist = match.artist,
            reason = "Selected for Stay-in-TuneMind background playback",
            confidence = match.confidence
        )
        return MusicPlayerAutoLauncher.launchWithAutoPlay(
            context = context,
            providerId = preferredExternal?.providerId ?: YouTubeMusicProvider.PROVIDER_ID,
            displayName = fallbackName,
            packageName = fallbackPkg,
            track = rec,
            query = match.searchQuery,
            deepLinkUri = resolved?.ytmVideoId?.let { "https://music.youtube.com/watch?v=$it" } ?: resolved?.deezerUrl,
            neverOpenExternalApp = true
        )
    }

    companion object {
        const val PROVIDER_ID = "tunemind_inapp"
    }
}
