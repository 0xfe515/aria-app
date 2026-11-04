package com.ariateam.aria.ui.screen

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ariateam.aria.hazard.MotionState
import com.ariateam.aria.speech.VoiceState
import com.ariateam.aria.ui.theme.AriaTheme
import com.ariateam.aria.vision.CameraSessionStatus
import com.ariateam.aria.vision.VisionModelMetadata
import com.ariateam.aria.vision.VisionModelState
import com.ariateam.aria.vision.VisionUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Connected device instrumentation test for the restored Home vision overlay,
 * direction line visualization, sibling positioning preservation, and read-only semantics.
 */
@RunWith(AndroidJUnit4::class)
class DashboardHeadingUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dashboardDisplaysRestoredBoundsAndRelativeHeadingTelemetry() {
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

        // 1. Verify overlay is displayed with restored established layout dimensions (~224dp x 150dp)
        val insetNode = composeRule.onNodeWithTag("mainVisionInset").assertIsDisplayed()
        val bounds = insetNode.getUnclippedBoundsInRoot()
        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top

        assertTrue("Overlay width ($width) must be restored within 180..240dp", width in 180.dp..240.dp)
        assertTrue("Overlay height ($height) must be restored within 120..170dp", height in 120.dp..170.dp)

        // 2. Verify estimate tag is displayed
        composeRule.onNodeWithTag("mainVisionEstimateTag").assertIsDisplayed()

        // 3. Verify relative heading badge displays truthful label
        composeRule.onNodeWithTag("mainVisionHeadingBadge").assertIsDisplayed()
        composeRule.onNodeWithText("상대 방향: 좌측 15°").assertIsDisplayed()

        // 4. Verify relative heading single-line canvas is rendered
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

        // Verify there are no clickable recovery buttons inside the read-only overlay
        composeRule.onNodeWithTag("mainVisionPermissionRecoveryButton").assertDoesNotExist()
        composeRule.onNodeWithTag("mainVisionRetryButton").assertDoesNotExist()
        composeRule.onNodeWithTag("mainVisionExplicitRestartButton").assertDoesNotExist()
        composeRule.onNodeWithTag("dashboardRecoveryGrantPermissionButton").assertDoesNotExist()
    }

    @Test
    fun overlayDoesNotShiftSiblingHomeComponents() {
        var showOverlay by mutableStateOf(false)

        composeRule.setContent {
            AriaTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Original Home composition
                    VoiceDemoScreen(
                        state = VoiceState(),
                        onStart = {},
                        onFinish = {},
                        onOpenSettings = {},
                        onStartWaiting = {},
                        onStop = {},
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 56.dp),
                    )

                    // Overlay visually floats on top at TopEnd when present
                    if (showOverlay) {
                        MainVisionInset(
                            visionUiState = VisionUiState(cameraStatus = CameraSessionStatus.Running),
                            visionModelState = VisionModelState.Ready(
                                VisionModelMetadata("test.tflite", 100L, "sha", 1000L)
                            ),
                            hasCameraPermission = true,
                            isHomeVisible = true,
                            onStartCamera = { _, _ -> },
                            onStopCamera = {},
                            onSurfaceProviderUpdated = { _, _ -> },
                            onRequestCameraPermission = {},
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 8.dp, end = 12.dp),
                        )
                    }
                }
            }
        }

        // 1. Measure sibling Home header bounds when overlay is ABSENT
        val headerNodeWithoutOverlay = composeRule.onNodeWithText("ARIA").assertIsDisplayed()
        val boundsWithoutOverlay = headerNodeWithoutOverlay.getUnclippedBoundsInRoot()

        // 2. Add overlay floating on top
        composeRule.runOnUiThread {
            showOverlay = true
        }
        composeRule.waitForIdle()

        // 3. Measure sibling Home header bounds when overlay is PRESENT
        val headerNodeWithOverlay = composeRule.onNodeWithText("ARIA").assertIsDisplayed()
        val boundsWithOverlay = headerNodeWithOverlay.getUnclippedBoundsInRoot()

        // 4. Assert sibling component positions remain completely unchanged (0 pixel shift)
        assertEquals(
            "Sibling top position must not shift when overlay is present",
            boundsWithoutOverlay.top,
            boundsWithOverlay.top,
        )
        assertEquals(
            "Sibling bottom position must not shift when overlay is present",
            boundsWithoutOverlay.bottom,
            boundsWithOverlay.bottom,
        )
        assertEquals(
            "Sibling left position must not shift when overlay is present",
            boundsWithoutOverlay.left,
            boundsWithOverlay.left,
        )
        assertEquals(
            "Sibling right position must not shift when overlay is present",
            boundsWithoutOverlay.right,
            boundsWithOverlay.right,
        )
    }

    @Test
    fun directionLineOriginIsAtBottomCenterAndHasNoExtraGeometry() {
        val width = 224f
        val height = 150f
        val strokeInset = 4f

        // Forward heading: exactly one line originating from bottom-center
        val forwardGeom = HeadingArrowGeometryCalculator.calculate(
            width = width,
            height = height,
            normalizedAngleDeg = 20f,
            isAuthoritativeEligible = true,
            strokeInset = strokeInset,
        )
        assertTrue(forwardGeom.isVisible)
        assertEquals("originX must be horizontal center", width / 2f, forwardGeom.originX, 0.01f)
        assertEquals("originY must be at bottom with stroke inset", height - strokeInset, forwardGeom.originY, 0.01f)
        assertTrue("tipY must point forward (upward)", forwardGeom.tipY < forwardGeom.originY)

        // Rearward heading: naturally hides line, leaves status to text badge
        val rearGeom = HeadingArrowGeometryCalculator.calculate(
            width = width,
            height = height,
            normalizedAngleDeg = 135f,
            isAuthoritativeEligible = true,
            strokeInset = strokeInset,
        )
        assertFalse("Rearward direction must naturally hide line", rearGeom.isVisible)
        assertEquals(0f, rearGeom.arrowLength, 0.01f)

        // Stale or uncalibrated: naturally hides line
        val uncalibratedGeom = HeadingArrowGeometryCalculator.calculate(
            width = width,
            height = height,
            normalizedAngleDeg = 0f,
            isAuthoritativeEligible = false,
            strokeInset = strokeInset,
        )
        assertFalse("Uncalibrated/stale state must naturally hide line", uncalibratedGeom.isVisible)
    }

    @Test
    fun settingsVisionRecoveryBarFunctionsWhenActionNeeded() {
        var restartClicked = false
        var profileClicked = false

        composeRule.setContent {
            AriaTheme {
                SettingsVisionRecoveryBar(
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

        // Recovery buttons are displayed in Settings recovery bar
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
