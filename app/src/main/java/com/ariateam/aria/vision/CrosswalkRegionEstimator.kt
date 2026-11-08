package com.ariateam.aria.vision

import java.util.Arrays
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Deterministic geometric crosswalk region estimator for display presentation.
 *
 * Infers a conservative filled crossing envelope from nearby aligned elongated class 3
 * (crosswalk stripe) evidence, bridging bounded inter-stripe road/background gaps (classes 0 and 1).
 *
 * Guarantees:
 * - Pure display supplement: does not mutate [classMask] or alter model/hazard semantics.
 * - Strict obstacle safety: obstacle classes (4 = person, 5 = vehicle, 6 = pole/sign) and invalid/unknown
 *   class IDs are strictly preserved as false (obstacle holes) even inside the crossing envelope.
 * - Anti-leakage / isolation: rejects isolated noise/single patches and keeps separate distant crossings
 *   independent without unbounded global convex hull or axis-aligned bounding box fills.
 * - Bounded CPU and memory: zero per-pixel or per-frame allocations, statically pre-allocated primitive arrays,
 *   deterministic cap on candidate components (K <= 64) to strictly bound O(K^2) pairing.
 */
internal class CrosswalkRegionEstimator {

    // Component analysis buffers (grown on demand only if image resolution increases)
    private var visitedEpochs = IntArray(0)
    private var componentPixels = IntArray(0)
    private var epoch = 0

    // Statically bounded candidate stripe buffers (deterministic K <= MAX_CANDIDATE_STRIPES)
    private val stripePixelStart = IntArray(MAX_CANDIDATE_STRIPES)
    private val stripePixelCount = IntArray(MAX_CANDIDATE_STRIPES)
    private val stripeCenterX = FloatArray(MAX_CANDIDATE_STRIPES)
    private val stripeCenterY = FloatArray(MAX_CANDIDATE_STRIPES)
    private val stripeDirX = FloatArray(MAX_CANDIDATE_STRIPES)
    private val stripeDirY = FloatArray(MAX_CANDIDATE_STRIPES)
    private val stripeMajorLength = FloatArray(MAX_CANDIDATE_STRIPES)
    private val stripeBoundaryX = FloatArray(MAX_CANDIDATE_STRIPES * BOUNDARY_POINTS_PER_STRIPE)
    private val stripeBoundaryY = FloatArray(MAX_CANDIDATE_STRIPES * BOUNDARY_POINTS_PER_STRIPE)

    // Clustering and pairing scratch
    private val ufParent = IntArray(MAX_CANDIDATE_STRIPES)
    private val ufClusterSize = IntArray(MAX_CANDIDATE_STRIPES)
    private val bestPosNeighbor = IntArray(MAX_CANDIDATE_STRIPES)
    private val bestNegNeighbor = IntArray(MAX_CANDIDATE_STRIPES)
    private val bestPosDist = FloatArray(MAX_CANDIDATE_STRIPES)
    private val bestNegDist = FloatArray(MAX_CANDIDATE_STRIPES)

    // Pairs scratch (at most 2 pairs per candidate stripe)
    private var pairCount = 0
    private val pairStripeA = IntArray(MAX_CANDIDATE_STRIPES * 2)
    private val pairStripeB = IntArray(MAX_CANDIDATE_STRIPES * 2)

    // Convex hull and rasterizer scratch (16 points maximum per pair of stripes)
    private val hullPtsX = FloatArray(PAIR_POINTS_MAX)
    private val hullPtsY = FloatArray(PAIR_POINTS_MAX)
    private val lowerX = FloatArray(PAIR_POINTS_MAX)
    private val lowerY = FloatArray(PAIR_POINTS_MAX)
    private val upperX = FloatArray(PAIR_POINTS_MAX)
    private val upperY = FloatArray(PAIR_POINTS_MAX)
    private val polyX = FloatArray(PAIR_POINTS_MAX)
    private val polyY = FloatArray(PAIR_POINTS_MAX)

