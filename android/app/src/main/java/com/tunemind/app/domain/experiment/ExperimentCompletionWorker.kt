package com.tunemind.app.domain.experiment

import android.content.Context
import android.util.Log
import androidx.work.*
import com.tunemind.app.data.local.TuneMindDatabase
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that checks whether the active experiment has elapsed
 * its seven-day window and transitions it to COMPLETED.
 */
class ExperimentCompletionWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result = runBlocking {
        val db = TuneMindDatabase.getInstance(applicationContext)
        val repo = ExperimentRepository(db)
        val experiment = repo.getExperiment() ?: return@runBlocking Result.success()

        if (experiment.status != ExperimentStatus.ACTIVE.name) {
            Log.i(TAG, "Experiment ${experiment.experimentId} is not active (${experiment.status})")
            return@runBlocking Result.success()
        }

        val deadlineMs = experiment.endDateMs ?: return@runBlocking Result.retry()
        val now = System.currentTimeMillis()

        if (now < deadlineMs) {
            val remaining = deadlineMs - now
            Log.i(TAG, "Experiment deadline not yet reached. Rescheduling in ${remaining}ms")
            schedule(applicationContext, experiment, remaining)
            return@runBlocking Result.success()
        }

        val count = repo.getSummary(experiment.experimentId)?.observationCount ?: 0
        repo.complete()
        Log.i(TAG, "Experiment ${experiment.experimentId} completed with $count observations")
        return@runBlocking Result.success()
    }

    companion object {
        private const val TAG = "TuneMindExpComplete"
        private const val UNIQUE_WORK_NAME = "tunemind-experiment-completion"

        fun schedule(context: Context, experiment: ExperimentEntity, delayMs: Long? = null) {
            val effectiveDelay = delayMs ?: (experiment.endDateMs?.minus(System.currentTimeMillis())?.coerceAtLeast(0L) ?: return)

            val request = OneTimeWorkRequestBuilder<ExperimentCompletionWorker>()
                .setInitialDelay(effectiveDelay, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.NONE)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .addTag(UNIQUE_WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
            Log.i(TAG, "Completion worker scheduled in ${effectiveDelay}ms")
        }
    }
}
