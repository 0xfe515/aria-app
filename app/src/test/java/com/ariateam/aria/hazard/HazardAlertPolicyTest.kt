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

    private fun structuredAssessment(
        timestampNanos: Long,
        severity: AlertSeverity,
        cause: HazardCause,
        trackId: Int? = null,
        corridor: CorridorDirection = CorridorDirection.CENTER,
        messageKorean: String = "테스트 메시지",
    ): HazardAssessment = HazardAssessment(
        timestampNanos = timestampNanos,
        level = when (severity) {
            AlertSeverity.WARNING -> HazardLevel.PATH_BLOCKED
            AlertSeverity.CAUTION -> HazardLevel.PATH_CAUTION
            else -> HazardLevel.CLEAR_UNWARNED
        },
        activeCorridor = corridor,
        isAlertCandidate = severity.rank >= AlertSeverity.CAUTION.rank,
        severity = severity,
        cause = cause,
        trackId = trackId,
        messageKorean = messageKorean,
    )

    @Test
    fun confirmationHysteresis_requiresTwoConsecutiveMatchingFrames() {
        // Frame 1: Candidate NEAR_PATH_HAZARD at capture 9,900ms (100ms old)
        val capture1 = (mockClockMs - 100L) * 1_000_000L
        val d1 = policy.evaluate(assessment(capture1, HazardLevel.NEAR_PATH_HAZARD), mockClockMs, mockClockMs * 1_000_000L)
        assertTrue("Frame 1 should be suppressed (waiting for confirmation)", d1 is AlertDecision.Suppressed)
        assertEquals(AlertSuppressionReason.CONFIRMATION_PENDING, (d1 as AlertDecision.Suppressed).reason)

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
    fun confirmationResetsWhenCaptureGapExceeds500Milliseconds() {
        val firstCapture = (mockClockMs - 50L) * 1_000_000L
        val first = policy.evaluate(
            assessment(firstCapture, HazardLevel.PATH_BLOCKED),
            mockClockMs,
            mockClockMs * 1_000_000L,
        )
        assertTrue(first is AlertDecision.Suppressed)

        mockClockMs += 600L
        val afterGapCapture = (mockClockMs - 50L) * 1_000_000L
        val afterGap = policy.evaluate(
            assessment(afterGapCapture, HazardLevel.PATH_BLOCKED),
            mockClockMs,
            mockClockMs * 1_000_000L,
        )
        assertTrue("A long capture gap starts a new confirmation run", afterGap is AlertDecision.Suppressed)

        mockClockMs += 100L
        val confirmedCapture = (mockClockMs - 50L) * 1_000_000L
        val confirmed = policy.evaluate(
            assessment(confirmedCapture, HazardLevel.PATH_BLOCKED),
            mockClockMs,
            mockClockMs * 1_000_000L,
        )
        assertTrue("Two new consecutive frames confirm after the gap", confirmed is AlertDecision.Fire)
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
        val postCooldownPending = policy.evaluate(assessment(capture, HazardLevel.PATH_BLOCKED), mockClockMs, mockClockMs * 1_000_000L)
        assertTrue("A capture gap over 500ms requires fresh confirmation", postCooldownPending is AlertDecision.Suppressed)

        mockClockMs += 100L
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

    @Test
    fun caution_requiresThreeConsecutiveFrames() {
        val capture1 = (mockClockMs - 100L) * 1_000_000L
        val d1 = policy.evaluate(
            structuredAssessment(capture1, AlertSeverity.CAUTION, HazardCause.C1_OBJECT_IN_PATH, trackId = 1),
            mockClockMs,
            mockClockMs * 1_000_000L,
        )
        assertTrue("Frame 1 should be suppressed (waiting for confirmation)", d1 is AlertDecision.Suppressed)
        assertEquals(AlertSuppressionReason.CONFIRMATION_PENDING, (d1 as AlertDecision.Suppressed).reason)

        mockClockMs += 100L
        val capture2 = (mockClockMs - 100L) * 1_000_000L
        val d2 = policy.evaluate(
            structuredAssessment(capture2, AlertSeverity.CAUTION, HazardCause.C1_OBJECT_IN_PATH, trackId = 1),
            mockClockMs,
            mockClockMs * 1_000_000L,
        )
        assertTrue("Frame 2 should still be suppressed", d2 is AlertDecision.Suppressed)
        assertEquals(AlertSuppressionReason.CONFIRMATION_PENDING, (d2 as AlertDecision.Suppressed).reason)

        mockClockMs += 100L
        val capture3 = (mockClockMs - 100L) * 1_000_000L
        val d3 = policy.evaluate(
            structuredAssessment(capture3, AlertSeverity.CAUTION, HazardCause.C1_OBJECT_IN_PATH, trackId = 1),
            mockClockMs,
            mockClockMs * 1_000_000L,
        )
        assertTrue("Frame 3 should FIRE for CAUTION!", d3 is AlertDecision.Fire)
        assertEquals("ESCALATION", (d3 as AlertDecision.Fire).reason)
    }

    @Test
    fun caution_uses6000msCooldown_suppressesRepeatsWithin6000msAndFiresAfter() {
        for (i in 0 until 3) {
            val capture = (mockClockMs - 50L) * 1_000_000L
            val res = policy.evaluate(
                structuredAssessment(capture, AlertSeverity.CAUTION, HazardCause.C1_OBJECT_IN_PATH, trackId = 1),
                mockClockMs,
                mockClockMs * 1_000_000L,
            )
            if (i < 2) assertTrue(res is AlertDecision.Suppressed)
            else assertTrue(res is AlertDecision.Fire)
            if (i < 2) mockClockMs += 100L
        }

        mockClockMs += 200L
        val captureWithinCooldown = (mockClockMs - 50L) * 1_000_000L
        val repeated = policy.evaluate(
            structuredAssessment(captureWithinCooldown, AlertSeverity.CAUTION, HazardCause.C1_OBJECT_IN_PATH, trackId = 1),
            mockClockMs,
            mockClockMs * 1_000_000L,
        )
        assertTrue("Identical CAUTION within 6,000ms must be suppressed with COOLDOWN", repeated is AlertDecision.Suppressed)
        assertEquals(AlertSuppressionReason.COOLDOWN, (repeated as AlertDecision.Suppressed).reason)

        mockClockMs += 5_900L
        for (i in 0 until 3) {
            val capture = (mockClockMs - 50L) * 1_000_000L
            val res = policy.evaluate(
                structuredAssessment(capture, AlertSeverity.CAUTION, HazardCause.C1_OBJECT_IN_PATH, trackId = 1),
                mockClockMs,
                mockClockMs * 1_000_000L,
            )
            if (i < 2) {
                assertTrue(res is AlertDecision.Suppressed)
            } else {
                assertTrue("After 6,000ms cooldown, CAUTION alert should FIRE", res is AlertDecision.Fire)
                assertEquals("COOLDOWN_ELAPSED", (res as AlertDecision.Fire).reason)
            }
            if (i < 2) mockClockMs += 100L
        }
    }

    @Test
    fun caution_escalatesImmediatelyToWarningWithinCooldownOnTwoFrames() {
        for (i in 0 until 3) {
            val capture = (mockClockMs - 50L) * 1_000_000L
            policy.evaluate(
                structuredAssessment(capture, AlertSeverity.CAUTION, HazardCause.C1_OBJECT_IN_PATH, trackId = 1),
                mockClockMs,
                mockClockMs * 1_000_000L,
            )
            if (i < 2) mockClockMs += 100L
        }

        mockClockMs += 300L
        val capWarn1 = (mockClockMs - 50L) * 1_000_000L
        val d1 = policy.evaluate(
            structuredAssessment(capWarn1, AlertSeverity.WARNING, HazardCause.W2_OBJECT_IMMINENT, trackId = 1),
            mockClockMs,
            mockClockMs * 1_000_000L,
        )
        assertTrue("WARNING frame 1 is confirmation pending", d1 is AlertDecision.Suppressed)

        mockClockMs += 100L
        val capWarn2 = (mockClockMs - 50L) * 1_000_000L
        val d2 = policy.evaluate(
            structuredAssessment(capWarn2, AlertSeverity.WARNING, HazardCause.W2_OBJECT_IMMINENT, trackId = 1),
            mockClockMs,
            mockClockMs * 1_000_000L,
        )
        assertTrue("WARNING should FIRE immediately upon 2-frame confirmation even within cooldown", d2 is AlertDecision.Fire)
        assertEquals("ESCALATION", (d2 as AlertDecision.Fire).reason)
    }

    @Test
    fun deduplicationByCauseAndTrackId_firesWithinCooldownOnNewTrackOrNewCause() {
        for (i in 0 until 3) {
            val capture = (mockClockMs - 50L) * 1_000_000L
            policy.evaluate(
                structuredAssessment(capture, AlertSeverity.CAUTION, HazardCause.C1_OBJECT_IN_PATH, trackId = 1),
                mockClockMs,
                mockClockMs * 1_000_000L,
            )
            if (i < 2) mockClockMs += 100L
        }

        mockClockMs += 500L
        for (i in 0 until 3) {
            val capture = (mockClockMs - 50L) * 1_000_000L
            val res = policy.evaluate(
                structuredAssessment(capture, AlertSeverity.CAUTION, HazardCause.C1_OBJECT_IN_PATH, trackId = 2),
                mockClockMs,
                mockClockMs * 1_000_000L,
            )
            if (i < 2) {
                assertTrue(res is AlertDecision.Suppressed)
            } else {
                assertTrue("New trackId should FIRE within cooldown", res is AlertDecision.Fire)
                assertEquals("CORRIDOR_CHANGED", (res as AlertDecision.Fire).reason)
            }
            if (i < 2) mockClockMs += 100L
        }

        mockClockMs += 500L
        for (i in 0 until 3) {
            val capture = (mockClockMs - 50L) * 1_000_000L
            val res = policy.evaluate(
                structuredAssessment(capture, AlertSeverity.CAUTION, HazardCause.C2_VEHICLE_NEARBY, trackId = 2),
                mockClockMs,
                mockClockMs * 1_000_000L,
            )
            if (i < 2) {
                assertTrue(res is AlertDecision.Suppressed)
            } else {
                assertTrue("New cause should FIRE within cooldown", res is AlertDecision.Fire)
                assertEquals("CORRIDOR_CHANGED", (res as AlertDecision.Fire).reason)
            }
            if (i < 2) mockClockMs += 100L
        }
    }

    @Test
    fun releaseFrames_clearsActiveTargetAfterInactiveFrames() {
        for (i in 0 until 3) {
            val capture = (mockClockMs - 50L) * 1_000_000L
            policy.evaluate(
                structuredAssessment(capture, AlertSeverity.CAUTION, HazardCause.C1_OBJECT_IN_PATH, trackId = 1),
                mockClockMs,
                mockClockMs * 1_000_000L,
            )
            if (i < 2) mockClockMs += 100L
        }

        for (i in 0 until 6) {
            mockClockMs += 100L
            val capture = (mockClockMs - 50L) * 1_000_000L
            val res = policy.evaluate(
                structuredAssessment(capture, AlertSeverity.NONE, HazardCause.NONE),
                mockClockMs,
                mockClockMs * 1_000_000L,
            )
            assertTrue("Clear frames must be suppressed", res is AlertDecision.Suppressed)
            assertEquals(AlertSuppressionReason.NOT_A_HAZARD_CANDIDATE, (res as AlertDecision.Suppressed).reason)
        }

        mockClockMs += 100L
        for (i in 0 until 3) {
            val capture = (mockClockMs - 50L) * 1_000_000L
            val res = policy.evaluate(
                structuredAssessment(capture, AlertSeverity.CAUTION, HazardCause.C1_OBJECT_IN_PATH, trackId = 1),
                mockClockMs,
                mockClockMs * 1_000_000L,
            )
            if (i < 2) {
                assertTrue(res is AlertDecision.Suppressed)
            } else {
                assertTrue("Released target should FIRE as fresh alert", res is AlertDecision.Fire)
                assertEquals("ESCALATION", (res as AlertDecision.Fire).reason)
            }
            if (i < 2) mockClockMs += 100L
        }
    }
}
