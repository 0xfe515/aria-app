package com.ariateam.aria.distance

import com.ariateam.aria.vision.od.DetectedObject
import kotlin.math.abs

/**
 * Validated OD detection qualified for semantic ground-contact distance estimation.
 */
data class QualifiedOdDetection(
    val detection: DetectedObject,
    val targetSemanticClass: Int,
)

/**
 * Result of extracting a ground-contact candidate point from a mask region.
 */
data class MaskContactPoint(
    val classId: Int,
    val pixelCount: Int,
    val contactX: Float,
    val contactY: Float,
    val isBottomClipped: Boolean,
    val isFloatingUnsupported: Boolean,
    val groundSupportPixels: Int,
    val associatedDetection: DetectedObject? = null,
)

/**
 * Associates accepted auxiliary object detections with semantic segmentation components.
 *
 * Enforces:
 * - Qualified detection filtering (finite geometry, valid confidence, explicit COCO->semantic class mapping).
 * - Full fallback to existing segmentation-only when OD evidence is missing, empty, low-confidence, or traffic-light only.
 * - Explicit pole (6) fallback retention when OD is active (since OD labelmap lacks poles).
 * - Spatial overlap and lower-boundary agreement between OD box and mask component.
 * - Rejection of artificial window-truncated bottoms by verifying real bottom boundary in original mask.
 * - Local ground support verification restricted to the contact foot columns rather than a broad bbox.
 * - Strict clipping rejection: box touching bottom boundary or mask bottom touching boundary is clipped.
 * - Bounded allocations: reusable internal buffers to prevent garbage collection churn.
 * - No box-bottom metric fallback: metric contact is ALWAYS derived from mask pixels.
 */
