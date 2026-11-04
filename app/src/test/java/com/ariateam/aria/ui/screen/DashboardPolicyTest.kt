package com.ariateam.aria.ui.screen

import androidx.compose.ui.unit.dp
import com.ariateam.aria.hazard.CorridorDirection
import com.ariateam.aria.hazard.MotionDegradedReason
import com.ariateam.aria.hazard.MotionState
import com.ariateam.aria.hazard.UserMovementActivity
import com.ariateam.aria.vision.CameraSessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardPolicyTest {

    @Test
    fun normalizeHeadingAngleDeg_exactValues() {
        assertEquals(0f, normalizeHeadingAngleDeg(0f), 0.001f)
        assertEquals(45f, normalizeHeadingAngleDeg(45f), 0.001f)
        assertEquals(-45f, normalizeHeadingAngleDeg(-45f), 0.001f)
        assertEquals(90f, normalizeHeadingAngleDeg(90f), 0.001f)
        assertEquals(-90f, normalizeHeadingAngleDeg(-90f), 0.001f)
        assertEquals(180f, normalizeHeadingAngleDeg(180f), 0.001f)
        assertEquals(-180f, normalizeHeadingAngleDeg(-180f), 0.001f)
    }

    @Test
    fun normalizeHeadingAngleDeg_angleWrapping() {
        // Beyond +180 deg
        assertEquals(-175f, normalizeHeadingAngleDeg(185f), 0.001f)
        assertEquals(-90f, normalizeHeadingAngleDeg(270f), 0.001f)
        assertEquals(0f, normalizeHeadingAngleDeg(360f), 0.001f)
        assertEquals(30f, normalizeHeadingAngleDeg(390f), 0.001f)
        assertEquals(0f, normalizeHeadingAngleDeg(720f), 0.001f)

        // Beyond -180 deg
        assertEquals(175f, normalizeHeadingAngleDeg(-185f), 0.001f)
        assertEquals(90f, normalizeHeadingAngleDeg(-270f), 0.001f)
        assertEquals(0f, normalizeHeadingAngleDeg(-360f), 0.001f)
        assertEquals(-30f, normalizeHeadingAngleDeg(-390f), 0.001f)
        assertEquals(0f, normalizeHeadingAngleDeg(-720f), 0.001f)
    }

    @Test
    fun normalizeHeadingAngleDeg_nonFiniteHandling() {
        assertEquals(0f, normalizeHeadingAngleDeg(Float.NaN), 0.001f)
        assertEquals(0f, normalizeHeadingAngleDeg(Float.POSITIVE_INFINITY), 0.001f)
        assertEquals(0f, normalizeHeadingAngleDeg(Float.NEGATIVE_INFINITY), 0.001f)
    }

    @Test
    fun evaluateHeadingPresentation_cameraStoppedOrHiddenHome() {
        val now = 10_000_000_000L
        val reliableMotion = MotionState(
            timestampNanos = now,
            isCalibrated = true,
            relativeHeadingDeg = 15f,
            confidence = 1.0f,
            degradedReason = null,
        )

        // Camera stopped -> not reliable, avoid authoritative arrow
        val stopped = evaluateHeadingPresentation(
            motionState = reliableMotion,
            cameraStatus = CameraSessionStatus.Stopped,
            isHomeVisible = true,
            currentElapsedRealtimeNanos = now,
        )
        assertFalse(stopped.isReliable)
        assertFalse(stopped.isAuthoritativeArrowEligible)
        assertEquals("상대 방향: 비전 중지됨", stopped.badgeText)
        assertTrue(stopped.talkBackDescription.contains("기기 상대 방위, 이동 경로 아님"))

        // Home not visible -> not reliable
        val hiddenHome = evaluateHeadingPresentation(
            motionState = reliableMotion,
            cameraStatus = CameraSessionStatus.Running,
            isHomeVisible = false,
            currentElapsedRealtimeNanos = now,
        )
        assertFalse(hiddenHome.isReliable)
        assertFalse(hiddenHome.isAuthoritativeArrowEligible)
        assertEquals("상대 방향: 비전 중지됨", hiddenHome.badgeText)
    }

    @Test
    fun evaluateHeadingPresentation_uncalibratedAndPending() {
        val now = 10_000_000_000L

        // Zero timestamp -> calibration pending
        val zeroTime = evaluateHeadingPresentation(
            motionState = MotionState(timestampNanos = 0L),
            cameraStatus = CameraSessionStatus.Running,
            isHomeVisible = true,
            currentElapsedRealtimeNanos = now,
        )
        assertFalse(zeroTime.isReliable)
        assertFalse(zeroTime.isAuthoritativeArrowEligible)
        assertEquals("상대 방향: 보정 대기 중", zeroTime.badgeText)

        // Not calibrated -> avoids authoritative arrow
        val uncalibrated = evaluateHeadingPresentation(
            motionState = MotionState(
                timestampNanos = now,
                isCalibrated = false,
                relativeHeadingDeg = 10f,
                confidence = 0f,
                degradedReason = MotionDegradedReason.CALIBRATION_PENDING,
            ),
            cameraStatus = CameraSessionStatus.Running,
            isHomeVisible = true,
            currentElapsedRealtimeNanos = now,
        )
        assertFalse(uncalibrated.isReliable)
        assertFalse(uncalibrated.isAuthoritativeArrowEligible)
        assertEquals("상대 방향: 보정 대기 중", uncalibrated.badgeText)
    }

    @Test
    fun evaluateHeadingPresentation_staleSamples() {
        val now = 10_000_000_000L
        val staleTime = now - 2_000_000_000L // 2.0s old (> 1.5s tolerance)

        val stale = evaluateHeadingPresentation(
            motionState = MotionState(
                timestampNanos = staleTime,
                isCalibrated = true,
                relativeHeadingDeg = 20f,
                confidence = 1.0f,
                degradedReason = null,
            ),
            cameraStatus = CameraSessionStatus.Running,
            isHomeVisible = true,
            currentElapsedRealtimeNanos = now,
        )
        assertFalse(stale.isReliable)
        assertFalse(stale.isAuthoritativeArrowEligible)
        assertEquals("상대 방향: 신호 지연", stale.badgeText)
    }

    @Test
    fun evaluateHeadingPresentation_degradedReasons() {
        val now = 10_000_000_000L

        val testCases = listOf(
            MotionDegradedReason.SENSORS_UNAVAILABLE to "상대 방향: 센서 미지원",
            MotionDegradedReason.SAMPLES_STALE to "상대 방향: 신호 지연",
            MotionDegradedReason.SAMPLES_GAPPED to "상대 방향: 신호 지연",
            MotionDegradedReason.NON_FINITE_DATA to "상대 방향: 센서 수치 이상",
            MotionDegradedReason.CAMERA_TILT_INVALID to "상대 방향: 카메라 각도 과도",
            MotionDegradedReason.RAPID_TURN_TRANSIENT to "상대 방향: 급격한 회전 중",
            MotionDegradedReason.EXCESSIVE_DRIFT to "상대 방향: 편차 과도 (재보정 필요)",
            MotionDegradedReason.CAMERA_FOV_UNKNOWN to "상대 방향: 시야각 미확인",
        )

        for ((reason, expectedBadge) in testCases) {
            val result = evaluateHeadingPresentation(
                motionState = MotionState(
                    timestampNanos = now,
                    isCalibrated = true,
                    relativeHeadingDeg = 15f,
                    confidence = 0.6f,
                    degradedReason = reason,
                ),
                cameraStatus = CameraSessionStatus.Running,
                isHomeVisible = true,
                currentElapsedRealtimeNanos = now,
            )
            assertFalse("Reason $reason must not be reliable", result.isReliable)
            assertFalse("Reason $reason must not be authoritative arrow eligible", result.isAuthoritativeArrowEligible)
            assertEquals("Badge for $reason", expectedBadge, result.badgeText)
            assertNotNull(result.guidanceReason)
        }
    }

    @Test
    fun evaluateHeadingPresentation_lowConfidence() {
        val now = 10_000_000_000L
        val lowConf = evaluateHeadingPresentation(
            motionState = MotionState(
                timestampNanos = now,
                isCalibrated = true,
                relativeHeadingDeg = 10f,
                confidence = 0.35f, // Below 0.5f threshold
                degradedReason = null,
            ),
            cameraStatus = CameraSessionStatus.Running,
            isHomeVisible = true,
            currentElapsedRealtimeNanos = now,
        )
        assertFalse(lowConf.isReliable)
        assertFalse(lowConf.isAuthoritativeArrowEligible)
        assertEquals("상대 방향: 신뢰도 부족", lowConf.badgeText)
    }

    @Test
    fun evaluateHeadingPresentation_nonFiniteInputsFailClosed() {
        val now = 10_000_000_000L

        // Heading is NaN
        val nanHeading = evaluateHeadingPresentation(
            motionState = MotionState(
                timestampNanos = now,
                isCalibrated = true,
                relativeHeadingDeg = Float.NaN,
                confidence = 0.9f,
                degradedReason = null,
            ),
            cameraStatus = CameraSessionStatus.Running,
            isHomeVisible = true,
            currentElapsedRealtimeNanos = now,
        )
        assertFalse(nanHeading.isReliable)
        assertFalse(nanHeading.isAuthoritativeArrowEligible)
        assertEquals(0f, nanHeading.normalizedHeadingDeg, 0.001f)
        assertEquals("상대 방향: 센서 수치 이상", nanHeading.badgeText)
        assertEquals(MotionDegradedReason.NON_FINITE_DATA.koreanMessage, nanHeading.guidanceReason)
        assertTrue(nanHeading.talkBackDescription.contains("기기 상대 방위, 이동 경로 아님"))

        // Heading is Positive/Negative Infinity
        val posInfHeading = evaluateHeadingPresentation(
            motionState = MotionState(
                timestampNanos = now,
                isCalibrated = true,
                relativeHeadingDeg = Float.POSITIVE_INFINITY,
                confidence = 0.9f,
                degradedReason = null,
            ),
            cameraStatus = CameraSessionStatus.Running,
            isHomeVisible = true,
            currentElapsedRealtimeNanos = now,
        )
        assertFalse(posInfHeading.isReliable)
        assertFalse(posInfHeading.isAuthoritativeArrowEligible)
        assertEquals(0f, posInfHeading.normalizedHeadingDeg, 0.001f)
        assertEquals("상대 방향: 센서 수치 이상", posInfHeading.badgeText)

        val negInfHeading = evaluateHeadingPresentation(
            motionState = MotionState(
                timestampNanos = now,
                isCalibrated = true,
                relativeHeadingDeg = Float.NEGATIVE_INFINITY,
                confidence = 0.9f,
                degradedReason = null,
            ),
            cameraStatus = CameraSessionStatus.Running,
            isHomeVisible = true,
            currentElapsedRealtimeNanos = now,
        )
        assertFalse(negInfHeading.isReliable)
        assertFalse(negInfHeading.isAuthoritativeArrowEligible)
        assertEquals(0f, negInfHeading.normalizedHeadingDeg, 0.001f)
        assertEquals("상대 방향: 센서 수치 이상", negInfHeading.badgeText)

        // Confidence is NaN
        val nanConfidence = evaluateHeadingPresentation(
            motionState = MotionState(
                timestampNanos = now,
                isCalibrated = true,
                relativeHeadingDeg = 15f,
                confidence = Float.NaN,
                degradedReason = null,
            ),
            cameraStatus = CameraSessionStatus.Running,
            isHomeVisible = true,
            currentElapsedRealtimeNanos = now,
        )
        assertFalse(nanConfidence.isReliable)
        assertFalse(nanConfidence.isAuthoritativeArrowEligible)
        assertEquals(0f, nanConfidence.normalizedHeadingDeg, 0.001f)
        assertEquals("상대 방향: 센서 수치 이상", nanConfidence.badgeText)
        assertEquals(MotionDegradedReason.NON_FINITE_DATA.koreanMessage, nanConfidence.guidanceReason)

        // Confidence is Positive/Negative Infinity
        val posInfConfidence = evaluateHeadingPresentation(
            motionState = MotionState(
                timestampNanos = now,
                isCalibrated = true,
                relativeHeadingDeg = 15f,
                confidence = Float.POSITIVE_INFINITY,
                degradedReason = null,
            ),
            cameraStatus = CameraSessionStatus.Running,
            isHomeVisible = true,
            currentElapsedRealtimeNanos = now,
        )
        assertFalse(posInfConfidence.isReliable)
        assertFalse(posInfConfidence.isAuthoritativeArrowEligible)
        assertEquals(0f, posInfConfidence.normalizedHeadingDeg, 0.001f)
        assertEquals("상대 방향: 센서 수치 이상", posInfConfidence.badgeText)

        val negInfConfidence = evaluateHeadingPresentation(
            motionState = MotionState(
                timestampNanos = now,
                isCalibrated = true,
                relativeHeadingDeg = 15f,
                confidence = Float.NEGATIVE_INFINITY,
                degradedReason = null,
            ),
            cameraStatus = CameraSessionStatus.Running,
            isHomeVisible = true,
            currentElapsedRealtimeNanos = now,
        )
        assertFalse(negInfConfidence.isReliable)
        assertFalse(negInfConfidence.isAuthoritativeArrowEligible)
        assertEquals(0f, negInfConfidence.normalizedHeadingDeg, 0.001f)
        assertEquals("상대 방향: 센서 수치 이상", negInfConfidence.badgeText)
    }

    @Test
    fun evaluateHeadingPresentation_confidenceRangeContract() {
        val now = 10_000_000_000L

        // Just below lower threshold (0.49f)
        val belowLower = evaluateHeadingPresentation(
            motionState = MotionState(
                timestampNanos = now,
                isCalibrated = true,
                relativeHeadingDeg = 10f,
                confidence = 0.49f,
                degradedReason = null,
            ),
            cameraStatus = CameraSessionStatus.Running,
            isHomeVisible = true,
            currentElapsedRealtimeNanos = now,
        )
        assertFalse(belowLower.isReliable)
        assertFalse(belowLower.isAuthoritativeArrowEligible)
        assertEquals("상대 방향: 신뢰도 부족", belowLower.badgeText)
        assertEquals("방향 신뢰도 부족 (49%)", belowLower.guidanceReason)

        // Negative out-of-contract confidence (-0.1f)
        val negativeConf = evaluateHeadingPresentation(
            motionState = MotionState(
                timestampNanos = now,
                isCalibrated = true,
                relativeHeadingDeg = 10f,
                confidence = -0.1f,
                degradedReason = null,
            ),
            cameraStatus = CameraSessionStatus.Running,
            isHomeVisible = true,
            currentElapsedRealtimeNanos = now,
        )
        assertFalse(negativeConf.isReliable)
        assertFalse(negativeConf.isAuthoritativeArrowEligible)
        assertEquals("상대 방향: 신뢰도 부족", negativeConf.badgeText)
        assertEquals("방향 신뢰도 범위 이상", negativeConf.guidanceReason)

        // Above upper contract bound (1.01f)
        val aboveUpper = evaluateHeadingPresentation(
            motionState = MotionState(
                timestampNanos = now,
                isCalibrated = true,
                relativeHeadingDeg = 10f,
                confidence = 1.01f,
                degradedReason = null,
            ),
            cameraStatus = CameraSessionStatus.Running,
            isHomeVisible = true,
            currentElapsedRealtimeNanos = now,
        )
        assertFalse(aboveUpper.isReliable)
        assertFalse(aboveUpper.isAuthoritativeArrowEligible)
        assertEquals("상대 방향: 신뢰도 부족", aboveUpper.badgeText)
        assertEquals("방향 신뢰도 범위 이상", aboveUpper.guidanceReason)

        // Extreme positive confidence (2.0f)
        val extremeConf = evaluateHeadingPresentation(
            motionState = MotionState(
                timestampNanos = now,
                isCalibrated = true,
                relativeHeadingDeg = 10f,
                confidence = 2.0f,
                degradedReason = null,
            ),
            cameraStatus = CameraSessionStatus.Running,
            isHomeVisible = true,
            currentElapsedRealtimeNanos = now,
        )
        assertFalse(extremeConf.isReliable)
        assertFalse(extremeConf.isAuthoritativeArrowEligible)
        assertEquals("상대 방향: 신뢰도 부족", extremeConf.badgeText)
        assertEquals("방향 신뢰도 범위 이상", extremeConf.guidanceReason)

        // Boundary: exact 0.5f threshold -> reliable & authoritative
        val exactMin = evaluateHeadingPresentation(
            motionState = MotionState(
                timestampNanos = now,
                isCalibrated = true,
                relativeHeadingDeg = 10f,
                confidence = 0.5f,
                degradedReason = null,
            ),
            cameraStatus = CameraSessionStatus.Running,
            isHomeVisible = true,
            currentElapsedRealtimeNanos = now,
        )
        assertTrue(exactMin.isReliable)
        assertTrue(exactMin.isAuthoritativeArrowEligible)
        assertNull(exactMin.guidanceReason)

        // Boundary: exact 1.0f threshold -> reliable & authoritative
        val exactMax = evaluateHeadingPresentation(
            motionState = MotionState(
                timestampNanos = now,
                isCalibrated = true,
                relativeHeadingDeg = 10f,
                confidence = 1.0f,
                degradedReason = null,
            ),
            cameraStatus = CameraSessionStatus.Running,
            isHomeVisible = true,
            currentElapsedRealtimeNanos = now,
        )
        assertTrue(exactMax.isReliable)
        assertTrue(exactMax.isAuthoritativeArrowEligible)
        assertNull(exactMax.guidanceReason)
    }

    @Test
    fun evaluateHeadingPresentation_reliableForwardLeftRight() {
        val now = 10_000_000_000L

        // Forward within deadband (< 3.0 deg)
        val forward = evaluateHeadingPresentation(
            motionState = MotionState(
                timestampNanos = now,
                isCalibrated = true,
                relativeHeadingDeg = 1.2f,
                confidence = 0.95f,
                degradedReason = null,
            ),
            cameraStatus = CameraSessionStatus.Running,
            isHomeVisible = true,
            currentElapsedRealtimeNanos = now,
        )
        assertTrue(forward.isReliable)
        assertTrue(forward.isAuthoritativeArrowEligible)
        assertEquals("상대 방향: 정면 (0°)", forward.badgeText)
        assertTrue(forward.talkBackDescription.contains("정면 0도"))
        assertTrue(forward.talkBackDescription.contains("기기 상대 방위, 이동 경로 아님"))
        assertNull(forward.guidanceReason)

        // Left yaw (positive angle in Aria: e.g. +18.4 deg)
        val left = evaluateHeadingPresentation(
            motionState = MotionState(
                timestampNanos = now,
                isCalibrated = true,
                relativeHeadingDeg = 18.4f,
                confidence = 1.0f,
                degradedReason = null,
            ),
            cameraStatus = CameraSessionStatus.Running,
            isHomeVisible = true,
            currentElapsedRealtimeNanos = now,
        )
        assertTrue(left.isReliable)
        assertTrue(left.isAuthoritativeArrowEligible)
        assertEquals("상대 방향: 좌측 18°", left.badgeText)
        assertTrue(left.talkBackDescription.contains("좌측 18도"))
        assertTrue(left.talkBackDescription.contains("기기 상대 방위, 이동 경로 아님"))

        // Right yaw (negative angle in Aria: e.g. -24.8 deg)
        val right = evaluateHeadingPresentation(
            motionState = MotionState(
                timestampNanos = now,
                isCalibrated = true,
                relativeHeadingDeg = -24.8f,
                confidence = 1.0f,
                degradedReason = null,
            ),
            cameraStatus = CameraSessionStatus.Running,
            isHomeVisible = true,
            currentElapsedRealtimeNanos = now,
        )
        assertTrue(right.isReliable)
        assertTrue(right.isAuthoritativeArrowEligible)
        assertEquals("상대 방향: 우측 25°", right.badgeText)
        assertTrue(right.talkBackDescription.contains("우측 25도"))
        assertTrue(right.talkBackDescription.contains("기기 상대 방위, 이동 경로 아님"))
    }

    @Test
    fun dashboardSizing_responsiveDimensions() {
        // Standard portrait phone (e.g. Pixel 3: 392dp x 785dp)
        val portrait = DashboardSizing.calculate(
            isLandscape = false,
            screenWidthDp = 392.dp,
            screenHeightDp = 785.dp,
            fontScale = 1.0f,
        )
        // Restores established layout proportions (~224dp x 150dp)
        assertEquals(224.dp, portrait.widthDp)
        assertEquals(150.dp, portrait.heightDp)

        // Portrait with large accessibility font scale shrinks overlay to avoid occluding siblings
        val portraitLargeFont = DashboardSizing.calculate(
            isLandscape = false,
            screenWidthDp = 392.dp,
            screenHeightDp = 785.dp,
            fontScale = 1.3f,
        )
        assertTrue(
            "Large font portrait width must shrink to protect Home controls",
            portraitLargeFont.widthDp <= portrait.widthDp,
        )
        assertTrue(
            "Large font portrait height must shrink to protect Home controls",
            portraitLargeFont.heightDp <= portrait.heightDp,
        )

        // Landscape phone (785dp x 392dp)
        val landscape = DashboardSizing.calculate(
            isLandscape = true,
            screenWidthDp = 785.dp,
            screenHeightDp = 392.dp,
            fontScale = 1.0f,
        )
        // Restores established landscape proportions (~210dp x 140dp)
        assertEquals(210.dp, landscape.widthDp)
        assertEquals(140.dp, landscape.heightDp)
    }

    @Test
    fun headingArrowGeometry_rearFacingAnglesNaturallyHide() {
        val testSizes = listOf(
            Pair(224f, 150f),
            Pair(210f, 140f),
            Pair(180f, 120f),
        )
        val rearAngles = listOf(
            91f, 105f, 120f, 135f, 150f, 170f, 179f, 180f,
            -91f, -105f, -120f, -135f, -150f, -170f, -179f, -180f,
        )

        for ((width, height) in testSizes) {
            val strokeInset = 4f
            for (angle in rearAngles) {
                val geom = HeadingArrowGeometryCalculator.calculate(
                    width = width,
                    height = height,
                    normalizedAngleDeg = angle,
                    isAuthoritativeEligible = true,
                    strokeInset = strokeInset,
                )

                // 1. Origin must be strictly at horizontal center and bottom of preview area
                assertEquals("originX must be horizontal center", width / 2f, geom.originX, 0.01f)
                assertEquals("originY must be at bottom with strokeInset", height - strokeInset, geom.originY, 0.01f)

                // 2. Rearward directions naturally hide the line
                assertFalse("Rearward direction ($angle°) must naturally hide line", geom.isVisible)
                assertEquals("Hidden line must have zero length", 0f, geom.arrowLength, 0.01f)
            }
        }
    }

    @Test
    fun headingArrowGeometry_forwardAndLateralAnglesAreVisibleAndBounded() {
        val testAngles = listOf(0f, 15f, -15f, 30f, -30f, 45f, -45f, 60f, -60f, 90f, -90f)
        val width = 224f
        val height = 150f
        val strokeInset = 4f

        for (angle in testAngles) {
            val geom = HeadingArrowGeometryCalculator.calculate(
                width = width,
                height = height,
                normalizedAngleDeg = angle,
                isAuthoritativeEligible = true,
                strokeInset = strokeInset,
                safePadding = 8f,
            )

            assertTrue("Forward/lateral direction ($angle°) must be visible", geom.isVisible)
            assertEquals("originX must be horizontal center", width / 2f, geom.originX, 0.01f)
            assertEquals("originY must be at bottom", height - strokeInset, geom.originY, 0.01f)

            assertTrue("tipX ($angle°) must be >= 0", geom.tipX >= 0f)
            assertTrue("tipX ($angle°) must be <= width", geom.tipX <= width)
            assertTrue("tipY ($angle°) must be >= 0", geom.tipY >= 0f)
            assertTrue("tipY ($angle°) must point upward or lateral", geom.tipY <= geom.originY)
            assertTrue("arrowLength must be positive", geom.arrowLength >= 10f)
        }
    }

    @Test
    fun headingArrowGeometry_ineligibleHidesLine() {
        val geom = HeadingArrowGeometryCalculator.calculate(
            width = 224f,
            height = 150f,
            normalizedAngleDeg = 0f,
            isAuthoritativeEligible = false,
        )
        assertFalse("Ineligible heading must hide line", geom.isVisible)
        assertEquals(0f, geom.arrowLength, 0.01f)
    }

    @Test
    fun dashboardSizing_extremeAccessibilityFontScale() {
        val normal = DashboardSizing.calculate(isLandscape = false, screenWidthDp = 392.dp, screenHeightDp = 785.dp, fontScale = 1.0f)
        val large = DashboardSizing.calculate(isLandscape = false, screenWidthDp = 392.dp, screenHeightDp = 785.dp, fontScale = 1.5f)
        val largest = DashboardSizing.calculate(isLandscape = false, screenWidthDp = 392.dp, screenHeightDp = 785.dp, fontScale = 2.0f)

        assertTrue(large.widthDp <= normal.widthDp)
        assertTrue(largest.widthDp <= large.widthDp)
        assertTrue(largest.widthDp >= 160.dp)
        assertTrue(largest.heightDp >= 110.dp)
    }
}
