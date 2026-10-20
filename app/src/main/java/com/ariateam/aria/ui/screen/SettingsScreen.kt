package com.ariateam.aria.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ariateam.aria.R
import com.ariateam.aria.speech.VoiceError
import com.ariateam.aria.speech.VoicePhase
import com.ariateam.aria.speech.VoiceState

@Composable
fun SettingsScreen(state: VoiceState, onStartWaiting: () -> Unit, onStop: () -> Unit,
    audibleFeedback: Boolean = true,
    onAudibleFeedbackChanged: (Boolean) -> Unit = {}, modifier: Modifier = Modifier,
    onDownloadModel: (Boolean) -> Unit = {}, onCancelDownload: () -> Unit = {},
    onDeleteModel: () -> Unit = {}, onNotificationSettings: () -> Unit = {}) {
    var mobileData by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.settings_tab), style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() })
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.gemma_heading), style = MaterialTheme.typography.titleMedium)
                Text(state.command.model)
                Text(state.command.runtime)
                Text(stringResource(R.string.gemma_description))
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(mobileData, role = Role.Switch, onValueChange = { mobileData = it }),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.gemma_mobile_data), Modifier.weight(1f))
                    Switch(checked = mobileData, onCheckedChange = null)
                }
                OutlinedButton(onClick = { onDownloadModel(mobileData) }) { Text(stringResource(R.string.gemma_download)) }
                OutlinedButton(onClick = onCancelDownload) { Text(stringResource(R.string.gemma_cancel)) }
                OutlinedButton(onClick = onDeleteModel) { Text(stringResource(R.string.gemma_delete)) }
                Text(stringResource(R.string.command_notification_description))
                OutlinedButton(onClick = onNotificationSettings) { Text(stringResource(R.string.command_notification_settings)) }
                if (!state.command.ttsAvailable) {
                    Text(stringResource(R.string.command_tts_missing))
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.engine_heading), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.engine_description))
                Text(stringResource(R.string.keyword_description))
                Text(stringResource(R.string.dictation_description))
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .toggleable(state.isActive, enabled = state.phase != VoicePhase.Stopping, role = Role.Switch,
                        onValueChange = { if (it) onStartWaiting() else onStop() }),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.wake_setting), Modifier.weight(1f))
                    Switch(checked = state.isActive, onCheckedChange = null, enabled = state.phase != VoicePhase.Stopping)
                }
                Text(stringResource(R.string.background_explanation))
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .toggleable(audibleFeedback, role = Role.Switch, onValueChange = onAudibleFeedbackChanged),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.audible_feedback), Modifier.weight(1f))
                    Switch(checked = audibleFeedback, onCheckedChange = null)
                }
                Text(stringResource(if (audibleFeedback) R.string.feedback_description else R.string.feedback_vibration_only))
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.app_info), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.app_version))
                if (state.errorCode != VoiceError.NONE) {
                    Text(stringResource(voiceError(state.errorCode)), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
