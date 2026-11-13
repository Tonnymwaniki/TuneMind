package com.tunemind.app

import android.app.Application
import android.util.Log
import com.tunemind.app.data.ingestion.AudioFocusIngestionMonitor
import com.tunemind.app.data.ingestion.SpotifyRecentlyPlayedSyncWorker

class TuneMindApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.tunemind.app.ui.AppHolder.context = applicationContext

        // Initialize Data Ingestion Branch: 15-min WorkManager sync & OS Audio-Focus trigger
        SpotifyRecentlyPlayedSyncWorker.schedulePeriodic15MinSync(applicationContext)
        AudioFocusIngestionMonitor.startMonitoring(applicationContext)

        // Global uncaught exception logger
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("TuneMindFatal", "Fatal crash in thread ${thread.name}: ${throwable.message}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