    fun estimate(classMask: ByteArray, width: Int, height: Int, outRegion: BooleanArray) {
        require(width > 0 && height > 0 && width <= Int.MAX_VALUE / height) {
            "Dimensions must be positive and fit in an Int: width=$width, height=$height"
        }
        val pixelCount = width * height
        require(classMask.size >= pixelCount) {
            "classMask size ${classMask.size} is smaller than required pixelCount $pixelCount"
        }
        require(outRegion.size >= pixelCount) {
            "outRegion size ${outRegion.size} is smaller than required pixelCount $pixelCount"
        }

        // Always clear destination region across frames / dimension changes
        Arrays.fill(outRegion, 0, pixelCount, false)

        ensurePixelBuffers(pixelCount)

        var candidateCount = 0

        // 1. Connected Component Labeling of class 3 (crosswalk) pixels
        var queueTail = 0
        for (startIndex in 0 until pixelCount) {
            val classId = classMask[startIndex].toInt() and 0xFF
            if (classId != CLASS_CROSSWALK || visitedEpochs[startIndex] == epoch) {
                continue
            }

            val componentStart = queueTail
            componentPixels[queueTail++] = startIndex
            visitedEpochs[startIndex] = epoch

            var head = componentStart
            var sumX = 0L
            var sumY = 0L
            var sumXX = 0L
            var sumYY = 0L
            var sumXY = 0L
            var minX = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var minY = Int.MAX_VALUE
            var maxY = Int.MIN_VALUE

            while (head < queueTail) {
                val index = componentPixels[head++]
                val y = index / width
                val x = index - y * width

                sumX += x
                sumY += y
                val xl = x.toLong()
                val yl = y.toLong()
                sumXX += xl * xl
                sumYY += yl * yl
                sumXY += xl * yl

                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y

                // 8-connected neighbors
                val nMinY = if (y > 0) y - 1 else y
                val nMaxY = if (y + 1 < height) y + 1 else y
                val nMinX = if (x > 0) x - 1 else x
                val nMaxX = if (x + 1 < width) x + 1 else x

                var ny = nMinY
                while (ny <= nMaxY) {
                    val rowStart = ny * width
                    var nx = nMinX
                    while (nx <= nMaxX) {
                        val neighborIndex = rowStart + nx
                        if (neighborIndex != index &&
                            visitedEpochs[neighborIndex] != epoch &&
                            (classMask[neighborIndex].toInt() and 0xFF) == CLASS_CROSSWALK
                        ) {
                            visitedEpochs[neighborIndex] = epoch
                            componentPixels[queueTail++] = neighborIndex
                        }
                        nx++
                    }
                    ny++
                }
            }

            val area = queueTail - componentStart
            if (area < MIN_STRIPE_PIXELS) {
                continue
            }

            // Central moments and orientation PCA
            val cx = sumX.toDouble() / area
            val cy = sumY.toDouble() / area
            val mu20 = (sumXX.toDouble() / area) - cx * cx
            val mu02 = (sumYY.toDouble() / area) - cy * cy
            val mu11 = (sumXY.toDouble() / area) - cx * cy

            val delta = sqrt((mu20 - mu02) * (mu20 - mu02) + 4.0 * mu11 * mu11)
            val lambda1 = (mu20 + mu02 + delta) * 0.5
            val lambda2 = max(0.01, (mu20 + mu02 - delta) * 0.5)

            val elongation = sqrt(lambda1 / lambda2)
            val majorLength = sqrt(12.0 * lambda1)

            // Strict stripe geometry evidence: require significant elongation and length
            if (elongation < MIN_STRIPE_ELONGATION || majorLength < MIN_STRIPE_LENGTH) {
                continue
            }

            // Statically cap candidate count to bound O(K^2) pairing
            if (candidateCount >= MAX_CANDIDATE_STRIPES) {
                break
            }

            val angle = 0.5 * atan2(2.0 * mu11, mu20 - mu02)
            val dirX = cos(angle).toFloat()
            val dirY = sin(angle).toFloat()
            val normX = -dirY
            val normY = dirX

            stripePixelStart[candidateCount] = componentStart
            stripePixelCount[candidateCount] = area
            stripeCenterX[candidateCount] = cx.toFloat()
            stripeCenterY[candidateCount] = cy.toFloat()
            stripeDirX[candidateCount] = dirX
            stripeDirY[candidateCount] = dirY
            stripeMajorLength[candidateCount] = majorLength.toFloat()

            // Extract 4 exact Oriented Bounding Box (OBB) corners in the principal orientation basis
            var minU = Float.MAX_VALUE
            var maxU = -Float.MAX_VALUE
            var minV = Float.MAX_VALUE
            var maxV = -Float.MAX_VALUE

            for (p in componentStart until queueTail) {
                val idx = componentPixels[p]
                val py = (idx / width).toFloat()
                val px = (idx - (idx / width) * width).toFloat()

                val projU = px * dirX + py * dirY
                if (projU < minU) minU = projU
                if (projU > maxU) maxU = projU

                val projV = px * normX + py * normY
                if (projV < minV) minV = projV
                if (projV > maxV) maxV = projV
            }

            val bOffset = candidateCount * BOUNDARY_POINTS_PER_STRIPE
            // Corner 0: (minU, minV)
            stripeBoundaryX[bOffset + 0] = minU * dirX + minV * normX
            stripeBoundaryY[bOffset + 0] = minU * dirY + minV * normY
            // Corner 1: (maxU, minV)
            stripeBoundaryX[bOffset + 1] = maxU * dirX + minV * normX
            stripeBoundaryY[bOffset + 1] = maxU * dirY + minV * normY
            // Corner 2: (maxU, maxV)
            stripeBoundaryX[bOffset + 2] = maxU * dirX + maxV * normX
            stripeBoundaryY[bOffset + 2] = maxU * dirY + maxV * normY
            // Corner 3: (minU, maxV)
            stripeBoundaryX[bOffset + 3] = minU * dirX + maxV * normX
            stripeBoundaryY[bOffset + 3] = minU * dirY + maxV * normY

            candidateCount++
        }

        // Multiple supported stripes required; single isolated patches rejected
        if (candidateCount < MIN_CLUSTER_STRIPES) {
            return
        }

        // 2. Discover strictly adjacent compatible stripe pairs
        for (i in 0 until candidateCount) {
            bestPosNeighbor[i] = -1
            bestNegNeighbor[i] = -1
            bestPosDist[i] = Float.MAX_VALUE
            bestNegDist[i] = Float.MAX_VALUE
            ufParent[i] = i
            ufClusterSize[i] = 1
        }

        val resolutionScale = (height.toFloat() / 288.0f).coerceIn(0.2f, 2.0f)
        val maxResolutionGap = MAX_TRANSVERSE_GAP_BASE_PIXELS * resolutionScale

        for (i in 0 until candidateCount) {
            val uix = stripeDirX[i]
            val uiy = stripeDirY[i]
            val cxi = stripeCenterX[i]
            val cyi = stripeCenterY[i]
            val lenI = stripeMajorLength[i]

            for (j in i + 1 until candidateCount) {
                val ujx = stripeDirX[j]
                val ujy = stripeDirY[j]
                val align = uix * ujx + uiy * ujy
                val absAlign = Math.abs(align)
                if (absAlign < MIN_ALIGNMENT_COSINE) {
                    continue
                }

                // Coordinate system aligned with mutual orientation
                val sign = if (align >= 0f) 1f else -1f
                val sujX = ujx * sign
                val sujY = ujy * sign

                var avgDirX = uix + sujX
                var avgDirY = uiy + sujY
                val dirLen = sqrt(avgDirX * avgDirX + avgDirY * avgDirY)
                if (dirLen < 1e-4f) continue
                avgDirX /= dirLen
                avgDirY /= dirLen
                val avgNormX = -avgDirY
                val avgNormY = avgDirX

                val dx = stripeCenterX[j] - cxi
                val dy = stripeCenterY[j] - cyi
                val lenJ = stripeMajorLength[j]
                val avgMajorLength = (lenI + lenJ) * 0.5f

                val transDisp = dx * avgNormX + dy * avgNormY
                val absTransDisp = Math.abs(transDisp)

                // Transverse gap must be constrained by component length, not arbitrary absolute distance.
                // Small blobs cannot bridge large gaps; only proportional stripes are bridged.
                val maxTransGap = min(maxResolutionGap, max(6.0f, avgMajorLength * 1.5f))
                if (absTransDisp > maxTransGap || absTransDisp < 1.0f) {
                    continue
                }

                // Longitudinal offset must be strictly bounded
                val longDisp = Math.abs(dx * avgDirX + dy * avgDirY)
                val maxLongDisp = 0.60f * max(lenI, lenJ) + 8.0f
                if (longDisp > maxLongDisp) {
                    continue
                }

                // Require substantial longitudinal overlap along the stripe axis (>= 35% of min length)
                var uMinI = Float.MAX_VALUE; var uMaxI = -Float.MAX_VALUE
                val bOffsetI = i * BOUNDARY_POINTS_PER_STRIPE
                for (b in 0 until BOUNDARY_POINTS_PER_STRIPE) {
                    val pu = stripeBoundaryX[bOffsetI + b] * avgDirX + stripeBoundaryY[bOffsetI + b] * avgDirY
                    if (pu < uMinI) uMinI = pu
                    if (pu > uMaxI) uMaxI = pu
                }

                var uMinJ = Float.MAX_VALUE; var uMaxJ = -Float.MAX_VALUE
                val bOffsetJ = j * BOUNDARY_POINTS_PER_STRIPE
                for (b in 0 until BOUNDARY_POINTS_PER_STRIPE) {
                    val pu = stripeBoundaryX[bOffsetJ + b] * avgDirX + stripeBoundaryY[bOffsetJ + b] * avgDirY
                    if (pu < uMinJ) uMinJ = pu
                    if (pu > uMaxJ) uMaxJ = pu
                }

                val uOverlap = min(uMaxI, uMaxJ) - max(uMinI, uMinJ)
                val minLen = min(lenI, lenJ)
                if (uOverlap < 0.35f * minLen) {
                    continue
                }

                // Register nearest immediate transverse neighbor to prevent long-distance leaping
                if (transDisp > 0f) {
                    if (absTransDisp < bestPosDist[i]) {
                        bestPosDist[i] = absTransDisp
                        bestPosNeighbor[i] = j
                    }
                    if (absTransDisp < bestNegDist[j]) {
                        bestNegDist[j] = absTransDisp
                        bestNegNeighbor[j] = i
                    }
                } else {
                    if (absTransDisp < bestNegDist[i]) {
                        bestNegDist[i] = absTransDisp
                        bestNegNeighbor[i] = j
                    }
                    if (absTransDisp < bestPosDist[j]) {
                        bestPosDist[j] = absTransDisp
                        bestPosNeighbor[j] = i
                    }
                }
            }
        }

        // 3. Assemble unique adjacent pairs and cluster candidate stripes
        pairCount = 0
        for (i in 0 until candidateCount) {
            val pos = bestPosNeighbor[i]
            if (pos != -1) {
                addUniquePair(i, pos)
                unionStripes(i, pos)
            }
            val neg = bestNegNeighbor[i]
            if (neg != -1) {
                addUniquePair(i, neg)
                unionStripes(i, neg)
            }
        }

        if (pairCount == 0) {
            return
        }

        // 4. Fill supported stripes and rasterize convex hulls of adjacent pairs
        // First mark raw member pixels of stripes in clusters that met the minimum size
        for (k in 0 until candidateCount) {
            val root = findRoot(k)
            if (ufClusterSize[root] < MIN_CLUSTER_STRIPES) {
                continue
            }
            val start = stripePixelStart[k]
            val end = start + stripePixelCount[k]
            for (p in start until end) {
                val idx = componentPixels[p]
                val classId = classMask[idx].toInt() and 0xFF
                if (classId == CLASS_CROSSWALK || classId == CLASS_BACKGROUND || classId == CLASS_ROAD) {
                    outRegion[idx] = true
                }
            }
        }

        // Bridge inter-stripe gaps for each adjacent pair within supported clusters
        for (p in 0 until pairCount) {
            val sa = pairStripeA[p]
            val sb = pairStripeB[p]
            val root = findRoot(sa)
            if (ufClusterSize[root] < MIN_CLUSTER_STRIPES) {
                continue
            }

            bridgeStripePair(sa, sb, classMask, width, height, outRegion)
        }
    }

