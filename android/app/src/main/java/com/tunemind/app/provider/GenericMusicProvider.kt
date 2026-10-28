package com.tunemind.app.provider

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import com.tunemind.app.domain.recommendation.RecommendedTrack

/**
 * Universal Android media intent provider.
 * Uses MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH to dispatch to any installed local player.
 */
class GenericMusicProvider : MusicProvider {
    override val providerId: String = PROVIDER_ID
    override val displayName: String = "Default Music Player"
    override val packageName: String = "*"

    override val supportedCapabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.CAN_SEARCH,
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
            searchQuery = query,
            confidence = track.confidence
        )
    }

    override fun launchPlayback(context: Context, match: TrackMatch): PlaybackLaunchResult {
        return try {
            val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
                putExtra(MediaStore.EXTRA_MEDIA_TITLE, match.title)
                putExtra(MediaStore.EXTRA_MEDIA_ARTIST, match.artist)
                putExtra(SearchManager.QUERY, match.searchQuery)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            PlaybackLaunchResult.SearchHandoffLaunched(
                providerId = providerId,
                providerDisplayName = displayName,
                query = match.searchQuery,
                message = "Requested playback of '${match.title}' via default music player."
            )
        } catch (e: Exception) {
            PlaybackLaunchResult.LaunchFailed(
                providerId = providerId,
                reason = "Could not dispatch media search intent: ${e.message}"
            )
        }
    }

    companion object {
        const val PROVIDER_ID = "generic"
    }
}
