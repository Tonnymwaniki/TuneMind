package com.tunemind.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.tunemind.app.navigation.TuneMindAppNavigation
import com.tunemind.app.service.NotificationAccess
import com.tunemind.app.service.TuneMindListenerService
import com.tunemind.app.ui.AppHolder
import com.tunemind.app.ui.theme.TuneMindTheme

open class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppHolder.context = applicationContext

        // Force Android system to bind or rebind to TuneMindListenerService
        forceRebindListener(this)

        enableEdgeToEdge()
        setContent {
            TuneMindTheme(darkTheme = true) {
                val navController = rememberNavController()
                TuneMindAppNavigation(navController = navController)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check binding on resume when user comes back from Settings
        forceRebindListener(this)
    }

    companion object {
        fun forceRebindListener(context: Context) {
            if (!NotificationAccess.isEnabled(context)) return

            try {
                // Toggling the component enabled state triggers Android's NotificationManager to bind immediately
                val pm = context.packageManager
                val component = ComponentName(context, TuneMindListenerService::class.java)
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            } catch (e: Exception) {
                Log.w("MainActivity", "Component toggle warning: ${e.message}")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    NotificationListenerService.requestRebind(
                        ComponentName(context, TuneMindListenerService::class.java)
                    )
                } catch (e: Exception) {
                    Log.w("MainActivity", "requestRebind warning: ${e.message}")
                }
            }
        }
    }
}
