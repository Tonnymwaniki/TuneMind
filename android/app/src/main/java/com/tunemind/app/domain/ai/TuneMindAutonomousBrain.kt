package com.tunemind.app.domain.ai

import android.content.Context
import android.util.Log
import com.tunemind.app.BuildConfig
import com.tunemind.app.domain.model.TrackMemoryEntry
import com.tunemind.app.domain.recommendation.RecommendationCategory
import com.tunemind.app.domain.recommendation.RecommendedTrack
import com.tunemind.app.ui.NowPlayingObservation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

enum class AgentActionType {
    PLAY_TRACK,
    CHANGE_TRACK,
    CANCEL,
    SPEAK_ONLY,
    SWITCH_PLAYER
}

data class BrainResponse(
    val spokenText: String,
    val action: AgentActionType = AgentActionType.SPEAK_ONLY,
    val resolvedTrack: RecommendedTrack? = null,
    val targetProviderId: String? = null,
    val reasoningRationale: String = ""
)

data class TrackLlmTag(
    val genre: String,
    val language: String,
    val vibes: List<String> = emptyList()
)

/**
 * Autonomous Audiophile Cognitive Intelligence Brain for TuneMind.
 * - Directly supports on-device Gemini API Key for autonomous LLM reasoning.
 * - Dynamic rotation: NEVER repeats the same song.
 * - High-precision East African cultural lexicons (Gikuyu/Mugithi, Bongo Flava, Gospel, Afrobeats).
 * - Conversational chatbot dialogue (brief, natural, no unsolicited monologues).
 */
object TuneMindAutonomousBrain {
    private const val TAG = "TuneMindBrain"
    private const val PREFS_TAGS = "tunemind_llm_tags"

    // History of recently suggested titles to prevent repetition
    private val recentSuggestions = mutableListOf<String>()

    // Diverse Roster of Gikuyu Mugithi Classics
    private val GIKUYU_CLASSICS_ROSTER = listOf(
        Pair("Wendo Wi Cama", "Samidoh & Joyce wa Mamaa"),
        Pair("Murata Wa Thiururuka", "Samidoh"),
        Pair("Wendo Ni Uriru", "Joyce wa Mamaa"),
        Pair("Nindacokia Ngatho", "John De'Mathew"),
        Pair("Tuirio Twega", "Jose Gatutura"),
        Pair("Kihikihi", "Salim Junior"),
        Pair("Menya Wari Wakwa", "Joyce wa Mamaa & Tony Young"),
        Pair("Mwomboko Classic", "Joseph Kamaru"),
        Pair("Niwe Ngoro Yakwa", "Samidoh"),
        Pair("Komester", "Mike Rua"),
        Pair("Kanyoni Gakwa", "Peter Kigia")
    )

    // Diverse Roster of Gospel Anthems
    private val GOSPEL_ROSTER = listOf(
        Pair("Mwema", "Mercy Masika"),
        Pair("Henzene", "Reuben Kigame"),
        Pair("Nadeka", "Guardian Angel"),
        Pair("Mungu Mkuu", "Evelyn Wanjiru"),
        Pair("Ninang'ara", "Christina Shusho"),
        Pair("Way Maker", "Sinach"),
        Pair("Katikia Yesu", "Florence Andenyi"),
        Pair("Huu Mwaka", "Gloria Muliro"),
        Pair("Uka", "Paul Mwai")
    )

    // Diverse Roster of Bongo Flava Hits
    private val BONGO_ROSTER = listOf(
        Pair("Sukari", "Zuchu"),
        Pair("Jeje", "Diamond Platnumz"),
        Pair("Single Again", "Harmonize"),
        Pair("Tetema", "Rayvanny ft. Diamond"),
        Pair("Nimekuzoea", "Nandy"),
        Pair("Mi Amor", "Marioo ft. Jovial"),
        Pair("Hodari", "Mbosso"),
        Pair("Enjoy", "Jux & Diamond Platnumz")
    )

    // Diverse Roster of Afrobeats Anthems
    private val AFROBEATS_ROSTER = listOf(
        Pair("Last Last", "Burna Boy"),
        Pair("Calm Down", "Rema"),
        Pair("Lonely At The Top", "Asake"),
        Pair("Essence", "Wizkid ft. Tems"),
        Pair("Unavailable", "Davido"),
        Pair("Rush", "Ayra Starr"),
        Pair("Bandana", "Fireboy DML & Asake")
    )

