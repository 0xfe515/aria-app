package com.ariateam.aria.hazard

import com.ariateam.aria.vision.VisionContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HazardDetectorTest {

    private lateinit var detector: HazardDetector
    private val mask = ByteArray(VisionContract.MODEL_WIDTH * VisionContract.MODEL_HEIGHT)

    @Before
    fun setUp() {
        detector = HazardDetector()
        // Default mask: all sidewalk (class 2: walkable)
        mask.fill(2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun assessHazard_rejectsMaskSmallerThanModelResolution() {
        val shortMask = ByteArray(100)
        val motion = MotionState(isCalibrated = true, confidence = 1.0f, degradedReason = null)
        detector.assessHazard(shortMask, motion, 1_000_000_000L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun assessHazard_rejectsZeroOrNegativeTimestamp() {
        val motion = MotionState(isCalibrated = true, confidence = 1.0f, degradedReason = null)
        detector.assessHazard(mask, motion, 0L)
    }

    @Test
    fun uncalibratedOrDegradedMotion_returnsUnknownDegraded() {
        val uncalibratedMotion = MotionState(
            isCalibrated = false,
            confidence = 0f,
            degradedReason = MotionDegradedReason.CALIBRATION_PENDING,
        )
        val result = detector.assessHazard(mask, uncalibratedMotion, 1_000_000_000L)
        assertEquals(HazardLevel.UNKNOWN_DEGRADED, result.level)
        assertFalse(result.isAlertCandidate)
        assertTrue(result.messageKorean.contains("보행 정면 캘리브레이션"))

        val tiltDegradedMotion = MotionState(
            isCalibrated = true,
            confidence = 0.3f,
            degradedReason = MotionDegradedReason.CAMERA_TILT_INVALID,
        )
        val tiltResult = detector.assessHazard(mask, tiltDegradedMotion, 1_000_000_000L)
        assertEquals(HazardLevel.UNKNOWN_DEGRADED, tiltResult.level)
        assertTrue(tiltResult.messageKorean.contains("보행 시야 이탈"))
    }

    @Test
    fun clearWalkablePath_returnsClearUnwarned() {
        val motion = MotionState(
            isCalibrated = true,
            activeCorridor = CorridorDirection.CENTER,
            confidence = 1.0f,
            degradedReason = null,
        )
        val assessment = detector.assessHazard(mask, motion, 1_000_000_000L)
        assertEquals(HazardLevel.CLEAR_UNWARNED, assessment.level)
        assertFalse(assessment.isAlertCandidate)
        assertEquals(1.0f, assessment.corridorWalkableRatio, 1e-4f)
        assertEquals(0f, assessment.nearObstacleOccupancy, 1e-4f)
        assertTrue(assessment.messageKorean.contains("경고 조건 미감지"))
    }

    @Test
    fun blockedWalkingCorridor_returnsPathBlocked() {
        val motion = MotionState(
            isCalibrated = true,
            activeCorridor = CorridorDirection.CENTER,
            confidence = 1.0f,
            degradedReason = null,
        )
        // Fill center corridor in bottom 45% ROI (rows 158..287) with vehicle (class 5: non-walkable)
        val width = VisionContract.MODEL_WIDTH
        for (y in 158 until 288) {
            val rowOffset = y * width
            for (x in 171..341) {
                mask[rowOffset + x] = 5 // vehicle
            }
        }

        val assessment = detector.assessHazard(mask, motion, 1_000_000_000L)
        // Since near proximity (230..287) is also 100% blocked, near hazard triggers first (higher urgency)
        assertEquals(HazardLevel.NEAR_PATH_HAZARD, assessment.level)
        assertTrue(assessment.isAlertCandidate)
        assertEquals(5, assessment.dominantNearClass)
        assertEquals(5, assessment.dominantNonWalkableClass)
        assertTrue(assessment.nearObstacleOccupancy > 0.35f)
    }

    @Test
    fun farObstacleOnly_triggersPathBlockedWithoutNearHazard() {
        val motion = MotionState(
            isCalibrated = true,
            activeCorridor = CorridorDirection.CENTER,
            confidence = 1.0f,
            degradedReason = null,
        )
        // Fill ONLY rows 158..229 (far obstacle in ROI) with road (class 1: non-walkable)
        // Leave rows 230..287 (near zone) clear sidewalk (class 2: walkable)
        val width = VisionContract.MODEL_WIDTH
        for (y in 158 until 230) {
            val rowOffset = y * width
            for (x in 171..341) {
                mask[rowOffset + x] = 1 // road (non-walkable)
            }
        }

        val assessment = detector.assessHazard(mask, motion, 1_000_000_000L)
        // Far rows (72 rows) blocked out of 130 total ROI rows = ~55.4% blocked -> walkable ~44.6% < 65% (CAUTION)
        assertEquals(HazardLevel.PATH_CAUTION, assessment.level)
        assertFalse(assessment.isAlertCandidate)
        assertEquals(0f, assessment.nearObstacleOccupancy, 1e-4f)

        // Block more far rows so walkable < 40%
        for (y in 158 until 235) {
            val rowOffset = y * width
            for (x in 171..341) {
                mask[rowOffset + x] = 1
            }
        }
        val blockedAssessment = detector.assessHazard(mask, motion, 1_000_000_000L)
        assertTrue("Walkable should be low", blockedAssessment.corridorWalkableRatio < 0.45f)
    }

    @Test
    fun corridorSlicing_isolatesLeftAndRightObstacles() {
        val width = VisionContract.MODEL_WIDTH
        // Block ONLY LEFT corridor (cols 0..170) with pole_sign (class 6)
        for (y in 158 until 288) {
            val rowOffset = y * width
            for (x in 0..170) {
                mask[rowOffset + x] = 6 // pole_sign
            }
        }

        val centerMotion = MotionState(
            isCalibrated = true,
            activeCorridor = CorridorDirection.CENTER,
            confidence = 1.0f,
            degradedReason = null,
        )
        val centerAssessment = detector.assessHazard(mask, centerMotion, 1_000_000_000L)
        // Center path is clear!
        assertEquals(HazardLevel.CLEAR_UNWARNED, centerAssessment.level)
        assertFalse(centerAssessment.isAlertCandidate)

        val leftMotion = MotionState(
            isCalibrated = true,
            activeCorridor = CorridorDirection.LEFT,
            confidence = 1.0f,
            degradedReason = null,
        )
        val leftAssessment = detector.assessHazard(mask, leftMotion, 1_000_000_000L)
        // Left path is blocked!
        assertTrue(leftAssessment.isAlertCandidate)
        assertEquals(CorridorDirection.LEFT, leftAssessment.activeCorridor)
        assertEquals(6, leftAssessment.dominantNearClass)
    }

    @Test
    fun assessHazard_populatesSeverityAndCauseFields_whilePreservingLegacyOutputs() {
        val motion = MotionState(
            isCalibrated = true,
            activeCorridor = CorridorDirection.CENTER,
            confidence = 1.0f,
            degradedReason = null,
            activity = UserMovementActivity.WALKING,
        )
        val assessment = detector.assessHazard(mask, motion, 1_000_000_000L)
        // Legacy assertions
        assertEquals(HazardLevel.CLEAR_UNWARNED, assessment.level)
        assertFalse(assessment.isAlertCandidate)
        assertEquals(1.0f, assessment.corridorWalkableRatio, 1e-4f)
        assertEquals(0f, assessment.nearObstacleOccupancy, 1e-4f)
        assertTrue(assessment.messageKorean.contains("경고 조건 미감지"))

        // Additive severity and cause fields
        assertEquals(AlertSeverity.NONE, assessment.severity)
        assertEquals(HazardCause.NONE, assessment.cause)
        assertEquals(0f, assessment.riskScore, 1e-4f)
    }

    @Test
    fun assessHazard_degradedMotion_populatesDegradedSeverityAndCause() {
        val motion = MotionState(
            isCalibrated = false,
            confidence = 0f,
            degradedReason = MotionDegradedReason.CALIBRATION_PENDING,
        )
        val assessment = detector.assessHazard(mask, motion, 1_000_000_000L)
        assertEquals(HazardLevel.UNKNOWN_DEGRADED, assessment.level)
        assertFalse(assessment.isAlertCandidate)
        assertEquals(AlertSeverity.DEGRADED, assessment.severity)
        assertEquals(HazardCause.DEGRADED, assessment.cause)
    }

    @Test
    fun assessHazard_roadUnderfoot_populatesWarningAndCauseW3() {
        val motion = MotionState(
            isCalibrated = true,
            activeCorridor = CorridorDirection.CENTER,
            confidence = 1.0f,
            degradedReason = null,
            activity = UserMovementActivity.WALKING,
        )
        // Fill near zone (rows 230..287) of center corridor (171..341) with road (class 1: non-walkable)
        val width = VisionContract.MODEL_WIDTH
        for (y in 230 until 288) {
            val rowOffset = y * width
            for (x in 171..341) {
                mask[rowOffset + x] = 1 // road
            }
        }
        val assessment = detector.assessHazard(mask, motion, 1_000_000_000L)
        // Legacy checks
        assertEquals(HazardLevel.NEAR_PATH_HAZARD, assessment.level)
        assertTrue(assessment.isAlertCandidate)
        assertEquals(1, assessment.dominantNearClass)

        // New fields
        assertEquals(AlertSeverity.WARNING, assessment.severity)
        assertEquals(HazardCause.W3_ROAD_UNDERFOOT, assessment.cause)
    }

    @Test
    fun reset_delegatesToRuleEngineWithoutError() {
        detector.reset()
    }

    @Test
    fun assessHazard_approachingVehicleTrack_triggersW1VehicleApproachWarningAndNearPathHazard() {
        val motion = MotionState(
            isCalibrated = true,
            activeCorridor = CorridorDirection.CENTER,
            confidence = 1.0f,
            degradedReason = null,
            activity = UserMovementActivity.WALKING,
        )
        val vehicleTrack = ObjectTrack(
            id = 1,
            kind = ObjectKind.VEHICLE,
            score = 0.9f,
            left = 0.4f,
            top = 0.4f,
            right = 0.6f,
            bottom = 0.8f,
            distanceMeters = 3.0f,
            lateralMeters = 0f,
            ttcSeconds = 2.5f,
            lateralVelocityNorm = 0.05f,
            corridorOverlap = 1.0f,
            confirmed = true,
            segAgree = true,
            lastCaptureNanos = 1_000_000_000L,
        )
        val assessment = detector.assessHazard(
            classMask = mask,
            motionState = motion,
            timestampNanos = 1_000_000_000L,
            tracks = listOf(vehicleTrack),
            applyRuleUpgrade = true,
        )
        assertEquals(AlertSeverity.WARNING, assessment.severity)
        assertEquals(HazardCause.W1_VEHICLE_APPROACH, assessment.cause)
        assertEquals(HazardLevel.NEAR_PATH_HAZARD, assessment.level)
        assertTrue(assessment.isAlertCandidate)
        assertTrue(assessment.messageKorean.contains("차량"))
    }

    @Test
    fun assessHazard_cannyHorizontalWallFootlineAcrossTwoFrames_triggersW4StaticImminentWarning() {
        val motion = MotionState(
            isCalibrated = true,
            activeCorridor = CorridorDirection.CENTER,
            confidence = 1.0f,
            degradedReason = null,
            activity = UserMovementActivity.WALKING,
        )
        val width = VisionContract.MODEL_WIDTH
        val height = VisionContract.MODEL_HEIGHT

        // Set up horizontal transition at row 200 in active corridor (171..341)
        // Rows 0..200: class 0 (other)
        // Rows 201..287: class 2 (sidewalk)
        for (y in 0..200) {
            val offset = y * width
            for (x in 171..341) {
                mask[offset + x] = 0
            }
        }
        for (y in 201 until height) {
            val offset = y * width
            for (x in 171..341) {
                mask[offset + x] = 2
            }
        }

        // Luma buffer with high vertical contrast at row 200: 0.0 above, 1.0 below
        val luma = FloatArray(width * height)
        for (y in 201 until height) {
            val offset = y * width
            for (x in 0 until width) {
                luma[offset + x] = 1.0f
            }
        }

        val mockProjector = PointProjector { _, _ ->
            GroundIntersection(
                forwardDistanceMeters = 0.9f,
                lateralDistanceMeters = 0f,
                totalDistanceMeters = 0.9f,
                rayDistanceMeters = 0.9f,
                intersectionPoint = Vector3D(0f, 0.9f, 0f),
            )
        }

        // Frame 1 at t1 = 1_000_000_000L
        val t1 = 1_000_000_000L
        detector.assessHazard(
            classMask = mask,
            motionState = motion,
            timestampNanos = t1,
            tracks = emptyList(),
            lumaOrRgb = luma,
            projector = mockProjector,
            applyRuleUpgrade = true,
        )

        // Frame 2 at t2 = 1_100_000_000L (100ms later, confirms footline)
        val t2 = 1_100_000_000L
        val assessment = detector.assessHazard(
            classMask = mask,
            motionState = motion,
            timestampNanos = t2,
            tracks = emptyList(),
            lumaOrRgb = luma,
            projector = mockProjector,
            applyRuleUpgrade = true,
        )

        assertEquals(AlertSeverity.WARNING, assessment.severity)
        assertEquals(HazardCause.W4_STATIC_IMMINENT, assessment.cause)
        assertEquals(HazardLevel.NEAR_PATH_HAZARD, assessment.level)
        assertTrue(assessment.isAlertCandidate)
        assertTrue(assessment.messageKorean.contains("정지.") && assessment.messageKorean.contains("장애물"))
    }

    @Test
    fun assessHazard_staticObstacleTrackAt2Meters_triggersC1ObjectInPathCautionAndPathCaution() {
        val motion = MotionState(
            isCalibrated = true,
            activeCorridor = CorridorDirection.CENTER,
            confidence = 1.0f,
            degradedReason = null,
            activity = UserMovementActivity.WALKING,
        )
        val staticTrack = ObjectTrack(
            id = 2,
            kind = ObjectKind.UNKNOWN,
            score = 0.9f,
            left = 0.4f,
            top = 0.4f,
            right = 0.6f,
            bottom = 0.8f,
            distanceMeters = 2.0f,
            lateralMeters = 0f,
            ttcSeconds = null,
            lateralVelocityNorm = 0f,
            corridorOverlap = 1.0f,
            confirmed = true,
            segAgree = true,
            lastCaptureNanos = 1_000_000_000L,
        )
        val assessment = detector.assessHazard(
            classMask = mask,
            motionState = motion,
            timestampNanos = 1_000_000_000L,
            tracks = listOf(staticTrack),
            applyRuleUpgrade = true,
        )
        assertEquals(AlertSeverity.CAUTION, assessment.severity)
        assertEquals(HazardCause.C1_OBJECT_IN_PATH, assessment.cause)
        assertEquals(HazardLevel.PATH_CAUTION, assessment.level)
        assertTrue(assessment.isAlertCandidate)
        assertTrue(assessment.messageKorean.contains("장애물"))
    }
}
