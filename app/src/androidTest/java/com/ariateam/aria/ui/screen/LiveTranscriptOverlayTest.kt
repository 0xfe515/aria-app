package com.ariateam.aria.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.ariateam.aria.speech.VoicePhase
import com.ariateam.aria.speech.VoiceState
import com.ariateam.aria.ui.navigation.FloatingTabBarBottomMargin
import com.ariateam.aria.ui.theme.AriaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LiveTranscriptOverlayTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun showsOnlyNonblankDictationAndHidesImmediatelyWhenPhaseEnds() {
        val state = mutableStateOf(VoiceState(VoicePhase.Dictating))
        val transcript = "지금 인식된 문장입니다"
        compose.setContent {
            AriaTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    LiveTranscriptOverlay(state.value, floatingTabBarVisible = false, floatingTabBarHeight = 60.dp)
                }
            }
        }

        compose.onNodeWithTag("live-transcript-overlay").assertDoesNotExist()
        compose.runOnIdle { state.value = VoiceState(VoicePhase.Dictating, transcript) }
        compose.onNodeWithText(transcript).assertIsDisplayed()
        compose.onNodeWithText("실시간 받아쓰기").assertDoesNotExist()
        compose.onNodeWithText("듣는 중").assertDoesNotExist()
        compose.onNodeWithText("말씀하시면 인식된 내용이 여기에 표시됩니다.").assertDoesNotExist()

        compose.runOnIdle { state.value = VoiceState(VoicePhase.Dictating, "  \n") }
        compose.onNodeWithTag("live-transcript-overlay").assertDoesNotExist()
        compose.runOnIdle { state.value = VoiceState(VoicePhase.Waiting, transcript) }
        compose.onNodeWithText(transcript).assertDoesNotExist()
        listOf(
            VoicePhase.Stopped,
            VoicePhase.Initializing,
            VoicePhase.Stopping,
            VoicePhase.Reconnecting,
            VoicePhase.Speaking,
            VoicePhase.Error,
        ).forEach { phase ->
            compose.runOnIdle { state.value = VoiceState(phase, transcript) }
            compose.onNodeWithText(transcript).assertDoesNotExist()
        }
    }

    @Test
    fun updatesCurrentTextAndKeepsOverlayAcrossContentChangesAboveMeasuredBar() {
        val state = mutableStateOf(VoiceState(VoicePhase.Dictating, "처음 인식된 문장"))
        var content by mutableStateOf("홈 화면")
        var barHeight by mutableStateOf(72.dp)
        compose.setContent {
            AriaTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    Box(Modifier.fillMaxSize().testTag("screen-content")) {
                        Text(content)
                    }
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                            .padding(bottom = FloatingTabBarBottomMargin)
                            .height(barHeight)
                            .fillMaxWidth()
                            .testTag("floating-bar"),
                    )
                    LiveTranscriptOverlay(
                        state = state.value,
                        floatingTabBarVisible = true,
                        floatingTabBarHeight = barHeight,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }

        val initialTextBounds = compose.onNodeWithText("처음 인식된 문장")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        compose.onNodeWithText("홈 화면").assertIsDisplayed()
        val barBounds = compose.onNodeWithTag("floating-bar").getUnclippedBoundsInRoot()
        assertEquals(8.dp, barBounds.top - initialTextBounds.bottom)

        compose.runOnIdle {
            content = "설정 화면"
            state.value = VoiceState(VoicePhase.Dictating, "업데이트된 받아쓰기")
            barHeight = 88.dp
        }
        compose.onNodeWithText("설정 화면").assertIsDisplayed()
        compose.onNodeWithText("처음 인식된 문장").assertDoesNotExist()
        val updatedTextBounds = compose.onNodeWithText("업데이트된 받아쓰기")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val updatedBarBounds = compose.onNodeWithTag("floating-bar").getUnclippedBoundsInRoot()
        assertEquals(8.dp, updatedBarBounds.top - updatedTextBounds.bottom)
    }

    @Test
    fun hiddenNavigationUsesSafeBottomAndLargeFontKeepsTextWithinViewport() {
        val transcript = "큰 글꼴에서도 안전 영역 안에 표시됩니다. ".repeat(20)
        val density = Density(density = 2.75f, fontScale = 1.5f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides density) {
                AriaTheme {
                    Box(Modifier.size(width = 360.dp, height = 560.dp)) {
                        LiveTranscriptOverlay(
                            state = VoiceState(VoicePhase.Dictating, transcript),
                            floatingTabBarVisible = false,
                            floatingTabBarHeight = 60.dp,
                        )
                    }
                }
            }
        }

        val transcriptBounds = compose.onNodeWithTag("live-transcript-overlay")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        assertTrue(transcriptBounds.left >= 0.dp && transcriptBounds.right <= 360.dp)
        assertTrue(transcriptBounds.top >= 0.dp && transcriptBounds.bottom <= 560.dp)
        assertTrue(transcriptBounds.bottom <= 536.dp)
        assertTrue(transcriptBounds.bottom - transcriptBounds.top <= 120.dp)
    }

    @Test
    fun longTextScrollsToTheLatestAppendedWords() {
        val first = "처음 인식된 문장입니다. ".repeat(40)
        val newest = "최신 인식 끝부분"
        val state = mutableStateOf(VoiceState(VoicePhase.Dictating, first))
        compose.setContent {
            AriaTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    LiveTranscriptOverlay(state.value, floatingTabBarVisible = false, floatingTabBarHeight = 60.dp)
                }
            }
        }

        compose.onNodeWithTag("live-transcript-overlay").assertIsDisplayed()
        compose.runOnIdle { state.value = VoiceState(VoicePhase.Dictating, first + newest) }
        compose.waitForIdle()

        val scrollRange = compose.onNodeWithTag("live-transcript-overlay")
            .fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(scrollRange.maxValue() > 0f)
        assertEquals(scrollRange.maxValue(), scrollRange.value(), 0.1f)
    }
}
