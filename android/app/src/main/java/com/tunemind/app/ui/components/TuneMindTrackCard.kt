package com.tunemind.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.domain.model.TrackIdentityResolver
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

@Composable
fun AnimatedEqualizerBars(
    color: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "live_eq_bars")
    val phaseState = infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(620, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eq_phase"
    )

    Canvas(modifier = modifier) {
        val phase = phaseState.value
        val barWidth = (size.width / 6.5f).coerceAtLeast(2.dp.toPx())
        val gap = (size.width - barWidth * 4f) / 3f
        val maxH = size.height
        val corner = CornerRadius(barWidth / 2f, barWidth / 2f)
        for (idx in 0..3) {
            val h = when (idx) {
                0 -> maxH * (0.40f + 0.50f * phase)
                1 -> maxH * (0.95f - 0.38f * phase)
                2 -> maxH * (0.52f + 0.44f * phase)
                else -> maxH * (0.82f - 0.34f * phase)
            }
            val x = idx * (barWidth + gap)
            val y = maxH - h
            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(barWidth, h),
                cornerRadius = corner
            )
        }
    }
}

/**
 * Formats play/track counts compactly for large numbers (e.g. 999 -> "999", 1100 -> "1.1k", 1200000 -> "1.2M").
 */
fun formatCompactPlayCount(count: Int): String {
    val safe = count.coerceAtLeast(0)
    return when {
        safe < 1_000 -> safe.toString()
        safe < 1_000_000 -> {
            val tenths = safe / 100
            val whole = tenths / 10
            val rem = tenths % 10
            if (rem == 0) "${whole}k" else "${whole}.${rem}k"
        }
        else -> {
            val tenths = safe / 100_000
            val whole = tenths / 10
            val rem = tenths % 10
            if (rem == 0) "${whole}M" else "${whole}.${rem}M"
        }
    }
}

fun formatPlaysLabel(count: Int, zeroLabel: String = "0 plays"): String {
    val safe = count.coerceAtLeast(0)
    return when {
        safe <= 0 -> zeroLabel
        safe == 1 -> "1 play"
        else -> "${formatCompactPlayCount(safe)} plays"
    }
}

/**
 * Clean open-canvas track list row with hairline separator and unboxed middot metadata.
 * Eliminates heavy per-song card boxes and pill clutter.
 */
