package com.ariateam.aria.walking

data class WalkingAssistInput(
    val sessionGeneration: Long,
    val sessionActive: Boolean,
    val explicitlyStopped: Boolean = false,
    val cameraPermissionGranted: Boolean,
    val cameraReady: Boolean,
    val cameraLost: Boolean = false,
    val modelReady: Boolean,
    val modelFailed: Boolean = false,
    val motionSensorsAvailable: Boolean,
    val motionCalibrated: Boolean,
    val motionReliable: Boolean,
    val resultCaptureTimestampNanos: Long?,
    val transientFault: WalkingAssistCause? = null,
    val autoCalibrationActive: Boolean = false,
)

class WalkingAssistStateMachine(
    private val transientFaultLimitMs: Long = TRANSIENT_FAULT_LIMIT_MS,
    private val recoveryStabilityMs: Long = RECOVERY_STABILITY_MS,
    private val freshResultLimitMs: Long = FRESH_RESULT_LIMIT_MS,
) {
    private var sessionGeneration: Long? = null
    private var sessionStartedAtNanos: Long = 0L
    private var stopped = false
    private var transientFault: WalkingAssistCause? = null
    private var transientFaultSinceMs: Long? = null
    private var validSinceMs: Long? = null
    private var recoveryPending = false
    private var sensingExpectedSinceMs: Long? = null
    private var latestResultCaptureTimestampNanos: Long? = null
    private var lastNowMs: Long = 0L
    private var lastNowNanos: Long = 0L
    private var state = PREPARING_STATE

    init {
        require(transientFaultLimitMs >= 0L)
        require(recoveryStabilityMs >= 0L)
        require(freshResultLimitMs > 0L)
    }

    @Synchronized
    fun startSession(
        generation: Long,
        nowElapsedMs: Long,
        nowElapsedNanos: Long = nowElapsedMs * NANOS_PER_MILLI,
    ): WalkingAssistTransition {
        require(generation >= 0L)
        require(nowElapsedMs >= 0L)
        require(nowElapsedNanos >= 0L)
        val previous = state
        if (sessionGeneration?.let { generation <= it } == true) {
            return WalkingAssistTransition(previous, state)
        }

        sessionGeneration = generation
        sessionStartedAtNanos = nowElapsedNanos
        stopped = false
        transientFault = null
        transientFaultSinceMs = null
        validSinceMs = null
        recoveryPending = false
        sensingExpectedSinceMs = null
        latestResultCaptureTimestampNanos = null
        lastNowMs = nowElapsedMs
        lastNowNanos = nowElapsedNanos
        state = PREPARING_STATE
        return transition(previous, state)
    }

    @Synchronized
    fun stopSession(generation: Long): WalkingAssistTransition {
        val previous = state
        if (sessionGeneration != generation || stopped) {
            return WalkingAssistTransition(previous, state)
        }
        stopped = true
        transientFault = null
        transientFaultSinceMs = null
        validSinceMs = null
        recoveryPending = false
        state = STOPPED_STATE
        return WalkingAssistTransition(previous, state, WalkingAssistAnnouncement.STOPPED)
    }

    @Synchronized
    fun update(
        input: WalkingAssistInput,
        nowElapsedMs: Long,
        nowElapsedNanos: Long,
    ): WalkingAssistTransition {
        require(nowElapsedMs >= 0L)
        require(nowElapsedNanos >= 0L)
        val previous = state
        if (sessionGeneration != input.sessionGeneration || stopped ||
            nowElapsedMs < lastNowMs || nowElapsedNanos < lastNowNanos
        ) {
            return WalkingAssistTransition(previous, state)
        }
        lastNowMs = nowElapsedMs
        lastNowNanos = nowElapsedNanos

        if (input.explicitlyStopped) {
            return stopSession(input.sessionGeneration)
        }

        val immediate = when {
            !input.cameraPermissionGranted -> limited(
                WalkingAssistCause.CAMERA_PERMISSION_REQUIRED,
                WalkingAssistAction.GRANT_CAMERA_PERMISSION,
            )
            input.cameraLost -> limited(
                WalkingAssistCause.CAMERA_UNAVAILABLE,
                WalkingAssistAction.RESTORE_CAMERA,
            )
            else -> null
        }
        if (immediate != null) {
            clearTimers()
            return transitionTo(previous, immediate)
        }

        recordResult(input.resultCaptureTimestampNanos, nowElapsedNanos)
        val inputFault = input.transientFault
        if (inputFault != null) {
            return updateTransientFault(
                previous = previous,
                nowElapsedMs = nowElapsedMs,
                cause = inputFault,
                action = actionFor(inputFault),
            )
        }
        if (input.modelFailed) {
            return updateTransientFault(
                previous = previous,
                nowElapsedMs = nowElapsedMs,
                cause = WalkingAssistCause.MODEL_UNAVAILABLE,
                action = WalkingAssistAction.RETRY_MODEL,
            )
        }

        if (!input.motionSensorsAvailable) {
            return updateTransientFault(
                previous = previous,
                nowElapsedMs = nowElapsedMs,
                cause = WalkingAssistCause.MOTION_UNAVAILABLE,
                action = WalkingAssistAction.WAIT_FOR_MOTION,
            )
        }

        // Auto-reanchor during active session is an explicit quiet exception (requires sensors available):
        if (!input.motionCalibrated && input.autoCalibrationActive) {
            clearTimers()
            return transitionTo(previous, stateFor(
                WalkingAssistPhase.PREPARING,
                WalkingAssistCause.SESSION_STARTING,
                WalkingAssistAction.WAIT_FOR_MOTION,
            ))
        }

        // If prior results existed in this session and results stop arriving,
        // it must eventually become LIMITED(RESULT_STALE) even if readiness was lost.
        val captureTimestampNanos = latestResultCaptureTimestampNanos
        val resultAgeNanos = captureTimestampNanos?.let { nowElapsedNanos - it }
        val priorResultStale = resultAgeNanos != null &&
            resultAgeNanos >= freshResultLimitMs * NANOS_PER_MILLI
        if (priorResultStale) {
            validSinceMs = null
            return transitionTo(
                previous,
                limited(WalkingAssistCause.RESULT_STALE, WalkingAssistAction.WAIT_FOR_RESULT),
            )
        }

        if (!input.sessionActive) {
            clearTimers()
            if (state.phase == WalkingAssistPhase.LIMITED) {
                return WalkingAssistTransition(previous, state)
            }
            return transitionTo(previous, PREPARING_STATE)
        }
        if (!input.cameraReady) {
            clearTimers()
            if (state.phase == WalkingAssistPhase.LIMITED) {
                return WalkingAssistTransition(previous, state)
            }
            return transitionTo(previous, stateFor(
                WalkingAssistPhase.PREPARING,
                WalkingAssistCause.CAMERA_STARTING,
                WalkingAssistAction.WAIT_FOR_CAMERA,
            ))
        }

        if (!input.modelReady) {
            clearTimers()
            if (state.phase == WalkingAssistPhase.LIMITED) {
                return WalkingAssistTransition(previous, state)
            }
            return transitionTo(previous, stateFor(
                WalkingAssistPhase.PREPARING,
                WalkingAssistCause.MODEL_LOADING,
                WalkingAssistAction.WAIT_FOR_MODEL,
            ))
        }

        if (!input.motionCalibrated) {
            clearTimers()
            return transitionTo(previous, stateFor(
                WalkingAssistPhase.CALIBRATION_REQUIRED,
                WalkingAssistCause.CALIBRATION_REQUIRED,
                WalkingAssistAction.CALIBRATE_FORWARD,
            ))
        }
        if (!input.motionReliable) {
            return updateTransientFault(
                previous = previous,
                nowElapsedMs = nowElapsedMs,
                cause = WalkingAssistCause.MOTION_UNRELIABLE,
                action = WalkingAssistAction.WAIT_FOR_MOTION,
            )
        }

        val hasFreshResult = resultAgeNanos != null &&
            resultAgeNanos >= 0L &&
            resultAgeNanos < freshResultLimitMs * NANOS_PER_MILLI
        val firstResultDeadlineReached = when {
            resultAgeNanos != null -> false
            else -> {
                val readySince = sensingExpectedSinceMs ?: nowElapsedMs.also {
                    sensingExpectedSinceMs = it
                }
                nowElapsedMs - readySince >= freshResultLimitMs
            }
        }
        if (firstResultDeadlineReached) {
            validSinceMs = null
            return transitionTo(
                previous,
                limited(WalkingAssistCause.RESULT_STALE, WalkingAssistAction.WAIT_FOR_RESULT),
            )
        }

        if (!hasFreshResult) {
            clearTransientFault()
            validSinceMs = null
            if (state.phase == WalkingAssistPhase.LIMITED) {
                return WalkingAssistTransition(previous, state)
            }
            return transitionTo(previous, stateFor(
                WalkingAssistPhase.PREPARING,
                WalkingAssistCause.RESULT_STALE,
                WalkingAssistAction.WAIT_FOR_RESULT,
            ))
        }

        clearTransientFault()
        sensingExpectedSinceMs = null
        if (state.phase == WalkingAssistPhase.LIMITED || recoveryPending) {
            val stableSince = validSinceMs ?: nowElapsedMs.also { validSinceMs = it }
            if (nowElapsedMs - stableSince < recoveryStabilityMs) {
                return WalkingAssistTransition(previous, state)
            }
            validSinceMs = null
            recoveryPending = false
            return transitionTo(previous, ACTIVE_STATE, WalkingAssistAnnouncement.RECOVERED)
        }

        validSinceMs = null
        return transitionTo(previous, ACTIVE_STATE)
    }

    private fun recordResult(timestampNanos: Long?, nowElapsedNanos: Long) {
        if (timestampNanos != null &&
            timestampNanos > 0L &&
            timestampNanos >= sessionStartedAtNanos &&
            (timestampNanos <= nowElapsedNanos ||
                timestampNanos - nowElapsedNanos <= MAX_FUTURE_RESULT_NANOS) &&
            (latestResultCaptureTimestampNanos == null ||
                timestampNanos > latestResultCaptureTimestampNanos!!)
        ) {
            latestResultCaptureTimestampNanos = timestampNanos
        }
    }

    private fun updateTransientFault(
        previous: WalkingAssistState,
        nowElapsedMs: Long,
        cause: WalkingAssistCause,
        action: WalkingAssistAction,
    ): WalkingAssistTransition {
        validSinceMs = null
        sensingExpectedSinceMs = null
        transientFault = cause
        if (transientFaultSinceMs == null) {
            transientFaultSinceMs = nowElapsedMs
        }
        val faultDuration = nowElapsedMs - (transientFaultSinceMs ?: nowElapsedMs)
        val next = if (state.phase == WalkingAssistPhase.LIMITED ||
            faultDuration >= transientFaultLimitMs
        ) {
            limited(cause, action)
        } else {
            stateFor(WalkingAssistPhase.PREPARING, cause, action)
        }
        return transitionTo(previous, next)
    }

    private fun clearTransientFault() {
        transientFault = null
        transientFaultSinceMs = null
    }

    private fun clearTimers() {
        clearTransientFault()
        validSinceMs = null
        sensingExpectedSinceMs = null
    }

    private fun transitionTo(
        previous: WalkingAssistState,
        next: WalkingAssistState,
        forcedAnnouncement: WalkingAssistAnnouncement? = null,
    ): WalkingAssistTransition {
        state = next
        if (next.phase == WalkingAssistPhase.LIMITED) {
            recoveryPending = true
        }
        val announcement = forcedAnnouncement ?: when {
            previous == next -> null
            next.phase == WalkingAssistPhase.LIMITED && previous.phase != WalkingAssistPhase.LIMITED ->
                WalkingAssistAnnouncement.LIMITED
            previous.phase == next.phase -> null
            next.phase == WalkingAssistPhase.CALIBRATION_REQUIRED ->
                WalkingAssistAnnouncement.CALIBRATION_REQUIRED
            next.phase == WalkingAssistPhase.ACTIVE -> WalkingAssistAnnouncement.READY
            next.phase == WalkingAssistPhase.STOPPED -> WalkingAssistAnnouncement.STOPPED
            else -> null
        }
        return WalkingAssistTransition(previous, next, announcement)
    }

    private fun transition(
        previous: WalkingAssistState,
        next: WalkingAssistState,
    ) = WalkingAssistTransition(previous, next)

    private fun limited(cause: WalkingAssistCause, action: WalkingAssistAction) =
        stateFor(WalkingAssistPhase.LIMITED, cause, action)

    private fun actionFor(cause: WalkingAssistCause): WalkingAssistAction = when (cause) {
        WalkingAssistCause.MODEL_UNAVAILABLE -> WalkingAssistAction.RETRY_MODEL
        WalkingAssistCause.MOTION_UNAVAILABLE,
        WalkingAssistCause.MOTION_UNRELIABLE -> WalkingAssistAction.WAIT_FOR_MOTION
        WalkingAssistCause.RESULT_STALE -> WalkingAssistAction.WAIT_FOR_RESULT
        WalkingAssistCause.PIPELINE_STALLED,
        WalkingAssistCause.PIPELINE_ERROR -> WalkingAssistAction.RETRY_PIPELINE
        WalkingAssistCause.CAMERA_PERMISSION_REQUIRED -> WalkingAssistAction.GRANT_CAMERA_PERMISSION
        WalkingAssistCause.CAMERA_UNAVAILABLE -> WalkingAssistAction.RESTORE_CAMERA
        WalkingAssistCause.CALIBRATION_REQUIRED -> WalkingAssistAction.CALIBRATE_FORWARD
        WalkingAssistCause.CAMERA_STARTING -> WalkingAssistAction.WAIT_FOR_CAMERA
        WalkingAssistCause.MODEL_LOADING -> WalkingAssistAction.WAIT_FOR_MODEL
        WalkingAssistCause.READY -> WalkingAssistAction.NONE
        WalkingAssistCause.SESSION_STARTING -> WalkingAssistAction.NONE
        WalkingAssistCause.SESSION_STOPPED -> WalkingAssistAction.START_ASSIST
    }

    private fun stateFor(
        phase: WalkingAssistPhase,
        cause: WalkingAssistCause,
        action: WalkingAssistAction,
    ) = WalkingAssistState(phase, cause, action)

    companion object {
        const val TRANSIENT_FAULT_LIMIT_MS = 1_000L
        const val RECOVERY_STABILITY_MS = 1_000L
        const val FRESH_RESULT_LIMIT_MS = 1_500L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val MAX_FUTURE_RESULT_NANOS = 500_000_000L

        private val PREPARING_STATE = WalkingAssistState(
            WalkingAssistPhase.PREPARING,
            WalkingAssistCause.SESSION_STARTING,
            WalkingAssistAction.NONE,
        )
        private val ACTIVE_STATE = WalkingAssistState(
            WalkingAssistPhase.ACTIVE,
            WalkingAssistCause.READY,
            WalkingAssistAction.NONE,
        )
        private val STOPPED_STATE = WalkingAssistState(
            WalkingAssistPhase.STOPPED,
            WalkingAssistCause.SESSION_STOPPED,
            WalkingAssistAction.START_ASSIST,
        )
    }
}
