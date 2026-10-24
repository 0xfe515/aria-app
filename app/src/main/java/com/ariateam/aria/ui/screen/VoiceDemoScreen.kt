package com.ariateam.aria.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ariateam.aria.R
import com.ariateam.aria.speech.VoiceError
import com.ariateam.aria.speech.VoicePhase
import com.ariateam.aria.speech.VoiceState
import com.ariateam.aria.ui.theme.AriaGreen
import com.ariateam.aria.ui.theme.AriaPanel
import com.ariateam.aria.ui.theme.AriaRed
import com.ariateam.aria.ui.theme.AriaSky
import com.ariateam.aria.ui.theme.AriaSlate
import com.ariateam.aria.ui.theme.AriaTheme

@Composable
fun VoiceDemoScreen(
    state: VoiceState,
    @Suppress("UNUSED_PARAMETER") onStart: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onFinish: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
    onStartWaiting: () -> Unit = onStart,
    onStop: () -> Unit = {},
) {
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

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        val availableHeight = maxHeight
        val isLandscape = maxWidth > availableHeight

        if (isLandscape) {
            val buttonSize = minOf(130.dp, availableHeight * 0.45f).coerceAtLeast(64.dp)
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HomeHeader()
                    PowerControl(state, accent, buttonSize, onStartWaiting, onStop)
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.widthIn(max = 340.dp),
                ) {
                    HomeStatusInfo(state, retrySeconds)
                    TapHintPill()
                    HomeActionButtons(state, onOpenSettings)
                }
            }
        } else {
            val buttonSize = minOf(200.dp, availableHeight * 0.28f, maxWidth * 0.55f).coerceAtLeast(80.dp)
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                HomeHeader()
                PowerControl(state, accent, buttonSize, onStartWaiting, onStop)
                HomeStatusInfo(state, retrySeconds)
                TapHintPill()
                HomeActionButtons(state, onOpenSettings)
            }
        }
    }
}

@Composable
private fun HomeHeader() {
    Text(
        text = "ARIA",
        color = AriaSlate,
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 4.sp,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun HomeStatusInfo(state: VoiceState, retrySeconds: Long) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(voiceStatus(state.phase)),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        if (state.phase == VoicePhase.Reconnecting && state.retryCount > 0 && retrySeconds > 0) {
            Text(
                text = stringResource(R.string.recognition_retry, state.retryCount, retrySeconds),
                style = MaterialTheme.typography.bodySmall,
                color = AriaSky,
            )
        }
    }
}

@Composable
private fun TapHintPill() {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = AriaPanel,
        border = BorderStroke(1.dp, AriaSlate.copy(alpha = 0.25f)),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.power_single_tap),
                color = AriaSky,
                style = MaterialTheme.typography.labelMedium,
            )
            Text("ON  |", color = AriaSlate, style = MaterialTheme.typography.labelMedium)
            Text(
                stringResource(R.string.power_double_tap),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelMedium,
            )
            Text("OFF", color = AriaSlate, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun HomeActionButtons(
    state: VoiceState,
    onOpenSettings: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(),
    ) {
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

@Composable
private fun PowerControl(
    state: VoiceState,
    accent: Color,
    buttonSize: Dp,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
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
    Box(
        Modifier
            .size(buttonSize)
            .shadow(16.dp, CircleShape)
            .clip(CircleShape)
            .background(AriaPanel)
            .border(4.dp, accent, CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { tap() },
                    onDoubleTap = {
                        if (currentState.value.isActive && currentState.value.phase != VoicePhase.Stopping) stop.value()
                    },
                )
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
            },
        contentAlignment = Alignment.Center,
    ) {
        val iconSize = (buttonSize * 0.28f).coerceIn(24.dp, 60.dp)
        val titleSize = (buttonSize.value * 0.12f).coerceIn(14f, 26f).sp
        val subtitleSize = (buttonSize.value * 0.052f).coerceIn(8f, 12f).sp

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(8.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_power),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(iconSize),
            )
            Text(
                stringResource(
                    when (state.phase) {
                        VoicePhase.Stopped, VoicePhase.Error -> R.string.power_off
                        VoicePhase.Waiting, VoicePhase.Dictating -> R.string.power_running
                        VoicePhase.Initializing -> R.string.power_starting
                        VoicePhase.Stopping -> R.string.power_stopping
                        VoicePhase.Reconnecting -> R.string.power_reconnecting
                        VoicePhase.Speaking -> R.string.power_speaking
                    }
                ),
                color = accent,
                fontSize = titleSize,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            Text(
                stringResource(
                    when (state.phase) {
                        VoicePhase.Initializing, VoicePhase.Stopping, VoicePhase.Reconnecting -> R.string.power_please_wait
                        VoicePhase.Waiting, VoicePhase.Dictating -> R.string.power_voice_on
                        VoicePhase.Speaking -> R.string.power_speaking
                        else -> R.string.power_touch_start
                    }
                ),
                color = AriaSlate,
                fontSize = subtitleSize,
            )
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
    AriaTheme { VoiceDemoScreen(VoiceState(VoicePhase.Waiting, ""), {}, {}, {}) }
}
