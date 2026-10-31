package com.ariateam.aria.distance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [DepthDistanceEstimator].
 *
 * Verifies geometry bounds, floor point rejection, elevated corridor coverage (off-corridor points
 * and floor-only points do not suffice for clear), outlier rejection, 15th-percentile robust depth calculation,
 * risk threshold boundaries, temporal consistency (no unknown->clear fabrication, alert hysteresis without
 * mixed CAUTION/URGENT escalation), safe transition without null-distance alerts, timestamp anomaly resets
 * (including <=0 timestamp reset), and Korean messaging with "추정 거리" and "경고 조건 미감지" without
 * absolute safety guarantees or TTC.
 */
class DepthDistanceEstimatorTest {

    private lateinit var estimator: DepthDistanceEstimator
    private val groundY: Float = -1.40f // camera 1.4m above floor

    @Before
    fun setUp() {
        estimator = DepthDistanceEstimator()
    }

    /**
     * Helper to create elevated corridor depth points beyond walking range (z = 5.5m..6.4m, body height)
     * establishing valid forward corridor volume coverage without placing arbitrary clutter inside the
     * walking corridor obstacle zone (0.3m..5.0m).
     */
    private fun corridorClearCoverageSamples(count: Int = 10): List<DepthSample> {
        val list = ArrayList<DepthSample>()
        for (i in 0 until count) {
            list.add(
                DepthSample(
                    xMeters = ((i % 3) - 1) * 0.15f,
                    yMeters = groundY + 0.8f,
                    zMeters = 5.50f + (i * 0.10f),
                    confidence = 0.95f
                )
            )
        }
        return list
    }

    /**
     * Helper to create an obstacle cluster of N samples around a target (x, y, z).
     */
    private fun obstacleCluster(
        centerX: Float,
        centerY: Float,
        centerZ: Float,
        count: Int = 5,
        confidence: Float = 0.9f
    ): List<DepthSample> {
        val list = ArrayList<DepthSample>()
        for (i in 0 until count) {
            val offsetX = ((i % 3) - 1) * 0.04f
            val offsetY = ((i / 3) - 1) * 0.04f
            val offsetZ = (i * 0.02f)
            list.add(
                DepthSample(
                    xMeters = centerX + offsetX,
                    yMeters = centerY + offsetY,
                    zMeters = centerZ + offsetZ,
                    confidence = confidence
                )
            )
        }
        return list
    }

    // ==========================================
    // 1. Geometry & Corridor Coverage Tests
    // ==========================================

    @Test
    fun corridorGeometry_inBoundsObstacleCluster_isDetected() {
        val samples = corridorClearCoverageSamples() + obstacleCluster(centerX = 0.0f, centerY = groundY + 0.8f, centerZ = 2.0f, count = 5)

        // Frame 1: warm-up / preparing
        val est1 = estimator.evaluate(samples, timestampNanos = 100_000_000L, groundY = groundY)
        assertEquals(DistanceStatus.PREPARING, est1.status)

        // Frame 2: confirmed tracking
        val est2 = estimator.evaluate(samples, timestampNanos = 133_000_000L, groundY = groundY)
        assertEquals(DistanceStatus.TRACKING, est2.status)
        assertEquals(DistanceRisk.CAUTION, est2.risk)
        assertNotNull(est2.distanceMeters)
        assertEquals(2.0f, est2.distanceMeters!!, 0.15f)
        assertTrue(est2.validSampleCount >= 4)
    }

    @Test
    fun corridorCoverage_offCorridorSamplesOnly_returnsUnavailable() {
        // Samples strictly outside corridor width (|x| > 0.45m); no corridor points
        val offCorridorSamples = (0 until 12).map { i ->
            DepthSample(
                xMeters = 0.80f + (i * 0.05f),
                yMeters = groundY + 0.8f,
                zMeters = 2.0f + (i * 0.1f),
                confidence = 0.95f
            )
        }

        val est = estimator.evaluate(offCorridorSamples, timestampNanos = 100_000_000L, groundY = groundY)
        assertEquals(DistanceStatus.UNAVAILABLE, est.status)
        assertEquals(DistanceRisk.UNKNOWN, est.risk)
        assertTrue(est.message.contains("보행 통로 깊이 관측 부족"))
    }

