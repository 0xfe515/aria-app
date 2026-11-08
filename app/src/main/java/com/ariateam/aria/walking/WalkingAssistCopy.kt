package com.ariateam.aria.walking

import androidx.annotation.StringRes
import com.ariateam.aria.R
import com.ariateam.aria.hazard.HazardCause
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
        HazardLevel.PATH_CAUTION -> R.string.walking_hazard_c4_narrow
        else -> null
    }

    @StringRes
    fun hazardWarning(cause: HazardCause): Int? = when (cause) {
        HazardCause.W1_VEHICLE_APPROACH -> R.string.walking_hazard_w1_vehicle
        HazardCause.W2_OBJECT_IMMINENT -> R.string.walking_hazard_w2_imminent
        HazardCause.W3_ROAD_UNDERFOOT -> R.string.walking_hazard_w3_road
        HazardCause.W4_STATIC_IMMINENT -> R.string.walking_hazard_w4_static
        HazardCause.W5_PATH_LOST -> R.string.walking_hazard_w5_path_lost
        HazardCause.C1_OBJECT_IN_PATH -> R.string.walking_hazard_c1_object
        HazardCause.C2_VEHICLE_NEARBY -> R.string.walking_hazard_c2_vehicle_near
        HazardCause.C3_ROAD_EDGE -> R.string.walking_hazard_c3_road_edge
        HazardCause.C4_PATH_NARROW -> R.string.walking_hazard_c4_narrow
        HazardCause.C5_CROSSWALK_ENTRY -> R.string.walking_hazard_c5_crosswalk
        HazardCause.C6_CROSSWALK_SIGNAL_PRESENCE -> R.string.walking_hazard_c6_crosswalk_signal
        HazardCause.NONE,
        HazardCause.DEGRADED -> null
    }
}
