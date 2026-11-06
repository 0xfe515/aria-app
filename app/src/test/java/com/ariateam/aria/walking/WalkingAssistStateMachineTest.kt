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
    fun missingResultDeadlineIncludesCameraAndModelPreparation() {
        machine.startSession(generation = 1L, nowElapsedMs = 100L)

        val preparing = machine.update(
            input(cameraReady = false, modelReady = false),
            nowElapsedMs = 1_599L,
            nowElapsedNanos = nanos(1_599L),
        )
        assertEquals(WalkingAssistPhase.PREPARING, preparing.current.phase)

        val unavailable = machine.update(
            input(cameraReady = false, modelReady = false),
            nowElapsedMs = 1_600L,
            nowElapsedNanos = nanos(1_600L),
        )
        assertEquals(WalkingAssistPhase.LIMITED, unavailable.current.phase)
        assertEquals(WalkingAssistCause.RESULT_STALE, unavailable.current.cause)
        assertEquals(WalkingAssistAnnouncement.LIMITED, unavailable.announcement)
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
    )

    private fun nanos(milliseconds: Long) = milliseconds * 1_000_000L
}
