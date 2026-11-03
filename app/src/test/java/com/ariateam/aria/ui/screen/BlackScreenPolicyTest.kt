package com.ariateam.aria.ui.screen

import com.ariateam.aria.speech.VoicePhase
import com.ariateam.aria.vision.CameraSessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlackScreenPolicyTest {
    private val eligible = BlackScreenEligibility(
        isHome = true,
        hasBeenAdmitted = true,
        isForeground = true,
        isStartupSettingsVisible = false,
        isExcludedScreen = false,
        isDialogVisible = false,
        hasActionRequiredFailure = false,
        voicePhase = VoicePhase.Waiting,
        cameraStatus = CameraSessionStatus.Running,
        visionReady = true,
    )

    @Test
    fun autoHideRequiresThirtySecondsOfEligibleIdle() {
        assertFalse(BlackScreenPolicy.shouldAutoHide(eligible, lastActivityAt = 1_000, now = 30_999))
        assertTrue(BlackScreenPolicy.shouldAutoHide(eligible, lastActivityAt = 1_000, now = 31_000))
        assertEquals(1_000L, BlackScreenPolicy.remainingMillis(lastActivityAt = 1_000, now = 30_000))
        assertEquals(0L, BlackScreenPolicy.remainingMillis(lastActivityAt = 1_000, now = 31_000))
    }

    @Test
    fun startupSettingsDialogsAndFailuresBlockHiding() {
        listOf(
            eligible.copy(isHome = false),
            eligible.copy(hasBeenAdmitted = false),
            eligible.copy(isForeground = false),
            eligible.copy(isStartupSettingsVisible = true),
            eligible.copy(isExcludedScreen = true),
            eligible.copy(isDialogVisible = true),
            eligible.copy(hasActionRequiredFailure = true),
            eligible.copy(voicePhase = VoicePhase.Error),
            eligible.copy(cameraStatus = CameraSessionStatus.Error),
            eligible.copy(visionReady = false),
        ).forEach { assertFalse(BlackScreenPolicy.canAutoHide(it)) }
    }

    @Test
    fun activeVoiceInteractionPausesAutoHideButAllowsManualHide() {
        val dictating = eligible.copy(voicePhase = VoicePhase.Dictating)

        assertTrue(BlackScreenPolicy.canHideManually(dictating))
        assertFalse(BlackScreenPolicy.canAutoHide(dictating))
    }

    @Test
    fun visibilityEventsMustAdvanceToBeFresh() {
        assertFalse(BlackScreenPolicy.isFreshVisibilityEvent(8, 8))
        assertFalse(BlackScreenPolicy.isFreshVisibilityEvent(7, 8))
        assertTrue(BlackScreenPolicy.isFreshVisibilityEvent(9, 8))
    }
}
