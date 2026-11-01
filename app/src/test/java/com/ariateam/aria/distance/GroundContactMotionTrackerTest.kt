package com.ariateam.aria.distance

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class GroundContactMotionTrackerTest {

    @Test
    fun initialQueryWithoutSamples_isUnreliable() {
        val tracker = GroundContactMotionTracker()
        val orientation = tracker.orientationAt(1_000_000_000L)
        assertFalse(orientation.isReliable)
        assertEquals("센서(자세) 신호 미수신", orientation.degradedReason)
    }

    @Test
    fun uprightVerticalPhone_producesMinusYUpVector() {
        val tracker = GroundContactMotionTracker()
        tracker.start(Surface.ROTATION_0)

        // Upright vertical phone in portrait: gravity reaction is +Y along phone
        var t = 1_000_000_000L
        for (i in 0 until 10) {
            tracker.onAccelerometerSample(t, rawAx = 0f, rawAy = 9.81f, rawAz = 0f)
            t += 10_000_000L // 10ms
        }

        val orientation = tracker.orientationAt(t)
        assertTrue(orientation.isReliable)
        assertNull(orientation.degradedReason)
        assertEquals(0f, orientation.upVector.x, 1e-3f)
        assertEquals(-1f, orientation.upVector.y, 1e-3f)
        assertEquals(0f, orientation.upVector.z, 1e-3f)
    }

    @Test
    fun thirtyDegreeTiltDown_producesTiltedUpVector() {
        val tracker = GroundContactMotionTracker()
        tracker.start(Surface.ROTATION_0)

        val rad30 = Math.toRadians(30.0).toFloat()
        val ay = 9.81f * cos(rad30)
        val az = 9.81f * sin(rad30)

        var t = 1_000_000_000L
        for (i in 0 until 50) {
            tracker.onAccelerometerSample(t, rawAx = 0f, rawAy = ay, rawAz = az)
            t += 10_000_000L
        }

        val orientation = tracker.orientationAt(t)
        assertTrue(orientation.isReliable)
        assertNull(orientation.degradedReason)
        assertEquals(0f, orientation.upVector.x, 1e-3f)
        assertEquals(-cos(rad30), orientation.upVector.y, 1e-2f)
        assertEquals(-sin(rad30), orientation.upVector.z, 1e-2f)
    }

    @Test
    fun landscapeRotationSigns_testedAgainstAndroidCoordinates() {
        // Landscape (ROTATION_90): phone rotated 90 deg CCW.
        // Android sensor +X points screen UP.
        // In upright orientation in landscape, accelerometer measures +9.81 along phone +X:
        val tracker = GroundContactMotionTracker()
        tracker.start(Surface.ROTATION_90)

        var t = 1_000_000_000L
        for (i in 0 until 50) {
            tracker.onAccelerometerSample(t, rawAx = 9.81f, rawAy = 0f, rawAz = 0f)
            t += 10_000_000L
        }

        val orientation = tracker.orientationAt(t)
        assertTrue(orientation.isReliable)
        assertEquals(0f, orientation.upVector.x, 1e-3f)
        assertEquals(-1f, orientation.upVector.y, 1e-3f)
        assertEquals(0f, orientation.upVector.z, 1e-3f)

        // When tilted 30 deg down in landscape (ROTATION_90):
        // Screen normal (+Z) tilts upward toward sky (rawAz > 0):
        val rad30 = Math.toRadians(30.0).toFloat()
        val axTilted = 9.81f * cos(rad30)
        val azTilted = 9.81f * sin(rad30)

        // Allow IIR filter to settle to new steady state (> 1.5 seconds)
        for (i in 0 until 150) {
            tracker.onAccelerometerSample(t, rawAx = axTilted, rawAy = 0f, rawAz = azTilted)
            t += 10_000_000L
        }

        val tiltedOrientation = tracker.orientationAt(t)
        assertTrue(tiltedOrientation.isReliable)
        assertEquals(0f, tiltedOrientation.upVector.x, 1e-3f)
        assertEquals(-cos(rad30), tiltedOrientation.upVector.y, 1e-2f)
        assertEquals(-sin(rad30), tiltedOrientation.upVector.z, 1e-2f)
    }

    @Test
    fun accelMagnitude_freefallAndShock_rejected() {
        val tracker = GroundContactMotionTracker()
        tracker.start(Surface.ROTATION_0)

        // Normal sample to initialize
        tracker.onAccelerometerSample(1_000_000_000L, 0f, 9.81f, 0f)

        // 1. Freefall (< 4.5 m/s^2)
        val tFreefall = 1_010_000_000L
        val acceptedFreefall = tracker.onAccelerometerSample(tFreefall, 0f, 2.0f, 0f)
        assertFalse(acceptedFreefall)
        val freefallOrientation = tracker.orientationAt(tFreefall)
        assertFalse(freefallOrientation.isReliable)
        assertEquals("비정상 센서 수치(가속도 이상) 감지", freefallOrientation.degradedReason)

        // 2. Extreme shock / dynamic acceleration (> 16.5 m/s^2)
        val tShock = 1_020_000_000L
        val acceptedShock = tracker.onAccelerometerSample(tShock, 0f, 20.0f, 0f)
        assertFalse(acceptedShock)
        val shockOrientation = tracker.orientationAt(tShock)
        assertFalse(shockOrientation.isReliable)
        assertEquals("비정상 센서 수치(가속도 이상) 감지", shockOrientation.degradedReason)
    }

    @Test
    fun staleSamples_marksOrientationDegraded() {
        val tracker = GroundContactMotionTracker()
        tracker.start(Surface.ROTATION_0)

        val t = 1_000_000_000L
        tracker.onAccelerometerSample(t, 0f, 9.81f, 0f)

        // Query within 100ms -> reliable
        val fresh = tracker.orientationAt(t + 100_000_000L)
        assertTrue(fresh.isReliable)

        // Query after 250ms (> 200ms threshold) -> degraded
        val stale = tracker.orientationAt(t + 250_000_000L)
        assertFalse(stale.isReliable)
        assertEquals("센서 데이터 지연", stale.degradedReason)
    }

    @Test
    fun rapidRotation_triggersRapidMotionHold() {
        val tracker = GroundContactMotionTracker()
        tracker.start(Surface.ROTATION_0)

        var t = 1_000_000_000L
        tracker.onAccelerometerSample(t, 0f, 9.81f, 0f)

        // Normal gyro (10 deg/s in radians)
        val normalGz = Math.toRadians(10.0).toFloat()
        tracker.onGyroscopeSample(t, 0f, 0f, normalGz)

        val normalOrientation = tracker.orientationAt(t)
        assertTrue(normalOrientation.isReliable)

        // Rapid gyro (90 deg/s in radians, well above 45 deg/s threshold)
        t += 10_000_000L
        val rapidGz = Math.toRadians(90.0).toFloat()
        tracker.onGyroscopeSample(t, 0f, 0f, rapidGz)
        tracker.onAccelerometerSample(t, 0f, 9.81f, 0f)

        val rapidOrientation = tracker.orientationAt(t)
        assertFalse(rapidOrientation.isReliable)
        assertTrue(rapidOrientation.rapidMotion)
        assertEquals("급격한 기기 움직임 감지", rapidOrientation.degradedReason)
    }

    @Test
    fun nonFiniteSample_latchesFault() {
        val tracker = GroundContactMotionTracker()
        tracker.start(Surface.ROTATION_0)

        // Accelerometer NaN latches fault
        val t = 1_000_000_000L
        val accelAccepted = tracker.onAccelerometerSample(t, Float.NaN, 9.81f, 0f)
        assertFalse(accelAccepted)

        val faultyAccel = tracker.orientationAt(t)
        assertFalse(faultyAccel.isReliable)
        assertEquals("비정상 센서 수치(가속도 이상) 감지", faultyAccel.degradedReason)

        // Gyroscope NaN latches fault
        val t2 = 1_010_000_000L
        val gyroAccepted = tracker.onGyroscopeSample(t2, Float.NaN, 0f, 0f)
        assertFalse(gyroAccepted)

        val faultyGyro = tracker.orientationAt(t2)
        assertFalse(faultyGyro.isReliable)
        assertEquals("비정상 센서 수치(가속도 이상) 감지", faultyGyro.degradedReason)
    }

    @Test
    fun listenerGeneration_rejectsLateCallbacksAcrossRestart() {
        val tracker = GroundContactMotionTracker()
        tracker.start(Surface.ROTATION_0)

        val gen1Listener = tracker.activeListener
        assertNotNull(gen1Listener)
        assertEquals(1L, gen1Listener!!.generation)

        // Stop session and start session 2: generation increments on both stop and start
        tracker.stop()
        tracker.start(Surface.ROTATION_0)
        val gen2Listener = tracker.activeListener
        assertNotNull(gen2Listener)
        assertTrue(
            "Generation must strictly increase across restart",
            gen2Listener!!.generation > gen1Listener.generation
        )

        // Late accelerometer callback from gen1 listener must be rejected
        val lateAccepted = tracker.onAccelerometerSample(
            timestampNanos = 1_000_000_000L,
            rawAx = 0f,
            rawAy = 9.81f,
            rawAz = 0f,
            generation = gen1Listener.generation,
            listener = gen1Listener,
        )
        assertFalse("Late callback from previous session generation must be rejected", lateAccepted)

        // Late gyroscope callback from gen1 listener must be rejected
        val lateGyroAccepted = tracker.onGyroscopeSample(
            timestampNanos = 1_000_000_000L,
            rawGx = 0f,
            rawGy = 0f,
            rawGz = 0f,
            generation = gen1Listener.generation,
            listener = gen1Listener,
        )
        assertFalse("Late gyro callback from previous session generation must be rejected", lateGyroAccepted)

        // Callback from active gen2 listener must be accepted
        val activeAccepted = tracker.onAccelerometerSample(
            timestampNanos = 1_000_000_000L,
            rawAx = 0f,
            rawAy = 9.81f,
            rawAz = 0f,
            generation = gen2Listener.generation,
            listener = gen2Listener,
        )
        assertTrue(activeAccepted)
    }

    @Test
    fun boundedRingBuffer_alignsExactCaptureTimestampAndWraparound() {
        val tracker = GroundContactMotionTracker()
        tracker.start(Surface.ROTATION_0)

        // Phase 1: 150 samples with phone upright (exceeds capacity 128 to test wraparound)
        var t = 1_000_000_000L
        for (i in 0 until 150) {
            tracker.onAccelerometerSample(t, rawAx = 0f, rawAy = 9.81f, rawAz = 0f)
            t += 10_000_000L // 10ms intervals
        }

        // Capture snapshot time while upright
        val captureTime = t
        tracker.onAccelerometerSample(captureTime, rawAx = 0f, rawAy = 9.81f, rawAz = 0f)

        // Phase 2: Over next 60 samples (600ms, well within 128-entry buffer), phone tilts 45 deg down
        val rad45 = Math.toRadians(45.0).toFloat()
        val ayTilted = 9.81f * cos(rad45)
        val azTilted = 9.81f * sin(rad45)
        for (i in 0 until 60) {
            t += 10_000_000L
            tracker.onAccelerometerSample(t, rawAx = 0f, rawAy = ayTilted, rawAz = azTilted)
        }

        // 1. Query orientation at captured time: must return upright vector (upY ~ -1.0, upZ ~ 0.0)
        val pastOrientation = tracker.orientationAt(captureTime)
        assertTrue(pastOrientation.isReliable)
        assertNull(pastOrientation.degradedReason)
        assertEquals(0f, pastOrientation.upVector.x, 1e-2f)
        assertEquals(-1f, pastOrientation.upVector.y, 1e-2f)
        assertEquals(0f, pastOrientation.upVector.z, 1e-2f)

        // 2. Query orientation at latest time: must return tilted vector (upZ < -0.4f)
        val latestOrientation = tracker.orientationAt(t)
        assertTrue(latestOrientation.isReliable)
        assertNull(latestOrientation.degradedReason)
        assertTrue("Latest Z component must reflect downward tilt", latestOrientation.upVector.z < -0.4f)

        // 3. Meaningful distinction: past query must differ significantly from latest query
        val zDifference = kotlin.math.abs(pastOrientation.upVector.z - latestOrientation.upVector.z)
        assertTrue("Past captured vector must differ from latest tilted vector", zDifference > 0.4f)

        // 4. Query for an evicted sample (> 128 samples ago from start of test): must report stale
        val evictedOrientation = tracker.orientationAt(1_000_000_000L)
        assertFalse(evictedOrientation.isReliable)
        assertEquals("센서 데이터 지연", evictedOrientation.degradedReason)
    }

    @Test
    fun gyroMonotonicAndPositiveTimestamps_rejectsOldOrNonPositiveCallbacks() {
        val tracker = GroundContactMotionTracker()
        tracker.start(Surface.ROTATION_0)

        // 1. Non-positive timestamps rejected
        assertFalse(tracker.onGyroscopeSample(0L, 0f, 0f, 1f))
        assertFalse(tracker.onGyroscopeSample(-100_000L, 0f, 0f, 1f))

        // 2. Initial positive timestamp accepted
        val t1 = 1_000_000_000L
        assertTrue(tracker.onGyroscopeSample(t1, 0f, 0f, 0f))

        // 3. Duplicate and backwards timestamps rejected
        assertFalse(tracker.onGyroscopeSample(t1, 0f, 0f, 0f))
        assertFalse(tracker.onGyroscopeSample(t1 - 10_000_000L, 0f, 0f, 0f))

        // 4. Old callbacks must not extend rapid motion hold
        // Trigger rapid motion at t1 + 10ms (hold lasts 200ms, until t1 + 210ms)
        val tRapid = t1 + 10_000_000L
        val rapidGz = Math.toRadians(90.0).toFloat()
        assertTrue(tracker.onGyroscopeSample(tRapid, 0f, 0f, rapidGz))
        tracker.onAccelerometerSample(tRapid, 0f, 9.81f, 0f)

        // At tRapid, rapid motion is active
        val activeRapid = tracker.orientationAt(tRapid)
        assertFalse(activeRapid.isReliable)
        assertTrue(activeRapid.rapidMotion)

        // Advance to tExpired = tRapid + 250ms (hold expired)
        val tExpired = tRapid + 250_000_000L
        assertTrue(tracker.onGyroscopeSample(tExpired, 0f, 0f, 0f))
        assertTrue(tracker.onAccelerometerSample(tExpired, 0f, 9.81f, 0f))

        val expiredRapid = tracker.orientationAt(tExpired)
        assertTrue(expiredRapid.isReliable)
        assertFalse(expiredRapid.rapidMotion)

        // An out-of-order callback with old timestamp (tRapid + 100ms) arrives:
        // Must be rejected and must NOT revive or extend the hold
        val oldCallbackAccepted = tracker.onGyroscopeSample(tRapid + 100_000_000L, 0f, 0f, rapidGz)
        assertFalse("Old callback must be rejected by monotonic check", oldCallbackAccepted)

        val checkStillExpired = tracker.orientationAt(tExpired)
        assertTrue("Rapid motion must remain inactive after old callback", checkStillExpired.isReliable)
        assertFalse(checkStillExpired.rapidMotion)
    }

    @Test
    fun accelNonPositiveOrNonMonotonicTimestamp_rejected() {
        val tracker = GroundContactMotionTracker()
        tracker.start(Surface.ROTATION_0)

        // Non-positive timestamps rejected
        assertFalse(tracker.onAccelerometerSample(0L, 0f, 9.81f, 0f))
        assertFalse(tracker.onAccelerometerSample(-50_000_000L, 0f, 9.81f, 0f))

        // Monotonic positive timestamp accepted
        val t = 1_000_000_000L
        assertTrue(tracker.onAccelerometerSample(t, 0f, 9.81f, 0f))

        // Duplicate or older timestamp rejected
        assertFalse(tracker.onAccelerometerSample(t, 0f, 9.81f, 0f))
        assertFalse(tracker.onAccelerometerSample(t - 10_000_000L, 0f, 9.81f, 0f))
    }
}
