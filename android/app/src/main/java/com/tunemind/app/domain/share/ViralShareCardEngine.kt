package com.tunemind.app.domain.share

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.tunemind.app.data.ingestion.UniversalMusicVisualRepository
import com.tunemind.app.data.local.CrossAppPlayRecorder
import com.tunemind.app.data.local.CrossAppTasteShiftSummary
import com.tunemind.app.domain.memory.WindowedListeningStats
import com.tunemind.app.domain.model.TrackIdentityResolver
import com.tunemind.app.ui.NowPlayingObservation
import com.tunemind.app.ui.VaultTrack
import com.tunemind.app.ui.screens.formatListeningDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ShareCardFormat(
    val title: String,
    val shortLabel: String
) {
    PERSONALITY_ROAST("Music Roast", "Roast"),
    DNA_RECEIPT("DNA Receipt", "Receipt"),
    NOW_PLAYING_VIBE("Vibe Check", "Vibe")
}

enum class ShareRecapPeriod(
    val label: String,
    val badgeTitle: String
) {
    SEVEN_DAYS("7 Days · Sunday Drop", "SUNDAY 7-DAY DROP"),
    MONTHLY("Monthly · 30 Days", "MONTHLY AUDIT")
}

data class ViralReceiptLine(
    val rank: Int,
    val title: String,
    val artist: String,
    val plays: Int,
    val durationLabel: String
)

data class ViralRoastStat(
    val label: String,
    val value: String,
    val roastSubtext: String
)

data class ViralShareCardModel(
    val period: ShareRecapPeriod,
    val listenerName: String,
    val rangeLabel: String,
    val archetypeTitle: String,
    val roastLine: String,
    val personaTag: String,
    val verdictBadge: String,
    val obsessionScore: Int,
    val totalListeningLabel: String,
    val totalPlays: Int,
    val completionPercent: Int,
    val skipCount: Int,
    val discoveryPercent: Int,
    val peakHourLabel: String,
    val appSplitSummary: String,
    val topGenre: String,
    val topTrackTitle: String,
    val topTrackArtist: String,
    val topTrackPlays: Int,
    val topTrackArtUrl: String?,
    val topArtistName: String,
    val topArtistPhotoUrl: String?,
    val overplayedCallout: String,
    val roastStats: List<ViralRoastStat>,
    val receiptLines: List<ViralReceiptLine>,
    val vibeTrackTitle: String,
    val vibeTrackArtist: String,
    val vibeTrackPlays: Int,
    val vibeSourceApp: String,
    val vibeStatusLine: String,
    val vibeArtUrl: String?,
    val vibeArtistPhotoUrl: String?,
    val shareCaption: String
)

object ViralShareCardEngine {

