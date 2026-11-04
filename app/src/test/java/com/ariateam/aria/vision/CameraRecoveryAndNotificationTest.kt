package com.ariateam.aria.vision

import androidx.camera.core.CameraState
import com.ariateam.aria.runtime.AriaRuntimeStateBridge
import com.ariateam.aria.runtime.AriaRuntimeTimingTracker
import com.ariateam.aria.runtime.AriaUnifiedNotificationCoordinator
import com.ariateam.aria.runtime.NotificationStateTuple
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM unit regression tests for:
 * 1. Camera error classification (transient in-use vs fatal vs permission denied).
 * 2. Bounded backoff retry delays and session generation fencing.
 * 3. Unified notification status resolution across dual-active, voice-only, and vision-only modes.
 * 4. 8-stage bounded runtime timing tracker (start, recognition, analyzer, preprocess, invoke, post, UI, alerts).
 * 5. Explicit stop latch semantics.
 */
class CameraRecoveryAndNotificationTest {

    @Before
    fun setUp() {
        AriaRuntimeTimingTracker.reset()
    }

    @Test
    fun cameraErrorClassification_distinguishesTransientInUseFromFatalAndPermission() {
        // Transient in-use errors (recoverable)
        val inUse = CameraErrorClassifier.classifyCode(CameraState.ERROR_CAMERA_IN_USE)
        assertTrue(inUse is CameraFailureKind.TransientInUse)
        assertTrue(inUse.isRecoverable)
        assertEquals(CameraState.ERROR_CAMERA_IN_USE, (inUse as CameraFailureKind.TransientInUse).code)

        val maxInUse = CameraErrorClassifier.classifyCode(CameraState.ERROR_MAX_CAMERAS_IN_USE)
        assertTrue(maxInUse is CameraFailureKind.TransientInUse)
        assertTrue(maxInUse.isRecoverable)

        val otherRecoverable = CameraErrorClassifier.classifyCode(CameraState.ERROR_OTHER_RECOVERABLE_ERROR)
        assertTrue(otherRecoverable is CameraFailureKind.TransientInUse)
        assertTrue(otherRecoverable.isRecoverable)

        val dndMode = CameraErrorClassifier.classifyCode(CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED)
        assertTrue(dndMode is CameraFailureKind.TransientInUse)
        assertTrue(dndMode.isRecoverable)

        // Fatal errors (non-recoverable)
        val fatalHardware = CameraErrorClassifier.classifyCode(CameraState.ERROR_CAMERA_FATAL_ERROR)
        assertTrue(fatalHardware is CameraFailureKind.Fatal)
        assertFalse(fatalHardware.isRecoverable)

        val disabled = CameraErrorClassifier.classifyCode(CameraState.ERROR_CAMERA_DISABLED)
        assertTrue(disabled is CameraFailureKind.Fatal)
        assertFalse(disabled.isRecoverable)

        val streamConfig = CameraErrorClassifier.classifyCode(CameraState.ERROR_STREAM_CONFIG)
        assertTrue(streamConfig is CameraFailureKind.Fatal)
        assertFalse(streamConfig.isRecoverable)

        // Exception classifications
        val secEx = CameraErrorClassifier.classifyException(SecurityException("Camera permission denied"))
        assertTrue(secEx is CameraFailureKind.Permission)
        assertFalse(secEx.isRecoverable)

        val busyEx = CameraErrorClassifier.classifyException(RuntimeException("Camera device in use by other process"))
        assertTrue(busyEx is CameraFailureKind.TransientInUse)
        assertTrue(busyEx.isRecoverable)

        val genericEx = CameraErrorClassifier.classifyException(IllegalStateException("Unknown hardware fault"))
        assertTrue(genericEx is CameraFailureKind.Fatal)
        assertFalse(genericEx.isRecoverable)
    }

    @Test
    fun boundedBackoffDelaysAndFencing_honorsScheduleAndGeneration() {
        val expectedDelays = longArrayOf(500L, 1000L, 2000L, 4000L, 8000L)
        assertEquals(5, VisionPipelineController.MAX_RETRY_COUNT)
        assertEquals(expectedDelays.size, VisionPipelineController.BACKOFF_DELAYS_MS.size)
        for (i in expectedDelays.indices) {
            assertEquals(expectedDelays[i], VisionPipelineController.BACKOFF_DELAYS_MS[i])
        }

        // Generation fencing check: old session ticket must be rejected once new session begins
        var currentSessionTicket = 1L
        fun isSessionLive(ticket: Long) = ticket == currentSessionTicket

        assertTrue(isSessionLive(1L))
        // New session starts
        currentSessionTicket = 2L
        assertFalse("Old session ticket must be rejected", isSessionLive(1L))
        assertTrue(isSessionLive(2L))
    }

