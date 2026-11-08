package com.ariateam.aria.distance

import com.ariateam.aria.hazard.CorridorDirection
import com.ariateam.aria.hazard.MotionState
import com.ariateam.aria.vision.UprightCropRect
import com.ariateam.aria.vision.VisionPreprocessingStrategy
import com.ariateam.aria.vision.od.DetectedObject
import com.ariateam.aria.vision.od.OdEvidence
import com.ariateam.aria.vision.od.OdEvidenceGate
import com.ariateam.aria.vision.od.OdFusionRejection
import com.ariateam.aria.vision.od.OdGeometryToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OdMaskMatcherTest {

    private val width = 512
    private val height = 288
    private val matcher = OdMaskMatcher()

    private fun det(
        classId: Int,
        label: String = "test",
        score: Float = 0.85f,
        left: Float = 0.4f,
        top: Float = 0.4f,
        right: Float = 0.6f,
        bottom: Float = 0.7f,
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
    fun classMapping_mapsPersonAndVehicles_strictlyExcludesTrafficLightAndOthers() {
        assertEquals(4, matcher.mapOdClassToSemanticClass(0)) // person -> 4
        assertEquals(5, matcher.mapOdClassToSemanticClass(1)) // bicycle -> 5
        assertEquals(5, matcher.mapOdClassToSemanticClass(2)) // car -> 5
        assertEquals(5, matcher.mapOdClassToSemanticClass(3)) // motorcycle -> 5
        assertEquals(5, matcher.mapOdClassToSemanticClass(5)) // bus -> 5
        assertEquals(5, matcher.mapOdClassToSemanticClass(7)) // truck -> 5

        // Category 9 is traffic light: MUST return null
        assertNull(matcher.mapOdClassToSemanticClass(9))
        assertNull(matcher.mapOdClassToSemanticClass(4)) // airplane / other
        assertNull(matcher.mapOdClassToSemanticClass(6)) // train
        assertNull(matcher.mapOdClassToSemanticClass(8)) // boat
        assertNull(matcher.mapOdClassToSemanticClass(-1))
    }

    @Test
    fun qualification_filtersLowScoreAndInvalidGeometry() {
        val validPerson = det(classId = 0, score = 0.8f)
        val lowScoreCar = det(classId = 2, score = 0.45f) // < 0.5 threshold
        val excessiveScore = det(classId = 0, score = 1.25f) // > 1.0 invalid
        val nanScoreBus = det(classId = 5, score = Float.NaN)
        val negativeCoords = DetectedObject(classId = 0, label = "p", score = 0.9f, left = -0.1f, top = 0f, right = 0.5f, bottom = 0.5f)
        val invertedCoords = DetectedObject(classId = 0, label = "p", score = 0.9f, left = 0.6f, top = 0.6f, right = 0.4f, bottom = 0.4f)
        val trafficLight = det(classId = 9, score = 0.95f)

        val input = listOf(validPerson, lowScoreCar, excessiveScore, nanScoreBus, negativeCoords, invertedCoords, trafficLight)
        val qualified = matcher.qualifyDetections(input)

        assertEquals(1, qualified.size)
        assertEquals(validPerson, qualified[0].detection)
        assertEquals(4, qualified[0].targetSemanticClass)
    }

    @Test
    fun fallbackPolicy_missingEmptyLowScoreOrTrafficLightOnly_fallsBackToFullSegmentation() {
        val mask = ByteArray(width * height)
        // Person mask (class 4) at x=250..260, y=140..170
        for (y in 140..170) {
            for (x in 250..260) {
                mask[y * width + x] = 4.toByte()
            }
        }
        // Supporting sidewalk (class 2)
        for (y in 171..174) {
            for (x in 250..260) {
                mask[y * width + x] = 2.toByte()
            }
        }

        // Case 1: Missing OD (null)
        val resultNull = matcher.matchAndExtract(mask, width, height, detections = null)
        assertEquals("Missing OD must fall back to segmentation-only", 1, resultNull.size)
        assertEquals(4, resultNull[0].classId)
        assertFalse(resultNull[0].isFloatingUnsupported)

        // Case 2: Empty OD list
        val resultEmpty = matcher.matchAndExtract(mask, width, height, detections = emptyList())
        assertEquals("Empty OD must fall back to segmentation-only", 1, resultEmpty.size)
        assertEquals(4, resultEmpty[0].classId)

        // Case 3: Low-score detection only (< 0.5f)
        val lowScoreDet = listOf(det(classId = 0, score = 0.4f))
        val resultLowScore = matcher.matchAndExtract(mask, width, height, detections = lowScoreDet)
        assertEquals("Low-score OD must fall back to segmentation-only", 1, resultLowScore.size)
        assertEquals(4, resultLowScore[0].classId)

        // Case 4: Traffic light only (class 9)
        val trafficLightDet = listOf(det(classId = 9, score = 0.95f))
        val resultTrafficLight = matcher.matchAndExtract(mask, width, height, detections = trafficLightDet)
        assertEquals("Traffic-light-only OD must fall back to segmentation-only", 1, resultTrafficLight.size)
        assertEquals(4, resultTrafficLight[0].classId)
    }

    @Test
    fun poleFallback_alwaysRetainedEvenWhenActiveOdDetectsOtherObjects() {
        val mask = ByteArray(width * height)
        // Person (class 4) at x=200..210, y=140..170, supported by sidewalk
        for (y in 140..170) {
            for (x in 200..210) mask[y * width + x] = 4.toByte()
        }
        for (y in 171..174) {
            for (x in 200..210) mask[y * width + x] = 2.toByte()
        }

        // Pole (class 6) at x=300..310, y=140..170, supported by sidewalk
        for (y in 140..170) {
            for (x in 300..310) mask[y * width + x] = 6.toByte()
        }
        for (y in 171..174) {
            for (x in 300..310) mask[y * width + x] = 2.toByte()
        }

        // OD only contains person detection
        val detections = listOf(
            det(classId = 0, left = 190f / width, top = 135f / height, right = 220f / width, bottom = 171f / height)
        )

        val results = matcher.matchAndExtract(mask, width, height, detections)
        assertEquals(2, results.size)

        val pole = results.firstOrNull { it.classId == 6 }
        val person = results.firstOrNull { it.classId == 4 }
        assertNotNull("Pole must be retained via explicit fallback", pole)
        assertNotNull("Person must be matched via OD", person)
        assertEquals(person!!.associatedDetection, detections[0])
        assertNull("Pole has no associated detection", pole!!.associatedDetection)
    }

    @Test
    fun contactDerivation_fromMaskPixelsNotBboxBottom() {
        val mask = ByteArray(width * height)
        // Mask person from y=140 to y=170 at x=250..260 (actual bottom = 170)
        for (y in 140..170) {
            for (x in 250..260) mask[y * width + x] = 4.toByte()
        }
        // Supporting sidewalk
        for (y in 171..174) {
            for (x in 250..260) mask[y * width + x] = 2.toByte()
        }

        // Detection bounding box bottom is at y=176 (different from mask bottom y=170)
        val bboxBottomNorm = 176f / height // ~0.6111
        val bboxCenterNorm = 270f / width // bbox center x=270, mask center x=255
        val detection = det(
            classId = 0,
            left = 240f / width,
            top = 130f / height,
            right = 300f / width,
            bottom = bboxBottomNorm,
        )

        val results = matcher.matchAndExtract(mask, width, height, listOf(detection))
        assertEquals(1, results.size)
        val cp = results[0]

        // Assert observable contact coordinates come from the MASK, NOT the bounding box:
        assertEquals("contactY must equal mask maxY (170.0)", 170f, cp.contactY, 1e-3f)
        assertTrue(
            "contactY (${cp.contactY}) must differ from bbox bottom (${bboxBottomNorm * height})",
            abs(cp.contactY - bboxBottomNorm * height) >= 5f
        )
        assertEquals("contactX must equal mask bottom center (255.0)", 255f, cp.contactX, 1.0f)
        assertTrue(
            "contactX (${cp.contactX}) must differ from bbox center (${bboxCenterNorm * width})",
            abs(cp.contactX - bboxCenterNorm * width) >= 10f
        )
    }

    @Test
    fun spatialAndClassMismatch_rejected() {
        val mask = ByteArray(width * height)
        // Person mask (class 4) at x=250..260, y=140..170
        for (y in 140..170) {
            for (x in 250..260) mask[y * width + x] = 4.toByte()
        }
        for (y in 171..174) {
            for (x in 250..260) mask[y * width + x] = 2.toByte()
        }

        // Case 1: Class mismatch - Car detection (class 2 -> semantic 5) over person mask (4)
        val carDet = det(
            classId = 2,
            left = 240f / width,
            top = 130f / height,
            right = 270f / width,
            bottom = 171f / height,
        )
        val resultClassMismatch = matcher.matchAndExtract(mask, width, height, listOf(carDet))
        assertTrue("Car detection over person mask must not produce candidate", resultClassMismatch.isEmpty())

        // Case 2: Spatial mismatch - Person detection far away from person mask
        val farPersonDet = det(
            classId = 0,
            left = 50f / width,
            top = 130f / height,
            right = 90f / width,
            bottom = 171f / height,
        )
        val resultSpatialMismatch = matcher.matchAndExtract(mask, width, height, listOf(farPersonDet))
        assertTrue("Spatially separated detection must not match mask", resultSpatialMismatch.isEmpty())
    }

    @Test
    fun multipleBoxes_mergedMask_assignsCorrectDistinctContacts() {
        val mask = ByteArray(width * height)
        // Two people merged into one continuous class 4 component via a bridge at y=175:
        // Person 1 (far): x=200..210, y=130..175
        for (y in 130..175) {
            for (x in 200..210) mask[y * width + x] = 4.toByte()
        }
        // Bridge between Person 1 and Person 2 at y=175
        for (x in 211..249) mask[175 * width + x] = 4.toByte()

        // Person 2 (near): x=250..265, y=175..240
        for (y in 175..240) {
            for (x in 250..265) mask[y * width + x] = 4.toByte()
        }

        // Ground support under Person 1
        for (y in 176..179) {
            for (x in 200..210) mask[y * width + x] = 2.toByte()
        }
        // Ground support under Person 2
        for (y in 241..244) {
            for (x in 250..265) mask[y * width + x] = 2.toByte()
        }

        val box1 = det(
            classId = 0,
            left = 195f / width,
            top = 125f / height,
            right = 215f / width,
            bottom = 175f / height,
        )
        val box2 = det(
            classId = 0,
            left = 245f / width,
            top = 170f / height,
            right = 270f / width,
            bottom = 240f / height,
        )

        val results = matcher.matchAndExtract(mask, width, height, listOf(box1, box2))
        assertEquals(2, results.size)

        val cp1 = results.firstOrNull { it.associatedDetection == box1 }
        val cp2 = results.firstOrNull { it.associatedDetection == box2 }
        assertNotNull(cp1)
        assertNotNull(cp2)

        // Distant box 1 MUST NOT receive near box 2's bottom (240)!
        assertEquals("Box 1 must derive its contact near y=175", 175f, cp1!!.contactY, 2f)
        assertEquals("Box 2 must derive its contact near y=240", 240f, cp2!!.contactY, 2f)
    }

    @Test
    fun truncationVerification_tallComponentExtendsOutsideBox_unrelatedGroundDoesNotLegitimizeWindowCutoff() {
        val mask = ByteArray(width * height)
        // Tall person at x=250..260 from y=100 down to y=250 (continues through y=180)
        for (y in 100..250) {
            for (x in 250..260) mask[y * width + x] = 4.toByte()
        }
        // Real ground support at the true bottom of the person (y=251..254)
        for (y in 251..254) {
            for (x in 250..260) mask[y * width + x] = 2.toByte()
        }

        // Broad box covering y=100..180, x=210..290 (box bottom cuts person at waist y=180)
        // Off to the side at x=215..225 (unrelated to the person at 250..260), place ground pixels at y=181..184
        for (y in 181..184) {
            for (x in 215..225) mask[y * width + x] = 2.toByte()
        }

        val cutBox = det(
            classId = 0,
            left = 210f / width,
            top = 100f / height,
            right = 290f / width,
            bottom = 180f / height,
        )

        val results = matcher.matchAndExtract(mask, width, height, listOf(cutBox))
        assertTrue(
            "Window cutoff must be rejected when component continues downward, even with unrelated ground to side",
            results.isEmpty()
        )
    }

    @Test
    fun clippingRejection_touchesBottomEdge_flaggedAsClipped() {
        val mask = ByteArray(width * height)
        // Person mask touching the bottom row (height - 1 = 287)
        for (y in 240 until height) {
            for (x in 250..260) mask[y * width + x] = 4.toByte()
        }

        val box = det(
            classId = 0,
            left = 240f / width,
            top = 230f / height,
            right = 270f / width,
            bottom = 1.0f,
        )

        val results = matcher.matchAndExtract(mask, width, height, listOf(box))
        assertEquals(1, results.size)
        assertTrue("Component touching bottom row must be marked isBottomClipped", results[0].isBottomClipped)
    }

    @Test
    fun floatingUnsupported_rejectedWhenNoGroundSupport() {
        val mask = ByteArray(width * height)
        // Person mask suspended at y=120..150 with NO supporting ground classes below
        for (y in 120..150) {
            for (x in 250..260) mask[y * width + x] = 4.toByte()
        }

        val box = det(
            classId = 0,
            left = 240f / width,
            top = 115f / height,
            right = 270f / width,
            bottom = 150f / height,
        )

        val results = matcher.matchAndExtract(mask, width, height, listOf(box))
        assertEquals(1, results.size)
        assertTrue("Suspended mask without ground support must be isFloatingUnsupported", results[0].isFloatingUnsupported)
    }

    @Test
    fun odEvidenceGateRejection_staleGeometrySession_integratesAndFallsBack() {
        val gate = OdEvidenceGate()
        val transform = UprightCropRect(
            bufferWidth = 640,
            bufferHeight = 480,
            rotationDegrees = 90,
            uprightWidth = 480,
            uprightHeight = 640,
            left = 0,
            top = 0,
            width = 480,
            height = 640,
            strategy = VisionPreprocessingStrategy.FULL_FRAME_UPRIGHT_LETTERBOX,
            contentLeft = 0,
            contentTop = 80,
            contentWidth = 288,
            contentHeight = 512,
            isRotatedClockwise = true,
        )
        val geometry = OdGeometryToken.from(transform, 90)
        val mismatchedGeometry = OdGeometryToken.from(transform.copy(rotationDegrees = 0), 0)
        val motion = MotionState(
            timestampNanos = 1_000L,
            isCalibrated = true,
            activeCorridor = CorridorDirection.CENTER,
            confidence = 0.9f,
            degradedReason = null,
        )
        val sessionToken = 101L

        val mask = ByteArray(width * height)
        for (y in 140..170) {
            for (x in 250..260) mask[y * width + x] = 4.toByte()
        }
        for (y in 171..174) {
            for (x in 250..260) mask[y * width + x] = 2.toByte()
        }

        val baseEvidence = OdEvidence(
            sessionToken = sessionToken,
            geometry = geometry,
            captureTimestampNanos = 1_000_000_000L,
            arrivalElapsedNanos = 500_000_000L,
            completedElapsedNanos = 550_000_000L,
            resultAgeNanos = 50_000_000L,
            detections = listOf(det(classId = 0)),
        )

        // 1. Stale evidence (resultAge > 250ms)
        val staleEvidence = baseEvidence.copy(
            arrivalElapsedNanos = 200_000_000L,
            resultAgeNanos = 300_000_000L,
        )
        val staleGate = gate.accept(
            evidence = staleEvidence,
            frameGeometry = geometry,
            frameCaptureTimestampNanos = 1_000_000_000L,
            nowElapsedNanos = 600_000_000L,
            sessionToken = sessionToken,
            isClockRealtime = true,
            motion = motion,
        )
        assertEquals(OdFusionRejection.STALE_EVIDENCE, staleGate.rejection)
        assertNull("Stale evidence must be rejected by gate", staleGate.accepted)
        // Passes null detections into distance estimator -> falls back to segmentation
        val staleFallback = matcher.matchAndExtract(mask, width, height, staleGate.accepted?.detections)
        assertEquals("Gate rejection must fall back to segmentation candidate", 1, staleFallback.size)
        assertEquals(4, staleFallback[0].classId)

        // 2. Geometry mismatch
        val geoMismatchGate = gate.accept(
            evidence = baseEvidence.copy(geometry = mismatchedGeometry),
            frameGeometry = geometry,
            frameCaptureTimestampNanos = 1_000_000_000L,
            nowElapsedNanos = 550_000_000L,
            sessionToken = sessionToken,
            isClockRealtime = true,
            motion = motion,
        )
        assertEquals(OdFusionRejection.GEOMETRY_MISMATCH, geoMismatchGate.rejection)
        assertNull(geoMismatchGate.accepted)
        val geoFallback = matcher.matchAndExtract(mask, width, height, geoMismatchGate.accepted?.detections)
        assertEquals(1, geoFallback.size)

        // 3. Session mismatch
        val sessionMismatchGate = gate.accept(
            evidence = baseEvidence.copy(sessionToken = 999L),
            frameGeometry = geometry,
            frameCaptureTimestampNanos = 1_000_000_000L,
            nowElapsedNanos = 550_000_000L,
            sessionToken = sessionToken,
            isClockRealtime = true,
            motion = motion,
        )
        assertEquals(OdFusionRejection.SESSION_MISMATCH, sessionMismatchGate.rejection)
        assertNull(sessionMismatchGate.accepted)

        // 4. Future evidence (lag < -50ms tolerance)
        val futureEvidenceGate = gate.accept(
            evidence = baseEvidence.copy(captureTimestampNanos = 1_100_000_000L),
            frameGeometry = geometry,
            frameCaptureTimestampNanos = 1_000_000_000L,
            nowElapsedNanos = 550_000_000L,
            sessionToken = sessionToken,
            isClockRealtime = true,
            motion = motion,
        )
        assertEquals(OdFusionRejection.FUTURE_EVIDENCE, futureEvidenceGate.rejection)
        assertNull(futureEvidenceGate.accepted)

        // 5. Valid fresh accepted evidence
        val validGate = gate.accept(
            evidence = baseEvidence,
            frameGeometry = geometry,
            frameCaptureTimestampNanos = 1_000_000_000L,
            nowElapsedNanos = 550_000_000L,
            sessionToken = sessionToken,
            isClockRealtime = true,
            motion = motion,
        )
        assertEquals(OdFusionRejection.NONE, validGate.rejection)
        val acceptedEvidence = validGate.accepted
        assertNotNull(acceptedEvidence)
        val validOdResult = matcher.matchAndExtract(mask, width, height, acceptedEvidence!!.detections)
        assertEquals(1, validOdResult.size)
        assertEquals(acceptedEvidence.detections[0], validOdResult[0].associatedDetection)

        // 6. Direct integration with GroundContactDistanceEstimator:
        // Rejected gate evidence (null accepted) falls back to pure segmentation distance
        val estimator = GroundContactDistanceEstimator()
        val rad30 = Math.toRadians(30.0).toFloat()
        val orientation = GroundContactOrientation(
            timestampNanos = 1_000_000_000L,
            upVector = com.ariateam.aria.hazard.Vector3D(0f, -kotlin.math.cos(rad30), -kotlin.math.sin(rad30)),
            isReliable = true,
        )
        val profile = BodyMountProfile(statureCm = 170f, position = BodyMountPosition.SHOULDER)
        val fov = com.ariateam.aria.hazard.EffectiveFov(hfovDegrees = 51.54f, vfovDegrees = 38.65f)

        val rejectedDistance = estimator.estimate(
            classMask = mask,
            width = width,
            height = height,
            profile = profile,
            effectiveFov = fov,
            orientation = orientation,
            timestampNanos = 1_000_000_000L,
            isClockRealtime = true,
            detections = staleGate.accepted?.detections, // null!
        )
        assertNotNull("Rejected OD evidence must fall back to valid segmentation distance", rejectedDistance.distanceMeters)

        val acceptedDistance = estimator.estimate(
            classMask = mask,
            width = width,
            height = height,
            profile = profile,
            effectiveFov = fov,
            orientation = orientation,
            timestampNanos = 1_000_000_000L,
            isClockRealtime = true,
            detections = acceptedEvidence.detections, // accepted!
        )
        assertNotNull("Accepted OD evidence drives OD-associated distance", acceptedDistance.distanceMeters)
        assertEquals(rejectedDistance.distanceMeters!!, acceptedDistance.distanceMeters!!, 1e-4f)
    }
}
