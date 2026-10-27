package com.tunemind.app.media

import java.util.UUID

/** Event identity is random and does not encode callback payload or mutable playback state. */
object ObservationIdentity {
    fun newEventId(): String = UUID.randomUUID().toString()
}

/**
 * Suppresses only an identical callback snapshot repeated within a short window.
 * Position/state/timestamp changes form a different fingerprint and remain recordable.
 */
class CallbackDeduplicator(
    private val duplicateWindowMs: Long = DEFAULT_DUPLICATE_WINDOW_MS,
    private val maxRememberedFingerprints: Int = 256,
) {
    private val lastSeenAt = LinkedHashMap<Any, Long>()

    fun shouldAccept(fingerprint: Any, elapsedRealtimeMs: Long): Boolean {
        val previous = lastSeenAt[fingerprint]
        if (previous != null && elapsedRealtimeMs >= previous && elapsedRealtimeMs - previous <= duplicateWindowMs) {
            return false
        }
        lastSeenAt[fingerprint] = elapsedRealtimeMs
        lastSeenAt.entries.removeIf { (_, seenAt) ->
            elapsedRealtimeMs >= seenAt && elapsedRealtimeMs - seenAt > duplicateWindowMs
        }
        while (lastSeenAt.size > maxRememberedFingerprints) {
            lastSeenAt.remove(lastSeenAt.keys.first())
        }
        return true
    }

    companion object {
        const val DEFAULT_DUPLICATE_WINDOW_MS = 1_000L
    }
}