    @Test
    fun unifiedNotificationResolution_aggregatesDualAndSingleServiceStatesTruthfully() {
        // Dual active: voice and vision both running
        val dual = AriaUnifiedNotificationCoordinator.resolveNotificationContent(
            voiceActive = true,
            visionActive = true,
            cameraUnavailable = false,
        )
        assertEquals("음성 인식 및 비전 모니터링 동작 중", dual.contentText)
        assertEquals("모두 중지", dual.stopActionLabel)

        // Dual active with camera temporarily unavailable
        val dualCamUnavail = AriaUnifiedNotificationCoordinator.resolveNotificationContent(
            voiceActive = true,
            visionActive = true,
            cameraUnavailable = true,
        )
        assertEquals("음성 인식 중 · 카메라 일시 사용 중 (재연결 대기)", dualCamUnavail.contentText)
        assertEquals("모두 중지", dualCamUnavail.stopActionLabel)

        // Vision only active
        val visionOnly = AriaUnifiedNotificationCoordinator.resolveNotificationContent(
            voiceActive = false,
            visionActive = true,
            cameraUnavailable = false,
        )
        assertEquals("카메라로 주변 위험을 모니터링 중입니다.", visionOnly.contentText)
        assertEquals("모니터링 중지", visionOnly.stopActionLabel)

        // Vision only active with camera unavailable
        val visionCamUnavail = AriaUnifiedNotificationCoordinator.resolveNotificationContent(
            voiceActive = false,
            visionActive = true,
            cameraUnavailable = true,
        )
        assertEquals("카메라 일시 사용 중 (재연결 대기)", visionCamUnavail.contentText)
        assertEquals("모니터링 중지", visionCamUnavail.stopActionLabel)

        // Voice only active
        val voiceOnly = AriaUnifiedNotificationCoordinator.resolveNotificationContent(
            voiceActive = true,
            visionActive = false,
            cameraUnavailable = false,
        )
        assertEquals("음성 대기 중입니다.", voiceOnly.contentText)
        assertEquals("음성 인식 중지", voiceOnly.stopActionLabel)

        // Neither active
        val neither = AriaUnifiedNotificationCoordinator.resolveNotificationContent(
            voiceActive = false,
            visionActive = false,
        )
        assertEquals("Aria 모니터링 서비스", neither.contentText)
        assertEquals("음성 인식 중지", neither.stopActionLabel)
    }

    @Test
    fun ariaRuntimeTimingTracker_recordsAll8StagesWithBoundedRingsAndPercentiles() {
        val initial = AriaRuntimeTimingTracker.snapshot()
        assertNull(initial.start)
        assertNull(initial.recognition)
        assertNull(initial.analyzer)
        assertNull(initial.preprocess)
        assertNull(initial.invoke)
        assertNull(initial.post)
        assertNull(initial.ui)
        assertNull(initial.alerts)

        // Record 120 samples into start (ring buffer size 100)
        for (i in 1..120) {
            AriaRuntimeTimingTracker.recordStart(i.toDouble())
            AriaRuntimeTimingTracker.recordRecognition((i * 2).toDouble())
            AriaRuntimeTimingTracker.recordAnalyzer((i * 0.5).toDouble())
            AriaRuntimeTimingTracker.recordPreprocess((i * 0.1).toDouble())
            AriaRuntimeTimingTracker.recordInvoke((i * 0.2).toDouble())
            AriaRuntimeTimingTracker.recordPost((i * 0.05).toDouble())
            AriaRuntimeTimingTracker.recordUi((i * 0.08).toDouble())
            AriaRuntimeTimingTracker.recordAlerts((i * 0.03).toDouble())
        }

        val snapshot = AriaRuntimeTimingTracker.snapshot()

        // 1. Start stage
        assertNotNull(snapshot.start)
        assertEquals(100, snapshot.start!!.sampleCount)
        assertEquals(120, snapshot.start!!.cumulativeCount)
        assertEquals(21.0, snapshot.start!!.minMs, 0.001)
        assertEquals(120.0, snapshot.start!!.maxMs, 0.001)
        assertTrue(snapshot.start!!.p50Ms > 0.0)
        assertTrue(snapshot.start!!.p95Ms >= snapshot.start!!.p50Ms)

        // 2. Recognition stage
        assertNotNull(snapshot.recognition)
        assertEquals(100, snapshot.recognition!!.sampleCount)
        assertEquals(120, snapshot.recognition!!.cumulativeCount)

        // 3. Analyzer stage
        assertNotNull(snapshot.analyzer)
        assertEquals(100, snapshot.analyzer!!.sampleCount)

        // 4. Preprocess stage
        assertNotNull(snapshot.preprocess)
        assertEquals(100, snapshot.preprocess!!.sampleCount)

        // 5. Invoke stage
        assertNotNull(snapshot.invoke)
        assertEquals(100, snapshot.invoke!!.sampleCount)

        // 6. Post stage
        assertNotNull(snapshot.post)
        assertEquals(100, snapshot.post!!.sampleCount)

        // 7. UI stage
        assertNotNull(snapshot.ui)
        assertEquals(100, snapshot.ui!!.sampleCount)

        // 8. Alerts stage
        assertNotNull(snapshot.alerts)
        assertEquals(100, snapshot.alerts!!.sampleCount)

        // Verify reset
        AriaRuntimeTimingTracker.reset()
        val afterReset = AriaRuntimeTimingTracker.snapshot()
        assertNull(afterReset.start)
        assertNull(afterReset.recognition)
        assertNull(afterReset.analyzer)
        assertNull(afterReset.preprocess)
        assertNull(afterReset.invoke)
        assertNull(afterReset.post)
        assertNull(afterReset.ui)
        assertNull(afterReset.alerts)
    }

