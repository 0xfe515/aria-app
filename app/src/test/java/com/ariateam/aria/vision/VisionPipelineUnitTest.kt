package com.ariateam.aria.vision

import android.view.Surface
import com.ariateam.aria.DebugDestination
import com.ariateam.aria.PendingCameraStartGate
import com.ariateam.aria.hazard.CorridorDirection
import com.ariateam.aria.hazard.HazardDetector
import com.ariateam.aria.hazard.HazardLevel
import com.ariateam.aria.hazard.MotionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files

class VisionPipelineUnitTest {

    @Test
    fun validateModelSpec_acceptsExactAriaContractAndRejectsMismatches() {
        val valid = VisionContract.validateModelSpec(
            inputTensorCount = 1,
            inputShape = intArrayOf(1, 512, 288, 3),
            inputDataType = "FLOAT32",
            outputTensorCount = 1,
            outputShape = intArrayOf(1, 512, 288, 7),
            outputDataType = "FLOAT32",
        )
        assertTrue(valid is VisionContract.SpecValidationResult.Valid)

        val wrongInputCount = VisionContract.validateModelSpec(
            inputTensorCount = 2,
            inputShape = intArrayOf(1, 512, 288, 3),
            inputDataType = "FLOAT32",
            outputTensorCount = 1,
            outputShape = intArrayOf(1, 512, 288, 7),
            outputDataType = "FLOAT32",
        )
        assertTrue(wrongInputCount is VisionContract.SpecValidationResult.Invalid)
        assertEquals("input_tensor_count", (wrongInputCount as VisionContract.SpecValidationResult.Invalid).code)

        val wrongInputType = VisionContract.validateModelSpec(
            inputTensorCount = 1,
            inputShape = intArrayOf(1, 512, 288, 3),
            inputDataType = "UINT8",
            outputTensorCount = 1,
            outputShape = intArrayOf(1, 512, 288, 7),
            outputDataType = "FLOAT32",
        )
        assertEquals("input_dtype", (wrongInputType as VisionContract.SpecValidationResult.Invalid).code)

        val wrongInputShape = VisionContract.validateModelSpec(
            inputTensorCount = 1,
            inputShape = intArrayOf(1, 288, 512, 3),
            inputDataType = "FLOAT32",
            outputTensorCount = 1,
            outputShape = intArrayOf(1, 512, 288, 7),
            outputDataType = "FLOAT32",
        )
        assertEquals("input_shape", (wrongInputShape as VisionContract.SpecValidationResult.Invalid).code)

        val wrongOutputShape = VisionContract.validateModelSpec(
            inputTensorCount = 1,
            inputShape = intArrayOf(1, 512, 288, 3),
            inputDataType = "FLOAT32",
            outputTensorCount = 1,
            outputShape = intArrayOf(1, 512, 288, 19),
            outputDataType = "FLOAT32",
        )
        assertEquals("output_shape", (wrongOutputShape as VisionContract.SpecValidationResult.Invalid).code)
    }

    @Test
    fun computeUprightCropRect_bottomAligns16By9CropAfterRotation() {
        // Sensor 640x480 rotated 90 degrees -> upright 480x640 (portrait).
        // 480 * 9 / 16 = 270 height, bottom-aligned -> top = 640 - 270 = 370, left = 0.
        val portraitCrop = VisionFramePreprocessor.computeUprightCropRect(
            bufferWidth = 640,
            bufferHeight = 480,
            rotationDegrees = 90,
            targetWidth = 512,
            targetHeight = 288,
            strategy = VisionPreprocessingStrategy.LEGACY_CROP,
        )
        assertEquals(480, portraitCrop.uprightWidth)
        assertEquals(640, portraitCrop.uprightHeight)
        assertEquals(0, portraitCrop.left)
        assertEquals(370, portraitCrop.top)
        assertEquals(480, portraitCrop.width)
        assertEquals(270, portraitCrop.height)

        // Sensor 640x480 rotated 0 degrees -> upright 640x480 (landscape).
        // 640 * 9 / 16 = 360 height, bottom-aligned -> top = 480 - 360 = 120, left = 0.
        val landscapeCrop = VisionFramePreprocessor.computeUprightCropRect(
            bufferWidth = 640,
            bufferHeight = 480,
            rotationDegrees = 0,
            targetWidth = 512,
            targetHeight = 288,
            strategy = VisionPreprocessingStrategy.LEGACY_CROP,
        )
        assertEquals(640, landscapeCrop.uprightWidth)
        assertEquals(480, landscapeCrop.uprightHeight)
        assertEquals(0, landscapeCrop.left)
        assertEquals(120, landscapeCrop.top)
        assertEquals(640, landscapeCrop.width)
        assertEquals(360, landscapeCrop.height)
    }

    @Test
    fun computeFitCenterOverlayViewport_mapsBottomAlignedCropInsidePreview() {
        val crop = VisionFramePreprocessor.computeUprightCropRect(
            bufferWidth = 640,
            bufferHeight = 480,
            rotationDegrees = 90,
            targetWidth = 512,
            targetHeight = 288,
            strategy = VisionPreprocessingStrategy.LEGACY_CROP,
        )
        val viewport = VisionFramePreprocessor.computeFitCenterOverlayViewport(
            containerWidthPx = 960f,
            containerHeightPx = 1280f,
            cropRect = crop,
            previewUprightWidth = 480,
            previewUprightHeight = 640,
        )
        assertEquals(0f, viewport.previewLeftPx, 1e-3f)
        assertEquals(0f, viewport.previewTopPx, 1e-3f)
        assertEquals(960f, viewport.previewWidthPx, 1e-3f)
        assertEquals(1280f, viewport.previewHeightPx, 1e-3f)
        assertEquals(0f, viewport.cropLeftPx, 1e-3f)
        assertEquals(740f, viewport.cropTopPx, 1e-3f)
        assertEquals(960f, viewport.cropWidthPx, 1e-3f)
        assertEquals(540f, viewport.cropHeightPx, 1e-3f)
    }

