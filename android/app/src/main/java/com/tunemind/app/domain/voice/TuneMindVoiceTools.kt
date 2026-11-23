package com.tunemind.app.domain.voice

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tunemind.app.data.auth.TuneMindAccountStorage
import com.tunemind.app.data.auth.TuneMindAuthSessionManager
import com.tunemind.app.data.ingestion.SpotifyLibrarySyncEngine
import com.tunemind.app.data.local.CrossAppLibraryDao
import com.tunemind.app.data.local.CrossAppTrackEntity
import com.tunemind.app.data.local.ProfileCustomizationStore
import com.tunemind.app.data.local.TuneMindDatabase
import com.tunemind.app.data.local.TuneMindPlaylistEntity
import com.tunemind.app.data.local.TuneMindPlaylistTrackEntity
import com.tunemind.app.data.local.TuneMindPlaylistWithTracks
import com.tunemind.app.data.supabase.SupabaseIntelligenceSyncManager
import com.tunemind.app.domain.audio.AudioDeviceObserver
import com.tunemind.app.domain.memory.ListeningPlayDeduplicator
import com.tunemind.app.domain.model.TrackIdentityResolver
import com.tunemind.app.domain.network.BackendEndpointManager
import com.tunemind.app.domain.recommendation.RecommendedTrack
import com.tunemind.app.domain.vault.RestingVaultEngine
import com.tunemind.app.provider.MusicPlayerAutoLauncher
import com.tunemind.app.provider.MusicProviderRegistry
import com.tunemind.app.provider.PlaybackLaunchResult
import com.tunemind.app.provider.SpotifyMusicProvider
import com.tunemind.app.provider.SpotifyPlayResult
import com.tunemind.app.provider.SpotifyPremiumConnectManager
import com.tunemind.app.provider.TuneMindInAppPlayer
import com.tunemind.app.provider.TuneMindInAppProvider
import com.tunemind.app.provider.TuneMindYouTubeEmbedController
import com.tunemind.app.service.TuneMindListenerService
import com.tunemind.app.ui.NowPlayingObservation
import com.tunemind.app.ui.theme.ThemeBehavior
import com.tunemind.app.ui.theme.ThemePreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

data class RealtimeToolDescriptor(
    val name: String,
    val description: String,
    val statusLabel: String,
    val parametersSchema: JSONObject,
    val executor: suspend (JSONObject) -> JSONObject
)

data class TuneMindVoiceToolHost(
    val context: Context,
    val getNowPlaying: () -> NowPlayingObservation?,
    val getCurrentScreenName: () -> String,
    val getSleepTimerRemainingMinutes: () -> Int?,
    val onTrackDispatched: (title: String, artist: String, sourcePlayer: String) -> Unit = { _, _, _ -> },
    val onPlaybackLaunchResult: (PlaybackLaunchResult) -> Unit = {},
    val onPauseUiSync: () -> Unit = {},
    val onResumeUiSync: () -> Unit = {},
    val onVaultRestoreByTitle: (String) -> Unit = {},
    val onRefreshVaultAndStats: () -> Unit = {},
    val onNavigateScreen: (screen: String, playlistName: String?) -> Unit = { _, _ -> },
    val onSetSleepTimerMinutes: (Int) -> Unit = {},
    val onSetTheme: (paletteId: String?, customAccentHex: String?, behavior: ThemeBehavior?) -> Unit = { _, _, _ -> }
)

/**
 * Auto-discovered OpenAI Realtime API tool registry backed exclusively by TuneMind's real
 * MediaSession observers, Spotify Premium Connect endpoints, Room DAOs, AudioDeviceObserver,
 * Settings stores, and Supabase user-scoped sync. Zero hardcoded intent rules or mock tracks.
 */
class TuneMindVoiceTools(private val host: TuneMindVoiceToolHost) {

    companion object {
        private const val TAG = "TuneMindVoiceTools"
        private const val SPOTIFY_API_BASE = "https://api.spotify.com/v1"
    }

    private val appContext: Context = host.context.applicationContext

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val registryMap: Map<String, RealtimeToolDescriptor> by lazy {
        buildDescriptors().associateBy { it.name }
    }

    fun getStatusLabel(toolName: String): String =
        registryMap[toolName]?.statusLabel ?: "Running $toolName"

    fun toOpenAiToolsJsonArray(): JSONArray {
        val arr = JSONArray()
        registryMap.values.forEach { tool ->
            arr.put(JSONObject().apply {
                put("type", "function")
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", tool.parametersSchema)
            })
        }
        return arr
    }

    suspend fun execute(toolName: String, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val tool = registryMap[toolName]
            ?: return@withContext errorResult("Unknown tool '$toolName'")

        val identity = resolveScopedIdentity()
        return@withContext try {
            val res = tool.executor(args)
            res.put("scoped_user_id", identity.userId)
            res.put("scoped_partition", identity.partitionKey)
            res
        } catch (t: Throwable) {
            Log.e(TAG, "Tool '$toolName' failed: ${t.message}", t)
            errorResult("Tool '$toolName' failed: ${t.message ?: "unexpected error"}")
                .put("scoped_user_id", identity.userId)
        }
    }

    private data class ScopedUser(
        val userId: String,
        val email: String,
        val isAuthenticated: Boolean,
        val partitionKey: String
    )

    private fun resolveScopedIdentity(): ScopedUser {
        val id = SupabaseIntelligenceSyncManager.resolveUserIdentity(appContext)
        val partition = TuneMindAccountStorage.resolveActivePartitionKey(appContext)
        val scopedId = id.userId.ifBlank { partition }
        return ScopedUser(
            userId = scopedId,
            email = id.email,
            isAuthenticated = id.isAuthenticatedAccount,
            partitionKey = partition
        )
    }

    private fun scopedDb(): TuneMindDatabase = TuneMindDatabase.getInstance(appContext)