    fun buildShareCardModel(
        context: Context,
        period: ShareRecapPeriod,
        listenerName: String,
        stats: WindowedListeningStats,
        nowPlaying: NowPlayingObservation?,
        vaultTracks: List<VaultTrack>,
        tasteShift: CrossAppTasteShiftSummary,
        resolvePlayCount: (title: String?, artist: String?, uri: String?) -> Int
    ): ViralShareCardModel {
        val visualRepo = UniversalMusicVisualRepository.getInstance(context)
        val visualsMap = visualRepo.trackVisualsMapFlow.value
        val artistsMap = visualRepo.artistProfilesMapFlow.value

        val cleanName = listenerName.trim().takeIf {
            it.isNotBlank() && !it.equals("Guest Listener", ignoreCase = true) && !it.equals("TuneMind Listener", ignoreCase = true)
        } ?: "You"

        val topTrack = stats.topTracks.firstOrNull()
        val topArtist = stats.topArtists.firstOrNull()
        val topGenre = tasteShift.topGenres.firstOrNull()?.genre
            ?.takeIf { it.isNotBlank() && !it.equals("Music", ignoreCase = true) }
            ?: "Eclectic Mix"

        val topTrackTitle = topTrack?.title?.ifBlank { "No Track Tracked Yet" } ?: "No Track Tracked Yet"
        val topTrackArtist = topTrack?.artist?.ifBlank { "Play across any app" } ?: "Play across any app"
        val topTrackPlays = topTrack?.verifiedPlayCount ?: 0

        val topTrackKey = TrackIdentityResolver.buildNormalizedMatchKey(topTrackTitle, topTrackArtist)
        val topArtistKey = UniversalMusicVisualRepository.normalizeArtistKey(topArtist?.artistName ?: topTrackArtist)
        val topTrackArtUrl = topTrack?.albumArtUrl?.takeIf { it.isNotBlank() }
            ?: visualsMap[topTrackKey]?.albumArtUrl?.takeIf { it.isNotBlank() }
        val topArtistPhotoUrl = artistsMap[topArtistKey]?.imageUrl?.takeIf { it.isNotBlank() }
            ?: visualsMap[topTrackKey]?.artistImageUrl?.takeIf { it.isNotBlank() }
            ?: topTrackArtUrl

        val appBreakdown = stats.appBreakdown.take(3)
        val appSplitSummary = if (appBreakdown.isEmpty()) {
            "Cross-App Ready"
        } else {
            appBreakdown.joinToString(" · ") {
                val pct = (it.shareFraction * 100f).toInt().coerceIn(1, 100)
                "${it.displayName} $pct%"
            }
        }

        val completion = stats.completionRatePercent.coerceIn(0, 100)
        val skips = stats.skippedCount.coerceAtLeast(0)
        val discovery = stats.discoveryRatePercent.coerceIn(0, 100)
        val peakHour = stats.peakHourOfDay
        val peakCompact = stats.peakWindowLabel.substringBefore("(").trim().ifBlank { "Evening" }
        val totalDurationLabel = formatListeningDuration(stats.totalListeningMs)

        // Determine playful, witty, roast-like persona & one-liner from real listening behavior
        val (archetype, roast, tag) = when {
            stats.totalVerifiedPlays == 0 -> Triple(
                "Silent Observer",
                "Zero plays logged yet — press play on Spotify, YouTube Music, or Boomplay so TuneMind can expose your taste.",
                "STANDBY MODE"
            )
            completion in 1..49 && skips >= 4 -> Triple(
                "Serial Skipper",
                "$completion% completion & $skips ruthless skips. Your attention span has trust issues with intros.",
                "SKIP HAPPY"
            )
            peakHour in 0..4 || peakHour >= 23 -> Triple(
                "2AM Overthinker",
                "Peaked at $peakCompact with \"$topTrackTitle\" on standby. Main-character syndrome after midnight.",
                "NIGHT SHIFT"
            )
            topTrackPlays >= 12 -> Triple(
                "One-Song Hostage",
                "Replayed \"$topTrackTitle\" $topTrackPlays times in ${if (period == ShareRecapPeriod.SEVEN_DAYS) "7 days" else "30 days"}. Blink twice if you're stuck.",
                "LOOP ADDICT"
            )
            stats.appBreakdown.size >= 2 && discovery >= 42 -> Triple(
                "Cross-App Nomad",
                "App-hopping across ${stats.appBreakdown.size} players ($appSplitSummary) with $discovery% fresh finds. Zero loyalty, pure taste.",
                "MULTI-APP"
            )
            completion >= 84 -> Triple(
                "Certified Purist",
                "$completion% full-song completion across ${stats.totalVerifiedPlays} plays. Respects the artist's outro every single time.",
                "ZERO SKIPS"
            )
            discovery <= 18 && stats.totalVerifiedPlays >= 6 -> Triple(
                "Comfort Loop Citizen",
                "Only $discovery% new tracks — emotionally attached to the exact same comfort rotation.",
                "COMFORT ZONE"
            )
            else -> Triple(
                "Vibe Architect",
                "$completion% completion, $skips skips, and $topGenre carrying your mood across ${stats.uniqueTrackCount} tracks.",
                "TASTE VERIFIED"
            )
        }

        val obsessionScore = when {
            stats.totalVerifiedPlays == 0 -> 12
            else -> ((topTrackPlays * 6) + (completion / 3) + ((100 - discovery) / 4)).coerceIn(28, 99)
        }
        val verdictBadge = when {
            stats.totalVerifiedPlays == 0 -> "AWAITING DATA"
            topTrackPlays >= 10 -> "LOOP HAZARD"
            completion >= 84 -> "PURIST FLEX"
            skips >= 5 -> "ADHD SHUFFLE"
            discovery >= 45 -> "CRATE DIGGER"
            else -> "MAIN CHARACTER"
        }

        // Most overplayed / vault track roast line
        val coolingVaultTop = vaultTracks.minByOrNull { it.recoveryProgress }
        val overplayedCallout = when {
            coolingVaultTop != null ->
                "IN COOLDOWN: \"${coolingVaultTop.title}\" (${(coolingVaultTop.recoveryProgress * 100).toInt().coerceIn(5, 95)}% rested)"
            topTrack != null && topTrack.isCoolingDown ->
                "NEEDS REST: \"${topTrack.title}\" (${topTrack.freshnessPercent}% fresh)"
            topTrack != null && topTrackPlays >= 5 ->
                "HEAVY LOOP: \"${topTrack.title}\" ($topTrackPlays× plays)"
            else ->
                "TOP ARTIST: ${topArtist?.artistName ?: topTrackArtist}"
        }

        val roastStats = listOf(
            ViralRoastStat(
                label = "COMPLETION",
                value = if (stats.totalVerifiedPlays > 0) "$completion%" else "--",
                roastSubtext = when {
                    stats.totalVerifiedPlays == 0 -> "No data yet"
                    completion >= 82 -> "Outro loyalist"
                    completion >= 60 -> "Selective listener"
                    else -> "$skips ruthless skips"
                }
            ),
            ViralRoastStat(
                label = "PEAK HOUR",
                value = peakCompact,
                roastSubtext = stats.peakWindowLabel.substringAfter("(", "").removeSuffix(")").ifBlank { "Active zone" }
            ),
            ViralRoastStat(
                label = "APP SPLIT",
                value = if (stats.appBreakdown.isNotEmpty()) "${stats.appBreakdown.size} ${if (stats.appBreakdown.size == 1) "App" else "Apps"}" else "All Apps",
                roastSubtext = appSplitSummary
            ),
            ViralRoastStat(
                label = "DISCOVERY",
                value = if (stats.totalVerifiedPlays > 0) "$discovery%" else "--",
                roastSubtext = if (discovery >= 40) "Fresh crate hunter" else "Repeat loyalist"
            )
        )

        val receiptLines = stats.topTracks.take(5).mapIndexed { idx, t ->
            ViralReceiptLine(
                rank = idx + 1,
                title = t.title,
                artist = t.artist,
                plays = t.verifiedPlayCount,
                durationLabel = formatListeningDuration(t.totalListeningMs)
            )
        }

        // Now Playing Vibe Card (live track or fallback to #1 track of period)
        val liveTitle = nowPlaying?.title?.trim().takeIf { !it.isNullOrBlank() && it != "Standing By" }
        val vibeTitle = liveTitle ?: topTrackTitle
        val vibeArtist = if (liveTitle != null) {
            nowPlaying?.artist?.trim().takeIf { !it.isNullOrBlank() } ?: "Music Track"
        } else {
            topTrackArtist
        }
        val vibePlays = if (liveTitle != null) {
            maxOf(
                1,
                resolvePlayCount(liveTitle, vibeArtist, nowPlaying?.spotifyUri)
            )
        } else {
            topTrackPlays
        }
        val vibeSourceApp = if (liveTitle != null) {
            CrossAppPlayRecorder.resolveFriendlyPlayerName(nowPlaying?.sourcePackage.orEmpty())
        } else {
            topTrack?.primaryAppName ?: "TuneMind"
        }

        val vibeKey = TrackIdentityResolver.buildNormalizedMatchKey(vibeTitle, vibeArtist)
        val vibeArtistKey = UniversalMusicVisualRepository.normalizeArtistKey(vibeArtist)
        val vibeArtUrl = visualsMap[vibeKey]?.albumArtUrl?.takeIf { it.isNotBlank() } ?: topTrackArtUrl
        val vibeArtistPhotoUrl = artistsMap[vibeArtistKey]?.imageUrl?.takeIf { it.isNotBlank() }
            ?: visualsMap[vibeKey]?.artistImageUrl?.takeIf { it.isNotBlank() }
            ?: vibeArtUrl

        val vibeStatusLine = when {
            liveTitle != null && nowPlaying?.isPlaying == true && vibePlays >= 6 ->
                "Caught red-handed replaying this $vibePlays times on $vibeSourceApp"
            liveTitle != null && nowPlaying?.isPlaying == true ->
                "Currently locked in on $vibeSourceApp · $completion% song finisher"
            vibePlays >= 8 ->
                "Top obsession of the ${if (period == ShareRecapPeriod.SEVEN_DAYS) "week" else "month"} ($vibePlays plays)"
            else ->
                "Certified rotation highlight via $vibeSourceApp"
        }

        val caption = when (period) {
            ShareRecapPeriod.SEVEN_DAYS ->
                "TuneMind Sunday Roast: I'm a \"$archetype\" — $completion% completion across ${stats.totalVerifiedPlays} plays ($appSplitSummary). Top obsession: $topTrackTitle by $topTrackArtist."
            ShareRecapPeriod.MONTHLY ->
                "TuneMind Monthly Receipt: \"$archetype\" — $totalDurationLabel & ${stats.totalVerifiedPlays} plays ($completion% completion). #1 track: $topTrackTitle."
        }

        return ViralShareCardModel(
            period = period,
            listenerName = cleanName,
            rangeLabel = stats.windowRangeLabel.ifBlank {
                if (period == ShareRecapPeriod.SEVEN_DAYS) "Last 7 Days" else "Last 30 Days"
            },
            archetypeTitle = archetype,
            roastLine = roast,
            personaTag = tag,
            verdictBadge = verdictBadge,
            obsessionScore = obsessionScore,
            totalListeningLabel = totalDurationLabel,
            totalPlays = stats.totalVerifiedPlays,
            completionPercent = completion,
            skipCount = skips,
            discoveryPercent = discovery,
            peakHourLabel = peakCompact,
            appSplitSummary = appSplitSummary,
            topGenre = topGenre,
            topTrackTitle = topTrackTitle,
            topTrackArtist = topTrackArtist,
            topTrackPlays = topTrackPlays,
            topTrackArtUrl = topTrackArtUrl,
            topArtistName = topArtist?.artistName ?: topTrackArtist,
            topArtistPhotoUrl = topArtistPhotoUrl,
            overplayedCallout = overplayedCallout,
            roastStats = roastStats,
            receiptLines = receiptLines,
            vibeTrackTitle = vibeTitle,
            vibeTrackArtist = vibeArtist,
            vibeTrackPlays = vibePlays,
            vibeSourceApp = vibeSourceApp,
            vibeStatusLine = vibeStatusLine,
            vibeArtUrl = vibeArtUrl,
            vibeArtistPhotoUrl = vibeArtistPhotoUrl,
            shareCaption = caption
        )
    }

