package com.ariateam.aria.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ariateam.aria.R
import com.ariateam.aria.cloud.AndroidKeyStoreAriaKeyStorage
import com.ariateam.aria.cloud.AriaKeyStorage
import com.ariateam.aria.distance.BodyMountProfile
import com.ariateam.aria.distance.BodyMountProfileStore
import com.ariateam.aria.speech.VoiceError
import com.ariateam.aria.speech.VoicePhase
import com.ariateam.aria.speech.VoiceState
import com.ariateam.aria.ui.theme.AriaSlate
import kotlin.math.roundToInt

private enum class SettingsPage {
    Root,
    General,
    Account,
    Diagnostics,
    Demo,
    AppInfo,
}

@Composable
fun SettingsScreen(
    state: VoiceState,
    onStartWaiting: () -> Unit,
    onStop: () -> Unit,
    audibleFeedback: Boolean = true,
    onAudibleFeedbackChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    onDownloadModel: (Boolean) -> Unit = {},
    onCancelDownload: () -> Unit = {},
    onDeleteModel: () -> Unit = {},
    onNotificationSettings: () -> Unit = {},
    onOpenVisionDiagnostics: () -> Unit = {},
    onOpenVoiceDiagnostics: () -> Unit = {},
    onOpenPresentationDemo: () -> Unit = {},
    initialDebugExpanded: Boolean = false,
    onOpenLicenses: () -> Unit = {},
    onOpenDistanceMode: () -> Unit = {},
    onOpenProfileSetup: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    keyStorage: AriaKeyStorage? = null,
) {
    val context = LocalContext.current
    val effectiveKeyStorage = remember(context, keyStorage) {
        keyStorage ?: AndroidKeyStoreAriaKeyStorage(context)
    }
    var hasApiKey by remember { mutableStateOf(effectiveKeyStorage.hasApiKey()) }
    var maskedKey by remember { mutableStateOf(effectiveKeyStorage.getMaskedApiKey()) }
    var showKeyInputDialog by rememberSaveable { mutableStateOf(false) }
    var confirmKeyDelete by rememberSaveable { mutableStateOf(false) }
    var keyInputText by rememberSaveable { mutableStateOf("") }

    val profileStore = remember(context) { BodyMountProfileStore(context) }
    DisposableEffect(profileStore) {
        onDispose {
            profileStore.close()
        }
    }
    val profile by profileStore.profile.collectAsStateWithLifecycle()

    var page by rememberSaveable {
        mutableStateOf(if (initialDebugExpanded) SettingsPage.Diagnostics else SettingsPage.Root)
    }
    var showInternalProfileDialog by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = page != SettingsPage.Root) {
        page = SettingsPage.Root
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (page) {
            SettingsPage.Root -> SettingsRoot(
                hasApiKey = hasApiKey,
                maskedKey = maskedKey,
                onOpenGeneral = { page = SettingsPage.General },
                onOpenAccount = { page = SettingsPage.Account },
                onOpenDiagnostics = { page = SettingsPage.Diagnostics },
                onOpenDemo = { page = SettingsPage.Demo },
                onOpenAppInfo = { page = SettingsPage.AppInfo },
            )

            SettingsPage.General -> GeneralSettings(
                state = state,
                audibleFeedback = audibleFeedback,
                onAudibleFeedbackChanged = onAudibleFeedbackChanged,
                onStartWaiting = onStartWaiting,
                onStop = onStop,
                onNotificationSettings = onNotificationSettings,
                profile = profile,
                onOpenProfileSetup = {
                    onOpenProfileSetup()
                    showInternalProfileDialog = true
                },
                onBack = { page = SettingsPage.Root },
            )

            SettingsPage.Account -> AccountSettings(
                hasApiKey = hasApiKey,
                maskedKey = maskedKey,
                onRegisterKey = {
                    keyInputText = ""
                    showKeyInputDialog = true
                },
                onChangeKey = {
                    keyInputText = ""
                    showKeyInputDialog = true
                },
                onClearKey = {
                    confirmKeyDelete = true
                },
                onBack = { page = SettingsPage.Root },
            )

            SettingsPage.Diagnostics -> DiagnosticsSettings(
                onOpenVisionDiagnostics = onOpenVisionDiagnostics,
                onOpenVoiceDiagnostics = onOpenVoiceDiagnostics,
                onOpenAppSettings = onOpenAppSettings,
                onBack = { page = SettingsPage.Root },
            )

            SettingsPage.Demo -> DemoSettings(
                onOpenDistanceMode = onOpenDistanceMode,
                onOpenPresentationDemo = onOpenPresentationDemo,
                onBack = { page = SettingsPage.Root },
            )

            SettingsPage.AppInfo -> AppInfoSettings(
                onOpenLicenses = onOpenLicenses,
                onBack = { page = SettingsPage.Root },
            )
        }
    }

    if (showInternalProfileDialog) {
        BodyMountProfileDialog(
            onDismiss = { showInternalProfileDialog = false },
            initialProfile = profile,
            profileStore = profileStore,
        )
    }

    if (showKeyInputDialog) {
        AlertDialog(
            onDismissRequest = { showKeyInputDialog = false },
            title = { Text(stringResource(R.string.settings_cloud_key_input_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.settings_cloud_privacy_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = keyInputText,
                        onValueChange = { keyInputText = it },
                        modifier = Modifier.fillMaxWidth().testTag("gemini_key_input"),
                        label = { Text(stringResource(R.string.settings_cloud_key_input_hint)) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = keyInputText.trim()
                        if (trimmed.isNotEmpty()) {
                            effectiveKeyStorage.saveApiKey(trimmed)
                            hasApiKey = effectiveKeyStorage.hasApiKey()
                            maskedKey = effectiveKeyStorage.getMaskedApiKey()
                        }
                        showKeyInputDialog = false
                    },
                ) {
                    Text(stringResource(R.string.settings_cloud_key_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showKeyInputDialog = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }

    if (confirmKeyDelete) {
        AlertDialog(
            onDismissRequest = { confirmKeyDelete = false },
            title = { Text(stringResource(R.string.settings_cloud_key_delete_title)) },
            text = { Text(stringResource(R.string.settings_cloud_key_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmKeyDelete = false
                        effectiveKeyStorage.clearApiKey()
                        hasApiKey = effectiveKeyStorage.hasApiKey()
                        maskedKey = effectiveKeyStorage.getMaskedApiKey()
                    },
                ) {
                    Text(stringResource(R.string.settings_cloud_key_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmKeyDelete = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

@Composable
private fun SettingsRoot(
    hasApiKey: Boolean,
    maskedKey: String?,
    onOpenGeneral: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenDemo: () -> Unit,
    onOpenAppInfo: () -> Unit,
) {
    Text(
        text = stringResource(R.string.settings_tab),
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(8.dp))
    SettingsSectionTitle(stringResource(R.string.settings_main_group))

    // 1. 일반 (General)
    SettingsNavigationRow(
        title = stringResource(R.string.settings_general_title),
        summary = stringResource(R.string.settings_general_summary),
        onClick = onOpenGeneral,
    )

    // 2. 계정 (Account)
    SettingsNavigationRow(
        title = stringResource(R.string.settings_account_title),
        summary = if (hasApiKey) {
            stringResource(R.string.settings_cloud_summary_registered, maskedKey.orEmpty())
        } else {
            stringResource(R.string.settings_cloud_summary_empty)
        },
        onClick = onOpenAccount,
    )

    // 3. 진단 (Diagnostics)
    SettingsNavigationRow(
        title = stringResource(R.string.settings_diagnostics_title),
        summary = stringResource(R.string.settings_diagnostics_summary),
        onClick = onOpenDiagnostics,
    )

    // 4. 데모 (Demo)
    SettingsNavigationRow(
        title = stringResource(R.string.settings_demo_page_title),
        summary = stringResource(R.string.settings_demo_page_summary),
        onClick = onOpenDemo,
    )

    // 5. 앱 정보 (App info)
    SettingsNavigationRow(
        title = stringResource(R.string.app_info),
        summary = stringResource(R.string.app_version),
        onClick = onOpenAppInfo,
    )
}

@Composable
private fun GeneralSettings(
    state: VoiceState,
    audibleFeedback: Boolean,
    onAudibleFeedbackChanged: (Boolean) -> Unit,
    onStartWaiting: () -> Unit,
    onStop: () -> Unit,
    onNotificationSettings: () -> Unit,
    profile: BodyMountProfile?,
    onOpenProfileSetup: () -> Unit,
    onBack: () -> Unit,
) {
    SettingsPageHeader(stringResource(R.string.settings_general_title), onBack)

    // 음성 및 알림
    SettingsSectionTitle(stringResource(R.string.settings_general_voice_section))
    SettingsSwitchRow(
        title = stringResource(R.string.wake_setting),
        summary = stringResource(
            if (state.isActive) R.string.settings_voice_enabled else R.string.settings_voice_disabled
        ),
        checked = state.isActive,
        enabled = state.phase != VoicePhase.Stopping,
        onCheckedChange = { if (it) onStartWaiting() else onStop() },
    )
    Text(
        stringResource(R.string.settings_voice_service_summary),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    if (state.errorCode != VoiceError.NONE) {
        Text(
            text = stringResource(voiceError(state.errorCode)),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    SettingsSwitchRow(
        title = stringResource(R.string.audible_feedback),
        summary = stringResource(
            if (audibleFeedback) R.string.settings_feedback_enabled
            else R.string.settings_feedback_disabled
        ),
        checked = audibleFeedback,
        onCheckedChange = onAudibleFeedbackChanged,
    )
    if (!state.command.ttsAvailable) {
        Text(
            text = stringResource(R.string.settings_tts_unavailable),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
    SettingsNavigationRow(
        title = stringResource(R.string.command_notification_settings),
        summary = stringResource(R.string.settings_notification_summary),
        onClick = onNotificationSettings,
    )

    // 신체 장착 기준 (키·착용 위치)
    SettingsSectionTitle(stringResource(R.string.settings_general_mount_section))
    val profileSummary = if (profile != null && profile.isValid) {
        val heightCm = profile.estimatedCameraHeightMeters?.let { (it * 100f).roundToInt() }
        val heightStr = if (heightCm != null) " (추정 렌즈 높이 약 ${heightCm}cm)" else ""
        val statureStr = if (profile.statureCm % 1f == 0f) profile.statureCm.toInt().toString() else profile.statureCm.toString()
        "${statureStr}cm · ${profile.position.koreanLabel}$heightStr"
    } else {
        stringResource(R.string.body_mount_profile_unconfigured)
    }
    SettingsNavigationRow(
        title = stringResource(R.string.body_mount_profile_title),
        summary = profileSummary,
        onClick = onOpenProfileSetup,
    )
}

@Composable
private fun AccountSettings(
    hasApiKey: Boolean,
    maskedKey: String?,
    onRegisterKey: () -> Unit,
    onChangeKey: () -> Unit,
    onClearKey: () -> Unit,
    onBack: () -> Unit,
) {
    SettingsPageHeader(stringResource(R.string.settings_account_title), onBack)
    SettingsSectionTitle(stringResource(R.string.settings_account_group))

    SettingsNavigationRow(
        title = stringResource(R.string.settings_cloud_model_title),
        summary = stringResource(R.string.settings_cloud_model_summary),
        onClick = {},
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (hasApiKey) {
                stringResource(R.string.settings_cloud_key_registered, maskedKey.orEmpty())
            } else {
                stringResource(R.string.settings_cloud_key_empty)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (hasApiKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (hasApiKey) {
                TextButton(onClick = onChangeKey, modifier = Modifier.testTag("gemini_change_key_btn")) {
                    Text(stringResource(R.string.settings_cloud_key_change_btn))
                }
                TextButton(onClick = onClearKey, modifier = Modifier.testTag("gemini_clear_key_btn")) {
                    Text(stringResource(R.string.settings_cloud_key_clear_btn), color = MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(onClick = onRegisterKey, modifier = Modifier.testTag("gemini_register_key_btn")) {
                    Text(stringResource(R.string.settings_cloud_key_register_btn))
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_cloud_privacy_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // 온디바이스 AI 컴포넌트 (음성 인식 및 비전)
    SettingsSectionTitle(stringResource(R.string.settings_account_local_models_group))
    SettingsNavigationRow(
        title = stringResource(R.string.settings_account_local_asr_title),
        summary = stringResource(R.string.settings_account_local_asr_summary),
        onClick = {},
    )
    SettingsNavigationRow(
        title = stringResource(R.string.settings_account_local_vision_title),
        summary = stringResource(R.string.settings_account_local_vision_summary),
        onClick = {},
    )
}

@Composable
private fun DiagnosticsSettings(
    onOpenVisionDiagnostics: () -> Unit,
    onOpenVoiceDiagnostics: () -> Unit,
    onOpenAppSettings: () -> Unit = {},
    onBack: () -> Unit,
) {
    SettingsPageHeader(stringResource(R.string.settings_diagnostics_title), onBack)
    SettingsSectionTitle(stringResource(R.string.settings_diagnostics_group))
    SettingsNavigationRow(
        title = stringResource(R.string.debug_open_vision),
        summary = stringResource(R.string.settings_vision_diagnostics_summary),
        onClick = onOpenVisionDiagnostics,
    )
    SettingsNavigationRow(
        title = stringResource(R.string.debug_open_voice),
        summary = stringResource(R.string.settings_voice_diagnostics_summary),
        onClick = onOpenVoiceDiagnostics,
    )
    SettingsNavigationRow(
        title = stringResource(R.string.open_app_settings),
        summary = "카메라 및 시스템 권한 설정 관리",
        onClick = onOpenAppSettings,
    )
}

@Composable
private fun DemoSettings(
    onOpenDistanceMode: () -> Unit,
    onOpenPresentationDemo: () -> Unit,
    onBack: () -> Unit,
) {
    SettingsPageHeader(stringResource(R.string.settings_demo_page_title), onBack)
    SettingsSectionTitle(stringResource(R.string.settings_demo_page_group))

    // 1. 통합 시연 데모 (Presentation Demo)
    SettingsNavigationRow(
        title = stringResource(R.string.debug_open_demo),
        summary = stringResource(R.string.settings_demo_summary),
        onClick = onOpenPresentationDemo,
    )

    // 2. AR 거리 측정 (ARCore DistanceActivity)
    SettingsNavigationRow(
        title = stringResource(R.string.settings_demo_ar_title),
        summary = stringResource(R.string.settings_demo_ar_summary),
        onClick = onOpenDistanceMode,
    )

    Spacer(Modifier.height(12.dp))

    // Flat ground assumption & safety notice
    Text(
        text = stringResource(R.string.settings_demo_safety_disclaimer),
        style = MaterialTheme.typography.bodySmall,
        color = AriaSlate,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("demoSafetyDisclaimer"),
    )
}

@Composable
private fun AppInfoSettings(onOpenLicenses: () -> Unit, onBack: () -> Unit) {
    SettingsPageHeader(stringResource(R.string.app_info), onBack)
    SettingsSectionTitle(stringResource(R.string.settings_app_info_group))
    Text(
        text = stringResource(R.string.app_version),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
    SettingsNavigationRow(
        title = stringResource(R.string.licenses_title),
        summary = stringResource(R.string.licenses_description),
        onClick = onOpenLicenses,
    )
}

@Composable
private fun SettingsPageHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackIconButton(onClick = onBack)
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 8.dp).semantics { heading() },
        )
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}
