package com.tunemind.app.domain.execution

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import com.google.gson.annotations.SerializedName
import com.tunemind.app.MainActivity
import com.tunemind.app.domain.recommendation.RecommendedTrack
import com.tunemind.app.provider.MusicPlayerAutoLauncher
import com.tunemind.app.service.TuneMindListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

// ============================================================================
// 1. STRUCTURED JSON DECISION & TOOL CALLING MODELS
// ============================================================================

data class SatietyDecisionPayload(
    @SerializedName("state") val state: String, // "fatigued" | "focused"
    @SerializedName("action") val action: String, // "change_genre" | "skip_track" | "maintain"
    @SerializedName("satiety_score") val satietyScore: Int = 30,
    @SerializedName("reasoning") val reasoning: String = "",
    @SerializedName("recommended_uri") val recommendedUri: String? = null,
    @SerializedName("recommended_query") val recommendedQuery: String? = null,
    @SerializedName("target_genre") val targetGenre: String? = null,
    @SerializedName("tool_calls") val toolCalls: List<AgentToolInvocationDto> = emptyList()
) {
    val isFatigued: Boolean
        get() = state.equals("fatigued", ignoreCase = true)

    companion object {
        fun fromJsonString(rawJson: String): SatietyDecisionPayload {
            val obj = JSONObject(rawJson)
            val state = obj.optString("state", "focused")
            val action = obj.optString("action", "maintain")
            val satietyScore = obj.optInt("satiety_score", if (state == "fatigued") 78 else 28)
            val reasoning = obj.optString("reasoning", "")
            val recUri = obj.optString("recommended_uri", "").takeIf { it.isNotBlank() }
            val recQuery = obj.optString("recommended_query", "").takeIf { it.isNotBlank() }
            val targetGenre = obj.optString("target_genre", "").takeIf { it.isNotBlank() }

            val parsedTools = mutableListOf<AgentToolInvocationDto>()
            val toolsArr = obj.optJSONArray("tool_calls")
            if (toolsArr != null) {
                for (i in 0 until toolsArr.length()) {
                    val item = toolsArr.optJSONObject(i) ?: continue
                    val name = item.optString("tool_name", "")
                    val args = item.optJSONObject("arguments") ?: JSONObject()
                    parsedTools.add(AgentToolInvocationDto(toolName = name, argumentsJson = args.toString()))
                }
            }

            return SatietyDecisionPayload(
                state = state,
                action = action,
                satietyScore = satietyScore,
                reasoning = reasoning,
                recommendedUri = recUri,
                recommendedQuery = recQuery,
                targetGenre = targetGenre,
                toolCalls = parsedTools
            )
        }
    }
}

data class AgentToolInvocationDto(
    @SerializedName("tool_name") val toolName: String,
    @SerializedName("arguments_json") val argumentsJson: String
)

