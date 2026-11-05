package com.tunemind.app.provider

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.tunemind.app.domain.recommendation.RecommendedTrack
import com.tunemind.app.service.TuneMindListenerService

/**
 * Concrete provider integration for Apple Music on Android.
 */
class AppleMusicProvider : MusicProvider {
    override val providerId: String = PROVIDER_ID
    override val displayName: String = "Apple Music"
    override val packageName: String = APPLE_MUSIC_PACKAGE

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
        val webUrl = "https://music.apple.com/search?term=$encodedQuery"

        return TrackMatch(
            providerId = providerId,
            providerDisplayName = displayName,
            title = track.title,
            artist = track.artist,
            providerTrackId = track.providerTrackId,
            deepLinkUri = webUrl,
            webFallbackUrl = webUrl,
            searchQuery = query,
            confidence = track.confidence
        )
    }

    override fun launchPlayback(context: Context, match: TrackMatch): PlaybackLaunchResult {
        val query = match.searchQuery
        val webUrl = match.webFallbackUrl ?: "https://music.apple.com/search?term=${Uri.encode(query)}"

        if (!isInstalled(context)) {
            return try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                PlaybackLaunchResult.SearchHandoffLaunched(
                    providerId = providerId,
                    providerDisplayName = displayName,
                    query = query,
                    message = "Opened Apple Music search in browser."
                )
            } catch (e: Exception) {
                PlaybackLaunchResult.ProviderNotInstalled(
                    providerId = providerId,
                    providerDisplayName = displayName,
                    playStorePackage = packageName,
                    message = "Apple Music is not installed."
                )
            }
        }

        val track = RecommendedTrack(
            id = match.providerTrackId ?: match.title,
            title = match.title,
            artist = match.artist,
            reason = "Calibrated for Apple Music playback",
            confidence = match.confidence
        )

        return MusicPlayerAutoLauncher.launchWithAutoPlay(
            context = context,
            providerId = providerId,
            displayName = displayName,
            packageName = packageName,
            track = track,
            query = query,
            deepLinkUri = webUrl,
            webFallbackUrl = webUrl
        )
    }

    companion object {
        const val PROVIDER_ID = "apple_music"
        const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"
    }
}
