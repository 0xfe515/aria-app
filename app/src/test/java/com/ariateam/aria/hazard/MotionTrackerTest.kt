package com.ariateam.aria.hazard

import android.hardware.Sensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTrackerTest {

    @Test
    fun testRejectsStaleGenerationCallbacksAfterRestart() {
        var currentTimeNanos = 1_000_000_000L
        val tracker = MotionTracker(clock = { currentTimeNanos })

        // Start session 1
        val listenerSession1 = tracker.startTestSession(generation = 1L, startNanos = 1_000_000_000L)
        assertTrue(tracker.isRunning)
        assertTrue(tracker.sensorsAvailable)
        assertEquals(0, tracker.engine.accelSamplesReceived)

        // Session 1 receives valid sample
        listenerSession1.dispatchTestSample(
            sensorType = Sensor.TYPE_ACCELEROMETER,
            timestamp = 1_010_000_000L,
            v0 = 0f,
            v1 = 9.8f,
            v2 = 0f,
        )
        assertEquals(1, tracker.engine.accelSamplesReceived)
        assertEquals(1_010_000_000L, tracker.engine.lastAccelNanos)

        // Simulate restart into session 2
        tracker.stop()
        assertFalse(tracker.isRunning)
        assertFalse(tracker.sensorsAvailable)
        assertEquals(0, tracker.engine.accelSamplesReceived)

        val listenerSession2 = tracker.startTestSession(generation = 2L, startNanos = 2_000_000_000L)
        assertTrue(tracker.isRunning)

        // Now a queued callback from session 1 arrives
        listenerSession1.dispatchTestSample(
            sensorType = Sensor.TYPE_ACCELEROMETER,
            timestamp = 2_010_000_000L,
            v0 = 0f,
            v1 = 9.8f,
            v2 = 0f,
        )

        // It should have been rejected because listenerSession1 != activeListener and gen 1 != gen 2
        assertEquals("Stale callback must be rejected without updating engine", 0, tracker.engine.accelSamplesReceived)
        assertEquals(0L, tracker.engine.lastAccelNanos)

        // Now listenerSession2 sends a sample:
        listenerSession2.dispatchTestSample(
            sensorType = Sensor.TYPE_ACCELEROMETER,
            timestamp = 2_010_000_000L,
            v0 = 0f,
            v1 = 9.8f,
            v2 = 0f,
        )
        assertEquals("Session 2 valid sample accepted", 1, tracker.engine.accelSamplesReceived)
        assertEquals(2_010_000_000L, tracker.engine.lastAccelNanos)

        tracker.close()
    }

    @Test
    fun testRejectsEventsEarlierThanSessionStart() {
        val tracker = MotionTracker()
        val listener = tracker.startTestSession(generation = 1L, startNanos = 1_000_000_000L)

        // Sample timestamp earlier than session start (e.g. buffered from hardware HAL before start)
        listener.dispatchTestSample(
            sensorType = Sensor.TYPE_ACCELEROMETER,
            timestamp = 999_999_999L,
            v0 = 0f,
            v1 = 9.8f,
            v2 = 0f,
        )

        assertEquals("Sample before session start must be rejected", 0, tracker.engine.accelSamplesReceived)

        // Valid timestamp
        listener.dispatchTestSample(
            sensorType = Sensor.TYPE_ACCELEROMETER,
            timestamp = 1_000_000_000L,
            v0 = 0f,
            v1 = 9.8f,
            v2 = 0f,
        )
        assertEquals("Sample at session start must be accepted", 1, tracker.engine.accelSamplesReceived)
        assertEquals(1_000_000_000L, tracker.engine.lastAccelNanos)

        tracker.close()
    }

    @Test
    fun testRejectsEventsAfterStop() {
        val tracker = MotionTracker()
        val listener = tracker.startTestSession(generation = 1L, startNanos = 1_000_000_000L)

        tracker.stop()
        assertFalse(tracker.isRunning)

        // Callback arrives after stop
        listener.dispatchTestSample(
            sensorType = Sensor.TYPE_ACCELEROMETER,
            timestamp = 1_010_000_000L,
            v0 = 0f,
            v1 = 9.8f,
            v2 = 0f,
        )

        val state = tracker.stateAt(1_010_000_000L)
        assertEquals("Post-stop callback must be rejected", 0f, state.confidence, 0.001f)
        assertEquals(MotionDegradedReason.SENSORS_UNAVAILABLE, state.degradedReason)

        tracker.close()
    }

    @Test
    fun testStateAtWhenNotRunningReturnsDegradedUnavailable() {
        val tracker = MotionTracker()
        val state = tracker.stateAt(12345L)
        assertEquals(0f, state.confidence, 0.001f)
        assertEquals(MotionDegradedReason.SENSORS_UNAVAILABLE, state.degradedReason)
        assertEquals(12345L, state.timestampNanos)
    }

    @Test
    fun testCalibratedFlowWithBothStreamsYieldsConfidence() {
        var currentTimeNanos = 1_000_000_000L
        val tracker = MotionTracker(
            clock = { currentTimeNanos },
            engine = MotionFusionEngine(defaultHfovDegrees = 65.5f),
        )
        val listener = tracker.startTestSession(generation = 1L, startNanos = 1_000_000_000L)

        for (i in 0 until 6) {
            val ts = 1_000_000_000L + i * 20_000_000L
            currentTimeNanos = ts
            listener.dispatchTestSample(Sensor.TYPE_ACCELEROMETER, ts, 0f, 9.8f, 0f)
            listener.dispatchTestSample(Sensor.TYPE_GYROSCOPE, ts, 0f, 0f, 0f)
        }

        assertTrue("Calibration should succeed", tracker.calibrateForward(currentTimeNanos))
        val state = tracker.stateAt(currentTimeNanos)
        assertTrue("State should be calibrated", state.isCalibrated)
        assertTrue("Confidence should be >= 0.5f", state.confidence >= 0.5f)
        assertTrue("State isReliable should be true", state.isReliable)

        tracker.close()
    }
}