class OdMaskMatcher(
    val minScore: Float = DEFAULT_MIN_OD_SCORE,
    val maxLowerBoundaryDiffNorm: Float = DEFAULT_MAX_LOWER_BOUNDARY_DIFF_NORM,
    val minClusterPixels: Int = GroundContactDistanceEstimator.MIN_CLUSTER_PIXELS,
    val boxPaddingPixels: Int = BOX_PADDING_PIXELS,
    initialBufferSize: Int = MAX_DEFAULT_BUFFER_SIZE,
) {
    // Reusable buffers for zero-allocation connected component traversal
    private var queueX = IntArray(initialBufferSize)
    private var queueY = IntArray(initialBufferSize)
    private var visitedStamp = IntArray(initialBufferSize)
    private var currentStamp = 0

    private fun ensureCapacity(requiredCapacity: Int) {
        if (queueX.size < requiredCapacity) {
            queueX = IntArray(requiredCapacity)
            queueY = IntArray(requiredCapacity)
            visitedStamp = IntArray(requiredCapacity)
            currentStamp = 0
        }
    }

    private fun nextStamp(): Int {
        currentStamp++
        if (currentStamp == Int.MAX_VALUE) {
            visitedStamp.fill(0)
            currentStamp = 1
        }
        return currentStamp
    }

    /**
     * Maps MediaPipe COCO category IDs to semantic segmentation classes:
     * - person (0) -> semantic 4
     * - bicycle (1), car (2), motorcycle (3), bus (5), truck (7) -> semantic 5 (vehicle)
     * - traffic light (9) and any other classes return null (excluded from ground contact).
     */
    fun mapOdClassToSemanticClass(odClassId: Int): Int? {
        return when (odClassId) {
            OD_PERSON_CLASS_ID -> SEMANTIC_PERSON
            in OD_VEHICLE_CLASS_IDS -> SEMANTIC_VEHICLE
            else -> null
        }
    }

    /**
     * Filters detections by geometry validity, confidence score threshold, and supported category mapping.
     */
    fun qualifyDetections(detections: List<DetectedObject>?): List<QualifiedOdDetection> {
        if (detections.isNullOrEmpty()) return emptyList()
        val qualified = mutableListOf<QualifiedOdDetection>()
        for (det in detections) {
            if (!det.hasValidGeometry) continue
            if (!det.score.isFinite() || det.score < minScore || det.score > 1.0f) continue
            val semanticClass = mapOdClassToSemanticClass(det.classId) ?: continue
            qualified.add(QualifiedOdDetection(det, semanticClass))
        }
        return qualified
    }

    /**
     * Matches detections with mask components and extracts ground-contact points.
     *
     * Fallback policy:
     * - If [detections] is null, empty, or has no qualified detections (e.g. low score, traffic light only),
     *   falls back to the existing segmentation-only extraction for all target classes (4, 5, 6).
     * - If qualified detections exist, activates OD-gated matching for person/vehicle and retains pole (6)
     *   as explicit documented segmentation fallback.
     */
    fun matchAndExtract(
        classMask: ByteArray,
        width: Int,
        height: Int,
        detections: List<DetectedObject>?,
    ): List<MaskContactPoint> {
        val pixelCount = width * height
        if (classMask.size < pixelCount || width <= 0 || height <= 0) return emptyList()
        ensureCapacity(pixelCount)

        val qualified = qualifyDetections(detections)

        // (1) Missing / empty / no qualified OD fallback to existing segmentation-only
        if (qualified.isEmpty()) {
            return extractSegmentationOnlyComponents(classMask, width, height, TARGET_CLASSES)
        }

        val result = mutableListOf<MaskContactPoint>()

        // Retain pole (6) from segmentation because OD does not detect poles
        val poleCandidates = extractSegmentationOnlyComponents(classMask, width, height, setOf(SEMANTIC_POLE))
        result.addAll(poleCandidates)

        val minSupportedY = (height * 0.35f).toInt()
        for (qDet in qualified) {
            val contactPoint = matchDetectionToMask(
                qDet = qDet,
                classMask = classMask,
                width = width,
                height = height,
                minSupportedY = minSupportedY,
            )
            if (contactPoint != null) {
                result.add(contactPoint)
            }
        }

        return result
    }

    private fun matchDetectionToMask(
        qDet: QualifiedOdDetection,
        classMask: ByteArray,
        width: Int,
        height: Int,
        minSupportedY: Int,
    ): MaskContactPoint? {
        val det = qDet.detection
        val targetClass = qDet.targetSemanticClass

        val boxLeftPx = (det.left * width).toInt().coerceIn(0, width - 1)
        val boxRightPx = (det.right * width).toInt().coerceIn(0, width - 1)
        val boxTopPx = (det.top * height).toInt().coerceIn(0, height - 1)
        val boxBottomPx = (det.bottom * height).toInt().coerceIn(0, height - 1)

        val searchLeft = (boxLeftPx - boxPaddingPixels).coerceAtLeast(0)
        val searchRight = (boxRightPx + boxPaddingPixels).coerceAtMost(width - 1)
        val searchTop = (boxTopPx - boxPaddingPixels).coerceAtLeast(0)
        val searchBottom = (boxBottomPx + boxPaddingPixels).coerceAtMost(height - 1)

        val stamp = nextStamp()

        // Best candidate compact tracking (zero allocation)
        var bestCount = 0
        var bestMinX = 0
        var bestMaxX = 0
        var bestMinY = 0
        var bestMaxY = 0
        var bestContactX = 0f
        var bestContactY = 0f
        var bestDiffNorm = Float.MAX_VALUE
        var bestBottomMinX = 0
        var bestBottomMaxX = 0

        // Search from bottom up for clusters of targetClass within the search box
        for (y in searchBottom downTo searchTop) {
            val rowOff = y * width
            for (x in searchLeft..searchRight) {
                val idx = rowOff + x
                if (visitedStamp[idx] == stamp) continue
                if (classMask[idx].toInt() != targetClass) {
                    visitedStamp[idx] = stamp
                    continue
                }

                // BFS traversal using preallocated queueX / queueY
                var head = 0
                var tail = 0
                queueX[tail] = x
                queueY[tail] = y
                tail++
                visitedStamp[idx] = stamp

                var cMinX = x
                var cMaxX = x
                var cMinY = y
                var cMaxY = y

                while (head < tail) {
                    val cx = queueX[head]
                    val cy = queueY[head]
                    head++

                    if (cx < cMinX) cMinX = cx
                    if (cx > cMaxX) cMaxX = cx
                    if (cy < cMinY) cMinY = cy
                    if (cy > cMaxY) cMaxY = cy

                    if (cy > searchTop) {
                        val upY = cy - 1
                        val upIdx = upY * width + cx
                        if (visitedStamp[upIdx] != stamp && classMask[upIdx].toInt() == targetClass) {
                            visitedStamp[upIdx] = stamp
                            queueX[tail] = cx
                            queueY[tail] = upY
                            tail++
                        }
                    }
                    if (cy < searchBottom) {
                        val downY = cy + 1
                        val downIdx = downY * width + cx
                        if (visitedStamp[downIdx] != stamp && classMask[downIdx].toInt() == targetClass) {
                            visitedStamp[downIdx] = stamp
                            queueX[tail] = cx
                            queueY[tail] = downY
                            tail++
                        }
                    }
                    if (cx > searchLeft) {
                        val leftX = cx - 1
                        val leftIdx = cy * width + leftX
                        if (visitedStamp[leftIdx] != stamp && classMask[leftIdx].toInt() == targetClass) {
                            visitedStamp[leftIdx] = stamp
                            queueX[tail] = leftX
                            queueY[tail] = cy
                            tail++
                        }
                    }
                    if (cx < searchRight) {
                        val rightX = cx + 1
                        val rightIdx = cy * width + rightX
                        if (visitedStamp[rightIdx] != stamp && classMask[rightIdx].toInt() == targetClass) {
                            visitedStamp[rightIdx] = stamp
                            queueX[tail] = rightX
                            queueY[tail] = cy
                            tail++
                        }
                    }
                }

                val cCount = tail
                if (cCount < minClusterPixels) continue

                // Check lower-boundary agreement
                val maskBottomNorm = (cMaxY + 0.5f) / height.toFloat()
                val diffNorm = abs(maskBottomNorm - det.bottom)
                if (diffNorm > maxLowerBoundaryDiffNorm) continue

                // (3) Truncation verification:
                // Verify that the component does NOT continue downward immediately below cMaxY in the original classMask.
                // If it continues below the search window, it is an artificial window cutoff, not a real bottom boundary!
                if (cMaxY < height - 1) {
                    var continuesBelow = false
                    val checkRowOff = (cMaxY + 1) * width
                    for (i in 0 until cCount) {
                        if (queueY[i] == cMaxY) {
                            val px = queueX[i]
                            if (classMask[checkRowOff + px].toInt() == targetClass) {
                                continuesBelow = true
                                break
                            }
                        }
                    }
                    if (continuesBelow) {
                        // Artificial window-truncated bottom: reject this cluster
                        continue
                    }
                }

                // If better lower-boundary agreement than previous cluster, keep it
                if (diffNorm < bestDiffNorm) {
                    // Derive contact from lowest 3 rows of this cluster
                    var bottomSumX = 0L
                    var bottomCount = 0
                    var bMinX = width
                    var bMaxX = 0
                    val bottomThresholdY = (cMaxY - 2).coerceAtLeast(cMinY)
                    for (i in 0 until cCount) {
                        val py = queueY[i]
                        if (py >= bottomThresholdY) {
                            val px = queueX[i]
                            bottomSumX += px
                            bottomCount++
                            if (px < bMinX) bMinX = px
                            if (px > bMaxX) bMaxX = px
                        }
                    }
                    val contactX = if (bottomCount > 0) bottomSumX.toFloat() / bottomCount else (cMinX + cMaxX) / 2.0f
                    val contactY = cMaxY.toFloat()

                    bestCount = cCount
                    bestMinX = cMinX
                    bestMaxX = cMaxX
                    bestMinY = cMinY
                    bestMaxY = cMaxY
                    bestContactX = contactX
                    bestContactY = contactY
                    bestDiffNorm = diffNorm
                    bestBottomMinX = if (bMinX <= bMaxX) bMinX else cMinX
                    bestBottomMaxX = if (bMinX <= bMaxX) bMaxX else cMaxX
                }
            }
        }

        if (bestCount == 0) return null

        // Clipping check: touches within 2 pixels of bottom edge
        val isBoxBottomClipped = (det.bottom * height) >= (height - 2)
        val isMaskBottomClipped = bestMaxY >= (height - 2)
        val isBottomClipped = isBoxBottomClipped || isMaskBottomClipped

        // Local ground support verification:
        // Must search locally below the actual bottom contact columns rather than broad bbox
        var groundSupportPixels = 0
        if (!isBottomClipped) {
            val sTop = bestMaxY + 1
            val sBottom = minOf(height - 1, bestMaxY + SUPPORT_SEARCH_DEPTH_PIXELS)
            val sLeft = (bestBottomMinX - 1).coerceIn(0, width - 1)
            val sRight = (bestBottomMaxX + 1).coerceIn(0, width - 1)

            for (sy in sTop..sBottom) {
                val rowOff = sy * width
                for (sx in sLeft..sRight) {
                    if (classMask[rowOff + sx].toInt() in GROUND_CLASSES) {
                        groundSupportPixels++
                    }
                }
            }
        }

        val hasGroundSupport = groundSupportPixels >= MIN_GROUND_SUPPORT_PIXELS
        val isFloatingUnsupported = (bestMaxY < minSupportedY) || !hasGroundSupport

        return MaskContactPoint(
            classId = targetClass,
            pixelCount = bestCount,
            contactX = bestContactX,
            contactY = bestContactY,
            isBottomClipped = isBottomClipped,
            isFloatingUnsupported = isFloatingUnsupported,
            groundSupportPixels = groundSupportPixels,
            associatedDetection = det,
        )
    }

    /**
     * Pure segmentation connected-component analysis for target classes.
     */
    fun extractSegmentationOnlyComponents(
        classMask: ByteArray,
        width: Int,
        height: Int,
        targetClasses: Set<Int>,
    ): List<MaskContactPoint> {
        val pixelCount = width * height
        if (classMask.size < pixelCount || width <= 0 || height <= 0) return emptyList()
        ensureCapacity(pixelCount)

        val stamp = nextStamp()
        val candidates = mutableListOf<MaskContactPoint>()
        val minSupportedY = (height * 0.35f).toInt()

        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                val index = rowOffset + x
                if (visitedStamp[index] == stamp) continue

                val classId = classMask[index].toInt()
                if (classId !in targetClasses) {
                    visitedStamp[index] = stamp
                    continue
                }

                var head = 0
                var tail = 0
                queueX[tail] = x
                queueY[tail] = y
                tail++
                visitedStamp[index] = stamp

                var minX = x
                var maxX = x
                var minY = y
                var maxY = y

                while (head < tail) {
                    val cx = queueX[head]
                    val cy = queueY[head]
                    head++

                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy

                    if (cy > 0) {
                        val upIdx = (cy - 1) * width + cx
                        if (visitedStamp[upIdx] != stamp && classMask[upIdx].toInt() == classId) {
                            visitedStamp[upIdx] = stamp
                            queueX[tail] = cx
                            queueY[tail] = cy - 1
                            tail++
                        }
                    }
                    if (cy < height - 1) {
                        val downIdx = (cy + 1) * width + cx
                        if (visitedStamp[downIdx] != stamp && classMask[downIdx].toInt() == classId) {
                            visitedStamp[downIdx] = stamp
                            queueX[tail] = cx
                            queueY[tail] = cy + 1
                            tail++
                        }
                    }
                    if (cx > 0) {
                        val leftIdx = cy * width + (cx - 1)
                        if (visitedStamp[leftIdx] != stamp && classMask[leftIdx].toInt() == classId) {
                            visitedStamp[leftIdx] = stamp
                            queueX[tail] = cx - 1
                            queueY[tail] = cy
                            tail++
                        }
                    }
                    if (cx < width - 1) {
                        val rightIdx = cy * width + (cx + 1)
                        if (visitedStamp[rightIdx] != stamp && classMask[rightIdx].toInt() == classId) {
                            visitedStamp[rightIdx] = stamp
                            queueX[tail] = cx + 1
                            queueY[tail] = cy
                            tail++
                        }
                    }
                }

                val count = tail
                if (count < minClusterPixels) continue

                val isBottomClipped = maxY >= height - 2

                var bottomSumX = 0L
                var bottomCount = 0
                val bottomThresholdY = (maxY - 2).coerceAtLeast(minY)
                for (i in 0 until count) {
                    val py = queueY[i]
                    if (py >= bottomThresholdY) {
                        bottomSumX += queueX[i]
                        bottomCount++
                    }
                }

                val contactX = if (bottomCount > 0) bottomSumX.toFloat() / bottomCount else (minX + maxX) / 2.0f
                val contactY = maxY.toFloat()

                var groundSupportPixels = 0
                if (!isBottomClipped) {
                    val searchTop = maxY + 1
                    val searchBottom = minOf(height - 1, maxY + SUPPORT_SEARCH_DEPTH_PIXELS)
                    val searchLeft = minX.coerceIn(0, width - 1)
                    val searchRight = maxX.coerceIn(0, width - 1)

                    for (sy in searchTop..searchBottom) {
                        val rowOff = sy * width
                        for (sx in searchLeft..searchRight) {
                            if (classMask[rowOff + sx].toInt() in GROUND_CLASSES) {
                                groundSupportPixels++
                            }
                        }
                    }
                }

                val hasGroundSupport = groundSupportPixels >= MIN_GROUND_SUPPORT_PIXELS
                val isFloatingUnsupported = (maxY < minSupportedY) || !hasGroundSupport

                candidates.add(
                    MaskContactPoint(
                        classId = classId,
                        pixelCount = count,
                        contactX = contactX,
                        contactY = contactY,
                        isBottomClipped = isBottomClipped,
                        isFloatingUnsupported = isFloatingUnsupported,
                        groundSupportPixels = groundSupportPixels,
                        associatedDetection = null,
                    )
                )
            }
        }

        return candidates
    }

    companion object {
        const val DEFAULT_MIN_OD_SCORE = 0.5f
        const val DEFAULT_MAX_LOWER_BOUNDARY_DIFF_NORM = 0.15f
        const val BOX_PADDING_PIXELS = 2
        const val SUPPORT_SEARCH_DEPTH_PIXELS = 4
        const val MIN_GROUND_SUPPORT_PIXELS = 3
        const val MAX_DEFAULT_BUFFER_SIZE = 512 * 288

        const val OD_PERSON_CLASS_ID = 0
        val OD_VEHICLE_CLASS_IDS = setOf(1, 2, 3, 5, 7)
        val EXCLUDED_OD_CLASS_IDS = setOf(9)

        const val SEMANTIC_PERSON = 4
        const val SEMANTIC_VEHICLE = 5
        const val SEMANTIC_POLE = 6

        val TARGET_CLASSES = setOf(SEMANTIC_PERSON, SEMANTIC_VEHICLE, SEMANTIC_POLE)
        val GROUND_CLASSES = setOf(1, 2, 3) // road=1, sidewalk=2, crosswalk=3
    }
}
