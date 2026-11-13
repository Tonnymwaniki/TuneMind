package com.tunemind.app.data.ingestion

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.tunemind.app.MainActivity
import kotlinx.coroutines.launch

/**
 * Dedicated OAuth Callback Activity registered in `AndroidManifest.xml` to intercept
 * the `tunemind://spotify-auth-callback` deep link from the Chrome Custom Tab.
 *
 * Immediately exchanges the intercepted `code` for an Access Token and Refresh Token via
 * `https://accounts.spotify.com/api/token`, schedules the 15-minute `SpotifyRecentlyPlayedSyncWorker`,
 * triggers an immediate first sync into `TuneMindDatabase`, and returns the user to `MainActivity`.
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
        if (uri == null || uri.scheme != "tunemind" ||
            (uri.host != "spotify-auth-callback" && uri.host != "spotify-callback")
        ) {
            navigateBackToMain()
            return
        }

        val error = uri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            Log.e(TAG, "Spotify OAuth authorization failed or cancelled: $error")
            navigateBackToMain()
            return
        }

        val code = uri.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            Log.w(TAG, "No authorization code found in callback URI: $uri")
            navigateBackToMain()
            return
        }

        val authManager = SpotifyAuthManager(applicationContext)
        lifecycleScope.launch {
            val exchanged = authManager.exchangeCodeForTokens(code)
            if (exchanged) {
                Log.i(TAG, "Spotify OAuth connected! Scheduling 15-min worker and running immediate sync.")
                SpotifyRecentlyPlayedSyncWorker.schedulePeriodic15MinSync(applicationContext)
                SpotifyRecentlyPlayedSyncWorker.enqueueImmediateOnAudioFocusChange(
                    context = applicationContext,
                    reason = "SPOTIFY_OAUTH_CALLBACK_SUCCESS",
                    bypassDebounce = true
                )
            }
            navigateBackToMain()
        }
    }

    private fun navigateBackToMain() {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(mainIntent)
        finish()
    }

    companion object {
        private const val TAG = "AuthCallbackActivity"
    }
}
