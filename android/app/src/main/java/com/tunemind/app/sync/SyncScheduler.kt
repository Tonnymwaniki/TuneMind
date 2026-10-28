package com.tunemind.app.sync

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules sync work tied to the service lifecycle.
 *
 * Uses [ExistingWorkPolicy.REPLACE] so each listener connection enqueues a
 * fresh sync attempt, replacing any previously failed or pending job.
 * WorkManager handles retry with exponential backoff when the network
 * is unavailable.
 */
object SyncScheduler {

    private const val TAG = "TuneMindScheduler"
    private const val UNIQUE_WORK_NAME = "tunemind-sync"

    /**
     * Enqueue a sync attempt, replacing any existing pending or failed job.
     */
    fun schedule(context: Context) {
        try {
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<EventSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    WorkRequestBackoffInitialMinutes,
                    TimeUnit.MINUTES,
                )
                .addTag(WORK_TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Sync work scheduling notice: ${t.message}")
        }
    }
}
