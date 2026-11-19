package com.tunemind.app.data.ingestion

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.tunemind.app.MainActivity
import com.tunemind.app.data.auth.TuneMindAuthSessionManager
import kotlinx.coroutines.launch

/**
 * Dedicated OAuth Callback Activity registered in `AndroidManifest.xml` to intercept
 * `tunemind://supabase-auth-callback` (Supabase Google OAuth redirect flow) and route
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
