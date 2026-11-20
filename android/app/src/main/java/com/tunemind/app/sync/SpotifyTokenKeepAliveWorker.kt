package com.tunemind.app.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tunemind.app.provider.SpotifyPremiumConnectManager

/**
 * Periodic WorkManager keep-alive worker that:
 * 1. Restores the user's encrypted Spotify connection from TuneMind cloud if local tokens are absent.
 * 2. Proactively rotates/refreshes the Spotify access & refresh tokens when connected, then syncs the
 *    latest encrypted refresh token back to TuneMind cloud so users never have to reconnect again.
 */
class SpotifyTokenKeepAliveWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            SpotifyPremiumConnectManager.syncAppDefaultClientId(applicationContext)
            val status = SpotifyPremiumConnectManager.ensureLoaded(applicationContext)
            if (!status.isConnected) {
                val restored = SpotifyPremiumConnectManager.restoreConnectionFromCloudIfNeeded(applicationContext)
                Log.d(TAG, "Spotify cloud restore check completed (restored=$restored)")
            } else {
                val refreshed = SpotifyPremiumConnectManager.refreshAccessTokenIfNeeded(
                    context = applicationContext,
                    forceRefresh = true
                )
                if (refreshed != null) {
                    SpotifyPremiumConnectManager.backupConnectionToCloud(applicationContext)
                    Log.i(TAG, "Spotify PKCE keep-alive token refresh + cloud sync succeeded.")
                }
            }
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "Spotify keep-alive worker notice: ${t.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SpotifyKeepAliveWorker"
    }
}
