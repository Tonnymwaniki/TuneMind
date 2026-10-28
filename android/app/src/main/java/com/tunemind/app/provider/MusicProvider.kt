package com.tunemind.app.provider

import android.content.Context
import com.tunemind.app.domain.recommendation.RecommendedTrack

/**
 * Outcome of attempting to launch playback for a recommended track through an external music app.
 * Distinguishes direct deep-links from search handoffs and unavailable states.
 */
sealed class PlaybackLaunchResult {
    data class DirectPlaybackLaunched(
        val providerId: String,
        val providerDisplayName: String,
        val trackTitle: String,
        val destinationUri: String,
        val message: String
    ) : PlaybackLaunchResult()

    data class SearchHandoffLaunched(
        val providerId: String,
        val providerDisplayName: String,
        val query: String,
        val message: String
    ) : PlaybackLaunchResult()

    data class ProviderNotInstalled(
        val providerId: String,
        val providerDisplayName: String,
        val playStorePackage: String,
        val message: String
    ) : PlaybackLaunchResult()

    data class LaunchFailed(
        val providerId: String,
        val reason: String
    ) : PlaybackLaunchResult()
}

/**
 * Modular provider integration boundary.
 * TuneMind uses this to interact with music services installed on the user's Android phone.
 */
interface MusicProvider {
    val providerId: String
    val displayName: String
    val packageName: String
    val supportedCapabilities: Set<ProviderCapability>

    /** Checks whether the provider's application is currently installed on the device. */
    fun isInstalled(context: Context): Boolean

    /** Resolves a recommended track into provider-specific URIs, web fallbacks, or search queries. */
    fun resolveTrack(track: RecommendedTrack): TrackMatch

    /** Initiates playback or handoff in the target music service. */
    fun launchPlayback(context: Context, match: TrackMatch): PlaybackLaunchResult
}
