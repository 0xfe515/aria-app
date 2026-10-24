package com.ariateam.aria.hazard

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MotionFusionEngineTest {

    private lateinit var engine: MotionFusionEngine

    @Before
    fun setUp() {
        engine = MotionFusionEngine(defaultHfovDegrees = 65.5f)
    }

    @Test
    fun initialState_isUncalibratedAndDegraded() {
        val state = engine.stateAt(1_000_000_000L)
        assertFalse("Initial state must not be reliable", state.isReliable)
        assertFalse("Initial state must not be calibrated", state.isCalibrated)
        assertEquals(0f, state.confidence, 1e-4f)
        assertNotNull(state.degradedReason)
    }

    @Test
    fun uprightPortraitOrientation_calculatesNearZeroPitchAndRoll() {
        var t = 1_000_000_000L
        for (i in 0 until 20) {
            engine.onAccelerometerSample(t, 0f, 9.81f, 0f)
            engine.onGyroscopeSample(t, 0f, 0f, 0f)
            t += 10_000_000L // 10ms (100Hz)
        }
        val calibOk = engine.calibrateForward(t)
        assertTrue("Calibration should succeed", calibOk)

        val state = engine.stateAt(t)
        assertTrue("State should be calibrated", state.isCalibrated)
        assertEquals(0f, state.pitchDegrees, 1.0f)
        assertEquals(0f, state.rollDegrees, 1.0f)
        assertNull("No degradation in upright posture", state.degradedReason)
        assertTrue("State must be reliable", state.isReliable)
    }

    @Test
    fun walkingLookDownOrientation_calculatesPositivePitch() {
        // Tilted ~30 deg downward toward walking path:
        // ay = 9.81 * cos(30 deg) = 8.496, az = 9.81 * sin(30 deg) = 4.905
        var t = 1_000_000_000L
        for (i in 0 until 30) {
            engine.onAccelerometerSample(t, 0f, 8.496f, 4.905f)
            engine.onGyroscopeSample(t, 0f, 0f, 0f)
            t += 10_000_000L
        }
        val calibOk = engine.calibrateForward(t)
        assertTrue(calibOk)

        val state = engine.stateAt(t)
        assertEquals(30f, state.pitchDegrees, 2.0f)
        assertEquals(0f, state.rollDegrees, 1.0f)
        assertNull(state.degradedReason)
        assertTrue(state.isReliable)
    }

    @Test
    fun extremePitchOrRoll_degradesMotionReliability() {
        var t = 1_000_000_000L
        for (i in 0 until 30) {
            engine.onAccelerometerSample(t, 0f, 9.81f, 0f)
            engine.onGyroscopeSample(t, 0f, 0f, 0f)
            t += 10_000_000L
        }
        assertTrue(engine.calibrateForward(t))

        // Extreme downward tilt (pointing straight down at feet, pitch > 55 deg)
        // ay = 2.0, az = 9.6 -> pitch ~ 78 deg
        for (i in 0 until 30) {
            engine.onAccelerometerSample(t, 0f, 2.0f, 9.6f)
            engine.onGyroscopeSample(t, 0f, 0f, 0f)
            t += 10_000_000L
        }
        val degradedPitchState = engine.stateAt(t)
        assertEquals(MotionDegradedReason.CAMERA_TILT_INVALID, degradedPitchState.degradedReason)
        assertFalse(degradedPitchState.isReliable)

        // Extreme roll (phone tilted sideways > 30 deg)
        for (i in 0 until 30) {
            engine.onAccelerometerSample(t, 7.0f, 7.0f, 0f)
            engine.onGyroscopeSample(t, 0f, 0f, 0f)
            t += 10_000_000L
        }
        val degradedRollState = engine.stateAt(t)
        assertEquals(MotionDegradedReason.CAMERA_TILT_INVALID, degradedRollState.degradedReason)
        assertFalse(degradedRollState.isReliable)
    }

    @Test
    fun displayRotationRemapping_worksAcrossLandscapeAndInverted() {
        // Landscape (ROTATION_90): physical UP is along sensor +X axis
        val r90 = MotionFusionEngine.remapSensorAxes(9.81f, 0f, 0f, Surface.ROTATION_90)
        assertEquals(0f, r90.first, 1e-4f)
        assertEquals(9.81f, r90.second, 1e-4f)
        assertEquals(0f, r90.third, 1e-4f)

        // Reverse portrait (ROTATION_180): physical UP is along sensor -Y axis
        val r180 = MotionFusionEngine.remapSensorAxes(0f, -9.81f, 0f, Surface.ROTATION_180)
        assertEquals(0f, r180.first, 1e-4f)
        assertEquals(9.81f, r180.second, 1e-4f)
        assertEquals(0f, r180.third, 1e-4f)

        // Reverse landscape (ROTATION_270): physical UP is along sensor -X axis
        val r270 = MotionFusionEngine.remapSensorAxes(-9.81f, 0f, 0f, Surface.ROTATION_270)
        assertEquals(0f, r270.first, 1e-4f)
        assertEquals(9.81f, r270.second, 1e-4f)
        assertEquals(0f, r270.third, 1e-4f)
    }

    @Test
    fun gyroTurning_projectsPathOppositeToYaw() {
        var t = 1_000_000_000L
        for (i in 0 until 30) {
            engine.onAccelerometerSample(t, 0f, 9.81f, 0f)
            engine.onGyroscopeSample(t, 0f, 0f, 0f)
            t += 10_000_000L
        }
        assertTrue(engine.calibrateForward(t))
        assertEquals(CorridorDirection.CENTER, engine.stateAt(t).activeCorridor)

        // Phone yaws LEFT at ~20 deg/s for 1 second (rotation around vertical Y axis: gy = +0.35 rad/s)
        val gyLeftRad = Math.toRadians(20.0).toFloat()
        for (i in 0 until 100) {
            engine.onAccelerometerSample(t, 0f, 9.81f, 0f)
            engine.onGyroscopeSample(t, 0f, gyLeftRad, 0f)
            t += 10_000_000L
        }

        val yawLeftState = engine.stateAt(t)
        assertTrue("Phone heading is left (positive relative heading)", yawLeftState.relativeHeadingDeg > 12f)
        assertEquals("Target projects to the RIGHT corridor", CorridorDirection.RIGHT, yawLeftState.activeCorridor)
        assertTrue(yawLeftState.continuousCorridorCenterPx > 256f)

        // Phone yaws RIGHT at ~30 deg/s for 2 seconds (rotation around vertical Y axis: gy = -0.52 rad/s)
        val gyRightRad = -Math.toRadians(30.0).toFloat()
        for (i in 0 until 200) {
            engine.onAccelerometerSample(t, 0f, 9.81f, 0f)
            engine.onGyroscopeSample(t, 0f, gyRightRad, 0f)
            t += 10_000_000L
        }

        val yawRightState = engine.stateAt(t)
        assertTrue("Phone heading is right (negative relative heading)", yawRightState.relativeHeadingDeg < -12f)
        assertEquals("Target projects to the LEFT corridor", CorridorDirection.LEFT, yawRightState.activeCorridor)
        assertTrue(yawRightState.continuousCorridorCenterPx < 256f)
    }

    @Test
    fun rapidTurn_temporarilyDegradesConfidence() {
        var t = 1_000_000_000L
        for (i in 0 until 30) {
            engine.onAccelerometerSample(t, 0f, 9.81f, 0f)
            engine.onGyroscopeSample(t, 0f, 0f, 0f)
            t += 10_000_000L
        }
        assertTrue(engine.calibrateForward(t))

        // Rapid violent turn around Y axis at 60 deg/s (> 35 deg/s threshold)
        t += 10_000_000L
        val rapidGy = Math.toRadians(60.0).toFloat()
        engine.onAccelerometerSample(t, 0f, 9.81f, 0f)
        engine.onGyroscopeSample(t, 0f, rapidGy, 0f)

        val turnState = engine.stateAt(t)
        assertEquals(MotionDegradedReason.RAPID_TURN_TRANSIENT, turnState.degradedReason)
        assertFalse(turnState.isReliable)
    }

    @Test
    fun singleStreamStaleness_marksSamplesStale() {
        var t = 1_000_000_000L
        for (i in 0 until 30) {
            engine.onAccelerometerSample(t, 0f, 9.81f, 0f)
            engine.onGyroscopeSample(t, 0f, 0f, 0f)
            t += 10_000_000L
        }
        assertTrue(engine.calibrateForward(t))

        // Continue accel updates for 500ms (> 200ms) with NO gyro updates
        for (i in 0 until 50) {
            engine.onAccelerometerSample(t, 0f, 9.81f, 0f)
            t += 10_000_000L
        }
        val staleState = engine.stateAt(t)
        assertEquals(MotionDegradedReason.SAMPLES_STALE, staleState.degradedReason)
        assertFalse(staleState.isReliable)
    }

    @Test
    fun nonFiniteSample_latchesFaultUntilFiniteRecovery() {
        var t = 1_000_000_000L
        for (i in 0 until 10) {
            engine.onAccelerometerSample(t, 0f, 9.81f, 0f)
            engine.onGyroscopeSample(t, 0f, 0f, 0f)
            t += 10_000_000L
        }
        assertTrue(engine.calibrateForward(t))

        // Send NaN on accel
        engine.onAccelerometerSample(t, Float.NaN, 9.81f, 0f)
        val faultState = engine.stateAt(t)
        assertEquals(MotionDegradedReason.NON_FINITE_DATA, faultState.degradedReason)

        // Sending normal gyro sample alone should NOT clear accel fault!
        t += 10_000_000L
        engine.onGyroscopeSample(t, 0f, 0f, 0f)
        val stillFaultState = engine.stateAt(t)
        assertEquals(MotionDegradedReason.NON_FINITE_DATA, stillFaultState.degradedReason)

        // Sending valid accel sample clears the fault
        t += 10_000_000L
        engine.onAccelerometerSample(t, 0f, 9.81f, 0f)
        val recoveredState = engine.stateAt(t)
        assertNull(recoveredState.degradedReason)
    }
}
