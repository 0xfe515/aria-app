package com.ariateam.aria.vision.od

import com.ariateam.aria.vision.UprightCropRect
import com.ariateam.aria.vision.VisionFramePreprocessor
import com.ariateam.aria.vision.VisionPreprocessingStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decode contract for the pinned postprocess outputs:
 * `boxes[ymin, xmin, ymax, xmax]`, `classes`, `scores`, `count`.
 */
class OdDetectionDecodeTest {

    private val adapter = OdFrameAdapter()

    /** Square upright crop -> 1:1 detector letterbox, so decoded boxes are directly comparable. */
    private fun squareTransform(): UprightCropRect = VisionFramePreprocessor.computeUprightCropRect(
        bufferWidth = 320,
        bufferHeight = 320,
        rotationDegrees = 0,
        strategy = VisionPreprocessingStrategy.FULL_FRAME_UPRIGHT_LETTERBOX,
    )

    /** 16:9 crops get vertical letterbox bars in the detector input. */
    private fun wideTransform(): UprightCropRect = VisionFramePreprocessor.computeUprightCropRect(
        bufferWidth = 640,
        bufferHeight = 480,
        rotationDegrees = 0,
        strategy = VisionPreprocessingStrategy.FULL_FRAME_UPRIGHT_LETTERBOX,
    )

    private fun labels(): List<String> {
        val names = MutableList(LiteRtObjectDetector.CLASS_COUNT) { "category_$it" }
        names[0] = "person"
        names[1] = "bicycle"
        names[2] = "car"
        names[3] = "motorcycle"
        names[5] = "bus"
        names[7] = "truck"
        return names
    }

    private fun config(maxDetections: Int = LiteRtObjectDetector.MAX_RESULTS): OdInferenceConfig = OdInferenceConfig(
        scoreThreshold = LiteRtObjectDetector.SCORE_THRESHOLD,
        maxDetections = maxDetections,
        allowedClassIds = LiteRtObjectDetector.ALLOWED_CLASS_IDS.copyOf(),
        labels = labels(),
    )

    private fun boxes(vararg values: Float) = values

    private fun decode(
        boxes: FloatArray,
        classes: FloatArray,
        scores: FloatArray,
        count: Float,
        transform: UprightCropRect = squareTransform(),
        config: OdInferenceConfig = config(),
    ): List<DetectedObject> = LiteRtObjectDetector.decodeDetectionOutput(
        boxes = boxes,
        classes = classes,
        scores = scores,
        count = count,
        adapter = adapter,
        transform = transform,
        config = config,
    )

    @Test
    fun pinnedBoxOrderMapsYminXminYmaxXmaxToLeftTopRightBottom() {
        val results = decode(
            boxes = boxes(0.10f, 0.20f, 0.40f, 0.60f),
            classes = floatArrayOf(0f),
            scores = floatArrayOf(0.9f),
            count = 1f,
        )
        assertEquals(1, results.size)
        val detection = results[0]
        assertEquals("left comes from xmin (index 1)", 0.20f, detection.left, 1e-5f)
        assertEquals("top comes from ymin (index 0)", 0.10f, detection.top, 1e-5f)
        assertEquals("right comes from xmax (index 3)", 0.60f, detection.right, 1e-5f)
        assertEquals("bottom comes from ymax (index 2)", 0.40f, detection.bottom, 1e-5f)
        assertEquals("person", detection.label)
        assertEquals(0, detection.classId)
    }

    @Test
    fun asymmetricTallBoxIsNotTransposed() {
        // ymin, xmin, ymax, xmax of a person-shaped (tall) box.
        val results = decode(
            boxes = boxes(0.10f, 0.30f, 0.70f, 0.40f),
            classes = floatArrayOf(0f),
            scores = floatArrayOf(0.8f),
            count = 1f,
        )
        assertEquals(1, results.size)
        val detection = results[0]
        assertEquals(0.30f, detection.left, 1e-5f)
        assertEquals(0.10f, detection.top, 1e-5f)
        assertEquals(0.40f, detection.right, 1e-5f)
        assertEquals(0.70f, detection.bottom, 1e-5f)
        assertTrue("tall box must stay tall: ${detection.width} x ${detection.height}", detection.height > detection.width)
    }