    @Test
    fun corridorGeometry_outsideForwardDistanceRange_isRejected() {
        // Points at body height but outside 0.3m..5.0m range
        val tooClose = obstacleCluster(centerX = 0.0f, centerY = groundY + 0.8f, centerZ = 0.20f, count = 5)
        val tooFar = obstacleCluster(centerX = 0.0f, centerY = groundY + 0.8f, centerZ = 5.20f, count = 5)
        val samples = corridorClearCoverageSamples() + tooClose + tooFar

        estimator.evaluate(samples, timestampNanos = 100_000_000L, groundY = groundY)
        val est = estimator.evaluate(samples, timestampNanos = 133_000_000L, groundY = groundY)

        assertEquals(DistanceStatus.TRACKING, est.status)
        assertEquals(DistanceRisk.CLEAR, est.risk)
        assertNull(est.distanceMeters)
        assertTrue(est.message.contains("경고 조건 미감지"))
    }

    @Test
    fun corridorGeometry_aboveOverheadClearance_isRejected() {
        // Overhead points: y > groundY + 1.90f
        val overhead = obstacleCluster(centerX = 0.0f, centerY = groundY + 2.10f, centerZ = 1.2f, count = 6)
        val samples = corridorClearCoverageSamples() + overhead

        estimator.evaluate(samples, timestampNanos = 100_000_000L, groundY = groundY)
        val est = estimator.evaluate(samples, timestampNanos = 133_000_000L, groundY = groundY)

        assertEquals(DistanceStatus.TRACKING, est.status)
        assertEquals(DistanceRisk.CLEAR, est.risk)
        assertNull(est.distanceMeters)
    }

    // ==========================================
    // 2. Floor & Coverage Tests
    // ==========================================

    @Test
    fun floorRejection_pointsBelowOrNearFloor_areRejected() {
        // Floor points: y < groundY + 0.12f
        val floorSamples = listOf(
            DepthSample(0.0f, groundY - 0.05f, 1.5f, 0.9f),
            DepthSample(0.1f, groundY + 0.02f, 1.5f, 0.9f),
            DepthSample(-0.1f, groundY + 0.08f, 1.5f, 0.9f),
            DepthSample(0.0f, groundY + 0.10f, 1.5f, 0.9f)
        )
        val samples = corridorClearCoverageSamples() + floorSamples

        estimator.evaluate(samples, timestampNanos = 100_000_000L, groundY = groundY)
        val est = estimator.evaluate(samples, timestampNanos = 133_000_000L, groundY = groundY)

        assertEquals(DistanceStatus.TRACKING, est.status)
        assertEquals(DistanceRisk.CLEAR, est.risk)
        assertNull(est.distanceMeters)
    }

    @Test
    fun coverage_sparseFloorOnlyCoverage_returnsUnavailableNotClear() {
        // Camera pointing down at floor: 20 floor points, 0 corridor body-height points
        val floorOnlySamples = (0 until 20).map { i ->
            DepthSample(
                xMeters = (i % 5 - 2) * 0.1f,
                yMeters = groundY + 0.04f,
                zMeters = 1.0f + (i * 0.1f),
                confidence = 0.9f
            )
        }

        val est1 = estimator.evaluate(floorOnlySamples, timestampNanos = 100_000_000L, groundY = groundY)
        assertEquals(DistanceStatus.UNAVAILABLE, est1.status)
        assertEquals(DistanceRisk.UNKNOWN, est1.risk)

        // Frame 2 still floor-only: must NEVER fabricate clear!
        val est2 = estimator.evaluate(floorOnlySamples, timestampNanos = 133_000_000L, groundY = groundY)
        assertEquals(DistanceStatus.UNAVAILABLE, est2.status)
        assertEquals(DistanceRisk.UNKNOWN, est2.risk)
    }

