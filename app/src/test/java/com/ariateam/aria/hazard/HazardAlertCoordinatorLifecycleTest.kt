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

    @Test
    fun testHazardAlertSink_receivesMonotonicSequenceAndCorrectEvent() {
        val sinkEvents = mutableListOf<HazardAlertEvent>()
        coordinator.setAlertSink { event -> sinkEvents.add(event) }
        coordinator.start()

        // Frame 1 requires confirmation
        val ts1 = 10_050_000_000L
        currentRealtimeMs = 10_050L
        currentElapsedNanos = ts1
        coordinator.processAssessment(candidateAssessment(ts1), ts1)
        assertEquals(0, sinkEvents.size)

        // Frame 2 confirms and fires alert #1
        val ts2 = 10_100_000_000L
        currentRealtimeMs = 10_100L
        currentElapsedNanos = ts2
        coordinator.processAssessment(candidateAssessment(ts2), ts2)

        assertEquals("Sink should receive confirmed alert", 1, sinkEvents.size)
        val event1 = sinkEvents[0]
        assertEquals(1L, event1.sequence)
        assertEquals(ts2, event1.timestampNanos)
        assertEquals("위험 (진행 경로 차단)", event1.message)
        assertEquals(HazardLevel.PATH_BLOCKED, event1.level)

        // Advance beyond cooldown (2500ms) to fire alert #2
        currentRealtimeMs += 3_000L
        currentElapsedNanos += 3_000_000_000L
        val ts3 = currentElapsedNanos

        // Since consecutiveRiskCount was already confirmed in frames 1 & 2,
        // cooldown expiry causes ongoing hazard to fire alert #2 immediately
        coordinator.processAssessment(candidateAssessment(ts3), ts3)
        assertEquals("Sink should receive alert #2", 2, sinkEvents.size)

        val event2 = sinkEvents[1]
        assertEquals(2L, event2.sequence)
        assertEquals(ts3, event2.timestampNanos)
        assertEquals(event1.session, event2.session)

        // Additional frame within cooldown is suppressed
        currentRealtimeMs += 50L
        currentElapsedNanos += 50_000_000L
        val ts4 = currentElapsedNanos
        coordinator.processAssessment(candidateAssessment(ts4), ts4)
        assertEquals("Frame within cooldown must not fire extra alert", 2, sinkEvents.size)

        coordinator.close()
    }

    @Test
    fun testHazardAlertSink_exceptionDoesNotCrashOrCorruptState() {
        coordinator.setAlertSink { _ ->
            throw RuntimeException("Simulated external TTS failure")
        }
        coordinator.start()

        val ts1 = 10_050_000_000L
        currentRealtimeMs = 10_050L
        currentElapsedNanos = ts1
        coordinator.processAssessment(candidateAssessment(ts1), ts1)

        val ts2 = 10_100_000_000L
        currentRealtimeMs = 10_100L
        currentElapsedNanos = ts2
        // Must not throw when sink fails
        coordinator.processAssessment(candidateAssessment(ts2), ts2)

        // Haptic and toast still fired
        assertEquals(1, vibrationLog.size)
        assertEquals(1, toastLog.size)

        coordinator.close()
    }

    @Test
    fun testSharedAlertSink_invokedWhenInstanceSinkIsNull() {
        val sharedLog = mutableListOf<HazardAlertEvent>()
        HazardAlertCoordinator.setSharedAlertSink { event -> sharedLog.add(event) }
        coordinator.setAlertSink(null)
        coordinator.start()

        val ts1 = 10_050_000_000L
        currentRealtimeMs = 10_050L
        currentElapsedNanos = ts1
        coordinator.processAssessment(candidateAssessment(ts1), ts1)

        val ts2 = 10_100_000_000L
        currentRealtimeMs = 10_100L
        currentElapsedNanos = ts2
        coordinator.processAssessment(candidateAssessment(ts2), ts2)

        assertEquals(1, sharedLog.size)
        assertEquals(1L, sharedLog[0].sequence)

        HazardAlertCoordinator.setSharedAlertSink(null)
        coordinator.close()
    }
}