    @Test
    fun allowedObjectBehindHigherScoringDisallowedSlotsIsStillReported() {
        val results = decode(
            boxes = boxes(
                0.10f, 0.10f, 0.20f, 0.20f,
                0.30f, 0.30f, 0.40f, 0.40f,
                0.11f, 0.12f, 0.41f, 0.62f,
                0.50f, 0.20f, 0.90f, 0.80f,
            ),
            classes = floatArrayOf(4f, 16f, 0f, 2f),
            scores = floatArrayOf(0.99f, 0.98f, 0.60f, 0.55f),
            count = 4f,
        )
        assertEquals("person and car must survive the disallowed top slots", 2, results.size)
        assertEquals(0, results[0].classId)
        assertEquals(2, results[1].classId)
        assertTrue("results must be ordered by descending score", results[0].score >= results[1].score)
    }

    @Test
    fun resultCapAppliesOnlyAfterClassFiltering() {
        val slots = LiteRtObjectDetector.MODEL_SLOT_COUNT
        val boxes = FloatArray(slots * 4) { index ->
            when (index % 4) {
                0 -> 0.10f
                1 -> 0.10f
                2 -> 0.50f
                else -> 0.30f
            }
        }
        val classes = FloatArray(slots) { 0f }
        val scores = FloatArray(slots) { 0.9f - it * 0.01f }
        val results = decode(boxes, classes, scores, slots.toFloat())
        assertEquals(LiteRtObjectDetector.MAX_RESULTS, results.size)
        assertEquals(12, LiteRtObjectDetector.MAX_RESULTS)
    }

    @Test
    fun declaredCountBeyondModelSlotsIsClampedToTensorCapacity() {
        val slots = LiteRtObjectDetector.MODEL_SLOT_COUNT
        val boxes = FloatArray(slots * 4) { index ->
            when (index % 4) {
                0 -> 0.10f
                1 -> 0.10f
                2 -> 0.50f
                else -> 0.30f
            }
        }
        val classes = FloatArray(slots) { 0f }
        val scores = FloatArray(slots) { 0.75f }

        // A malformed declared count that exceeds the 25 postprocess slots is clamped to the real
        // tensor capacity: it can never add detections beyond what the model produced.
        val capped = decode(boxes, classes, scores, count = 1000f)
        assertEquals(LiteRtObjectDetector.MAX_RESULTS, capped.size)

        val allSlots = decode(
            boxes,
            classes,
            scores,
            count = 1000f,
            config = config(maxDetections = LiteRtObjectDetector.MODEL_SLOT_COUNT),
        )
        assertEquals(
            "declared count must clamp to the 25 tensor slots, not to the declared 1000",
            LiteRtObjectDetector.MODEL_SLOT_COUNT,
            allSlots.size,
        )
    }

    @Test
    fun zeroOrNonFiniteCountReturnsNoDetections() {
        val boxes = boxes(0.1f, 0.1f, 0.2f, 0.2f)
        val classes = floatArrayOf(0f)
        val scores = floatArrayOf(0.9f)
        assertTrue(decode(boxes, classes, scores, 0f).isEmpty())
        assertTrue(decode(boxes, classes, scores, Float.NaN).isEmpty())
        assertTrue(decode(boxes, classes, scores, -3f).isEmpty())
    }

    @Test
    fun scoresBelowThresholdOrOutsideUnitRangeAreDropped() {
        val boxes = boxes(0.1f, 0.1f, 0.2f, 0.2f, 0.1f, 0.1f, 0.2f, 0.2f, 0.1f, 0.1f, 0.2f, 0.2f, 0.1f, 0.1f, 0.2f, 0.2f)
        val classes = floatArrayOf(0f, 0f, 0f, 0f)
        val scores = floatArrayOf(0.49f, Float.NaN, Float.POSITIVE_INFINITY, 1.5f)
        assertTrue(decode(boxes, classes, scores, 4f).isEmpty())

        val accepted = decode(
            boxes = boxes(0.1f, 0.1f, 0.2f, 0.2f),
            classes = floatArrayOf(0f),
            scores = floatArrayOf(1.0f),
            count = 1f,
        )
        assertEquals(1, accepted.size)
        assertEquals(1.0f, accepted[0].score, 0f)
    }

