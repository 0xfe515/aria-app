package com.ariateam.aria.startup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupCuePolicyTest {

    @Test
    fun shouldTriggerAdmissionCue_returnsTrue_onlyWhenReadyForegroundAndNotYetPlayed() {
        val ready = StartupDecision.Ready()

        val canPlay = StartupCuePolicy.shouldTriggerAdmissionCue(
            hasPlayed = false,
            decision = ready,
            isForeground = true,
        )

        assertTrue("Boot cue should trigger when ready in foreground and not yet played", canPlay)
    }

    @Test
    fun shouldTriggerAdmissionCue_returnsFalse_whenAlreadyPlayed() {
        val ready = StartupDecision.Ready()

        val canPlay = StartupCuePolicy.shouldTriggerAdmissionCue(
            hasPlayed = true,
            decision = ready,
            isForeground = true,
        )

        assertFalse("Boot cue must never trigger again after it has already played", canPlay)
    }

    @Test
    fun shouldTriggerAdmissionCue_returnsFalse_whenInHostBackground() {
        val ready = StartupDecision.Ready()

        val canPlay = StartupCuePolicy.shouldTriggerAdmissionCue(
            hasPlayed = false,
            decision = ready,
            isForeground = false,
        )

        assertFalse("Boot cue must not trigger while host activity/screen is in background", canPlay)
    }

    @Test
    fun shouldTriggerAdmissionCue_returnsFalse_duringLoadingStages() {
        val loadingStages = listOf(
            StartupDecision.Loading(StartupStage.RuntimePermissions, "권한 확인 중…"),
            StartupDecision.Loading(StartupStage.VoiceSync, "음성 서비스 동기화 중…"),
            StartupDecision.Loading(StartupStage.VisionRestore, "비전 모델 복원 중…"),
        )

        for (decision in loadingStages) {
            val canPlay = StartupCuePolicy.shouldTriggerAdmissionCue(
                hasPlayed = false,
                decision = decision,
                isForeground = true,
            )
            assertFalse("Boot cue must not trigger during loading stage: ${decision.stage}", canPlay)
        }
    }

    @Test
    fun shouldTriggerAdmissionCue_returnsFalse_whenActionRequiredOrError() {
        val errorDecision = StartupDecision.ActionRequired(
            issue = StartupIssue.RuntimePermissionsDenied,
            title = "권한 필요",
            message = "마이크 권한이 필요합니다.",
            canRetry = true,
            canContinueDegraded = false,
            needsSettings = true,
        )

        val canPlay = StartupCuePolicy.shouldTriggerAdmissionCue(
            hasPlayed = false,
            decision = errorDecision,
            isForeground = true,
        )

        assertFalse("Boot cue must not trigger when action or error resolution is required", canPlay)
    }

    @Test
    fun shouldPlaySound_respectsAudibleFeedbackPreference() {
        val ready = StartupDecision.Ready()

        val soundWhenAudible = StartupCuePolicy.shouldPlaySound(
            hasPlayed = false,
            decision = ready,
            isForeground = true,
            audibleEnabled = true,
        )
        assertTrue("Sound should play when audible feedback is enabled", soundWhenAudible)

        val soundWhenMuted = StartupCuePolicy.shouldPlaySound(
            hasPlayed = false,
            decision = ready,
            isForeground = true,
            audibleEnabled = false,
        )
        assertFalse("Sound must be suppressed when audible feedback is disabled", soundWhenMuted)
    }

    @Test
    fun shouldPlaySound_returnsFalse_whenGatingFailsEvenIfAudibleEnabled() {
        val loading = StartupDecision.Loading(StartupStage.VoiceSync, "동기화 중…")
        assertFalse(
            "Sound must not play while loading even if audible is enabled",
            StartupCuePolicy.shouldPlaySound(
                hasPlayed = false,
                decision = loading,
                isForeground = true,
                audibleEnabled = true,
            ),
        )

        val ready = StartupDecision.Ready()
        assertFalse(
            "Sound must not repeat after having played even if audible is enabled",
            StartupCuePolicy.shouldPlaySound(
                hasPlayed = true,
                decision = ready,
                isForeground = true,
                audibleEnabled = true,
            ),
        )

        assertFalse(
            "Sound must not play in background even if audible is enabled",
            StartupCuePolicy.shouldPlaySound(
                hasPlayed = false,
                decision = ready,
                isForeground = false,
                audibleEnabled = true,
            ),
        )
    }
}
