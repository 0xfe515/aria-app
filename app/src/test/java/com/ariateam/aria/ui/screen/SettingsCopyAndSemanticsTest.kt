package com.ariateam.aria.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Focused unit tests verifying camera recovery guidance behavior without voice claims.
 */
class SettingsCopyAndSemanticsTest {

    @Test
    fun computeMainVisionGuidance_truthfulSettingsRecoveryWithoutVoiceClaims() {
        // When explicit restart is required, recovery must refer to Settings or Diagnostics, never voice commands
        val restartGuidance = computeMainVisionGuidance(
            status = MainVisionStatus.Stopped,
            requiresExplicitRestart = true,
            isActionableSetupHint = false,
        )
        assertEquals("설정 또는 진단 화면에서 카메라를 다시 시작할 수 있습니다.", restartGuidance)
        assertFalse("Must not claim voice command restart", restartGuidance.contains("음성 명령"))

        // When inactive, guidance points to Settings or Diagnostics, never voice activation
        val defaultGuidance = computeMainVisionGuidance(
            status = MainVisionStatus.Stopped,
            requiresExplicitRestart = false,
            isActionableSetupHint = false,
        )
        assertEquals("설정 또는 진단 화면에서 카메라를 시작할 수 있습니다.", defaultGuidance)
        assertFalse("Must not claim voice command activation", defaultGuidance.contains("음성 명령"))

        // Permission denied guidance
        val permissionGuidance = computeMainVisionGuidance(
            status = MainVisionStatus.PermissionDenied,
            requiresExplicitRestart = false,
            isActionableSetupHint = false,
        )
        assertEquals("카메라 권한을 허용해야 비전 모니터링이 시작됩니다.", permissionGuidance)

        // Actionable setup hint guidance
        val setupGuidance = computeMainVisionGuidance(
            status = MainVisionStatus.Stopped,
            requiresExplicitRestart = false,
            isActionableSetupHint = true,
        )
        assertEquals("설정에서 키와 착용 위치를 등록할 수 있습니다.", setupGuidance)
    }
}
