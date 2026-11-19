package com.tunemind.app.data.supabase

import android.content.Context
import android.util.Log
import com.tunemind.app.data.auth.TuneMindAuthSessionManager
import com.tunemind.app.data.local.TuneMindDatabase
import com.tunemind.app.domain.model.TrackIdentityResolver
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
        val supabaseAnonKey: String,
        val accessToken: String
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
            supabaseAnonKey = sessionManager.getSupabaseAnonKey(),
            accessToken = sessionManager.getAccessToken().trim()
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
        satietyPercent: Int = 24,
        trackId: String? = null
    ) {
        val appCtx = context.applicationContext
        scope.launch {
            try {
                val identity = resolveUserIdentity(appCtx)
                val cleanTitle = trackTitle?.trim() ?: ""
                val cleanArtist = trackArtist?.trim() ?: ""
                val canonical = if (cleanTitle.isNotEmpty() || !trackId.isNullOrBlank() || !spotifyUri.isNullOrBlank()) {
                    TrackIdentityResolver.resolveCanonicalIdentity(
                        trackId = trackId,
                        spotifyUri = spotifyUri,
                        title = cleanTitle,
                        artist = cleanArtist
                    )
                } else null
                val resolvedSpotifyUri = canonical?.spotifyUri ?: ""
                val targetQuery = listOfNotNull(
                    "$cleanTitle $cleanArtist".trim().takeIf { it.isNotEmpty() },
                    "action=$action",
                    resolvedSpotifyUri.takeIf { it.isNotBlank() }?.let { "uri=$it" }
                ).joinToString(" | ")

                // 1. Direct write to live Supabase `/rest/v1/agent_interventions` if configured on-device
                if (identity.supabaseUrl.startsWith("https://") && identity.supabaseAnonKey.isNotBlank()) {
                    postDirectToSupabaseInterventions(
                        supabaseUrl = identity.supabaseUrl,
                        anonKey = identity.supabaseAnonKey,
                        accessToken = identity.accessToken,
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
                        put("track_id", canonical?.trackId ?: "")
                        put("spotify_uri", resolvedSpotifyUri)
                    })
                }

                val req = Request.Builder()
                    .url("$baseUrl/api/v1/intelligence/sync")
                    .apply {
                        if (identity.accessToken.isNotBlank()) {
                            addHeader("Authorization", "Bearer ${identity.accessToken}")
                        }
                    }
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
     * Syncs the user's permanent Cross-App Library from local Room database (`cross_app_track_library`)
     * into live Supabase Taste Genome & `/api/v1/intelligence/sync`.
     */
    suspend fun syncCrossAppLibraryToSupabase(context: Context) = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        try {
            val identity = resolveUserIdentity(appCtx)
            val dao = TuneMindDatabase.getInstance(appCtx).crossAppLibraryDao()
            val aggregated = dao.getAllLibraryTracksOnce().sortedByDescending { it.lastPlayedAtMs }.take(80)
            if (aggregated.isEmpty()) return@withContext

            val tracksArr = JSONArray()
            for (item in aggregated) {
                val canonical = TrackIdentityResolver.resolveCanonicalIdentity(
                    trackId = item.trackId,
                    spotifyUri = null,
                    title = item.title,
                    artist = item.artist
                )
                tracksArr.put(JSONObject().apply {
                    put("title", item.title)
                    put("artist", item.artist)
                    put("trackId", canonical.trackId)
                    put("playCount", item.playCount.coerceAtLeast(0))
                    put("source", item.primarySourcePackage.ifBlank { "unknown_player" })
                    put("sourcePackages", item.sourcePackagesCsv)
                    put("album", item.album)
                    put("genre", item.genre)
                    put("totalListenedMs", item.totalListenedMs)
                    put("isLiked", item.isLiked)
                })
            }

            if (identity.supabaseUrl.startsWith("https://") && identity.supabaseAnonKey.isNotBlank()) {
                val topSummary = aggregated.take(10).joinToString(" | ") { t ->
                    "${t.title} by ${t.artist} (${t.playCount}x) [${t.primarySourcePackage}]"
                }
                postDirectToSupabaseInterventions(
                    supabaseUrl = identity.supabaseUrl,
                    anonKey = identity.supabaseAnonKey,
                    accessToken = identity.accessToken,
                    userId = identity.userId,
                    triggerTranscript = "CROSS_APP_LIBRARY_TELEMETRY_SYNC",
                    satietyPercent = 22,
                    agentTtsResponse = "Synced ${aggregated.size} cross-app library tracks from Android device.",
                    osIntent = "TELEMETRY_SYNC",
                    targetSearchQuery = topSummary.take(900)
                )
            }

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
                .apply {
                    if (identity.accessToken.isNotBlank()) {
                        addHeader("Authorization", "Bearer ${identity.accessToken}")
                    }
                }
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(req).execute().close()
            Log.i(TAG, "✅ Synced ${aggregated.size} cross-app library tracks to live Supabase for user=${identity.userId}")
        } catch (e: Exception) {
            Log.d(TAG, "Cross-app -> Supabase sync note: ${e.message}")
        }
    }

    private fun postDirectToSupabaseInterventions(
        supabaseUrl: String,
        anonKey: String,
        accessToken: String,
        userId: String,
        triggerTranscript: String,
        satietyPercent: Int,
        agentTtsResponse: String,
        osIntent: String,
        targetSearchQuery: String
    ) {
        try {
            val bearer = accessToken.ifBlank { anonKey }
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
                .addHeader("Authorization", "Bearer $bearer")
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
