package com.tunemind.app.domain.ai

import android.content.Context
import com.tunemind.app.BuildConfig
import com.tunemind.app.domain.execution.OsExecutionCommand
import com.tunemind.app.domain.execution.ReasoningResponse
import com.tunemind.app.domain.execution.TuneMindExecutionEngine
import com.tunemind.app.domain.model.TrackMemoryEntry
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
import java.util.UUID

/**
 * Cognitive Reasoning Engine powered exclusively by the TuneMind Backend
 * (Anthropic Claude via paid server-side `ANTHROPIC_API_KEY`), with local
 * neuro-acoustic heuristic fallbacks when offline.
 *
 * No Google Gemini endpoints or keys are used anywhere in TuneMind.
 */
object GeminiReasoningEngine {

    private const val PREFS_REPORT = "tunemind_telemetry_reasoning_prefs"
    private const val KEY_LATEST_REPORT = "latest_reasoning_report_json"

    fun getApiKey(@Suppress("UNUSED_PARAMETER") context: Context): String = ""

    fun saveApiKey(@Suppress("UNUSED_PARAMETER") context: Context, @Suppress("UNUSED_PARAMETER") key: String) {
        // AI credentials live strictly on the backend (paid ANTHROPIC_API_KEY).
    }

    fun isConfigured(@Suppress("UNUSED_PARAMETER") context: Context): Boolean {
        return BuildConfig.TUNEMIND_BASE_URL.trim().isNotBlank()
    }

