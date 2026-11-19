package com.tunemind.app

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.tunemind.app.data.auth.TuneMindAuthSessionManager
import com.tunemind.app.domain.network.BackendEndpointManager
import java.net.HttpURLConnection
import java.net.URL

class TuneMindApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.tunemind.app.ui.AppHolder.context = applicationContext

        // Initialize Firebase Crashlytics cleanly whenever google-services.json is bundled.
        val activeBaseUrl = BackendEndpointManager.getActiveHttpBaseUrl(applicationContext)
        runCatching {
            if (FirebaseApp.getApps(this).isNotEmpty()) {
                val crashlytics = FirebaseCrashlytics.getInstance()
                crashlytics.isCrashlyticsCollectionEnabled = true
                crashlytics.setCustomKey("backend_url", activeBaseUrl)
                TuneMindAuthSessionManager.getInstance(applicationContext)
                    .sessionFlow.value.userId
                    .takeIf { it.isNotBlank() }
                    ?.let { crashlytics.setUserId(it) }
            }
        }

        // Pre-warm Render Free instance in the background on cold app launch so auth/AI calls don't stall.
        Thread {
            runCatching {
                val conn = (URL("${activeBaseUrl.trimEnd('/')}/health").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5_000
                    readTimeout = 5_000
                }
                try {
                    conn.responseCode
                } finally {
                    conn.disconnect()
                }
            }
        }.apply {
            name = "TuneMindHealthWarmup"
            isDaemon = true
            start()
        }

        // Global uncaught exception logger
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("TuneMindFatal", "Fatal crash in thread ${thread.name}: ${throwable.message}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
