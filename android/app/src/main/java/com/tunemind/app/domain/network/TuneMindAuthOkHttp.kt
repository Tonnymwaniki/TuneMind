package com.tunemind.app.domain.network

import android.content.Context
import com.tunemind.app.BuildConfig
import com.tunemind.app.data.auth.TuneMindAuthSessionManager
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.util.concurrent.TimeUnit

/**
 * Shared OkHttp client factory enforcing short-lived Bearer JWT authentication
 * and single-flight silent token refresh via OkHttp Authenticator on 401 responses.
 */
object TuneMindAuthOkHttp {

    private val refreshLock = Any()

    @Volatile
    private var sharedClient: OkHttpClient? = null

    fun getAuthenticatedClient(context: Context): OkHttpClient {
        sharedClient?.let { return it }
        return synchronized(this) {
            sharedClient ?: buildClient(context.applicationContext).also { sharedClient = it }
        }
    }

    private fun buildClient(appContext: Context): OkHttpClient {
        val authInterceptor = Interceptor { chain ->
            val original = chain.request()
            val token = TuneMindAuthSessionManager
                .getInstance(appContext)
                .getAccessToken()
                .trim()

            val builder = original.newBuilder()
                .header("X-App-Version", BuildConfig.VERSION_NAME)

            if (token.isNotBlank() && original.header("Authorization").isNullOrBlank()) {
                builder.header("Authorization", "Bearer $token")
            }
            chain.proceed(builder.build())
        }

        val tokenAuthenticator = Authenticator { _: Route?, response: Response ->
            if (responseCount(response) >= 2) {
                return@Authenticator null
            }

            val sentAuth = response.request.header("Authorization").orEmpty()
            val sentToken = sentAuth.removePrefix("Bearer ").trim()

            synchronized(refreshLock) {
                val mgr = TuneMindAuthSessionManager.getInstance(appContext)
                val latestInMemory = mgr.getAccessToken().trim()
                val freshToken = if (latestInMemory.isNotBlank() && latestInMemory != sentToken) {
                    latestInMemory
                } else {
                    runBlocking { mgr.ensureFreshAccessToken() }.trim()
                }

                if (freshToken.isBlank() || freshToken == sentToken) {
                    return@Authenticator null
                }

                response.request.newBuilder()
                    .header("Authorization", "Bearer $freshToken")
                    .build()
            }
        }

        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .authenticator(tokenAuthenticator)
            .build()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
