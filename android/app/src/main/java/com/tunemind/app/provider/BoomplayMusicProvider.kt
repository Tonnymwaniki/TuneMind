package com.tunemind.app.provider

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.tunemind.app.domain.recommendation.RecommendedTrack
import com.tunemind.app.service.TuneMindListenerService

/**
 * Concrete provider integration for Boomplay Music on Android.
 * Essential for East African & African audio ecosystems (Bongo Flava, Afrobeats, Amapiano).
 */
class BoomplayMusicProvider : MusicProvider {
    override val providerId: String = PROVIDER_ID
    override val displayName: String = "Boomplay"
    override val packageName: String = BOOMPLAY_PACKAGE

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
        val webUrl = "https://www.boomplay.com/search/${encodedQuery}"

        return TrackMatch(
            providerId = providerId,
            providerDisplayName = displayName,
            title = track.title,
            artist = track.artist,
            providerTrackId = track.providerTrackId,
            deepLinkUri = "boomplay://search?keyword=$encodedQuery",
            webFallbackUrl = webUrl,
            searchQuery = query,
            confidence = track.confidence
        )
    }

    override fun launchPlayback(context: Context, match: TrackMatch): PlaybackLaunchResult {
        val query = match.searchQuery
        val webUrl = match.webFallbackUrl ?: "https://www.boomplay.com/search/${Uri.encode(query)}"

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
                    message = "Opened Boomplay track search in browser."
                )
            } catch (e: Exception) {
                PlaybackLaunchResult.ProviderNotInstalled(
                    providerId = providerId,
                    providerDisplayName = displayName,
                    playStorePackage = packageName,
                    message = "Boomplay is not installed."
                )
            }
        }

        val track = RecommendedTrack(
            id = match.providerTrackId ?: match.title,
            title = match.title,
            artist = match.artist,
            reason = "Acoustically selected for Boomplay playback",
            confidence = match.confidence
        )

        return MusicPlayerAutoLauncher.launchWithAutoPlay(
            context = context,
            providerId = providerId,
            displayName = displayName,
            packageName = packageName,
            track = track,
            query = query,
            deepLinkUri = match.deepLinkUri,
            webFallbackUrl = webUrl
        )
    }

    companion object {
        const val PROVIDER_ID = "boomplay"
        const val BOOMPLAY_PACKAGE = "com.afmobi.boomplaymusic"
    }
}
