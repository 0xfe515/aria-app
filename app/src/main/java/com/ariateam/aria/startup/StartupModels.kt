package com.ariateam.aria.startup

enum class StartupStage {
    Initializing,
    VisionRestore,
    VoiceSync,
    RuntimePermissions,
    LanguagePrepare,
    Ready,
}

enum class StartupIssue {
    None,
    VisionModelMissing,
    VisionModelError,
    RuntimePermissionsDenied,
    VoiceServiceError,
    LanguagePrepareFailed,
    StartupTimeout,
}

sealed interface StartupDecision {
    data class Loading(
        val stage: StartupStage,
        val message: String,
    ) : StartupDecision

    data class Ready(
        val isDegraded: Boolean = false,
        val issue: StartupIssue = StartupIssue.None,
    ) : StartupDecision

    data class ActionRequired(
        val issue: StartupIssue,
        val title: String,
        val message: String,
        val canRetry: Boolean = true,
        val canContinueDegraded: Boolean = true,
        val needsSettings: Boolean = false,
        val stage: StartupStage? = null,
    ) : StartupDecision
}
