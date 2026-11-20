package com.tunemind.app.data.ingestion

import android.content.Context
import android.util.Log
import com.tunemind.app.data.local.ArtistProfileCacheEntity
import com.tunemind.app.data.local.CrossAppTrackEntity
import com.tunemind.app.data.local.TrackVisualCacheEntity
import com.tunemind.app.data.local.TuneMindDatabase
import com.tunemind.app.data.local.TuneMindPlaylistEntity
import com.tunemind.app.data.local.TuneMindPlaylistTrackEntity
import com.tunemind.app.domain.model.TrackIdentityResolver
import com.tunemind.app.provider.SpotifyMusicProvider
import com.tunemind.app.provider.SpotifyPremiumConnectManager
import com.tunemind.app.provider.SpotifySyncedArtist
import com.tunemind.app.provider.SpotifySyncedPlaylist
import com.tunemind.app.provider.SpotifySyncedTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

data class SpotifyLibrarySyncUiState(
    val isSyncing: Boolean = false,
    val progressMessage: String = "",
    val lastSyncAtMs: Long = 0L,
    val syncedLikedCount: Int = 0,
    val syncedPlaylistCount: Int = 0,
    val syncedPlaylistTracksCount: Int = 0,
    val syncedRecentlyPlayedCount: Int = 0,
    val syncedTopArtistsCount: Int = 0,
    val optionalInsightsNeedReconnect: Boolean = false,
    val lastError: String? = null
) {
    val hasSyncedBefore: Boolean
        get() = lastSyncAtMs > 0L

    val summaryLabel: String
        get() {
            if (isSyncing && progressMessage.isNotBlank()) return progressMessage
            if (lastSyncAtMs <= 0L) return "Bring your Spotify Liked Songs & Playlists into TuneMind"
            val parts = mutableListOf<String>()
            if (syncedLikedCount > 0) parts.add("$syncedLikedCount Liked")
            if (syncedPlaylistCount > 0) parts.add("$syncedPlaylistCount Playlist${if (syncedPlaylistCount == 1) "" else "s"}")
            if (syncedTopArtistsCount > 0) parts.add("$syncedTopArtistsCount Top Artists")
            return if (parts.isEmpty()) {
                "Spotify library synced"
            } else {
                "Synced: ${parts.joinToString(" · ")}"
            }
        }
}

data class SpotifyLibrarySyncResult(
    val success: Boolean,
    val likedSongsSynced: Int = 0,
    val playlistsSynced: Int = 0,
    val playlistTracksSynced: Int = 0,
    val recentlyPlayedSynced: Int = 0,
    val topTracksSynced: Int = 0,
    val topArtistsSynced: Int = 0,
    val optionalInsightsNeedReconnect: Boolean = false,
    val message: String = ""
)

/**
 * Full Spotify Library & Playlist Sync Engine for BOTH Spotify Premium and Spotify Free users.
 *
 * Imports and merges cleanly into TuneMind's permanent Room database without duplicating or
 * overwriting any existing MediaSession listening history:
 * 1. Spotify Liked Songs (`GET /v1/me/tracks`) -> `cross_app_track_library` (`is_liked = true`)
 * 2. Spotify User Playlists + Tracks (`GET /v1/me/playlists`) -> `tunemind_playlists` & `tunemind_playlist_tracks`
 * 3. Spotify Recently Played (`GET /v1/me/player/recently-played`) -> `cross_app_track_library`
 * 4. Spotify Top Tracks & Top Artists (`GET /v1/me/top/tracks` and `artists`) -> library + `universal_artist_profile_cache`
 * 5. High-res Spotify artwork -> `universal_track_visual_cache` for instant visuals across all screens.
 */
object SpotifyLibrarySyncEngine {
    private const val TAG = "SpotifyLibrarySync"
    private const val PREFS_NAME = "tunemind_spotify_library_sync_meta_v1"
    const val SPOTIFY_PLAYLIST_ID_PREFIX = "spotify_pl_"

    private const val KEY_LAST_SYNC_MS = "last_sync_ms"
    private const val KEY_LIKED_COUNT = "liked_count"
    private const val KEY_PLAYLIST_COUNT = "playlist_count"
    private const val KEY_PLAYLIST_TRACKS_COUNT = "playlist_tracks_count"
    private const val KEY_RECENT_COUNT = "recent_count"
    private const val KEY_TOP_ARTISTS_COUNT = "top_artists_count"
    private const val KEY_NEEDS_INSIGHTS_SCOPE = "needs_insights_scope"

    private val syncRunning = AtomicBoolean(false)