    suspend fun renderShareCardBitmap(
        context: Context,
        format: ShareCardFormat,
        model: ViralShareCardModel,
        primaryAccentInt: Int,
        secondaryAccentInt: Int
    ): Bitmap = withContext(Dispatchers.IO) {
        val width = 1080
        val height = 1920
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Fetch relevant artwork bitmap cleanly if available
        val artUrlToLoad = when (format) {
            ShareCardFormat.NOW_PLAYING_VIBE -> model.vibeArtUrl ?: model.vibeArtistPhotoUrl
            else -> model.topTrackArtUrl ?: model.topArtistPhotoUrl
        }
        val avatarUrlToLoad = when (format) {
            ShareCardFormat.NOW_PLAYING_VIBE -> model.vibeArtistPhotoUrl
            else -> model.topArtistPhotoUrl
        }
        val artBitmap = loadBitmapViaCoil(context, artUrlToLoad)
        val avatarBitmap = if (avatarUrlToLoad == artUrlToLoad) artBitmap else loadBitmapViaCoil(context, avatarUrlToLoad)

        // 1. Deep Studio Canvas Background + Dual Ambient Glows
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        bgPaint.color = Color.parseColor("#060811")
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val topGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                width * 0.22f,
                height * 0.16f,
                820f,
                intArrayOf(withAlpha(primaryAccentInt, 92), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), topGlow)

