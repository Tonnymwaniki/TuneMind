package com.tunemind.app.data.ingestion

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.tunemind.app.MainActivity
import com.tunemind.app.data.auth.TuneMindAuthSessionManager
import com.tunemind.app.provider.SpotifyPremiumConnectManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated OAuth Callback Activity registered in `AndroidManifest.xml` to intercept
 * `tunemind://supabase-auth-callback` (Supabase Google OAuth redirect flow) and
 * `tunemind://spotify-callback` (Spotify Premium Direct Connect PKCE flow) and route
 * the user back into [MainActivity].
 */
class AuthCallbackActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingCallbackIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingCallbackIntent(intent)
    }

    private fun handleIncomingCallbackIntent(callbackIntent: Intent?) {
        val uri: Uri? = callbackIntent?.data
        if (uri == null || uri.scheme != "tunemind") {
            navigateBackToMain()
            return
        }

        if (uri.host == "supabase-auth-callback") {
            val sessionManager = TuneMindAuthSessionManager.getInstance(applicationContext)
            lifecycleScope.launch {
                sessionManager.handleSupabaseRedirectUri(uri)
                navigateBackToMain()
            }
            return
        }

        if (uri.host == "spotify-callback") {
            lifecycleScope.launch {
                val connected = withContext(Dispatchers.IO) {
                    SpotifyPremiumConnectManager.handleCallbackUri(applicationContext, uri)
                }
                if (connected) {
                    val appCtx = applicationContext
                    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                        runCatching {
                            SpotifyLibrarySyncEngine.syncAll(appCtx, forceRefresh = true)
                        }
                    }
                }
                navigateBackToMain()
            }
            return
        }

        navigateBackToMain()
    }

    private fun navigateBackToMain() {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(mainIntent)
        finish()
    }
}
