package com.ariateam.aria.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ariateam.aria.R
import com.ariateam.aria.speech.VoiceError
import com.ariateam.aria.speech.VoicePhase
import com.ariateam.aria.speech.VoiceState
import com.ariateam.aria.ui.navigation.FloatingTabBar
import com.ariateam.aria.ui.theme.AriaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VoiceDemoScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun ariaTitleHasExactCaseAndHeadingSemantics() {
        compose.setContent {
            AriaTheme { VoiceDemoScreen(VoiceState(), {}, {}, {}) }
        }
        compose.onNodeWithText("ARIA")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        // Old branding must not exist
        compose.onNodeWithText("AI GLASS ARIA").assertDoesNotExist()
        // SYSTEM ACTIVE indicator must not exist
        compose.onNodeWithText("SYSTEM ACTIVE").assertDoesNotExist()
        compose.onNodeWithText("SYSTEM DISCONNECTED").assertDoesNotExist()
    }

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
        // Non-scrolling Home: click directly without performScrollTo
        power.performTouchInput { click() }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle {
            assertEquals(1, starts)
            state.value = VoiceState(VoicePhase.Waiting)
        }
        compose.onNodeWithText(context.getString(R.string.stop_listening)).assertDoesNotExist()
        power.performTouchInput { click() }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals(1, starts); assertEquals(0, stops) }
        power.performTouchInput { doubleClick() }
        compose.runOnIdle { assertEquals(1, stops) }
    }

    @Test fun cleanHomeOmitsCommandCardDictationButtonAndScrollSemantics() {
        val state = mutableStateOf(
            VoiceState(
                phase = VoicePhase.Speaking,
                transcript = "숨겨져야 하는 테스트 문장",
            )
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            AriaTheme { VoiceDemoScreen(state.value, {}, {}, {}) }
        }
        // Manual dictation controls and transcript must not be on Home
        compose.onNodeWithText(context.getString(R.string.start_dictation)).assertDoesNotExist()
        compose.onNodeWithText("숨겨져야 하는 테스트 문장").assertDoesNotExist()

        // Command Card and help text must be absent from Home
        compose.onNodeWithText(context.getString(R.string.command_heading)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.command_examples)).assertDoesNotExist()

        // Persistent error banners are removed in favor of transient toast feedback
        compose.runOnIdle {
            state.value = VoiceState(VoicePhase.Stopping, errorCode = VoiceError.TIMEOUT)
        }
        compose.onNodeWithText(context.getString(R.string.error_timeout)).assertDoesNotExist()

        // Verify Home has NO vertical scroll semantics
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .assertDoesNotExist()
    }

    @Test
    fun homeDoesNotRenderTranscriptLocally() {
        val transcript = "인식된 문장은 공통 오버레이에만 표시됩니다"
        compose.setContent {
            AriaTheme { VoiceDemoScreen(VoiceState(VoicePhase.Dictating, transcript)) }
        }
        compose.onNodeWithText(transcript).assertDoesNotExist()
    }

    @Test fun homeKeepsPermissionRecoveryWithoutStopButton() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            AriaTheme {
                VoiceDemoScreen(
                    VoiceState(VoicePhase.Error, errorCode = VoiceError.PERMISSION),
                    onOpenSettings = {},
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.open_app_settings)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.stop_listening)).assertDoesNotExist()
    }

    @Test fun retryStateShowsRecoveryOnCleanHome() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            AriaTheme {
                VoiceDemoScreen(
                    VoiceState(
                        VoicePhase.Reconnecting,
                        retryCount = 2,
                        retryAtMillis = android.os.SystemClock.elapsedRealtime() + 30_000,
                    ),
                    {},
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.status_reconnecting)).assertIsDisplayed()
        compose.onNodeWithText("재시도", substring = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.start_dictation)).assertDoesNotExist()
    }

    @Test
    fun homeResponsiveLayoutInLandscapeFitsAndDisplaysKeyControlsWithoutOverlap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val statusText = context.getString(R.string.status_stopped)
        val powerLabel = context.getString(R.string.power_control)

        compose.setContent {
            AriaTheme {
                Box(modifier = Modifier.size(width = 720.dp, height = 360.dp)) {
                    VoiceDemoScreen(
                        state = VoiceState(),
                        onStart = {},
                        onFinish = {},
                        onOpenSettings = {},
                    )
                }
            }
        }

        // 1. Verify key controls are displayed within the viewport
        val ariaNode = compose.onNodeWithText("ARIA").assertIsDisplayed()
        val powerNode = compose.onNodeWithContentDescription(powerLabel).assertIsDisplayed()
        val statusNode = compose.onNodeWithText(statusText).assertIsDisplayed()
        val hintNode = compose.onNodeWithText("ON  |").assertIsDisplayed()

        // 2. Bounds and non-overlap verification
        val ariaBounds = ariaNode.getUnclippedBoundsInRoot()
        val powerBounds = powerNode.getUnclippedBoundsInRoot()
        val statusBounds = statusNode.getUnclippedBoundsInRoot()
        val hintBounds = hintNode.getUnclippedBoundsInRoot()

        // Vertical order within left column: ARIA header is strictly above Power button
        assertTrue("ARIA header bottom (${ariaBounds.bottom}) must be <= power top (${powerBounds.top})",
            ariaBounds.bottom <= powerBounds.top)

        // Horizontal separation: Left column elements must be to the left of right column elements
        assertTrue("Power button right (${powerBounds.right}) must not overlap horizontally with status left (${statusBounds.left})",
            powerBounds.right <= statusBounds.left)

        // Vertical order within right column: Status is above Tap hint
        assertTrue("Status bottom (${statusBounds.bottom}) must be <= hint top (${hintBounds.top})",
            statusBounds.bottom <= hintBounds.top)

        // All elements must fit within the 360.dp height
        assertTrue("Power button bottom (${powerBounds.bottom}) must fit within 360.dp", powerBounds.bottom <= 360.dp)
        assertTrue("Hint pill bottom (${hintBounds.bottom}) must fit within 360.dp", hintBounds.bottom <= 360.dp)

        // 3. Must have NO vertical scroll semantics
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .assertDoesNotExist()
    }

    @Test
    fun homeResponsiveLayoutWithLargeFontScale15FitsWithinCompactBoundsWithoutClipping() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val statusText = context.getString(R.string.status_waiting)
        val powerLabel = context.getString(R.string.power_control)

        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 2.75f, fontScale = 1.5f)
            ) {
                AriaTheme {
                    Box(modifier = Modifier.size(width = 360.dp, height = 540.dp)) {
                        VoiceDemoScreen(
                            state = VoiceState(VoicePhase.Waiting),
                            onStart = {},
                            onFinish = {},
                            onOpenSettings = {},
                            onStop = {},
                        )
                    }
                }
            }
        }

        // 1. All key controls must be displayed even at 1.5x font
        val ariaNode = compose.onNodeWithText("ARIA").assertIsDisplayed()
        val powerNode = compose.onNodeWithContentDescription(powerLabel).assertIsDisplayed()
        val statusNode = compose.onNodeWithText(statusText).assertIsDisplayed()
        val hintNode = compose.onNodeWithText("ON  |").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.stop_listening)).assertDoesNotExist()

        // 2. Strict vertical hierarchy without overlap
        val ariaBounds = ariaNode.getUnclippedBoundsInRoot()
        val powerBounds = powerNode.getUnclippedBoundsInRoot()
        val statusBounds = statusNode.getUnclippedBoundsInRoot()
        val hintBounds = hintNode.getUnclippedBoundsInRoot()

        assertTrue("ARIA header bottom (${ariaBounds.bottom}) must be <= power top (${powerBounds.top})",
            ariaBounds.bottom <= powerBounds.top)
        assertTrue("Power bottom (${powerBounds.bottom}) must be <= status top (${statusBounds.top})",
            powerBounds.bottom <= statusBounds.top)
        assertTrue("Status bottom (${statusBounds.bottom}) must be <= hint top (${hintBounds.top})",
            statusBounds.bottom <= hintBounds.top)
        assertTrue("Hint bottom (${hintBounds.bottom}) must fit within 540.dp",
            hintBounds.bottom <= 540.dp)

        // 3. Must have NO vertical scroll semantics
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .assertDoesNotExist()
    }

    @Test
    fun floatingTabBarDynamicHeightMeasurementExpandsSafePaddingAtLargeFontScale() {
        var measuredHeight = 0.dp
        var fontScaleState by mutableStateOf(1.0f)

        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 2.75f, fontScale = fontScaleState)
            ) {
                FloatingTabBar(
                    selectedTab = 0,
                    onSelected = {},
                    onHeightChanged = { measuredHeight = it },
                )
            }
        }
        compose.waitForIdle()
        val defaultHeight = measuredHeight
        assertTrue("Default tab bar height must be >= 48.dp touch target", defaultHeight >= 48.dp)

        // Mutate font scale state without calling setContent again
        compose.runOnIdle {
            fontScaleState = 1.5f
        }
        compose.waitForIdle()
        val largeHeight = measuredHeight
        assertTrue("Measured height at fontScale 1.5 ($largeHeight) must be >= fontScale 1.0 ($defaultHeight)",
            largeHeight >= defaultHeight)
    }

    @Test fun voiceDiagnosticsScreenRetainsManualDictationControlsWithoutTranscriptReadout() {
        val state = mutableStateOf(VoiceState(VoicePhase.Waiting))
        var startRequested = false
        var finishRequested = false
        var backClicked = false
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val start = context.getString(R.string.start_dictation)
        val finish = context.getString(R.string.finish_dictation)
        compose.setContent {
            AriaTheme {
                VoiceDiagnosticsScreen(
                    state = state.value,
                    audibleFeedback = true,
                    onBack = { backClicked = true },
                    onStartWaiting = {},
                    onStop = {},
                    onStartDictation = { startRequested = true },
                    onFinishDictation = { finishRequested = true },
                    onAudibleFeedbackChanged = {},
                    onOpenSettings = {},
                )
            }
        }
        compose.onNodeWithText(start).performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(startRequested) }
        compose.runOnIdle { state.value = VoiceState(VoicePhase.Dictating, "인식 중인 문장") }
        compose.onNodeWithText("인식 중인 문장").assertDoesNotExist()
        compose.onNodeWithText(finish).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(finishRequested) }
        compose.runOnIdle { state.value = VoiceState(VoicePhase.Waiting, "확정된 문장") }
        compose.onNodeWithText(start).performScrollTo().assertIsEnabled()
        compose.onNodeWithText("확정된 문장").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.debug_back_to_settings))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertTrue(backClicked) }
    }
}
