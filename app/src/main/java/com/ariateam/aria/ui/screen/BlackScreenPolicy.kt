package com.ariateam.aria.ui.screen

import com.ariateam.aria.speech.VoicePhase
import com.ariateam.aria.vision.CameraSessionStatus

internal data class BlackScreenEligibility(
    val isHome: Boolean,
    val hasBeenAdmitted: Boolean,
    val isForeground: Boolean,
    val isStartupSettingsVisible: Boolean,
    val isExcludedScreen: Boolean,
    val isDialogVisible: Boolean,
    val hasActionRequiredFailure: Boolean,
    val voicePhase: VoicePhase,
    val cameraStatus: CameraSessionStatus,
    val visionReady: Boolean,
)

internal object BlackScreenPolicy {
    const val IDLE_TIMEOUT_MILLIS = 30_000L

    fun canHideManually(value: BlackScreenEligibility): Boolean =
        value.isHome &&
            value.hasBeenAdmitted &&
            value.isForeground &&
            !value.isStartupSettingsVisible &&
            !value.isExcludedScreen &&
            !value.isDialogVisible &&
            !value.hasActionRequiredFailure &&
            value.voicePhase in setOf(VoicePhase.Waiting, VoicePhase.Dictating, VoicePhase.Speaking) &&
            value.cameraStatus == CameraSessionStatus.Running &&
            value.visionReady

    fun canAutoHide(value: BlackScreenEligibility): Boolean =
        canHideManually(value) && value.voicePhase == VoicePhase.Waiting

    fun shouldAutoHide(value: BlackScreenEligibility, lastActivityAt: Long, now: Long): Boolean =
        canAutoHide(value) && now - lastActivityAt >= IDLE_TIMEOUT_MILLIS

    fun remainingMillis(lastActivityAt: Long, now: Long): Long =
        (IDLE_TIMEOUT_MILLIS - (now - lastActivityAt)).coerceAtLeast(0L)

    fun isFreshVisibilityEvent(eventId: Long, lastSeenEventId: Long): Boolean =
        eventId > lastSeenEventId
}
