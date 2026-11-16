package com.tunemind.app.data.ingestion

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tunemind.app.data.auth.TuneMindAuthSessionManager
import com.tunemind.app.data.local.TuneMindDatabase
import com.tunemind.app.domain.memory.ListeningPlayDeduplicator
import com.tunemind.app.domain.network.BackendEndpointManager
import com.tunemind.app.provider.SpotifyTrackUriResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Central Spotify visual & taste-intelligence repository (Zero Claude AI Tokens).
 *
 * Responsibilities:
 * 1. Resolves & Room-caches Track Album Art + Artist Profile Photos for every track/artist
 *    across all TuneMind screens (via Room tables + Spotify `/v1/tracks`, `/v1/artists`, `/v1/search`).
 * 2. Syncs the authenticated user's Spotify profile (`/v1/me`) & multi-timeframe top artists
 *    (`/v1/me/top/artists` across short/medium/long terms) to power free real Genre DNA & Taste Shifts.
 * 3. Fetches new releases from the user's followed artists (`/v1/me/following` + `/v1/artists/{id}/albums`).
 * 4. Executes one-tap Spotify actions: Add to Queue, Save to Liked Songs, Export Playlist, and Device Switcher.
 */
class SpotifyVisualArtworkRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val db = TuneMindDatabase.getInstance(appContext)
    private val visualDao = db.spotifyVisualDao()
    private val historyDao = db.spotifyListeningHistoryDao()
    private val libraryDao = db.spotifyLibraryDao()
    private val authManager = SpotifyAuthManager(appContext)
    private val tokenManager = SpotifyTokenManager(appContext)
    private val authService = SpotifyApiFactory.accountsAuthService
    private val directApi = SpotifyApiFactory.directWebApiService
    private val userApi = SpotifyApiFactory.createWebApiService(tokenManager)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val universalHttp = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val trackCache = ConcurrentHashMap<String, ResolvedTrackVisuals>()
    private val artistCache = ConcurrentHashMap<String, ArtistProfileCacheEntity>()
    private val pendingTrackRequests = ConcurrentHashMap<String, PendingTrackLookup>()
    private val pendingArtistRequests = ConcurrentHashMap<String, String>()
    private val attemptedKeys = ConcurrentHashMap<String, Long>()
    private val batchMutex = Mutex()
    private val intelMutex = Mutex()

    @Volatile
    private var batchScheduled = false

    private val _trackVisualsFlow = MutableStateFlow<Map<String, ResolvedTrackVisuals>>(emptyMap())
    val trackVisualsFlow: StateFlow<Map<String, ResolvedTrackVisuals>> = _trackVisualsFlow.asStateFlow()

    private val _artistProfilesFlow = MutableStateFlow<Map<String, ArtistProfileCacheEntity>>(emptyMap())
    val artistProfilesFlow: StateFlow<Map<String, ArtistProfileCacheEntity>> = _artistProfilesFlow.asStateFlow()

    private val _userProfileFlow = MutableStateFlow<SpotifyUserProfileSnapshot?>(null)
    val userProfileFlow: StateFlow<SpotifyUserProfileSnapshot?> = _userProfileFlow.asStateFlow()

    private val _tasteShiftSummaryFlow = MutableStateFlow(SpotifyTasteShiftSummary())
    val tasteShiftSummaryFlow: StateFlow<SpotifyTasteShiftSummary> = _tasteShiftSummaryFlow.asStateFlow()

    private val _newReleasesFlow = MutableStateFlow<List<SpotifyNewReleaseItem>>(emptyList())
    val newReleasesFlow: StateFlow<List<SpotifyNewReleaseItem>> = _newReleasesFlow.asStateFlow()

    private val _playbackDevicesFlow = MutableStateFlow<List<SpotifyDeviceItem>>(emptyList())
    val playbackDevicesFlow: StateFlow<List<SpotifyDeviceItem>> = _playbackDevicesFlow.asStateFlow()

    private val _isSyncingIntelligenceFlow = MutableStateFlow(false)
    val isSyncingIntelligenceFlow: StateFlow<Boolean> = _isSyncingIntelligenceFlow.asStateFlow()

    private data class PendingTrackLookup(
        val trackKey: String,
        val title: String,
        val artist: String,
        val spotifyUri: String?,
        val fallbackAlbumArtUrl: String?
    )

    init {
        loadPersistedIntelligenceSnapshot()
        scope.launch {
            seedFromLocalRoomTables()
        }
        scope.launch {
            runCatching {
                visualDao.observeAllTrackVisuals().collect { list ->
                    list.forEach { entity ->
                        val resolved = entity.toResolved()
                        trackCache[entity.trackKey] = resolved
                        if (!entity.spotifyUri.isNullOrBlank()) {
                            trackCache[uriKey(entity.spotifyUri)] = resolved
                        }
                    }
                    _trackVisualsFlow.value = trackCache.toMap()
                }
            }
        }
        scope.launch {
            runCatching {
                visualDao.observeAllArtistProfiles().collect { list ->
                    list.forEach { entity ->
                        artistCache[entity.artistKey] = entity
                        if (!entity.artistId.isNullOrBlank()) {
                            artistCache["id:${entity.artistId}"] = entity
                        }
                    }
                    _artistProfilesFlow.value = artistCache.toMap()
                }
            }
        }
    }

    fun getCachedTrackVisuals(
        title: String?,
        artist: String?,
        spotifyUri: String? = null
    ): ResolvedTrackVisuals? {
        val cleanUri = spotifyUri?.trim()?.takeIf { it.isNotBlank() }
        if (cleanUri != null) {
            trackCache[uriKey(cleanUri)]?.let { direct ->
                val filledArtist = direct.artistImageUrl ?: lookupCachedArtistPhoto(artist)
                return if (filledArtist != direct.artistImageUrl) direct.copy(artistImageUrl = filledArtist) else direct
            }
        }
        val tKey = canonicalTrackKey(title, artist)
        if (tKey.isBlank()) return null
        val cached = trackCache[tKey]
        if (cached != null) {
            val filledArtist = cached.artistImageUrl ?: lookupCachedArtistPhoto(artist)
            return if (filledArtist != cached.artistImageUrl) cached.copy(artistImageUrl = filledArtist) else cached
        }
        val artistPhoto = lookupCachedArtistPhoto(artist)
        return if (!artistPhoto.isNullOrBlank()) {
            ResolvedTrackVisuals(trackKey = tKey, artistImageUrl = artistPhoto)
        } else null
    }

    fun lookupCachedArtistPhoto(artistName: String?): String? {
        val primary = extractPrimaryArtistName(artistName)
        if (primary.isBlank()) return null
        return artistCache[canonicalArtistKey(primary)]?.imageUrl
    }

    fun requestTrackVisuals(
        title: String?,
        artist: String?,
        spotifyUri: String? = null,
        fallbackAlbumArtUrl: String? = null
    ) {
        val cleanTitle = title?.trim().orEmpty()
        val cleanArtist = artist?.trim().orEmpty()
        if (cleanTitle.isBlank() && spotifyUri.isNullOrBlank()) return

        val tKey = canonicalTrackKey(cleanTitle, cleanArtist).ifBlank { uriKey(spotifyUri.orEmpty()) }
        val existing = getCachedTrackVisuals(cleanTitle, cleanArtist, spotifyUri)
        if (existing != null && !existing.albumArtUrl.isNullOrBlank() && !existing.artistImageUrl.isNullOrBlank()) {
            return
        }

        // Fast fallback seed if caller already gave us an albumArtUrl
        if (!fallbackAlbumArtUrl.isNullOrBlank() && existing?.albumArtUrl.isNullOrBlank()) {
            val seeded = (existing ?: ResolvedTrackVisuals(trackKey = tKey)).copy(
                spotifyUri = spotifyUri ?: existing?.spotifyUri,
                albumArtUrl = fallbackAlbumArtUrl
            )
            trackCache[tKey] = seeded
            if (!spotifyUri.isNullOrBlank()) {
                trackCache[uriKey(spotifyUri)] = seeded
            }
            _trackVisualsFlow.value = trackCache.toMap()
        }

        val now = System.currentTimeMillis()
        val lastTried = attemptedKeys[tKey] ?: 0L
        if (now - lastTried < RETRY_COOLDOWN_MS) return

        pendingTrackRequests[tKey] = PendingTrackLookup(
            trackKey = tKey,
            title = cleanTitle,
            artist = cleanArtist,
            spotifyUri = spotifyUri,
            fallbackAlbumArtUrl = fallbackAlbumArtUrl
        )
        scheduleBatchFlush()
    }

    fun requestArtistProfile(artistName: String?, artistId: String? = null) {
        val primary = extractPrimaryArtistName(artistName)
        if (primary.isBlank() && artistId.isNullOrBlank()) return
        val aKey = canonicalArtistKey(primary).ifBlank { "id:$artistId" }
        val existing = artistCache[aKey] ?: artistId?.let { artistCache["id:$it"] }
        if (existing != null && !existing.imageUrl.isNullOrBlank()) return

        val now = System.currentTimeMillis()
        val lastTried = attemptedKeys["art:$aKey"] ?: 0L
        if (now - lastTried < RETRY_COOLDOWN_MS) return

        if (primary.isNotBlank()) {
            pendingArtistRequests[aKey] = primary
            scheduleBatchFlush()
        }
    }

    private fun scheduleBatchFlush() {
        if (batchScheduled) return
        batchScheduled = true
        scope.launch {
            delay(220L)
            batchScheduled = false
            flushPendingVisualLookups()
        }
    }

    private suspend fun flushPendingVisualLookups() = batchMutex.withLock {
        val trackBatch = pendingTrackRequests.entries.take(24).map { entry ->
            pendingTrackRequests.remove(entry.key)
            entry.value
        }
        val artistBatch = pendingArtistRequests.entries.take(20).map { entry ->
            pendingArtistRequests.remove(entry.key)
            entry.key to entry.value
        }
        if (trackBatch.isEmpty() && artistBatch.isEmpty()) return

        val now = System.currentTimeMillis()
        val trackEntities = mutableListOf<TrackVisualCacheEntity>()
        val newArtistEntities = mutableListOf<ArtistProfileCacheEntity>()
        val unresolvedTrackRequests = mutableListOf<PendingTrackLookup>()

        // 0. Universal Backend Profile Engine (/api/v1/music/resolve-batch) — works for ALL music players (0 AI tokens, zero Spotify login required)
        if (trackBatch.isNotEmpty()) {
            val resolvedKeys = resolveTrackBatchViaBackend(trackBatch, now, trackEntities, newArtistEntities)
            trackBatch.forEach { req ->
                if (req.trackKey in resolvedKeys) {
                    attemptedKeys[req.trackKey] = now
                } else {
                    unresolvedTrackRequests.add(req)
                }
            }
        }

        val bearer = if (unresolvedTrackRequests.isNotEmpty() || artistBatch.isNotEmpty()) {
            SpotifyTrackUriResolver.getSearchBearerToken(appContext)
        } else null
        val authHeader = bearer?.let { "Bearer $it" }

        val artistIdsToFetch = linkedSetOf<String>()
        val trackEntitiesNeedingArtistPhoto = mutableListOf<TrackVisualCacheEntity>()

        // 1. Batch resolve tracks that already have a spotify:track:<id> URI via /v1/tracks?ids=...
        val withIds = unresolvedTrackRequests.mapNotNull { item ->
            val trackId = extractSpotifyTrackId(item.spotifyUri)
            if (!trackId.isNullOrBlank()) item to trackId else null
        }
        val withoutIds = unresolvedTrackRequests.filter { extractSpotifyTrackId(it.spotifyUri).isNullOrBlank() }

        if (authHeader != null) {
            withIds.chunked(40).forEach { chunk ->
                val idsCsv = chunk.joinToString(",") { it.second }
                runCatching {
                    val resp = directApi.getTracksByIds(idsCsv = idsCsv, authHeader = authHeader)
                    if (resp.isSuccessful) {
                        val tracksById = resp.body()?.tracks.orEmpty().filterNotNull().associateBy { it.id.orEmpty() }
                        chunk.forEach { (req, id) ->
                            attemptedKeys[req.trackKey] = now
                            val dto = tracksById[id]
                            if (dto != null) {
                                val primaryArtist = dto.artists.firstOrNull()
                                val primaryArtistName = primaryArtist?.name?.takeIf { it.isNotBlank() } ?: req.artist
                                val aId = primaryArtist?.id
                                val artUrl = dto.album?.images?.firstOrNull()?.url ?: req.fallbackAlbumArtUrl
                                val existingArtistImg = lookupCachedArtistPhoto(primaryArtistName)
                                    ?: aId?.let { artistCache["id:$it"]?.imageUrl }
                                if (existingArtistImg.isNullOrBlank() && !aId.isNullOrBlank()) {
                                    artistIdsToFetch.add(aId)
                                }
                                val entity = TrackVisualCacheEntity(
                                    trackKey = req.trackKey,
                                    spotifyTrackId = dto.id,
                                    spotifyUri = dto.uri.ifBlank { req.spotifyUri },
                                    title = dto.name.ifBlank { req.title },
                                    artist = primaryArtistName,
                                    artistId = aId,
                                    albumName = dto.album?.name,
                                    albumArtUrl = artUrl,
                                    artistImageUrl = existingArtistImg,
                                    genresCsv = aId?.let { artistCache["id:$it"]?.genresCsv }.orEmpty(),
                                    popularity = dto.popularity,
                                    updatedAtMs = now
                                )
                                trackEntities.add(entity)
                                if (existingArtistImg.isNullOrBlank()) {
                                    trackEntitiesNeedingArtistPhoto.add(entity)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Resolve tracks without URI via sanitized Spotify /v1/search + Deezer public fallback
        withoutIds.take(10).forEach { req ->
            attemptedKeys[req.trackKey] = now
            val (cleanedTitle, cleanedArtist) = sanitizeNonSpotifyTrackMetadata(req.title, req.artist)
            var matched = false
            if (authHeader != null && cleanedTitle.isNotBlank()) {
                val q = if (cleanedArtist.isNotBlank()) "track:$cleanedTitle artist:$cleanedArtist" else cleanedTitle
                runCatching {
                    val resp = directApi.searchCatalog(query = q, type = "track,artist", limit = 2, authHeader = authHeader)
                    if (resp.isSuccessful) {
                        val trackDto = resp.body()?.tracks?.items?.firstOrNull()
                        val artistDto = resp.body()?.artists?.items?.firstOrNull()
                        if (trackDto != null) {
                            matched = true
                            val matchedArtist = trackDto.artists.firstOrNull() ?: artistDto
                            val aId = matchedArtist?.id ?: artistDto?.id
                            val artUrl = trackDto.album?.images?.firstOrNull()?.url ?: req.fallbackAlbumArtUrl
                            val artistImg = artistDto?.images?.firstOrNull()?.url
                                ?: lookupCachedArtistPhoto(cleanedArtist)
                                ?: aId?.let { artistCache["id:$it"]?.imageUrl }
                            if (artistImg.isNullOrBlank() && !aId.isNullOrBlank()) {
                                artistIdsToFetch.add(aId)
                            }
                            if (artistDto != null && !artistDto.images.isNullOrEmpty()) {
                                saveArtistDto(artistDto, now)?.let { newArtistEntities.add(it) }
                            }
                            val entity = TrackVisualCacheEntity(
                                trackKey = req.trackKey,
                                spotifyTrackId = trackDto.id,
                                spotifyUri = trackDto.uri ?: req.spotifyUri,
                                title = trackDto.name.takeIf { it.isNotBlank() } ?: req.title,
                                artist = matchedArtist?.name?.takeIf { it.isNotBlank() } ?: req.artist,
                                artistId = aId,
                                albumName = trackDto.album?.name,
                                albumArtUrl = artUrl,
                                artistImageUrl = artistImg,
                                genresCsv = artistDto?.genres.orEmpty().joinToString(","),
                                popularity = trackDto.popularity,
                                updatedAtMs = now
                            )
                            trackEntities.add(entity)
                            if (artistImg.isNullOrBlank()) {
                                trackEntitiesNeedingArtistPhoto.add(entity)
                            }
                        }
                    }
                }
            }
            if (!matched && cleanedTitle.isNotBlank()) {
                fetchDeezerPublicFallback(req, cleanedTitle, cleanedArtist, now)?.let { dzEntity ->
                    trackEntities.add(dzEntity)
                    if (!dzEntity.artistImageUrl.isNullOrBlank()) {
                        val aName = dzEntity.artist.ifBlank { cleanedArtist }
                        if (aName.isNotBlank()) {
                            val aKey = canonicalArtistKey(aName)
                            val artEntity = ArtistProfileCacheEntity(
                                artistKey = aKey,
                                artistId = null,
                                artistName = aName,
                                imageUrl = dzEntity.artistImageUrl,
                                genresCsv = "",
                                popularity = 0,
                                followersTotal = 0L,
                                updatedAtMs = now
                            )
                            artistCache[aKey] = artEntity
                            newArtistEntities.add(artEntity)
                        }
                    }
                }
            }
        }

        // 3. Batch fetch missing artist photos + genres via /v1/artists?ids=...
        if (authHeader != null && artistIdsToFetch.isNotEmpty()) {
            artistIdsToFetch.chunked(45).forEach { idChunk ->
                runCatching {
                    val resp = directApi.getArtistsByIds(idsCsv = idChunk.joinToString(","), authHeader = authHeader)
                    if (resp.isSuccessful) {
                        resp.body()?.artists.orEmpty().filterNotNull().forEach { dto ->
                            saveArtistDto(dto, now)?.let { newArtistEntities.add(it) }
                        }
                    }
                }
            }
        }

        // 4. Standalone artist searches for Stats / Top Artists rows
        if (authHeader != null) {
            artistBatch.take(8).forEach { (aKey, name) ->
                attemptedKeys["art:$aKey"] = now
                if (artistCache[aKey]?.imageUrl != null) return@forEach
                runCatching {
                    val resp = directApi.searchCatalog(query = name, type = "artist", limit = 1, authHeader = authHeader)
                    if (resp.isSuccessful) {
                        resp.body()?.artists?.items?.firstOrNull()?.let { dto ->
                            saveArtistDto(dto, now)?.let { newArtistEntities.add(it) }
                        }
                    }
                }
            }
        }

        // 5. Backfill artistImageUrl + genres into resolved track entities and persist to Room
        val finalizedTracks = trackEntities.map { t ->
            val cachedArtist = t.artistId?.let { artistCache["id:$it"] }
                ?: artistCache[canonicalArtistKey(extractPrimaryArtistName(t.artist))]
            t.copy(
                artistImageUrl = t.artistImageUrl ?: cachedArtist?.imageUrl,
                genresCsv = t.genresCsv.ifBlank { cachedArtist?.genresCsv.orEmpty() }
            )
        }

        if (newArtistEntities.isNotEmpty()) {
            runCatching { visualDao.upsertArtistProfiles(newArtistEntities) }
        }
        if (finalizedTracks.isNotEmpty()) {
            runCatching { visualDao.upsertTrackVisuals(finalizedTracks) }
            finalizedTracks.forEach { entity ->
                val resolved = entity.toResolved()
                trackCache[entity.trackKey] = resolved
                if (!entity.spotifyUri.isNullOrBlank()) {
                    trackCache[uriKey(entity.spotifyUri)] = resolved
                }
            }
            _trackVisualsFlow.value = trackCache.toMap()
        }
        _artistProfilesFlow.value = artistCache.toMap()

        if (pendingTrackRequests.isNotEmpty() || pendingArtistRequests.isNotEmpty()) {
            scheduleBatchFlush()
        }
    }

    private fun saveArtistDto(dto: SpotifyArtistDto, now: Long): ArtistProfileCacheEntity? {
        val name = dto.name.trim()
        if (name.isBlank()) return null
        val aKey = canonicalArtistKey(name)
        val imageUrl = dto.images?.firstOrNull()?.url
        val genresCsv = dto.genres.orEmpty().joinToString(",")
        val entity = ArtistProfileCacheEntity(
            artistKey = aKey,
            artistId = dto.id,
            artistName = name,
            imageUrl = imageUrl,
            genresCsv = genresCsv,
            popularity = dto.popularity ?: 0,
            followersTotal = dto.followers?.total ?: 0L,
            updatedAtMs = now
        )
        artistCache[aKey] = entity
        if (!dto.id.isNullOrBlank()) {
            artistCache["id:${dto.id}"] = entity
        }
        return entity
    }

    private fun resolveTrackBatchViaBackend(
        batch: List<PendingTrackLookup>,
        now: Long,
        outTracks: MutableList<TrackVisualCacheEntity>,
        outArtists: MutableList<ArtistProfileCacheEntity>
    ): Set<String> {
        val resolvedKeys = mutableSetOf<String>()
        val baseUrl = BackendEndpointManager.getActiveHttpBaseUrl(appContext).trimEnd('/')
        if (baseUrl.isBlank()) return resolvedKeys

        return runCatching {
            val arr = JSONArray()
            val byKey = LinkedHashMap<String, PendingTrackLookup>()
            batch.take(20).forEach { req ->
                byKey[req.trackKey] = req
                arr.put(JSONObject().apply {
                    put("title", req.title)
                    put("artist", req.artist)
                    if (!req.spotifyUri.isNullOrBlank()) {
                        put("spotify_uri", req.spotifyUri)
                    }
                })
            }
            val payload = JSONObject().put("tracks", arr).toString()
            val request = Request.Builder()
                .url("$baseUrl/api/v1/music/resolve-batch")
                .addHeader("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            universalHttp.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use resolvedKeys
                val root = JSONObject(resp.body?.string() ?: "{}")
                val profiles = root.optJSONArray("profiles") ?: return@use resolvedKeys
                for (i in 0 until profiles.length()) {
                    val item = profiles.optJSONObject(i) ?: continue
                    val qTitle = item.optString("query_title")
                    val qArtist = item.optString("query_artist")
                    val req = byKey[canonicalTrackKey(qTitle, qArtist)] ?: batch.getOrNull(i) ?: continue
                    val prof = item.optJSONObject("profile") ?: continue

                    val albumArtUrl = prof.optString("albumArtUrl").takeIf { it.startsWith("http") }
                        ?: req.fallbackAlbumArtUrl
                    val artistImg = prof.optString("artistImageUrl").takeIf { it.startsWith("http") }
                    val cleanTitle = prof.optString("cleanTitle").takeIf { it.isNotBlank() } ?: req.title
                    val cleanArtist = prof.optString("cleanArtist").takeIf { it.isNotBlank() } ?: req.artist
                    val artistId = prof.optString("artistId").takeIf { it.isNotBlank() }
                    val genresArr = prof.optJSONArray("genres")
                    val genresList = mutableListOf<String>()
                    if (genresArr != null) {
                        for (g in 0 until genresArr.length()) {
                            val gv = genresArr.optString(g).trim()
                            if (gv.isNotBlank()) genresList.add(gv)
                        }
                    }
                    val genresCsv = genresList.joinToString(",")

                    if (cleanArtist.isNotBlank() && (!artistImg.isNullOrBlank() || genresCsv.isNotBlank())) {
                        val aKey = canonicalArtistKey(cleanArtist)
                        val artistEntity = ArtistProfileCacheEntity(
                            artistKey = aKey,
                            artistId = artistId,
                            artistName = cleanArtist,
                            imageUrl = artistImg ?: artistCache[aKey]?.imageUrl,
                            genresCsv = genresCsv.ifBlank { artistCache[aKey]?.genresCsv.orEmpty() },
                            popularity = prof.optInt("popularity", 0),
                            followersTotal = 0L,
                            updatedAtMs = now
                        )
                        artistCache[aKey] = artistEntity
                        if (!artistId.isNullOrBlank()) artistCache["id:$artistId"] = artistEntity
                        outArtists.add(artistEntity)
                    }

                    val trackEntity = TrackVisualCacheEntity(
                        trackKey = req.trackKey,
                        spotifyTrackId = prof.optString("spotifyTrackId").takeIf { it.isNotBlank() },
                        spotifyUri = prof.optString("spotifyUri").takeIf { it.isNotBlank() } ?: req.spotifyUri,
                        title = cleanTitle,
                        artist = cleanArtist,
                        artistId = artistId,
                        albumName = prof.optString("albumName").takeIf { it.isNotBlank() },
                        albumArtUrl = albumArtUrl,
                        artistImageUrl = artistImg,
                        genresCsv = genresCsv,
                        popularity = prof.optInt("popularity", 0),
                        updatedAtMs = now
                    )
                    outTracks.add(trackEntity)
                    resolvedKeys.add(req.trackKey)
                }
                resolvedKeys
            }
        }.getOrElse { resolvedKeys }
    }

    private fun fetchDeezerPublicFallback(
        req: PendingTrackLookup,
        cleanedTitle: String,
        cleanedArtist: String,
        now: Long
    ): TrackVisualCacheEntity? {
        return runCatching {
            val query = if (cleanedArtist.isNotBlank()) "$cleanedTitle $cleanedArtist" else cleanedTitle
            val url = Uri.parse("https://api.deezer.com/search").buildUpon()
                .appendQueryParameter("q", query)
                .appendQueryParameter("limit", "2")
                .build()
                .toString()
            val request = Request.Builder().url(url).get().build()
            universalHttp.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val data = JSONObject(resp.body?.string() ?: "{}").optJSONArray("data") ?: return@use null
                val item = data.optJSONObject(0) ?: return@use null
                val alb = item.optJSONObject("album")
                val art = item.optJSONObject("artist")
                val albumArt = alb?.optString("cover_big")?.takeIf { it.startsWith("http") }
                    ?: alb?.optString("cover_medium")?.takeIf { it.startsWith("http") }
                    ?: req.fallbackAlbumArtUrl
                val artistImg = art?.optString("picture_big")?.takeIf { it.startsWith("http") }
                    ?: art?.optString("picture_medium")?.takeIf { it.startsWith("http") }
                TrackVisualCacheEntity(
                    trackKey = req.trackKey,
                    spotifyTrackId = null,
                    spotifyUri = req.spotifyUri,
                    title = item.optString("title").takeIf { it.isNotBlank() } ?: req.title,
                    artist = art?.optString("name")?.takeIf { it.isNotBlank() } ?: req.artist,
                    artistId = null,
                    albumName = alb?.optString("title")?.takeIf { it.isNotBlank() },
                    albumArtUrl = albumArt,
                    artistImageUrl = artistImg,
                    genresCsv = "",
                    popularity = 0,
                    updatedAtMs = now
                )
            }
        }.getOrNull()
    }

    /**
     * For users listening via ANY music app (YouTube Music, Apple Music, Boomplay, Audiomack,
     * local MP3 players) without Spotify OAuth, synthesizes real Genre DNA & Top Artist Profiles
     * from all resolved TrackVisualCache / ArtistProfileCache items.
     */
    fun refreshUniversalTasteIntelligenceFromResolvedTracks(recentTracks: List<Pair<String, String>>) {
        val current = _tasteShiftSummaryFlow.value
        if (current.shortTermTopArtists.isNotEmpty()) return // Spotify OAuth personal taste active

        val genreCounts = linkedMapOf<String, Int>()
        val artistAgg = linkedMapOf<String, Pair<Int, ArtistProfileCacheEntity>>()

        recentTracks.forEach { (title, artist) ->
            val resolved = getCachedTrackVisuals(title, artist) ?: return@forEach
            resolved.genres.forEach { g ->
                val pretty = formatGenreTitle(g)
                if (pretty.isNotBlank()) {
                    genreCounts[pretty] = (genreCounts[pretty] ?: 0) + 1
                }
            }
            val primary = extractPrimaryArtistName(artist)
            if (primary.isNotBlank()) {
                val aKey = canonicalArtistKey(primary)
                val cachedArtist = artistCache[aKey]
                if (cachedArtist != null) {
                    cachedArtist.genresCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { g ->
                        val pretty = formatGenreTitle(g)
                        genreCounts[pretty] = (genreCounts[pretty] ?: 0) + 1
                    }
                    val prevCount = artistAgg[aKey]?.first ?: 0
                    artistAgg[aKey] = (prevCount + 1) to cachedArtist
                }
            }
        }

        if (genreCounts.isEmpty() && artistAgg.isEmpty()) return

        val totalWeight = genreCounts.values.sum().coerceAtLeast(1)
        val topGenres = genreCounts.entries
            .sortedByDescending { it.value }
            .take(8)
            .map { (g, c) ->
                SpotifyGenreWeight(
                    genre = g,
                    count = c,
                    sharePercent = ((c.toFloat() / totalWeight.toFloat()) * 100f).toInt().coerceIn(4, 100)
                )
            }

        val topArtistInsights = artistAgg.values
            .sortedByDescending { it.first }
            .take(8)
            .map { (_, ent) ->
                SpotifyArtistInsight(
                    artistId = ent.artistId.orEmpty(),
                    name = ent.artistName,
                    imageUrl = ent.imageUrl,
                    genres = ent.genresCsv.split(",").map { formatGenreTitle(it) }.filter { it.isNotBlank() },
                    popularity = ent.popularity,
                    followersTotal = ent.followersTotal
                )
            }

        _tasteShiftSummaryFlow.value = SpotifyTasteShiftSummary(
            topGenres = topGenres,
            risingArtists = topArtistInsights.take(4),
            consistentArtists = topArtistInsights.take(4),
            emergingGenres = topGenres.take(3).map { it.genre },
            shortTermTopArtists = topArtistInsights,
            mediumTermTopArtists = emptyList(),
            longTermTopArtists = emptyList()
        )
    }

    private suspend fun seedFromLocalRoomTables() {
        val now = System.currentTimeMillis()
        runCatching {
            val savedArtists = visualDao.getArtistProfileSnapshot()
            savedArtists.forEach { entity ->
                artistCache[entity.artistKey] = entity
                if (!entity.artistId.isNullOrBlank()) {
                    artistCache["id:${entity.artistId}"] = entity
                }
            }
            val savedTracks = visualDao.getTrackVisualSnapshot()
            savedTracks.forEach { entity ->
                val resolved = entity.toResolved()
                trackCache[entity.trackKey] = resolved
                if (!entity.spotifyUri.isNullOrBlank()) {
                    trackCache[uriKey(entity.spotifyUri)] = resolved
                }
            }
        }

        // Also seed album art already synced in history & liked tracks
        runCatching {
            historyDao.getAggregatedTracksSnapshot(120).forEach { agg ->
                if (!agg.albumArtUrl.isNullOrBlank()) {
                    val key = canonicalTrackKey(agg.trackTitle, agg.artistName)
                    val cur = trackCache[key]
                    if (cur == null || cur.albumArtUrl.isNullOrBlank()) {
                        val seeded = ResolvedTrackVisuals(
                            trackKey = key,
                            spotifyTrackId = agg.trackId.takeIf { it.isNotBlank() },
                            spotifyUri = agg.spotifyUri.takeIf { it.isNotBlank() },
                            albumName = agg.albumName.takeIf { it.isNotBlank() },
                            albumArtUrl = agg.albumArtUrl,
                            artistImageUrl = cur?.artistImageUrl ?: lookupCachedArtistPhoto(agg.artistName),
                            genres = cur?.genres.orEmpty(),
                            popularity = cur?.popularity ?: 0
                        )
                        trackCache[key] = seeded
                        if (agg.spotifyUri.isNotBlank()) {
                            trackCache[uriKey(agg.spotifyUri)] = seeded
                        }
                    }
                }
            }
        }
        runCatching {
            libraryDao.getLikedTracksSnapshot(120).forEach { liked ->
                if (!liked.albumArtUrl.isNullOrBlank()) {
                    val key = canonicalTrackKey(liked.trackTitle, liked.artistName)
                    val cur = trackCache[key]
                    if (cur == null || cur.albumArtUrl.isNullOrBlank()) {
                        val seeded = ResolvedTrackVisuals(
                            trackKey = key,
                            spotifyTrackId = liked.trackId.takeIf { it.isNotBlank() },
                            spotifyUri = liked.spotifyUri.takeIf { it.isNotBlank() },
                            albumName = liked.albumName.takeIf { it.isNotBlank() },
                            albumArtUrl = liked.albumArtUrl,
                            artistImageUrl = cur?.artistImageUrl ?: lookupCachedArtistPhoto(liked.artistName),
                            genres = cur?.genres.orEmpty(),
                            popularity = cur?.popularity ?: 0
                        )
                        trackCache[key] = seeded
                        if (liked.spotifyUri.isNotBlank()) {
                            trackCache[uriKey(liked.spotifyUri)] = seeded
                        }
                    }
                }
            }
        }

        // Seed any uncached tracks up to 15 from recent Room history if needed later
        _artistProfilesFlow.value = artistCache.toMap()
        _trackVisualsFlow.value = trackCache.toMap()
        if (trackCache.isNotEmpty() && now > 0L) {
            // Also refresh top artist photos for existing track entries missing artist photos
            val missingArtistIds = trackCache.values
                .filter { it.artistImageUrl.isNullOrBlank() && !it.spotifyUri.isNullOrBlank() }
                .take(15)
            missingArtistIds.forEach { item ->
                val parts = item.trackKey.split("|", limit = 2)
                if (parts.size == 2) {
                    requestTrackVisuals(parts[0], parts[1], item.spotifyUri, item.albumArtUrl)
                }
            }
        }
    }

    /**
     * Fetches user profile (`/v1/me`) + multi-timeframe Top Artists (`/v1/me/top/artists`)
     * for short/medium/long terms to provide free Real Genres, Artist Photos, and Taste Shifts.
     */
    suspend fun syncUserProfileAndTasteIntelligence(force: Boolean = false) = intelMutex.withLock {
        val lastSync = prefs.getLong(KEY_LAST_INTEL_SYNC_MS, 0L)
        if (!force && System.currentTimeMillis() - lastSync < INTEL_SYNC_INTERVAL_MS && _userProfileFlow.value != null) {
            return@withLock
        }
        val bearer = getUserBearerToken() ?: return@withLock
        val authHeader = "Bearer $bearer"
        _isSyncingIntelligenceFlow.value = true
        val now = System.currentTimeMillis()
        try {
            // 1. User Profile (/v1/me)
            runCatching {
                val meResp = userApi.getCurrentUserProfile(authHeader)
                if (meResp.isSuccessful) {
                    val me = meResp.body()
                    if (me != null && me.id.isNotBlank()) {
                        val avatar = me.images.firstOrNull()?.url.orEmpty()
                        val snapshot = SpotifyUserProfileSnapshot(
                            userId = me.id,
                            displayName = me.displayName?.trim().orEmpty(),
                            email = me.email?.trim().orEmpty(),
                            country = me.country?.trim().orEmpty(),
                            product = me.product?.trim().orEmpty(),
                            avatarUrl = avatar,
                            followersCount = me.followers?.total ?: 0L,
                            syncedAtMs = now
                        )
                        _userProfileFlow.value = snapshot
                        TuneMindAuthSessionManager.getInstance(appContext).updateSpotifyProfileCache(
                            displayName = snapshot.displayName.takeIf { it.isNotBlank() },
                            avatarUrl = snapshot.avatarUrl.takeIf { it.isNotBlank() }
                        )
                    }
                }
            }

            // 2. Top Artists across 3 timeframes (short_term, medium_term, long_term)
            val shortList = fetchTopArtistsRange("short_term", authHeader, now)
            val mediumList = fetchTopArtistsRange("medium_term", authHeader, now)
            val longList = fetchTopArtistsRange("long_term", authHeader, now)

            if (shortList.isNotEmpty() || mediumList.isNotEmpty() || longList.isNotEmpty()) {
                val summary = computeTasteShiftSummary(shortList, mediumList, longList)
                _tasteShiftSummaryFlow.value = summary
                _artistProfilesFlow.value = artistCache.toMap()
            }

            prefs.edit().putLong(KEY_LAST_INTEL_SYNC_MS, now).apply()
            persistIntelligenceSnapshot()
        } catch (e: Exception) {
            Log.w(TAG, "syncUserProfileAndTasteIntelligence notice: ${e.message}")
        } finally {
            _isSyncingIntelligenceFlow.value = false
        }
    }

    private suspend fun fetchTopArtistsRange(
        timeRange: String,
        authHeader: String,
        now: Long
    ): List<SpotifyArtistInsight> {
        return try {
            val resp = userApi.getTopArtists(authHeader = authHeader, timeRange = timeRange, limit = 20)
            if (!resp.isSuccessful) return emptyList()
            val dtos = resp.body()?.items.orEmpty()
            val entities = mutableListOf<ArtistProfileCacheEntity>()
            val insights = dtos.mapNotNull { dto ->
                saveArtistDto(dto, now)?.let { entities.add(it) }
                if (dto.name.isBlank()) null else {
                    SpotifyArtistInsight(
                        artistId = dto.id.orEmpty(),
                        name = dto.name.trim(),
                        imageUrl = dto.images?.firstOrNull()?.url,
                        genres = dto.genres.orEmpty().map { formatGenreTitle(it) },
                        popularity = dto.popularity ?: 0,
                        followersTotal = dto.followers?.total ?: 0L
                    )
                }
            }
            if (entities.isNotEmpty()) {
                runCatching { visualDao.upsertArtistProfiles(entities) }
            }
            insights
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun computeTasteShiftSummary(
        shortList: List<SpotifyArtistInsight>,
        mediumList: List<SpotifyArtistInsight>,
        longList: List<SpotifyArtistInsight>
    ): SpotifyTasteShiftSummary {
        val longNames = longList.map { canonicalArtistKey(it.name) }.toSet()
        val mediumNames = mediumList.map { canonicalArtistKey(it.name) }.toSet()
        val shortNames = shortList.map { canonicalArtistKey(it.name) }.toSet()

        val rising = shortList.filter { canonicalArtistKey(it.name) !in longNames }.take(6)
        val consistent = shortList.filter {
            val k = canonicalArtistKey(it.name)
            k in longNames || k in mediumNames
        }.take(6)

        val genreCounts = linkedMapOf<String, Int>()
        (shortList + mediumList.take(10)).forEachIndexed { index, artist ->
            val weight = if (index < shortList.size) 2 else 1
            artist.genres.forEach { genre ->
                if (genre.isNotBlank()) {
                    genreCounts[genre] = (genreCounts[genre] ?: 0) + weight
                }
            }
        }
        val totalWeight = genreCounts.values.sum().coerceAtLeast(1)
        val topGenres = genreCounts.entries
            .sortedByDescending { it.value }
            .take(8)
            .map { (g, c) ->
                SpotifyGenreWeight(
                    genre = g,
                    count = c,
                    sharePercent = ((c.toFloat() / totalWeight.toFloat()) * 100f).toInt().coerceIn(4, 100)
                )
            }

        val longGenres = longList.flatMap { it.genres }.map { it.lowercase() }.toSet()
        val emergingGenres = shortList.flatMap { it.genres }
            .filter { it.lowercase() !in longGenres }
            .distinct()
            .take(5)

        return SpotifyTasteShiftSummary(
            topGenres = topGenres,
            risingArtists = rising,
            consistentArtists = if (consistent.isNotEmpty()) consistent else shortList.take(4),
            emergingGenres = if (emergingGenres.isNotEmpty()) emergingGenres else topGenres.take(3).map { it.genre },
            shortTermTopArtists = shortList.take(10),
            mediumTermTopArtists = mediumList.take(10),
            longTermTopArtists = longList.take(10)
        )
    }

    /**
     * Fetches New Releases from the user's followed artists (`/v1/me/following?type=artist` -> `/v1/artists/{id}/albums`).
     */
    suspend fun syncFollowedNewReleases(force: Boolean = false) {
        val lastSync = prefs.getLong(KEY_LAST_RELEASES_SYNC_MS, 0L)
        if (!force && System.currentTimeMillis() - lastSync < RELEASES_SYNC_INTERVAL_MS && _newReleasesFlow.value.isNotEmpty()) {
            return
        }
        val bearer = getUserBearerToken() ?: return
        val authHeader = "Bearer $bearer"
        try {
            val followedResp = userApi.getFollowedArtists(authHeader = authHeader, type = "artist", limit = 15)
            val artistCandidates: List<SpotifyArtistDto> = if (followedResp.isSuccessful) {
                val items = followedResp.body()?.artists?.items.orEmpty()
                val now = System.currentTimeMillis()
                val entities = items.mapNotNull { saveArtistDto(it, now) }
                if (entities.isNotEmpty()) runCatching { visualDao.upsertArtistProfiles(entities) }
                items
            } else {
                // Fallback to top short-term artist IDs if user-follow-read wasn't granted yet
                _tasteShiftSummaryFlow.value.shortTermTopArtists.take(8).map {
                    SpotifyArtistDto(id = it.artistId, name = it.name)
                }
            }

            if (artistCandidates.isEmpty()) return

            val releases = mutableListOf<SpotifyNewReleaseItem>()
            artistCandidates.filter { !it.id.isNullOrBlank() }.take(8).forEach { artist ->
                val aId = artist.id ?: return@forEach
                runCatching {
                    val albResp = userApi.getArtistAlbums(artistId = aId, authHeader = authHeader, includeGroups = "single,album", limit = 2)
                    if (albResp.isSuccessful) {
                        albResp.body()?.items.orEmpty().forEach { alb ->
                            if (alb.name.isNotBlank() && alb.uri.isNotBlank()) {
                                releases.add(
                                    SpotifyNewReleaseItem(
                                        albumId = alb.id,
                                        title = alb.name.trim(),
                                        artistName = alb.artists.firstOrNull()?.name?.takeIf { it.isNotBlank() } ?: artist.name,
                                        releaseDate = alb.releaseDate,
                                        albumType = alb.albumType,
                                        imageUrl = alb.images.firstOrNull()?.url,
                                        spotifyUri = alb.uri
                                    )
                                )
                            }
                        }
                    }
                }
            }

            val sortedUnique = releases
                .distinctBy { it.albumId.ifBlank { "${it.title.lowercase()}|${it.artistName.lowercase()}" } }
                .sortedByDescending { it.releaseDate }
                .take(12)

            if (sortedUnique.isNotEmpty()) {
                _newReleasesFlow.value = sortedUnique
                prefs.edit().putLong(KEY_LAST_RELEASES_SYNC_MS, System.currentTimeMillis()).apply()
                persistIntelligenceSnapshot()
            }
        } catch (e: Exception) {
            Log.w(TAG, "syncFollowedNewReleases notice: ${e.message}")
        }
    }

    suspend fun addToSpotifyQueue(
        title: String,
        artist: String,
        spotifyUri: String? = null
    ): SpotifyActionResult {
        val bearer = getUserBearerToken()
            ?: return SpotifyActionResult(false, "Connect Spotify first to queue tracks.", false)
        val resolvedUri = resolvePlayableTrackUri(title, artist, spotifyUri)
            ?: return SpotifyActionResult(false, "Couldn't find \"$title\" on Spotify.", false)

        return try {
            val resp = userApi.addTrackToQueue(authHeader = "Bearer $bearer", uri = resolvedUri)
            when {
                resp.isSuccessful -> SpotifyActionResult(true, "Added \"$title\" to Spotify Up Next queue.")
                resp.code() == 404 -> SpotifyActionResult(false, "Open Spotify on any device first so a queue is active.")
                resp.code() == 403 -> SpotifyActionResult(false, "Update Spotify permissions to control queue.", true)
                else -> SpotifyActionResult(false, "Spotify queue returned HTTP ${resp.code()}.")
            }
        } catch (e: Exception) {
            SpotifyActionResult(false, "Queue request failed: ${e.message}")
        }
    }

    suspend fun saveTrackToSpotifyLiked(
        title: String,
        artist: String,
        spotifyUri: String? = null
    ): SpotifyActionResult {
        val bearer = getUserBearerToken()
            ?: return SpotifyActionResult(false, "Connect Spotify first to save tracks.", false)
        val resolvedUri = resolvePlayableTrackUri(title, artist, spotifyUri)
            ?: return SpotifyActionResult(false, "Couldn't match \"$title\" in Spotify catalog.", false)
        val trackId = extractSpotifyTrackId(resolvedUri)
            ?: return SpotifyActionResult(false, "Only Spotify tracks can be saved to Liked Songs.", false)

        return try {
            val resp = userApi.saveTracksToLibrary(
                authHeader = "Bearer $bearer",
                idsCsv = trackId,
                body = SpotifyTrackIdsBodyDto(ids = listOf(trackId))
            )
            when {
                resp.isSuccessful -> SpotifyActionResult(true, "Saved \"$title\" to your Spotify Liked Songs.")
                resp.code() == 403 || resp.code() == 401 -> SpotifyActionResult(
                    false,
                    "Tap 'Update Permissions' in Profile or Spotify tab to allow saving Liked Songs.",
                    true
                )
                else -> SpotifyActionResult(false, "Could not save song (HTTP ${resp.code()}).")
            }
        } catch (e: Exception) {
            SpotifyActionResult(false, "Save failed: ${e.message}")
        }
    }

    suspend fun exportDropAsSpotifyPlaylist(
        playlistName: String,
        description: String,
        tracks: List<Triple<String, String, String?>>
    ): SpotifyActionResult {
        val bearer = getUserBearerToken()
            ?: return SpotifyActionResult(false, "Connect Spotify to export playlists.", false)
        val authHeader = "Bearer $bearer"

        // Ensure we have user ID
        var userId = _userProfileFlow.value?.userId.orEmpty()
        if (userId.isBlank()) {
            val meResp = runCatching { userApi.getCurrentUserProfile(authHeader) }.getOrNull()
            if (meResp?.isSuccessful == true) {
                userId = meResp.body()?.id.orEmpty()
            }
        }
        if (userId.isBlank()) {
            return SpotifyActionResult(false, "Sign in with Spotify again to grant profile & playlist access.", true)
        }

        val resolvedUris = tracks.mapNotNull { (title, artist, uri) ->
            resolvePlayableTrackUri(title, artist, uri)
        }.distinct().take(40)

        if (resolvedUris.isEmpty()) {
            return SpotifyActionResult(false, "No playable Spotify track URIs resolved for this drop.")
        }

        return try {
            val createResp = userApi.createPlaylist(
                userId = userId,
                authHeader = authHeader,
                body = CreatePlaylistRequestDto(
                    name = playlistName,
                    description = description,
                    isPublic = false
                )
            )
            if (!createResp.isSuccessful) {
                return if (createResp.code() == 403 || createResp.code() == 401) {
                    SpotifyActionResult(false, "Update Spotify permissions to create playlists.", true)
                } else {
                    SpotifyActionResult(false, "Could not create playlist (HTTP ${createResp.code()}).")
                }
            }
            val playlistId = createResp.body()?.id.orEmpty()
            if (playlistId.isBlank()) {
                return SpotifyActionResult(false, "Spotify did not return a playlist ID.")
            }
            val addResp = userApi.addItemsToPlaylist(
                playlistId = playlistId,
                authHeader = authHeader,
                body = AddTracksToPlaylistRequestDto(uris = resolvedUris)
            )
            if (addResp.isSuccessful) {
                SpotifyActionResult(true, "Saved \"${playlistName}\" (${resolvedUris.size} tracks) to your Spotify library!")
            } else {
                SpotifyActionResult(false, "Created playlist, but failed adding tracks (HTTP ${addResp.code()}).")
            }
        } catch (e: Exception) {
            SpotifyActionResult(false, "Playlist export error: ${e.message}")
        }
    }

    suspend fun refreshPlaybackDevices(): List<SpotifyDeviceItem> {
        val bearer = getUserBearerToken() ?: return emptyList()
        return try {
            val resp = userApi.getPlaybackDevices("Bearer $bearer")
            if (resp.isSuccessful) {
                val list = resp.body()?.devices.orEmpty().mapNotNull { dto ->
                    val id = dto.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    SpotifyDeviceItem(
                        id = id,
                        name = dto.name,
                        type = dto.type,
                        isActive = dto.isActive,
                        volumePercent = dto.volumePercent
                    )
                }
                _playbackDevicesFlow.value = list
                list
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun transferPlaybackToDevice(deviceId: String, startPlaying: Boolean = true): SpotifyActionResult {
        val bearer = getUserBearerToken()
            ?: return SpotifyActionResult(false, "Connect Spotify first.")
        return try {
            val resp = userApi.transferPlayback(
                authHeader = "Bearer $bearer",
                body = TransferPlaybackRequestDto(deviceIds = listOf(deviceId), play = startPlaying)
            )
            if (resp.isSuccessful) {
                refreshPlaybackDevices()
                SpotifyActionResult(true, "Switched Spotify playback device.")
            } else if (resp.code() == 403) {
                SpotifyActionResult(false, "Switching devices requires Spotify Premium.")
            } else {
                SpotifyActionResult(false, "Device switch returned HTTP ${resp.code()}.")
            }
        } catch (e: Exception) {
            SpotifyActionResult(false, "Device switch failed: ${e.message}")
        }
    }

    private suspend fun resolvePlayableTrackUri(title: String, artist: String, explicitUri: String?): String? {
        val cleanExplicit = explicitUri?.trim()
        if (!cleanExplicit.isNullOrBlank() && cleanExplicit.startsWith("spotify:track:")) {
            return cleanExplicit
        }
        trackCache[canonicalTrackKey(title, artist)]?.spotifyUri?.takeIf { it.startsWith("spotify:track:") }?.let {
            return it
        }
        return SpotifyTrackUriResolver.resolveTrackUriBlocking(
            context = appContext,
            cleanTitle = title,
            cleanArtist = artist,
            fullQuery = "$title $artist".trim()
        )
    }

    private suspend fun getUserBearerToken(): String? {
        return authManager.getValidAccessToken()
            ?: tokenManager.getValidAccessToken(authService)
    }

    private fun TrackVisualCacheEntity.toResolved(): ResolvedTrackVisuals {
        return ResolvedTrackVisuals(
            trackKey = trackKey,
            spotifyTrackId = spotifyTrackId,
            spotifyUri = spotifyUri,
            albumName = albumName,
            albumArtUrl = albumArtUrl,
            artistImageUrl = artistImageUrl,
            genres = genresCsv.split(",").map { it.trim() }.filter { it.isNotBlank() },
            popularity = popularity
        )
    }

    private fun extractSpotifyTrackId(uriOrId: String?): String? {
        val clean = uriOrId?.trim().orEmpty()
        return when {
            clean.startsWith("spotify:track:") -> clean.removePrefix("spotify:track:").takeIf { it.isNotBlank() }
            clean.matches(Regex("[A-Za-z0-9]{22}")) -> clean
            else -> null
        }
    }

    private fun formatGenreTitle(raw: String): String {
        return raw.trim()
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase(Locale.ROOT) else ch.toString()
                }
            }
    }

    private fun persistIntelligenceSnapshot() {
        runCatching {
            val root = JSONObject()
            _userProfileFlow.value?.let { p ->
                root.put("profile", JSONObject().apply {
                    put("userId", p.userId)
                    put("displayName", p.displayName)
                    put("email", p.email)
                    put("country", p.country)
                    put("product", p.product)
                    put("avatarUrl", p.avatarUrl)
                    put("followersCount", p.followersCount)
                    put("syncedAtMs", p.syncedAtMs)
                })
            }
            val s = _tasteShiftSummaryFlow.value
            root.put("taste", JSONObject().apply {
                put("topGenres", JSONArray().apply {
                    s.topGenres.forEach { g ->
                        put(JSONObject().apply {
                            put("genre", g.genre)
                            put("count", g.count)
                            put("share", g.sharePercent)
                        })
                    }
                })
                put("emergingGenres", JSONArray(s.emergingGenres))
                put("shortArtists", artistsToJson(s.shortTermTopArtists))
                put("mediumArtists", artistsToJson(s.mediumTermTopArtists))
                put("longArtists", artistsToJson(s.longTermTopArtists))
                put("risingArtists", artistsToJson(s.risingArtists))
                put("consistentArtists", artistsToJson(s.consistentArtists))
            })
            root.put("releases", JSONArray().apply {
                _newReleasesFlow.value.forEach { r ->
                    put(JSONObject().apply {
                        put("albumId", r.albumId)
                        put("title", r.title)
                        put("artistName", r.artistName)
                        put("releaseDate", r.releaseDate)
                        put("albumType", r.albumType)
                        put("imageUrl", r.imageUrl.orEmpty())
                        put("spotifyUri", r.spotifyUri)
                    })
                }
            })
            prefs.edit().putString(KEY_INTEL_JSON, root.toString()).apply()
        }
    }

    private fun loadPersistedIntelligenceSnapshot() {
        val raw = prefs.getString(KEY_INTEL_JSON, null) ?: return
        runCatching {
            val root = JSONObject(raw)
            root.optJSONObject("profile")?.let { p ->
                _userProfileFlow.value = SpotifyUserProfileSnapshot(
                    userId = p.optString("userId"),
                    displayName = p.optString("displayName"),
                    email = p.optString("email"),
                    country = p.optString("country"),
                    product = p.optString("product"),
                    avatarUrl = p.optString("avatarUrl"),
                    followersCount = p.optLong("followersCount", 0L),
                    syncedAtMs = p.optLong("syncedAtMs", 0L)
                )
            }
            root.optJSONObject("taste")?.let { t ->
                val genres = mutableListOf<SpotifyGenreWeight>()
                val gArr = t.optJSONArray("topGenres")
                if (gArr != null) {
                    for (i in 0 until gArr.length()) {
                        val obj = gArr.optJSONObject(i) ?: continue
                        genres.add(
                            SpotifyGenreWeight(
                                genre = obj.optString("genre"),
                                count = obj.optInt("count"),
                                sharePercent = obj.optInt("share")
                            )
                        )
                    }
                }
                val emerging = mutableListOf<String>()
                val eArr = t.optJSONArray("emergingGenres")
                if (eArr != null) {
                    for (i in 0 until eArr.length()) {
                        val sVal = eArr.optString(i)
                        if (sVal.isNotBlank()) emerging.add(sVal)
                    }
                }
                _tasteShiftSummaryFlow.value = SpotifyTasteShiftSummary(
                    topGenres = genres,
                    risingArtists = jsonToArtists(t.optJSONArray("risingArtists")),
                    consistentArtists = jsonToArtists(t.optJSONArray("consistentArtists")),
                    emergingGenres = emerging,
                    shortTermTopArtists = jsonToArtists(t.optJSONArray("shortArtists")),
                    mediumTermTopArtists = jsonToArtists(t.optJSONArray("mediumArtists")),
                    longTermTopArtists = jsonToArtists(t.optJSONArray("longArtists"))
                )
            }
            val relArr = root.optJSONArray("releases")
            if (relArr != null) {
                val rels = mutableListOf<SpotifyNewReleaseItem>()
                for (i in 0 until relArr.length()) {
                    val obj = relArr.optJSONObject(i) ?: continue
                    rels.add(
                        SpotifyNewReleaseItem(
                            albumId = obj.optString("albumId"),
                            title = obj.optString("title"),
                            artistName = obj.optString("artistName"),
                            releaseDate = obj.optString("releaseDate"),
                            albumType = obj.optString("albumType"),
                            imageUrl = obj.optString("imageUrl").takeIf { it.isNotBlank() },
                            spotifyUri = obj.optString("spotifyUri")
                        )
                    )
                }
                _newReleasesFlow.value = rels
            }
        }
    }

    private fun artistsToJson(list: List<SpotifyArtistInsight>): JSONArray {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(JSONObject().apply {
                put("artistId", a.artistId)
                put("name", a.name)
                put("imageUrl", a.imageUrl.orEmpty())
                put("genres", JSONArray(a.genres))
                put("popularity", a.popularity)
                put("followersTotal", a.followersTotal)
            })
        }
        return arr
    }

    private fun jsonToArtists(arr: JSONArray?): List<SpotifyArtistInsight> {
        if (arr == null) return emptyList()
        val list = mutableListOf<SpotifyArtistInsight>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val genres = mutableListOf<String>()
            val gArr = obj.optJSONArray("genres")
            if (gArr != null) {
                for (j in 0 until gArr.length()) {
                    val g = gArr.optString(j)
                    if (g.isNotBlank()) genres.add(g)
                }
            }
            list.add(
                SpotifyArtistInsight(
                    artistId = obj.optString("artistId"),
                    name = obj.optString("name"),
                    imageUrl = obj.optString("imageUrl").takeIf { it.isNotBlank() },
                    genres = genres,
                    popularity = obj.optInt("popularity"),
                    followersTotal = obj.optLong("followersTotal")
                )
            )
        }
        return list
    }

    companion object {
        private const val TAG = "SpotifyVisualRepo"
        private const val PREFS_NAME = "tunemind_spotify_intelligence_cache"
        private const val KEY_LAST_INTEL_SYNC_MS = "last_intel_sync_ms"
        private const val KEY_LAST_RELEASES_SYNC_MS = "last_releases_sync_ms"
        private const val KEY_INTEL_JSON = "intel_snapshot_json_v1"
        private const val RETRY_COOLDOWN_MS = 10 * 60_000L
        private const val INTEL_SYNC_INTERVAL_MS = 4 * 3600_000L
        private const val RELEASES_SYNC_INTERVAL_MS = 6 * 3600_000L

        @Volatile
        private var INSTANCE: SpotifyVisualArtworkRepository? = null

        fun getInstance(context: Context): SpotifyVisualArtworkRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SpotifyVisualArtworkRepository(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }

        fun canonicalTrackKey(title: String?, artist: String?): String {
            return ListeningPlayDeduplicator.canonicalTrackKey(title, artist)
        }

        fun uriKey(uri: String): String = "uri:${uri.trim().lowercase(Locale.ROOT)}"

        fun extractPrimaryArtistName(artist: String?): String {
            val clean = artist?.trim().orEmpty()
            if (clean.isBlank() || clean.equals("Unknown Artist", ignoreCase = true)) return ""
            return clean.split(",", "&", " feat.", " ft.", " x ")
                .firstOrNull()
                ?.trim()
                .orEmpty()
        }

        fun canonicalArtistKey(name: String): String {
            return name.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
        }

        fun sanitizeNonSpotifyTrackMetadata(rawTitle: String?, rawArtist: String?): Pair<String, String> {
            var title = rawTitle.orEmpty()
                .replace(Regex("\\.(mp3|m4a|flac|wav|ogg|aac|opus)$", RegexOption.IGNORE_CASE), "")
                .replace(
                    Regex(
                        "\\((?:official\\s*(?:music\\s*)?video|official\\s*audio|lyric\\s*video|lyrics|visualizer|hd|4k|hq|live|clean|explicit)\\)",
                        RegexOption.IGNORE_CASE
                    ),
                    " "
                )
                .replace(
                    Regex(
                        "\\[(?:official\\s*(?:music\\s*)?video|official\\s*audio|lyric\\s*video|lyrics|visualizer|hd|4k|hq|live|clean|explicit)\\]",
                        RegexOption.IGNORE_CASE
                    ),
                    " "
                )
                .replace(Regex("\\s+"), " ")
                .trim()

            var artist = rawArtist.orEmpty()
                .replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*VEVO$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+"), " ")
                .trim()

            val lowerArtist = artist.lowercase(Locale.ROOT)
            val isGeneric = artist.isBlank() ||
                lowerArtist in setOf("unknown", "unknown artist", "<unknown>", "youtube", "youtube music", "music player")

            if (isGeneric && title.contains(" - ")) {
                val splitIdx = title.indexOf(" - ")
                if (splitIdx >= 2) {
                    artist = title.substring(0, splitIdx).trim()
                    title = title.substring(splitIdx + 3).trim()
                }
            } else if (!isGeneric && title.lowercase(Locale.ROOT).startsWith("$lowerArtist - ")) {
                title = title.substring(artist.length + 3).trim()
            }

            val primaryArtist = if (isGeneric && artist.isBlank()) "" else extractPrimaryArtistName(artist)
            val cleanTitle = title
                .replace(Regex("\\s*[\\(\\[]\\s*(?:feat\\.?|ft\\.?|featuring)\\s+[^\\)\\]]+[\\)\\]]", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+"), " ")
                .trim()

            return cleanTitle to primaryArtist
        }
    }
}