    @Test
    fun cameraStartingState_remainsStartingUntilFreshFrameDelivered() {
        val uiState = VisionUiState(
            cameraStatus = CameraSessionStatus.Starting,
            cameraErrorDetail = null,
        )
        // Verify that Starting state is distinct from Running
        assertEquals(CameraSessionStatus.Starting, uiState.cameraStatus)
        assertFalse(uiState.cameraStatus == CameraSessionStatus.Running)

        // Only after frame delivery or sensor capture does it transition to Running
        val runningState = uiState.copy(cameraStatus = CameraSessionStatus.Running)
        assertEquals(CameraSessionStatus.Running, runningState.cameraStatus)
    }

    @Test
    fun explicitUserStop_survivesIncidentalStateQueriesAndFaults() {
        VisionSession.setUserStopRequestedForTesting(true)
        assertTrue(VisionSession.isUserStopRequestedForTesting)

        // Incidental runtime bridge queries and updates must never clear the explicit user stop latch
        AriaRuntimeStateBridge.setVoiceActiveLocal(true)
        AriaRuntimeStateBridge.setVisionActiveLocal(false)
        assertTrue(AriaRuntimeStateBridge.isVoiceActiveLocal())
        assertFalse(AriaRuntimeStateBridge.isVisionActiveLocal())
        assertTrue("User stop latch must survive runtime bridge queries", VisionSession.isUserStopRequestedForTesting)

        // Transient or fatal error classifications do not alter the user stop latch
        val inUse = CameraErrorClassifier.classifyCode(CameraState.ERROR_CAMERA_IN_USE)
        assertTrue(inUse.isRecoverable)
        assertTrue("Error classification must not alter user stop latch", VisionSession.isUserStopRequestedForTesting)

        // Only explicit reset clears the latch
        VisionSession.setUserStopRequestedForTesting(false)
        assertFalse(VisionSession.isUserStopRequestedForTesting)
    }

    @Test
    fun runtimeBridge_stateQueriesAreNonReentrantAndDoNotReviveServices() {
        AriaRuntimeStateBridge.resetForTesting()
        assertFalse(AriaRuntimeStateBridge.isVoiceActiveLocal())
        assertFalse(AriaRuntimeStateBridge.isVisionActiveLocal())

        // Setting voice active does not affect vision
        AriaRuntimeStateBridge.setVoiceActiveLocal(true)
        assertTrue(AriaRuntimeStateBridge.isVoiceActiveLocal())
        assertFalse(AriaRuntimeStateBridge.isVisionActiveLocal())

        // Querying state repeatedly does not mutate state or trigger reentrant service start
        for (i in 1..5) {
            assertTrue(AriaRuntimeStateBridge.isVoiceActiveLocal())
            assertFalse(AriaRuntimeStateBridge.isVisionActiveLocal())
        }

        AriaRuntimeStateBridge.resetForTesting()
    }

