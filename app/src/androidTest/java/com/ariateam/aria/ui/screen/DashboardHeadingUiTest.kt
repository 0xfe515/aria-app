package com.ariateam.aria.ui.screen

import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ariateam.aria.hazard.MotionState
import com.ariateam.aria.ui.theme.AriaTheme
import com.ariateam.aria.vision.CameraSessionStatus
import com.ariateam.aria.vision.VisionModelMetadata
import com.ariateam.aria.vision.VisionModelState
import com.ariateam.aria.vision.VisionUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Connected device instrumentation test for the read-only Home vision dashboard
 * and relative IMU heading overlay on Google Pixel 3.
 */
@RunWith(AndroidJUnit4::class)
class DashboardHeadingUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dashboardDisplaysEnlargedBoundsAndRelativeHeadingTelemetry() {
        val testMotion = MotionState(
            timestampNanos = SystemClock.elapsedRealtimeNanos(),
            isCalibrated = true,
            relativeHeadingDeg = 15f,
            confidence = 1.0f,
            degradedReason = null,
        )

        composeRule.setContent {
            AriaTheme {
                MainVisionInset(
                    visionUiState = VisionUiState(
                        cameraStatus = CameraSessionStatus.Running,
                        motionState = testMotion,
                    ),
                    visionModelState = VisionModelState.Ready(
                        VisionModelMetadata("test.tflite", 100L, "sha", 1000L)
                    ),
                    hasCameraPermission = true,
                    isHomeVisible = true,
                    onStartCamera = { _, _ -> },
                    onStopCamera = {},
                    onSurfaceProviderUpdated = { _, _ -> },
                    onRequestCameraPermission = {},
                )
            }
        }

        // 1. Verify dashboard inset is displayed with enlarged dimensions
        val insetNode = composeRule.onNodeWithTag("mainVisionInset").assertIsDisplayed()
        val bounds = insetNode.getUnclippedBoundsInRoot()
        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top

        assertTrue("Dashboard width ($width) must be enlarged >= 250dp", width >= 250.dp)
        assertTrue("Dashboard height ($height) must be enlarged >= 170dp", height >= 170.dp)

        // 2. Verify estimate tag is displayed
        composeRule.onNodeWithTag("mainVisionEstimateTag").assertIsDisplayed()

        // 3. Verify relative heading badge displays truthful label
        composeRule.onNodeWithTag("mainVisionHeadingBadge").assertIsDisplayed()
        composeRule.onNodeWithText("상대 방향: 좌측 15°").assertIsDisplayed()

        // 4. Verify relative heading arrow canvas is rendered
        composeRule.onNodeWithTag("dashboardHeadingCanvas").assertIsDisplayed()
    }

    @Test
    fun dashboardIsStrictlyReadOnlyWithoutControlButtons() {
        composeRule.setContent {
            AriaTheme {
                MainVisionInset(
                    visionUiState = VisionUiState(
                        cameraStatus = CameraSessionStatus.Stopped,
                    ),
                    visionModelState = VisionModelState.Ready(
                        VisionModelMetadata("test.tflite", 100L, "sha", 1000L)
                    ),
                    hasCameraPermission = true,
                    isHomeVisible = true,
                    onStartCamera = { _, _ -> },
                    onStopCamera = {},
                    onSurfaceProviderUpdated = { _, _ -> },
                    onRequestCameraPermission = {},
                )
            }
        }

        // Verify stopped status and heading status are displayed as informative text
        composeRule.onNodeWithTag("mainVisionStoppedStatus").assertIsDisplayed()
        composeRule.onNodeWithTag("mainVisionInactiveHeadingStatus").assertIsDisplayed()

        // Verify there are no clickable recovery buttons inside the read-only dashboard
        composeRule.onNodeWithTag("mainVisionPermissionRecoveryButton").assertDoesNotExist()
        composeRule.onNodeWithTag("mainVisionRetryButton").assertDoesNotExist()
        composeRule.onNodeWithTag("mainVisionExplicitRestartButton").assertDoesNotExist()
    }

    @Test
    fun homeRecoveryBarProvidesRelocatedControlsWhenActionNeeded() {
        var restartClicked = false
        var profileClicked = false

        composeRule.setContent {
            AriaTheme {
                DashboardHomeRecoveryBar(
                    hasCameraPermission = true,
                    cameraStatus = CameraSessionStatus.Stopped,
                    requiresExplicitRestart = true,
                    isProfileConfigured = false,
                    onRequestCameraPermission = {},
                    onExplicitRestartCamera = { restartClicked = true },
                    onRetryCamera = {},
                    onOpenProfileSetup = { profileClicked = true },
                )
            }
        }

        // Recovery buttons are displayed on Home outside the dashboard
        composeRule.onNodeWithTag("dashboardRecoveryRestartCameraButton")
            .assertIsDisplayed()
            .performClick()

        composeRule.onNodeWithTag("dashboardRecoveryProfileSetupButton")
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle {
            assertTrue(restartClicked)
            assertTrue(profileClicked)
        }
    }
}
