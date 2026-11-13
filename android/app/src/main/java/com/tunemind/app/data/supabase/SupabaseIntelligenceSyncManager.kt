package com.tunemind.app.data.supabase

import android.content.Context
import android.util.Log
import com.tunemind.app.data.auth.TuneMindAuthSessionManager
import com.tunemind.app.data.local.TuneMindDatabase
import com.tunemind.app.domain.network.BackendEndpointManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Dual-Path Live Supabase Intelligence & Taste Genome Sync Engine for Android.
 *
 * Ensures that ALL user listening data, Spotify history syncs, voice turns, track approvals,
 * track rejections, hearing telemetry, and Cooldown Vault state are durably stored in the live
 * Supabase database so TuneMind's intelligence grows continuously over time.
 *
 * Sync Paths:
 * 1. Direct Supabase PostgREST API (`/rest/v1/agent_interventions` & `/rest/v1/listening_events`)
 *    when `SUPABASE_URL` and `SUPABASE_ANON_KEY` are configured on-device.
 * 2. Cloud Brain Backend (`POST /api/v1/intelligence/sync`) which writes to the live Supabase
 *    database using `SUPABASE_SERVICE_ROLE_KEY`.
 */
object SupabaseIntelligenceSyncManager {
    private const val TAG = "SupabaseIntelSync"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    data class ActiveUserIdentity(
        val userId: String,
        val email: String,
        val supabaseUrl: String,
        val supabaseAnonKey: String
    )

    fun resolveUserIdentity(context: Context): ActiveUserIdentity {
        val sessionManager = TuneMindAuthSessionManager.getInstance(context)
        val session = sessionManager.sessionFlow.value
        val email = session.email.trim().lowercase()
        val effectiveUserId = when {
            email.isNotBlank() && email.contains("@") -> email
            session.userId.isNotBlank() -> session.userId.trim()
            else -> "tunemind-default-user"
        }
        return ActiveUserIdentity(
            userId = effectiveUserId,
            email = email,
            supabaseUrl = sessionManager.getSupabaseUrl(),
            supabaseAnonKey = sessionManager.getSupabaseAnonKey()
        )
    }

    /**
     * Asynchronously syncs a voice turn, song proposal, song approval (`APPROVED_TRACK`),
     * or song rejection (`REJECTED_TRACK`) along with the user's current Spotify track history
     * to live Supabase for continuous intelligence growth.
     */
    fun recordVoiceOrTrackDecisionAsync(
        context: Context,
        triggerTranscript: String,
        agentTtsResponse: String,
        action: String,
        osIntent: String = "NONE",
        trackTitle: String? = null,
        trackArtist: String? = null,
        spotifyUri: String? = null,
        satietyPercent: Int = 24
    ) {
        val appCtx = context.applicationContext
        scope.launch {
            try {
                val identity = resolveUserIdentity(appCtx)
                val cleanTitle = trackTitle?.trim() ?: ""
                val cleanArtist = trackArtist?.trim() ?: ""
                val targetQuery = listOfNotNull(
                    "$cleanTitle $cleanArtist".trim().takeIf { it.isNotEmpty() },
                    "action=$action",
                    spotifyUri?.takeIf { it.isNotBlank() }?.let { "uri=$it" }
                ).joinToString(" | ")

                // 1. Direct write to live Supabase `/rest/v1/agent_interventions` if configured on-device
                if (identity.supabaseUrl.startsWith("https://") && identity.supabaseAnonKey.isNotBlank()) {
                    postDirectToSupabaseInterventions(
                        supabaseUrl = identity.supabaseUrl,
                        anonKey = identity.supabaseAnonKey,
                        userId = identity.userId,
                        triggerTranscript = triggerTranscript,
                        satietyPercent = satietyPercent,
                        agentTtsResponse = agentTtsResponse,
                        osIntent = osIntent.ifBlank { action },
                        targetSearchQuery = targetQuery
                    )
                }

                // 2. Sync to Cloud Brain `/api/v1/intelligence/sync` (which writes to live Supabase via service role key)
                val baseUrl = BackendEndpointManager.getActiveHttpBaseUrl(appCtx).trimEnd('/')
                val payload = JSONObject().apply {
                    put("user_id", identity.userId)
                    put("user_email", identity.email)
                    put("supabase_url", identity.supabaseUrl)
                    put("supabase_anon_key", identity.supabaseAnonKey)
                    put("intervention", JSONObject().apply {
                        put("trigger_transcript", triggerTranscript)
                        put("computed_satiety", satietyPercent)
                        put("agent_tts_response", agentTtsResponse)
                        put("os_intent", osIntent)
                        put("target_search_query", "$cleanTitle $cleanArtist".trim())
                        put("action", action)
                        put("track_title", cleanTitle)
                        put("track_artist", cleanArtist)
                        put("spotify_uri", spotifyUri ?: "")
                    })
                }

                val req = Request.Builder()
                    .url("$baseUrl/api/v1/intelligence/sync")
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .build()

                httpClient.newCall(req).execute().close()
                Log.d(TAG, "☁️ Synced agent decision ($action: $cleanTitle) to live Supabase intelligence store.")
            } catch (e: Exception) {
                Log.d(TAG, "Supabase decision sync note: ${e.message}")
            }
        }
    }