data class OrbTelemetrySignal(
    val orbState: String = "IDLE", // "FATIGUE_ALERT" | "FLOW_LOCKED" | "SPEAKING" | "IDLE"
    val headline: String = "TuneMind Active",
    val subtext: String = "Monitoring local Room telemetry",
    val satietyScore: Int = 28,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Reactive bridge connecting the background [NotificationTool] to the Compose Android Orb UI.
 */
object OrbTelemetryBridge {
    private val _orbSignalFlow = MutableStateFlow(OrbTelemetrySignal())
    val orbSignalFlow: StateFlow<OrbTelemetrySignal> = _orbSignalFlow.asStateFlow()

    fun emitOrbUpdate(signal: OrbTelemetrySignal) {
        _orbSignalFlow.value = signal
    }
}

// ============================================================================
// 2. AGENTIC TOOL REGISTRY (SpotifyPlaybackTool & NotificationTool)
// ============================================================================

object AgenticToolRegistry {
    private const val TAG = "AgenticToolRegistry"

    /**
     * Dispatches all LLM tool calls in the [SatietyDecisionPayload], or falls back to
     * executing the top-level `state` + `action` decision directly.
     */
    fun executeDecisionAndTools(context: Context, decision: SatietyDecisionPayload) {
        val appContext = context.applicationContext
        Log.i(
            TAG,
            "Executing Satiety Decision: state=${decision.state}, action=${decision.action}, " +
                "score=${decision.satietyScore}%, tools=${decision.toolCalls.size}"
        )

        var playbackExecutedViaTool = false
        var notificationExecutedViaTool = false

        for (tool in decision.toolCalls) {
            val args = runCatching { JSONObject(tool.argumentsJson) }.getOrNull() ?: JSONObject()
            when (tool.toolName) {
                "SpotifyPlaybackTool" -> {
                    playbackExecutedViaTool = true
                    SpotifyPlaybackTool.execute(
                        context = appContext,
                        command = args.optString("command", decision.action),
                        targetUri = args.optString("target_uri", "").ifBlank { decision.recommendedUri },
                        searchQuery = args.optString("search_query", "").ifBlank { decision.recommendedQuery },
                        targetGenre = args.optString("target_genre", "").ifBlank { decision.targetGenre },
                        rationale = args.optString("rationale", decision.reasoning)
                    )
                }
                "NotificationTool" -> {
                    notificationExecutedViaTool = true
                    NotificationTool.execute(
                        context = appContext,
                        orbState = args.optString("orb_state", if (decision.isFatigued) "FATIGUE_ALERT" else "FLOW_LOCKED"),
                        headline = args.optString("headline", "TuneMind Satiety Intelligence"),
                        subtext = args.optString("subtext", decision.reasoning),
                        satietyScore = args.optInt("satiety_score", decision.satietyScore),
                        showHeadsUpBanner = args.optBoolean("show_heads_up_banner", decision.isFatigued)
                    )
                }
            }
        }

        // If the backend returned a raw {"state": "fatigued", "action": "skip_track|change_genre"} without explicit tool_calls array:
        if (!playbackExecutedViaTool && decision.isFatigued) {
            SpotifyPlaybackTool.execute(
                context = appContext,
                command = decision.action,
                targetUri = decision.recommendedUri,
                searchQuery = decision.recommendedQuery,
                targetGenre = decision.targetGenre,
                rationale = decision.reasoning
            )
        }

        if (!notificationExecutedViaTool) {
            NotificationTool.execute(
                context = appContext,
                orbState = if (decision.isFatigued) "FATIGUE_ALERT" else "FLOW_LOCKED",
                headline = if (decision.isFatigued) {
                    "Satiety Alert (${decision.satietyScore}%) • ${decision.action.replace('_', ' ').uppercase()}"
                } else {
                    "Cognitive Flow Locked (${decision.satietyScore}% Satiety)"
                },
                subtext = decision.reasoning,
                satietyScore = decision.satietyScore,
                showHeadsUpBanner = decision.isFatigued
            )
        }
    }
}

/**
 * Tool 1: [SpotifyPlaybackTool]
 * Executes OS-level Spotify playback commands (`skip_track`, `play_playlist`, `change_genre`, `pause`, `resume`)
 * via Spotify App Remote protocol / Active `MediaSession` TransportControls / `MusicPlayerAutoLauncher`
 * without requiring the user to open the TuneMind app.
 */
object SpotifyPlaybackTool {
    private const val TAG = "SpotifyPlaybackTool"
    private const val SPOTIFY_PACKAGE = "com.spotify.music"

    fun execute(
        context: Context,
        command: String,
        targetUri: String? = null,
        searchQuery: String? = null,
        targetGenre: String? = null,
        rationale: String? = null
    ) {
        val normalizedCmd = command.lowercase().trim()
        Log.i(TAG, "SpotifyPlaybackTool invoking command='$normalizedCmd', uri='$targetUri', query='$searchQuery'")

        when (normalizedCmd) {
            "skip_track" -> {
                if (!searchQuery.isNullOrBlank() || !targetUri.isNullOrBlank()) {
                    launchPlaylistOrTrackInBackground(
                        context = context,
                        uri = targetUri,
                        query = searchQuery ?: targetGenre ?: "Fresh Track",
                        rationale = rationale
                    )
                } else {
                    executeHardwareOrSessionSkip(context)
                }
            }
            "change_genre", "play_playlist" -> {
                val effectiveQuery = searchQuery?.takeIf { it.isNotBlank() }
                    ?: targetGenre?.takeIf { it.isNotBlank() }
                    ?: "Chill Afro Acoustic"
                launchPlaylistOrTrackInBackground(
                    context = context,
                    uri = targetUri,
                    query = effectiveQuery,
                    rationale = rationale
                )
            }
            "pause" -> {
                val listener = TuneMindListenerService.instance
                listener?.pause()
                dispatchMediaKeyEvent(context, KeyEvent.KEYCODE_MEDIA_PAUSE)
            }
            "resume" -> {
                val listener = TuneMindListenerService.instance
                listener?.play(SPOTIFY_PACKAGE)
                dispatchMediaKeyEvent(context, KeyEvent.KEYCODE_MEDIA_PLAY)
            }
            else -> {
                Log.d(TAG, "SpotifyPlaybackTool maintaining current playback trajectory.")
            }
        }
    }

    private fun executeHardwareOrSessionSkip(context: Context) {
        val listener = TuneMindListenerService.instance
        val hasSpotifySession = listener?.hasActiveSession(SPOTIFY_PACKAGE) == true
        if (hasSpotifySession) {
            listener?.skipNext(SPOTIFY_PACKAGE)
        } else {
            dispatchMediaKeyEvent(context, KeyEvent.KEYCODE_MEDIA_NEXT)
        }
        Log.i(TAG, "Executed OS-level skip_track (session=$hasSpotifySession)")
    }

    private fun launchPlaylistOrTrackInBackground(
        context: Context,
        uri: String?,
        query: String,
        rationale: String?
    ) {
        val recommendedTrack = RecommendedTrack(
            id = uri ?: "satiety_shift_${System.currentTimeMillis()}",
            title = query,
            artist = "",
            album = null,
            reason = rationale ?: "Autonomous Satiety Intervention",
            category = com.tunemind.app.domain.recommendation.RecommendationCategory.PALATE_CLEANSER,
            confidence = 0.94f
        )

        MusicPlayerAutoLauncher.launchWithAutoPlay(
            context = context,
            providerId = "spotify",
            displayName = "Spotify",
            packageName = SPOTIFY_PACKAGE,
            track = recommendedTrack,
            query = query,
            deepLinkUri = uri
        )
    }

    private fun dispatchMediaKeyEvent(context: Context, keyCode: Int) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        } catch (e: Exception) {
            Log.w(TAG, "MediaKeyEvent dispatch notice: ${e.message}")
        }
    }
}

