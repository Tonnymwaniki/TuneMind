package com.tunemind.app.provider

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

/**
 * Cross-App Hybrid Playback Engine across all Android music players
 * (Boomplay, YouTube Music, Audiomack, Spotify passive, Deezer, SoundCloud,
 * local device audio, and TuneMind's built-in audio engine).
 *
 * Hybrid Tiered Strategy (zero Spotify Developer keys / OAuth / App Remote SDK / BAL overlay hacks):
 * 1. Local Audio (`MediaStore.Audio.Media`) -> Full track inside [TuneMindInAppPlayer] (zero switch).
 * 2. Open-Catalog Full Stream (Audius API) -> Full track inside [TuneMindInAppPlayer] (zero switch).
 * 3. Active Warm MediaSession -> `playFromSearch(query, extras)` + transition check (zero switch).
 * 4. Strict Never-Leave-TuneMind Mode -> 30s iTunes/Deezer preview inside [TuneMindInAppPlayer]
 *    plus metadata for "Open full track in [Player]".
 * 5. Hybrid Cold Handoff (when strict mode is OFF) -> Exact YouTube Music watch URL / Boomplay /
 *    Audiomack / Spotify passive `MEDIA_PLAY_FROM_SEARCH` intent + a Play-compliant notification
 *    ("Playing in [Player] · Tap to return to TuneMind").
 */
object MusicPlayerAutoLauncher {
    private const val TAG = "MusicPlayerAutoLauncher"
    private const val GOOGLE_ASSISTANT_REFERRER =
        "android-app://com.google.android.googlequicksearchbox/https/www.google.com"
    private const val RETURN_CHANNEL_ID = "tunemind_playback_return"
    private const val RETURN_NOTIFICATION_ID = 4409
    private val mainHandler = Handler(Looper.getMainLooper())

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

    private fun launchHybridPlayback(
        context: Context,
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

        val sessionExtras = buildAutoPlayExtras(cleanTitle, cleanArtist, fullSearchQuery)

        // TIER 2: Warm Active MediaSession (boomplay / youtube music / audiomack / spotify passive / OEM)
        if (stayInTuneMind || strictNoSwitch) {
            try {
                val listener = TuneMindListenerService.instance
                if (listener != null && fullSearchQuery.isNotBlank() && listener.hasActiveSession(targetPkg)) {
                    val sent = listener.playFromSearch(targetPkg, fullSearchQuery, sessionExtras)
                    if (sent) {
                        val normExpected = UniversalTrackResolver.normalizeTitleForExactMatch(cleanTitle)
                        var verifiedPlaying = false
                        var nudgedPlay = false
                        for (waitMs in listOf(350L, 450L, 500L)) {
                            try {
                                Thread.sleep(waitMs)
                            } catch (_: InterruptedException) {}
                            val activeTitle = listener.getActiveTitle(targetPkg)?.trim().orEmpty()
                            val normActive = UniversalTrackResolver.normalizeTitleForExactMatch(activeTitle)
                            val switched = normActive.isNotBlank() && (
                                normActive == normExpected ||
                                    normActive.contains(normExpected) ||
                                    normExpected.contains(normActive) ||
                                    (!previousTitle.isNullOrBlank() && !activeTitle.equals(previousTitle, ignoreCase = true))
                                )
                            if (switched) {
                                if (listener.isPlaying()) {
                                    verifiedPlaying = true
                                    break
                                } else if (!nudgedPlay) {
                                    nudgedPlay = true
                                    listener.play(targetPkg)
                                }
                            }
                        }
                        if (verifiedPlaying) {
                            Log.i(TAG, "✅ Tier-2 Warm MediaSession switched silently for '$cleanTitle' in $displayName")
                            return LaunchOutcome(
                                launched = true,
                                usedInAppPlayer = false,
                                destinationUri = "mediasession:${targetPkg ?: "active"}",
                                detailMessage = "Playing '$cleanTitle' via active $displayName session without leaving TuneMind."
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Warm MediaSession playFromSearch notice: ${e.message}")
            }
        }

        // TIER 3 (Strict Never-Leave Mode): 30s Preview inside TuneMind (with external player metadata for "Open full track")
        if (strictNoSwitch) {
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
                return LaunchOutcome(
                    launched = true,
                    usedInAppPlayer = true,
                    destinationUri = previewUrl,
                    detailMessage = "Playing '$cleanTitle' (30s preview) inside TuneMind · Tap 'Open in $displayName' for full track."
                )
            }
            return LaunchOutcome(
                launched = false,
                usedInAppPlayer = false,
                detailMessage = "Kept $displayName closed (Never-Leave Mode). Start a track in $displayName once to warm its background session, or disable Strict Mode for one-tap handoff."
            )
        }

        // TIER 4 (Hybrid Cold Handoff): Precision deep-link / MEDIA_PLAY_FROM_SEARCH + Return-to-TuneMind Notification
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
                scheduleAutoPlayKicker(context, targetPkg, track, fullSearchQuery, previousTitle)
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
                scheduleAutoPlayKicker(context, targetPkg, track, fullSearchQuery, previousTitle)
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
                scheduleAutoPlayKicker(context, targetPkg, track, fullSearchQuery, previousTitle)
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

    /**
     * Play-safe high-priority notification shown after launching an external player so the
     * user has a one-tap pill to jump straight back to TuneMind (zero BAL background pop-overs).
     */
    private fun postReturnToTuneMindNotification(
        context: Context,
        trackTitle: String,
        playerDisplayName: String
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
                    "External Player Handoff",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "One-tap return pill when TuneMind launches a track in an external player."
                    setSound(null, null)
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

            val notification = NotificationCompat.Builder(context, RETURN_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Playing '$trackTitle' in $playerDisplayName")
                .setContentText("Tap to return to TuneMind")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setTimeoutAfter(25_000L)
                .setContentIntent(pi)
                .build()

            nm.notify(RETURN_NOTIFICATION_ID, notification)
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
     * registers its MediaSession after cold boot, then calls `play()` ONLY once the active title
     * matches the newly requested song (never resuming the old paused track).
     */
    fun scheduleAutoPlayKicker(
        context: Context,
        targetPackage: String?,
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
                    if (titleMatchesNewSong && listener?.isPlaying() != true) {
                        listener?.play(targetPackage)
                        audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                        audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Auto-play verification kicker notice: ${e.message}")
                }
            }, delay)
        }
    }
}
