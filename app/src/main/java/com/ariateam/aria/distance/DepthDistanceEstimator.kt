package com.ariateam.aria.distance

import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Single depth sample in gravity-aligned camera coordinates.
 *
 * @property xMeters Horizontal lateral distance to the right in meters.
 * @property yMeters Vertical height relative to camera in meters (positive up).
 * @property zMeters Forward horizontal depth distance in meters.
 * @property confidence Confidence score in [0.0, 1.0]. Samples with confidence < 0.5 or non-finite values are rejected.
 */
data class DepthSample(
    val xMeters: Float,
    val yMeters: Float,
    val zMeters: Float,
    val confidence: Float = 1f
)

/**
 * State of the depth distance estimation pipeline.
 */
enum class DistanceStatus {
    PREPARING,
    TRACKING,
    UNAVAILABLE
}

/**
 * Assessed risk level for forward walking navigation.
 */
enum class DistanceRisk {
    UNKNOWN,
    CLEAR,
    CAUTION,
    URGENT
}

/**
 * Evaluated distance estimate for the walking corridor.
 *
 * @property status Current tracking and pipeline status.
 * @property risk Assessed navigation obstacle risk.
 * @property distanceMeters Estimated distance to nearest confirmed obstacle in meters, or null if clear/unavailable.
 * @property validSampleCount Number of supporting depth samples contributing to this estimate.
 * @property message Korean status or alert explanation without absolute safety guarantees.
 */
data class DistanceEstimate(
    val status: DistanceStatus,
    val risk: DistanceRisk,
    val distanceMeters: Float? = null,
    val validSampleCount: Int = 0,
    val message: String = ""
)

/**
 * Real-time depth obstacle distance estimator for walking corridor navigation.
 *
 * Evaluates gravity-aligned camera-relative depth point clouds against the forward walking corridor.
 * Rejects floor returns, ceiling points, low-confidence samples, and isolated outlier singletons.
 * Employs spatial connected-component grouping and a robust 15th-percentile depth metric to prevent
 * single-point false alarms. Enforces temporal confirmation hysteresis to avoid fabricating clear
 * status from unknown states or triggering transient alert warnings.
 *
 * Note: Pure Kotlin JVM implementation without Android framework dependencies.
 */
