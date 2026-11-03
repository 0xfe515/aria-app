package com.ariateam.aria.distance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundContactDistanceEvaluatorTest {

    private val baseTimeNanos = 10_000_000_000L

    @Test
    fun validGroundContactDistance_updatesDistanceAndCautionRisk() {
        val input = GroundContactEvaluationInput(
            groundContactDistance = GroundContactDistance(
                distanceMeters = 2.4f,
                message = "추정 거리 2.4m",
                timestampNanos = baseTimeNanos,
            ),
            isCameraRunning = true,
            isProfileSet = true,
            isModelReady = true,
            isSensorTracking = true,
            currentNanos = baseTimeNanos + 50_000_000L, // 50ms later
        )

        val result = GroundContactDistanceEvaluator.evaluate(input)
        assertTrue(result.isNumericValid)
        assertEquals(2.4f, result.reportedDistanceMeters ?: 0f, 1e-4f)
        assertEquals(DistanceRisk.CAUTION, result.risk)
        assertEquals(DistanceStatus.TRACKING, result.status)
        assertEquals("추정 거리 2.4m", result.message)
    }

    @Test
    fun urgentThreshold_evaluatesUrgentRisk() {
        val input = GroundContactEvaluationInput(
            groundContactDistance = GroundContactDistance(
                distanceMeters = 1.2f,
                message = "추정 거리 1.2m",
                timestampNanos = baseTimeNanos,
            ),
            isCameraRunning = true,
            isProfileSet = true,
            isModelReady = true,
            isSensorTracking = true,
            currentNanos = baseTimeNanos + 10_000_000L,
        )

        val result = GroundContactDistanceEvaluator.evaluate(input)
        assertTrue(result.isNumericValid)
        assertEquals(1.2f, result.reportedDistanceMeters ?: 0f, 1e-4f)
        assertEquals(DistanceRisk.URGENT, result.risk)
    }

    @Test
    fun clearThreshold_evaluatesClearRisk() {
        val input = GroundContactEvaluationInput(
            groundContactDistance = GroundContactDistance(
                distanceMeters = 3.5f,
                message = "추정 거리 3.5m",
                timestampNanos = baseTimeNanos,
            ),
            isCameraRunning = true,
            isProfileSet = true,
            isModelReady = true,
            isSensorTracking = true,
            currentNanos = baseTimeNanos + 10_000_000L,
        )

        val result = GroundContactDistanceEvaluator.evaluate(input)
        assertTrue(result.isNumericValid)
        assertEquals(3.5f, result.reportedDistanceMeters ?: 0f, 1e-4f)
        assertEquals(DistanceRisk.CLEAR, result.risk)
    }

    @Test
    fun staleTimestamp_fencesNumericDistance() {
        // Frame is 2.0s old (> 1.5s TTL)
        val input = GroundContactEvaluationInput(
            groundContactDistance = GroundContactDistance(
                distanceMeters = 2.0f,
                message = "추정 거리 2.0m",
                timestampNanos = baseTimeNanos,
            ),
            isCameraRunning = true,
            isProfileSet = true,
            isModelReady = true,
            isSensorTracking = true,
            currentNanos = baseTimeNanos + 2_000_000_000L,
        )

        val result = GroundContactDistanceEvaluator.evaluate(input)
        assertFalse("Stale distance must not be marked numeric valid", result.isNumericValid)
        assertNull("Stale distance must be fenced to null", result.reportedDistanceMeters)
        assertEquals(DistanceRisk.UNKNOWN, result.risk)
    }

    @Test
    fun preStopFencing_rejectsQueuedFrames() {
        // Frame arrived with timestamp earlier than minValidTimestampNanos
        val input = GroundContactEvaluationInput(
            groundContactDistance = GroundContactDistance(
                distanceMeters = 2.0f,
                message = "추정 거리 2.0m",
                timestampNanos = baseTimeNanos - 100_000_000L,
            ),
            isCameraRunning = true,
            isProfileSet = true,
            isModelReady = true,
            isSensorTracking = true,
            currentNanos = baseTimeNanos,
            minValidTimestampNanos = baseTimeNanos,
        )

        val result = GroundContactDistanceEvaluator.evaluate(input)
        assertFalse("Pre-stop/recalibration frames must be fenced", result.isNumericValid)
        assertNull(result.reportedDistanceMeters)
    }

    @Test
    fun uncalibratedOrDegradedSensor_fencesNumericDistance() {
        val input = GroundContactEvaluationInput(
            groundContactDistance = GroundContactDistance(
                distanceMeters = 2.0f,
                message = "추정 거리 2.0m",
                timestampNanos = baseTimeNanos,
            ),
            isCameraRunning = true,
            isProfileSet = true,
            isModelReady = true,
            isSensorTracking = false, // Sensor degraded / rapid motion
            currentNanos = baseTimeNanos + 10_000_000L,
        )

        val result = GroundContactDistanceEvaluator.evaluate(input)
        assertFalse("Degraded sensor tracking must fence numeric distance", result.isNumericValid)
        assertNull(result.reportedDistanceMeters)
        assertEquals("기기 센서 안정화 대기 중", result.message)
    }

    @Test
    fun cameraError_causesUnavailableStatusWithDetail() {
        val input = GroundContactEvaluationInput(
            groundContactDistance = GroundContactDistance(
                distanceMeters = 2.0f,
                message = "추정 거리 2.0m",
                timestampNanos = baseTimeNanos,
            ),
            isCameraRunning = false,
            isProfileSet = true,
            isModelReady = true,
            isSensorTracking = true,
            currentNanos = baseTimeNanos + 10_000_000L,
            cameraErrorDetail = "카메라 초기화 실패",
        )

        val result = GroundContactDistanceEvaluator.evaluate(input)
        assertFalse(result.isNumericValid)
        assertNull(result.reportedDistanceMeters)
        assertEquals(DistanceStatus.UNAVAILABLE, result.status)
        assertEquals("카메라 초기화 실패", result.message)
    }

    @Test
    fun modelError_causesUnavailableStatusWithDetail() {
        val input = GroundContactEvaluationInput(
            groundContactDistance = GroundContactDistance(
                distanceMeters = 2.0f,
                message = "추정 거리 2.0m",
                timestampNanos = baseTimeNanos,
            ),
            isCameraRunning = true,
            isProfileSet = true,
            isModelReady = false,
            isSensorTracking = true,
            currentNanos = baseTimeNanos + 10_000_000L,
            modelErrorMessage = "모델 로드 실패",
        )

        val result = GroundContactDistanceEvaluator.evaluate(input)
        assertFalse(result.isNumericValid)
        assertNull(result.reportedDistanceMeters)
        assertEquals(DistanceStatus.UNAVAILABLE, result.status)
        assertEquals("모델 로드 실패", result.message)
    }

    @Test
    fun missingProfile_fencesNumericDistance() {
        val input = GroundContactEvaluationInput(
            groundContactDistance = GroundContactDistance(
                distanceMeters = 2.0f,
                message = "키와 착용 위치를 설정하세요",
                timestampNanos = baseTimeNanos,
            ),
            isCameraRunning = true,
            isProfileSet = false,
            isModelReady = true,
            isSensorTracking = true,
            currentNanos = baseTimeNanos + 50_000_000L,
        )

        val result = GroundContactDistanceEvaluator.evaluate(input)
        assertFalse(result.isNumericValid)
        assertNull(result.reportedDistanceMeters)
        assertEquals(DistanceStatus.PREPARING, result.status)
        assertEquals(DistanceRisk.UNKNOWN, result.risk)
    }

    @Test
    fun nonFiniteOrNegativeDistance_rejected() {
        val invalidValues = listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1.0f, 0f)
        for (v in invalidValues) {
            val input = GroundContactEvaluationInput(
                groundContactDistance = GroundContactDistance(
                    distanceMeters = v,
                    message = "invalid",
                    timestampNanos = baseTimeNanos,
                ),
                isCameraRunning = true,
                isProfileSet = true,
                isModelReady = true,
                isSensorTracking = true,
                currentNanos = baseTimeNanos + 10_000_000L,
            )
            val result = GroundContactDistanceEvaluator.evaluate(input)
            assertFalse("Distance $v must not be valid", result.isNumericValid)
            assertNull("Distance $v must be null", result.reportedDistanceMeters)
        }
    }

    @Test
    fun clearCorridorWithNoCandidate_reportsClear() {
        val input = GroundContactEvaluationInput(
            groundContactDistance = GroundContactDistance(
                distanceMeters = null,
                message = "보행 통로 경고 조건 미감지",
                timestampNanos = baseTimeNanos,
            ),
            isCameraRunning = true,
            isProfileSet = true,
            isModelReady = true,
            isSensorTracking = true,
            currentNanos = baseTimeNanos + 50_000_000L,
        )

        val result = GroundContactDistanceEvaluator.evaluate(input)
        assertFalse(result.isNumericValid)
        assertNull(result.reportedDistanceMeters)
        assertEquals(DistanceStatus.TRACKING, result.status)
        assertEquals(DistanceRisk.CLEAR, result.risk)
    }
}
