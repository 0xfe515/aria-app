package com.ariateam.aria.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlackScreenOverlayTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun overlayConsumesTouchAndLeavesUnderlyingSessionComposed() {
        var underlyingClicks = 0
        var sessionStarts = 0
        var sessionStops = 0

        composeRule.setContent {
            DisposableEffect(Unit) {
                sessionStarts++
                onDispose { sessionStops++ }
            }
            var hidden by remember { mutableStateOf(true) }
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag("camera-session")
                        .clickable { underlyingClicks++ },
                )
                if (hidden) BlackScreenOverlay(onReveal = { hidden = false })
            }
        }

        composeRule.onNodeWithContentDescription("화면 숨김. 다시 표시하려면 탭하세요.")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("camera-session").performTouchInput { click() }
        composeRule.waitForIdle()

        assertEquals(0, underlyingClicks)
        assertEquals(1, sessionStarts)
        assertEquals(0, sessionStops)
        composeRule.onNodeWithContentDescription("화면 숨김. 다시 표시하려면 탭하세요.").assertDoesNotExist()
    }
}
