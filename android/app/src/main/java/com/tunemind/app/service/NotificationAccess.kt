package com.tunemind.app.service

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/** Checks approval for TuneMind's listener component using the platform API where available. */
object NotificationAccess {
    fun isEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val manager = context.getSystemService(NotificationManager::class.java)
            return manager.isNotificationListenerAccessGranted(
                ComponentName(context, TuneMindListenerService::class.java),
            )
        }
        return NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
}
