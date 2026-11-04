package com.ariateam.aria.startup

/**
 * Policy governing vision preparation and camera stability gating during application startup
 * and session lifecycle transitions.
 *
 * Gating rationale:
 * On SoC platforms such as Snapdragon 845 / Adreno 630, concurrent Camera2 HAL capture session
 * creation (ISP memory allocation, buffer negotiation) and LiteRT GPU delegate compilation
 * (OpenCL kernel compilation, GPU tensor allocation) create memory bus contention and can cause
 * camera pipeline stalls.
 *
 * However, during natural cold startup before admission, no camera graph is active or pending:
 * MainScreen gates Home camera activation on [StartupViewModel.hasBeenAdmitted], which in turn
 * requires [com.ariateam.aria.vision.VisionModelState.Ready]. If model preparation unconditionally
 * waits for camera stability, neither can proceed, causing a circular deadlock that times out
 * after 35 seconds.
 *
 * Sequencing invariants:
 * 1. When no camera graph is active or pending, model preparation proceeds immediately to compile
 *    and warm up the GPU delegate prior to Home admission, avoiding startup deadlock.
 * 2. When a camera graph is already active, pending, or in active recovery (e.g. during model/backend
 *    switches on Home or Settings, or during scheduled retry), camera stability gating is enforced
 *    to protect the active stream.
 * 3. Recovery state must reflect live active recovery work (e.g. pending retry runnable or active
 *    rebind), not historical failure counters after terminal session failure.
 * 4. Persisted explicit user stop on cold launch must not produce a misleading model error or
 *    silently restart the camera; the model prepares cleanly and camera remains stopped with
 *    accessible recovery.
 * 5. Closed controller cancels the stability gate immediately.
 */
object StartupVisionPreparationPolicy {

    enum class GatingAction {
        PROCEED_IMMEDIATELY,
        AWAIT_CAMERA_STABILITY,
        CANCEL,
    }

    /**
     * Evaluates whether vision preparation should await the camera stability gate.
     *
     * @param isCameraGraphActive Whether the camera session is currently open, starting, or running.
     * @param isCameraPending Whether a camera start request is pending (e.g. awaiting permission or lifecycle resume).
     * @param isCameraRecovering Whether camera hardware/stall recovery is actively in flight (live runnable or active rebind).
     * @param hasCameraPermission Whether the CAMERA runtime permission is currently granted.
     * @param isExplicitStopActive Whether the user explicitly stopped vision (unified stop or user stop latch).
     * @param isClosed Whether the pipeline controller or model store has been closed.
     * @return The gating action to take: [GatingAction.PROCEED_IMMEDIATELY], [GatingAction.AWAIT_CAMERA_STABILITY], or [GatingAction.CANCEL].
     */
    fun evaluateGatingAction(
        isCameraGraphActive: Boolean,
        isCameraPending: Boolean = false,
        isCameraRecovering: Boolean = false,
        hasCameraPermission: Boolean = true,
        isExplicitStopActive: Boolean = false,
        isClosed: Boolean = false,
    ): GatingAction {
        if (isClosed) {
            return GatingAction.CANCEL
        }
        if (isExplicitStopActive && isCameraGraphActive) {
            // User requested explicit stop while camera graph was active; cancel gate promptly
            return GatingAction.CANCEL
        }
        if (!hasCameraPermission) {
            // Camera cannot run without permission; do not wait on a camera graph that cannot start
            return GatingAction.PROCEED_IMMEDIATELY
        }
        if (isCameraGraphActive || isCameraPending || isCameraRecovering) {
            // A camera graph is active, pending, or actively recovering: wait for camera stream stability
            // before GPU delegate compilation to avoid memory bus and driver contention.
            return GatingAction.AWAIT_CAMERA_STABILITY
        }
        // No camera graph is active, pending, or recovering (e.g. natural cold startup before admission,
        // or cold launch with persisted explicit stop, or after terminal camera failure).
        // Proceed immediately with model preparation to complete warmup without deadlock or misleading errors.
        return GatingAction.PROCEED_IMMEDIATELY
    }

    /**
     * Determines whether camera stability is required based on the evaluated gating action.
     */
    fun shouldAwaitStability(action: GatingAction): Boolean =
        action == GatingAction.AWAIT_CAMERA_STABILITY
}
