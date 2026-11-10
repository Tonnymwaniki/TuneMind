package com.tunemind.app.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object PermissionUtils {

    fun hasMicrophonePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasNotificationListenerPermission(context: Context): Boolean {
        val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(context)
        return enabledListeners.contains(context.packageName)
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun getNotificationListenerIntent(): Intent {
        return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }

    fun getBatteryOptimizationIntent(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }
}
