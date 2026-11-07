package com.ariateam.aria.hazard

import com.ariateam.aria.vision.VisionContract
import java.util.ArrayList
import java.util.Arrays
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class WallFootlineCandidate(
    val contactXNorm: Float,
    val contactYNorm: Float,
    val leftNorm: Float,
    val rightNorm: Float,
    val spanRatio: Float,
    val distanceMeters: Float?,
    val lateralMeters: Float?,
    val confirmed: Boolean,
)

data class VerticalEdgeCandidate(
    val xNorm: Float,
    val topNorm: Float,
    val bottomNorm: Float,
    val distanceMeters: Float?,
    val confirmed: Boolean,
)

data class StructuralEdgeAnalysis(
    val wallFootline: WallFootlineCandidate?,
    val verticalCandidates: List<VerticalEdgeCandidate>,
    val roiLumaVariance: Float,
    val roiEdgeDensity: Float,
    val isCloseWallOcclusion: Boolean,
    val syntheticTracks: List<ObjectTrack>,
    val adjustedFeatures: SegmentationFeatures,
)

/**
 * Mask-gated structural Canny edge extractor for detecting wall/step horizontal footlines,
 * vertical structural obstacle edges (pillars/doorframes), and close blank-wall looming occlusion.
 *
 * Designed with pre-allocated buffers to ensure zero per-pixel heap allocations per frame.
 */
