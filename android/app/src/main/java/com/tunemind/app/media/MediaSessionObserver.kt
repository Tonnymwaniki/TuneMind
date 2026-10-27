package com.tunemind.app.media

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import com.tunemind.app.domain.event.ObservationEventType
import com.tunemind.app.domain.model.PlaybackObservation
import com.tunemind.app.media.adapters.GenericMediaAdapter
import com.tunemind.app.service.TuneMindListenerService
import com.tunemind.app.service.NotificationAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Manages active MediaController callbacks while the notification listener is connected. */
class MediaSessionObserver(
    private val service: NotificationListenerService,
    private val scope: CoroutineScope,
    private val onObservation: suspend (PlaybackObservation) -> Unit,
) {
    private val manager = service.getSystemService(MediaSessionManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val adapter = GenericMediaAdapter()
    private val controllers = mutableMapOf<MediaSession.Token, Entry>()
    val observerSessionId: String = java.util.UUID.randomUUID().toString()
    private val deduplicator = CallbackDeduplicator()
    @Volatile
    private var started = false

    /** True when callbacks are registered and media-session access is active. */
    val isStarted: Boolean get() = started

    private val activeSessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { active ->
        reconcile(active.orEmpty())
    }

    /** Starts observing. Returns false when the caller should schedule a retry. */
    fun start(): Boolean {
        if (started) return true
        if (!hasNotificationAccess()) {
            diagnosticStatus = "Observer idle: Notification Access unavailable"
            return false
        }
        diagnosticStatus = "Checking active media sessions…"
        started = true
        return try {
            manager.addOnActiveSessionsChangedListener(
                activeSessionsListener,
                ComponentName(service, TuneMindListenerService::class.java),
                mainHandler,
            )
            val active = manager.getActiveSessions(ComponentName(service, TuneMindListenerService::class.java)).orEmpty()
            diagnosticStatus = "Watching ${active.size} active media session(s)"
            Log.i(TAG, "Observer started with ${active.size} active media sessions")
            reconcile(active)
            started
        } catch (_: SecurityException) {
            diagnosticStatus = "Observer blocked: Android denied media-session access"
            // Access may be revoked between the settings check and framework call.
            stop()
            false
        } catch (_: IllegalStateException) {
            diagnosticStatus = "Observer unavailable: Android media-session service not ready"
            stop()
            false
        }
    }

    fun stop() {
        if (!started && controllers.isEmpty()) return
        started = false
        diagnosticStatus = "Observer stopped"
        runCatching { manager.removeOnActiveSessionsChangedListener(activeSessionsListener) }
        controllers.values.forEach { entry ->
            runCatching { entry.controller.unregisterCallback(entry.callback) }
        }
        controllers.clear()
    }

    private fun hasNotificationAccess(): Boolean = NotificationAccess.isEnabled(service)

    private fun reconcile(activeControllers: List<MediaController>) {
        if (!started || !hasNotificationAccess()) {
            stop()
            return
        }
        val eligibleControllers = activeControllers.filterNot {
            isExcludedSource(sourcePackage(it))
        }
        val excludedCount = activeControllers.size - eligibleControllers.size
        Log.i(TAG, "Active media session update: ${eligibleControllers.size} eligible sessions")
        diagnosticStatus = buildString {
            append("Watching ${eligibleControllers.size} active media session(s)")
            if (excludedCount > 0) append("; YouTube app excluded")
        }
        val activeByToken = eligibleControllers.associateBy { it.sessionToken }

        (controllers.keys - activeByToken.keys).forEach { token ->
            controllers.remove(token)?.let { entry ->
                runCatching { entry.controller.unregisterCallback(entry.callback) }
                emit(entry, ObservationEventType.SESSION_DESTROYED)
            }
        }

        activeByToken.forEach { (token, controller) ->
            if (token !in controllers) {
                val entry = Entry(controller)
                entry.callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) {
                        emit(entry, ObservationEventType.PLAYBACK_STATE_CHANGED, state = state)
                    }

                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        emit(entry, ObservationEventType.METADATA_CHANGED, metadata = metadata)
                    }

                    override fun onSessionDestroyed() {
                        controllers.remove(token)?.let { removed ->
                            runCatching { removed.controller.unregisterCallback(this) }
                        }
                        emit(entry, ObservationEventType.SESSION_DESTROYED)
                    }
                }
                controllers[token] = entry
                try {
                    controller.registerCallback(entry.callback, mainHandler)
                    emit(entry, ObservationEventType.SESSION_CREATED)
                } catch (_: SecurityException) {
                    controllers.remove(token)
                } catch (_: IllegalStateException) {
                    controllers.remove(token)
                }
            }
        }
    }

    private fun emit(
        entry: Entry,
        eventType: ObservationEventType,
        metadata: MediaMetadata? = entry.controller.metadata,
        state: android.media.session.PlaybackState? = entry.controller.playbackState,
    ) {
        if (!started) return
        if (!hasNotificationAccess()) {
            stop()
            return
        }
        val source = sourcePackage(entry.controller)
        // Keep this guard in the callback path as well as reconciliation so a controller
        // cannot be persisted if its package becomes excluded during its lifecycle.
        if (isExcludedSource(source)) {
            diagnosticStatus = "Ignoring excluded YouTube app media session"
            return
        }
        if (entry.advertisementFilterState.shouldSuppress(metadata)) {
            diagnosticStatus = "Skipping explicitly marked advertisement from ${source ?: "unknown source"}"
            return
        }
        val fingerprint = callbackFingerprint(
            controllerKey = entry.controller.sessionToken.toString(),
            type = eventType,
            source = source,
            metadata = metadata,
            state = state,
        )
        if (!deduplicator.shouldAccept(fingerprint, SystemClock.elapsedRealtime())) return
        val observation = adapter.extractObservation(
            eventId = ObservationIdentity.newEventId(),
            eventType = eventType,
            metadata = metadata,
            playbackState = state,
            observerSessionId = observerSessionId,
            sourcePackage = source,
            eventTimeMs = System.currentTimeMillis(),
        )
        diagnosticStatus = "Saving ${eventType.name} from ${source ?: "unknown source"}"
        Log.d(TAG, "Observed ${eventType.name} from ${source ?: "unknown source"}")
        scope.launch {
            try {
                onObservation(observation)
                diagnosticStatus = "Saved ${eventType.name} from ${source ?: "unknown source"}"
                Log.d(TAG, "Stored ${eventType.name} observation from ${source ?: "unknown source"}")
            } catch (error: Exception) {
                diagnosticStatus = "Room write failed: ${error.javaClass.simpleName}"
                Log.e(TAG, "Could not store ${eventType.name} observation", error)
            }
        }
    }

    private fun callbackFingerprint(
        controllerKey: String,
        type: ObservationEventType,
        source: String?,
        metadata: MediaMetadata?,
        state: android.media.session.PlaybackState?,
    ): CallbackFingerprint = CallbackFingerprint(
        controllerKey = controllerKey,
        type = type,
        source = source,
        mediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
        title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
        artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
        album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
        durationMs = metadata?.takeIf { it.containsKey(MediaMetadata.METADATA_KEY_DURATION) }
            ?.getLong(MediaMetadata.METADATA_KEY_DURATION),
        playbackState = state?.state,
        positionMs = state?.position,
        playbackSpeed = state?.playbackSpeed,
        positionUpdateElapsedRealtimeMs = state?.lastPositionUpdateTime,
    )

    private data class CallbackFingerprint(
        val controllerKey: String,
        val type: ObservationEventType,
        val source: String?,
        val mediaId: String?,
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long?,
        val playbackState: Int?,
        val positionMs: Long?,
        val playbackSpeed: Float?,
        val positionUpdateElapsedRealtimeMs: Long?,
    )

    private fun sourcePackage(controller: MediaController): String? =
        runCatching { controller.packageName }.getOrNull()

    private fun isExcludedSource(packageName: String?): Boolean =
        packageName == EXCLUDED_YOUTUBE_PACKAGE

    private class Entry(val controller: MediaController) {
        lateinit var callback: MediaController.Callback
        val advertisementFilterState = AdvertisementFilterState()
    }

    companion object {
        private const val TAG = "TuneMindObserver"
        private const val EXCLUDED_YOUTUBE_PACKAGE = "com.google.android.youtube"

        @Volatile
        var diagnosticStatus: String = "Observer not started"
            private set
    }
}
