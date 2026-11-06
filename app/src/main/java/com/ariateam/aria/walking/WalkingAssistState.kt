package com.ariateam.aria.walking

enum class WalkingAssistPhase {
    PREPARING,
    CALIBRATION_REQUIRED,
    ACTIVE,
    LIMITED,
    STOPPED,
}

enum class WalkingAssistCause {
    READY,
    SESSION_STARTING,
    CAMERA_PERMISSION_REQUIRED,
    CAMERA_UNAVAILABLE,
    CAMERA_STARTING,
    MODEL_LOADING,
    MODEL_UNAVAILABLE,
    CALIBRATION_REQUIRED,
    MOTION_UNAVAILABLE,
    MOTION_UNRELIABLE,
    RESULT_STALE,
    PIPELINE_STALLED,
    PIPELINE_ERROR,
    SESSION_STOPPED,
}

enum class WalkingAssistAction {
    NONE,
    START_ASSIST,
    GRANT_CAMERA_PERMISSION,
    RESTORE_CAMERA,
    WAIT_FOR_CAMERA,
    WAIT_FOR_MODEL,
    RETRY_MODEL,
    CALIBRATE_FORWARD,
    WAIT_FOR_MOTION,
    WAIT_FOR_RESULT,
    RETRY_PIPELINE,
}

enum class WalkingAssistAnnouncement {
    READY,
    LIMITED,
    RECOVERED,
    CALIBRATION_REQUIRED,
    STOPPED,
}

data class WalkingAssistState(
    val phase: WalkingAssistPhase,
    val cause: WalkingAssistCause,
    val action: WalkingAssistAction,
)

data class WalkingAssistTransition(
    val previous: WalkingAssistState,
    val current: WalkingAssistState,
    val announcement: WalkingAssistAnnouncement? = null,
) {
    val changed: Boolean get() = previous != current
}
