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
    private const val DEFAULT_MODEL = "gemini-2.5-flash"
    private const val BASE_API_URL = "https://generativelanguage.googleapis.com/v1beta/models"

    /**
     * Resolves active Gemini API key from:
     * 1. In-app Settings (SharedPreferences)
     * 2. BuildConfig.GEMINI_API_KEY (from local.properties or gradle build env)
     * 3. System environment variable GEMINI_API_KEY
     */
    fun getApiKey(context: Context): String {
        val userSaved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_GEMINI_API, "")
            ?.trim() ?: ""
        if (userSaved.isNotBlank()) return userSaved

        val buildConfigKey = runCatching { BuildConfig.GEMINI_API_KEY }.getOrNull()?.trim() ?: ""
        if (buildConfigKey.isNotBlank() && buildConfigKey != "\"\"") return buildConfigKey

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
     * Checks if a valid Gemini API key is configured.
     */
    fun isConfigured(context: Context): Boolean {
        return getApiKey(context).isNotBlank()
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
     */
    suspend fun analyzeActiveSession(
        context: Context,
        recentTracks: List<TrackMemoryEntry>,
        nowPlaying: NowPlayingObservation?,
        vaultTracks: List<VaultTrack>,
        timeSlot: String
    ): Result<String> {
        val key = getApiKey(context)
        if (key.isBlank()) {
            return Result.failure(IllegalStateException("Gemini key required"))
        }

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
}
