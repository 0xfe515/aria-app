package com.ariateam.aria.walking

import androidx.annotation.StringRes
import com.ariateam.aria.R
import com.ariateam.aria.hazard.HazardLevel

object WalkingAssistCopy {
    @StringRes
    fun announcement(announcement: WalkingAssistAnnouncement, state: WalkingAssistState): Int =
        when (announcement) {
            WalkingAssistAnnouncement.READY -> R.string.walking_assist_ready
            WalkingAssistAnnouncement.RECOVERED -> R.string.walking_assist_recovered
            WalkingAssistAnnouncement.CALIBRATION_REQUIRED -> R.string.walking_assist_calibration_required
            WalkingAssistAnnouncement.STOPPED -> R.string.walking_assist_stopped
            WalkingAssistAnnouncement.LIMITED -> status(state)
        }

    @StringRes
    fun status(state: WalkingAssistState): Int = when (state.phase) {
        WalkingAssistPhase.PREPARING -> R.string.walking_assist_preparing
        WalkingAssistPhase.CALIBRATION_REQUIRED -> R.string.walking_assist_calibration_required
        WalkingAssistPhase.ACTIVE -> R.string.walking_assist_status_active
        WalkingAssistPhase.STOPPED -> R.string.walking_assist_stopped
        WalkingAssistPhase.LIMITED -> when (state.cause) {
            WalkingAssistCause.CAMERA_PERMISSION_REQUIRED -> R.string.walking_assist_limited_permission
            WalkingAssistCause.CAMERA_UNAVAILABLE -> R.string.walking_assist_limited_camera
            WalkingAssistCause.CAMERA_STARTING -> R.string.walking_assist_preparing_camera
            WalkingAssistCause.MODEL_LOADING -> R.string.walking_assist_preparing_model
            WalkingAssistCause.MODEL_UNAVAILABLE -> R.string.walking_assist_limited_model
            WalkingAssistCause.CALIBRATION_REQUIRED -> R.string.walking_assist_calibration_required
            WalkingAssistCause.MOTION_UNAVAILABLE -> R.string.walking_assist_limited_motion_unavailable
            WalkingAssistCause.MOTION_UNRELIABLE -> R.string.walking_assist_limited_motion_unreliable
            WalkingAssistCause.RESULT_STALE -> R.string.walking_assist_limited_stale
            WalkingAssistCause.PIPELINE_STALLED -> R.string.walking_assist_limited_stalled
            WalkingAssistCause.PIPELINE_ERROR -> R.string.walking_assist_limited_pipeline
            WalkingAssistCause.READY,
            WalkingAssistCause.SESSION_STARTING,
            WalkingAssistCause.SESSION_STOPPED -> R.string.walking_assist_limited_generic
        }
    }

    @StringRes
    fun hazardWarning(level: HazardLevel): Int? = when (level) {
        HazardLevel.NEAR_PATH_HAZARD -> R.string.walking_hazard_near
        HazardLevel.PATH_BLOCKED -> R.string.walking_hazard_blocked
        else -> null
    }
}