    @Test
    fun coverage_sufficientCorridorCoverageWithoutCluster_returnsClear() {
        val samples = corridorClearCoverageSamples(count = 10)

        estimator.evaluate(samples, timestampNanos = 100_000_000L, groundY = groundY)
        val est = estimator.evaluate(samples, timestampNanos = 133_000_000L, groundY = groundY)

        assertEquals(DistanceStatus.TRACKING, est.status)
        assertEquals(DistanceRisk.CLEAR, est.risk)
        assertNull(est.distanceMeters)
        assertTrue(est.message.contains("보행 통로 경고 조건 미감지"))
    }

    // ==========================================
    // 3. Outlier & Clustering Tests
    // ==========================================

    @Test
    fun outlierRejection_singletonPoint_doesNotTriggerAlert() {
        val singleton = DepthSample(xMeters = 0.0f, yMeters = groundY + 0.8f, zMeters = 1.0f, confidence = 0.95f)
        val samples = corridorClearCoverageSamples() + singleton

        estimator.evaluate(samples, timestampNanos = 100_000_000L, groundY = groundY)
        val est = estimator.evaluate(samples, timestampNanos = 133_000_000L, groundY = groundY)

        assertEquals(DistanceStatus.TRACKING, est.status)
        assertEquals(DistanceRisk.CLEAR, est.risk)
        assertNull(est.distanceMeters)
    }

    @Test
    fun outlierRejection_smallClusterBelowMinimumSize_isRejected() {
        // 3 points in corridor (below minClusterSize = 4).
        // Background coverage is at z = 5.5m+, well separated from z = 1.2m
        val threePoints = listOf(
            DepthSample(0.0f, groundY + 0.8f, 1.2f, 0.9f),
            DepthSample(0.05f, groundY + 0.82f, 1.22f, 0.9f),
            DepthSample(-0.05f, groundY + 0.78f, 1.24f, 0.9f)
        )
        val samples = corridorClearCoverageSamples() + threePoints

        estimator.evaluate(samples, timestampNanos = 100_000_000L, groundY = groundY)
        val est = estimator.evaluate(samples, timestampNanos = 133_000_000L, groundY = groundY)

        assertEquals(DistanceStatus.TRACKING, est.status)
        assertEquals(DistanceRisk.CLEAR, est.risk)
        assertNull(est.distanceMeters)
    }

    @Test
    fun outlierRejection_spatiallyDisconnectedPoints_doNotMerge() {
        val splitPoints = listOf(
            DepthSample(0.0f, groundY + 0.8f, 1.0f, 0.9f),
            DepthSample(0.05f, groundY + 0.8f, 1.05f, 0.9f),
            DepthSample(0.0f, groundY + 0.8f, 4.0f, 0.9f),
            DepthSample(0.05f, groundY + 0.8f, 4.05f, 0.9f)
        )
        val samples = corridorClearCoverageSamples() + splitPoints

        estimator.evaluate(samples, timestampNanos = 100_000_000L, groundY = groundY)
        val est = estimator.evaluate(samples, timestampNanos = 133_000_000L, groundY = groundY)

        assertEquals(DistanceStatus.TRACKING, est.status)
        assertEquals(DistanceRisk.CLEAR, est.risk)
    }

    @Test
    fun clustering_robustPercentileRejectsFrontEdgeSpike() {
        // Cluster of 5 points where front point is at z = 1.85m and other 4 points at z = 2.00..2.08m
        // Delta Z between 1.85 and 2.00 is 0.15m <= 0.35m, so all 5 belong to the same cluster
        val clusterWithSpike = listOf(
            DepthSample(0.0f, groundY + 0.8f, 1.85f, 0.9f), // front leading point
            DepthSample(0.0f, groundY + 0.82f, 2.00f, 0.9f),
            DepthSample(0.05f, groundY + 0.80f, 2.02f, 0.9f),
            DepthSample(-0.05f, groundY + 0.78f, 2.05f, 0.9f),
            DepthSample(0.02f, groundY + 0.85f, 2.08f, 0.9f)
        )
        val samples = corridorClearCoverageSamples() + clusterWithSpike

        estimator.evaluate(samples, timestampNanos = 100_000_000L, groundY = groundY)
        val est = estimator.evaluate(samples, timestampNanos = 133_000_000L, groundY = groundY)

        assertEquals(DistanceStatus.TRACKING, est.status)
        assertEquals(DistanceRisk.CAUTION, est.risk)
        assertNotNull(est.distanceMeters)
        // 15th percentile of 5 points is index 1 -> 2.00m, suppressing the 1.85m leading spike
        assertEquals(2.00f, est.distanceMeters!!, 0.05f)
    }