    private val _syncState = MutableStateFlow(SpotifyLibrarySyncUiState())
    val syncState: StateFlow<SpotifyLibrarySyncUiState> = _syncState.asStateFlow()

    fun ensureLoaded(context: Context): SpotifyLibrarySyncUiState {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = _syncState.value
        if (current.isSyncing) return current
        val loaded = SpotifyLibrarySyncUiState(
            isSyncing = false,
            progressMessage = "",
            lastSyncAtMs = prefs.getLong(KEY_LAST_SYNC_MS, 0L),
            syncedLikedCount = prefs.getInt(KEY_LIKED_COUNT, 0),
            syncedPlaylistCount = prefs.getInt(KEY_PLAYLIST_COUNT, 0),
            syncedPlaylistTracksCount = prefs.getInt(KEY_PLAYLIST_TRACKS_COUNT, 0),
            syncedRecentlyPlayedCount = prefs.getInt(KEY_RECENT_COUNT, 0),
            syncedTopArtistsCount = prefs.getInt(KEY_TOP_ARTISTS_COUNT, 0),
            optionalInsightsNeedReconnect = prefs.getBoolean(KEY_NEEDS_INSIGHTS_SCOPE, false),
            lastError = current.lastError
        )
        _syncState.value = loaded
        return loaded
    }

    fun isSpotifySyncedPlaylistId(playlistId: String): Boolean =
        playlistId.startsWith(SPOTIFY_PLAYLIST_ID_PREFIX)

    fun extractSpotifyPlaylistId(playlistId: String): String? =
        playlistId.takeIf { it.startsWith(SPOTIFY_PLAYLIST_ID_PREFIX) }
            ?.removePrefix(SPOTIFY_PLAYLIST_ID_PREFIX)
            ?.takeIf { it.isNotBlank() }

    /**
     * Runs a complete Spotify library & playlist sync. Safe to call from any coroutine or background worker.
     *
     * @param forceRefresh If false, skips automatic sync when a successful sync already ran within [minIntervalMs].
     */
    suspend fun syncAll(
        context: Context,
        forceRefresh: Boolean = true,
        minIntervalMs: Long = 10 * 60 * 1000L
    ): SpotifyLibrarySyncResult = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val previous = ensureLoaded(appCtx)

        if (!SpotifyPremiumConnectManager.isConnected(appCtx)) {
            return@withContext SpotifyLibrarySyncResult(
                success = false,
                message = "Connect Spotify in Settings first to sync your Liked Songs and Playlists."
            )
        }

        if (!forceRefresh && previous.lastSyncAtMs > 0L && (System.currentTimeMillis() - previous.lastSyncAtMs) < minIntervalMs) {
            return@withContext SpotifyLibrarySyncResult(
                success = true,
                likedSongsSynced = previous.syncedLikedCount,
                playlistsSynced = previous.syncedPlaylistCount,
                playlistTracksSynced = previous.syncedPlaylistTracksCount,
                recentlyPlayedSynced = previous.syncedRecentlyPlayedCount,
                topArtistsSynced = previous.syncedTopArtistsCount,
                message = previous.summaryLabel
            )
        }

        if (!syncRunning.compareAndSet(false, true)) {
            return@withContext SpotifyLibrarySyncResult(
                success = true,
                message = "Spotify library sync is already running..."
            )
        }

