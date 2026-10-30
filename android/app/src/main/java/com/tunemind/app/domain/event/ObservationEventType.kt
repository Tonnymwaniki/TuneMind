package com.tunemind.app.domain.event

/**
 * The type of raw observation or action recorded by TuneMind.
 *
 * Categorized according to Part 12 of the TuneMind Master Product Directive.
 */
enum class ObservationEventType {
    // Playback Events
    PLAYBACK_STATE_CHANGED,
    METADATA_CHANGED,
    SESSION_CREATED,
    SESSION_DESTROYED,
    OBSERVATION_STARTED,
    OBSERVATION_STOPPED,

    // Audio Device Events
    AUDIO_DEVICE_CONNECTED,
    AUDIO_DEVICE_DISCONNECTED,

    // Volume Events
    VOLUME_CHANGED,

    // Musical Moment Events
    MOMENT_MARKED,
    MOMENT_UPDATED,
    MOMENT_REMOVED,

    // Recommendation Events
    RECOMMENDATION_GENERATED,
    RECOMMENDATION_ACCEPTED,
    RECOMMENDATION_DISMISSED,

    // Playback Agent Events
    TRACK_LAUNCH_REQUESTED,
    TRACK_LAUNCH_RESULT,

    // Privacy & Control Events
    OBSERVATION_ENABLED,
    OBSERVATION_DISABLED
}

/**
 * Distinct provenance of recorded events.
 * A user action is never confused with an Android observation.
 */
enum class EventOrigin {
    ANDROID_OBSERVATION,
    USER_ACTION,
    SYSTEM_DERIVED,
    PROVIDER_RESPONSE
}
