package com.tunemind.app.provider

import android.app.SearchManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.tunemind.app.domain.recommendation.RecommendedTrack
import com.tunemind.app.service.TuneMindListenerService

/**
 * Concrete provider that integrates directly with local phone audio files and offline music players.
 * Resolves tracks against device storage (MediaStore.Audio.Media) and dispatches to installed audio player apps.
 */
class LocalFilesAudioProvider : MusicProvider {
    override val providerId: String = PROVIDER_ID
    override val displayName: String = "Phone Audio Files / Local Player"
    override val packageName: String = "*"

    override val supportedCapabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.CAN_SEARCH,
        ProviderCapability.CAN_OPEN_TRACK,
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
            searchQuery = query,
            confidence = track.confidence
        )
    }

    override fun launchPlayback(context: Context, match: TrackMatch): PlaybackLaunchResult {
        val titleQuery = match.title.trim()
        val artistQuery = match.artist.trim()
        val strictNoSwitch = com.tunemind.app.domain.voice.AgentSettingsStore.isStrictNoAppSwitchEnabled(context)

        // 0. OPTION 3: Native TuneMind In-App Audio Engine (Zero app switching!)
        if (TuneMindInAppPlayer.playLocalMatch(context, titleQuery, artistQuery)) {
            return PlaybackLaunchResult.DirectPlaybackLaunched(
                providerId = providerId,
                providerDisplayName = "TuneMind In-App Player",
                trackTitle = match.title,
                destinationUri = TuneMindInAppPlayer.currentUriFlow.value ?: "tunemind:inapp",
                message = "Playing '${match.title}' natively inside TuneMind (zero app switch)."
            )
        }

        // 1. FAST PATH: Active Background MediaSession
        try {
            val listener = TuneMindListenerService.instance
            if (listener != null) {
                val sent = listener.playFromSearch(null, match.searchQuery)
                if (sent) {
                    return PlaybackLaunchResult.DirectPlaybackLaunched(
                        providerId = providerId,
                        providerDisplayName = displayName,
                        trackTitle = match.title,
                        destinationUri = "mediasession:local",
                        message = "Streaming '${match.title}' via active local player in background."
                    )
                }
            }
        } catch (_: Exception) {}

        if (strictNoSwitch) {
            return PlaybackLaunchResult.LaunchFailed(
                providerId = providerId,
                reason = "Strict Never-Leave-TuneMind Mode is active, and no matching local audio file was found for '${match.title}' on phone storage."
            )
        }

        // 2. Fallback Query MediaStore for an external audio player Activity if Strict Mode is OFF
        try {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST
            )

            val selection = "${MediaStore.Audio.Media.TITLE} LIKE ?"
            val selectionArgs = arrayOf("%$titleQuery%")

            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )

            cursor?.use { c ->
                val idColumn = c.getColumnIndex(MediaStore.Audio.Media._ID)
                if (c.moveToFirst() && idColumn != -1) {
                    val audioId = c.getLong(idColumn)
                    val contentUri: Uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, audioId)

                    val playIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(contentUri, "audio/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    context.startActivity(playIntent)
                    return PlaybackLaunchResult.DirectPlaybackLaunched(
                        providerId = providerId,
                        providerDisplayName = displayName,
                        trackTitle = match.title,
                        destinationUri = contentUri.toString(),
                        message = "Playing local audio file '${match.title}' from phone storage."
                    )
                }
            }
        } catch (_: Exception) {}

        // 3. Fallback: Dispatch universal Android Media Play From Search
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
                message = "Opened '${match.title}' in phone's default audio player."
            )
        } catch (e: Exception) {
            PlaybackLaunchResult.LaunchFailed(
                providerId = providerId,
                reason = "Could not open local audio player: ${e.message}"
            )
        }
    }

    companion object {
        const val PROVIDER_ID = "local_files"
    }
}
