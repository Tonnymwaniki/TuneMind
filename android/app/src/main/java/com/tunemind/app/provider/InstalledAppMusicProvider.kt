package com.tunemind.app.provider

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import com.tunemind.app.domain.recommendation.RecommendedTrack
import com.tunemind.app.service.TuneMindListenerService

/**
 * Dynamically discovered third-party or OEM music/audio player installed on the device.
 * Automatically accommodates unpopular, niche, or regional audio players without hardcoded classes.
 */
class InstalledAppMusicProvider(
    override val packageName: String,
    override val displayName: String,
    override val providerId: String = "pkg_$packageName"
) : MusicProvider {

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
        return TrackMatch(
            providerId = providerId,
            providerDisplayName = displayName,
            title = track.title,
            artist = track.artist,
            providerTrackId = track.providerTrackId,
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
                        message = "Streaming '${match.title}' on $displayName in background."
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
                    message = "Opened '${match.title}' in $displayName."
                )
            } catch (_: Exception) {}

            // 3. Fallback: Launch app's main launcher activity
            try {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (launchIntent != null) {
                    context.startActivity(launchIntent)
                    return PlaybackLaunchResult.SearchHandoffLaunched(
                        providerId = providerId,
                        providerDisplayName = displayName,
                        query = query,
                        message = "Launched $displayName."
                    )
                }
            } catch (_: Exception) {}
        }

        return PlaybackLaunchResult.LaunchFailed(
            providerId = providerId,
            reason = "Could not open $displayName."
        )
    }
}
