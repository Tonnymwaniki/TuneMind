package com.tunemind.app

import android.content.ComponentName
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

        // Rebind the listener service if previously unbound by app reinstall
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (NotificationAccess.isEnabled(this)) {
                try {
                    NotificationListenerService.requestRebind(
                        ComponentName(this, TuneMindListenerService::class.java)
                    )
                    Log.i("MainActivity", "Requested rebind of TuneMindListenerService")
                } catch (e: Exception) {
                    Log.w("MainActivity", "requestRebind error: ${e.message}")
                }
            }
        }

        enableEdgeToEdge()
        setContent {
            TuneMindTheme(darkTheme = true) {
                val navController = rememberNavController()
                TuneMindAppNavigation(navController = navController)
            }
        }
    }
}
