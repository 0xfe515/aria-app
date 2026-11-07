package com.ariateam.aria.hazard

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for automatic forward reference calibration functionality.
 */
class AutoCalibrationTest {

    private lateinit var engine: MotionFusionEngine

    @Before
    fun setUp() {
        engine = MotionFusionEngine(defaultHfovDegrees = 65.5f, autoCalibrationEnabled = true)
    }

    @Test
    fun autoCalibrationOccursAfterStableDuration() {
        // Auto-calibration is disabled by default - engine should not auto-calibrate
        val disabledEngine = MotionFusionEngine(defaultHfovDegrees = 65.5f, autoCalibrationEnabled = false)
        var timestamp = 1_000_000_000L

        // Provide many stable samples
        for (i in 0 until 106) {
            disabledEngine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            disabledEngine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
            timestamp += 10_000_000L
        }

        val state = disabledEngine.stateAt(timestamp)
        assertFalse("Auto-calibration disabled should not auto-calibrate", state.isCalibrated)
    }

    @Test
    fun autoCalibrationOccursWhenConditionsAreMet() {
        // Simulate sensor data indicating upright, stationary phone
        var timestamp = 1_000_000_000L
        val samplesNeeded = 5

        // Provide accelerometer and gyroscope samples for more than 1 second stable duration.
        // Counts >= 5 required before stable window can start (first 5 samples at 10ms = 50ms),
        // then 1 second continuous stable window needed: 105 samples at 10ms intervals.
        // Use 106 for margin.
        for (i in 0 until 106) {
            // Upright orientation: ax=0, ay=9.81, az=0 (no linear acceleration)
            engine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            // No rotation: gx=0, gy=0, gz=0
            engine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
            timestamp += 10_000_000L // 10ms (100Hz)
        }

        // After >1 second of stable conditions, engine should be auto-calibrated
        val stateAfter = engine.stateAt(timestamp)
        assertTrue("Engine should be auto-calibrated after stable duration", stateAfter.isCalibrated)
        assertNull("Should have no degradation reason after calibration", stateAfter.degradedReason)
        assertTrue("Should be reliable after calibration", stateAfter.isReliable)
        // Relative heading should be zero since we calibrated with no rotation
        assertEquals(0f, stateAfter.relativeHeadingDeg, 0.1f)
    }

    @Test
    fun autoCalibrationDoesNotOccurWithExcessiveLinearAcceleration() {
        // Simulate sensor data with significant linear acceleration (walking/moving)
        // Use alternating accel jitter so the gravity filter never settles into treating
        // sustained 15m/s2 as gravity — this proves the linear acceleration guard works.
        var timestamp = 1_000_000_000L

        // Provide accelerometer samples with alternating high linear acceleration
        for (i in 0 until 106) {
            val linY = if (i % 2 == 0) 15.0f else -15.0f
            engine.onAccelerometerSample(timestamp, 0f, linY, 0f)
            // No rotation
            engine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
            timestamp += 10_000_000L
        }

        // Engine should not be calibrated due to excessive linear acceleration
        val state = engine.stateAt(timestamp)
        assertFalse("Engine should not calibrate with excessive linear acceleration", state.isCalibrated)
    }

    @Test
    fun autoCalibrationDoesNotOccurWithHighGyroRotation() {
        // Simulate sensor data with significant gyro rotation
        var timestamp = 1_000_000_000L

        // Provide accelerometer samples (upright) but with high gyro rotation
        for (i in 0 until 106) {
            engine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)  // Upright
            // High gyro rotation - above deadband threshold
            engine.onGyroscopeSample(timestamp, 0f, 0.1f, 0f)  // ~5.7 deg/s, above 1.5 deg/s deadband
            timestamp += 10_000_000L
        }

