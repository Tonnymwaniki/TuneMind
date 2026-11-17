package com.tunemind.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tunemind.app.data.ingestion.ArtistProfileCacheEntity
import com.tunemind.app.data.ingestion.ResolvedTrackVisuals
import com.tunemind.app.data.ingestion.SpotifyVisualArtworkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private fun resolveTrackVisualSlice(
    visualsMap: Map<String, ResolvedTrackVisuals>,
    artistsMap: Map<String, ArtistProfileCacheEntity>,
    tKey: String,
    uKey: String?,
    aKey: String,
    cleanUri: String?,
    fallbackAlbumArtUrl: String?
): ResolvedTrackVisuals {
    val byUri = uKey?.let { visualsMap[it] }
    val byTrack = if (tKey.isNotBlank()) visualsMap[tKey] else null
    val matched = byUri ?: byTrack
    val cachedArtistUrl = if (aKey.isNotBlank()) artistsMap[aKey]?.imageUrl else null

    val effectiveAlbum = matched?.albumArtUrl?.takeIf { it.isNotBlank() }
        ?: fallbackAlbumArtUrl?.takeIf { it.isNotBlank() }
    val effectiveArtist = matched?.artistImageUrl?.takeIf { it.isNotBlank() }
        ?: cachedArtistUrl?.takeIf { it.isNotBlank() }

    return (matched ?: ResolvedTrackVisuals(trackKey = tKey, spotifyUri = cleanUri)).copy(
        albumArtUrl = effectiveAlbum,
        artistImageUrl = effectiveArtist
    )
}

@Composable
fun rememberResolvedTrackMedia(
    title: String?,
    artist: String?,
    spotifyUri: String? = null,
    fallbackAlbumArtUrl: String? = null
): ResolvedTrackVisuals {
    val context = LocalContext.current
    val repo = remember(context) { SpotifyVisualArtworkRepository.getInstance(context) }

    val cleanUri = remember(spotifyUri) { spotifyUri?.trim()?.takeIf { it.isNotBlank() } }
    val tKey = remember(title, artist) { SpotifyVisualArtworkRepository.canonicalTrackKey(title, artist) }
    val uKey = remember(cleanUri) { cleanUri?.let { SpotifyVisualArtworkRepository.uriKey(it) } }
    val aKey = remember(artist) {
        SpotifyVisualArtworkRepository.canonicalArtistKey(
            SpotifyVisualArtworkRepository.extractPrimaryArtistName(artist)
        )
    }

    LaunchedEffect(title, artist, spotifyUri, fallbackAlbumArtUrl) {
        withContext(Dispatchers.IO) {
            repo.requestTrackVisuals(
                title = title,
                artist = artist,
                spotifyUri = spotifyUri,
                fallbackAlbumArtUrl = fallbackAlbumArtUrl
            )
        }
    }

    val initialValue = remember(tKey, uKey, aKey, fallbackAlbumArtUrl) {
        resolveTrackVisualSlice(
            visualsMap = repo.trackVisualsFlow.value,
            artistsMap = repo.artistProfilesFlow.value,
            tKey = tKey,
            uKey = uKey,
            aKey = aKey,
            cleanUri = cleanUri,
            fallbackAlbumArtUrl = fallbackAlbumArtUrl
        )
    }

    val resolved by remember(tKey, uKey, aKey, fallbackAlbumArtUrl) {
        combine(repo.trackVisualsFlow, repo.artistProfilesFlow) { visualsMap, artistsMap ->
            resolveTrackVisualSlice(visualsMap, artistsMap, tKey, uKey, aKey, cleanUri, fallbackAlbumArtUrl)
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }.collectAsState(initial = initialValue)

    return resolved
}

@Composable
fun rememberResolvedArtistPhoto(
    artistName: String?,
    artistId: String? = null
): String? {
    val context = LocalContext.current
    val repo = remember(context) { SpotifyVisualArtworkRepository.getInstance(context) }
    val aKey = remember(artistName) {
        SpotifyVisualArtworkRepository.canonicalArtistKey(
            SpotifyVisualArtworkRepository.extractPrimaryArtistName(artistName)
        )
    }

    LaunchedEffect(artistName, artistId) {
        withContext(Dispatchers.IO) {
            repo.requestArtistProfile(artistName = artistName, artistId = artistId)
        }
    }

    val initialValue = remember(aKey, artistId) {
        val snap = repo.artistProfilesFlow.value
        (if (aKey.isNotBlank()) snap[aKey]?.imageUrl else null)
            ?: artistId?.takeIf { it.isNotBlank() }?.let { snap["id:$it"]?.imageUrl }
    }

    val photo by remember(aKey, artistId) {
        repo.artistProfilesFlow.map { artistsMap ->
            val byName = if (aKey.isNotBlank()) artistsMap[aKey]?.imageUrl else null
            val byId = artistId?.takeIf { it.isNotBlank() }?.let { artistsMap["id:$it"]?.imageUrl }
            byName ?: byId
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }.collectAsState(initial = initialValue)

    return photo
}

@Composable
fun SpotifyAvatarCircle(
    imageUrl: String?,
    fallbackInitial: String,
    size: Dp,
    accentColor: Color,
    modifier: Modifier = Modifier,
    borderWidth: Dp = 1.5.dp,
    borderColor: Color = accentColor.copy(alpha = 0.55f)
) {
    val context = LocalContext.current
    val cleanUrl = imageUrl?.trim()?.takeIf { it.startsWith("http") }
    var imageFailed by remember(cleanUrl) { mutableStateOf(false) }
    val letter = fallbackInitial.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "T"

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.28f),
                        Color(0xFF101726)
                    )
                )
            )
            .border(borderWidth, borderColor, CircleShape)
    ) {
        Text(
            text = letter,
            color = accentColor,
            fontSize = (size.value * 0.40f).coerceIn(10f, 24f).sp,
            fontWeight = FontWeight.Bold
        )
        if (cleanUrl != null && !imageFailed) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(cleanUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { imageFailed = true },
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
            )
        }
    }
}