    // ==========================================
    // 4. Unknown & Validation Tests
    // ==========================================

    @Test
    fun unknownGround_nullOrInvalidGroundY_returnsUnavailable() {
        val samples = corridorClearCoverageSamples()

        val estNull = estimator.evaluate(samples, timestampNanos = 100_000_000L, groundY = null)
        assertEquals(DistanceStatus.UNAVAILABLE, estNull.status)
        assertEquals(DistanceRisk.UNKNOWN, estNull.risk)
        assertTrue(estNull.message.contains("바닥면 인식 불가"))

        val estPositive = estimator.evaluate(samples, timestampNanos = 133_000_000L, groundY = 0.5f)
        assertEquals(DistanceStatus.UNAVAILABLE, estPositive.status)
        assertEquals(DistanceRisk.UNKNOWN, estPositive.risk)

        val estNaN = estimator.evaluate(samples, timestampNanos = 166_000_000L, groundY = Float.NaN)
        assertEquals(DistanceStatus.UNAVAILABLE, estNaN.status)
        assertEquals(DistanceRisk.UNKNOWN, estNaN.risk)
    }

    @Test
    fun unknownCoverage_insufficientSamplesOverall_returnsUnavailable() {
        val sparse = listOf(
            DepthSample(0.0f, groundY + 0.8f, 2.0f, 0.9f),
            DepthSample(0.1f, groundY + 0.8f, 2.1f, 0.9f)
        )
        val est = estimator.evaluate(sparse, timestampNanos = 100_000_000L, groundY = groundY)
        assertEquals(DistanceStatus.UNAVAILABLE, est.status)
        assertEquals(DistanceRisk.UNKNOWN, est.risk)
        assertTrue(est.message.contains("깊이 데이터 범위 부족"))
    }

    @Test
    fun sampleValidation_infiniteAndOutOfRangeConfidence_areRejected() {
        val invalidConfidenceSamples = listOf(
            DepthSample(0.0f, groundY + 0.8f, 1.2f, Float.POSITIVE_INFINITY),
            DepthSample(0.05f, groundY + 0.8f, 1.2f, 1.5f),
            DepthSample(-0.05f, groundY + 0.8f, 1.2f, 0.49f) // < 0.5f
        )
        val samples = corridorClearCoverageSamples() + invalidConfidenceSamples

        estimator.evaluate(samples, timestampNanos = 100_000_000L, groundY = groundY)
        val est = estimator.evaluate(samples, timestampNanos = 133_000_000L, groundY = groundY)

        assertEquals(DistanceStatus.TRACKING, est.status)
        assertEquals(DistanceRisk.CLEAR, est.risk)
        assertNull(est.distanceMeters)
    }

    // ==========================================
    // 5. Risk Threshold Tests
    // ==========================================

    @Test
    fun riskThreshold_urgentAtOrBelowOnePointFiveMeters() {
        val cluster14 = corridorClearCoverageSamples() + obstacleCluster(0.0f, groundY + 0.8f, 1.40f, count = 5)
        estimator.evaluate(cluster14, timestampNanos = 100_000_000L, groundY = groundY)
        val est14 = estimator.evaluate(cluster14, timestampNanos = 133_000_000L, groundY = groundY)
        assertEquals(DistanceRisk.URGENT, est14.risk)
        assertTrue(est14.message.contains("긴급 장애물"))

        estimator.reset()
        val cluster15 = corridorClearCoverageSamples() + obstacleCluster(0.0f, groundY + 0.8f, 1.48f, count = 5)
        estimator.evaluate(cluster15, timestampNanos = 100_000_000L, groundY = groundY)
        val est15 = estimator.evaluate(cluster15, timestampNanos = 133_000_000L, groundY = groundY)
        assertEquals(DistanceRisk.URGENT, est15.risk)
    }