    @Test
    fun preprocessYuv420_bt601LimitedBlackWhiteAndRgbChannelOrder() {
        val pre = VisionFramePreprocessor(targetWidth = 16, targetHeight = 9)
        val w = 32
        val h = 18
        val yBlack = ByteArray(w * h) { 16.toByte() }
        val uNeutral = ByteArray((w / 2) * (h / 2)) { 128.toByte() }
        val vNeutral = ByteArray((w / 2) * (h / 2)) { 128.toByte() }

        pre.preprocessYuv420Arrays(
            bufferWidth = w,
            bufferHeight = h,
            rotationDegrees = 0,
            yBytes = yBlack,
            yRowStride = w,
            yPixelStride = 1,
            uBytes = uNeutral,
            uRowStride = w / 2,
            uPixelStride = 1,
            vBytes = vNeutral,
            vRowStride = w / 2,
            vPixelStride = 1,
            colorRange = YuvColorRange.BT601_LIMITED,
        )
        for (value in pre.reusableFloatArray) {
            assertEquals(0f, value, 1e-4f)
        }

        val yWhite = ByteArray(w * h) { 235.toByte() }
        pre.preprocessYuv420Arrays(
            bufferWidth = w,
            bufferHeight = h,
            rotationDegrees = 0,
            yBytes = yWhite,
            yRowStride = w,
            yPixelStride = 1,
            uBytes = uNeutral,
            uRowStride = w / 2,
            uPixelStride = 1,
            vBytes = vNeutral,
            vRowStride = w / 2,
            vPixelStride = 1,
            colorRange = YuvColorRange.BT601_LIMITED,
        )
        for (value in pre.reusableFloatArray) {
            assertEquals(1f, value, 1e-4f)
        }

        // High V (>128), low U (<128) -> Red dominated (R > G and R > B), verifying RGB order (not BGR).
        val yMid = ByteArray(w * h) { 128.toByte() }
        val uLow = ByteArray((w / 2) * (h / 2)) { 90.toByte() }
        val vHigh = ByteArray((w / 2) * (h / 2)) { 220.toByte() }
        pre.preprocessYuv420Arrays(
            bufferWidth = w,
            bufferHeight = h,
            rotationDegrees = 0,
            yBytes = yMid,
            yRowStride = w,
            yPixelStride = 1,
            uBytes = uLow,
            uRowStride = w / 2,
            uPixelStride = 1,
            vBytes = vHigh,
            vRowStride = w / 2,
            vPixelStride = 1,
        )
        val r = pre.reusableFloatArray[0]
        val g = pre.reusableFloatArray[1]
        val b = pre.reusableFloatArray[2]
        assertTrue("Expected R ($r) > G ($g) and R ($r) > B ($b)", r > g && r > b)
    }

    @Test
    fun preprocessYuv420_respectsRotationsPaddedStridesAndRejectsShortPlanes() {
        val pre = VisionFramePreprocessor(targetWidth = 16, targetHeight = 9)
        val w = 32
        val h = 24
        val yRowStride = 48 // padded row stride > width
        val uvRowStride = 24
        val uvPixelStride = 2 // semi-planar NV12/NV21 style interleaved stride

        val yBytes = ByteArray(yRowStride * (h - 1) + w)
        for (row in 0 until h) {
            for (col in 0 until w) {
                yBytes[row * yRowStride + col] = (16 + ((row * 4 + col * 2) % 200)).toByte()
            }
        }
        val chromaH = h / 2
        val chromaW = w / 2
        val minUvSize = uvRowStride * (chromaH - 1) + (chromaW - 1) * uvPixelStride + 1
        val uBytes = ByteArray(minUvSize) { 128.toByte() }
        val vBytes = ByteArray(minUvSize) { 128.toByte() }

        for (rotation in listOf(0, 90, 180, 270)) {
            val crop = pre.preprocessYuv420(
                bufferWidth = w,
                bufferHeight = h,
                rotationDegrees = rotation,
                yBuffer = ByteBuffer.wrap(yBytes),
                yRowStride = yRowStride,
                yPixelStride = 1,
                uBuffer = ByteBuffer.wrap(uBytes),
                uRowStride = uvRowStride,
                uPixelStride = uvPixelStride,
                vBuffer = ByteBuffer.wrap(vBytes),
                vRowStride = uvRowStride,
                vPixelStride = uvPixelStride,
                resizeFilter = VisionResizeFilter.BILINEAR,
            )
            assertTrue(crop.width > 0 && crop.height > 0)
        }

        // Short Y buffer must throw IllegalArgumentException in both ByteBuffer and ByteArray entry points.
        val shortY = ByteArray(yBytes.size - 1)
        var bufferThrew = false
        try {
            pre.preprocessYuv420(
                bufferWidth = w,
                bufferHeight = h,
                rotationDegrees = 0,
                yBuffer = ByteBuffer.wrap(shortY),
                yRowStride = yRowStride,
                yPixelStride = 1,
                uBuffer = ByteBuffer.wrap(uBytes),
                uRowStride = uvRowStride,
                uPixelStride = uvPixelStride,
                vBuffer = ByteBuffer.wrap(vBytes),
                vRowStride = uvRowStride,
                vPixelStride = uvPixelStride,
            )
        } catch (_: IllegalArgumentException) {
            bufferThrew = true
        }
        assertTrue("Short ByteBuffer plane must be rejected", bufferThrew)

        var arrayThrew = false
        try {
            pre.preprocessYuv420Arrays(
                bufferWidth = w,
                bufferHeight = h,
                rotationDegrees = 0,
                yBytes = shortY,
                yRowStride = yRowStride,
                yPixelStride = 1,
                uBytes = uBytes,
                uRowStride = uvRowStride,
                uPixelStride = uvPixelStride,
                vBytes = vBytes,
                vRowStride = uvRowStride,
                vPixelStride = uvPixelStride,
            )
        } catch (_: IllegalArgumentException) {
            arrayThrew = true
        }
        assertTrue("Short ByteArray plane must be rejected", arrayThrew)

        // Invalid rotation must also throw.
        var invalidRotationThrew = false
        try {
            VisionFramePreprocessor.computeUprightCropRect(640, 480, 45)
        } catch (_: IllegalArgumentException) {
            invalidRotationThrew = true
        }
        assertTrue(invalidRotationThrew)
    }

