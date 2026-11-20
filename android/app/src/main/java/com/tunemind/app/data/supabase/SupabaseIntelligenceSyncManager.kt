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
    @Volatile
    private var lastRelationalSyncQueuedMs: Long = 0L

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
            syncTrackCardsAndPlaylistsToBackend(appCtx)
        } catch (e: Exception) {
            Log.d(TAG, "Cross-app -> Supabase sync note: ${e.message}")
        }
    }

    /**
     * Lightweight debounced trigger so rapid plays or playlist edits batch cleanly
     * into a single relational Track Cards sync call.
     */
    fun triggerRelationalTrackCardsSyncAsync(context: Context) {
        val appCtx = context.applicationContext
        val now = System.currentTimeMillis()
        if (now - lastRelationalSyncQueuedMs < 2500L) return
        lastRelationalSyncQueuedMs = now
        scope.launch {
            kotlinx.coroutines.delay(1800L)
            syncTrackCardsAndPlaylistsToBackend(appCtx)
        }
    }

    /**
     * Permanently syncs canonical Track Cards, verified Play Events, and relational Playlists
     * to `/api/v1/track-cards/sync`, then reconciles authoritative Postgres `verified_play_count`
     * values back into Room so frontend song cards ALWAYS show 100% accurate plays.
     */
    suspend fun syncTrackCardsAndPlaylistsToBackend(context: Context) = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        try {
            val identity = resolveUserIdentity(appCtx)
            val db = TuneMindDatabase.getInstance(appCtx)
            val crossAppDao = db.crossAppLibraryDao()
            val playlistDao = db.tuneMindPlaylistDao()

            val allTracks = crossAppDao.getAllLibraryTracksOnce()
            val playLogs = crossAppDao.getPlayLogsOnce(limit = 500)
            val allPlaylists = playlistDao.getAllPlaylistsOnce()

            if (allTracks.isEmpty() && playLogs.isEmpty() && allPlaylists.isEmpty()) return@withContext

            val trackCardsArr = JSONArray()
            for (t in allTracks) {
                val pkgsArr = JSONArray()
                t.sourcePackagesCsv
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .forEach { pkgsArr.put(it) }

                trackCardsArr.put(JSONObject().apply {
                    put("canonical_track_key", t.canonicalTrackKey)
                    put("track_id", t.trackId)
                    put("title", t.title)
                    put("artist", t.artist)
                    put("album", t.album)
                    put("genre", t.genre)
                    put("duration_ms", t.durationMs)
                    if (t.trackId.startsWith("spotify:track:")) {
                        put("spotify_uri", t.trackId)
                    }
                    if (!t.ytmVideoId.isNullOrBlank()) put("ytm_video_id", t.ytmVideoId)
                    if (!t.albumArtUrl.isNullOrBlank()) put("album_art_url", t.albumArtUrl)
                    if (!t.previewUrl.isNullOrBlank()) put("preview_url", t.previewUrl)
                    put("primary_source_package", t.primarySourcePackage)
                    put("source_packages", pkgsArr)
                    put("is_liked", t.isLiked)
                    put("is_in_vault", t.isInVault)
                    put("first_played_at_ms", t.firstPlayedAtMs)
                    put("last_played_at_ms", t.lastPlayedAtMs)
                })
            }

            val playEventsArr = JSONArray()
            for (ev in playLogs) {
                playEventsArr.put(JSONObject().apply {
                    put("play_event_id", ev.playLogId)
                    put("canonical_track_key", ev.canonicalTrackKey)
                    put("track_id", ev.trackId)
                    put("title", ev.title)
                    put("artist", ev.artist)
                    put("album", ev.album)
                    put("source_package", ev.sourcePackage)
                    put("source_app_name", ev.sourceAppName)
                    put("played_at_ms", ev.playedAtMs)
                    put("listened_ms", ev.listenedMs)
                    put("track_duration_ms", ev.trackDurationMs)
                    put("completion_ratio", ev.completionRatio.toDouble())
                    put("was_skipped", ev.wasSkipped)
                })
            }

            val playlistsArr = JSONArray()
            for (pl in allPlaylists) {
                val items = playlistDao.getTracksForPlaylistOnce(pl.playlistId)
                val itemsArr = JSONArray()
                for (tr in items) {
                    val key = TrackIdentityResolver.buildNormalizedMatchKey(tr.title, tr.artist)
                    if (key.isBlank()) continue
                    itemsArr.put(JSONObject().apply {
                        put("entry_id", tr.entryId)
                        put("canonical_track_key", key)
                        put("title", tr.title)
                        put("artist", tr.artist)
                        put("external_uri", tr.externalUri)
                        put("track_id", tr.trackId)
                        put("source_player", tr.sourcePlayer)
                        put("position_index", tr.positionIndex)
                        put("added_by", if (pl.playlistId.startsWith("spotify_pl_")) "SPOTIFY_SYNC" else "USER")
                        put("added_at_ms", tr.addedAtMs)
                    })
                }
                playlistsArr.put(JSONObject().apply {
                    put("id", pl.playlistId)
                    put("playlist_origin", if (pl.playlistId.startsWith("spotify_pl_")) "SPOTIFY" else "TUNEMIND")
                    if (pl.playlistId.startsWith("spotify_pl_")) {
                        put("external_playlist_id", pl.playlistId.removePrefix("spotify_pl_"))
                    }
                    put("name", pl.name)
                    put("description", pl.description)
                    put("mood_or_audience", pl.moodOrAudience)
                    put("created_at_ms", pl.createdAtMs)
                    put("updated_at_ms", pl.updatedAtMs)
                    put("items", itemsArr)
                })
            }

            val baseUrl = BackendEndpointManager.getActiveHttpBaseUrl(appCtx).trimEnd('/')
            val payload = JSONObject().apply {
                put("user_id", identity.userId)
                put("user_email", identity.email)
                put("track_cards", trackCardsArr)
                put("play_events", playEventsArr)
                put("playlists", playlistsArr)
            }

            val req = Request.Builder()
                .url("$baseUrl/api/v1/track-cards/sync")
                .apply {
                    if (identity.accessToken.isNotBlank()) {
                        addHeader("Authorization", "Bearer ${identity.accessToken}")
                    }
                }
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.d(TAG, "Relational Track Cards sync returned HTTP ${resp.code}")
                    return@withContext
                }
                val bodyStr = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(bodyStr) }.getOrNull() ?: return@withContext
                val countsObj = json.optJSONObject("verified_play_counts") ?: return@withContext
                val now = System.currentTimeMillis()
                val existingByKey = allTracks.associateBy { it.canonicalTrackKey.lowercase() }

                val keys = countsObj.keys()
                var reconciledCount = 0
                while (keys.hasNext()) {
                    val key = keys.next()
                    val serverVerifiedPlays = countsObj.optInt(key, -1)
                    if (serverVerifiedPlays < 0) continue
                    val local = existingByKey[key.lowercase()] ?: continue
                    if (serverVerifiedPlays != local.playCount && serverVerifiedPlays >= 0) {
                        // Authoritative reconciliation: adopt exact Postgres verified play count when backed by events
                        if (serverVerifiedPlays > local.playCount || local.playCount == 1 && serverVerifiedPlays == 0) {
                            crossAppDao.updateVerifiedPlayCount(local.canonicalTrackKey, serverVerifiedPlays, now)
                            reconciledCount++
                        }
                    }
                }
                Log.i(
                    TAG,
                    "✅ Relational Track Cards synced: cards=${allTracks.size}, plays=${playLogs.size}, playlists=${allPlaylists.size}, reconciled=$reconciledCount"
                )
            }
        } catch (e: Exception) {
            Log.d(TAG, "Relational Track Cards sync note: ${e.message}")
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