    @Test
    fun riskThreshold_cautionBetweenOnePointFiveAndThreeMeters() {
        val cluster16 = corridorClearCoverageSamples() + obstacleCluster(0.0f, groundY + 0.8f, 1.60f, count = 5)
        estimator.evaluate(cluster16, timestampNanos = 100_000_000L, groundY = groundY)
        val est16 = estimator.evaluate(cluster16, timestampNanos = 133_000_000L, groundY = groundY)
        assertEquals(DistanceRisk.CAUTION, est16.risk)
        assertTrue(est16.message.contains("주의 장애물"))

        estimator.reset()
        val cluster30 = corridorClearCoverageSamples() + obstacleCluster(0.0f, groundY + 0.8f, 2.95f, count = 5)
        estimator.evaluate(cluster30, timestampNanos = 100_000_000L, groundY = groundY)
        val est30 = estimator.evaluate(cluster30, timestampNanos = 133_000_000L, groundY = groundY)
        assertEquals(DistanceRisk.CAUTION, est30.risk)
    }

    @Test
    fun riskThreshold_clearAboveThreeMetersWithDistanceReported() {
        val cluster35 = corridorClearCoverageSamples() + obstacleCluster(0.0f, groundY + 0.8f, 3.50f, count = 5)
        estimator.evaluate(cluster35, timestampNanos = 100_000_000L, groundY = groundY)
        val est35 = estimator.evaluate(cluster35, timestampNanos = 133_000_000L, groundY = groundY)

        assertEquals(DistanceStatus.TRACKING, est35.status)
        assertEquals(DistanceRisk.CLEAR, est35.risk)
        assertNotNull(est35.distanceMeters)
        assertEquals(3.5f, est35.distanceMeters!!, 0.15f)
        assertTrue(est35.message.contains("경고 조건 미달"))
    }

    // ==========================================
    // 6. Temporal Consistency & Reset Tests
    // ==========================================

    @Test
    fun temporalHysteresis_noUnknownToClearFabrication() {
        val clearSamples = corridorClearCoverageSamples()

        // Frame 1: must be PREPARING and UNKNOWN
        val frame1 = estimator.evaluate(clearSamples, timestampNanos = 100_000_000L, groundY = groundY)
        assertEquals(DistanceStatus.PREPARING, frame1.status)
        assertEquals(DistanceRisk.UNKNOWN, frame1.risk)
        assertNull(frame1.distanceMeters)

        // Frame 2: confirmed tracking and clear
        val frame2 = estimator.evaluate(clearSamples, timestampNanos = 133_000_000L, groundY = groundY)
        assertEquals(DistanceStatus.TRACKING, frame2.status)
        assertEquals(DistanceRisk.CLEAR, frame2.risk)
        assertNull(frame2.distanceMeters)
    }

    @Test
    fun temporalHysteresis_alertRequiresConsecutiveConfirmation() {
        val clearSamples = corridorClearCoverageSamples()
        estimator.evaluate(clearSamples, timestampNanos = 100_000_000L, groundY = groundY)
        val clearEst = estimator.evaluate(clearSamples, timestampNanos = 133_000_000L, groundY = groundY)
        assertEquals(DistanceRisk.CLEAR, clearEst.risk)

        // Frame 3: sudden obstacle appears at 1.2m
        val obstacleSamples = corridorClearCoverageSamples() + obstacleCluster(0.0f, groundY + 0.8f, 1.20f, count = 5)
        val frame3 = estimator.evaluate(obstacleSamples, timestampNanos = 166_000_000L, groundY = groundY)
        // Transition frame: pending confirmation, does NOT fire URGENT yet
        assertEquals("Frame 1 of obstacle should be UNKNOWN transition", DistanceRisk.UNKNOWN, frame3.risk)
        assertNull(frame3.distanceMeters)

        // Frame 4: obstacle confirmed on 2nd frame
        val frame4 = estimator.evaluate(obstacleSamples, timestampNanos = 200_000_000L, groundY = groundY)
        assertEquals("Frame 2 of obstacle must confirm URGENT", DistanceRisk.URGENT, frame4.risk)
        assertNotNull(frame4.distanceMeters)
    }