        try {
            _syncState.value = previous.copy(
                isSyncing = true,
                progressMessage = "Connecting to Spotify library...",
                lastError = null
            )

            // Verify token works before starting
            val token = SpotifyPremiumConnectManager.refreshAccessTokenIfNeeded(appCtx)
            if (token.isNullOrBlank()) {
                val err = "Could not refresh Spotify session. Check your internet or reconnect Spotify in Settings."
                _syncState.value = previous.copy(isSyncing = false, progressMessage = "", lastError = err)
                return@withContext SpotifyLibrarySyncResult(success = false, message = err)
            }

            val db = TuneMindDatabase.getInstance(appCtx)
            val crossAppDao = db.crossAppLibraryDao()
            val playlistDao = db.tuneMindPlaylistDao()

            // 1. Fetch & merge Spotify Liked Songs
            _syncState.value = _syncState.value.copy(progressMessage = "Importing Spotify Liked Songs...")
            val likedTracks = SpotifyPremiumConnectManager.fetchLikedTracks(
                context = appCtx,
                maxTracks = 300
            ) { fetched ->
                _syncState.value = _syncState.value.copy(
                    progressMessage = "Importing Liked Songs ($fetched)..."
                )
            }
            if (likedTracks.isNotEmpty()) {
                mergeTracksIntoCrossAppLibrary(
                    dao = crossAppDao,
                    tracks = likedTracks,
                    markAsLiked = true,
                    seedPlayCountIfNew = 0
                )
            }

            // 2. Fetch & upsert Spotify User Playlists + their tracks
            _syncState.value = _syncState.value.copy(progressMessage = "Importing Spotify Playlists...")
            val playlists = SpotifyPremiumConnectManager.fetchUserPlaylistsWithTracks(
                context = appCtx,
                maxPlaylists = 30,
                maxTracksPerPlaylist = 100
            ) { done, total ->
                _syncState.value = _syncState.value.copy(
                    progressMessage = "Syncing playlists ($done/$total)..."
                )
            }
            var playlistTracksTotal = 0
            if (playlists.isNotEmpty()) {
                playlistTracksTotal = upsertSpotifyPlaylists(
                    playlistDao = playlistDao,
                    crossAppDao = crossAppDao,
                    playlists = playlists
                )
            }

            // 3. Optional enrichment: Recently Played & Top Tracks / Top Artists (gracefully skipped if older token lacks scope)
            _syncState.value = _syncState.value.copy(progressMessage = "Syncing recent plays & top artists...")
            val (recentTracks, recentScopeMissing) = SpotifyPremiumConnectManager.fetchRecentlyPlayedTracks(appCtx, limit = 50)
            if (recentTracks.isNotEmpty()) {
                mergeTracksIntoCrossAppLibrary(
                    dao = crossAppDao,
                    tracks = recentTracks,
                    markAsLiked = false,
                    seedPlayCountIfNew = 1
                )
            }

            val (topTracks, topTrackScopeMissing) = SpotifyPremiumConnectManager.fetchTopTracks(appCtx, limit = 40)
            if (topTracks.isNotEmpty()) {
                mergeTracksIntoCrossAppLibrary(
                    dao = crossAppDao,
                    tracks = topTracks,
                    markAsLiked = false,
                    seedPlayCountIfNew = 1
                )
            }

            val (topArtists, topArtistScopeMissing) = SpotifyPremiumConnectManager.fetchTopArtists(appCtx, limit = 25)
            if (topArtists.isNotEmpty()) {
                upsertTopArtistsToVisualCache(crossAppDao, topArtists)
            }

            val needsInsightsScope = recentScopeMissing || topTrackScopeMissing || topArtistScopeMissing
            val nowMs = System.currentTimeMillis()

            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putLong(KEY_LAST_SYNC_MS, nowMs)
                .putInt(KEY_LIKED_COUNT, likedTracks.size)
                .putInt(KEY_PLAYLIST_COUNT, playlists.size)
                .putInt(KEY_PLAYLIST_TRACKS_COUNT, playlistTracksTotal)
                .putInt(KEY_RECENT_COUNT, recentTracks.size)
                .putInt(KEY_TOP_ARTISTS_COUNT, topArtists.size)
                .putBoolean(KEY_NEEDS_INSIGHTS_SCOPE, needsInsightsScope)
                .apply()

            val finalState = SpotifyLibrarySyncUiState(
                isSyncing = false,
                progressMessage = "",
                lastSyncAtMs = nowMs,
                syncedLikedCount = likedTracks.size,
                syncedPlaylistCount = playlists.size,
                syncedPlaylistTracksCount = playlistTracksTotal,
                syncedRecentlyPlayedCount = recentTracks.size,
                syncedTopArtistsCount = topArtists.size,
                optionalInsightsNeedReconnect = needsInsightsScope,
                lastError = null
            )
            _syncState.value = finalState

            val summaryParts = buildList {
                add("${likedTracks.size} Liked Song${if (likedTracks.size == 1) "" else "s"}")
                add("${playlists.size} Playlist${if (playlists.size == 1) "" else "s"}")
                if (recentTracks.isNotEmpty()) add("${recentTracks.size} Recent Plays")
                if (topArtists.isNotEmpty()) add("${topArtists.size} Top Artists")
            }
            val msg = "Synced ${summaryParts.joinToString(", ")} from Spotify."
            Log.i(TAG, "✅ $msg (playlistTracks=$playlistTracksTotal, needsInsightsScope=$needsInsightsScope)")

            SpotifyLibrarySyncResult(
                success = true,
                likedSongsSynced = likedTracks.size,
                playlistsSynced = playlists.size,
                playlistTracksSynced = playlistTracksTotal,
                recentlyPlayedSynced = recentTracks.size,
                topTracksSynced = topTracks.size,
                topArtistsSynced = topArtists.size,
                optionalInsightsNeedReconnect = needsInsightsScope,
                message = msg
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Spotify library sync failed: ${t.message}", t)
            val errMsg = "Spotify sync hit a snag: ${t.message ?: "Network error"}"
            _syncState.value = previous.copy(
                isSyncing = false,
                progressMessage = "",
                lastError = errMsg
            )
            SpotifyLibrarySyncResult(success = false, message = errMsg)
        } finally {
            syncRunning.set(false)
        }
    }

