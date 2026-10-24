package com.ariateam.aria.vision

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.floor
import kotlin.math.min

/**
 * YCbCr color range and matrix used when converting `YUV_420_888` camera planes to normalized RGB `[0.0f, 1.0f]`.
 *
 * - [BT601_LIMITED]: Canonical ITU-R BT.601 studio/video swing (`Y` in `16..235` -> `0..255`, `U/V` in `16..240` centered at `128`).
 *   Used by default for Android `YUV_420_888` camera streams unless frame dataspace explicitly signals full range.
 * - [BT601_FULL]: Full-swing JFIF YCbCr (`Y` in `0..255`, `U/V` in `0..255` centered at `128`).
 */
enum class YuvColorRange {
    BT601_LIMITED,
    BT601_FULL,
}

/**
 * Spatial resampling filter used during fused crop + resize to `512x288`.
 */
enum class VisionResizeFilter {
    BILINEAR,
    NEAREST,
}

/**
 * Upright (post-rotation) crop rectangle aligned to the 512:288 (16:9) model aspect ratio.
 *
 * When the upright frame is taller than 512:288 (e.g. 640x480 landscape -> 640x360, or 480x640 portrait -> 480x270),
 * the vertical crop is bottom-aligned (`top = uprightHeight - cropHeight`) so the lower road/sidewalk region is preserved,
 * matching upstream `vision_model/test_video_multi.py`.
 * When the upright frame is wider than 512:288, the horizontal crop is centered (`left = (uprightWidth - cropWidth) / 2`).
 */
data class UprightCropRect(
    val bufferWidth: Int,
    val bufferHeight: Int,
    val rotationDegrees: Int,
    val uprightWidth: Int,
    val uprightHeight: Int,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    val normLeft: Float get() = if (uprightWidth > 0) left.toFloat() / uprightWidth else 0f
    val normTop: Float get() = if (uprightHeight > 0) top.toFloat() / uprightHeight else 0f
    val normWidth: Float get() = if (uprightWidth > 0) width.toFloat() / uprightWidth else 1f
    val normHeight: Float get() = if (uprightHeight > 0) height.toFloat() / uprightHeight else 1f
}

/**
 * Exact screen-space rectangle for rendering the 512x288 segmentation overlay or ROI border
 * on top of a `FIT_CENTER` camera preview container of size `(containerWidthPx, containerHeightPx)`.
 */
data class OverlayViewportRect(
    val previewLeftPx: Float,
    val previewTopPx: Float,
    val previewWidthPx: Float,
    val previewHeightPx: Float,
    val cropLeftPx: Float,
    val cropTopPx: Float,
    val cropWidthPx: Float,
    val cropHeightPx: Float,
)

/**
 * Single-pass fused YUV_420_888 preprocessor that converts unrotated sensor planes directly into
 * a reusable `[1, 288, 512, 3]` FLOAT32 RGB `[0.0f, 1.0f]` direct `ByteBuffer`.
 *
 * Design properties:
 * - Zero JPEG compression/decompression, zero full-frame intermediate `Bitmap` or RGBA buffer allocations.
 * - Strictly validates dimensions, rotation (`0`, `90`, `180`, `270`), positive strides, and minimum required plane byte lengths in both `ByteBuffer` and `ByteArray` entry points (never silently clamping out-of-bounds plane offsets).
 * - Uses canonical ITU-R BT.601 limited-range (`Y16=black`, `Y235=white`) conversion by default, with explicit support for full-range (`BT601_FULL`).
 * - Supports precomputed 2x2 `BILINEAR` interpolation (default, matching upstream bilinear/area image scaling) as well as `NEAREST` sampling.
 */
