package com.tunemind.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.domain.share.ShareCardFormat
import com.tunemind.app.domain.share.ViralShareCardModel
import com.tunemind.app.ui.theme.LocalAdaptiveTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ViralShareCardPreview(
    format: ShareCardFormat,
    model: ViralShareCardModel,
    modifier: Modifier = Modifier
) {
    val theme = LocalAdaptiveTheme.current
    val primary = theme.primaryAccent
    val secondary = theme.secondaryAccent

    val cardBrush = Brush.linearGradient(
        colors = listOf(
            Color(0xFF10172A),
            Color(0xFF090D18),
            Color(0xFF130F24)
        )
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(cardBrush)
            .border(
                width = 1.2.dp,
                brush = Brush.linearGradient(
                    listOf(
                        primary.copy(alpha = 0.65f),
                        secondary.copy(alpha = 0.45f)
                    )
                ),
                shape = RoundedCornerShape(24.dp)
            )
            .padding(18.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Card Header Pill + Date Range
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(100.dp),
                    color = primary.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, primary.copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = when (format) {
                                ShareCardFormat.PERSONALITY_ROAST -> Icons.Rounded.Whatshot
                                ShareCardFormat.DNA_RECEIPT -> Icons.Rounded.ReceiptLong
                                ShareCardFormat.NOW_PLAYING_VIBE -> Icons.Rounded.GraphicEq
                            },
                            contentDescription = null,
                            tint = primary,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "TUNEMIND · ${model.period.badgeTitle}",
                            color = Color(0xFFF8FAFC),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.4.sp
                        )
                    }
                }

                Text(
                    text = model.rangeLabel.uppercase(Locale.getDefault()),
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }

            // Card Body
            when (format) {
                ShareCardFormat.PERSONALITY_ROAST -> PersonalityRoastPreviewBody(model, primary, secondary)
                ShareCardFormat.DNA_RECEIPT -> DnaReceiptPreviewBody(model, primary, secondary)
                ShareCardFormat.NOW_PLAYING_VIBE -> NowPlayingVibePreviewBody(model, primary, secondary)
            }

            StudioHairlineDivider()

            // Brand Watermark Footer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Tracked Live by TuneMind",
                        color = Color(0xFFF8FAFC),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Spotify · YouTube Music · Boomplay · Audiomack",
                        color = Color.White.copy(alpha = 0.56f),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    shape = RoundedCornerShape(100.dp),
                    color = secondary.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, secondary.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = "GET YOUR ROAST",
                        color = Color(0xFFF8FAFC),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonalityRoastPreviewBody(
    model: ViralShareCardModel,
    primary: Color,
    secondary: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "${model.listenerName.uppercase(Locale.getDefault())}'S AUDIT · ${model.personaTag}",
                color = primary,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp
            )
            Text(
                text = model.archetypeTitle,
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 30.sp
            )
            Text(
                text = "\"${model.roastLine}\"",
                color = Color.White.copy(alpha = 0.84f),
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }

        // #1 Obsession Track Showcase
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White.copy(alpha = 0.06f),
            border = BorderStroke(1.dp, primary.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TuneMindAvatarCircle(
                    imageUrl = model.topTrackArtUrl,
                    fallbackInitial = model.topTrackTitle,
                    size = 46.dp,
                    accentColor = primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "#1 OBSESSION · ${model.topTrackPlays} PLAYS",
                        color = primary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = model.topTrackTitle,
                        color = Color.White,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = model.topTrackArtist,
                        color = Color.White.copy(alpha = 0.68f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TuneMindAvatarCircle(
                    imageUrl = model.topArtistPhotoUrl,
                    fallbackInitial = model.topArtistName,
                    size = 34.dp,
                    accentColor = secondary
                )
            }
        }

        // 2x2 Signature Stat Grid
        val rows = model.roastStats.chunked(2)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEachIndexed { rIdx, pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pair.forEachIndexed { cIdx, stat ->
                        val accent = if ((rIdx * 2 + cIdx) % 2 == 0) primary else secondary
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color.White.copy(alpha = 0.05f),
                            border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.10f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(
                                modifier = Modifier.padding(11.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = stat.label,
                                    color = Color.White.copy(alpha = 0.54f),
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stat.value,
                                    color = accent,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = stat.roastSubtext,
                                    color = Color.White.copy(alpha = 0.78f),
                                    fontSize = 10.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        Text(
            text = "${model.overplayedCallout} · ${model.totalListeningLabel}",
            color = Color(0xFFFBBF24),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DnaReceiptPreviewBody(
    model: ViralShareCardModel,
    primary: Color,
    secondary: Color
) {
    val dateLabel = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date())
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "MUSIC DNA RECEIPT",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.6.sp
            )
            Text(
                text = "LISTENER: ${model.listenerName.uppercase(Locale.getDefault())} · $dateLabel",
                color = Color.White.copy(alpha = 0.68f),
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "VERDICT: ${model.archetypeTitle.uppercase(Locale.getDefault())}",
                color = primary,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        StudioHairlineDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "QTY  TRACK & ARTIST",
                color = primary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "PLAYS",
                color = primary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }

        if (model.receiptLines.isEmpty()) {
            Text(
                text = "Play songs in Spotify, YouTube Music, or Boomplay to print your listening receipt.",
                color = Color.White.copy(alpha = 0.68f),
                fontSize = 12.5.sp,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                model.receiptLines.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "0${item.rank}",
                            color = primary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                color = Color.White,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${item.artist} · ${item.durationLabel}",
                                color = Color.White.copy(alpha = 0.60f),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "${item.plays}×",
                            color = secondary,
                            fontSize = 13.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        StudioHairlineDivider()

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ReceiptSummaryRow("TOTAL TIME", model.totalListeningLabel)
            ReceiptSummaryRow("COMPLETION RATE", "${model.completionPercent}% (${model.skipCount} skips)")
            ReceiptSummaryRow("APPS", model.appSplitSummary)
        }

        Text(
            text = model.overplayedCallout,
            color = Color(0xFFFBBF24),
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ReceiptSummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

@Composable
private fun NowPlayingVibePreviewBody(
    model: ViralShareCardModel,
    primary: Color,
    secondary: Color
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "CURRENT VIBE CHECK · VIA ${model.vibeSourceApp.uppercase(Locale.getDefault())}",
            color = primary,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            textAlign = TextAlign.Center
        )

        Box(
            modifier = Modifier.size(134.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(132.dp)
                    .clip(CircleShape)
                    .background(primary.copy(alpha = 0.16f))
            )
            LayeredSongArtistBadge(
                albumArtUrl = model.vibeArtUrl,
                artistImageUrl = model.vibeArtistPhotoUrl,
                title = model.vibeTrackTitle,
                artist = model.vibeTrackArtist,
                accentColor = primary,
                size = 110.dp
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = model.vibeTrackTitle,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = model.vibeTrackArtist,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 13.5.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.White.copy(alpha = 0.06f),
            border = BorderStroke(1.dp, secondary.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = secondary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "\"${model.vibeStatusLine}\"",
                    color = Color(0xFFF8FAFC),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 17.sp
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            VibeStatPill("PLAYS", "${model.vibeTrackPlays}×", primary)
            VibeStatPill("FINISHER", "${model.completionPercent}%", secondary)
            VibeStatPill("PERSONA", model.archetypeTitle.substringBefore(" "), primary)
        }
    }
}

@Composable
private fun VibeStatPill(label: String, value: String, accent: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.54f),
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value,
            color = accent,
            fontSize = 17.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}
