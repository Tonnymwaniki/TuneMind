package com.tunemind.app.provider

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tunemind.app.R
import com.tunemind.app.domain.recommendation.RecommendedTrack
import com.tunemind.app.domain.voice.AgentSettingsStore
import com.tunemind.app.service.TuneMindListenerService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cross-App Hybrid Playback Engine across all Android music players
 * (Boomplay, YouTube Music, Audiomack, Spotify passive, Deezer, SoundCloud,
 * local device audio, and TuneMind's built-in audio engine).
 *
 * Hybrid Tiered Strategy (zero Spotify Developer keys / OAuth / App Remote SDK / BAL overlay hacks):
 * 1A. Local Audio (`MediaStore.Audio.Media`) -> Full track inside [TuneMindInAppPlayer] (zero switch).
 * 1B. Open-Catalog Full Stream (Audius API) -> Full track inside [TuneMindInAppPlayer] (zero switch).
 * 1C. Audience-Gated Visible YouTube Embed -> Full track inside [TuneMindYouTubeEmbedController]
 *     ONLY when target/default is YouTube Music (or explicitly allowed in-app by the user).
 * 2. Active Warm MediaSession -> `playFromSearch(query, extras)` + title-only retry (zero switch).
 * 3. Strict Never-Leave-TuneMind Mode -> 30s iTunes/Deezer preview inside [TuneMindInAppPlayer]
 *    plus metadata for "Open full track in [Player]" (or YouTube in-app prompt).
 * 4. Hybrid Cold Handoff (when strict mode is OFF) -> Exact YouTube Music watch URL / Boomplay /
 *    Audiomack / Spotify passive `MEDIA_PLAY_FROM_SEARCH` intent + a Play-safe Heads-Up
 *    "[Back to TuneMind]" banner that peeks cleanly and updates once playback starts.
 */
object MusicPlayerAutoLauncher {
    private const val TAG = "MusicPlayerAutoLauncher"
    private const val GOOGLE_ASSISTANT_REFERRER =
        "android-app://com.google.android.googlequicksearchbox/https/www.google.com"
    private const val RETURN_CHANNEL_ID = "tunemind_quick_return_banner_v2"
    private const val RETURN_NOTIFICATION_ID = 4409
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    var lastDispatchedTrackTitle: String = ""
        private set

    @Volatile
    var lastDispatchedAtMs: Long = 0L
        private set

    fun wasRecentlyDispatched(title: String?, windowMs: Long = 12_000L): Boolean {
        val clean = title?.trim().orEmpty()
        val last = lastDispatchedTrackTitle.trim()
        if (clean.isBlank() || last.isBlank()) return false
        if ((System.currentTimeMillis() - lastDispatchedAtMs) > windowMs) return false
        return clean.equals(last, ignoreCase = true) ||
            clean.contains(last, ignoreCase = true) ||
            last.contains(clean, ignoreCase = true)
    }

    /**
     * Strips noisy suffixes like ", Tenzi No 22.", "(Official Video)", "[Lyrics]", etc.
     */
    fun sanitizeTrackTitleForPlay(rawTitle: String): String {
        return rawTitle
            .replace(Regex("\\s*\\((official\\s*(music\\s*)?(video|audio|lyric\\s*video|visualizer)|lyrics?|audio|hd|4k|live.*?)\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[(official\\s*(music\\s*)?(video|audio|lyric\\s*video|visualizer)|lyrics?|audio|hd|4k|live.*?)\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex(",\\s*tenzi\\s*no\\.?\\s*\\d+\\.?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+-\\s+(official\\s*(music\\s*)?(video|audio)|lyrics?)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[.\"'`]+$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun sanitizeArtistForPlay(rawArtist: String): String {
        val cleaned = rawArtist.trim()
        if (cleaned.equals("Unknown Artist", ignoreCase = true) || cleaned.equals("Spotify", ignoreCase = true)) {
            return ""
        }
        return cleaned
            .split(Regex("[,&]|feat\\.?|ft\\.?", RegexOption.IGNORE_CASE))
            .firstOrNull()
            ?.replace(Regex("[.\"'`]+$"), "")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?: ""
    }

    /**
     * Opens the full track explicitly in the requested external player (e.g. from the
     * "Open full track in [Player]" button while listening to an in-app 30s preview).
     */
    fun openFullTrackExternally(
        context: Context,
        title: String,
        artist: String,
        packageName: String?,
        displayName: String,
        explicitUrlOrDeepLink: String? = null
    ): PlaybackLaunchResult {
        TuneMindInAppPlayer.pause()
        TuneMindYouTubeEmbedController.pause()
        val rec = RecommendedTrack(
            id = title,
            title = title,
            artist = artist,
            reason = "User requested full track in $displayName",
            confidence = 1.0f
        )
        val cleanTitle = sanitizeTrackTitleForPlay(title)
        val cleanArtist = sanitizeArtistForPlay(artist)
        val fullQuery = listOf(cleanTitle, cleanArtist).filter { it.isNotBlank() }.joinToString(" ")
        val outcome = launchExternalColdHandoff(
            context = context,
            targetPkg = packageName?.takeIf { it != "*" && it.isNotBlank() },
            displayName = displayName.ifBlank { "your music app" },
            track = rec,
            cleanTitle = cleanTitle,
            cleanArtist = cleanArtist,
            fullSearchQuery = fullQuery,
            deepLinkUri = explicitUrlOrDeepLink,
            previousTitle = null,
            resolvedLinks = UniversalTrackResolver.getCached(cleanTitle, cleanArtist)
        )
        return if (outcome.launched) {
            PlaybackLaunchResult.DirectPlaybackLaunched(
                providerId = packageName ?: "external",
                providerDisplayName = displayName,
                trackTitle = cleanTitle.ifBlank { title },
                destinationUri = outcome.destinationUri ?: "external:$packageName",
                message = outcome.detailMessage ?: "Opened '$cleanTitle' in $displayName."
            )
        } else {
            PlaybackLaunchResult.LaunchFailed(
                providerId = packageName ?: "external",
                reason = outcome.detailMessage ?: "Could not open '$cleanTitle' in $displayName."
            )
        }
    }

    fun launchWithAutoPlay(
        context: Context,
        providerId: String,
        displayName: String,
        packageName: String?,
        track: RecommendedTrack,
        query: String,
        deepLinkUri: String? = null,
        webFallbackUrl: String? = null,
        neverOpenExternalApp: Boolean = false
    ): PlaybackLaunchResult {
        val targetPkg = if (packageName == "*" || packageName.isNullOrBlank()) null else packageName
        val listener = TuneMindListenerService.instance
        val previousTitle = listener?.getActiveTitle(targetPkg)?.trim()

        // 0. Silence any currently playing song before switching
        try {
            TuneMindInAppPlayer.pause()
            TuneMindYouTubeEmbedController.pause()
            listener?.pause()
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        } catch (e: Exception) {
            Log.d(TAG, "Audio pause interruption note: ${e.message}")
        }

        val cleanTitle = sanitizeTrackTitleForPlay(track.title.ifBlank { query })
        val cleanArtist = sanitizeArtistForPlay(track.artist)
        lastDispatchedTrackTitle = cleanTitle.ifBlank { track.title.trim() }
        lastDispatchedAtMs = System.currentTimeMillis()
        val fullSearchQuery = if (cleanArtist.isNotBlank() && !cleanTitle.contains(cleanArtist, ignoreCase = true)) {
            "$cleanTitle $cleanArtist".trim()
        } else {
            cleanTitle.ifBlank { sanitizeTrackTitleForPlay(query) }
        }

        val outcome = launchHybridPlayback(
            context = context,
            providerId = providerId,
            targetPkg = targetPkg,
            displayName = displayName,
            track = track,
            cleanTitle = cleanTitle,
            cleanArtist = cleanArtist,
            fullSearchQuery = fullSearchQuery,
            deepLinkUri = deepLinkUri ?: webFallbackUrl,
            previousTitle = previousTitle,
            neverOpenExternalApp = neverOpenExternalApp
        )

        return if (outcome.launched) {
            PlaybackLaunchResult.DirectPlaybackLaunched(
                providerId = if (outcome.usedInAppPlayer) TuneMindInAppProvider.PROVIDER_ID else providerId,
                providerDisplayName = if (outcome.usedInAppPlayer) "TuneMind In-App Player" else displayName,
                trackTitle = cleanTitle.ifBlank { track.title },
                destinationUri = outcome.destinationUri ?: deepLinkUri ?: "intent:${targetPkg ?: "active"}",
                message = outcome.detailMessage ?: "Playing '${cleanTitle.ifBlank { track.title }}' cleanly."
            )
        } else {
            PlaybackLaunchResult.LaunchFailed(
                providerId = providerId,
                reason = outcome.detailMessage ?: "Could not start playback in $displayName."
            )
        }
    }

    private data class LaunchOutcome(
        val launched: Boolean,
        val usedInAppPlayer: Boolean = false,
        val destinationUri: String? = null,
        val detailMessage: String? = null
    )

    internal fun isYouTubeMusicTarget(targetPkg: String?, providerId: String?): Boolean {
        return targetPkg?.contains("youtube.music", ignoreCase = true) == true ||
            providerId.equals(YouTubeMusicProvider.PROVIDER_ID, ignoreCase = true)
    }

    internal fun shouldActivateBuiltInYouTubeTier(
        targetPkg: String?,
        providerId: String?,
        ytmVideoId: String?,
        preferInAppPlayer: Boolean,
        strictNoSwitch: Boolean
    ): Boolean {
        return (preferInAppPlayer || strictNoSwitch) &&
            isYouTubeMusicTarget(targetPkg, providerId) &&
            !ytmVideoId.isNullOrBlank()
    }

    private fun launchHybridPlayback(
        context: Context,
        providerId: String,
        targetPkg: String?,
        displayName: String,
        track: RecommendedTrack,
        cleanTitle: String,
        cleanArtist: String,
        fullSearchQuery: String,
        deepLinkUri: String?,
        previousTitle: String?,
        neverOpenExternalApp: Boolean
    ): LaunchOutcome {
        val stayInTuneMind = AgentSettingsStore.isStayInTuneMindEnabled(context)
        val preferInAppPlayer = AgentSettingsStore.isPreferInAppPlayerEnabled(context)
        val strictNoSwitch = neverOpenExternalApp ||
            AgentSettingsStore.isStrictNoAppSwitchEnabled(context)
        val isYtmTarget = isYouTubeMusicTarget(targetPkg, providerId)

        // TIER 1A: Local Audio Match inside TuneMind (full length, zero app switch)
        if ((preferInAppPlayer || strictNoSwitch) && TuneMindInAppPlayer.playLocalMatch(context, cleanTitle, cleanArtist)) {
            val localUri = TuneMindInAppPlayer.currentUriFlow.value
            Log.i(TAG, "✅ Tier-1A In-App Local Playback started for '$cleanTitle'")
            return LaunchOutcome(
                launched = true,
                usedInAppPlayer = true,
                destinationUri = localUri,
                detailMessage = "Playing '$cleanTitle' from phone audio inside TuneMind."
            )
        }

        // Resolve Open Catalog Links (Audius stream, YouTube Music videoId, Deezer URL, 30s preview)
        val resolvedLinks = UniversalTrackResolver.resolveTrackBlocking(
            context = context,
            cleanTitle = cleanTitle,
            cleanArtist = cleanArtist,
            fullQuery = fullSearchQuery
        )

        // TIER 1B: Open-Catalog Full Track Stream via Audius (when preferInAppPlayer or strict mode is on)
        val audiusStream = resolvedLinks?.audiusStreamUrl?.takeIf { it.startsWith("http") }
        if ((preferInAppPlayer || strictNoSwitch) && audiusStream != null) {
            if (TuneMindInAppPlayer.playStreamUrl(
                    context = context,
                    streamUrl = audiusStream,
                    title = cleanTitle.ifBlank { track.title },
                    artist = cleanArtist.ifBlank { track.artist },
                    album = resolvedLinks.albumName,
                    expectedDurationMs = 0L,
                    isPreview = false
                )
            ) {
                Log.i(TAG, "✅ Tier-1B Audius full stream started inside TuneMind for '$cleanTitle'")
                return LaunchOutcome(
                    launched = true,
                    usedInAppPlayer = true,
                    destinationUri = audiusStream,
                    detailMessage = "Streaming '$cleanTitle' inside TuneMind without leaving the app."
                )
            }
        }

        // TIER 1C (Option A — Audience-Gated): Visible Official YouTube IFrame Player inside TuneMind
        // Activates specifically for YouTube Music users (or when user explicitly opted to allow YouTube in-app).
        val ytmVideoId = resolvedLinks?.ytmVideoId?.takeIf { it.isNotBlank() }
        if (shouldActivateBuiltInYouTubeTier(targetPkg, providerId, ytmVideoId, preferInAppPlayer, strictNoSwitch)) {
            val started = TuneMindYouTubeEmbedController.startVideo(
                context = context,
                videoId = ytmVideoId!!,
                title = cleanTitle.ifBlank { track.title },
                artist = cleanArtist.ifBlank { track.artist },
                album = resolvedLinks.albumName,
                previewFallback = resolvedLinks.previewUrl,
                fallbackExternalPkg = targetPkg ?: YouTubeMusicProvider.YTM_PACKAGE,
                fallbackExternalName = displayName
            )
            if (started) {
                Log.i(TAG, "✅ Tier-1C Built-In Visible YouTube Player started for '$cleanTitle' ($ytmVideoId)")
                return LaunchOutcome(
                    launched = true,
                    usedInAppPlayer = true,
                    destinationUri = "https://music.youtube.com/watch?v=$ytmVideoId",
                    detailMessage = "Playing '$cleanTitle' inside TuneMind's built-in YouTube player."
                )
            }
        }

        // TIER 2: Warm Active MediaSession (boomplay / youtube music / audiomack / spotify passive / OEM)
        if ((stayInTuneMind || strictNoSwitch) &&
            tryWarmMediaSessionSwitch(targetPkg, displayName, cleanTitle, cleanArtist, fullSearchQuery, previousTitle)
        ) {
            return LaunchOutcome(
                launched = true,
                usedInAppPlayer = false,
                destinationUri = "mediasession:${targetPkg ?: "active"}",
                detailMessage = "Playing '$cleanTitle' via active $displayName session without leaving TuneMind."
            )
        }

        // TIER 3 (Strict Never-Leave Mode): 30s Preview inside TuneMind (with external player metadata + YouTube hint)
        if (strictNoSwitch) {
            val ytmInstalled = !isYtmTarget && YouTubeMusicProvider().isInstalled(context)
            val previewUrl = resolvedLinks?.previewUrl?.takeIf { it.startsWith("http") }
            if (previewUrl != null && TuneMindInAppPlayer.playStreamUrl(
                    context = context,
                    streamUrl = previewUrl,
                    title = cleanTitle.ifBlank { track.title },
                    artist = cleanArtist.ifBlank { track.artist },
                    album = resolvedLinks.albumName,
                    expectedDurationMs = 30_000L,
                    isPreview = true,
                    fallbackExternalPkg = targetPkg,
                    fallbackExternalName = displayName
                )
            ) {
                val extraHint = if (ytmInstalled) {
                    "Say 'use YouTube inside TuneMind' for the full song, or tap 'Open full track in $displayName'."
                } else {
                    "Tap 'Open full track in $displayName' for the full song."
                }
                return LaunchOutcome(
                    launched = true,
                    usedInAppPlayer = true,
                    destinationUri = previewUrl,
                    detailMessage = "Playing '$cleanTitle' (30s preview) inside TuneMind · $extraHint"
                )
            }
            val failHint = if (ytmInstalled) {
                "Kept $displayName closed (Never-Leave Mode). Say 'use YouTube inside TuneMind' for the full song here, start a track in $displayName once to warm its session, or open $displayName."
            } else {
                "Kept $displayName closed (Never-Leave Mode). Start a track in $displayName once to warm its background session, or disable Strict Mode for quick handoff."
            }
            return LaunchOutcome(
                launched = false,
                usedInAppPlayer = false,
                detailMessage = failHint
            )
        }

        // TIER 4 (Hybrid Cold Handoff): Precision deep-link / MEDIA_PLAY_FROM_SEARCH + Heads-Up Return Banner
        return launchExternalColdHandoff(
            context = context,
            targetPkg = targetPkg,
            displayName = displayName,
            track = track,
            cleanTitle = cleanTitle,
            cleanArtist = cleanArtist,
            fullSearchQuery = fullSearchQuery,
            deepLinkUri = deepLinkUri,
            previousTitle = previousTitle,
            resolvedLinks = resolvedLinks
        )
    }

    private fun tryWarmMediaSessionSwitch(
        targetPkg: String?,
        displayName: String,
        cleanTitle: String,
        cleanArtist: String,
        fullSearchQuery: String,
        previousTitle: String?
    ): Boolean {
        return try {
            val listener = TuneMindListenerService.instance ?: return false
            if (fullSearchQuery.isBlank() || !listener.hasActiveSession(targetPkg)) return false
            val sessionExtras = buildAutoPlayExtras(cleanTitle, cleanArtist, fullSearchQuery)
            val sent = listener.playFromSearch(targetPkg, fullSearchQuery, sessionExtras)
            if (!sent) return false

            val normExpected = UniversalTrackResolver.normalizeTitleForExactMatch(cleanTitle)
            var nudgedPlay = false
            var retriedTitleOnly = false

            for (attempt in 0 until 3) {
                val waitMs = when (attempt) {
                    0 -> 450L
                    1 -> 500L
                    else -> 550L
                }
                try {
                    Thread.sleep(waitMs)
                } catch (_: InterruptedException) {}

                val activeTitle = listener.getActiveTitle(targetPkg)?.trim().orEmpty()
                val normActive = UniversalTrackResolver.normalizeTitleForExactMatch(activeTitle)
                val switched = normActive.isNotBlank() && (
                    normActive == normExpected ||
                        (normExpected.length >= 4 && (normActive.contains(normExpected) || normExpected.contains(normActive))) ||
                        (!previousTitle.isNullOrBlank() && !activeTitle.equals(previousTitle, ignoreCase = true))
                    )
                if (switched) {
                    if (listener.isPlaying(targetPkg)) {
                        Log.i(TAG, "✅ Tier-2 Warm MediaSession switched silently for '$cleanTitle' in $displayName")
                        return true
                    } else if (!nudgedPlay) {
                        nudgedPlay = true
                        listener.play(targetPkg)
                    }
                } else if (!retriedTitleOnly && cleanTitle.isNotBlank() && cleanArtist.isNotBlank()) {
                    retriedTitleOnly = true
                    listener.playFromSearch(
                        targetPkg,
                        cleanTitle,
                        buildAutoPlayExtras(cleanTitle, "", cleanTitle)
                    )
                }
            }
            false
        } catch (e: Exception) {
            Log.d(TAG, "Warm MediaSession playFromSearch notice: ${e.message}")
            false
        }
    }

    private fun launchExternalColdHandoff(
        context: Context,
        targetPkg: String?,
        displayName: String,
        track: RecommendedTrack,
        cleanTitle: String,
        cleanArtist: String,
        fullSearchQuery: String,
        deepLinkUri: String?,
        previousTitle: String?,
        resolvedLinks: ResolvedPlayableLinks?
    ): LaunchOutcome {
        val isYtmTarget = targetPkg?.contains("youtube.music", ignoreCase = true) == true
        val precisionWatchUrl = if (isYtmTarget && !resolvedLinks?.ytmVideoId.isNullOrBlank()) {
            "https://music.youtube.com/watch?v=${resolvedLinks!!.ytmVideoId}"
        } else null

        // A. Exact YouTube Music watch URL if resolved
        if (!precisionWatchUrl.isNullOrBlank()) {
            try {
                val ytmIntent = Intent(Intent.ACTION_VIEW, Uri.parse(precisionWatchUrl)).apply {
                    if (targetPkg != null) setPackage(targetPkg)
                    putExtra(Intent.EXTRA_REFERRER, Uri.parse(GOOGLE_ASSISTANT_REFERRER))
                    putExtra("android.intent.extra.START_PLAYBACK", true)
                    putExtra("autostart", true)
                    putExtra("play", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(ytmIntent)
                scheduleAutoPlayKicker(context, targetPkg, displayName, track, fullSearchQuery, previousTitle)
                postReturnToTuneMindNotification(context, cleanTitle.ifBlank { track.title }, displayName)
                return LaunchOutcome(
                    launched = true,
                    usedInAppPlayer = false,
                    destinationUri = precisionWatchUrl,
                    detailMessage = "Opened exact track '$cleanTitle' in $displayName."
                )
            } catch (e: Exception) {
                Log.d(TAG, "Precision YTM watch link failed, trying search intent: ${e.message}")
            }
        }

        // B. Standard Android MEDIA_PLAY_FROM_SEARCH for Boomplay / Spotify Passive / Audiomack / YTM / OEM players
        if (targetPkg != null && fullSearchQuery.isNotBlank()) {
            try {
                val mediaPlayIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    putExtra(SearchManager.QUERY, fullSearchQuery)
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
                    if (cleanTitle.isNotBlank()) putExtra(MediaStore.EXTRA_MEDIA_TITLE, cleanTitle)
                    if (cleanArtist.isNotBlank()) putExtra(MediaStore.EXTRA_MEDIA_ARTIST, cleanArtist)
                    putExtra(Intent.EXTRA_REFERRER, Uri.parse(GOOGLE_ASSISTANT_REFERRER))
                    putExtra("android.intent.extra.REFERRER_NAME", GOOGLE_ASSISTANT_REFERRER)
                    putExtra("android.intent.extra.START_PLAYBACK", true)
                    putExtra("autostart", true)
                    putExtra("play", true)
                    setPackage(targetPkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(mediaPlayIntent)
                scheduleAutoPlayKicker(context, targetPkg, displayName, track, fullSearchQuery, previousTitle)
                postReturnToTuneMindNotification(context, cleanTitle.ifBlank { track.title }, displayName)
                return LaunchOutcome(
                    launched = true,
                    usedInAppPlayer = false,
                    destinationUri = "search:$targetPkg",
                    detailMessage = "Playing '$cleanTitle' in $displayName."
                )
            } catch (e: Exception) {
                Log.d(TAG, "MEDIA_PLAY_FROM_SEARCH not accepted by $targetPkg: ${e.message}")
            }
        }

        // C. Explicit Deep Link / Deezer / Web Search Fallback
        val fallbackLink = deepLinkUri ?: resolvedLinks?.deezerUrl
        if (!fallbackLink.isNullOrBlank()) {
            try {
                val uriIntent = Intent(Intent.ACTION_VIEW, Uri.parse(fallbackLink)).apply {
                    if (targetPkg != null && !fallbackLink.startsWith("https://www.deezer.com")) {
                        setPackage(targetPkg)
                    }
                    putExtra(Intent.EXTRA_REFERRER, Uri.parse(GOOGLE_ASSISTANT_REFERRER))
                    putExtra("android.intent.extra.START_PLAYBACK", true)
                    putExtra("autostart", true)
                    putExtra("play", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(uriIntent)
                scheduleAutoPlayKicker(context, targetPkg, displayName, track, fullSearchQuery, previousTitle)
                postReturnToTuneMindNotification(context, cleanTitle.ifBlank { track.title }, displayName)
                return LaunchOutcome(
                    launched = true,
                    usedInAppPlayer = false,
                    destinationUri = fallbackLink,
                    detailMessage = "Opened '$cleanTitle' in $displayName."
                )
            } catch (e: Exception) {
                Log.w(TAG, "Deep link handoff failed: ${e.message}")
            }
        }

        return LaunchOutcome(
            launched = false,
            detailMessage = "Could not launch playback for '$cleanTitle' in $displayName."
        )
    }

    fun cancelReturnNotification(context: Context) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(RETURN_NOTIFICATION_ID)
        }
    }

    /**
     * Play-safe high-priority Heads-Up banner shown after launching an external player so users
     * of Spotify, Boomplay, Audiomack, Deezer, or any Android player get a prominent floating peek
     * banner with a 1-tap "Back to TuneMind" action right at the top of the screen (zero BAL/overlay risks).
     */
    private fun postReturnToTuneMindNotification(
        context: Context,
        trackTitle: String,
        playerDisplayName: String,
        confirmedPlaying: Boolean = false,
        silentUpdate: Boolean = false
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) return
            }

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    RETURN_CHANNEL_ID,
                    "Quick Return to TuneMind",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Floating Heads-Up return pill when TuneMind starts a song in Spotify, Boomplay, Audiomack, or other players."
                    val attrs = AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build()
                    setSound(null, attrs)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0L, 18L)
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(channel)
            }

            val returnIntent = Intent(context, com.tunemind.app.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pi = PendingIntent.getActivity(
                context,
                RETURN_NOTIFICATION_ID,
                returnIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val titleText = if (confirmedPlaying) {
                "Now playing '$trackTitle' in $playerDisplayName"
            } else {
                "Starting '$trackTitle' in $playerDisplayName"
            }
            val bodyText = if (confirmedPlaying) {
                "Tap Back to TuneMind — $playerDisplayName stays playing"
            } else {
                "Tap to return to TuneMind anytime"
            }

            val builder = NotificationCompat.Builder(context, RETURN_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(titleText)
                .setContentText(bodyText)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(silentUpdate)
                .setAutoCancel(true)
                .setTimeoutAfter(28_000L)
                .setContentIntent(pi)
                .addAction(
                    R.drawable.ic_launcher_foreground,
                    "Back to TuneMind",
                    pi
                )

            if (!silentUpdate) {
                builder.setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            }

            nm.notify(RETURN_NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            Log.d(TAG, "Return notification skipped: ${e.message}")
        }
    }

    private fun buildAutoPlayExtras(
        cleanTitle: String,
        cleanArtist: String,
        query: String
    ): Bundle {
        return Bundle().apply {
            putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
            if (cleanTitle.isNotBlank()) putString(MediaStore.EXTRA_MEDIA_TITLE, cleanTitle)
            if (cleanArtist.isNotBlank()) putString(MediaStore.EXTRA_MEDIA_ARTIST, cleanArtist)
            putString(SearchManager.QUERY, query)
            putBoolean("android.intent.extra.START_PLAYBACK", true)
            putBoolean("autostart", true)
            putBoolean("play", true)
        }
    }

    /**
     * Gentle post-launch verification kicker: sends `playFromSearch` once the target player
     * registers its MediaSession after cold boot, calls `play()` once the active title matches
     * the newly requested song, and refreshes the Quick-Return banner once playback starts cleanly.
     */
    fun scheduleAutoPlayKicker(
        context: Context,
        targetPackage: String?,
        displayName: String,
        track: RecommendedTrack,
        overrideQuery: String? = null,
        previousTitle: String? = null
    ) {
        val cleanTitle = sanitizeTrackTitleForPlay(track.title)
        val cleanArtist = sanitizeArtistForPlay(track.artist)
        val query = overrideQuery?.takeIf { it.isNotBlank() }
            ?: listOf(cleanTitle, cleanArtist).filter { it.isNotBlank() }.joinToString(" ")

        val extras = buildAutoPlayExtras(cleanTitle, cleanArtist, query)
        val normExpected = UniversalTrackResolver.normalizeTitleForExactMatch(cleanTitle)
        val normPrev = UniversalTrackResolver.normalizeTitleForExactMatch(previousTitle.orEmpty())
        val confirmedReturnNotified = AtomicBoolean(false)

        for (delay in listOf(450L, 1100L)) {
            mainHandler.postDelayed({
                try {
                    val listener = TuneMindListenerService.instance ?: return@postDelayed
                    val curTitle = listener.getActiveTitle(targetPackage)?.trim().orEmpty()
                    val normCur = UniversalTrackResolver.normalizeTitleForExactMatch(curTitle)
                    val alreadyTarget = normCur.isNotBlank() && normExpected.isNotBlank() && (
                        normCur == normExpected ||
                            normCur.contains(normExpected) ||
                            normExpected.contains(normCur)
                        )
                    if (!alreadyTarget && query.isNotBlank()) {
                        listener.playFromSearch(targetPackage, query, extras)
                    }
                } catch (_: Exception) {}
            }, delay)
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        for (delay in listOf(1450L, 2150L, 3000L)) {
            mainHandler.postDelayed({
                try {
                    val listener = TuneMindListenerService.instance
                    val curTitle = listener?.getActiveTitle(targetPackage)?.trim().orEmpty()
                    val normCur = UniversalTrackResolver.normalizeTitleForExactMatch(curTitle)
                    val titleMatchesNewSong = normCur.isNotBlank() && (
                        (normExpected.isNotBlank() && (normCur == normExpected || normCur.contains(normExpected) || normExpected.contains(normCur))) ||
                            (normPrev.isNotBlank() && normCur != normPrev && delay >= 1450L)
                        )
                    if (titleMatchesNewSong) {
                        val playingTarget = listener?.isPlaying(targetPackage) == true
                        if (!playingTarget) {
                            listener?.play(targetPackage)
                            audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                            audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
                        } else if (confirmedReturnNotified.compareAndSet(false, true)) {
                            postReturnToTuneMindNotification(
                                context = context,
                                trackTitle = cleanTitle.ifBlank { track.title },
                                playerDisplayName = displayName,
                                confirmedPlaying = true,
                                silentUpdate = true
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Auto-play verification kicker notice: ${e.message}")
                }
            }, delay)
        }
    }
}
