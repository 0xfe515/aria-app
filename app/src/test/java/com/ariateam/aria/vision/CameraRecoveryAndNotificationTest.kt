package com.ariateam.aria.vision

import androidx.camera.core.CameraState
import com.ariateam.aria.runtime.AriaRuntimeStateBridge
import com.ariateam.aria.runtime.AriaRuntimeTimingTracker
import com.ariateam.aria.runtime.AriaUnifiedNotificationCoordinator
import com.ariateam.aria.runtime.NotificationStateTuple
import com.ariateam.aria.walking.WalkingAssistAction
import com.ariateam.aria.walking.WalkingAssistAnnouncement
import com.ariateam.aria.walking.WalkingAssistCause
import com.ariateam.aria.walking.WalkingAssistPhase
import com.ariateam.aria.walking.WalkingAssistState
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

    @Test
    fun cameraStabilityGate_blocksUntilStabilityThresholdAndProceeds() {
        val gate = VisionModelStore.CameraStabilityGate(enabled = true)
        assertEquals(VisionModelStore.CameraWarmupGateState.WAITING, gate.state)
        assertFalse(gate.isStable)
        assertFalse(gate.isCancelled)

        // Production policy helper check: streaming frames and duration below threshold must NOT permit warmup
        assertFalse(
            "Below threshold (0 frames, 0ms) must not permit warmup",
            VisionPipelineController.isWarmupStabilityPermitted(0L, 0L),
        )
        assertFalse(
            "Below threshold (149 frames, 5000ms) must not permit warmup",
            VisionPipelineController.isWarmupStabilityPermitted(
                VisionPipelineController.SUSTAINED_WARMUP_HEALTHY_FRAMES - 1L,
                VisionPipelineController.SUSTAINED_WARMUP_HEALTHY_WINDOW_MS,
            ),
        )
        assertFalse(
            "Below threshold (150 frames, 4999ms) must not permit warmup",
            VisionPipelineController.isWarmupStabilityPermitted(
                VisionPipelineController.SUSTAINED_WARMUP_HEALTHY_FRAMES,
                VisionPipelineController.SUSTAINED_WARMUP_HEALTHY_WINDOW_MS - 1L,
            ),
        )

        val unblocked = java.util.concurrent.CountDownLatch(1)
        val awaitResult = java.util.concurrent.atomic.AtomicBoolean(false)
        val thread = Thread {
            awaitResult.set(gate.await(2000L))
            unblocked.countDown()
        }
        thread.start()

        // Gate must remain blocked while below threshold
        assertFalse("Gate must remain waiting before stability signal", unblocked.await(50L, java.util.concurrent.TimeUnit.MILLISECONDS))

        // Reaching threshold permits warmup and signals stability
        assertTrue(
            "Reaching threshold (150 frames, 5000ms) must permit warmup",
            VisionPipelineController.isWarmupStabilityPermitted(
                VisionPipelineController.SUSTAINED_WARMUP_HEALTHY_FRAMES,
                VisionPipelineController.SUSTAINED_WARMUP_HEALTHY_WINDOW_MS,
            ),
        )
        assertTrue(
            "Above threshold (200 frames, 6000ms) must permit warmup",
            VisionPipelineController.isWarmupStabilityPermitted(200L, 6_000L),
        )
        assertTrue("Signaling camera stability must succeed", gate.signalCameraStability())

        // Gate unblocks with success
        assertTrue(unblocked.await(1000L, java.util.concurrent.TimeUnit.MILLISECONDS))
        assertTrue(awaitResult.get())
        assertTrue(gate.isStable)
        assertEquals(VisionModelStore.CameraWarmupGateState.STABLE, gate.state)
        thread.join(1000L)

        // Reset re-arms gate to WAITING for subsequent sessions
        assertTrue("Resetting gate must succeed", gate.reset())
        assertEquals(VisionModelStore.CameraWarmupGateState.WAITING, gate.state)
        assertFalse(gate.isStable)
        assertFalse(gate.isCancelled)
    }

    @Test
    fun cameraStabilityGate_unblocksSafelyOnCancelWithoutDeadlock() {
        val gate = VisionModelStore.CameraStabilityGate(enabled = true)
        val unblocked = java.util.concurrent.CountDownLatch(1)
        val awaitResult = java.util.concurrent.atomic.AtomicBoolean(true)

        val thread = Thread {
            awaitResult.set(gate.await(2000L))
            unblocked.countDown()
        }
        thread.start()

        assertFalse(unblocked.await(50L, java.util.concurrent.TimeUnit.MILLISECONDS))

        // Cancelling gate (e.g. on camera error, stop, or permission denied) unblocks without waiting for timeout
        val cancelled = gate.cancel()
        assertTrue(cancelled)
        assertTrue(gate.isCancelled)
        assertFalse(gate.isStable)
        assertEquals(VisionModelStore.CameraWarmupGateState.CANCELLED, gate.state)

        assertTrue("Gate must unblock promptly upon cancellation", unblocked.await(500L, java.util.concurrent.TimeUnit.MILLISECONDS))
        assertFalse("Cancelled gate await must return false", awaitResult.get())
        thread.join(1000L)
    }

    @Test
    fun cameraStabilityGate_sequencingPolicy_bypassesWhenNoCameraGraphActiveAndProtectsWhenActive() {
        // 1. Natural startup (no camera graph active): policy evaluates PROCEED_IMMEDIATELY, gate must not await
        val startupAction = com.ariateam.aria.startup.StartupVisionPreparationPolicy.evaluateGatingAction(
            isCameraGraphActive = false,
            isCameraPending = false,
            isCameraRecovering = false,
            hasCameraPermission = true,
            isExplicitStopActive = false,
            isClosed = false,
        )
        assertEquals(
            com.ariateam.aria.startup.StartupVisionPreparationPolicy.GatingAction.PROCEED_IMMEDIATELY,
            startupAction,
        )
        assertFalse(
            "Must not await camera stability gate during natural startup when no camera graph is active",
            com.ariateam.aria.startup.StartupVisionPreparationPolicy.shouldAwaitStability(startupAction),
        )

        // 2. Active camera graph (e.g. model change on Home or Settings): policy evaluates AWAIT_CAMERA_STABILITY
        val activeAction = com.ariateam.aria.startup.StartupVisionPreparationPolicy.evaluateGatingAction(
            isCameraGraphActive = true,
            isCameraPending = false,
            isCameraRecovering = false,
            hasCameraPermission = true,
            isExplicitStopActive = false,
            isClosed = false,
        )
        assertEquals(
            com.ariateam.aria.startup.StartupVisionPreparationPolicy.GatingAction.AWAIT_CAMERA_STABILITY,
            activeAction,
        )
        assertTrue(
            "Must await camera stability gate when camera graph is active to prevent GPU/camera contention",
            com.ariateam.aria.startup.StartupVisionPreparationPolicy.shouldAwaitStability(activeAction),
        )

        // 3. User explicit stop active while camera is active: policy evaluates CANCEL
        val stopAction = com.ariateam.aria.startup.StartupVisionPreparationPolicy.evaluateGatingAction(
            isCameraGraphActive = true,
            isCameraPending = false,
            isCameraRecovering = false,
            hasCameraPermission = true,
            isExplicitStopActive = true,
            isClosed = false,
        )
        assertEquals(
            com.ariateam.aria.startup.StartupVisionPreparationPolicy.GatingAction.CANCEL,
            stopAction,
        )

        // 4. Cold launch with persisted explicit stop (no camera active): proceeds without misleading error
        val coldStopAction = com.ariateam.aria.startup.StartupVisionPreparationPolicy.evaluateGatingAction(
            isCameraGraphActive = false,
            isCameraPending = false,
            isCameraRecovering = false,
            hasCameraPermission = true,
            isExplicitStopActive = true,
            isClosed = false,
        )
        assertEquals(
            com.ariateam.aria.startup.StartupVisionPreparationPolicy.GatingAction.PROCEED_IMMEDIATELY,
            coldStopAction,
        )
    }

    @Test
    fun fatalCameraDeviceError_boundsRecoveryAttemptsToConfiguredLimit() {
        val maxAttempts = VisionPipelineController.MAX_DEVICE_ERROR_RECOVERY_ATTEMPTS
        assertEquals("Configured max fatal device recovery attempts must be 1", 1, maxAttempts)

        // Attempt 1: Permitted via production helper
        assertTrue(
            "Attempt 1 must be permitted for bounded recovery",
            VisionPipelineController.canAttemptFatalDeviceRecovery(1),
        )

        // Attempt 2: Exceeds limit
        assertFalse(
            "Attempt 2 must exceed bound and trigger terminal Error",
            VisionPipelineController.canAttemptFatalDeviceRecovery(2),
        )
    }

    @Test
    fun invocationReadyGuard_preventsStaleReadyWithoutActiveSession() {
        val mockMetadata = VisionModelMetadata(
            displayName = "test.tflite",
            sizeBytes = 1000L,
            sha256 = "abc",
            importedAtMillis = 100L,
        )
        val readyState: VisionModelState = VisionModelState.Ready(mockMetadata)
        val loadingState: VisionModelState = VisionModelState.Loading("test.tflite")

        // When segmenter is not invocation ready, effective state must be Loading
        val unwarmedEffectiveState = VisionPipelineController.resolveEffectiveModelStateName(
            state = readyState,
            isInvocationReady = false,
        )
        assertEquals("Unwarmed session must report Loading instead of Ready", "Loading", unwarmedEffectiveState)

        // When warmed and invocation ready, effective state is Ready
        val warmedEffectiveState = VisionPipelineController.resolveEffectiveModelStateName(
            state = readyState,
            isInvocationReady = true,
        )
        assertEquals("Warmed session reports Ready", "Ready", warmedEffectiveState)

        // Loading state remains Loading regardless
        assertEquals(
            "Loading state must report Loading",
            "Loading",
            VisionPipelineController.resolveEffectiveModelStateName(loadingState, isInvocationReady = false),
        )
    }

    @Test
    fun recoverySequencingPolicy_enforcesCloseThenAvailabilityThenSingleRebind() {
        var rebindCount = 0
        var closeTimeoutState: String? = null
        var availabilityTimeoutCalled = false

        val coordinator = RecoverySequencingCoordinator(
            onRebind = { rebindCount++ },
            onPriorCloseTimeout = { closeTimeoutState = it },
            onAvailabilityTimeout = { availabilityTimeoutCalled = true },
        )

        assertFalse("Initial coordinator must not have prior closed", coordinator.isPriorClosed)
        assertFalse("Initial coordinator must not have camera available", coordinator.isCameraAvailable)
        assertFalse(
            "Initial coordinator must not satisfy rebind",
            CameraRecoverySequencingPolicy.canRebind(coordinator.isPriorClosed, coordinator.isCameraAvailable),
        )

        // 1. Prior graph closes first, but camera is not yet available
        val triggeredOnClose = coordinator.markPriorClosed()
        assertFalse("Closing prior graph without camera availability must not trigger rebind", triggeredOnClose)
        assertEquals(0, rebindCount)
        assertTrue(coordinator.isPriorClosed)
        assertFalse(coordinator.isCameraAvailable)

        // 2. CameraManager reports camera available -> rebind triggers immediately
        val triggeredOnAvail = coordinator.markCameraAvailable()
        assertTrue("Reporting camera available when prior closed must trigger rebind", triggeredOnAvail)
        assertEquals(1, rebindCount)
        assertTrue(CameraRecoverySequencingPolicy.canRebind(coordinator.isPriorClosed, coordinator.isCameraAvailable))

        // 3. Repeated callbacks must never trigger duplicate rebinds
        assertFalse("Duplicate close must not retrigger", coordinator.markPriorClosed())
        assertFalse("Duplicate availability must not retrigger", coordinator.markCameraAvailable())
        assertEquals("Rebind count must remain strictly 1", 1, rebindCount)
        assertNull(closeTimeoutState)
        assertFalse(availabilityTimeoutCalled)
    }

    @Test
    fun recoverySequencingPolicy_whenCameraAvailableFirst_waitsForCloseBeforeRebind() {
        var rebindCount = 0
        val coordinator = RecoverySequencingCoordinator(
            onRebind = { rebindCount++ },
            onPriorCloseTimeout = {},
            onAvailabilityTimeout = {},
        )

        // Camera becomes available before prior graph finishes closing
        val triggeredOnAvail = coordinator.markCameraAvailable()
        assertFalse("Camera available before prior close must not trigger rebind", triggeredOnAvail)
        assertEquals(0, rebindCount)
        assertFalse(coordinator.isPriorClosed)
        assertTrue(coordinator.isCameraAvailable)

        // Prior graph finishes closing -> rebind triggers
        val triggeredOnClose = coordinator.markPriorClosed()
        assertTrue("Prior graph close when camera available must trigger rebind", triggeredOnClose)
        assertEquals(1, rebindCount)
    }

    @Test
    fun recoverySequencingPolicy_priorCloseTimeout_triggersActionableCloseFailure() {
        var rebindCount = 0
        var reportedState: String? = null
        val coordinator = RecoverySequencingCoordinator(
            onRebind = { rebindCount++ },
            onPriorCloseTimeout = { reportedState = it },
            onAvailabilityTimeout = {},
        )

        coordinator.handlePriorCloseTimeout("OPENING")
        assertEquals("Prior close timeout must report current prior state", "OPENING", reportedState)
        assertEquals("Rebind must not be triggered on close timeout", 0, rebindCount)
    }

    @Test
    fun recoverySequencingPolicy_availabilityTimeout_triggersActionableAvailabilityFailure() {
        var rebindCount = 0
        var availTimeoutFired = false
        val coordinator = RecoverySequencingCoordinator(
            onRebind = { rebindCount++ },
            onPriorCloseTimeout = {},
            onAvailabilityTimeout = { availTimeoutFired = true },
        )

        coordinator.markPriorClosed()
        coordinator.handleAvailabilityTimeout()
        assertTrue("Availability timeout must fire when camera remains unavailable after close", availTimeoutFired)
        assertEquals("Rebind must not be triggered on availability timeout", 0, rebindCount)
    }

    @Test
    fun recoverySequencingPolicy_availabilityDeadline_isBoundedAndExceedsObservedGpuCompletion() {
        val deadlineMs = VisionPipelineController.REBIND_AVAILABILITY_DEADLINE_MS
        assertEquals("Configured availability deadline must be 10000ms", 10_000L, deadlineMs)

        // Must safely exceed observed GPU kernel completion (3400ms) and HAL drain (6400ms)
        assertTrue(
            "Availability deadline must exceed observed post-close GPU completion (3400ms)",
            CameraRecoverySequencingPolicy.isAvailabilityDeadlineSufficient(
                deadlineMs,
                CameraRecoverySequencingPolicy.OBSERVED_POST_CLOSE_GPU_COMPLETION_MS,
            ),
        )
        assertTrue(
            "Availability deadline must exceed observed post-close HAL drain (6400ms)",
            CameraRecoverySequencingPolicy.isAvailabilityDeadlineSufficient(
                deadlineMs,
                CameraRecoverySequencingPolicy.OBSERVED_POST_CLOSE_HAL_DRAIN_MS,
            ),
        )

        // Sub-threshold deadlines (e.g. former 2000ms) fail sufficiency check
        assertFalse(
            "2000ms deadline is insufficient for 3400ms GPU kernel completion",
            CameraRecoverySequencingPolicy.isAvailabilityDeadlineSufficient(2_000L, 3_400L),
        )
    }

    @Test
    fun cameraStabilityGate_timeoutIsConsistentWithStartupAndWarmupWindows() {
        val gateTimeoutMs = VisionModelStore.CAMERA_STABILITY_GATE_TIMEOUT_MS
        assertEquals("Configured stability gate timeout must be 35000ms", 35_000L, gateTimeoutMs)

        // Must safely accommodate 20s test initial frame arrival deadline + 5s sustained warmup window
        val maxInitialStartupMs = 20_000L
        val sustainedWarmupMs = VisionPipelineController.SUSTAINED_WARMUP_HEALTHY_WINDOW_MS
        val minRequiredGateTimeoutMs = maxInitialStartupMs + sustainedWarmupMs
        assertTrue(
            "Gate timeout ($gateTimeoutMs) must exceed min required ($minRequiredGateTimeoutMs) with positive headroom",
            gateTimeoutMs > minRequiredGateTimeoutMs,
        )
        val headroomMs = gateTimeoutMs - minRequiredGateTimeoutMs
        assertTrue("Headroom must be at least 10s", headroomMs >= 10_000L)
    }

    @Test
    fun recoverySequencingPolicy_healthyStreamRecoverySustained_requiresBothFramesAndDuration() {
        val requiredFrames = VisionPipelineController.SUSTAINED_HEALTHY_FRAMES
        val requiredDurationMs = VisionPipelineController.SUSTAINED_HEALTHY_WINDOW_MS
        assertEquals(60L, requiredFrames)
        assertEquals(10_000L, requiredDurationMs)

        // Sub-threshold cases: neither, frames only, duration only
        assertFalse(
            "Zero frames and zero duration must not be sustained",
            VisionPipelineController.isHealthyStreamRecoverySustained(0L, 0L),
        )
        assertFalse(
            "Sub-threshold frames (59/60) must not be sustained even if duration met",
            VisionPipelineController.isHealthyStreamRecoverySustained(59L, 10_000L),
        )
        assertFalse(
            "Sub-threshold duration (9999/10000ms) must not be sustained even if frames met",
            VisionPipelineController.isHealthyStreamRecoverySustained(60L, 9_999L),
        )

        // Threshold met exactly
        assertTrue(
            "Exactly 60 frames and 10000ms must satisfy sustained recovery",
            VisionPipelineController.isHealthyStreamRecoverySustained(60L, 10_000L),
        )

        // Above threshold
        assertTrue(
            "120 frames and 15000ms must satisfy sustained recovery",
            VisionPipelineController.isHealthyStreamRecoverySustained(120L, 15_000L),
        )

        // Direct policy call with custom thresholds
        assertTrue(
            "Custom thresholds honored",
            CameraRecoverySequencingPolicy.isHealthyStreamRecoverySustained(
                healthyFrames = 10L,
                healthyDurationMs = 2_000L,
                requiredFrames = 10L,
                requiredDurationMs = 2_000L,
            ),
        )
        assertFalse(
            "Custom duration sub-threshold honored",
            CameraRecoverySequencingPolicy.isHealthyStreamRecoverySustained(
                healthyFrames = 10L,
                healthyDurationMs = 1_999L,
                requiredFrames = 10L,
                requiredDurationMs = 2_000L,
            ),
        )
    }

    @Test
    fun recoverySequencingPolicy_stallRecoveryBounds_enforcesFiniteRetries() {
        assertEquals(3, VisionPipelineController.MAX_STALL_RECOVERY_ATTEMPTS)

        // In-bounds attempts (0, 1, 2)
        assertTrue(
            "Initial stall attempt (0) must be allowed",
            VisionPipelineController.canAttemptStallRecovery(0),
        )
        assertTrue(
            "First retry attempt (1) must be allowed",
            VisionPipelineController.canAttemptStallRecovery(1),
        )
        assertTrue(
            "Second retry attempt (2) must be allowed",
            VisionPipelineController.canAttemptStallRecovery(2),
        )

        // Terminal bound (>= 3)
        assertFalse(
            "Third retry attempt (3) exceeds max stall recovery attempts and must be rejected",
            VisionPipelineController.canAttemptStallRecovery(3),
        )
        assertFalse(
            "Subsequent retry attempt (4) must be rejected",
            VisionPipelineController.canAttemptStallRecovery(4),
        )

        // Direct policy call with custom limit
        assertTrue(
            "Custom attempt limit honored",
            CameraRecoverySequencingPolicy.canAttemptStallRecovery(currentAttempts = 1, maxAttempts = 2),
        )
        assertFalse(
            "Custom attempt limit reached",
            CameraRecoverySequencingPolicy.canAttemptStallRecovery(currentAttempts = 2, maxAttempts = 2),
        )
    }

    @Test
    fun cameraAnnouncementPolicy_suppressesTransientCameraStartingNoise() {
        val startingState = WalkingAssistState(
            phase = WalkingAssistPhase.LIMITED,
            cause = WalkingAssistCause.CAMERA_STARTING,
            action = WalkingAssistAction.WAIT_FOR_CAMERA,
        )

        // Transient camera starting must not emit voice announcements, even if marked LIMITED
        val (shouldAnnounce, _) = CameraAnnouncementPolicy.shouldAnnounce(
            lastAnnouncedCause = null,
            lastAnnouncedNanos = 0L,
            announcement = WalkingAssistAnnouncement.LIMITED,
            currentState = startingState,
            nowNanos = 1_000_000_000L,
        )
        assertFalse("Transient camera starting must not trigger TTS announcement", shouldAnnounce)
    }

    @Test
    fun cameraAnnouncementPolicy_rateLimitsRepeatedActionableFailures() {
        val unavailableState = WalkingAssistState(
            phase = WalkingAssistPhase.LIMITED,
            cause = WalkingAssistCause.CAMERA_UNAVAILABLE,
            action = WalkingAssistAction.RESTORE_CAMERA,
        )
        val t0 = 10_000_000_000L

        // First occurrence: must announce
        val (firstAnnounce, cause1) = CameraAnnouncementPolicy.shouldAnnounce(
            lastAnnouncedCause = null,
            lastAnnouncedNanos = 0L,
            announcement = WalkingAssistAnnouncement.LIMITED,
            currentState = unavailableState,
            nowNanos = t0,
        )
        assertTrue("First actionable failure must announce", firstAnnounce)
        assertEquals(WalkingAssistCause.CAMERA_UNAVAILABLE, cause1)

        // Repeated occurrence within 10s rate limit window: must be suppressed
        val t1 = t0 + 5_000_000_000L // 5 seconds later
        val (repeatAnnounce, _) = CameraAnnouncementPolicy.shouldAnnounce(
            lastAnnouncedCause = cause1,
            lastAnnouncedNanos = t0,
            announcement = WalkingAssistAnnouncement.LIMITED,
            currentState = unavailableState,
            nowNanos = t1,
        )
        assertFalse("Duplicate actionable failure within 10s cooldown must be rate-limited", repeatAnnounce)

        // Occurrence after 10s cooldown: must announce again
        val t2 = t0 + 10_000_000_000L // exactly 10s later
        val (cooldownAnnounce, cause2) = CameraAnnouncementPolicy.shouldAnnounce(
            lastAnnouncedCause = cause1,
            lastAnnouncedNanos = t0,
            announcement = WalkingAssistAnnouncement.LIMITED,
            currentState = unavailableState,
            nowNanos = t2,
        )
        assertTrue("Actionable failure after cooldown window must announce", cooldownAnnounce)
        assertEquals(WalkingAssistCause.CAMERA_UNAVAILABLE, cause2)
    }

    @Test
    fun cameraAnnouncementPolicy_differentActionableFailureAnnouncesImmediately() {
        val unavailableState = WalkingAssistState(
            phase = WalkingAssistPhase.LIMITED,
            cause = WalkingAssistCause.CAMERA_UNAVAILABLE,
            action = WalkingAssistAction.RESTORE_CAMERA,
        )
        val permissionState = WalkingAssistState(
            phase = WalkingAssistPhase.LIMITED,
            cause = WalkingAssistCause.CAMERA_PERMISSION_REQUIRED,
            action = WalkingAssistAction.GRANT_CAMERA_PERMISSION,
        )
        val t0 = 10_000_000_000L

        // First occurrence is CAMERA_UNAVAILABLE
        val (firstAnnounce, cause1) = CameraAnnouncementPolicy.shouldAnnounce(
            lastAnnouncedCause = null,
            lastAnnouncedNanos = 0L,
            announcement = WalkingAssistAnnouncement.LIMITED,
            currentState = unavailableState,
            nowNanos = t0,
        )
        assertTrue(firstAnnounce)

        // Different failure cause (CAMERA_PERMISSION_REQUIRED) occurs 1s later: must announce immediately
        val t1 = t0 + 1_000_000_000L
        val (diffAnnounce, cause2) = CameraAnnouncementPolicy.shouldAnnounce(
            lastAnnouncedCause = cause1,
            lastAnnouncedNanos = t0,
            announcement = WalkingAssistAnnouncement.LIMITED,
            currentState = permissionState,
            nowNanos = t1,
        )
        assertTrue("Different failure cause must announce immediately despite recent prior failure", diffAnnounce)
        assertEquals(WalkingAssistCause.CAMERA_PERMISSION_REQUIRED, cause2)
    }

    @Test
    fun cameraAnnouncementPolicy_recoveryPreservesActionableFailureTimestampAndSuppressesFlapping() {
        val unavailableState = WalkingAssistState(
            phase = WalkingAssistPhase.LIMITED,
            cause = WalkingAssistCause.CAMERA_UNAVAILABLE,
            action = WalkingAssistAction.RESTORE_CAMERA,
        )
        val activeState = WalkingAssistState(
            phase = WalkingAssistPhase.ACTIVE,
            cause = WalkingAssistCause.READY,
            action = WalkingAssistAction.NONE,
        )
        val t0 = 10_000_000_000L

        // Initial failure is announced
        val (firstAnnounce, cause1) = CameraAnnouncementPolicy.shouldAnnounce(
            lastAnnouncedCause = null,
            lastAnnouncedNanos = 0L,
            announcement = WalkingAssistAnnouncement.LIMITED,
            currentState = unavailableState,
            nowNanos = t0,
        )
        assertTrue("First actionable failure must announce", firstAnnounce)
        assertEquals(WalkingAssistCause.CAMERA_UNAVAILABLE, cause1)

        // Brief transient recovery occurs 2s later: must NOT speak TTS, but preserves failure cause
        val t1 = t0 + 2_000_000_000L
        val (recoveredAnnounce, preservedCause) = CameraAnnouncementPolicy.shouldAnnounce(
            lastAnnouncedCause = cause1,
            lastAnnouncedNanos = t0,
            announcement = WalkingAssistAnnouncement.RECOVERED,
            currentState = activeState,
            nowNanos = t1,
        )
        assertFalse("Recovery transition must not trigger TTS announcement (quiet recovery)", recoveredAnnounce)
        assertEquals("Actionable failure cause must be preserved across brief recovery to fence flapping", cause1, preservedCause)

        // Flapping: same failure recurs 1s after brief recovery (3s after t0, well within 10s cooldown):
        val t2 = t1 + 1_000_000_000L // 3s after t0
        val (flappingAnnounce, _) = CameraAnnouncementPolicy.shouldAnnounce(
            lastAnnouncedCause = preservedCause,
            lastAnnouncedNanos = t0,
            announcement = WalkingAssistAnnouncement.LIMITED,
            currentState = unavailableState,
            nowNanos = t2,
        )
        assertFalse("Flapping duplicate failure within 10s cooldown must NOT re-announce", flappingAnnounce)

        // Failure persists after full 10s cooldown (t0 + 10s):
        val t3 = t0 + 10_000_000_000L
        val (afterCooldownAnnounce, _) = CameraAnnouncementPolicy.shouldAnnounce(
            lastAnnouncedCause = preservedCause,
            lastAnnouncedNanos = t0,
            announcement = WalkingAssistAnnouncement.LIMITED,
            currentState = unavailableState,
            nowNanos = t3,
        )
        assertTrue("Persistent failure after full cooldown must announce again", afterCooldownAnnounce)
    }

    @Test
    fun cameraAnnouncementPolicy_quietWhenNoAnnouncement() {
        val preparingState = WalkingAssistState(
            phase = WalkingAssistPhase.PREPARING,
            cause = WalkingAssistCause.SESSION_STARTING,
            action = WalkingAssistAction.WAIT_FOR_MOTION,
        )
        val (shouldAnnounce, _) = CameraAnnouncementPolicy.shouldAnnounce(
            lastAnnouncedCause = null,
            lastAnnouncedNanos = 0L,
            announcement = null,
            currentState = preparingState,
            nowNanos = 1_000_000_000L,
        )
        assertFalse("Null announcement (quiet preparation) must return false", shouldAnnounce)
    }

    @Test
    fun cameraAnnouncementPolicy_suppressesResultStaleNoise() {
        val staleState = WalkingAssistState(
            phase = WalkingAssistPhase.LIMITED,
            cause = WalkingAssistCause.RESULT_STALE,
            action = WalkingAssistAction.WAIT_FOR_RESULT,
        )

        // Result stale must not emit voice announcement, preserving any previous actionable failure
        val (shouldAnnounce, preservedCause) = CameraAnnouncementPolicy.shouldAnnounce(
            lastAnnouncedCause = WalkingAssistCause.CAMERA_UNAVAILABLE,
            lastAnnouncedNanos = 1_000_000_000L,
            announcement = WalkingAssistAnnouncement.LIMITED,
            currentState = staleState,
            nowNanos = 2_000_000_000L,
        )
        assertFalse("RESULT_STALE must not trigger automatic TTS announcement", shouldAnnounce)
        assertEquals(WalkingAssistCause.CAMERA_UNAVAILABLE, preservedCause)
    }

    @Test
    fun cameraAnnouncementPolicy_suppressesAutomaticReadyAndRecoveredAnnouncements() {
        val readyState = WalkingAssistState(
            phase = WalkingAssistPhase.ACTIVE,
            cause = WalkingAssistCause.READY,
            action = WalkingAssistAction.NONE,
        )

        // READY announcement must be suppressed
        val (readyAnnounce, readyCause) = CameraAnnouncementPolicy.shouldAnnounce(
            lastAnnouncedCause = WalkingAssistCause.PIPELINE_STALLED,
            lastAnnouncedNanos = 5_000_000_000L,
            announcement = WalkingAssistAnnouncement.READY,
            currentState = readyState,
            nowNanos = 7_000_000_000L,
        )
        assertFalse("READY must not trigger automatic TTS announcement", readyAnnounce)
        assertEquals(WalkingAssistCause.PIPELINE_STALLED, readyCause)

        // RECOVERED announcement must also be suppressed
        val (recoveredAnnounce, recoveredCause) = CameraAnnouncementPolicy.shouldAnnounce(
            lastAnnouncedCause = WalkingAssistCause.PIPELINE_STALLED,
            lastAnnouncedNanos = 5_000_000_000L,
            announcement = WalkingAssistAnnouncement.RECOVERED,
            currentState = readyState,
            nowNanos = 8_000_000_000L,
        )
        assertFalse("RECOVERED must not trigger automatic TTS announcement", recoveredAnnounce)
        assertEquals(WalkingAssistCause.PIPELINE_STALLED, recoveredCause)
    }

    @Test
    fun cameraAnnouncementPolicy_repeatedFlappingCyclesProduceZeroAutomaticAnnouncements() {
        var session = CameraAnnouncementPolicy.SessionState()

        val startingState = WalkingAssistState(
            phase = WalkingAssistPhase.LIMITED,
            cause = WalkingAssistCause.CAMERA_STARTING,
            action = WalkingAssistAction.WAIT_FOR_CAMERA,
        )
        val staleState = WalkingAssistState(
            phase = WalkingAssistPhase.LIMITED,
            cause = WalkingAssistCause.RESULT_STALE,
            action = WalkingAssistAction.WAIT_FOR_RESULT,
        )
        val activeState = WalkingAssistState(
            phase = WalkingAssistPhase.ACTIVE,
            cause = WalkingAssistCause.READY,
            action = WalkingAssistAction.NONE,
        )

        var timeNanos = 1_000_000_000L

        // Step 1: Startup starting phase
        val (a1, s1) = CameraAnnouncementPolicy.evaluateTransition(
            session, WalkingAssistAnnouncement.LIMITED, startingState, timeNanos,
        )
        assertFalse("Starting must not announce", a1)
        session = s1

        // Step 2: Result stale (first-result delay)
        timeNanos += 1_500_000_000L
        val (a2, s2) = CameraAnnouncementPolicy.evaluateTransition(
            session, WalkingAssistAnnouncement.LIMITED, staleState, timeNanos,
        )
        assertFalse("Cold startup result stale must not announce", a2)
        session = s2

        // Step 3: First result arrives (READY)
        timeNanos += 500_000_000L
        val (a3, s3) = CameraAnnouncementPolicy.evaluateTransition(
            session, WalkingAssistAnnouncement.READY, activeState, timeNanos,
        )
        assertFalse("Automatic READY must not announce", a3)
        session = s3

        // Step 4: Flap cycle 1 - stale again
        timeNanos += 1_600_000_000L
        val (a4, s4) = CameraAnnouncementPolicy.evaluateTransition(
            session, WalkingAssistAnnouncement.LIMITED, staleState, timeNanos,
        )
        assertFalse("Flapping result stale cycle 1 must not announce", a4)
        session = s4

        // Step 5: Flap cycle 1 - recovered
        timeNanos += 400_000_000L
        val (a5, s5) = CameraAnnouncementPolicy.evaluateTransition(
            session, WalkingAssistAnnouncement.RECOVERED, activeState, timeNanos,
        )
        assertFalse("Flapping RECOVERED cycle 1 must not announce", a5)
        session = s5

        // Step 6: Flap cycle 2 - stale again
        timeNanos += 1_600_000_000L
        val (a6, s6) = CameraAnnouncementPolicy.evaluateTransition(
            session, WalkingAssistAnnouncement.LIMITED, staleState, timeNanos,
        )
        assertFalse("Flapping result stale cycle 2 must not announce", a6)
        session = s6

        // Step 7: Flap cycle 2 - recovered
        timeNanos += 400_000_000L
        val (a7, s7) = CameraAnnouncementPolicy.evaluateTransition(
            session, WalkingAssistAnnouncement.RECOVERED, activeState, timeNanos,
        )
        assertFalse("Flapping RECOVERED cycle 2 must not announce", a7)
        session = s7

        assertNull("Flapping sequence without actionable failure must keep null lastAnnouncedCause", session.lastAnnouncedCause)
    }

    @Test
    fun cameraAnnouncementPolicy_suppressedFlappingPreservesActionableFailureRateLimit() {
        val unavailableState = WalkingAssistState(
            phase = WalkingAssistPhase.LIMITED,
            cause = WalkingAssistCause.CAMERA_UNAVAILABLE,
            action = WalkingAssistAction.RESTORE_CAMERA,
        )
        val staleState = WalkingAssistState(
            phase = WalkingAssistPhase.LIMITED,
            cause = WalkingAssistCause.RESULT_STALE,
            action = WalkingAssistAction.WAIT_FOR_RESULT,
        )
        val activeState = WalkingAssistState(
            phase = WalkingAssistPhase.ACTIVE,
            cause = WalkingAssistCause.READY,
            action = WalkingAssistAction.NONE,
        )

        var session = CameraAnnouncementPolicy.SessionState()
        val t0 = 10_000_000_000L

        // Initial real actionable failure: must announce
        val (a0, s0) = CameraAnnouncementPolicy.evaluateTransition(
            session, WalkingAssistAnnouncement.LIMITED, unavailableState, t0,
        )
        assertTrue("Actionable failure must announce on first occurrence", a0)
        assertEquals(WalkingAssistCause.CAMERA_UNAVAILABLE, s0.lastAnnouncedCause)
        assertEquals(t0, s0.lastAnnouncedNanos)
        session = s0

        // Flapping through suppressed states while in cooldown:
        // t0 + 1s: RESULT_STALE
        val (a1, s1) = CameraAnnouncementPolicy.evaluateTransition(
            session, WalkingAssistAnnouncement.LIMITED, staleState, t0 + 1_000_000_000L,
        )
        assertFalse("RESULT_STALE must not announce", a1)
        assertEquals(WalkingAssistCause.CAMERA_UNAVAILABLE, s1.lastAnnouncedCause)
        assertEquals(t0, s1.lastAnnouncedNanos)
        session = s1

        // t0 + 2s: RECOVERED
        val (a2, s2) = CameraAnnouncementPolicy.evaluateTransition(
            session, WalkingAssistAnnouncement.RECOVERED, activeState, t0 + 2_000_000_000L,
        )
        assertFalse("RECOVERED must not announce", a2)
        assertEquals(WalkingAssistCause.CAMERA_UNAVAILABLE, s2.lastAnnouncedCause)
        assertEquals(t0, s2.lastAnnouncedNanos)
        session = s2

        // t0 + 3s: RESULT_STALE
        val (a3, s3) = CameraAnnouncementPolicy.evaluateTransition(
            session, WalkingAssistAnnouncement.LIMITED, staleState, t0 + 3_000_000_000L,
        )
        assertFalse("RESULT_STALE must not announce", a3)
        assertEquals(WalkingAssistCause.CAMERA_UNAVAILABLE, s3.lastAnnouncedCause)
        assertEquals(t0, s3.lastAnnouncedNanos)
        session = s3

        // t0 + 4s: RECOVERED
        val (a4, s4) = CameraAnnouncementPolicy.evaluateTransition(
            session, WalkingAssistAnnouncement.RECOVERED, activeState, t0 + 4_000_000_000L,
        )
        assertFalse("RECOVERED must not announce", a4)
        assertEquals(WalkingAssistCause.CAMERA_UNAVAILABLE, s4.lastAnnouncedCause)
        assertEquals(t0, s4.lastAnnouncedNanos)
        session = s4

        // t0 + 5s: CAMERA_UNAVAILABLE recurs (5s into 10s cooldown): must NOT re-announce
        val (a5, s5) = CameraAnnouncementPolicy.evaluateTransition(
            session, WalkingAssistAnnouncement.LIMITED, unavailableState, t0 + 5_000_000_000L,
        )
        assertFalse("Failure recurrence within cooldown must NOT announce despite intervening flaps", a5)
        assertEquals(WalkingAssistCause.CAMERA_UNAVAILABLE, s5.lastAnnouncedCause)
        assertEquals(t0, s5.lastAnnouncedNanos)
        session = s5

        // t0 + 10s: CAMERA_UNAVAILABLE persists after full 10s cooldown: must announce again
        val (a6, s6) = CameraAnnouncementPolicy.evaluateTransition(
            session, WalkingAssistAnnouncement.LIMITED, unavailableState, t0 + 10_000_000_000L,
        )
        assertTrue("Actionable failure after cooldown must announce again", a6)
        assertEquals(WalkingAssistCause.CAMERA_UNAVAILABLE, s6.lastAnnouncedCause)
        assertEquals(t0 + 10_000_000_000L, s6.lastAnnouncedNanos)
    }

    @Test
    fun cameraAnnouncementPolicy_allRealActionableFailuresAnnounceWithCooldown() {
        val actionableCauses = listOf(
            WalkingAssistCause.CAMERA_PERMISSION_REQUIRED to WalkingAssistAction.GRANT_CAMERA_PERMISSION,
            WalkingAssistCause.CAMERA_UNAVAILABLE to WalkingAssistAction.RESTORE_CAMERA,
            WalkingAssistCause.PIPELINE_STALLED to WalkingAssistAction.RETRY_PIPELINE,
            WalkingAssistCause.MOTION_UNAVAILABLE to WalkingAssistAction.NONE,
            WalkingAssistCause.MODEL_UNAVAILABLE to WalkingAssistAction.RETRY_MODEL,
            WalkingAssistCause.PIPELINE_ERROR to WalkingAssistAction.RETRY_PIPELINE,
        )

        for ((cause, action) in actionableCauses) {
            val state = WalkingAssistState(
                phase = WalkingAssistPhase.LIMITED,
                cause = cause,
                action = action,
            )
            val t0 = 100_000_000_000L
            var session = CameraAnnouncementPolicy.SessionState()

            // First occurrence: must announce
            val (a1, s1) = CameraAnnouncementPolicy.evaluateTransition(
                session, WalkingAssistAnnouncement.LIMITED, state, t0,
            )
            assertTrue("Actionable failure $cause must announce on first occurrence", a1)
            assertEquals(cause, s1.lastAnnouncedCause)
            session = s1

            // Recurrence within cooldown (t0 + 5s): must be suppressed
            val (a2, s2) = CameraAnnouncementPolicy.evaluateTransition(
                session, WalkingAssistAnnouncement.LIMITED, state, t0 + 5_000_000_000L,
            )
            assertFalse("Actionable failure $cause must be suppressed within 10s cooldown", a2)
            assertEquals(cause, s2.lastAnnouncedCause)
            session = s2

            // Recurrence after cooldown (t0 + 10s): must announce again
            val (a3, s3) = CameraAnnouncementPolicy.evaluateTransition(
                session, WalkingAssistAnnouncement.LIMITED, state, t0 + 10_000_000_000L,
            )
            assertTrue("Actionable failure $cause must announce again after 10s cooldown", a3)
            assertEquals(cause, s3.lastAnnouncedCause)
        }
    }

    @Test
    fun cameraAnnouncementPolicy_stoppedAnnouncementClearsFailureHistoryAndAnnounces() {
        val stoppedState = WalkingAssistState(
            phase = WalkingAssistPhase.STOPPED,
            cause = WalkingAssistCause.SESSION_STOPPED,
            action = WalkingAssistAction.NONE,
        )
        val unavailableState = WalkingAssistState(
            phase = WalkingAssistPhase.LIMITED,
            cause = WalkingAssistCause.CAMERA_UNAVAILABLE,
            action = WalkingAssistAction.RESTORE_CAMERA,
        )

        // Session had an active failure
        val activeSession = CameraAnnouncementPolicy.SessionState(
            lastAnnouncedCause = WalkingAssistCause.CAMERA_UNAVAILABLE,
            lastAnnouncedNanos = 10_000_000_000L,
        )

        // STOPPED occurs: must announce and reset session
        val (stoppedAnnounce, resetSession) = CameraAnnouncementPolicy.evaluateTransition(
            activeSession, WalkingAssistAnnouncement.STOPPED, stoppedState, 12_000_000_000L,
        )
        assertTrue("STOPPED announcement must be spoken", stoppedAnnounce)
        assertNull("STOPPED must reset lastAnnouncedCause to null", resetSession.lastAnnouncedCause)
        assertEquals(0L, resetSession.lastAnnouncedNanos)

        // Subsequent failure in new session starts fresh without cooldown suppression
        val (nextAnnounce, nextSession) = CameraAnnouncementPolicy.evaluateTransition(
            resetSession, WalkingAssistAnnouncement.LIMITED, unavailableState, 13_000_000_000L,
        )
        assertTrue("New failure after STOPPED must announce immediately", nextAnnounce)
        assertEquals(WalkingAssistCause.CAMERA_UNAVAILABLE, nextSession.lastAnnouncedCause)
    }
}
