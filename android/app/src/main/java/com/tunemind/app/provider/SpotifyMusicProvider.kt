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
        } catch (_: PackageManager.NameNotFoundException) {
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
            // Attempt web fallback if app is not installed
            match.webFallbackUrl?.let { webUrl ->
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                return try {
                    context.startActivity(webIntent)
                    PlaybackLaunchResult.SearchHandoffLaunched(
                        providerId = providerId,
                        providerDisplayName = displayName,
                        query = match.searchQuery,
                        message = "Spotify app not installed. Opened track search in browser."
                    )
                } catch (e: Exception) {
                    PlaybackLaunchResult.ProviderNotInstalled(
                        providerId = providerId,
                        providerDisplayName = displayName,
                        playStorePackage = packageName,
                        message = "Spotify is not installed on this device."
                    )
                }
            }
            return PlaybackLaunchResult.ProviderNotInstalled(
                providerId = providerId,
                providerDisplayName = displayName,
                playStorePackage = packageName,
                message = "Spotify is not installed on this device."
            )
        }

        val isDirectTrack = match.deepLinkUri?.startsWith("spotify:track:") == true

        // Launch in-app deep link
        match.deepLinkUri?.let { uriString ->
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
                    setPackage(packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)

                return if (isDirectTrack) {
                    PlaybackLaunchResult.DirectPlaybackLaunched(
                        providerId = providerId,
                        providerDisplayName = displayName,
                        trackTitle = match.title,
                        destinationUri = uriString,
                        message = "Starting playback for '${match.title}' in Spotify."
                    )
                } else {
                    PlaybackLaunchResult.SearchHandoffLaunched(
                        providerId = providerId,
                        providerDisplayName = displayName,
                        query = match.searchQuery,
                        message = "Opened '${match.title}' in Spotify for instant playback."
                    )
                }
            } catch (e: Exception) {
                // Fallback to web URL or generic search
            }
        }

        // Fallback to web URL targeting Spotify
        return try {
            val fallback = match.webFallbackUrl ?: "https://open.spotify.com/search/${Uri.encode(match.searchQuery)}"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fallback)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            PlaybackLaunchResult.SearchHandoffLaunched(
                providerId = providerId,
                providerDisplayName = displayName,
                query = match.searchQuery,
                message = "Handoff to Spotify via web link."
            )
        } catch (e: Exception) {
            PlaybackLaunchResult.LaunchFailed(
                providerId = providerId,
                reason = "Could not launch Spotify: ${e.message}"
            )
        }
    }

    companion object {
        const val PROVIDER_ID = "spotify"
        const val SPOTIFY_PACKAGE = "com.spotify.music"
    }
}
