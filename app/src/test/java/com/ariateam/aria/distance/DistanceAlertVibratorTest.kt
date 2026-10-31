package com.ariateam.aria.distance

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests the temporal cooldown and immediate escalation logic of production [DistanceAlertPolicy].
 */
class DistanceAlertVibratorTest {

    @Test
    fun testUrgentTriggersImmediatelyAndRespectsCooldown() {
        val policy = DistanceAlertPolicy()
        var time = 1000L

        // First urgent triggers immediately
        val decision1 = policy.evaluate(DistanceRisk.URGENT, time)
        assertEquals(VibrationDecision.URGENT, decision1)

        // Rapid subsequent call within cooldown is suppressed
        time += 500L // Less than 1200ms
        val decision2 = policy.evaluate(DistanceRisk.URGENT, time)
        assertEquals(VibrationDecision.NONE, decision2)

        // After cooldown passes, next urgent fires
        time += 700L // Total 1200ms elapsed
        val decision3 = policy.evaluate(DistanceRisk.URGENT, time)
        assertEquals(VibrationDecision.URGENT, decision3)
    }

    @Test
    fun testCautionTriggersAndRespectsCooldown() {
        val policy = DistanceAlertPolicy()
        var time = 1000L

        // From UNKNOWN to CAUTION: triggers immediately
        val decision1 = policy.evaluate(DistanceRisk.CAUTION, time)
        assertEquals(VibrationDecision.CAUTION, decision1)

        // Within caution cooldown (2500ms)
        time += 1000L
        val decision2 = policy.evaluate(DistanceRisk.CAUTION, time)
        assertEquals(VibrationDecision.NONE, decision2)

        // After cooldown
        time += 1500L // Total 2500ms
        val decision3 = policy.evaluate(DistanceRisk.CAUTION, time)
        assertEquals(VibrationDecision.CAUTION, decision3)
    }

    @Test
    fun testImmediateEscalationFromCautionToUrgentBypassesCooldown() {
        val policy = DistanceAlertPolicy()
        var time = 1000L

        // Start with CAUTION
        val d1 = policy.evaluate(DistanceRisk.CAUTION, time)
        assertEquals(VibrationDecision.CAUTION, d1)

        // Only 200ms later, escalate to URGENT
        time += 200L
        val d2 = policy.evaluate(DistanceRisk.URGENT, time)
        assertEquals("Escalation to URGENT must bypass caution cooldown immediately", VibrationDecision.URGENT, d2)
    }

    @Test
    fun testClearAndUnknownDoNotVibrate() {
        val policy = DistanceAlertPolicy()
        var time = 1000L

        assertEquals(VibrationDecision.NONE, policy.evaluate(DistanceRisk.CLEAR, time))
        assertEquals(VibrationDecision.NONE, policy.evaluate(DistanceRisk.UNKNOWN, time))
    }

    @Test
    fun testResetClearsTimestampsAndRisk() {
        val policy = DistanceAlertPolicy()
        var time = 1000L

        policy.evaluate(DistanceRisk.URGENT, time)
        policy.reset()

        assertEquals(DistanceRisk.UNKNOWN, policy.lastRisk)
        assertEquals(0L, policy.lastVibrateTimestamp)
    }
}
