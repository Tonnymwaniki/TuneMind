package com.tunemind.app.ui.feedback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BannerSeverity {
    ERROR,
    WARNING,
    INFO,
    SUCCESS
}

enum class TuneMindBannerAction {
    NONE,
    RETRY,
    OPEN_SETTINGS,
    RECONNECT_SPOTIFY,
    ALLOW_MIC_PERMISSION,
    ALLOW_NOTIFICATION_ACCESS,
    USE_YOUTUBE_IN_APP
}

data class TuneMindBannerMessage(
    val id: Long = System.nanoTime(),
    val title: String,
    val message: String,
    val severity: BannerSeverity = BannerSeverity.ERROR,
    val action: TuneMindBannerAction = TuneMindBannerAction.NONE,
    val actionLabel: String = "",
    val autoDismissMs: Long = 6500L,
    val onRetry: (() -> Unit)? = null
)

/**
 * Global, user-friendly feedback bus for TuneMind.
 * Ensures every screen renders consistent dark-glass notices without exposing raw exceptions.
 */
object TuneMindFeedbackCenter {
    private val _currentBanner = MutableStateFlow<TuneMindBannerMessage?>(null)
    val currentBanner: StateFlow<TuneMindBannerMessage?> = _currentBanner.asStateFlow()

    fun showMappedError(
        rawError: String?,
        contextHint: String? = null,
        onRetry: (() -> Unit)? = null
    ) {
        val mapped = TuneMindErrorMapper.map(rawError, contextHint, onRetry)
        _currentBanner.value = mapped
    }

    fun showError(
        title: String,
        message: String,
        action: TuneMindBannerAction = TuneMindBannerAction.NONE,
        actionLabel: String = "",
        autoDismissMs: Long = 8000L,
        onRetry: (() -> Unit)? = null
    ) {
        _currentBanner.value = TuneMindBannerMessage(
            title = title,
            message = message,
            severity = BannerSeverity.ERROR,
            action = action,
            actionLabel = actionLabel,
            autoDismissMs = autoDismissMs,
            onRetry = onRetry
        )
    }

    fun showWarning(
        title: String,
        message: String,
        action: TuneMindBannerAction = TuneMindBannerAction.NONE,
        actionLabel: String = "",
        autoDismissMs: Long = 7500L,
        onRetry: (() -> Unit)? = null
    ) {
        _currentBanner.value = TuneMindBannerMessage(
            title = title,
            message = message,
            severity = BannerSeverity.WARNING,
            action = action,
            actionLabel = actionLabel,
            autoDismissMs = autoDismissMs,
            onRetry = onRetry
        )
    }

    fun showInfo(
        title: String,
        message: String,
        autoDismissMs: Long = 4500L
    ) {
        _currentBanner.value = TuneMindBannerMessage(
            title = title,
            message = message,
            severity = BannerSeverity.INFO,
            action = TuneMindBannerAction.NONE,
            autoDismissMs = autoDismissMs
        )
    }

    fun showSuccess(
        title: String,
        message: String,
        autoDismissMs: Long = 3800L
    ) {
        _currentBanner.value = TuneMindBannerMessage(
            title = title,
            message = message,
            severity = BannerSeverity.SUCCESS,
            action = TuneMindBannerAction.NONE,
            autoDismissMs = autoDismissMs
        )
    }

    fun dismiss(id: Long? = null) {
        val cur = _currentBanner.value ?: return
        if (id == null || cur.id == id) {
            _currentBanner.value = null
        }
    }
}

/**
 * Translates raw exceptions, HTTP status codes, and provider/mic errors into clear,
 * empathetic English messages with a single actionable recovery button.
 */
