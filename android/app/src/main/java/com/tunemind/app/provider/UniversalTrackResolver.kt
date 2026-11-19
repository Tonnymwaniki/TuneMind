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

        // 2. Direct zero-key on-device fallback (Deezer + iTunes + Audius) when offline from backend / fast path
        val q = if (a.isNotBlank() && !t.contains(a, ignoreCase = true)) "$t $a" else t.ifBlank { fullQuery }
        val deezer = fetchDeezerSingle(q, t, a)
        val itunes = fetchItunesSingle(q, t, a)
        val audiusStream = fetchAudiusStreamUrl(t, a, q)

        if (deezer != null || itunes != null || !audiusStream.isNullOrBlank()) {
            val songlinkSpotify = deezer?.deezerUrl?.let { resolveSpotifyViaSonglink(it) }
            val resolved = ResolvedPlayableLinks(
                canonicalTrackKey = matchKey,
                trackId = TrackIdentityResolver.buildDeterministicTrackId(
                    deezer?.title ?: itunes?.title ?: t,
                    deezer?.artist ?: itunes?.artist ?: a
                ),
                cleanTitle = deezer?.title ?: itunes?.title ?: t,
                cleanArtist = deezer?.artist ?: itunes?.artist ?: a,
                albumName = deezer?.album ?: itunes?.album,
                albumArtUrl = deezer?.albumArtUrl ?: itunes?.albumArtUrl,
                artistImageUrl = deezer?.albumArtUrl,
                genres = listOfNotNull(itunes?.genre?.takeIf { it.isNotBlank() && it != "Music" }).ifEmpty { listOf("Music") },
                popularity = deezer?.popularity ?: 74,
                previewUrl = deezer?.previewUrl ?: itunes?.previewUrl,
                audiusStreamUrl = audiusStream,
                ytmVideoId = null,
                deezerUrl = deezer?.deezerUrl,
                spotifyUri = songlinkSpotify?.first,
                spotifyUrl = songlinkSpotify?.second,
                source = if (!audiusStream.isNullOrBlank()) "audius+open" else "deezer+itunes"
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

        // 2. Direct device Deezer + iTunes fallback
        val results = mutableListOf<OpenCatalogSearchTrack>()
        val seenKeys = mutableSetOf<String>()

        fetchDeezerList(cleanQ, limit).forEach { track ->
            if (excludeExplicit && track.isExplicit) return@forEach
            val key = TrackIdentityResolver.buildNormalizedMatchKey(track.title, track.artist)
            if (seenKeys.add(key)) results.add(track)
        }
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
                val genresArr = resolvedObj.optJSONArray("genres")
                val genresList = mutableListOf<String>()
                if (genresArr != null) {
                    for (i in 0 until genresArr.length()) {
                        genresArr.optString(i).takeIf { it.isNotBlank() }?.let { genresList.add(it) }
                    }
                }
                val deezerLink = resolvedObj.optString("deezerUrl").takeIf { it.startsWith("http") }
                var spUri = resolvedObj.optString("spotifyUri").takeIf { it.startsWith("spotify:track:") }
                var spUrl = resolvedObj.optString("spotifyUrl").takeIf { it.startsWith("http") }
                if (spUri == null && deezerLink != null) {
                    val fallbackSp = resolveSpotifyViaSonglink(deezerLink)
                    spUri = fallbackSp?.first
                    spUrl = fallbackSp?.second ?: spUrl
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
                    ytmVideoId = resolvedObj.optString("ytmVideoId").takeIf { it.isNotBlank() && it != "null" },
                    deezerUrl = deezerLink,
                    spotifyUri = spUri,
                    spotifyUrl = spUrl,
                    source = resolvedObj.optString("source").ifBlank { "backend_open_catalog" }
                )
            }
        } catch (e: Exception) {
            Log.d(TAG, "Backend resolve-playable skipped (${e.message}), using on-device catalog fallback.")
            null
        }
    }

    private fun fetchDeezerList(query: String, limit: Int): List<OpenCatalogSearchTrack> {
        return try {
            val url = "https://api.deezer.com/search?q=${Uri.encode(query)}&limit=$limit"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("data") ?: return emptyList()
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val title = obj.optString("title").trim()
                        val artistObj = obj.optJSONObject("artist")
                        val artist = artistObj?.optString("name")?.trim().orEmpty()
                        if (title.isBlank() || artist.isBlank()) continue
                        val albumObj = obj.optJSONObject("album")
                        val cover = albumObj?.optString("cover_xl")?.takeIf { it.startsWith("http") }
                            ?: albumObj?.optString("cover_big")?.takeIf { it.startsWith("http") }
                            ?: albumObj?.optString("cover_medium")?.takeIf { it.startsWith("http") }
                        add(
                            OpenCatalogSearchTrack(
                                trackId = TrackIdentityResolver.buildDeterministicTrackId(title, artist),
                                title = title,
                                artist = artist,
                                album = albumObj?.optString("title")?.takeIf { it.isNotBlank() },
                                albumArtUrl = cover,
                                genre = "Music",
                                previewUrl = obj.optString("preview").takeIf { it.startsWith("http") },
                                deezerUrl = obj.optString("link").takeIf { it.startsWith("http") },
                                popularity = ((obj.optInt("rank", 500_000) / 10_000).coerceIn(55, 98)),
                                isExplicit = obj.optBoolean("explicit_lyrics", false)
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fetchDeezerSingle(query: String, title: String, artist: String): OpenCatalogSearchTrack? {
        val list = fetchDeezerList(query, 6)
        val normT = normalizeTitleForExactMatch(title)
        val normA = normalizeTitleForExactMatch(artist)
        return list.firstOrNull {
            normalizeTitleForExactMatch(it.title) == normT &&
                (normA.isBlank() || normalizeTitleForExactMatch(it.artist).contains(normA))
        } ?: list.firstOrNull {
            normalizeTitleForExactMatch(it.title).contains(normT)
        } ?: list.firstOrNull()
    }

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
        val list = fetchItunesList(query, 6)
        val normT = normalizeTitleForExactMatch(title)
        val normA = normalizeTitleForExactMatch(artist)
        return list.firstOrNull {
            normalizeTitleForExactMatch(it.title) == normT &&
                (normA.isBlank() || normalizeTitleForExactMatch(it.artist).contains(normA))
        } ?: list.firstOrNull()
    }

    private fun fetchAudiusStreamUrl(title: String, artist: String, query: String): String? {
        return try {
            val url = "https://api.audius.co/v1/tracks/search?query=${Uri.encode(query)}&app_name=TuneMind"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("data") ?: return null
                val normT = normalizeTitleForExactMatch(title)
                val normA = normalizeTitleForExactMatch(artist)
                for (i in 0 until minOf(arr.length(), 8)) {
                    val obj = arr.optJSONObject(i) ?: continue
                    if (!obj.optBoolean("is_streamable", true)) continue
                    val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val hitTitle = normalizeTitleForExactMatch(obj.optString("title"))
                    val hitArtist = normalizeTitleForExactMatch(obj.optJSONObject("user")?.optString("name").orEmpty())
                    val titleMatches = hitTitle == normT || (normT.length >= 4 && hitTitle.contains(normT))
                    val artistMatches = normA.isBlank() || hitArtist.contains(normA) || normA.contains(hitArtist)
                    if (titleMatches && artistMatches) {
                        return "https://api.audius.co/v1/tracks/$id/stream?app_name=TuneMind"
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private val songlinkCache = ConcurrentHashMap<String, Pair<String?, String?>>()

    private fun resolveSpotifyViaSonglink(sourceUrl: String): Pair<String?, String?>? {
        val clean = sourceUrl.trim().takeIf { it.startsWith("http") } ?: return null
        songlinkCache[clean]?.let { return it }
        return try {
            val url = "https://api.song.link/v1-alpha.1/links?url=${Uri.encode(clean)}"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    songlinkCache[clean] = null to null
                    return null
                }
                val json = JSONObject(resp.body?.string().orEmpty())
                val spUrl = json.optJSONObject("linksByPlatform")
                    ?.optJSONObject("spotify")
                    ?.optString("url")
                    ?.trim()
                    ?.takeIf { it.startsWith("http") }
                val idMatch = spUrl?.let { Regex("/track/([A-Za-z0-9]{22})", RegexOption.IGNORE_CASE).find(it) }
                val spUri = idMatch?.groupValues?.getOrNull(1)?.let { "spotify:track:$it" }
                val canonicalUrl = idMatch?.groupValues?.getOrNull(1)?.let { "https://open.spotify.com/track/$it" } ?: spUrl
                val result = spUri to canonicalUrl
                songlinkCache[clean] = result
                result
            }
        } catch (_: Exception) {
            null
        }
    }
}
