package com.ariateam.aria.walking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkingAssistStateMachineTest {
    private val machine = WalkingAssistStateMachine()

    @Test
    fun readyIsAnnouncedOnlyWhenAllInputsIncludingFreshResultAreReady() {
        machine.startSession(generation = 1L, nowElapsedMs = 0L)

        val calibration = machine.update(input(motionCalibrated = false), 100L, nanos(100L))
        assertEquals(WalkingAssistPhase.CALIBRATION_REQUIRED, calibration.current.phase)
        assertEquals(WalkingAssistAnnouncement.CALIBRATION_REQUIRED, calibration.announcement)

        val noResult = machine.update(input(), 200L, nanos(200L))
        assertEquals(WalkingAssistPhase.PREPARING, noResult.current.phase)
        assertNull(noResult.announcement)

        val ready = machine.update(input(resultCaptureTimestampNanos = nanos(300L)), 300L, nanos(300L))
        assertEquals(WalkingAssistPhase.ACTIVE, ready.current.phase)
        assertEquals(WalkingAssistAnnouncement.READY, ready.announcement)
        assertNull(machine.update(input(resultCaptureTimestampNanos = nanos(350L)), 350L, nanos(350L)).announcement)
    }

    @Test
    fun coldCameraAndModelPreparationRemainsQuietPreparingBeyondInitialDeadline() {
        machine.startSession(generation = 1L, nowElapsedMs = 100L)

        val preparing = machine.update(
            input(cameraReady = false, modelReady = false),
            nowElapsedMs = 1_599L,
            nowElapsedNanos = nanos(1_599L),
        )
        assertEquals(WalkingAssistPhase.PREPARING, preparing.current.phase)
        assertEquals(WalkingAssistCause.CAMERA_STARTING, preparing.current.cause)
        assertNull("Cold camera preparation before 1500ms must be quiet", preparing.announcement)

        // At and beyond the 1,500ms initial session deadline, cold camera/model prep
        // must remain quietly PREPARING, not false RESULT_STALE or LIMITED.
        val stillPreparingAt1600 = machine.update(
            input(cameraReady = false, modelReady = false),
            nowElapsedMs = 1_600L,
            nowElapsedNanos = nanos(1_600L),
        )
        assertEquals(WalkingAssistPhase.PREPARING, stillPreparingAt1600.current.phase)
        assertEquals(WalkingAssistCause.CAMERA_STARTING, stillPreparingAt1600.current.cause)
        assertNull("Cold camera preparation at 1600ms must NOT announce RESULT_STALE", stillPreparingAt1600.announcement)

        val stillPreparingAt3000 = machine.update(
            input(cameraReady = false, modelReady = false),
            nowElapsedMs = 3_000L,
            nowElapsedNanos = nanos(3_000L),
        )
        assertEquals(WalkingAssistPhase.PREPARING, stillPreparingAt3000.current.phase)
        assertEquals(WalkingAssistCause.CAMERA_STARTING, stillPreparingAt3000.current.cause)
        assertNull("Prolonged cold startup must remain quiet PREPARING", stillPreparingAt3000.announcement)
    }

    @Test
    fun permissionAndCameraLossBecomeLimitedImmediatelyAndRepeatedStateIsSilent() {
        machine.startSession(1L, 0L)

        val permission = machine.update(input(cameraPermissionGranted = false), 1L, nanos(1L))
        assertEquals(WalkingAssistPhase.LIMITED, permission.current.phase)
        assertEquals(WalkingAssistCause.CAMERA_PERMISSION_REQUIRED, permission.current.cause)
        assertEquals(WalkingAssistAction.GRANT_CAMERA_PERMISSION, permission.current.action)
        assertEquals(WalkingAssistAnnouncement.LIMITED, permission.announcement)
        assertNull(machine.update(input(cameraPermissionGranted = false), 2L, nanos(2L)).announcement)

        val camera = machine.update(
            input(cameraPermissionGranted = true, cameraLost = true),
            3L,
            nanos(3L),
        )
        assertEquals(WalkingAssistCause.CAMERA_UNAVAILABLE, camera.current.cause)
        assertNull(camera.announcement)
    }

    @Test
    fun transientFaultRequiresOneContinuousSecondAndRepeatsDoNotReannounce() {
        machine.startSession(1L, 0L)

        val pending = machine.update(
            input(transientFault = WalkingAssistCause.PIPELINE_STALLED),
            100L,
            nanos(100L),
        )
        assertEquals(WalkingAssistPhase.PREPARING, pending.current.phase)

        val stillPending = machine.update(
            input(transientFault = WalkingAssistCause.PIPELINE_STALLED),
            1_099L,
            nanos(1_099L),
        )
        assertEquals(WalkingAssistPhase.PREPARING, stillPending.current.phase)

        val limited = machine.update(
            input(transientFault = WalkingAssistCause.PIPELINE_STALLED),
            1_100L,
            nanos(1_100L),
        )
        assertEquals(WalkingAssistPhase.LIMITED, limited.current.phase)
        assertEquals(WalkingAssistAnnouncement.LIMITED, limited.announcement)
        assertNull(
            machine.update(
                input(transientFault = WalkingAssistCause.PIPELINE_STALLED),
                1_200L,
                nanos(1_200L),
            ).announcement,
        )
    }

    @Test
    fun persistentFaultIsNotResetByCameraReadinessChanging() {
        machine.startSession(1L, 0L)

        machine.update(
            input(transientFault = WalkingAssistCause.PIPELINE_STALLED),
            100L,
            nanos(100L),
        )
        val limited = machine.update(
            input(cameraReady = false, transientFault = WalkingAssistCause.PIPELINE_STALLED),
            1_100L,
            nanos(1_100L),
        )

        assertEquals(WalkingAssistPhase.LIMITED, limited.current.phase)
        assertEquals(WalkingAssistCause.PIPELINE_STALLED, limited.current.cause)
    }

    @Test
    fun persistentFaultCauseChangesDoNotRestartLimitedTimer() {
        machine.startSession(1L, 0L)

        machine.update(input(transientFault = WalkingAssistCause.PIPELINE_STALLED), 100L, nanos(100L))
        val limited = machine.update(
            input(transientFault = WalkingAssistCause.PIPELINE_ERROR),
            1_100L,
            nanos(1_100L),
        )

        assertEquals(WalkingAssistPhase.LIMITED, limited.current.phase)
        assertEquals(WalkingAssistCause.PIPELINE_ERROR, limited.current.cause)
    }

    @Test
    fun modelFailureRequiresOneContinuousSecondBeforeLimited() {
        machine.startSession(1L, 0L)

        val preparing = machine.update(input(modelReady = false, modelFailed = true), 10L, nanos(10L))
        assertEquals(WalkingAssistPhase.PREPARING, preparing.current.phase)

        val limited = machine.update(input(modelReady = false, modelFailed = true), 1_010L, nanos(1_010L))
        assertEquals(WalkingAssistPhase.LIMITED, limited.current.phase)
        assertEquals(WalkingAssistCause.MODEL_UNAVAILABLE, limited.current.cause)
        assertEquals(WalkingAssistAction.RETRY_MODEL, limited.current.action)
    }

    @Test
    fun recoveryRequiresOneContinuousSecondOfValidSensing() {
        machine.startSession(1L, 0L)
        machine.update(input(cameraLost = true), 10L, nanos(10L))

        val recovering = machine.update(input(resultCaptureTimestampNanos = nanos(100L)), 100L, nanos(100L))
        assertEquals(WalkingAssistPhase.LIMITED, recovering.current.phase)
        assertNull(recovering.announcement)

        machine.update(input(resultCaptureTimestampNanos = nanos(500L)), 500L, nanos(500L))
        val recovered = machine.update(input(resultCaptureTimestampNanos = nanos(1_100L)), 1_100L, nanos(1_100L))
        assertEquals(WalkingAssistPhase.ACTIVE, recovered.current.phase)
        assertEquals(WalkingAssistAnnouncement.RECOVERED, recovered.announcement)
        assertNull(
            machine.update(input(resultCaptureTimestampNanos = nanos(1_200L)), 1_200L, nanos(1_200L))
                .announcement,
        )
    }

    @Test
    fun interruptedRecoveryRestartsStabilityWindow() {
        machine.startSession(1L, 0L)
        machine.update(input(cameraLost = true), 10L, nanos(10L))
        machine.update(input(resultCaptureTimestampNanos = nanos(100L)), 100L, nanos(100L))
        machine.update(input(resultCaptureTimestampNanos = nanos(800L)), 800L, nanos(800L))

        val interrupted = machine.update(input(cameraLost = true), 900L, nanos(900L))
        assertEquals(WalkingAssistPhase.LIMITED, interrupted.current.phase)

        machine.update(input(resultCaptureTimestampNanos = nanos(1_000L)), 1_000L, nanos(1_000L))
        val tooSoon = machine.update(input(resultCaptureTimestampNanos = nanos(1_900L)), 1_900L, nanos(1_900L))
        assertEquals(WalkingAssistPhase.LIMITED, tooSoon.current.phase)
        assertNull(tooSoon.announcement)
    }

    @Test
    fun calibrationRequiredDuringRecoveryDoesNotSkipStableReadyWindow() {
        machine.startSession(1L, 0L)
        machine.update(input(cameraLost = true), 10L, nanos(10L))

        val calibration = machine.update(
            input(motionCalibrated = false),
            100L,
            nanos(100L),
        )
        assertEquals(WalkingAssistPhase.CALIBRATION_REQUIRED, calibration.current.phase)

        machine.update(input(resultCaptureTimestampNanos = nanos(200L)), 200L, nanos(200L))
        val stillRecovering = machine.update(
            input(resultCaptureTimestampNanos = nanos(1_199L)),
            1_199L,
            nanos(1_199L),
        )
        assertEquals(WalkingAssistPhase.CALIBRATION_REQUIRED, stillRecovering.current.phase)
        assertNull(stillRecovering.announcement)

        val recovered = machine.update(input(resultCaptureTimestampNanos = nanos(1_200L)), 1_200L, nanos(1_200L))
        assertEquals(WalkingAssistPhase.ACTIVE, recovered.current.phase)
        assertEquals(WalkingAssistAnnouncement.RECOVERED, recovered.announcement)
    }

    @Test
    fun resultBecomesLimitedAtStalenessBoundaryAndOutOfOrderResultCannotRecover() {
        machine.startSession(1L, 0L)
        machine.update(input(resultCaptureTimestampNanos = nanos(100L)), 100L, nanos(100L))

        val stale = machine.update(input(resultCaptureTimestampNanos = nanos(100L)), 1_600L, nanos(1_600L))
        assertEquals(WalkingAssistPhase.LIMITED, stale.current.phase)
        assertEquals(WalkingAssistCause.RESULT_STALE, stale.current.cause)

        val outOfOrder = machine.update(input(resultCaptureTimestampNanos = nanos(50L)), 1_700L, nanos(1_700L))
        assertEquals(WalkingAssistPhase.LIMITED, outOfOrder.current.phase)
        assertTrue(outOfOrder.changed.not())
    }

    @Test
    fun outOfOrderCaptureCannotReplaceTheLatestFreshResult() {
        machine.startSession(1L, 0L)
        machine.update(input(resultCaptureTimestampNanos = nanos(1_000L)), 1_000L, nanos(1_000L))

        val outOfOrder = machine.update(
            input(resultCaptureTimestampNanos = nanos(100L)),
            1_600L,
            nanos(1_600L),
        )

        assertEquals(WalkingAssistPhase.ACTIVE, outOfOrder.current.phase)
        assertNull(outOfOrder.announcement)
    }

    @Test
    fun preSessionCaptureCannotSatisfyFreshResultRequirement() {
        machine.startSession(1L, 1_000L)

        val oldCapture = machine.update(
            input(resultCaptureTimestampNanos = nanos(999L)),
            1_001L,
            nanos(1_001L),
        )

        assertEquals(WalkingAssistPhase.PREPARING, oldCapture.current.phase)
        assertEquals(WalkingAssistCause.RESULT_STALE, oldCapture.current.cause)
    }

    @Test
    fun captureFromPriorSessionCannotSatisfyFreshResultRequirement() {
        machine.startSession(1L, 0L)
        machine.update(input(resultCaptureTimestampNanos = nanos(900L)), 900L, nanos(900L))
        machine.startSession(2L, 1_000L)

        val priorSessionCapture = machine.update(
            input(
                generation = 2L,
                resultCaptureTimestampNanos = nanos(999L),
            ),
            1_001L,
            nanos(1_001L),
        )

        assertEquals(WalkingAssistPhase.PREPARING, priorSessionCapture.current.phase)
        assertEquals(WalkingAssistCause.RESULT_STALE, priorSessionCapture.current.cause)
    }

    @Test
    fun cameraReadinessLossEventuallyBecomesLimitedWhenResultsStop() {
        machine.startSession(1L, 0L)
        machine.update(input(resultCaptureTimestampNanos = nanos(100L)), 100L, nanos(100L))

        val warming = machine.update(
            input(cameraReady = false, resultCaptureTimestampNanos = nanos(100L)),
            1_599L,
            nanos(1_599L),
        )
        assertEquals(WalkingAssistPhase.PREPARING, warming.current.phase)
        assertEquals(WalkingAssistCause.CAMERA_STARTING, warming.current.cause)
        assertEquals(WalkingAssistAction.WAIT_FOR_CAMERA, warming.current.action)

        val limited = machine.update(
            input(cameraReady = false, resultCaptureTimestampNanos = nanos(100L)),
            1_600L,
            nanos(1_600L),
        )
        assertEquals(WalkingAssistPhase.LIMITED, limited.current.phase)
        assertEquals(WalkingAssistCause.RESULT_STALE, limited.current.cause)
    }

    @Test
    fun failedCalibrationNeverProducesActiveState() {
        machine.startSession(1L, 0L)

        val failed = machine.update(
            input(
                motionCalibrated = false,
                resultCaptureTimestampNanos = nanos(500L),
            ),
            500L,
            nanos(500L),
        )
        assertEquals(WalkingAssistPhase.CALIBRATION_REQUIRED, failed.current.phase)
        assertEquals(WalkingAssistAction.CALIBRATE_FORWARD, failed.current.action)
        assertTrue(failed.current.phase != WalkingAssistPhase.ACTIVE)
    }

    @Test
    fun stopFencesLateCallbacksAndDoesNotRestartTimersWithinSameSession() {
        machine.startSession(1L, 0L)
        val stopped = machine.stopSession(1L)
        assertEquals(WalkingAssistPhase.STOPPED, stopped.current.phase)
        assertEquals(WalkingAssistAnnouncement.STOPPED, stopped.announcement)

        val late = machine.update(input(cameraLost = true), 10_000L, nanos(10_000L))
        assertEquals(WalkingAssistPhase.STOPPED, late.current.phase)
        assertNull(late.announcement)

        machine.startSession(2L, 10_001L)
        val latePriorSession = machine.update(input(generation = 1L), 10_002L, nanos(10_002L))
        assertEquals(WalkingAssistPhase.PREPARING, latePriorSession.current.phase)
        val newSession = machine.update(input(generation = 2L), 10_002L, nanos(10_002L))
        assertEquals(WalkingAssistPhase.PREPARING, newSession.current.phase)
    }

    @Test
    fun automaticCalibrationPendingProducesQuietPreparingStateWithoutVoiceNag() {
        machine.startSession(1L, 0L)

        // When autoCalibrationActive is true and motion is pending calibration,
        // it must produce a quiet PREPARING state with WAIT_FOR_MOTION, without CALIBRATION_REQUIRED nag.
        val pending = machine.update(
            input(motionCalibrated = false, autoCalibrationActive = true),
            100L,
            nanos(100L),
        )
        assertEquals(WalkingAssistPhase.PREPARING, pending.current.phase)
        assertEquals(WalkingAssistCause.SESSION_STARTING, pending.current.cause)
        assertEquals(WalkingAssistAction.WAIT_FOR_MOTION, pending.current.action)
        assertNull("Auto-calibration pending must be completely quiet (no announcement)", pending.announcement)

        // Repeat updates remain quiet and in PREPARING
        val pendingTick2 = machine.update(
            input(motionCalibrated = false, autoCalibrationActive = true),
            200L,
            nanos(200L),
        )
        assertEquals(WalkingAssistPhase.PREPARING, pendingTick2.current.phase)
        assertNull(pendingTick2.announcement)
    }

    @Test
    fun automaticCalibrationCompletionProducesReadyAnnouncementExactlyOnce() {
        machine.startSession(1L, 0L)

        // Auto-calibrating in progress (quiet)
        val pending = machine.update(
            input(motionCalibrated = false, autoCalibrationActive = true),
            100L,
            nanos(100L),
        )
        assertEquals(WalkingAssistPhase.PREPARING, pending.current.phase)
        assertNull(pending.announcement)

        // Motion becomes calibrated and fresh frame arrives
        val ready = machine.update(
            input(motionCalibrated = true, autoCalibrationActive = true, resultCaptureTimestampNanos = nanos(250L)),
            250L,
            nanos(250L),
        )
        assertEquals(WalkingAssistPhase.ACTIVE, ready.current.phase)
        assertEquals(WalkingAssistAnnouncement.READY, ready.announcement)

        // Subsequent active ticks do not re-announce
        val steady = machine.update(
            input(motionCalibrated = true, autoCalibrationActive = true, resultCaptureTimestampNanos = nanos(300L)),
            300L,
            nanos(300L),
        )
        assertEquals(WalkingAssistPhase.ACTIVE, steady.current.phase)
        assertNull(steady.announcement)
    }

    @Test
    fun automaticCalibrationReanchoringIsQuietAndDoesNotNagUser() {
        machine.startSession(1L, 0L)
        // Establish ACTIVE
        machine.update(
            input(motionCalibrated = true, autoCalibrationActive = true, resultCaptureTimestampNanos = nanos(100L)),
            100L,
            nanos(100L),
        )

        // Sudden turn triggers re-anchoring (motion temporarily uncalibrated while auto-cal active)
        val reanchoring = machine.update(
            input(motionCalibrated = false, autoCalibrationActive = true, resultCaptureTimestampNanos = nanos(200L)),
            200L,
            nanos(200L),
        )
        assertEquals(WalkingAssistPhase.PREPARING, reanchoring.current.phase)
        assertEquals(WalkingAssistAction.WAIT_FOR_MOTION, reanchoring.current.action)
        assertNull("Reanchoring must NOT emit CALIBRATION_REQUIRED announcement", reanchoring.announcement)

        // Reanchoring completes after motion settles
        val reanchored = machine.update(
            input(motionCalibrated = true, autoCalibrationActive = true, resultCaptureTimestampNanos = nanos(300L)),
            300L,
            nanos(300L),
        )
        assertEquals(WalkingAssistPhase.ACTIVE, reanchored.current.phase)
    }

    @Test
    fun missingMotionSensorsWithAutoCalibrationBecomesLimitedMotionUnavailable() {
        machine.startSession(1L, 0L)

        // Even when autoCalibrationActive is true, missing motion sensors must not be masked as quiet PREPARING;
        // it must follow the debounce path to LIMITED(MOTION_UNAVAILABLE).
        val initialFault = machine.update(
            input(motionSensorsAvailable = false, autoCalibrationActive = true, motionCalibrated = false),
            100L,
            nanos(100L),
        )
        assertEquals(WalkingAssistPhase.PREPARING, initialFault.current.phase)
        assertEquals(WalkingAssistCause.MOTION_UNAVAILABLE, initialFault.current.cause)
        assertEquals(WalkingAssistAction.WAIT_FOR_MOTION, initialFault.current.action)

        // 1,000ms fault duration -> LIMITED(MOTION_UNAVAILABLE)
        val limitedFault = machine.update(
            input(motionSensorsAvailable = false, autoCalibrationActive = true, motionCalibrated = false),
            1_100L,
            nanos(1_100L),
        )
        assertEquals(WalkingAssistPhase.LIMITED, limitedFault.current.phase)
        assertEquals(WalkingAssistCause.MOTION_UNAVAILABLE, limitedFault.current.cause)
        assertEquals(WalkingAssistAction.WAIT_FOR_MOTION, limitedFault.current.action)
        assertEquals(WalkingAssistAnnouncement.LIMITED, limitedFault.announcement)
    }

    @Test
    fun manualCalibrationModeEmitsCalibrationRequiredActionAndAnnouncement() {
        machine.startSession(1L, 0L)

        // When auto-calibration is not active (manual calibration required),
        // it must emit CALIBRATION_REQUIRED with CALIBRATE_FORWARD action and announcement.
        val manual = machine.update(
            input(motionCalibrated = false, autoCalibrationActive = false),
            100L,
            nanos(100L),
        )
        assertEquals(WalkingAssistPhase.CALIBRATION_REQUIRED, manual.current.phase)
        assertEquals(WalkingAssistCause.CALIBRATION_REQUIRED, manual.current.cause)
        assertEquals(WalkingAssistAction.CALIBRATE_FORWARD, manual.current.action)
        assertEquals(WalkingAssistAnnouncement.CALIBRATION_REQUIRED, manual.announcement)
    }

    @Test
    fun longStartupFollowedByInitialReadyGracePeriodAndTimeout() {
        machine.startSession(1L, 100L)

        // 1. Long cold startup for 3,000ms: must remain quietly PREPARING
        val coldTick = machine.update(
            input(cameraReady = false),
            2_900L,
            nanos(2_900L),
        )
        assertEquals(WalkingAssistPhase.PREPARING, coldTick.current.phase)
        assertEquals(WalkingAssistCause.CAMERA_STARTING, coldTick.current.cause)
        assertNull("Long cold startup must never announce RESULT_STALE", coldTick.announcement)

        // 2. Camera and model become ready at 3,000ms: sensing is now expected -> initial grace period starts
        val readyGrace = machine.update(
            input(cameraReady = true, modelReady = true, motionCalibrated = true, resultCaptureTimestampNanos = null),
            3_000L,
            nanos(3_000L),
        )
        assertEquals(WalkingAssistPhase.PREPARING, readyGrace.current.phase)
        assertEquals(WalkingAssistCause.RESULT_STALE, readyGrace.current.cause)
        assertEquals(WalkingAssistAction.WAIT_FOR_RESULT, readyGrace.current.action)
        assertNull("Initial ready grace window must be quiet (no announcement)", readyGrace.announcement)

        // 3. Still within 1,500ms grace window (1,000ms elapsed since ready)
        val withinGrace = machine.update(
            input(cameraReady = true, modelReady = true, motionCalibrated = true, resultCaptureTimestampNanos = null),
            4_000L,
            nanos(4_000L),
        )
        assertEquals(WalkingAssistPhase.PREPARING, withinGrace.current.phase)
        assertNull(withinGrace.announcement)

        // 4. Grace timeout reached at 4,500ms (1,500ms after ready without frame): now transitions to LIMITED
        val timedOut = machine.update(
            input(cameraReady = true, modelReady = true, motionCalibrated = true, resultCaptureTimestampNanos = null),
            4_500L,
            nanos(4_500L),
        )
        assertEquals(WalkingAssistPhase.LIMITED, timedOut.current.phase)
        assertEquals(WalkingAssistCause.RESULT_STALE, timedOut.current.cause)
        assertEquals(WalkingAssistAction.WAIT_FOR_RESULT, timedOut.current.action)
        assertEquals(WalkingAssistAnnouncement.LIMITED, timedOut.announcement)
    }

    @Test
    fun longStartupFollowedByFreshResultDuringGraceBecomesActive() {
        machine.startSession(1L, 100L)

        // Cold startup until 3,000ms
        machine.update(input(cameraReady = false), 2_999L, nanos(2_999L))

        // Ready at 3,000ms
        machine.update(
            input(cameraReady = true, modelReady = true, motionCalibrated = true, resultCaptureTimestampNanos = null),
            3_000L,
            nanos(3_000L),
        )

        // Result arrives 200ms into grace period at 3,200ms
        val active = machine.update(
            input(cameraReady = true, modelReady = true, motionCalibrated = true, resultCaptureTimestampNanos = nanos(3_200L)),
            3_200L,
            nanos(3_200L),
        )
        assertEquals(WalkingAssistPhase.ACTIVE, active.current.phase)
        assertEquals(WalkingAssistCause.READY, active.current.cause)
        assertEquals(WalkingAssistAnnouncement.READY, active.announcement)

        // Steady active tick does not re-announce
        val steady = machine.update(
            input(cameraReady = true, modelReady = true, motionCalibrated = true, resultCaptureTimestampNanos = nanos(3_250L)),
            3_250L,
            nanos(3_250L),
        )
        assertEquals(WalkingAssistPhase.ACTIVE, steady.current.phase)
        assertNull(steady.announcement)
    }

    @Test
    fun trueActiveStallOccursWhenResultsStopArrivingForPreviouslyActivePipeline() {
        machine.startSession(1L, 100L)

        // Establish ACTIVE with fresh results
        machine.update(
            input(resultCaptureTimestampNanos = nanos(1_000L)),
            1_000L,
            nanos(1_000L),
        )
        val steady = machine.update(
            input(resultCaptureTimestampNanos = nanos(1_200L)),
            1_200L,
            nanos(1_200L),
        )
        assertEquals(WalkingAssistPhase.ACTIVE, steady.current.phase)

        // 1,499ms since last frame: still ACTIVE
        val nearStale = machine.update(
            input(resultCaptureTimestampNanos = nanos(1_200L)),
            2_699L,
            nanos(2_699L),
        )
        assertEquals(WalkingAssistPhase.ACTIVE, nearStale.current.phase)
        assertNull(nearStale.announcement)

        // Exactly 1,500ms since last frame: true active stall reached -> LIMITED
        val activeStall = machine.update(
            input(resultCaptureTimestampNanos = nanos(1_200L)),
            2_700L,
            nanos(2_700L),
        )
        assertEquals(WalkingAssistPhase.LIMITED, activeStall.current.phase)
        assertEquals(WalkingAssistCause.RESULT_STALE, activeStall.current.cause)
        assertEquals(WalkingAssistAction.WAIT_FOR_RESULT, activeStall.current.action)
        assertEquals(WalkingAssistAnnouncement.LIMITED, activeStall.announcement)
    }

    @Test
    fun autoReanchorDuringActiveSessionRemainsQuietPreparingWithoutStaleNoise() {
        machine.startSession(1L, 100L)
        // Establish ACTIVE
        machine.update(
            input(motionCalibrated = true, autoCalibrationActive = true, resultCaptureTimestampNanos = nanos(1_000L)),
            1_000L,
            nanos(1_000L),
        )

        // Turn begins at 1,100L: motion uncalibrated while auto-calibration is active
        val turning = machine.update(
            input(motionCalibrated = false, autoCalibrationActive = true, resultCaptureTimestampNanos = nanos(1_000L)),
            1_100L,
            nanos(1_100L),
        )
        assertEquals(WalkingAssistPhase.PREPARING, turning.current.phase)
        assertEquals(WalkingAssistCause.SESSION_STARTING, turning.current.cause)
        assertEquals(WalkingAssistAction.WAIT_FOR_MOTION, turning.current.action)
        assertNull("Auto-reanchor transition must be quiet", turning.announcement)

        // User turn takes 2,000ms without new frames (3,100L): must remain quiet PREPARING, not false RESULT_STALE
        val stillTurning = machine.update(
            input(motionCalibrated = false, autoCalibrationActive = true, resultCaptureTimestampNanos = nanos(1_000L)),
            3_100L,
            nanos(3_100L),
        )
        assertEquals(WalkingAssistPhase.PREPARING, stillTurning.current.phase)
        assertEquals(WalkingAssistAction.WAIT_FOR_MOTION, stillTurning.current.action)
        assertNull("Extended turning during auto-reanchor must remain quiet", stillTurning.announcement)

        // Motion settles and fresh frame arrives at 3,200L: recovers to ACTIVE
        val settled = machine.update(
            input(motionCalibrated = true, autoCalibrationActive = true, resultCaptureTimestampNanos = nanos(3_200L)),
            3_200L,
            nanos(3_200L),
        )
        assertEquals(WalkingAssistPhase.ACTIVE, settled.current.phase)
        assertEquals(WalkingAssistAnnouncement.READY, settled.announcement)
    }

    @Test
    fun modelFailureTransitionsToModelUnavailableNotResultStale() {
        machine.startSession(1L, 100L)

        // Model failure reported
        val modelFault = machine.update(
            input(modelFailed = true),
            100L,
            nanos(100L),
        )
        assertEquals(WalkingAssistPhase.PREPARING, modelFault.current.phase)
        assertEquals(WalkingAssistCause.MODEL_UNAVAILABLE, modelFault.current.cause)
        assertEquals(WalkingAssistAction.RETRY_MODEL, modelFault.current.action)

        // 1,000ms fault duration -> LIMITED(MODEL_UNAVAILABLE)
        val limitedModel = machine.update(
            input(modelFailed = true),
            1_100L,
            nanos(1_100L),
        )
        assertEquals(WalkingAssistPhase.LIMITED, limitedModel.current.phase)
        assertEquals(WalkingAssistCause.MODEL_UNAVAILABLE, limitedModel.current.cause)
        assertEquals(WalkingAssistAction.RETRY_MODEL, limitedModel.current.action)
        assertEquals(WalkingAssistAnnouncement.LIMITED, limitedModel.announcement)
    }

    @Test
    fun cameraLossAndRevokedPermissionTakeImmediatePrecedenceOverStale() {
        machine.startSession(1L, 100L)

        // Camera loss is immediate LIMITED
        val loss = machine.update(
            input(cameraLost = true),
            100L,
            nanos(100L),
        )
        assertEquals(WalkingAssistPhase.LIMITED, loss.current.phase)
        assertEquals(WalkingAssistCause.CAMERA_UNAVAILABLE, loss.current.cause)
        assertEquals(WalkingAssistAction.RESTORE_CAMERA, loss.current.action)
        assertEquals(WalkingAssistAnnouncement.LIMITED, loss.announcement)

        // Revoked permission is immediate LIMITED
        machine.startSession(2L, 200L)
        val perm = machine.update(
            input(generation = 2L, cameraPermissionGranted = false),
            200L,
            nanos(200L),
        )
        assertEquals(WalkingAssistPhase.LIMITED, perm.current.phase)
        assertEquals(WalkingAssistCause.CAMERA_PERMISSION_REQUIRED, perm.current.cause)
        assertEquals(WalkingAssistAction.GRANT_CAMERA_PERMISSION, perm.current.action)
        assertEquals(WalkingAssistAnnouncement.LIMITED, perm.announcement)
    }

    private fun input(
        generation: Long = 1L,
        sessionActive: Boolean = true,
        explicitlyStopped: Boolean = false,
        cameraPermissionGranted: Boolean = true,
        cameraReady: Boolean = true,
        cameraLost: Boolean = false,
        modelReady: Boolean = true,
        modelFailed: Boolean = false,
        motionSensorsAvailable: Boolean = true,
        motionCalibrated: Boolean = true,
        motionReliable: Boolean = true,
        resultCaptureTimestampNanos: Long? = null,
        transientFault: WalkingAssistCause? = null,
        autoCalibrationActive: Boolean = false,
    ) = WalkingAssistInput(
        sessionGeneration = generation,
        sessionActive = sessionActive,
        explicitlyStopped = explicitlyStopped,
        cameraPermissionGranted = cameraPermissionGranted,
        cameraReady = cameraReady,
        cameraLost = cameraLost,
        modelReady = modelReady,
        modelFailed = modelFailed,
        motionSensorsAvailable = motionSensorsAvailable,
        motionCalibrated = motionCalibrated,
        motionReliable = motionReliable,
        resultCaptureTimestampNanos = resultCaptureTimestampNanos,
        transientFault = transientFault,
        autoCalibrationActive = autoCalibrationActive,
    )

    private fun nanos(milliseconds: Long) = milliseconds * 1_000_000L
}
