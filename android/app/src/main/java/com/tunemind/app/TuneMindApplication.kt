package com.tunemind.app

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager

class TuneMindApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            WorkManager.initialize(this, Configuration.Builder().build())
        } catch (_: IllegalStateException) {
            // Already initialized by the default content provider
        }
    }
}