    /**
     * Checks whether the paid Claude reasoning backend is online and configured.
     */
    suspend fun testConnection(
        @Suppress("UNUSED_PARAMETER") context: Context,
        @Suppress("UNUSED_PARAMETER") candidateKey: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = BuildConfig.TUNEMIND_BASE_URL.trim().trimEnd('/')
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
            if (baseUrl.isBlank()) {
                return@withContext Result.failure(IllegalStateException("No backend URL configured in BuildConfig.TUNEMIND_BASE_URL"))
            }
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
            val baseUrl = BuildConfig.TUNEMIND_BASE_URL.trim().trimEnd('/')
            if (baseUrl.isBlank()) {
                return@withContext Result.failure(IllegalStateException("No backend URL configured in BuildConfig.TUNEMIND_BASE_URL"))
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
                val osCommand = OsExecutionCommand(
                    intent = cmdJson?.optString("intent", "NONE") ?: "NONE",
                    target_player = cmdJson?.optString("target_player", "com.spotify.music") ?: "com.spotify.music",
                    search_query = cmdJson?.optString("search_query", "") ?: "",
                    suggested_bpm_target = if (cmdJson?.has("suggested_bpm_target") == true) cmdJson.optInt("suggested_bpm_target") else null
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
            put("topSourceApp", nowPlaying?.sourcePackage ?: "Spotify")
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
                tracksArray.put(JSONObject().apply {
                    put("title", t.title)
                    put("artist", t.artist)
                    put("plays", t.distinctAppearanceCount)
                    put("satiety", minOf(95, maxOf(10, t.distinctAppearanceCount * 22)))
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
                observationCountBasis = 1
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
        val payload = JSONObject().apply {
            put("timeSlot", "Track Profile")
            put("eventCount", maxOf(1, playCount))
            put("topSourceApp", "Spotify")
            put("nowPlaying", JSONObject().apply {
                put("title", title)
                put("artist", artist)
                put("isPlaying", true)
            })
            put(
                "recentTracks",
                JSONArray().put(
                    JSONObject().apply {
                        put("title", title)
                        put("artist", artist)
                        put("plays", maxOf(1, playCount))
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

    /**
     * Generates persistent Chrono-Receptive AI recommendations via backend Claude (`/api/v1/reasoning/discovery`),
     * with genre-aware adaptive baseline fallback when offline.
     */
    suspend fun generateChronoCrateRecommendations(
        @Suppress("UNUSED_PARAMETER") context: Context,
        recentTracks: List<TrackMemoryEntry>,
        vaultTracks: List<VaultTrack>,
        timeSlotKey: String,
        timeSlotLabel: String,
        satietyPercent: Int,
        preferredProviderId: String,
        userTasteAnchors: List<String> = emptyList(),
        nowPlayingArtist: String? = null,
        excludeTitles: List<String> = emptyList(),
        playedSignals: List<String> = emptyList(),
        dismissedSignals: List<String> = emptyList()
    ): List<com.tunemind.app.domain.recommendation.AiRecommendationEntity> = withContext(Dispatchers.IO) {
        val observedArtists = (recentTracks.mapNotNull { it.artist } + listOfNotNull(nowPlayingArtist))
            .filter { it.isNotBlank() }
            .distinct()
        val allArtists = (userTasteAnchors + observedArtists).distinct().filter { it.isNotBlank() }
        val artistsSummary = allArtists.take(8).joinToString(", ").ifEmpty { "Bongo Flava, Afrobeats, Hip-Hop, R&B, Pop" }

        val payload = JSONObject().apply {
            put("timeSlot", timeSlotLabel)
            put("satietyLevel", satietyPercent)
            put("preferredProvider", preferredProviderId)

            val artistsArray = JSONArray()
            allArtists.take(8).forEach { artistsArray.put(it) }
            put("topArtists", artistsArray)

            val anchorsArray = JSONArray()
            userTasteAnchors.forEach { anchorsArray.put(it) }
            put("userTasteAnchors", anchorsArray)

            val excludeArray = JSONArray()
            excludeTitles.take(60).forEach { excludeArray.put(it) }
            put("excludeTitles", excludeArray)

            val playedArray = JSONArray()
            playedSignals.take(12).forEach { playedArray.put(it) }
            put("playedFeedback", playedArray)

            val dismissedArray = JSONArray()
            dismissedSignals.take(12).forEach { dismissedArray.put(it) }
            put("dismissedFeedback", dismissedArray)

            val tracksArray = JSONArray()
            recentTracks.take(8).forEach { t ->
                tracksArray.put(JSONObject().apply {
                    put("title", t.title)
                    put("artist", t.artist)
                    put("plays", t.distinctAppearanceCount)
                    put("satiety", minOf(95, maxOf(10, t.distinctAppearanceCount * 22)))
                })
            }
            put("recentTracks", tracksArray)

            val vaultArray = JSONArray()
            vaultTracks.forEach { v ->
                vaultArray.put(JSONObject().apply {
                    put("title", v.title)
                    put("artist", v.artist)
                })
            }
            put("vaultTracks", vaultArray)
        }

        // Route via backend paid Claude (/api/v1/reasoning/discovery)
        val backendUrl = BuildConfig.TUNEMIND_BASE_URL.trim().trimEnd('/')
        if (backendUrl.isNotBlank()) {
            try {
                val url = URL("$backendUrl/api/v1/reasoning/discovery")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Accept", "application/json")
                conn.connectTimeout = 12_000
                conn.readTimeout = 24_000
                conn.doOutput = true

                OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }

                if (conn.responseCode in 200..299) {
                    val text = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                    val root = JSONObject(text)
                    val recsArray = root.optJSONArray("recommendations")
                    if (recsArray != null && recsArray.length() > 0) {
                        val list = mutableListOf<com.tunemind.app.domain.recommendation.AiRecommendationEntity>()
                        val seenKeys = mutableSetOf<String>()
                        for (i in 0 until recsArray.length()) {
                            val item = recsArray.getJSONObject(i)
                            val title = item.optString("title", "Discovery Track").trim()
                            val artist = item.optString("artist", "Featured Artist").trim()
                            val key = "${title.lowercase()}_${artist.lowercase()}"
                            if (title.isBlank() || seenKeys.contains(key)) continue
                            seenKeys.add(key)

                            list.add(
                                com.tunemind.app.domain.recommendation.AiRecommendationEntity(
                                    id = com.tunemind.app.domain.recommendation.AiRecommendationEntity.createDeterministicId(title, artist),
                                    title = title,
                                    artist = artist,
                                    album = item.optString("album", null),
                                    category = item.optString("category", "DOPAMINE_FLOW"),
                                    subTier = item.optString("subTier", if (i < 4) "CORE_DOPAMINE" else if (i < 7) "DEEP_CUT" else if (i < 9) "PALATE_CLEANSER" else "SONIC_FRONTIER"),
                                    reason = item.optString("reason", "Curated for your $timeSlotLabel dopamine state based on your listening pattern."),
                                    confidence = item.optDouble("confidence", 0.94).toFloat(),
                                    timeSlotKey = timeSlotKey,
                                    timeSlotLabel = timeSlotLabel,
                                    recommendedAtMs = System.currentTimeMillis(),
                                    suggestedProviderId = preferredProviderId,
                                    telemetryBasisSummary = "Demonstrated Rotation: $artistsSummary"
                                )
                            )
                        }
                        if (list.isNotEmpty()) return@withContext list
                    }
                }
            } catch (_: Exception) {}
        }

        // Offline genre-aware baseline fallback
        val lower = artistsSummary.lowercase()
        val isBongo = lower.contains("bongo") || lower.contains("mbosso") || lower.contains("diamond") || lower.contains("platnumz") || lower.contains("harmonize") || lower.contains("rayvanny") || lower.contains("zuchu") || lower.contains("marioo") || lower.contains("kiba") || lower.contains("kusah") || lower.contains("jux") || lower.contains("otile") || lower.contains("nandy") || lower.contains("swahili") || lower.contains("east africa")
        val isAfro = lower.contains("afro") || lower.contains("amapiano") || lower.contains("burna") || lower.contains("wizkid") || lower.contains("rema") || lower.contains("asake") || lower.contains("tems")
        val isHipHop = lower.contains("hip") || lower.contains("rap") || lower.contains("drake") || lower.contains("kendrick") || lower.contains("cole") || lower.contains("travis")

        data class FallbackTrack(val title: String, val artist: String, val reason: String, val category: String, val subTier: String)

        val fullLibrary = if (isBongo) {
            listOf(
                FallbackTrack("Huwezi Jua", "Mbosso", "Soulful Bongo Flava acoustic chords deliver immediate emotional entrainment.", "DOPAMINE_FLOW", "CORE_DOPAMINE"),
                FallbackTrack("Mi Amor", "Marioo & Jovial", "Tender Swahili vocal harmonies and sweet acoustic rhythm clear cognitive auditory fatigue.", "DOPAMINE_FLOW", "CORE_DOPAMINE"),
                FallbackTrack("Komasava", "Diamond Platnumz", "High-energy Afro-Bongo groove and infectious tempo boost midday dopamine alertness.", "DOPAMINE_FLOW", "CORE_DOPAMINE"),
                FallbackTrack("Sukari", "Zuchu", "Playful taarab-infused acoustic cadence and bright melodic hooks provide sustained joy.", "DOPAMINE_FLOW", "CORE_DOPAMINE"),
                FallbackTrack("Amepotea", "Mbosso", "Introspective acoustic ballad highlighting rich Swahili lyricism and melancholy strings.", "NOVEL_EXPLORATION", "DEEP_CUT"),
                FallbackTrack("Enjoy", "Jux & Diamond Platnumz", "Effortless Afro-Bongo bounce with smooth vocal trade-offs maintaining flow state.", "NOVEL_EXPLORATION", "DEEP_CUT"),
                FallbackTrack("Single Again", "Harmonize", "Melodic guitar progression and relatable emotional phrasing tailored for chrono-recovery.", "CHRONO_RESTORE", "DEEP_CUT"),
                FallbackTrack("Dah!", "Nandy", "Airy, gentle vocal production resets acoustic receptor saturation effortlessly.", "PALATE_CLEANSER", "PALATE_CLEANSER"),
                FallbackTrack("Inatosha", "Marioo", "Warm mid-tempo guitar arpeggios soothe high-frequency auditory fatigue.", "PALATE_CLEANSER", "PALATE_CLEANSER"),
                FallbackTrack("Kelebe", "Rayvanny & Innoss'B", "Congolese Rumba meets Bongo Flava fusion expands your listening horizon.", "NOVEL_EXPLORATION", "SONIC_FRONTIER")
            )
        } else if (isAfro) {
            listOf(
                FallbackTrack("Last Last", "Burna Boy", "Syncopated groove triggers instantaneous dopamine entrainment.", "DOPAMINE_FLOW", "CORE_DOPAMINE"),
                FallbackTrack("Calm Down", "Rema", "Smooth melodic warmth and mid-tempo rhythmic pacing calibrated for circadian focus.", "DOPAMINE_FLOW", "CORE_DOPAMINE"),
                FallbackTrack("Lonely At The Top", "Asake", "Acoustic log drum undertones deliver supreme cognitive comfort.", "DOPAMINE_FLOW", "CORE_DOPAMINE"),
                FallbackTrack("Rush", "Ayra Starr", "Punchy percussion and magnetic hook provide sustained listening pleasure.", "DOPAMINE_FLOW", "CORE_DOPAMINE"),
                FallbackTrack("Soweto", "Victony & Tempoe", "Subtle flute hooks and delicate rhythmic pacing keep listening receptors active.", "NOVEL_EXPLORATION", "DEEP_CUT"),
                FallbackTrack("Ku Lo Sa", "Oxlade", "Intricate falsetto and warm percussion create an expansive acoustic space.", "NOVEL_EXPLORATION", "DEEP_CUT"),
                FallbackTrack("Essence", "Wizkid & Tems", "Smooth duet harmonies induce effortless calm and flow.", "CHRONO_RESTORE", "DEEP_CUT"),
                FallbackTrack("Free Mind", "Tems", "Intimate acoustic guitar chords and raw vocal dynamics clear auditory habituation.", "PALATE_CLEANSER", "PALATE_CLEANSER"),
                FallbackTrack("Understand", "Omah Lay", "Gentle mid-tempo bounce allows ear rest between high-energy sessions.", "PALATE_CLEANSER", "PALATE_CLEANSER"),
                FallbackTrack("Mnike", "Tyler ICU & Tumelo.za", "Deep Amapiano percussion patterns introduce sonic novelty.", "NOVEL_EXPLORATION", "SONIC_FRONTIER")
            )
        } else if (isHipHop) {
            listOf(
                FallbackTrack("Middle Child", "J. Cole", "Crisp horn fanfare and punchy 808 cadence stimulate focus.", "DOPAMINE_FLOW", "CORE_DOPAMINE"),
                FallbackTrack("First Person Shooter", "Drake & J. Cole", "Dynamic beat switch maintains intense cognitive engagement.", "DOPAMINE_FLOW", "CORE_DOPAMINE"),
                FallbackTrack("HUMBLE.", "Kendrick Lamar", "Piano motif and crisp bass structure provide immediate alertness.", "DOPAMINE_FLOW", "CORE_DOPAMINE"),
                FallbackTrack("SICKO MODE", "Travis Scott", "Layered tempo shift creates rich auditory immersion.", "DOPAMINE_FLOW", "CORE_DOPAMINE"),
                FallbackTrack("family ties", "Baby Keem & Kendrick Lamar", "Dynamic brass modulation expands your listening horizon.", "NOVEL_EXPLORATION", "DEEP_CUT"),
                FallbackTrack("Surround Sound", "JID & 21 Savage", "Soul sample flips into high-precision technical cadence.", "NOVEL_EXPLORATION", "DEEP_CUT"),
                FallbackTrack("a lot", "21 Savage & J. Cole", "Soulful backing vocal loop balances temporal energy.", "CHRONO_RESTORE", "DEEP_CUT"),
                FallbackTrack("PRIDE.", "Kendrick Lamar", "Relaxed vocal pacing provides ear fatigue relief.", "PALATE_CLEANSER", "PALATE_CLEANSER"),
                FallbackTrack("LOVE.", "Kendrick Lamar & Zacari", "Minimalist production resets auditory saturation.", "PALATE_CLEANSER", "PALATE_CLEANSER"),
                FallbackTrack("Redrum", "21 Savage", "Classic vocal chop fused with contemporary rhythm.", "NOVEL_EXPLORATION", "SONIC_FRONTIER")
            )
        } else {
            listOf(
                FallbackTrack("Blinding Lights", "The Weeknd", "Driving synth progression and steady groove deliver peak engagement.", "DOPAMINE_FLOW", "CORE_DOPAMINE"),
                FallbackTrack("Kill Bill", "SZA", "Bouncy bassline and crisp vocal hooks trigger sustained listening pleasure.", "DOPAMINE_FLOW", "CORE_DOPAMINE"),
                FallbackTrack("Levitating", "Dua Lipa", "Upbeat rhythm and energetic tempo lift daily energy.", "DOPAMINE_FLOW", "CORE_DOPAMINE"),
                FallbackTrack("As It Was", "Harry Styles", "Bright chimes and driving drum beat elevate focus.", "DOPAMINE_FLOW", "CORE_DOPAMINE"),
                FallbackTrack("Snooze", "SZA", "Velvety chord progressions harmonize with calm sessions.", "CHRONO_RESTORE", "DEEP_CUT"),
                FallbackTrack("Bad Habit", "Steve Lacy", "Warm guitar hooks and harmonies keep rotation fresh.", "NOVEL_EXPLORATION", "DEEP_CUT"),
                FallbackTrack("Stay", "The Kid LAROI & Justin Bieber", "Upbeat tempo and bright harmonic cadence boost alertness.", "CHRONO_RESTORE", "DEEP_CUT"),
                FallbackTrack("Redbone", "Childish Gambino", "Warm bassline and falsetto reset compressed ear fatigue.", "PALATE_CLEANSER", "PALATE_CLEANSER"),
                FallbackTrack("Pink + White", "Frank Ocean", "Stripped piano and acoustic guitar reset sensory saturation.", "PALATE_CLEANSER", "PALATE_CLEANSER"),
                FallbackTrack("Dead Man Walking", "Brent Faiyaz", "Rich strings and measured cadence deliver temporal balance.", "NOVEL_EXPLORATION", "SONIC_FRONTIER")
            )
        }

        val excludedSet = excludeTitles.map { it.lowercase().trim() }.toSet()
        val nonExcluded = fullLibrary.filter { !excludedSet.contains(it.title.lowercase()) }
        val finalTracks = (if (nonExcluded.size >= 4) nonExcluded else fullLibrary).take(10)

        finalTracks.map { item ->
            com.tunemind.app.domain.recommendation.AiRecommendationEntity(
                id = com.tunemind.app.domain.recommendation.AiRecommendationEntity.createDeterministicId(item.title, item.artist),
                title = item.title,
                artist = item.artist,
                album = null,
                category = item.category,
                subTier = item.subTier,
                reason = item.reason,
                confidence = 0.95f,
                timeSlotKey = timeSlotKey,
                timeSlotLabel = timeSlotLabel,
                recommendedAtMs = System.currentTimeMillis(),
                suggestedProviderId = preferredProviderId,
                telemetryBasisSummary = "Demonstrated Rotation: $artistsSummary"
            )
        }
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
        timeSlot: String
    ): Result<ReasoningReport> {
        val currentTrackName = nowPlaying?.title ?: recentTracks.firstOrNull()?.title
        val currentTrackPlays = recentTracks.firstOrNull()?.distinctAppearanceCount ?: 1
        val estSatiety = minOf(95, maxOf(15, currentTrackPlays * 22))

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
                tracksArray.put(JSONObject().apply {
                    put("title", t.title)
                    put("artist", t.artist)
                    put("plays", t.distinctAppearanceCount)
                    put("satiety", minOf(95, maxOf(10, t.distinctAppearanceCount * 22)))
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
                    analyzedEventCount = maxOf(eventCount, 1),
                    topSourceApp = topSourceApp,
                    receptorPotencyScore = score.coerceIn(5, 100),
                    fatigueRiskLevel = state,
                    fatigueRiskLabel = label,
                    flowStateDescription = narrative.ifBlank { "Auditory receptor recovery is balanced with dopamine engagement." },
                    acousticShiftRecommendation = shiftRec,
                    steerAction = steer,
                    observedTrackContext = currentTrackName,
                    isGeminiGenerated = res.optBoolean("isAiGenerated", true)
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
