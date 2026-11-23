package com.tunemind.app.provider

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tunemind.app.domain.model.TrackIdentityResolver
import com.tunemind.app.domain.network.BackendEndpointManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class ResolvedPlayableLinks(
    val canonicalTrackKey: String,
    val trackId: String,
    val cleanTitle: String,
    val cleanArtist: String,
    val albumName: String? = null,
    val albumArtUrl: String? = null,
    val artistImageUrl: String? = null,
    val genres: List<String> = emptyList(),
    val popularity: Int = 72,
    val previewUrl: String? = null,
    val audiusStreamUrl: String? = null,
    val ytmVideoId: String? = null,
    val deezerUrl: String? = null,
    val spotifyUri: String? = null,
    val spotifyUrl: String? = null,
    val audiomackUrl: String? = null,
    val source: String = "open_catalog"
)

data class OpenCatalogSearchTrack(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val albumArtUrl: String? = null,
    val genre: String = "Music",
    val previewUrl: String? = null,
    val audiusStreamUrl: String? = null,
    val ytmVideoId: String? = null,
    val deezerUrl: String? = null,
    val itunesUrl: String? = null,
    val popularity: Int = 70,
    val isExplicit: Boolean = false
)

/**
 * Zero-API-key Cross-App Catalog & Playable Stream Resolver.
 *
 * Combines TuneMind's backend open-catalog endpoint (`/api/v1/music/resolve-playable`,
 * `/api/v1/music/search`) with resilient on-device public catalog lookups (Audius full streams,
 * Deezer 30s previews & high-res art, iTunes 30s previews & real genre taxonomy) without any
 * Spotify Developer API credentials or OAuth tokens.
 */
object UniversalTrackResolver {
    private const val TAG = "UniversalTrackResolver"

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val resolvedCache = ConcurrentHashMap<String, ResolvedPlayableLinks>()