    private fun bridgeStripePair(
        sa: Int,
        sb: Int,
        classMask: ByteArray,
        width: Int,
        height: Int,
        outRegion: BooleanArray
    ) {
        val bOffsetA = sa * BOUNDARY_POINTS_PER_STRIPE
        val bOffsetB = sb * BOUNDARY_POINTS_PER_STRIPE

        for (i in 0 until BOUNDARY_POINTS_PER_STRIPE) {
            hullPtsX[i] = stripeBoundaryX[bOffsetA + i]
            hullPtsY[i] = stripeBoundaryY[bOffsetA + i]
            hullPtsX[BOUNDARY_POINTS_PER_STRIPE + i] = stripeBoundaryX[bOffsetB + i]
            hullPtsY[BOUNDARY_POINTS_PER_STRIPE + i] = stripeBoundaryY[bOffsetB + i]
        }

        // Sort 16 points by X, then Y
        for (i in 1 until PAIR_POINTS_MAX) {
            val kx = hullPtsX[i]
            val ky = hullPtsY[i]
            var j = i - 1
            while (j >= 0 && (hullPtsX[j] > kx || (hullPtsX[j] == kx && hullPtsY[j] > ky))) {
                hullPtsX[j + 1] = hullPtsX[j]
                hullPtsY[j + 1] = hullPtsY[j]
                j--
            }
            hullPtsX[j + 1] = kx
            hullPtsY[j + 1] = ky
        }

        // Monotone chain 2D convex hull
        var lowerCount = 0
        for (i in 0 until PAIR_POINTS_MAX) {
            val px = hullPtsX[i]
            val py = hullPtsY[i]
            while (lowerCount >= 2) {
                val x1 = lowerX[lowerCount - 2]
                val y1 = lowerY[lowerCount - 2]
                val x2 = lowerX[lowerCount - 1]
                val y2 = lowerY[lowerCount - 1]
                val cross = (x2 - x1) * (py - y1) - (y2 - y1) * (px - x1)
                if (cross <= 0.0001f) {
                    lowerCount--
                } else {
                    break
                }
            }
            lowerX[lowerCount] = px
            lowerY[lowerCount] = py
            lowerCount++
        }

        var upperCount = 0
        for (i in PAIR_POINTS_MAX - 1 downTo 0) {
            val px = hullPtsX[i]
            val py = hullPtsY[i]
            while (upperCount >= 2) {
                val x1 = upperX[upperCount - 2]
                val y1 = upperY[upperCount - 2]
                val x2 = upperX[upperCount - 1]
                val y2 = upperY[upperCount - 1]
                val cross = (x2 - x1) * (py - y1) - (y2 - y1) * (px - x1)
                if (cross <= 0.0001f) {
                    upperCount--
                } else {
                    break
                }
            }
            upperX[upperCount] = px
            upperY[upperCount] = py
            upperCount++
        }

        var polySize = 0
        for (i in 0 until lowerCount - 1) {
            polyX[polySize] = lowerX[i]
            polyY[polySize] = lowerY[i]
            polySize++
        }
        for (i in 0 until upperCount - 1) {
            polyX[polySize] = upperX[i]
            polyY[polySize] = upperY[i]
            polySize++
        }

        if (polySize < 3) {
            return
        }

        // Scanline rasterization bounded to polygon extent
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until polySize) {
            val y = polyY[i]
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        val scanMinY = max(0, Math.floor(minY.toDouble()).toInt())
        val scanMaxY = min(height - 1, Math.ceil(maxY.toDouble()).toInt())

        for (y in scanMinY..scanMaxY) {
            val yF = y.toFloat()
            var interCount = 0
            var x1 = 0.0f
            var x2 = 0.0f

            for (i in 0 until polySize) {
                val next = if (i + 1 == polySize) 0 else i + 1
                val py1 = polyY[i]
                val py2 = polyY[next]
                if ((py1 <= yF && py2 > yF) || (py2 <= yF && py1 > yF)) {
                    val px1 = polyX[i]
                    val px2 = polyX[next]
                    val interX = px1 + (yF - py1) * (px2 - px1) / (py2 - py1)
                    if (interCount == 0) {
                        x1 = interX
                        interCount = 1
                    } else {
                        x2 = interX
                        interCount = 2
                        break
                    }
                }
            }

            if (interCount == 2) {
                val minX = min(x1, x2)
                val maxX = max(x1, x2)
                val startX = max(0, Math.round(minX))
                val endX = min(width - 1, Math.round(maxX))
                val rowOffset = y * width
                for (x in startX..endX) {
                    val idx = rowOffset + x
                    val classId = classMask[idx].toInt() and 0xFF
                    // Strictly allow only crosswalk and bounded road/background gap filling.
                    // Obstacle classes (4, 5, 6) and invalid/unknown IDs remain false.
                    if (classId == CLASS_CROSSWALK || classId == CLASS_BACKGROUND || classId == CLASS_ROAD) {
                        outRegion[idx] = true
                    }
                }
            }
        }
    }