    @Test
    fun fractionalClassIdsAreRejected() {
        val boxes = boxes(0.1f, 0.1f, 0.2f, 0.2f, 0.1f, 0.1f, 0.2f, 0.2f)
        val scores = floatArrayOf(0.9f, 0.9f)
        assertTrue(decode(boxes, floatArrayOf(2.5f, 2.01f), scores, 2f).isEmpty())

        val accepted = decode(
            boxes = boxes(0.1f, 0.1f, 0.2f, 0.2f),
            classes = floatArrayOf(2.0005f),
            scores = floatArrayOf(0.9f),
            count = 1f,
        )
        assertEquals(1, accepted.size)
        assertEquals(2, accepted[0].classId)
        assertEquals("car", accepted[0].label)
    }

    @Test
    fun disallowedCocoCategoriesAreDropped() {
        val slots = 4
        val boxes = FloatArray(slots * 4) { 0.4f }
        val classes = floatArrayOf(4f, 6f, 16f, 89f)
        val scores = FloatArray(slots) { 0.95f }
        assertTrue(decode(boxes, classes, scores, slots.toFloat()).isEmpty())
    }

    @Test
    fun invalidBoxesAreDroppedAndOutOfRangeBoxesAreClipped() {
        val invalid = decode(
            boxes = boxes(0.60f, 0.60f, 0.40f, 0.40f, 0.20f, 0.20f, 0.20f, 0.20f, Float.NaN, 0.1f, 0.2f, 0.2f),
            classes = floatArrayOf(0f, 0f, 0f),
            scores = floatArrayOf(0.9f, 0.9f, 0.9f),
            count = 3f,
        )
        assertTrue(invalid.isEmpty())

        val clipped = decode(
            boxes = boxes(-0.2f, -0.2f, 1.1f, 1.1f),
            classes = floatArrayOf(0f),
            scores = floatArrayOf(0.9f),
            count = 1f,
        )
        assertEquals(1, clipped.size)
        assertEquals(0f, clipped[0].left, 1e-5f)
        assertEquals(0f, clipped[0].top, 1e-5f)
        assertEquals(1f, clipped[0].right, 1e-5f)
        assertEquals(1f, clipped[0].bottom, 1e-5f)
    }

    @Test
    fun boxFullyInsideLetterboxPaddingIsDropped() {
        val transform = wideTransform()
        val letterbox = adapter.computeLetterbox(transform)
        val size = adapter.detectorSize.toFloat()
        val paddingBoxBottom = (letterbox.padTop / size) * 0.5f
        val results = decode(
            boxes = boxes(0f, 0.1f, paddingBoxBottom, 0.9f),
            classes = floatArrayOf(0f),
            scores = floatArrayOf(0.9f),
            count = 1f,
            transform = transform,
        )
        assertTrue("a box entirely in the letterbox padding is not a valid obstacle", results.isEmpty())
    }

    @Test
    fun labelIndexOutsideLabelMapIsDropped() {
        val shortConfig = OdInferenceConfig(
            scoreThreshold = 0.5f,
            maxDetections = 12,
            allowedClassIds = intArrayOf(5),
            labels = listOf("person", "bicycle", "car"),
        )
        val results = decode(
            boxes = boxes(0.1f, 0.1f, 0.2f, 0.2f),
            classes = floatArrayOf(5f),
            scores = floatArrayOf(0.9f),
            count = 1f,
            config = shortConfig,
        )
        assertTrue(results.isEmpty())
    }