    private fun schemaObj(
        props: Map<String, JSONObject>,
        required: List<String> = emptyList()
    ): JSONObject {
        val propJson = JSONObject()
        props.forEach { (k, v) -> propJson.put(k, v) }
        return JSONObject().apply {
            put("type", "object")
            put("properties", propJson)
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
    }

    private fun prop(type: String, description: String, enumValues: List<String>? = null): JSONObject =
        JSONObject().apply {
            put("type", type)
            put("description", description)
            if (!enumValues.isNullOrEmpty()) put("enum", JSONArray(enumValues))
        }

    private fun errorResult(msg: String): JSONObject = JSONObject().apply {
        put("ok", false)
        put("error", msg)
    }

    private fun buildDescriptors(): List<RealtimeToolDescriptor> = listOf(
        RealtimeToolDescriptor(
            name = "get_live_playback_state",
            description = "Inspect what song/artist/player is currently playing or just paused across active Android MediaSessions, in-app players, Spotify Connect devices, installed music apps, default player setting, and sleep timer.",
            statusLabel = "Checking live playback & devices",
            parametersSchema = schemaObj(emptyMap()),
            executor = { execGetLivePlaybackState() }
        ),
        RealtimeToolDescriptor(
            name = "control_playback",
            description = "Execute live media transport or audio controls: pause, resume, next, previous, seek_to_ms, seek_delta_ms, set_volume_percent, set_shuffle, set_repeat_mode, or transfer_spotify_device.",
            statusLabel = "Controlling playback",
            parametersSchema = schemaObj(
                props = mapOf(
                    "command" to prop(
                        "string",
                        "Transport or Spotify Connect control command to execute.",
                        listOf("pause", "resume", "next", "previous", "seek_to_ms", "seek_delta_ms", "set_volume_percent", "set_shuffle", "set_repeat_mode", "transfer_spotify_device")
                    ),
                    "position_ms" to prop("integer", "Target position in milliseconds (for seek_to_ms)."),
                    "delta_ms" to prop("integer", "Relative seek offset in milliseconds, e.g. 30000 or -15000 (for seek_delta_ms)."),
                    "volume_percent" to prop("integer", "Target volume 0..100 (for set_volume_percent)."),
                    "shuffle_enabled" to prop("boolean", "Shuffle state (for set_shuffle)."),
                    "repeat_mode" to prop("string", "Repeat mode (for set_repeat_mode).", listOf("off", "context", "track")),
                    "device_id_or_name" to prop("string", "Target Spotify Connect device id or name (for transfer_spotify_device).")
                ),
                required = listOf("command")
            ),
            executor = ::execControlPlayback
        ),
        RealtimeToolDescriptor(
            name = "play_track",
            description = "Play a verified song right now on the user's chosen/default player (waking Spotify silently via Connect when connected, or targeted Spotify wake). Never pass unverified or guessed URIs.",
            statusLabel = "Launching track playback",
            parametersSchema = schemaObj(
                props = mapOf(
                    "title" to prop("string", "Exact song title to play."),
                    "artist" to prop("string", "Exact primary artist name."),
                    "spotify_uri" to prop("string", "Verified spotify:track:<id> URI if already retrieved from Spotify tools."),
                    "target_player" to prop("string", "Optional explicit player override (e.g. 'spotify', 'youtube_music', 'boomplay', 'audiomack', 'tunemind_inapp'). Leave omitted to use user's default player.")
                ),
                required = listOf("title")
            ),
            executor = ::execPlayTrack
        ),
        RealtimeToolDescriptor(
            name = "play_spotify_context",
            description = "Play a full Spotify playlist, album, or artist context silently via Spotify Connect.",
            statusLabel = "Starting Spotify mix",
            parametersSchema = schemaObj(
                props = mapOf(
                    "context_uri" to prop("string", "Verified spotify:playlist:<id>, spotify:album:<id>, or spotify:artist:<id> URI."),
                    "label" to prop("string", "Display label for the playlist/album/artist."),
                    "shuffle_enabled" to prop("boolean", "Optional shuffle toggle.")
                ),
                required = listOf("context_uri")
            ),
            executor = ::execPlaySpotifyContext
        ),
        RealtimeToolDescriptor(
            name = "queue_spotify_track",
            description = "Add a verified track to Spotify's Up Next playback queue.",
            statusLabel = "Adding track to Spotify queue",
            parametersSchema = schemaObj(
                props = mapOf(
                    "title" to prop("string", "Track title."),
                    "artist" to prop("string", "Track artist."),
                    "spotify_uri" to prop("string", "Optional verified spotify:track:<id> URI.")
                ),
                required = listOf("title")
            ),
            executor = ::execQueueSpotifyTrack
        ),
        RealtimeToolDescriptor(
            name = "search_spotify_catalog",
            description = "Search live Spotify catalog (/v1/search) for tracks, artists, albums, or playlists (with TuneMind catalog fallback if Spotify is disconnected). Use this before proposing or playing songs outside Room history.",
            statusLabel = "Searching Spotify catalog",
            parametersSchema = schemaObj(
                props = mapOf(
                    "query" to prop("string", "Song title, artist name, album, mood/genre, or search phrase."),
                    "types" to prop("string", "Comma-separated entity types: track,artist,album,playlist (default: track,artist)."),
                    "limit" to prop("integer", "Maximum results per type (1..10, default 6).")
                ),
                required = listOf("query")
            ),
            executor = ::execSearchSpotifyCatalog
        ),
        RealtimeToolDescriptor(
            name = "get_spotify_artist_top_tracks",
            description = "Look up an artist on Spotify and return BOTH their top tracks on Spotify (by popularity) AND the user's personal TuneMind verified play/skip counts for every song by that artist. Ideal for questions like 'most listened song of that same artist'.",
            statusLabel = "Checking artist top tracks & your plays",
            parametersSchema = schemaObj(
                props = mapOf(
                    "artist_name" to prop("string", "Exact artist name (e.g. from get_live_playback_state)."),
                    "artist_id" to prop("string", "Optional Spotify artist ID if already known."),
                    "limit" to prop("integer", "Max tracks to return (1..10, default 8).")
                ),
                required = listOf("artist_name")
            ),
            executor = ::execGetSpotifyArtistTopTracks
        ),
        RealtimeToolDescriptor(
            name = "get_spotify_account_library",
            description = "Inspect the signed-in user's real Spotify account: top_tracks, top_artists, recently_played, liked_songs, playlists, or playlist_tracks.",
            statusLabel = "Reading your Spotify library",
            parametersSchema = schemaObj(
                props = mapOf(
                    "query_type" to prop(
                        "string",
                        "Which Spotify library dataset to fetch.",
                        listOf("top_tracks", "top_artists", "recently_played", "liked_songs", "playlists", "playlist_tracks")
                    ),
                    "playlist_id_or_name" to prop("string", "Spotify playlist ID or name when query_type is 'playlist_tracks'."),
                    "limit" to prop("integer", "Max items to return (1..25, default 12).")
                ),
                required = listOf("query_type")
            ),
            executor = ::execGetSpotifyAccountLibrary
        ),
        RealtimeToolDescriptor(
            name = "set_spotify_track_like",
            description = "Save (like) or remove (unlike) a track in the user's Spotify Liked Songs and update TuneMind's local library.",
            statusLabel = "Updating Liked Songs",
            parametersSchema = schemaObj(
                props = mapOf(
                    "title" to prop("string", "Track title."),
                    "artist" to prop("string", "Track artist."),
                    "spotify_uri" to prop("string", "Optional spotify:track:<id> URI."),
                    "liked" to prop("boolean", "True to like/save, false to unlike/remove.")
                ),
                required = listOf("title", "liked")
            ),
            executor = ::execSetSpotifyTrackLike
        ),
        RealtimeToolDescriptor(
            name = "query_listening_history_and_analytics",
            description = "Query TuneMind's user-scoped Room cross-app listening library & play/skip logs for verified play counts, skips, completion ratios, fatigue/satiety %, freshness %, and time-windowed stats (today, 7d, 30d, all_time).",
            statusLabel = "Analyzing your TuneMind history",
            parametersSchema = schemaObj(
                props = mapOf(
                    "artist_filter" to prop("string", "Filter tracks to a specific artist (case-insensitive substring match)."),
                    "title_query" to prop("string", "Filter tracks by title keyword."),
                    "sort_by" to prop("string", "How to order returned tracks.", listOf("most_played", "recent", "most_skipped", "highest_fatigue", "freshest")),
                    "time_window" to prop("string", "Stats window summary.", listOf("today", "7d", "30d", "all_time")),
                    "liked_only" to prop("boolean", "Return only liked tracks."),
                    "in_vault_only" to prop("boolean", "Return only tracks currently in Resting Vault."),
                    "limit" to prop("integer", "Max tracks to return (1..25, default 10).")
                )
            ),
            executor = ::execQueryListeningHistory
        ),
        RealtimeToolDescriptor(
            name = "manage_resting_vault",
            description = "List tracks cooling down in TuneMind's Resting Vault, quarantine an overplayed track into Vault cooldown, or restore a track back to active rotation.",
            statusLabel = "Updating Resting Vault",
            parametersSchema = schemaObj(
                props = mapOf(
                    "operation" to prop("string", "Vault operation.", listOf("list", "quarantine", "restore")),
                    "title" to prop("string", "Track title (for quarantine or restore)."),
                    "artist" to prop("string", "Optional artist name.")
                ),
                required = listOf("operation")
            ),
            executor = ::execManageRestingVault
        ),
        RealtimeToolDescriptor(
            name = "get_hearing_health_telemetry",
            description = "Inspect live audio output hardware (Bluetooth/wired/speaker), estimated decibels (dBA), safe headroom dB, today's exposure minutes, and NIOSH daily dose.",
            statusLabel = "Checking Hearing Health telemetry",
            parametersSchema = schemaObj(emptyMap()),
            executor = { execGetHearingHealth() }
        ),
        RealtimeToolDescriptor(
            name = "manage_tunemind_playlists",
            description = "Read or mutate TuneMind-owned Room playlists: list, get_tracks, create, add_tracks, remove_track, rename, delete, play, or export_to_spotify.",
            statusLabel = "Managing TuneMind playlists",
            parametersSchema = schemaObj(
                props = mapOf(
                    "operation" to prop(
                        "string",
                        "Playlist operation.",
                        listOf("list", "get_tracks", "create", "add_tracks", "remove_track", "rename", "delete", "play", "export_to_spotify")
                    ),
                    "playlist_name" to prop("string", "Target playlist name."),
                    "playlist_id" to prop("string", "Optional playlist ID."),
                    "new_name" to prop("string", "New name when renaming."),
                    "description" to prop("string", "Optional description when creating."),
                    "track_title" to prop("string", "Track title for add_tracks / remove_track."),
                    "track_artist" to prop("string", "Track artist for add_tracks / remove_track."),
                    "spotify_uri" to prop("string", "Optional spotify:track:<id> URI when adding a track.")
                ),
                required = listOf("operation")
            ),
            executor = ::execManageTuneMindPlaylists
        ),
        RealtimeToolDescriptor(
            name = "get_or_update_user_preferences",
            description = "Read or persist TuneMind settings, theme palette/accent, profile bio/favorite genres, default music player, and log confirmed memories cleanly to user-scoped storage.",
            statusLabel = "Updating TuneMind preferences",
            parametersSchema = schemaObj(
                props = mapOf(
                    "mode" to prop("string", "Read current preferences or update fields.", listOf("get", "update")),
                    "autonomous_dj_enabled" to prop("boolean", "Enable/disable Autonomous End-of-Song DJ."),
                    "stay_in_tunemind_enabled" to prop("boolean", "Prefer silent in-app / Spotify Connect playback without switching away."),
                    "strict_no_app_switch_enabled" to prop("boolean", "Never open external apps even for cold wake."),
                    "kids_mode_enabled" to prop("boolean", "Toggle Kids Mode."),
                    "private_session_enabled" to prop("boolean", "Toggle Private Session (pause history capture)."),
                    "preferred_player" to prop("string", "Set default player: 'spotify', 'youtube_music', 'boomplay', 'audiomack', or 'tunemind_inapp'."),
                    "palette_id" to prop("string", "Shift theme palette: 'morning', 'afternoon', 'evening', or 'night'."),
                    "custom_accent_hex" to prop("string", "Hex accent color (e.g. '#00FFA3') or 'AUTO' for adaptive."),
                    "favorite_genres" to prop("string", "Comma-separated favorite genres to persist in ProfileCustomizationStore.")
                ),
                required = listOf("mode")
            ),
            executor = ::execPreferences
        ),
        RealtimeToolDescriptor(
            name = "navigate_app_or_timer",
            description = "Navigate to a TuneMind Compose screen or configure a playback sleep timer in minutes (0 cancels).",
            statusLabel = "Updating app screen / timer",
            parametersSchema = schemaObj(
                props = mapOf(
                    "target_screen" to prop(
                        "string",
                        "Screen destination.",
                        listOf("PULSE_AGENT", "FRESH", "RESTING_VAULT", "FOR_YOU", "RHYTHM_DNA", "STATS", "WEEKLY_REPORT", "HEARING_HEALTH", "SEARCH", "NOW_PLAYING", "TUNEMIND_PLAYLISTS", "PROFILE", "SETTINGS")
                    ),
                    "playlist_name" to prop("string", "Optional playlist name when opening TUNEMIND_PLAYLISTS."),
                    "sleep_timer_minutes" to prop("integer", "Set sleep timer in minutes (>0) or 0 to cancel.")
                )
            ),
            executor = ::execNavigateOrTimer
        )
    )

    // ─── Executors (100% Real Repo Data & Capabilities) ───────────────────────

    private suspend fun execGetLivePlaybackState(): JSONObject {
        val listener = TuneMindListenerService.instance
        val snap = listener?.getActivePlaybackSnapshot()
        val vmNp = host.getNowPlaying()
        val inAppPlaying = TuneMindInAppPlayer.isPlaying()
        val ytPlaying = TuneMindYouTubeEmbedController.isPlaying()

        val activeTitle = when {
            inAppPlaying -> TuneMindInAppPlayer.currentTitleFlow.value
            ytPlaying -> TuneMindYouTubeEmbedController.currentTitleFlow.value
            !snap?.title.isNullOrBlank() -> snap!!.title
            else -> vmNp?.title
        }.orEmpty()
        val activeArtist = when {
            inAppPlaying -> TuneMindInAppPlayer.currentArtistFlow.value
            ytPlaying -> TuneMindYouTubeEmbedController.currentArtistFlow.value
            !snap?.artist.isNullOrBlank() -> snap!!.artist
            else -> vmNp?.artist
        }.orEmpty()
        val isPlaying = when {
            inAppPlaying || ytPlaying -> true
            snap != null -> snap.isPlaying
            else -> vmNp?.isPlaying == true
        }
        val activeSource = when {
            inAppPlaying -> "tunemind_inapp"
            ytPlaying -> "youtube_embed_inapp"
            !snap?.packageName.isNullOrBlank() -> snap!!.packageName
            else -> vmNp?.sourcePackage ?: "unknown_player"
        }

        val registry = MusicProviderRegistry.getInstance(appContext)
        val installed = registry.getInstalledProviders()
        val defaultProviderId = registry.getPreferredProviderId()
        val spotifyStatus = SpotifyPremiumConnectManager.ensureLoaded(appContext)
        val deviceCheck = if (spotifyStatus.isConnected && spotifyStatus.isPremium) {
            SpotifyPremiumConnectManager.checkActiveDevice(appContext)
        } else null
        val audioSnap = AudioDeviceObserver.getActiveAudioDeviceSnapshot(appContext)

        val installedArr = JSONArray()
        installed.forEach { p ->
            installedArr.put(JSONObject().apply {
                put("provider_id", p.providerId)
                put("display_name", p.displayName)
                put("package_name", p.packageName)
            })
        }

        return JSONObject().apply {
            put("ok", true)
            put("current_screen", host.getCurrentScreenName())
            put("now_playing", JSONObject().apply {
                put("title", activeTitle.ifBlank { "Nothing active" })
                put("artist", activeArtist)
                put("album", snap?.album ?: "")
                put("is_playing", isPlaying)
                put("source_package", activeSource)
                put("position_ms", snap?.positionMs ?: vmNp?.positionMs ?: 0L)
                put("duration_ms", snap?.durationMs ?: vmNp?.durationMs ?: 0L)
                put("spotify_uri", vmNp?.spotifyUri ?: "")
            })
            put("spotify", JSONObject().apply {
                put("is_connected", spotifyStatus.isConnected)
                put("is_premium", spotifyStatus.isPremium)
                put("has_active_connect_device", deviceCheck?.hasActiveDevice == true)
                put("active_device_name", deviceCheck?.deviceName ?: spotifyStatus.lastDeviceName)
                put("active_device_type", deviceCheck?.deviceType ?: "")
            })
            put("player_preferences", JSONObject().apply {
                put("has_explicit_default", registry.hasExplicitDefault())
                put("default_provider_id", defaultProviderId)
                put("default_provider_name", registry.getProvider(defaultProviderId).displayName)
                put("installed_players", installedArr)
            })
            put("audio_output", JSONObject().apply {
                put("device", audioSnap.productName)
                put("category", audioSnap.transducerCategory)
                put("estimated_db", audioSnap.estimatedDb.toDouble())
                put("safe_headroom_db", audioSnap.headroomDb.toDouble())
                put("volume_index", audioSnap.volumeIndex)
                put("max_volume", audioSnap.maxVolume)
            })
            put("sleep_timer_remaining_minutes", host.getSleepTimerRemainingMinutes() ?: JSONObject.NULL)
            put("notification_listener_connected", TuneMindListenerService.isConnected)
        }
    }

    private suspend fun execControlPlayback(args: JSONObject): JSONObject {
        val cmd = args.optString("command").trim().lowercase()
        val listener = TuneMindListenerService.instance
        val isSpotifyPremium = SpotifyPremiumConnectManager.isConnectedAndPremium(appContext)
        val snapPkg = listener?.getActivePlaybackSnapshot()?.packageName

        val ok: Boolean = when (cmd) {
            "pause" -> {
                var handled = false
                if (TuneMindInAppPlayer.hasActiveTrack()) { TuneMindInAppPlayer.pause(); handled = true }
                if (TuneMindYouTubeEmbedController.hasActiveVideo()) { TuneMindYouTubeEmbedController.pause(); handled = true }
                if (listener?.pause(snapPkg) == true) handled = true
                if (!handled && isSpotifyPremium) handled = SpotifyPremiumConnectManager.pausePlayback(appContext)
                if (handled) host.onPauseUiSync()
                handled
            }
            "resume" -> {
                var handled = false
                if (TuneMindInAppPlayer.hasActiveTrack() && TuneMindInAppPlayer.resume()) handled = true
                if (!handled && TuneMindYouTubeEmbedController.hasActiveVideo() && TuneMindYouTubeEmbedController.resume()) handled = true
                if (!handled && listener?.play(snapPkg) == true) handled = true
                if (!handled && isSpotifyPremium) handled = SpotifyPremiumConnectManager.resumePlayback(appContext)
                if (handled) host.onResumeUiSync()
                handled
            }
            "next" -> {
                (listener?.skipNext(snapPkg) == true) ||
                    (isSpotifyPremium && SpotifyPremiumConnectManager.skipNext(appContext))
            }
            "previous" -> {
                (listener?.skipPrev(snapPkg) == true) ||
                    (isSpotifyPremium && SpotifyPremiumConnectManager.skipPrevious(appContext))
            }
            "seek_to_ms", "seek_delta_ms" -> {
                val absMs = if (args.has("position_ms") && !args.isNull("position_ms")) args.optLong("position_ms") else null
                val deltaMs = if (args.has("delta_ms") && !args.isNull("delta_ms")) args.optLong("delta_ms") else null
                when {
                    TuneMindYouTubeEmbedController.hasActiveVideo() -> {
                        val dest = (absMs ?: (TuneMindYouTubeEmbedController.positionMsFlow.value + (deltaMs ?: 0L))).coerceAtLeast(0L)
                        TuneMindYouTubeEmbedController.seekTo(dest); true
                    }
                    TuneMindInAppPlayer.hasActiveTrack() -> {
                        val dest = (absMs ?: (TuneMindInAppPlayer.positionMsFlow.value + (deltaMs ?: 0L))).coerceAtLeast(0L)
                        TuneMindInAppPlayer.seekTo(dest); true
                    }
                    isSpotifyPremium && SpotifyPremiumConnectManager.seekPlayback(appContext, absMs, deltaMs) -> true
                    listener != null -> {
                        val cur = listener.getActivePlaybackSnapshot(snapPkg)?.positionMs ?: 0L
                        listener.seekTo(snapPkg, (absMs ?: (cur + (deltaMs ?: 0L))).coerceAtLeast(0L))
                    }
                    else -> false
                }
            }
            "set_volume_percent" -> {
                val pct = args.optInt("volume_percent", 60).coerceIn(0, 100)
                AudioDeviceObserver.setMediaVolumePercent(appContext, pct, showUi = false)
                if (isSpotifyPremium) SpotifyPremiumConnectManager.setVolumePercent(appContext, pct)
                true
            }
            "set_shuffle" -> isSpotifyPremium && SpotifyPremiumConnectManager.setShuffle(appContext, args.optBoolean("shuffle_enabled", true))
            "set_repeat_mode" -> isSpotifyPremium && SpotifyPremiumConnectManager.setRepeatMode(appContext, args.optString("repeat_mode", "off"))
            "transfer_spotify_device" -> isSpotifyPremium && SpotifyPremiumConnectManager.transferPlaybackDevice(appContext, deviceName = args.optString("device_id_or_name")).first
            else -> return errorResult("Unsupported playback command '$cmd'")
        }

        return JSONObject().apply {
            put("ok", ok)
            put("command", cmd)
            put("detail", if (ok) "Executed '$cmd' successfully." else "No active playback session responded to '$cmd'.")
        }
    }

    private suspend fun execPlayTrack(args: JSONObject): JSONObject {
        val rawTitle = args.optString("title").trim()
        if (rawTitle.isBlank()) return errorResult("Track title is required")
        val rawArtist = args.optString("artist").trim()
        val rawUri = args.optString("spotify_uri").trim()
        val explicitPlayer = args.optString("target_player").trim()

        val registry = MusicProviderRegistry.getInstance(appContext)
        val spotifyConnected = SpotifyPremiumConnectManager.isConnected(appContext)

        val resolvedProviderId = when {
            explicitPlayer.contains("spotify", true) -> SpotifyMusicProvider.PROVIDER_ID
            explicitPlayer.contains("youtube", true) -> "youtube_music"
            explicitPlayer.contains("boomplay", true) -> "boomplay"
            explicitPlayer.contains("audiomack", true) -> "audiomack"
            explicitPlayer.contains("tunemind", true) || explicitPlayer.contains("inapp", true) -> TuneMindInAppProvider.PROVIDER_ID
            spotifyConnected && !registry.hasExplicitDefault() -> SpotifyMusicProvider.PROVIDER_ID
            else -> registry.getPreferredProviderId()
        }
        val provider = registry.getProvider(resolvedProviderId)
        val targetPkg = provider.packageName.ifBlank { resolvedProviderId }
        val isSpotifyTarget = targetPkg.contains("spotify", true) || resolvedProviderId == SpotifyMusicProvider.PROVIDER_ID

        val searchQuery = listOf(rawTitle, rawArtist).filter { it.isNotBlank() }.joinToString(" ")
        var verifiedUri = MusicPlayerAutoLauncher.extractSpotifyTrackUri(rawUri)
        if (verifiedUri.isNullOrBlank() && isSpotifyTarget && spotifyConnected) {
            verifiedUri = SpotifyPremiumConnectManager.searchExactTrackUri(
                context = appContext,
                title = rawTitle,
                artist = rawArtist,
                query = searchQuery
            )
        }

        val canonical = TrackIdentityResolver.resolveCanonicalIdentity(
            trackId = verifiedUri,
            spotifyUri = verifiedUri,
            title = rawTitle,
            artist = rawArtist
        )

        val rec = RecommendedTrack(
            id = canonical.trackId,
            title = rawTitle,
            artist = rawArtist,
            reason = "Selected by TuneMind Realtime Voice Agent",
            confidence = 0.99f,
            trackId = canonical.trackId,
            spotifyUri = verifiedUri
        )

        val neverExternal = AgentSettingsStore.isStrictNoAppSwitchEnabled(appContext)
        host.onTrackDispatched(rawTitle, rawArtist, targetPkg)

        val launchResult = if (resolvedProviderId == TuneMindInAppProvider.PROVIDER_ID && !isSpotifyTarget) {
            registry.launchRecommendation(rec, TuneMindInAppProvider.PROVIDER_ID)
        } else {
            MusicPlayerAutoLauncher.launchWithAutoPlay(
                context = appContext,
                providerId = resolvedProviderId,
                displayName = provider.displayName,
                packageName = targetPkg,
                track = rec,
                query = searchQuery,
                deepLinkUri = verifiedUri,
                neverOpenExternalApp = neverExternal
            )
        }

        host.onPlaybackLaunchResult(launchResult)

        // Log verified decision into user-scoped Supabase memory
        SupabaseIntelligenceSyncManager.recordVoiceOrTrackDecisionAsync(
            context = appContext,
            triggerTranscript = "Realtime play_track: $rawTitle",
            agentTtsResponse = "Playing $rawTitle",
            action = "PLAY_TRACK_NOW",
            osIntent = "OS_MEDIA_NEXT_AND_DEEP_LINK",
            trackTitle = rawTitle,
            trackArtist = rawArtist,
            spotifyUri = verifiedUri,
            trackId = canonical.trackId
        )

        return JSONObject().apply {
            put("ok", launchResult.isSuccess)
            put("launched", launchResult.isSuccess)
            put("title", rawTitle)
            put("artist", rawArtist)
            put("resolved_spotify_uri", verifiedUri ?: "")
            put("target_player", provider.displayName)
            put(
                "detail",
                when (launchResult) {
                    is PlaybackLaunchResult.DirectPlaybackLaunched -> launchResult.message
                    is PlaybackLaunchResult.LaunchFailed -> launchResult.reason
                    else -> "Playback attempted via ${provider.displayName}."
                }
            )
        }
    }

    private suspend fun execPlaySpotifyContext(args: JSONObject): JSONObject {
        val ctxUri = args.optString("context_uri").trim()
        if (ctxUri.isBlank()) return errorResult("context_uri is required (e.g. spotify:playlist:...)")
        val label = args.optString("label", "Spotify Mix").ifBlank { "Spotify Mix" }
        val shuffle = if (args.has("shuffle_enabled") && !args.isNull("shuffle_enabled")) args.optBoolean("shuffle_enabled") else null

        val res = SpotifyPremiumConnectManager.playSpotifyContextSilently(
            context = appContext,
            contextUri = ctxUri,
            shuffleEnabled = shuffle
        )
        return when (res) {
            is SpotifyPlayResult.Success -> JSONObject().apply {
                put("ok", true)
                put("context_uri", ctxUri)
                put("label", label)
                put("device", res.deviceName)
                put("detail", "Playing $label silently on ${res.deviceName}.")
            }
            is SpotifyPlayResult.NoActiveDevice -> JSONObject().apply {
                put("ok", false)
                put("error", res.message)
            }
            is SpotifyPlayResult.PremiumRequired -> errorResult(res.message)
            is SpotifyPlayResult.Failed -> errorResult(res.reason)
            SpotifyPlayResult.NotConnected -> errorResult("Spotify is not connected.")
        }
    }

    private suspend fun execQueueSpotifyTrack(args: JSONObject): JSONObject {
        val title = args.optString("title").trim()
        if (title.isBlank()) return errorResult("Track title is required")
        val artist = args.optString("artist").trim()
        var uri = MusicPlayerAutoLauncher.extractSpotifyTrackUri(args.optString("spotify_uri"))
        if (uri.isNullOrBlank()) {
            uri = SpotifyPremiumConnectManager.searchExactTrackUri(
                context = appContext,
                title = title,
                artist = artist,
                query = "$title $artist".trim()
            )
        }
        if (uri.isNullOrBlank()) return errorResult("Could not locate '$title' on Spotify to queue.")
        val queued = SpotifyPremiumConnectManager.queueTrack(appContext, uri)
        return JSONObject().apply {
            put("ok", queued)
            put("title", title)
            put("artist", artist)
            put("spotify_uri", uri)
            put("detail", if (queued) "Added '$title' to your Spotify queue." else "Could not reach an active Spotify device to queue '$title'.")
        }
    }

    private suspend fun execSearchSpotifyCatalog(args: JSONObject): JSONObject {
        val q = args.optString("query").trim()
        if (q.isBlank()) return errorResult("Search query is required")
        val limit = args.optInt("limit", 6).coerceIn(1, 10)
        val rawTypes = args.optString("types", "track,artist").split(",")
            .map { it.trim().lowercase() }
            .filter { it in setOf("track", "artist", "album", "playlist") }
            .ifEmpty { listOf("track", "artist") }

        val token = SpotifyPremiumConnectManager.refreshAccessTokenIfNeeded(appContext)
        if (!token.isNullOrBlank()) {
            val encoded = URLEncoder.encode(q, "UTF-8")
            val url = "$SPOTIFY_API_BASE/search?q=$encoded&type=${rawTypes.joinToString(",")}&limit=$limit"
            val json = executeSpotifyGet(url, token)
            if (json != null) {
                val tracksArr = JSONArray()
                val rawTracks = json.optJSONObject("tracks")?.optJSONArray("items")
                if (rawTracks != null) {
                    for (i in 0 until rawTracks.length()) {
                        val t = rawTracks.optJSONObject(i) ?: continue
                        val artistNames = parseArtistNames(t.optJSONArray("artists"))
                        tracksArr.put(JSONObject().apply {
                            put("title", t.optString("name"))
                            put("artist", artistNames)
                            put("album", t.optJSONObject("album")?.optString("name").orEmpty())
                            put("popularity", t.optInt("popularity", 0))
                            put("explicit", t.optBoolean("explicit", false))
                            put("duration_ms", t.optLong("duration_ms", 0L))
                            put("spotify_uri", t.optString("uri"))
                        })
                    }
                }
                val artistsArr = JSONArray()
                val rawArtists = json.optJSONObject("artists")?.optJSONArray("items")
                if (rawArtists != null) {
                    for (i in 0 until rawArtists.length()) {
                        val a = rawArtists.optJSONObject(i) ?: continue
                        artistsArr.put(JSONObject().apply {
                            put("artist_id", a.optString("id"))
                            put("name", a.optString("name"))
                            put("spotify_uri", a.optString("uri"))
                            put("popularity", a.optInt("popularity", 0))
                            put("genres", a.optJSONArray("genres") ?: JSONArray())
                        })
                    }
                }
                val playlistsArr = JSONArray()
                val rawPlaylists = json.optJSONObject("playlists")?.optJSONArray("items")
                if (rawPlaylists != null) {
                    for (i in 0 until rawPlaylists.length()) {
                        val p = rawPlaylists.optJSONObject(i) ?: continue
                        playlistsArr.put(JSONObject().apply {
                            put("id", p.optString("id"))
                            put("name", p.optString("name"))
                            put("context_uri", p.optString("uri"))
                            put("owner", p.optJSONObject("owner")?.optString("display_name").orEmpty())
                        })
                    }
                }
                return JSONObject().apply {
                    put("ok", true)
                    put("source", "spotify_live_api")
                    put("tracks", tracksArr)
                    put("artists", artistsArr)
                    put("playlists", playlistsArr)
                }
            }
        }

        // Open-catalog fallback via TuneMind backend when Spotify token is unavailable
        val baseUrl = BackendEndpointManager.getActiveHttpBaseUrl(appContext).trimEnd('/')
        val backendUrl = "$baseUrl/api/v1/music/search?q=${URLEncoder.encode(q, "UTF-8")}&type=track&limit=$limit"
        val req = Request.Builder().url(backendUrl).get().build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use errorResult("Catalog search HTTP ${resp.code}")
                val body = JSONObject(resp.body?.string().orEmpty())
                JSONObject().apply {
                    put("ok", true)
                    put("source", "tunemind_open_catalog")
                    put("tracks", body.optJSONArray("tracks") ?: JSONArray())
                }
            }
        }.getOrElse { err -> errorResult("Catalog search failed: ${err.message}") }
    }

    private suspend fun execGetSpotifyArtistTopTracks(args: JSONObject): JSONObject {
        val artistName = args.optString("artist_name").trim()
        var artistId = args.optString("artist_id").trim()
        val limit = args.optInt("limit", 8).coerceIn(1, 10)
        if (artistName.isBlank() && artistId.isBlank()) {
            return errorResult("artist_name or artist_id is required")
        }

        // 1. Cross-reference user's own Room history for this artist
        val dao: CrossAppLibraryDao = scopedDb().crossAppLibraryDao()
        val allTracks = dao.getAllLibraryTracksOnce()
        val userTracksForArtist = allTracks
            .filter { t ->
                artistName.isNotBlank() && (
                    t.artist.contains(artistName, ignoreCase = true) ||
                        artistName.contains(t.artist, ignoreCase = true)
                    )
            }
            .sortedByDescending { it.playCount }

        val userPlaysByNormTitle = mutableMapOf<String, Int>()
        val userArr = JSONArray()
        userTracksForArtist.take(limit).forEach { t ->
            userPlaysByNormTitle[ListeningPlayDeduplicator.canonicalTrackKey(t.title, t.artist)] = t.playCount
            userArr.put(JSONObject().apply {
                put("title", t.title)
                put("artist", t.artist)
                put("user_verified_play_count", t.playCount)
                put("is_liked", t.isLiked)
                put("is_in_vault", t.isInVault)
                put("last_played_at_ms", t.lastPlayedAtMs)
            })
        }

        // 2. Fetch live Spotify Artist Top Tracks (/v1/artists/{id}/top-tracks)
        val token = SpotifyPremiumConnectManager.refreshAccessTokenIfNeeded(appContext)
        val spotifyTopArr = JSONArray()
        var resolvedArtistName = artistName

        if (!token.isNullOrBlank()) {
            if (artistId.isBlank() && artistName.isNotBlank()) {
                val searchJson = executeSpotifyGet(
                    "$SPOTIFY_API_BASE/search?q=${URLEncoder.encode(artistName, "UTF-8")}&type=artist&limit=3",
                    token
                )
                val firstArtist = searchJson?.optJSONObject("artists")?.optJSONArray("items")?.optJSONObject(0)
                if (firstArtist != null) {
                    artistId = firstArtist.optString("id")
                    resolvedArtistName = firstArtist.optString("name", artistName)
                }
            }
            if (artistId.isNotBlank()) {
                val topJson = executeSpotifyGet("$SPOTIFY_API_BASE/artists/$artistId/top-tracks", token)
                val items = topJson?.optJSONArray("tracks")
                if (items != null) {
                    for (i in 0 until minOf(limit, items.length())) {
                        val tr = items.optJSONObject(i) ?: continue
                        val trTitle = tr.optString("name")
                        val trArtist = parseArtistNames(tr.optJSONArray("artists")).ifBlank { resolvedArtistName }
                        val key = ListeningPlayDeduplicator.canonicalTrackKey(trTitle, trArtist)
                        spotifyTopArr.put(JSONObject().apply {
                            put("rank", i + 1)
                            put("title", trTitle)
                            put("artist", trArtist)
                            put("spotify_popularity", tr.optInt("popularity", 0))
                            put("spotify_uri", tr.optString("uri"))
                            put("album", tr.optJSONObject("album")?.optString("name").orEmpty())
                            put("user_verified_play_count", userPlaysByNormTitle[key] ?: 0)
                        })
                    }
                }
            }
        }

        return JSONObject().apply {
            put("ok", true)
            put("artist_name", resolvedArtistName)
            put("artist_id", artistId)
            put("spotify_top_tracks", spotifyTopArr)
            put("user_personal_plays_for_artist", userArr)
        }
    }

    private suspend fun execGetSpotifyAccountLibrary(args: JSONObject): JSONObject {
        val queryType = args.optString("query_type").trim().lowercase()
        val limit = args.optInt("limit", 12).coerceIn(1, 25)
        if (!SpotifyPremiumConnectManager.isConnected(appContext)) {
            return errorResult("Spotify account is not connected.")
        }

        return when (queryType) {
            "top_tracks" -> {
                val (tracks, scopeMissing) = SpotifyPremiumConnectManager.fetchTopTracks(appContext, limit)
                if (scopeMissing) return errorResult("Spotify permission scope user-top-read is needed; reconnect Spotify in Settings.")
                val arr = JSONArray()
                tracks.forEach { t ->
                    arr.put(JSONObject().apply {
                        put("title", t.title); put("artist", t.artist); put("album", t.album)
                        put("spotify_uri", t.spotifyUri ?: "")
                    })
                }
                JSONObject().put("ok", true).put("tracks", arr)
            }
            "top_artists" -> {
                val (artists, scopeMissing) = SpotifyPremiumConnectManager.fetchTopArtists(appContext, limit)
                if (scopeMissing) return errorResult("Spotify permission scope user-top-read is needed; reconnect Spotify in Settings.")
                val arr = JSONArray()
                artists.forEach { a ->
                    arr.put(JSONObject().apply {
                        put("name", a.name); put("spotify_id", a.spotifyId)
                        put("genres", JSONArray(a.genres))
                    })
                }
                JSONObject().put("ok", true).put("artists", arr)
            }
            "recently_played" -> {
                val (tracks, scopeMissing) = SpotifyPremiumConnectManager.fetchRecentlyPlayedTracks(appContext, limit)
                if (scopeMissing) return errorResult("Spotify permission scope user-read-recently-played is needed.")
                val arr = JSONArray()
                tracks.forEach { t ->
                    arr.put(JSONObject().apply {
                        put("title", t.title); put("artist", t.artist); put("played_at_ms", t.addedAtMs)
                        put("spotify_uri", t.spotifyUri)
                    })
                }
                JSONObject().put("ok", true).put("tracks", arr)
            }
            "liked_songs" -> {
                val arr = JSONArray()
                val liked = SpotifyPremiumConnectManager.fetchLikedTracks(appContext, limit)
                liked.forEach { t ->
                    arr.put(JSONObject().apply {
                        put("title", t.title); put("artist", t.artist); put("spotify_uri", t.spotifyUri)
                    })
                }
                JSONObject().put("ok", true).put("tracks", arr)
            }
            "playlists" -> {
                val pls = SpotifyPremiumConnectManager.fetchUserPlaylistsWithTracks(appContext, maxPlaylists = limit)
                val arr = JSONArray()
                pls.forEach { p ->
                    arr.put(JSONObject().apply {
                        put("id", p.spotifyId)
                        put("name", p.name)
                        put("context_uri", "spotify:playlist:${p.spotifyId}")
                        put("track_count", p.totalTracks)
                    })
                }
                JSONObject().put("ok", true).put("playlists", arr)
            }
            "playlist_tracks" -> {
                val needle = args.optString("playlist_id_or_name").trim()
                if (needle.isBlank()) return errorResult("playlist_id_or_name is required")
                val pls = SpotifyPremiumConnectManager.fetchUserPlaylistsWithTracks(appContext, maxPlaylists = 30)
                val matched = pls.firstOrNull {
                    it.spotifyId.equals(needle, true) || it.name.contains(needle, true)
                }
                val plId = matched?.spotifyId ?: needle.removePrefix("spotify:playlist:")
                val tracks = SpotifyPremiumConnectManager.fetchPlaylistTracks(appContext, plId, maxTracks = limit)
                val arr = JSONArray()
                tracks.forEach { t ->
                    arr.put(JSONObject().apply {
                        put("title", t.title); put("artist", t.artist); put("spotify_uri", t.spotifyUri)
                    })
                }
                JSONObject().apply {
                    put("ok", true)
                    put("playlist_name", matched?.name ?: plId)
                    put("context_uri", "spotify:playlist:$plId")
                    put("tracks", arr)
                }
            }
            else -> errorResult("Unknown query_type '$queryType'")
        }
    }

    private suspend fun execSetSpotifyTrackLike(args: JSONObject): JSONObject {
        val title = args.optString("title").trim()
        val artist = args.optString("artist").trim()
        val liked = args.optBoolean("liked", true)
        if (title.isBlank()) return errorResult("Track title is required")

        var uri = MusicPlayerAutoLauncher.extractSpotifyTrackUri(args.optString("spotify_uri"))
        if (uri.isNullOrBlank() && SpotifyPremiumConnectManager.isConnected(appContext)) {
            uri = SpotifyPremiumConnectManager.searchExactTrackUri(appContext, title, artist, "$title $artist".trim())
        }
        val canonicalKey = ListeningPlayDeduplicator.canonicalTrackKey(title, artist)
        val dao = scopedDb().crossAppLibraryDao()
        dao.setTrackLiked(canonicalKey, liked)

        val spotifyUpdated = if (!uri.isNullOrBlank() && SpotifyPremiumConnectManager.isConnected(appContext)) {
            if (liked) SpotifyPremiumConnectManager.likeTrackOnSpotify(appContext, uri)
            else SpotifyPremiumConnectManager.unlikeTrackOnSpotify(appContext, uri)
        } else false

        return JSONObject().apply {
            put("ok", true)
            put("title", title)
            put("liked", liked)
            put("spotify_updated", spotifyUpdated)
            put("local_library_updated", true)
        }
    }

    private suspend fun execQueryListeningHistory(args: JSONObject): JSONObject {
        val artistFilter = args.optString("artist_filter").trim().lowercase()
        val titleQuery = args.optString("title_query").trim().lowercase()
        val sortBy = args.optString("sort_by", "most_played").trim().lowercase()
        val timeWindow = args.optString("time_window", "all_time").trim().lowercase()
        val likedOnly = args.optBoolean("liked_only", false)
        val inVaultOnly = args.optBoolean("in_vault_only", false)
        val limit = args.optInt("limit", 10).coerceIn(1, 25)

        val dao = scopedDb().crossAppLibraryDao()
        val libraryTracks = dao.getAllLibraryTracksOnce()
        val playLogs = dao.getPlayLogsOnce(1500)
        val nowMs = System.currentTimeMillis()
        val windowStartMs = when (timeWindow) {
            "today" -> nowMs - 86_400_000L
            "7d" -> nowMs - 7L * 86_400_000L
            "30d" -> nowMs - 30L * 86_400_000L
            else -> 0L
        }

        val logsByTrackKey = playLogs.filter { it.playedAtMs >= windowStartMs }.groupBy { it.canonicalTrackKey }
        val sensitivity = AgentSettingsStore.getRepetitionSensitivity(appContext)

        data class TrackRow(
            val entity: CrossAppTrackEntity,
            val playsInWindow: Int,
            val skipsInWindow: Int,
            val avgCompletion: Float,
            val satietyPercent: Int,
            val freshnessPercent: Int
        )

        val rows = libraryTracks.filter { t ->
            (!likedOnly || t.isLiked) &&
                (!inVaultOnly || t.isInVault) &&
                (artistFilter.isBlank() || t.artist.lowercase().contains(artistFilter)) &&
                (titleQuery.isBlank() || t.title.lowercase().contains(titleQuery))
        }.map { t ->
            val tLogs = logsByTrackKey[t.canonicalTrackKey].orEmpty()
            val plays = if (windowStartMs == 0L) maxOf(t.playCount, tLogs.count { !it.wasSkipped }) else tLogs.count { !it.wasSkipped }
            val skips = tLogs.count { it.wasSkipped }
            val avgComp = if (tLogs.isNotEmpty()) tLogs.map { it.completionRatio }.average().toFloat() else 1f
            val fatigue = RestingVaultEngine.evaluateTrackFatigue(
                playTimestampsMs = tLogs.map { it.playedAtMs },
                listenedRatios = tLogs.map { it.completionRatio },
                sensitivity = sensitivity,
                nowMs = nowMs
            )
            TrackRow(
                entity = t,
                playsInWindow = plays,
                skipsInWindow = skips,
                avgCompletion = avgComp,
                satietyPercent = fatigue.effectiveSatiety,
                freshnessPercent = fatigue.freshnessPercent
            )
        }

        val sorted = when (sortBy) {
            "recent" -> rows.sortedByDescending { it.entity.lastPlayedAtMs }
            "most_skipped" -> rows.sortedByDescending { it.skipsInWindow }
            "highest_fatigue" -> rows.sortedByDescending { it.satietyPercent }
            "freshest" -> rows.sortedByDescending { it.freshnessPercent }
            else -> rows.sortedByDescending { it.playsInWindow }
        }.take(limit)

        val tracksArr = JSONArray()
        sorted.forEach { r ->
            tracksArr.put(JSONObject().apply {
                put("title", r.entity.title)
                put("artist", r.entity.artist)
                put("verified_play_count", r.playsInWindow)
                put("skip_count", r.skipsInWindow)
                put("avg_completion_ratio", String.format("%.2f", r.avgCompletion).toDouble())
                put("fatigue_satiety_percent", r.satietyPercent)
                put("freshness_percent", r.freshnessPercent)
                put("is_liked", r.entity.isLiked)
                put("is_in_vault", r.entity.isInVault)
                put("primary_source", r.entity.primarySourcePackage)
                put("last_played_at_ms", r.entity.lastPlayedAtMs)
            })
        }

        val filteredLogs = playLogs.filter { it.playedAtMs >= windowStartMs }
        val totalMinutes = (filteredLogs.sumOf { it.listenedMs } / 60_000L).toInt()
        val totalSkips = filteredLogs.count { it.wasSkipped }

        return JSONObject().apply {
            put("ok", true)
            put("time_window", timeWindow)
            put("total_library_tracks", libraryTracks.size)
            put("window_total_listened_minutes", totalMinutes)
            put("window_total_skips", totalSkips)
            put("window_total_log_events", filteredLogs.size)
            put("tracks", tracksArr)
        }
    }

    private suspend fun execManageRestingVault(args: JSONObject): JSONObject {
        val op = args.optString("operation").trim().lowercase()
        val title = args.optString("title").trim()
        val artist = args.optString("artist").trim()
        val dao = scopedDb().crossAppLibraryDao()
        val all = dao.getAllLibraryTracksOnce()

        return when (op) {
            "list" -> {
                val arr = JSONArray()
                all.filter { it.isInVault }.forEach { v ->
                    arr.put(JSONObject().apply {
                        put("title", v.title); put("artist", v.artist); put("play_count", v.playCount)
                    })
                }
                JSONObject().put("ok", true).put("vault_tracks", arr)
            }
            "quarantine", "restore" -> {
                if (title.isBlank()) return errorResult("Track title is required for '$op'")
                val inVault = (op == "quarantine")
                val match = all.firstOrNull {
                    it.title.equals(title, true) && (artist.isBlank() || it.artist.contains(artist, true))
                }
                if (match != null) {
                    dao.setTrackVaultState(match.canonicalTrackKey, inVault)
                    if (!inVault) host.onVaultRestoreByTitle(match.title)
                    host.onRefreshVaultAndStats()
                    SupabaseIntelligenceSyncManager.triggerRelationalTrackCardsSyncAsync(appContext)
                    JSONObject().apply {
                        put("ok", true)
                        put("operation", op)
                        put("title", match.title)
                        put("artist", match.artist)
                        put("is_in_vault", inVault)
                    }
                } else {
                    if (!inVault) host.onVaultRestoreByTitle(title)
                    errorResult("Track '$title' was not found in your TuneMind Room library.")
                }
            }
            else -> errorResult("Unknown vault operation '$op'")
        }
    }

    private suspend fun execGetHearingHealth(): JSONObject {
        val audio = AudioDeviceObserver.getActiveAudioDeviceSnapshot(appContext)
        val logs = scopedDb().crossAppLibraryDao().getPlayLogsOnce(500)
        val todayCutoff = System.currentTimeMillis() - 86_400_000L
        val todayMs = logs.filter { it.playedAtMs >= todayCutoff }.sumOf { it.listenedMs }
        val todayMin = (todayMs / 60_000L).toInt()
        val safeHours = if (audio.estimatedDb <= 72f) 24f else (8.0 / Math.pow(2.0, ((audio.estimatedDb - 85f) / 3f).toDouble())).toFloat().coerceIn(0.05f, 24f)
        val remaining = (safeHours - (todayMs.toFloat() / 3_600_000f)).coerceAtLeast(0f)

        return JSONObject().apply {
            put("ok", true)
            put("output_device", audio.productName)
            put("transducer_category", audio.transducerCategory)
            put("estimated_db", audio.estimatedDb.toDouble())
            put("safe_headroom_db", audio.headroomDb.toDouble())
            put("today_exposure_minutes", todayMin)
            put("safe_hours_remaining_today", String.format("%.1f", remaining).toDouble())
        }
    }

    private suspend fun execManageTuneMindPlaylists(args: JSONObject): JSONObject {
        val op = args.optString("operation").trim().lowercase()
        val pName = args.optString("playlist_name").trim()
        val pId = args.optString("playlist_id").trim()
        val pDao = scopedDb().tuneMindPlaylistDao()
        val playlists = pDao.getAllPlaylistsOnce()

        fun findPlaylist(): TuneMindPlaylistEntity? = playlists.firstOrNull {
            (pId.isNotBlank() && it.playlistId == pId) ||
                (pName.isNotBlank() && it.name.equals(pName, true))
        }

        return when (op) {
            "list" -> {
                val arr = JSONArray()
                for (pl in playlists) {
                    val count = pDao.getTracksForPlaylistOnce(pl.playlistId).size
                    arr.put(JSONObject().apply {
                        put("playlist_id", pl.playlistId)
                        put("name", pl.name)
                        put("description", pl.description)
                        put("track_count", count)
                        put("is_spotify_synced", SpotifyLibrarySyncEngine.isSpotifySyncedPlaylistId(pl.playlistId))
                    })
                }
                JSONObject().put("ok", true).put("playlists", arr)
            }
            "get_tracks" -> {
                val pl = findPlaylist() ?: return errorResult("Playlist '$pName' not found.")
                val tracks = pDao.getTracksForPlaylistOnce(pl.playlistId)
                val arr = JSONArray()
                tracks.forEach { tr ->
                    arr.put(JSONObject().apply {
                        put("entry_id", tr.entryId)
                        put("title", tr.title)
                        put("artist", tr.artist)
                        put("external_uri", tr.externalUri)
                    })
                }
                JSONObject().put("ok", true).put("playlist_name", pl.name).put("tracks", arr)
            }
            "create", "add_tracks" -> {
                val cleanName = pName.ifBlank { "TuneMind Mix" }
                val existing = findPlaylist()
                val targetId = existing?.playlistId ?: UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                pDao.upsertPlaylist(
                    existing?.copy(updatedAtMs = now) ?: TuneMindPlaylistEntity(
                        playlistId = targetId,
                        name = cleanName,
                        description = args.optString("description", "Created via TuneMind Voice"),
                        createdAtMs = now,
                        updatedAtMs = now
                    )
                )
                val trTitle = args.optString("track_title").trim()
                val trArtist = args.optString("track_artist").trim()
                var added = 0
                if (trTitle.isNotBlank()) {
                    var trUri = MusicPlayerAutoLauncher.extractSpotifyTrackUri(args.optString("spotify_uri"))
                    if (trUri.isNullOrBlank() && SpotifyPremiumConnectManager.isConnected(appContext)) {
                        trUri = SpotifyPremiumConnectManager.searchExactTrackUri(appContext, trTitle, trArtist, "$trTitle $trArtist".trim())
                    }
                    val canon = TrackIdentityResolver.resolveCanonicalIdentity(spotifyUri = trUri, title = trTitle, artist = trArtist)
                    val dedupKey = ListeningPlayDeduplicator.canonicalTrackKey(trTitle, trArtist)
                    val currentTracks = pDao.getTracksForPlaylistOnce(targetId)
                    pDao.upsertPlaylistTracks(
                        listOf(
                            TuneMindPlaylistTrackEntity(
                                entryId = "${targetId}::$dedupKey",
                                playlistId = targetId,
                                title = trTitle,
                                artist = trArtist,
                                externalUri = trUri ?: canon.spotifyUri.orEmpty(),
                                trackId = canon.trackId,
                                sourcePlayer = MusicProviderRegistry.getInstance(appContext).getPreferredProviderId(),
                                positionIndex = currentTracks.size,
                                addedAtMs = now
                            )
                        )
                    )
                    pDao.touchPlaylist(targetId, now)
                    added = 1
                }
                SupabaseIntelligenceSyncManager.triggerRelationalTrackCardsSyncAsync(appContext)
                JSONObject().put("ok", true).put("playlist_id", targetId).put("name", cleanName).put("added_tracks", added)
            }
            "remove_track" -> {
                val pl = findPlaylist() ?: return errorResult("Playlist '$pName' not found.")
                val trTitle = args.optString("track_title").trim()
                val tracks = pDao.getTracksForPlaylistOnce(pl.playlistId)
                val hit = tracks.firstOrNull { it.title.equals(trTitle, true) }
                    ?: return errorResult("Track '$trTitle' not found in '${pl.name}'.")
                pDao.deleteTrackByEntryId(hit.entryId)
                pDao.touchPlaylist(pl.playlistId)
                SupabaseIntelligenceSyncManager.triggerRelationalTrackCardsSyncAsync(appContext)
                JSONObject().put("ok", true).put("removed_title", hit.title).put("playlist_name", pl.name)
            }
            "rename" -> {
                val pl = findPlaylist() ?: return errorResult("Playlist '$pName' not found.")
                val newName = args.optString("new_name").trim()
                if (newName.isBlank()) return errorResult("new_name is required")
                pDao.renamePlaylist(pl.playlistId, newName)
                JSONObject().put("ok", true).put("old_name", pl.name).put("new_name", newName)
            }
            "delete" -> {
                val pl = findPlaylist() ?: return errorResult("Playlist '$pName' not found.")
                pDao.deleteTracksByPlaylistId(pl.playlistId)
                pDao.deletePlaylistById(pl.playlistId)
                JSONObject().put("ok", true).put("deleted_playlist", pl.name)
            }
            "play" -> {
                val pl = findPlaylist() ?: return errorResult("Playlist '$pName' not found.")
                val spId = SpotifyLibrarySyncEngine.extractSpotifyPlaylistId(pl.playlistId)
                if (!spId.isNullOrBlank() && SpotifyPremiumConnectManager.isConnectedAndPremium(appContext)) {
                    return execPlaySpotifyContext(JSONObject().put("context_uri", "spotify:playlist:$spId").put("label", pl.name))
                }
                val first = pDao.getTracksForPlaylistOnce(pl.playlistId).firstOrNull()
                    ?: return errorResult("Playlist '${pl.name}' has no tracks.")
                execPlayTrack(JSONObject().put("title", first.title).put("artist", first.artist).put("spotify_uri", first.externalUri))
            }
            "export_to_spotify" -> {
                val pl = findPlaylist() ?: return errorResult("Playlist '$pName' not found.")
                val tracks = pDao.getTracksForPlaylistOnce(pl.playlistId)
                val pairs = tracks.map { Triple(it.title, it.artist, it.externalUri) }
                val res = SpotifyPremiumConnectManager.exportPlaylistToSpotify(appContext, pl.name, pl.description, pairs)
                JSONObject().put("ok", res.success).put("playlist_name", pl.name).put("detail", res.message)
            }
            else -> errorResult("Unknown playlist operation '$op'")
        }
    }

    private fun execPreferences(args: JSONObject): JSONObject {
        val mode = args.optString("mode", "get").trim().lowercase()
        val registry = MusicProviderRegistry.getInstance(appContext)
        val themeRepo = ThemePreferencesRepository(appContext)
        val profileCustom = ProfileCustomizationStore.load(appContext)

        if (mode == "update") {
            if (args.has("autonomous_dj_enabled")) AgentSettingsStore.setAutonomousDjEnabled(appContext, args.optBoolean("autonomous_dj_enabled"))
            if (args.has("stay_in_tunemind_enabled")) AgentSettingsStore.setStayInTuneMindEnabled(appContext, args.optBoolean("stay_in_tunemind_enabled"))
            if (args.has("strict_no_app_switch_enabled")) AgentSettingsStore.setStrictNoAppSwitchEnabled(appContext, args.optBoolean("strict_no_app_switch_enabled"))
            if (args.has("kids_mode_enabled")) AgentSettingsStore.setKidsModeEnabled(appContext, args.optBoolean("kids_mode_enabled"))
            if (args.has("private_session_enabled")) AgentSettingsStore.setPrivateSessionEnabled(appContext, args.optBoolean("private_session_enabled"))

            val prefPlayer = args.optString("preferred_player").trim().lowercase()
            if (prefPlayer.isNotBlank()) {
                val targetId = when {
                    prefPlayer.contains("spotify") -> SpotifyMusicProvider.PROVIDER_ID
                    prefPlayer.contains("youtube") -> "youtube_music"
                    prefPlayer.contains("boomplay") -> "boomplay"
                    prefPlayer.contains("audiomack") -> "audiomack"
                    else -> TuneMindInAppProvider.PROVIDER_ID
                }
                registry.setPreferredProviderId(targetId)
            }

            val paletteId = args.optString("palette_id").trim().ifBlank { null }
            val accentHex = args.optString("custom_accent_hex").trim().ifBlank { null }
            if (paletteId != null || accentHex != null) {
                val behavior = if (accentHex.equals("AUTO", true)) ThemeBehavior.ADAPTIVE else null
                host.onSetTheme(paletteId, accentHex, behavior)
            }

            val genresRaw = args.optString("favorite_genres").trim()
            if (genresRaw.isNotBlank()) {
                val parsed = genresRaw.split(",").map { it.trim() }.filter { it.isNotBlank() }
                ProfileCustomizationStore.save(appContext, profileCustom.copy(favoriteGenres = parsed))
            }

            SupabaseIntelligenceSyncManager.recordVoiceOrTrackDecisionAsync(
                context = appContext,
                triggerTranscript = "Realtime preference update",
                agentTtsResponse = "Updated TuneMind preferences",
                action = "UPDATE_PREFERENCES"
            )
        }

        val theme = themeRepo.loadPreferences()
        val updatedProfile = ProfileCustomizationStore.load(appContext)
        return JSONObject().apply {
            put("ok", true)
            put("autonomous_dj_enabled", AgentSettingsStore.isAutonomousDjEnabled(appContext))
            put("stay_in_tunemind_enabled", AgentSettingsStore.isStayInTuneMindEnabled(appContext))
            put("strict_no_app_switch_enabled", AgentSettingsStore.isStrictNoAppSwitchEnabled(appContext))
            put("kids_mode_enabled", AgentSettingsStore.isKidsModeEnabled(appContext))
            put("private_session_enabled", AgentSettingsStore.isPrivateSessionEnabled(appContext))
            put("default_player_id", registry.getPreferredProviderId())
            put("theme_palette", theme.fixedPaletteId)
            put("theme_behavior", theme.behavior.name)
            put("favorite_genres", JSONArray(updatedProfile.favoriteGenres))
        }
    }

    private fun execNavigateOrTimer(args: JSONObject): JSONObject {
        val screen = args.optString("target_screen").trim()
        val plName = args.optString("playlist_name").trim().ifBlank { null }
        if (screen.isNotBlank()) {
            host.onNavigateScreen(screen, plName)
        }
        if (args.has("sleep_timer_minutes")) {
            host.onSetSleepTimerMinutes(args.optInt("sleep_timer_minutes", 0).coerceAtLeast(0))
        }
        return JSONObject().apply {
            put("ok", true)
            put("navigated_to", screen)
            if (args.has("sleep_timer_minutes")) put("sleep_timer_minutes", args.optInt("sleep_timer_minutes", 0))
        }
    }

    // ─── Shared HTTP / JSON Helpers ───────────────────────────────────────────

    private fun executeSpotifyGet(url: String, token: String): JSONObject? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                JSONObject(resp.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Spotify GET failed ($url): ${e.message}")
            null
        }
    }

    private fun parseArtistNames(arr: JSONArray?): String {
        if (arr == null || arr.length() == 0) return ""
        val names = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val n = arr.optJSONObject(i)?.optString("name")?.trim().orEmpty()
            if (n.isNotBlank()) names.add(n)
        }
        return names.joinToString(", ")
    }
}
