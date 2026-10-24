package com.ariateam.aria.hazard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HazardAlertCoordinatorLifecycleTest {

    private var currentRealtimeMs = 10_000L
    private var currentElapsedNanos = 10_000_000_000L
    private val vibrationLog = mutableListOf<HazardLevel>()
    private val toastLog = mutableListOf<String>()

    private lateinit var coordinator: HazardAlertCoordinator

    @Before
    fun setUp() {
        currentRealtimeMs = 10_000L
        currentElapsedNanos = 10_000_000_000L
        vibrationLog.clear()
        toastLog.clear()

        coordinator = HazardAlertCoordinator(
            context = null,
            policy = HazardAlertPolicy(
                confirmationFramesRequired = 2,
                alertCooldownMs = 2_500L,
                staleResultExpiryMs = 1_500L,
                clockMs = { currentRealtimeMs },
            ),
            clockNanos = { currentElapsedNanos },
            vibratorDispatcher = { level -> vibrationLog.add(level) },
            toastDispatcher = { msg -> toastLog.add(msg) },
        )
    }

    private fun candidateAssessment(timestampNanos: Long): HazardAssessment {
        return HazardAssessment(
            timestampNanos = timestampNanos,
            level = HazardLevel.PATH_BLOCKED,
            activeCorridor = CorridorDirection.CENTER,
            dominantNearClass = 1,
            corridorWalkableRatio = 0.1f,
            nearObstacleOccupancy = 0.6f,
            motionConfidence = 0.9f,
            messageKorean = "위험 (진행 경로 차단)",
            isAlertCandidate = true,
        )
    }

    @Test
    fun testRejectsAssessmentOlderThanSessionStart() {
        // Start session at 10_000_000_000L
        coordinator.start()
        assertTrue(coordinator.isSessionActive)

        // Late frame arrives with timestamp 9_900_000_000L (prior session / before start)
        coordinator.processAssessment(
            candidateAssessment(9_900_000_000L),
            captureTimestampNanos = 9_900_000_000L,
        )

        // Should be completely rejected (no vibration, no toast)
        assertEquals(0, vibrationLog.size)
        assertEquals(0, toastLog.size)

        coordinator.close()
    }

    @Test
    fun testTwoConsecutiveFramesAfterSessionStartFireAlert() {
        coordinator.start()

        // Frame 1 at 10_050_000_000L
        val ts1 = 10_050_000_000L
        currentRealtimeMs = 10_050L
        currentElapsedNanos = ts1
        coordinator.processAssessment(candidateAssessment(ts1), ts1)
        assertEquals("Frame 1 requires confirmation", 0, vibrationLog.size)

        // Frame 2 at 10_100_000_000L
        val ts2 = 10_100_000_000L
        currentRealtimeMs = 10_100L
        currentElapsedNanos = ts2
        coordinator.processAssessment(candidateAssessment(ts2), ts2)

        assertEquals("Frame 2 should fire vibration", 1, vibrationLog.size)
        assertEquals(HazardLevel.PATH_BLOCKED, vibrationLog[0])
        assertEquals("Frame 2 should fire toast", 1, toastLog.size)

        coordinator.close()
    }

    @Test
    fun testCrossingStopAndRestartRejectsOldAssessment() {
        coordinator.start()

        // Frame 1 in session 1
        val ts1 = 10_050_000_000L
        currentRealtimeMs = 10_050L
        currentElapsedNanos = ts1
        coordinator.processAssessment(candidateAssessment(ts1), ts1)

        // Stop session 1
        coordinator.stop()
        assertFalse(coordinator.isSessionActive)

        // Start session 2 at 20_000_000_000L
        currentRealtimeMs = 20_000L
        currentElapsedNanos = 20_000_000_000L
        coordinator.start()

        // Stale frame from session 1 arrives
        coordinator.processAssessment(candidateAssessment(ts1), ts1)

        assertEquals("Old session frame must not fire in new session", 0, vibrationLog.size)
        assertEquals(0, toastLog.size)

        coordinator.close()
    }

    @Test
    fun testToastExpiryRejectsStaleToast() {
        var testNanos = 10_000_000_000L
        var scheduledToast: Runnable? = null

        val lateClockCoordinator = HazardAlertCoordinator(
            context = null,
            policy = HazardAlertPolicy(
                confirmationFramesRequired = 1,
                alertCooldownMs = 2_500L,
                staleResultExpiryMs = 1_500L,
                clockMs = { testNanos / 1_000_000L },
            ),
            clockNanos = { testNanos },
            vibratorDispatcher = { level -> vibrationLog.add(level) },
            toastPoster = { runnable -> scheduledToast = runnable },
            toastDispatcher = { msg -> toastLog.add(msg) },
        )
        lateClockCoordinator.start()
        lateClockCoordinator.processAssessment(candidateAssessment(testNanos), testNanos)

        // Vibration fires synchronously
        assertEquals(1, vibrationLog.size)
        // Toast is deferred via toastPoster
        assertEquals(0, toastLog.size)

        // Advance elapsed time by 2 seconds (> 1500ms expiry)
        testNanos += 2_000_000_000L
        scheduledToast?.run()

        assertEquals("Toast should be discarded if age > 1500ms", 0, toastLog.size)

        lateClockCoordinator.close()
        coordinator.close()
    }
}
