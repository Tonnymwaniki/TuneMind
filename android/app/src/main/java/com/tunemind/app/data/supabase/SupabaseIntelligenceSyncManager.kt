package com.tunemind.app.data.supabase

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tunemind.app.data.auth.TuneMindAccountStorage
import com.tunemind.app.data.auth.TuneMindAuthSessionManager
import com.tunemind.app.data.local.CrossAppTrackEntity
import com.tunemind.app.data.local.TuneMindDatabase
import com.tunemind.app.data.local.TuneMindPlaylistEntity
import com.tunemind.app.data.local.TuneMindPlaylistTrackEntity
import com.tunemind.app.domain.model.TrackIdentityResolver
import com.tunemind.app.domain.network.BackendEndpointManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Dual-Path Live Supabase Intelligence & Taste Genome Sync Engine for Android.
 *
 * Ensures that ALL user listening data, Spotify history syncs, voice turns, track approvals,
 * track rejections, hearing telemetry, and Cooldown Vault state are durably stored in the live
 * Supabase database scoped strictly per authenticated account.
 *
 * Sync Paths:
 * 1. Direct Supabase PostgREST API (`/rest/v1/agent_interventions` & `/rest/v1/listening_events`)
 *    when `SUPABASE_URL` and `SUPABASE_ANON_KEY` are configured on-device.
 * 2. Cloud Brain Backend (`POST /api/v1/intelligence/sync`, `/api/v1/track-cards/sync`,
 *    `GET /api/v1/track-cards/snapshot`) which writes to & restores from Supabase Postgres.
 */