    private fun addUniquePair(a: Int, b: Int) {
        val u = min(a, b)
        val v = max(a, b)
        for (p in 0 until pairCount) {
            if (pairStripeA[p] == u && pairStripeB[p] == v) {
                return
            }
        }
        if (pairCount < pairStripeA.size) {
            pairStripeA[pairCount] = u
            pairStripeB[pairCount] = v
            pairCount++
        }
    }

    private fun findRoot(i: Int): Int {
        var curr = i
        while (curr != ufParent[curr]) {
            ufParent[curr] = ufParent[ufParent[curr]]
            curr = ufParent[curr]
        }
        return curr
    }

    private fun unionStripes(a: Int, b: Int) {
        val rootA = findRoot(a)
        val rootB = findRoot(b)
        if (rootA != rootB) {
            if (ufClusterSize[rootA] >= ufClusterSize[rootB]) {
                ufParent[rootB] = rootA
                ufClusterSize[rootA] += ufClusterSize[rootB]
            } else {
                ufParent[rootA] = rootB
                ufClusterSize[rootB] += ufClusterSize[rootA]
            }
        }
    }

    private fun ensurePixelBuffers(pixelCount: Int) {
        if (visitedEpochs.size < pixelCount) {
            visitedEpochs = IntArray(pixelCount)
            componentPixels = IntArray(pixelCount)
            epoch = 0
        }
        if (epoch == Int.MAX_VALUE) {
            Arrays.fill(visitedEpochs, 0)
            epoch = 1
        } else {
            epoch++
        }
    }

    internal companion object {
        const val CLASS_BACKGROUND = 0
        const val CLASS_ROAD = 1
        const val CLASS_SIDEWALK = 2
        const val CLASS_CROSSWALK = 3
        const val CLASS_PERSON = 4
        const val CLASS_VEHICLE = 5
        const val CLASS_POLE_SIGN = 6

        const val MIN_STRIPE_PIXELS = 10
        const val MIN_STRIPE_LENGTH = 6.0
        const val MIN_STRIPE_ELONGATION = 1.30
        const val MIN_ALIGNMENT_COSINE = 0.75f // ~41.4 degrees tolerance for perspective convergence
        const val MAX_TRANSVERSE_GAP_BASE_PIXELS = 75.0f
        const val MIN_CLUSTER_STRIPES = 2
        const val MAX_CANDIDATE_STRIPES = 64

        private const val BOUNDARY_POINTS_PER_STRIPE = 4
        private const val PAIR_POINTS_MAX = 8
    }
}
