package com.ariateam.aria.hazard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MotionRapidTurnPolicyTest {

    private lateinit var directionPolicy: MotionRapidTurnPolicy
    private lateinit var groundPolicy: MotionRapidTurnPolicy

    @Before
    fun setUp() {
        directionPolicy = MotionRapidTurnPolicy.forDirection()
        groundPolicy = MotionRapidTurnPolicy.forGroundContact()
    }

    @Test
    fun initialState_isNotRestricted() {
        assertFalse(directionPolicy.isRestricted)
        assertEquals(0f, directionPolicy.rawRateDegPerSec, 1e-4f)
        assertEquals(0f, directionPolicy.smoothedRateDegPerSec, 1e-4f)
        assertNull(directionPolicy.lastTransitionReason)
    }

    @Test
    fun briefModerateSpike_doesNotRestrict() {
        var t = 1_000_000_000L
        // 5 samples of 50 deg/s (duration 50ms < 150ms entry sustain)
        for (i in 0 until 5) {
            t += 10_000_000L
            val restricted = directionPolicy.update(t, 50.0f)
            assertFalse("Brief moderate spike must not restrict at t=${i * 10}ms", restricted)
        }
        assertFalse(directionPolicy.isRestricted)

        // Quiet tail (25 samples = 250ms of quiet): verify no delayed restriction triggers
        for (i in 0 until 25) {
            t += 10_000_000L
            val restricted = directionPolicy.update(t, 0.0f)
            assertFalse("Quiet tail after brief spike must never restrict", restricted)
        }
        assertFalse("Final state after quiet tail must not be restricted", directionPolicy.isRestricted)
        assertNull(directionPolicy.lastTransitionReason)
    }

    @Test
    fun sustainedRotation_restrictsAfter150ms() {
        var t = 1_000_000_000L
        // Feed 50 deg/s for 30 samples (300ms)
        // From 0 deg/s, smoothed rate crosses 35 deg/s in ~70ms, then 150ms sustained -> restricts at ~220ms
        for (i in 0 until 15) {
            t += 10_000_000L
            directionPolicy.update(t, 50.0f)
        }
        // At 150ms from start, smoothed rate has crossed 35 deg/s, now feed 15 more samples (150ms)
        for (i in 0 until 15) {
            t += 10_000_000L
            directionPolicy.update(t, 50.0f)
        }
        assertTrue("Sustained rotation above 35 deg/s must restrict", directionPolicy.isRestricted)
        assertEquals(MotionRapidTurnPolicy.REASON_SUSTAINED_RAPID_TURN, directionPolicy.lastTransitionReason)
    }

    @Test
    fun severeRotation_immediatelyRestricts() {
        val t = 1_000_000_000L
        // Single sample at 120 deg/s triggers immediate restriction
        val restricted = directionPolicy.update(t, 120.0f)
        assertTrue("Raw >= 120 deg/s must restrict immediately", restricted)
        assertTrue(directionPolicy.isRestricted)
        assertEquals(MotionRapidTurnPolicy.REASON_IMMEDIATE_SEVERE, directionPolicy.lastTransitionReason)

        // Above 120 deg/s (e.g. 150 deg/s) also immediately restricts ground policy
        val groundRestricted = groundPolicy.update(t, 150.0f)
        assertTrue("Raw >= 120 deg/s must restrict ground policy immediately", groundRestricted)
        assertEquals(MotionRapidTurnPolicy.REASON_IMMEDIATE_SEVERE, groundPolicy.lastTransitionReason)
    }

    @Test
    fun repeatedOscillations_doNotAccumulateAcrossBreaks() {
        var t = 1_000_000_000L
        // Repeat 10 cycles: 60ms of 50 deg/s followed by 60ms of 0 deg/s
        // Each pulse is 60ms < 150ms sustain. The quiet break drops rate and breaks entry timer.
        for (cycle in 0 until 10) {
            for (i in 0 until 6) {
                t += 10_000_000L
                directionPolicy.update(t, 50.0f)
            }
            assertFalse("Oscillation burst must not trigger restriction", directionPolicy.isRestricted)

            for (i in 0 until 6) {
                t += 10_000_000L
                directionPolicy.update(t, 0.0f)
            }
            assertFalse("Oscillation break must reset entry timer", directionPolicy.isRestricted)
        }
        assertFalse("Repeated broken oscillations must never restrict", directionPolicy.isRestricted)
    }

    @Test
    fun recoveryHysteresis_requires300msBelowExitThreshold() {
        var t = 1_000_000_000L
        // 1. Enter restriction via immediate severe sample
        directionPolicy.update(t, 130.0f)
        assertTrue(directionPolicy.isRestricted)

        // 2. Drop to quiet (0 deg/s)
        // Rate smoothing decays exponentially toward 0.
        // It drops < 25 deg/s in ~170ms, then needs 300ms sustained quiet -> total ~470ms to recover.
        for (i in 0 until 35) {
            t += 10_000_000L
            directionPolicy.update(t, 0.0f)
        }
        assertTrue("Must remain restricted during recovery hysteresis (at 350ms)", directionPolicy.isRestricted)

        // Feed remaining samples to complete 300ms sustained below exit threshold (total 50 samples = 500ms)
        for (i in 0 until 15) {
            t += 10_000_000L
            directionPolicy.update(t, 0.0f)
        }
        assertFalse("Must recover after 300ms sustained below exit threshold", directionPolicy.isRestricted)
        assertEquals(MotionRapidTurnPolicy.REASON_RECOVERED, directionPolicy.lastTransitionReason)
    }

    @Test
    fun recoveryInterruption_resetsRecoveryTimer() {
        var t = 1_000_000_000L
        directionPolicy.update(t, 130.0f)
        assertTrue(directionPolicy.isRestricted)

        // Let smoothed rate drop below exit threshold (feed 25 samples = 250ms of quiet)
        for (i in 0 until 25) {
            t += 10_000_000L
            directionPolicy.update(t, 0.0f)
        }
        assertTrue(directionPolicy.isRestricted)

        // Interrupt recovery with a burst above exit threshold (60 deg/s for 100ms)
        for (i in 0 until 10) {
            t += 10_000_000L
            directionPolicy.update(t, 60.0f)
        }
        assertTrue("Still restricted after interruption", directionPolicy.isRestricted)
        assertTrue(
            "Smoothed rate must cross above exit threshold (25 deg/s) to break recovery",
            directionPolicy.smoothedRateDegPerSec >= 25.0f,
        )

        // Recovery timer was broken. Even with 200ms of quiet, it must NOT recover yet
        for (i in 0 until 20) {
            t += 10_000_000L
            directionPolicy.update(t, 0.0f)
        }
        assertTrue("Recovery timer was broken by interruption; must not recover early", directionPolicy.isRestricted)

        // Now provide full uninterrupted 300ms window (feed 40 more samples = 400ms)
        for (i in 0 until 40) {
            t += 10_000_000L
            directionPolicy.update(t, 0.0f)
        }
        assertFalse("Must recover once a full uninterrupted 300ms window elapses", directionPolicy.isRestricted)
        assertEquals(MotionRapidTurnPolicy.REASON_RECOVERED, directionPolicy.lastTransitionReason)
    }

    @Test
    fun exactThresholdBoundaries() {
        val policy = MotionRapidTurnPolicy(
            entryThresholdDegPerSec = 35.0f,
            exitThresholdDegPerSec = 25.0f,
            immediateThresholdDegPerSec = 120.0f,
        )

        var t = 1_000_000_000L
        // 1. Raw rate 119.9f does NOT immediately restrict on first sample
        policy.update(t, 119.9f)
        assertFalse("119.9f is below 120.0f; must not immediately restrict", policy.isRestricted)

        // Reset
        policy.reset()

        // 2. Raw rate 120.0f IMMEDIATELY restricts on first sample
        policy.update(t, 120.0f)
        assertTrue("Exact 120.0f must immediately restrict", policy.isRestricted)

        // Reset
        policy.reset()

        // 3. Exactly 35.0f (smoothed) does NOT start entry timer (strictly > 35.0f required)
        for (i in 0 until 30) {
            t += 10_000_000L
            policy.update(t, 35.0f)
        }
        assertFalse("Exact 35.0f threshold must not restrict (strictly > required)", policy.isRestricted)

        // 4. Exactly 35.1f sustains and restricts
        for (i in 0 until 30) {
            t += 10_000_000L
            policy.update(t, 35.1f)
        }
        assertTrue("35.1f sustained must restrict", policy.isRestricted)
    }

    @Test
    fun variableCadence_handlesIrregularSampleIntervals() {
        var t = 1_000_000_000L
        // Simulate variable sampling intervals (5ms, 12ms, 25ms, 8ms)
        val intervals = listOf(5_000_000L, 12_000_000L, 25_000_000L, 8_000_000L, 15_000_000L)
        var totalElapsed = 0L

        // Feed 50 deg/s until 300ms elapsed
        var idx = 0
        while (totalElapsed < 300_000_000L) {
            val dt = intervals[idx % intervals.size]
            t += dt
            totalElapsed += dt
            idx++
            directionPolicy.update(t, 50.0f)
        }
        assertTrue("Variable cadence should restrict after sustained duration", directionPolicy.isRestricted)
    }

    @Test
    fun timestampRegression_clearsPolicy() {
        var t = 1_000_000_000L
        directionPolicy.update(t, 130.0f)
        assertTrue(directionPolicy.isRestricted)

        // Out-of-order timestamp regression (t - 10ms)
        val accepted = directionPolicy.update(t - 10_000_000L, 50.0f)
        assertFalse("Timestamp regression must clear restriction", accepted)
        assertFalse(directionPolicy.isRestricted)
        assertEquals(0f, directionPolicy.smoothedRateDegPerSec, 1e-4f)
    }

    @Test
    fun nonFiniteSample_clearsPolicy() {
        val t = 1_000_000_000L
        directionPolicy.update(t, 130.0f)
        assertTrue(directionPolicy.isRestricted)

        directionPolicy.update(t + 10_000_000L, Float.NaN)
        assertFalse("NaN sample must clear restriction", directionPolicy.isRestricted)
    }

    @Test
    fun streamGap_clearsPolicyAndRestarts() {
        var t = 1_000_000_000L
        directionPolicy.update(t, 130.0f)
        assertTrue(directionPolicy.isRestricted)

        // Stream gap of 300ms (> 200ms streamFreshnessToleranceNanos)
        t += 300_000_000L
        val res = directionPolicy.update(t, 20.0f)
        assertFalse("Stream gap must reset restriction for non-severe rate", res)
        assertFalse(directionPolicy.isRestricted)
    }

    @Test
    fun sessionReset_clearsPolicy() {
        val t = 1_000_000_000L
        directionPolicy.update(t, 130.0f)
        assertTrue(directionPolicy.isRestricted)

        directionPolicy.reset()
        assertFalse(directionPolicy.isRestricted)
        assertEquals(0f, directionPolicy.smoothedRateDegPerSec, 1e-4f)
        assertNull(directionPolicy.lastTransitionReason)
    }

    @Test
    fun signedYawSymmetry_positiveAndNegativeYawBehaveIdentically() {
        val posPolicy = MotionRapidTurnPolicy.forDirection()
        val negPolicy = MotionRapidTurnPolicy.forDirection()

        var t = 1_000_000_000L
        for (i in 0 until 30) {
            t += 10_000_000L
            val posRes = posPolicy.update(t, 60.0f)
            val negRes = negPolicy.update(t, -60.0f)
            assertEquals("Restriction status must match symmetrically for +yaw and -yaw", posRes, negRes)
            assertEquals(posPolicy.smoothedRateDegPerSec, negPolicy.smoothedRateDegPerSec, 1e-4f)
        }
        assertTrue(posPolicy.isRestricted)
        assertTrue(negPolicy.isRestricted)
        assertEquals(posPolicy.lastTransitionReason, negPolicy.lastTransitionReason)
    }

    @Test
    fun groundOrdinaryThresholdTiming_entryAndRecovery() {
        var t = 1_000_000_000L
        // Ground policy: entry > 45 deg/s sustained 150ms, exit < 35 deg/s sustained 300ms
        // 1. Brief spike of 60 deg/s for 50ms does not restrict
        for (i in 0 until 5) {
            t += 10_000_000L
            assertFalse(groundPolicy.update(t, 60.0f))
        }

        // 2. Sustained rotation at 60 deg/s (> 45 deg/s threshold)
        for (i in 0 until 25) {
            t += 10_000_000L
            groundPolicy.update(t, 60.0f)
        }
        assertTrue("Ground policy must restrict after sustained rotation above 45 deg/s", groundPolicy.isRestricted)
        assertEquals(MotionRapidTurnPolicy.REASON_SUSTAINED_RAPID_TURN, groundPolicy.lastTransitionReason)

        // 3. Drop to 0 deg/s: smoothing drops < 35 deg/s in ~100ms, then needs 300ms sustained quiet -> total ~400ms
        for (i in 0 until 30) {
            t += 10_000_000L
            groundPolicy.update(t, 0.0f)
        }
        assertTrue("Ground policy must remain restricted during recovery window", groundPolicy.isRestricted)

        for (i in 0 until 20) {
            t += 10_000_000L
            groundPolicy.update(t, 0.0f)
        }
        assertFalse("Ground policy must recover after 300ms sustained below 35 deg/s", groundPolicy.isRestricted)
        assertEquals(MotionRapidTurnPolicy.REASON_RECOVERED, groundPolicy.lastTransitionReason)
    }
}
