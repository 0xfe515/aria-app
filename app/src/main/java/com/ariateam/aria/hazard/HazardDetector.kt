package com.ariateam.aria.hazard

import com.ariateam.aria.vision.VisionContract

/**
 * Geometric hazard detector combining offline semantic segmentation output with [MotionState].
 *
 * Scans the bottom 45% ROI (rows 158..287) and bottom proximity zone (rows 230..287) across the
 * active corridor (LEFT, CENTER, or RIGHT) selected by forward-relative IMU motion estimation.
 */
class HazardDetector(
    private val width: Int = VisionContract.MODEL_WIDTH,
    private val height: Int = VisionContract.MODEL_HEIGHT,
    private val roiStartRow: Int = VisionContract.ROI_START_ROW,
    private val proximityStartRow: Int = 230,
) {

    fun assessHazard(
        classMask: ByteArray,
        motionState: MotionState,
        timestampNanos: Long,
    ): HazardAssessment {
        require(classMask.size >= width * height) {
            "Mask buffer size ${classMask.size} is smaller than required ${width * height}"
        }
        require(timestampNanos > 0L) {
            "Assessment timestamp must be positive, was $timestampNanos"
        }

        if (!motionState.isReliable || motionState.degradedReason != null) {
            val reasonMsg = motionState.degradedReason?.koreanMessage
                ?: "방향 신뢰도 부족 (${(motionState.confidence * 100).toInt()}%)"
            return HazardAssessment(
                timestampNanos = timestampNanos,
                level = HazardLevel.UNKNOWN_DEGRADED,
                activeCorridor = motionState.activeCorridor,
                dominantNonWalkableClass = -1,
                dominantNearClass = -1,
                corridorWalkableRatio = 0f,
                nearObstacleOccupancy = 0f,
                motionConfidence = motionState.confidence,
                messageKorean = "방향·자세 확인 필요: $reasonMsg",
                isAlertCandidate = false,
            )
        }

        val corridor = motionState.activeCorridor
        val startCol = corridor.startCol
        val endCol = corridor.endCol

        val walkableFlags = VisionContract.CLASS_WALKABLE
        val numClasses = VisionContract.MODEL_CLASSES
        val roiBlockedCounts = IntArray(numClasses)
        val nearBlockedCounts = IntArray(numClasses)

        var corridorTotalPixels = 0
        var corridorWalkablePixels = 0
        var nearTotalPixels = 0
        var nearBlockedPixels = 0

        for (y in roiStartRow until height) {
            val isNearRow = y >= proximityStartRow
            val rowOffset = y * width
            for (x in startCol..endCol) {
                corridorTotalPixels++
                val classId = classMask[rowOffset + x].toInt() and 0xFF
                val safeClassId = if (classId in 0 until numClasses) classId else 0
                val isWalkable = walkableFlags[safeClassId]

                if (isWalkable) {
                    corridorWalkablePixels++
                } else {
                    roiBlockedCounts[safeClassId]++
                }

                if (isNearRow) {
                    nearTotalPixels++
                    if (!isWalkable) {
                        nearBlockedPixels++
                        nearBlockedCounts[safeClassId]++
                    }
                }
            }
        }

        val walkableRatio = if (corridorTotalPixels > 0) corridorWalkablePixels.toFloat() / corridorTotalPixels else 0f
        val nearBlockedRatio = if (nearTotalPixels > 0) nearBlockedPixels.toFloat() / nearTotalPixels else 0f

        // Dominant non-walkable obstacle class in active corridor ROI
        var dominantRoiClass = 0
        var maxRoiCount = -1
        for (c in 0 until numClasses) {
            if (!walkableFlags[c] && roiBlockedCounts[c] > maxRoiCount) {
                maxRoiCount = roiBlockedCounts[c]
                dominantRoiClass = c
            }
        }
        val dominantRoiClassName = if (dominantRoiClass in VisionContract.CLASSES.indices) {
            VisionContract.CLASSES[dominantRoiClass].koreanLabel
        } else {
            "장애물"
        }

        // Dominant non-walkable obstacle class in immediate proximity zone
        var dominantNearClass = 0
        var maxNearCount = -1
        for (c in 0 until numClasses) {
            if (!walkableFlags[c] && nearBlockedCounts[c] > maxNearCount) {
                maxNearCount = nearBlockedCounts[c]
                dominantNearClass = c
            }
        }
        val dominantNearClassName = if (dominantNearClass in VisionContract.CLASSES.indices) {
            VisionContract.CLASSES[dominantNearClass].koreanLabel
        } else {
            "근접 장애물"
        }

        // Decision logic
        return when {
            // Proximity heuristic: non-walkable ground > 35% in bottom 20% zone
            nearBlockedRatio > 0.35f -> {
                HazardAssessment(
                    timestampNanos = timestampNanos,
                    level = HazardLevel.NEAR_PATH_HAZARD,
                    activeCorridor = corridor,
                    dominantNonWalkableClass = dominantRoiClass,
                    dominantNearClass = dominantNearClass,
                    corridorWalkableRatio = walkableRatio,
                    nearObstacleOccupancy = nearBlockedRatio,
                    motionConfidence = motionState.confidence,
                    messageKorean = "[주의] ${corridor.koreanLabel} 근접 경로 장애물 추정 ($dominantNearClassName)",
                    isAlertCandidate = true,
                )
            }

            // Path fully blocked in walking direction: walkable < 40%
            walkableRatio < 0.40f -> {
                HazardAssessment(
                    timestampNanos = timestampNanos,
                    level = HazardLevel.PATH_BLOCKED,
                    activeCorridor = corridor,
                    dominantNonWalkableClass = dominantRoiClass,
                    dominantNearClass = dominantNearClass,
                    corridorWalkableRatio = walkableRatio,
                    nearObstacleOccupancy = nearBlockedRatio,
                    motionConfidence = motionState.confidence,
                    messageKorean = "[위험] ${corridor.koreanLabel} 진행 경로 차단 ($dominantRoiClassName)",
                    isAlertCandidate = true,
                )
            }

            // Path narrow / caution: walkable < 65%
            walkableRatio < 0.65f -> {
                HazardAssessment(
                    timestampNanos = timestampNanos,
                    level = HazardLevel.PATH_CAUTION,
                    activeCorridor = corridor,
                    dominantNonWalkableClass = dominantRoiClass,
                    dominantNearClass = dominantNearClass,
                    corridorWalkableRatio = walkableRatio,
                    nearObstacleOccupancy = nearBlockedRatio,
                    motionConfidence = motionState.confidence,
                    messageKorean = "[주의] ${corridor.koreanLabel} 진행 경로 협소 ($dominantRoiClassName 근접)",
                    isAlertCandidate = false,
                )
            }

            else -> {
                HazardAssessment(
                    timestampNanos = timestampNanos,
                    level = HazardLevel.CLEAR_UNWARNED,
                    activeCorridor = corridor,
                    dominantNonWalkableClass = -1,
                    dominantNearClass = -1,
                    corridorWalkableRatio = walkableRatio,
                    nearObstacleOccupancy = nearBlockedRatio,
                    motionConfidence = motionState.confidence,
                    messageKorean = "${corridor.koreanLabel} 경고 조건 미감지 (보행 영역 ${(walkableRatio * 100).toInt()}%)",
                    isAlertCandidate = false,
                )
            }
        }
    }
}
