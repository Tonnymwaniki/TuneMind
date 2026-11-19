package com.tunemind.app.provider

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.tunemind.app.domain.recommendation.RecommendedTrack

/**
 * Passive OS-controlled provider integration for Spotify on Android.
 *
 * Uses zero Spotify Developer credentials, OAuth tokens, or App Remote SDKs.
 * Dispatches playback strictly via standard Android MediaSession (`playFromSearch`)
 * or Android's `MEDIA_PLAY_FROM_SEARCH` intent into the installed Spotify app.
 */
class SpotifyMusicProvider : MusicProvider {
    override val providerId: String = PROVIDER_ID
    override val displayName: String = "Spotify"
    override val packageName: String = SPOTIFY_PACKAGE

    override val supportedCapabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.CAN_SEARCH,
        ProviderCapability.CAN_OPEN_TRACK,
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
        val searchWebUrl = "https://open.spotify.com/search/$encodedQuery"

        return TrackMatch(
            providerId = providerId,
            providerDisplayName = displayName,
            title = track.title,
            artist = track.artist,
            providerTrackId = track.providerTrackId,
            deepLinkUri = null,
            webFallbackUrl = searchWebUrl,
            searchQuery = query,
            confidence = track.confidence
        )
    }

    override fun launchPlayback(context: Context, match: TrackMatch): PlaybackLaunchResult {
        if (!isInstalled(context)) {
            return PlaybackLaunchResult.ProviderNotInstalled(
                providerId = providerId,
                providerDisplayName = displayName,
                playStorePackage = packageName,
                message = "Spotify is not installed on this device."
            )
        }

        val track = RecommendedTrack(
            id = match.providerTrackId ?: match.title,
            title = match.title,
            artist = match.artist,
            reason = "Selected for passive Spotify OS playback",
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
