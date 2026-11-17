package com.tunemind.app.domain.vault

import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Shared Mathematical Resting Vault Engine (Android & Backend parity).
 *
 * Formulas:
 * 1. Temporal Satiety Kernel:
 *      S_eff = min(99, round(sum(2^(-dt_h / H(sigma)) * skipMult) * W(sigma) + Burst24h))
 *      where H(sigma) = 48 + 48*sigma hours, W(sigma) = 13 + 12*sigma.
 *      Plays prior to `lastRestoredAtMs` are cleared from active fatigue so graduated tracks
 *      do not immediately re-enter the vault from historical counts.
 * 2. Auto-Entry Threshold:
 *      S_enter(sigma) = round(86 - 22*sigma), or >= bingeCap(sigma) plays in 24h.
 * 3. Adaptive Cooldown Duration:
 *      T_days in [2.0, 7.0] scaled by satiety severity, 7d excess plays, 24h binge density, & sensitivity.
 * 4. Ease-Out Recovery Curve:
 *      u = clamp((now - quarantinedAtMs) / (T_days * DAY_MS), 0, 1)
 *      R(u) = 1 - (1 - u)^1.25  -> reaches 1.0 when elapsed >= T_days.
 * 5. Automatic Graduation:
 *      When u >= 1.0 (R >= 0.999), track graduates back to Fresh with restored baseline timestamp.
 */
object RestingVaultEngine {
    const val DAY_MS: Long = 86_400_000L

    data class TrackFatigueMetrics(
        val effectiveSatiety: Int,
        val freshnessPercent: Int,
        val recentPlays24h: Int,
        val recentPlays72h: Int,
        val recentPlays7d: Int,
        val shouldAutoQuarantine: Boolean,
        val recommendedCooldownDays: Float,
        val entryReason: String
    )

    data class RecoveryComputation(
        val recoveryProgress: Float,
        val cooldownDaysRemaining: Float,
        val freshnessPercent: Int,
        val isGraduated: Boolean,
        val remainingLabel: String
    )

    enum class FreshCategory {
        COMEBACK,
        UNPLAYED_GEM,
        PEAK_FRESH,
        WARMING_UP
    }

    data class FreshReadinessResult(
        val readinessScore: Int,
        val baseFreshnessPercent: Int,
        val category: FreshCategory,
        val freshReason: String
    )

    fun normalizeTrackKey(title: String?, artist: String?): String {
        val t = (title ?: "").trim().lowercase(Locale.ROOT)
        val a = (artist ?: "").trim().lowercase(Locale.ROOT)
        return "$t::$a"
    }

    fun computeEntryThreshold(sensitivity: Float = 0.75f): Int {
        val s = sensitivity.coerceIn(0.2f, 1.0f)
        return (86f - 22f * s).roundToInt()
    }

    fun computeCooldownDurationDays(
        satietyScore: Int,
        recentPlays7d: Int = 3,
        recentPlays24h: Int = 0,
        sensitivity: Float = 0.75f
    ): Float {
        val satiety = satietyScore.coerceIn(25, 100)
        val s = sensitivity.coerceIn(0.2f, 1.0f)
        val excess7d = max(0, recentPlays7d - 2)
        val bingeBonus = if (recentPlays24h >= 3) 0.6f else 0f

        val rawDays =
            1.5f +
                (max(0, satiety - 50) / 50f) * 3.5f +
                min(1.5f, excess7d * 0.35f) +
                (1.2f * s - 0.5f) +
                bingeBonus

        return (rawDays.coerceIn(2.0f, 7.0f) * 10f).roundToInt() / 10f
    }

