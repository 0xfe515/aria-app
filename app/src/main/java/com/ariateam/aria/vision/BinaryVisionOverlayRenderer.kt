package com.ariateam.aria.vision

/**
 * Unified binary presentation renderer for Aria accessibility vision overlays.
 *
 * Every pixel inside the valid image area is rendered with exactly one of two values:
 * - [VisionContract.OVERLAY_COLOR_GREEN] (0x8039FF39) for walkable surfaces (sidewalk, crosswalk, and
 *   gap-filled crosswalk pavement).
 * - [VisionContract.OVERLAY_COLOR_RED] (0x80FF3939) for non-walkable surfaces, obstacles, and background.
 *
 * Both colors share an identical alpha channel (0x80), ensuring uniform contrast without transparent holes.
 * Raw semantic class masks, probability tensors, and hazard metrics remain completely unmodified.
 */
internal class BinaryVisionOverlayRenderer(
    private val overlayComponentFilter: OverlayComponentFilter = OverlayComponentFilter(),
) {
    /**
     * Renders [classMask] into [outOverlayArgb] using binary presentation colors, optional crosswalk
     * region gap-filling from [crosswalkRegionEstimator], and noise component suppression.
     */
    fun renderOverlay(
        classMask: ByteArray,
        outOverlayArgb: IntArray,
        width: Int,
        height: Int,
        crosswalkRegionEstimator: CrosswalkRegionEstimator? = null,
        crosswalkRegionBuffer: BooleanArray? = null,
        minimumComponentPixels: Int = OverlayComponentFilter.DEFAULT_MINIMUM_COMPONENT_PIXELS,
    ) {
        val pixelCount = width * height
        require(width > 0 && height > 0 && width <= Int.MAX_VALUE / height) {
            "Dimensions must be positive: width=$width, height=$height"
        }
        require(classMask.size >= pixelCount && outOverlayArgb.size >= pixelCount) {
            "Arrays too small for ${width}x${height}: classMask=${classMask.size}, outOverlayArgb=${outOverlayArgb.size}"
        }

        val green = VisionContract.OVERLAY_COLOR_GREEN
        val red = VisionContract.OVERLAY_COLOR_RED

        // 1. Initial binary mapping from 7-class semantic class mask:
        // Classes 2 (sidewalk) and 3 (crosswalk) map to green.
        // Classes 0, 1, 4, 5, 6 as well as unknown/invalid classes map to red.
        for (i in 0 until pixelCount) {
            val classId = classMask[i].toInt() and 0xFF
            outOverlayArgb[i] = if (classId == 2 || classId == 3) green else red
        }

        // 2. Crosswalk supplementary region estimation and gap-filling:
        // Bridges inter-stripe road/background gaps into green while preserving obstacle classes
        // (4 = person, 5 = vehicle, 6 = pole/sign) and invalid class IDs as red holes.
        if (crosswalkRegionEstimator != null && crosswalkRegionBuffer != null && crosswalkRegionBuffer.size >= pixelCount) {
            crosswalkRegionEstimator.estimate(classMask, width, height, crosswalkRegionBuffer)
            for (i in 0 until pixelCount) {
                if (crosswalkRegionBuffer[i]) {
                    val classId = classMask[i].toInt() and 0xFF
                    val isKnownObstacleOrInvalid = classId == 4 || classId == 5 || classId == 6 ||
                        classId !in 0 until VisionContract.MODEL_CLASSES
                    if (!isKnownObstacleOrInvalid) {
                        outOverlayArgb[i] = green
                    }
                }
            }
        }

        // 3. Small-component noise filtering:
        // Noisy green components smaller than threshold become red.
        // Obstacles are already red and remain red even if tiny.
        overlayComponentFilter.suppressSmallComponents(
            classMask = classMask,
            overlayArgb = outOverlayArgb,
            width = width,
            height = height,
            minimumComponentPixels = minimumComponentPixels,
        )
    }
}