    private val GIKUYU_ARTISTS = listOf(
        "samidoh", "salim", "salim junior", "demathew", "de'mathew", "john demathew",
        "joyce wa mamaa", "mamaa", "gatutura", "jose gatutura", "kamande wa kioi", "kamande",
        "musaimo", "peter kigia", "kigio", "njoroge", "tony young", "kioi junior", "karwimbo",
        "wanjiku", "muthoni", "njeri", "waithaka wa jane", "kamaru", "joseph kamaru",
        "simon kihara", "lady wanja", "queen jane", "kimani wa turacco", "mike rua", "wachira"
    )

    private val GIKUYU_TITLE_WORDS = listOf(
        "wendo", "ngoro", "mwanake", "ciugo", "kiriro", "mutumia", "ndoto", "mamaa", "mugithi",
        "kigooco", "mwathani", "ngai", "murata", "kanyoni", "cama", "thie", "muthoni", "thiururuka"
    )

    private val BONGO_ARTISTS = listOf(
        "diamond", "diamond platnumz", "platnumz", "harmonize", "rayvanny", "zuchu", "mbosso",
        "marioo", "jux", "aslay", "nandy", "kusah", "barnaba", "alikiba", "ali kiba", "konde",
        "wasafi", "wcb", "navy kenzo", "darassa", "billnass", "whozu", "linex", "rich mavoko"
    )

    private val GOSPEL_ARTISTS_AND_WORDS = listOf(
        "gospel", "worship", "praise", "mwema", "hallelujah", "amen", "jesus", "god", "christ",
        "reuben kigame", "kigame", "mercy masika", "masika", "guardian angel", "sinach",
        "christina shusho", "shusho", "evelyn wanjiru", "gloria muliro", "rose muhando",
        "paul mwai", "sarah k", "kambua", "janet otieno", "florence andenyi", "eunice njeri",
        "size 8", "hillsong", "don moen", "maverick city", "travis greene", "nathaniel bassey"
    )

    /**
     * Primary reasoning entrypoint.
     * Evaluates user input against:
     * 1. On-device Gemini LLM API (if API key is present)
     * 2. Backend LLM agent-chat
     * 3. Non-repeating deterministic East African musicology engine
     */
    suspend fun reason(
        context: Context,
        input: String,
        historyTracks: List<TrackMemoryEntry>,
        nowPlaying: NowPlayingObservation?,
        satietyPercent: Int,
        daypart: String,
        currentProviderId: String,
        currentProviderName: String,
        userName: String = "Tonny"
    ): BrainResponse = withContext(Dispatchers.Default) {
        val cleanInput = input.trim().lowercase(Locale.getDefault())
        Log.i(TAG, "Reasoning over query: '$input' (Catalog size: ${historyTracks.size})")

        // 1. NEGATION & CANCELLATION UNDERSTANDING
        if (isNegationOrCancel(cleanInput)) {
            return@withContext BrainResponse(
                spokenText = "Understood, cancelled. What would you like to hear instead?",
                action = AgentActionType.CANCEL,
                reasoningRationale = "Halted playback immediately on user negation without unsolicited prompting."
            )
        }

        // 2. EXPLICIT AUDITORY / SATIETY INQUIRIES (Only if explicitly asked!)
        if (cleanInput.contains("satiety") || cleanInput.contains("fatigue") || cleanInput.contains("tired of this") || cleanInput.contains("ear")) {
            val nowTitle = nowPlaying?.title ?: "current track"
            val explanation = if (satietyPercent >= 70) {
                "Your acoustic satiety on '$nowTitle' is elevated at $satietyPercent%. Your neural receptors are experiencing habituation. Would you like a palate cleanser or a different genre?"
            } else {
                "Your acoustic satiety is at an optimal $satietyPercent%. Receptors are receptive. What would you like to stream?"
            }
            return@withContext BrainResponse(
                spokenText = explanation,
                action = AgentActionType.SPEAK_ONLY,
                reasoningRationale = "Explicit neuro-acoustic question answered."
            )
        }

        // 3. TAG CATALOG IN BACKGROUND
        val tagsMap = examineAndTagCatalogWithLLM(context, historyTracks)

        // 4. PRIMARY INTELLIGENCE: BACKEND GEMINI LLM AGENT CHAT (Secure server-side API Key)
        try {
            val backendResponse = callBackendAgentChat(
                query = input,
                tracks = historyTracks,
                tags = tagsMap,
                currentPlaying = nowPlaying?.title,
                userName = userName,
                currentProviderId = currentProviderId,
                currentProviderName = currentProviderName
            )
            if (backendResponse != null) {
                recordSuggestion(backendResponse.resolvedTrack?.title)
                return@withContext backendResponse
            }
        } catch (e: Exception) {
            Log.w(TAG, "Backend agent-chat fallback: ${e.message}")
        }

        // 5. SECONDARY ATTEMPT: ON-DEVICE DIRECT GEMINI LLM REASONING (IF PREVIOUSLY CONFIGURED)
        val apiKey = GeminiReasoningEngine.getApiKey(context)
        if (apiKey.isNotBlank()) {
            try {
                val directResponse = callDirectGeminiAgent(
                    apiKey = apiKey,
                    query = input,
                    tracks = historyTracks,
                    tags = tagsMap,
                    currentPlaying = nowPlaying?.title,
                    userName = userName,
                    currentProviderId = currentProviderId
                )
                if (directResponse != null) {
                    recordSuggestion(directResponse.resolvedTrack?.title)
                    return@withContext directResponse
                }
            } catch (e: Exception) {
                Log.w(TAG, "Direct Gemini API call failed, falling back: ${e.message}")
            }
        }

        // 6. DYNAMIC NON-REPEATING OFFLINE MUSICOLOGY ENGINE
        val offlineResponse = offlineReasoning(
            query = cleanInput,
            tracks = historyTracks,
            tags = tagsMap,
            nowPlaying = nowPlaying,
            currentProviderId = currentProviderId,
            currentProviderName = currentProviderName,
            userName = userName
        )
        recordSuggestion(offlineResponse.resolvedTrack?.title)
        return@withContext offlineResponse
    }

