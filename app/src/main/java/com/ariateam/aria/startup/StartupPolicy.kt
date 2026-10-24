package com.ariateam.aria.startup

import com.ariateam.aria.command.LanguageStatus
import com.ariateam.aria.speech.VoicePhase
import com.ariateam.aria.speech.VoiceState
import com.ariateam.aria.vision.VisionModelState

object StartupPolicy {
    fun evaluate(
        isVisionRestoreCompleted: Boolean,
        visionModelState: VisionModelState,
        isVoiceServiceSynced: Boolean,
        voiceState: VoiceState,
        missingRuntimePermissions: Set<String> = emptySet(),
        hasCompletedPermissionCheck: Boolean = true,
        userAcceptedDegraded: Boolean = false,
        userAcceptedPermissionDegraded: Boolean = false,
        timedOutStage: StartupStage? = null,
    ): StartupDecision {
        if (!hasCompletedPermissionCheck) {
            return pending(
                StartupStage.RuntimePermissions,
                "권한 확인 중…",
                timedOutStage,
            )
        }
        if (missingRuntimePermissions.isNotEmpty() && !userAcceptedPermissionDegraded) {
            val missingSummary = StartupPermissionInventory.deniedPermissionSummary(missingRuntimePermissions)
            val permissionLabel = missingSummary.takeIf { it.isNotEmpty() }?.let { "$it 권한" } ?: "일부 권한"
            return StartupDecision.ActionRequired(
                issue = StartupIssue.RuntimePermissionsDenied,
                title = "권한을 확인해 주세요",
                message = "${permissionLabel}이 허용되지 않았어요. 설정에서 변경하거나 해당 기능 없이 계속 이용할 수 있습니다.",
                canRetry = true,
                canContinueDegraded = true,
                needsSettings = true,
            )
        }
        if (userAcceptedDegraded) {
            return StartupDecision.Ready(isDegraded = true)
        }

        // 1. Vision model initial restore gating
        if (!isVisionRestoreCompleted) {
            return pending(
                StartupStage.VisionRestore,
                "주변 인식 준비 중…",
                timedOutStage,
            )
        }
        if (visionModelState is VisionModelState.Loading) {
            return pending(
                StartupStage.VisionRestore,
                "주변 인식 준비 중…",
                timedOutStage,
            )
        }
        if (visionModelState is VisionModelState.Error) {
            return StartupDecision.ActionRequired(
                issue = StartupIssue.VisionModelError,
                title = "주변 인식을 준비하지 못했어요",
                message = "주변 인식 기능을 설정에서 다시 준비하거나 음성 기능만 계속 이용할 수 있습니다.",
                canRetry = true,
                canContinueDegraded = true,
                needsSettings = true,
            )
        }
        if (visionModelState is VisionModelState.Missing) {
            return StartupDecision.ActionRequired(
                issue = StartupIssue.VisionModelMissing,
                title = "주변 인식 기능을 사용할 수 없어요",
                message = "설정에서 주변 인식을 준비하거나 음성 기능만 계속 이용할 수 있습니다.",
                canRetry = true,
                canContinueDegraded = true,
                needsSettings = true,
            )
        }

        if (userAcceptedPermissionDegraded &&
            StartupPermissionInventory.RECORD_AUDIO in missingRuntimePermissions
        ) {
            return StartupDecision.Ready(isDegraded = true)
        }

        // 2. Voice service connection / sync
        if (!isVoiceServiceSynced) {
            if (voiceState.phase == VoicePhase.Error) {
                return StartupDecision.ActionRequired(
                    issue = StartupIssue.VoiceServiceError,
                    title = "음성 기능을 시작하지 못했어요",
                    message = "음성 기능을 다시 시도하거나 음성 없이 계속 이용할 수 있습니다.",
                    canRetry = true,
                    canContinueDegraded = true,
                    needsSettings = false,
                )
            }
            return pending(
                StartupStage.VoiceSync,
                "음성 기능 준비 중…",
                timedOutStage,
            )
        }

        // 3. Voice session phase
        if (voiceState.phase == VoicePhase.Error) {
            return StartupDecision.ActionRequired(
                issue = StartupIssue.VoiceServiceError,
                title = "음성 기능을 시작하지 못했어요",
                message = "음성 기능을 다시 시도하거나 음성 없이 계속 이용할 수 있습니다.",
                canRetry = true,
                canContinueDegraded = true,
                needsSettings = false,
            )
        }
        if (voiceState.phase !in listOf(
                VoicePhase.Waiting,
                VoicePhase.Dictating,
                VoicePhase.Speaking,
            )
        ) {
            return pending(
                StartupStage.VoiceSync,
                "음성 기능 준비 중…",
                timedOutStage,
            )
        }

        // 4. Language model (Gemma) readiness if installed
        if (voiceState.command.languageStatus == LanguageStatus.Preparing) {
            return pending(
                StartupStage.LanguagePrepare,
                "명령 처리 준비 중…",
                timedOutStage,
            )
        }
        if (voiceState.command.languageStatus == LanguageStatus.Failed) {
            return StartupDecision.ActionRequired(
                issue = StartupIssue.LanguagePrepareFailed,
                title = "명령 처리 기능을 준비하지 못했어요",
                message = "다시 시도하거나 기본 음성 기능으로 계속 이용할 수 있습니다.",
                canRetry = true,
                canContinueDegraded = true,
                needsSettings = false,
            )
        }

        // 5. All systems ready
        return StartupDecision.Ready(
            isDegraded = userAcceptedPermissionDegraded && missingRuntimePermissions.isNotEmpty(),
        )
    }

    private fun pending(
        stage: StartupStage,
        message: String,
        timedOutStage: StartupStage?,
    ): StartupDecision {
        if (timedOutStage != stage) {
            return StartupDecision.Loading(stage = stage, message = message)
        }
        return StartupDecision.ActionRequired(
            issue = StartupIssue.StartupTimeout,
            title = "준비가 오래 걸리고 있어요",
            message = when (stage) {
                StartupStage.VisionRestore -> "주변 인식을 다시 준비하거나 음성 기능만 계속 이용할 수 있습니다."
                StartupStage.VoiceSync -> "음성 기능을 다시 시도하거나 음성 없이 계속 이용할 수 있습니다."
                StartupStage.RuntimePermissions -> "권한을 설정에서 확인한 뒤 다시 시도하거나 계속 이용할 수 있습니다."
                StartupStage.LanguagePrepare -> "명령 처리 기능을 다시 시도하거나 기본 음성 기능으로 계속 이용할 수 있습니다."
                else -> "다시 시도하거나 계속 이용할 수 있습니다."
            },
            needsSettings = stage == StartupStage.VisionRestore || stage == StartupStage.RuntimePermissions,
            stage = stage,
        )
    }
}
