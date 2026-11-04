package com.ariateam.aria.ui.screen

import com.ariateam.aria.distance.BodyMountPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit regression tests for ARIA-FIELD-SETTINGS-020 settings reorganization.
 *
 * Verifies:
 * - Exact category ordering and Korean labels (일반, 계정, 진단, 데모, 앱 정보)
 * - Consolidation of ordinary settings and stature/mount in General (removed from Demo)
 * - Developer state/probes isolated in Diagnostics with NO duplicate demo entrance
 * - Unified presentation and AR distance entrances in Demo with NO ordinary settings or diagnostics
 * - Account scope: Gemini only, replaces local conversation LLM management while retaining
 *   on-device Local ASR and Vision models
 * - Mount choices: exactly "어깨" and "가슴" with NO explanatory subtitles
 */
class SettingsPolicyTest {

    @Test
    fun exactMenuOrderingAndKoreanLabels() {
        val expectedCategories = listOf(
            SettingsCategory.General,
            SettingsCategory.Account,
            SettingsCategory.Diagnostics,
            SettingsCategory.Demo,
            SettingsCategory.AppInfo,
        )
        assertEquals(expectedCategories, SettingsPolicy.ORDERED_CATEGORIES)
        assertEquals(5, SettingsPolicy.ORDERED_CATEGORIES.size)

        val expectedLabels = listOf("일반", "계정", "진단", "데모", "앱 정보")
        assertEquals(expectedLabels, SettingsPolicy.EXPECTED_KOREAN_LABELS)
        assertEquals("일반", SettingsCategory.General.koreanLabel)
        assertEquals("계정", SettingsCategory.Account.koreanLabel)
        assertEquals("진단", SettingsCategory.Diagnostics.koreanLabel)
        assertEquals("데모", SettingsCategory.Demo.koreanLabel)
        assertEquals("앱 정보", SettingsCategory.AppInfo.koreanLabel)
    }

    @Test
    fun generalConsolidatesStatureMountAndOrdinarySettings() {
        // Mount profile is allowed only in General
        assertTrue(SettingsPolicy.isMountProfileAllowed(SettingsCategory.General))
        assertFalse(SettingsPolicy.isMountProfileAllowed(SettingsCategory.Demo))
        assertFalse(SettingsPolicy.isMountProfileAllowed(SettingsCategory.Diagnostics))
        assertFalse(SettingsPolicy.isMountProfileAllowed(SettingsCategory.Account))
        assertFalse(SettingsPolicy.isMountProfileAllowed(SettingsCategory.AppInfo))
    }

    @Test
    fun diagnosticsOnlyContainsDeveloperStateAndProbes() {
        // Diagnostics is allowed developer diagnostics
        assertTrue(SettingsPolicy.isDeveloperDiagnosticsAllowed(SettingsCategory.Diagnostics))
        assertFalse(SettingsPolicy.isDeveloperDiagnosticsAllowed(SettingsCategory.General))
        assertFalse(SettingsPolicy.isDeveloperDiagnosticsAllowed(SettingsCategory.Account))
        assertFalse(SettingsPolicy.isDeveloperDiagnosticsAllowed(SettingsCategory.Demo))
        assertFalse(SettingsPolicy.isDeveloperDiagnosticsAllowed(SettingsCategory.AppInfo))

        // Presentation demo is strictly FORBIDDEN in Diagnostics (no duplicate demo entrance)
        assertFalse(SettingsPolicy.isPresentationDemoAllowed(SettingsCategory.Diagnostics))
    }

    @Test
    fun demoUnifiesPresentationAndArDistanceAndContainsNoOrdinarySettingsOrDiagnostics() {
        // Demo holds presentation demo
        assertTrue(SettingsPolicy.isPresentationDemoAllowed(SettingsCategory.Demo))

        // Demo must NOT hold stature/mount profile or diagnostics
        assertFalse(SettingsPolicy.isMountProfileAllowed(SettingsCategory.Demo))
        assertFalse(SettingsPolicy.isDeveloperDiagnosticsAllowed(SettingsCategory.Demo))
    }

    @Test
    fun accountReplacesLocalLlmManagementAndScopeIsGeminiOnly() {
        // Local conversation LLM UI management is disabled / removed
        assertFalse(SettingsPolicy.IS_LOCAL_CONVERSATION_LLM_MANAGEMENT_ENABLED)

        // Local ASR and Vision models are retained
        assertTrue(SettingsPolicy.IS_LOCAL_ASR_RETAINED)
        assertTrue(SettingsPolicy.IS_LOCAL_VISION_RETAINED)

        // Gemini only account provider policy
        assertTrue(SettingsPolicy.isSupportedAccountProvider("gemini"))
        assertTrue(SettingsPolicy.isSupportedAccountProvider("GEMINI"))
        assertTrue(SettingsPolicy.isSupportedAccountProvider("Gemini"))
        assertFalse(SettingsPolicy.isSupportedAccountProvider("openai"))
        assertFalse(SettingsPolicy.isSupportedAccountProvider("anthropic"))
        assertFalse(SettingsPolicy.isSupportedAccountProvider("ollama"))
    }

    @Test
    fun mountChoicesAreConciseWithoutExplanatorySubtitles() {
        val expectedChoices = listOf(BodyMountPosition.SHOULDER, BodyMountPosition.CHEST)
        assertEquals(expectedChoices, SettingsPolicy.MOUNT_CHOICES)
        assertEquals(2, SettingsPolicy.MOUNT_CHOICES.size)

        assertEquals("어깨", SettingsPolicy.MOUNT_CHOICE_LABELS[BodyMountPosition.SHOULDER])
        assertEquals("가슴", SettingsPolicy.MOUNT_CHOICE_LABELS[BodyMountPosition.CHEST])

        // No explanatory subtitles allowed
        assertFalse(SettingsPolicy.HAS_MOUNT_EXPLANATORY_SUBTITLES)
    }
}
