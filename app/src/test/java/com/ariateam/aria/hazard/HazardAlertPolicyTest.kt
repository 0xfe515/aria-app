package com.ariateam.aria.hazard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HazardAlertPolicyTest {

    private lateinit var policy: HazardAlertPolicy
    private var mockClockMs: Long = 10_000L

    @Before
    fun setUp() {
        mockClockMs = 10_000L
        policy = HazardAlertPolicy(
            confirmationFramesRequired = 2,
            alertCooldownMs = 2_500L,
            staleResultExpiryMs = 1_500L,
            clockMs = { mockClockMs },
        )
    }

    private fun assessment(
        timestampNanos: Long,
        level: HazardLevel,
        corridor: CorridorDirection = CorridorDirection.CENTER,
    ): HazardAssessment = HazardAssessment(
        timestampNanos = timestampNanos,
        level = level,
        activeCorridor = corridor,
        isAlertCandidate = level.priority >= HazardLevel.PATH_BLOCKED.priority,
    )

    @Test
    fun confirmationHysteresis_requiresTwoConsecutiveMatchingFrames() {
        // Frame 1: Candidate NEAR_PATH_HAZARD at capture 9,900ms (100ms old)
        val capture1 = (mockClockMs - 100L) * 1_000_000L
        val d1 = policy.evaluate(assessment(capture1, HazardLevel.NEAR_PATH_HAZARD), mockClockMs, mockClockMs * 1_000_000L)
        assertTrue("Frame 1 should be suppressed (waiting for confirmation)", d1 is AlertDecision.Suppressed)

        // Advance clock by 100ms to 10,100ms
        mockClockMs += 100L
        val capture2 = (mockClockMs - 100L) * 1_000_000L
        val d2 = policy.evaluate(assessment(capture2, HazardLevel.NEAR_PATH_HAZARD), mockClockMs, mockClockMs * 1_000_000L)
        assertTrue("Frame 2 matching candidate should FIRE!", d2 is AlertDecision.Fire)
    }

    @Test
    fun duplicateOrOutOfOrderTimestamps_areSuppressedWithoutCounting() {
        val capture1 = (mockClockMs - 100L) * 1_000_000L
        policy.evaluate(assessment(capture1, HazardLevel.PATH_BLOCKED), mockClockMs, mockClockMs * 1_000_000L)

        // Duplicate timestamp
        val dup = policy.evaluate(assessment(capture1, HazardLevel.PATH_BLOCKED), mockClockMs, mockClockMs * 1_000_000L)
        assertTrue("Duplicate frame must be suppressed", dup is AlertDecision.Suppressed)

        // Older timestamp
        val older = policy.evaluate(assessment(capture1 - 10_000_000L, HazardLevel.PATH_BLOCKED), mockClockMs, mockClockMs * 1_000_000L)
        assertTrue("Older frame must be suppressed", older is AlertDecision.Suppressed)
    }

    @Test
    fun staleFrames_areSuppressedAndResetHysteresis() {
        // Stale frame: capture age is 2,000ms (> 1,500ms limit)
        val staleCapture = (mockClockMs - 2_000L) * 1_000_000L
        val d = policy.evaluate(assessment(staleCapture, HazardLevel.NEAR_PATH_HAZARD), mockClockMs, mockClockMs * 1_000_000L)
        assertTrue("Stale result must be suppressed", d is AlertDecision.Suppressed)
    }

    @Test
    fun cooldown_suppressesRepeatsWithinCooldownAndAllowsAfter() {
        // Confirm and fire first alert
        var capture = (mockClockMs - 50L) * 1_000_000L
        policy.evaluate(assessment(capture, HazardLevel.PATH_BLOCKED), mockClockMs, mockClockMs * 1_000_000L)

        mockClockMs += 100L
        capture = (mockClockMs - 50L) * 1_000_000L
        val fired1 = policy.evaluate(assessment(capture, HazardLevel.PATH_BLOCKED), mockClockMs, mockClockMs * 1_000_000L)
        assertTrue(fired1 is AlertDecision.Fire)

        // Repeat frame after 500ms (< 2,500ms cooldown)
        mockClockMs += 500L
        capture = (mockClockMs - 50L) * 1_000_000L
        val repeated = policy.evaluate(assessment(capture, HazardLevel.PATH_BLOCKED), mockClockMs, mockClockMs * 1_000_000L)
        assertTrue("Identical risk within cooldown must be suppressed", repeated is AlertDecision.Suppressed)

        // Advance past cooldown (2,600ms later)
        mockClockMs += 2_600L
        capture = (mockClockMs - 50L) * 1_000_000L
        val postCooldown = policy.evaluate(assessment(capture, HazardLevel.PATH_BLOCKED), mockClockMs, mockClockMs * 1_000_000L)
        assertTrue("After cooldown elapses, alert should FIRE", postCooldown is AlertDecision.Fire)
    }

    @Test
    fun escalationOrCorridorChange_bypassesCooldown() {
        // Confirm and fire PATH_BLOCKED
        var capture = (mockClockMs - 50L) * 1_000_000L
        policy.evaluate(assessment(capture, HazardLevel.PATH_BLOCKED, CorridorDirection.CENTER), mockClockMs, mockClockMs * 1_000_000L)
        mockClockMs += 100L
        capture = (mockClockMs - 50L) * 1_000_000L
        policy.evaluate(assessment(capture, HazardLevel.PATH_BLOCKED, CorridorDirection.CENTER), mockClockMs, mockClockMs * 1_000_000L)

        // 300ms later: risk escalates to NEAR_PATH_HAZARD (priority 3 > 2)
        mockClockMs += 300L
        capture = (mockClockMs - 50L) * 1_000_000L
        policy.evaluate(assessment(capture, HazardLevel.NEAR_PATH_HAZARD, CorridorDirection.CENTER), mockClockMs, mockClockMs * 1_000_000L)

        mockClockMs += 100L
        capture = (mockClockMs - 50L) * 1_000_000L
        val escalated = policy.evaluate(assessment(capture, HazardLevel.NEAR_PATH_HAZARD, CorridorDirection.CENTER), mockClockMs, mockClockMs * 1_000_000L)
        assertTrue("Escalation should FIRE immediately upon confirmation", escalated is AlertDecision.Fire)
        assertEquals("ESCALATION", (escalated as AlertDecision.Fire).reason)
    }
}