    @Test
    fun decodeArgmaxAndMask_computesWalkableRatiosAndDominantCenterNonWalkableClass() {
        val w = 6
        val h = 4
        val classes = 7
        val probs = FloatArray(w * h * classes)
        // Left third (x=0,1): sidewalk (class 2, walkable)
        // Center third (x=2,3): vehicle (class 5, non-walkable)
        // Right third (x=4,5): road (class 1, walkable)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val chosenClass = when {
                    x < 2 -> 2
                    x < 4 -> 5
                    else -> 1
                }
                val base = (y * w + x) * classes
                for (c in 0 until classes) {
                    probs[base + c] = if (c == chosenClass) 0.9f else 0.01f
                }
            }
        }
        val mask = ByteArray(w * h)
        val argb = IntArray(w * h)
        val summary = LiteRtVisionSegmenter.decodeArgmaxAndMask(
            probabilities = probs,
            outClassMask = mask,
            outOverlayArgb = argb,
            width = w,
            height = h,
            numClasses = classes,
            roiStartRow = 2,
        )
        assertEquals(8, summary.classPixelCounts[2])
        assertEquals(8, summary.classPixelCounts[5])
        assertEquals(8, summary.classPixelCounts[1])
        assertEquals(1.0f, summary.roiLeftWalkableRatio, 1e-4f)
        assertEquals(0.0f, summary.roiCenterWalkableRatio, 1e-4f)
        assertEquals(0.0f, summary.roiRightWalkableRatio, 1e-4f)
        assertEquals(5, summary.dominantNonWalkableCenterClassId)
    }

    @Test
    fun timingTracker_separatesWarmupAndSteadyWallClockFpsAndMissingModelLabels() {
        val tracker = VisionTimingTracker(warmupDurationMs = 5_000L, ringCapacity = 4)
        val baseNanos = 1_000_000_000L
        val crop = VisionFramePreprocessor.computeUprightCropRect(640, 480, 90)

        // Record 2 warmup frames at t=1s and t=2s.
        tracker.recordAnalyzerFrame(
            analyzerStartElapsedNanos = baseNanos,
            analyzerEndElapsedNanos = baseNanos + 4_000_000L,
            preprocessNanos = 3_500_000L,
            invokeNanos = null,
            postprocessNanos = null,
            modelLoaded = false,
            frameWidth = 640,
            frameHeight = 480,
            frameRotationDegrees = 90,
            frameCropRect = crop,
            sensorTimestampSource = SensorTimestampSource.UNKNOWN_OR_UNVERIFIED,
        )
        tracker.recordAnalyzerFrame(
            analyzerStartElapsedNanos = baseNanos + 1_000_000_000L,
            analyzerEndElapsedNanos = baseNanos + 1_004_000_000L,
            preprocessNanos = 3_600_000L,
            invokeNanos = null,
            postprocessNanos = null,
            modelLoaded = false,
            frameWidth = 640,
            frameHeight = 480,
            frameRotationDegrees = 90,
            frameCropRect = crop,
            sensorTimestampSource = SensorTimestampSource.UNKNOWN_OR_UNVERIFIED,
        )

        // Record 6 steady frames starting at t=6s (5s after first frame), ringCapacity=4.
        for (i in 0 until 6) {
            val start = baseNanos + 5_000_000_000L + i * 1_000_000_000L
            val end = start + 5_000_000L
            tracker.recordAnalyzerFrame(
                analyzerStartElapsedNanos = start,
                analyzerEndElapsedNanos = end,
                preprocessNanos = 4_000_000L,
                invokeNanos = null,
                postprocessNanos = null,
                modelLoaded = false,
                frameWidth = 640,
                frameHeight = 480,
                frameRotationDegrees = 90,
                frameCropRect = crop,
                sensorTimestampSource = SensorTimestampSource.REALTIME_VERIFIED,
            )
            tracker.recordPublication(
                analyzerStartElapsedNanos = start,
                analyzerEndElapsedNanos = end,
                publishCompletedElapsedNanos = end + 500_000L,
                imageTimestampNanos = start - 10_000_000L,
                sensorTimestampSource = SensorTimestampSource.REALTIME_VERIFIED,
            )
        }

        val snapAtLastFrame = tracker.snapshot(
            mode = VisionPipelineMode.PREPROCESS_OR_INFERENCE,
            modelLoaded = false,
            backendLabel = "N/A",
            nowElapsedNanos = baseNanos + 10_005_000_000L,
        )
        assertTrue(snapAtLastFrame.warmupCompleted)
        assertEquals(2, snapAtLastFrame.warmupFrames)
        assertEquals(6, snapAtLastFrame.steadyFrames)
        // Ring capacity is 4, while cumulative count is 6.
        assertNotNull(snapAtLastFrame.preprocessMs)
        assertEquals(4, snapAtLastFrame.preprocessMs?.sampleCount)
        assertEquals(6, snapAtLastFrame.preprocessMs?.cumulativeCount)
        // Missing model stages must format as N/A, never 0.00 ms.
        assertEquals("N/A (모델 미적용)", snapAtLastFrame.formatModelInvokeLabel())
        assertEquals("N/A (모델 미적용)", snapAtLastFrame.formatPostprocessLabel())
        // Verified REALTIME timestamp produces valid camera-to-result age (~15.5 ms).
        assertNotNull(snapAtLastFrame.cameraToResultAgeMs)
        assertEquals(15.5, snapAtLastFrame.cameraToResultAgeMs!!.p50Ms, 0.2)

        // If 10 more seconds pass without frames, wall-clock FPS decays cleanly.
        val snapAfterStall = tracker.snapshot(
            mode = VisionPipelineMode.PREPROCESS_OR_INFERENCE,
            modelLoaded = false,
            backendLabel = "N/A",
            nowElapsedNanos = baseNanos + 20_005_000_000L,
        )
        assertTrue(snapAfterStall.steadyProcessedFps < snapAtLastFrame.steadyProcessedFps)
    }

    @Test
    fun copyBoundedWithSha256_enforcesSizeBoundsAndComputesDigest() {
        val tempDir = Files.createTempDirectory("aria-vision-test").toFile()
        try {
            val dest = File(tempDir, "test.tflite")
            val emptyResult = VisionModelStore.copyBoundedWithSha256(
                input = ByteArrayInputStream(ByteArray(0)),
                destination = dest,
                maxBytes = 1024L,
            )
            assertTrue(emptyResult is VisionModelStore.BoundedCopyOutcome.Failure)
            assertEquals("empty_file", (emptyResult as VisionModelStore.BoundedCopyOutcome.Failure).code)
            assertFalse(dest.exists())

            val oversizedResult = VisionModelStore.copyBoundedWithSha256(
                input = ByteArrayInputStream(ByteArray(2048) { 1 }),
                destination = dest,
                maxBytes = 1024L,
            )
            assertTrue(oversizedResult is VisionModelStore.BoundedCopyOutcome.Failure)
            assertEquals("file_too_large", (oversizedResult as VisionModelStore.BoundedCopyOutcome.Failure).code)

            val payload = "aria-offline-vision-contract".toByteArray(Charsets.UTF_8)
            val okResult = VisionModelStore.copyBoundedWithSha256(
                input = ByteArrayInputStream(payload),
                destination = dest,
                maxBytes = 1024L,
            )
            assertTrue(okResult is VisionModelStore.BoundedCopyOutcome.Success)
            val success = okResult as VisionModelStore.BoundedCopyOutcome.Success
            assertEquals(payload.size.toLong(), success.bytesWritten)
            assertEquals(64, success.sha256Hex.length)
            assertTrue(dest.isFile)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun pendingCameraStartGate_blocksPermissionGrantAfterLeavingDebugOrBeforeResume() {
        val gate = PendingCameraStartGate()

        // Cannot issue request from Home or Settings root.
        assertNull(
            gate.issueRequest(
                destination = DebugDestination.None,
                surfaceProvider = null,
                mode = VisionPipelineMode.PREPROCESS_OR_INFERENCE,
                targetRotation = Surface.ROTATION_0,
            )
        )

        // Issue request on VisionDiagnostics, then navigate to Home (selectedTab = 0) before grant.
        gate.issueRequest(
            destination = DebugDestination.VisionDiagnostics,
            surfaceProvider = null,
            mode = VisionPipelineMode.PREPROCESS_OR_INFERENCE,
            targetRotation = Surface.ROTATION_0,
        )
        val decisionOnHome = gate.onPermissionCallback(
            granted = true,
            selectedTab = 0,
            currentDestination = DebugDestination.None,
            isLifecycleResumed = true,
        )
        assertEquals(PendingCameraStartGate.PermissionDecision.Ignored, decisionOnHome)
        assertNull(gate.pendingRequest)

        // Issue request on PresentationDemo, then invalidate via Back/background before callback.
        gate.issueRequest(
            destination = DebugDestination.PresentationDemo,
            surfaceProvider = null,
            mode = VisionPipelineMode.PREPROCESS_OR_INFERENCE,
            targetRotation = Surface.ROTATION_0,
        )
        gate.invalidate()
        val decisionAfterInvalidate = gate.onPermissionCallback(
            granted = true,
            selectedTab = 1,
            currentDestination = DebugDestination.PresentationDemo,
            isLifecycleResumed = true,
        )
        assertEquals(PendingCameraStartGate.PermissionDecision.Ignored, decisionAfterInvalidate)

        // Issue request on VisionDiagnostics; callback arrives in STARTED state (not yet RESUMED),
        // then consumes on ON_RESUME when still on Settings > VisionDiagnostics.
        gate.issueRequest(
            destination = DebugDestination.VisionDiagnostics,
            surfaceProvider = null,
            mode = VisionPipelineMode.PREPROCESS_OR_INFERENCE,
            targetRotation = Surface.ROTATION_90,
        )
        val deferred = gate.onPermissionCallback(
            granted = true,
            selectedTab = 1,
            currentDestination = DebugDestination.VisionDiagnostics,
            isLifecycleResumed = false,
        )
        assertEquals(PendingCameraStartGate.PermissionDecision.DeferredUntilResumed, deferred)
        val resumedRequest = gate.consumeGrantedOnResumeIfEligible(
            selectedTab = 1,
            currentDestination = DebugDestination.VisionDiagnostics,
            isLifecycleResumed = true,
        )
        assertNotNull(resumedRequest)
        assertEquals(DebugDestination.VisionDiagnostics, resumedRequest?.destination)
        assertEquals(Surface.ROTATION_90, resumedRequest?.targetRotation)
        assertNull(gate.pendingRequest)
    }

    @Test
    fun computeUprightCropRect_fullFrameStrategies_preservesBoundsAndCalculatesLetterboxPadding() {
        // 1. Portrait 640x480 at 90 deg -> upright is 480x640.
        // Under FULL_FRAME_ROTATED_LANDSCAPE with 512x288 target:
        // Rotates 90 deg clockwise into landscape model-oriented space: moWidth=640, moHeight=480.
        // Scale = min(512/640, 288/480) = min(0.8, 0.6) = 0.6.
        // contentWidth = 640 * 0.6 = 384, contentHeight = 480 * 0.6 = 288.
        // padLeft = (512 - 384) / 2 = 64, padTop = 0.
        val portraitFullFrame = VisionFramePreprocessor.computeUprightCropRect(
            bufferWidth = 640,
            bufferHeight = 480,
            rotationDegrees = 90,
            targetWidth = 512,
            targetHeight = 288,
            strategy = VisionPreprocessingStrategy.FULL_FRAME_ROTATED_LANDSCAPE,
        )
        assertEquals(480, portraitFullFrame.uprightWidth)
        assertEquals(640, portraitFullFrame.uprightHeight)
        assertEquals(0, portraitFullFrame.left)
        assertEquals(0, portraitFullFrame.top)
        assertEquals(480, portraitFullFrame.width)
        assertEquals(640, portraitFullFrame.height)
        assertTrue(portraitFullFrame.isFullFrame)
        assertTrue(portraitFullFrame.isRotatedClockwise)
        assertEquals(64, portraitFullFrame.contentLeft)
        assertEquals(0, portraitFullFrame.contentTop)
        assertEquals(384, portraitFullFrame.contentWidth)
        assertEquals(288, portraitFullFrame.contentHeight)

        // 2. Landscape 640x480 at 0 deg -> upright is 640x480.
        // Under FULL_FRAME_ROTATED_LANDSCAPE with 512x288 target:
        val landscapeFullFrame = VisionFramePreprocessor.computeUprightCropRect(
            bufferWidth = 640,
            bufferHeight = 480,
            rotationDegrees = 0,
            targetWidth = 512,
            targetHeight = 288,
            strategy = VisionPreprocessingStrategy.FULL_FRAME_ROTATED_LANDSCAPE,
        )
        assertEquals(640, landscapeFullFrame.uprightWidth)
        assertEquals(480, landscapeFullFrame.uprightHeight)
        assertEquals(0, landscapeFullFrame.left)
        assertEquals(0, landscapeFullFrame.top)
        assertEquals(640, landscapeFullFrame.width)
        assertEquals(480, landscapeFullFrame.height)
        assertTrue(landscapeFullFrame.isFullFrame)
        assertFalse(landscapeFullFrame.isRotatedClockwise)
        assertEquals(64, landscapeFullFrame.contentLeft)
        assertEquals(0, landscapeFullFrame.contentTop)
        assertEquals(384, landscapeFullFrame.contentWidth)
        assertEquals(288, landscapeFullFrame.contentHeight)

        // 3. Exact 16:9 1280x720 at 0 deg -> upright is 1280x720 with 512x288 target.
        val exact16By9 = VisionFramePreprocessor.computeUprightCropRect(
            bufferWidth = 1280,
            bufferHeight = 720,
            rotationDegrees = 0,
            targetWidth = 512,
            targetHeight = 288,
            strategy = VisionPreprocessingStrategy.FULL_FRAME_ROTATED_LANDSCAPE,
        )
        assertEquals(0, exact16By9.contentLeft)
        assertEquals(0, exact16By9.contentTop)
        assertEquals(512, exact16By9.contentWidth)
        assertEquals(288, exact16By9.contentHeight)

        // 4. Portrait 480x640 under native portrait FULL_FRAME_UPRIGHT_LETTERBOX (288x512 target):
        // Upright 480x640 (3:4). Target is 288x512 (9:16).
        // scale = min(288/480, 512/640) = min(0.6, 0.8) = 0.6.
        // contentWidth = 480 * 0.6 = 288, contentHeight = 640 * 0.6 = 384.
        // padLeft = (288 - 288) / 2 = 0, padTop = (512 - 384) / 2 = 64.
        val uprightLetterbox = VisionFramePreprocessor.computeUprightCropRect(
            bufferWidth = 640,
            bufferHeight = 480,
            rotationDegrees = 90,
            targetWidth = VisionContract.MODEL_INPUT_WIDTH,
            targetHeight = VisionContract.MODEL_INPUT_HEIGHT,
            strategy = VisionPreprocessingStrategy.FULL_FRAME_UPRIGHT_LETTERBOX,
        )
        assertFalse(uprightLetterbox.isRotatedClockwise)
        assertTrue(uprightLetterbox.isFullFrame)
        assertEquals(0, uprightLetterbox.contentLeft)
        assertEquals(64, uprightLetterbox.contentTop)
        assertEquals(288, uprightLetterbox.contentWidth)
        assertEquals(384, uprightLetterbox.contentHeight)

        // 5. Native 9:16 portrait camera (720x1280 at 90 deg -> upright 720x1280):
        // scale = min(288/720, 512/1280) = 0.4.
        // contentWidth = 288, contentHeight = 512 -> Zero letterbox padding.
        val full9By16 = VisionFramePreprocessor.computeUprightCropRect(
            bufferWidth = 1280,
            bufferHeight = 720,
            rotationDegrees = 90,
            targetWidth = VisionContract.MODEL_INPUT_WIDTH,
            targetHeight = VisionContract.MODEL_INPUT_HEIGHT,
            strategy = VisionPreprocessingStrategy.FULL_FRAME_UPRIGHT_LETTERBOX,
        )
        assertFalse(full9By16.isRotatedClockwise)
        assertEquals(0, full9By16.contentLeft)
        assertEquals(0, full9By16.contentTop)
        assertEquals(288, full9By16.contentWidth)
        assertEquals(512, full9By16.contentHeight)
    }

    @Test
    fun mapUprightNormToModelTensor_and_inverse_pixelCentersAndPaddingExclusion() {
        val portraitCrop = VisionFramePreprocessor.computeUprightCropRect(
            bufferWidth = 640,
            bufferHeight = 480,
            rotationDegrees = 90,
            targetWidth = 512,
            targetHeight = 288,
            strategy = VisionPreprocessingStrategy.FULL_FRAME_ROTATED_LANDSCAPE,
        )
        // Portrait 480x640 rotated 90 deg clockwise into 384x288 content centered at x=64 in 512x288:
        // Upright (u=0, v=0) [top-left] -> moX = 1-v = 1, moY = u = 0 -> mx = 64 + 1.0 * 384 = 448, my = 0 [top-right of content]
        val (tlX, tlY) = portraitCrop.mapUprightNormToModelTensor(0f, 0f, 512, 288)
        assertEquals(448f, tlX, 1e-4f)
        assertEquals(0f, tlY, 1e-4f)

        // Upright (u=1, v=0) [top-right] -> moX = 1-0 = 1, moY = 1 -> mx = 448, my = 288 [bottom-right of content]
        val (trX, trY) = portraitCrop.mapUprightNormToModelTensor(1f, 0f, 512, 288)
        assertEquals(448f, trX, 1e-4f)
        assertEquals(288f, trY, 1e-4f)

        // Upright (u=0, v=1) [bottom-left] -> moX = 1-1 = 0, moY = 0 -> mx = 64, my = 0 [top-left of content]
        val (blX, blY) = portraitCrop.mapUprightNormToModelTensor(0f, 1f, 512, 288)
        assertEquals(64f, blX, 1e-4f)
        assertEquals(0f, blY, 1e-4f)

        // Upright (u=1, v=1) [bottom-right] -> moX = 0, moY = 1 -> mx = 64, my = 288 [bottom-left of content]
        val (brX, brY) = portraitCrop.mapUprightNormToModelTensor(1f, 1f, 512, 288)
        assertEquals(64f, brX, 1e-4f)
        assertEquals(288f, brY, 1e-4f)

        // Upright Center (u=0.5, v=0.5) -> mx = 64 + 0.5 * 384 = 256, my = 144
        val (cenX, cenY) = portraitCrop.mapUprightNormToModelTensor(0.5f, 0.5f, 512, 288)
        assertEquals(256f, cenX, 1e-4f)
        assertEquals(144f, cenY, 1e-4f)

        // Inverse mapping of center returns exact normalized upright center (0.5, 0.5)
        val invCenter = portraitCrop.mapModelTensorToUprightNorm(cenX, cenY, 512, 288)
        assertNotNull(invCenter)
        assertEquals(0.5f, invCenter!!.first, 1e-4f)
        assertEquals(0.5f, invCenter.second, 1e-4f)

        // Inverse mapping of padding pixels outside active content returns null (padding excluded)
        assertNull("Left letterbox padding (x=10) must return null", portraitCrop.mapModelTensorToUprightNorm(10f, 144f, 512, 288))
        assertNull("Right letterbox padding (x=480) must return null", portraitCrop.mapModelTensorToUprightNorm(480f, 144f, 512, 288))
    }

    @Test
    fun preprocessYuv420_fullFrameRotatedLandscape_zeroesPaddingAndLetterboxesContent() {
        val pre = VisionFramePreprocessor(
            targetWidth = 16,
            targetHeight = 9,
            defaultStrategy = VisionPreprocessingStrategy.FULL_FRAME_ROTATED_LANDSCAPE,
        )
        // Upright 12x16 (portrait 3:4).
        // Rotated 90 deg -> 16x12 (4:3).
        // target is 16x9 (16:9).
        // scale = min(16/16, 9/12) = 0.75.
        // contentWidth = 16 * 0.75 = 12, contentHeight = 12 * 0.75 = 9.
        // padLeft = (16 - 12) / 2 = 2.
        // Columns 0..1 and 14..15 are padding (total 4 columns of padding).
        val w = 16
        val h = 12
        val yAllWhite = ByteArray(w * h) { 235.toByte() }
        val uvNeutral = ByteArray((w / 2) * (h / 2)) { 128.toByte() }

        val crop = pre.preprocessYuv420Arrays(
            bufferWidth = w,
            bufferHeight = h,
            rotationDegrees = 90,
            yBytes = yAllWhite,
            yRowStride = w,
            yPixelStride = 1,
            uBytes = uvNeutral,
            uRowStride = w / 2,
            uPixelStride = 1,
            vBytes = uvNeutral,
            vRowStride = w / 2,
            vPixelStride = 1,
            colorRange = YuvColorRange.BT601_LIMITED,
            strategy = VisionPreprocessingStrategy.FULL_FRAME_ROTATED_LANDSCAPE,
        )
        assertEquals(2, crop.contentLeft)
        assertEquals(12, crop.contentWidth)
        assertEquals(9, crop.contentHeight)

        // Check reusableFloatArray:
        // Columns 0..1 must be strictly 0f (padding zeroed out).
        // Columns 2..13 must be 1.0f (white content).
        // Columns 14..15 must be strictly 0f (padding zeroed out).
        for (y in 0 until 9) {
            for (x in 0 until 16) {
                val offset = (y * 16 + x) * 3
                val r = pre.reusableFloatArray[offset]
                val g = pre.reusableFloatArray[offset + 1]
                val b = pre.reusableFloatArray[offset + 2]
                if (x < 2 || x >= 14) {
                    assertEquals("Padding at ($x, $y) must be 0f", 0f, r, 1e-4f)
                    assertEquals("Padding at ($x, $y) must be 0f", 0f, g, 1e-4f)
                    assertEquals("Padding at ($x, $y) must be 0f", 0f, b, 1e-4f)
                } else {
                    assertEquals("Content at ($x, $y) must be 1.0f", 1f, r, 1e-4f)
                    assertEquals("Content at ($x, $y) must be 1.0f", 1f, g, 1e-4f)
                    assertEquals("Content at ($x, $y) must be 1.0f", 1f, b, 1e-4f)
                }
            }
        }
    }

    @Test
    fun decodeArgmaxAndMask_withTransform_asymmetricFixturesAndPaddingExclusion() {
        val w = VisionContract.MODEL_WIDTH // 512
        val h = VisionContract.MODEL_HEIGHT // 288
        val classes = VisionContract.MODEL_CLASSES // 7
        val probs = FloatArray(w * h * classes)

        // Set up portrait 480x640 at 90 deg:
        // content is x in 64..447, y in 0..287.
        // Padding is x in 0..63 and x in 448..511.
        val portraitCrop = VisionFramePreprocessor.computeUprightCropRect(
            bufferWidth = 640,
            bufferHeight = 480,
            rotationDegrees = 90,
            targetWidth = 512,
            targetHeight = 288,
            strategy = VisionPreprocessingStrategy.FULL_FRAME_ROTATED_LANDSCAPE,
        )

        // 1. Poison the letterbox padding region with Class 5 (vehicle: non-walkable obstacle)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x < 64 || x >= 448) {
                    val base = (y * w + x) * classes
                    probs[base + 5] = 0.99f // vehicle in padding
                }
            }
        }

        // 2. In active content (which was rotated 90 deg clockwise):
        // Upright bottom (ground) maps to Model Left (x in 64..255).
        // Upright top maps to Model Right (x in 256..447).
        // Upright left maps to Model Top (y in 0..95).
        // Upright center maps to Model Center (y in 96..191).
        // Upright right maps to Model Bottom (y in 192..287).
        //
        // In the upright bottom region (ground):
        // - Upright bottom-center: Class 4 (person: non-walkable obstacle)
        // - Upright bottom-left: Class 2 (sidewalk: walkable)
        // - Upright bottom-right: Class 2 (sidewalk: walkable)
        // Upright top region: Class 0 (other)
        for (y in 0 until h) {
            for (x in 64 until 448) {
                val base = (y * w + x) * classes
                val isModelLeft = x < 256 // upright bottom
                val chosenClass = when {
                    isModelLeft && (y in 96..191) -> 4 // upright bottom-center: person
                    isModelLeft -> 2 // upright bottom-left and bottom-right: sidewalk
                    else -> 0 // upright top: background
                }
                for (c in 0 until classes) {
                    probs[base + c] = if (c == chosenClass) 0.95f else 0.01f
                }
            }
        }

        val mask = ByteArray(w * h)
        val argb = IntArray(w * h)
        val summary = LiteRtVisionSegmenter.decodeArgmaxAndMask(
            probabilities = probs,
            outClassMask = mask,
            outOverlayArgb = argb,
            width = w,
            height = h,
            numClasses = classes,
            roiStartRow = VisionContract.ROI_START_ROW,
            transform = portraitCrop,
            modelWidth = w,
            modelHeight = h,
        )

        // A. Verify padding exclusion:
        // Despite padding having Class 5 (vehicle), the dominant center non-walkable obstacle is Person (Class 4), NOT vehicle!
        assertEquals("Dominant center non-walkable class must be person (4), not vehicle (5) in padding", 4, summary.dominantNonWalkableCenterClassId)

        // B. Verify upright corridor walkable ratios:
        // Left corridor has sidewalk (Class 2) -> high walkable ratio (> 0.8)
        assertTrue("Upright left corridor must be walkable sidewalk", summary.roiLeftWalkableRatio > 0.8f)
        // Right corridor has sidewalk (Class 2) -> high walkable ratio (> 0.8)
        assertTrue("Upright right corridor must be walkable sidewalk", summary.roiRightWalkableRatio > 0.8f)
        // Center corridor has person (Class 4) -> low walkable ratio (< 0.2)
        assertTrue("Upright center corridor must be blocked by person", summary.roiCenterWalkableRatio < 0.2f)

        // C. Feed inverse-mapped upright mask to HazardDetector:
        val detector = HazardDetector()
        val motionCenter = MotionState(isCalibrated = true, activeCorridor = CorridorDirection.CENTER, confidence = 1f, degradedReason = null)
        val motionLeft = MotionState(isCalibrated = true, activeCorridor = CorridorDirection.LEFT, confidence = 1f, degradedReason = null)
        val motionRight = MotionState(isCalibrated = true, activeCorridor = CorridorDirection.RIGHT, confidence = 1f, degradedReason = null)

        val assessCenter = detector.assessHazard(mask, motionCenter, 1_000_000_000L)
        val assessLeft = detector.assessHazard(mask, motionLeft, 1_000_000_000L)
        val assessRight = detector.assessHazard(mask, motionRight, 1_000_000_000L)

        // Center must be blocked / near hazard due to Person (Class 4)
        assertTrue("Center path must detect hazard", assessCenter.isAlertCandidate)
        assertEquals(4, assessCenter.dominantNearClass)

        // Left and Right corridors must be clear unwarned
        assertEquals(HazardLevel.CLEAR_UNWARNED, assessLeft.level)
        assertFalse(assessLeft.isAlertCandidate)
        assertEquals(HazardLevel.CLEAR_UNWARNED, assessRight.level)
        assertFalse(assessRight.isAlertCandidate)
    }

    @Test
    fun asymmetricFixtures_allFourRotations_consistentlyIsolateUprightGroundObstacle() {
        // Test all four rotations (0, 90, 180, 270) with FULL_FRAME_ROTATED_LANDSCAPE
        for (rotation in listOf(0, 90, 180, 270)) {
            val crop = VisionFramePreprocessor.computeUprightCropRect(
                bufferWidth = 640,
                bufferHeight = 480,
                rotationDegrees = rotation,
                targetWidth = 512,
                targetHeight = 288,
                strategy = VisionPreprocessingStrategy.FULL_FRAME_ROTATED_LANDSCAPE,
            )
            val w = VisionContract.MODEL_WIDTH
            val h = VisionContract.MODEL_HEIGHT
            val classes = VisionContract.MODEL_CLASSES
            val probs = FloatArray(w * h * classes)

            // Fill all active content with sidewalk (Class 2), and place a person (Class 4) in the bottom-center of the upright scene
            for (uy in 0 until h) {
                val vNorm = (uy + 0.5f) / h
                val isBottomHalf = uy >= 158
                for (ux in 0 until w) {
                    val uNorm = (ux + 0.5f) / w
                    val isCenterThird = ux in 171..341
                    val uprightClass = if (isBottomHalf && isCenterThird) 4 else 2 // person in bottom-center, else sidewalk

                    val (mx, my) = crop.mapUprightNormToModelTensor(uNorm, vNorm, w, h)
                    val mCol = mx.toInt().coerceIn(crop.contentLeft, crop.contentLeft + crop.contentWidth - 1)
                    val mRow = my.toInt().coerceIn(crop.contentTop, crop.contentTop + crop.contentHeight - 1)
                    val offset = (mRow * w + mCol) * classes
                    probs[offset + uprightClass] = 0.99f
                }
            }

            val mask = ByteArray(w * h)
            val summary = LiteRtVisionSegmenter.decodeArgmaxAndMask(
                probabilities = probs,
                outClassMask = mask,
                width = w,
                height = h,
                roiStartRow = VisionContract.ROI_START_ROW,
                transform = crop,
                modelWidth = w,
                modelHeight = h,
            )

            assertEquals("Rotation $rotation must identify Person (4) in center", 4, summary.dominantNonWalkableCenterClassId)
            assertTrue("Rotation $rotation left corridor must be walkable sidewalk", summary.roiLeftWalkableRatio > 0.8f)
            assertTrue("Rotation $rotation right corridor must be walkable sidewalk", summary.roiRightWalkableRatio > 0.8f)
            assertTrue("Rotation $rotation center corridor must be blocked by person", summary.roiCenterWalkableRatio < 0.3f)
        }
    }

    @Test
    fun defaultPreprocessingStrategy_isFullFrameUprightLetterbox_andPrimitiveAccessorsMatchPair() {
        // 1. Verify default strategy across components is FULL_FRAME_UPRIGHT_LETTERBOX
        val preprocessor = VisionFramePreprocessor()
        assertEquals(VisionPreprocessingStrategy.FULL_FRAME_UPRIGHT_LETTERBOX, preprocessor.defaultStrategy)
        assertEquals(VisionContract.MODEL_INPUT_WIDTH, preprocessor.targetWidth)
        assertEquals(VisionContract.MODEL_INPUT_HEIGHT, preprocessor.targetHeight)

        val uiState = VisionUiState()
        assertEquals(VisionPreprocessingStrategy.FULL_FRAME_UPRIGHT_LETTERBOX, uiState.preprocessingStrategy)

        val cropDefault = VisionFramePreprocessor.computeUprightCropRect(
            bufferWidth = 640,
            bufferHeight = 480,
            rotationDegrees = 90,
        )
        assertEquals(VisionPreprocessingStrategy.FULL_FRAME_UPRIGHT_LETTERBOX, cropDefault.strategy)
        assertEquals(VisionContract.MODEL_INPUT_WIDTH, cropDefault.contentWidth)
        assertEquals(384, cropDefault.contentHeight)
        assertEquals(0, cropDefault.contentLeft)
        assertEquals(64, cropDefault.contentTop)

        // 2. Verify primitive accessors mapUprightNormToModelTensorX/Y match mapUprightNormToModelTensor across all strategies
        for (strategy in VisionPreprocessingStrategy.values()) {
            val crop = VisionFramePreprocessor.computeUprightCropRect(
                bufferWidth = 640,
                bufferHeight = 480,
                rotationDegrees = 90,
                strategy = strategy,
            )
            for (u in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                for (v in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                    val (pairX, pairY) = crop.mapUprightNormToModelTensor(u, v)
                    val primX = crop.mapUprightNormToModelTensorX(u, v)
                    val primY = crop.mapUprightNormToModelTensorY(u, v)
                    assertEquals("X mismatch for strategy $strategy at ($u, $v)", pairX, primX, 1e-4f)
                    assertEquals("Y mismatch for strategy $strategy at ($u, $v)", pairY, primY, 1e-4f)
                }
            }
        }
    }

    @Test
    fun decodeArgmaxAndMask_withNullTransform_usesFullNativePortraitBounds_andMapsAsymmetricFeaturesCorrectly() {
        val mw = VisionContract.MODEL_INPUT_WIDTH // 288
        val mh = VisionContract.MODEL_INPUT_HEIGHT // 512
        val classes = VisionContract.MODEL_CLASSES // 7
        val probs = FloatArray(mw * mh * classes)

        // Fill active area with sidewalk (Class 2), and place a person (Class 4) in the bottom-center of the upright camera view.
        // In full upright bounds:
        // Center corridor: ux in 171..341 of 512 -> unorm in [0.334, 0.666] -> mCol in [96, 191] of 288.
        // Bottom ROI: uy in 158..287 of 288 -> vnorm in [0.55, 1.0] -> mRow in [281, 511] of 512.
        for (my in 0 until mh) {
            val isBottom = my >= (mh * 0.55f).toInt()
            for (mx in 0 until mw) {
                val isCenter = mx in (mw / 3) until (2 * mw / 3)
                val chosenClass = if (isBottom && isCenter) 4 else 2
                val offset = (my * mw + mx) * classes
                probs[offset + chosenClass] = 0.99f
            }
        }

        val mask = ByteArray(VisionContract.MODEL_WIDTH * VisionContract.MODEL_HEIGHT)
        val summary = LiteRtVisionSegmenter.decodeArgmaxAndMask(
            probabilities = probs,
            outClassMask = mask,
            transform = null,
            modelWidth = mw,
            modelHeight = mh,
        )

        assertEquals("Must identify Person (4) in center corridor with null transform", 4, summary.dominantNonWalkableCenterClassId)
        assertTrue("Left corridor must be walkable", summary.roiLeftWalkableRatio > 0.8f)
        assertTrue("Right corridor must be walkable", summary.roiRightWalkableRatio > 0.8f)
        assertTrue("Center corridor must be blocked by Person", summary.roiCenterWalkableRatio < 0.2f)
    }

    @Test
    fun decodeArgmaxAndMask_withNativePortraitLetterbox_correctlyStripsPaddingAndPreservesCorners() {
        val mw = VisionContract.MODEL_INPUT_WIDTH // 288
        val mh = VisionContract.MODEL_INPUT_HEIGHT // 512
        val classes = VisionContract.MODEL_CLASSES // 7
        val probs = FloatArray(mw * mh * classes)

        // 640x480 at 90 deg -> content is 288x384, top=64, left=0.
        val crop = VisionFramePreprocessor.computeUprightCropRect(
            bufferWidth = 640,
            bufferHeight = 480,
            rotationDegrees = 90,
            targetWidth = mw,
            targetHeight = mh,
            strategy = VisionPreprocessingStrategy.FULL_FRAME_UPRIGHT_LETTERBOX,
        )
        assertEquals(0, crop.contentLeft)
        assertEquals(64, crop.contentTop)
        assertEquals(288, crop.contentWidth)
        assertEquals(384, crop.contentHeight)

        // Fill padding rows (my < 64 or my >= 448) with Vehicle (Class 5) to verify padding exclusion
        for (my in 0 until mh) {
            val isPadding = my < crop.contentTop || my >= (crop.contentTop + crop.contentHeight)
            for (mx in 0 until mw) {
                val offset = (my * mw + mx) * classes
                if (isPadding) {
                    probs[offset + 5] = 0.99f // vehicle in padding
                } else {
                    probs[offset + 2] = 0.99f // sidewalk in active content
                }
            }
        }

        // Place distinct corner markers in the 4 active corners of content:
        // Top-Left (0, 64): Class 1 (road)
        probs[(64 * mw + 0) * classes + 1] = 1.0f
        probs[(64 * mw + 0) * classes + 2] = 0.0f
        // Top-Right (287, 64): Class 6 (pole_sign)
        probs[(64 * mw + 287) * classes + 6] = 1.0f
        probs[(64 * mw + 287) * classes + 2] = 0.0f
        // Bottom-Left (0, 447): Class 3 (crosswalk)
        probs[(447 * mw + 0) * classes + 3] = 1.0f
        probs[(447 * mw + 0) * classes + 2] = 0.0f
        // Bottom-Right (287, 447): Class 4 (person)
        probs[(447 * mw + 287) * classes + 4] = 1.0f
        probs[(447 * mw + 287) * classes + 2] = 0.0f

        val mask = ByteArray(VisionContract.MODEL_WIDTH * VisionContract.MODEL_HEIGHT)
        val summary = LiteRtVisionSegmenter.decodeArgmaxAndMask(
            probabilities = probs,
            outClassMask = mask,
            transform = crop,
            modelWidth = mw,
            modelHeight = mh,
        )

        // Verify letterbox padding (vehicle class 5) was completely stripped
        assertEquals("Vehicle in letterbox padding must be excluded from summary", 0, summary.classPixelCounts[5])

        // Verify 4 corners in canonical 512x288 output mask match active corners
        val outW = VisionContract.MODEL_WIDTH
        val outH = VisionContract.MODEL_HEIGHT
        assertEquals("Top-Left corner must be Road (1)", 1, mask[0 * outW + 0].toInt())
        assertEquals("Top-Right corner must be Pole/Sign (6)", 6, mask[0 * outW + (outW - 1)].toInt())
        assertEquals("Bottom-Left corner must be Crosswalk (3)", 3, mask[(outH - 1) * outW + 0].toInt())
        assertEquals("Bottom-Right corner must be Person (4)", 4, mask[(outH - 1) * outW + (outW - 1)].toInt())
    }

    @Test
    fun segmenterGpuPolicy_productionDefaultsToGpuAndRejectsCpuConfiguration() {
        val segmenter = LiteRtVisionSegmenter()
        try {
            assertEquals("Production segmenter must default to GPU", VisionBackendType.GPU, segmenter.configuredBackend)
            var cpuRejected = false
            try {
                segmenter.configureBackend(VisionBackendType.CPU)
            } catch (e: IllegalArgumentException) {
                cpuRejected = true
                assertTrue(e.message?.contains("CPU backend is disabled in production") == true)
            }
            assertTrue("configureBackend(CPU) must be rejected with IllegalArgumentException", cpuRejected)
            assertEquals("Backend must remain GPU after rejected CPU selection", VisionBackendType.GPU, segmenter.configuredBackend)
        } finally {
            segmenter.close()
        }
    }

    @Test
    fun segmenterGpuPolicy_diagnosticPolicyAllowsCpuReferenceForTesting() {
        val segmenter = LiteRtVisionSegmenter(
            inferencePolicy = DiagnosticInferencePolicy(allowCpuReferenceForTesting = true),
        )
        try {
            assertEquals(VisionBackendType.GPU, segmenter.configuredBackend)
            segmenter.configureBackend(VisionBackendType.CPU)
            assertEquals(VisionBackendType.CPU, segmenter.configuredBackend)
        } finally {
            segmenter.close()
        }
    }

    @Test
    fun segmenterGpuPolicy_buildSessionRejectsCpuUnderProductionPolicy() {
        val tempFile = File.createTempFile("dummy_model", ".tflite")
        tempFile.writeBytes(ByteArray(64))
        try {
            val mapped = java.io.RandomAccessFile(tempFile, "r").use { raf ->
                raf.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0L, raf.length())
            }
            val segmenter = LiteRtVisionSegmenter(
                inferencePolicy = ProductionGpuOnlyPolicy,
            )
            try {
                var rejected = false
                try {
                    segmenter.buildSession(mapped, VisionBackendType.CPU, 4)
                } catch (e: UnsupportedOperationException) {
                    rejected = true
                    assertTrue(e.message?.contains("CPU inference is disabled in production") == true)
                }
                assertTrue("buildSession with CPU must throw UnsupportedOperationException", rejected)
            } finally {
                segmenter.close()
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun segmenterGpuPolicy_unsupportedGpuThrowsImmediatelyWithoutCpuFallback() {
        val tempFile = File.createTempFile("dummy_model", ".tflite")
        tempFile.writeBytes(ByteArray(64))
        try {
            val mapped = java.io.RandomAccessFile(tempFile, "r").use { raf ->
                raf.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0L, raf.length())
            }
            val unsupportedEnv = object : GpuEnvironment() {
                override fun isGpuSupported(): Boolean = false
            }
            val segmenter = LiteRtVisionSegmenter(
                gpuEnvironment = unsupportedEnv,
            )
            try {
                var thrown = false
                try {
                    segmenter.buildSession(mapped, VisionBackendType.GPU, 4)
                } catch (e: IllegalStateException) {
                    thrown = true
                    assertTrue(e.message?.contains("LiteRT GPU delegate is not supported on this device") == true)
                }
                assertTrue("Unsupported GPU must throw IllegalStateException without CPU fallback", thrown)
                assertEquals(VisionBackendType.GPU, segmenter.configuredBackend)
            } finally {
                segmenter.close()
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun segmenterGpuPolicy_failedGpuInitCleansUpAndRethrowsWithoutCpuFallback() {
        val tempFile = File.createTempFile("dummy_model", ".tflite")
        tempFile.writeBytes(ByteArray(64))
        try {
            val mapped = java.io.RandomAccessFile(tempFile, "r").use { raf ->
                raf.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0L, raf.length())
            }
            val failingEnv = object : GpuEnvironment() {
                override fun isGpuSupported(): Boolean = true
                override fun createDelegate(precisionLossAllowed: Boolean): org.tensorflow.lite.gpu.GpuDelegate {
                    throw RuntimeException("Simulated GPU shader compilation failure")
                }
            }
            val segmenter = LiteRtVisionSegmenter(
                gpuEnvironment = failingEnv,
            )
            try {
                var thrown = false
                try {
                    segmenter.buildSession(mapped, VisionBackendType.GPU, 4)
                } catch (e: RuntimeException) {
                    thrown = true
                    assertEquals("Simulated GPU shader compilation failure", e.message)
                }
                assertTrue("Failing GPU delegate init must rethrow without CPU fallback", thrown)
                assertEquals(VisionBackendType.GPU, segmenter.configuredBackend)
            } finally {
                segmenter.close()
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun visionUiStateAndMetadata_defaultsToGpu() {
        val uiState = VisionUiState()
        assertEquals(VisionBackendType.GPU, uiState.configuredBackend)

        val metadata = VisionModelMetadata(
            displayName = "test.tflite",
            sizeBytes = 1000L,
            sha256 = "dummy_sha",
            importedAtMillis = 1000L,
        )
        assertTrue(metadata.backendDescription.contains("LiteRT GPU FP32"))
    }
}