    @Test
    fun temporalHysteresis_mixedCautionThenUrgentDoesNotPrematurelyEscalate() {
        val cautionSamples = corridorClearCoverageSamples() + obstacleCluster(0.0f, groundY + 0.8f, 2.50f, count = 5)
        val urgentSamples = corridorClearCoverageSamples() + obstacleCluster(0.0f, groundY + 0.8f, 1.20f, count = 5)

        // Confirm CAUTION over 2 frames
        estimator.evaluate(cautionSamples, timestampNanos = 100_000_000L, groundY = groundY)
        val caution = estimator.evaluate(cautionSamples, timestampNanos = 133_000_000L, groundY = groundY)
        assertEquals(DistanceRisk.CAUTION, caution.risk)

        // Frame 3: 1 single urgent frame after caution; must NOT confirm URGENT on single frame
        val singleUrgent = estimator.evaluate(urgentSamples, timestampNanos = 166_000_000L, groundY = groundY)
        assertEquals("Single urgent frame must not confirm URGENT immediately", DistanceRisk.CAUTION, singleUrgent.risk)

        // Frame 4: 2nd consecutive urgent frame confirms URGENT
        val confirmedUrgent = estimator.evaluate(urgentSamples, timestampNanos = 200_000_000L, groundY = groundY)
        assertEquals(DistanceRisk.URGENT, confirmedUrgent.risk)
    }

    @Test
    fun temporalHysteresis_dropoutDoesNotReportNullDistanceWithUrgentMessage() {
        val obstacleSamples = corridorClearCoverageSamples() + obstacleCluster(0.0f, groundY + 0.8f, 1.20f, count = 5)
        val clearSamples = corridorClearCoverageSamples()

        // Confirm URGENT
        estimator.evaluate(obstacleSamples, timestampNanos = 100_000_000L, groundY = groundY)
        val urgent = estimator.evaluate(obstacleSamples, timestampNanos = 133_000_000L, groundY = groundY)
        assertEquals(DistanceRisk.URGENT, urgent.risk)
        assertNotNull(urgent.distanceMeters)

        // Dropout frame (raw clear): pending transition
        val dropout = estimator.evaluate(clearSamples, timestampNanos = 166_000_000L, groundY = groundY)
        // Must NOT report contradictory URGENT with null distance!
        assertEquals("Transition frame must be UNKNOWN pending confirmation", DistanceRisk.UNKNOWN, dropout.risk)
        assertNull(dropout.distanceMeters)
        assertFalse(dropout.message.contains("null"))

        // Frame 4 confirms CLEAR
        val restored = estimator.evaluate(clearSamples, timestampNanos = 200_000_000L, groundY = groundY)
        assertEquals(DistanceRisk.CLEAR, restored.risk)
        assertNull(restored.distanceMeters)
    }

    @Test
    fun temporalReset_invalidZeroOrNegativeTimestamp_resetsState() {
        val samples = corridorClearCoverageSamples()
        estimator.evaluate(samples, timestampNanos = 100_000_000L, groundY = groundY)
        estimator.evaluate(samples, timestampNanos = 133_000_000L, groundY = groundY)

        // Invalid timestamp (0L) resets state
        val estInvalid = estimator.evaluate(samples, timestampNanos = 0L, groundY = groundY)
        assertEquals(DistanceStatus.UNAVAILABLE, estInvalid.status)
        assertEquals(DistanceRisk.UNKNOWN, estInvalid.risk)

        // Next valid timestamp starts fresh in PREPARING
        val nextFrame = estimator.evaluate(samples, timestampNanos = 200_000_000L, groundY = groundY)
        assertEquals(DistanceStatus.PREPARING, nextFrame.status)
        assertEquals(DistanceRisk.UNKNOWN, nextFrame.risk)
    }

