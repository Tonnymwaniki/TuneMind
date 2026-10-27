package com.tunemind.app.domain.event

/**
 * The type of raw observation recorded from a MediaController.Callback.
 *
 * These are what the Android MediaSession APIs directly report.
 * They are NOT inferred concepts — SKIP, PAUSE, and COMPLETION are not here.
 * Inferences are computed by the backend AnalyticsService from sequences of these events.
 */
enum class ObservationEventType {
    /** PlaybackState callback; the v1 record retains the raw state code and available values. */
    PLAYBACK_STATE_CHANGED,

    /** MediaMetadata changed — a new track is playing. */
    METADATA_CHANGED,

    /** A new MediaController (active session) was discovered. */
    SESSION_CREATED,

    /** A MediaController session was destroyed. */
    SESSION_DESTROYED,

    /** TuneMindListenerService connected — observation began. */
    OBSERVATION_STARTED,

    /** TuneMindListenerService disconnected — observation stopped. */
    OBSERVATION_STOPPED,
}
