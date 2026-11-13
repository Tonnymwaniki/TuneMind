package com.tunemind.app.provider

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tunemind.app.data.ingestion.SpotifyAuthManager
import com.tunemind.app.data.local.TuneMindDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Resolves any `(title, artist, query)` into a canonical `spotify:track:<22-char-id>` URI
 * using the Spotify Web API (`GET /v1/search?type=track`) and triggers direct Spotify Web API
 * remote playback (`PUT /v1/me/player/play`) so Spotify never stalls on a static search screen.
 */
object SpotifyTrackUriResolver {
    private const val TAG = "SpotifyUriResolver"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uriCache = ConcurrentHashMap<String, String>()

    @Volatile
    private var cachedClientCredentialsToken: String? = null
    @Volatile
    private var clientCredentialsExpiresAtMs: Long = 0L

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Resolves a canonical `spotify:track:<id>` URI within a fast 1.6s budget:
     * 1. Checks in-memory cache and local Room `spotify_listening_history` table.
     * 2. Queries `GET https://api.spotify.com/v1/search?type=track&limit=5&q=...` using either
     *    the user's OAuth token or a Spotify Client Credentials token (`CLIENT_ID:CLIENT_SECRET`).
     */
    fun resolveTrackUriBlocking(
        context: Context,
        cleanTitle: String,
        cleanArtist: String,
        fullQuery: String
    ): String? {
        val cacheKey = "${cleanTitle.lowercase().trim()}|${cleanArtist.lowercase().trim()}|${fullQuery.lowercase().trim()}"
        uriCache[cacheKey]?.let { return it }

        return runBlocking {
            withTimeoutOrNull(1650L) {
                withContext(Dispatchers.IO) {
                    // 1. Check local Room Spotify history cache first (< 3ms)
                    try {
                        val dao = TuneMindDatabase.getInstance(context).spotifyListeningHistoryDao()
                        val localTracks = dao.getAggregatedTracksSnapshot(60)
                        val matchedLocal = localTracks.firstOrNull { item ->
                            item.spotifyUri.startsWith("spotify:track:") && (
                                item.trackTitle.equals(cleanTitle, ignoreCase = true) ||
                                    fullQuery.contains(item.trackTitle, ignoreCase = true)
                                )
                        }
                        if (matchedLocal != null) {
                            uriCache[cacheKey] = matchedLocal.spotifyUri
                            return@withContext matchedLocal.spotifyUri
                        }
                    } catch (_: Exception) {}

                    // 2. Query Spotify Web API /v1/search?type=track
                    val token = getSearchBearerToken(context) ?: return@withContext null
                    val searchQuery = when {
                        cleanTitle.isNotBlank() && cleanArtist.isNotBlank() ->
                            "track:$cleanTitle artist:$cleanArtist"
                        fullQuery.isNotBlank() -> fullQuery
                        else -> cleanTitle
                    }

                    val firstAttemptUri = querySpotifyTrackSearch(token, searchQuery, cleanTitle, cleanArtist)
                        ?: if (fullQuery.isNotBlank() && fullQuery != searchQuery) {
                            querySpotifyTrackSearch(token, fullQuery, cleanTitle, cleanArtist)
                        } else null

                    if (!firstAttemptUri.isNullOrBlank()) {
                        uriCache[cacheKey] = firstAttemptUri
                    }
                    firstAttemptUri
                }
            }
        }
    }