class DepthDistanceEstimator(
    val corridorHalfWidthMeters: Float = DEFAULT_CORRIDOR_HALF_WIDTH,
    val minObstacleHeightAboveGround: Float = DEFAULT_MIN_OBSTACLE_HEIGHT,
    val maxObstacleHeightAboveGround: Float = DEFAULT_MAX_OBSTACLE_HEIGHT,
    val minDistanceMeters: Float = DEFAULT_MIN_DISTANCE,
    val maxDistanceMeters: Float = DEFAULT_MAX_DISTANCE,
    val minConfidence: Float = DEFAULT_MIN_CONFIDENCE,
    val minCoverageSamples: Int = DEFAULT_MIN_COVERAGE_SAMPLES,
    val minCorridorCoverageSamples: Int = DEFAULT_MIN_CORRIDOR_COVERAGE_SAMPLES,
    val minClusterSize: Int = DEFAULT_MIN_CLUSTER_SIZE,
    val confirmationFramesRequired: Int = DEFAULT_CONFIRMATION_FRAMES,
    val maxFrameGapNanos: Long = DEFAULT_MAX_FRAME_GAP_NANOS,
) {
    companion object {
        const val DEFAULT_CORRIDOR_HALF_WIDTH = 0.45f
        const val DEFAULT_MIN_OBSTACLE_HEIGHT = 0.12f
        const val DEFAULT_MAX_OBSTACLE_HEIGHT = 1.90f
        const val DEFAULT_MIN_DISTANCE = 0.30f
        const val DEFAULT_MAX_DISTANCE = 5.00f
        const val DEFAULT_MIN_CONFIDENCE = 0.50f
        const val DEFAULT_MIN_COVERAGE_SAMPLES = 8
        const val DEFAULT_MIN_CORRIDOR_COVERAGE_SAMPLES = 4
        const val DEFAULT_MIN_CLUSTER_SIZE = 4
        const val DEFAULT_CONFIRMATION_FRAMES = 2
        const val DEFAULT_MAX_FRAME_GAP_NANOS = 1_500_000_000L // 1.5 seconds

        const val URGENT_THRESHOLD_METERS = 1.50f
        const val CAUTION_THRESHOLD_METERS = 3.00f

        private const val MIN_PLAUSIBLE_GROUND_Y = -3.50f
        private const val MAX_PLAUSIBLE_GROUND_Y = -0.10f

        private const val CLUSTER_MAX_DELTA_Z = 0.35f
        private const val CLUSTER_MAX_DELTA_XY = 0.40f
    }

    private var lastTimestampNanos: Long = 0L
    private var consecutiveValidFrames: Int = 0
    private var consecutiveRiskFrames: Int = 0
    private var lastCandidateRisk: DistanceRisk = DistanceRisk.UNKNOWN
    private var confirmedRisk: DistanceRisk = DistanceRisk.UNKNOWN

    /**
     * Resets temporal consistency counters, tracking history, and timestamp state.
     */
    fun reset() {
        lastTimestampNanos = 0L
        consecutiveValidFrames = 0
        consecutiveRiskFrames = 0
        lastCandidateRisk = DistanceRisk.UNKNOWN
        confirmedRisk = DistanceRisk.UNKNOWN
    }

    /**
     * Evaluates a frame of depth samples against the walking corridor.
     *
     * @param samples Depth samples in gravity-aligned camera coordinates.
     * @param timestampNanos Monotonic frame timestamp in nanoseconds.
     * @param groundY Ground surface Y-coordinate relative to camera in meters (negative when camera is above floor).
     * @return [DistanceEstimate] containing status, risk level, metric distance, sample count, and Korean message.
     */
    fun evaluate(
        samples: List<DepthSample>,
        timestampNanos: Long,
        groundY: Float?
    ): DistanceEstimate {
        // 1. Timestamp validation & anomaly detection (backward / duplicate / gap reset)
        if (timestampNanos <= 0L) {
            reset()
            return DistanceEstimate(
                status = DistanceStatus.UNAVAILABLE,
                risk = DistanceRisk.UNKNOWN,
                distanceMeters = null,
                validSampleCount = 0,
                message = "유효하지 않은 타임스탬프 (측정 대기 중)"
            )
        }

        if (lastTimestampNanos > 0L) {
            if (timestampNanos <= lastTimestampNanos) {
                // Out-of-order or duplicate timestamp: reset temporal hysteresis
                reset()
            } else if (timestampNanos - lastTimestampNanos > maxFrameGapNanos) {
                // Large time gap between frames: tracking discontinuity, reset hysteresis
                reset()
            }
        }
        lastTimestampNanos = timestampNanos

        // 2. Ground plane validation
        if (groundY == null || groundY.isNaN() || groundY.isInfinite() ||
            groundY < MIN_PLAUSIBLE_GROUND_Y || groundY > MAX_PLAUSIBLE_GROUND_Y
        ) {
            consecutiveValidFrames = 0
            consecutiveRiskFrames = 0
            lastCandidateRisk = DistanceRisk.UNKNOWN
            confirmedRisk = DistanceRisk.UNKNOWN
            return DistanceEstimate(
                status = DistanceStatus.UNAVAILABLE,
                risk = DistanceRisk.UNKNOWN,
                distanceMeters = null,
                validSampleCount = 0,
                message = "바닥면 인식 불가 (거리 측정 대기 중)"
            )
        }

        // 3. Filter valid depth samples (confidence finite and in [minConfidence, 1.0], finite coordinates, positive depth)
        val validSamples = samples.filter { sample ->
            !sample.confidence.isNaN() && !sample.confidence.isInfinite() &&
                sample.confidence >= minConfidence && sample.confidence <= 1.0f &&
                !sample.xMeters.isNaN() && !sample.xMeters.isInfinite() &&
                !sample.yMeters.isNaN() && !sample.yMeters.isInfinite() &&
                !sample.zMeters.isNaN() && !sample.zMeters.isInfinite() &&
                sample.zMeters > 0f
        }

        if (validSamples.size < minCoverageSamples) {
            consecutiveValidFrames = 0
            consecutiveRiskFrames = 0
            lastCandidateRisk = DistanceRisk.UNKNOWN
            confirmedRisk = DistanceRisk.UNKNOWN
            return DistanceEstimate(
                status = DistanceStatus.UNAVAILABLE,
                risk = DistanceRisk.UNKNOWN,
                distanceMeters = null,
                validSampleCount = validSamples.size,
                message = "깊이 데이터 범위 부족 (신뢰도 미달)"
            )
        }

        // 4. Height classification: reject floor points (< groundY + 0.12m) and overhead clearance (> groundY + 1.9m)
        val floorThresholdY = groundY + minObstacleHeightAboveGround
        val ceilingThresholdY = groundY + maxObstacleHeightAboveGround

        // 5. Corridor obstacle candidate samples:
        // Strictly within corridor lateral bounds (|x| <= corridorHalfWidthMeters),
        // body height [floorThresholdY, ceilingThresholdY], and distance [minDistanceMeters, maxDistanceMeters]
        val corridorObstacleSamples = validSamples.filter { sample ->
            sample.xMeters in -corridorHalfWidthMeters..corridorHalfWidthMeters &&
                sample.yMeters in floorThresholdY..ceilingThresholdY &&
                sample.zMeters in minDistanceMeters..maxDistanceMeters
        }

        // 6. Spatial connected-component clustering & robust nearest distance calculation
        val detectedObstacle = findNearestSupportedCluster(corridorObstacleSamples)

        // 7. Forward corridor observation evidence verification when no obstacle is detected
        // "For clear only accept adequate elevated forward coverage, not floor-only."
        // Elevated forward coverage requires points in corridor lateral bounds and body height beyond walking range
        if (detectedObstacle == null) {
            val elevatedForwardCoverage = validSamples.filter { sample ->
                sample.xMeters in -corridorHalfWidthMeters..corridorHalfWidthMeters &&
                    sample.yMeters in floorThresholdY..ceilingThresholdY &&
                    sample.zMeters > maxDistanceMeters
            }

            if (elevatedForwardCoverage.size < minCorridorCoverageSamples) {
                consecutiveValidFrames = 0
                consecutiveRiskFrames = 0
                lastCandidateRisk = DistanceRisk.UNKNOWN
                confirmedRisk = DistanceRisk.UNKNOWN
                return DistanceEstimate(
                    status = DistanceStatus.UNAVAILABLE,
                    risk = DistanceRisk.UNKNOWN,
                    distanceMeters = null,
                    validSampleCount = elevatedForwardCoverage.size,
                    message = "보행 통로 깊이 관측 부족 (신뢰도 미달)"
                )
            }
        }

        // 8. Frame-level raw risk determination
        val rawRisk: DistanceRisk
        val candidateDistance: Float?
        val clusterSampleCount: Int

        if (detectedObstacle != null) {
            candidateDistance = detectedObstacle.robustDistanceMeters
            clusterSampleCount = detectedObstacle.clusterSize
            rawRisk = when {
                candidateDistance <= URGENT_THRESHOLD_METERS -> DistanceRisk.URGENT
                candidateDistance <= CAUTION_THRESHOLD_METERS -> DistanceRisk.CAUTION
                else -> DistanceRisk.CLEAR
            }
        } else {
            candidateDistance = null
            clusterSampleCount = corridorObstacleSamples.size
            rawRisk = DistanceRisk.CLEAR
        }

        // 9. Temporal consistency hysteresis (prevent unknown->clear fabrication & transient alert spikes)
        consecutiveValidFrames++
        if (consecutiveValidFrames < confirmationFramesRequired) {
            // Initial warm-up: do not fabricate CLEAR or alert without consecutive confirmation
            lastCandidateRisk = rawRisk
            consecutiveRiskFrames = 1
            return DistanceEstimate(
                status = DistanceStatus.PREPARING,
                risk = DistanceRisk.UNKNOWN,
                distanceMeters = null,
                validSampleCount = clusterSampleCount,
                message = "깊이 센서 초기화 중 (준비 중)"
            )
        }

        // Track consecutive frames of the EXACT SAME risk level
        if (rawRisk == lastCandidateRisk) {
            consecutiveRiskFrames++
        } else {
            lastCandidateRisk = rawRisk
            consecutiveRiskFrames = 1
        }

        // Evaluate confirmed risk state
        val effectiveRisk: DistanceRisk
        if (consecutiveRiskFrames >= confirmationFramesRequired) {
            confirmedRisk = rawRisk
            effectiveRisk = rawRisk
        } else {
            // Unconfirmed frame during transition:
            // Prevent reporting contradictory state (e.g. holding URGENT when raw distance is null)
            // or premature alert escalation on a single frame
            if (confirmedRisk != DistanceRisk.CLEAR && rawRisk == DistanceRisk.CLEAR) {
                effectiveRisk = DistanceRisk.UNKNOWN
            } else if (confirmedRisk == DistanceRisk.CLEAR && rawRisk != DistanceRisk.CLEAR) {
                effectiveRisk = DistanceRisk.UNKNOWN
            } else if (confirmedRisk == DistanceRisk.CAUTION && rawRisk == DistanceRisk.URGENT) {
                effectiveRisk = DistanceRisk.CAUTION
            } else {
                effectiveRisk = confirmedRisk
            }
        }

        // 10. Format distance output and Korean user message (no +/- error claims, no safety guarantees)
        val reportedDistance: Float? = when (effectiveRisk) {
            DistanceRisk.URGENT, DistanceRisk.CAUTION -> candidateDistance
            DistanceRisk.CLEAR -> if (candidateDistance != null && candidateDistance > CAUTION_THRESHOLD_METERS) candidateDistance else null
            DistanceRisk.UNKNOWN -> null
        }

        val formattedDist = reportedDistance?.let { String.format(Locale.US, "%.1f", it) }

        val message = when (effectiveRisk) {
            DistanceRisk.URGENT -> "전방 추정 거리 ${formattedDist}m 긴급 장애물 감지: 즉시 정지"
            DistanceRisk.CAUTION -> "전방 추정 거리 ${formattedDist}m 주의 장애물 감지: 감속 주의"
            DistanceRisk.CLEAR -> {
                if (reportedDistance != null) {
                    "전방 추정 거리 ${formattedDist}m 장애물 감지 (경고 조건 미달)"
                } else {
                    "보행 통로 경고 조건 미감지"
                }
            }
            DistanceRisk.UNKNOWN -> "보행 통로 상태 분석 중"
        }

        return DistanceEstimate(
            status = DistanceStatus.TRACKING,
            risk = effectiveRisk,
            distanceMeters = reportedDistance,
            validSampleCount = clusterSampleCount,
            message = message
        )
    }

    private data class ClusterResult(
        val robustDistanceMeters: Float,
        val clusterSpreadMeters: Float,
        val clusterSize: Int
    )

    /**
     * Groups corridor points into connected clusters and computes the nearest supported obstacle.
     * Rejects singletons and small clusters below [minClusterSize].
     * Uses 15th percentile of sorted depths in the cluster to prevent front-edge outlier noise.
     */
    private fun findNearestSupportedCluster(points: List<DepthSample>): ClusterResult? {
        if (points.size < minClusterSize) return null

        val n = points.size
        val parent = IntArray(n) { it }

        fun find(i: Int): Int {
            var root = i
            while (root != parent[root]) {
                root = parent[root]
            }
            var curr = i
            while (curr != root) {
                val nxt = parent[curr]
                parent[curr] = root
                curr = nxt
            }
            return root
        }

        fun union(i: Int, j: Int) {
            val rootI = find(i)
            val rootJ = find(j)
            if (rootI != rootJ) {
                parent[rootI] = rootJ
            }
        }

        for (i in 0 until n) {
            val pi = points[i]
            for (j in i + 1 until n) {
                val pj = points[j]
                val dz = abs(pi.zMeters - pj.zMeters)
                if (dz <= CLUSTER_MAX_DELTA_Z) {
                    val dxy = hypot(pi.xMeters - pj.xMeters, pi.yMeters - pj.yMeters)
                    if (dxy <= CLUSTER_MAX_DELTA_XY) {
                        union(i, j)
                    }
                }
            }
        }

        val clusters = HashMap<Int, MutableList<DepthSample>>()
        for (i in 0 until n) {
            val root = find(i)
            clusters.getOrPut(root) { ArrayList() }.add(points[i])
        }

        var nearestResult: ClusterResult? = null

        for (cluster in clusters.values) {
            if (cluster.size < minClusterSize) continue

            val sortedDepths = cluster.map { it.zMeters }.sorted()
            // 15th percentile: rejects leading outlier spike while capturing the obstacle surface
            val p15Index = minOf(sortedDepths.lastIndex, maxOf(1, (sortedDepths.size * 0.15f).toInt()))
            val robustDistance = sortedDepths[p15Index]

            // Cluster depth dispersion (spread)
            val meanZ = sortedDepths.average().toFloat()
            val varianceZ = sortedDepths.map { (it - meanZ) * (it - meanZ) }.average().toFloat()
            val spreadZ = sqrt(varianceZ)

            if (nearestResult == null || robustDistance < nearestResult.robustDistanceMeters) {
                nearestResult = ClusterResult(
                    robustDistanceMeters = robustDistance,
                    clusterSpreadMeters = spreadZ,
                    clusterSize = cluster.size
                )
            }
        }

        return nearestResult
    }
}
