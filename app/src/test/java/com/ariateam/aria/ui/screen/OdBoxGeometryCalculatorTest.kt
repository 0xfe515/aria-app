package com.ariateam.aria.ui.screen

import com.ariateam.aria.vision.CameraSessionStatus
import com.ariateam.aria.vision.UprightCropRect
import com.ariateam.aria.vision.VisionContract
import com.ariateam.aria.vision.VisionFramePreprocessor
import com.ariateam.aria.vision.VisionPreprocessingStrategy
import com.ariateam.aria.vision.od.DetectedObject
import com.ariateam.aria.vision.od.OdOverlaySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OdBoxGeometryCalculatorTest {

    private fun makeObj(
        classId: Int = 0,
        label: String = "person",
        score: Float = 0.85f,
        left: Float = 0.10f,
        top: Float = 0.20f,
        right: Float = 0.50f,
        bottom: Float = 0.80f,
    ): DetectedObject = DetectedObject(
        classId = classId,
        label = label,
        score = score,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
    )

    @Test
    fun mapAndClip_mapsNormalizedToContainerPixels() {
        val obj = makeObj(left = 0.10f, top = 0.20f, right = 0.50f, bottom = 0.80f)
        val box = OdBoxGeometryCalculator.mapAndClip(
            detection = obj,
            cropLeftPx = 100f,
            cropTopPx = 200f,
            cropWidthPx = 400f,
            cropHeightPx = 600f,
        )
        assertNotNull(box)
        assertEquals(0, box!!.classId)
        assertEquals("사람", box.koreanLabel)
        assertEquals("사람 85%", box.formattedLabel)
        assertEquals(140f, box.rectLeftPx, 0.01f)
        assertEquals(320f, box.rectTopPx, 0.01f)
        assertEquals(300f, box.rectRightPx, 0.01f)
        assertEquals(680f, box.rectBottomPx, 0.01f)
        assertEquals(160f, box.widthPx, 0.01f)
        assertEquals(360f, box.heightPx, 0.01f)
    }

    @Test
    fun mapAndClip_clampsOutOfBoundsToCropViewport() {
        // Raw coordinates extending outside the 0..1 interval
        val obj = makeObj(left = 0.0f, top = 0.0f, right = 1.0f, bottom = 1.0f)
        val box = OdBoxGeometryCalculator.mapAndClip(
            detection = obj,
            cropLeftPx = 50f,
            cropTopPx = 100f,
            cropWidthPx = 300f,
            cropHeightPx = 500f,
        )
        assertNotNull(box)
        assertEquals(50f, box!!.rectLeftPx, 0.01f)
        assertEquals(100f, box.rectTopPx, 0.01f)
        assertEquals(350f, box.rectRightPx, 0.01f)
        assertEquals(600f, box.rectBottomPx, 0.01f)
    }

    @Test
    fun mapAndClip_rejectsInvalidGeometryAndDegenerateCrop() {
        val valid = makeObj()

        // Zero or negative crop size
        assertNull(OdBoxGeometryCalculator.mapAndClip(valid, 0f, 0f, 0f, 100f))
        assertNull(OdBoxGeometryCalculator.mapAndClip(valid, 0f, 0f, 100f, -10f))

        // Invalid geometry object
        val invalidObj = DetectedObject(
            classId = 0,
            label = "person",
            score = Float.NaN,
            left = 0f,
            top = 0f,
            right = 1f,
            bottom = 1f,
        )
        assertFalse(invalidObj.hasValidGeometry)
        assertNull(OdBoxGeometryCalculator.mapAndClip(invalidObj, 0f, 0f, 100f, 100f))

        // Reversed coordinates
        val reversed = DetectedObject(
            classId = 0,
            label = "person",
            score = 0.9f,
            left = 0.8f,
            top = 0.8f,
            right = 0.2f,
            bottom = 0.2f,
        )
        assertNull(OdBoxGeometryCalculator.mapAndClip(reversed, 0f, 0f, 100f, 100f))
    }

    @Test
    fun mapAndClip_wideLandscapeContainerFitCenter() {
        // Simulates landscape container 800x400 with 720x1280 preview
        val cropRect = UprightCropRect(
            bufferWidth = 1280,
            bufferHeight = 720,
            rotationDegrees = 90,
            uprightWidth = 720,
            uprightHeight = 1280,
            left = 0,
            top = 400,
            width = 720,
            height = 405,
            strategy = VisionPreprocessingStrategy.FULL_FRAME_UPRIGHT_LETTERBOX,
        )

        val vp = VisionFramePreprocessor.computeFitCenterOverlayViewport(
            containerWidthPx = 800f,
            containerHeightPx = 400f,
            cropRect = cropRect,
            previewUprightWidth = 720,
            previewUprightHeight = 1280,
        )

        assertTrue("Preview should be centered horizontally with letterbox padding", vp.previewLeftPx > 0f)
        assertTrue(vp.cropWidthPx > 0f)
        assertTrue(vp.cropHeightPx > 0f)

        val obj = makeObj(left = 0.25f, top = 0.10f, right = 0.75f, bottom = 0.90f)
        val box = OdBoxGeometryCalculator.mapAndClip(
            detection = obj,
            cropLeftPx = vp.cropLeftPx,
            cropTopPx = vp.cropTopPx,
            cropWidthPx = vp.cropWidthPx,
            cropHeightPx = vp.cropHeightPx,
        )

        assertNotNull(box)
        assertTrue("Box left must sit inside crop left", box!!.rectLeftPx >= vp.cropLeftPx)
        assertTrue("Box right must sit inside crop right", box.rectRightPx <= vp.cropLeftPx + vp.cropWidthPx)
        assertTrue("Box top must sit inside crop top", box.rectTopPx >= vp.cropTopPx)
        assertTrue("Box bottom must sit inside crop bottom", box.rectBottomPx <= vp.cropTopPx + vp.cropHeightPx)
    }

    @Test
    fun mapAndClip_tallPortraitContainerFitCenter() {
        // Simulates portrait container 360x720 with 720x1280 preview
        val cropRect = UprightCropRect(
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

        val vp = VisionFramePreprocessor.computeFitCenterOverlayViewport(
            containerWidthPx = 360f,
            containerHeightPx = 720f,
            cropRect = cropRect,
            previewUprightWidth = 720,
            previewUprightHeight = 1280,
        )

        assertTrue("Preview should be centered vertically with letterbox padding", vp.previewTopPx > 0f)

        val obj = makeObj(left = 0.1f, top = 0.3f, right = 0.9f, bottom = 0.7f)
        val box = OdBoxGeometryCalculator.mapAndClip(
            detection = obj,
            cropLeftPx = vp.cropLeftPx,
            cropTopPx = vp.cropTopPx,
            cropWidthPx = vp.cropWidthPx,
            cropHeightPx = vp.cropHeightPx,
        )

        assertNotNull(box)
        assertTrue(box!!.rectLeftPx >= vp.cropLeftPx)
        assertTrue(box.rectRightPx <= vp.cropLeftPx + vp.cropWidthPx)
        assertTrue(box.rectTopPx >= vp.cropTopPx)
        assertTrue(box.rectBottomPx <= vp.cropTopPx + vp.cropHeightPx)
    }

    @Test
    fun evaluateBoxes_expiryGating() {
        val now = 1_000_000_000L
        val obj = makeObj()

        // Fresh snapshot: now < expiresAt
        val freshSnapshot = OdOverlaySnapshot(
            detections = listOf(obj),
            expiresAtElapsedNanos = 1_500_000_000L,
        )
        val freshBoxes = OdBoxGeometryCalculator.evaluateBoxes(
            cameraStatus = CameraSessionStatus.Running,
            modelLoaded = true,
            hasFreshImage = true,
            odOverlay = freshSnapshot,
            nowElapsedRealtimeNanos = now,
            cropLeftPx = 0f,
            cropTopPx = 0f,
            cropWidthPx = 200f,
            cropHeightPx = 200f,
        )
        assertEquals(1, freshBoxes.size)

        // Boundary snapshot: now == expiresAt (still fresh per isFreshAt)
        val exactSnapshot = OdOverlaySnapshot(
            detections = listOf(obj),
            expiresAtElapsedNanos = now,
        )
        val exactBoxes = OdBoxGeometryCalculator.evaluateBoxes(
            cameraStatus = CameraSessionStatus.Running,
            modelLoaded = true,
            hasFreshImage = true,
            odOverlay = exactSnapshot,
            nowElapsedRealtimeNanos = now,
            cropLeftPx = 0f,
            cropTopPx = 0f,
            cropWidthPx = 200f,
            cropHeightPx = 200f,
        )
        assertEquals(1, exactBoxes.size)

        // Expired snapshot: now > expiresAt
        val expiredSnapshot = OdOverlaySnapshot(
            detections = listOf(obj),
            expiresAtElapsedNanos = 999_999_999L,
        )
        val expiredBoxes = OdBoxGeometryCalculator.evaluateBoxes(
            cameraStatus = CameraSessionStatus.Running,
            modelLoaded = true,
            hasFreshImage = true,
            odOverlay = expiredSnapshot,
            nowElapsedRealtimeNanos = now,
            cropLeftPx = 0f,
            cropTopPx = 0f,
            cropWidthPx = 200f,
            cropHeightPx = 200f,
        )
        assertTrue("Expired snapshot must yield empty boxes", expiredBoxes.isEmpty())

        // Invalid zero/negative timestamps
        val invalidSnapshot = OdOverlaySnapshot(
            detections = listOf(obj),
            expiresAtElapsedNanos = 0L,
        )
        assertTrue(
            OdBoxGeometryCalculator.evaluateBoxes(
                cameraStatus = CameraSessionStatus.Running,
                modelLoaded = true,
                hasFreshImage = true,
                odOverlay = invalidSnapshot,
                nowElapsedRealtimeNanos = now,
                cropLeftPx = 0f,
                cropTopPx = 0f,
                cropWidthPx = 200f,
                cropHeightPx = 200f,
            ).isEmpty()
        )
    }

    @Test
    fun evaluateBoxes_cameraAndModelGating() {
        val now = 1_000_000_000L
        val snapshot = OdOverlaySnapshot(
            detections = listOf(makeObj()),
            expiresAtElapsedNanos = 2_000_000_000L,
        )

        // Stopped camera
        assertTrue(
            OdBoxGeometryCalculator.evaluateBoxes(
                cameraStatus = CameraSessionStatus.Stopped,
                modelLoaded = true,
                hasFreshImage = true,
                odOverlay = snapshot,
                nowElapsedRealtimeNanos = now,
                cropLeftPx = 0f,
                cropTopPx = 0f,
                cropWidthPx = 200f,
                cropHeightPx = 200f,
            ).isEmpty()
        )

        // Camera in error
        assertTrue(
            OdBoxGeometryCalculator.evaluateBoxes(
                cameraStatus = CameraSessionStatus.Error,
                modelLoaded = true,
                hasFreshImage = true,
                odOverlay = snapshot,
                nowElapsedRealtimeNanos = now,
                cropLeftPx = 0f,
                cropTopPx = 0f,
                cropWidthPx = 200f,
                cropHeightPx = 200f,
            ).isEmpty()
        )

        // Model not loaded
        assertTrue(
            OdBoxGeometryCalculator.evaluateBoxes(
                cameraStatus = CameraSessionStatus.Running,
                modelLoaded = false,
                hasFreshImage = true,
                odOverlay = snapshot,
                nowElapsedRealtimeNanos = now,
                cropLeftPx = 0f,
                cropTopPx = 0f,
                cropWidthPx = 200f,
                cropHeightPx = 200f,
            ).isEmpty()
        )

        // No fresh overlay image
        assertTrue(
            OdBoxGeometryCalculator.evaluateBoxes(
                cameraStatus = CameraSessionStatus.Running,
                modelLoaded = true,
                hasFreshImage = false,
                odOverlay = snapshot,
                nowElapsedRealtimeNanos = now,
                cropLeftPx = 0f,
                cropTopPx = 0f,
                cropWidthPx = 200f,
                cropHeightPx = 200f,
            ).isEmpty()
        )

        // Null snapshot
        assertTrue(
            OdBoxGeometryCalculator.evaluateBoxes(
                cameraStatus = CameraSessionStatus.Running,
                modelLoaded = true,
                hasFreshImage = true,
                odOverlay = null,
                nowElapsedRealtimeNanos = now,
                cropLeftPx = 0f,
                cropTopPx = 0f,
                cropWidthPx = 200f,
                cropHeightPx = 200f,
            ).isEmpty()
        )

        // Empty detections
        val emptySnapshot = OdOverlaySnapshot(emptyList(), 2_000_000_000L)
        assertTrue(
            OdBoxGeometryCalculator.evaluateBoxes(
                cameraStatus = CameraSessionStatus.Running,
                modelLoaded = true,
                hasFreshImage = true,
                odOverlay = emptySnapshot,
                nowElapsedRealtimeNanos = now,
                cropLeftPx = 0f,
                cropTopPx = 0f,
                cropWidthPx = 200f,
                cropHeightPx = 200f,
            ).isEmpty()
        )
    }

    @Test
    fun koreanClassLabel_andFormatting() {
        assertEquals("사람", formatKoreanClassLabel(0, "person"))
        assertEquals("자전거", formatKoreanClassLabel(1, "bicycle"))
        assertEquals("자동차", formatKoreanClassLabel(2, "car"))
        assertEquals("오토바이", formatKoreanClassLabel(3, "motorcycle"))
        assertEquals("버스", formatKoreanClassLabel(5, "bus"))
        assertEquals("트럭", formatKoreanClassLabel(7, "truck"))
        assertEquals("dog", formatKoreanClassLabel(18, "dog"))
        assertEquals("장애물", formatKoreanClassLabel(99, ""))

        val objPerson = makeObj(classId = 0, score = 0.884f)
        assertEquals("사람 88%", formatOdLabel(objPerson))

        val objCar = makeObj(classId = 2, score = 0.999f)
        assertEquals("자동차 100%", formatOdLabel(objCar))
    }

    @Test
    fun evaluateBoxes_capsAtMax() {
        val many = (0 until 15).map { i ->
            makeObj(classId = 0, left = 0.05f * i, right = 0.05f * i + 0.04f)
        }
        val snapshot = OdOverlaySnapshot(many, 2_000_000_000L)
        val boxes = OdBoxGeometryCalculator.evaluateBoxes(
            cameraStatus = CameraSessionStatus.Running,
            modelLoaded = true,
            hasFreshImage = true,
            odOverlay = snapshot,
            nowElapsedRealtimeNanos = 1_000_000_000L,
            cropLeftPx = 0f,
            cropTopPx = 0f,
            cropWidthPx = 500f,
            cropHeightPx = 500f,
            maxBoxes = 10,
        )
        assertEquals(10, boxes.size)
    }

    @Test
    fun accessibilityDescription() {
        assertNull(OdBoxGeometryCalculator.buildAccessibilityDescription(emptyList()))

        val boxes = listOf(
            OdRenderedBox(0, "사람", "사람 85%", 0.85f, 10f, 10f, 50f, 80f),
            OdRenderedBox(2, "자동차", "자동차 92%", 0.92f, 60f, 10f, 120f, 80f),
        )
        val desc = OdBoxGeometryCalculator.buildAccessibilityDescription(boxes)
        assertNotNull(desc)
        assertEquals("보조 탐지 객체 2개: 사람 85%, 자동차 92%", desc)
    }

    @Test
    fun cropEdgeBoundaries_clippedStrictlyWithinCropViewport() {
        val cropLeft = 40f
        val cropTop = 60f
        val cropWidth = 300f
        val cropHeight = 400f

        // Detection extending beyond top-left corner
        val objTopLeft = makeObj(left = 0.0f, top = 0.0f, right = 0.2f, bottom = 0.3f)
        val boxTopLeft = OdBoxGeometryCalculator.mapAndClip(objTopLeft, cropLeft, cropTop, cropWidth, cropHeight)
        assertNotNull(boxTopLeft)
        assertEquals(cropLeft, boxTopLeft!!.rectLeftPx, 0.001f)
        assertEquals(cropTop, boxTopLeft.rectTopPx, 0.001f)

        // Detection extending beyond bottom-right corner
        val objBottomRight = makeObj(left = 0.8f, top = 0.7f, right = 1.0f, bottom = 1.0f)
        val boxBottomRight = OdBoxGeometryCalculator.mapAndClip(objBottomRight, cropLeft, cropTop, cropWidth, cropHeight)
        assertNotNull(boxBottomRight)
        assertEquals(cropLeft + cropWidth, boxBottomRight!!.rectRightPx, 0.001f)
        assertEquals(cropTop + cropHeight, boxBottomRight.rectBottomPx, 0.001f)
    }

    @Test
    fun fractionalMillisecondDelayCeiling_guaranteesExpiryWakeup() {
        val startNanos = 1_000_000_000L
        // Expiry has a fractional-ms remainder: 1.234567 ms
        val remainingNanos = 1_234_567L
        val expiryNanos = startNanos + remainingNanos
        val snapshot = OdOverlaySnapshot(listOf(makeObj()), expiryNanos)

        // Integer floor division would yield 1 ms delay (too early!)
        val flooredDelayMillis = remainingNanos / 1_000_000L
        assertEquals(1L, flooredDelayMillis)
        val wakingEarlyNanos = startNanos + flooredDelayMillis * 1_000_000L
        // Notice: waking early still has now < expiryNanos, so it would falsely stay fresh!
        assertTrue("Waking early leaves snapshot fresh", snapshot.isFreshAt(wakingEarlyNanos))

        // Ceiling division: ((remainingNanos + 999_999L) / 1_000_000L) yields 2 ms
        val ceilingDelayMillis = ((remainingNanos + 999_999L) / 1_000_000L).coerceAtLeast(1L)
        assertEquals(2L, ceilingDelayMillis)
        val wakingCeilingNanos = startNanos + ceilingDelayMillis * 1_000_000L

        // Upon wake-up with ceiling delay, now > expiryNanos: snapshot is strictly expired!
        assertTrue("Waking at ceiling nanos guarantees now > expiry", wakingCeilingNanos > expiryNanos)
        assertFalse("Snapshot must be expired at ceiling wake time", snapshot.isFreshAt(wakingCeilingNanos))
        assertTrue(
            "evaluateBoxes must return empty when woken via ceiling delay without further publishes",
            OdBoxGeometryCalculator.evaluateBoxes(
                cameraStatus = CameraSessionStatus.Running,
                modelLoaded = true,
                hasFreshImage = true,
                odOverlay = snapshot,
                nowElapsedRealtimeNanos = wakingCeilingNanos,
                cropLeftPx = 0f,
                cropTopPx = 0f,
                cropWidthPx = 100f,
                cropHeightPx = 100f,
            ).isEmpty()
        )
    }

    @Test
    fun exactBoundaryDelay_guaranteesExpiryWakeup() {
        val startNanos = 1_000_000_000L
        val expiryNanos = startNanos // remainingNanos = 0
        val snapshot = OdOverlaySnapshot(listOf(makeObj()), expiryNanos)

        // At exact boundary (now == expiresAtElapsedNanos), isFreshAt is still true!
        assertTrue("At exact boundary, isFreshAt is still true", snapshot.isFreshAt(startNanos))

        // Timer delay calculation: (0L / 1_000_000L) + 1L = 1 ms
        val delayMillis = (0L / 1_000_000L) + 1L
        assertEquals(1L, delayMillis)
        val wakingNanos = startNanos + delayMillis * 1_000_000L

        // Upon wake-up after 1 ms delay, now > expiryNanos: snapshot is strictly expired!
        assertTrue("now > expiryNanos after 1 ms wake-up", wakingNanos > expiryNanos)
        assertFalse("Snapshot is strictly expired after wake-up", snapshot.isFreshAt(wakingNanos))
        assertTrue(
            "evaluateBoxes returns empty at wakingNanos without further camera publishes",
            OdBoxGeometryCalculator.evaluateBoxes(
                cameraStatus = CameraSessionStatus.Running,
                modelLoaded = true,
                hasFreshImage = true,
                odOverlay = snapshot,
                nowElapsedRealtimeNanos = wakingNanos,
                cropLeftPx = 0f,
                cropTopPx = 0f,
                cropWidthPx = 100f,
                cropHeightPx = 100f,
            ).isEmpty()
        )
    }

    @Test
    fun evaluateBoxes_heldSnapshot_persistsBeyondExpiryNanos() {
        val now = 1_000_000_000L
        val obj = makeObj()
        val heldSnapshot = OdOverlaySnapshot(
            detections = listOf(obj),
            expiresAtElapsedNanos = now, // even if expiresAt is now or earlier
            holdUntilNextResult = true,
        )

        // At exact timestamp
        val boxesNow = OdBoxGeometryCalculator.evaluateBoxes(
            cameraStatus = CameraSessionStatus.Running,
            modelLoaded = true,
            hasFreshImage = true,
            odOverlay = heldSnapshot,
            nowElapsedRealtimeNanos = now,
            cropLeftPx = 0f,
            cropTopPx = 0f,
            cropWidthPx = 200f,
            cropHeightPx = 200f,
        )
        assertEquals(1, boxesNow.size)

        // 750 ms later (well past legacy 750ms timeout)
        val boxes750ms = OdBoxGeometryCalculator.evaluateBoxes(
            cameraStatus = CameraSessionStatus.Running,
            modelLoaded = true,
            hasFreshImage = true,
            odOverlay = heldSnapshot,
            nowElapsedRealtimeNanos = now + 750_000_000L,
            cropLeftPx = 0f,
            cropTopPx = 0f,
            cropWidthPx = 200f,
            cropHeightPx = 200f,
        )
        assertEquals("Held snapshot must persist past 750ms", 1, boxes750ms.size)

        // 10 seconds later
        val boxes10s = OdBoxGeometryCalculator.evaluateBoxes(
            cameraStatus = CameraSessionStatus.Running,
            modelLoaded = true,
            hasFreshImage = true,
            odOverlay = heldSnapshot,
            nowElapsedRealtimeNanos = now + 10_000_000_000L,
            cropLeftPx = 0f,
            cropTopPx = 0f,
            cropWidthPx = 200f,
            cropHeightPx = 200f,
        )
        assertEquals("Held snapshot must persist indefinitely until replaced or invalidated", 1, boxes10s.size)

        // Invalid non-positive clock nanos still rejected
        val boxesZeroClock = OdBoxGeometryCalculator.evaluateBoxes(
            cameraStatus = CameraSessionStatus.Running,
            modelLoaded = true,
            hasFreshImage = true,
            odOverlay = heldSnapshot,
            nowElapsedRealtimeNanos = 0L,
            cropLeftPx = 0f,
            cropTopPx = 0f,
            cropWidthPx = 200f,
            cropHeightPx = 200f,
        )
        assertTrue("Zero/negative clock must yield empty boxes", boxesZeroClock.isEmpty())
    }

    @Test
    fun evaluateBoxes_heldSnapshot_clearedOnEmptyOrNullOrStoppedOrMissingModelOrImage() {
        val now = 1_000_000_000L
        val obj = makeObj()
        val heldSnapshot = OdOverlaySnapshot(
            detections = listOf(obj),
            expiresAtElapsedNanos = 2_000_000_000L,
            holdUntilNextResult = true,
        )

        // 1. Cleared on null snapshot
        assertTrue(
            OdBoxGeometryCalculator.evaluateBoxes(
                cameraStatus = CameraSessionStatus.Running,
                modelLoaded = true,
                hasFreshImage = true,
                odOverlay = null,
                nowElapsedRealtimeNanos = now,
                cropLeftPx = 0f,
                cropTopPx = 0f,
                cropWidthPx = 200f,
                cropHeightPx = 200f,
            ).isEmpty()
        )

        // 2. Cleared on empty detections snapshot
        val emptyHeldSnapshot = OdOverlaySnapshot(
            detections = emptyList(),
            expiresAtElapsedNanos = 2_000_000_000L,
            holdUntilNextResult = true,
        )
        assertTrue(
            OdBoxGeometryCalculator.evaluateBoxes(
                cameraStatus = CameraSessionStatus.Running,
                modelLoaded = true,
                hasFreshImage = true,
                odOverlay = emptyHeldSnapshot,
                nowElapsedRealtimeNanos = now,
                cropLeftPx = 0f,
                cropTopPx = 0f,
                cropWidthPx = 200f,
                cropHeightPx = 200f,
            ).isEmpty()
        )

        // 3. Cleared on camera stopped
        assertTrue(
            OdBoxGeometryCalculator.evaluateBoxes(
                cameraStatus = CameraSessionStatus.Stopped,
                modelLoaded = true,
                hasFreshImage = true,
                odOverlay = heldSnapshot,
                nowElapsedRealtimeNanos = now,
                cropLeftPx = 0f,
                cropTopPx = 0f,
                cropWidthPx = 200f,
                cropHeightPx = 200f,
            ).isEmpty()
        )

        // 4. Cleared on camera error
        assertTrue(
            OdBoxGeometryCalculator.evaluateBoxes(
                cameraStatus = CameraSessionStatus.Error,
                modelLoaded = true,
                hasFreshImage = true,
                odOverlay = heldSnapshot,
                nowElapsedRealtimeNanos = now,
                cropLeftPx = 0f,
                cropTopPx = 0f,
                cropWidthPx = 200f,
                cropHeightPx = 200f,
            ).isEmpty()
        )

        // 5. Cleared on model not loaded
        assertTrue(
            OdBoxGeometryCalculator.evaluateBoxes(
                cameraStatus = CameraSessionStatus.Running,
                modelLoaded = false,
                hasFreshImage = true,
                odOverlay = heldSnapshot,
                nowElapsedRealtimeNanos = now,
                cropLeftPx = 0f,
                cropTopPx = 0f,
                cropWidthPx = 200f,
                cropHeightPx = 200f,
            ).isEmpty()
        )

        // 6. Cleared on missing fresh image
        assertTrue(
            OdBoxGeometryCalculator.evaluateBoxes(
                cameraStatus = CameraSessionStatus.Running,
                modelLoaded = true,
                hasFreshImage = false,
                odOverlay = heldSnapshot,
                nowElapsedRealtimeNanos = now,
                cropLeftPx = 0f,
                cropTopPx = 0f,
                cropWidthPx = 200f,
                cropHeightPx = 200f,
            ).isEmpty()
        )
    }

    @Test
    fun evaluateBoxes_heldSnapshot_replacementWithoutFlicker() {
        val now = 1_000_000_000L
        val person = makeObj(classId = 0, label = "person", score = 0.9f)
        val car = makeObj(classId = 2, label = "car", score = 0.95f)

        val firstSnapshot = OdOverlaySnapshot(
            detections = listOf(person),
            expiresAtElapsedNanos = 1_100_000_000L,
            holdUntilNextResult = true,
        )
        val boxes1 = OdBoxGeometryCalculator.evaluateBoxes(
            cameraStatus = CameraSessionStatus.Running,
            modelLoaded = true,
            hasFreshImage = true,
            odOverlay = firstSnapshot,
            nowElapsedRealtimeNanos = now,
            cropLeftPx = 0f,
            cropTopPx = 0f,
            cropWidthPx = 200f,
            cropHeightPx = 200f,
        )
        assertEquals(1, boxes1.size)
        assertEquals("사람", boxes1[0].koreanLabel)

        // Replaced with second snapshot 500ms later (no intermediate empty/flicker)
        val secondSnapshot = OdOverlaySnapshot(
            detections = listOf(car),
            expiresAtElapsedNanos = 1_600_000_000L,
            holdUntilNextResult = true,
        )
        val boxes2 = OdBoxGeometryCalculator.evaluateBoxes(
            cameraStatus = CameraSessionStatus.Running,
            modelLoaded = true,
            hasFreshImage = true,
            odOverlay = secondSnapshot,
            nowElapsedRealtimeNanos = now + 500_000_000L,
            cropLeftPx = 0f,
            cropTopPx = 0f,
            cropWidthPx = 200f,
            cropHeightPx = 200f,
        )
        assertEquals(1, boxes2.size)
        assertEquals("자동차", boxes2[0].koreanLabel)
    }

    @Test
    fun mapAndClip_trafficLightFormatsWithKoreanLabelAndPercentage() {
        val trafficLightObj = makeObj(
            classId = 9,
            label = "traffic light",
            score = 0.92f,
            left = 0.40f,
            top = 0.05f,
            right = 0.60f,
            bottom = 0.25f,
        )
        val box = OdBoxGeometryCalculator.mapAndClip(
            detection = trafficLightObj,
            cropLeftPx = 50f,
            cropTopPx = 100f,
            cropWidthPx = 300f,
            cropHeightPx = 400f,
        )
        assertNotNull(box)
        assertEquals(9, box!!.classId)
        assertEquals("신호등", box.koreanLabel)
        assertEquals("신호등 92%", box.formattedLabel)
        assertEquals(170f, box.rectLeftPx, 0.01f)
        assertEquals(120f, box.rectTopPx, 0.01f)
        assertEquals(230f, box.rectRightPx, 0.01f)
        assertEquals(200f, box.rectBottomPx, 0.01f)
    }

    @Test
    fun formatKoreanClassLabel_mapsAllSupportedClassesIncludingTrafficLight() {
        assertEquals("사람", formatKoreanClassLabel(0))
        assertEquals("자전거", formatKoreanClassLabel(1))
        assertEquals("자동차", formatKoreanClassLabel(2))
        assertEquals("오토바이", formatKoreanClassLabel(3))
        assertEquals("버스", formatKoreanClassLabel(5))
        assertEquals("트럭", formatKoreanClassLabel(7))
        assertEquals("신호등", formatKoreanClassLabel(9))
        assertEquals("장애물", formatKoreanClassLabel(4))
        assertEquals("custom", formatKoreanClassLabel(4, fallbackLabel = "custom"))
    }

    @Test
    fun evaluateBoxes_trafficLightBoxRenderedAndClearedOnCameraStopOrUnfresh() {
        val now = 1_000_000_000L
        val trafficLightObj = makeObj(classId = 9, label = "traffic light", score = 0.90f)
        val snapshot = OdOverlaySnapshot(
            detections = listOf(trafficLightObj),
            expiresAtElapsedNanos = now + 500_000_000L,
            holdUntilNextResult = true,
        )

        // 1. Rendered when active
        val rendered = OdBoxGeometryCalculator.evaluateBoxes(
            cameraStatus = CameraSessionStatus.Running,
            modelLoaded = true,
            hasFreshImage = true,
            odOverlay = snapshot,
            nowElapsedRealtimeNanos = now,
            cropLeftPx = 0f,
            cropTopPx = 0f,
            cropWidthPx = 300f,
            cropHeightPx = 300f,
        )
        assertEquals(1, rendered.size)
        assertEquals(9, rendered[0].classId)
        assertEquals("신호등", rendered[0].koreanLabel)
        assertEquals("신호등 90%", rendered[0].formattedLabel)

        // TalkBack accessibility summary
        val accessibilityDesc = OdBoxGeometryCalculator.buildAccessibilityDescription(rendered)
        assertNotNull(accessibilityDesc)
        assertTrue(accessibilityDesc!!.contains("신호등 90%"))
        assertFalse(accessibilityDesc.contains("초록") || accessibilityDesc.contains("빨강") || accessibilityDesc.contains("건너"))

        // 2. Cleared on camera stop
        val stoppedBoxes = OdBoxGeometryCalculator.evaluateBoxes(
            cameraStatus = CameraSessionStatus.Stopped,
            modelLoaded = true,
            hasFreshImage = true,
            odOverlay = snapshot,
            nowElapsedRealtimeNanos = now,
            cropLeftPx = 0f,
            cropTopPx = 0f,
            cropWidthPx = 300f,
            cropHeightPx = 300f,
        )
        assertTrue(stoppedBoxes.isEmpty())
    }
}