    private suspend fun mergeTracksIntoCrossAppLibrary(
        dao: com.tunemind.app.data.local.CrossAppLibraryDao,
        tracks: List<SpotifySyncedTrack>,
        markAsLiked: Boolean,
        seedPlayCountIfNew: Int
    ) {
        val now = System.currentTimeMillis()
        val existingTracksByKey = dao.getAllLibraryTracksOnce().associateBy { it.canonicalTrackKey }.toMutableMap()
        val toUpsert = mutableListOf<CrossAppTrackEntity>()
        val visualsToUpsert = mutableListOf<TrackVisualCacheEntity>()
        val spotifyPkg = SpotifyMusicProvider.SPOTIFY_PACKAGE

        tracks.forEach { st ->
            val key = TrackIdentityResolver.buildNormalizedMatchKey(st.title, st.artist)
            if (key.isBlank()) return@forEach

            val existing = existingTracksByKey[key]
            val effectiveTimestamp = st.addedAtMs.takeIf { it > 0L } ?: now
            val merged = if (existing != null) {
                val mergedPackages = existing.sourcePackagesCsv
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toMutableSet()
                    .apply { add(spotifyPkg) }
                    .joinToString(",")
                val resolvedTrackId = if (existing.trackId.startsWith("spotify:track:")) {
                    existing.trackId
                } else {
                    st.spotifyUri
                }
                existing.copy(
                    trackId = resolvedTrackId,
                    album = existing.album.ifBlank { st.album },
                    durationMs = if (existing.durationMs > 0L) existing.durationMs else st.durationMs,
                    sourcePackagesCsv = mergedPackages,
                    primarySourcePackage = existing.primarySourcePackage.takeIf { it.isNotBlank() && it != "unknown_player" }
                        ?: spotifyPkg,
                    isLiked = existing.isLiked || markAsLiked,
                    albumArtUrl = existing.albumArtUrl?.takeIf { it.isNotBlank() } ?: st.albumArtUrl,
                    previewUrl = existing.previewUrl?.takeIf { it.isNotBlank() } ?: st.previewUrl,
                    firstPlayedAtMs = when {
                        existing.firstPlayedAtMs > 0L && effectiveTimestamp > 0L -> minOf(existing.firstPlayedAtMs, effectiveTimestamp)
                        existing.firstPlayedAtMs > 0L -> existing.firstPlayedAtMs
                        else -> effectiveTimestamp
                    },
                    lastPlayedAtMs = maxOf(existing.lastPlayedAtMs, effectiveTimestamp),
                    updatedAtMs = now
                )
            } else {
                CrossAppTrackEntity(
                    canonicalTrackKey = key,
                    trackId = st.spotifyUri,
                    title = st.title,
                    artist = st.artist,
                    album = st.album,
                    genre = "Music",
                    durationMs = st.durationMs,
                    primarySourcePackage = spotifyPkg,
                    sourcePackagesCsv = spotifyPkg,
                    playCount = seedPlayCountIfNew,
                    totalListenedMs = 0L,
                    firstPlayedAtMs = effectiveTimestamp,
                    lastPlayedAtMs = effectiveTimestamp,
                    isLiked = markAsLiked,
                    isInVault = false,
                    albumArtUrl = st.albumArtUrl,
                    previewUrl = st.previewUrl,
                    updatedAtMs = now
                )
            }

            existingTracksByKey[key] = merged
            toUpsert.add(merged)

            if (!st.albumArtUrl.isNullOrBlank()) {
                visualsToUpsert.add(
                    TrackVisualCacheEntity(
                        trackKey = key,
                        trackId = st.spotifyUri,
                        title = st.title,
                        artist = st.artist,
                        artistId = TrackIdentityResolver.slugifyPart(st.artist),
                        albumName = st.album.takeIf { it.isNotBlank() },
                        albumArtUrl = st.albumArtUrl,
                        artistImageUrl = st.albumArtUrl,
                        popularity = st.popularity,
                        previewUrl = st.previewUrl,
                        updatedAtMs = now
                    )
                )
            }
        }

        if (toUpsert.isNotEmpty()) {
            // Keep latest merged copy per key so REPLACE doesn't revert earlier fields in same batch
            val dedupedTracks = toUpsert.associateBy { it.canonicalTrackKey }.values.toList()
            dao.upsertLibraryTracks(dedupedTracks)
        }
        if (visualsToUpsert.isNotEmpty()) {
            val dedupedVisuals = visualsToUpsert.associateBy { it.trackKey }.values.toList()
            dao.upsertTrackVisuals(dedupedVisuals)
        }
    }