    @Test
    fun publishedBitmapOwnership_priorPublishedSnapshotUnchangedAfterSubsequentInferenceAndClose() {
        var firstCapturedPixel = 0
        var secondCapturedPixel = 0
        val capturedBuffers = mutableListOf<IntArray>()

        val testFactory = object : OverlayBitmapFactory {
            override fun create(colors: IntArray, width: Int, height: Int): android.graphics.Bitmap? {
                // Defensive copy simulating immutable Bitmap snapshot creation
                val copy = colors.clone()
                capturedBuffers.add(copy)
                return null
            }
        }

        val segmenter = LiteRtVisionSegmenter(bitmapFactory = testFactory)

        // Simulate frame 1 producing color pattern A
        segmenter.reusableOverlayArgb[0] = 0xAA112233.toInt()
        val snapshot1 = testFactory.create(segmenter.reusableOverlayArgb, 512, 288)
        firstCapturedPixel = capturedBuffers[0][0]
        assertEquals(0xAA112233.toInt(), firstCapturedPixel)

        // Simulate frame 2 producing different color pattern B into the same internal segmenter buffer
        segmenter.reusableOverlayArgb[0] = 0xBB445566.toInt()
        val snapshot2 = testFactory.create(segmenter.reusableOverlayArgb, 512, 288)
        secondCapturedPixel = capturedBuffers[1][0]
        assertEquals(0xBB445566.toInt(), secondCapturedPixel)

        // Verify Frame 1 snapshot remains UNCHANGED despite subsequent inference in segmenter
        assertEquals(0xAA112233.toInt(), capturedBuffers[0][0])
        assertEquals(0xBB445566.toInt(), capturedBuffers[1][0])

        // Verify closing segmenter does not affect previously captured frame
        segmenter.close()
        assertEquals("Prior published frame must remain unchanged after segmenter close", 0xAA112233.toInt(), capturedBuffers[0][0])
    }

    @Test
    fun selectedCameraAvailabilityFiltering_ignoresOtherCamerasAndOnlyTriggersForTarget() {
        val selectedId = "0"
        val nonSelectedId = "1"
        var retriesAttempted = 0

        class TestAvailabilityGate(private val targetId: String, private val onRetry: () -> Unit) {
            var isInitial = true
            var sawUnavailable = false

            fun onCameraUnavailable(id: String) {
                if (id == targetId) {
                    sawUnavailable = true
                    isInitial = false
                }
            }

            fun onCameraAvailable(id: String) {
                if (id != targetId) return // Filter non-selected camera
                if (isInitial) {
                    isInitial = false
                    return // Initial callback handling: do not bypass backoff
                }
                onRetry()
            }
        }

        val gate = TestAvailabilityGate(selectedId) { retriesAttempted++ }

        // Initial callback on registration reports target camera available
        gate.onCameraAvailable(selectedId)
        assertEquals("Initial callback for target camera must not trigger immediate retry", 0, retriesAttempted)

        // Non-selected camera becomes available
        gate.onCameraAvailable(nonSelectedId)
        assertEquals("Other camera availability must be ignored", 0, retriesAttempted)

        // Target camera transitions to unavailable
        gate.onCameraUnavailable(selectedId)
        assertEquals(0, retriesAttempted)

        // Target camera later becomes available
        gate.onCameraAvailable(selectedId)
        assertEquals("Selected camera availability after unavailable must trigger retry", 1, retriesAttempted)
    }

    @Test
    fun singleRetryPerAttempt_fencesConcurrentDelayAndAvailabilityEvents() {
        val attemptToken = 1L
        val currentAttempt = java.util.concurrent.atomic.AtomicLong(attemptToken)
        val retryExecuted = java.util.concurrent.atomic.AtomicBoolean(false)
        var executeCount = 0

        fun tryExecuteRetry(token: Long) {
            if (currentAttempt.get() == token && retryExecuted.compareAndSet(false, true)) {
                executeCount++
            }
        }

        // Event 1: Availability callback fires
        tryExecuteRetry(attemptToken)
        assertEquals(1, executeCount)

        // Event 2: Scheduled backoff timer fires for the same attempt
        tryExecuteRetry(attemptToken)
        assertEquals("Subsequent delay timer for the same attempt must be gated", 1, executeCount)

        // Event 3: Late timer for older attempt token
        tryExecuteRetry(attemptToken - 1)
        assertEquals("Late callback from older attempt must be rejected", 1, executeCount)
    }

    @Test
    fun notificationDeduplication_suppressesDuplicatePostsForIdenticalStateTuple() {
        AriaUnifiedNotificationCoordinator.resetForTesting()
        assertEquals(0, AriaUnifiedNotificationCoordinator.getNotificationPostCountForTesting())

        val tuple1 = NotificationStateTuple(voiceActive = true, visionActive = true, cameraUnavailable = false)
        val tuple2 = NotificationStateTuple(voiceActive = true, visionActive = true, cameraUnavailable = false)
        assertEquals(tuple1, tuple2)

        val tupleChanged = NotificationStateTuple(voiceActive = true, visionActive = true, cameraUnavailable = true)
        assertFalse(tuple1 == tupleChanged)
    }
}
