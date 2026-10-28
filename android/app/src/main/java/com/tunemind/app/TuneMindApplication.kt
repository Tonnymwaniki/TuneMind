package com.tunemind.app

import android.app.Application
import android.util.Log

class TuneMindApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.tunemind.app.ui.AppHolder.context = applicationContext

        // Global uncaught exception logger
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("TuneMindFatal", "Fatal crash in thread ${thread.name}: ${throwable.message}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
