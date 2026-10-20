package com.ariateam.aria.ui.screen

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.test.platform.app.InstrumentationRegistry
import com.ariateam.aria.R
import com.ariateam.aria.speech.VoicePhase
import com.ariateam.aria.speech.VoiceState
import com.ariateam.aria.ui.theme.AriaTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VoiceDemoScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun powerGesturesStartWaitingAndStopTheRealSession() {
        val state = mutableStateOf(VoiceState())
        var starts = 0
        var stops = 0
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            AriaTheme {
                VoiceDemoScreen(state.value, {}, {}, {}, onStartWaiting = { starts++ }, onStop = { stops++ })
            }
        }
        val power = compose.onNodeWithContentDescription(context.getString(R.string.power_control))
        power.performScrollTo().performTouchInput { click() }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle {
            assertEquals(1, starts)
            state.value = VoiceState(VoicePhase.Waiting)
        }
        power.performTouchInput { click() }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals(1, starts); assertEquals(0, stops) }
        power.performTouchInput { doubleClick() }
        compose.runOnIdle { assertEquals(1, stops) }
    }

    @Test fun speakingAllowsManualInputAndStoppingDisablesIt() {
        val state = mutableStateOf(VoiceState(VoicePhase.Speaking))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            AriaTheme { VoiceDemoScreen(state.value, {}, {}, {}) }
        }
        compose.onNodeWithText(context.getString(R.string.start_dictation)).assertIsEnabled()
        compose.runOnIdle {
            state.value = VoiceState(VoicePhase.Stopping, errorCode = com.ariateam.aria.speech.VoiceError.TIMEOUT)
        }
        compose.onNodeWithText(context.getString(R.string.error_timeout)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.start_dictation)).assertIsNotEnabled()
        compose.runOnIdle {
            state.value = VoiceState(VoicePhase.Error, errorCode = com.ariateam.aria.speech.VoiceError.TIMEOUT)
        }
        compose.onNodeWithText(context.getString(R.string.error_timeout)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.start_dictation)).assertIsEnabled()
    }

    @Test fun retryStateShowsRecoveryAndAllowsManualInput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            AriaTheme {
                VoiceDemoScreen(VoiceState(VoicePhase.Reconnecting, retryCount = 2,
                    retryAtMillis = android.os.SystemClock.elapsedRealtime() + 30_000), {}, {}, {})
            }
        }
        compose.onNodeWithText(context.getString(R.string.status_reconnecting)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("재시도", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.start_dictation)).assertIsEnabled()
    }

    @Test fun serviceStateChangesUpdateButtonTranscriptAndStatusWithoutPressingStart() {
        val state = mutableStateOf(VoiceState(VoicePhase.Waiting))
        var finishRequested = false
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val start = context.getString(R.string.start_dictation)
        val finish = context.getString(R.string.finish_dictation)
        compose.setContent {
            AriaTheme {
                VoiceDemoScreen(state.value, onStart = {},
                    onFinish = { finishRequested = true }, onOpenSettings = {})
            }
        }
        compose.onNodeWithText(start).assertIsEnabled()
        compose.runOnIdle { state.value = VoiceState(VoicePhase.Dictating, "인식 중인 문장") }
        compose.onNodeWithText(finish).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(finishRequested) }
        compose.onNodeWithText("인식 중인 문장").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state.value = VoiceState(VoicePhase.Waiting, "확정된 문장") }
        compose.onNodeWithText(start).assertIsEnabled()
        compose.onNodeWithText("확정된 문장").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.status_waiting)).performScrollTo().assertIsDisplayed()
    }
}
