package com.ariateam.aria.vision

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BinaryVisionOverlayRendererTest {

    private val renderer = BinaryVisionOverlayRenderer()

    @Test
    fun allSevenClassesAndInvalidClassesMapToBinaryColorsWithoutTransparency() {
        val width = 10
        val height = 1
        // Classes: 0, 1, 2, 3, 4, 5, 6, 7 (invalid), 255 (invalid), 100 (invalid)
        val mask = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 255.toByte(), 100)
        val overlay = IntArray(width * height)

        // Use minimumComponentPixels = 0 to evaluate direct mapping without size suppression
        renderer.renderOverlay(
            classMask = mask,
            outOverlayArgb = overlay,
            width = width,
            height = height,
            minimumComponentPixels = 0,
        )

        // Classes 2 (sidewalk) and 3 (crosswalk) must be GREEN
        assertEquals(VisionContract.OVERLAY_COLOR_GREEN, overlay[2])
        assertEquals(VisionContract.OVERLAY_COLOR_GREEN, overlay[3])

        // Classes 0, 1, 4, 5, 6 and all invalid classes must be RED
        val redIndices = listOf(0, 1, 4, 5, 6, 7, 8, 9)
        for (idx in redIndices) {
            assertEquals("Index $idx (class ${mask[idx].toInt() and 0xFF}) must be RED", VisionContract.OVERLAY_COLOR_RED, overlay[idx])
        }

        // Verify zero transparent pixels inside valid image bounds
        for (i in overlay.indices) {
            val alpha = (overlay[i] ushr 24) and 0xFF
            assertEquals("Pixel $i must have 0x80 alpha", 0x80, alpha)
            assertTrue("Pixel $i must be either GREEN or RED", overlay[i] == VisionContract.OVERLAY_COLOR_GREEN || overlay[i] == VisionContract.OVERLAY_COLOR_RED)
        }
    }

    @Test
    fun noisyGreenComponentSuppressedToRedWhileObstaclesRemainRedEvenTiny() {
        val width = 8
        val height = 8
        val mask = ByteArray(width * height) { 1 } // Road (red)
        // Insert a 3-pixel green component (< 10 threshold)
        mask[0] = 2
        mask[1] = 2
        mask[2] = 2
        // Insert a 1-pixel obstacle (person class 4) in the middle
        mask[10] = 4

        val overlay = IntArray(width * height)
        renderer.renderOverlay(
            classMask = mask,
            outOverlayArgb = overlay,
            width = width,
            height = height,
            minimumComponentPixels = 10,
        )

        // The 3-pixel green component was noisy (< 10), so it became RED
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[0])
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[1])
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[2])

        // The 1-pixel obstacle (class 4) remains RED
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[10])

        // All pixels are RED
        for (i in overlay.indices) {
            assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[i])
        }
    }

    @Test
    fun largeGreenComponentPreservedAboveThreshold() {
        val width = 8
        val height = 8
        val mask = ByteArray(width * height) { 1 } // Road (red)
        // Create a 16-pixel green component (4x4)
        for (y in 2..5) {
            for (x in 2..5) {
                mask[y * width + x] = 2
            }
        }

        val overlay = IntArray(width * height)
        renderer.renderOverlay(
            classMask = mask,
            outOverlayArgb = overlay,
            width = width,
            height = height,
            minimumComponentPixels = 16,
        )

        // The 16-pixel component meets the threshold (not < 16) -> preserved as GREEN
        for (y in 2..5) {
            for (x in 2..5) {
                assertEquals(VisionContract.OVERLAY_COLOR_GREEN, overlay[y * width + x])
            }
        }
        // Surrounding pixels remain RED
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[0])
    }

    @Test
    fun crosswalkEstimatorGapFillingFillsPavementAndPreservesObstacleHoles() {
        val width = 60
        val height = 40
        val mask = ByteArray(width * height) { 1 } // Road background (class 1)

        // Draw 3 elongated vertical zebra stripes (class 3) from y=10 to y=30
        val stripeCols = listOf(15, 25, 35)
        for (col in stripeCols) {
            for (y in 10..30) {
                for (dx in -1..1) {
                    mask[y * width + (col + dx)] = 3
                }
            }
        }

        // Place an obstacle (class 4 = person) in the gap between stripe 1 and stripe 2
        val obstacleX = 20
        val obstacleY = 20
        mask[obstacleY * width + obstacleX] = 4

        val estimator = CrosswalkRegionEstimator()
        val regionBuffer = BooleanArray(width * height)
        val overlay = IntArray(width * height)

        renderer.renderOverlay(
            classMask = mask,
            outOverlayArgb = overlay,
            width = width,
            height = height,
            crosswalkRegionEstimator = estimator,
            crosswalkRegionBuffer = regionBuffer,
            minimumComponentPixels = 8,
        )

        // 1. Stripe pixels are GREEN
        for (col in stripeCols) {
            assertEquals("Stripe pixel must be green", VisionContract.OVERLAY_COLOR_GREEN, overlay[20 * width + col])
        }

        // 2. Inter-stripe gap pixel (road, class 1) at (21, 20) inside the estimated crosswalk region is filled GREEN
        if (regionBuffer[obstacleY * width + 21]) {
            assertEquals("Filled crosswalk gap must be green", VisionContract.OVERLAY_COLOR_GREEN, overlay[obstacleY * width + 21])
        }

        // 3. Obstacle pixel (person, class 4) at (20, 20) strictly remains a RED hole
        assertEquals("Obstacle inside crosswalk must remain red hole", VisionContract.OVERLAY_COLOR_RED, overlay[obstacleY * width + obstacleX])

        // 4. Outside the crosswalk (e.g. at x=2, y=2), road remains RED
        assertEquals("Road outside crosswalk must remain red", VisionContract.OVERLAY_COLOR_RED, overlay[2 * width + 2])
    }

    @Test
    fun semanticOutputInvarianceWithOverlayNullVsEnabled() {
        val w = 16
        val h = 16
        val numClasses = VisionContract.MODEL_CLASSES
        val probabilities = FloatArray(w * h * numClasses)

        // Fill with mixed probabilities
        for (i in 0 until w * h) {
            val assignedClass = when {
                i < 50 -> 2 // sidewalk
                i < 100 -> 1 // road
                i < 150 -> 4 // person
                else -> 3 // crosswalk
            }
            probabilities[i * numClasses + assignedClass] = 2.0f
        }

        val maskWithoutOverlay = ByteArray(w * h)
        val summaryWithoutOverlay = LiteRtVisionSegmenter.decodeArgmaxAndMask(
            probabilities = probabilities,
            outClassMask = maskWithoutOverlay,
            outOverlayArgb = null,
            width = w,
            height = h,
            numClasses = numClasses,
            roiStartRow = 4,
        )

        val maskWithOverlay = ByteArray(w * h)
        val overlayArgb = IntArray(w * h)
        val summaryWithOverlay = LiteRtVisionSegmenter.decodeArgmaxAndMask(
            probabilities = probabilities,
            outClassMask = maskWithOverlay,
            outOverlayArgb = overlayArgb,
            width = w,
            height = h,
            numClasses = numClasses,
            roiStartRow = 4,
        )

        // Raw semantic class mask must be byte-for-byte identical
        assertArrayEquals("Class mask must be identical", maskWithoutOverlay, maskWithOverlay)

        // All summary statistics must match exactly
        assertArrayEquals("Class pixel counts must match", summaryWithoutOverlay.classPixelCounts, summaryWithOverlay.classPixelCounts)
        assertEquals("ROI Left walkable ratio must match", summaryWithoutOverlay.roiLeftWalkableRatio, summaryWithOverlay.roiLeftWalkableRatio, 1e-6f)
        assertEquals("ROI Center walkable ratio must match", summaryWithoutOverlay.roiCenterWalkableRatio, summaryWithOverlay.roiCenterWalkableRatio, 1e-6f)
        assertEquals("ROI Right walkable ratio must match", summaryWithoutOverlay.roiRightWalkableRatio, summaryWithOverlay.roiRightWalkableRatio, 1e-6f)
        assertEquals("Dominant center obstacle must match", summaryWithoutOverlay.dominantNonWalkableCenterClassId, summaryWithOverlay.dominantNonWalkableCenterClassId)

        // Every pixel in overlayArgb must be either GREEN or RED with zero transparency
        for (i in overlayArgb.indices) {
            assertTrue("Pixel $i must be binary", overlayArgb[i] == VisionContract.OVERLAY_COLOR_GREEN || overlayArgb[i] == VisionContract.OVERLAY_COLOR_RED)
        }
    }

    @Test
    fun staticAndLiveInternalParityWithCrosswalkFixture() {
        val width = 60
        val height = 40
        val numClasses = VisionContract.MODEL_CLASSES
        val probs = FloatArray(width * height * numClasses)

        // Build a crosswalk zebra fixture (road base + vertical stripes)
        for (i in 0 until width * height) {
            probs[i * numClasses + 1] = 2.0f // default road (class 1)
        }
        val stripeCols = listOf(15, 25, 35)
        for (col in stripeCols) {
            for (y in 10..30) {
                for (dx in -1..1) {
                    val idx = y * width + (col + dx)
                    probs[idx * numClasses + 1] = 0f
                    probs[idx * numClasses + 3] = 2.0f // crosswalk (class 3)
                }
            }
        }
        // Place obstacle at (20, 20)
        val obstIdx = 20 * width + 20
        probs[obstIdx * numClasses + 1] = 0f
        probs[obstIdx * numClasses + 4] = 3.0f // person (class 4)

        // Path 1: Static public decodeArgmaxAndMask
        val staticMask = ByteArray(width * height)
        val staticOverlay = IntArray(width * height)
        val staticSummary = LiteRtVisionSegmenter.decodeArgmaxAndMask(
            probabilities = probs,
            outClassMask = staticMask,
            outOverlayArgb = staticOverlay,
            width = width,
            height = height,
            numClasses = numClasses,
            roiStartRow = 0,
        )

        // Path 2: Live internal decode with dedicated estimator, buffer, and renderer
        val liveEstimator = CrosswalkRegionEstimator()
        val liveRegion = BooleanArray(width * height)
        val liveMask = ByteArray(width * height)
        val liveOverlay = IntArray(width * height)
        val liveRenderer = BinaryVisionOverlayRenderer()
        val liveSummary = LiteRtVisionSegmenter.decodeArgmaxAndMaskInternal(
            probabilities = probs,
            outClassMask = liveMask,
            outOverlayArgb = liveOverlay,
            width = width,
            height = height,
            numClasses = numClasses,
            roiStartRow = 0,
            transform = null,
            modelWidth = width,
            modelHeight = height,
            binaryVisionOverlayRenderer = liveRenderer,
            crosswalkRegionEstimator = liveEstimator,
            crosswalkRegionBuffer = liveRegion,
        )

        assertArrayEquals("Static and live class mask must be identical", liveMask, staticMask)
        assertArrayEquals("Static and live overlay colors must match exactly with gap filling", liveOverlay, staticOverlay)
        assertArrayEquals("Summary counts must match", liveSummary.classPixelCounts, staticSummary.classPixelCounts)
        assertEquals("Obstacle hole at (20,20) must be RED in both", VisionContract.OVERLAY_COLOR_RED, staticOverlay[obstIdx])
    }

    @Test
    fun concurrentStaticDecodeCallsAreThreadSafe() {
        val width = 20
        val height = 20
        val numClasses = VisionContract.MODEL_CLASSES
        val probs = FloatArray(width * height * numClasses)
        for (i in 0 until width * height) {
            probs[i * numClasses + 2] = 1.0f // sidewalk (class 2)
        }

        val threadCount = 4
        val iterationsPerThread = 20
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val threads = (0 until threadCount).map {
            Thread {
                try {
                    val localMask = ByteArray(width * height)
                    val localOverlay = IntArray(width * height)
                    for (iter in 0 until iterationsPerThread) {
                        LiteRtVisionSegmenter.decodeArgmaxAndMask(
                            probabilities = probs,
                            outClassMask = localMask,
                            outOverlayArgb = localOverlay,
                            width = width,
                            height = height,
                            numClasses = numClasses,
                            roiStartRow = 0,
                        )
                        for (color in localOverlay) {
                            assertEquals(VisionContract.OVERLAY_COLOR_GREEN, color)
                        }
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(5000) }
        assertTrue("Concurrent decode calls must not produce errors: $errors", errors.isEmpty())
    }

    @Test
    fun productionDecodeArgmaxAndMaskRoutesThroughBinaryVisionOverlayRenderer() {
        val width = 10
        val height = 10
        val numClasses = VisionContract.MODEL_CLASSES
        val probs = FloatArray(width * height * numClasses)
        // Background: default road (class 1)
        for (i in 0 until width * height) {
            probs[i * numClasses + 1] = 2.0f
        }

        // 1. Create a 36-pixel valid sidewalk area (class 2) from x=2..7, y=2..7
        for (y in 2..7) {
            for (x in 2..7) {
                val idx = y * width + x
                probs[idx * numClasses + 1] = 0f
                probs[idx * numClasses + 2] = 5.0f
            }
        }

        // 2. Insert a 1-pixel obstacle (person, class 4) in the sidewalk at (5, 5)
        val obstIdx = 5 * width + 5
        probs[obstIdx * numClasses + 2] = 0f
        probs[obstIdx * numClasses + 4] = 6.0f

        // 3. Insert an isolated 2-pixel noisy green speckle (crosswalk, class 3) at (0, 0) and (0, 1)
        probs[(0 * width + 0) * numClasses + 1] = 0f
        probs[(0 * width + 0) * numClasses + 3] = 5.0f
        probs[(0 * width + 1) * numClasses + 1] = 0f
        probs[(0 * width + 1) * numClasses + 3] = 5.0f

        val mask = ByteArray(width * height)
        val overlay = IntArray(width * height)

        LiteRtVisionSegmenter.decodeArgmaxAndMask(
            probabilities = probs,
            outClassMask = mask,
            outOverlayArgb = overlay,
            width = width,
            height = height,
            numClasses = numClasses,
            roiStartRow = 0,
        )

        // The 36-pixel sidewalk component meets the >= 32 threshold -> preserved as GREEN
        assertEquals(VisionContract.OVERLAY_COLOR_GREEN, overlay[2 * width + 2])
        assertEquals(VisionContract.OVERLAY_COLOR_GREEN, overlay[7 * width + 7])

        // The 1-pixel obstacle inside the sidewalk strictly remains a RED hole
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[obstIdx])

        // The 2-pixel noisy green speckle (< 32 pixels) was suppressed to RED by OverlayComponentFilter
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[0 * width + 0])
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[0 * width + 1])

        // Outside road pavement is RED
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[9 * width + 9])

        // Verify all pixels are binary with 0x80 alpha
        for (i in overlay.indices) {
            val alpha = (overlay[i] ushr 24) and 0xFF
            assertEquals("Pixel $i must have 0x80 alpha", 0x80, alpha)
            assertTrue("Pixel $i must be binary", overlay[i] == VisionContract.OVERLAY_COLOR_GREEN || overlay[i] == VisionContract.OVERLAY_COLOR_RED)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidDimensionsThrowBeforeAllocation() {
        val probs = FloatArray(16)
        val mask = ByteArray(16)
        val overlay = IntArray(16)
        LiteRtVisionSegmenter.decodeArgmaxAndMask(
            probabilities = probs,
            outClassMask = mask,
            outOverlayArgb = overlay,
            width = -1,
            height = 10,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun undersizedBuffersThrowBeforeAllocation() {
        val probs = FloatArray(10 * 10 * 7)
        val mask = ByteArray(5) // Too small
        val overlay = IntArray(100)
        LiteRtVisionSegmenter.decodeArgmaxAndMask(
            probabilities = probs,
            outClassMask = mask,
            outOverlayArgb = overlay,
            width = 10,
            height = 10,
        )
    }
}