    private suspend fun upsertSpotifyPlaylists(
        playlistDao: com.tunemind.app.data.local.TuneMindPlaylistDao,
        crossAppDao: com.tunemind.app.data.local.CrossAppLibraryDao,
        playlists: List<SpotifySyncedPlaylist>
    ): Int {
        val now = System.currentTimeMillis()
        val visualsToUpsert = mutableListOf<TrackVisualCacheEntity>()
        var totalTracksInserted = 0

        playlists.forEachIndexed { plIndex, pl ->
            val localPlaylistId = "$SPOTIFY_PLAYLIST_ID_PREFIX${pl.spotifyId}"
            val cleanDesc = pl.description.replace(Regex("<[^>]*>"), "").trim()
                .ifBlank {
                    if (pl.ownerName.isNotBlank()) "Synced from Spotify · by ${pl.ownerName}" else "Synced from Spotify"
                }

            val playlistEntity = TuneMindPlaylistEntity(
                playlistId = localPlaylistId,
                name = pl.name,
                description = cleanDesc,
                moodOrAudience = "Spotify",
                createdAtMs = now - (plIndex * 1000L),
                updatedAtMs = now - (plIndex * 500L)
            )
            playlistDao.upsertPlaylist(playlistEntity)

            // Replace track entries for this synced Spotify playlist cleanly so re-syncing stays 100% duplicate-free
            playlistDao.deleteTracksByPlaylistId(localPlaylistId)
            val trackEntities = pl.tracks.mapIndexed { idx, tr ->
                val visualKey = TrackIdentityResolver.buildNormalizedMatchKey(tr.title, tr.artist)
                if (!tr.albumArtUrl.isNullOrBlank() && visualKey.isNotBlank()) {
                    visualsToUpsert.add(
                        TrackVisualCacheEntity(
                            trackKey = visualKey,
                            trackId = tr.spotifyUri,
                            title = tr.title,
                            artist = tr.artist,
                            artistId = TrackIdentityResolver.slugifyPart(tr.artist),
                            albumName = tr.album.takeIf { it.isNotBlank() },
                            albumArtUrl = tr.albumArtUrl,
                            artistImageUrl = tr.albumArtUrl,
                            popularity = tr.popularity,
                            previewUrl = tr.previewUrl,
                            updatedAtMs = now
                        )
                    )
                }
                TuneMindPlaylistTrackEntity(
                    entryId = "${localPlaylistId}::${idx}::${tr.spotifyId}",
                    playlistId = localPlaylistId,
                    title = tr.title,
                    artist = tr.artist,
                    externalUri = tr.spotifyUri,
                    trackId = tr.spotifyUri,
                    sourcePlayer = SpotifyMusicProvider.SPOTIFY_PACKAGE,
                    positionIndex = idx,
                    addedAtMs = tr.addedAtMs.takeIf { it > 0L } ?: now
                )
            }
            if (trackEntities.isNotEmpty()) {
                playlistDao.upsertPlaylistTracks(trackEntities)
                totalTracksInserted += trackEntities.size
            }
        }

        if (visualsToUpsert.isNotEmpty()) {
            crossAppDao.upsertTrackVisuals(visualsToUpsert.associateBy { it.trackKey }.values.toList())
        }
        return totalTracksInserted
    }

    private suspend fun upsertTopArtistsToVisualCache(
        dao: com.tunemind.app.data.local.CrossAppLibraryDao,
        artists: List<SpotifySyncedArtist>
    ) {
        val now = System.currentTimeMillis()
        val entities = artists.mapNotNull { a ->
            val key = UniversalMusicVisualRepository.normalizeArtistKey(a.name)
            if (key.isBlank()) return@mapNotNull null
            ArtistProfileCacheEntity(
                artistKey = key,
                artistId = a.spotifyId,
                artistName = a.name,
                imageUrl = a.imageUrl,
                genresCsv = a.genres.joinToString(","),
                popularity = a.popularity,
                followersTotal = a.followersTotal,
                updatedAtMs = now
            )
        }
        if (entities.isNotEmpty()) {
            dao.upsertArtistProfiles(entities.associateBy { it.artistKey }.values.toList())
        }
    }
}
