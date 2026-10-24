package com.ariateam.aria.vision

import android.view.Surface
import com.ariateam.aria.DebugDestination
import com.ariateam.aria.PendingCameraStartGate
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
            inputShape = intArrayOf(1, 288, 512, 3),
            inputDataType = "FLOAT32",
            outputTensorCount = 1,
            outputShape = intArrayOf(1, 288, 512, 7),
            outputDataType = "FLOAT32",
        )
        assertTrue(valid is VisionContract.SpecValidationResult.Valid)

        val wrongInputCount = VisionContract.validateModelSpec(
            inputTensorCount = 2,
            inputShape = intArrayOf(1, 288, 512, 3),
            inputDataType = "FLOAT32",
            outputTensorCount = 1,
            outputShape = intArrayOf(1, 288, 512, 7),
            outputDataType = "FLOAT32",
        )
        assertTrue(wrongInputCount is VisionContract.SpecValidationResult.Invalid)
        assertEquals("input_tensor_count", (wrongInputCount as VisionContract.SpecValidationResult.Invalid).code)

        val wrongInputType = VisionContract.validateModelSpec(
            inputTensorCount = 1,
            inputShape = intArrayOf(1, 288, 512, 3),
            inputDataType = "UINT8",
            outputTensorCount = 1,
            outputShape = intArrayOf(1, 288, 512, 7),
            outputDataType = "FLOAT32",
        )
        assertEquals("input_dtype", (wrongInputType as VisionContract.SpecValidationResult.Invalid).code)

        val wrongInputShape = VisionContract.validateModelSpec(
            inputTensorCount = 1,
            inputShape = intArrayOf(1, 512, 288, 3),
            inputDataType = "FLOAT32",
            outputTensorCount = 1,
            outputShape = intArrayOf(1, 288, 512, 7),
            outputDataType = "FLOAT32",
        )
        assertEquals("input_shape", (wrongInputShape as VisionContract.SpecValidationResult.Invalid).code)

        val wrongOutputShape = VisionContract.validateModelSpec(
            inputTensorCount = 1,
            inputShape = intArrayOf(1, 288, 512, 3),
            inputDataType = "FLOAT32",
            outputTensorCount = 1,
            outputShape = intArrayOf(1, 288, 512, 19),
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
        val crop = VisionFramePreprocessor.computeUprightCropRect(640, 480, 90)
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
}