class VisionFramePreprocessor(
    val targetWidth: Int = VisionContract.MODEL_WIDTH,
    val targetHeight: Int = VisionContract.MODEL_HEIGHT,
    val defaultColorRange: YuvColorRange = YuvColorRange.BT601_LIMITED,
    val defaultResizeFilter: VisionResizeFilter = VisionResizeFilter.BILINEAR,
) {
    init {
        require(targetWidth > 0 && targetHeight > 0) {
            "Target dimensions must be positive: ${targetWidth}x${targetHeight}"
        }
    }

    private val pixelCount = targetWidth * targetHeight
    private val floatCount = pixelCount * VisionContract.MODEL_CHANNELS

    val inputByteBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(floatCount * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    private val inputFloatBuffer: FloatBuffer = inputByteBuffer.asFloatBuffer()
    val reusableFloatArray = FloatArray(floatCount)

    private var yPlaneBytes = ByteArray(0)
    private var uPlaneBytes = ByteArray(0)
    private var vPlaneBytes = ByteArray(0)

    private var cachedBufferWidth = -1
    private var cachedBufferHeight = -1
    private var cachedRotation = -1
    private var cachedYRowStride = -1
    private var cachedYPixelStride = -1
    private var cachedURowStride = -1
    private var cachedUPixelStride = -1
    private var cachedVRowStride = -1
    private var cachedVPixelStride = -1
    private var cachedResizeFilter: VisionResizeFilter? = null
    private var cachedCropRect: UprightCropRect? = null

    // 4-corner LUTs for bilinear (and nearest when w00=256, w01=w10=w11=0)
    private val yOffset00Lut = IntArray(pixelCount)
    private val yOffset01Lut = IntArray(pixelCount)
    private val yOffset10Lut = IntArray(pixelCount)
    private val yOffset11Lut = IntArray(pixelCount)
    private val weight00Lut = IntArray(pixelCount)
    private val weight01Lut = IntArray(pixelCount)
    private val weight10Lut = IntArray(pixelCount)
    private val weight11Lut = IntArray(pixelCount)
    private val uOffsetLut = IntArray(pixelCount)
    private val vOffsetLut = IntArray(pixelCount)

    fun preprocessYuv420(
        bufferWidth: Int,
        bufferHeight: Int,
        rotationDegrees: Int,
        yBuffer: ByteBuffer,
        yRowStride: Int,
        yPixelStride: Int,
        uBuffer: ByteBuffer,
        uRowStride: Int,
        uPixelStride: Int,
        vBuffer: ByteBuffer,
        vRowStride: Int,
        vPixelStride: Int,
        colorRange: YuvColorRange = defaultColorRange,
        resizeFilter: VisionResizeFilter = defaultResizeFilter,
    ): UprightCropRect {
        val yLen = yBuffer.remaining()
        val uLen = uBuffer.remaining()
        val vLen = vBuffer.remaining()
        validateFrameAndPlaneBounds(
            bufferWidth = bufferWidth,
            bufferHeight = bufferHeight,
            rotationDegrees = rotationDegrees,
            yLength = yLen,
            yRowStride = yRowStride,
            yPixelStride = yPixelStride,
            uLength = uLen,
            uRowStride = uRowStride,
            uPixelStride = uPixelStride,
            vLength = vLen,
            vRowStride = vRowStride,
            vPixelStride = vPixelStride,
        )

        if (yPlaneBytes.size < yLen) yPlaneBytes = ByteArray(yLen)
        if (uPlaneBytes.size < uLen) uPlaneBytes = ByteArray(uLen)
        if (vPlaneBytes.size < vLen) vPlaneBytes = ByteArray(vLen)

        yBuffer.duplicate().get(yPlaneBytes, 0, yLen)
        uBuffer.duplicate().get(uPlaneBytes, 0, uLen)
        vBuffer.duplicate().get(vPlaneBytes, 0, vLen)

        return preprocessYuv420Arrays(
            bufferWidth = bufferWidth,
            bufferHeight = bufferHeight,
            rotationDegrees = rotationDegrees,
            yBytes = yPlaneBytes,
            yLength = yLen,
            yRowStride = yRowStride,
            yPixelStride = yPixelStride,
            uBytes = uPlaneBytes,
            uLength = uLen,
            uRowStride = uRowStride,
            uPixelStride = uPixelStride,
            vBytes = vPlaneBytes,
            vLength = vLen,
            vRowStride = vRowStride,
            vPixelStride = vPixelStride,
            colorRange = colorRange,
            resizeFilter = resizeFilter,
        )
    }

    fun preprocessYuv420Arrays(
        bufferWidth: Int,
        bufferHeight: Int,
        rotationDegrees: Int,
        yBytes: ByteArray,
        yLength: Int = yBytes.size,
        yRowStride: Int,
        yPixelStride: Int,
        uBytes: ByteArray,
        uLength: Int = uBytes.size,
        uRowStride: Int,
        uPixelStride: Int,
        vBytes: ByteArray,
        vLength: Int = vBytes.size,
        vRowStride: Int,
        vPixelStride: Int,
        colorRange: YuvColorRange = defaultColorRange,
        resizeFilter: VisionResizeFilter = defaultResizeFilter,
    ): UprightCropRect {
        require(yLength <= yBytes.size && uLength <= uBytes.size && vLength <= vBytes.size) {
            "Declared plane length exceeds backing array size"
        }
        validateFrameAndPlaneBounds(
            bufferWidth = bufferWidth,
            bufferHeight = bufferHeight,
            rotationDegrees = rotationDegrees,
            yLength = yLength,
            yRowStride = yRowStride,
            yPixelStride = yPixelStride,
            uLength = uLength,
            uRowStride = uRowStride,
            uPixelStride = uPixelStride,
            vLength = vLength,
            vRowStride = vRowStride,
            vPixelStride = vPixelStride,
        )

        val crop = ensureLookupTables(
            bufferWidth = bufferWidth,
            bufferHeight = bufferHeight,
            rotationDegrees = rotationDegrees,
            yRowStride = yRowStride,
            yPixelStride = yPixelStride,
            uRowStride = uRowStride,
            uPixelStride = uPixelStride,
            vRowStride = vRowStride,
            vPixelStride = vPixelStride,
            resizeFilter = resizeFilter,
        )

        val y00 = yOffset00Lut
        val y01 = yOffset01Lut
        val y10 = yOffset10Lut
        val y11 = yOffset11Lut
        val w00 = weight00Lut
        val w01 = weight01Lut
        val w10 = weight10Lut
        val w11 = weight11Lut
        val uLut = uOffsetLut
        val vLut = vOffsetLut
        val out = reusableFloatArray
        val norm = NORM_LUT
        val isLimited = colorRange == YuvColorRange.BT601_LIMITED
        val isNearest = resizeFilter == VisionResizeFilter.NEAREST

        var outOffset = 0
        for (i in 0 until pixelCount) {
            val yVal = if (isNearest) {
                yBytes[y00[i]].toInt() and 0xFF
            } else {
                val s00 = yBytes[y00[i]].toInt() and 0xFF
                val s01 = yBytes[y01[i]].toInt() and 0xFF
                val s10 = yBytes[y10[i]].toInt() and 0xFF
                val s11 = yBytes[y11[i]].toInt() and 0xFF
                (s00 * w00[i] + s01 * w01[i] + s10 * w10[i] + s11 * w11[i] + 128) shr 8
            }
            val uVal = (uBytes[uLut[i]].toInt() and 0xFF) - 128
            val vVal = (vBytes[vLut[i]].toInt() and 0xFF) - 128

            val rInt: Int
            val gInt: Int
            val bInt: Int
            if (isLimited) {
                // Canonical ITU-R BT.601 studio swing (Y in 16..235, U/V in 16..240)
                val c = (yVal - 16).coerceAtLeast(0)
                val y298 = 298 * c
                rInt = ((y298 + 409 * vVal + 128) shr 8).coerceIn(0, 255)
                gInt = ((y298 - 100 * uVal - 208 * vVal + 128) shr 8).coerceIn(0, 255)
                bInt = ((y298 + 516 * uVal + 128) shr 8).coerceIn(0, 255)
            } else {
                // Full-swing BT.601 / JFIF YCbCr (Y in 0..255, U/V in 0..255)
                rInt = (yVal + ((1436 * vVal + 512) shr 10)).coerceIn(0, 255)
                gInt = (yVal - ((352 * uVal + 731 * vVal + 512) shr 10)).coerceIn(0, 255)
                bInt = (yVal + ((1815 * uVal + 512) shr 10)).coerceIn(0, 255)
            }

            out[outOffset] = norm[rInt]
            out[outOffset + 1] = norm[gInt]
            out[outOffset + 2] = norm[bInt]
            outOffset += 3
        }

        inputFloatBuffer.rewind()
        inputFloatBuffer.put(out)
        inputByteBuffer.rewind()
        return crop
    }

    private fun ensureLookupTables(
        bufferWidth: Int,
        bufferHeight: Int,
        rotationDegrees: Int,
        yRowStride: Int,
        yPixelStride: Int,
        uRowStride: Int,
        uPixelStride: Int,
        vRowStride: Int,
        vPixelStride: Int,
        resizeFilter: VisionResizeFilter,
    ): UprightCropRect {
        val existing = cachedCropRect
        if (existing != null &&
            cachedBufferWidth == bufferWidth &&
            cachedBufferHeight == bufferHeight &&
            cachedRotation == rotationDegrees &&
            cachedYRowStride == yRowStride &&
            cachedYPixelStride == yPixelStride &&
            cachedURowStride == uRowStride &&
            cachedUPixelStride == uPixelStride &&
            cachedVRowStride == vRowStride &&
            cachedVPixelStride == vPixelStride &&
            cachedResizeFilter == resizeFilter
        ) {
            return existing
        }

        val crop = computeUprightCropRect(
            bufferWidth = bufferWidth,
            bufferHeight = bufferHeight,
            rotationDegrees = rotationDegrees,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
        )

        var idx = 0
        for (outY in 0 until targetHeight) {
            val exactUpY = crop.top + ((outY + 0.5f) * crop.height / targetHeight) - 0.5f
            val upY0 = floor(exactUpY).toInt().coerceIn(0, crop.uprightHeight - 1)
            val upY1 = (upY0 + 1).coerceIn(0, crop.uprightHeight - 1)
            val fy = (exactUpY - upY0).coerceIn(0f, 1f)
            val wy1 = (fy * 16f + 0.5f).toInt().coerceIn(0, 16)
            val wy0 = 16 - wy1
            val nearestUpY = (crop.top + ((outY + 0.5f) * crop.height / targetHeight).toInt())
                .coerceIn(0, crop.uprightHeight - 1)

            for (outX in 0 until targetWidth) {
                val exactUpX = crop.left + ((outX + 0.5f) * crop.width / targetWidth) - 0.5f
                val upX0 = floor(exactUpX).toInt().coerceIn(0, crop.uprightWidth - 1)
                val upX1 = (upX0 + 1).coerceIn(0, crop.uprightWidth - 1)
                val fx = (exactUpX - upX0).coerceIn(0f, 1f)
                val wx1 = (fx * 16f + 0.5f).toInt().coerceIn(0, 16)
                val wx0 = 16 - wx1
                val nearestUpX = (crop.left + ((outX + 0.5f) * crop.width / targetWidth).toInt())
                    .coerceIn(0, crop.uprightWidth - 1)

                if (resizeFilter == VisionResizeFilter.NEAREST) {
                    val (srcX, srcY) = mapUprightToSensor(nearestUpX, nearestUpY, bufferWidth, bufferHeight, rotationDegrees)
                    val yOff = srcY * yRowStride + srcX * yPixelStride
                    yOffset00Lut[idx] = yOff
                    yOffset01Lut[idx] = yOff
                    yOffset10Lut[idx] = yOff
                    yOffset11Lut[idx] = yOff
                    weight00Lut[idx] = 256
                    weight01Lut[idx] = 0
                    weight10Lut[idx] = 0
                    weight11Lut[idx] = 0
                    val chromaX = srcX shr 1
                    val chromaY = srcY shr 1
                    uOffsetLut[idx] = chromaY * uRowStride + chromaX * uPixelStride
                    vOffsetLut[idx] = chromaY * vRowStride + chromaX * vPixelStride
                } else {
                    val (sX00, sY00) = mapUprightToSensor(upX0, upY0, bufferWidth, bufferHeight, rotationDegrees)
                    val (sX01, sY01) = mapUprightToSensor(upX1, upY0, bufferWidth, bufferHeight, rotationDegrees)
                    val (sX10, sY10) = mapUprightToSensor(upX0, upY1, bufferWidth, bufferHeight, rotationDegrees)
                    val (sX11, sY11) = mapUprightToSensor(upX1, upY1, bufferWidth, bufferHeight, rotationDegrees)
                    yOffset00Lut[idx] = sY00 * yRowStride + sX00 * yPixelStride
                    yOffset01Lut[idx] = sY01 * yRowStride + sX01 * yPixelStride
                    yOffset10Lut[idx] = sY10 * yRowStride + sX10 * yPixelStride
                    yOffset11Lut[idx] = sY11 * yRowStride + sX11 * yPixelStride
                    weight00Lut[idx] = wx0 * wy0
                    weight01Lut[idx] = wx1 * wy0
                    weight10Lut[idx] = wx0 * wy1
                    weight11Lut[idx] = wx1 * wy1

                    val (cSrcX, cSrcY) = mapUprightToSensor(nearestUpX, nearestUpY, bufferWidth, bufferHeight, rotationDegrees)
                    val chromaX = cSrcX shr 1
                    val chromaY = cSrcY shr 1
                    uOffsetLut[idx] = chromaY * uRowStride + chromaX * uPixelStride
                    vOffsetLut[idx] = chromaY * vRowStride + chromaX * vPixelStride
                }
                idx++
            }
        }

        cachedBufferWidth = bufferWidth
        cachedBufferHeight = bufferHeight
        cachedRotation = rotationDegrees
        cachedYRowStride = yRowStride
        cachedYPixelStride = yPixelStride
        cachedURowStride = uRowStride
        cachedUPixelStride = uPixelStride
        cachedVRowStride = vRowStride
        cachedVPixelStride = vPixelStride
        cachedResizeFilter = resizeFilter
        cachedCropRect = crop
        return crop
    }

    companion object {
        private val NORM_LUT = FloatArray(256) { it / 255.0f }

        fun validateFrameAndPlaneBounds(
            bufferWidth: Int,
            bufferHeight: Int,
            rotationDegrees: Int,
            yLength: Int,
            yRowStride: Int,
            yPixelStride: Int,
            uLength: Int,
            uRowStride: Int,
            uPixelStride: Int,
            vLength: Int,
            vRowStride: Int,
            vPixelStride: Int,
        ) {
            require(bufferWidth > 0 && bufferHeight > 0) {
                "Invalid frame dimensions: ${bufferWidth}x${bufferHeight}"
            }
            require(rotationDegrees in setOf(0, 90, 180, 270)) {
                "Unsupported rotation degrees: $rotationDegrees (must be 0, 90, 180, or 270)"
            }
            require(yRowStride > 0 && yPixelStride > 0 && uRowStride > 0 && uPixelStride > 0 && vRowStride > 0 && vPixelStride > 0) {
                "Plane rowStride and pixelStride must be positive"
            }
            val minYBytes = (bufferHeight - 1) * yRowStride + (bufferWidth - 1) * yPixelStride + 1
            require(yLength >= minYBytes) {
                "Y plane buffer too small: length=$yLength, required>=$minYBytes for ${bufferWidth}x${bufferHeight} (rowStride=$yRowStride, pixelStride=$yPixelStride)"
            }
            val chromaWidth = (bufferWidth + 1) / 2
            val chromaHeight = (bufferHeight + 1) / 2
            val minUBytes = (chromaHeight - 1) * uRowStride + (chromaWidth - 1) * uPixelStride + 1
            val minVBytes = (chromaHeight - 1) * vRowStride + (chromaWidth - 1) * vPixelStride + 1
            require(uLength >= minUBytes) {
                "U plane buffer too small: length=$uLength, required>=$minUBytes for chroma ${chromaWidth}x${chromaHeight} (rowStride=$uRowStride, pixelStride=$uPixelStride)"
            }
            require(vLength >= minVBytes) {
                "V plane buffer too small: length=$vLength, required>=$minVBytes for chroma ${chromaWidth}x${chromaHeight} (rowStride=$vRowStride, pixelStride=$vPixelStride)"
            }
        }

        fun computeUprightCropRect(
            bufferWidth: Int,
            bufferHeight: Int,
            rotationDegrees: Int,
            targetWidth: Int = VisionContract.MODEL_WIDTH,
            targetHeight: Int = VisionContract.MODEL_HEIGHT,
        ): UprightCropRect {
            require(bufferWidth > 0 && bufferHeight > 0) {
                "Invalid frame dimensions: ${bufferWidth}x${bufferHeight}"
            }
            require(rotationDegrees in setOf(0, 90, 180, 270)) {
                "Unsupported rotation degrees: $rotationDegrees"
            }
            val swap = rotationDegrees == 90 || rotationDegrees == 270
            val uprightW = if (swap) bufferHeight else bufferWidth
            val uprightH = if (swap) bufferWidth else bufferHeight

            val scaledHeightForFullWidth = (uprightW * targetHeight) / targetWidth
            val cropLeft: Int
            val cropTop: Int
            val cropWidth: Int
            val cropHeight: Int
            if (scaledHeightForFullWidth <= uprightH) {
                cropWidth = uprightW.coerceAtLeast(1)
                cropHeight = scaledHeightForFullWidth.coerceIn(1, uprightH)
                cropLeft = 0
                // Bottom-align vertical crop to keep the road/sidewalk region at the bottom of the frame.
                cropTop = (uprightH - cropHeight).coerceAtLeast(0)
            } else {
                val scaledWidthForFullHeight = ((uprightH * targetWidth) / targetHeight).coerceIn(1, uprightW)
                cropWidth = scaledWidthForFullHeight
                cropHeight = uprightH.coerceAtLeast(1)
                cropLeft = ((uprightW - cropWidth) / 2).coerceAtLeast(0)
                cropTop = 0
            }

            return UprightCropRect(
                bufferWidth = bufferWidth,
                bufferHeight = bufferHeight,
                rotationDegrees = rotationDegrees,
                uprightWidth = uprightW,
                uprightHeight = uprightH,
                left = cropLeft,
                top = cropTop,
                width = cropWidth,
                height = cropHeight,
            )
        }

        /**
         * Maps an upright coordinate `(upX, upY)` back to unrotated sensor buffer coordinate `(srcX, srcY)`.
         */
        fun mapUprightToSensor(
            upX: Int,
            upY: Int,
            bufferWidth: Int,
            bufferHeight: Int,
            rotationDegrees: Int,
        ): Pair<Int, Int> {
            require(rotationDegrees in setOf(0, 90, 180, 270)) {
                "Unsupported rotation degrees: $rotationDegrees"
            }
            return when (rotationDegrees) {
                90 -> upY.coerceIn(0, bufferWidth - 1) to (bufferHeight - 1 - upX).coerceIn(0, bufferHeight - 1)
                180 -> (bufferWidth - 1 - upX).coerceIn(0, bufferWidth - 1) to (bufferHeight - 1 - upY).coerceIn(0, bufferHeight - 1)
                270 -> (bufferWidth - 1 - upY).coerceIn(0, bufferWidth - 1) to upX.coerceIn(0, bufferHeight - 1)
                else -> upX.coerceIn(0, bufferWidth - 1) to upY.coerceIn(0, bufferHeight - 1)
            }
        }

        /**
         * Computes the exact viewport coordinates of the camera preview content and the 512:288 crop region
         * inside a `FIT_CENTER` preview container of size `(containerWidthPx, containerHeightPx)`.
         *
         * If [previewUprightWidth] and [previewUprightHeight] are provided (from `Preview.getResolutionInfo()`),
         * the container `FIT_CENTER` box uses the actual Preview stream aspect ratio and maps [cropRect]'s
         * normalized bounds (`normLeft`, `normTop`, `normWidth`, `normHeight`) onto the rendered preview box.
         */
        fun computeFitCenterOverlayViewport(
            containerWidthPx: Float,
            containerHeightPx: Float,
            cropRect: UprightCropRect,
            previewUprightWidth: Int = cropRect.uprightWidth,
            previewUprightHeight: Int = cropRect.uprightHeight,
        ): OverlayViewportRect {
            val effectivePreviewW = if (previewUprightWidth > 0) previewUprightWidth else cropRect.uprightWidth
            val effectivePreviewH = if (previewUprightHeight > 0) previewUprightHeight else cropRect.uprightHeight
            if (containerWidthPx <= 0f || containerHeightPx <= 0f || effectivePreviewW <= 0 || effectivePreviewH <= 0) {
                return OverlayViewportRect(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            }
            val scale = min(
                containerWidthPx / effectivePreviewW.toFloat(),
                containerHeightPx / effectivePreviewH.toFloat(),
            )
            val previewW = effectivePreviewW * scale
            val previewH = effectivePreviewH * scale
            val previewLeft = (containerWidthPx - previewW) * 0.5f
            val previewTop = (containerHeightPx - previewH) * 0.5f

            val cropLeft = previewLeft + cropRect.normLeft * previewW
            val cropTop = previewTop + cropRect.normTop * previewH
            val cropW = cropRect.normWidth * previewW
            val cropH = cropRect.normHeight * previewH

            return OverlayViewportRect(
                previewLeftPx = previewLeft,
                previewTopPx = previewTop,
                previewWidthPx = previewW,
                previewHeightPx = previewH,
                cropLeftPx = cropLeft,
                cropTopPx = cropTop,
                cropWidthPx = cropW,
                cropHeightPx = cropH,
            )
        }
    }
}
