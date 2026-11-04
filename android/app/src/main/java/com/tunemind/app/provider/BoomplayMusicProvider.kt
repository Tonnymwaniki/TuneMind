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

        // 1. FAST PATH: Active Background MediaSession
        try {
            val listener = TuneMindListenerService.instance
            if (listener != null) {
                val sent = listener.playFromSearch(packageName, query)
                if (sent) {
                    return PlaybackLaunchResult.DirectPlaybackLaunched(
                        providerId = providerId,
                        providerDisplayName = displayName,
                        trackTitle = match.title,
                        destinationUri = "mediasession:$packageName",
                        message = "Streaming '${match.title}' on Boomplay in background."
                    )
                }
            }
        } catch (_: Exception) {}

        // 2. Targeted Media Play From Search
        if (isInstalled(context)) {
            try {
                val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
                    putExtra(MediaStore.EXTRA_MEDIA_TITLE, match.title)
                    putExtra(MediaStore.EXTRA_MEDIA_ARTIST, match.artist)
                    putExtra(SearchManager.QUERY, query)
                    setPackage(packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return PlaybackLaunchResult.SearchHandoffLaunched(
                    providerId = providerId,
                    providerDisplayName = displayName,
                    query = query,
                    message = "Opened '${match.title}' in Boomplay."
                )
            } catch (_: Exception) {}

            // Try deep link
            try {
                val deepLinkIntent = Intent(Intent.ACTION_VIEW, Uri.parse(match.deepLinkUri)).apply {
                    setPackage(packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(deepLinkIntent)
                return PlaybackLaunchResult.SearchHandoffLaunched(
                    providerId = providerId,
                    providerDisplayName = displayName,
                    query = query,
                    message = "Opened '${match.title}' in Boomplay."
                )
            } catch (_: Exception) {}
        }

        // 3. Web Fallback
        val webUrl = match.webFallbackUrl ?: "https://www.boomplay.com/search/${Uri.encode(query)}"
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
            PlaybackLaunchResult.LaunchFailed(
                providerId = providerId,
                reason = "Could not open Boomplay: ${e.message}"
            )
        }
    }

    companion object {
        const val PROVIDER_ID = "boomplay"
        const val BOOMPLAY_PACKAGE = "com.afmobi.boomplaymusic"
    }
}