    @Test
    fun quantizeRgbMatchesMetadataNormalizationAndAffineQuantization() {
        val source = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f, Float.NaN, -1f, 2f)
        val destination = ByteArray(source.size)
        LiteRtObjectDetector.quantizeRgb(
            source,
            destination,
            LiteRtObjectDetector.INPUT_QUANT_SCALE,
            LiteRtObjectDetector.INPUT_ZERO_POINT,
        )
        assertEquals(0, destination[0].toInt() and 0xFF)
        assertEquals(63, destination[1].toInt() and 0xFF)
        assertEquals(127, destination[2].toInt() and 0xFF)
        assertEquals(191, destination[3].toInt() and 0xFF)
        assertEquals(255, destination[4].toInt() and 0xFF)
        assertEquals("non-finite samples become black", 0, destination[5].toInt() and 0xFF)
        assertEquals(0, destination[6].toInt() and 0xFF)
        assertEquals(255, destination[7].toInt() and 0xFF)
    }

    @Test
    fun quantizationIsMonotonicAndSaturatedAcrossTheUnitRange() {
        val source = FloatArray(256) { it / 255f }
        val destination = ByteArray(source.size)
        LiteRtObjectDetector.quantizeRgb(
            source,
            destination,
            LiteRtObjectDetector.INPUT_QUANT_SCALE,
            LiteRtObjectDetector.INPUT_ZERO_POINT,
        )
        var previous = -1
        for (index in destination.indices) {
            val value = destination[index].toInt() and 0xFF
            assertTrue("quantized values must be monotonic at $index", value >= previous)
            previous = value
        }
        assertEquals(0f, source[0], 0f)
        assertEquals(255, destination[255].toInt() and 0xFF)
    }

    @Test
    fun detectedObjectFactoryRejectsInvalidGeometryAndScores() {
        assertTrue(DetectedObject.createOrNull(0, "person", 0.9f, 0.1f, 0.1f, 0.2f, 0.2f) != null)
        assertTrue(DetectedObject.createOrNull(-1, "person", 0.9f, 0.1f, 0.1f, 0.2f, 0.2f) == null)
        assertTrue(DetectedObject.createOrNull(0, "person", 0f, 0.1f, 0.1f, 0.2f, 0.2f) == null)
        assertTrue(DetectedObject.createOrNull(0, "person", 1.1f, 0.1f, 0.1f, 0.2f, 0.2f) == null)
        assertTrue(DetectedObject.createOrNull(0, "person", Float.NaN, 0.1f, 0.1f, 0.2f, 0.2f) == null)
        assertTrue(DetectedObject.createOrNull(0, "person", 0.9f, 0.3f, 0.1f, 0.2f, 0.2f) == null)
        assertTrue(DetectedObject.createOrNull(0, "person", 0.9f, 0.1f, 0.3f, 0.2f, 0.2f) == null)
        val clipped = DetectedObject.createOrNull(0, "person", 0.9f, -0.5f, -0.5f, 1.5f, 1.5f)
        assertTrue(clipped != null)
        assertEquals(0f, clipped!!.left, 0f)
        assertEquals(1f, clipped.bottom, 0f)
        assertTrue(clipped.hasValidGeometry)
        assertFalse(DetectedObject(0, "person", 0.9f, 0.3f, 0.1f, 0.2f, 0.2f).hasValidGeometry)
    }

    @Test
    fun scoreThresholdBoundaryIsInclusive() {
        val results = decode(
            boxes = boxes(0.1f, 0.1f, 0.2f, 0.2f),
            classes = floatArrayOf(2f),
            scores = floatArrayOf(LiteRtObjectDetector.SCORE_THRESHOLD),
            count = 1f,
        )
        assertEquals(1, results.size)
        assertEquals("car", results[0].label)
        assertEquals(LiteRtObjectDetector.SCORE_THRESHOLD, results[0].score, 0f)
    }

    @Test
    fun clearOutputBuffersPreventsBufferOverflowAcrossWarmupAndRepeatedInvokes() {
        val boxesBuffer = java.nio.ByteBuffer.allocateDirect(
            LiteRtObjectDetector.MODEL_SLOT_COUNT * LiteRtObjectDetector.BOX_COORDS * Float.SIZE_BYTES,
        )
        val classesBuffer = java.nio.ByteBuffer.allocateDirect(
            LiteRtObjectDetector.MODEL_SLOT_COUNT * Float.SIZE_BYTES,
        )
        val scoresBuffer = java.nio.ByteBuffer.allocateDirect(
            LiteRtObjectDetector.MODEL_SLOT_COUNT * Float.SIZE_BYTES,
        )
        val countBuffer = java.nio.ByteBuffer.allocateDirect(Float.SIZE_BYTES)
        val outputs = arrayOf(boxesBuffer, classesBuffer, scoresBuffer, countBuffer)

        fun simulateTensorImplCopyTo() {
            for (dst in outputs) {
                val payload = ByteArray(dst.capacity())
                // LiteRT TensorImpl.copyTo(Buffer) writes via ((ByteBuffer) dst).put(...) at current position.
                dst.put(payload)
                assertEquals("TensorImpl.copyTo advances position to limit", 0, dst.remaining())
            }
        }

        // Warm-up pass
        LiteRtObjectDetector.clearOutputBuffers(*outputs)
        simulateTensorImplCopyTo()

        // First real detect() pass and subsequent passes must not throw BufferOverflowException
        repeat(3) {
            LiteRtObjectDetector.clearOutputBuffers(*outputs)
            for (dst in outputs) {
                assertEquals(0, dst.position())
                assertEquals(dst.capacity(), dst.remaining())
            }
            simulateTensorImplCopyTo()
        }
    }
}
