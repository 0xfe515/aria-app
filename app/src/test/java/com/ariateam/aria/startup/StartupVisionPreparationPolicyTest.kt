package com.ariateam.aria.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupVisionPreparationPolicyTest {

    @Test
    fun naturalColdStartup_whenNoCameraGraphActive_proceedsImmediatelyWithoutAwaiting() {
        val action = StartupVisionPreparationPolicy.evaluateGatingAction(
            isCameraGraphActive = false,
            isCameraPending = false,
            isCameraRecovering = false,
            hasCameraPermission = true,
            isExplicitStopActive = false,
            isClosed = false,
        )
        assertEquals(StartupVisionPreparationPolicy.GatingAction.PROCEED_IMMEDIATELY, action)
        assertFalse(StartupVisionPreparationPolicy.shouldAwaitStability(action))
    }

    @Test
    fun activeCameraGraph_enforcesCameraStabilityGatingToProtectFromContention() {
        val action = StartupVisionPreparationPolicy.evaluateGatingAction(
            isCameraGraphActive = true,
            isCameraPending = false,
            isCameraRecovering = false,
            hasCameraPermission = true,
            isExplicitStopActive = false,
            isClosed = false,
        )
        assertEquals(StartupVisionPreparationPolicy.GatingAction.AWAIT_CAMERA_STABILITY, action)
        assertTrue(StartupVisionPreparationPolicy.shouldAwaitStability(action))
    }

    @Test
    fun pendingCameraStart_enforcesCameraStabilityGating() {
        val action = StartupVisionPreparationPolicy.evaluateGatingAction(
            isCameraGraphActive = false,
            isCameraPending = true,
            isCameraRecovering = false,
            hasCameraPermission = true,
            isExplicitStopActive = false,
            isClosed = false,
        )
        assertEquals(StartupVisionPreparationPolicy.GatingAction.AWAIT_CAMERA_STABILITY, action)
        assertTrue(StartupVisionPreparationPolicy.shouldAwaitStability(action))
    }

    @Test
    fun activeRecoveryInFlight_enforcesCameraStabilityGating() {
        val action = StartupVisionPreparationPolicy.evaluateGatingAction(
            isCameraGraphActive = false,
            isCameraPending = false,
            isCameraRecovering = true,
            hasCameraPermission = true,
            isExplicitStopActive = false,
            isClosed = false,
        )
        assertEquals(StartupVisionPreparationPolicy.GatingAction.AWAIT_CAMERA_STABILITY, action)
        assertTrue(StartupVisionPreparationPolicy.shouldAwaitStability(action))
    }

    @Test
    fun terminalCameraFailure_historicalCountersRemain_proceedsImmediatelyWithoutGateTimeout() {
        // After terminal failCameraSession: running=false, owner=null, UI Error, retryRunnable=null.
        // Even if historical counters were > 0, live recovery is false and no camera graph can deliver frames.
        val action = StartupVisionPreparationPolicy.evaluateGatingAction(
            isCameraGraphActive = false,
            isCameraPending = false,
            isCameraRecovering = false,
            hasCameraPermission = true,
            isExplicitStopActive = false,
            isClosed = false,
        )
        assertEquals(StartupVisionPreparationPolicy.GatingAction.PROCEED_IMMEDIATELY, action)
        assertFalse(StartupVisionPreparationPolicy.shouldAwaitStability(action))
    }

    @Test
    fun coldLaunch_withPersistedExplicitStop_proceedsImmediatelyWithoutMisleadingModelError() {
        // Cold launch where user previously stopped session: no camera is active.
        // Model must compile cleanly (no camera_stability_cancelled error); camera remains stopped.
        val action = StartupVisionPreparationPolicy.evaluateGatingAction(
            isCameraGraphActive = false,
            isCameraPending = false,
            isCameraRecovering = false,
            hasCameraPermission = true,
            isExplicitStopActive = true,
            isClosed = false,
        )
        assertEquals(
            "Cold launch with explicit stop must not fail model restore with misleading error",
            StartupVisionPreparationPolicy.GatingAction.PROCEED_IMMEDIATELY,
            action,
        )
        assertFalse(StartupVisionPreparationPolicy.shouldAwaitStability(action))
    }

    @Test
    fun explicitStopDuringActiveSession_cancelsStabilityGatePromptly() {
        // User triggers stop while camera was actively running/binding: gate must cancel promptly
        val action = StartupVisionPreparationPolicy.evaluateGatingAction(
            isCameraGraphActive = true,
            isCameraPending = false,
            isCameraRecovering = false,
            hasCameraPermission = true,
            isExplicitStopActive = true,
            isClosed = false,
        )
        assertEquals(StartupVisionPreparationPolicy.GatingAction.CANCEL, action)
        assertFalse(StartupVisionPreparationPolicy.shouldAwaitStability(action))
    }

    @Test
    fun closedController_cancelsStabilityGateImmediately() {
        val action = StartupVisionPreparationPolicy.evaluateGatingAction(
            isCameraGraphActive = true,
            isCameraPending = true,
            isCameraRecovering = true,
            hasCameraPermission = true,
            isExplicitStopActive = false,
            isClosed = true,
        )
        assertEquals(StartupVisionPreparationPolicy.GatingAction.CANCEL, action)
        assertFalse(StartupVisionPreparationPolicy.shouldAwaitStability(action))
    }

    @Test
    fun missingCameraPermission_proceedsImmediatelyWithoutDeadlocking() {
        val action = StartupVisionPreparationPolicy.evaluateGatingAction(
            isCameraGraphActive = false,
            isCameraPending = false,
            isCameraRecovering = false,
            hasCameraPermission = false,
            isExplicitStopActive = false,
            isClosed = false,
        )
        assertEquals(StartupVisionPreparationPolicy.GatingAction.PROCEED_IMMEDIATELY, action)
        assertFalse(StartupVisionPreparationPolicy.shouldAwaitStability(action))
    }
}