/**
 * Layered Song Art + Mini Artist Badge placed at the end of every song card across TuneMind.
 * Renders a rounded-square album cover with a miniature circular artist photo tucked cleanly
 * at the bottom-right corner with a dark surface cutout ring.
 */
@Composable
fun LayeredSongArtistBadge(
    albumArtUrl: String?,
    artistImageUrl: String?,
    title: String,
    artist: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp
) {
    val context = LocalContext.current
    val cleanAlbumUrl = albumArtUrl?.trim()?.takeIf { it.startsWith("http") }
    val cleanArtistUrl = artistImageUrl?.trim()?.takeIf { it.startsWith("http") }
    var albumFailed by remember(cleanAlbumUrl) { mutableStateOf(false) }
    var artistFailed by remember(cleanArtistUrl) { mutableStateOf(false) }

    val cornerRadius = (size * 0.22f).coerceIn(8.dp, 16.dp)
    val badgeSize = (size * 0.44f).coerceIn(18.dp, 28.dp)
    val artistInitial = SpotifyVisualArtworkRepository.extractPrimaryArtistName(artist)
        .ifBlank { title }
        .trim()
        .firstOrNull()
        ?.uppercaseChar()
        ?.toString()
        ?: "♪"

    Box(
        modifier = modifier.size(size)
    ) {
        // Primary layer: Rounded-square song album art (with clean studio fallback)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(cornerRadius))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.24f),
                            Color(0xFF0E1422)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(cornerRadius)
                )
        ) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = accentColor.copy(alpha = 0.72f),
                modifier = Modifier.size((size * 0.44f).coerceIn(16.dp, 28.dp))
            )

            if (cleanAlbumUrl != null && !albumFailed) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(cleanAlbumUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = "$title cover",
                    contentScale = ContentScale.Crop,
                    onError = { albumFailed = true },
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(cornerRadius))
                )
            }
        }

        // Secondary layer: Mini circular artist profile photo tucked bottom-right with cutout ring
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 3.dp, y = 3.dp)
                .size(badgeSize)
                .clip(CircleShape)
                .background(Color(0xFF111827))
                .border(1.6.dp, Color(0xFF070A12), CircleShape)
        ) {
            Text(
                text = artistInitial,
                color = Color.White.copy(alpha = 0.88f),
                fontSize = (badgeSize.value * 0.44f).coerceIn(8f, 13f).sp,
                fontWeight = FontWeight.Bold
            )
            if (cleanArtistUrl != null && !artistFailed) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(cleanArtistUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = "$artist profile",
                    contentScale = ContentScale.Crop,
                    onError = { artistFailed = true },
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                )
            }
        }
    }
}
