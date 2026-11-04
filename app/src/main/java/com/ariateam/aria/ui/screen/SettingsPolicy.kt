package com.ariateam.aria.ui.screen

import com.ariateam.aria.R
import com.ariateam.aria.distance.BodyMountPosition

/**
 * Formal policy and category organization for Aria settings screens.
 *
 * Requirements (ARIA-FIELD-SETTINGS-020):
 * - Exact category order: General, Account, Diagnostics, Demo, App info
 * - Korean labels: 일반, 계정, 진단, 데모, 앱 정보
 * - General holds: stature/mount (removed from demo), voice wake, audible feedback, notifications
 * - Account holds: Gemini API key register/status/delete, replaces local LLM UI/dependencies
 *   (retains on-device Local ASR and Vision models, no local LLM download/setup gating)
 * - Diagnostics holds: strictly developer state/probes (Vision diagnostics, Voice diagnostics, App settings)
 * - Demo holds: unified demo/presentation entrances (Presentation demo, AR distance demo);
 *   strictly NO ordinary settings (e.g. mount profile) and NO diagnostics
 * - Mount choices: exactly "어깨" and "가슴" with NO explanatory subtitles
 * - Scope: Gemini only, no provider expansion; API key store not reset
 */
enum class SettingsCategory(
    val titleResId: Int,
    val summaryResId: Int,
    val koreanLabel: String,
) {
    General(
        titleResId = R.string.settings_general_title,
        summaryResId = R.string.settings_general_summary,
        koreanLabel = "일반",
    ),
    Account(
        titleResId = R.string.settings_account_title,
        summaryResId = R.string.settings_account_group,
        koreanLabel = "계정",
    ),
    Diagnostics(
        titleResId = R.string.settings_diagnostics_title,
        summaryResId = R.string.settings_diagnostics_summary,
        koreanLabel = "진단",
    ),
    Demo(
        titleResId = R.string.settings_demo_page_title,
        summaryResId = R.string.settings_demo_page_summary,
        koreanLabel = "데모",
    ),
    AppInfo(
        titleResId = R.string.app_info,
        summaryResId = R.string.app_version,
        koreanLabel = "앱 정보",
    );
}

object SettingsPolicy {
    val ORDERED_CATEGORIES: List<SettingsCategory> = listOf(
        SettingsCategory.General,
        SettingsCategory.Account,
        SettingsCategory.Diagnostics,
        SettingsCategory.Demo,
        SettingsCategory.AppInfo,
    )

    val EXPECTED_KOREAN_LABELS: List<String> = ORDERED_CATEGORIES.map { it.koreanLabel }

    /**
     * Mount choices must be exactly Shoulder ("어깨") and Chest ("가슴") with no subtitles.
     */
    val MOUNT_CHOICES: List<BodyMountPosition> = listOf(
        BodyMountPosition.SHOULDER,
        BodyMountPosition.CHEST,
    )

    val MOUNT_CHOICE_LABELS: Map<BodyMountPosition, String> = mapOf(
        BodyMountPosition.SHOULDER to "어깨",
        BodyMountPosition.CHEST to "가슴",
    )

    const val HAS_MOUNT_EXPLANATORY_SUBTITLES: Boolean = false

    /**
     * Account provider policy: Gemini only. No provider expansion permitted.
     */
    fun isSupportedAccountProvider(provider: String): Boolean =
        provider.equals("gemini", ignoreCase = true)

    /**
     * Validates whether a category is permitted to hold stature and mount profile setup.
     * Only General is permitted; Demo is forbidden from holding mount profile.
     */
    fun isMountProfileAllowed(category: SettingsCategory): Boolean =
        category == SettingsCategory.General

    /**
     * Validates whether a category is permitted to hold presentation demo entrance.
     * Only Demo is permitted; Diagnostics is forbidden from holding presentation demo.
     */
    fun isPresentationDemoAllowed(category: SettingsCategory): Boolean =
        category == SettingsCategory.Demo

    /**
     * Validates whether a category is permitted to hold developer diagnostics.
     * Only Diagnostics is permitted; Demo is forbidden from holding developer diagnostics.
     */
    fun isDeveloperDiagnosticsAllowed(category: SettingsCategory): Boolean =
        category == SettingsCategory.Diagnostics

    /**
     * Determines whether local conversation model (Gemma) management is active in UI.
     * Replaced entirely by Account (Gemini API key).
     */
    const val IS_LOCAL_CONVERSATION_LLM_MANAGEMENT_ENABLED: Boolean = false

    /**
     * Retained on-device models (ASR and Vision) remain active.
     */
    const val IS_LOCAL_ASR_RETAINED: Boolean = true
    const val IS_LOCAL_VISION_RETAINED: Boolean = true
}
