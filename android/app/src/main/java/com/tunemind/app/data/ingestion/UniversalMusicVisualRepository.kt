package com.tunemind.app.data.ingestion

import android.content.Context
import android.util.Log
import com.tunemind.app.data.local.ArtistProfileCacheEntity
import com.tunemind.app.data.local.CrossAppArtistInsight
import com.tunemind.app.data.local.CrossAppGenreWeight
import com.tunemind.app.data.local.CrossAppLibraryDao
import com.tunemind.app.data.local.CrossAppTasteShiftSummary
import com.tunemind.app.data.local.CrossAppTrackEntity
import com.tunemind.app.data.local.TrackVisualCacheEntity
import com.tunemind.app.data.local.TuneMindDatabase
import com.tunemind.app.domain.model.TrackIdentityResolver
import com.tunemind.app.provider.UniversalTrackResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Open-catalog Artwork, Playable Stream, & Artist Profile Visual Repository.
 *
 * Replaces Spotify Web API visual lookups cleanly using zero-key Deezer, iTunes,
 * Audius, and TuneMind backend open-catalog APIs, caching results persistently in
 * Room (`universal_track_visual_cache` + `universal_artist_profile_cache` +
 * enriching `cross_app_track_library` rows).
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class UniversalMusicVisualRepository private constructor(private val context: Context) {

    private val appContext = context.applicationContext
    private val db: TuneMindDatabase get() = TuneMindDatabase.getInstance(appContext)
    private val dao: CrossAppLibraryDao get() = db.crossAppLibraryDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightKeys = ConcurrentHashMap.newKeySet<String>()
    private val resolvedWithArtKeys = ConcurrentHashMap.newKeySet<String>()

    val trackVisualsMapFlow: StateFlow<Map<String, TrackVisualCacheEntity>> by lazy {
        com.tunemind.app.data.auth.TuneMindAuthSessionManager.getInstance(appContext)
            .activePartitionKeyFlow
            .flatMapLatest {
                dao.observeAllTrackVisuals()
            }
            .debounce(350L)
            .map { list ->
                buildMap(list.size * 2) {
                    for (item in list) {
                        put(item.trackKey, item)
                        if (!item.albumArtUrl.isNullOrBlank()) {
                            resolvedWithArtKeys.add(item.trackKey)
                        }
                        item.trackId?.takeIf { it.isNotBlank() }?.let { put(it, item) }
                    }
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000L), emptyMap())
    }

    val artistProfilesMapFlow: StateFlow<Map<String, ArtistProfileCacheEntity>> by lazy {
        com.tunemind.app.data.auth.TuneMindAuthSessionManager.getInstance(appContext)
            .activePartitionKeyFlow
            .flatMapLatest {
                dao.observeAllArtistProfiles()
            }
            .debounce(350L)
            .map { list ->
                list.associateBy { it.artistKey }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000L), emptyMap())
    }

    fun observeTrackVisualsMap(): Flow<Map<String, TrackVisualCacheEntity>> = trackVisualsMapFlow

    fun observeArtistProfilesMap(): Flow<Map<String, ArtistProfileCacheEntity>> = artistProfilesMapFlow

    fun enqueueVisualEnrichment(tracks: List<Pair<String, String>>) {
        if (tracks.isEmpty()) return
        val quickCandidates = tracks.mapNotNull { (title, artist) ->
            val cleanTitle = title.trim()
            val cleanArtist = artist.trim()
            if (cleanTitle.isBlank()) return@mapNotNull null
            val key = TrackIdentityResolver.buildNormalizedMatchKey(cleanTitle, cleanArtist)
            if (resolvedWithArtKeys.contains(key) || inFlightKeys.contains(key)) return@mapNotNull null
            Triple(key, cleanTitle, cleanArtist)
        }.distinctBy { it.first }
        if (quickCandidates.isEmpty()) return

        scope.launch {
            val existing = trackVisualsMapFlow.value.ifEmpty {
                runCatching { dao.getTrackVisualSnapshot().associateBy { it.trackKey } }.getOrDefault(emptyMap())
            }
            val pending = quickCandidates
                .filter { (key, _, _) ->
                    val cached = existing[key]
                    if (cached != null && !cached.albumArtUrl.isNullOrBlank()) {
                        resolvedWithArtKeys.add(key)
                        false
                    } else {
                        true
                    }
                }
                .take(12)

            for ((key, title, artist) in pending) {
                if (!inFlightKeys.add(key)) continue
                try {
                    val resolved = UniversalTrackResolver.resolveTrackBlocking(appContext, title, artist)
                    if (resolved != null) {
                        val nowMs = System.currentTimeMillis()
                        val entity = TrackVisualCacheEntity(
                            trackKey = key,
                            trackId = resolved.trackId,
                            title = resolved.cleanTitle,
                            artist = resolved.cleanArtist,
                            artistId = TrackIdentityResolver.slugifyPart(resolved.cleanArtist),
                            albumName = resolved.albumName,
                            albumArtUrl = resolved.albumArtUrl,
                            artistImageUrl = resolved.artistImageUrl ?: resolved.albumArtUrl,
                            genresCsv = resolved.genres.joinToString(","),
                            popularity = resolved.popularity,
                            previewUrl = resolved.previewUrl,
                            audiusStreamUrl = resolved.audiusStreamUrl,
                            ytmVideoId = resolved.ytmVideoId,
                            deezerUrl = resolved.deezerUrl,
                            updatedAtMs = nowMs
                        )
                        dao.upsertTrackVisual(entity)
                        if (resolved.cleanArtist.isNotBlank()) {
                            val artistKey = normalizeArtistKey(resolved.cleanArtist)
                            dao.upsertArtistProfile(
                                ArtistProfileCacheEntity(
                                    artistKey = artistKey,
                                    artistId = TrackIdentityResolver.slugifyPart(resolved.cleanArtist),
                                    artistName = resolved.cleanArtist,
                                    imageUrl = resolved.artistImageUrl ?: resolved.albumArtUrl,
                                    genresCsv = resolved.genres.joinToString(","),
                                    popularity = resolved.popularity,
                                    followersTotal = 0L,
                                    updatedAtMs = nowMs
                                )
                            )
                        }
                        // Also enrich matching library entry permanently!
                        dao.updateTrackMediaLinks(
                            canonicalTrackKey = key,
                            albumArtUrl = resolved.albumArtUrl,
                            previewUrl = resolved.previewUrl,
                            audiusStreamUrl = resolved.audiusStreamUrl,
                            ytmVideoId = resolved.ytmVideoId,
                            deezerUrl = resolved.deezerUrl,
                            genre = resolved.genres.firstOrNull(),
                            updatedAtMs = nowMs
                        )
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Visual resolve notice for '$title': ${e.message}")
                } finally {
                    inFlightKeys.remove(key)
                }
            }
        }
    }

    fun computeTasteShiftSummary(
        libraryTracks: List<CrossAppTrackEntity>,
        visualsByKey: Map<String, TrackVisualCacheEntity>,
        artistsByKey: Map<String, ArtistProfileCacheEntity>
    ): CrossAppTasteShiftSummary {
        if (libraryTracks.isEmpty()) return CrossAppTasteShiftSummary()

        val playedTracks = libraryTracks.filter { it.playCount > 0 }.ifEmpty { libraryTracks }

        // Aggregate genre weights from real resolved tags + library genres
        val genreCounts = mutableMapOf<String, Int>()
        for (track in playedTracks) {
            val visual = visualsByKey[track.canonicalTrackKey]
            val genres = visual?.genresCsv?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() && it != "Music" }
                ?.ifEmpty { listOfNotNull(track.genre.takeIf { it.isNotBlank() && it != "Music" }) }
                ?: listOfNotNull(track.genre.takeIf { it.isNotBlank() && it != "Music" })
            val weight = track.playCount.coerceAtLeast(1)
            for (g in genres) {
                genreCounts[g] = (genreCounts[g] ?: 0) + weight
            }
        }

        val totalGenreWeight = genreCounts.values.sum().coerceAtLeast(1)
        val topGenres = genreCounts.entries
            .sortedByDescending { it.value }
            .take(6)
            .map { (genre, cnt) ->
                CrossAppGenreWeight(
                    genre = genre,
                    count = cnt,
                    sharePercent = ((cnt * 100f) / totalGenreWeight).toInt().coerceIn(1, 100)
                )
            }

        val artistAgg = playedTracks
            .filter { it.artist.isNotBlank() && !it.artist.equals("Unknown Artist", ignoreCase = true) }
            .groupBy { normalizeArtistKey(it.artist) }
            .map { (aKey, tracks) ->
                val primary = tracks.maxByOrNull { it.lastPlayedAtMs } ?: tracks.first()
                val profile = artistsByKey[aKey]
                val visual = tracks.firstNotNullOfOrNull { visualsByKey[it.canonicalTrackKey] }
                val totalPlays = tracks.sumOf { it.playCount.coerceAtLeast(1) }
                val recentPlays = tracks.count { (System.currentTimeMillis() - it.lastPlayedAtMs) <= 7L * 86_400_000L }
                CrossAppArtistInsight(
                    artistId = profile?.artistId ?: TrackIdentityResolver.slugifyPart(primary.artist),
                    name = profile?.artistName ?: primary.artist,
                    imageUrl = profile?.imageUrl ?: visual?.artistImageUrl ?: visual?.albumArtUrl ?: primary.albumArtUrl,
                    genres = profile?.genresCsv?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                        ?: listOfNotNull(primary.genre.takeIf { it.isNotBlank() }),
                    popularity = profile?.popularity ?: visual?.popularity ?: 72,
                    totalPlays = totalPlays,
                    primaryPlayerPackage = primary.primarySourcePackage
                ) to recentPlays
            }

        val rising = artistAgg
            .sortedWith(compareByDescending<Pair<CrossAppArtistInsight, Int>> { it.second }.thenByDescending { it.first.totalPlays })
            .take(5)
            .map { it.first }

        val consistent = artistAgg
            .sortedByDescending { it.first.totalPlays }
            .take(6)
            .map { it.first }

        return CrossAppTasteShiftSummary(
            topGenres = topGenres,
            risingArtists = rising,
            consistentArtists = consistent,
            emergingGenres = topGenres.drop(2).take(3).map { it.genre }
        )
    }

    companion object {
        private const val TAG = "UniversalMusicVisualRepo"

        @Volatile
        private var INSTANCE: UniversalMusicVisualRepository? = null

        fun getInstance(context: Context): UniversalMusicVisualRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UniversalMusicVisualRepository(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun normalizeTrackKey(title: String?, artist: String?): String =
            TrackIdentityResolver.buildNormalizedMatchKey(title, artist)

        fun normalizeArtistKey(artist: String?): String {
            return artist.orEmpty()
                .trim()
                .lowercase(Locale.ROOT)
                .split(Regex("\\s*(,|&|feat\\.?|ft\\.?|\\bx\\b)\\s*"), limit = 2)
                .firstOrNull()
                .orEmpty()
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
        }
    }
}
