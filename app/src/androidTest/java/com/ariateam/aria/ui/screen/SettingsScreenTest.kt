package com.ariateam.aria.ui.screen

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.ariateam.aria.R
import com.ariateam.aria.speech.VoicePhase
import com.ariateam.aria.speech.VoiceState
import com.ariateam.aria.ui.theme.AriaTheme
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun settingsDescribeTheDefaultRecognizerWithoutRetiredDiagnostics() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            AriaTheme { SettingsScreen(VoiceState(), {}, {}) }
        }
        compose.onNodeWithText(context.getString(R.string.engine_description))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("음성 엔진 확인").assertDoesNotExist()
    }

    @Test fun modelDownloadDefaultsToWifiAndCanBeCancelled() {
        var mobileAllowed: Boolean? = null
        var cancelled = false
        compose.setContent {
            AriaTheme {
                SettingsScreen(VoiceState(), {}, {}, onDownloadModel = { mobileAllowed = it },
                    onCancelDownload = { cancelled = true })
            }
        }
        compose.onNodeWithText("모델 다운로드 / 재시도").performScrollTo().performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(false, mobileAllowed) }
        compose.onNodeWithText("모바일 데이터 사용 허용").performScrollTo().performClick()
        compose.onNodeWithText("모델 다운로드 / 재시도").performScrollTo().performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(true, mobileAllowed) }
        compose.onNodeWithText("다운로드 취소").performScrollTo().performClick()
        compose.runOnIdle { org.junit.Assert.assertTrue(cancelled) }
    }

    @Test fun feedbackSwitchUpdatesTheSelectedMode() {
        val audible = mutableStateOf(true)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            AriaTheme {
                SettingsScreen(VoiceState(), {}, {}, audibleFeedback = audible.value,
                    onAudibleFeedbackChanged = { audible.value = it })
            }
        }
        val switch = compose.onNodeWithText(context.getString(R.string.audible_feedback))
        switch.performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.onNodeWithText(context.getString(R.string.feedback_vibration_only)).performScrollTo().assertIsDisplayed()
    }
}
