package com.ariateam.aria.ui.screen

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.ariateam.aria.speech.VoiceState
import com.ariateam.aria.ui.theme.AriaTheme
import com.ariateam.aria.vision.CameraSessionStatus
import com.ariateam.aria.vision.UprightCropRect
import com.ariateam.aria.vision.VisionModelMetadata
import com.ariateam.aria.vision.VisionModelState
import com.ariateam.aria.vision.VisionPreprocessingStrategy
import com.ariateam.aria.vision.VisionTimingSnapshot
import com.ariateam.aria.vision.VisionUiState
import com.ariateam.aria.vision.od.DetectedObject
import com.ariateam.aria.vision.od.OdOverlaySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Deterministic Compose UI tests for [OdBoundingBoxesOverlay] and full screen integrations.
 *
 * Verifies:
 * - Pixel-mapped geometry semantics ([OdRenderedBoxesKey]) tied to actual drawing
 * - Contrasting bounding box placement within the crop rectangle
 * - Non-interactive / non-clickable behavior
 * - Expiry gating when clock elapses past [OdOverlaySnapshot.expiresAtElapsedNanos]
 * - State gating on camera stopped / model absent
 * - Integration into both [MainVisionInset] and [PresentationDemoScreen]
 */
class OdBoundingBoxOverlayUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val testCropRect = UprightCropRect(
        bufferWidth = 1280,
        bufferHeight = 720,
        rotationDegrees = 90,
        uprightWidth = 720,
        uprightHeight = 1280,
        left = 0,
        top = 200,
        width = 720,
        height = 800,
        strategy = VisionPreprocessingStrategy.FULL_FRAME_UPRIGHT_LETTERBOX,
    )

    private fun makePersonObj(
        left: Float = 0.20f,
        top: Float = 0.30f,
        right: Float = 0.60f,
        bottom: Float = 0.80f,
        score: Float = 0.85f,
    ) = DetectedObject(
        classId = 0,
        label = "person",
        score = score,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
    )

    @Test
    fun overlayRendersDetectionsAndExposesGeometrySemantics() {
        val nowNanos = 1_000_000_000L
        val expiryNanos = 2_000_000_000L
        val obj = makePersonObj()
        val snapshot = OdOverlaySnapshot(listOf(obj), expiryNanos)

        compose.setContent {
            AriaTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    OdBoundingBoxesOverlay(
                        cropRect = testCropRect,
                        previewUprightWidth = 720,
                        previewUprightHeight = 1280,
                        modelLoaded = true,
                        hasFreshImage = true,
                        odOverlay = snapshot,
                        cameraStatus = CameraSessionStatus.Running,
                        clockNanos = { nowNanos },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        val overlayNode = compose.onNodeWithTag("visionOdOverlay").assertIsDisplayed()
        val semanticsNode = overlayNode.fetchSemanticsNode()

        // 1. Geometry semantics tied to actual drawing
        val renderedBoxes = semanticsNode.config[OdRenderedBoxesKey]
        assertNotNull(renderedBoxes)
        assertEquals(1, renderedBoxes.size)

        val box = renderedBoxes[0]
        assertEquals(0, box.classId)
        assertEquals("사람", box.koreanLabel)
        assertEquals("사람 85%", box.formattedLabel)
        assertTrue("Rendered left must be positive", box.rectLeftPx > 0f)
        assertTrue("Rendered right must exceed left", box.rectRightPx > box.rectLeftPx)
        assertTrue("Rendered bottom must exceed top", box.rectBottomPx > box.rectTopPx)

        // 2. Strictly non-interactive: no click actions
        assertFalse(
            "Overlay must have no click action",
            semanticsNode.config.contains(SemanticsActions.OnClick),
        )

        // 3. Accessibility summary content description
        assertTrue(
            "Overlay must contain ContentDescription",
            semanticsNode.config.contains(SemanticsProperties.ContentDescription),
        )
        val contentDesc = semanticsNode.config[SemanticsProperties.ContentDescription]
        assertTrue("Description must contain Korean label", contentDesc.any { it.contains("사람 85%") })
    }

    @Test
    fun overlayDisappearsWhenSnapshotExpires() {
        val clock = mutableLongStateOf(1_000_000_000L)
        val expiryNanos = 1_200_000_000L // expires after 200ms
        val snapshot = OdOverlaySnapshot(listOf(makePersonObj()), expiryNanos)

        compose.setContent {
            AriaTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    OdBoundingBoxesOverlay(
                        cropRect = testCropRect,
                        previewUprightWidth = 720,
                        previewUprightHeight = 1280,
                        modelLoaded = true,
                        hasFreshImage = true,
                        odOverlay = snapshot,
                        cameraStatus = CameraSessionStatus.Running,
                        clockNanos = { clock.longValue },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // Before expiry: box is present
        var boxes = compose.onNodeWithTag("visionOdOverlay").fetchSemanticsNode().config[OdRenderedBoxesKey]
        assertEquals(1, boxes.size)

        // Advance clock past expiry without republishing any snapshot
        clock.longValue = 1_250_000_000L
        compose.mainClock.advanceTimeBy(250L)
        compose.waitForIdle()

        // After expiry: internal timer loop wakes up and clears boxes
        boxes = compose.onNodeWithTag("visionOdOverlay").fetchSemanticsNode().config[OdRenderedBoxesKey]
        assertTrue("Boxes must disappear when expired via internal timer", boxes.isEmpty())
    }

    @Test
    fun timerExpiryWakesWithCeilingAndClearsWithoutNewPublish() {
        val clock = mutableLongStateOf(1_000_000_000L)
        // 50.4 ms in the future
        val expiryNanos = 1_050_400_000L
        val snapshot = OdOverlaySnapshot(listOf(makePersonObj()), expiryNanos)

        compose.setContent {
            AriaTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    OdBoundingBoxesOverlay(
                        cropRect = testCropRect,
                        previewUprightWidth = 720,
                        previewUprightHeight = 1280,
                        modelLoaded = true,
                        hasFreshImage = true,
                        odOverlay = snapshot,
                        cameraStatus = CameraSessionStatus.Running,
                        clockNanos = { clock.longValue },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // Before expiry: box is present
        var boxes = compose.onNodeWithTag("visionOdOverlay").fetchSemanticsNode().config[OdRenderedBoxesKey]
        assertEquals(1, boxes.size)

        // Advance fake clock past expiry first so resuming coroutine observes elapsed time,
        // then advance Compose mainClock past the ceiling delay (51ms)
        clock.longValue = 1_055_000_000L
        compose.mainClock.advanceTimeBy(55L)
        compose.waitForIdle()

        // Without any new snapshot publish, the timer expiry loop must have cleared the boxes
        boxes = compose.onNodeWithTag("visionOdOverlay").fetchSemanticsNode().config[OdRenderedBoxesKey]
        assertTrue("Boxes must disappear via timer loop without any new camera frame publish", boxes.isEmpty())
    }

    @Test
    fun overlayHidesWhenCameraStoppedOrModelNotLoaded() {
        val nowNanos = 1_000_000_000L
        val snapshot = OdOverlaySnapshot(listOf(makePersonObj()), 2_000_000_000L)
        val cameraStatusState = mutableStateOf(CameraSessionStatus.Stopped)
        val modelLoadedState = mutableStateOf(true)

        compose.setContent {
            AriaTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    OdBoundingBoxesOverlay(
                        cropRect = testCropRect,
                        previewUprightWidth = 720,
                        previewUprightHeight = 1280,
                        modelLoaded = modelLoadedState.value,
                        hasFreshImage = true,
                        odOverlay = snapshot,
                        cameraStatus = cameraStatusState.value,
                        clockNanos = { nowNanos },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // Camera stopped -> empty boxes
        var boxes = compose.onNodeWithTag("visionOdOverlay").fetchSemanticsNode().config[OdRenderedBoxesKey]
        assertTrue(boxes.isEmpty())

        // Camera running but model not loaded -> empty boxes
        compose.runOnIdle {
            cameraStatusState.value = CameraSessionStatus.Running
            modelLoadedState.value = false
        }
        boxes = compose.onNodeWithTag("visionOdOverlay").fetchSemanticsNode().config[OdRenderedBoxesKey]
        assertTrue(boxes.isEmpty())

        // Both active -> 1 box
        compose.runOnIdle {
            modelLoadedState.value = true
        }
        boxes = compose.onNodeWithTag("visionOdOverlay").fetchSemanticsNode().config[OdRenderedBoxesKey]
        assertEquals(1, boxes.size)
    }

    @Test
    fun presentationDemoScreenIntegratesOdOverlay() {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val snapshot = OdOverlaySnapshot(listOf(makePersonObj()), nowNanos + 5_000_000_000L)
        val dummyBitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)

        val uiState = VisionUiState(
            cameraStatus = CameraSessionStatus.Running,
            latestOverlayBitmap = dummyBitmap,
            timing = VisionTimingSnapshot(
                modelLoaded = true,
                cropRect = testCropRect,
                previewUprightWidth = 720,
                previewUprightHeight = 1280,
                steadyFrames = 1,
            ),
            odOverlay = snapshot,
        )

        compose.setContent {
            AriaTheme {
                PresentationDemoScreen(
                    voiceState = VoiceState(),
                    visionUiState = uiState,
                    visionModelState = VisionModelState.Ready(
                        VisionModelMetadata(
                            displayName = "test.tflite",
                            sizeBytes = 100L,
                            sha256 = "sha",
                            importedAtMillis = 1000L,
                        ),
                    ),
                    onBack = {},
                    onStartCamera = { _, _, _ -> },
                    onStopCamera = {},
                    onStartVoiceWaiting = {},
                    onStopVoice = {},
                    onOpenAppSettings = {},
                )
            }
        }

        // PresentationDemoScreen contains the OD overlay with rendered boxes
        val overlayNode = compose.onNodeWithTag("visionOdOverlay").assertIsDisplayed()
        val renderedBoxes = overlayNode.fetchSemanticsNode().config[OdRenderedBoxesKey]
        assertNotNull(renderedBoxes)
        assertEquals(1, renderedBoxes.size)
        assertEquals("사람", renderedBoxes[0].koreanLabel)
        assertEquals("사람 85%", renderedBoxes[0].formattedLabel)
    }

    @Test
    fun mainVisionInsetIntegratesOdOverlay() {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val snapshot = OdOverlaySnapshot(listOf(makePersonObj()), nowNanos + 5_000_000_000L)
        val dummyBitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)

        val uiState = VisionUiState(
            cameraStatus = CameraSessionStatus.Running,
            latestOverlayBitmap = dummyBitmap,
            timing = VisionTimingSnapshot(
                modelLoaded = true,
                cropRect = testCropRect,
                previewUprightWidth = 720,
                previewUprightHeight = 1280,
                steadyFrames = 1,
            ),
            odOverlay = snapshot,
        )

        compose.setContent {
            AriaTheme {
                MainVisionInset(
                    visionUiState = uiState,
                    visionModelState = VisionModelState.Ready(
                        VisionModelMetadata(
                            displayName = "test.tflite",
                            sizeBytes = 100L,
                            sha256 = "sha",
                            importedAtMillis = 1000L,
                        ),
                    ),
                    hasCameraPermission = true,
                    isHomeVisible = true,
                    onStartCamera = { _, _ -> },
                    onStopCamera = {},
                    onSurfaceProviderUpdated = { _, _ -> },
                )
            }
        }

        val overlayNode = compose.onNodeWithTag("visionOdOverlay").assertIsDisplayed()
        val renderedBoxes = overlayNode.fetchSemanticsNode().config[OdRenderedBoxesKey]
        assertNotNull(renderedBoxes)
        assertEquals(1, renderedBoxes.size)
        assertEquals("사람", renderedBoxes[0].koreanLabel)
        assertEquals("사람 85%", renderedBoxes[0].formattedLabel)
    }

    @Test
    fun heldSnapshotPersistsBeyond750msAndFiniteExpiryWithoutNewPublish() {
        val clock = mutableLongStateOf(1_000_000_000L)
        // expiresAt is 200ms in future, but holdUntilNextResult is true
        val expiryNanos = 1_200_000_000L
        val snapshot = OdOverlaySnapshot(
            detections = listOf(makePersonObj()),
            expiresAtElapsedNanos = expiryNanos,
            holdUntilNextResult = true,
        )

        compose.setContent {
            AriaTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    OdBoundingBoxesOverlay(
                        cropRect = testCropRect,
                        previewUprightWidth = 720,
                        previewUprightHeight = 1280,
                        modelLoaded = true,
                        hasFreshImage = true,
                        odOverlay = snapshot,
                        cameraStatus = CameraSessionStatus.Running,
                        clockNanos = { clock.longValue },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // Initially present
        var boxes = compose.onNodeWithTag("visionOdOverlay").fetchSemanticsNode().config[OdRenderedBoxesKey]
        assertEquals(1, boxes.size)

        // Advance clock past 200ms expiry and past 750ms (advance by 1000ms = 1s)
        clock.longValue = 2_000_000_000L
        compose.mainClock.advanceTimeBy(1000L)
        compose.waitForIdle()

        // Held snapshot MUST NOT disappear! Still rendered!
        boxes = compose.onNodeWithTag("visionOdOverlay").fetchSemanticsNode().config[OdRenderedBoxesKey]
        assertEquals("Held boxes must remain visible beyond 750ms and finite expiry", 1, boxes.size)
        assertEquals("사람", boxes[0].koreanLabel)
    }

    @Test
    fun heldSnapshotUpdatesOnNewResultAndClearsOnEmptyOrNull() {
        val clock = mutableLongStateOf(1_000_000_000L)
        val snapshotState = mutableStateOf<OdOverlaySnapshot?>(
            OdOverlaySnapshot(
                detections = listOf(makePersonObj()),
                expiresAtElapsedNanos = 1_200_000_000L,
                holdUntilNextResult = true,
            ),
        )

        compose.setContent {
            AriaTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    OdBoundingBoxesOverlay(
                        cropRect = testCropRect,
                        previewUprightWidth = 720,
                        previewUprightHeight = 1280,
                        modelLoaded = true,
                        hasFreshImage = true,
                        odOverlay = snapshotState.value,
                        cameraStatus = CameraSessionStatus.Running,
                        clockNanos = { clock.longValue },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        var boxes = compose.onNodeWithTag("visionOdOverlay").fetchSemanticsNode().config[OdRenderedBoxesKey]
        assertEquals(1, boxes.size)
        assertEquals("사람", boxes[0].koreanLabel)

        // 1. Advance clock past 800ms; boxes persist
        clock.longValue = 1_800_000_000L
        compose.mainClock.advanceTimeBy(800L)
        compose.waitForIdle()
        boxes = compose.onNodeWithTag("visionOdOverlay").fetchSemanticsNode().config[OdRenderedBoxesKey]
        assertEquals(1, boxes.size)

        // 2. New detection result replaces prior boxes without flicker
        val carObj = DetectedObject(
            classId = 2,
            label = "car",
            score = 0.92f,
            left = 0.10f,
            top = 0.20f,
            right = 0.50f,
            bottom = 0.70f,
        )
        compose.runOnIdle {
            snapshotState.value = OdOverlaySnapshot(
                detections = listOf(carObj),
                expiresAtElapsedNanos = 2_800_000_000L,
                holdUntilNextResult = true,
            )
        }
        boxes = compose.onNodeWithTag("visionOdOverlay").fetchSemanticsNode().config[OdRenderedBoxesKey]
        assertEquals(1, boxes.size)
        assertEquals("자동차", boxes[0].koreanLabel)

        // 3. New empty result clears boxes
        compose.runOnIdle {
            snapshotState.value = OdOverlaySnapshot(
                detections = emptyList(),
                expiresAtElapsedNanos = 3_000_000_000L,
                holdUntilNextResult = true,
            )
        }
        boxes = compose.onNodeWithTag("visionOdOverlay").fetchSemanticsNode().config[OdRenderedBoxesKey]
        assertTrue("Empty detection result must clear held boxes", boxes.isEmpty())

        // 4. Null snapshot clears boxes
        compose.runOnIdle {
            snapshotState.value = OdOverlaySnapshot(
                detections = listOf(makePersonObj()),
                expiresAtElapsedNanos = 4_000_000_000L,
                holdUntilNextResult = true,
            )
        }
        boxes = compose.onNodeWithTag("visionOdOverlay").fetchSemanticsNode().config[OdRenderedBoxesKey]
        assertEquals(1, boxes.size)

        compose.runOnIdle {
            snapshotState.value = null
        }
        boxes = compose.onNodeWithTag("visionOdOverlay").fetchSemanticsNode().config[OdRenderedBoxesKey]
        assertTrue("Null snapshot must clear held boxes", boxes.isEmpty())
    }

    @Test
    fun heldSnapshotClearsWhenCameraStoppedOrModelNotLoadedOrMissingImage() {
        val clock = mutableLongStateOf(1_000_000_000L)
        val snapshot = OdOverlaySnapshot(
            detections = listOf(makePersonObj()),
            expiresAtElapsedNanos = 1_200_000_000L,
            holdUntilNextResult = true,
        )
        val cameraStatusState = mutableStateOf(CameraSessionStatus.Running)
        val modelLoadedState = mutableStateOf(true)
        val hasFreshImageState = mutableStateOf(true)

        compose.setContent {
            AriaTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    OdBoundingBoxesOverlay(
                        cropRect = testCropRect,
                        previewUprightWidth = 720,
                        previewUprightHeight = 1280,
                        modelLoaded = modelLoadedState.value,
                        hasFreshImage = hasFreshImageState.value,
                        odOverlay = snapshot,
                        cameraStatus = cameraStatusState.value,
                        clockNanos = { clock.longValue },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        var boxes = compose.onNodeWithTag("visionOdOverlay").fetchSemanticsNode().config[OdRenderedBoxesKey]
        assertEquals(1, boxes.size)

        // Stopped camera clears
        compose.runOnIdle {
            cameraStatusState.value = CameraSessionStatus.Stopped
        }
        boxes = compose.onNodeWithTag("visionOdOverlay").fetchSemanticsNode().config[OdRenderedBoxesKey]
        assertTrue("Camera stopped must clear held boxes", boxes.isEmpty())

        // Restore camera
        compose.runOnIdle {
            cameraStatusState.value = CameraSessionStatus.Running
        }
        boxes = compose.onNodeWithTag("visionOdOverlay").fetchSemanticsNode().config[OdRenderedBoxesKey]
        assertEquals(1, boxes.size)

        // Model not loaded clears
        compose.runOnIdle {
            modelLoadedState.value = false
        }
        boxes = compose.onNodeWithTag("visionOdOverlay").fetchSemanticsNode().config[OdRenderedBoxesKey]
        assertTrue("Model unloaded must clear held boxes", boxes.isEmpty())

        // Restore model
        compose.runOnIdle {
            modelLoadedState.value = true
        }
        boxes = compose.onNodeWithTag("visionOdOverlay").fetchSemanticsNode().config[OdRenderedBoxesKey]
        assertEquals(1, boxes.size)

        // Missing fresh image clears
        compose.runOnIdle {
            hasFreshImageState.value = false
        }
        boxes = compose.onNodeWithTag("visionOdOverlay").fetchSemanticsNode().config[OdRenderedBoxesKey]
        assertTrue("Missing image must clear held boxes", boxes.isEmpty())
    }

    @Test
    fun presentationDemoScreenIntegratesHeldOdOverlay() {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val snapshot = OdOverlaySnapshot(
            detections = listOf(makePersonObj()),
            expiresAtElapsedNanos = nowNanos,
            holdUntilNextResult = true,
        )
        val dummyBitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)

        val uiState = VisionUiState(
            cameraStatus = CameraSessionStatus.Running,
            latestOverlayBitmap = dummyBitmap,
            timing = VisionTimingSnapshot(
                modelLoaded = true,
                cropRect = testCropRect,
                previewUprightWidth = 720,
                previewUprightHeight = 1280,
                steadyFrames = 1,
            ),
            odOverlay = snapshot,
        )

        compose.setContent {
            AriaTheme {
                PresentationDemoScreen(
                    voiceState = VoiceState(),
                    visionUiState = uiState,
                    visionModelState = VisionModelState.Ready(
                        VisionModelMetadata(
                            displayName = "test.tflite",
                            sizeBytes = 100L,
                            sha256 = "sha",
                            importedAtMillis = 1000L,
                        ),
                    ),
                    onBack = {},
                    onStartCamera = { _, _, _ -> },
                    onStopCamera = {},
                    onStartVoiceWaiting = {},
                    onStopVoice = {},
                    onOpenAppSettings = {},
                )
            }
        }

        val overlayNode = compose.onNodeWithTag("visionOdOverlay").assertIsDisplayed()
        val renderedBoxes = overlayNode.fetchSemanticsNode().config[OdRenderedBoxesKey]
        assertNotNull(renderedBoxes)
        assertEquals(1, renderedBoxes.size)
        assertEquals("사람", renderedBoxes[0].koreanLabel)
        assertEquals("사람 85%", renderedBoxes[0].formattedLabel)
    }

    @Test
    fun mainVisionInsetIntegratesHeldOdOverlay() {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val snapshot = OdOverlaySnapshot(
            detections = listOf(makePersonObj()),
            expiresAtElapsedNanos = nowNanos,
            holdUntilNextResult = true,
        )
        val dummyBitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)

        val uiState = VisionUiState(
            cameraStatus = CameraSessionStatus.Running,
            latestOverlayBitmap = dummyBitmap,
            timing = VisionTimingSnapshot(
                modelLoaded = true,
                cropRect = testCropRect,
                previewUprightWidth = 720,
                previewUprightHeight = 1280,
                steadyFrames = 1,
            ),
            odOverlay = snapshot,
        )

        compose.setContent {
            AriaTheme {
                MainVisionInset(
                    visionUiState = uiState,
                    visionModelState = VisionModelState.Ready(
                        VisionModelMetadata(
                            displayName = "test.tflite",
                            sizeBytes = 100L,
                            sha256 = "sha",
                            importedAtMillis = 1000L,
                        ),
                    ),
                    hasCameraPermission = true,
                    isHomeVisible = true,
                    onStartCamera = { _, _ -> },
                    onStopCamera = {},
                    onSurfaceProviderUpdated = { _, _ -> },
                )
            }
        }

        val overlayNode = compose.onNodeWithTag("visionOdOverlay").assertIsDisplayed()
        val renderedBoxes = overlayNode.fetchSemanticsNode().config[OdRenderedBoxesKey]
        assertNotNull(renderedBoxes)
        assertEquals(1, renderedBoxes.size)
        assertEquals("사람", renderedBoxes[0].koreanLabel)
        assertEquals("사람 85%", renderedBoxes[0].formattedLabel)
    }
}
