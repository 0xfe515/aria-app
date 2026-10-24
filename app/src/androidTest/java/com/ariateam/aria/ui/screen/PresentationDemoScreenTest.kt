package com.ariateam.aria.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.platform.app.InstrumentationRegistry
import com.ariateam.aria.R
import com.ariateam.aria.speech.VoicePhase
import com.ariateam.aria.speech.VoiceState
import com.ariateam.aria.ui.theme.AriaTheme
import com.ariateam.aria.vision.CameraSessionStatus
import com.ariateam.aria.vision.VisionModelState
import com.ariateam.aria.vision.VisionPipelineMode
import com.ariateam.aria.vision.VisionUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PresentationDemoScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun viewportFillsPortraitAndLandscapeWithoutAStackedScrollPage() {
        val viewportSize = mutableStateOf(360.dp to 800.dp)
        compose.setContent {
            AriaTheme {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val rootWidth = minOf(viewportSize.value.first, maxWidth)
                    val rootHeight = minOf(viewportSize.value.second, maxHeight)
                    Box(Modifier.size(rootWidth, rootHeight).testTag("presentationTestRoot")) {
                        CompositionLocalProvider(
                            LocalDensity provides Density(density = LocalDensity.current.density, fontScale = 1.5f),
                        ) {
                            PresentationDemoScreen(
                                voiceState = VoiceState(),
                                visionUiState = VisionUiState(),
                                visionModelState = VisionModelState.Missing,
                                onBack = {},
                                onStartCamera = { _, _, _ -> },
                                onStopCamera = {},
                                onStartVoiceWaiting = {},
                                onStopVoice = {},
                                onOpenAppSettings = {},
                            )
                        }
                    }
                }
            }
        }

        var rootBounds = compose.onNodeWithTag("presentationTestRoot").getUnclippedBoundsInRoot()
        var viewportBounds = compose.onNodeWithTag("presentationCameraViewport").getUnclippedBoundsInRoot()
        assertTrue(rootBounds.width > 0.dp)
        assertTrue(rootBounds.height > 0.dp)
        assertTrue(viewportBounds.width >= rootBounds.width * 0.65f)
        assertTrue(viewportBounds.height >= rootBounds.height * 0.65f)
        compose.onNodeWithTag("presentationCombinedControl").assertIsDisplayed()
        compose.onNodeWithTag("presentationCameraControl").assertIsDisplayed()
        compose.onNodeWithTag("presentationVoiceControl").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .assertDoesNotExist()

        compose.runOnIdle { viewportSize.value = 720.dp to 360.dp }
        rootBounds = compose.onNodeWithTag("presentationTestRoot").getUnclippedBoundsInRoot()
        viewportBounds = compose.onNodeWithTag("presentationCameraViewport").getUnclippedBoundsInRoot()
        assertTrue(rootBounds.width > 0.dp)
        assertTrue(rootBounds.height > 0.dp)
        assertTrue(viewportBounds.width >= rootBounds.width * 0.65f)
        assertTrue(viewportBounds.height >= rootBounds.height * 0.65f)
        compose.onNodeWithTag("presentationCombinedControl").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .assertDoesNotExist()
        listOf(
            "presentationCombinedControl",
            "presentationCameraControl",
            "presentationVoiceControl",
            "presentationDetailsButton",
        ).forEach { tag ->
            val control = compose.onNodeWithTag(tag).assertIsDisplayed()
            val controlBounds = control.getUnclippedBoundsInRoot()
            assertTrue(controlBounds.top >= rootBounds.top)
            assertTrue(controlBounds.bottom <= rootBounds.bottom)
        }
    }

    @Test
    fun combinedControlStartsAndStopsRealCameraAndVoiceCallbacks() {
        val visionState = mutableStateOf(VisionUiState())
        val voiceState = mutableStateOf(VoiceState())
        var cameraStarts = 0
        var cameraStops = 0
        var voiceStarts = 0
        var voiceStops = 0
        compose.setContent {
            AriaTheme {
                PresentationDemoScreen(
                    voiceState = voiceState.value,
                    visionUiState = visionState.value,
                    visionModelState = VisionModelState.Missing,
                    onBack = {},
                    onStartCamera = { _: androidx.camera.core.Preview.SurfaceProvider?, mode: VisionPipelineMode, _: Int ->
                        assertEquals(VisionPipelineMode.PREPROCESS_OR_INFERENCE, mode)
                        cameraStarts++
                    },
                    onStopCamera = { cameraStops++ },
                    onStartVoiceWaiting = { voiceStarts++ },
                    onStopVoice = { voiceStops++ },
                    onOpenAppSettings = {},
                )
            }
        }

        compose.onNodeWithTag("presentationCombinedControl").performClick()
        compose.runOnIdle {
            assertEquals(1, cameraStarts)
            assertEquals(1, voiceStarts)
            visionState.value = VisionUiState(cameraStatus = CameraSessionStatus.Running)
            voiceState.value = VoiceState(com.ariateam.aria.speech.VoicePhase.Waiting)
        }
        compose.onNodeWithTag("presentationCombinedControl").performClick()
        compose.runOnIdle {
            assertEquals(1, cameraStops)
            assertEquals(1, voiceStops)
        }
    }

    @Test
    fun combinedControlStartsOnlyCameraWhenVoiceIsAlreadyWaiting() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var cameraStarts = 0
        var voiceStarts = 0
        compose.setContent {
            AriaTheme {
                PresentationDemoScreen(
                    voiceState = VoiceState(phase = VoicePhase.Waiting),
                    visionUiState = VisionUiState(),
                    visionModelState = VisionModelState.Missing,
                    onBack = {},
                    onStartCamera = { _, _, _ -> cameraStarts++ },
                    onStopCamera = {},
                    onStartVoiceWaiting = { voiceStarts++ },
                    onStopVoice = {},
                    onOpenAppSettings = {},
                )
            }
        }

        compose.onNodeWithContentDescription(context.getString(R.string.presentation_start_all_description))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.presentation_start_all)).assertIsDisplayed()
        compose.onNodeWithTag("presentationCombinedControl").performClick()
        compose.runOnIdle {
            assertEquals(1, cameraStarts)
            assertEquals(0, voiceStarts)
        }
    }

    @Test
    fun combinedControlStartsOnlyVoiceWhenCameraIsAlreadyRunning() {
        var cameraStarts = 0
        var voiceStarts = 0
        compose.setContent {
            AriaTheme {
                PresentationDemoScreen(
                    voiceState = VoiceState(),
                    visionUiState = VisionUiState(cameraStatus = CameraSessionStatus.Running),
                    visionModelState = VisionModelState.Missing,
                    onBack = {},
                    onStartCamera = { _, _, _ -> cameraStarts++ },
                    onStopCamera = {},
                    onStartVoiceWaiting = { voiceStarts++ },
                    onStopVoice = {},
                    onOpenAppSettings = {},
                )
            }
        }

        compose.onNodeWithTag("presentationCombinedControl").performClick()
        compose.runOnIdle {
            assertEquals(0, cameraStarts)
            assertEquals(1, voiceStarts)
        }
    }

    @Test
    fun combinedControlIsDisabledWhileVoiceStopsAndCameraIsStopped() {
        var cameraStarts = 0
        var voiceStarts = 0
        compose.setContent {
            AriaTheme {
                PresentationDemoScreen(
                    voiceState = VoiceState(phase = VoicePhase.Stopping),
                    visionUiState = VisionUiState(),
                    visionModelState = VisionModelState.Missing,
                    onBack = {},
                    onStartCamera = { _, _, _ -> cameraStarts++ },
                    onStopCamera = {},
                    onStartVoiceWaiting = { voiceStarts++ },
                    onStopVoice = {},
                    onOpenAppSettings = {},
                )
            }
        }

        compose.onNodeWithTag("presentationCombinedControl").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(0, cameraStarts)
            assertEquals(0, voiceStarts)
        }
    }

    @Test
    fun missingModelAndDeniedCameraRemainTruthfulAndActionable() {
        var settingsOpened = false
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            AriaTheme {
                PresentationDemoScreen(
                    voiceState = VoiceState(),
                    visionUiState = VisionUiState(cameraStatus = CameraSessionStatus.PermissionDenied),
                    visionModelState = VisionModelState.Missing,
                    onBack = {},
                    onStartCamera = { _, _, _ -> },
                    onStopCamera = {},
                    onStartVoiceWaiting = {},
                    onStopVoice = {},
                    onOpenAppSettings = { settingsOpened = true },
                )
            }
        }

        compose.onNodeWithTag("presentationModelStatus")
            .assertIsDisplayed()
            .assertTextContains(context.getString(R.string.presentation_model_missing_short))
        compose.onNodeWithText(context.getString(R.string.presentation_model_missing_detail)).assertDoesNotExist()
        compose.onNodeWithText("현재 세그멘테이션 .tflite 모델이 로드되지 않아", substring = true)
            .assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.presentation_camera_permission)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.open_app_settings)).performClick()
        compose.runOnIdle { assertTrue(settingsOpened) }
    }

    @Test
    fun modelAndCameraErrorsAreAvailableInCompactOverlayAndDetails() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            AriaTheme {
                PresentationDemoScreen(
                    voiceState = VoiceState(),
                    visionUiState = VisionUiState(
                        cameraStatus = CameraSessionStatus.Error,
                        cameraErrorDetail = "Camera service unavailable",
                    ),
                    visionModelState = VisionModelState.Error("Model rejected", "invalid output shape"),
                    onBack = {},
                    onStartCamera = { _, _, _ -> },
                    onStopCamera = {},
                    onStartVoiceWaiting = {},
                    onStopVoice = {},
                    onOpenAppSettings = {},
                )
            }
        }

        compose.onNodeWithText("Camera service unavailable").assertIsDisplayed()
        compose.onNodeWithTag("presentationDetailsButton").performClick()
        compose.onNodeWithText(context.getString(R.string.presentation_model_error, "Model rejected"))
            .assertIsDisplayed()
        compose.onNodeWithText("invalid output shape").assertIsDisplayed()
    }

    @Test
    fun backButtonStopsCameraBeforeNavigatingBack() {
        var stopCalls = 0
        var backCalls = 0
        compose.setContent {
            AriaTheme {
                PresentationDemoScreen(
                    voiceState = VoiceState(),
                    visionUiState = VisionUiState(),
                    visionModelState = VisionModelState.Missing,
                    onBack = { backCalls++ },
                    onStartCamera = { _, _, _ -> },
                    onStopCamera = { stopCalls++ },
                    onStartVoiceWaiting = {},
                    onStopVoice = {},
                    onOpenAppSettings = {},
                )
            }
        }

        compose.onNodeWithContentDescription("통합 시연 종료").performClick()
        compose.runOnIdle {
            assertEquals(1, backCalls)
            assertTrue(stopCalls >= 1)
        }
    }
}