    fun normalizeTitleForExactMatch(raw: String): String {
        return raw.lowercase()
            .replace(Regex("\\([^)]*\\)"), "")
            .replace(Regex("\\[[^\\]]*\\]"), "")
            .replace(Regex(",\\s*tenzi\\s*no\\.?\\s*\\d+\\.?"), "")
            .replace(Regex("\\s+-\\s+.*$"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun isStrictTitleArtistMatch(
        expectedTitle: String,
        expectedArtist: String,
        candidateTitle: String,
        candidateArtist: String
    ): Boolean {
        val normExpTitle = normalizeTitleForExactMatch(expectedTitle)
        val normCandTitle = normalizeTitleForExactMatch(candidateTitle)
        if (normExpTitle.isBlank() || normCandTitle.isBlank()) return false

        val versionTags = listOf("remix", "karaoke", "tribute", "cover", "instrumental", "acapella", "sped up", "slowed")
        val expLower = expectedTitle.lowercase()
        val candLower = candidateTitle.lowercase()
        if (versionTags.any { tag -> candLower.contains(tag) && !expLower.contains(tag) }) {
            return false
        }

        val titleMatched = normCandTitle == normExpTitle ||
            (normExpTitle.length >= 5 && (normCandTitle.contains(normExpTitle) || normExpTitle.includesWordOrContained(normCandTitle)))
        val expWords = normExpTitle.split(" ").filter { it.length >= 2 }.toSet()
        val candWords = normCandTitle.split(" ").filter { it.length >= 2 }.toSet()
        val overlap = if (expWords.isNotEmpty() && candWords.isNotEmpty()) {
            expWords.count { candWords.contains(it) }.toFloat() / maxOf(expWords.size, candWords.size).toFloat()
        } else 0f

        if (!titleMatched && overlap < 0.72f) return false

        val expPrimaryArtist = expectedArtist
            .split(Regex("\\s*(?:,|&|feat\\.?|ft\\.?|featuring|with|\\bx\\b)\\s*", RegexOption.IGNORE_CASE))
            .firstOrNull()
            .orEmpty()
        val normExpArtist = normalizeTitleForExactMatch(expPrimaryArtist.ifBlank { expectedArtist })
        val normFullExpArtist = normalizeTitleForExactMatch(expectedArtist)
        val normCandArtist = normalizeTitleForExactMatch(candidateArtist)
        if (normExpArtist.isBlank()) return titleMatched || overlap >= 0.80f
        if (normCandArtist.isBlank()) return false

        if (normCandArtist == normExpArtist ||
            normCandArtist == normFullExpArtist ||
            (normExpArtist.length >= 3 && (normCandArtist.contains(normExpArtist) || normExpArtist.contains(normCandArtist)))
        ) {
            return true
        }
        val expArtistWords = normExpArtist.split(" ").filter { it.length >= 2 }.toSet()
        val candArtistWords = normCandArtist.split(" ").filter { it.length >= 2 }.toSet()
        if (expArtistWords.isEmpty() || candArtistWords.isEmpty()) return false
        val artistRatio = expArtistWords.count { candArtistWords.contains(it) }.toFloat() /
            maxOf(expArtistWords.size, candArtistWords.size).toFloat()
        return artistRatio >= 0.75f
    }

    private fun String.includesWordOrContained(other: String): Boolean = this.contains(other)

    fun getCached(title: String, artist: String): ResolvedPlayableLinks? {
        val key = TrackIdentityResolver.buildNormalizedMatchKey(title, artist)
        return resolvedCache[key]
    }

    /**
     * Resolves full stream links (Audius), YouTube Music watch ID, Deezer URL, 30s preview URL,
     * album artwork, and real genre tags cleanly. Must be called from a background/IO thread.
     */
    fun resolveTrackBlocking(
        context: Context,
        cleanTitle: String,
        cleanArtist: String = "",
        fullQuery: String = ""
    ): ResolvedPlayableLinks? {
        val t = cleanTitle.trim()
        val a = cleanArtist.trim()
        if (t.isBlank() && fullQuery.isBlank()) return null

        val matchKey = TrackIdentityResolver.buildNormalizedMatchKey(t.ifBlank { fullQuery }, a)
        resolvedCache[matchKey]?.let { return it }

        // 1. Ask TuneMind backend open-catalog stack (parallel Deezer + iTunes + Audius + YT Data API v3)
        resolveViaBackend(context, t.ifBlank { fullQuery }, a)?.let { hit ->
            resolvedCache[matchKey] = hit
            return hit
        }

        // 2. Direct zero-key on-device fallback (iTunes + Audius) when offline from backend / fast path
        val q = if (a.isNotBlank() && !t.contains(a, ignoreCase = true)) "$t $a" else t.ifBlank { fullQuery }
        val itunes = fetchItunesSingle(q, t, a)
        val audiusStream = fetchAudiusStreamUrl(t, a, q)

        if (itunes != null || !audiusStream.isNullOrBlank()) {
            val songlink = resolveLinksViaSonglink(listOfNotNull(itunes?.itunesUrl))
            val resolved = ResolvedPlayableLinks(
                canonicalTrackKey = matchKey,
                trackId = TrackIdentityResolver.buildDeterministicTrackId(
                    itunes?.title ?: t,
                    itunes?.artist ?: a
                ),
                cleanTitle = itunes?.title ?: t,
                cleanArtist = itunes?.artist ?: a,
                albumName = itunes?.album,
                albumArtUrl = itunes?.albumArtUrl,
                artistImageUrl = itunes?.albumArtUrl,
                genres = listOfNotNull(itunes?.genre?.takeIf { it.isNotBlank() && it != "Music" }).ifEmpty { listOf("Music") },
                popularity = 74,
                previewUrl = itunes?.previewUrl,
                audiusStreamUrl = audiusStream,
                ytmVideoId = songlink?.ytmVideoId,
                deezerUrl = null,
                spotifyUri = songlink?.spotifyUri,
                spotifyUrl = songlink?.spotifyUrl,
                audiomackUrl = songlink?.audiomackUrl,
                source = if (!audiusStream.isNullOrBlank()) "audius+open" else "itunes+songlink"
            )
            resolvedCache[matchKey] = resolved
            return resolved
        }

        return null
    }

    /**
     * Resolves best available in-app stream URL: Audius full stream first, then Deezer/iTunes 30s preview.
     */
    fun fetchPlayableStreamOrPreviewBlocking(
        context: Context,
        title: String,
        artist: String = ""
    ): Pair<String, Boolean>? {
        val resolved = resolveTrackBlocking(context, title, artist) ?: return null
        resolved.audiusStreamUrl?.takeIf { it.startsWith("http") }?.let {
            return it to true // true = full track stream!
        }
        resolved.previewUrl?.takeIf { it.startsWith("http") }?.let {
            return it to false // false = 30s preview clip
        }
        return null
    }

    /**
     * Searches TuneMind's Open Music Catalog (`/api/v1/music/search`) with direct iTunes/Deezer fallback.
     */
    fun searchCatalogBlocking(
        context: Context,
        query: String,
        searchType: String = "track",
        limit: Int = 20,
        excludeExplicit: Boolean = false
    ): List<OpenCatalogSearchTrack> {
        val cleanQ = query.trim()
        if (cleanQ.isBlank()) return emptyList()

        // 1. Backend /api/v1/music/search
        try {
            val baseUrl = BackendEndpointManager.getActiveHttpBaseUrl(context).trimEnd('/')
            val url = "$baseUrl/api/v1/music/search?q=${Uri.encode(cleanQ)}&type=${Uri.encode(searchType)}&limit=$limit" +
                if (excludeExplicit) "&kids=true" else ""
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bodyStr = resp.body?.string().orEmpty()
                    val json = JSONObject(bodyStr)
                    val arr = json.optJSONArray("tracks")
                    if (arr != null && arr.length() > 0) {
                        val out = mutableListOf<OpenCatalogSearchTrack>()
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            val item = OpenCatalogSearchTrack(
                                trackId = obj.optString("trackId").ifBlank {
                                    TrackIdentityResolver.buildDeterministicTrackId(
                                        obj.optString("title"),
                                        obj.optString("artist")
                                    )
                                },
                                title = obj.optString("title"),
                                artist = obj.optString("artist"),
                                album = obj.optString("album").takeIf { it.isNotBlank() && it != "null" },
                                albumArtUrl = obj.optString("albumArtUrl").takeIf { it.startsWith("http") },
                                genre = obj.optString("genre").ifBlank { "Music" },
                                previewUrl = obj.optString("previewUrl").takeIf { it.startsWith("http") },
                                audiusStreamUrl = obj.optString("audiusStreamUrl").takeIf { it.startsWith("http") },
                                ytmVideoId = obj.optString("ytmVideoId").takeIf { it.isNotBlank() && it != "null" },
                                deezerUrl = obj.optString("deezerUrl").takeIf { it.startsWith("http") },
                                popularity = obj.optInt("popularity", 72),
                                isExplicit = obj.optBoolean("isExplicit", false)
                            )
                            if (item.title.isNotBlank() && (!excludeExplicit || !item.isExplicit)) {
                                out.add(item)
                            }
                        }
                        if (out.isNotEmpty()) return out
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Backend catalog search fallback triggered: ${e.message}")
        }

        // 2. Direct device iTunes fallback
        val results = mutableListOf<OpenCatalogSearchTrack>()
        val seenKeys = mutableSetOf<String>()

        fetchItunesList(cleanQ, limit).forEach { track ->
            if (excludeExplicit && track.isExplicit) return@forEach
            val key = TrackIdentityResolver.buildNormalizedMatchKey(track.title, track.artist)
            if (seenKeys.add(key)) results.add(track)
        }

        return results.take(limit)
    }

    private fun resolveViaBackend(
        context: Context,
        title: String,
        artist: String
    ): ResolvedPlayableLinks? {
        return try {
            val baseUrl = BackendEndpointManager.getActiveHttpBaseUrl(context).trimEnd('/')
            val url = "$baseUrl/api/v1/music/resolve-playable?title=${Uri.encode(title)}&artist=${Uri.encode(artist)}"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string().orEmpty())
                val resolvedObj = json.optJSONObject("profile")
                    ?: json.optJSONObject("resolved")
                    ?: return null
                val cleanTitle = resolvedObj.optString("cleanTitle").ifBlank { title }
                val cleanArtist = resolvedObj.optString("cleanArtist").ifBlank { artist }
                if (title.isNotBlank() && !isStrictTitleArtistMatch(title, artist, cleanTitle, cleanArtist)) {
                    return null
                }
                val genresArr = resolvedObj.optJSONArray("genres")
                val genresList = mutableListOf<String>()
                if (genresArr != null) {
                    for (i in 0 until genresArr.length()) {
                        genresArr.optString(i).takeIf { it.isNotBlank() }?.let { genresList.add(it) }
                    }
                }
                val deezerLink = resolvedObj.optString("deezerUrl").takeIf { it.startsWith("http") }
                var ytmId = resolvedObj.optString("ytmVideoId").takeIf { it.isNotBlank() && it != "null" }
                val ytmWatchUrl = ytmId?.let { "https://music.youtube.com/watch?v=$it" }
                var spUri = resolvedObj.optString("spotifyUri").takeIf { it.startsWith("spotify:track:") }
                var spUrl = resolvedObj.optString("spotifyUrl").takeIf { it.startsWith("http") }
                var audiomackUrl: String? = null
                if (spUri == null || ytmId == null) {
                    val songlink = resolveLinksViaSonglink(listOfNotNull(deezerLink, ytmWatchUrl))
                    spUri = spUri ?: songlink?.spotifyUri
                    spUrl = spUrl ?: songlink?.spotifyUrl
                    ytmId = ytmId ?: songlink?.ytmVideoId
                    audiomackUrl = songlink?.audiomackUrl
                }
                ResolvedPlayableLinks(
                    canonicalTrackKey = resolvedObj.optString("trackKey")
                        .ifBlank { TrackIdentityResolver.buildNormalizedMatchKey(cleanTitle, cleanArtist) },
                    trackId = resolvedObj.optString("trackId")
                        .ifBlank { TrackIdentityResolver.buildDeterministicTrackId(cleanTitle, cleanArtist) },
                    cleanTitle = cleanTitle,
                    cleanArtist = cleanArtist,
                    albumName = resolvedObj.optString("albumName").takeIf { it.isNotBlank() && it != "null" },
                    albumArtUrl = resolvedObj.optString("albumArtUrl").takeIf { it.startsWith("http") },
                    artistImageUrl = resolvedObj.optString("artistImageUrl").takeIf { it.startsWith("http") },
                    genres = genresList,
                    popularity = resolvedObj.optInt("popularity", 74),
                    previewUrl = resolvedObj.optString("previewUrl").takeIf { it.startsWith("http") },
                    audiusStreamUrl = resolvedObj.optString("audiusStreamUrl").takeIf { it.startsWith("http") },
                    ytmVideoId = ytmId,
                    deezerUrl = deezerLink,
                    spotifyUri = spUri,
                    spotifyUrl = spUrl,
                    audiomackUrl = audiomackUrl,
                    source = resolvedObj.optString("source").ifBlank { "backend_open_catalog" }
                )
            }
        } catch (e: Exception) {
            Log.d(TAG, "Backend resolve-playable skipped (${e.message}), using on-device catalog fallback.")
            null
        }
    }

    // Deezer removed by product decision — on-device open-catalog fallback uses iTunes, Audius, and Songlink.

    private fun fetchItunesList(query: String, limit: Int): List<OpenCatalogSearchTrack> {
        return try {
            val url = "https://itunes.apple.com/search?term=${Uri.encode(query)}&media=music&entity=song&limit=$limit"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("results") ?: return emptyList()
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val title = obj.optString("trackName").trim()
                        val artist = obj.optString("artistName").trim()
                        if (title.isBlank() || artist.isBlank()) continue
                        val art100 = obj.optString("artworkUrl100").trim()
                        val art600 = if (art100.contains("100x100")) art100.replace("100x100", "600x600") else art100
                        add(
                            OpenCatalogSearchTrack(
                                trackId = TrackIdentityResolver.buildDeterministicTrackId(title, artist),
                                title = title,
                                artist = artist,
                                album = obj.optString("collectionName").takeIf { it.isNotBlank() },
                                albumArtUrl = art600.takeIf { it.startsWith("http") },
                                genre = obj.optString("primaryGenreName").ifBlank { "Music" },
                                previewUrl = obj.optString("previewUrl").takeIf { it.startsWith("http") },
                                itunesUrl = obj.optString("trackViewUrl").takeIf { it.startsWith("http") },
                                popularity = 76,
                                isExplicit = obj.optString("trackExplicitness").equals("explicit", ignoreCase = true)
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fetchItunesSingle(query: String, title: String, artist: String): OpenCatalogSearchTrack? {
        val list = fetchItunesList(query, 8)
        return list.firstOrNull { isStrictTitleArtistMatch(title, artist, it.title, it.artist) }
    }

    private fun fetchAudiusStreamUrl(title: String, artist: String, query: String): String? {
        return try {
            val url = "https://api.audius.co/v1/tracks/search?query=${Uri.encode(query)}&app_name=TuneMind"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("data") ?: return null
                for (i in 0 until minOf(arr.length(), 8)) {
                    val obj = arr.optJSONObject(i) ?: continue
                    if (!obj.optBoolean("is_streamable", true)) continue
                    val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val hitTitle = obj.optString("title")
                    val hitArtist = obj.optJSONObject("user")?.optString("name").orEmpty()
                    if (isStrictTitleArtistMatch(title, artist, hitTitle, hitArtist)) {
                        return "https://api.audius.co/v1/tracks/$id/stream?app_name=TuneMind"
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private data class SonglinkResult(
        val spotifyUri: String?,
        val spotifyUrl: String?,
        val audiomackUrl: String?,
        val ytmVideoId: String? = null
    )

    private val songlinkCache = ConcurrentHashMap<String, SonglinkResult>()

    internal fun extractYouTubeVideoIdFromUrl(rawUrl: String?): String? {
        val clean = rawUrl?.trim().orEmpty()
        if (clean.isBlank()) return null
        Regex("[?&]v=([A-Za-z0-9_-]{11})", RegexOption.IGNORE_CASE).find(clean)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("youtu\\.be/([A-Za-z0-9_-]{11})", RegexOption.IGNORE_CASE).find(clean)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("/(?:embed|shorts)/([A-Za-z0-9_-]{11})", RegexOption.IGNORE_CASE).find(clean)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    private fun resolveLinksViaSonglink(sourceUrls: List<String?>): SonglinkResult? {
        var bestSpotifyUri: String? = null
        var bestSpotifyUrl: String? = null
        var bestAudiomackUrl: String? = null
        var bestYtmVideoId: String? = null

        for (rawUrl in sourceUrls) {
            val clean = rawUrl?.trim()?.takeIf { it.startsWith("http") } ?: continue
            val cached = songlinkCache[clean]
            if (cached != null) {
                bestSpotifyUri = bestSpotifyUri ?: cached.spotifyUri
                bestSpotifyUrl = bestSpotifyUrl ?: cached.spotifyUrl
                bestAudiomackUrl = bestAudiomackUrl ?: cached.audiomackUrl
                bestYtmVideoId = bestYtmVideoId ?: cached.ytmVideoId
                if ((bestSpotifyUri != null || bestSpotifyUrl != null) && bestYtmVideoId != null) {
                    return SonglinkResult(bestSpotifyUri, bestSpotifyUrl, bestAudiomackUrl, bestYtmVideoId)
                }
                continue
            }
            try {
                val url = "https://api.song.link/v1-alpha.1/links?url=${Uri.encode(clean)}"
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val json = JSONObject(resp.body?.string().orEmpty())
                    val platforms = json.optJSONObject("linksByPlatform")
                    val spUrl = platforms?.optJSONObject("spotify")
                        ?.optString("url")
                        ?.trim()
                        ?.takeIf { it.startsWith("http") }
                    val audiomackUrl = platforms?.optJSONObject("audiomack")
                        ?.optString("url")
                        ?.trim()
                        ?.takeIf { it.startsWith("http") }
                    val ytmId = extractYouTubeVideoIdFromUrl(
                        platforms?.optJSONObject("youtubeMusic")?.optString("url")
                    ) ?: extractYouTubeVideoIdFromUrl(
                        platforms?.optJSONObject("youtube")?.optString("url")
                    )
                    val idMatch = spUrl?.let { Regex("/track/([A-Za-z0-9]{22})", RegexOption.IGNORE_CASE).find(it) }
                    val spUri = idMatch?.groupValues?.getOrNull(1)?.let { "spotify:track:$it" }
                    val canonicalUrl = idMatch?.groupValues?.getOrNull(1)?.let { "https://open.spotify.com/track/$it" } ?: spUrl
                    val result = SonglinkResult(spUri, canonicalUrl, audiomackUrl, ytmId)
                    if (spUri != null || canonicalUrl != null || audiomackUrl != null || ytmId != null) {
                        songlinkCache[clean] = result
                    }
                    bestSpotifyUri = bestSpotifyUri ?: spUri
                    bestSpotifyUrl = bestSpotifyUrl ?: canonicalUrl
                    bestAudiomackUrl = bestAudiomackUrl ?: audiomackUrl
                    bestYtmVideoId = bestYtmVideoId ?: ytmId
                    if ((bestSpotifyUri != null || bestSpotifyUrl != null) && bestYtmVideoId != null) {
                        return SonglinkResult(bestSpotifyUri, bestSpotifyUrl, bestAudiomackUrl, bestYtmVideoId)
                    }
                }
            } catch (_: Exception) {
                // Transient error: never cache negative result forever so next attempt succeeds.
            }
        }
        return if (bestSpotifyUri != null || bestSpotifyUrl != null || bestAudiomackUrl != null || bestYtmVideoId != null) {
            SonglinkResult(bestSpotifyUri, bestSpotifyUrl, bestAudiomackUrl, bestYtmVideoId)
        } else {
            null
        }
    }
}
