package com.tunemind.app.domain.model

data class CanonicalTrackIdentity(
    val trackId: String,
    val spotifyUri: String? = null
)

/**
 * Shared canonical track identity resolver across Android Room entities,
 * ViewModel state, Compose cards, and Backend/Supabase telemetry sync.
 *
 * Guarantees:
 * - Every song card (new or historical) always carries a deterministic canonical `trackId`.
 * - `spotifyUri` is ONLY populated with genuine `spotify:track:<alphanumeric_id>` URIs
 *   (never fabricated from title_artist slugs or ephemeral UI keys).
 * - Ephemeral UI Compose keys (`hist_...`, `sp_like_...`, `vault_...`, `rec_...`, etc.)
 *   are rejected and replaced by canonical Spotify ID or `trk:<artist>::<title>`.
 */
object TrackIdentityResolver {

    private val SPOTIFY_URI_RE = Regex("^spotify:track:([A-Za-z0-9]+)$", RegexOption.IGNORE_CASE)
    private val SPOTIFY_OPEN_URL_RE = Regex("^https?://open\\.spotify\\.com/track/([A-Za-z0-9]+).*$", RegexOption.IGNORE_CASE)
    private val SPOTIFY_BASE62_RE = Regex("^[A-Za-z0-9]{22}$")

    private val UI_LIST_KEY_PREFIXES = listOf(
        "hist_",
        "sp_like_",
        "sp_pl_",
        "sp_seed_",
        "vault_",
        "cap_",
        "fy_",
        "np_",
        "search_",
        "rec_",
        "local_",
        "memory:",
        "live_"
    )

    fun isSpotifyTrackUri(raw: String?): Boolean {
        val trimmed = raw?.trim().orEmpty()
        return trimmed.isNotEmpty() && SPOTIFY_URI_RE.matches(trimmed)
    }

    fun extractSpotifyBase62Id(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        SPOTIFY_URI_RE.matchEntire(trimmed)?.groupValues?.getOrNull(1)?.let { return it }
        SPOTIFY_OPEN_URL_RE.matchEntire(trimmed)?.groupValues?.getOrNull(1)?.let { return it }
        if (SPOTIFY_BASE62_RE.matches(trimmed)) return trimmed
        return null
    }

    fun normalizeSpotifyUri(raw: String?): String? {
        val id = extractSpotifyBase62Id(raw) ?: return null
        return "spotify:track:$id"
    }

    fun slugifyPart(raw: String?, fallback: String = "unknown"): String {
        val cleaned = raw.orEmpty()
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        return cleaned.ifEmpty { fallback }
    }

    fun buildDeterministicTrackId(
        title: String?,
        artist: String?,
        prefix: String = "trk"
    ): String {
        val titleSlug = slugifyPart(title, "untitled").take(48)
        val artistSlug = slugifyPart(artist, "unknown").take(36)
        return "$prefix:$artistSlug::$titleSlug"
    }

    fun isEphemeralUiListKey(rawId: String?): Boolean {
        val lower = rawId?.trim()?.lowercase().orEmpty()
        if (lower.isEmpty()) return false
        return UI_LIST_KEY_PREFIXES.any { lower.startsWith(it) } ||
            (lower.startsWith("sp_") && !lower.startsWith("spotify:"))
    }

    fun resolveCanonicalIdentity(
        trackId: String? = null,
        spotifyUri: String? = null,
        title: String? = null,
        artist: String? = null,
        prefix: String = "trk"
    ): CanonicalTrackIdentity {
        val rawTrackId = trackId?.trim().orEmpty()
        val rawSpotifyUri = spotifyUri?.trim().orEmpty()

        val resolvedSpotifyUri = normalizeSpotifyUri(rawSpotifyUri)
            ?: normalizeSpotifyUri(rawTrackId)

        val resolvedTrackId = when {
            resolvedSpotifyUri != null && (rawTrackId.isEmpty() || isEphemeralUiListKey(rawTrackId) || rawTrackId.startsWith("spotify:") || rawTrackId.startsWith("http")) -> {
                extractSpotifyBase62Id(resolvedSpotifyUri) ?: resolvedSpotifyUri
            }
            rawTrackId.isNotEmpty() && !isEphemeralUiListKey(rawTrackId) && !rawTrackId.startsWith("track:") -> {
                val asSpotifyId = extractSpotifyBase62Id(rawTrackId)
                when {
                    rawTrackId.startsWith("spotify:", ignoreCase = true) ->
                        asSpotifyId ?: buildDeterministicTrackId(title, artist, prefix)
                    rawTrackId.startsWith("http://", ignoreCase = true) || rawTrackId.startsWith("https://", ignoreCase = true) ->
                        asSpotifyId ?: buildDeterministicTrackId(title, artist, prefix)
                    else -> rawTrackId
                }
            }
            resolvedSpotifyUri != null -> extractSpotifyBase62Id(resolvedSpotifyUri) ?: resolvedSpotifyUri
            else -> buildDeterministicTrackId(title, artist, prefix)
        }

        return CanonicalTrackIdentity(
            trackId = resolvedTrackId,
            spotifyUri = resolvedSpotifyUri
        )
    }

    fun resolveCanonicalTrackId(
        trackId: String? = null,
        spotifyUri: String? = null,
        title: String? = null,
        artist: String? = null
    ): String = resolveCanonicalIdentity(trackId, spotifyUri, title, artist).trackId
}