@Composable
fun TuneMindTrackCard(
    title: String,
    artist: String,
    playCount: Int,
    isCurrentlyPlaying: Boolean,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    appLabel: String? = null,
    freshnessPercent: Int? = null,
    isCoolingDown: Boolean = false,
    cooldownRemainingLabel: String? = null,
    recoveryPercent: Int? = null,
    accentColor: Color = LocalAdaptiveTheme.current.primaryAccent,
    secondaryAccent: Color = LocalAdaptiveTheme.current.secondaryAccent,
    leadingIndex: Int? = null,
    showUnplayedLabel: Boolean = true,
    spotifyUri: String? = null,
    albumArtUrl: String? = null,
    artistImageUrl: String? = null,
    trackId: String = "",
    zeroPlayLabel: String = "0 plays",
    trailingActions: (@Composable RowScope.() -> Unit)? = null,
    onSyncClick: (() -> Unit)? = null,
    onCardClick: (() -> Unit)? = null
) {
    val liveGreen = Color(0xFF10B981)
    val coolingAmber = Color(0xFFFFB000)
    val normalizedSpotifyUri = remember(spotifyUri) {
        TrackIdentityResolver.normalizeSpotifyUri(spotifyUri)
    }

    val effectiveAccent = when {
        isCurrentlyPlaying -> liveGreen
        isCoolingDown -> coolingAmber
        else -> accentColor
    }

    val resolvedMedia = rememberResolvedTrackMedia(
        title = title,
        artist = artist,
        spotifyUri = normalizedSpotifyUri,
        fallbackAlbumArtUrl = albumArtUrl
    )
    val effectiveAlbumUrl = albumArtUrl?.takeIf { it.isNotBlank() } ?: resolvedMedia.albumArtUrl
    val effectiveArtistUrl = artistImageUrl?.takeIf { it.isNotBlank() } ?: resolvedMedia.artistImageUrl

    val safePlayCount = playCount.coerceAtLeast(0)
    val metadataLine = remember(
        artist,
        subtitle,
        safePlayCount,
        showUnplayedLabel,
        zeroPlayLabel,
        isCoolingDown,
        cooldownRemainingLabel,
        recoveryPercent,
        freshnessPercent,
        appLabel
    ) {
        val playSummary = when {
            safePlayCount > 0 -> formatPlaysLabel(safePlayCount)
            showUnplayedLabel -> zeroPlayLabel.ifBlank { "0 plays" }
            else -> null
        }
        val freshnessSummary = when {
            isCoolingDown -> buildString {
                append("Resting")
                if (!cooldownRemainingLabel.isNullOrBlank()) {
                    append(" · $cooldownRemainingLabel")
                } else if (recoveryPercent != null && recoveryPercent > 0) {
                    append(" · $recoveryPercent% recovered")
                }
            }
            freshnessPercent != null -> "${freshnessPercent}% fresh"
            else -> null
        }
        listOfNotNull(
            artist.ifBlank { "Unknown Artist" },
            subtitle?.takeIf { it.isNotBlank() },
            playSummary,
            freshnessSummary,
            appLabel?.takeIf { it.isNotBlank() }
        ).joinToString(" · ")
    }

    val haptics = LocalHapticFeedback.current
    val cardInteraction = remember { MutableInteractionSource() }
    Column(modifier = modifier.fillMaxWidth().animateContentSize(TuneMindSprings.snappy())) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .tactilePressScale(cardInteraction, pressedScale = 0.98f)
                .clip(RoundedCornerShape(12.dp))
                .background(if (isCurrentlyPlaying) liveGreen.copy(alpha = 0.08f) else Color.Transparent)
                .clickable(
                    interactionSource = cardInteraction,
                    indication = null
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    (onCardClick ?: onPlayClick).invoke()
                }
                .padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (leadingIndex != null) {
                Text(
                    text = "$leadingIndex",
                    color = if (isCurrentlyPlaying) liveGreen else Color.White.copy(alpha = 0.44f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.widthIn(min = 20.dp)
                )
            }

            // Start: 44dp touch-safe play / equalizer circle
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .bouncyClickable { onPlayClick() }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(color = effectiveAccent.copy(alpha = if (isCurrentlyPlaying) 0.18f else 0.10f))
                    if (freshnessPercent != null) {
                        drawArc(
                            color = effectiveAccent.copy(alpha = 0.18f),
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                        )
                        drawArc(
                            color = effectiveAccent,
                            startAngle = -90f,
                            sweepAngle = 360f * (freshnessPercent.coerceIn(5, 100) / 100f),
                            useCenter = false,
                            style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }

                if (isCurrentlyPlaying) {
                    AnimatedEqualizerBars(
                        color = liveGreen,
                        modifier = Modifier.size(16.dp, 14.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Play $title",
                        tint = effectiveAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Middle: Song Title & Metadata
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = if (isCurrentlyPlaying) liveGreen else Color(0xFFF8FAFC),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = metadataLine,
                    color = Color.White.copy(alpha = 0.56f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Optional custom inline trailing actions (e.g. Promote/Swap in Up Next)
            trailingActions?.invoke(this)

            // Gently left of the end photo: Neural Sonic Action button when provided
            if (onSyncClick != null) {
                NeuralSonicActionButton(
                    accentColor = effectiveAccent,
                    secondaryColor = secondaryAccent,
                    onClick = onSyncClick
                )
            }

            // End: Layered Song Album Art + Mini Circular Artist Profile Badge
            LayeredSongArtistBadge(
                albumArtUrl = effectiveAlbumUrl,
                artistImageUrl = effectiveArtistUrl,
                title = title,
                artist = artist,
                accentColor = effectiveAccent,
                size = 46.dp
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(start = if (leadingIndex != null) 88.dp else 60.dp),
            thickness = 0.5.dp,
            color = Color.White.copy(alpha = 0.06f)
        )
    }
}

