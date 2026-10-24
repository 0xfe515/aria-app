package com.ariateam.aria.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ariateam.aria.R
import com.ariateam.aria.speech.VoiceError
import com.ariateam.aria.speech.VoicePhase
import com.ariateam.aria.speech.VoiceState
import com.ariateam.aria.ui.theme.AriaRed
import com.ariateam.aria.ui.theme.AriaSky
import kotlinx.coroutines.delay

@Composable
fun VoiceDiagnosticsScreen(
    state: VoiceState,
    audibleFeedback: Boolean,
    onBack: () -> Unit,
    onStartWaiting: () -> Unit,
    onStop: () -> Unit,
    onStartDictation: () -> Unit,
    onFinishDictation: () -> Unit,
    onAudibleFeedbackChanged: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    val dictating = state.phase == VoicePhase.Dictating
    val stopping = state.phase == VoicePhase.Stopping
    var retrySeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.phase, state.retryAtMillis) {
        while (state.phase == VoicePhase.Reconnecting) {
            retrySeconds = ((state.retryAtMillis - android.os.SystemClock.elapsedRealtime() + 999) / 1000)
                .coerceAtLeast(0)
            if (retrySeconds == 0L) break
            delay(250)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackIconButton(
                onClick = onBack,
                testTag = "voiceDiagnosticsBackButton",
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.debug_open_voice),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
        }

        // 1. Wake-word & Session Status Card
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "호출어 및 음성 세션 상태",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text("세션 단계: ${state.phase.name} (${stringResource(voiceStatus(state.phase))})")
                Text("활성 여부: ${if (state.isActive) "활성 (Active)" else "비활성 (Stopped/Error)"}")
                Text(stringResource(R.string.keyword_description), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.engine_description), style = MaterialTheme.typography.bodySmall)

                if (state.phase == VoicePhase.Reconnecting && state.retryCount > 0 && retrySeconds > 0) {
                    Text(
                        text = stringResource(R.string.recognition_retry, state.retryCount, retrySeconds),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.errorCode != VoiceError.NONE) {
                    Text(
                        text = stringResource(voiceError(state.errorCode)),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onStartWaiting,
                        enabled = !state.isActive && !stopping,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Text("호출어 대기 시작")
                    }
                    OutlinedButton(
                        onClick = onStop,
                        enabled = state.isActive && !stopping,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.stop_listening))
                    }
                }
                if (state.errorCode == VoiceError.PERMISSION) {
                    OutlinedButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.open_app_settings))
                    }
                }
            }
        }

        // 2. Manual Dictation Controls
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "수동 받아쓰기",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Button(
                    onClick = if (dictating) onFinishDictation else onStartDictation,
                    enabled = !stopping,
                    colors = ButtonDefaults.buttonColors(containerColor = if (dictating) AriaRed else AriaSky),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Text(stringResource(if (dictating) R.string.finish_dictation else R.string.start_dictation))
                }
            }
        }

        // 3. Command Coordinator, Gemma & TTS Status Card
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "명령 해석 · Gemma 모델 · TTS 상태",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (state.command.processing) {
                        stringResource(R.string.command_processing)
                    } else {
                        stringResource(R.string.command_heading)
                    },
                    fontWeight = FontWeight.Medium,
                )
                Text("최근 명령 응답: ${state.command.message}")
                Text("Gemma 모델 상태: ${state.command.model}")
                Text("Gemma 런타임 상태: ${state.command.runtime}")
                Text(
                    text = if (state.command.ttsAvailable) {
                        "한국어 오프라인 TTS: 준비됨"
                    } else {
                        "한국어 오프라인 TTS: 미준비 (진동 및 화면/알림으로 대체)"
                    },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = audibleFeedback,
                            role = Role.Switch,
                            onValueChange = onAudibleFeedbackChanged,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.audible_feedback), Modifier.weight(1f))
                    Switch(checked = audibleFeedback, onCheckedChange = null)
                }
            }
        }
    }
}