    fun evaluateTrackFatigue(
        playTimestampsMs: List<Long>,
        listenedRatios: List<Float> = emptyList(),
        sensitivity: Float = 0.75f,
        lastRestoredAtMs: Long = 0L,
        nowMs: Long = System.currentTimeMillis()
    ): TrackFatigueMetrics {
        val s = sensitivity.coerceIn(0.2f, 1.0f)
        val halfLifeHours = (48f + 48f * s).toDouble()
        val weightPerPlay = 13f + 12f * s

        var decayedMass = 0.0
        var recentPlays24h = 0
        var recentPlays72h = 0
        var recentPlays7d = 0
        var earlySkips = 0

        for (i in playTimestampsMs.indices) {
            val ts = playTimestampsMs[i]
            if (ts <= 0L) continue
            if (lastRestoredAtMs > 0L && ts <= lastRestoredAtMs) {
                continue
            }

            val ageMs = max(0L, nowMs - ts)
            val ageHours = ageMs / 3_600_000.0
            val decay = 2.0.pow(-ageHours / halfLifeHours)

            val ratio = listenedRatios.getOrNull(i) ?: 1.0f
            val skipMult = if (ratio in 0.01f..0.34f) 1.25 else 1.0
            if (skipMult > 1.0 && ageHours <= 72.0) {
                earlySkips++
            }

            decayedMass += decay * skipMult
            if (ageMs <= DAY_MS) recentPlays24h++
            if (ageMs <= 3L * DAY_MS) recentPlays72h++
            if (ageMs <= 7L * DAY_MS) recentPlays7d++
        }

        val burstPenalty = if (recentPlays24h >= 2) {
            min(28f, (recentPlays24h - 1) * (6f + 6f * s))
        } else {
            0f
        }

        val effectiveSatiety = (decayedMass.toFloat() * weightPerPlay + burstPenalty)
            .roundToInt()
            .coerceIn(0, 99)
        val freshnessPercent = (100 - effectiveSatiety).coerceIn(6, 99)

        val entryThreshold = computeEntryThreshold(s)
        val bingeCap = if (s >= 0.65f) 3 else 4
        val withinRestoreGrace = lastRestoredAtMs > 0L && (nowMs - lastRestoredAtMs) < 12L * 3_600_000L

        val hitSatietyGate = effectiveSatiety >= entryThreshold && (recentPlays72h >= 2 || recentPlays7d >= 3)
        val hitBingeGate = recentPlays24h >= bingeCap && effectiveSatiety >= 58
        val shouldAutoQuarantine = !withinRestoreGrace && (hitSatietyGate || hitBingeGate)

        val entryReason = when {
            hitBingeGate -> "Binge guard · $recentPlays24h plays in 24h ($effectiveSatiety% satiety)"
            hitSatietyGate && earlySkips >= 2 -> "Fatigue + early skips ($effectiveSatiety% satiety)"
            hitSatietyGate -> "Auto-rested · $recentPlays7d recent plays ($effectiveSatiety% satiety)"
            else -> "Manual Vault Lock"
        }

        val recommendedDays = computeCooldownDurationDays(
            satietyScore = max(effectiveSatiety, 65),
            recentPlays7d = recentPlays7d,
            recentPlays24h = recentPlays24h,
            sensitivity = s
        )

        return TrackFatigueMetrics(
            effectiveSatiety = effectiveSatiety,
            freshnessPercent = freshnessPercent,
            recentPlays24h = recentPlays24h,
            recentPlays72h = recentPlays72h,
            recentPlays7d = recentPlays7d,
            shouldAutoQuarantine = shouldAutoQuarantine,
            recommendedCooldownDays = recommendedDays,
            entryReason = entryReason
        )
    }

    fun computeRecoveryState(
        quarantinedAtMs: Long,
        totalDays: Float,
        baseSatietyScore: Int,
        nowMs: Long = System.currentTimeMillis()
    ): RecoveryComputation {
        val safeTotalDays = (if (totalDays.isFinite() && totalDays > 0f) totalDays else 3.5f).coerceIn(0.5f, 14.0f)
        val elapsedMs = max(0L, nowMs - quarantinedAtMs).toDouble()
        val totalMs = safeTotalDays.toDouble() * DAY_MS.toDouble()
        val u = (elapsedMs / totalMs).coerceIn(0.0, 1.0)

        val recoveryProgress = if (u >= 0.999) {
            1.0f
        } else {
            ((1.0 - (1.0 - u).pow(1.25)).toFloat()).coerceIn(0f, 1f)
        }

        val remainingDaysRaw = max(0f, safeTotalDays * (1f - u.toFloat()))
        val cooldownDaysRemaining = (remainingDaysRaw * 10f).roundToInt() / 10f

        val baseSatiety = baseSatietyScore.coerceIn(40, 99)
        val currentSatiety = max(
            5,
            (baseSatiety.toDouble() * max(0.0, (1.0 - recoveryProgress.toDouble())).pow(0.85)).roundToInt()
        )
        val freshnessPercent = (100 - currentSatiety).coerceIn(8, 99)

        return RecoveryComputation(
            recoveryProgress = recoveryProgress,
            cooldownDaysRemaining = cooldownDaysRemaining,
            freshnessPercent = freshnessPercent,
            isGraduated = u >= 1.0 || recoveryProgress >= 0.999f,
            remainingLabel = formatRemainingCooldown(cooldownDaysRemaining)
        )
    }

