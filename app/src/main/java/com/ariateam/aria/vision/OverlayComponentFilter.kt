package com.ariateam.aria.vision

import java.util.Arrays

internal class OverlayComponentFilter {
    private var visitedEpochs = IntArray(0)
    private var componentPixels = IntArray(0)
    private var epoch = 0

    fun suppressSmallComponents(
        classMask: ByteArray,
        overlayArgb: IntArray,
        width: Int,
        height: Int,
        minimumComponentPixels: Int = DEFAULT_MINIMUM_COMPONENT_PIXELS,
    ) {
        require(width > 0 && height > 0 && width <= Int.MAX_VALUE / height) {
            "Dimensions must be positive and fit in an Int: width=$width, height=$height"
        }
        require(minimumComponentPixels >= 0) { "Minimum component area must not be negative" }
        val pixelCount = width * height
        require(classMask.size >= pixelCount && overlayArgb.size >= pixelCount) {
            "Mask arrays too small for ${width}x${height}"
        }
        if (minimumComponentPixels == 0) return

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

        val colors = VisionContract.CLASS_OVERLAY_ARGB
        val nearZoneStartRow = ((height.toLong() * NEAR_ZONE_START_ROW + MODEL_HEIGHT - 1) / MODEL_HEIGHT).toInt()
        var pixelCountQueued = 0

        for (startIndex in 0 until pixelCount) {
            val classId = classMask[startIndex].toInt() and 0xFF
            if (classId >= colors.size || (colors[classId] ushr 24) == 0 || visitedEpochs[startIndex] == epoch) {
                continue
            }

            val componentStart = pixelCountQueued
            componentPixels[pixelCountQueued++] = startIndex
            visitedEpochs[startIndex] = epoch
            var head = componentStart
            var touchesProtectedNearZone = false
            while (head < pixelCountQueued) {
                val index = componentPixels[head++]
                val y = index / width
                if ((classId == 4 || classId == 5 || classId == 6) && y >= nearZoneStartRow) {
                    touchesProtectedNearZone = true
                }

                val minY = if (y > 0) y - 1 else y
                val maxY = if (y + 1 < height) y + 1 else y
                val x = index - y * width
                val minX = if (x > 0) x - 1 else x
                val maxX = if (x + 1 < width) x + 1 else x
                var neighborY = minY
                while (neighborY <= maxY) {
                    val rowStart = neighborY * width
                    var neighborX = minX
                    while (neighborX <= maxX) {
                        val neighborIndex = rowStart + neighborX
                        if (
                            neighborIndex != index &&
                            visitedEpochs[neighborIndex] != epoch &&
                            (classMask[neighborIndex].toInt() and 0xFF) == classId
                        ) {
                            visitedEpochs[neighborIndex] = epoch
                            componentPixels[pixelCountQueued++] = neighborIndex
                        }
                        neighborX++
                    }
                    neighborY++
                }
            }

            if (
                pixelCountQueued - componentStart < minimumComponentPixels &&
                !touchesProtectedNearZone
            ) {
                var componentIndex = componentStart
                while (componentIndex < pixelCountQueued) {
                    overlayArgb[componentPixels[componentIndex]] = TRANSPARENT
                    componentIndex++
                }
            }
        }
    }

    internal companion object {
        const val DEFAULT_MINIMUM_COMPONENT_PIXELS = 32
        const val MODEL_HEIGHT = 288
        const val NEAR_ZONE_START_ROW = 230
        const val TRANSPARENT = 0x00000000
    }
}
