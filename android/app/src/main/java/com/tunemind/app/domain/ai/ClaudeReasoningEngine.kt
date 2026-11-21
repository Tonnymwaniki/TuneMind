package com.tunemind.app.domain.ai

import android.content.Context
import com.tunemind.app.BuildConfig
import com.tunemind.app.data.auth.TuneMindAuthSessionManager
import com.tunemind.app.domain.execution.OsExecutionCommand
import com.tunemind.app.domain.execution.ReasoningResponse
import com.tunemind.app.domain.execution.TuneMindExecutionEngine
import com.tunemind.app.domain.model.TrackIdentityResolver
import com.tunemind.app.domain.model.TrackMemoryEntry
import com.tunemind.app.domain.network.BackendEndpointManager
import com.tunemind.app.domain.recommendation.AiRecommendationEntity
import com.tunemind.app.domain.recommendation.RecommendationCategory
import com.tunemind.app.domain.recommendation.RecommendedTrack
import com.tunemind.app.ui.NowPlayingObservation
import com.tunemind.app.ui.VaultTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.roundToInt

data class ForYouLearningSummary(
    val intelligenceScorePercent: Int = 42,
    val evolutionStage: String = "STAGE_1_CALIBRATING",
    val evolutionLabel: String = "Stage 1 · Calibrating Your Taste",
    val learningIterations: Int = 0,
    val evaluatedOutcomes: Int = 0,
    val hitRatePercent: Int = 70,
    val risingGenres: List<String> = emptyList(),
    val coolingGenres: List<String> = emptyList(),
    val avgVolumePercent: Int = 62,
    val learnedTraits: List<String> = emptyList(),
    val narrative: String = "AI learns from every track you play, lock, preview, or pass and refreshes picks daily at 12:00 PM.",
    val isAiGenerated: Boolean = true,
    val modelUsed: String = "Claude Reasoning + Neural Bandit",
    val dropDateKey: String = "",
    val nextRefreshLocalLabel: String = "12:00 PM local time",
    val lastUpdatedMs: Long = System.currentTimeMillis()
) {
    fun toJsonString(): String {
        return JSONObject().apply {
            put("intelligenceScorePercent", intelligenceScorePercent)
            put("evolutionStage", evolutionStage)
            put("evolutionLabel", evolutionLabel)
            put("learningIterations", learningIterations)
            put("evaluatedOutcomes", evaluatedOutcomes)
            put("hitRatePercent", hitRatePercent)
            put("risingGenres", JSONArray(risingGenres))
            put("coolingGenres", JSONArray(coolingGenres))
            put("avgVolumePercent", avgVolumePercent)
            put("learnedTraits", JSONArray(learnedTraits))
            put("narrative", narrative)
            put("isAiGenerated", isAiGenerated)
            put("modelUsed", modelUsed)
            put("dropDateKey", dropDateKey)
            put("nextRefreshLocalLabel", nextRefreshLocalLabel)
            put("lastUpdatedMs", lastUpdatedMs)
        }.toString()
    }

    companion object {
        fun fromJsonString(raw: String): ForYouLearningSummary? {
            return try {
                val obj = JSONObject(raw)
                val risingArr = obj.optJSONArray("risingGenres")
                val coolingArr = obj.optJSONArray("coolingGenres")
                val traitsArr = obj.optJSONArray("learnedTraits")
                ForYouLearningSummary(
                    intelligenceScorePercent = obj.optInt("intelligenceScorePercent", 42).coerceIn(10, 99),
                    evolutionStage = obj.optString("evolutionStage", "STAGE_1_CALIBRATING"),
                    evolutionLabel = obj.optString("evolutionLabel", "Stage 1 · Calibrating Your Taste"),
                    learningIterations = obj.optInt("learningIterations", 0),
                    evaluatedOutcomes = obj.optInt("evaluatedOutcomes", 0),
                    hitRatePercent = obj.optInt("hitRatePercent", 70).coerceIn(0, 100),
                    risingGenres = List(risingArr?.length() ?: 0) { idx -> risingArr!!.optString(idx) }.filter { it.isNotBlank() },
                    coolingGenres = List(coolingArr?.length() ?: 0) { idx -> coolingArr!!.optString(idx) }.filter { it.isNotBlank() },
                    avgVolumePercent = obj.optInt("avgVolumePercent", 62).coerceIn(5, 100),
                    learnedTraits = List(traitsArr?.length() ?: 0) { idx -> traitsArr!!.optString(idx) }.filter { it.isNotBlank() },
                    narrative = obj.optString("narrative", ""),
                    isAiGenerated = obj.optBoolean("isAiGenerated", true),
                    modelUsed = obj.optString("modelUsed", "Claude Reasoning + Neural Bandit"),
                    dropDateKey = obj.optString("dropDateKey", ""),
                    nextRefreshLocalLabel = obj.optString("nextRefreshLocalLabel", "12:00 PM local time"),
                    lastUpdatedMs = obj.optLong("lastUpdatedMs", System.currentTimeMillis())
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Manages the exact 12:00 PM (Noon) daily drop cycle based on the user's device local timezone.
 * Before 12:00 PM local time, returns `<yesterday>@12PM`.
 * At or after 12:00 PM local time, rotates cleanly to `<today>@12PM`.
 */
object NoonDropSchedule {
    fun getNoonCycleKey(calendar: Calendar = Calendar.getInstance()): String {
        val copy = (calendar.clone() as Calendar)
        val hour = copy.get(Calendar.HOUR_OF_DAY)
        if (hour < 12) {
            copy.add(Calendar.DAY_OF_YEAR, -1)
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = copy.timeZone
        }
        return "${fmt.format(copy.time)}@12PM"
    }

    fun millisUntilNextNoon(nowMs: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val nextNoon = (cal.clone() as Calendar).apply {
            if (get(Calendar.HOUR_OF_DAY) >= 12) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return (nextNoon.timeInMillis - nowMs).coerceAtLeast(60_000L)
    }

    fun formatCountdownToNoon(nowMs: Long = System.currentTimeMillis()): String {
        val remainingMs = millisUntilNextNoon(nowMs)
        val totalMins = (remainingMs / 60_000L).coerceAtLeast(1L)
        val hours = totalMins / 60L
        val mins = totalMins % 60L
        return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
    }

    fun getLocalTimeZoneShortLabel(): String {
        return runCatching {
            TimeZone.getDefault().getDisplayName(false, TimeZone.SHORT, Locale.getDefault())
                ?.takeIf { it.isNotBlank() }
                ?: TimeZone.getDefault().id
        }.getOrDefault("Local")
    }
}

/**
 * Cognitive Reasoning Engine powered exclusively by the TuneMind Backend
 * (Anthropic Claude via paid server-side `ANTHROPIC_API_KEY`), with local
 * neuro-acoustic heuristic fallbacks when offline.
 */
object ClaudeReasoningEngine {

    private const val PREFS_REPORT = "tunemind_telemetry_reasoning_prefs"
    private const val KEY_LATEST_REPORT = "latest_reasoning_report_json"
    private const val PREFS_FOR_YOU = "tunemind_foryou_prefs"
    private const val KEY_LEARNING_SUMMARY = "latest_foryou_learning_summary_json"

    fun getApiKey(@Suppress("UNUSED_PARAMETER") context: Context): String = ""

    fun saveApiKey(@Suppress("UNUSED_PARAMETER") context: Context, @Suppress("UNUSED_PARAMETER") key: String) {
        // AI credentials live strictly on the backend (paid ANTHROPIC_API_KEY).
    }

    fun isConfigured(@Suppress("UNUSED_PARAMETER") context: Context): Boolean {
        return BuildConfig.TUNEMIND_BASE_URL.trim().isNotBlank() ||
            BackendEndpointManager.PRIMARY_PRODUCTION_URL.isNotBlank()
    }

    private fun resolveActiveBackendUrl(context: Context): String {
        val preferred = BackendEndpointManager.getActiveHttpBaseUrl(context).trim().trimEnd('/')
        if (preferred.isNotBlank()) return preferred
        return BuildConfig.TUNEMIND_BASE_URL.trim().trimEnd('/')
    }

    fun saveLearningSummary(context: Context, summary: ForYouLearningSummary) {
        try {
            context.applicationContext.getSharedPreferences(PREFS_FOR_YOU, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LEARNING_SUMMARY, summary.toJsonString())
                .apply()
        } catch (_: Exception) {}
    }

    fun getSavedLearningSummary(context: Context): ForYouLearningSummary {
        return try {
            val raw = context.applicationContext.getSharedPreferences(PREFS_FOR_YOU, Context.MODE_PRIVATE)
                .getString(KEY_LEARNING_SUMMARY, null)
            if (!raw.isNullOrBlank()) {
                ForYouLearningSummary.fromJsonString(raw) ?: ForYouLearningSummary(dropDateKey = NoonDropSchedule.getNoonCycleKey())
            } else {
                ForYouLearningSummary(dropDateKey = NoonDropSchedule.getNoonCycleKey())
            }
        } catch (_: Exception) {
            ForYouLearningSummary(dropDateKey = NoonDropSchedule.getNoonCycleKey())
        }
    }

    /**
     * Checks whether the paid Claude reasoning backend is online and configured.
     */
    suspend fun testConnection(
        context: Context,
        @Suppress("UNUSED_PARAMETER") candidateKey: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = resolveActiveBackendUrl(context)
            if (baseUrl.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Backend URL is not configured."))
            }
            val url = URL("$baseUrl/api/v1/reasoning/status")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 12_000

            val code = conn.responseCode
            if (code in 200..299) {
                val text = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                val root = JSONObject(text)
                val claudeReady = root.optBoolean("isClaudeConfigured", false)
                if (claudeReady) {
                    Result.success("TuneMind Claude Reasoning Engine Online.")
                } else {
                    Result.failure(IllegalStateException("Backend reachable, but paid ANTHROPIC_API_KEY is missing on server."))
                }
            } else {
                Result.failure(IllegalStateException("Backend status check failed (HTTP $code)."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Proxies raw telemetry payload to the TuneMind backend powered by paid Claude (`ANTHROPIC_API_KEY`).
     */
    suspend fun callBackendAnalyze(payload: JSONObject): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = BuildConfig.TUNEMIND_BASE_URL.trim().trimEnd('/')
                .ifBlank { BackendEndpointManager.PRIMARY_PRODUCTION_URL }
            val url = URL("$baseUrl/api/v1/reasoning/analyze")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 12_000
            conn.readTimeout = 22_000
            conn.doOutput = true

            OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val code = conn.responseCode
            if (code in 200..299) {
                val text = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                Result.success(JSONObject(text))
            } else {
                val err = conn.errorStream?.let { BufferedReader(InputStreamReader(it, "UTF-8")).use { r -> r.readText() } } ?: "HTTP $code"
                Result.failure(IllegalStateException("Backend Claude reasoning error ($code): $err"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Sends telemetry context to POST /api/v1/reasoning/evaluate-satiety (Claude), parses the
     * ReasoningResponse, and dispatches OS media commands and voice agent TTS via TuneMindExecutionEngine.
     */
    suspend fun evaluateSatietyAndExecute(
        context: Context,
        telemetryContext: JSONObject
    ): Result<ReasoningResponse> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = resolveActiveBackendUrl(context)
            if (baseUrl.isBlank()) {
                return@withContext Result.failure(IllegalStateException("No backend URL configured"))
            }
            val url = URL("$baseUrl/api/v1/reasoning/evaluate-satiety")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 12_000
            conn.readTimeout = 22_000
            conn.doOutput = true

            OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                writer.write(telemetryContext.toString())
                writer.flush()
            }

            val code = conn.responseCode
            if (code in 200..299) {
                val text = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                val rootJson = JSONObject(text)

                val cmdJson = rootJson.optJSONObject("os_execution_command")
                val rawCmdUri = cmdJson?.optString("spotify_uri", "")?.takeIf { it.isNotBlank() }
                val rawCmdTitle = cmdJson?.optString("track_title", "")?.takeIf { it.isNotBlank() }
                val rawCmdArtist = cmdJson?.optString("track_artist", "")?.takeIf { it.isNotBlank() }
                val rawCmdTrackId = cmdJson?.optString("track_id", "")?.takeIf { it.isNotBlank() }
                    ?: cmdJson?.optString("trackId", "")?.takeIf { it.isNotBlank() }
                val cmdIdentity = if (!rawCmdTitle.isNullOrBlank() || !rawCmdTrackId.isNullOrBlank() || !rawCmdUri.isNullOrBlank()) {
                    TrackIdentityResolver.resolveCanonicalIdentity(
                        trackId = rawCmdTrackId,
                        spotifyUri = rawCmdUri,
                        title = rawCmdTitle,
                        artist = rawCmdArtist
                    )
                } else null
                val osCommand = OsExecutionCommand(
                    intent = cmdJson?.optString("intent", "NONE") ?: "NONE",
                    target_player = cmdJson?.optString("target_player", "unknown_player") ?: "unknown_player",
                    search_query = cmdJson?.optString("search_query", "") ?: "",
                    suggested_bpm_target = if (cmdJson?.has("suggested_bpm_target") == true) cmdJson.optInt("suggested_bpm_target") else null,
                    track_title = rawCmdTitle,
                    track_artist = rawCmdArtist,
                    track_id = cmdIdentity?.trackId,
                    spotify_uri = cmdIdentity?.spotifyUri
                )

                val tagsArray = rootJson.optJSONArray("database_tags")
                val tagsList = mutableListOf<String>()
                if (tagsArray != null) {
                    for (i in 0 until tagsArray.length()) {
                        tagsList.add(tagsArray.getString(i))
                    }
                }

                val response = ReasoningResponse(
                    calculated_satiety_percentage = rootJson.optInt("calculated_satiety_percentage", 30),
                    flow_state_analysis = rootJson.optString("flow_state_analysis", "Cognitive flow optimal."),
                    voice_agent_tts = rootJson.optString("voice_agent_tts", ""),
                    os_execution_command = osCommand,
                    database_tags = tagsList
                )

                val executionEngine = TuneMindExecutionEngine(context)
                executionEngine.executeBackendCommand(response)

                Result.success(response)
            } else {
                val err = conn.errorStream?.let { BufferedReader(InputStreamReader(it, "UTF-8")).use { r -> r.readText() } } ?: "HTTP $code"
                Result.failure(IllegalStateException("Backend evaluate-satiety error ($code): $err"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Real-time cognitive analysis of user's active listening session via backend Claude.
     */
    suspend fun analyzeActiveSession(
        @Suppress("UNUSED_PARAMETER") context: Context,
        recentTracks: List<TrackMemoryEntry>,
        nowPlaying: NowPlayingObservation?,
        vaultTracks: List<VaultTrack>,
        timeSlot: String
    ): Result<String> {
        val payload = JSONObject().apply {
            put("timeSlot", timeSlot)
            put("eventCount", recentTracks.sumOf { it.distinctAppearanceCount })
            put("topSourceApp", nowPlaying?.sourcePackage ?: "Music App")
            if (nowPlaying != null && !nowPlaying.title.isNullOrBlank()) {
                put("nowPlaying", JSONObject().apply {
                    put("title", nowPlaying.title)
                    put("artist", nowPlaying.artist ?: "")
                    put("isPlaying", nowPlaying.isPlaying)
                    put("sourcePackage", nowPlaying.sourcePackage ?: "")
                })
            }
            val tracksArray = JSONArray()
            recentTracks.take(6).forEach { t ->
                val identity = TrackIdentityResolver.resolveCanonicalIdentity(
                    trackId = t.trackId.ifBlank { null },
                    spotifyUri = t.providerTrackId,
                    title = t.title,
                    artist = t.artist
                )
                tracksArray.put(JSONObject().apply {
                    put("trackId", identity.trackId)
                    identity.spotifyUri?.let { put("spotifyUri", it) }
                    put("title", t.title)
                    put("artist", t.artist)
                    put("plays", t.distinctAppearanceCount.coerceAtLeast(0))
                    put("satiety", minOf(95, (t.distinctAppearanceCount.coerceAtLeast(0) * 22)))
                })
            }
            put("recentTracks", tracksArray)
            val vaultArray = JSONArray()
            vaultTracks.forEach { v ->
                vaultArray.put(JSONObject().apply {
                    put("title", v.title)
                    put("artist", v.artist)
                    put("cooldownDaysRemaining", v.cooldownDaysRemaining.toInt())
                })
            }
            put("vaultTracks", vaultArray)
        }

        val backendResult = callBackendAnalyze(payload)
        backendResult.getOrNull()?.let { res ->
            val narrative = res.optString("flowNarrative", res.optString("flowStateDescription", ""))
            if (narrative.isNotBlank()) {
                return Result.success(narrative)
            }
        }

        return Result.failure(IllegalStateException("Claude backend reasoning unavailable right now."))
    }

    /**
     * Synthesizes AI-reasoned music recommendations grounded in real user data via backend Claude.
     */
    suspend fun generateReasonedRecommendations(
        context: Context,
        recentTracks: List<TrackMemoryEntry>,
        vaultTracks: List<VaultTrack>,
        preferredProviderId: String
    ): List<RecommendedTrack>? {
        val crate = generateChronoCrateRecommendations(
            context = context,
            recentTracks = recentTracks,
            vaultTracks = vaultTracks,
            timeSlotKey = "session",
            timeSlotLabel = "Active Session",
            satietyPercent = 35,
            preferredProviderId = preferredProviderId
        )
        return crate.take(4).map { rec ->
            val cat = when (rec.category.uppercase()) {
                "PALATE_CLEANSER" -> RecommendationCategory.PALATE_CLEANSER
                "CHRONO_RESTORE" -> RecommendationCategory.COOLDOWN_RESTORED
                "DOPAMINE_FLOW" -> RecommendationCategory.FAMILIAR_RECEPTIVE
                else -> RecommendationCategory.EXPLORATION_DISCOVERY
            }
            RecommendedTrack(
                id = rec.id,
                title = rec.title,
                artist = rec.artist,
                album = rec.album,
                reason = rec.reason,
                confidence = rec.confidence,
                category = cat,
                suggestedProviderId = preferredProviderId,
                observationCountBasis = 0,
                trackId = rec.resolvedCanonicalTrackId(),
                spotifyUri = rec.spotifyUri
            )
        }.takeIf { it.isNotEmpty() }
    }

    /**
     * Generates a neuro-acoustic Song Profile Dossier via backend Claude.
     */
    suspend fun generateSongProfileDossier(
        @Suppress("UNUSED_PARAMETER") context: Context,
        title: String,
        artist: String,
        playCount: Int,
        satietyPercent: Int
    ): Result<String> = withContext(Dispatchers.IO) {
        val satiety = satietyPercent.coerceIn(0, 100)
        val safePlayCount = playCount.coerceAtLeast(0)
        val canonicalId = TrackIdentityResolver.buildDeterministicTrackId(title, artist)
        val payload = JSONObject().apply {
            put("timeSlot", "Track Profile")
            put("eventCount", safePlayCount)
            put("topSourceApp", "Music App")
            put("nowPlaying", JSONObject().apply {
                put("trackId", canonicalId)
                put("title", title)
                put("artist", artist)
                put("isPlaying", safePlayCount > 0)
            })
            put(
                "recentTracks",
                JSONArray().put(
                    JSONObject().apply {
                        put("trackId", canonicalId)
                        put("title", title)
                        put("artist", artist)
                        put("plays", safePlayCount)
                        put("satiety", satiety)
                    }
                )
            )
        }

        val backendResult = callBackendAnalyze(payload)
        backendResult.getOrNull()?.let { res ->
            val narrative = res.optString("flowNarrative", "").trim()
            val statusLabel = res.optString("statusLabel", "Optimal Cognitive Flow").trim()
            val recs = res.optJSONArray("recommendations")
            val firstAction = if (recs != null && recs.length() > 0) {
                recs.optJSONObject(0)?.optString("action", "")?.trim().orEmpty()
            } else ""
            val firstRationale = if (recs != null && recs.length() > 0) {
                recs.optJSONObject(0)?.optString("rationale", "")?.trim().orEmpty()
            } else ""

            if (narrative.isNotBlank()) {
                val dossier = buildString {
                    appendLine("1. ACOUSTIC DNA: '$title' by $artist is operating in $statusLabel ($satiety% satiety across $playCount observed play(s)).")
                    appendLine("2. RECEPTOR DYNAMICS: $narrative")
                    val guidance = if (firstAction.isNotBlank()) {
                        "$firstAction${if (firstRationale.isNotBlank()) " — $firstRationale" else ""}"
                    } else if (satiety >= 70) {
                        "High repetition detected — rotate to a fresh palette track soon to preserve dopamine sensitivity."
                    } else {
                        "Receptors remain fresh; ideal for active rotation or deep-focus playlists."
                    }
                    append("3. AGENT GUIDANCE: $guidance")
                }
                return@withContext Result.success(dossier)
            }
        }

        val fallbackDossier = buildString {
            appendLine("1. ACOUSTIC DNA: '$title' by $artist has $playCount verified play(s) with an estimated $satiety% repetition load.")
            appendLine(
                if (satiety >= 70) {
                    "2. RECEPTOR DYNAMICS: Elevated repetition indicates rising habituation in your auditory reward loop."
                } else {
                    "2. RECEPTOR DYNAMICS: Listening cadence stays balanced with low risk of burnout."
                }
            )
            append(
                if (satiety >= 70) {
                    "3. AGENT GUIDANCE: Rest this track briefly in Dopamine Vault or blend in an unplayed discovery song."
                } else {
                    "3. AGENT GUIDANCE: Great fit for focused or casual sessions while receptors stay sharp."
                }
            )
        }
        Result.success(fallbackDossier)
    }

    private fun parseLearningSummaryJson(
        root: JSONObject,
        dropDateKey: String
    ): ForYouLearningSummary? {
        val ls = root.optJSONObject("learningSummary") ?: return null
        val risingArr = ls.optJSONArray("risingGenres")
        val coolingArr = ls.optJSONArray("coolingGenres")
        val traitsArr = ls.optJSONArray("learnedTraits")
        return ForYouLearningSummary(
            intelligenceScorePercent = ls.optInt("intelligenceScorePercent", 45).coerceIn(10, 99),
            evolutionStage = ls.optString("evolutionStage", "STAGE_1_CALIBRATING"),
            evolutionLabel = ls.optString("evolutionLabel", "Stage 1 · Calibrating Your Taste"),
            learningIterations = ls.optInt("learningIterations", 0),
            evaluatedOutcomes = ls.optInt("evaluatedOutcomes", 0),
            hitRatePercent = ls.optInt("hitRatePercent", 70).coerceIn(0, 100),
            risingGenres = List(risingArr?.length() ?: 0) { idx -> risingArr!!.optString(idx) }.filter { it.isNotBlank() },
            coolingGenres = List(coolingArr?.length() ?: 0) { idx -> coolingArr!!.optString(idx) }.filter { it.isNotBlank() },
            avgVolumePercent = ls.optInt("avgVolumePercent", 62).coerceIn(5, 100),
            learnedTraits = List(traitsArr?.length() ?: 0) { idx -> traitsArr!!.optString(idx) }.filter { it.isNotBlank() },
            narrative = ls.optString("narrative", ""),
            isAiGenerated = root.optBoolean("isAiGenerated", true),
            modelUsed = root.optString("modelUsed", "Claude Reasoning + Neural Bandit"),
            dropDateKey = root.optString("dropDate", dropDateKey).ifBlank { dropDateKey },
            nextRefreshLocalLabel = root.optString("nextRefreshLocalLabel", "12:00 PM local time"),
            lastUpdatedMs = System.currentTimeMillis()
        )
    }

    private const val PREFS_PERM_REC_HISTORY = "tunemind_for_you_perm_history_v1"
    private const val KEY_PERM_REC_KEYS_JSON = "perm_rec_keys_json"

    fun normalizeTitleExclusionKey(rawTitle: String): String {
        return rawTitle.trim().lowercase()
            .replace(Regex("\\s*\\([^)]*\\)"), "")
            .replace(Regex("\\s*\\[[^\\]]*\\]"), "")
            .replace(Regex("\\s+-\\s+(remaster(ed)?|radio edit|single version|live|deluxe|acoustic|explicit|clean).*$"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    fun getPermanentExcludedKeys(context: Context): List<String> {
        return try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_PERM_REC_HISTORY, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_PERM_REC_KEYS_JSON, null) ?: return emptyList()
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i).trim()
                    if (s.isNotBlank()) add(s)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun recordPermanentExcludedRecommendations(context: Context, items: List<AiRecommendationEntity>) {
        if (items.isEmpty()) return
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_PERM_REC_HISTORY, Context.MODE_PRIVATE)
            val existing = LinkedHashSet<String>(getPermanentExcludedKeys(context))
            items.forEach { rec ->
                val normKey = TrackIdentityResolver.buildNormalizedMatchKey(rec.title, rec.artist)
                if (normKey.isNotBlank()) existing.add(normKey)
                val cleanT = normalizeTitleExclusionKey(rec.title)
                if (cleanT.isNotBlank()) existing.add(cleanT)
                TrackIdentityResolver.normalizeSpotifyUri(rec.spotifyUri)?.let { existing.add(it.lowercase()) }
            }
            val bounded = if (existing.size > 3000) existing.toList().takeLast(3000) else existing.toList()
            prefs.edit().putString(KEY_PERM_REC_KEYS_JSON, JSONArray(bounded).toString()).apply()
        } catch (_: Exception) {}
    }

    /**
     * Generates the 12:00 PM Self-Learning AI Daily Drop (`POST /api/v1/discovery/daily-drop`):
     * - Auto-rotates strictly on the user's local 12:00 PM cycle key.
     * - Delivers 2 unheard songs per active genre + 1 Wildcard Deck (3 songs from uncharted genres).
     * - Feeds back previous drop outcomes, volume ratio, & cross-app listening so the bandit + Claude evolve daily.
     */
    suspend fun generateChronoCrateRecommendations(
        context: Context,
        recentTracks: List<TrackMemoryEntry>,
        vaultTracks: List<VaultTrack>,
        timeSlotKey: String,
        timeSlotLabel: String,
        satietyPercent: Int,
        preferredProviderId: String,
        userTasteAnchors: List<String> = emptyList(),
        nowPlayingArtist: String? = null,
        excludeTitles: List<String> = emptyList(),
        excludeKeys: List<String> = emptyList(),
        excludedSpotifyUris: List<String> = emptyList(),
        excludedTrackIds: List<String> = emptyList(),
        spotifyAccessToken: String? = null,
        playedSignals: List<String> = emptyList(),
        dismissedSignals: List<String> = emptyList(),
        userId: String = "",
        userEmail: String = "",
        avgVolumeRatio: Float? = null,
        yesterdayOutcomes: List<AiRecommendationEntity> = emptyList(),
        forceRefresh: Boolean = false,
        genreDistribution: Map<String, Int> = emptyMap(),
        artistGenres: Map<String, String> = emptyMap(),
        cleanPlayEvents: List<com.tunemind.app.domain.memory.UnifiedPlayEvent> = emptyList(),
        onLearningSummaryUpdated: ((ForYouLearningSummary) -> Unit)? = null,
        onBillingRestriction: ((code: String, message: String) -> Unit)? = null
    ): List<AiRecommendationEntity> = withContext(Dispatchers.IO) {
        val observedArtists = (recentTracks.mapNotNull { it.artist } + listOfNotNull(nowPlayingArtist))
            .filter { it.isNotBlank() }
            .distinct()
        val allArtists = (userTasteAnchors + observedArtists).distinct().filter { it.isNotBlank() }
        val artistsSummary = allArtists.take(8).joinToString(", ").ifEmpty { "Bongo Flava, Gikuyu / Mugithi, Gospel, Afrobeats" }
        val calendar = Calendar.getInstance()
        val noonCycleKey = NoonDropSchedule.getNoonCycleKey(calendar)
        val timezoneId = calendar.timeZone.id ?: TimeZone.getDefault().id
        val localHour = calendar.get(Calendar.HOUR_OF_DAY)
        val permanentHistoryKeys = getPermanentExcludedKeys(context)
        val mergedExcludeKeys = (excludeKeys + permanentHistoryKeys).distinct()
        val mergedExcludeTitles = excludeTitles.distinct()

        val payload = JSONObject().apply {
            if (userId.isNotBlank()) put("userId", userId)
            if (userEmail.isNotBlank()) put("userEmail", userEmail)
            put("dropDate", noonCycleKey)
            put("forceRefresh", forceRefresh)
            put("timezone", timezoneId)
            put("localHour", localHour)
            put("timeSlot", timeSlotLabel)
            put("satietyLevel", satietyPercent)
            put("preferredProvider", preferredProviderId)
            if (avgVolumeRatio != null && avgVolumeRatio > 0f) {
                put("avgVolumeRatio", avgVolumeRatio.toDouble().coerceIn(0.05, 1.0))
            }

            if (genreDistribution.isNotEmpty()) {
                val distObj = JSONObject()
                genreDistribution.forEach { (g, cnt) ->
                    if (g.isNotBlank() && cnt > 0) distObj.put(g, cnt)
                }
                put("genreDistribution", distObj)
            }
            if (artistGenres.isNotEmpty()) {
                val agObj = JSONObject()
                artistGenres.entries.take(80).forEach { (art, g) ->
                    if (art.isNotBlank() && g.isNotBlank()) agObj.put(art, g)
                }
                put("artistGenres", agObj)
            }

            val artistsArray = JSONArray()
            allArtists.take(12).forEach { artistsArray.put(it) }
            put("topArtists", artistsArray)

            val anchorsArray = JSONArray()
            userTasteAnchors.forEach { anchorsArray.put(it) }
            put("userTasteAnchors", anchorsArray)

            val excludeArray = JSONArray()
            mergedExcludeTitles.take(1500).forEach { excludeArray.put(it) }
            put("excludeTitles", excludeArray)

            if (mergedExcludeKeys.isNotEmpty()) {
                val exKeyArray = JSONArray()
                mergedExcludeKeys.take(2000).forEach { exKeyArray.put(it) }
                put("excludeKeys", exKeyArray)
            }
            if (excludedSpotifyUris.isNotEmpty()) {
                val exUriArray = JSONArray()
                excludedSpotifyUris.distinct().take(1000).forEach { exUriArray.put(it) }
                put("excludedSpotifyUris", exUriArray)
            }
            if (excludedTrackIds.isNotEmpty()) {
                val exIdArray = JSONArray()
                excludedTrackIds.distinct().take(1000).forEach { exIdArray.put(it) }
                put("excludedTrackIds", exIdArray)
            }
            if (!spotifyAccessToken.isNullOrBlank()) {
                put("spotifyAccessToken", spotifyAccessToken)
            }

            val playedArray = JSONArray()
            playedSignals.take(15).forEach { playedArray.put(it) }
            put("playedFeedback", playedArray)

            val dismissedArray = JSONArray()
            dismissedSignals.take(15).forEach { dismissedArray.put(it) }
            put("dismissedFeedback", dismissedArray)

            val playEventByKey = cleanPlayEvents.associateBy {
                "${it.title.trim().lowercase()}|${it.artist.trim().lowercase()}"
            }
            val tracksArray = JSONArray()
            recentTracks.take(250).forEach { t ->
                val identity = TrackIdentityResolver.resolveCanonicalIdentity(
                    trackId = t.trackId.ifBlank { null },
                    spotifyUri = t.providerTrackId,
                    title = t.title,
                    artist = t.artist
                )
                val safeCount = t.distinctAppearanceCount.coerceAtLeast(0)
                val evKey = "${t.title.orEmpty().trim().lowercase()}|${t.artist.orEmpty().trim().lowercase()}"
                val matchedEv = playEventByKey[evKey]
                val primaryArtistKey = t.artist.orEmpty().trim().lowercase().split(Regex("[,&]|feat\\.?|ft\\.?", RegexOption.IGNORE_CASE)).firstOrNull()?.trim().orEmpty()
                val explicitGenre = artistGenres[primaryArtistKey] ?: artistGenres[t.artist.orEmpty().trim().lowercase()]
                val evSlot = if (matchedEv != null && matchedEv.playedAtMs > 0L) {
                    val cal = Calendar.getInstance().apply { timeInMillis = matchedEv.playedAtMs }
                    when (cal.get(Calendar.HOUR_OF_DAY)) {
                        in 6..11 -> "Morning"
                        in 12..16 -> "Midday"
                        in 17..21 -> "Evening"
                        else -> "Late Night"
                    }
                } else timeSlotLabel

                tracksArray.put(JSONObject().apply {
                    put("trackId", identity.trackId)
                    identity.spotifyUri?.let { put("spotifyUri", it) }
                    put("title", t.title)
                    put("artist", t.artist)
                    if (!explicitGenre.isNullOrBlank()) put("genre", explicitGenre)
                    put("playCount", safeCount)
                    put("plays", safeCount)
                    put("satiety", minOf(95, safeCount * 22))
                    put("sourceApp", t.sourcePackage ?: "Music App")
                    put("timeSlot", evSlot)
                    if (matchedEv != null) {
                        put("listenedMs", matchedEv.listenedMs)
                        put("durationMs", matchedEv.durationMs)
                        put("playedAtMs", matchedEv.playedAtMs)
                        if (matchedEv.durationMs > 0L) {
                            put("completionRatio", (matchedEv.listenedMs.toDouble() / matchedEv.durationMs.toDouble()).coerceIn(0.05, 1.0))
                        }
                    }
                    if (avgVolumeRatio != null && avgVolumeRatio > 0f) {
                        put("volumeRatio", avgVolumeRatio.toDouble().coerceIn(0.05, 1.0))
                    }
                })
            }
            put("recentTracks", tracksArray)

            val vaultArray = JSONArray()
            vaultTracks.forEach { v ->
                vaultArray.put(JSONObject().apply {
                    put("title", v.title)
                    put("artist", v.artist)
                    put("cooldownDaysRemaining", v.cooldownDaysRemaining.toInt())
                })
            }
            put("vaultTracks", vaultArray)

            val outcomesArray = JSONArray()
            yesterdayOutcomes.take(24).forEach { prev ->
                val inferredOutcome = when {
                    prev.isDismissed -> "DISMISSED"
                    prev.completedListenCount > 0 -> "COMPLETED"
                    prev.isSaved -> "SAVED"
                    prev.wasPlayed -> "PLAYED"
                    else -> "IGNORED"
                }
                outcomesArray.put(JSONObject().apply {
                    put("title", prev.title)
                    put("artist", prev.artist)
                    put("genre", prev.genreTag)
                    put("subTier", prev.subTier)
                    put("outcome", inferredOutcome)
                    put("listenedMs", prev.listenedDurationMs)
                    put("durationMs", prev.trackDurationMs)
                    if (prev.avgVolumeUsedRatio > 0f) {
                        put("volumeRatio", prev.avgVolumeUsedRatio.toDouble())
                    }
                    put("timeSlot", prev.timeSlotLabel)
                    put("dropDate", prev.dropDateKey)
                })
            }
            put("yesterdayOutcomes", outcomesArray)
        }

        // Route via backend SelfLearningDiscoveryEngine (/api/v1/discovery/daily-drop)
        val backendUrl = resolveActiveBackendUrl(context)
        if (backendUrl.isNotBlank()) {
            try {
                val url = URL("$backendUrl/api/v1/discovery/daily-drop")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Accept", "application/json")
                val token = runCatching { TuneMindAuthSessionManager.getInstance(context).getAccessToken() }.getOrDefault("")
                if (token.isNotBlank()) {
                    conn.setRequestProperty("Authorization", "Bearer $token")
                }
                conn.connectTimeout = 14_000
                conn.readTimeout = 28_000
                conn.doOutput = true

                OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }

                val statusCode = conn.responseCode
                if (statusCode == 402 || statusCode == 429) {
                    val errText = runCatching {
                        val stream = conn.errorStream ?: conn.inputStream
                        if (stream != null) BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() } else ""
                    }.getOrDefault("")
                    val errJson = runCatching { JSONObject(errText) }.getOrNull()
                    errJson?.optJSONObject("entitlement")?.let { entJson ->
                        com.tunemind.app.billing.TuneMindBillingManager.getInstance(context).applyEntitlementJson(entJson)
                    }
                    val code = errJson?.optString("code", if (statusCode == 402) "PRO_TRIAL_REQUIRED" else "QUOTA_REACHED")
                        ?: if (statusCode == 402) "PRO_TRIAL_REQUIRED" else "QUOTA_REACHED"
                    val msg = errJson?.optString(
                        "message",
                        "Start your 7-day free trial to refresh For You drops and unlock all 15 daily recommendations."
                    ) ?: "Start your 7-day free trial to unlock unlimited refreshes."
                    onBillingRestriction?.invoke(code, msg)
                    return@withContext emptyList()
                }

                if (statusCode in 200..299) {
                    val text = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                    val root = JSONObject(text)
                    root.optJSONObject("entitlement")?.let { entJson ->
                        com.tunemind.app.billing.TuneMindBillingManager.getInstance(context).applyEntitlementJson(entJson)
                    }
                    val parsedSummary = parseLearningSummaryJson(root, noonCycleKey)
                    if (parsedSummary != null) {
                        saveLearningSummary(context, parsedSummary)
                        onLearningSummaryUpdated?.invoke(parsedSummary)
                    }

                    val localExTitles = mergedExcludeTitles.flatMap {
                        listOf(it.lowercase().trim(), normalizeTitleExclusionKey(it))
                    }.filter { it.isNotBlank() }.toHashSet()
                    val localExKeys = mergedExcludeKeys.map { it.lowercase().trim() }.toHashSet()
                    val localExUris = excludedSpotifyUris.mapNotNull { TrackIdentityResolver.normalizeSpotifyUri(it)?.lowercase() }.toHashSet()
                    val localExIds = excludedTrackIds.filter { it.isNotBlank() && !it.startsWith("disc:") && !it.startsWith("trk:") }.toHashSet()

                    val recsArray = root.optJSONArray("recommendations")
                    if (recsArray != null && recsArray.length() > 0) {
                        val list = mutableListOf<AiRecommendationEntity>()
                        val seenKeys = mutableSetOf<String>()
                        val nowMs = System.currentTimeMillis()
                        for (i in 0 until recsArray.length()) {
                            val item = recsArray.getJSONObject(i)
                            val title = item.optString("title", "").trim()
                            val artist = item.optString("artist", "").trim()
                            val normMatchKey = TrackIdentityResolver.buildNormalizedMatchKey(title, artist)
                            val key = normMatchKey.ifBlank { "${title.lowercase()}|${artist.lowercase()}" }
                            if (title.isBlank() || artist.isBlank() || seenKeys.contains(key)) continue

                            val recTrackIdRaw = item.optString("trackId", "").ifBlank { item.optString("track_id", "") }
                            val recSpotifyUriRaw = item.optString("spotifyUri", "").ifBlank { item.optString("spotify_uri", "") }
                            val recIdentity = TrackIdentityResolver.resolveCanonicalIdentity(
                                trackId = recTrackIdRaw.ifBlank { null },
                                spotifyUri = recSpotifyUriRaw.ifBlank { null },
                                title = title,
                                artist = artist
                            )
                            val normCleanTitle = normalizeTitleExclusionKey(title)
                            val isExcludedLocal =
                                localExKeys.contains(key) ||
                                    localExTitles.contains(title.lowercase()) ||
                                    (normCleanTitle.isNotBlank() && localExTitles.contains(normCleanTitle)) ||
                                    (recIdentity.spotifyUri != null && localExUris.contains(recIdentity.spotifyUri.lowercase())) ||
                                    localExIds.contains(recIdentity.trackId)
                            if (isExcludedLocal) continue

                            seenKeys.add(key)

                            val genreTag = item.optString("genre", "Discovery").trim().ifBlank { "Discovery" }
                            val subTier = item.optString("subTier", "GENRE_PICK").trim().ifBlank { "GENRE_PICK" }
                            val isWildcard = item.optBoolean("isWildcard", subTier == "GLOBAL_WILDCARD")
                            val confidence = item.optDouble("confidence", 0.91).toFloat().coerceIn(0.65f, 0.99f)
                            val mlScoreRaw = item.optDouble("mlScore", confidence.toDouble())
                            val matchPercent = (mlScoreRaw * 100.0).roundToInt().coerceIn(72, 99)
                            val previewUrl = item.optString("previewUrl", "").trim().takeIf { it.startsWith("http") }

                            list.add(
                                AiRecommendationEntity(
                                    id = AiRecommendationEntity.createDeterministicId(title, artist),
                                    title = title,
                                    artist = artist,
                                    album = item.optString("album", "").trim().takeIf { it.isNotBlank() },
                                    category = item.optString("category", if (isWildcard) "NOVEL_EXPLORATION" else "DOPAMINE_FLOW"),
                                    subTier = if (isWildcard) "GLOBAL_WILDCARD" else subTier,
                                    reason = item.optString("reason", "Predicted for your $timeSlotLabel mood."),
                                    confidence = confidence,
                                    timeSlotKey = timeSlotKey,
                                    timeSlotLabel = timeSlotLabel,
                                    recommendedAtMs = nowMs - i,
                                    suggestedProviderId = preferredProviderId,
                                    telemetryBasisSummary = "Demonstrated Rotation: $artistsSummary",
                                    genreTag = genreTag,
                                    dropDateKey = noonCycleKey,
                                    matchScorePercent = matchPercent,
                                    isWildcard = isWildcard,
                                    spotifyUri = TrackIdentityResolver.normalizeSpotifyUri(recIdentity.spotifyUri),
                                    previewUrl = previewUrl,
                                    trackDurationMs = item.optLong("durationMs", 0L),
                                    trackId = recIdentity.trackId
                                )
                            )
                        }
                        if (list.isNotEmpty()) {
                            recordPermanentExcludedRecommendations(context, list)
                            return@withContext list
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Offline genre-aware baseline fallback (preserves 2-per-genre + 1 Wildcard Deck of 3 songs)
        val lower = artistsSummary.lowercase()
        val isGikuyu = lower.contains("gikuyu") || lower.contains("kikuyu") || lower.contains("mugithi") || lower.contains("samidoh") || lower.contains("salim") || lower.contains("demathew") || lower.contains("gatutura")
        val isGospel = lower.contains("gospel") || lower.contains("worship") || lower.contains("mercy masika") || lower.contains("kigame") || lower.contains("sinach") || lower.contains("guardian angel")
        val isBongo = lower.contains("bongo") || lower.contains("mbosso") || lower.contains("diamond") || lower.contains("platnumz") || lower.contains("harmonize") || lower.contains("rayvanny") || lower.contains("zuchu") || lower.contains("marioo") || lower.contains("kiba") || lower.contains("kusah") || lower.contains("jux") || lower.contains("otile") || lower.contains("nandy") || lower.contains("swahili") || lower.contains("east africa")
        val isAfro = lower.contains("afro") || lower.contains("amapiano") || lower.contains("burna") || lower.contains("wizkid") || lower.contains("rema") || lower.contains("asake") || lower.contains("tems")
        val isHipHop = lower.contains("hip") || lower.contains("rap") || lower.contains("drake") || lower.contains("kendrick") || lower.contains("cole") || lower.contains("travis")

        data class FallbackTrack(
            val title: String,
            val artist: String,
            val genre: String,
            val reason: String,
            val category: String,
            val subTier: String,
            val isWildcard: Boolean = false,
            val matchPercent: Int = 92
        )

        val genrePicks = when {
            isGikuyu -> listOf(
                FallbackTrack("Murata Wa Thiururuka", "Samidoh", "Gikuyu / Mugithi", "Crisp acoustic Mugithi guitar & storytelling fit your melodic groove.", "DOPAMINE_FLOW", "GENRE_PICK", false, 95),
                FallbackTrack("Nindacokia Ngatho", "John De'Mathew", "Gikuyu / Mugithi", "Timeless Kikuyu brass and vocal phrasing preserve high freshness.", "DOPAMINE_FLOW", "GENRE_PICK", false, 93),
                FallbackTrack("Huwezi Jua", "Mbosso", "Bongo Flava", "Warm Swahili guitar harmonies complement Mugithi warmth cleanly.", "DOPAMINE_FLOW", "GENRE_PICK", false, 91),
                FallbackTrack("Mi Amor", "Marioo & Jovial", "Bongo Flava", "Mid-tempo coastal cadence balances acoustic fatigue smoothly.", "PALATE_CLEANSER", "GENRE_PICK", false, 89)
            )
            isGospel -> listOf(
                FallbackTrack("Mwema", "Mercy Masika", "Kenyan Gospel", "Uplifting East African praise harmonies sustain receptive focus.", "DOPAMINE_FLOW", "GENRE_PICK", false, 95),
                FallbackTrack("Nadeka", "Guardian Angel", "Kenyan Gospel", "Warm acoustic worship groove fits calm reflective flow.", "DOPAMINE_FLOW", "GENRE_PICK", false, 93),
                FallbackTrack("Lonely At The Top", "Asake", "Afrobeats", "Spacious log drums extend uplifting groove without overload.", "DOPAMINE_FLOW", "GENRE_PICK", false, 90),
                FallbackTrack("Free Mind", "Tems", "Afrobeats", "Airy vocal space gives auditory receptors restorative calm.", "PALATE_CLEANSER", "GENRE_PICK", false, 88)
            )
            isBongo -> listOf(
                FallbackTrack("Huwezi Jua", "Mbosso", "Bongo Flava", "Soulful Bongo Flava chords match your melodic cadence cleanly.", "DOPAMINE_FLOW", "GENRE_PICK", false, 95),
                FallbackTrack("Mi Amor", "Marioo & Jovial", "Bongo Flava", "Warm Swahili vocal harmonies lift receptor freshness immediately.", "DOPAMINE_FLOW", "GENRE_PICK", false, 93),
                FallbackTrack("Lonely At The Top", "Asake", "Afrobeats", "Acoustic log drums bridge your East-West groove seamlessly.", "DOPAMINE_FLOW", "GENRE_PICK", false, 91),
                FallbackTrack("Free Mind", "Tems", "Afrobeats", "Spacious guitar phrasing rests overstimulated ears cleanly.", "PALATE_CLEANSER", "GENRE_PICK", false, 89)
            )
            isAfro -> listOf(
                FallbackTrack("Soweto", "Victony & Tempoe", "Afrobeats", "Crisp flute motifs and syncopated bounce keep focus sharp.", "DOPAMINE_FLOW", "GENRE_PICK", false, 95),
                FallbackTrack("Ku Lo Sa", "Oxlade", "Afrobeats", "Silk falsetto and warm percussion extend groove longevity.", "DOPAMINE_FLOW", "GENRE_PICK", false, 93),
                FallbackTrack("Snooze", "SZA", "R&B", "Velvet harmonies ease afternoon fatigue while keeping warmth.", "CHRONO_RESTORE", "GENRE_PICK", false, 91),
                FallbackTrack("Bad Habit", "Steve Lacy", "R&B", "Warm guitar phrasing keeps unfamiliar rotation lively.", "PALATE_CLEANSER", "GENRE_PICK", false, 89)
            )
            isHipHop -> listOf(
                FallbackTrack("Surround Sound", "JID & 21 Savage", "Hip-Hop", "Soul sample flips into surgical cadence fit high-focus windows.", "DOPAMINE_FLOW", "GENRE_PICK", false, 95),
                FallbackTrack("a lot", "21 Savage & J. Cole", "Hip-Hop", "Soulful loop balances crisp vocal clarity cleanly.", "DOPAMINE_FLOW", "GENRE_PICK", false, 92),
                FallbackTrack("Pink + White", "Frank Ocean", "R&B", "Stripped piano chords reset sensory repetition effortlessly.", "PALATE_CLEANSER", "GENRE_PICK", false, 90),
                FallbackTrack("Ku Lo Sa", "Oxlade", "Afrobeats", "Warm syncopation adds uplifting melodic relief.", "NOVEL_EXPLORATION", "GENRE_PICK", false, 88)
            )
            else -> listOf(
                FallbackTrack("Snooze", "SZA", "R&B", "Velvety chord progressions match balanced circadian listening.", "DOPAMINE_FLOW", "GENRE_PICK", false, 94),
                FallbackTrack("Bad Habit", "Steve Lacy", "R&B", "Warm guitar groove sparks freshness without ear fatigue.", "DOPAMINE_FLOW", "GENRE_PICK", false, 92),
                FallbackTrack("Calm Down", "Rema", "Afrobeats", "Smooth melodic hooks sustain effortless engagement.", "DOPAMINE_FLOW", "GENRE_PICK", false, 90),
                FallbackTrack("Levitating", "Dua Lipa", "Pop", "Crisp four-on-the-floor energy refreshes midday momentum.", "CHRONO_RESTORE", "GENRE_PICK", false, 88)
            )
        }

        // 3 Global Wildcard deck picks strictly outside the user's primary genres
        val activeGenreNames = genrePicks.map { it.genre.lowercase() }.toSet()
        val wildcardCandidates = listOf(
            FallbackTrack("Black Satin", "Yussef Dayes", "Nu-Jazz & Neo-Soul", "Live London percussion meets hypnotic Rhodes chords — a fresh sonic leap outside your normal lanes.", "NOVEL_EXPLORATION", "GLOBAL_WILDCARD", true, 84),
            FallbackTrack("From The Start", "Laufey", "Bossa Nova & MPB", "Light acoustic bossa sway resets heavy bass saturation with airy warmth.", "NOVEL_EXPLORATION", "GLOBAL_WILDCARD", true, 82),
            FallbackTrack("Delilah (pull me out of this)", "Fred again..", "Electronic & Afro House", "Euphoric chopped vocals over warm house chords widen your rhythmic palette.", "NOVEL_EXPLORATION", "GLOBAL_WILDCARD", true, 81),
            FallbackTrack("Mambote", "Fally Ipupa", "Afro-Francophone", "Silky Congolese rumba guitars bridge effortlessly from warm melodic tastes.", "NOVEL_EXPLORATION", "GLOBAL_WILDCARD", true, 83)
        ).filter { !activeGenreNames.contains(it.genre.lowercase()) }.take(3)

        val excludedSet = mergedExcludeTitles.flatMap { listOf(it.lowercase().trim(), normalizeTitleExclusionKey(it)) }
            .filter { it.isNotBlank() }
            .toSet()
        val excludedKeySet = mergedExcludeKeys.map { it.lowercase().trim() }.toSet()
        val combined = (genrePicks + wildcardCandidates).filter { item ->
            val key = TrackIdentityResolver.buildNormalizedMatchKey(item.title, item.artist)
            val cleanT = normalizeTitleExclusionKey(item.title)
            !excludedSet.contains(item.title.lowercase()) &&
                !excludedSet.contains(cleanT) &&
                !excludedKeySet.contains(key)
        }

        val offlineSummary = getSavedLearningSummary(context).copy(
            dropDateKey = noonCycleKey,
            isAiGenerated = false,
            modelUsed = "TuneMind Offline Taste Model",
            nextRefreshLocalLabel = "12:00 PM ${NoonDropSchedule.getLocalTimeZoneShortLabel()}",
            lastUpdatedMs = System.currentTimeMillis()
        )
        saveLearningSummary(context, offlineSummary)
        onLearningSummaryUpdated?.invoke(offlineSummary)

        val nowMs = System.currentTimeMillis()
        val offlineEntities = combined.mapIndexed { idx, item ->
            AiRecommendationEntity(
                id = AiRecommendationEntity.createDeterministicId(item.title, item.artist),
                title = item.title,
                artist = item.artist,
                album = null,
                category = item.category,
                subTier = item.subTier,
                reason = item.reason,
                confidence = (item.matchPercent / 100f).coerceIn(0.75f, 0.98f),
                timeSlotKey = timeSlotKey,
                timeSlotLabel = timeSlotLabel,
                recommendedAtMs = nowMs - idx,
                suggestedProviderId = preferredProviderId,
                telemetryBasisSummary = "Demonstrated Rotation: $artistsSummary",
                genreTag = item.genre,
                dropDateKey = noonCycleKey,
                matchScorePercent = item.matchPercent,
                isWildcard = item.isWildcard,
                trackId = TrackIdentityResolver.buildDeterministicTrackId(item.title, item.artist)
            )
        }
        recordPermanentExcludedRecommendations(context, offlineEntities)
        offlineEntities
    }

    /**
     * Records real-time recommendation feedback (`PLAYED`, `COMPLETED`, `SAVED`, `SKIPPED`, `DISMISSED`)
     * to POST /api/v1/discovery/feedback so the Bayesian bandit + EMA taste weights evolve immediately.
     */
    suspend fun sendDiscoveryFeedback(
        context: Context,
        userId: String,
        item: AiRecommendationEntity,
        outcome: String,
        listenedMs: Long = 0L,
        durationMs: Long = item.trackDurationMs,
        volumeRatio: Float? = null,
        timeSlot: String = item.timeSlotLabel
    ): ForYouLearningSummary? = withContext(Dispatchers.IO) {
        val baseUrl = resolveActiveBackendUrl(context)
        if (baseUrl.isBlank()) return@withContext null
        try {
            val payload = JSONObject().apply {
                if (userId.isNotBlank()) put("userId", userId)
                put("title", item.title)
                put("artist", item.artist)
                put("genre", item.genreTag)
                put("subTier", item.subTier)
                put("outcome", outcome)
                put("listenedMs", listenedMs.coerceAtLeast(0L))
                put("durationMs", durationMs.coerceAtLeast(0L))
                if (volumeRatio != null && volumeRatio > 0f) {
                    put("volumeRatio", volumeRatio.toDouble().coerceIn(0.05, 1.0))
                }
                put("timeSlot", timeSlot)
                put("dropDate", item.dropDateKey.ifBlank { NoonDropSchedule.getNoonCycleKey() })
            }
            val url = URL("$baseUrl/api/v1/discovery/feedback")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            val token = runCatching { TuneMindAuthSessionManager.getInstance(context).getAccessToken() }.getOrDefault("")
            if (token.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }
            conn.connectTimeout = 8_000
            conn.readTimeout = 12_000
            conn.doOutput = true

            OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            if (conn.responseCode in 200..299) {
                val text = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                val root = JSONObject(text)
                val updated = parseLearningSummaryJson(root, NoonDropSchedule.getNoonCycleKey())
                if (updated != null) {
                    saveLearningSummary(context, updated)
                }
                return@withContext updated
            }
        } catch (_: Exception) {}
        null
    }

    fun saveLatestReport(context: Context, report: ReasoningReport) {
        try {
            context.getSharedPreferences(PREFS_REPORT, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LATEST_REPORT, report.toJson())
                .apply()
        } catch (_: Exception) {}
    }

    fun getLatestReport(context: Context): ReasoningReport? {
        return try {
            val json = context.getSharedPreferences(PREFS_REPORT, Context.MODE_PRIVATE)
                .getString(KEY_LATEST_REPORT, null) ?: return null
            ReasoningReport.fromJson(json)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Synthesizes a Cognitive Reasoning Report from telemetry database events using paid Claude backend.
     */
    suspend fun generateTelemetryReasoningReport(
        context: Context,
        eventCount: Int,
        topSourceApp: String,
        recentTracks: List<TrackMemoryEntry>,
        vaultTracks: List<VaultTrack>,
        nowPlaying: NowPlayingObservation?,
        timeSlot: String,
        forceRefresh: Boolean = false
    ): Result<ReasoningReport> {
        val existingCached = getLatestReport(context)
        if (!forceRefresh && existingCached != null) {
            val ageMs = System.currentTimeMillis() - existingCached.generatedAtMs
            val eventDelta = kotlin.math.abs(eventCount - existingCached.analyzedEventCount)
            if (ageMs in 0L..(4 * 60 * 60_000L) && eventDelta < 8) {
                return Result.success(existingCached)
            }
        }

        val currentTrackName = nowPlaying?.title ?: recentTracks.firstOrNull()?.title
        val currentTrackPlays = recentTracks.firstOrNull()?.distinctAppearanceCount ?: 0
        val estSatiety = minOf(95, currentTrackPlays * 22)

        val payload = JSONObject().apply {
            put("timeSlot", timeSlot)
            put("eventCount", eventCount)
            put("topSourceApp", topSourceApp)
            if (nowPlaying != null && !nowPlaying.title.isNullOrBlank()) {
                put("nowPlaying", JSONObject().apply {
                    put("title", nowPlaying.title)
                    put("artist", nowPlaying.artist ?: "")
                    put("isPlaying", nowPlaying.isPlaying)
                    put("sourcePackage", nowPlaying.sourcePackage ?: topSourceApp)
                })
            }
            val tracksArray = JSONArray()
            recentTracks.take(8).forEach { t ->
                val identity = TrackIdentityResolver.resolveCanonicalIdentity(
                    trackId = t.trackId.ifBlank { null },
                    spotifyUri = t.providerTrackId,
                    title = t.title,
                    artist = t.artist
                )
                tracksArray.put(JSONObject().apply {
                    put("trackId", identity.trackId)
                    identity.spotifyUri?.let { put("spotifyUri", it) }
                    put("title", t.title)
                    put("artist", t.artist)
                    put("plays", t.distinctAppearanceCount.coerceAtLeast(0))
                    put("satiety", minOf(95, t.distinctAppearanceCount.coerceAtLeast(0) * 22))
                })
            }
            put("recentTracks", tracksArray)
            val vaultArray = JSONArray()
            vaultTracks.forEach { v ->
                vaultArray.put(JSONObject().apply {
                    put("title", v.title)
                    put("artist", v.artist)
                    put("cooldownDaysRemaining", v.cooldownDaysRemaining.toInt())
                })
            }
            put("vaultTracks", vaultArray)
        }

        val backendResult = callBackendAnalyze(payload)
        backendResult.getOrNull()?.let { res ->
            try {
                val score = res.optInt("receptivityScore", res.optInt("receptorPotencyScore", 85))
                val state = res.optString("cognitiveState", res.optString("state", "OPTIMAL_FLOW"))
                val label = res.optString("statusLabel", "Optimal Cognitive Flow")
                val narrative = res.optString("flowNarrative", res.optString("flowStateDescription", ""))
                val recsArray = res.optJSONArray("recommendations") ?: res.optJSONArray("acousticShiftRecommendations")
                val shiftRec = if (recsArray != null && recsArray.length() > 0) {
                    val r = recsArray.getJSONObject(0)
                    r.optString("action", r.optString("recommendation", "Maintain balanced frequency range."))
                } else "Maintain balanced frequency dynamic range."
                val steer = if (recsArray != null && recsArray.length() > 1) {
                    val r = recsArray.getJSONObject(1)
                    r.optString("action", r.optString("recommendation", "Continue current listening curve."))
                } else "Continue current listening curve; receptor fatigue remains optimal."

                val report = ReasoningReport(
                    id = UUID.randomUUID().toString(),
                    generatedAtMs = System.currentTimeMillis(),
                    analyzedEventCount = eventCount.coerceAtLeast(0),
                    topSourceApp = topSourceApp,
                    receptorPotencyScore = score.coerceIn(5, 100),
                    fatigueRiskLevel = state,
                    fatigueRiskLabel = label,
                    flowStateDescription = narrative.ifBlank { "Auditory receptor recovery is balanced with dopamine engagement." },
                    acousticShiftRecommendation = shiftRec,
                    steerAction = steer,
                    observedTrackContext = currentTrackName,
                    isClaudeGenerated = res.optBoolean("isAiGenerated", true)
                )
                saveLatestReport(context, report)
                return Result.success(report)
            } catch (_: Exception) {}
        }

        val fallback = ReasoningReport.createHeuristicBaseline(
            eventCount = eventCount,
            topSource = topSourceApp,
            satietyPercent = estSatiety,
            currentTrack = currentTrackName
        )
        saveLatestReport(context, fallback)
        return Result.success(fallback)
    }
}