        val bottomGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                width * 0.82f,
                height * 0.84f,
                840f,
                intArrayOf(withAlpha(secondaryAccentInt, 84), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bottomGlow)

        // Subtle studio frame
        val frameRect = RectF(56f, 84f, width - 56f, height - 96f)
        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                frameRect.left, frameRect.top,
                frameRect.right, frameRect.bottom,
                Color.parseColor("#111625"),
                Color.parseColor("#0A0D16"),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(frameRect, 54f, 54f, framePaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            shader = LinearGradient(
                frameRect.left, frameRect.top,
                frameRect.right, frameRect.bottom,
                withAlpha(primaryAccentInt, 165),
                withAlpha(secondaryAccentInt, 115),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(frameRect, 54f, 54f, borderPaint)

        // 2. Top Brand Pill + Period Header
        val padX = 112f
        drawPillBadge(
            canvas = canvas,
            x = padX,
            y = 148f,
            text = "TUNEMIND · ${model.period.badgeTitle}",
            bgColor = withAlpha(primaryAccentInt, 42),
            borderColor = withAlpha(primaryAccentInt, 150),
            textColor = Color.parseColor("#F8FAFC"),
            textSize = 26f
        )

        val rangePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(175, 255, 255, 255)
            textSize = 30f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(model.rangeLabel.uppercase(Locale.getDefault()), width - padX, 186f, rangePaint)

        // 3. Render Specific Viral Format Body
        when (format) {
            ShareCardFormat.PERSONALITY_ROAST -> drawPersonalityRoastBody(
                canvas = canvas,
                model = model,
                artBitmap = artBitmap,
                avatarBitmap = avatarBitmap,
                primaryAccent = primaryAccentInt,
                secondaryAccent = secondaryAccentInt,
                padX = padX,
                cardWidth = width.toFloat()
            )

            ShareCardFormat.DNA_RECEIPT -> drawReceiptBody(
                canvas = canvas,
                model = model,
                primaryAccent = primaryAccentInt,
                secondaryAccent = secondaryAccentInt,
                padX = padX,
                cardWidth = width.toFloat()
            )

            ShareCardFormat.NOW_PLAYING_VIBE -> drawVibeBody(
                canvas = canvas,
                model = model,
                artBitmap = artBitmap,
                avatarBitmap = avatarBitmap,
                primaryAccent = primaryAccentInt,
                secondaryAccent = secondaryAccentInt,
                padX = padX,
                cardWidth = width.toFloat()
            )
        }

        // 4. Universal Viral Footer CTA
        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(40, 255, 255, 255)
            strokeWidth = 2f
        }
        canvas.drawLine(padX, 1665f, width - padX, 1665f, dividerPaint)

        val footerTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F8FAFC")
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("Tracked Live by TuneMind", padX, 1732f, footerTitlePaint)

        val footerSubPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(165, 255, 255, 255)
            textSize = 27f
        }
        canvas.drawText("Spotify · YouTube Music · Boomplay · Audiomack", padX, 1776f, footerSubPaint)

        drawPillBadge(
            canvas = canvas,
            x = width - padX - 270f,
            y = 1702f,
            text = "GET YOUR ROAST",
            bgColor = withAlpha(secondaryAccentInt, 48),
            borderColor = withAlpha(secondaryAccentInt, 165),
            textColor = Color.parseColor("#F8FAFC"),
            textSize = 23f
        )