        // Engine should not be calibrated due to high gyro rotation
        val state = engine.stateAt(timestamp)
        assertFalse("Engine should not calibrate with high gyro rotation", state.isCalibrated)
    }

    @Test
    fun autoCalibrationResetsAfterInvalidation() {
        // First, establish a calibrated state through auto-calibration
        var timestamp = 1_000_000_000L

        // Provide good samples for calibration (>1 second stable, counts>=5 before window)
        for (i in 0 until 106) {
            engine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            engine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
            timestamp += 10_000_000L
        }

        val stateAfterCalibration = engine.stateAt(timestamp)
        assertTrue("Engine should be calibrated after stable duration", stateAfterCalibration.isCalibrated)

        // Now invalidate calibration by changing display rotation
        engine.setDisplayRotation(Surface.ROTATION_90)

        val stateAfterInvalidation = engine.stateAt(timestamp)
        assertFalse("Engine should not be calibrated after display rotation change",
                stateAfterInvalidation.isCalibrated)
        assertEquals("Should be waiting for calibration after invalidation",
                MotionDegradedReason.CALIBRATION_PENDING, stateAfterInvalidation.degradedReason)

        // Provide more good samples, should re-calibrate automatically
        for (i in 0 until 106) {
            timestamp += 10_000_000L
            // ROTATION_90 remaps (-y, x, z): rawAx=9.81 -> (0, 9.81, 0) which is valid upright
            engine.onAccelerometerSample(timestamp, 9.81f, 0f, 0f)
            engine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
        }

        val stateAfterReCalibration = engine.stateAt(timestamp)
        assertTrue("Engine should re-calibrate after invalidation", stateAfterReCalibration.isCalibrated)
        assertNull("Should have no degradation reason after re-calibration",
                stateAfterReCalibration.degradedReason)
    }

    @Test
    fun manualCalibrationPreventsAutoOverwrite() {
        // First, establish a calibrated state through auto-calibration
        var timestamp = 1_000_000_000L

        // Provide good samples for calibration (>1 second stable)
        for (i in 0 until 106) {
            engine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            engine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
            timestamp += 10_000_000L
        }

        val stateAfterAuto = engine.stateAt(timestamp)
        assertTrue("Should be auto-calibrated", stateAfterAuto.isCalibrated)
        val baselineAfterAuto = stateAfterAuto.relativeHeadingDeg

        // Manually calibrate (should still work)
        val manualCalibResult = engine.calibrateForward(timestamp + 10_000_000L)
        assertTrue("Manual calibration should still work", manualCalibResult)

        val stateAfterManual = engine.stateAt(timestamp + 10_000_000L)
        assertTrue("Should still be calibrated after manual calibration", stateAfterManual.isCalibrated)

        // Auto-calibration should not overwrite manual calibration
    }

    @Test
    fun autoCalibrationDoesNotOverwriteManualCalibration() {
        var timestamp = 1_000_000_000L

        // First do manual calibration
        for (i in 0 until 106) {
            engine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            engine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
            timestamp += 10_000_000L
        }

        // Manual calibration
        engine.calibrateForward(timestamp)
        val stateAfterManual = engine.stateAt(timestamp)
        assertTrue("Should be manually calibrated", stateAfterManual.isCalibrated)

        // Continue providing stable samples - auto should not overwrite manual
        for (i in 0 until 106) {
            timestamp += 10_000_000L
            engine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            engine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
        }

        val stateAfterContinuedStable = engine.stateAt(timestamp)
        assertTrue("Should still be calibrated after continued stable samples", stateAfterContinuedStable.isCalibrated)
    }

    @Test
    fun disabledEngineDoesNotAutoCalibrate() {
        val disabledEngine = MotionFusionEngine(defaultHfovDegrees = 65.5f, autoCalibrationEnabled = false)
        var timestamp = 1_000_000_000L

        // Provide many stable samples
        for (i in 0 until 106) {
            disabledEngine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            disabledEngine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
            timestamp += 10_000_000L
        }

        val state = disabledEngine.stateAt(timestamp)
        assertFalse("Disabled engine should not auto-calibrate", state.isCalibrated)
        assertEquals(MotionDegradedReason.CALIBRATION_PENDING, state.degradedReason)
    }

    @Test
    fun noAutoCalibrationWhenAlreadyCalibrated() {
        var timestamp = 1_000_000_000L

        // First calibrate manually
        for (i in 0 until 106) {
            engine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            engine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
            timestamp += 10_000_000L
        }

        engine.calibrateForward(timestamp)
        val stateAfterManual = engine.stateAt(timestamp)
        assertTrue("Should be manually calibrated", stateAfterManual.isCalibrated)

        // Continue stable samples - should not re-auto-calibrate since already calibrated
        for (i in 0 until 106) {
            timestamp += 10_000_000L
            engine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            engine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
        }

        val stateAfterContinued = engine.stateAt(timestamp)
        assertTrue("Should remain calibrated", stateAfterContinued.isCalibrated)
    }

    @Test
    fun autoCalibrationBlockedWhenGeometryUnknown() {
        // No camera geometry (null HFoV) — auto-calibration must be blocked
        val engineNoGeo = MotionFusionEngine(autoCalibrationEnabled = true)
        var timestamp = 1_000_000_000L

        for (i in 0 until 106) {
            engineNoGeo.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            engineNoGeo.onGyroscopeSample(timestamp, 0f, 0f, 0f)
            timestamp += 10_000_000L
        }

        val state = engineNoGeo.stateAt(timestamp)
        assertFalse("Engine should not auto-calibrate with unknown geometry", state.isCalibrated)
        assertEquals(MotionDegradedReason.CAMERA_FOV_UNKNOWN, state.degradedReason)
    }

    @Test
    fun autoCalibrationBlockedWhenStreamStale() {
        // Provide stable samples then stop gyro updates (stale stream) — auto-calibration must be blocked
        var timestamp = 1_000_000_000L

        for (i in 0 until 50) {
            engine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            engine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
            timestamp += 10_000_000L
        }

        // Continue accel only for >1s with NO gyro updates (stale gyro stream)
        for (i in 0 until 101) {
            engine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            timestamp += 10_000_000L
        }

        val state = engine.stateAt(timestamp)
        assertFalse("Engine should not auto-calibrate with stale gyro stream", state.isCalibrated)
        assertEquals(MotionDegradedReason.SAMPLES_STALE, state.degradedReason)
    }

    @Test
    fun autoCalibrationDoesNotRecenterRealTurn() {
        // A real turn should not be recentered by auto-calibration
        var timestamp = 1_000_000_000L

        // Establish baseline calibration first (stable, no rotation)
        for (i in 0 until 106) {
            engine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            engine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
            timestamp += 10_000_000L
        }

        val stateBeforeTurn = engine.stateAt(timestamp)
        assertTrue("Should be calibrated before turn", stateBeforeTurn.isCalibrated)
        assertEquals(0f, stateBeforeTurn.relativeHeadingDeg, 0.1f)

        // Yaw left at ~20 deg/s for 1 second (gy = +0.35 rad/s)
        val gyLeftRad = Math.toRadians(20.0).toFloat()
        for (i in 0 until 100) {
            engine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            engine.onGyroscopeSample(timestamp, 0f, gyLeftRad, 0f)
            timestamp += 10_000_000L
        }

        val turnState = engine.stateAt(timestamp)
        // Relative heading should reflect the turn, not be recentered to zero
        assertTrue("Real turn should produce non-zero relative heading",
                turnState.relativeHeadingDeg > 12f)
        // Manual calibration during turn should preserve the turn heading
        val manualOk = engine.calibrateForward(timestamp)
        assertTrue("Manual calibration during turn should succeed", manualOk)
        val stateAfterManualTurn = engine.stateAt(timestamp)
        assertTrue("Should remain calibrated after manual turn calibration",
                stateAfterManualTurn.isCalibrated)
        // Relative heading after manual calibration should be zero (baseline reset to current heading)
        assertEquals(0f, stateAfterManualTurn.relativeHeadingDeg, 0.1f)
    }

    @Test
    fun manualCalibrationNotOverwrittenByAutoCalibration() {
        // Manual calibration must persist even when stable conditions continue
        var timestamp = 1_000_000_000L

        // Establish stable conditions
        for (i in 0 until 106) {
            engine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            engine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
            timestamp += 10_000_000L
        }

        // Manual calibration
        assertTrue("Manual calibration should succeed", engine.calibrateForward(timestamp))
        val stateAfterManual = engine.stateAt(timestamp)
        assertTrue("Should be manually calibrated", stateAfterManual.isCalibrated)

        // Continue providing stable samples — auto-calibration should not disturb manual calibration
        for (i in 0 until 106) {
            timestamp += 10_000_000L
            engine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            engine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
        }

        val stateAfterContinued = engine.stateAt(timestamp)
        assertTrue("Should remain calibrated after continued stable samples", stateAfterContinued.isCalibrated)
    }

    @Test
    fun autoCalibrationOutOfOrderResetsStableWindow() {
        // Out-of-order timestamp must invalidate pending stable window without integrating sample
        var timestamp = 1_000_000_000L

        // Provide 50 stable samples (starts stable window at sample 5)
        for (i in 0 until 50) {
            engine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            engine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
            timestamp += 10_000_000L
        }

        // Send out-of-order timestamp (earlier than last) — should reset stable window
        val outOfOrderTs = timestamp - 100_000_000L
        assertFalse("Out-of-order accel should be rejected",
                engine.onAccelerometerSample(outOfOrderTs, 0f, 9.81f, 0f))
        assertFalse("Out-of-order gyro should be rejected",
                engine.onGyroscopeSample(outOfOrderTs, 0f, 0f, 0f))

        // After out-of-order rejection, stable window should be reset.
        // Provide another 106 stable samples — should still calibrate since window restarted.
        for (i in 0 until 106) {
            timestamp += 10_000_000L
            engine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            engine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
        }

        val state = engine.stateAt(timestamp)
        assertTrue("Engine should auto-calibrate after out-of-order reset with sufficient samples",
                state.isCalibrated)
    }

    @Test
    fun autoCalibrationBlockedByInvalidPosture() {
        // Invalid posture (excessive roll) should block auto-calibration.
        // When not yet calibrated, CALIBRATION_PENDING takes diagnostic precedence over
        // CAMERA_TILT_INVALID, so assert uncalibrated + unreliable rather than the tilt reason.
        var timestamp = 1_000_000_000L

        // Tilt phone ~45 degrees roll (exceeds maxRollDegrees=30)
        // ax = 9.81 * sin(45) ≈ 6.94, ay = 9.81 * cos(45) ≈ 6.94
        val tilt = 6.94f
        for (i in 0 until 106) {
            engine.onAccelerometerSample(timestamp, tilt, tilt, 0f)
            engine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
            timestamp += 10_000_000L
        }

        val state = engine.stateAt(timestamp)
        assertFalse("Engine should not auto-calibrate with excessive roll", state.isCalibrated)
        assertEquals(0f, state.confidence, 1e-4f)
        assertNotNull(state.degradedReason)
    }

    @Test
    fun autoCalibrationGapReacquisition() {
        // Stream gap should reset calibration, then re-acquisition should re-calibrate
        var timestamp = 1_000_000_000L

        // Establish calibration first
        for (i in 0 until 106) {
            engine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            engine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
            timestamp += 10_000_000L
        }
        assertTrue(engine.stateAt(timestamp).isCalibrated)

        // Create gyro stream gap (>200ms) — invalidates calibration
        for (i in 0 until 25) {
            timestamp += 10_000_000L
            engine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            // No gyro — gap grows
        }
        val gapState = engine.stateAt(timestamp)
        assertFalse("Engine should not be calibrated after stream gap", gapState.isCalibrated)

        // Re-acquire: resume both streams, should re-calibrate
        for (i in 0 until 106) {
            timestamp += 10_000_000L
            engine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            engine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
        }
        val reacquiredState = engine.stateAt(timestamp)
        assertTrue("Engine should re-calibrate after gap reacquisition", reacquiredState.isCalibrated)
    }

    @Test
    fun autoCalibrationFaultThenResetRecovers() {
        // Non-finite sample latches fault, blocking calibration; reset should clear
        var timestamp = 1_000_000_000L

        // Establish calibration first
        for (i in 0 until 106) {
            engine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            engine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
            timestamp += 10_000_000L
        }
        assertTrue(engine.stateAt(timestamp).isCalibrated)

        // Send NaN on accel — latches fault
        engine.onAccelerometerSample(timestamp, Float.NaN, 9.81f, 0f)
        val faultState = engine.stateAt(timestamp)
        assertEquals(MotionDegradedReason.NON_FINITE_DATA, faultState.degradedReason)
        assertFalse("Engine should not be calibrated during fault", faultState.isCalibrated)

        // Reset engine — should clear fault and calibration
        engine.reset()

        // Re-establish calibration after reset
        for (i in 0 until 106) {
            timestamp += 10_000_000L
            engine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            engine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
        }
        val recoveredState = engine.stateAt(timestamp)
        assertTrue("Engine should re-calibrate after reset and stable samples", recoveredState.isCalibrated)
    }

    @Test
    fun autoCalibrationGyroOutOfOrderResetsStableWindow() {
        // Out-of-order gyro timestamp must invalidate pending stable window without integrating sample
        var timestamp = 1_000_000_000L

        // Provide 50 stable samples (starts stable window at sample 5, ~0.45s window so far)
        for (i in 0 until 50) {
            engine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            engine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
            timestamp += 10_000_000L
        }

        // Before out-of-order rejection: engine should NOT yet be calibrated (window not full 1s)
        val beforeState = engine.stateAt(timestamp)
        assertFalse("Before full stable window, engine should not be calibrated",
                beforeState.isCalibrated)

        // Send out-of-order gyro timestamp (earlier than last) — should reset stable window
        val outOfOrderTs = timestamp - 100_000_000L
        assertFalse("Out-of-order gyro should be rejected",
                engine.onGyroscopeSample(outOfOrderTs, 0f, 0f, 0f))

        // After out-of-order rejection, stable window is reset.
        // Provide 60 quiet samples (0.6s) — should still be false since window restarted.
        for (i in 0 until 60) {
            timestamp += 10_000_000L
            engine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            engine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
        }
        val afterPartialState = engine.stateAt(timestamp)
        assertFalse("After partial window post-reset, engine should not be calibrated",
                afterPartialState.isCalibrated)

        // Continue remaining 45 samples (0.45s) — total ~1.0s window post-reset — should calibrate.
        for (i in 0 until 45) {
            timestamp += 10_000_000L
            engine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            engine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
        }

        val state = engine.stateAt(timestamp)
        assertTrue("Engine should auto-calibrate after gyro out-of-order reset with full window",
                state.isCalibrated)
    }

    @Test
    fun autoCalibrationBlockedByNonYawGyroRotation() {
        // Non-yaw gyro rotation (gx=0.1 rad/s roll, upright gravity) should block calibration
        // Full gyro vector magnitude ~5.7 deg/s > 4.5 deg/s stability threshold (3x deadband)
        var timestamp = 1_000_000_000L

        // Upright gravity with roll gyro rotation (non-yaw): gx=0.1 rad/s, gy=0, gz=0
        for (i in 0 until 106) {
            engine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)  // Upright
            engine.onGyroscopeSample(timestamp, 0.1f, 0f, 0f)  // ~5.7 deg/s roll, non-yaw
            timestamp += 10_000_000L
        }

        val state = engine.stateAt(timestamp)
        assertFalse("Engine should not auto-calibrate with non-yaw gyro rotation", state.isCalibrated)
    }

    @Test
    fun autoCalibrationBlockedByZeroGravity() {
        // All-zero accelerometer yields finite atan2(0,0)=0 pitch/roll which could falsely
        // pass posture guards. Gravity norm must be within plausible physical band (.5g..1.5g).
        // Quiet gyro, all-zero accel — should never auto-calibrate.
        var timestamp = 1_000_000_000L

        for (i in 0 until 106) {
            engine.onAccelerometerSample(timestamp, 0f, 0f, 0f)  // Zero gravity — implausible
            engine.onGyroscopeSample(timestamp, 0f, 0f, 0f)  // Quiet gyro
            timestamp += 10_000_000L
        }

        val state = engine.stateAt(timestamp)
        assertFalse("Engine should not auto-calibrate with zero gravity norm", state.isCalibrated)
    }

    @Test
    fun walkingAutoCalibration_calibratesWhileWalkingWithModerateMotion() {
        val walkingEngine = MotionFusionEngine(
            defaultHfovDegrees = 65.5f,
            autoCalibrationEnabled = true,
            walkingAutoCalibrationEnabled = true,
            walkingAutoCalibrationWindowNanos = 450_000_000L,
            walkingMaxLinearEnergy = 2.8f,
            walkingMaxGyroDegPerSec = 12.0f,
        )
        val stationaryEngine = MotionFusionEngine(
            defaultHfovDegrees = 65.5f,
            autoCalibrationEnabled = true,
            walkingAutoCalibrationEnabled = false,
        )

        var timestamp = 1_000_000_000L
        // Moderate motion typical of steady walking:
        // linMag ~ 1.2 m/s^2 (energy between 0.45 and 2.8 m/s^2), gyro ~ 5.7 deg/s (0.1 rad/s)
        for (i in 0 until 55) {
            val linY = if (i % 2 == 0) 1.2f else -1.2f
            walkingEngine.onAccelerometerSample(timestamp, 0f, 9.81f + linY, 0f)
            walkingEngine.onGyroscopeSample(timestamp, 0f, 0.10f, 0f)

            stationaryEngine.onAccelerometerSample(timestamp, 0f, 9.81f + linY, 0f)
            stationaryEngine.onGyroscopeSample(timestamp, 0f, 0.10f, 0f)

            timestamp += 10_000_000L
        }

        val stationaryState = stationaryEngine.stateAt(timestamp)
        assertFalse(
            "Stationary auto-calibration should reject moderate walking motion",
            stationaryState.isCalibrated,
        )

        val walkingState = walkingEngine.stateAt(timestamp)
        assertTrue(
            "Walking auto-calibration should succeed with moderate motion after 450ms window",
            walkingState.isCalibrated,
        )
        assertNull("Walking auto-calibrated state should have no degradation reason", walkingState.degradedReason)
    }

    @Test
    fun postTurnAutoReanchor_clearsExcessiveDriftAfterCornerTurn() {
        val turnEngine = MotionFusionEngine(
            defaultHfovDegrees = 65.5f,
            autoCalibrationEnabled = true,
            continuousRecenteringEnabled = true,
            postTurnReanchorWindowNanos = 650_000_000L,
            postTurnMaxYawRateDegPerSec = 8.0f,
        )

        var timestamp = 1_000_000_000L
        // 1. Initial stationary calibration
        for (i in 0 until 106) {
            turnEngine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            turnEngine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
            timestamp += 10_000_000L
        }
        assertTrue("Engine should be initial calibrated", turnEngine.stateAt(timestamp).isCalibrated)

        // 2. Corner turn: rotate 90 degrees around vertical axis
        // 90 degrees in 100 samples (1 second) = 90 deg/s = 1.5707963 rad/s
        for (i in 0 until 100) {
            timestamp += 10_000_000L
            turnEngine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            turnEngine.onGyroscopeSample(timestamp, 0f, 1.5707963f, 0f)
        }
        // Settle rotation with quiet sample: initially in recovery hysteresis
        timestamp += 10_000_000L
        turnEngine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
        turnEngine.onGyroscopeSample(timestamp, 0f, 0f, 0f)

        val turnedState = turnEngine.stateAt(timestamp)
        assertEquals(MotionDegradedReason.RAPID_TURN_TRANSIENT, turnedState.degradedReason)

        // Settle quiet samples until smoothing drops below 25 deg/s plus 300ms recovery window (45 samples = 450ms)
        for (i in 0 until 45) {
            timestamp += 10_000_000L
            turnEngine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            turnEngine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
        }
        val postRecoveryState = turnEngine.stateAt(timestamp)
        assertEquals(
            "After rapid turn recovery hysteresis elapses, state reflects EXCESSIVE_DRIFT",
            MotionDegradedReason.EXCESSIVE_DRIFT,
            postRecoveryState.degradedReason,
        )

        // 3. Steady heading in new direction: 10 more samples (100ms) -> total 560ms < 650ms window
        for (i in 0 until 10) {
            timestamp += 10_000_000L
            turnEngine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            turnEngine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
        }
        val midState = turnEngine.stateAt(timestamp)
        assertEquals(
            "Should still be degraded before 650ms post-turn reanchor window elapses",
            MotionDegradedReason.EXCESSIVE_DRIFT,
            midState.degradedReason,
        )

        // Feed remaining samples (20 samples = 200ms, total 760ms > 650ms)
        for (i in 0 until 20) {
            timestamp += 10_000_000L
            turnEngine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            turnEngine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
        }

        val reanchoredState = turnEngine.stateAt(timestamp)
        assertNull(
            "Post-turn re-anchor should clear excessive drift after steady window",
            reanchoredState.degradedReason,
        )
        assertEquals(0f, reanchoredState.relativeHeadingDeg, 0.5f)
        assertEquals(CorridorDirection.CENTER, reanchoredState.activeCorridor)
    }

    @Test
    fun continuousRecentering_eliminatesSlowDriftWhilePreservingShortTermTurnSteering() {
        val recenteringEngine = MotionFusionEngine(
            defaultHfovDegrees = 65.5f,
            autoCalibrationEnabled = true,
            continuousRecenteringEnabled = true,
            recenteringTauSeconds = 3.5f,
        )

        var timestamp = 1_000_000_000L
        // 1. Initial calibration
        for (i in 0 until 106) {
            recenteringEngine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            recenteringEngine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
            timestamp += 10_000_000L
        }
        assertTrue("Engine should be initial calibrated", recenteringEngine.stateAt(timestamp).isCalibrated)

        // 2. Introduce small turn within FOV (~10 degrees = ~0.174 rad) over 100ms (10 samples)
        for (i in 0 until 10) {
            timestamp += 10_000_000L
            recenteringEngine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            recenteringEngine.onGyroscopeSample(timestamp, 0f, 1.74533f, 0f)
        }

        val steeringState = recenteringEngine.stateAt(timestamp)
        // Short-term steering should be preserved: heading around 10 degrees
        assertEquals(10.0f, steeringState.relativeHeadingDeg, 1.5f)

        // 3. User continues walking straight (gy = 0) for 500 samples (5.0s, > tau of 3.5s)
        for (i in 0 until 500) {
            timestamp += 10_000_000L
            recenteringEngine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            recenteringEngine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
        }

        val recenteredState = recenteringEngine.stateAt(timestamp)
        // Slow drift should be eliminated: relative heading decayed towards 0
        assertTrue(
            "Continuous recentering should decay relative heading towards 0 (was ${recenteredState.relativeHeadingDeg})",
            recenteredState.relativeHeadingDeg < 3.0f,
        )
    }

    @Test
    fun visualCorridorObservation_immediatelyCalibratesAndReanchorsExcessiveDrift() {
        val visualEngine = MotionFusionEngine(
            defaultHfovDegrees = 65.5f,
            autoCalibrationEnabled = false,
            continuousRecenteringEnabled = true,
        )

        var timestamp = 1_000_000_000L
        // Provide 10 samples so accelSampleCount >= 5 and gyroSampleCount >= 5
        for (i in 0 until 10) {
            visualEngine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            visualEngine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
            timestamp += 10_000_000L
        }
        assertFalse("Should be uncalibrated initially", visualEngine.stateAt(timestamp).isCalibrated)

        // 1. Rejection when clear path condition is not met (roiWalkable < 0.45f)
        val rejectedLowWalkable = visualEngine.onVisualCorridorObservation(
            timestampNanos = timestamp,
            roiWalkableRatio = 0.30f,
            nearWalkableRatio = 0.40f,
            walkableCentroidOffsetX = 0f,
        )
        assertFalse("Observation with low walkable ratio should be rejected", rejectedLowWalkable)

        // Rejection when walkable centroid is off-center (> 0.18f)
        val rejectedOffCenter = visualEngine.onVisualCorridorObservation(
            timestampNanos = timestamp,
            roiWalkableRatio = 0.60f,
            nearWalkableRatio = 0.50f,
            walkableCentroidOffsetX = 0.25f,
        )
        assertFalse("Observation with off-center centroid should be rejected", rejectedOffCenter)

        // 2. Successful immediate calibration from uncalibrated state
        val calibrated = visualEngine.onVisualCorridorObservation(
            timestampNanos = timestamp,
            roiWalkableRatio = 0.70f,
            nearWalkableRatio = 0.60f,
            walkableCentroidOffsetX = 0.05f,
        )
        assertTrue("Visual corridor observation should immediately calibrate uncalibrated engine", calibrated)
        val stateAfterVisualCalib = visualEngine.stateAt(timestamp)
        assertTrue("Engine state should reflect calibration", stateAfterVisualCalib.isCalibrated)
        assertEquals(0f, stateAfterVisualCalib.relativeHeadingDeg, 0.1f)

        // 3. Drift off to > halfHfov (e.g. 60 degrees turn)
        for (i in 0 until 60) {
            timestamp += 10_000_000L
            visualEngine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            visualEngine.onGyroscopeSample(timestamp, 0f, 1.74533f, 0f)
        }
        // Settle rotation: initially in recovery hysteresis (RAPID_TURN_TRANSIENT)
        timestamp += 10_000_000L
        visualEngine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
        visualEngine.onGyroscopeSample(timestamp, 0f, 0f, 0f)

        val recoveringState = visualEngine.stateAt(timestamp)
        assertEquals(MotionDegradedReason.RAPID_TURN_TRANSIENT, recoveringState.degradedReason)

        // Settle quiet samples until smoothing drops below 25 deg/s plus 300ms recovery window (45 samples = 450ms)
        for (i in 0 until 45) {
            timestamp += 10_000_000L
            visualEngine.onAccelerometerSample(timestamp, 0f, 9.81f, 0f)
            visualEngine.onGyroscopeSample(timestamp, 0f, 0f, 0f)
        }

        val driftedState = visualEngine.stateAt(timestamp)
        assertEquals(MotionDegradedReason.EXCESSIVE_DRIFT, driftedState.degradedReason)

        // 4. Visual corridor observation immediately clears excessive drift
        val reanchored = visualEngine.onVisualCorridorObservation(
            timestampNanos = timestamp,
            roiWalkableRatio = 0.65f,
            nearWalkableRatio = 0.55f,
            walkableCentroidOffsetX = 0.02f,
        )
        assertTrue("Visual corridor observation should re-anchor excessive drift", reanchored)
        val reanchoredState = visualEngine.stateAt(timestamp)
        assertNull("Degraded reason should be cleared after visual re-anchor", reanchoredState.degradedReason)
        assertEquals(0f, reanchoredState.relativeHeadingDeg, 0.1f)
    }
}
