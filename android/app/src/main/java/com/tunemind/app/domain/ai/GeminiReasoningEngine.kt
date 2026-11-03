package com.tunemind.app.domain.ai

import android.content.Context
import com.tunemind.app.BuildConfig
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
 * Real-time Cognitive Reasoning Engine powered by Google Gemini.
 * Synthesizes local listening telemetry, temporal patterns, satiety accumulation,
 * and auditory anchor dynamics into real cognitive audio intelligence.
 */
object GeminiReasoningEngine {

    private const val PREFS_NAME = "tunemind_gemini_prefs"
    private const val KEY_GEMINI_API = "gemini_api_key"
    private const val DEFAULT_MODEL = "gemini-3.8-flash"
    private val FALLBACK_MODELS = listOf("gemini-3.8-flash", "gemini-2.5-flash", "gemini-3.1-flash-lite")
    private const val BASE_API_URL = "https://generativelanguage.googleapis.com/v1beta/models"

    /**
     * Resolves active Gemini API key from:
     * 1. In-app Settings (SharedPreferences)
     * 2. BuildConfig.GEMINI_API_KEY (from local.properties or gradle build env)
     * 3. System environment variable API_KEY or GEMINI_API_KEY
     */
    fun getApiKey(context: Context): String {
        val userSaved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_GEMINI_API, "")
            ?.trim() ?: ""
        if (userSaved.isNotBlank()) return userSaved

        val buildConfigKey = runCatching { BuildConfig.GEMINI_API_KEY }.getOrNull()?.trim() ?: ""
        if (buildConfigKey.isNotBlank() && buildConfigKey != "\"\"") return buildConfigKey

        val envApi = runCatching { System.getenv("API_KEY") }.getOrNull()?.trim() ?: ""
        if (envApi.isNotBlank()) return envApi

