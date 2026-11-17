package com.tunemind.app.domain.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestingVaultEngineTest {
    private val now = 1_760_000_000_000L

    @Test
    fun spacedHistoricalPlaysStayFreshAndDoNotAutoEnterVault() {
        val oldPlays = listOf(10, 13, 16, 19, 22).map { now - it * RestingVaultEngine.DAY_MS }
        val metrics = RestingVaultEngine.evaluateTrackFatigue(
            playTimestampsMs = oldPlays,
            sensitivity = 0.75f,
            nowMs = now
        )
        assertEquals(0, metrics.recentPlays24h)
        assertTrue(metrics.effectiveSatiety < RestingVaultEngine.computeEntryThreshold(0.75f))
        assertTrue(metrics.freshnessPercent > 65)
        assertFalse(metrics.shouldAutoQuarantine)
    }

    @Test
    fun bingeIn24hAutoEntersVaultAndScalesDuration() {
        val bingePlays = listOf(
            now - 1L * 3_600_000L,
            now - 3L * 3_600_000L,
            now - 5L * 3_600_000L,
            now - 28L * 3_600_000L
        )
        val metrics = RestingVaultEngine.evaluateTrackFatigue(
            playTimestampsMs = bingePlays,
            sensitivity = 0.75f,
            nowMs = now
        )
        assertEquals(3, metrics.recentPlays24h)
        assertTrue(metrics.shouldAutoQuarantine)
        assertTrue(metrics.recommendedCooldownDays in 3.5f..7.0f)
    }

    @Test
    fun recoveryCurveProgressesAndGraduatesCleanly() {
        val qTime = now - 2L * RestingVaultEngine.DAY_MS
        val mid = RestingVaultEngine.computeRecoveryState(qTime, 4.0f, 88, now)
        assertFalse(mid.isGraduated)
        assertTrue(mid.recoveryProgress > 0.5f)
        assertTrue(mid.cooldownDaysRemaining in 1.9f..2.1f)

        val done = RestingVaultEngine.computeRecoveryState(now - (4.1 * RestingVaultEngine.DAY_MS).toLong(), 4.0f, 88, now)
        assertTrue(done.isGraduated)
        assertEquals(1.0f, done.recoveryProgress, 0.001f)
        assertEquals(0.0f, done.cooldownDaysRemaining, 0.001f)
    }

    @Test
    fun postRestoreCutoffResetsHistoricalFatigue() {
        val restoredAt = now - 2L * 3_600_000L
        val oldPlays = listOf(now - 10L * 3_600_000L, now - 12L * 3_600_000L, now - 14L * 3_600_000L)
        val metrics = RestingVaultEngine.evaluateTrackFatigue(
            playTimestampsMs = oldPlays,
            sensitivity = 0.9f,
            lastRestoredAtMs = restoredAt,
            nowMs = now
        )
        assertEquals(0, metrics.effectiveSatiety)
        assertEquals(99, metrics.freshnessPercent)
        assertFalse(metrics.shouldAutoQuarantine)
    }
}