/**
 * Tool 2: [NotificationTool]
 * Updates the TuneMind Android Orb UI state via [OrbTelemetryBridge] and posts an OS-level
 * notification when listener fatigue triggers an autonomous intervention.
 */
object NotificationTool {
    const val CHANNEL_ID = "tunemind_satiety_execution_channel"
    const val NOTIFICATION_ID = 4209

    fun execute(
        context: Context,
        orbState: String,
        headline: String,
        subtext: String,
        satietyScore: Int,
        showHeadsUpBanner: Boolean
    ) {
        // 1. Update live Android Orb UI state
        OrbTelemetryBridge.emitOrbUpdate(
            OrbTelemetrySignal(
                orbState = orbState,
                headline = headline,
                subtext = subtext,
                satietyScore = satietyScore
            )
        )

        // 2. Post or update OS notification
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val priority = if (showHeadsUpBanner) {
            NotificationCompat.PRIORITY_HIGH
        } else {
            NotificationCompat.PRIORITY_LOW
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(headline)
            .setContentText(subtext)
            .setStyle(NotificationCompat.BigTextStyle().bigText(subtext))
            .setPriority(priority)
            .setOnlyAlertOnce(!showHeadsUpBanner)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TuneMind Satiety & Autonomous Execution",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Autonomous psychoacoustic satiety alerts and background music interventions"
            }
            nm.createNotificationChannel(channel)
        }
    }
}
