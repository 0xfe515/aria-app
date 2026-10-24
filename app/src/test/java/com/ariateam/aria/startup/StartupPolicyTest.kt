package com.ariateam.aria.startup

import com.ariateam.aria.command.CommandSnapshot
import com.ariateam.aria.command.LanguageStatus
import com.ariateam.aria.speech.VoicePhase
import com.ariateam.aria.speech.VoiceState
import com.ariateam.aria.vision.VisionModelMetadata
import com.ariateam.aria.vision.VisionModelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupPolicyTest {

    private val readyVision = VisionModelState.Ready(
        metadata = VisionModelMetadata("test.tflite", 1024L, "dummy_sha", 12345L)
    )

    private val waitingVoice = VoiceState(
        phase = VoicePhase.Waiting,
        command = CommandSnapshot(languageStatus = LanguageStatus.Missing)
    )

    @Test
    fun initialVisionRestorePending_returnsLoadingVisionRestore() {
        val decision = StartupPolicy.evaluate(
            isVisionRestoreCompleted = false,
            visionModelState = VisionModelState.Missing,
            isVoiceServiceSynced = true,
            voiceState = waitingVoice,
            missingRuntimePermissions = emptySet(),
            hasCompletedPermissionCheck = true,
            userAcceptedDegraded = false,
        )
        assertTrue(decision is StartupDecision.Loading)
        assertEquals(StartupStage.VisionRestore, (decision as StartupDecision.Loading).stage)
    }

    @Test
    fun visionLoadingAfterRestoreSignal_stillWaitsForUsableModel() {
        val decision = StartupPolicy.evaluate(
            isVisionRestoreCompleted = true,
            visionModelState = VisionModelState.Loading("model.tflite"),
            isVoiceServiceSynced = true,
            voiceState = waitingVoice,
            missingRuntimePermissions = emptySet(),
            hasCompletedPermissionCheck = true,
        )
        assertEquals(StartupStage.VisionRestore, (decision as StartupDecision.Loading).stage)
    }

    @Test
    fun timedOutVisionRestoreOffersRecoveryInsteadOfAdmission() {
        val decision = StartupPolicy.evaluate(
            isVisionRestoreCompleted = false,
            visionModelState = VisionModelState.Loading("model.tflite"),
            isVoiceServiceSynced = true,
            voiceState = waitingVoice,
            missingRuntimePermissions = emptySet(),
            hasCompletedPermissionCheck = true,
            timedOutStage = StartupStage.VisionRestore,
        )
        val action = decision as StartupDecision.ActionRequired
        assertEquals(StartupIssue.StartupTimeout, action.issue)
        assertEquals(StartupStage.VisionRestore, action.stage)
        assertTrue(action.needsSettings)
        assertTrue(action.canContinueDegraded)
    }

    @Test
    fun visionModelMissing_afterRestoreCompleted_returnsActionRequiredMissing() {
        val decision = StartupPolicy.evaluate(
            isVisionRestoreCompleted = true,
            visionModelState = VisionModelState.Missing,
            isVoiceServiceSynced = true,
            voiceState = waitingVoice,
            missingRuntimePermissions = emptySet(),
            hasCompletedPermissionCheck = true,
            userAcceptedDegraded = false,
        )
        assertTrue(decision is StartupDecision.ActionRequired)
        val action = decision as StartupDecision.ActionRequired
        assertEquals(StartupIssue.VisionModelMissing, action.issue)
        assertTrue(action.canContinueDegraded)
        assertTrue(action.needsSettings)
    }

    @Test
    fun visionModelError_afterRestoreCompleted_returnsActionRequiredError() {
        val decision = StartupPolicy.evaluate(
            isVisionRestoreCompleted = true,
            visionModelState = VisionModelState.Error("손상된 모델 파일", "test"),
            isVoiceServiceSynced = true,
            voiceState = waitingVoice,
            missingRuntimePermissions = emptySet(),
            hasCompletedPermissionCheck = true,
            userAcceptedDegraded = false,
        )
        assertTrue(decision is StartupDecision.ActionRequired)
        val action = decision as StartupDecision.ActionRequired
        assertEquals(StartupIssue.VisionModelError, action.issue)
        assertTrue(action.canContinueDegraded)
        assertTrue(action.canRetry)
    }

    @Test
    fun voiceServiceUnsynced_returnsLoadingVoiceSync() {
        val decision = StartupPolicy.evaluate(
            isVisionRestoreCompleted = true,
            visionModelState = readyVision,
            isVoiceServiceSynced = false,
            voiceState = VoiceState(phase = VoicePhase.Stopped),
            missingRuntimePermissions = emptySet(),
            hasCompletedPermissionCheck = true,
            userAcceptedDegraded = false,
        )
        assertTrue(decision is StartupDecision.Loading)
        assertEquals(StartupStage.VoiceSync, (decision as StartupDecision.Loading).stage)
    }

    @Test
    fun voiceServiceError_returnsActionRequiredVoiceServiceError() {
        val decision = StartupPolicy.evaluate(
            isVisionRestoreCompleted = true,
            visionModelState = readyVision,
            isVoiceServiceSynced = true,
            voiceState = VoiceState(phase = VoicePhase.Error),
            missingRuntimePermissions = emptySet(),
            hasCompletedPermissionCheck = true,
            userAcceptedDegraded = false,
        )
        assertTrue(decision is StartupDecision.ActionRequired)
        assertEquals(StartupIssue.VoiceServiceError, (decision as StartupDecision.ActionRequired).issue)
    }

    @Test
    fun permissionsNotYetChecked_returnsLoadingPermissionStage() {
        val decision = StartupPolicy.evaluate(
            isVisionRestoreCompleted = true,
            visionModelState = readyVision,
            isVoiceServiceSynced = true,
            voiceState = VoiceState(phase = VoicePhase.Stopped),
            missingRuntimePermissions = setOf(StartupPermissionInventory.RECORD_AUDIO),
            hasCompletedPermissionCheck = false,
            userAcceptedDegraded = false,
        )
        assertTrue(decision is StartupDecision.Loading)
        assertEquals(StartupStage.RuntimePermissions, (decision as StartupDecision.Loading).stage)
    }

    @Test
    fun incompletePermissionRoundCannotAdmitEvenWhenSystemsAreReady() {
        val decision = StartupPolicy.evaluate(
            isVisionRestoreCompleted = true,
            visionModelState = readyVision,
            isVoiceServiceSynced = true,
            voiceState = waitingVoice,
            missingRuntimePermissions = emptySet(),
            hasCompletedPermissionCheck = false,
        )
        assertTrue(decision is StartupDecision.Loading)
        assertEquals(StartupStage.RuntimePermissions, (decision as StartupDecision.Loading).stage)
    }

    @Test
    fun microphoneDenied_returnsActionRequiredPermissionRecovery() {
        val decision = StartupPolicy.evaluate(
            isVisionRestoreCompleted = true,
            visionModelState = readyVision,
            isVoiceServiceSynced = true,
            voiceState = VoiceState(phase = VoicePhase.Stopped),
            missingRuntimePermissions = setOf(StartupPermissionInventory.RECORD_AUDIO),
            hasCompletedPermissionCheck = true,
            userAcceptedDegraded = false,
        )
        assertTrue(decision is StartupDecision.ActionRequired)
        val action = decision as StartupDecision.ActionRequired
        assertEquals(StartupIssue.RuntimePermissionsDenied, action.issue)
        assertTrue(action.canContinueDegraded)
        assertTrue(action.needsSettings)
    }

    @Test
    fun mixedPermissionDenialOffersSettingsAndExplicitDegradedContinuation() {
        val decision = StartupPolicy.evaluate(
            isVisionRestoreCompleted = true,
            visionModelState = readyVision,
            isVoiceServiceSynced = true,
            voiceState = waitingVoice,
            missingRuntimePermissions = setOf(
                StartupPermissionInventory.RECORD_AUDIO,
                StartupPermissionInventory.CAMERA,
                StartupPermissionInventory.POST_NOTIFICATIONS,
            ),
            hasCompletedPermissionCheck = true,
        ) as StartupDecision.ActionRequired

        assertEquals(StartupIssue.RuntimePermissionsDenied, decision.issue)
        assertTrue(decision.canRetry)
        assertTrue(decision.needsSettings)
        assertTrue(decision.canContinueDegraded)
        assertTrue(decision.message.contains("마이크"))
        assertTrue(decision.message.contains("카메라"))
        assertTrue(decision.message.contains("알림"))
    }

    @Test
    fun voicePhaseStoppedOrInitializing_returnsLoadingVoiceSync() {
        val decision = StartupPolicy.evaluate(
            isVisionRestoreCompleted = true,
            visionModelState = readyVision,
            isVoiceServiceSynced = true,
            voiceState = VoiceState(phase = VoicePhase.Initializing),
            missingRuntimePermissions = emptySet(),
            hasCompletedPermissionCheck = true,
            userAcceptedDegraded = false,
        )
        assertTrue(decision is StartupDecision.Loading)
        assertEquals(StartupStage.VoiceSync, (decision as StartupDecision.Loading).stage)
    }

    @Test
    fun reconnectingAndStoppingVoicePhasesNeverAdmitHome() {
        listOf(VoicePhase.Reconnecting, VoicePhase.Stopping).forEach { phase ->
            val decision = StartupPolicy.evaluate(
                isVisionRestoreCompleted = true,
                visionModelState = readyVision,
                isVoiceServiceSynced = true,
                voiceState = VoiceState(phase = phase),
                missingRuntimePermissions = emptySet(),
                hasCompletedPermissionCheck = true,
            )
            assertEquals(StartupStage.VoiceSync, (decision as StartupDecision.Loading).stage)
        }
    }

    @Test
    fun dictatingAndSpeakingAreStableUsableVoicePhases() {
        listOf(VoicePhase.Dictating, VoicePhase.Speaking).forEach { phase ->
            val decision = StartupPolicy.evaluate(
                isVisionRestoreCompleted = true,
                visionModelState = readyVision,
                isVoiceServiceSynced = true,
                voiceState = VoiceState(phase = phase),
                missingRuntimePermissions = emptySet(),
                hasCompletedPermissionCheck = true,
            )
            assertTrue("$phase should be usable during startup", decision is StartupDecision.Ready)
        }
    }

    @Test
    fun languageGemmaPreparing_returnsLoadingLanguagePrepare() {
        val decision = StartupPolicy.evaluate(
            isVisionRestoreCompleted = true,
            visionModelState = readyVision,
            isVoiceServiceSynced = true,
            voiceState = VoiceState(
                phase = VoicePhase.Waiting,
                command = CommandSnapshot(languageStatus = LanguageStatus.Preparing)
            ),
            missingRuntimePermissions = emptySet(),
            hasCompletedPermissionCheck = true,
            userAcceptedDegraded = false,
        )
        assertTrue(decision is StartupDecision.Loading)
        assertEquals(StartupStage.LanguagePrepare, (decision as StartupDecision.Loading).stage)
    }

    @Test
    fun languageGemmaFailed_returnsActionRequiredLanguageFailed() {
        val decision = StartupPolicy.evaluate(
            isVisionRestoreCompleted = true,
            visionModelState = readyVision,
            isVoiceServiceSynced = true,
            voiceState = VoiceState(
                phase = VoicePhase.Waiting,
                command = CommandSnapshot(languageStatus = LanguageStatus.Failed)
            ),
            missingRuntimePermissions = emptySet(),
            hasCompletedPermissionCheck = true,
            userAcceptedDegraded = false,
        )
        assertTrue(decision is StartupDecision.ActionRequired)
        val action = decision as StartupDecision.ActionRequired
        assertEquals(StartupIssue.LanguagePrepareFailed, action.issue)
        assertTrue(action.canContinueDegraded)
    }

    @Test
    fun timedOutLanguagePreparationRequiresActionInsteadOfFakingReady() {
        val decision = StartupPolicy.evaluate(
            isVisionRestoreCompleted = true,
            visionModelState = readyVision,
            isVoiceServiceSynced = true,
            voiceState = VoiceState(
                phase = VoicePhase.Waiting,
                command = CommandSnapshot(languageStatus = LanguageStatus.Preparing),
            ),
            missingRuntimePermissions = emptySet(),
            hasCompletedPermissionCheck = true,
            timedOutStage = StartupStage.LanguagePrepare,
        )
        val action = decision as StartupDecision.ActionRequired
        assertEquals(StartupIssue.StartupTimeout, action.issue)
        assertEquals(StartupStage.LanguagePrepare, action.stage)
        assertTrue(action.canRetry)
        assertTrue(action.canContinueDegraded)
    }

    @Test
    fun allSystemsReady_withMissingLanguage_isNonBlocking_returnsReady() {
        val decision = StartupPolicy.evaluate(
            isVisionRestoreCompleted = true,
            visionModelState = readyVision,
            isVoiceServiceSynced = true,
            voiceState = VoiceState(
                phase = VoicePhase.Waiting,
                command = CommandSnapshot(languageStatus = LanguageStatus.Missing)
            ),
            missingRuntimePermissions = emptySet(),
            hasCompletedPermissionCheck = true,
            userAcceptedDegraded = false,
        )
        assertTrue(decision is StartupDecision.Ready)
        assertFalse((decision as StartupDecision.Ready).isDegraded)
    }

    @Test
    fun allSystemsReady_withReadyLanguage_returnsReady() {
        val decision = StartupPolicy.evaluate(
            isVisionRestoreCompleted = true,
            visionModelState = readyVision,
            isVoiceServiceSynced = true,
            voiceState = VoiceState(
                phase = VoicePhase.Waiting,
                command = CommandSnapshot(languageStatus = LanguageStatus.Ready)
            ),
            missingRuntimePermissions = emptySet(),
            hasCompletedPermissionCheck = true,
            userAcceptedDegraded = false,
        )
        assertTrue(decision is StartupDecision.Ready)
        assertFalse((decision as StartupDecision.Ready).isDegraded)
    }

    @Test
    fun modelDegradedChoiceCannotSkipPermissionDecision() {
        val decision = StartupPolicy.evaluate(
            isVisionRestoreCompleted = true,
            visionModelState = VisionModelState.Missing,
            isVoiceServiceSynced = true,
            voiceState = VoiceState(phase = VoicePhase.Error),
            missingRuntimePermissions = setOf(StartupPermissionInventory.RECORD_AUDIO),
            hasCompletedPermissionCheck = true,
            userAcceptedDegraded = true,
        )
        assertTrue(decision is StartupDecision.ActionRequired)
        assertEquals(StartupIssue.RuntimePermissionsDenied, (decision as StartupDecision.ActionRequired).issue)
    }

    @Test
    fun acceptedMissingMicrophoneWaitsForVisionButDoesNotWaitForVoice() {
        val decision = StartupPolicy.evaluate(
            isVisionRestoreCompleted = true,
            visionModelState = readyVision,
            isVoiceServiceSynced = false,
            voiceState = VoiceState(phase = VoicePhase.Stopped),
            missingRuntimePermissions = setOf(StartupPermissionInventory.RECORD_AUDIO),
            hasCompletedPermissionCheck = true,
            userAcceptedPermissionDegraded = true,
        )
        assertTrue(decision is StartupDecision.Ready)
        assertTrue((decision as StartupDecision.Ready).isDegraded)
    }

    @Test
    fun acceptedMissingCameraStillWaitsForVoiceReadiness() {
        val decision = StartupPolicy.evaluate(
            isVisionRestoreCompleted = true,
            visionModelState = readyVision,
            isVoiceServiceSynced = true,
            voiceState = VoiceState(phase = VoicePhase.Stopped),
            missingRuntimePermissions = setOf(StartupPermissionInventory.CAMERA),
            hasCompletedPermissionCheck = true,
            userAcceptedPermissionDegraded = true,
        )
        assertTrue(decision is StartupDecision.Loading)
        assertEquals(StartupStage.VoiceSync, (decision as StartupDecision.Loading).stage)
    }

    @Test
    fun acceptedMissingNotificationsDoesNotBlockReadyVoiceAndVision() {
        val decision = StartupPolicy.evaluate(
            isVisionRestoreCompleted = true,
            visionModelState = readyVision,
            isVoiceServiceSynced = true,
            voiceState = waitingVoice,
            missingRuntimePermissions = setOf(StartupPermissionInventory.POST_NOTIFICATIONS),
            hasCompletedPermissionCheck = true,
            userAcceptedPermissionDegraded = true,
        )
        assertTrue(decision is StartupDecision.Ready)
        assertTrue((decision as StartupDecision.Ready).isDegraded)
    }

    @Test
    fun acceptedMissingCameraDoesNotSkipVisionModelLoading() {
        val decision = StartupPolicy.evaluate(
            isVisionRestoreCompleted = true,
            visionModelState = VisionModelState.Loading("model.tflite"),
            isVoiceServiceSynced = true,
            voiceState = waitingVoice,
            missingRuntimePermissions = setOf(StartupPermissionInventory.CAMERA),
            hasCompletedPermissionCheck = true,
            userAcceptedPermissionDegraded = true,
        )
        assertTrue(decision is StartupDecision.Loading)
        assertEquals(StartupStage.VisionRestore, (decision as StartupDecision.Loading).stage)
    }
}