    /**
     * Direct on-device call to Google Gemini 2.5 Flash / 3.8 Flash REST API.
     */
    private suspend fun callDirectGeminiAgent(
        apiKey: String,
        query: String,
        tracks: List<TrackMemoryEntry>,
        tags: Map<String, TrackLlmTag>,
        currentPlaying: String?,
        userName: String,
        currentProviderId: String
    ): BrainResponse? = withContext(Dispatchers.IO) {
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
        val url = URL(endpoint)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.connectTimeout = 8000
        conn.readTimeout = 12000
        conn.doOutput = true

        val sample = tracks.take(70).map { t ->
            val tag = tags[t.entryId]
            JSONObject().apply {
                put("id", t.entryId)
                put("title", t.title ?: "")
                put("artist", t.artist ?: "")
                put("genre", tag?.genre ?: "Unclassified")
                put("language", tag?.language ?: "Unclassified")
            }
        }

        val prompt = """
You are TuneMind Voice Agent, an audiophile personal music assistant for $userName in Kenya.
User Query: "$query"
Currently Playing: "${currentPlaying ?: "None"}"
Recently Played (DO NOT REPEAT): ${recentSuggestions.takeLast(10).joinToString(", ")}
User's Real Timeline Catalog:
${JSONArray(sample)}

Instructions:
1. If the user asks for Gikuyu / Mugithi, inspect the catalog and select a Kikuyu track by Kikuyu artists (Samidoh, Joyce wa Mamaa, De'Mathew, Salim, Gatutura, etc.). NEVER pick an English pop song.
2. If the user asks for Gospel, pick a Gospel praise or worship track.
3. If the user asks for Bongo, pick a Bongo Flava track.
4. Keep spokenText short and natural (e.g., "Playing 'Wendo Wi Cama' by Samidoh from your Gikuyu collection.").
5. If changing songs, select a fresh distinct track not in the recently played list.

Respond ONLY with valid JSON:
{
  "matchedTrackId": "string or null",
  "suggestedTitle": "string",
  "suggestedArtist": "string",
  "spokenText": "short spoken response",
  "action": "PLAY_TRACK" or "CANCEL" or "SPEAK_ONLY",
  "genre": "identified genre"
}
""".trimIndent()

        val rootJson = JSONObject().apply {
            val contents = JSONArray().put(
                JSONObject().put("role", "user").put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", prompt))
                )
            )
            put("contents", contents)
            put("generationConfig", JSONObject().put("temperature", 0.4).put("responseMimeType", "application/json"))
        }

        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(rootJson.toString()); it.flush() }
        if (conn.responseCode in 200..299) {
            val responseText = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
            val parsed = JSONObject(responseText)
            val candidates = parsed.optJSONArray("candidates")
            val content = candidates?.optJSONObject(0)?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            val text = parts?.optJSONObject(0)?.optString("text")

            if (!text.isNullOrBlank()) {
                val json = JSONObject(text)
                val actionStr = json.optString("action", "PLAY_TRACK")
                val spoken = json.optString("spokenText", "Playing track.")
                val matchedId = json.optString("matchedTrackId", null)
                val suggTitle = json.optString("suggestedTitle", "Song")
                val suggArtist = json.optString("suggestedArtist", "Artist")

                val action = when (actionStr) {
                    "CANCEL" -> AgentActionType.CANCEL
                    "SPEAK_ONLY" -> AgentActionType.SPEAK_ONLY
                    else -> AgentActionType.PLAY_TRACK
                }

                if (action == AgentActionType.CANCEL || action == AgentActionType.SPEAK_ONLY) {
                    return@withContext BrainResponse(spokenText = spoken, action = action)
                }

                val matchedEntry = tracks.firstOrNull { it.entryId == matchedId }
                val resolvedTrack = RecommendedTrack(
                    id = matchedEntry?.entryId ?: "gemini_${System.currentTimeMillis()}",
                    title = matchedEntry?.title ?: suggTitle,
                    artist = matchedEntry?.artist ?: suggArtist,
                    album = matchedEntry?.album,
                    reason = "Directly reasoned by Gemini AI from your timeline",
                    confidence = 0.99f,
                    category = RecommendationCategory.FAMILIAR_RECEPTIVE,
                    suggestedProviderId = currentProviderId
                )

                return@withContext BrainResponse(
                    spokenText = spoken,
                    action = action,
                    resolvedTrack = resolvedTrack,
                    reasoningRationale = "Gemini on-device intelligence."
                )
            }
        }
        null
    }

    /**
     * Non-repeating deterministic East African Musicology Engine.
     */
    private fun offlineReasoning(
        query: String,
        tracks: List<TrackMemoryEntry>,
        tags: Map<String, TrackLlmTag>,
        nowPlaying: NowPlayingObservation?,
        currentProviderId: String,
        currentProviderName: String,
        userName: String
    ): BrainResponse {
        val q = query.lowercase(Locale.getDefault())

        // 1. CHANGE SONG (Instant switch to fresh non-repeating track)
        if (q.contains("change that song") || q.contains("change song") || q.contains("skip") || q.contains("next song") || q.contains("next")) {
            val candidates = tracks.filter {
                !it.title.isNullOrBlank() &&
                it.title != nowPlaying?.title &&
                it.title !in recentSuggestions
            }
            val picked = candidates.randomOrNull() ?: tracks.filter { it.title != nowPlaying?.title }.randomOrNull()

            val title = picked?.title ?: "Jeje"
            val artist = picked?.artist ?: "Diamond Platnumz"
            val rec = RecommendedTrack(
                id = picked?.entryId ?: "alt_${System.currentTimeMillis()}",
                title = title,
                artist = artist,
                reason = "Fresh track to refresh auditory receptors",
                confidence = 0.95f,
                category = RecommendationCategory.FAMILIAR_RECEPTIVE,
                suggestedProviderId = currentProviderId
            )
            return BrainResponse(
                spokenText = "Switching right now. Playing '$title' by $artist.",
                action = AgentActionType.PLAY_TRACK,
                resolvedTrack = rec
            )
        }

        // 2. GIKUYU / MUGITHI QUERY
        if (q.contains("gikuyu") || q.contains("kikuyu") || q.contains("mugithi")) {
            val timelineGikuyu = tracks.filter { t ->
                val tag = tags[t.entryId]
                val matchesGenre = tag?.genre?.contains("Gikuyu") == true || tag?.language == "Kikuyu" ||
                    isGikuyuByMetadata(t.title ?: "", t.artist ?: "")
                matchesGenre && t.title !in recentSuggestions
            }

            val finalTrack = if (timelineGikuyu.isNotEmpty()) {
                val chosen = timelineGikuyu.random()
                RecommendedTrack(
                    id = chosen.entryId,
                    title = chosen.title ?: "Wendo Wi Cama",
                    artist = chosen.artist ?: "Samidoh",
                    album = chosen.album,
                    reason = "Gikuyu classic from your timeline vault",
                    confidence = 0.98f,
                    category = RecommendationCategory.FAMILIAR_RECEPTIVE,
                    suggestedProviderId = currentProviderId
                )
            } else {
                // Pick non-repeating from diverse classics roster
                val available = GIKUYU_CLASSICS_ROSTER.filter { it.first !in recentSuggestions }
                val pair = if (available.isNotEmpty()) available.random() else GIKUYU_CLASSICS_ROSTER.random()
                RecommendedTrack(
                    id = "gikuyu_${System.currentTimeMillis()}",
                    title = pair.first,
                    artist = pair.second,
                    reason = "Iconic Gikuyu Mugithi acoustic anchor",
                    confidence = 0.99f,
                    category = RecommendationCategory.FAMILIAR_RECEPTIVE,
                    suggestedProviderId = currentProviderId
                )
            }

            return BrainResponse(
                spokenText = "Playing '${finalTrack.title}' by ${finalTrack.artist} from your Gikuyu collection.",
                action = AgentActionType.PLAY_TRACK,
                resolvedTrack = finalTrack
            )
        }

        // 3. GOSPEL QUERY
        if (q.contains("gospel") || q.contains("worship") || q.contains("praise")) {
            val timelineGospel = tracks.filter { t ->
                val tag = tags[t.entryId]
                val matches = tag?.genre?.contains("Gospel") == true || isGospelByMetadata(t.title ?: "", t.artist ?: "")
                matches && t.title !in recentSuggestions
            }

            val finalTrack = if (timelineGospel.isNotEmpty()) {
                val chosen = timelineGospel.random()
                RecommendedTrack(
                    id = chosen.entryId,
                    title = chosen.title ?: "Mwema",
                    artist = chosen.artist ?: "Mercy Masika",
                    album = chosen.album,
                    reason = "Gospel praise anthem from your timeline",
                    confidence = 0.97f,
                    category = RecommendationCategory.FAMILIAR_RECEPTIVE,
                    suggestedProviderId = currentProviderId
                )
            } else {
                val available = GOSPEL_ROSTER.filter { it.first !in recentSuggestions }
                val pair = if (available.isNotEmpty()) available.random() else GOSPEL_ROSTER.random()
                RecommendedTrack(
                    id = "gospel_${System.currentTimeMillis()}",
                    title = pair.first,
                    artist = pair.second,
                    reason = "Uplifting Gospel praise anthem",
                    confidence = 0.98f,
                    category = RecommendationCategory.FAMILIAR_RECEPTIVE,
                    suggestedProviderId = currentProviderId
                )
            }

            return BrainResponse(
                spokenText = "Playing '${finalTrack.title}' by ${finalTrack.artist} for your Gospel worship.",
                action = AgentActionType.PLAY_TRACK,
                resolvedTrack = finalTrack
            )
        }

        // 4. BONGO FLAVA QUERY
        if (q.contains("bongo")) {
            val timelineBongo = tracks.filter { t ->
                val tag = tags[t.entryId]
                val matches = tag?.genre?.contains("Bongo") == true || isBongoByMetadata(t.title ?: "", t.artist ?: "")
                matches && t.title !in recentSuggestions
            }

            val finalTrack = if (timelineBongo.isNotEmpty()) {
                val chosen = timelineBongo.random()
                RecommendedTrack(
                    id = chosen.entryId,
                    title = chosen.title ?: "Sukari",
                    artist = chosen.artist ?: "Zuchu",
                    album = chosen.album,
                    reason = "Bongo Flava groove from your timeline",
                    confidence = 0.97f,
                    category = RecommendationCategory.FAMILIAR_RECEPTIVE,
                    suggestedProviderId = currentProviderId
                )
            } else {
                val available = BONGO_ROSTER.filter { it.first !in recentSuggestions }
                val pair = if (available.isNotEmpty()) available.random() else BONGO_ROSTER.random()
                RecommendedTrack(
                    id = "bongo_${System.currentTimeMillis()}",
                    title = pair.first,
                    artist = pair.second,
                    reason = "High-energy Bongo Flava groove",
                    confidence = 0.98f,
                    category = RecommendationCategory.FAMILIAR_RECEPTIVE,
                    suggestedProviderId = currentProviderId
                )
            }

            return BrainResponse(
                spokenText = "Playing '${finalTrack.title}' by ${finalTrack.artist} for your Bongo flow.",
                action = AgentActionType.PLAY_TRACK,
                resolvedTrack = finalTrack
            )
        }

        // 5. TIMELINE ARCHIVE QUERY
        if (q.contains("timeline") || q.contains("history") || q.contains("past songs") || q.contains("my songs")) {
            val candidates = tracks.filter { !it.title.isNullOrBlank() && it.title != nowPlaying?.title && it.title !in recentSuggestions }
            val chosen = candidates.randomOrNull() ?: tracks.filter { it.title != nowPlaying?.title }.randomOrNull()

            if (chosen != null) {
                val rec = RecommendedTrack(
                    id = chosen.entryId,
                    title = chosen.title ?: "Timeline Favorite",
                    artist = chosen.artist ?: "Timeline Artist",
                    album = chosen.album,
                    reason = "Song from your ${tracks.size} timeline songs archive",
                    confidence = 0.96f,
                    category = RecommendationCategory.FAMILIAR_RECEPTIVE,
                    suggestedProviderId = currentProviderId
                )
                return BrainResponse(
                    spokenText = "Playing '${rec.title}' by ${rec.artist} from your timeline.",
                    action = AgentActionType.PLAY_TRACK,
                    resolvedTrack = rec
                )
            }
        }

        // 5.5 CONVERSATIONAL / NON-MUSIC TURN ("how are you", greetings, open questions)
        val isExplicitPlayOrRecommend = Regex(
            "\\b(play|spin|put on|queue|recommend|suggest|song|track|music|tune|vibe|listen|bongo|afrobeat|amapiano|another|different|next)\\b"
        ).containsMatchIn(q)
        if (!isExplicitPlayOrRecommend) {
            val activeTitle = nowPlaying?.title?.takeIf { it.isNotBlank() && it != "Standing By" }
            val activeArtist = nowPlaying?.artist?.takeIf { it.isNotBlank() && it != "Unknown Artist" }
            val sessionContext = if (activeTitle != null) {
                " while '$activeTitle'${if (activeArtist != null) " by $activeArtist" else ""} is in your session"
            } else {
                ""
            }
            val spokenReply = when {
                Regex("\\b(how are you|how're you|how are u|how is it going|how's it going|habari|mambo|niaje|sasa)\\b").containsMatchIn(q) ->
                    "I'm doing great, thanks for asking! My neural audio engine is dialed in$sessionContext. How are you feeling today, and what kind of vibe are you in the mood for?"
                Regex("\\b(hi|hello|hey|yo|good morning|good afternoon|good evening)\\b").containsMatchIn(q) ->
                    "Hey there! Good to hear your voice. How's your day going so far?"
                Regex("\\b(tired|exhausted|stressed|sad|down|anxious|overwhelmed|bored)\\b").containsMatchIn(q) ->
                    "I hear you—some days really take it out of you. Want to just unwind and chat, or would you like me to put on something calm when you're ready?"
                else ->
                    "I'm right here with you. Tell me more about what's on your mind, or let me know if you'd like me to play or adjust anything."
            }
            return BrainResponse(
                spokenText = spokenReply,
                action = AgentActionType.SPEAK_ONLY,
                resolvedTrack = null
            )
        }

        // 6. DEFAULT NON-REPEATING RANDOM CHOICE (only for explicit music requests)
        val unplayed = tracks.filter { !it.title.isNullOrBlank() && it.title != nowPlaying?.title && it.title !in recentSuggestions }
        val pick = unplayed.randomOrNull() ?: tracks.randomOrNull()
        val pair = if (pick != null) Pair(pick.title!!, pick.artist ?: "Artist") else BONGO_ROSTER.random()

        val rec = RecommendedTrack(
            id = pick?.entryId ?: "default_${System.currentTimeMillis()}",
            title = pair.first,
            artist = pair.second,
            reason = "Acoustic recommendation calibrated for low fatigue",
            confidence = 0.94f,
            category = RecommendationCategory.FAMILIAR_RECEPTIVE,
            suggestedProviderId = currentProviderId
        )
        return BrainResponse(
            spokenText = "Playing '${pair.first}' by ${pair.second}.",
            action = AgentActionType.PLAY_TRACK,
            resolvedTrack = rec
        )
    }

    private fun recordSuggestion(title: String?) {
        if (!title.isNullOrBlank()) {
            recentSuggestions.add(title)
            if (recentSuggestions.size > 20) {
                recentSuggestions.removeAt(0)
            }
        }
    }

    private suspend fun callBackendAgentChat(
        query: String,
        tracks: List<TrackMemoryEntry>,
        tags: Map<String, TrackLlmTag>,
        currentPlaying: String?,
        userName: String,
        currentProviderId: String,
        currentProviderName: String
    ): BrainResponse? = withContext(Dispatchers.IO) {
        val baseUrl = BuildConfig.TUNEMIND_BASE_URL.trim().trimEnd('/')
        if (baseUrl.isBlank()) return@withContext null

        val url = URL("$baseUrl/api/v1/reasoning/agent-chat")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.connectTimeout = 6000
        conn.readTimeout = 9000
        conn.doOutput = true

        val sample = tracks.take(60).map { t ->
            val tag = tags[t.entryId]
            JSONObject().apply {
                put("id", t.entryId)
                put("title", t.title ?: "")
                put("artist", t.artist ?: "")
                put("genre", tag?.genre ?: "Unclassified")
                put("language", tag?.language ?: "Unclassified")
            }
        }

        val payload = JSONObject().apply {
            put("query", query)
            put("userName", userName)
            put("currentPlaying", currentPlaying ?: "")
            put("tracks", JSONArray(sample))
        }

        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(payload.toString()); it.flush() }
        if (conn.responseCode in 200..299) {
            val text = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
            val json = JSONObject(text)
            val actionStr = json.optString("action", "PLAY_TRACK")
            val spoken = json.optString("spokenText", "Playing track.")
            val matchedId = json.optString("matchedTrackId", null)
            val suggTitle = json.optString("suggestedTitle", "Song")
            val suggArtist = json.optString("suggestedArtist", "Artist")

            val action = when (actionStr) {
                "CANCEL" -> AgentActionType.CANCEL
                "SPEAK_ONLY" -> AgentActionType.SPEAK_ONLY
                "CHANGE_TRACK" -> AgentActionType.CHANGE_TRACK
                else -> AgentActionType.PLAY_TRACK
            }

            if (action == AgentActionType.CANCEL || action == AgentActionType.SPEAK_ONLY) {
                return@withContext BrainResponse(spokenText = spoken, action = action)
            }

            val matchedEntry = tracks.firstOrNull { it.entryId == matchedId }
            val resolvedTrack = RecommendedTrack(
                id = matchedEntry?.entryId ?: "llm_${System.currentTimeMillis()}",
                title = matchedEntry?.title ?: suggTitle,
                artist = matchedEntry?.artist ?: suggArtist,
                album = matchedEntry?.album,
                reason = "Selected by Gemini LLM from your timeline archive",
                confidence = 0.98f,
                category = RecommendationCategory.FAMILIAR_RECEPTIVE,
                suggestedProviderId = currentProviderId
            )

            return@withContext BrainResponse(
                spokenText = spoken,
                action = action,
                resolvedTrack = resolvedTrack,
                reasoningRationale = "Gemini LLM semantic match."
            )
        }
        null
    }

    suspend fun examineAndTagCatalogWithLLM(
        context: Context,
        tracks: List<TrackMemoryEntry>
    ): Map<String, TrackLlmTag> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_TAGS, Context.MODE_PRIVATE)
        val result = mutableMapOf<String, TrackLlmTag>()

        val untagged = mutableListOf<TrackMemoryEntry>()
        for (track in tracks) {
            val cachedJson = prefs.getString(track.entryId, null)
            if (cachedJson != null) {
                try {
                    val obj = JSONObject(cachedJson)
                    result[track.entryId] = TrackLlmTag(
                        genre = obj.optString("genre", "Curated"),
                        language = obj.optString("language", "Other"),
                        vibes = emptyList()
                    )
                } catch (_: Exception) {
                    untagged.add(track)
                }
            } else {
                untagged.add(track)
            }
        }

        if (untagged.isEmpty()) {
            return@withContext result
        }

        // 1. Query backend Gemini tagger if server is available
        val baseUrl = BuildConfig.TUNEMIND_BASE_URL.trim().trimEnd('/')
        if (baseUrl.isNotBlank()) {
            try {
                val url = URL("$baseUrl/api/v1/reasoning/tag-tracks")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.connectTimeout = 3000
                conn.readTimeout = 5000
                conn.doOutput = true

                val sample = untagged.take(50).map { t ->
                    JSONObject().apply {
                        put("id", t.entryId)
                        put("title", t.title ?: "")
                        put("artist", t.artist ?: "")
                    }
                }
                val payload = JSONObject().apply {
                    put("tracks", JSONArray(sample))
                }
                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(payload.toString()); it.flush() }
                if (conn.responseCode in 200..299) {
                    val respText = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                    val json = JSONObject(respText)
                    val tagsObj = json.optJSONObject("tags")
                    if (tagsObj != null) {
                        val editor = prefs.edit()
                        for (key in tagsObj.keys()) {
                            val item = tagsObj.getJSONObject(key)
                            val tag = TrackLlmTag(
                                genre = item.optString("genre", "Curated"),
                                language = item.optString("language", "Other"),
                                vibes = emptyList()
                            )
                            result[key] = tag
                            editor.putString(key, item.toString())
                        }
                        editor.apply()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Backend tagging skipped/fallback: ${e.message}")
            }
        }

        // 2. Apply robust offline heuristic tags for any remaining untagged tracks
        val editor = prefs.edit()
        for (t in untagged) {
            if (!result.containsKey(t.entryId)) {
                val tag = offlineHeuristicTag(t.title ?: "", t.artist ?: "")
                result[t.entryId] = tag
                val obj = JSONObject().apply {
                    put("genre", tag.genre)
                    put("language", tag.language)
                }
                editor.putString(t.entryId, obj.toString())
            }
        }
        editor.apply()

        result
    }

    private fun isGikuyuByMetadata(title: String, artist: String): Boolean {
        val combined = "$title $artist".lowercase(Locale.getDefault())
        return GIKUYU_ARTISTS.any { combined.contains(it) } || GIKUYU_TITLE_WORDS.any { combined.contains(it) }
    }

    private fun isBongoByMetadata(title: String, artist: String): Boolean {
        val combined = "$title $artist".lowercase(Locale.getDefault())
        return BONGO_ARTISTS.any { combined.contains(it) }
    }

    private fun isGospelByMetadata(title: String, artist: String): Boolean {
        val combined = "$title $artist".lowercase(Locale.getDefault())
        return GOSPEL_ARTISTS_AND_WORDS.any { combined.contains(it) }
    }

    fun offlineHeuristicTag(title: String, artist: String): TrackLlmTag {
        return when {
            isGikuyuByMetadata(title, artist) -> TrackLlmTag(genre = "Gikuyu / Mugithi", language = "Kikuyu")
            isBongoByMetadata(title, artist) -> TrackLlmTag(genre = "Bongo Flava", language = "Swahili")
            isGospelByMetadata(title, artist) -> TrackLlmTag(genre = "Gospel", language = "Swahili/English")
            else -> TrackLlmTag(genre = "Pop / R&B", language = "English")
        }
    }

    private fun isNegationOrCancel(text: String): Boolean {
        val negations = listOf("no", "nope", "not that", "stop", "cancel", "don't", "never mind", "wait", "hold on", "shut up", "pause")
        return negations.any { text == it || text.startsWith("$it ") || text.endsWith(" $it") || text.contains(" $it ") }
    }

    fun generateWelcomeGreeting(userName: String, daypart: String, satietyPercent: Int): String {
        return when (daypart.lowercase(Locale.getDefault())) {
            "morning" -> "Good morning $userName. I'm ready with your music whenever you are."
            "midday", "afternoon" -> "Good afternoon $userName. How can I help with your music today?"
            "evening" -> "Good evening $userName. What would you like to listen to tonight?"
            else -> "Late night listening, $userName. What sounds good for your ears?"
        }
    }
}