object TuneMindErrorMapper {
    fun map(
        rawError: String?,
        contextHint: String? = null,
        onRetry: (() -> Unit)? = null
    ): TuneMindBannerMessage {
        val raw = rawError.orEmpty().trim()
        val lower = raw.lowercase()
        val hintLower = contextHint.orEmpty().lowercase()

        return when {
            lower.contains("record_audio") ||
                lower.contains("audiorecord") ||
                lower.contains("microphone") ||
                lower.contains("permission denied") && hintLower.contains("mic") -> {
                TuneMindBannerMessage(
                    title = "Microphone unavailable",
                    message = "TuneMind needs microphone access (or another app is using the mic) to hear your voice.",
                    severity = BannerSeverity.WARNING,
                    action = TuneMindBannerAction.ALLOW_MIC_PERMISSION,
                    actionLabel = "Allow Mic",
                    autoDismissMs = 8500L
                )
            }

            lower.contains("notification") && (lower.contains("access") || lower.contains("permission") || lower.contains("listener")) -> {
                TuneMindBannerMessage(
                    title = "Music tracking paused",
                    message = "Enable Notification Access so TuneMind can automatically count plays across Spotify, Boomplay & Audiomack.",
                    severity = BannerSeverity.WARNING,
                    action = TuneMindBannerAction.ALLOW_NOTIFICATION_ACCESS,
                    actionLabel = "Enable Access",
                    autoDismissMs = 9000L
                )
            }

            lower.contains("spotify") && (lower.contains("expired") || lower.contains("401") || lower.contains("token") || lower.contains("not connected") || lower.contains("reconnect")) -> {
                TuneMindBannerMessage(
                    title = "Reconnect Spotify",
                    message = "Your Spotify link needs a quick refresh to keep syncing playlists and playing seamlessly.",
                    severity = BannerSeverity.WARNING,
                    action = TuneMindBannerAction.RECONNECT_SPOTIFY,
                    actionLabel = "Reconnect",
                    autoDismissMs = 9000L
                )
            }

            lower.contains("strict never-leave") || lower.contains("deeply asleep") || lower.contains("no active") && lower.contains("spotify") -> {
                TuneMindBannerMessage(
                    title = "Spotify is asleep",
                    message = "Open Spotify once (or tap Settings to adjust your player) so TuneMind can wake playback smoothly.",
                    severity = BannerSeverity.WARNING,
                    action = TuneMindBannerAction.OPEN_SETTINGS,
                    actionLabel = "Settings",
                    autoDismissMs = 8500L
                )
            }

            lower.contains("premium required") ||
                lower.contains("premium_required") ||
                lower.contains("premium is required") ||
                (lower.contains("spotify") && lower.contains("premium")) -> {
                TuneMindBannerMessage(
                    title = "Spotify Free account detected",
                    message = "Silent background playback requires Spotify Premium. TuneMind will open Spotify directly to play for you.",
                    severity = BannerSeverity.INFO,
                    action = TuneMindBannerAction.NONE,
                    autoDismissMs = 6500L
                )
            }

            lower.contains("unknownhost") ||
                lower.contains("unable to resolve host") ||
                lower.contains("failed to connect") ||
                lower.contains("network") ||
                lower.contains("offline") -> {
                TuneMindBannerMessage(
                    title = "Connection issue",
                    message = "Check your internet connection. Local history and saved playlists still work normally.",
                    severity = BannerSeverity.WARNING,
                    action = if (onRetry != null) TuneMindBannerAction.RETRY else TuneMindBannerAction.NONE,
                    actionLabel = if (onRetry != null) "Retry" else "",
                    autoDismissMs = 7500L,
                    onRetry = onRetry
                )
            }

            lower.contains("timeout") || lower.contains("timed out") || lower.contains("429") || lower.contains("quota") || lower.contains("busy") -> {
                TuneMindBannerMessage(
                    title = "Service is busy",
                    message = "That request took a little longer than usual. Please try again in a moment.",
                    severity = BannerSeverity.WARNING,
                    action = if (onRetry != null) TuneMindBannerAction.RETRY else TuneMindBannerAction.NONE,
                    actionLabel = if (onRetry != null) "Try Again" else "",
                    autoDismissMs = 7500L,
                    onRetry = onRetry
                )
            }

            lower.contains("session expired") ||
                lower.contains("unauthorized") ||
                lower.contains("sign back in") ||
                lower.contains("log out") -> {
                TuneMindBannerMessage(
                    title = "Sign-in expired",
                    message = "Open Profile → Log Out and sign back in to refresh your TuneMind session.",
                    severity = BannerSeverity.WARNING,
                    action = TuneMindBannerAction.NONE,
                    autoDismissMs = 9500L
                )
            }

            lower.contains("websocket") || lower.contains("session") || lower.contains("realtime") || hintLower.contains("voice") -> {
                TuneMindBannerMessage(
                    title = "Voice connection dropped",
                    message = "Tap the orb to reconnect your voice companion.",
                    severity = BannerSeverity.WARNING,
                    action = if (onRetry != null) TuneMindBannerAction.RETRY else TuneMindBannerAction.NONE,
                    actionLabel = if (onRetry != null) "Reconnect" else "",
                    autoDismissMs = 7500L,
                    onRetry = onRetry
                )
            }

            lower.contains("billing") || lower.contains("purchase") || lower.contains("play store") -> {
                TuneMindBannerMessage(
                    title = "Google Play unavailable",
                    message = "Could not reach Google Play Billing right now. Please try again shortly.",
                    severity = BannerSeverity.WARNING,
                    action = TuneMindBannerAction.NONE,
                    autoDismissMs = 7500L
                )
            }

            else -> {
                val cleanMessage = sanitizeTechnicalText(raw)
                TuneMindBannerMessage(
                    title = when {
                        hintLower.contains("play") -> "Couldn't start playback"
                        hintLower.contains("sync") -> "Sync paused"
                        else -> "Something went wrong"
                    },
                    message = cleanMessage,
                    severity = BannerSeverity.ERROR,
                    action = if (onRetry != null) TuneMindBannerAction.RETRY else TuneMindBannerAction.OPEN_SETTINGS,
                    actionLabel = if (onRetry != null) "Retry" else "Settings",
                    autoDismissMs = 8000L,
                    onRetry = onRetry
                )
            }
        }
    }

