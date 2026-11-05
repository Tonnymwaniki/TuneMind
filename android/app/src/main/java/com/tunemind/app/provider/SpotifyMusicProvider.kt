package com.tunemind.app.provider

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.tunemind.app.domain.recommendation.RecommendedTrack

/**
 * Concrete provider integration for Spotify on Android.
 * Leverages documented Spotify URIs and web fallbacks.
 */
class SpotifyMusicProvider : MusicProvider {
    override val providerId: String = PROVIDER_ID
    override val displayName: String = "Spotify"
    override val packageName: String = SPOTIFY_PACKAGE

    override val supportedCapabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.CAN_SEARCH,
        ProviderCapability.CAN_OPEN_TRACK,
        ProviderCapability.CAN_DEEP_LINK,
        ProviderCapability.CAN_CONTROL_ACTIVE_SESSION,
        ProviderCapability.CAN_REPORT_PLAYBACK
    )

    override fun isInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun resolveTrack(track: RecommendedTrack): TrackMatch {
        val query = "${track.artist} ${track.title}".trim()
        val encodedQuery = Uri.encode(query)

        val deepLink = if (!track.providerTrackId.isNullOrBlank()) {
            "spotify:track:${track.providerTrackId}"
        } else {
            "spotify:search:$encodedQuery"
        }

        val webUrl = if (!track.providerTrackId.isNullOrBlank()) {
            "https://open.spotify.com/track/${track.providerTrackId}"
        } else {
            "https://open.spotify.com/search/$encodedQuery"
        }

        return TrackMatch(
            providerId = providerId,
            providerDisplayName = displayName,
            title = track.title,
            artist = track.artist,
            providerTrackId = track.providerTrackId,
            deepLinkUri = deepLink,
            webFallbackUrl = webUrl,
            searchQuery = query,
            confidence = track.confidence
        )
    }

    override fun launchPlayback(context: Context, match: TrackMatch): PlaybackLaunchResult {
        if (!isInstalled(context)) {
            // Web fallback if app not installed
            val fallback = match.webFallbackUrl ?: "https://open.spotify.com/search/${Uri.encode(match.searchQuery)}"
            return try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fallback)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                PlaybackLaunchResult.SearchHandoffLaunched(
                    providerId = providerId,
                    providerDisplayName = displayName,
                    query = match.searchQuery,
                    message = "Opened Spotify in browser."
                )
            } catch (e: Exception) {
                PlaybackLaunchResult.ProviderNotInstalled(
                    providerId = providerId,
                    providerDisplayName = displayName,
                    playStorePackage = packageName,
                    message = "Spotify is not installed."
                )
            }
        }

        val track = RecommendedTrack(
            id = match.providerTrackId ?: match.title,
            title = match.title,
            artist = match.artist,
            reason = "Acoustically selected for instant Spotify playback",
            confidence = match.confidence
        )

        return MusicPlayerAutoLauncher.launchWithAutoPlay(
            context = context,
            providerId = providerId,
            displayName = displayName,
            packageName = packageName,
            track = track,
            query = match.searchQuery,
            deepLinkUri = match.deepLinkUri,
            webFallbackUrl = match.webFallbackUrl
        )
    }

    companion object {
        const val PROVIDER_ID = "spotify"
        const val SPOTIFY_PACKAGE = "com.spotify.music"
    }
}