        return runCatching { System.getenv("GEMINI_API_KEY") }.getOrNull()?.trim() ?: ""
    }

    /**
     * Saves user-entered Gemini API key to persistent local storage.
     */
    fun saveApiKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GEMINI_API, key.trim())
            .apply()
    }

    /**
     * Checks if a valid Gemini API key is configured locally OR backend proxy is available.
     */
    fun isConfigured(context: Context): Boolean {
        return getApiKey(context).isNotBlank() || BuildConfig.TUNEMIND_BASE_URL.isNotBlank()
    }

    /**
     * Executes a raw generateContent call against Google Gemini REST API.
     */
    suspend fun callGemini(
        apiKey: String,
        prompt: String,
        systemInstruction: String? = null,
        temperature: Float = 0.7f,
        maxTokens: Int = 1000
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Gemini API key is not configured."))
            }

            val endpoint = "$BASE_API_URL/$DEFAULT_MODEL:generateContent?key=$apiKey"
            val url = URL(endpoint)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 15_000
            conn.readTimeout = 25_000
            conn.doOutput = true

            val rootJson = JSONObject()

            // System instruction if provided
            if (!systemInstruction.isNullOrBlank()) {
                val sysParts = JSONArray().put(JSONObject().put("text", systemInstruction))
                rootJson.put("systemInstruction", JSONObject().put("parts", sysParts))
            }

            // User prompt contents
            val userParts = JSONArray().put(JSONObject().put("text", prompt))
            val contentsArray = JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", userParts)
            )
            rootJson.put("contents", contentsArray)

            // Generation config
            val genConfig = JSONObject()
                .put("temperature", temperature.toDouble())
                .put("maxOutputTokens", maxTokens)
            rootJson.put("generationConfig", genConfig)

            OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                writer.write(rootJson.toString())
                writer.flush()
            }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val responseText = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                val parsed = JSONObject(responseText)
                val candidates = parsed.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val candidate = candidates.getJSONObject(0)
                    val contentObj = candidate.optJSONObject("content")
                    val parts = contentObj?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val text = parts.getJSONObject(0).optString("text", "")
                        return@withContext Result.success(text.trim())
                    }
                }
                Result.failure(IllegalStateException("No content candidate returned by Gemini."))
            } else {
                val errorStream = conn.errorStream
                val errorText = if (errorStream != null) {
                    BufferedReader(InputStreamReader(errorStream, "UTF-8")).use { it.readText() }
                } else "HTTP $responseCode"
                Result.failure(IllegalStateException("Gemini API error ($responseCode): $errorText"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Proxies raw telemetry payload to the TuneMind backend running with the user's API_KEY.
     * Allows Android devices to execute real-time Gemini cognitive intelligence via the backend.
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
                Result.failure(IllegalStateException("Backend reasoning error ($code): $err"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Validates connection to Gemini API.
     */
    suspend fun testConnection(context: Context, candidateKey: String? = null): Result<String> {
        val key = candidateKey?.takeIf { it.isNotBlank() } ?: getApiKey(context)
        if (key.isBlank()) return Result.failure(IllegalStateException("No API key provided."))

        val testPrompt = "Respond with exactly: 'TuneMind Gemini Reasoning Engine Online. Calibration verified.'"
        return callGemini(
            apiKey = key,
            prompt = testPrompt,
            systemInstruction = "You are the acoustic cognitive intelligence engine for TuneMind.",
            temperature = 0.2f,
            maxTokens = 60
        )
    }

    /**
     * Real-time cognitive analysis of user's active listening session and satiety dynamics.
     * Resolves via backend proxy (using backend's API_KEY) first, falling back to local on-device Gemini.
     */
    suspend fun analyzeActiveSession(
        context: Context,
        recentTracks: List<TrackMemoryEntry>,
        nowPlaying: NowPlayingObservation?,
        vaultTracks: List<VaultTrack>,
        timeSlot: String
    ): Result<String> {
        // Build JSON payload for backend analysis proxy
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

        // 1. Try Backend Proxy with backend's API_KEY
        val backendResult = callBackendAnalyze(payload)
        backendResult.getOrNull()?.let { res ->
            val narrative = res.optString("flowNarrative", res.optString("flowStateDescription", ""))
            if (narrative.isNotBlank()) {
                return Result.success(narrative)
            }
        }

        // 2. Direct on-device call if local key exists
        val key = getApiKey(context)
        if (key.isNotBlank()) {
            val topTracksSummary = recentTracks.take(5).joinToString("\n") {
                "- '${it.title}' by ${it.artist} (${it.distinctAppearanceCount} plays, satiety ~${minOf(95, it.distinctAppearanceCount * 22)}%)"
            }.ifEmpty { "No prior tracks logged yet." }

            val nowPlayingSummary = if (nowPlaying != null && !nowPlaying.title.isNullOrBlank()) {
                "'${nowPlaying.title}' by ${nowPlaying.artist} [${if (nowPlaying.isPlaying) "Playing" else "Paused"}]"
            } else "None currently active"

            val vaultSummary = vaultTracks.joinToString(", ") { "${it.title} (${(it.recoveryProgress * 100).toInt()}% restored)" }
                .ifEmpty { "Vault is clear." }

            val prompt = """
                Analyze the user's real-time music listening state:
                - Current Time Context: $timeSlot
                - Currently Observed: $nowPlayingSummary
                - Recent Listening History:
                $topTracksSummary
                - Resting in Cooldown Vault: $vaultSummary

                Provide a 2-sentence sharp cognitive synthesis:
                1. What neuro-acoustic pattern is currently driving the user (focus flow, dopamine loop, or early receptor fatigue)?
                2. Concrete action recommendation (e.g., maintain flow, switch tempo, or insert palate-cleanser).
                Do not use markdown headers or bullet points. Keep it punchy, conversational, and grounded in auditory neuroscience.
            """.trimIndent()

            return callGemini(
                apiKey = key,
                prompt = prompt,
                systemInstruction = "You are TuneMind's Cognitive Audio AI, a real-time auditory neuroscience assistant protecting listeners from music satiety and burnout while maximizing cognitive flow.",
                temperature = 0.6f,
                maxTokens = 200
            )
        }

        return Result.failure(IllegalStateException("No active Gemini API key or backend proxy available."))
    }

    /**
     * Synthesizes AI-reasoned music recommendations grounded in real user data.
     */
    suspend fun generateReasonedRecommendations(
        context: Context,
        recentTracks: List<TrackMemoryEntry>,
        vaultTracks: List<VaultTrack>,
        preferredProviderId: String
    ): List<RecommendedTrack>? {
        val key = getApiKey(context)
        if (key.isBlank()) return null

        val topArtists = recentTracks.mapNotNull { it.artist }.filter { it.isNotBlank() }.distinct().take(4)
        val vaultTitles = vaultTracks.map { it.title }.take(4)

        val prompt = """
            Based on the user's music profile:
            - Familiar Artists: ${topArtists.joinToString(", ").ifEmpty { "Electronic, Indie, Ambient" }}
            - Songs on Rest / Satiated: ${vaultTitles.joinToString(", ").ifEmpty { "None" }}

            Recommend 4 real songs to optimize their cognitive receptivity.
            Output ONLY a JSON array with exactly this structure:
            [
              {
                "title": "Song Title",
                "artist": "Artist Name",
                "album": "Album Name",
                "category": "DISCOVERY" | "CLEANSER" | "FAMILIAR" | "RESTORED",
                "reason": "1-sentence cognitive explanation of why this song fits their acoustic state",
                "confidence": 0.92
              }
            ]
        """.trimIndent()

        val result = callGemini(
            apiKey = key,
            prompt = prompt,
            systemInstruction = "Output valid JSON only. No markdown formatting, no code blocks.",
            temperature = 0.7f,
            maxTokens = 600
        )

        return result.getOrNull()?.let { raw ->
            try {
                val cleaned = raw.replace("```json", "").replace("```", "").trim()
                val array = JSONArray(cleaned)
                val list = mutableListOf<RecommendedTrack>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val catStr = obj.optString("category", "DISCOVERY")
                    val cat = when (catStr.uppercase()) {
                        "CLEANSER" -> RecommendationCategory.PALATE_CLEANSER
                        "FAMILIAR" -> RecommendationCategory.FAMILIAR_RECEPTIVE
                        "RESTORED" -> RecommendationCategory.COOLDOWN_RESTORED
                        else -> RecommendationCategory.EXPLORATION_DISCOVERY
                    }
                    list.add(
                        RecommendedTrack(
                            id = "ai-rec-${UUID.randomUUID()}",
                            title = obj.getString("title"),
                            artist = obj.getString("artist"),
                            album = obj.optString("album", null),
                            reason = obj.optString("reason", "AI Curated for your cognitive state."),
                            confidence = obj.optDouble("confidence", 0.88).toFloat(),
                            category = cat,
                            suggestedProviderId = preferredProviderId,
                            observationCountBasis = 1
                        )
                    )
                }
                list.takeIf { it.isNotEmpty() }
            } catch (t: Throwable) {
                null
            }
        }
    }

    /**
     * Generates a deep neuro-acoustic Song Profile Dossier via Gemini.
     */
    suspend fun generateSongProfileDossier(
        context: Context,
        title: String,
        artist: String,
        playCount: Int,
        satietyPercent: Int
    ): Result<String> {
        val key = getApiKey(context)
        if (key.isBlank()) {
            return Result.failure(IllegalStateException("Gemini API key is required."))
        }

        val prompt = """
            Perform an acoustic & cognitive profile on:
            Track: '$title' by $artist
            Current Metrics: Played $playCount time(s), Satiety estimate: $satietyPercent%.

            In 3 brief sections:
            1. ACOUSTIC DNA: What makes this track appealing (harmonic cadence, rhythm, hooks)?
            2. RECEPTOR DYNAMICS: How quickly does this track cause receptor exhaustion vs sustained focus?
            3. AGENT GUIDANCE: Best time of day or mental state to listen, and when to pause.
            Keep each section to 1-2 concise sentences.
        """.trimIndent()

        return callGemini(
            apiKey = key,
            prompt = prompt,
            systemInstruction = "You are TuneMind's Cognitive Audio AI producing deep song profiles based on auditory perception.",
            temperature = 0.5f,
            maxTokens = 350
        )
    }

    /**
     * Generates persistent Chrono-Receptive AI recommendations stored into user's crate.
     * Evaluates time of day, auditory satiety, and longitudinal listening telemetry.
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
        playedSignals: List<String> = emptyList(),
        dismissedSignals: List<String> = emptyList()
    ): List<com.tunemind.app.domain.recommendation.AiRecommendationEntity> = withContext(Dispatchers.IO) {
        val observedArtists = (recentTracks.mapNotNull { it.artist } + listOfNotNull(nowPlayingArtist))
            .filter { it.isNotBlank() }
            .distinct()
        // Explicit user taste anchors take top precedence
        val allArtists = (userTasteAnchors + observedArtists).distinct().filter { it.isNotBlank() }
        val artistsSummary = allArtists.take(8).joinToString(", ").ifEmpty { "Bongo Flava, Afrobeats, Hip-Hop, R&B, Pop" }

        // 1. Prepare JSON payload for Backend Discovery Proxy
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

        // Try Backend Proxy first
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

        // 2. Direct On-Device Gemini call if local key exists
        val key = getApiKey(context)
        if (key.isNotBlank()) {
            val exclusionStr = if (excludeTitles.isNotEmpty()) {
                "STRICT EXCLUSIONS (DO NOT RECOMMEND): " + excludeTitles.take(40).joinToString(", ")
            } else ""

            val feedbackStr = buildString {
                if (playedSignals.isNotEmpty()) {
                    append("USER PLAYED & ENJOYED: ${playedSignals.take(8).joinToString("; ")}. Emphasize kindred melodies. ")
                }
                if (dismissedSignals.isNotEmpty()) {
                    append("USER DISMISSED/SKIPPED: ${dismissedSignals.take(8).joinToString("; ")}. Steer away from this sound.")
                }
            }

            val prompt = """
                CRITICAL DIRECTIVE:
                The listener actively listens to: $artistsSummary.
                Do NOT guess or recommend generic ambient or indie songs unless their history shows it.
                Every recommended song MUST belong to the EXACT SAME or kindred musical genre, style, and vibe as $artistsSummary.
                - If the listener listens to Bongo Flava / East African Afro-Pop (e.g. Mbosso, Diamond Platnumz, Harmonize, Rayvanny, Zuchu, Marioo, Ali Kiba, Jux, Otile Brown, Nandy): recommend genuine, celebrated Bongo Flava hits from Mbosso, Diamond Platnumz, Marioo, Rayvanny, Harmonize, Zuchu, Alikiba, Otile Brown, etc.! NEVER substitute Western ambient music for Bongo Flava!
                - If the listener listens to Afrobeats/Amapiano: recommend Burna Boy, Asake, Tems, Rema, Wizkid, Ayra Starr, etc.
                - If the listener listens to Hip-Hop/Rap: recommend Kendrick Lamar, J. Cole, Drake, Travis Scott, etc.
                Time of Day: $timeSlotLabel | Satiety Level: $satietyPercent%.
                $feedbackStr
                $exclusionStr

                Recommend EXACTLY 10 real, acclaimed songs across 4 tiers:
                - 4 tracks: "CORE_DOPAMINE" (category: "DOPAMINE_FLOW") - Instant groove / banger matching their top artists' energy.
                - 3 tracks: "DEEP_CUT" (category: "NOVEL_EXPLORATION" or "DOPAMINE_FLOW") - Rising artist or high-quality b-side in their genre.
                - 2 tracks: "PALATE_CLEANSER" (category: "PALATE_CLEANSER") - Stripped-back acoustic or tender vocal track to reset ear fatigue.
                - 1 track: "SONIC_FRONTIER" (category: "NOVEL_EXPLORATION") - Controlled adjacent crossover (e.g. Afro-Amapiano blend).

                Output ONLY a JSON array of 10 objects matching:
                [
                  {
                    "title": "Song Title",
                    "artist": "Artist Name",
                    "album": "Album Name",
                    "category": "DOPAMINE_FLOW" | "PALATE_CLEANSER" | "CHRONO_RESTORE" | "NOVEL_EXPLORATION",
                    "subTier": "CORE_DOPAMINE" | "DEEP_CUT" | "PALATE_CLEANSER" | "SONIC_FRONTIER",
                    "reason": "1 sentence on why fans of ${artistsSummary.split(',')[0]} love this track for $timeSlotLabel.",
                    "confidence": 0.95
                  }
                ]
            """.trimIndent()

            val geminiResult = callGemini(
                apiKey = key,
                prompt = prompt,
                systemInstruction = "Output valid JSON only. Recommend real, popular, genre-congruent songs matching user taste. No duplicates.",
                temperature = 0.35f,
                maxTokens = 2000
            )

            geminiResult.getOrNull()?.let { raw ->
                try {
                    val clean = raw.replace("```json", "").replace("```", "").trim()
                    val array = JSONArray(clean)
                    val list = mutableListOf<com.tunemind.app.domain.recommendation.AiRecommendationEntity>()
                    val seenKeys = mutableSetOf<String>()
                    val excludedLower = excludeTitles.map { it.lowercase().trim() }.toSet()

                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        val title = item.optString("title", "Discovery Track").trim()
                        val artist = item.optString("artist", "Featured Artist").trim()
                        val keyLower = "${title.lowercase()}_${artist.lowercase()}"
                        if (title.isBlank() || seenKeys.contains(keyLower) || excludedLower.contains(title.lowercase())) continue
                        seenKeys.add(keyLower)

                        list.add(
                            com.tunemind.app.domain.recommendation.AiRecommendationEntity(
                                id = com.tunemind.app.domain.recommendation.AiRecommendationEntity.createDeterministicId(title, artist),
                                title = title,
                                artist = artist,
                                album = item.optString("album", null),
                                category = item.optString("category", "DOPAMINE_FLOW"),
                                subTier = item.optString("subTier", if (i < 4) "CORE_DOPAMINE" else if (i < 7) "DEEP_CUT" else if (i < 9) "PALATE_CLEANSER" else "SONIC_FRONTIER"),
                                reason = item.optString("reason", "Curated for your $timeSlotLabel state based on your listening pattern."),
                                confidence = item.optDouble("confidence", 0.94).toFloat(),
                                timeSlotKey = timeSlotKey,
                                timeSlotLabel = timeSlotLabel,
                                recommendedAtMs = System.currentTimeMillis(),
                                suggestedProviderId = preferredProviderId,
                                telemetryBasisSummary = "Demonstrated Rotation: $artistsSummary"
                            )
                        )
                    }
                    if (list.size >= 4) return@withContext list
                } catch (_: Exception) {}
            }
        }

        // 3. Genre-Aware Adaptive Baseline Fallback (10 verified tracks per palette, filtering exclusions)
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
                FallbackTrack("Kelebe", "Rayvanny & Innoss'B", "Exciting Congolese Rumba meets Bongo Flava fusion expands your listening horizon.", "NOVEL_EXPLORATION", "SONIC_FRONTIER")
            )
        } else if (isAfro) {
            listOf(
                FallbackTrack("Last Last", "Burna Boy", "Toni Braxton sample cadence and syncopated groove triggers instantaneous dopamine entrainment.", "DOPAMINE_FLOW", "CORE_DOPAMINE"),
                FallbackTrack("Calm Down", "Rema", "Smooth melodic warmth and mid-tempo rhythmic pacing calibrated for circadian focus.", "DOPAMINE_FLOW", "CORE_DOPAMINE"),
                FallbackTrack("Lonely At The Top", "Asake", "Acoustic log drum undertones deliver supreme cognitive comfort.", "DOPAMINE_FLOW", "CORE_DOPAMINE"),
                FallbackTrack("Rush", "Ayra Starr", "Punchy percussion and magnetic hook provide sustained listening pleasure.", "DOPAMINE_FLOW", "CORE_DOPAMINE"),
                FallbackTrack("Soweto", "Victony & Tempoe", "Subtle flute hooks and delicate rhythmic pacing keep listening receptors active.", "NOVEL_EXPLORATION", "DEEP_CUT"),
                FallbackTrack("Ku Lo Sa", "Oxlade", "Intricate falsetto and warm percussion create an expansive, resonant acoustic space.", "NOVEL_EXPLORATION", "DEEP_CUT"),
                FallbackTrack("Essence", "Wizkid & Tems", "Sun-drenched saxophone cadence and smooth duet harmonies induce tranquility.", "CHRONO_RESTORE", "DEEP_CUT"),
                FallbackTrack("Free Mind", "Tems", "Intimate acoustic guitar chords and raw vocal dynamics clear auditory habituation.", "PALATE_CLEANSER", "PALATE_CLEANSER"),
                FallbackTrack("Understand", "Omah Lay", "Gentle mid-tempo bounce allows ear rest between high-energy sessions.", "PALATE_CLEANSER", "PALATE_CLEANSER"),
                FallbackTrack("Mnike", "Tyler ICU & Tumelo.za", "Deep, hypnotic Amapiano shaker patterns introduce sonic novelty.", "NOVEL_EXPLORATION", "SONIC_FRONTIER")
            )
        } else if (isHipHop) {
            listOf(
                FallbackTrack("Middle Child", "J. Cole", "Crisp horn fanfare and punchy 808 cadence stimulate dopamine release.", "DOPAMINE_FLOW", "CORE_DOPAMINE"),
                FallbackTrack("First Person Shooter", "Drake & J. Cole", "Dynamic two-part beat switch maintains intense cognitive engagement.", "DOPAMINE_FLOW", "CORE_DOPAMINE"),
                FallbackTrack("HUMBLE.", "Kendrick Lamar", "Aggressive piano riff and bass-heavy structure provide immediate alertness.", "DOPAMINE_FLOW", "CORE_DOPAMINE"),
                FallbackTrack("SICKO MODE", "Travis Scott", "Triple-act tempo shift and lush low-end frequencies create rich immersion.", "DOPAMINE_FLOW", "CORE_DOPAMINE"),
                FallbackTrack("family ties", "Baby Keem & Kendrick Lamar", "Dynamic brass modulation and beat shifts expand your listening horizon.", "NOVEL_EXPLORATION", "DEEP_CUT"),
                FallbackTrack("Surround Sound", "JID & 21 Savage", "Aretha Franklin sample flips into high-precision technical cadence.", "NOVEL_EXPLORATION", "DEEP_CUT"),
                FallbackTrack("a lot", "21 Savage & J. Cole", "Soulful backing vocal loop and laid-back cadence balance temporal energy.", "CHRONO_RESTORE", "DEEP_CUT"),
                FallbackTrack("PRIDE.", "Kendrick Lamar", "Warbled guitars and relaxed vocal pacing provide complete ear fatigue relief.", "PALATE_CLEANSER", "PALATE_CLEANSER"),
                FallbackTrack("LOVE.", "Kendrick Lamar & Zacari", "Soothing falsetto vocal lines and minimalist production reset auditory saturation.", "PALATE_CLEANSER", "PALATE_CLEANSER"),
                FallbackTrack("Redrum", "21 Savage", "Vintage vocal sample fused with contemporary trap beats expands aesthetic boundaries.", "NOVEL_EXPLORATION", "SONIC_FRONTIER")
            )
        } else {
            listOf(
                FallbackTrack("Blinding Lights", "The Weeknd", "Driving 80s synth progression and relentless groove deliver peak engagement.", "DOPAMINE_FLOW", "CORE_DOPAMINE"),
                FallbackTrack("Kill Bill", "SZA", "Bouncy bassline and crisp vocal hooks trigger sustained auditory pleasure.", "DOPAMINE_FLOW", "CORE_DOPAMINE"),
                FallbackTrack("Levitating", "Dua Lipa", "Nu-disco funk rhythm and energetic tempo expand current listening patterns.", "DOPAMINE_FLOW", "CORE_DOPAMINE"),
                FallbackTrack("As It Was", "Harry Styles", "Bright bell chimes and driving indie-pop drum beat elevate daily focus.", "DOPAMINE_FLOW", "CORE_DOPAMINE"),
                FallbackTrack("Snooze", "SZA", "Velvety chord progressions harmonize with your circadian rhythm.", "CHRONO_RESTORE", "DEEP_CUT"),
                FallbackTrack("Bad Habit", "Steve Lacy", "Lo-fi guitar hooks and funky harmonies push taste boundaries with zero friction.", "NOVEL_EXPLORATION", "DEEP_CUT"),
                FallbackTrack("Stay", "The Kid LAROI & Justin Bieber", "Upbeat tempo and bright harmonic cadence boost cognitive alertness.", "CHRONO_RESTORE", "DEEP_CUT"),
                FallbackTrack("Redbone", "Childish Gambino", "Warm 70s soul bassline and falsetto reset compressed ear fatigue.", "PALATE_CLEANSER", "PALATE_CLEANSER"),
                FallbackTrack("Pink + White", "Frank Ocean", "Stripped piano and acoustic guitar reset sensory saturation effortlessly.", "PALATE_CLEANSER", "PALATE_CLEANSER"),
                FallbackTrack("Dead Man Walking", "Brent Faiyaz", "Lush orchestral strings and slow cadence deliver perfect temporal balance.", "NOVEL_EXPLORATION", "SONIC_FRONTIER")
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

    private const val PREFS_REPORT = "tunemind_telemetry_reasoning_prefs"
    private const val KEY_LATEST_REPORT = "latest_reasoning_report_json"

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
     * Periodically synthesizes a deep Cognitive Reasoning Report from telemetry database events.
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
        val key = getApiKey(context)
        val currentTrackName = nowPlaying?.title ?: recentTracks.firstOrNull()?.title
        val currentTrackArtist = nowPlaying?.artist ?: recentTracks.firstOrNull()?.artist
        val currentTrackPlays = recentTracks.firstOrNull()?.distinctAppearanceCount ?: 1
        val estSatiety = minOf(95, maxOf(15, currentTrackPlays * 22))

        // 1. Prepare Telemetry Payload for Backend Proxy
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

        // Try backend proxy first (which uses server.ts / process.env.API_KEY)
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

        if (key.isBlank()) {
            val fallback = ReasoningReport.createHeuristicBaseline(
                eventCount = eventCount,
                topSource = topSourceApp,
                satietyPercent = estSatiety,
                currentTrack = currentTrackName
            )
            saveLatestReport(context, fallback)
            return Result.success(fallback)
        }

        val topTracksSummary = recentTracks.take(6).joinToString("\n") {
            "- '${it.title}' by ${it.artist} (${it.distinctAppearanceCount} plays, satiety ~${minOf(95, it.distinctAppearanceCount * 22)}%)"
        }.ifEmpty { "- No prior track signatures logged yet." }

        val nowPlayingDesc = if (nowPlaying != null && !nowPlaying.title.isNullOrBlank()) {
            "'${nowPlaying.title}' by ${nowPlaying.artist} [${if (nowPlaying.isPlaying) "Playing" else "Paused"}, ${nowPlaying.sourcePackage ?: topSourceApp}]"
        } else "None currently active"

        val vaultSummary = vaultTracks.joinToString(", ") { "${it.title} (${(it.recoveryProgress * 100).toInt()}% restored)" }
            .ifEmpty { "Vault is clear." }

        val prompt = """
            Telemetry Context:
            - Database Events Ingested: $eventCount
            - Active Source App: $topSourceApp
            - Time Context: $timeSlot
            - Current Playback: $nowPlayingDesc
            - Recent Frequency Patterns:
            $topTracksSummary
            - Quarantined in Vault: $vaultSummary

            Act as TuneMind's Chief Cognitive Audio AI. Synthesize a structured Cognitive Reasoning Report on user's auditory receptor state, fatigue risk, and focus stability.
            Return ONLY a valid JSON object matching exactly this schema (no markdown, no backticks, no wrap):
            {
              "receptorPotencyScore": 86,
              "fatigueRiskLevel": "OPTIMAL_FLOW",
              "fatigueRiskLabel": "Optimal Cognitive Flow",
              "flowStateDescription": "2 concise sentences explaining the listener's neural engagement, habituation rate, and focus stability based on real tracks.",
              "acousticShiftRecommendation": "1 sentence proposing specific acoustic or tempo changes (e.g. shift from 130bpm kick drums to organic ambient or 70bpm lo-fi).",
              "steerAction": "1 short imperative action recommendation (e.g. 'Rest [Track] in Vault', 'Take 10m acoustic pause', 'Shift to acoustic strings').",
              "observedTrackContext": "${currentTrackName ?: "General Telemetry"}"
            }
        """.trimIndent()

        val rawResult = callGemini(
            apiKey = key,
            prompt = prompt,
            systemInstruction = "You are TuneMind's Cognitive Audio AI engine. Output strictly raw JSON only. Valid values for fatigueRiskLevel: OPTIMAL_FLOW, SENSITIZED, ELEVATED_BURNOUT.",
            temperature = 0.5f,
            maxTokens = 450
        )

        return rawResult.fold(
            onSuccess = { rawText ->
                try {
                    val cleanJson = rawText.replace("```json", "").replace("```", "").trim()
                    val obj = JSONObject(cleanJson)
                    val report = ReasoningReport(
                        id = UUID.randomUUID().toString(),
                        generatedAtMs = System.currentTimeMillis(),
                        analyzedEventCount = maxOf(eventCount, 1),
                        topSourceApp = topSourceApp,
                        receptorPotencyScore = obj.optInt("receptorPotencyScore", 85).coerceIn(5, 100),
                        fatigueRiskLevel = obj.optString("fatigueRiskLevel", "OPTIMAL_FLOW"),
                        fatigueRiskLabel = obj.optString("fatigueRiskLabel", "Optimal Cognitive Flow"),
                        flowStateDescription = obj.optString(
                            "flowStateDescription",
                            "Auditory receptor recovery is balanced with dopamine engagement."
                        ),
                        acousticShiftRecommendation = obj.optString(
                            "acousticShiftRecommendation",
                            "Maintain moderate tempo (95-115 BPM) with wide dynamic range."
                        ),
                        steerAction = obj.optString(
                            "steerAction",
                            "Continue current listening curve; receptor fatigue remains low."
                        ),
                        observedTrackContext = obj.optString("observedTrackContext", currentTrackName),
                        isGeminiGenerated = true
                    )
                    saveLatestReport(context, report)
                    Result.success(report)
                } catch (e: Exception) {
                    val fallback = ReasoningReport.createHeuristicBaseline(
                        eventCount = eventCount,
                        topSource = topSourceApp,
                        satietyPercent = estSatiety,
                        currentTrack = currentTrackName
                    )
                    saveLatestReport(context, fallback)
                    Result.success(fallback)
                }
            },
            onFailure = { err ->
                val fallback = ReasoningReport.createHeuristicBaseline(
                    eventCount = eventCount,
                    topSource = topSourceApp,
                    satietyPercent = estSatiety,
                    currentTrack = currentTrackName
                )
                saveLatestReport(context, fallback)
                Result.success(fallback)
            }
        )
    }
}
