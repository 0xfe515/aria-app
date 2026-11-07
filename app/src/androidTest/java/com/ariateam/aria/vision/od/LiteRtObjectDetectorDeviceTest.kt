package com.ariateam.aria.vision.od

import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ariateam.aria.vision.UprightCropRect
import com.ariateam.aria.vision.VisionContract
import com.ariateam.aria.vision.VisionFramePreprocessor
import com.ariateam.aria.vision.VisionPreprocessingStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.FloatBuffer
import java.security.MessageDigest

/**
 * Instrumented contract test for the packaged OD helper asset and the real LiteRT engine.
 *
 * Verifies real model loading, dynamic postprocess tensor allocation, positive obstacle detection
 * on a pinned COCO fixture, repeated invocations after warm-up (regression for direct output
 * ByteBuffer cursor overflow), and end-to-end sidecar overlay snapshot publication.
 */
@RunWith(AndroidJUnit4::class)
class LiteRtObjectDetectorDeviceTest {

    private fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun testContext(): Context = InstrumentationRegistry.getInstrumentation().context

    private fun syntheticTensor(): FloatArray {
        val tensor = FloatArray(VisionContract.INPUT_FLOATS)
        val width = VisionContract.MODEL_INPUT_WIDTH
        val height = VisionContract.MODEL_INPUT_HEIGHT
        var offset = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                tensor[offset] = x.toFloat() / (width - 1)
                tensor[offset + 1] = y.toFloat() / (height - 1)
                tensor[offset + 2] = 0.5f
                offset += 3
            }
        }
        return tensor
    }

    private fun legacyTransform(width: Int = 640, height: Int = 480) =
        VisionFramePreprocessor.computeUprightCropRect(
            bufferWidth = width,
            bufferHeight = height,
            rotationDegrees = 0,
            strategy = VisionPreprocessingStrategy.LEGACY_CROP,
        )

    private fun loadPositiveFixtureTensor(): Pair<FloatArray, UprightCropRect> {
        val bytes = testContext().assets.open(POSITIVE_FIXTURE_ASSET_PATH).use { it.readBytes() }
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals("positive fixture SHA-256 must match pinned COCO image", POSITIVE_FIXTURE_SHA256, sha)

        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull("positive fixture bitmap must decode", bitmap)
        val width = bitmap!!.width
        val height = bitmap.height
        val transform = legacyTransform(width = width, height = height)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()

        val tensor = FloatArray(VisionContract.INPUT_FLOATS)
        val dstWidth = VisionContract.MODEL_INPUT_WIDTH
        val dstHeight = VisionContract.MODEL_INPUT_HEIGHT
        var dstOffset = 0
        for (dstY in 0 until dstHeight) {
            val srcY = (transform.top + ((dstY + 0.5f) * transform.height / dstHeight).toInt())
                .coerceIn(0, height - 1)
            val rowOffset = srcY * width
            for (dstX in 0 until dstWidth) {
                val srcX = (transform.left + ((dstX + 0.5f) * transform.width / dstWidth).toInt())
                    .coerceIn(0, width - 1)
                val argb = pixels[rowOffset + srcX]
                tensor[dstOffset] = ((argb shr 16) and 0xFF) / 255f
                tensor[dstOffset + 1] = ((argb shr 8) and 0xFF) / 255f
                tensor[dstOffset + 2] = (argb and 0xFF) / 255f
                dstOffset += 3
            }
        }
        return tensor to transform
    }

    @Test
    fun packagedAssetMatchesPinnedShaAndIsNotAStub() {
        val assets = context().assets
        val bytes = assets.open(LiteRtObjectDetector.ASSET_MODEL_PATH).use { it.readBytes() }
        assertEquals(LiteRtObjectDetector.MODEL_BYTE_COUNT, bytes.size.toLong())
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sha = digest.joinToString("") { "%02x".format(it) }
        assertEquals(LiteRtObjectDetector.MODEL_SHA256, sha)
    }

    @Test
    fun realEngineLoadsRunsAndReturnsOnlyValidObstacleDetections() {
        val engine = LiteRtObjectDetector(context())
        try {
            assertEquals(90, engine.resolvedLabelCount())
            assertTrue(
                "allowed class ids must match the frozen obstacle set",
                engine.allowedClassIds().toSet() == setOf(0, 1, 2, 3, 5, 7),
            )
            val transform = legacyTransform()
            val results = engine.detect(syntheticTensor(), transform)
            for (detection in results) {
                assertTrue("detection must have valid geometry: $detection", detection.hasValidGeometry)
                assertTrue("score must respect the threshold", detection.score >= LiteRtObjectDetector.SCORE_THRESHOLD)
                assertTrue("class must be in the allowed set", detection.classId in LiteRtObjectDetector.ALLOWED_CLASS_IDS)
                assertTrue("label must be non-blank", detection.label.isNotBlank())
            }
            // A second invocation must behave identically (no leaked per-run state).
            val second = engine.detect(syntheticTensor(), transform)
            assertEquals("repeated inference must be deterministic for the same input", results.size, second.size)
        } finally {
            engine.close()
        }
    }

    @Test
    fun realEngineDetectsPositiveFixtureAcrossRepeatedCallsAndPublishesSidecarOverlay() {
        val (fixtureTensor, transform) = loadPositiveFixtureTensor()
        val engine = LiteRtObjectDetector(context())
        try {
            val first = engine.detect(fixtureTensor, transform)
            assertFalse("positive COCO fixture must yield at least one obstacle detection", first.isEmpty())
            assertTrue(
                "positive COCO fixture must detect a person (classId=0)",
                first.any { it.classId == 0 && it.label.equals("person", ignoreCase = true) },
            )
            for (detection in first) {
                assertTrue("detection must have valid geometry: $detection", detection.hasValidGeometry)
                assertTrue("score must respect the threshold", detection.score >= LiteRtObjectDetector.SCORE_THRESHOLD)
                assertTrue("class must be in the allowed set", detection.classId in LiteRtObjectDetector.ALLOWED_CLASS_IDS)
            }

            val second = engine.detect(fixtureTensor, transform)
            val third = engine.detect(fixtureTensor, transform)
            assertEquals("repeated invocations must not overflow output buffers or drift", first, second)
            assertEquals("third invocation must match first invocation", first, third)
        } finally {
            engine.close()
        }

        var nowNanos = 1_000_000_000L
        val directWorker = object : OdWorkerExecutor {
            override fun execute(command: Runnable): Boolean {
                command.run()
                return true
            }

            override fun shutdown() = Unit
        }
        val pipeline = AuxiliaryObjectDetectionPipeline(
            engineFactory = { LiteRtObjectDetector(context()) },
            worker = directWorker,
            clockNanos = { nowNanos },
            config = OdSidecarConfig(
                followSegmentationCadence = true,
                holdOverlayUntilNextResult = true,
            ),
        )
        try {
            pipeline.setEnabled(true)
            pipeline.onSessionStarted(token = 7L, nowElapsedNanos = nowNanos)
            val outcome1 = pipeline.trySubmit(
                rgb = FloatBuffer.wrap(fixtureTensor),
                transform = transform,
                rotationDegrees = 0,
                captureTimestampNanos = nowNanos,
                arrivalElapsedNanos = nowNanos,
                nowElapsedNanos = nowNanos,
            )
            assertEquals(OdSubmitOutcome.QUEUED, outcome1)

            val telemetry1 = pipeline.telemetry(nowNanos)
            assertEquals("sidecar must not fail with ENGINE_ERROR: ${telemetry1.detail}", 0L, telemetry1.errors)
            assertEquals(OdSidecarStatus.IDLE, telemetry1.status)
            assertEquals(OdBackoffReason.NONE, telemetry1.backoffReason)
            assertEquals(1L, telemetry1.completed)
            assertEquals(0L, telemetry1.rateLimited)
            assertTrue(telemetry1.followsSegmentationCadence)
            assertEquals(0, telemetry1.rateHz)

            val geometry = OdGeometryToken.from(transform, rotationDegrees = 0)
            val overlay1 = pipeline.latestOverlaySnapshot(
                sessionToken = 7L,
                geometry = geometry,
                nowElapsedNanos = nowNanos,
            )
            assertNotNull("fresh positive detection must publish an OdOverlaySnapshot", overlay1)
            assertTrue(overlay1!!.holdUntilNextResult)
            assertTrue(overlay1.detections.any { it.classId == 0 })

            // Second submission spaced <200ms apart (50ms delta) must not be rate-limited in follow mode
            nowNanos += 50_000_000L
            val outcome2 = pipeline.trySubmit(
                rgb = FloatBuffer.wrap(fixtureTensor),
                transform = transform,
                rotationDegrees = 0,
                captureTimestampNanos = nowNanos,
                arrivalElapsedNanos = nowNanos,
                nowElapsedNanos = nowNanos,
            )
            assertEquals(OdSubmitOutcome.QUEUED, outcome2)

            val telemetry2 = pipeline.telemetry(nowNanos)
            assertEquals(2L, telemetry2.submitted)
            assertEquals(2L, telemetry2.completed)
            assertEquals(0L, telemetry2.rateLimited)
            assertTrue(telemetry2.followsSegmentationCadence)

            val overlay2 = pipeline.latestOverlaySnapshot(
                sessionToken = 7L,
                geometry = geometry,
                nowElapsedNanos = nowNanos,
            )
            assertNotNull("second positive detection must update held snapshot", overlay2)
            assertTrue(overlay2!!.holdUntilNextResult)
            assertTrue(overlay2.detections.any { it.classId == 0 })

            // Held overlay must persist beyond the legacy 750ms window without disappearing
            nowNanos += 2_000_000_000L
            val heldOverlay = pipeline.latestOverlaySnapshot(
                sessionToken = 7L,
                geometry = geometry,
                nowElapsedNanos = nowNanos,
            )
            assertNotNull("held positive detection must remain visible past 750ms until next completed result", heldOverlay)
            assertTrue(heldOverlay!!.holdUntilNextResult)
            assertEquals(overlay2.expiresAtElapsedNanos, heldOverlay.expiresAtElapsedNanos)

            val heldTelemetry = pipeline.telemetry(nowNanos)
            assertEquals(2_000L, heldTelemetry.resultAgeMs)
            assertEquals(heldOverlay.detections.size, heldTelemetry.detectionCount)

            // Lifecycle gate: disabling must clear the held overlay snapshot
            pipeline.setEnabled(false)
            assertNull(
                "disabling pipeline must clear the held display snapshot",
                pipeline.latestOverlaySnapshot(sessionToken = 7L, geometry = geometry, nowElapsedNanos = nowNanos),
            )
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun engineDoesNotMutateTheCallerTensor() {
        val engine = LiteRtObjectDetector(context())
        try {
            val tensor = syntheticTensor()
            val copy = tensor.copyOf()
            engine.detect(tensor, legacyTransform())
            assertTrue("engine must never mutate the caller's RGB tensor", tensor.contentEquals(copy))
        } finally {
            engine.close()
        }
    }

    @Test
    fun invalidCallerInputFailsClosedWithEmptyResults() {
        val engine = LiteRtObjectDetector(context())
        try {
            assertTrue("short input is not a valid frame", engine.detect(FloatArray(16), legacyTransform()).isEmpty())
            val degenerate = legacyTransform().copy(width = 0)
            assertTrue("degenerate geometry is not a valid frame", engine.detect(syntheticTensor(), degenerate).isEmpty())
        } finally {
            engine.close()
        }
    }

    @Test
    fun detectAfterCloseFailsAndCloseIsIdempotent() {
        val engine = LiteRtObjectDetector(context())
        engine.close()
        engine.close()
        try {
            engine.detect(syntheticTensor(), legacyTransform())
            fail("detect after close must throw so the sidecar can disable OD")
        } catch (expected: IllegalStateException) {
            assertNotNull(expected.message)
        }
    }

    companion object {
        private const val POSITIVE_FIXTURE_ASSET_PATH = "od/coco_val2017_000000001000.jpg"
        private const val POSITIVE_FIXTURE_SHA256 =
            "24bb77a31928404e45a0454b06f6a0bd54a8db103590c9d7917288a1e0269f05"
    }
}
