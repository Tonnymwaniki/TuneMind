package com.tunemind.app.service

import android.service.notification.NotificationListenerService
import android.util.Log
import com.tunemind.app.data.local.EventBufferRepository
import com.tunemind.app.data.local.TuneMindDatabase
import com.tunemind.app.domain.event.ObservationEventType
import com.tunemind.app.domain.experiment.ExperimentRepository
import com.tunemind.app.domain.experiment.ExperimentStatus
import com.tunemind.app.domain.model.PlaybackObservation
import com.tunemind.app.media.MediaSessionObserver
import com.tunemind.app.media.ObservationIdentity
import com.tunemind.app.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** System-bound observer; active media access is available only with Notification Access. */
class TuneMindListenerService : NotificationListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var observer: MediaSessionObserver
    private val stopEmitted = AtomicBoolean(false)

    override fun onListenerConnected() {
        super.onListenerConnected()
        stopEmitted.set(false)
        observer = MediaSessionObserver(this, serviceScope) { observation ->
            repository.record(observation)
            serviceScope.launch {
                SyncScheduler.schedule(this@TuneMindListenerService)
            }
            // Ensure 7-day study is active and store observation with zero loss
            serviceScope.launch {
                var exp = experimentRepo.getExperiment()
                if (exp == null || exp.status == ExperimentStatus.NOT_STARTED.name) {
                    exp = experimentRepo.start(this@TuneMindListenerService)
                    Log.i(TAG, "Auto-started 7-day listening study from today: ${exp.experimentId}")
                }
                if (exp.status == ExperimentStatus.ACTIVE.name) {
                    val windowEnd = exp.endDateMs ?: (System.currentTimeMillis() + 7L * 86_400_000L)
                    if (observation.eventTimeMs > 0L && observation.eventTimeMs <= windowEnd) {
                        experimentRepo.recordObservation(observation, exp.experimentId)
                    }
                }
            }
        }
        observer.start()
        isConnected = true
        instance = this
        Log.i(TAG, "Notification listener connected; observer initialization requested")

        // Schedule background sync.
        serviceScope.launch {
            SyncScheduler.schedule(this@TuneMindListenerService)
            Log.i(TAG, "Sync work scheduled")
        }
    }

    override fun onListenerDisconnected() {
        emitObservationStop()
        observerOrNull()?.stop()
        isConnected = false
        instance = null
        Log.i(TAG, "Notification listener disconnected")
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        emitObservationStop()
        observerOrNull()?.stop()
        isConnected = false
        instance = null
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Best-effort emission of OBSERVATION_STOPPED.
     *
     * Uses [AtomicBoolean] to prevent duplicate emission for the same observer session.
     *
     * The stop event is persisted on [Dispatchers.IO] with [NonCancellable] context so
     * that it is not cancelled by [serviceScope.cancel] in [onDestroy]. The calling
     * thread (the main thread, for both lifecycle callbacks) is never blocked — the
     * persistence runs asynchronously on an IO thread.
     *
     * Limitations:
     * - Android may kill the process without calling onDestroy() or
     *   onListenerDisconnected(). In that case, this event is never emitted.
     * - The IO thread may not execute the Room write before the process ends.
     * - The backend must not depend on this event for correctness.
     */
    private fun emitObservationStop() {
        if (!stopEmitted.compareAndSet(false, true)) return
        val sessionId = observerOrNull()?.observerSessionId ?: run {
            stopEmitted.set(false)
            return
        }
        val stopEvent = PlaybackObservation(
            eventId = ObservationIdentity.newEventId(),
            observerSessionId = sessionId,
            eventType = ObservationEventType.OBSERVATION_STOPPED,
            eventTimeMs = System.currentTimeMillis(),
            sourcePackage = null,
        )
        // Launch on IO with NonCancellable so the write survives serviceScope.cancel().
        serviceScope.launch(Dispatchers.IO + NonCancellable) {
            try {
                repository.record(stopEvent)
                Log.i(TAG, "OBSERVATION_STOPPED persisted for session $sessionId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist OBSERVATION_STOPPED", e)
            }
        }
    }

    private val db: TuneMindDatabase by lazy { TuneMindDatabase.getInstance(this) }
    private val repository: EventBufferRepository by lazy { EventBufferRepository(db.eventQueueDao()) }
    private val experimentRepo: ExperimentRepository by lazy { ExperimentRepository(db) }

    fun observerOrNull(): MediaSessionObserver? = if (::observer.isInitialized) observer else null

    fun togglePlayback() {
        observerOrNull()?.togglePlayback()
    }

    fun skipNext() {
        observerOrNull()?.skipNext()
    }

    fun skipPrev() {
        observerOrNull()?.skipPrev()
    }

    companion object {
        private const val TAG = "TuneMindListener"

        @Volatile
        var isConnected: Boolean = false
            private set

        @Volatile
        var instance: TuneMindListenerService? = null
            private set
    }
}
