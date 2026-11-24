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
import com.tunemind.app.data.ingestion.UniversalMusicVisualRepository
import com.tunemind.app.data.local.ArtistProfileCacheEntity
import com.tunemind.app.data.local.ResolvedTrackVisuals
import com.tunemind.app.data.local.TrackVisualCacheEntity
import com.tunemind.app.domain.model.TrackIdentityResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private fun resolveVisualSlice(
    visualsMap: Map<String, TrackVisualCacheEntity>,
    artistsMap: Map<String, ArtistProfileCacheEntity>,
    tKey: String,
    aKey: String,
    fallbackAlbumArtUrl: String?
): ResolvedTrackVisuals {
    val matched = if (tKey.isNotBlank()) visualsMap[tKey] else null
    val cachedArtistUrl = if (aKey.isNotBlank()) artistsMap[aKey]?.imageUrl else null

    val effectiveAlbum = matched?.albumArtUrl?.takeIf { it.isNotBlank() }
        ?: fallbackAlbumArtUrl?.takeIf { it.isNotBlank() }
    val effectiveArtist = matched?.artistImageUrl?.takeIf { it.isNotBlank() }
        ?: cachedArtistUrl?.takeIf { it.isNotBlank() }
        ?: effectiveAlbum

    return ResolvedTrackVisuals(
        trackKey = tKey,
        trackId = matched?.trackId,
        albumName = matched?.albumName,
        albumArtUrl = effectiveAlbum,
        artistImageUrl = effectiveArtist,
        genres = matched?.genresCsv?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
        popularity = matched?.popularity ?: 0,
        previewUrl = matched?.previewUrl,
        audiusStreamUrl = matched?.audiusStreamUrl,
        ytmVideoId = matched?.ytmVideoId,
        deezerUrl = matched?.deezerUrl
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
    val repo = remember(context) { UniversalMusicVisualRepository.getInstance(context) }

    val cleanTitle = remember(title) { title?.trim().orEmpty() }
    val cleanArtist = remember(artist) { artist?.trim().orEmpty() }
    val tKey = remember(cleanTitle, cleanArtist) {
        TrackIdentityResolver.buildNormalizedMatchKey(cleanTitle, cleanArtist)
    }
    val aKey = remember(cleanArtist) {
        UniversalMusicVisualRepository.normalizeArtistKey(cleanArtist)
    }

    val perTrackFlow = remember(repo, tKey, aKey, fallbackAlbumArtUrl) {
        combine(repo.trackVisualsMapFlow, repo.artistProfilesMapFlow) { visualsMap, artistsMap ->
            resolveVisualSlice(visualsMap, artistsMap, tKey, aKey, fallbackAlbumArtUrl)
        }.distinctUntilChanged()
    }
    val initialResolved = remember(tKey, aKey, fallbackAlbumArtUrl) {
        resolveVisualSlice(
            repo.trackVisualsMapFlow.value,
            repo.artistProfilesMapFlow.value,
            tKey,
            aKey,
            fallbackAlbumArtUrl
        )
    }
    val resolved by perTrackFlow.collectAsState(initial = initialResolved)

    LaunchedEffect(tKey) {
        if (cleanTitle.isNotBlank() && resolved.albumArtUrl.isNullOrBlank()) {
            repo.enqueueVisualEnrichment(listOf(cleanTitle to cleanArtist))
        }
    }

    return resolved
}

@Composable
fun rememberResolvedArtistPhoto(
    artistName: String?,
    artistId: String? = null
): String? {
    val context = LocalContext.current
    val repo = remember(context) { UniversalMusicVisualRepository.getInstance(context) }
    val cleanArtist = remember(artistName) { artistName?.trim().orEmpty() }
    val aKey = remember(cleanArtist) { UniversalMusicVisualRepository.normalizeArtistKey(cleanArtist) }

    val photoFlow = remember(repo, aKey, artistId) {
        repo.artistProfilesMapFlow.map { artistsMap ->
            (if (aKey.isNotBlank()) artistsMap[aKey]?.imageUrl else null)
                ?: artistId?.takeIf { it.isNotBlank() }?.let { id ->
                    artistsMap.values.firstOrNull { it.artistId == id }?.imageUrl
                }
        }.distinctUntilChanged()
    }
    val photo by photoFlow.collectAsState(initial = null)

    LaunchedEffect(aKey) {
        if (cleanArtist.isNotBlank() && photo.isNullOrBlank()) {
            repo.enqueueVisualEnrichment(listOf("" to cleanArtist))
        }
    }

    return photo
}

@Composable
fun TuneMindAvatarCircle(
    imageUrl: String?,
    fallbackInitial: String,
    size: Dp,
    accentColor: Color,
    modifier: Modifier = Modifier,
    borderWidth: Dp = 1.5.dp,
    borderColor: Color = accentColor.copy(alpha = 0.55f)
) {
    val context = LocalContext.current
    val isLowTier = rememberIsLowTierDevice()
    val targetPx = remember(size) {
        (size.value * context.resources.displayMetrics.density * 1.5f).toInt().coerceIn(48, 512)
    }
    val cleanUrl = imageUrl?.trim()?.takeIf {
        it.startsWith("http") || it.startsWith("file://") || it.startsWith("/")
    }
    var imageFailed by remember(cleanUrl) { mutableStateOf(false) }
    val letter = fallbackInitial.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "T"

    val avatarBrush = remember(accentColor) {
        Brush.linearGradient(
            colors = listOf(
                accentColor.copy(alpha = 0.28f),
                Color(0xFF101726)
            )
        )
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(avatarBrush)
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
                model = remember(cleanUrl, targetPx, isLowTier) {
                    ImageRequest.Builder(context)
                        .data(cleanUrl)
                        .size(targetPx)
                        .crossfade(if (isLowTier) 0 else 120)
                        .build()
                },
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

@Composable
fun SpotifyAvatarCircle(
    imageUrl: String?,
    fallbackInitial: String,
    size: Dp,
    accentColor: Color,
    modifier: Modifier = Modifier,
    borderWidth: Dp = 1.5.dp,
    borderColor: Color = accentColor.copy(alpha = 0.55f)
) = TuneMindAvatarCircle(imageUrl, fallbackInitial, size, accentColor, modifier, borderWidth, borderColor)

/**
 * Layered Song Art + Mini Artist Badge placed on track cards across TuneMind.
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
    val isLowTier = rememberIsLowTierDevice()
    val density = context.resources.displayMetrics.density
    val albumTargetPx = remember(size, density) { (size.value * density * 1.5f).toInt().coerceIn(64, 512) }
    val artistTargetPx = remember(size, density) { (size.value * 0.44f * density * 1.5f).toInt().coerceIn(36, 160) }
    val cleanAlbumUrl = albumArtUrl?.trim()?.takeIf { it.startsWith("http") }
    val cleanArtistUrl = artistImageUrl?.trim()?.takeIf { it.startsWith("http") }
    var albumFailed by remember(cleanAlbumUrl) { mutableStateOf(false) }
    var artistFailed by remember(cleanArtistUrl) { mutableStateOf(false) }

    val cornerRadius = (size * 0.22f).coerceIn(8.dp, 16.dp)
    val badgeSize = (size * 0.44f).coerceIn(18.dp, 28.dp)
    val artistInitial = UniversalMusicVisualRepository.normalizeArtistKey(artist)
        .ifBlank { title }
        .trim()
        .firstOrNull()
        ?.uppercaseChar()
        ?.toString()
        ?: "♪"

    Box(modifier = modifier.size(size)) {
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
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(cornerRadius))
        ) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = accentColor.copy(alpha = 0.72f),
                modifier = Modifier.size((size * 0.44f).coerceIn(16.dp, 28.dp))
            )

            if (cleanAlbumUrl != null && !albumFailed) {
                AsyncImage(
                    model = remember(cleanAlbumUrl, albumTargetPx, isLowTier) {
                        ImageRequest.Builder(context)
                            .data(cleanAlbumUrl)
                            .size(albumTargetPx)
                            .crossfade(if (isLowTier) 0 else 120)
                            .build()
                    },
                    contentDescription = "$title cover",
                    contentScale = ContentScale.Crop,
                    onError = { albumFailed = true },
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(cornerRadius))
                )
            }
        }

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
                    model = remember(cleanArtistUrl, artistTargetPx, isLowTier) {
                        ImageRequest.Builder(context)
                            .data(cleanArtistUrl)
                            .size(artistTargetPx)
                            .crossfade(if (isLowTier) 0 else 100)
                            .build()
                    },
                    contentDescription = "$artist profile",
                    contentScale = ContentScale.Crop,
                    onError = { artistFailed = true },
                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                )
            }
        }
    }
}