        bitmap
    }

    private fun drawPersonalityRoastBody(
        canvas: Canvas,
        model: ViralShareCardModel,
        artBitmap: Bitmap?,
        avatarBitmap: Bitmap?,
        primaryAccent: Int,
        secondaryAccent: Int,
        padX: Float,
        cardWidth: Float
    ) {
        val innerWidth = (cardWidth - padX * 2).toInt()

        val tagPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primaryAccent
            textSize = 30f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.08f
        }
        canvas.drawText("${model.listenerName.uppercase(Locale.getDefault())}'S AUDIT · ${model.personaTag}", padX, 286f, tagPaint)

        val headlinePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFFF")
            textSize = 84f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val headlineBottom = drawWrappedText(
            canvas = canvas,
            text = model.archetypeTitle,
            paint = headlinePaint,
            x = padX,
            y = 318f,
            maxWidth = innerWidth,
            maxLines = 2
        )

        val roastPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(215, 241, 245, 249)
            textSize = 36f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val roastBottom = drawWrappedText(
            canvas = canvas,
            text = "\"${model.roastLine}\"",
            paint = roastPaint,
            x = padX,
            y = headlineBottom + 24f,
            maxWidth = innerWidth,
            maxLines = 4
        )

        // Hero #1 Track Showcase Box
        val trackTop = (roastBottom + 44f).coerceAtLeast(620f)
        val trackBox = RectF(padX, trackTop, cardWidth - padX, trackTop + 250f)
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(24, 255, 255, 255)
        }
        canvas.drawRoundRect(trackBox, 36f, 36f, boxPaint)

        val boxBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = withAlpha(primaryAccent, 125)
        }
        canvas.drawRoundRect(trackBox, 36f, 36f, boxBorder)

        // #1 Track Artwork on Left, Artist Avatar Badge on Right
        drawCircularArtOrMonogram(
            canvas = canvas,
            bitmap = artBitmap,
            fallbackText = model.topTrackTitle,
            cx = trackBox.left + 112f,
            cy = trackBox.centerY(),
            radius = 74f,
            accentColor = primaryAccent
        )

        val labelSmall = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primaryAccent
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("#1 OBSESSION · ${com.tunemind.app.ui.components.formatCompactPlayCount(model.topTrackPlays)} PLAYS", trackBox.left + 214f, trackBox.top + 68f, labelSmall)

        val trackTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 40f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        drawSingleEllipsizedText(canvas, model.topTrackTitle, trackTitlePaint, trackBox.left + 214f, trackBox.top + 124f, trackBox.width() - 335f)

        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 255, 255, 255)
            textSize = 31f
        }
        drawSingleEllipsizedText(canvas, model.topTrackArtist, artistPaint, trackBox.left + 214f, trackBox.top + 172f, trackBox.width() - 335f)

        drawCircularArtOrMonogram(
            canvas = canvas,
            bitmap = avatarBitmap,
            fallbackText = model.topArtistName,
            cx = trackBox.right - 74f,
            cy = trackBox.centerY(),
            radius = 44f,
            accentColor = secondaryAccent
        )

        // 2x2 Signature Stat Grid
        val gridTop = trackBox.bottom + 36f
        val tileGap = 24f
        val tileW = (innerWidth - tileGap) / 2f
        val tileH = 216f

        model.roastStats.take(4).forEachIndexed { idx, stat ->
            val col = idx % 2
            val row = idx / 2
            val left = padX + col * (tileW + tileGap)
            val top = gridTop + row * (tileH + tileGap)
            val tileRect = RectF(left, top, left + tileW, top + tileH)

            val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(18, 255, 255, 255)
            }
            canvas.drawRoundRect(tileRect, 32f, 32f, tilePaint)

            val tileBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = Color.argb(42, 255, 255, 255)
            }
            canvas.drawRoundRect(tileRect, 32f, 32f, tileBorder)

            val sLabel = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(150, 255, 255, 255)
                textSize = 24f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            canvas.drawText(stat.label, left + 28f, top + 52f, sLabel)

            val sVal = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (idx % 2 == 0) primaryAccent else secondaryAccent
                textSize = 54f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            drawSingleEllipsizedText(canvas, stat.value, sVal, left + 28f, top + 122f, tileW - 56f)

            val sSub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(200, 241, 245, 249)
                textSize = 24f
            }
            drawSingleEllipsizedText(canvas, stat.roastSubtext, sSub, left + 28f, top + 174f, tileW - 56f)
        }

        // Bottom Verdict Strip + Obsession Meter
        val verdictTop = gridTop + tileH * 2 + tileGap + 24f
        val meterBox = RectF(padX, verdictTop, cardWidth - padX, (verdictTop + 122f).coerceAtMost(1635f))
        val meterBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(20, 255, 255, 255) }
        canvas.drawRoundRect(meterBox, 26f, 26f, meterBg)

        val meterLabel = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FBBF24")
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(
            "OBSESSION INDEX · ${model.obsessionScore}% · ${model.verdictBadge}",
            meterBox.left + 26f,
            meterBox.top + 42f,
            meterLabel
        )

        val barLeft = meterBox.left + 26f
        val barRight = meterBox.right - 26f
        val barTop = meterBox.top + 60f
        val barBottom = barTop + 14f
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(38, 255, 255, 255) }
        canvas.drawRoundRect(RectF(barLeft, barTop, barRight, barBottom), 7f, 7f, trackPaint)

        val fillW = (barRight - barLeft) * (model.obsessionScore.coerceIn(5, 100) / 100f)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                barLeft, barTop, barLeft + fillW, barBottom,
                primaryAccent, secondaryAccent, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(RectF(barLeft, barTop, barLeft + fillW, barBottom), 7f, 7f, fillPaint)

        val verdictPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(210, 248, 250, 252)
            textSize = 23f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        drawSingleEllipsizedText(
            canvas = canvas,
            text = "${model.overplayedCallout} · ${model.totalListeningLabel}",
            paint = verdictPaint,
            x = meterBox.left + 26f,
            y = meterBox.bottom - 16f,
            maxWidth = meterBox.width() - 52f
        )
    }

    private fun drawReceiptBody(
        canvas: Canvas,
        model: ViralShareCardModel,
        primaryAccent: Int,
        secondaryAccent: Int,
        padX: Float,
        cardWidth: Float
    ) {
        val innerWidth = (cardWidth - padX * 2).toInt()
        val paperRect = RectF(padX - 18f, 256f, cardWidth - padX + 18f, 1636f)
        val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F6F3EC")
        }
        canvas.drawRoundRect(paperRect, 34f, 34f, paperPaint)

        val inkDark = Color.parseColor("#111827")
        val inkMuted = Color.parseColor("#4B5563")
        val pLeft = paperRect.left + 44f
        val pRight = paperRect.right - 44f
        val pWidth = (pRight - pLeft).toInt()

        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkDark
            textSize = 62f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        canvas.drawText("TUNEMIND RECEIPT", pLeft, 340f, headerPaint)

        val subMono = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkMuted
            textSize = 26f
            typeface = Typeface.MONOSPACE
        }
        val dateText = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date())
        canvas.drawText("ORDER #TM-07 · $dateText", pLeft, 388f, subMono)
        canvas.drawText("LISTENER: ${model.listenerName.uppercase(Locale.getDefault())} · ${model.archetypeTitle.uppercase(Locale.getDefault())}", pLeft, 426f, subMono)

        val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#9CA3AF")
            strokeWidth = 3f
            pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
        }
        canvas.drawLine(pLeft, 458f, pRight, 458f, dashPaint)

        val colPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkDark
            textSize = 24f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        canvas.drawText("QTY  ITEM // ARTIST", pLeft, 504f, colPaint)
        val rightColPaint = TextPaint(colPaint).apply { textAlign = Paint.Align.RIGHT }
        canvas.drawText("PLAYS", pRight, 504f, rightColPaint)

        var y = 576f
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkDark
            textSize = 35f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkMuted
            textSize = 25f
            typeface = Typeface.MONOSPACE
        }
        val countPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkDark
            textSize = 34f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }

        if (model.receiptLines.isEmpty()) {
            val emptyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = inkMuted
                textSize = 32f
                typeface = Typeface.MONOSPACE
            }
            drawWrappedText(
                canvas,
                "Play songs on Spotify, YouTube Music, or Boomplay to print your verified listening receipt.",
                emptyPaint,
                pLeft,
                610f,
                pWidth,
                3
            )
        } else {
            model.receiptLines.forEach { item ->
                canvas.drawText("0${item.rank}", pLeft, y, colPaint.apply { textSize = 30f })
                drawSingleEllipsizedText(canvas, item.title.uppercase(Locale.getDefault()), titlePaint, pLeft + 68f, y, pWidth - 190f)
                canvas.drawText("${com.tunemind.app.ui.components.formatCompactPlayCount(item.plays)}×", pRight, y, countPaint)
                drawSingleEllipsizedText(
                    canvas,
                    "${item.artist} · ${item.durationLabel}",
                    artistPaint,
                    pLeft + 68f,
                    y + 38f,
                    pWidth - 190f
                )
                y += 130f
            }
        }

        val footerDashY = 1255f
        canvas.drawLine(pLeft, footerDashY, pRight, footerDashY, dashPaint)

        val totalLabel = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkMuted
            textSize = 27f
            typeface = Typeface.MONOSPACE
        }
        val totalVal = TextPaint(totalLabel).apply {
            color = inkDark
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        canvas.drawText("TOTAL TIME", pLeft, 1316f, totalLabel)
        canvas.drawText(model.totalListeningLabel, pRight, 1316f, totalVal)

        canvas.drawText("COMPLETION", pLeft, 1364f, totalLabel)
        canvas.drawText("${model.completionPercent}% (${model.skipCount} skips)", pRight, 1364f, totalVal)

        canvas.drawText("APPS", pLeft, 1412f, totalLabel)
        drawSingleEllipsizedText(canvas, model.appSplitSummary, totalVal, pRight, 1412f, pWidth - 180f)

        val alertPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B45309")
            textSize = 25f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        drawSingleEllipsizedText(canvas, model.overplayedCallout, alertPaint, pLeft, 1470f, pWidth.toFloat())

        // Crisp Thermal Barcode + Thank You Stamp
        var bx = pLeft
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = inkDark }
        val pattern = intArrayOf(6, 3, 10, 4, 4, 8, 3, 12, 4, 6, 3, 9, 5, 4, 11, 3, 6, 5, 8, 4)
        var i = 0
        while (bx < pRight - 14f) {
            val w = pattern[i % pattern.size].toFloat()
            if (i % 2 == 0) {
                canvas.drawRect(bx, 1502f, bx + w, 1572f, barPaint)
            }
            bx += w + 6f
            i++
        }
        val thanksPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkMuted
            textSize = 22f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("THANK YOU FOR LISTENING · TUNEMIND.APP", paperRect.centerX(), 1612f, thanksPaint)
    }

    private fun drawVibeBody(
        canvas: Canvas,
        model: ViralShareCardModel,
        artBitmap: Bitmap?,
        avatarBitmap: Bitmap?,
        primaryAccent: Int,
        secondaryAccent: Int,
        padX: Float,
        cardWidth: Float
    ) {
        val innerWidth = (cardWidth - padX * 2).toInt()
        val cx = cardWidth / 2f

        val tagPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primaryAccent
            textSize = 30f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("CURRENT VIBE CHECK · VIA ${model.vibeSourceApp.uppercase(Locale.getDefault())}", cx, 304f, tagPaint)

        // Large Album Cover Disc in Center
        val coverRadius = 260f
        val coverCy = 640f
        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx,
                coverCy,
                340f,
                intArrayOf(withAlpha(primaryAccent, 115), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, coverCy, 340f, haloPaint)

        drawRoundedArtOrMonogram(
            canvas = canvas,
            bitmap = artBitmap,
            fallbackText = model.vibeTrackTitle,
            rect = RectF(cx - coverRadius, coverCy - coverRadius, cx + coverRadius, coverCy + coverRadius),
            cornerRadius = 64f,
            accentColor = primaryAccent
        )

        // Overlapping Artist Avatar Badge on bottom-right corner
        drawCircularArtOrMonogram(
            canvas = canvas,
            bitmap = avatarBitmap,
            fallbackText = model.vibeTrackArtist,
            cx = cx + coverRadius - 30f,
            cy = coverCy + coverRadius - 30f,
            radius = 74f,
            accentColor = secondaryAccent
        )

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 62f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        drawSingleEllipsizedTextCentered(canvas, model.vibeTrackTitle, titlePaint, cx, 1020f, innerWidth.toFloat())

        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(195, 255, 255, 255)
            textSize = 40f
            textAlign = Paint.Align.CENTER
        }
        drawSingleEllipsizedTextCentered(canvas, model.vibeTrackArtist, artistPaint, cx, 1084f, innerWidth.toFloat())

        // Witty Callout Pill Box
        val calloutBox = RectF(padX, 1156f, cardWidth - padX, 1344f)
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(24, 255, 255, 255)
        }
        canvas.drawRoundRect(calloutBox, 34f, 34f, boxPaint)
        val boxBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = withAlpha(secondaryAccent, 140)
        }
        canvas.drawRoundRect(calloutBox, 34f, 34f, boxBorder)

        val statusPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F8FAFC")
            textSize = 35f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        drawWrappedText(
            canvas = canvas,
            text = "\"${model.vibeStatusLine}\"",
            paint = statusPaint,
            x = calloutBox.left + 38f,
            y = calloutBox.top + 46f,
            maxWidth = (calloutBox.width() - 76f).toInt(),
            maxLines = 3
        )

        // Bottom Stat Strip (3 Columns)
        val metricsY = 1480f
        val colW = innerWidth / 3f
        val metricItems = listOf(
            "PLAYS" to "${com.tunemind.app.ui.components.formatCompactPlayCount(model.vibeTrackPlays)}×",
            "FINISHER" to "${model.completionPercent}%",
            "PERSONA" to model.archetypeTitle.substringBefore(" ")
        )
        metricItems.forEachIndexed { idx, (lbl, v) ->
            val mx = padX + colW * idx + colW / 2f
            val mLbl = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(150, 255, 255, 255)
                textSize = 25f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val mVal = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (idx == 1) secondaryAccent else primaryAccent
                textSize = 54f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(lbl, mx, metricsY, mLbl)
            canvas.drawText(v, mx, metricsY + 66f, mVal)
        }
    }

    suspend fun exportShareCardToCacheUri(
        context: Context,
        format: ShareCardFormat,
        model: ViralShareCardModel,
        primaryAccentInt: Int,
        secondaryAccentInt: Int
    ): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = renderShareCardBitmap(
                context = context,
                format = format,
                model = model,
                primaryAccentInt = primaryAccentInt,
                secondaryAccentInt = secondaryAccentInt
            )
            val dir = File(context.cacheDir, "share_cards").apply { mkdirs() }
            // Keep cache tidy (delete older temporary PNGs)
            dir.listFiles()?.forEach { old ->
                if (System.currentTimeMillis() - old.lastModified() > 600_000L) {
                    old.delete()
                }
            }
            val file = File(dir, "tunemind_${format.name.lowercase(Locale.US)}_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 96, out)
            }
            bitmap.recycle()
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }

    suspend fun saveCardToGallery(
        context: Context,
        format: ShareCardFormat,
        model: ViralShareCardModel,
        primaryAccentInt: Int,
        secondaryAccentInt: Int
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = renderShareCardBitmap(
                context = context,
                format = format,
                model = model,
                primaryAccentInt = primaryAccentInt,
                secondaryAccentInt = secondaryAccentInt
            )
            val filename = "TuneMind_${format.shortLabel}_${System.currentTimeMillis()}.png"
            val saved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/TuneMind")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching false
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 96, out)
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } else {
                @Suppress("DEPRECATION")
                val picsDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "TuneMind"
                ).apply { mkdirs() }
                val outFile = File(picsDir, filename)
                FileOutputStream(outFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 96, out)
                }
                true
            }
            bitmap.recycle()
            saved
        }.getOrDefault(false)
    }

    fun copyRoastCaption(context: Context, caption: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("TuneMind Roast", caption))
    }

    fun shareToWhatsApp(context: Context, imageUri: Uri, caption: String) {
        val baseIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, imageUri)
            putExtra(Intent.EXTRA_TEXT, caption)
            clipData = ClipData.newRawUri("TuneMind Card", imageUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val candidates = listOf("com.whatsapp", "com.whatsapp.w4b")
        for (pkg in candidates) {
            val targeted = Intent(baseIntent).apply { setPackage(pkg) }
            if (targeted.resolveActivity(context.packageManager) != null) {
                runCatching {
                    context.startActivity(targeted)
                    return
                }
            }
        }
        // Fallback cleanly to system chooser if WhatsApp isn't installed
        shareToAllSocials(context, imageUri, caption)
    }

    fun shareToAllSocials(context: Context, imageUri: Uri, caption: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, imageUri)
            putExtra(Intent.EXTRA_TEXT, caption)
            clipData = ClipData.newRawUri("TuneMind Card", imageUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, "Post TuneMind Card To...").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private suspend fun loadBitmapViaCoil(context: Context, url: String?): Bitmap? {
        val clean = url?.trim()?.takeIf { it.startsWith("http") } ?: return null
        return runCatching {
            val loader = ImageLoader(context.applicationContext)
            val req = ImageRequest.Builder(context.applicationContext)
                .data(clean)
                .allowHardware(false)
                .size(512)
                .build()
            val result = loader.execute(req) as? SuccessResult ?: return null
            result.drawable.toBitmap()
        }.getOrNull()
    }

    private fun drawPillBadge(
        canvas: Canvas,
        x: Float,
        y: Float,
        text: String,
        bgColor: Int,
        borderColor: Int,
        textColor: Int,
        textSize: Float
    ) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            this.textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val tw = paint.measureText(text)
        val rect = RectF(x, y, x + tw + 44f, y + textSize + 28f)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
        val br = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = borderColor
        }
        canvas.drawRoundRect(rect, 100f, 100f, bg)
        canvas.drawRoundRect(rect, 100f, 100f, br)
        canvas.drawText(text, x + 22f, y + textSize + 10f, paint)
    }

    private fun drawWrappedText(
        canvas: Canvas,
        text: String,
        paint: TextPaint,
        x: Float,
        y: Float,
        maxWidth: Int,
        maxLines: Int
    ): Float {
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, maxWidth.coerceAtLeast(100))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines)
            .setLineSpacing(6f, 1.05f)
            .build()
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
        return y + layout.height
    }

    private fun drawSingleEllipsizedText(
        canvas: Canvas,
        text: String,
        paint: TextPaint,
        x: Float,
        y: Float,
        maxWidth: Float
    ) {
        val ellipsized = android.text.TextUtils.ellipsize(text, paint, maxWidth.coerceAtLeast(40f), android.text.TextUtils.TruncateAt.END).toString()
        canvas.drawText(ellipsized, x, y, paint)
    }

    private fun drawSingleEllipsizedTextCentered(
        canvas: Canvas,
        text: String,
        paint: TextPaint,
        cx: Float,
        y: Float,
        maxWidth: Float
    ) {
        val ellipsized = android.text.TextUtils.ellipsize(text, paint, maxWidth.coerceAtLeast(40f), android.text.TextUtils.TruncateAt.END).toString()
        canvas.drawText(ellipsized, cx, y, paint)
    }

    private fun drawCircularArtOrMonogram(
        canvas: Canvas,
        bitmap: Bitmap?,
        fallbackText: String,
        cx: Float,
        cy: Float,
        radius: Float,
        accentColor: Int
    ) {
        if (bitmap != null) {
            val scaled = Bitmap.createScaledBitmap(bitmap, (radius * 2).toInt(), (radius * 2).toInt(), true)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    val m = android.graphics.Matrix()
                    m.setTranslate(cx - radius, cy - radius)
                    setLocalMatrix(m)
                }
            }
            canvas.drawCircle(cx, cy, radius, paint)
        } else {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(accentColor, 64)
            }
            canvas.drawCircle(cx, cy, radius, fill)
            val letter = fallbackText.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "T"
            val lp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = radius * 0.8f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(letter, cx, cy + radius * 0.28f, lp)
        }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = withAlpha(accentColor, 200)
        }
        canvas.drawCircle(cx, cy, radius, ring)
    }

    private fun drawRoundedArtOrMonogram(
        canvas: Canvas,
        bitmap: Bitmap?,
        fallbackText: String,
        rect: RectF,
        cornerRadius: Float,
        accentColor: Int
    ) {
        if (bitmap != null) {
            val scaled = Bitmap.createScaledBitmap(bitmap, rect.width().toInt(), rect.height().toInt(), true)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    val m = android.graphics.Matrix()
                    m.setTranslate(rect.left, rect.top)
                    setLocalMatrix(m)
                }
            }
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
        } else {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    rect.left, rect.top, rect.right, rect.bottom,
                    withAlpha(accentColor, 110),
                    Color.parseColor("#131A2D"),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fill)
            val letter = fallbackText.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "T"
            val lp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = rect.width() * 0.36f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(letter, rect.centerX(), rect.centerY() + rect.height() * 0.12f, lp)
        }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = withAlpha(accentColor, 190)
        }
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, ring)
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }
}