class StructuralEdgeExtractor(
    val width: Int = VisionContract.MODEL_WIDTH,
    val height: Int = VisionContract.MODEL_HEIGHT,
    val roiStartRow: Int = VisionContract.ROI_START_ROW,
    val lowThreshold: Float = 0.08f,
    val highThreshold: Float = 0.18f,
    val boundaryBandPx: Int = 6,
    val minFootlineSpanRatio: Float = 0.35f,
    val minVerticalHeightRatio: Float = 0.35f,
    val blankWallMaxVariance: Float = 0.0025f,
    val blankWallMaxEdgeDensity: Float = 0.02f,
) {
    private val roiRows = (height - roiStartRow).coerceAtLeast(0)
    private val roiSize = width * roiRows

    // Rows needed for separable blur: (roiStartRow - 1) up to (height - 1)
    private val blurStartRow = (roiStartRow - 1).coerceAtLeast(0)
    private val blurRows = (height - blurStartRow).coerceAtLeast(0)

    // Pre-allocated buffers for zero per-pixel allocation
    private val fullLumaBuffer = FloatArray(width * height)
    private val blurXBuffer = FloatArray(width * (blurRows + 2))
    private val blurredBuffer = FloatArray(width * (blurRows + 2))
    private val gx = FloatArray(roiSize)
    private val gy = FloatArray(roiSize)
    private val mag = FloatArray(roiSize)
    private val nmsMag = FloatArray(roiSize)
    private val isEdge = BooleanArray(roiSize)
    private val queue = IntArray(roiSize)

    // Column candidate buffers
    private val hasEdgeCol = BooleanArray(width)
    private val boundaryY = IntArray(width)
    private val sortBuf = IntArray(width)

    // Vertical chain buffers
    private val colChainTop = IntArray(width)
    private val colChainBottom = IntArray(width)
    private val colChainLen = IntArray(width)

    // Temporal tracking state
    private var prevFootlineYNorm: Float? = null
    private var prevFootlineCaptureNanos: Long = 0L
    private var prevFootlineConfirmed: Boolean = false

    private val prevVerticalCandidates = ArrayList<VerticalEdgeCandidate>()
    private var prevVerticalCaptureNanos: Long = 0L

    private var lastWalkableNanos: Long = -1L

    fun reset() {
        prevFootlineYNorm = null
        prevFootlineCaptureNanos = 0L
        prevFootlineConfirmed = false
        prevVerticalCandidates.clear()
        prevVerticalCaptureNanos = 0L
        lastWalkableNanos = -1L
    }

    fun analyze(
        lumaOrRgb: FloatArray,
        classMask: ByteArray,
        features: SegmentationFeatures,
        captureNanos: Long,
        isWalking: Boolean = true,
        projector: PointProjector? = null,
    ): StructuralEdgeAnalysis {
        require(lumaOrRgb.size >= width * height) {
            "Luma/RGB buffer size ${lumaOrRgb.size} is smaller than minimum required ${width * height}"
        }
        require(classMask.size >= width * height) {
            "Class mask buffer size ${classMask.size} is smaller than required ${width * height}"
        }

        // 1. Resolve luma plane
        val luma: FloatArray = if (lumaOrRgb.size >= width * height * 3) {
            val totalPixels = width * height
            for (i in 0 until totalPixels) {
                val r = lumaOrRgb[i * 3]
                val g = lumaOrRgb[i * 3 + 1]
                val b = lumaOrRgb[i * 3 + 2]
                fullLumaBuffer[i] = 0.299f * r + 0.587f * g + 0.114f * b
            }
            fullLumaBuffer
        } else {
            lumaOrRgb
        }

        if (roiRows <= 0 || width <= 0) {
            return StructuralEdgeAnalysis(
                wallFootline = null,
                verticalCandidates = emptyList(),
                roiLumaVariance = 0f,
                roiEdgeDensity = 0f,
                isCloseWallOcclusion = false,
                syntheticTracks = emptyList(),
                adjustedFeatures = features,
            )
        }

        // 2. Separable Gaussian blur [1, 2, 1] / 4
        // Pass 1: Horizontal blur for rows from max(0, blurStartRow - 1) until height
        val hStartRow = max(0, blurStartRow - 1)
        for (y in hStartRow until height) {
            val inRowOffset = y * width
            val outRowOffset = (y - hStartRow) * width
            for (x in 0 until width) {
                val x0 = max(0, x - 1)
                val x1 = x
                val x2 = min(width - 1, x + 1)
                val v0 = luma[inRowOffset + x0]
                val v1 = luma[inRowOffset + x1]
                val v2 = luma[inRowOffset + x2]
                blurXBuffer[outRowOffset + x] = 0.25f * v0 + 0.5f * v1 + 0.25f * v2
            }
        }

        // Pass 2: Vertical blur for rows from blurStartRow until height
        for (y in blurStartRow until height) {
            val y0 = max(hStartRow, y - 1) - hStartRow
            val y1 = y - hStartRow
            val y2 = min(height - 1, y + 1) - hStartRow
            val r0 = y0 * width
            val r1 = y1 * width
            val r2 = y2 * width
            val outOffset = (y - blurStartRow) * width
            for (x in 0 until width) {
                val v0 = blurXBuffer[r0 + x]
                val v1 = blurXBuffer[r1 + x]
                val v2 = blurXBuffer[r2 + x]
                blurredBuffer[outOffset + x] = 0.25f * v0 + 0.5f * v1 + 0.25f * v2
            }
        }

        // 3. Sobel 3x3 gradients gx, gy and magnitude for roiStartRow until height
        for (roiY in 0 until roiRows) {
            val y = roiStartRow + roiY
            val y0 = max(blurStartRow, y - 1) - blurStartRow
            val y1 = y - blurStartRow
            val y2 = min(height - 1, y + 1) - blurStartRow

            val r0 = y0 * width
            val r1 = y1 * width
            val r2 = y2 * width
            val roiOffset = roiY * width

            for (x in 0 until width) {
                val x0 = max(0, x - 1)
                val x2 = min(width - 1, x + 1)

                val p00 = blurredBuffer[r0 + x0]
                val p01 = blurredBuffer[r0 + x]
                val p02 = blurredBuffer[r0 + x2]

                val p10 = blurredBuffer[r1 + x0]
                val p12 = blurredBuffer[r1 + x2]

                val p20 = blurredBuffer[r2 + x0]
                val p21 = blurredBuffer[r2 + x]
                val p22 = blurredBuffer[r2 + x2]

                val gValX = (p02 - p00 + 2f * (p12 - p10) + p22 - p20) / 4f
                val gValY = (p20 - p00 + 2f * (p21 - p01) + p22 - p02) / 4f
                val gMag = sqrt(gValX * gValX + gValY * gValY)

                val idx = roiOffset + x
                gx[idx] = gValX
                gy[idx] = gValY
                mag[idx] = gMag
            }
        }

        // 4. 4-direction Non-Maximum Suppression (NMS)
        for (roiY in 0 until roiRows) {
            val rowOffset = roiY * width
            for (x in 0 until width) {
                val roiIdx = rowOffset + x
                val m = mag[roiIdx]
                if (m < lowThreshold) {
                    nmsMag[roiIdx] = 0f
                    continue
                }

                val gxx = gx[roiIdx]
                val gyy = gy[roiIdx]
                val ax = abs(gxx)
                val ay = abs(gyy)

                val n1: Float
                val n2: Float

                if (ay <= 0.4142f * ax) {
                    // Mostly horizontal gradient -> vertical edge
                    n1 = if (x > 0) mag[rowOffset + (x - 1)] else 0f
                    n2 = if (x + 1 < width) mag[rowOffset + (x + 1)] else 0f
                } else if (ax <= 0.4142f * ay) {
                    // Mostly vertical gradient -> horizontal edge
                    n1 = if (roiY > 0) mag[(roiY - 1) * width + x] else 0f
                    n2 = if (roiY + 1 < roiRows) mag[(roiY + 1) * width + x] else 0f
                } else if ((gxx > 0f) == (gyy > 0f)) {
                    // Diagonal 45 deg / -135 deg
                    n1 = if (x > 0 && roiY > 0) mag[(roiY - 1) * width + (x - 1)] else 0f
                    n2 = if (x + 1 < width && roiY + 1 < roiRows) mag[(roiY + 1) * width + (x + 1)] else 0f
                } else {
                    // Diagonal 135 deg / -45 deg
                    n1 = if (x + 1 < width && roiY > 0) mag[(roiY - 1) * width + (x + 1)] else 0f
                    n2 = if (x > 0 && roiY + 1 < roiRows) mag[(roiY + 1) * width + (x - 1)] else 0f
                }

                if (m < n1 || m < n2) {
                    nmsMag[roiIdx] = 0f
                } else {
                    nmsMag[roiIdx] = m
                }
            }
        }

        // 5. Double-threshold Hysteresis
        isEdge.fill(false)
        var head = 0
        var tail = 0

        for (i in 0 until roiSize) {
            if (nmsMag[i] >= highThreshold) {
                isEdge[i] = true
                queue[tail++] = i
            }
        }

        while (head < tail) {
            val currIdx = queue[head++]
            val cx = currIdx % width
            val cy = currIdx / width

            val yMin = max(0, cy - 1)
            val yMax = min(roiRows - 1, cy + 1)
            val xMin = max(0, cx - 1)
            val xMax = min(width - 1, cx + 1)

            for (ny in yMin..yMax) {
                val nRow = ny * width
                for (nx in xMin..xMax) {
                    val nIdx = nRow + nx
                    if (!isEdge[nIdx] && nmsMag[nIdx] >= lowThreshold) {
                        isEdge[nIdx] = true
                        queue[tail++] = nIdx
                    }
                }
            }
        }

        // 6. Active corridor metrics: Luma mean & variance, ROI edge density
        val startCol = features.corridor.startCol.coerceIn(0, width - 1)
        val endCol = features.corridor.endCol.coerceIn(0, width - 1)
        val corridorWidth = if (endCol >= startCol) endCol - startCol + 1 else 0
        val corridorPixels = roiRows * corridorWidth

        var sumLuma = 0.0
        for (y in roiStartRow until height) {
            val rowOffset = y * width
            for (x in startCol..endCol) {
                sumLuma += luma[rowOffset + x]
            }
        }
        val meanLuma = if (corridorPixels > 0) (sumLuma / corridorPixels).toFloat() else 0f

        var sumSqDiff = 0.0
        var corridorEdges = 0
        for (roiY in 0 until roiRows) {
            val y = roiStartRow + roiY
            val lumaRowOffset = y * width
            val roiRowOffset = roiY * width
            for (x in startCol..endCol) {
                val diff = luma[lumaRowOffset + x] - meanLuma
                sumSqDiff += diff * diff
                if (isEdge[roiRowOffset + x]) {
                    corridorEdges++
                }
            }
        }
        val roiLumaVariance = if (corridorPixels > 0) (sumSqDiff / corridorPixels).toFloat().coerceAtLeast(0f) else 0f
        val roiEdgeDensity = if (corridorPixels > 0) (corridorEdges.toFloat() / corridorPixels).coerceIn(0f, 1f) else 0f

        // 7. Wall/step horizontal footline detection
        hasEdgeCol.fill(false)
        boundaryY.fill(0)

        for (x in startCol..endCol) {
            var yTrans = -1
            for (y in roiStartRow until height - 1) {
                val cAbove = classMask[y * width + x].toInt() and 0xFF
                val cBelow = classMask[(y + 1) * width + x].toInt() and 0xFF
                if (cAbove == 0 && (cBelow == 2 || cBelow == 3)) {
                    // Verify rows at/above y are other (0)
                    val isAboveOther = if (y > roiStartRow) {
                        (classMask[(y - 1) * width + x].toInt() and 0xFF) == 0
                    } else {
                        true
                    }
                    if (isAboveOther) {
                        yTrans = y
                        break // Highest transition in image (smallest y)
                    }
                }
            }

            if (yTrans != -1) {
                val yMin = max(roiStartRow, yTrans - boundaryBandPx)
                val yMax = min(height - 1, yTrans + boundaryBandPx)
                var edgeFound = false
                for (ye in yMin..yMax) {
                    val roiIdx = (ye - roiStartRow) * width + x
                    if (isEdge[roiIdx] && abs(gy[roiIdx]) >= 2f * abs(gx[roiIdx])) {
                        edgeFound = true
                        break
                    }
                }
                if (edgeFound) {
                    hasEdgeCol[x] = true
                    boundaryY[x] = yTrans
                }
            }
        }

        // Longest contiguous column run allowing 1-2 px gaps
        var longestRunStart = -1
        var longestRunEnd = -1
        var maxRunWidth = 0

        var currentRunStart = -1
        var lastTrueCol = -1

        for (x in startCol..endCol) {
            if (hasEdgeCol[x]) {
                if (currentRunStart == -1) {
                    currentRunStart = x
                    lastTrueCol = x
                } else {
                    val gap = x - lastTrueCol - 1
                    if (gap <= 2) {
                        lastTrueCol = x
                    } else {
                        val runW = lastTrueCol - currentRunStart + 1
                        if (runW > maxRunWidth) {
                            maxRunWidth = runW
                            longestRunStart = currentRunStart
                            longestRunEnd = lastTrueCol
                        }
                        currentRunStart = x
                        lastTrueCol = x
                    }
                }
            }
        }
        if (currentRunStart != -1) {
            val runW = lastTrueCol - currentRunStart + 1
            if (runW > maxRunWidth) {
                maxRunWidth = runW
                longestRunStart = currentRunStart
                longestRunEnd = lastTrueCol
            }
        }

        var wallFootline: WallFootlineCandidate? = null
        val spanRatio = if (corridorWidth > 0) maxRunWidth.toFloat() / corridorWidth.toFloat() else 0f
        if (longestRunStart != -1 && spanRatio >= minFootlineSpanRatio) {
            var k = 0
            for (x in longestRunStart..longestRunEnd) {
                if (hasEdgeCol[x]) {
                    sortBuf[k++] = boundaryY[x]
                }
            }
            if (k > 0) {
                Arrays.sort(sortBuf, 0, k)
                val medianBoundaryY = if (k % 2 == 1) {
                    sortBuf[k / 2].toFloat()
                } else {
                    (sortBuf[k / 2 - 1] + sortBuf[k / 2]) / 2f
                }
                val runCenterCol = (longestRunStart + longestRunEnd) / 2
                val contactXNorm = (runCenterCol + 0.5f) / width
                val contactYNorm = (medianBoundaryY + 0.5f) / height
                val leftNorm = longestRunStart.toFloat() / width
                val rightNorm = (longestRunEnd + 1).toFloat() / width

                val intersection = projector?.project(contactXNorm, contactYNorm)
                val distanceMeters = intersection?.forwardDistanceMeters
                val lateralMeters = intersection?.lateralDistanceMeters

                val confirmed = if (prevFootlineYNorm != null) {
                    if (captureNanos == prevFootlineCaptureNanos) {
                        prevFootlineConfirmed
                    } else if (captureNanos > prevFootlineCaptureNanos &&
                        (captureNanos - prevFootlineCaptureNanos) <= 700_000_000L &&
                        abs(contactYNorm - prevFootlineYNorm!!) <= 0.08f
                    ) {
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }

                prevFootlineYNorm = contactYNorm
                prevFootlineCaptureNanos = captureNanos
                prevFootlineConfirmed = confirmed

                wallFootline = WallFootlineCandidate(
                    contactXNorm = contactXNorm,
                    contactYNorm = contactYNorm,
                    leftNorm = leftNorm,
                    rightNorm = rightNorm,
                    spanRatio = spanRatio,
                    distanceMeters = distanceMeters,
                    lateralMeters = lateralMeters,
                    confirmed = confirmed,
                )
            }
        } else {
            if (captureNanos > prevFootlineCaptureNanos) {
                prevFootlineYNorm = null
                prevFootlineConfirmed = false
            }
        }

        // 8. Vertical structural obstacle edge detection
        colChainLen.fill(0)
        val minVertHeight = minVerticalHeightRatio * roiRows

        for (x in startCol..endCol) {
            var chainStartY = -1
            var chainLastY = -1
            var bestStartY = -1
            var bestEndY = -1
            var bestLen = 0

            for (y in roiStartRow until height) {
                val roiIdx = (y - roiStartRow) * width + x
                val isVert = ((classMask[y * width + x].toInt() and 0xFF) == 0) &&
                    isEdge[roiIdx] &&
                    (abs(gx[roiIdx]) >= 2f * abs(gy[roiIdx]))
                if (isVert) {
                    if (chainStartY == -1) {
                        chainStartY = y
                        chainLastY = y
                    } else {
                        val gap = y - chainLastY - 1
                        if (gap <= 2) {
                            chainLastY = y
                        } else {
                            val len = chainLastY - chainStartY + 1
                            if (len > bestLen) {
                                bestLen = len
                                bestStartY = chainStartY
                                bestEndY = chainLastY
                            }
                            chainStartY = y
                            chainLastY = y
                        }
                    }
                }
            }
            if (chainStartY != -1) {
                val len = chainLastY - chainStartY + 1
                if (len > bestLen) {
                    bestLen = len
                    bestStartY = chainStartY
                    bestEndY = chainLastY
                }
            }

            if (bestLen >= minVertHeight) {
                val checkMaxY = min(height - 1, bestEndY + boundaryBandPx)
                var touchesWalkable = false
                for (cy in bestEndY..checkMaxY) {
                    val c0 = classMask[cy * width + x].toInt() and 0xFF
                    if (c0 == 2 || c0 == 3) {
                        touchesWalkable = true
                        break
                    }
                    if (x > 0) {
                        val cl = classMask[cy * width + (x - 1)].toInt() and 0xFF
                        if (cl == 2 || cl == 3) {
                            touchesWalkable = true
                            break
                        }
                    }
                    if (x + 1 < width) {
                        val cr = classMask[cy * width + (x + 1)].toInt() and 0xFF
                        if (cr == 2 || cr == 3) {
                            touchesWalkable = true
                            break
                        }
                    }
                }
                if (touchesWalkable) {
                    colChainTop[x] = bestStartY
                    colChainBottom[x] = bestEndY
                    colChainLen[x] = bestLen
                }
            }
        }

        // Cluster adjacent vertical columns within 2 px
        val currentVerticalCandidates = ArrayList<VerticalEdgeCandidate>()
        var clusterStartCol = -1
        var clusterLastCol = -1

        fun flushCluster(cStart: Int, cEnd: Int) {
            var minTop = height
            var maxBottom = 0
            for (cx in cStart..cEnd) {
                if (colChainLen[cx] > 0) {
                    minTop = min(minTop, colChainTop[cx])
                    maxBottom = max(maxBottom, colChainBottom[cx])
                }
            }
            val centerCol = (cStart + cEnd) / 2
            val xNorm = (centerCol + 0.5f) / width
            val topNorm = minTop.toFloat() / height
            val bottomNorm = (maxBottom + 1).toFloat() / height

            val intersection = projector?.project(xNorm, bottomNorm)
            val distanceMeters = intersection?.forwardDistanceMeters

            var confirmed = false
            if (prevVerticalCandidates.isNotEmpty()) {
                if (captureNanos == prevVerticalCaptureNanos) {
                    val prevMatch = prevVerticalCandidates.firstOrNull { abs(it.xNorm - xNorm) <= 0.06f }
                    confirmed = prevMatch?.confirmed ?: false
                } else if (captureNanos > prevVerticalCaptureNanos &&
                    (captureNanos - prevVerticalCaptureNanos) <= 700_000_000L
                ) {
                    val prevMatch = prevVerticalCandidates.firstOrNull { abs(it.xNorm - xNorm) <= 0.06f }
                    if (prevMatch != null) {
                        confirmed = true
                    }
                }
            }

            currentVerticalCandidates.add(
                VerticalEdgeCandidate(
                    xNorm = xNorm,
                    topNorm = topNorm,
                    bottomNorm = bottomNorm,
                    distanceMeters = distanceMeters,
                    confirmed = confirmed,
                )
            )
        }

        for (x in startCol..endCol) {
            if (colChainLen[x] > 0) {
                if (clusterStartCol == -1) {
                    clusterStartCol = x
                    clusterLastCol = x
                } else {
                    val gap = x - clusterLastCol - 1
                    if (gap <= 2) {
                        clusterLastCol = x
                    } else {
                        flushCluster(clusterStartCol, clusterLastCol)
                        clusterStartCol = x
                        clusterLastCol = x
                    }
                }
            }
        }
        if (clusterStartCol != -1) {
            flushCluster(clusterStartCol, clusterLastCol)
        }

        if (captureNanos > prevVerticalCaptureNanos) {
            prevVerticalCandidates.clear()
            prevVerticalCandidates.addAll(currentVerticalCandidates)
            prevVerticalCaptureNanos = captureNanos
        }

        // 9. Close blank-wall looming occlusion
        if (isWalking) {
            if (features.nearWalkable >= 0.35f || wallFootline?.confirmed == true) {
                lastWalkableNanos = captureNanos
            }
        }

        val recentHistory = isWalking && (lastWalkableNanos > 0L) &&
            (captureNanos >= lastWalkableNanos) &&
            ((captureNanos - lastWalkableNanos) <= 1_500_000_000L)

        val isCloseWallOcclusion = isWalking &&
            recentHistory &&
            (features.otherRatio > 0.6f) &&
            (features.nearWalkable < 0.2f) &&
            (roiLumaVariance <= blankWallMaxVariance) &&
            (roiEdgeDensity <= blankWallMaxEdgeDensity)

        val adjustedFeatures = if (isCloseWallOcclusion) {
            features.copy(otherRatio = 0.5f)
        } else {
            features
        }

        // 10. Assemble synthetic ObjectTrack entries
        val syntheticTracks = ArrayList<ObjectTrack>()
        if (wallFootline != null) {
            syntheticTracks.add(
                ObjectTrack(
                    id = -100,
                    kind = ObjectKind.UNKNOWN,
                    score = 0.9f,
                    left = wallFootline.leftNorm,
                    top = (wallFootline.contactYNorm - 0.25f).coerceAtLeast(0f),
                    right = wallFootline.rightNorm,
                    bottom = wallFootline.contactYNorm,
                    distanceMeters = wallFootline.distanceMeters,
                    lateralMeters = wallFootline.lateralMeters,
                    ttcSeconds = null,
                    lateralVelocityNorm = 0f,
                    corridorOverlap = wallFootline.spanRatio.coerceIn(0f, 1f),
                    confirmed = wallFootline.confirmed,
                    segAgree = true,
                    lastCaptureNanos = captureNanos,
                )
            )
        }

        for ((idx, cand) in currentVerticalCandidates.withIndex()) {
            val intersection = projector?.project(cand.xNorm, cand.bottomNorm)
            syntheticTracks.add(
                ObjectTrack(
                    id = -200 - idx,
                    kind = ObjectKind.POLE,
                    score = 0.9f,
                    left = (cand.xNorm - 0.02f).coerceAtLeast(0f),
                    top = cand.topNorm,
                    right = (cand.xNorm + 0.02f).coerceAtMost(1f),
                    bottom = cand.bottomNorm,
                    distanceMeters = cand.distanceMeters,
                    lateralMeters = intersection?.lateralDistanceMeters,
                    ttcSeconds = null,
                    lateralVelocityNorm = 0f,
                    corridorOverlap = 1.0f,
                    confirmed = cand.confirmed,
                    segAgree = true,
                    lastCaptureNanos = captureNanos,
                )
            )
        }

        return StructuralEdgeAnalysis(
            wallFootline = wallFootline,
            verticalCandidates = currentVerticalCandidates,
            roiLumaVariance = roiLumaVariance,
            roiEdgeDensity = roiEdgeDensity,
            isCloseWallOcclusion = isCloseWallOcclusion,
            syntheticTracks = syntheticTracks,
            adjustedFeatures = adjustedFeatures,
        )
    }
}
