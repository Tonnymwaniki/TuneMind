package com.tunemind.app.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tunemind.app.data.local.EventBufferRepository
import com.tunemind.app.data.local.TuneMindDatabase
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * CoroutineWorker that delivers unsynced observations to the backend.
 *
 * Protocol:
 * 1. Read up to 100 unsynced rows from Room (by local sequence order).
 * 2. Map each local PlaybackObservation to the backend ListeningEvent schema.
 * 3. POST the batch to /api/v1/events.
 * 4. Parse the JSON response for "accepted" eventIds.
 * 5. Mark only explicitly acknowledged eventIds as synced.
 *
 * Events are NEVER marked synced on network error, timeout, server error,
 * malformed response, or persistence failure. Rejected eventIds remain unsynced.
 */
class EventSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "doWork() entered")
        val dao: com.tunemind.app.data.local.EventQueueDao
        try {
            dao = TuneMindDatabase.getInstance(applicationContext).eventQueueDao()
            Log.i(TAG, "Room DAO obtained")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get Room DAO", e)
            return Result.retry()
        }

        val unsynced: List<com.tunemind.app.data.local.EventQueueEntity>
        try {
            unsynced = dao.getUnsynced(limit = BATCH_SIZE)
            Log.i(TAG, "Unsynced query returned ${unsynced.size} rows")
        } catch (e: Exception) {
            Log.e(TAG, "Unsynced query failed", e)
            return Result.retry()
        }

        if (unsynced.isEmpty()) {
            Log.i(TAG, "No unsynced events to deliver")
            return Result.success()
        }

        val observations = unsynced.mapNotNull { row ->
            runCatching {
                com.tunemind.app.data.local.PlaybackObservationCodec.decode(
                    row.payloadJson,
                    legacySavedAtMs = row.savedAtMs,
                )
            }.getOrNull()
        }
        Log.i(TAG, "Decoded ${observations.size}/${unsynced.size} observations")
        if (observations.isEmpty()) {
            Log.w(TAG, "All ${unsynced.size} unsynced rows failed to decode")
            return Result.success()
        }

        val requestBody = ListeningEventMapper.toRequestBody(observations)
        val baseUrl = SyncConfig.baseUrl(applicationContext)
        Log.i(TAG, "Backend URL resolved to: $baseUrl")
        val url = URL("$baseUrl/api/v1/events")

        val responseJson: String
        try {
            Log.i(TAG, "POSTing ${observations.size} events to $url")
            responseJson = httpPost(url, requestBody)
            Log.i(TAG, "HTTP response received (${responseJson.length} chars)")
        } catch (e: Exception) {
            Log.e(TAG, "Network error delivering ${observations.size} events", e)
            return Result.retry()
        }

        val acceptedIds: List<String>
        try {
            acceptedIds = parseAcceptedIds(responseJson)
            Log.i(TAG, "Parsed ${acceptedIds.size} accepted eventIds from response")
        } catch (e: Exception) {
            Log.e(TAG, "Malformed server response: ${responseJson.take(200)}", e)
            return Result.retry()
        }

        if (acceptedIds.isNotEmpty()) {
            dao.markSynced(acceptedIds)
            Log.i(TAG, "Marked ${acceptedIds.size} events as synced (batch of ${observations.size})")
        } else {
            Log.i(TAG, "No events were accepted by the server")
        }

        return Result.success()
    }

    private fun httpPost(url: URL, body: String): String {
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(body)
                writer.flush()
            }

            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                val errorBody = try {
                    BufferedReader(InputStreamReader(conn.errorStream)).readText()
                } catch (_: Exception) { "" }
                throw SyncHttpException(responseCode, errorBody)
            }

            return BufferedReader(InputStreamReader(conn.inputStream)).readText()
        } finally {
            conn.disconnect()
        }
    }

    /** Parses the server response and returns the list of acknowledged eventIds. */
    internal fun parseAcceptedIds(responseJson: String): List<String> {
        val obj = org.json.JSONObject(responseJson)
        val accepted = obj.optJSONArray("accepted") ?: return emptyList()
        return (0 until accepted.length()).map { accepted.getString(it) }
    }

    companion object {
        private const val TAG = "TuneMindSync"
        private const val BATCH_SIZE = 100
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000

        private class SyncHttpException(val code: Int, body: String) :
            Exception("HTTP $code: $body")

        /**
         * Enqueue a one-time sync attempt. Call this from the scheduler;
         * WorkManager handles retry with exponential backoff.
         */
        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<EventSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequestBackoffInitialMinutes,
                    TimeUnit.MINUTES,
                )
                .addTag(WORK_TAG)
                .build()

            WorkManager.getInstance(context)
                .enqueue(request)
        }
    }
}

/** Configuration constants for the sync worker. */
object SyncConfig {
    private const val TAG = "TuneMindSyncConfig"

    /**
     * Returns the backend base URL.
     *
     * Resolution order:
     * 1. System property "tunemind.baseUrl" (set via adb shell setprop for testing)
     * 2. BuildConfig field (set by build type in build.gradle.kts)
     * 3. Default for development
     *
     * Development URLs:
     *   Android emulator  → http://10.0.2.2:3000  (host loopback via emulator)
     *   Physical device    → http://<your-lan-ip>:3000
     */
    fun baseUrl(context: Context): String {
        val prefUrl = try {
            val prefs = context.getSharedPreferences("tunemind_sync_prefs", Context.MODE_PRIVATE)
            prefs.getString("tunemind_backend_url", null)
        } catch (_: Exception) {
            null
        }
        if (!prefUrl.isNullOrBlank()) {
            return prefUrl.trim().removeSuffix("/")
        }

        return try {
            val clazz = Class.forName("${context.packageName}.BuildConfig")
            val field = clazz.getField("TUNEMIND_BASE_URL")
            field.get(null) as? String
        } catch (_: Exception) {
            null
        }?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL
    }

    private const val DEFAULT_BASE_URL = "https://ais-dev-j7hrc5mfe5abbddivox3ii-740908901963.europe-west2.run.app"
}

/**
 * Work request initial backoff minutes. Kept in a val to allow test overrides.
 * Default matches WorkManager's default (30 seconds = 0.5 minutes), but exposed
 * here to make the policy visible.
 */
var WorkRequestBackoffInitialMinutes: Long = 1
    internal set

internal const val WORK_TAG = "tunemind_event_sync"
