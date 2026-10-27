package com.tunemind.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.tunemind.app.navigation.TuneMindAppNavigation
import com.tunemind.app.ui.AppHolder
import com.tunemind.app.ui.theme.TuneMindTheme

open class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppHolder.context = applicationContext
        enableEdgeToEdge()
        setContent {
            TuneMindTheme(darkTheme = true) {
                val navController = rememberNavController()
                TuneMindAppNavigation(navController = navController)
            }
        }
    }
}
