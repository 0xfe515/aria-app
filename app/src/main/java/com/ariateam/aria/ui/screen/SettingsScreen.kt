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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ariateam.aria.R
import com.ariateam.aria.speech.VoiceError
import com.ariateam.aria.speech.VoicePhase
import com.ariateam.aria.speech.VoiceState

private enum class SettingsPage {
    Root,
    Voice,
    Model,
    Diagnostics,
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
) {
    var page by rememberSaveable {
        mutableStateOf(if (initialDebugExpanded) SettingsPage.Diagnostics else SettingsPage.Root)
    }
    var mobileData by rememberSaveable { mutableStateOf(false) }
    var confirmModelDelete by rememberSaveable { mutableStateOf(false) }

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
                state = state,
                onOpenVoice = { page = SettingsPage.Voice },
                onOpenModel = { page = SettingsPage.Model },
                onOpenDiagnostics = { page = SettingsPage.Diagnostics },
                onOpenAppInfo = { page = SettingsPage.AppInfo },
            )

            SettingsPage.Voice -> VoiceSettings(
                state = state,
                audibleFeedback = audibleFeedback,
                onAudibleFeedbackChanged = onAudibleFeedbackChanged,
                onStartWaiting = onStartWaiting,
                onStop = onStop,
                onNotificationSettings = onNotificationSettings,
                onBack = { page = SettingsPage.Root },
            )

            SettingsPage.Model -> ModelSettings(
                state = state,
                mobileData = mobileData,
                onMobileDataChanged = { mobileData = it },
                onDownloadModel = { onDownloadModel(mobileData) },
                onCancelDownload = onCancelDownload,
                onDeleteModel = { confirmModelDelete = true },
                onBack = { page = SettingsPage.Root },
            )

            SettingsPage.Diagnostics -> DiagnosticsSettings(
                onOpenVisionDiagnostics = onOpenVisionDiagnostics,
                onOpenVoiceDiagnostics = onOpenVoiceDiagnostics,
                onOpenPresentationDemo = onOpenPresentationDemo,
                onBack = { page = SettingsPage.Root },
            )

            SettingsPage.AppInfo -> AppInfoSettings(
                onOpenLicenses = onOpenLicenses,
                onBack = { page = SettingsPage.Root },
            )
        }
    }

    if (confirmModelDelete) {
        AlertDialog(
            onDismissRequest = { confirmModelDelete = false },
            title = { Text(stringResource(R.string.settings_model_delete_title)) },
            text = { Text(stringResource(R.string.settings_model_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmModelDelete = false
                        onDeleteModel()
                    },
                ) {
                    Text(stringResource(R.string.settings_model_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmModelDelete = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

@Composable
private fun SettingsRoot(
    state: VoiceState,
    onOpenVoice: () -> Unit,
    onOpenModel: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenAppInfo: () -> Unit,
) {
    Text(
        text = stringResource(R.string.settings_tab),
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(8.dp))
    SettingsSectionTitle(stringResource(R.string.settings_main_group))
    SettingsNavigationRow(
        title = stringResource(R.string.settings_voice_title),
        summary = stringResource(
            if (state.isActive) R.string.settings_voice_enabled else R.string.settings_voice_disabled
        ),
        onClick = onOpenVoice,
    )
    SettingsNavigationRow(
        title = stringResource(R.string.settings_model_title),
        summary = state.command.model,
        onClick = onOpenModel,
    )
    SettingsNavigationRow(
        title = stringResource(R.string.settings_diagnostics_title),
        summary = stringResource(R.string.settings_diagnostics_summary),
        onClick = onOpenDiagnostics,
    )
    SettingsNavigationRow(
        title = stringResource(R.string.app_info),
        summary = stringResource(R.string.app_version),
        onClick = onOpenAppInfo,
    )
}

@Composable
private fun VoiceSettings(
    state: VoiceState,
    audibleFeedback: Boolean,
    onAudibleFeedbackChanged: (Boolean) -> Unit,
    onStartWaiting: () -> Unit,
    onStop: () -> Unit,
    onNotificationSettings: () -> Unit,
    onBack: () -> Unit,
) {
    SettingsPageHeader(stringResource(R.string.settings_voice_title), onBack)
    SettingsSectionTitle(stringResource(R.string.settings_voice_group))
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
}

@Composable
private fun ModelSettings(
    state: VoiceState,
    mobileData: Boolean,
    onMobileDataChanged: (Boolean) -> Unit,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteModel: () -> Unit,
    onBack: () -> Unit,
) {
    val modelStatus = state.command.model
    val installed = modelStatus.startsWith("설치됨")
    val activeDownload = modelStatus == "다운로드 대기 중" ||
        (modelStatus.startsWith("다운로드 ") && modelStatus.contains("%")) ||
        modelStatus == "다운로드 파일 검증 중"
    val cancelAvailable = activeDownload

    SettingsPageHeader(stringResource(R.string.settings_model_title), onBack)
    SettingsSectionTitle(stringResource(R.string.settings_model_group))
    Text(
        text = stringResource(R.string.gemma_heading),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    Text(
        text = modelStatus,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Text(
        text = stringResource(R.string.settings_model_size_summary),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    SettingsSwitchRow(
        title = stringResource(R.string.gemma_mobile_data),
        summary = stringResource(
            if (mobileData) R.string.settings_mobile_data_enabled
            else R.string.settings_mobile_data_disabled
        ),
        checked = mobileData,
        onCheckedChange = onMobileDataChanged,
    )
    SettingsActionRow(
        title = stringResource(R.string.gemma_download),
        summary = stringResource(
            if (activeDownload) R.string.settings_model_download_active
            else R.string.settings_model_download_summary
        ),
        enabled = !installed && !activeDownload,
        onClick = onDownloadModel,
    )
    SettingsActionRow(
        title = stringResource(R.string.gemma_cancel),
        summary = stringResource(R.string.settings_model_cancel_summary),
        enabled = cancelAvailable,
        onClick = onCancelDownload,
    )
    SettingsActionRow(
        title = stringResource(R.string.gemma_delete),
        summary = stringResource(R.string.settings_model_delete_summary),
        enabled = installed,
        destructive = true,
        onClick = onDeleteModel,
    )
}

@Composable
private fun DiagnosticsSettings(
    onOpenVisionDiagnostics: () -> Unit,
    onOpenVoiceDiagnostics: () -> Unit,
    onOpenPresentationDemo: () -> Unit,
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
        title = stringResource(R.string.debug_open_demo),
        summary = stringResource(R.string.settings_demo_summary),
        onClick = onOpenPresentationDemo,
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
