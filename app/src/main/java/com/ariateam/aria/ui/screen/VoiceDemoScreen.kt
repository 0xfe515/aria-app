package com.ariateam.aria.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ariateam.aria.R
import com.ariateam.aria.speech.VoiceError
import com.ariateam.aria.speech.VoicePhase
import com.ariateam.aria.speech.VoiceState
import com.ariateam.aria.ui.theme.AriaTheme
import com.ariateam.aria.ui.theme.AriaGreen
import com.ariateam.aria.ui.theme.AriaPanel
import com.ariateam.aria.ui.theme.AriaRed
import com.ariateam.aria.ui.theme.AriaSky
import com.ariateam.aria.ui.theme.AriaSlate

@Composable
fun VoiceDemoScreen(state: VoiceState, onStart: () -> Unit, onFinish: () -> Unit,
    onOpenSettings: () -> Unit, modifier: Modifier = Modifier,
    onStartWaiting: () -> Unit = onStart, onStop: () -> Unit = {}) {
    val dictating = state.phase == VoicePhase.Dictating
    var retrySeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.phase, state.retryAtMillis) {
        while (state.phase == VoicePhase.Reconnecting) {
            retrySeconds = ((state.retryAtMillis - android.os.SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0)
            if (retrySeconds == 0L) break
            kotlinx.coroutines.delay(250)
        }
    }
    val active = state.isActive
    val busy = state.phase in listOf(VoicePhase.Initializing, VoicePhase.Stopping, VoicePhase.Reconnecting, VoicePhase.Speaking)
    val accent = when {
        state.errorCode != VoiceError.NONE -> AriaRed
        busy -> AriaSky
        active -> AriaGreen
        else -> AriaRed
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.glass_title), color = AriaSlate, fontSize = 20.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 3.sp,
            modifier = Modifier.semantics { heading() })
        Text(stringResource(if (active) R.string.system_active else R.string.system_disconnected),
            color = if (active) accent else AriaSlate, fontSize = 12.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        PowerControl(state, accent, onStartWaiting, onStop)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (state.command.processing) stringResource(R.string.command_processing) else stringResource(R.string.command_heading), style = MaterialTheme.typography.titleMedium)
                Text(state.command.message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Text(stringResource(R.string.command_examples),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(stringResource(voiceStatus(state.phase)), color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium)
        if (state.phase == VoicePhase.Reconnecting && state.retryCount > 0 && retrySeconds > 0) {
            Text(stringResource(R.string.recognition_retry, state.retryCount, retrySeconds),
                style = MaterialTheme.typography.bodySmall)
        }
        Surface(shape = RoundedCornerShape(24.dp), color = AriaPanel,
            border = BorderStroke(1.dp, AriaSlate.copy(alpha = 0.25f))) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.power_single_tap), color = AriaSky,
                    style = MaterialTheme.typography.labelLarge)
                Text("ON  |", color = AriaSlate, style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.power_double_tap), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge)
                Text("OFF", color = AriaSlate, style = MaterialTheme.typography.labelLarge)
            }
        }
        if (state.transcript.isNotBlank() || dictating) {
            Card(Modifier.fillMaxWidth()) {
                SelectionContainer(Modifier.fillMaxWidth().heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()).padding(20.dp)) {
                    Text(state.transcript.ifBlank { stringResource(R.string.dictation_placeholder) },
                        style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (state.errorCode != VoiceError.NONE) {
            Text(stringResource(voiceError(state.errorCode)), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
        }
        Button(onClick = if (dictating) onFinish else onStart,
            enabled = state.phase !in listOf(VoicePhase.Stopping),
            colors = ButtonDefaults.buttonColors(containerColor = if (dictating) AriaRed else AriaSky),
            modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(if (dictating) R.string.finish_dictation else R.string.start_dictation))
        }
        if (active) {
            OutlinedButton(onClick = onStop, enabled = state.phase != VoicePhase.Stopping,
                modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.stop_listening))
            }
        }
        if (state.errorCode == VoiceError.PERMISSION) {
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.open_app_settings))
            }
        }
    }
}

@Composable
private fun PowerControl(state: VoiceState, accent: androidx.compose.ui.graphics.Color,
    onStart: () -> Unit, onStop: () -> Unit) {
    val currentState = rememberUpdatedState(state)
    val start = rememberUpdatedState(onStart)
    val stop = rememberUpdatedState(onStop)
    val haptics = LocalHapticFeedback.current
    val status = stringResource(voiceStatus(state.phase))
    val controlLabel = stringResource(R.string.power_control)
    val startLabel = stringResource(R.string.power_start_label)
    val stopLabel = stringResource(R.string.stop_listening)
    val tap = {
        val value = currentState.value
        if (!value.isActive) start.value()
        else if (value.phase != VoicePhase.Stopping) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    Box(Modifier.size(230.dp).shadow(16.dp, CircleShape).clip(CircleShape)
        .background(AriaPanel).border(4.dp, accent, CircleShape)
        .pointerInput(Unit) {
            detectTapGestures(onTap = { tap() }, onDoubleTap = {
                if (currentState.value.isActive && currentState.value.phase != VoicePhase.Stopping) stop.value()
            })
        }
        .semantics(mergeDescendants = true) {
            contentDescription = controlLabel
            role = Role.Button
            if (state.phase == VoicePhase.Stopping) disabled()
            stateDescription = status
            onClick(label = startLabel) { tap(); true }
            if (state.isActive && state.phase != VoicePhase.Stopping) {
                customActions = listOf(CustomAccessibilityAction(stopLabel) { onStop(); true })
            }
        }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(24.dp)) {
            Icon(painterResource(R.drawable.ic_power), contentDescription = null,
                tint = accent, modifier = Modifier.size(64.dp))
            Text(stringResource(when (state.phase) {
                VoicePhase.Stopped, VoicePhase.Error -> R.string.power_off
                VoicePhase.Waiting, VoicePhase.Dictating -> R.string.power_running
                VoicePhase.Initializing -> R.string.power_starting
                VoicePhase.Stopping -> R.string.power_stopping
                VoicePhase.Reconnecting -> R.string.power_reconnecting
                VoicePhase.Speaking -> R.string.power_speaking
            }), color = accent, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text(stringResource(when (state.phase) {
                VoicePhase.Initializing, VoicePhase.Stopping, VoicePhase.Reconnecting -> R.string.power_please_wait
                VoicePhase.Waiting, VoicePhase.Dictating -> R.string.power_voice_on
                VoicePhase.Speaking -> R.string.power_speaking
                else -> R.string.power_touch_start
            }),
                color = AriaSlate, fontSize = 11.sp)
        }
    }
}

internal fun voiceStatus(phase: VoicePhase): Int = when (phase) {
    VoicePhase.Stopped -> R.string.status_stopped
    VoicePhase.Reconnecting -> R.string.status_reconnecting
    VoicePhase.Speaking -> R.string.status_speaking
    VoicePhase.Initializing -> R.string.status_initializing
    VoicePhase.Waiting -> R.string.status_waiting
    VoicePhase.Dictating -> R.string.status_dictating
    VoicePhase.Stopping -> R.string.status_stopping
    VoicePhase.Error -> R.string.status_error
}

internal fun voiceError(code: Int): Int = com.ariateam.aria.speech.voiceErrorMessage(code)

@Preview(showBackground = true)
@Composable
private fun VoiceDemoPreview() {
    AriaTheme { VoiceDemoScreen(VoiceState(VoicePhase.Dictating, "오늘 날씨를 알려줘"), {}, {}, {}) }
}
