package com.tunemind.app.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.provider.BoomplayMusicProvider
import com.tunemind.app.provider.MusicProvider
import com.tunemind.app.provider.ProviderCapability
import com.tunemind.app.ui.theme.LocalAdaptiveTheme
import java.util.Locale

/**
 * UI-layer curation and compact grid presentation for Playback Output & Default Music Player.
 * Filters out non-music apps (video players, file sharers like Xender, regular YouTube, browsers)
 * and ensures regional African/global music players like Boomplay are cleanly surfaced.
 */
object MusicPlayerUiHelper {

    private val excludedPackageKeywords = listOf(
        "cn.xender",
        "xender",
        "anyshare",
        "shareit",
        "com.google.android.youtube", // Regular YouTube video app (YouTube Music is com.google.android.apps.youtube.music)
        "miui.videoplayer",
        "videoplayer",
        "mxtech",
        "kmplayer",
        "bsplayer",
        "gallery",
        "filemanager",
        "documentsui",
        "chrome",
        "browser",
        "opera",
        "firefox",
        "whatsapp",
        "telegram",
        "instagram",
        "facebook",
        "tiktok",
        "soundrecorder",
        "recorder",
        "ringtone",
        "drive",
        "dropbox",
        "vidmate",
        "snaptube",
        "tubemate"
    )

    private val excludedLabelKeywords = listOf(
        "xender",
        "video player",
        "mi video",
        "mx player",
        "file manager",
        "files",
        "gallery",
        "photos",
        "recorder",
        "chrome",
        "browser",
        "drive",
        "shareit",
        "vidmate",
        "snaptube"
    )

    private val boomplayPackages = listOf(
        "com.afmobi.boomplay",
        "com.afmobi.boomplaymusic",
        "com.boomplay.music"
    )

    fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun isBoomplayInstalled(context: Context): Boolean {
        return boomplayPackages.any { isPackageInstalled(context, it) }
    }

    fun isGenuineMusicPlayer(provider: MusicProvider): Boolean {
        val pkg = provider.packageName.lowercase(Locale.ROOT)
        val label = provider.displayName.lowercase(Locale.ROOT).trim()

        // Always keep YouTube Music even though pkg contains "youtube"
        if (pkg == "com.google.android.apps.youtube.music" || provider.providerId == "youtube_music") {
            return true
        }
        // Exclude regular YouTube video app
        if (pkg == "com.google.android.youtube" || label == "youtube") {
            return false
        }
        if (excludedPackageKeywords.any { pkg.contains(it) }) {
            return false
        }
        if (excludedLabelKeywords.any { label == it || label.contains(it) }) {
            return false
        }
        return true
    }

    /**
     * Returns a clean, deduplicated list of genuine music players with Boomplay always available.
     */
    fun filterMusicPlayers(context: Context, rawProviders: List<MusicProvider>): List<MusicProvider> {
        val filtered = rawProviders.filter { isGenuineMusicPlayer(it) }.toMutableList()

        // Deduplicate if Boomplay was discovered dynamically via pkg_com.afmobi.boomplay
        val hasBoomplay = filtered.any {
            it.providerId == BoomplayMusicProvider.PROVIDER_ID ||
                boomplayPackages.any { bp -> it.packageName.equals(bp, ignoreCase = true) }
        }
        filtered.removeAll {
            it.providerId.startsWith("pkg_") &&
                boomplayPackages.any { bp -> it.packageName.equals(bp, ignoreCase = true) }
        }
        if (!hasBoomplay || filtered.none { it.providerId == BoomplayMusicProvider.PROVIDER_ID }) {
            filtered.add(BoomplayMusicProvider())
        }

        val priorityOrder = mapOf(
            "tunemind_inapp" to 0,
            "boomplay" to 1,
            "youtube_music" to 2,
            "audiomack" to 3,
            "spotify" to 4,
            "local_files" to 5
        )

        return filtered
            .distinctBy { it.providerId }
            .sortedWith(
                compareBy<MusicProvider>(
                    { priorityOrder[it.providerId] ?: 10 },
                    { shortDisplayName(it).lowercase(Locale.ROOT) }
                )
            )
    }

    fun shortDisplayName(provider: MusicProvider): String {
        return when (provider.providerId) {
            "tunemind_inapp" -> "TuneMind"
            "local_files" -> "Local Audio"
            "boomplay" -> "Boomplay"
            "spotify" -> "Spotify"
            "youtube_music" -> "YT Music"
            "audiomack" -> "Audiomack"
            else -> provider.displayName
                .replace(" Music Player", "", ignoreCase = true)
                .replace(" (Built-In)", "", ignoreCase = true)
                .trim()
        }
    }

    fun brandAccentColor(provider: MusicProvider, fallbackPrimary: Color, fallbackSecondary: Color): Color {
        return when {
            provider.providerId == "spotify" -> Color(0xFF1DB954)
            provider.providerId == "boomplay" -> Color(0xFFFF6B00)
            provider.providerId == "youtube_music" -> Color(0xFFFF3D57)
            provider.providerId == "audiomack" -> Color(0xFFFFA200)
            provider.providerId == "tunemind_inapp" -> fallbackPrimary
            provider.providerId == "local_files" -> fallbackSecondary
            else -> fallbackPrimary
        }
    }

    fun statusBadgeText(context: Context, provider: MusicProvider): String {
        return when (provider.providerId) {
            "tunemind_inapp" -> "Built-in"
            "local_files" -> "On device"
            "boomplay" -> if (isBoomplayInstalled(context)) "Installed" else "Web"
            else -> {
                val installed = provider.isInstalled(context)
                val direct = provider.supportedCapabilities.contains(ProviderCapability.CAN_DEEP_LINK)
                when {
                    installed && direct -> "Direct"
                    installed -> "Installed"
                    else -> "Web"
                }
            }
        }
    }
}

/**
 * Compact 2-column grid of music player tiles replacing tall vertical lists.
 */
@Composable
fun CompactMusicPlayerGrid(
    providers: List<MusicProvider>,
    isProviderSelected: (MusicProvider) -> Boolean,
    onSelectProvider: (MusicProvider) -> Unit,
    context: Context,
    modifier: Modifier = Modifier
) {
    val theme = LocalAdaptiveTheme.current
    val rows = providers.chunked(2)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        rows.forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { provider ->
                    val selected = isProviderSelected(provider)
                    val brandColor = MusicPlayerUiHelper.brandAccentColor(
                        provider = provider,
                        fallbackPrimary = theme.primaryAccent,
                        fallbackSecondary = theme.secondaryAccent
                    )
                    val activeColor = if (selected) theme.primaryAccent else brandColor
                    val statusLabel = MusicPlayerUiHelper.statusBadgeText(context, provider)

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (selected) theme.primaryAccent.copy(alpha = 0.14f)
                                else theme.recessedBackground.copy(alpha = 0.60f)
                            )
                            .border(
                                width = if (selected) 1.dp else 0.8.dp,
                                color = if (selected) theme.primaryAccent.copy(alpha = 0.55f)
                                else Color.White.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { onSelectProvider(provider) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(activeColor)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = MusicPlayerUiHelper.shortDisplayName(provider),
                                color = if (selected) Color(0xFFFAFAFA) else Color.White.copy(alpha = 0.88f),
                                fontSize = 13.5.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(1.dp))
                            Text(
                                text = statusLabel,
                                color = Color.White.copy(alpha = 0.50f),
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                        if (selected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = "Selected",
                                tint = theme.primaryAccent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