object SupabaseIntelligenceSyncManager {
    private const val TAG = "SupabaseIntelSync"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val restoreMutex = Mutex()
    @Volatile
    private var lastRelationalSyncQueuedMs: Long = 0L
    @Volatile
    private var lastRestorePartitionKey: String = ""
    @Volatile
    private var lastRestoreCompletedMs: Long = 0L

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
        val accessToken: String,
        val isAuthenticatedAccount: Boolean
    )

    fun resolveUserIdentity(context: Context): ActiveUserIdentity {
        val sessionManager = TuneMindAuthSessionManager.getInstance(context)
        val session = sessionManager.sessionFlow.value
        val email = session.email.trim().lowercase()
        val accessToken = sessionManager.getAccessToken().trim()
        val cleanUid = session.userId.trim()
        val hasExplicitProvider = session.primaryProvider == "GOOGLE" || session.primaryProvider == "EMAIL"

        // Align 100% with backend authMiddleware:
        // When a Supabase JWT is present, Supabase Auth UUID (`sub` / `session.userId`) is authoritative.
        // Otherwise fall back to normalized email, then uid.
        val effectiveUserId = when {
            accessToken.isNotBlank() && cleanUid.isNotBlank() -> cleanUid
            email.isNotBlank() && email.contains("@") -> email
            cleanUid.isNotBlank() -> cleanUid
            else -> ""
        }
        return ActiveUserIdentity(
            userId = effectiveUserId,
            email = email,
            supabaseUrl = sessionManager.getSupabaseUrl(),
            supabaseAnonKey = sessionManager.getSupabaseAnonKey(),
            accessToken = accessToken,
            isAuthenticatedAccount = session.isLoggedIn && hasExplicitProvider && effectiveUserId.isNotBlank()
        )
    }

    private suspend fun resolveFreshUserIdentity(context: Context): ActiveUserIdentity {
        val sessionManager = TuneMindAuthSessionManager.getInstance(context)
        val freshToken = sessionManager.ensureFreshAccessToken()
        val base = resolveUserIdentity(context)
        return if (freshToken.isNotBlank() && freshToken != base.accessToken) {
            resolveUserIdentity(context).copy(accessToken = freshToken)
        } else {
            base
        }
    }

    private fun parseIsoToMs(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return runCatching { Instant.parse(raw.trim()).toEpochMilli() }.getOrDefault(0L)
    }

    /**
     * Asynchronously syncs a voice turn, song proposal, song approval (`APPROVED_TRACK`),
     * or song rejection (`REJECTED_TRACK`) to live Supabase for continuous intelligence growth.
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
                val identity = resolveFreshUserIdentity(appCtx)
                if (!identity.isAuthenticatedAccount) return@launch

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

                // 2. Sync to Cloud Brain `/api/v1/intelligence/sync`
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
                Log.d(TAG, "☁️ Synced agent decision ($action: $cleanTitle) for user=${identity.userId}")
            } catch (e: Exception) {
                Log.d(TAG, "Supabase decision sync note: ${e.message}")
            }
        }
    }

    /**
     * Restores the signed-in user's Track Cards, Playlists, and Vault state from the cloud
     * into their isolated local partition on sign-in or account switch so data follows their
     * account seamlessly across devices and switches without mixing with any other account.
     */
    suspend fun restoreUserAccountSnapshotFromCloud(
        context: Context,
        force: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        restoreMutex.withLock {
            try {
                val identity = resolveFreshUserIdentity(appCtx)
                if (!identity.isAuthenticatedAccount) return@withLock

                val partitionAtStart = TuneMindAccountStorage.resolveActivePartitionKey(appCtx)
                if (partitionAtStart == TuneMindAccountStorage.SIGNED_OUT_PARTITION) return@withLock

                val nowMs = System.currentTimeMillis()
                if (!force &&
                    lastRestorePartitionKey == partitionAtStart &&
                    (nowMs - lastRestoreCompletedMs) < 15_000L
                ) {
                    return@withLock
                }

                val baseUrl = BackendEndpointManager.getActiveHttpBaseUrl(appCtx).trimEnd('/')
                val snapshotUrl = "$baseUrl/api/v1/track-cards/snapshot" +
                    "?user_id=${Uri.encode(identity.userId)}" +
                    "&user_email=${Uri.encode(identity.email)}"

                val snapReq = Request.Builder()
                    .url(snapshotUrl)
                    .apply {
                        if (identity.accessToken.isNotBlank()) {
                            addHeader("Authorization", "Bearer ${identity.accessToken}")
                        }
                    }
                    .get()
                    .build()

                var restoredCards = 0
                var restoredPlaylists = 0

                httpClient.newCall(snapReq).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.d(TAG, "Snapshot restore returned HTTP ${resp.code}")
                        return@use
                    }
                    // Abort write if account changed while network request was in flight
                    if (TuneMindAccountStorage.resolveActivePartitionKey(appCtx) != partitionAtStart) {
                        return@withLock
                    }

                    val bodyStr = resp.body?.string().orEmpty()
                    val json = runCatching { JSONObject(bodyStr) }.getOrNull() ?: return@use

                    val db = TuneMindDatabase.getInstance(appCtx)
                    val crossAppDao = db.crossAppLibraryDao()
                    val playlistDao = db.tuneMindPlaylistDao()
                    val now = System.currentTimeMillis()

                    val cardsArr = json.optJSONArray("track_cards")
                    if (cardsArr != null && cardsArr.length() > 0) {
                        val toUpsert = mutableListOf<CrossAppTrackEntity>()
                        for (i in 0 until cardsArr.length()) {
                            val obj = cardsArr.optJSONObject(i) ?: continue
                            val title = obj.optString("title", "").trim()
                            val canonicalKey = obj.optString("canonical_track_key", "").trim().lowercase()
                            if (title.isBlank() || canonicalKey.isBlank()) continue

                            val existing = crossAppDao.getTrackByKey(canonicalKey)
                            val pkgs = linkedSetOf<String>()
                            existing?.sourcePackagesCsv
                                ?.split(",")
                                ?.map { it.trim() }
                                ?.filter { it.isNotBlank() }
                                ?.let { pkgs.addAll(it) }
                            val remotePkgs = obj.optJSONArray("source_packages")
                            if (remotePkgs != null) {
                                for (p in 0 until remotePkgs.length()) {
                                    val pkg = remotePkgs.optString(p, "").trim()
                                    if (pkg.isNotBlank()) pkgs.add(pkg)
                                }
                            }
                            val remotePrimaryPkg = obj.optString("primary_source_package", "")
                                .trim()
                                .ifBlank { existing?.primarySourcePackage ?: "unknown_player" }
                            if (remotePrimaryPkg.isNotBlank()) pkgs.add(remotePrimaryPkg)

                            val remotePlayCount = obj.optInt("verified_play_count", 0)
                            val remoteListenedMs = obj.optLong("total_listened_ms", 0L)
                            val remoteFirstMs = parseIsoToMs(obj.optString("first_played_at", ""))
                            val remoteLastMs = parseIsoToMs(obj.optString("last_played_at", ""))

                            val mergedFirstMs = when {
                                (existing?.firstPlayedAtMs ?: 0L) > 0L && remoteFirstMs > 0L ->
                                    minOf(existing!!.firstPlayedAtMs, remoteFirstMs)
                                (existing?.firstPlayedAtMs ?: 0L) > 0L -> existing!!.firstPlayedAtMs
                                else -> remoteFirstMs
                            }
                            val mergedLastMs = maxOf(existing?.lastPlayedAtMs ?: 0L, remoteLastMs)

                            val rawSpotifyUri = obj.optString("spotify_uri", "").takeIf { it.isNotBlank() && it != "null" }
                            val rawTrackId = obj.optString("track_id", "").trim()
                                .ifBlank { rawSpotifyUri ?: existing?.trackId ?: canonicalKey }

                            toUpsert.add(
                                CrossAppTrackEntity(
                                    canonicalTrackKey = canonicalKey,
                                    trackId = rawTrackId,
                                    title = title,
                                    artist = obj.optString("artist", existing?.artist ?: "").trim(),
                                    album = obj.optString("album", existing?.album ?: "").trim(),
                                    genre = obj.optString("genre", existing?.genre ?: "Music").ifBlank { "Music" },
                                    durationMs = maxOf(obj.optLong("duration_ms", 0L), existing?.durationMs ?: 0L),
                                    primarySourcePackage = remotePrimaryPkg,
                                    sourcePackagesCsv = pkgs.joinToString(","),
                                    playCount = maxOf(existing?.playCount ?: 0, remotePlayCount),
                                    totalListenedMs = maxOf(existing?.totalListenedMs ?: 0L, remoteListenedMs),
                                    firstPlayedAtMs = mergedFirstMs,
                                    lastPlayedAtMs = mergedLastMs,
                                    isLiked = (existing?.isLiked == true) || obj.optBoolean("is_liked", false),
                                    isInVault = (existing?.isInVault == true) || obj.optBoolean("is_in_vault", false),
                                    albumArtUrl = obj.optString("album_art_url", "")
                                        .takeIf { it.isNotBlank() && it != "null" } ?: existing?.albumArtUrl,
                                    previewUrl = obj.optString("preview_url", "")
                                        .takeIf { it.isNotBlank() && it != "null" } ?: existing?.previewUrl,
                                    audiusStreamUrl = existing?.audiusStreamUrl,
                                    ytmVideoId = obj.optString("ytm_video_id", "")
                                        .takeIf { it.isNotBlank() && it != "null" } ?: existing?.ytmVideoId,
                                    deezerUrl = existing?.deezerUrl,
                                    updatedAtMs = now
                                )
                            )
                        }
                        if (toUpsert.isNotEmpty()) {
                            crossAppDao.upsertLibraryTracks(toUpsert)
                            restoredCards = toUpsert.size
                        }
                    }

                    val playlistsArr = json.optJSONArray("playlists")
                    if (playlistsArr != null && playlistsArr.length() > 0) {
                        for (i in 0 until playlistsArr.length()) {
                            val pl = playlistsArr.optJSONObject(i) ?: continue
                            val pid = pl.optString("id", "").trim()
                            val name = pl.optString("name", "").trim()
                            if (pid.isBlank() || name.isBlank()) continue

                            val createdAtMs = parseIsoToMs(pl.optString("created_at", ""))
                                .takeIf { it > 0L }
                                ?: pl.optLong("created_at_ms", now).takeIf { it > 0L }
                                ?: now
                            val updatedAtMs = parseIsoToMs(pl.optString("updated_at", ""))
                                .takeIf { it > 0L }
                                ?: pl.optLong("updated_at_ms", now).takeIf { it > 0L }
                                ?: now

                            playlistDao.upsertPlaylist(
                                TuneMindPlaylistEntity(
                                    playlistId = pid,
                                    name = name,
                                    description = pl.optString("description", "").trim(),
                                    moodOrAudience = pl.optString("mood_or_audience", "").trim(),
                                    createdAtMs = createdAtMs,
                                    updatedAtMs = updatedAtMs
                                )
                            )
                            restoredPlaylists++

                            // Support both Supabase relational `user_playlist_items` and memory fallback `items`
                            val relItems = pl.optJSONArray("user_playlist_items") ?: pl.optJSONArray("items")
                            if (relItems != null && relItems.length() > 0) {
                                val trackEntities = mutableListOf<TuneMindPlaylistTrackEntity>()
                                for (idx in 0 until relItems.length()) {
                                    val itemObj = relItems.optJSONObject(idx) ?: continue
                                    val cardObj = itemObj.optJSONObject("track_card")
                                    val title = cardObj?.optString("title", "")?.trim()
                                        ?.ifBlank { itemObj.optString("title", "").trim() }
                                        ?: itemObj.optString("title", "").trim()
                                    if (title.isBlank()) continue

                                    val artist = cardObj?.optString("artist", "")?.trim()
                                        ?: itemObj.optString("artist", "").trim()
                                    val trackId = cardObj?.optString("track_id", "")?.trim()
                                        ?.ifBlank { itemObj.optString("track_id", "").trim() }
                                        ?: itemObj.optString("track_id", "").trim()
                                    val extUri = cardObj?.optString("spotify_uri", "")
                                        ?.takeIf { it.isNotBlank() && it != "null" }
                                        ?: itemObj.optString("external_uri", "")
                                            .takeIf { it.isNotBlank() && it != "null" }
                                        ?: trackId
                                    val entryId = itemObj.optString("entry_id", "${pid}::$idx").trim()
                                    val addedAt = parseIsoToMs(itemObj.optString("added_at", ""))
                                        .takeIf { it > 0L }
                                        ?: itemObj.optLong("added_at_ms", now).takeIf { it > 0L }
                                        ?: now

                                    trackEntities.add(
                                        TuneMindPlaylistTrackEntity(
                                            entryId = entryId,
                                            playlistId = pid,
                                            title = title,
                                            artist = artist,
                                            externalUri = extUri,
                                            trackId = trackId,
                                            sourcePlayer = itemObj.optString("source_player", "unknown_player")
                                                .ifBlank { "unknown_player" },
                                            positionIndex = itemObj.optInt("position_index", idx),
                                            addedAtMs = addedAt
                                        )
                                    )
                                }
                                if (trackEntities.isNotEmpty()) {
                                    playlistDao.upsertPlaylistTracks(trackEntities)
                                }
                            }
                        }
                    }
                }

                // Also restore cloud vault tracks if local vault prefs for this account are still empty
                val vaultPrefs = TuneMindAccountStorage.getUserPrefs(appCtx, "tunemind_vault_prefs")
                val localVaultRaw = vaultPrefs.getString("vault_items", null)
                if (localVaultRaw.isNullOrBlank()) {
                    if (TuneMindAccountStorage.resolveActivePartitionKey(appCtx) != partitionAtStart) {
                        return@withLock
                    }
                    val syncPayload = JSONObject().apply {
                        put("user_id", identity.userId)
                        put("user_email", identity.email)
                        put("supabase_url", identity.supabaseUrl)
                        put("supabase_anon_key", identity.supabaseAnonKey)
                        put("tracks", JSONArray())
                    }
                    val syncReq = Request.Builder()
                        .url("$baseUrl/api/v1/intelligence/sync")
                        .apply {
                            if (identity.accessToken.isNotBlank()) {
                                addHeader("Authorization", "Bearer ${identity.accessToken}")
                            }
                        }
                        .post(syncPayload.toString().toRequestBody(jsonMediaType))
                        .build()
                    runCatching {
                        httpClient.newCall(syncReq).execute().use { resp ->
                            if (!resp.isSuccessful) return@use
                            if (TuneMindAccountStorage.resolveActivePartitionKey(appCtx) != partitionAtStart) {
                                return@withLock
                            }
                            val json = JSONObject(resp.body?.string().orEmpty())
                            val vaultArr = json.optJSONArray("vault_tracks")
                            if (vaultArr != null && vaultArr.length() > 0 &&
                                vaultPrefs.getString("vault_items", null).isNullOrBlank()
                            ) {
                                vaultPrefs.edit().putString("vault_items", vaultArr.toString()).apply()
                            }
                        }
                    }
                }

                lastRestorePartitionKey = partitionAtStart
                lastRestoreCompletedMs = System.currentTimeMillis()
                Log.i(
                    TAG,
                    "☁️ Cloud snapshot restore finished for user=${identity.userId}: cards=$restoredCards, playlists=$restoredPlaylists"
                )
            } catch (e: Exception) {
                Log.d(TAG, "Cloud snapshot restore note: ${e.message}")
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
            val identity = resolveFreshUserIdentity(appCtx)
            if (!identity.isAuthenticatedAccount) return@withContext

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
            val identity = resolveFreshUserIdentity(appCtx)
            if (!identity.isAuthenticatedAccount) return@withContext
            val partitionAtStart = TuneMindAccountStorage.resolveActivePartitionKey(appCtx)

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
                if (TuneMindAccountStorage.resolveActivePartitionKey(appCtx) != partitionAtStart) {
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
                        if (serverVerifiedPlays > local.playCount || (local.playCount == 1 && serverVerifiedPlays == 0)) {
                            crossAppDao.updateVerifiedPlayCount(local.canonicalTrackKey, serverVerifiedPlays, now)
                            reconciledCount++
                        }
                    }
                }
                Log.i(
                    TAG,
                    "✅ Relational Track Cards synced for user=${identity.userId}: cards=${allTracks.size}, plays=${playLogs.size}, playlists=${allPlaylists.size}, reconciled=$reconciledCount"
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
