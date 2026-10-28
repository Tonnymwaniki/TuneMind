package com.tunemind.app

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager

class TuneMindApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.tunemind.app.ui.AppHolder.context = applicationContext
        try {
            WorkManager.initialize(this, Configuration.Builder().build())
        } catch (_: IllegalStateException) {
            // Already initialized by the default content provider
        }
    }
}
