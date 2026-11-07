package com.ariateam.aria.hazard

import com.ariateam.aria.vision.VisionContract
import java.util.ArrayList

/**
 * Extracts class-aware spatial and semantic features from segmentation masks across walking corridors.
 *
 * Uses bounded memory and preallocated arrays to eliminate per-frame allocations during mask traversal.
 */
class SegmentationFeatureExtractor(
    private val width: Int = VisionContract.MODEL_WIDTH,
    private val height: Int = VisionContract.MODEL_HEIGHT,
    private val roiStartRow: Int = VisionContract.ROI_START_ROW,
    private val nearStartRow: Int = 230,
    private val sideBandCols: Int = 64,
    private val minPoleBlobPixels: Int = 40,
) {
    private val roiRows = (height - roiStartRow).coerceAtLeast(0)
    private val roiSize = width * roiRows

    // Reusable buffers for 4-connected component labeling of pole blobs
    private val visited = if (roiSize > 0) IntArray(roiSize) else IntArray(0)
    private val queue = if (roiSize > 0) IntArray(roiSize) else IntArray(0)
    private var currentRunId = 1

    // Reusable buffers for class counts within active corridor
    private val roiBlockedCounts = IntArray(VisionContract.MODEL_CLASSES)
    private val nearBlockedCounts = IntArray(VisionContract.MODEL_CLASSES)

    fun extract(classMask: ByteArray, corridor: CorridorDirection): SegmentationFeatures {
        require(classMask.size >= width * height) {
            "Mask buffer size ${classMask.size} is smaller than required ${width * height}"
        }

        val startCol = corridor.startCol
        val endCol = corridor.endCol
        val walkableFlags = VisionContract.CLASS_WALKABLE
        val numClasses = VisionContract.MODEL_CLASSES

        roiBlockedCounts.fill(0)
        nearBlockedCounts.fill(0)

        var roiTotalPixels = 0
        var roiWalkablePixels = 0
        var otherPixels = 0

        var nearTotalPixels = 0
        var nearWalkablePixels = 0
        var nearRoadPixels = 0
        var nearCrosswalkPixels = 0

        for (y in roiStartRow until height) {
            val isNearRow = y >= nearStartRow
            val rowOffset = y * width
            for (x in startCol..endCol) {
                roiTotalPixels++
                val rawClass = classMask[rowOffset + x].toInt() and 0xFF
                val safeClass = if (rawClass in 0 until numClasses) rawClass else 0
                val isWalkable = walkableFlags[safeClass]

                if (isWalkable) {
                    roiWalkablePixels++
                } else {
                    roiBlockedCounts[safeClass]++
                }

                if (safeClass == 0) {
                    otherPixels++
                }

                if (isNearRow) {
                    nearTotalPixels++
                    if (isWalkable) {
                        nearWalkablePixels++
                    } else {
                        nearBlockedCounts[safeClass]++
                    }
                    if (safeClass == 1) {
                        nearRoadPixels++
                    } else if (safeClass == 3) {
                        nearCrosswalkPixels++
                    }
                }
            }
        }

        val roiWalkable = if (roiTotalPixels > 0) roiWalkablePixels.toFloat() / roiTotalPixels else 0f
        val nearWalkable = if (nearTotalPixels > 0) nearWalkablePixels.toFloat() / nearTotalPixels else 0f
        val nearRoad = if (nearTotalPixels > 0) nearRoadPixels.toFloat() / nearTotalPixels else 0f
        val nearCrosswalk = if (nearTotalPixels > 0) nearCrosswalkPixels.toFloat() / nearTotalPixels else 0f
        val otherRatio = if (roiTotalPixels > 0) otherPixels.toFloat() / roiTotalPixels else 0f

        var dominantRoiClass = -1
        var maxRoiCount = 0
        for (c in 0 until numClasses) {
            if (!walkableFlags[c] && roiBlockedCounts[c] > maxRoiCount) {
                maxRoiCount = roiBlockedCounts[c]
                dominantRoiClass = c
            }
        }

        var dominantNearClass = -1
        var maxNearCount = 0
        for (c in 0 until numClasses) {
            if (!walkableFlags[c] && nearBlockedCounts[c] > maxNearCount) {
                maxNearCount = nearBlockedCounts[c]
                dominantNearClass = c
            }
        }

        // Side road fraction in the near band immediately left/right of the corridor
        val leftBandStartCol = (startCol - sideBandCols).coerceAtLeast(0)
        val leftBandEndCol = startCol - 1
        var sideRoadLeft = 0f
        if (leftBandStartCol <= leftBandEndCol && nearStartRow < height) {
            var leftRoadCount = 0
            var leftTotalCount = 0
            for (y in nearStartRow until height) {
                val rowOffset = y * width
                for (x in leftBandStartCol..leftBandEndCol) {
                    leftTotalCount++
                    val rawClass = classMask[rowOffset + x].toInt() and 0xFF
                    val safeClass = if (rawClass in 0 until numClasses) rawClass else 0
                    if (safeClass == 1) {
                        leftRoadCount++
                    }
                }
            }
            sideRoadLeft = if (leftTotalCount > 0) leftRoadCount.toFloat() / leftTotalCount else 0f
        }

        val rightBandStartCol = endCol + 1
        val rightBandEndCol = (endCol + sideBandCols).coerceAtMost(width - 1)
        var sideRoadRight = 0f
        if (rightBandStartCol <= rightBandEndCol && nearStartRow < height) {
            var rightRoadCount = 0
            var rightTotalCount = 0
            for (y in nearStartRow until height) {
                val rowOffset = y * width
                for (x in rightBandStartCol..rightBandEndCol) {
                    rightTotalCount++
                    val rawClass = classMask[rowOffset + x].toInt() and 0xFF
                    val safeClass = if (rawClass in 0 until numClasses) rawClass else 0
                    if (safeClass == 1) {
                        rightRoadCount++
                    }
                }
            }
            sideRoadRight = if (rightTotalCount > 0) rightRoadCount.toFloat() / rightTotalCount else 0f
        }

        // 4-connected components of class 6 (pole_sign) across the full width in ROI rows
        val poleBlobs = ArrayList<MaskBlob>()
        if (roiSize > 0) {
            currentRunId++
            if (currentRunId == 0) {
                visited.fill(0)
                currentRunId = 1
            }

            for (y in roiStartRow until height) {
                val rowOffset = y * width
                val roiY = y - roiStartRow
                for (x in 0 until width) {
                    val roiIndex = roiY * width + x
                    val rawClass = classMask[rowOffset + x].toInt() and 0xFF
                    if (rawClass == 6 && visited[roiIndex] != currentRunId) {
                        visited[roiIndex] = currentRunId
                        var head = 0
                        var tail = 0
                        queue[tail++] = roiIndex
                        var count = 0
                        var minX = x
                        var maxX = x
                        var minY = y
                        var maxY = y

                        while (head < tail) {
                            val currRoiIdx = queue[head++]
                            count++
                            val currX = currRoiIdx % width
                            val currY = currRoiIdx / width + roiStartRow
                            if (currX < minX) minX = currX
                            if (currX > maxX) maxX = currX
                            if (currY < minY) minY = currY
                            if (currY > maxY) maxY = currY

                            // Left
                            if (currX > 0) {
                                val nRoiIdx = currRoiIdx - 1
                                if (visited[nRoiIdx] != currentRunId &&
                                    (classMask[currY * width + (currX - 1)].toInt() and 0xFF) == 6
                                ) {
                                    visited[nRoiIdx] = currentRunId
                                    queue[tail++] = nRoiIdx
                                }
                            }
                            // Right
                            if (currX + 1 < width) {
                                val nRoiIdx = currRoiIdx + 1
                                if (visited[nRoiIdx] != currentRunId &&
                                    (classMask[currY * width + (currX + 1)].toInt() and 0xFF) == 6
                                ) {
                                    visited[nRoiIdx] = currentRunId
                                    queue[tail++] = nRoiIdx
                                }
                            }
                            // Up
                            if (currY > roiStartRow) {
                                val nRoiIdx = currRoiIdx - width
                                if (visited[nRoiIdx] != currentRunId &&
                                    (classMask[(currY - 1) * width + currX].toInt() and 0xFF) == 6
                                ) {
                                    visited[nRoiIdx] = currentRunId
                                    queue[tail++] = nRoiIdx
                                }
                            }
                            // Down
                            if (currY + 1 < height) {
                                val nRoiIdx = currRoiIdx + width
                                if (visited[nRoiIdx] != currentRunId &&
                                    (classMask[(currY + 1) * width + currX].toInt() and 0xFF) == 6
                                ) {
                                    visited[nRoiIdx] = currentRunId
                                    queue[tail++] = nRoiIdx
                                }
                            }
                        }

                        if (count >= minPoleBlobPixels) {
                            poleBlobs.add(
                                MaskBlob(
                                    classId = 6,
                                    pixelCount = count,
                                    minX = minX,
                                    maxX = maxX,
                                    minY = minY,
                                    maxY = maxY,
                                )
                            )
                        }
                    }
                }
            }
        }

        return SegmentationFeatures(
            corridor = corridor,
            nearWalkable = nearWalkable.coerceIn(0f, 1f),
            roiWalkable = roiWalkable.coerceIn(0f, 1f),
            nearRoad = nearRoad.coerceIn(0f, 1f),
            sideRoadLeft = sideRoadLeft.coerceIn(0f, 1f),
            sideRoadRight = sideRoadRight.coerceIn(0f, 1f),
            nearCrosswalk = nearCrosswalk.coerceIn(0f, 1f),
            otherRatio = otherRatio.coerceIn(0f, 1f),
            dominantNearClass = dominantNearClass,
            dominantRoiClass = dominantRoiClass,
            poleBlobs = poleBlobs,
        )
    }
}
