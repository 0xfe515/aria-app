package com.ariateam.aria.vision.od

import com.ariateam.aria.vision.UprightCropRect
import com.ariateam.aria.vision.VisionContract
import com.ariateam.aria.vision.VisionFramePreprocessor
import com.ariateam.aria.vision.VisionPreprocessingStrategy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Geometry contract of the OD adapter for all three frozen preprocessing strategies.
 *
 * The synthetic source tensor encodes its own tensor coordinates (`R = x / (W-1)`,
 * `G = y / (H-1)`), so every sampled detector pixel can be checked against the expected model-space
 * mapping without duplicating the sampling implementation.
 */
class OdFrameAdapterTest {

    private val adapter = OdFrameAdapter()

    private fun coordinateTensor(): FloatArray {
        val width = VisionContract.MODEL_INPUT_WIDTH
        val height = VisionContract.MODEL_INPUT_HEIGHT
        val tensor = FloatArray(VisionContract.INPUT_FLOATS)
        var offset = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                tensor[offset] = x.toFloat() / (width - 1).toFloat()
                tensor[offset + 1] = y.toFloat() / (height - 1).toFloat()
                tensor[offset + 2] = 0.25f
                offset += 3
            }
        }
        return tensor
    }

    private fun legacyTransform(): UprightCropRect = VisionFramePreprocessor.computeUprightCropRect(
        bufferWidth = 640,
        bufferHeight = 480,
        rotationDegrees = 0,
        strategy = VisionPreprocessingStrategy.LEGACY_CROP,
    )

    private fun uprightLetterboxTransform(): UprightCropRect =
        VisionFramePreprocessor.computeUprightCropRect(
            bufferWidth = 640,
            bufferHeight = 480,
            rotationDegrees = 0,
            strategy = VisionPreprocessingStrategy.FULL_FRAME_UPRIGHT_LETTERBOX,
        )

    private fun rotatedLandscapeTransform(): UprightCropRect =
        VisionFramePreprocessor.computeUprightCropRect(
            bufferWidth = 640,
            bufferHeight = 480,
            rotationDegrees = 90,
            strategy = VisionPreprocessingStrategy.FULL_FRAME_ROTATED_LANDSCAPE,
        )

    private fun pixel(out: FloatArray, x: Int, y: Int): Triple<Float, Float, Float> {
        val offset = (y * adapter.detectorSize + x) * VisionContract.MODEL_CHANNELS
        return Triple(out[offset], out[offset + 1], out[offset + 2])
    }

    @Test
    fun uprightLetterboxContentCornersMapToContentBounds_andPaddingIsBlack() {
        val transform = uprightLetterboxTransform()
        val out = FloatArray(adapter.detectorSize * adapter.detectorSize * 3)
        adapter.fillDetectorTensor(coordinateTensor(), transform, out)

        val letterbox = adapter.computeLetterbox(transform)
        val firstRow = letterbox.padTop.toInt()
        val lastRow = (letterbox.padTop + letterbox.contentHeight).toInt() - 1
        val contentTopNorm = transform.contentTop.toFloat() / (VisionContract.MODEL_INPUT_HEIGHT - 1).toFloat()
        val contentBottomNorm = (transform.contentTop + transform.contentHeight - 1).toFloat() /
            (VisionContract.MODEL_INPUT_HEIGHT - 1).toFloat()

        val topLeft = pixel(out, 0, firstRow)
        assertEquals("top-left R must sample tensor content left edge", 0f, topLeft.first, 0.02f)
        assertEquals("top-left G must sample tensor content top", contentTopNorm, topLeft.second, 0.02f)
        assertEquals("content B channel is sampled", 0.25f, topLeft.third, 0.01f)

        val topRight = pixel(out, adapter.detectorSize - 1, firstRow)
        assertEquals("top-right R must sample tensor content right edge", 1f, topRight.first, 0.02f)
        assertEquals(contentTopNorm, topRight.second, 0.02f)

        val bottomLeft = pixel(out, 0, lastRow)
        assertEquals(0f, bottomLeft.first, 0.02f)
        assertEquals("bottom G must sample tensor content bottom", contentBottomNorm, bottomLeft.second, 0.02f)

        val paddingPixel = pixel(out, 10, 2)
        assertEquals("letterbox padding must be black", 0f, paddingPixel.first, 0f)
        assertEquals(0f, paddingPixel.second, 0f)
        assertEquals(0f, paddingPixel.third, 0f)
    }

    @Test
    fun rotatedLandscapeSwapsAxesComparedToTheUprightCrop() {
        val rotated = rotatedLandscapeTransform()
        assertTrue("fixture must be a clockwise-rotated portrait frame", rotated.isRotatedClockwise)
        val rotatedOut = FloatArray(adapter.detectorSize * adapter.detectorSize * 3)
        adapter.fillDetectorTensor(coordinateTensor(), rotated, rotatedOut)

        val uprightTransform = uprightLetterboxTransform()
        val uprightOut = FloatArray(adapter.detectorSize * adapter.detectorSize * 3)
        adapter.fillDetectorTensor(coordinateTensor(), uprightTransform, uprightOut)

        val rotatedLetterbox = adapter.computeLetterbox(rotated)
        val rotatedTopLeft = pixel(
            rotatedOut,
            rotatedLetterbox.padLeft.toInt(),
            rotatedLetterbox.padTop.toInt(),
        )
        val uprightTopLeft = pixel(uprightOut, 0, adapter.computeLetterbox(uprightTransform).padTop.toInt())

        // The rotated frame's upright (u=0, v=0) corner is the model tensor's right edge; the
        // upright frame's (u=0, v=0) corner is the model tensor's left edge.
        assertEquals("rotated top-left must sample the model content right edge", 1f, rotatedTopLeft.first, 0.03f)
        assertEquals("upright top-left must sample the model content left edge", 0f, uprightTopLeft.first, 0.03f)
        assertTrue(
            "rotation must actually change the sampled x axis",
            abs(rotatedTopLeft.first - uprightTopLeft.first) > 0.9f,
        )

        val rotatedBottomRight = pixel(
            rotatedOut,
            (rotatedLetterbox.padLeft + rotatedLetterbox.contentWidth).toInt() - 1,
            (rotatedLetterbox.padTop + rotatedLetterbox.contentHeight).toInt() - 1,
        )
        val rotatedBottomNorm = (rotated.contentTop + rotated.contentHeight - 1).toFloat() /
            (VisionContract.MODEL_INPUT_HEIGHT - 1).toFloat()
        assertEquals("rotated bottom-right samples content left edge", 0f, rotatedBottomRight.first, 0.03f)
        assertEquals(rotatedBottomNorm, rotatedBottomRight.second, 0.03f)
    }

    @Test
    fun detectorContentBoxRoundTripsToFullCropForEveryStrategy() {
        val transforms = listOf(legacyTransform(), uprightLetterboxTransform(), rotatedLandscapeTransform())
        for (transform in transforms) {
            val letterbox = adapter.computeLetterbox(transform)
            val size = adapter.detectorSize.toFloat()
            val mapped = adapter.mapDetectorBoxToCropNorm(
                left = letterbox.padLeft / size,
                top = letterbox.padTop / size,
                right = (letterbox.padLeft + letterbox.contentWidth) / size,
                bottom = (letterbox.padTop + letterbox.contentHeight) / size,
                transform = transform,
            )
            assertNotNull("Full content box must map for ${transform.strategy}", mapped)
            assertArrayEquals(
                "Full content box must map to the full upright crop for ${transform.strategy}",
                floatArrayOf(0f, 0f, 1f, 1f),
                mapped!!,
                1e-4f,
            )
        }
    }

    @Test
    fun halfContentBoxMapsToHalfCropIndependentlyOfStrategy() {
        for (transform in listOf(uprightLetterboxTransform(), rotatedLandscapeTransform())) {
            val letterbox = adapter.computeLetterbox(transform)
            val size = adapter.detectorSize.toFloat()
            val mapped = adapter.mapDetectorBoxToCropNorm(
                left = (letterbox.padLeft + letterbox.contentWidth * 0.25f) / size,
                top = (letterbox.padTop + letterbox.contentHeight * 0.5f) / size,
                right = (letterbox.padLeft + letterbox.contentWidth * 0.75f) / size,
                bottom = (letterbox.padTop + letterbox.contentHeight) / size,
                transform = transform,
            )
            assertNotNull(mapped)
            assertEquals(0.25f, mapped!![0], 1e-4f)
            assertEquals(0.5f, mapped[1], 1e-4f)
            assertEquals(0.75f, mapped[2], 1e-4f)
            assertEquals(1f, mapped[3], 1e-4f)
        }
    }

    @Test
    fun boxInsideLetterboxPaddingIsRejected() {
        val transform = uprightLetterboxTransform()
        val letterbox = adapter.computeLetterbox(transform)
        val size = adapter.detectorSize.toFloat()
        // Entirely above the content band.
        val mapped = adapter.mapDetectorBoxToCropNorm(
            left = 0.1f,
            top = 0f,
            right = 0.9f,
            bottom = (letterbox.padTop / size) * 0.5f,
            transform = transform,
        )
        assertNull("Box fully inside padding must be rejected", mapped)
    }

    @Test
    fun boxPartiallyInPaddingIsClippedToContent() {
        val transform = uprightLetterboxTransform()
        val letterbox = adapter.computeLetterbox(transform)
        val size = adapter.detectorSize.toFloat()
        val mapped = adapter.mapDetectorBoxToCropNorm(
            left = 0f,
            top = 0f,
            right = 1f,
            bottom = (letterbox.padTop + letterbox.contentHeight * 0.5f) / size,
            transform = transform,
        )
        assertNotNull(mapped)
        assertEquals("Top must clip to crop top", 0f, mapped!![1], 1e-4f)
        assertEquals("Bottom must land at half crop height", 0.5f, mapped[3], 1e-4f)
        assertEquals(0f, mapped[0], 1e-4f)
        assertEquals(1f, mapped[2], 1e-4f)
    }

    @Test
    fun extremeBoundsAndInvalidBoxesAreRejected() {
        val transform = uprightLetterboxTransform()
        val invalid = listOf(
            floatArrayOf(Float.NaN, 0f, 1f, 1f),
            floatArrayOf(0f, Float.POSITIVE_INFINITY, 1f, 1f),
            floatArrayOf(0.6f, 0.6f, 0.4f, 0.4f),
            floatArrayOf(0.2f, 0.2f, 0.2f, 0.2f),
        )
        for (box in invalid) {
            assertNull(
                "Invalid box ${box.joinToString()} must be rejected",
                adapter.mapDetectorBoxToCropNorm(box[0], box[1], box[2], box[3], transform),
            )
        }
    }

    @Test
    fun outOfRangeBoxIsClippedIntoUnitSquare() {
        val transform = uprightLetterboxTransform()
        val mapped = adapter.mapDetectorBoxToCropNorm(-0.5f, -0.5f, 1.5f, 1.5f, transform)
        assertNotNull(mapped)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 1f), mapped!!, 1e-4f)
    }

    @Test
    fun fillDetectorTensorNeverMutatesTheSourceTensor() {
        val source = coordinateTensor()
        val copy = source.copyOf()
        adapter.fillDetectorTensor(source, legacyTransform(), FloatArray(320 * 320 * 3))
        assertTrue("Adapter must not mutate the caller's RGB tensor", source.contentEquals(copy))
    }

    @Test
    fun degenerateTransformIsNotUsable() {
        val degenerate = uprightLetterboxTransform().copy(width = 0)
        assertFalse(adapter.isUsable(degenerate))
        assertTrue(adapter.isUsable(uprightLetterboxTransform()))
    }

    @Test
    fun letterboxPreservesTheCropAspectRatio() {
        // LEGACY_CROP targets the portrait model aspect (288:512), so a 640x480 buffer is cropped
        // to 270x480 with centered side margins and then letterboxed with side bars in the square
        // detector input. The adapter itself never warps: it only preserves that crop aspect.
        val transform = legacyTransform()
        val legacy = adapter.computeLetterbox(transform)
        val cropAspect = transform.width.toFloat() / transform.height.toFloat()
        assertEquals("crop aspect is the portrait model aspect", 288f / 512f, cropAspect, 1e-4f)
        assertEquals(320f, legacy.contentHeight, 0.5f)
        assertEquals(180f, legacy.contentWidth, 0.5f)
        assertEquals(0f, legacy.padTop, 0.5f)
        assertEquals(70f, legacy.padLeft, 0.5f)
        assertEquals(
            "letterbox must preserve the crop aspect ratio",
            cropAspect,
            legacy.contentWidth / legacy.contentHeight,
            1e-4f,
        )
    }

    @Test
    fun invertedBoxIsRejectedWhileOrderedOutOfRangeBoxIsClipped() {
        val transform = uprightLetterboxTransform()
        // Deterministic regression for the production bug: reversed coordinates must not be
        // silently sorted into a plausible rectangle.
        assertNull(
            "reversed box must be rejected",
            adapter.mapDetectorBoxToCropNorm(0.6f, 0.6f, 0.4f, 0.4f, transform),
        )
        assertNull(
            "inverted vertical box must be rejected",
            adapter.mapDetectorBoxToCropNorm(0.2f, 0.6f, 0.8f, 0.4f, transform),
        )
        assertNull(
            "zero-area box must be rejected",
            adapter.mapDetectorBoxToCropNorm(0.4f, 0.4f, 0.4f, 0.4f, transform),
        )
        // Ordered boxes that merely extend past the tensor are still clipped.
        val clipped = adapter.mapDetectorBoxToCropNorm(-0.5f, -0.5f, 1.5f, 1.5f, transform)
        assertNotNull(clipped)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 1f), clipped!!, 1e-4f)
    }
}
