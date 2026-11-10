package com.tunemind.app.domain.ai

import android.util.Log
import com.tunemind.app.domain.execution.ReasoningResponse
import com.tunemind.app.domain.execution.OsExecutionCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class ReasoningApiClient(private val baseUrl: String) {
    private val client = OkHttpClient()
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    suspend fun evaluateSatiety(
        transcript: String,
        currentTrackTitle: String,
        currentTrackArtist: String,
        sourcePlayer: String
    ): ReasoningResponse? = withContext(Dispatchers.IO) {
        try {
            // Build the TuneMindTelemetryContext JSON
            val cleanBaseUrl = baseUrl.trim().trimEnd('/')
            val jsonBody = JSONObject().apply {
                put("timestamp_utc", java.time.Instant.now().toString())
                put("circadian_daypart", "Midday") // Can be dynamically calculated later
                put("current_track", JSONObject().apply {
                    put("title", currentTrackTitle)
                    put("artist", currentTrackArtist)
                    put("source_player", sourcePlayer)
                })
                put("user_command", transcript)
                put("rolling_history", JSONArray()) // Add actual history if available in context
                put("current_satiety_percentage", 50) // Mock baseline for now
            }

            val request = Request.Builder()
                .url("$cleanBaseUrl/api/v1/reasoning/evaluate-satiety")
                .post(jsonBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val json = JSONObject(responseBody)
                val osCommand = json.getJSONObject("os_execution_command")

                val tagsArray = json.optJSONArray("database_tags")
                val tagsList = mutableListOf<String>()
                if (tagsArray != null) {
                    for (i in 0 until tagsArray.length()) {
                        tagsList.add(tagsArray.getString(i))
                    }
                }
                
                val voiceAgentAudio = if (json.has("voice_agent_audio_base64") && !json.isNull("voice_agent_audio_base64")) {
                    json.optString("voice_agent_audio_base64").takeIf { it.isNotBlank() }
                } else {
                    null
                }
                
                return@withContext ReasoningResponse(
                    calculated_satiety_percentage = json.optInt("calculated_satiety_percentage"),
                    flow_state_analysis = json.optString("flow_state_analysis"),
                    voice_agent_tts = json.optString("voice_agent_tts"),
                    os_execution_command = OsExecutionCommand(
                        intent = osCommand.getString("intent"),
                        target_player = osCommand.getString("target_player"),
                        search_query = osCommand.getString("search_query"),
                        suggested_bpm_target = if (osCommand.has("suggested_bpm_target")) osCommand.optInt("suggested_bpm_target") else null
                    ),
                    database_tags = tagsList,
                    voice_agent_audio_base64 = voiceAgentAudio
                )
            } else {
                Log.e("ReasoningApiClient", "Backend error: ${response.code} - $responseBody")
            }
        } catch (e: Exception) {
            Log.e("ReasoningApiClient", "Network failure", e)
        }
        return@withContext null
    }
}
