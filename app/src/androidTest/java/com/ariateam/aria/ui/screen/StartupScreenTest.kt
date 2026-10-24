package com.ariateam.aria.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.ariateam.aria.startup.StartupDecision
import com.ariateam.aria.startup.StartupIssue
import com.ariateam.aria.startup.StartupStage
import com.ariateam.aria.ui.theme.AriaTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class StartupScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun loadingState_displaysAriaTitleAndLoadingIndicatorAndStageMessage() {
        compose.setContent {
            AriaTheme {
                StartupScreen(
                    decision = StartupDecision.Loading(
                        stage = StartupStage.VisionRestore,
                        message = "주변 인식 준비 중…",
                    ),
                    onRetry = {},
                    onContinueDegraded = {},
                    onOpenSettings = {},
                )
            }
        }

        compose.onNodeWithText("ARIA").assertIsDisplayed()
        compose.onNodeWithText("주변 인식 준비 중…").assertIsDisplayed()
        compose.onNodeWithTag("startup_loading_indicator").assertIsDisplayed()
    }

    @Test
    fun actionRequiredState_displaysCardAndInvokesCallbacks() {
        var retried = false
        var continued = false
        var settingsOpened = false

        compose.setContent {
            AriaTheme {
                StartupScreen(
                    decision = StartupDecision.ActionRequired(
                        issue = StartupIssue.VisionModelMissing,
                        title = "주변 인식 기능을 사용할 수 없어요",
                        message = "설정에서 주변 인식을 준비하거나 음성 기능만 계속 이용할 수 있습니다.",
                        canRetry = true,
                        canContinueDegraded = true,
                        needsSettings = true,
                    ),
                    onRetry = { retried = true },
                    onContinueDegraded = { continued = true },
                    onOpenSettings = { settingsOpened = true },
                )
            }
        }

        compose.onNodeWithText("ARIA").assertIsDisplayed()
        compose.onNodeWithText("주변 인식 기능을 사용할 수 없어요").assertIsDisplayed()
        compose.onNodeWithText("설정에서 주변 인식을 준비하거나 음성 기능만 계속 이용할 수 있습니다.").assertIsDisplayed()

        compose.onNodeWithTag("startup_retry_button").assertIsDisplayed().performClick()
        assertTrue("Retry button should trigger onRetry", retried)

        compose.onNodeWithTag("startup_settings_button").assertIsDisplayed().performClick()
        assertTrue("Settings button should trigger onOpenSettings", settingsOpened)

        compose.onNodeWithTag("startup_continue_button").assertIsDisplayed().performClick()
        assertTrue("Continue button should trigger onContinueDegraded", continued)
    }

    @Test
    fun compactLargeTextFailureCanScrollToEveryAction() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                AriaTheme {
                    Box(Modifier.width(480.dp).height(240.dp)) {
                        StartupScreen(
                            decision = StartupDecision.ActionRequired(
                                issue = StartupIssue.VisionModelError,
                                title = "주변 인식을 준비하지 못했어요",
                                message = "주변 인식 기능을 설정에서 다시 준비하거나 음성 기능만 계속 이용할 수 있습니다.",
                                canRetry = true,
                                canContinueDegraded = true,
                                needsSettings = true,
                            ),
                            onRetry = {},
                            onContinueDegraded = {},
                            onOpenSettings = {},
                        )
                    }
                }
            }
        }

        listOf(
            "startup_retry_button",
            "startup_settings_button",
            "startup_continue_button",
        ).forEach { tag ->
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        }
    }
}