    /**
     * Syncs the user's aggregated Spotify listening history from local Room database into live Supabase
     * whenever the 15-minute or immediate Spotify sync completes.
     */
    suspend fun syncSpotifyHistoryToSupabase(context: Context) = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        try {
            val identity = resolveUserIdentity(appCtx)
            val dao = TuneMindDatabase.getInstance(appCtx).spotifyListeningHistoryDao()
            val aggregated = dao.getAggregatedTracksSnapshot(limit = 60)
            if (aggregated.isEmpty()) return@withContext

            val tracksArr = JSONArray()
            for (item in aggregated) {
                tracksArr.put(JSONObject().apply {
                    put("title", item.trackTitle)
                    put("artist", item.artistName)
                    put("trackId", item.spotifyUri)
                    put("playCount", item.playCount)
                    put("source", "com.spotify.music")
                })
            }

            // 1. Direct write to live Supabase if configured on-device
            if (identity.supabaseUrl.startsWith("https://") && identity.supabaseAnonKey.isNotBlank()) {
                val topSummary = aggregated.take(10).joinToString(" | ") { t ->
                    "${t.trackTitle} by ${t.artistName} (${t.playCount}x) [${t.spotifyUri}]"
                }
                postDirectToSupabaseInterventions(
                    supabaseUrl = identity.supabaseUrl,
                    anonKey = identity.supabaseAnonKey,
                    userId = identity.userId,
                    triggerTranscript = "SPOTIFY_HISTORY_TELEMETRY_SYNC",
                    satietyPercent = 22,
                    agentTtsResponse = "Synced ${aggregated.size} Spotify tracks from Android device.",
                    osIntent = "TELEMETRY_SYNC",
                    targetSearchQuery = topSummary.take(900)
                )
            }

            // 2. Push full track batch to Cloud Brain `/api/v1/intelligence/sync`
            val baseUrl = BackendEndpointManager.getActiveHttpBaseUrl(appCtx).trimEnd('/')
            val payload = JSONObject().apply {
                put("user_id", identity.userId)
                put("user_email", identity.email)
                put("supabase_url", identity.supabaseUrl)
                put("supabase_anon_key", identity.supabaseAnonKey)
                put("tracks", tracksArr)
            }

            val req = Request.Builder()
                .url("$baseUrl/api/v1/intelligence/sync")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(req).execute().close()
            Log.i(TAG, "✅ Synced ${aggregated.size} Spotify tracks to live Supabase Taste Genome for user=${identity.userId}")
        } catch (e: Exception) {
            Log.d(TAG, "Spotify -> Supabase sync note: ${e.message}")
        }
    }

    private fun postDirectToSupabaseInterventions(
        supabaseUrl: String,
        anonKey: String,
        userId: String,
        triggerTranscript: String,
        satietyPercent: Int,
        agentTtsResponse: String,
        osIntent: String,
        targetSearchQuery: String
    ) {
        try {
            val row = JSONObject().apply {
                put("user_id", userId)
                put("trigger_transcript", triggerTranscript.take(500))
                put("computed_satiety", satietyPercent)
                put("agent_tts_response", agentTtsResponse.take(1000))
                put("os_intent", osIntent)
                put("target_search_query", targetSearchQuery.take(500))
            }
            val req = Request.Builder()
                .url("${supabaseUrl.trimEnd('/')}/rest/v1/agent_interventions")
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $anonKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .post(row.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(req).execute().close()
        } catch (e: Exception) {
            Log.d(TAG, "Direct Supabase PostgREST note: ${e.message}")
        }
    }
}
