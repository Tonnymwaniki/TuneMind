package com.tunemind.app.domain.ai

import org.json.JSONObject
import java.util.UUID

/**
 * High-fidelity cognitive telemetry reasoning report synthesized by Google Gemini.
 * Evaluates real-time listening habits, track repetition velocity, auditory receptor fatigue,
 * and cognitive flow state directly from local Room database telemetry.
 */
data class ReasoningReport(
    val id: String = UUID.randomUUID().toString(),
    val generatedAtMs: Long = System.currentTimeMillis(),
    val analyzedEventCount: Int = 0,
    val topSourceApp: String = "Spotify",
    val receptorPotencyScore: Int = 85, // 0 - 100%
    val fatigueRiskLevel: String = "OPTIMAL_FLOW", // OPTIMAL_FLOW, SENSITIZED, ELEVATED_BURNOUT
    val fatigueRiskLabel: String = "Optimal Cognitive Flow",
    val flowStateDescription: String = "Auditory receptor recovery is balanced with dopamine engagement.",
    val acousticShiftRecommendation: String = "Maintain moderate tempo (95-115 BPM) with wide dynamic range.",
    val steerAction: String = "Continue current listening curve; receptor fatigue remains low.",
    val observedTrackContext: String? = null,
    val isGeminiGenerated: Boolean = true
) {
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("generatedAtMs", generatedAtMs)
        obj.put("analyzedEventCount", analyzedEventCount)
        obj.put("topSourceApp", topSourceApp)
        obj.put("receptorPotencyScore", receptorPotencyScore)
        obj.put("fatigueRiskLevel", fatigueRiskLevel)
        obj.put("fatigueRiskLabel", fatigueRiskLabel)
        obj.put("flowStateDescription", flowStateDescription)
        obj.put("acousticShiftRecommendation", acousticShiftRecommendation)
        obj.put("steerAction", steerAction)
        obj.put("observedTrackContext", observedTrackContext ?: "")
        obj.put("isGeminiGenerated", isGeminiGenerated)
        return obj.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): ReasoningReport? {
            if (jsonStr.isBlank()) return null
            return try {
                val obj = JSONObject(jsonStr)
                ReasoningReport(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    generatedAtMs = obj.optLong("generatedAtMs", System.currentTimeMillis()),
                    analyzedEventCount = obj.optInt("analyzedEventCount", 0),
                    topSourceApp = obj.optString("topSourceApp", "Spotify"),
                    receptorPotencyScore = obj.optInt("receptorPotencyScore", 85),
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
                    observedTrackContext = obj.optString("observedTrackContext", "").takeIf { it.isNotBlank() },
                    isGeminiGenerated = obj.optBoolean("isGeminiGenerated", true)
                )
            } catch (_: Exception) {
                null
            }
        }

        fun createHeuristicBaseline(
            eventCount: Int,
            topSource: String,
            satietyPercent: Int,
            currentTrack: String? = null
        ): ReasoningReport {
            val isHighFatigue = satietyPercent >= 75
            val isModerate = satietyPercent in 45..74
            val potency = (100 - satietyPercent).coerceIn(15, 95)

            val level = when {
                isHighFatigue -> "ELEVATED_BURNOUT"
                isModerate -> "SENSITIZED"
                else -> "OPTIMAL_FLOW"
            }

            val label = when {
                isHighFatigue -> "Auditory Receptor Burnout"
                isModerate -> "Early Acoustic Saturation"
                else -> "Optimal Cognitive Flow"
            }

            val description = when {
                isHighFatigue -> "Elevated repetition loops detected in telemetry. Auditory hair cells and dopamine receptors exhibit heightened adaptation."
                isModerate -> "Steady acoustic stimulation with mild habituation. Listening curve remains engaging but approaching fatigue inflection point."
                else -> "Auditory receptors operating in peak receptive zone. Signal variety from $topSource is sustaining cognitive alertness."
            }

            val shift = when {
                isHighFatigue -> "Insert 15-minute acoustic silence or pivot to minimalist ambient textures under 70 BPM."
                isModerate -> "Introduce dynamic tempo variations or transition to instrumental frequency palettes."
                else -> "Retain current genre flow; acoustic receptors are primed for continued focus."
            }

            val action = when {
                isHighFatigue -> if (currentTrack != null) "Rest '$currentTrack' in Cooldown Vault" else "Trigger 15m Reset Interval"
                isModerate -> "Explore a Curated Palate Cleanser"
                else -> "Maintain Deep Flow"
            }

            return ReasoningReport(
                analyzedEventCount = eventCount,
                topSourceApp = topSource,
                receptorPotencyScore = potency,
                fatigueRiskLevel = level,
                fatigueRiskLabel = label,
                flowStateDescription = description,
                acousticShiftRecommendation = shift,
                steerAction = action,
                observedTrackContext = currentTrack,
                isGeminiGenerated = false
            )
        }
    }
}