    private fun querySpotifyTrackSearch(
        bearerToken: String,
        query: String,
        expectedTitle: String,
        expectedArtist: String
    ): String? {
        return try {
            val url = Uri.parse("https://api.spotify.com/v1/search").buildUpon()
                .appendQueryParameter("q", query)
                .appendQueryParameter("type", "track")
                .appendQueryParameter("limit", "5")
                .build()
                .toString()

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $bearerToken")
                .addHeader("Accept", "application/json")
                .get()
                .build()

            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val raw = resp.body?.string() ?: return null
                val items = JSONObject(raw).optJSONObject("tracks")?.optJSONArray("items") ?: return null
                if (items.length() == 0) return null

                // Pick the best matching track by artist/title or default to the top result
                var bestUri: String? = null
                for (i in 0 until items.length()) {
                    val trackObj = items.optJSONObject(i) ?: continue
                    val uri = trackObj.optString("uri", "")
                    if (!uri.startsWith("spotify:track:")) continue
                    if (bestUri == null) bestUri = uri

                    val name = trackObj.optString("name", "")
                    val artistsArr = trackObj.optJSONArray("artists")
                    val firstArtist = artistsArr?.optJSONObject(0)?.optString("name", "") ?: ""

                    val titleMatches = expectedTitle.isBlank() ||
                        name.contains(expectedTitle, ignoreCase = true) ||
                        expectedTitle.contains(name, ignoreCase = true)
                    val artistMatches = expectedArtist.isBlank() ||
                        firstArtist.contains(expectedArtist, ignoreCase = true) ||
                        expectedArtist.contains(firstArtist, ignoreCase = true)

                    if (titleMatches && artistMatches) {
                        Log.i(TAG, "Resolved exact Spotify track URI: $uri ('$name' by '$firstArtist')")
                        return uri
                    }
                }
                Log.i(TAG, "Resolved top Spotify track URI: $bestUri for query='$query'")
                bestUri
            }
        } catch (e: Exception) {
            Log.d(TAG, "Spotify /v1/search notice: ${e.message}")
            null
        }
    }

    /**
     * Obtains a valid Bearer token for `/v1/search`:
     * Uses the user's OAuth token if available, otherwise fetches a lightweight
     * `client_credentials` token using `SpotifyAuthManager.CLIENT_ID` & `CLIENT_SECRET`.
     */
    private suspend fun getSearchBearerToken(context: Context): String? {
        val authManager = SpotifyAuthManager(context)
        if (authManager.isAuthenticated()) {
            val userToken = authManager.getValidAccessToken()
            if (!userToken.isNullOrBlank()) return userToken
        }

        val now = System.currentTimeMillis()
        val cached = cachedClientCredentialsToken
        if (!cached.isNullOrBlank() && now + 30_000L < clientCredentialsExpiresAtMs) {
            return cached
        }

        return try {
            val body = "grant_type=client_credentials"
                .toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val req = Request.Builder()
                .url("https://accounts.spotify.com/api/token")
                .addHeader("Authorization", SpotifyAuthManager.buildBasicAuthHeader())
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .post(body)
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string() ?: "{}")
                val token = json.optString("access_token", "")
                val expiresIn = json.optLong("expires_in", 3600L)
                if (token.isNotBlank()) {
                    cachedClientCredentialsToken = token
                    clientCredentialsExpiresAtMs = now + (expiresIn * 1000L)
                    token
                } else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client credentials token notice: ${e.message}")
            null
        }
    }

    /**
     * Fires `PUT https://api.spotify.com/v1/me/player/play` with `{"uris": ["spotify:track:<id>"]}`
     * across multiple waves (`200ms`, `1100ms`, `2300ms`) so as soon as Spotify opens or wakes its
     * playback session on the phone, the Spotify Web API forces the track to start streaming.
     */
    fun triggerWebApiRemotePlayAsync(context: Context, spotifyTrackUri: String) {
        if (!spotifyTrackUri.startsWith("spotify:track:")) return
        val appContext = context.applicationContext

        scope.launch {
            val authManager = SpotifyAuthManager(appContext)
            if (!authManager.isAuthenticated()) return@launch
            val token = authManager.getValidAccessToken() ?: return@launch

            for (waveDelay in listOf(200L, 1100L, 2300L)) {
                delay(waveDelay)
                try {
                    val listener = com.tunemind.app.service.TuneMindListenerService.instance
                    if (waveDelay > 500L && listener?.isPlaying() == true) {
                        // Already streaming!
                        break
                    }

                    val deviceId = findActiveOrSmartphoneDeviceId(token)
                    val playUrl = if (!deviceId.isNullOrBlank()) {
                        "https://api.spotify.com/v1/me/player/play?device_id=${Uri.encode(deviceId)}"
                    } else {
                        "https://api.spotify.com/v1/me/player/play"
                    }

                    val payload = JSONObject().apply {
                        put("uris", JSONArray().put(spotifyTrackUri))
                        put("position_ms", 0)
                    }

                    val req = Request.Builder()
                        .url(playUrl)
                        .addHeader("Authorization", "Bearer $token")
                        .addHeader("Content-Type", "application/json")
                        .put(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()

                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful || resp.code == 204) {
                            Log.i(TAG, "Spotify Web API PUT /v1/me/player/play succeeded for $spotifyTrackUri")
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Web API play wave notice: ${e.message}")
                }
            }
        }
    }

    private fun findActiveOrSmartphoneDeviceId(bearerToken: String): String? {
        return try {
            val req = Request.Builder()
                .url("https://api.spotify.com/v1/me/player/devices")
                .addHeader("Authorization", "Bearer $bearerToken")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val devices = JSONObject(resp.body?.string() ?: "{}").optJSONArray("devices") ?: return null
                var smartphoneId: String? = null
                var firstDeviceId: String? = null
                for (i in 0 until devices.length()) {
                    val d = devices.optJSONObject(i) ?: continue
                    val id = d.optString("id", "")
                    if (id.isBlank()) continue
                    if (firstDeviceId == null) firstDeviceId = id
                    if (d.optBoolean("is_active", false)) return id
                    if (d.optString("type", "").equals("Smartphone", ignoreCase = true)) {
                        smartphoneId = id
                    }
                }
                smartphoneId ?: firstDeviceId
            }
        } catch (_: Exception) {
            null
        }
    }
}