    private fun sanitizeTechnicalText(raw: String): String {
        if (raw.isBlank()) return "Please try again in a moment."
        val looksTechnical = raw.contains("Exception", ignoreCase = true) ||
            raw.contains("HTTP ", ignoreCase = true) ||
            raw.contains("{") ||
            raw.contains("at com.") ||
            raw.contains("java.") ||
            raw.length > 160
        return if (looksTechnical) {
            "Something interrupted that request. Please try again in a moment."
        } else {
            raw
        }
    }
}

@Composable
fun TuneMindGlobalBannerHost(
    primaryAccent: Color,
    onOpenSettings: () -> Unit,
    onReconnectSpotify: () -> Unit,
    onAllowMicPermission: () -> Unit,
    onAllowNotificationAccess: () -> Unit,
    onUseYouTubeInApp: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val banner by TuneMindFeedbackCenter.currentBanner.collectAsState()

    LaunchedEffect(banner?.id) {
        val current = banner ?: return@LaunchedEffect
        delay(current.autoDismissMs.coerceIn(2500L, 12_000L))
        TuneMindFeedbackCenter.dismiss(current.id)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        AnimatedVisibility(
            visible = banner != null,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
        ) {
            val active = banner ?: return@AnimatedVisibility
            val (accentColor, bgColor, iconVec) = when (active.severity) {
                BannerSeverity.ERROR -> Triple(Color(0xFFFF4D6D), Color(0xFF27111A), Icons.Rounded.ErrorOutline)
                BannerSeverity.WARNING -> Triple(Color(0xFFF59E0B), Color(0xFF23180C), Icons.Rounded.WarningAmber)
                BannerSeverity.SUCCESS -> Triple(Color(0xFF10B981), Color(0xFF0E241C), Icons.Rounded.CheckCircle)
                BannerSeverity.INFO -> Triple(primaryAccent, Color(0xFF121828), Icons.Rounded.Info)
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = bgColor.copy(alpha = 0.96f),
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.55f)),
                shadowElevation = 10.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 8.dp, top = 11.dp, bottom = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = iconVec,
                            contentDescription = active.title,
                            tint = accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = active.title,
                            color = Color(0xFFFAFAFA),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = active.message,
                            color = Color.White.copy(alpha = 0.78f),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (active.action != TuneMindBannerAction.NONE && active.actionLabel.isNotBlank()) {
                        TextButton(
                            onClick = {
                                val act = active.action
                                val retry = active.onRetry
                                TuneMindFeedbackCenter.dismiss(active.id)
                                when (act) {
                                    TuneMindBannerAction.RETRY -> retry?.invoke()
                                    TuneMindBannerAction.OPEN_SETTINGS -> onOpenSettings()
                                    TuneMindBannerAction.RECONNECT_SPOTIFY -> onReconnectSpotify()
                                    TuneMindBannerAction.ALLOW_MIC_PERMISSION -> onAllowMicPermission()
                                    TuneMindBannerAction.ALLOW_NOTIFICATION_ACCESS -> onAllowNotificationAccess()
                                    TuneMindBannerAction.USE_YOUTUBE_IN_APP -> onUseYouTubeInApp()
                                    TuneMindBannerAction.NONE -> Unit
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = active.actionLabel,
                                color = accentColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    IconButton(
                        onClick = { TuneMindFeedbackCenter.dismiss(active.id) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Dismiss",
                            tint = Color.White.copy(alpha = 0.55f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
