package com.tunemind.app

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.tunemind.app.data.auth.TuneMindAuthSessionManager
import com.tunemind.app.domain.network.BackendEndpointManager
import com.tunemind.app.ui.components.TuneMindDevicePerf
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class TuneMindApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        val isLowTier = TuneMindDevicePerf.isLowTierDevice(applicationContext)
        return ImageLoader.Builder(applicationContext)
            .memoryCache {
                MemoryCache.Builder(applicationContext)
                    .maxSizePercent(if (isLowTier) 0.10 else 0.18)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(applicationContext.cacheDir, "tunemind_art_cache"))
                    .maxSizeBytes(if (isLowTier) 25L * 1024 * 1024 else 50L * 1024 * 1024)
                    .build()
            }
            .crossfade(if (isLowTier) 0 else 120)
            .respectCacheHeaders(false)
            .build()
    }
    override fun onCreate() {
        super.onCreate()
        com.tunemind.app.ui.AppHolder.context = applicationContext

        // Initialize Firebase Crashlytics cleanly without blocking Application.onCreate on Keystore IPC.
        val activeBaseUrl = BackendEndpointManager.getActiveHttpBaseUrl(applicationContext)
        runCatching {
            if (FirebaseApp.getApps(this).isNotEmpty()) {
                val crashlytics = FirebaseCrashlytics.getInstance()
                crashlytics.isCrashlyticsCollectionEnabled = true
                crashlytics.setCustomKey("backend_url", activeBaseUrl)
            }
        }

        // Bind user ID + pre-warm Render instance + schedule Sunday/Monthly drop reminders off the main thread
        Thread {
            runCatching { Thread.sleep(1_500L) }
            runCatching {
                com.tunemind.app.sync.WeeklyShareDropWorker.ensureChannel(applicationContext)
                com.tunemind.app.sync.SyncScheduler.scheduleRecapDropReminders(applicationContext)
            }
            runCatching {
                if (FirebaseApp.getApps(this).isNotEmpty()) {
                    val sessionMgr = TuneMindAuthSessionManager.getInstance(applicationContext)
                    sessionMgr.ensureFastInitialized()
                    sessionMgr.sessionFlow.value.userId
                        .takeIf { it.isNotBlank() }
                        ?.let { FirebaseCrashlytics.getInstance().setUserId(it) }
                }
            }
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
            name = "TuneMindStartupWarmup"
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