    @Test
    fun temporalReset_backwardTimestamp_resetsState() {
        val samples = corridorClearCoverageSamples()
        estimator.evaluate(samples, timestampNanos = 200_000_000L, groundY = groundY)
        estimator.evaluate(samples, timestampNanos = 233_000_000L, groundY = groundY)

        val resetFrame = estimator.evaluate(samples, timestampNanos = 150_000_000L, groundY = groundY)
        assertEquals(DistanceStatus.PREPARING, resetFrame.status)
        assertEquals(DistanceRisk.UNKNOWN, resetFrame.risk)
    }

    @Test
    fun temporalReset_timestampGap_resetsState() {
        val samples = corridorClearCoverageSamples()
        estimator.evaluate(samples, timestampNanos = 100_000_000L, groundY = groundY)
        estimator.evaluate(samples, timestampNanos = 133_000_000L, groundY = groundY)

        val gapFrame = estimator.evaluate(samples, timestampNanos = 2_133_000_000L, groundY = groundY)
        assertEquals(DistanceStatus.PREPARING, gapFrame.status)
        assertEquals(DistanceRisk.UNKNOWN, gapFrame.risk)
    }

    @Test
    fun temporalReset_explicitResetMethod_clearsHistory() {
        val samples = corridorClearCoverageSamples()
        estimator.evaluate(samples, timestampNanos = 100_000_000L, groundY = groundY)
        val est = estimator.evaluate(samples, timestampNanos = 133_000_000L, groundY = groundY)
        assertEquals(DistanceStatus.TRACKING, est.status)

        estimator.reset()

        val afterReset = estimator.evaluate(samples, timestampNanos = 166_000_000L, groundY = groundY)
        assertEquals(DistanceStatus.PREPARING, afterReset.status)
        assertEquals(DistanceRisk.UNKNOWN, afterReset.risk)
    }

    // ==========================================
    // 7. Message Format & Safety Claims Tests
    // ==========================================

    @Test
    fun messageFormat_usesEstimatedDistanceWithoutErrorClaims() {
        val urgentSamples = corridorClearCoverageSamples() + obstacleCluster(0.0f, groundY + 0.8f, 1.20f, count = 5)
        estimator.evaluate(urgentSamples, timestampNanos = 100_000_000L, groundY = groundY)
        val urgentEst = estimator.evaluate(urgentSamples, timestampNanos = 133_000_000L, groundY = groundY)

        assertTrue(urgentEst.message.contains("전방 추정 거리"))
        assertTrue(urgentEst.message.contains("긴급 장애물 감지: 즉시 정지"))
        // Must NOT claim +/- calibrated numerical error bounds
        assertFalse(urgentEst.message.contains("±"))

        estimator.reset()
        val cautionSamples = corridorClearCoverageSamples() + obstacleCluster(0.0f, groundY + 0.8f, 2.20f, count = 5)
        estimator.evaluate(cautionSamples, timestampNanos = 100_000_000L, groundY = groundY)
        val cautionEst = estimator.evaluate(cautionSamples, timestampNanos = 133_000_000L, groundY = groundY)

        assertTrue(cautionEst.message.contains("전방 추정 거리"))
        assertTrue(cautionEst.message.contains("주의 장애물 감지: 감속 주의"))
        assertFalse(cautionEst.message.contains("±"))
    }

    @Test
    fun messageFormat_avoidsSafetyGuaranteesAndTtc() {
        val clearSamples = corridorClearCoverageSamples()
        estimator.evaluate(clearSamples, timestampNanos = 100_000_000L, groundY = groundY)
        val clearEst = estimator.evaluate(clearSamples, timestampNanos = 133_000_000L, groundY = groundY)

        // Clear must state "경고 조건 미감지", not "경로 안전" or "장애물 없음"
        assertTrue(clearEst.message.contains("보행 통로 경고 조건 미감지"))
        assertFalse(clearEst.message.contains("경로 안전"))
        assertFalse(clearEst.message.contains("안전 거리"))
        assertFalse(clearEst.message.contains("장애물 없음"))
        assertFalse(clearEst.message.contains("TTC", ignoreCase = true))
    }
}
