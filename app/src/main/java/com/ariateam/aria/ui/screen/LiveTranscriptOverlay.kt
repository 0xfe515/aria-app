package com.ariateam.aria.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ariateam.aria.speech.VoicePhase
import com.ariateam.aria.speech.VoiceState
import com.ariateam.aria.ui.navigation.FloatingTabBarBottomMargin

@Composable
internal fun LiveTranscriptOverlay(
    state: VoiceState,
    floatingTabBarVisible: Boolean,
    floatingTabBarHeight: Dp,
    onHeightChanged: (Dp) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val transcript = state.transcript
    if (state.phase != VoicePhase.Dictating || transcript.isBlank()) return

    val density = LocalDensity.current
    val textStyle = MaterialTheme.typography.bodyLarge
    val maxTextHeight = with(density) { textStyle.lineHeight.toDp() * 3 }
    val scrollState = rememberScrollState()
    LaunchedEffect(transcript) {
        withFrameNanos { }
        scrollState.scrollTo(scrollState.maxValue)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            ),
    ) {
        val availableHeight = maxHeight
        val bottomClearance = if (floatingTabBarVisible) {
            FloatingTabBarBottomMargin + floatingTabBarHeight + 8.dp
        } else {
            24.dp
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(bottom = bottomClearance),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = minOf(maxTextHeight, availableHeight * 0.35f))
                    .verticalScroll(scrollState)
                    .onSizeChanged { size -> onHeightChanged(with(density) { size.height.toDp() }) }
                    .testTag("live-transcript-overlay"),
            ) {
                Text(
                    text = transcript,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = textStyle,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
