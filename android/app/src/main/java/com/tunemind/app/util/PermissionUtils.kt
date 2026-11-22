package com.tunemind.app.util

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.tunemind.app.MainActivity
import com.tunemind.app.service.NotificationAccess
import com.tunemind.app.service.TuneMindListenerService

object PermissionUtils {

    fun findActivity(context: Context): Activity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    fun hasMicrophonePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasNotificationListenerPermission(context: Context): Boolean {
        return NotificationAccess.isEnabled(context)
    }

    fun hasPostNotificationsPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun hasOverlayPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }

    fun hasAudioStoragePermission(context: Context): Boolean {
        val perm = audioStoragePermissionName()
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    fun audioStoragePermissionName(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    /**
     * Returns true when a runtime permission prompt was just denied and Android will no longer
     * show an in-app dialog ("Don't ask again" / permanently denied), so the caller should send
     * the user to Android App Info instead of leaving a dead button.
     */
    fun isRuntimePermissionPermanentlyDeniedAfterResult(
        context: Context,
        permission: String,
        granted: Boolean
    ): Boolean {
        if (granted) return false
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val activity = findActivity(context) ?: return false
        return !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }

    fun getAppDetailsSettingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun getAppNotificationSettingsIntent(context: Context): Intent {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notifIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (notifIntent.resolveActivity(pm) != null) return notifIntent
        }
        return getAppDetailsSettingsIntent(context)
    }

    fun getOverlayPermissionIntent(context: Context): Intent {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val overlayIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (overlayIntent.resolveActivity(pm) != null) return overlayIntent
        }
        return getAppDetailsSettingsIntent(context)
    }

    fun getNotificationListenerIntent(context: Context? = null): Intent {
        if (context != null) {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val componentName = ComponentName(context, TuneMindListenerService::class.java)
                val detailIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                    putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        componentName.flattenToString()
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (detailIntent.resolveActivity(pm) != null) return detailIntent
            }
            val listIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (listIntent.resolveActivity(pm) != null) return listIntent
            return getAppDetailsSettingsIntent(context)
        }
        return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }

    /**
     * Play-Store-compliant battery optimization settings intent.
     * Avoids ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS because REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
     * is intentionally not declared in AndroidManifest.xml.
     */
    fun getBatteryOptimizationIntent(context: Context): Intent {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val settingsIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (settingsIntent.resolveActivity(pm) != null) return settingsIntent
        }
        return getAppDetailsSettingsIntent(context)
    }

    private fun startFirstResolvableActivity(context: Context, vararg intents: Intent): Boolean {
        for (intent in intents) {
            val prepared = Intent(intent).apply {
                if (findActivity(context) == null) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            val launched = runCatching {
                context.startActivity(prepared)
                true
            }.getOrDefault(false)
            if (launched) return true
        }
        return false
    }

    fun openNotificationListenerSettings(context: Context): Boolean {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                        putExtra(
                            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            ComponentName(context, TuneMindListenerService::class.java).flattenToString()
                        )
                    }
                )
            }
            add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            add(getAppNotificationSettingsIntent(context))
            add(getAppDetailsSettingsIntent(context))
        }.toTypedArray()
        return startFirstResolvableActivity(context, *candidates)
    }

    fun openBatteryOptimizationSettings(context: Context): Boolean {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            add(getAppDetailsSettingsIntent(context))
            add(Intent(Settings.ACTION_SETTINGS))
        }.toTypedArray()
        return startFirstResolvableActivity(context, *candidates)
    }

    fun openOverlayPermissionSettings(context: Context): Boolean {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                add(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }
            add(getAppDetailsSettingsIntent(context))
        }.toTypedArray()
        return startFirstResolvableActivity(context, *candidates)
    }

    fun openAppNotificationSettings(context: Context): Boolean {
        return startFirstResolvableActivity(
            context,
            getAppNotificationSettingsIntent(context),
            getAppDetailsSettingsIntent(context)
        )
    }

    fun openAppDetailsSettings(context: Context): Boolean {
        return startFirstResolvableActivity(
            context,
            getAppDetailsSettingsIntent(context),
            Intent(Settings.ACTION_SETTINGS)
        )
    }

    /**
     * Ensures NotificationListenerService binds immediately after the user toggles access ON
     * and refreshes active MediaSessions so capture begins without restarting TuneMind.
     */
    fun kickstartMediaObservation(context: Context) {
        if (!hasNotificationListenerPermission(context)) return
        runCatching { MainActivity.forceRebindListener(context) }
        runCatching {
            val observer = TuneMindListenerService.instance?.observerOrNull()
            if (observer != null) {
                if (!observer.isStarted) {
                    observer.start()
                } else {
                    observer.refreshSessions(force = true)
                }
            }
        }
    }
}
