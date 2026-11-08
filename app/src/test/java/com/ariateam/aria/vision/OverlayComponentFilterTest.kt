package com.ariateam.aria.vision

import android.graphics.Bitmap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

class OverlayComponentFilterTest {

    @Test
    fun suppressesIsolatedSpeckleInOverlayOnly() {
        val width = 8
        val height = 8
        // Red background with a single green pixel
        val mask = ByteArray(width * height) { 1 }
        mask[0] = 2
        val originalMask = mask.copyOf()
        val overlay = overlayFor(mask)

        OverlayComponentFilter().suppressSmallComponents(mask, overlay, width, height)

        assertArrayEquals(originalMask, mask)
        // Noisy green speckle (<32 pixels) becomes red; no transparent holes
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[0])
        for (index in 1 until overlay.size) {
            assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[index])
        }
    }

    @Test
    fun decoderFiltersDisplayOverlayAfterDirectMappingWithoutChangingAnalysis() {
        assertDecoderOutputOnlyFiltersOverlay(outputWidth = 8, outputHeight = 8, modelWidth = 8, modelHeight = 8)
    }

    @Test
    fun decoderFiltersDisplayOverlayAfterInverseMappingWithoutChangingAnalysis() {
        assertDecoderOutputOnlyFiltersOverlay(outputWidth = 8, outputHeight = 8, modelWidth = 7, modelHeight = 8)
    }

    @Test
    fun runIfLoadedPassesFilteredColorsToBitmapFactory() {
        val modelProbabilities = FloatArray(VisionContract.OUTPUT_FLOATS)
        for (pixelIndex in 0 until VisionContract.INPUT_PIXELS) {
            modelProbabilities[pixelIndex * VisionContract.MODEL_CLASSES + 2] = 1f
        }
        modelProbabilities[1] = 2f
        val capturedColors = AtomicReference<IntArray?>()
        val tempPath = Files.createTempFile("overlay_component_filter_", ".tflite")
        tempPath.toFile().deleteOnExit()
        Files.write(tempPath, ByteArray(64))
        val mappedBuffer = RandomAccessFile(tempPath.toFile(), "r").use { file ->
            file.channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, 0L, 64L)
            }
        }
        var clockNanos = 0L
        val segmenter = LiteRtVisionSegmenter(
            sessionBuilderForTesting = { buffer, backend, threads, _ ->
                LiteRtVisionSegmenter.InferenceSession(
                    mappedBuffer = buffer,
                    backendType = backend,
                    cpuThreads = threads,
                    backendDescription = "Test GPU session",
                    runInferenceForTesting = { _, output ->
                        output.clear()
                        output.asFloatBuffer().put(modelProbabilities)
                        output.rewind()
                    },
                )
            },
            bitmapFactory = object : OverlayBitmapFactory {
                override fun create(colors: IntArray, width: Int, height: Int): Bitmap? {
                    assertEquals(VisionContract.MODEL_WIDTH, width)
                    assertEquals(VisionContract.MODEL_HEIGHT, height)
                    capturedColors.set(colors.copyOf())
                    return null
                }
            },
            monotonicClockNanos = {
                clockNanos += 1_000_000L
                clockNanos
            },
        )

        try {
            segmenter.commitSession(segmenter.buildSession(mappedBuffer, VisionBackendType.GPU, 4))
            val output = segmenter.runIfLoaded(
                inputByteBuffer = ByteBuffer.allocateDirect(VisionContract.INPUT_BYTES).order(ByteOrder.nativeOrder()),
                produceOverlayBitmap = true,
            )

            assertNotNull(output)
            assertEquals(2, output!!.summary.classPixelCounts[1])
            assertEquals(VisionContract.MODEL_WIDTH * VisionContract.MODEL_HEIGHT - 2, output.summary.classPixelCounts[2])
            assertEquals(1_000_000L, output.invokeDurationNanos)
            assertEquals(1_000_000L, output.postprocessDurationNanos)
            val bitmapColors = capturedColors.get()
            assertNotNull(bitmapColors)
            // Class 1 (road) is an obstacle/non-walkable class: it remains RED even tiny
            assertEquals(VisionContract.OVERLAY_COLOR_RED, bitmapColors!![0])
            assertEquals(VisionContract.OVERLAY_COLOR_RED, bitmapColors[1])
            assertEquals(VisionContract.OVERLAY_COLOR_GREEN, bitmapColors[2])
        } finally {
            segmenter.close()
            tempPath.toFile().delete()
        }
    }

    @Test
    fun connectsDiagonallyAndKeepsComponentAtThreshold() {
        val mask = ByteArray(4) { 1 }
        mask[0] = 2
        mask[3] = 2
        val overlay = overlayFor(mask)

        OverlayComponentFilter().suppressSmallComponents(mask, overlay, width = 2, height = 2, minimumComponentPixels = 2)

        assertEquals(VisionContract.OVERLAY_COLOR_GREEN, overlay[0])
        assertEquals(VisionContract.OVERLAY_COLOR_GREEN, overlay[3])
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[1])
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[2])
    }

    @Test
    fun thresholdsZero16_32And64ApplyStrictLessThanRule() {
        val width = 8
        val height = 8
        val mask = ByteArray(width * height) { 1 }
        for (y in 1..4) {
            for (x in 1..4) {
                mask[y * width + x] = 2
            }
        }

        for ((threshold, expectedVisiblePixels) in listOf(0 to 16, 16 to 16, 32 to 0, 64 to 0)) {
            val overlay = overlayFor(mask)
            OverlayComponentFilter().suppressSmallComponents(mask, overlay, width, height, threshold)
            val visibleGreenPixels = overlay.count { it == VisionContract.OVERLAY_COLOR_GREEN }
            assertEquals("threshold=$threshold", expectedVisiblePixels, visibleGreenPixels)
        }
    }

    @Test
    fun separatesAdjacentClassesWhenFindingComponents() {
        val mask = ByteArray(4) { 1 }
        mask[0] = 2
        mask[1] = 4
        val originalMask = mask.copyOf()
        val overlay = overlayFor(mask)

        OverlayComponentFilter().suppressSmallComponents(mask, overlay, width = 2, height = 2, minimumComponentPixels = 2)

        assertArrayEquals(originalMask, mask)
        // Green component size 1 < 2 is suppressed to red; obstacle class 4 remains red
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[0])
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[1])
    }

    @Test
    fun preservesProtectedClassesTouchingScaledNearZone() {
        val width = 10
        val height = 10
        val mask = ByteArray(width * height) { 2 }
        mask[7 * width] = 4
        mask[8 * width] = 4
        mask[8 * width + 2] = 5
        mask[8 * width + 4] = 6
        mask[7 * width + 8] = 4
        val overlay = overlayFor(mask)

        OverlayComponentFilter().suppressSmallComponents(mask, overlay, width, height)

        // All obstacles remain red even tiny; no transparent holes are punched
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[7 * width])
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[8 * width])
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[8 * width + 2])
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[8 * width + 4])
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[7 * width + 8])
    }

    @Test
    fun preservesInvalidLabelsConservativelyAndFiltersValidComponents() {
        val mask = byteArrayOf(255.toByte(), 7, 1, 2)
        val originalMask = mask.copyOf()
        val overlay = intArrayOf(0x80112233.toInt(), 0x80445566.toInt(), VisionContract.OVERLAY_COLOR_RED, VisionContract.OVERLAY_COLOR_GREEN)
        val invalidOverlayColors = overlay.copyOfRange(0, 2)

        OverlayComponentFilter().suppressSmallComponents(mask, overlay, width = 2, height = 2)

        assertArrayEquals(originalMask, mask)
        assertArrayEquals(invalidOverlayColors, overlay.copyOfRange(0, 2))
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[2])
        // Green component of size 1 < 32 is suppressed to red
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[3])
    }

    @Test
    fun handlesEdgesAndAllImageComponents() {
        val edgeMask = ByteArray(16) { 1 }
        edgeMask[0] = 2
        edgeMask[1] = 2
        val edgeOverlay = overlayFor(edgeMask)
        OverlayComponentFilter().suppressSmallComponents(edgeMask, edgeOverlay, width = 4, height = 4, minimumComponentPixels = 3)
        assertEquals(VisionContract.OVERLAY_COLOR_RED, edgeOverlay[0])
        assertEquals(VisionContract.OVERLAY_COLOR_RED, edgeOverlay[1])

        val fullMask = ByteArray(9) { 1 }
        val fullOverlay = overlayFor(fullMask)
        OverlayComponentFilter().suppressSmallComponents(fullMask, fullOverlay, width = 3, height = 3, minimumComponentPixels = 9)
        assertArrayEquals(IntArray(9) { VisionContract.OVERLAY_COLOR_RED }, fullOverlay)
    }

    @Test
    fun repeatedCallsUseFreshVisitedState() {
        val filter = OverlayComponentFilter()
        val mask = ByteArray(9) { 1 }
        mask[0] = 2
        val overlay = overlayFor(mask)
        filter.suppressSmallComponents(mask, overlay, width = 3, height = 3, minimumComponentPixels = 2)
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[0])

        mask[1] = 2
        overlay[0] = VisionContract.OVERLAY_COLOR_GREEN
        overlay[1] = VisionContract.OVERLAY_COLOR_GREEN
        filter.suppressSmallComponents(mask, overlay, width = 3, height = 3, minimumComponentPixels = 2)

        assertEquals(VisionContract.OVERLAY_COLOR_GREEN, overlay[0])
        assertEquals(VisionContract.OVERLAY_COLOR_GREEN, overlay[1])
    }

    private fun overlayFor(mask: ByteArray): IntArray =
        IntArray(mask.size) { index ->
            val classId = mask[index].toInt() and 0xFF
            VisionContract.CLASS_OVERLAY_ARGB.getOrElse(classId) { VisionContract.OVERLAY_COLOR_RED }
        }

    private fun assertDecoderOutputOnlyFiltersOverlay(
        outputWidth: Int,
        outputHeight: Int,
        modelWidth: Int,
        modelHeight: Int,
    ) {
        val numClasses = VisionContract.MODEL_CLASSES
        val probabilities = FloatArray(modelWidth * modelHeight * numClasses)
        for (modelPixel in 0 until modelWidth * modelHeight) {
            probabilities[modelPixel * numClasses + 2] = 1f
        }
        probabilities[1] = 2f

        val originalMask = ByteArray(outputWidth * outputHeight)
        val originalSummary = LiteRtVisionSegmenter.decodeArgmaxAndMask(
            probabilities = probabilities,
            outClassMask = originalMask,
            width = outputWidth,
            height = outputHeight,
            numClasses = numClasses,
            roiStartRow = 0,
            modelWidth = modelWidth,
            modelHeight = modelHeight,
        )
        val filteredMask = ByteArray(outputWidth * outputHeight)
        val overlay = IntArray(outputWidth * outputHeight)
        val filteredSummary = LiteRtVisionSegmenter.decodeArgmaxAndMaskInternal(
            probabilities = probabilities,
            outClassMask = filteredMask,
            outOverlayArgb = overlay,
            width = outputWidth,
            height = outputHeight,
            numClasses = numClasses,
            roiStartRow = 0,
            transform = null,
            modelWidth = modelWidth,
            modelHeight = modelHeight,
            overlayComponentFilter = OverlayComponentFilter(),
        )

        assertArrayEquals(originalMask, filteredMask)
        assertEquals(originalSummary, filteredSummary)
        assertEquals(1, filteredSummary.classPixelCounts[1])
        assertEquals(63, filteredSummary.classPixelCounts[2])
        // Class 1 (road) pixel remains red; class 2 (sidewalk) pixel remains green
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[0])
        assertEquals(VisionContract.OVERLAY_COLOR_GREEN, overlay[1])
    }
}