    fun formatRemainingCooldown(remainingDays: Float): String {
        val totalHours = max(0, (remainingDays * 24f).roundToInt())
        return when {
            totalHours <= 0 -> "Ready now"
            totalHours < 24 -> "${totalHours}h left"
            else -> {
                val days = totalHours / 24
                val hours = totalHours % 24
                if (hours > 0) "${days}d ${hours}h left" else "${days}d left"
            }
        }
    }

    /**
     * Mathematical Fresh Readiness [6, 100] & tier classification for non-Vault songs.
     * Combines temporal base freshness, dormancy/rediscovery spacing, Vault graduation bonus,
     * and library affinity / completion signals.
     */
    fun computeFreshReadiness(
        baseFreshnessPercent: Int,
        playCount: Int,
        lastPlayedAtMs: Long = 0L,
        isLikedOrSaved: Boolean = false,
        isGraduatedComeback: Boolean = false,
        restedDays: Float = 0f,
        avgCompletionRatio: Float? = null,
        sourceLabel: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): FreshReadinessResult {
        val plays = max(0, playCount)
        val baseFresh = baseFreshnessPercent.coerceIn(6, 99)
        val daysSinceLast = if (lastPlayedAtMs > 0L) {
            max(0.0, (nowMs - lastPlayedAtMs).toDouble() / DAY_MS.toDouble())
        } else {
            0.0
        }

        val dormancyBonus = if (plays == 0) {
            6.0
        } else {
            (ln(1.0 + daysSinceLast) * 4.2).coerceIn(0.0, 10.0)
        }
        val comebackBonus = if (isGraduatedComeback) 10.0 else 0.0
        val affinityBonus = if (isLikedOrSaved) 4.0 else 0.0
        val completionAdj = when {
            avgCompletionRatio == null || avgCompletionRatio <= 0f -> 0.0
            avgCompletionRatio >= 0.75f -> 3.0
            avgCompletionRatio < 0.35f -> -6.0
            else -> 0.0
        }

        val readinessScore = (baseFresh + dormancyBonus + comebackBonus + affinityBonus + completionAdj)
            .roundToInt()
            .coerceIn(6, 100)

        val category: FreshCategory
        val freshReason: String
        when {
            isGraduatedComeback -> {
                category = FreshCategory.COMEBACK
                val restedTxt = if (restedDays > 0f) String.format(Locale.US, "%.1fd", restedDays) else "fully"
                freshReason = "Graduated comeback · Rested $restedTxt"
            }
            plays == 0 -> {
                category = FreshCategory.UNPLAYED_GEM
                val prefix = sourceLabel?.takeIf { it.isNotBlank() }
                    ?: if (isLikedOrSaved) "Liked Song" else "Library Gem"
                freshReason = "$prefix · Untouched"
            }
            baseFresh >= 75 -> {
                category = FreshCategory.PEAK_FRESH
                freshReason = if (daysSinceLast >= 2.0) {
                    "Crisp · ${daysSinceLast.toInt()}d since last play"
                } else {
                    "Low rotation · $plays ${if (plays == 1) "play" else "plays"}"
                }
            }
            else -> {
                category = FreshCategory.WARMING_UP
                freshReason = "Approaching Vault · $baseFresh% fresh"
            }
        }

        return FreshReadinessResult(
            readinessScore = readinessScore,
            baseFreshnessPercent = baseFresh,
            category = category,
            freshReason = freshReason
        )
    }
}
