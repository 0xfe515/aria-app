package com.ariateam.aria.ui.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirstLaunchAccessibilityGateTest {
    @get:Rule val compose = createComposeRule()

    private data class UiState(
        val showGate: Boolean = true,
        val pendingReturn: Boolean = false,
        val settingsUnavailable: Boolean = false,
        val handled: Boolean = false,
    )

    @Test fun failedSettingsLaunchKeepsGateAndSkipProceeds() {
        var state by mutableStateOf(UiState())
        var settingsAttempted = false
        compose.setContent {
            if (state.showGate) {
                FirstLaunchAccessibilityGate(
                    settingsUnavailable = state.settingsUnavailable,
                    onOpenSettings = {
                        settingsAttempted = true
                        // The fake launcher fails, so no handled or pending marker is written.
                        state = state.copy(settingsUnavailable = true)
                    },
                    onSkip = { state = state.copy(showGate = false, pendingReturn = false, handled = true) },
                )
            } else {
                Text("App content", modifier = Modifier.testTag("appContent"))
            }
        }

        compose.onNodeWithTag("appContent").assertDoesNotExist()
        compose.onNodeWithTag("firstLaunchAccessibilityOpenSettings").performClick()
        compose.onNodeWithTag("firstLaunchAccessibilityError").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(settingsAttempted)
            assertTrue(state.showGate)
            assertFalse(state.handled)
            assertFalse(state.pendingReturn)
        }
        compose.onNodeWithTag("firstLaunchAccessibilitySkip").performClick()
        compose.onNodeWithTag("appContent").assertIsDisplayed()
        compose.runOnIdle { assertTrue(state.handled) }
    }

    @Test fun successfulSettingsHandoffKeepsAppHiddenUntilReturnThenReleasesGate() {
        var state by mutableStateOf(UiState())
        var settingsLaunchCount = 0
        compose.setContent {
            if (state.showGate) {
                FirstLaunchAccessibilityGate(
                    settingsUnavailable = state.settingsUnavailable,
                    onOpenSettings = {
                        settingsLaunchCount++
                        // Model the successful public settings launch and its persisted handoff marker.
                        state = state.copy(handled = true, pendingReturn = true, settingsUnavailable = false)
                    },
                    onSkip = { state = state.copy(showGate = false, pendingReturn = false, handled = true) },
                )
            } else if (!state.pendingReturn) {
                Text("App content", modifier = Modifier.testTag("appContent"))
            }
        }

        compose.onNodeWithTag("appContent").assertDoesNotExist()
        compose.onNodeWithTag("firstLaunchAccessibilityOpenSettings").performClick()
        compose.onNodeWithTag("firstLaunchAccessibilityOpenSettings").assertIsDisplayed()
        compose.onNodeWithTag("appContent").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, settingsLaunchCount)
            assertTrue(state.handled)
            assertTrue(state.pendingReturn)
        }

        // Invoke the same policy used by MainActivity's ON_RESUME observer; the fake return
        // reports disabled, which still releases the gate and never claims permission was granted.
        compose.runOnIdle {
            val returned = FirstLaunchAccessibilityPolicy.afterSettingsReturn(isEnabled = false)
            state = state.copy(
                showGate = returned.showGate,
                pendingReturn = false,
            )
        }
        compose.onNodeWithTag("appContent").assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(state.pendingReturn)
            assertFalse(FirstLaunchAccessibilityPolicy.afterSettingsReturn(false).accessibilityEnabled)
        }
    }
}
