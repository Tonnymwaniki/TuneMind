package com.tunemind.app.provider

/**
 * Technical capabilities supported by an external music provider on Android.
 * TuneMind inspects these to choose the most reliable launch route.
 */
enum class ProviderCapability {
    /** Can accept a text search query to locate tracks. */
    CAN_SEARCH,

    /** Can open a specific track via a direct URL or URI. */
    CAN_OPEN_TRACK,

    /** Supports documented app deep links (e.g. spotify:track:..., spotify:search:...). */
    CAN_DEEP_LINK,

    /** Supports controlling an active media session via MediaController.TransportControls. */
    CAN_CONTROL_ACTIVE_SESSION,

    /** Exposes observable playback states and metadata via Android MediaSessionManager. */
    CAN_REPORT_PLAYBACK
}

/**
 * A resolved track match targeting a specific external music provider.
 */
data class TrackMatch(
    val providerId: String,
    val providerDisplayName: String,
    val title: String,
    val artist: String,
    val providerTrackId: String? = null,
    val deepLinkUri: String? = null,
    val webFallbackUrl: String? = null,
    val searchQuery: String = "$artist $title".trim(),
    val confidence: Float = 1.0f
)
