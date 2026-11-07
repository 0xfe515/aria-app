package com.ariateam.aria.vision.od

import com.ariateam.aria.vision.UprightCropRect
import com.ariateam.aria.vision.VisionContract
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Pure geometry adapter between the frozen `512x288` segmentation tensor and the square
 * object-detection tensor.
 *
 * Sampling path (upright crop -> detector):
 * 1. Each detector pixel is mapped through the aspect-preserving letterbox back to normalized
 *    upright-crop coordinates `(u, v)`.
 * 2. `(u, v)` is mapped to the segmentation tensor with
 *    [UprightCropRect.mapUprightNormToModelTensorX]/[UprightCropRect.mapUprightNormToModelTensorY],
 *    which already accounts for `FULL_FRAME_ROTATED_LANDSCAPE` rotation,
 *    `FULL_FRAME_UPRIGHT_LETTERBOX` padding and `LEGACY_CROP` cropping.
 * 3. The tensor is sampled bilinearly (padding samples are black), so the detector always sees an
 *    upright, letterboxed copy of the same crop the segmentation grid represents.
 *
 * Box path (detector -> upright crop): the inverse of step 1 only. Because the detector input was
 * sampled from the upright crop, detector-normalized coordinates are already upright-crop
 * normalized coordinates once the letterbox is removed. Applying
 * [UprightCropRect.mapModelTensorToUprightNorm] again would double-invert rotation.
 *
 * The adapter never mutates the caller's RGB tensor.
 */
internal class OdFrameAdapter(
    val tensorWidth: Int = VisionContract.MODEL_INPUT_WIDTH,
    val tensorHeight: Int = VisionContract.MODEL_INPUT_HEIGHT,
    val detectorSize: Int = LiteRtObjectDetector.DETECTOR_INPUT_SIZE,
) {
    init {
        require(tensorWidth > 0 && tensorHeight > 0) {
            "Tensor dimensions must be positive: ${tensorWidth}x$tensorHeight"
        }
        require(detectorSize > 0) { "Detector size must be positive: $detectorSize" }
    }

    /** Aspect-preserving letterbox of the upright crop inside the square detector tensor. */
    data class Letterbox(
        val padLeft: Float,
        val padTop: Float,
        val contentWidth: Float,
        val contentHeight: Float,
    )

    fun computeLetterbox(transform: UprightCropRect): Letterbox {
        val cropWidth = max(transform.width, 1)
        val cropHeight = max(transform.height, 1)
        val scale = min(
            detectorSize.toFloat() / cropWidth.toFloat(),
            detectorSize.toFloat() / cropHeight.toFloat(),
        )
        val contentWidth = (cropWidth * scale).coerceIn(1f, detectorSize.toFloat())
        val contentHeight = (cropHeight * scale).coerceIn(1f, detectorSize.toFloat())
        return Letterbox(
            padLeft = (detectorSize - contentWidth) * 0.5f,
            padTop = (detectorSize - contentHeight) * 0.5f,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
        )
    }

    /** True when the frame carries a usable geometry description. */
    fun isUsable(transform: UprightCropRect): Boolean =
        transform.uprightWidth > 0 &&
            transform.uprightHeight > 0 &&
            transform.width > 0 &&
            transform.height > 0

    /**
     * Fills [out] (`detectorSize * detectorSize * 3` RGB floats in `[0, 1]`, row-major) with the
     * letterboxed upright crop sampled from [inputRgb].
     */
    fun fillDetectorTensor(
        inputRgb: FloatArray,
        transform: UprightCropRect,
        out: FloatArray,
    ) {
        val letterbox = computeLetterbox(transform)
        val padLeft = letterbox.padLeft
        val padTop = letterbox.padTop
        val contentWidth = letterbox.contentWidth
        val contentHeight = letterbox.contentHeight
        val contentRight = padLeft + contentWidth
        val contentBottom = padTop + contentHeight

        var offset = 0
        for (dy in 0 until detectorSize) {
            val centerY = dy + 0.5f
            val insideRow = centerY >= padTop && centerY <= contentBottom
            val vNorm = if (insideRow) ((centerY - padTop) / contentHeight).coerceIn(0f, 1f) else 0f
            for (dx in 0 until detectorSize) {
                val centerX = dx + 0.5f
                if (!insideRow || centerX < padLeft || centerX > contentRight) {
                    out[offset] = 0f
                    out[offset + 1] = 0f
                    out[offset + 2] = 0f
                    offset += 3
                    continue
                }
                val uNorm = ((centerX - padLeft) / contentWidth).coerceIn(0f, 1f)
                val modelX = transform.mapUprightNormToModelTensorX(uNorm, vNorm, tensorWidth)
                val modelY = transform.mapUprightNormToModelTensorY(uNorm, vNorm, tensorHeight)
                sampleBilinear(inputRgb, modelX, modelY, out, offset)
                offset += 3
            }
        }
    }

    /**
     * Maps one detector-space box (normalized `[0, 1]` over the full detector tensor) back to
     * upright-crop normalized display coordinates, clipping letterbox padding.
     *
     * Returns `null` when the clipped box is empty or non-finite.
     */
    fun mapDetectorBoxToCropNorm(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        transform: UprightCropRect,
    ): FloatArray? {
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return null
        // Order is validated, not silently repaired: an inverted box is a contract violation.
        if (right <= left || bottom <= top) return null
        val letterbox = computeLetterbox(transform)
        if (letterbox.contentWidth <= 0f || letterbox.contentHeight <= 0f) return null

        val pixelLeft = left * detectorSize
        val pixelTop = top * detectorSize
        val pixelRight = right * detectorSize
        val pixelBottom = bottom * detectorSize

        val u1 = ((pixelLeft - letterbox.padLeft) / letterbox.contentWidth).coerceIn(0f, 1f)
        val v1 = ((pixelTop - letterbox.padTop) / letterbox.contentHeight).coerceIn(0f, 1f)
        val u2 = ((pixelRight - letterbox.padLeft) / letterbox.contentWidth).coerceIn(0f, 1f)
        val v2 = ((pixelBottom - letterbox.padTop) / letterbox.contentHeight).coerceIn(0f, 1f)

        val cropLeft = min(u1, u2)
        val cropRight = max(u1, u2)
        val cropTop = min(v1, v2)
        val cropBottom = max(v1, v2)
        if (cropRight <= cropLeft || cropBottom <= cropTop) return null
        return floatArrayOf(cropLeft, cropTop, cropRight, cropBottom)
    }

    private fun sampleBilinear(
        inputRgb: FloatArray,
        modelX: Float,
        modelY: Float,
        out: FloatArray,
        outOffset: Int,
    ) {
        val x = (modelX - 0.5f).coerceIn(0f, (tensorWidth - 1).toFloat())
        val y = (modelY - 0.5f).coerceIn(0f, (tensorHeight - 1).toFloat())
        val x0 = floor(x).toInt().coerceIn(0, tensorWidth - 1)
        val y0 = floor(y).toInt().coerceIn(0, tensorHeight - 1)
        val x1 = min(x0 + 1, tensorWidth - 1)
        val y1 = min(y0 + 1, tensorHeight - 1)
        val tx = (x - x0).coerceIn(0f, 1f)
        val ty = (y - y0).coerceIn(0f, 1f)

        val rowStride = tensorWidth * VisionContract.MODEL_CHANNELS
        val i00 = y0 * rowStride + x0 * VisionContract.MODEL_CHANNELS
        val i01 = y0 * rowStride + x1 * VisionContract.MODEL_CHANNELS
        val i10 = y1 * rowStride + x0 * VisionContract.MODEL_CHANNELS
        val i11 = y1 * rowStride + x1 * VisionContract.MODEL_CHANNELS

        for (channel in 0 until VisionContract.MODEL_CHANNELS) {
            val top = inputRgb[i00 + channel] * (1f - tx) + inputRgb[i01 + channel] * tx
            val bottom = inputRgb[i10 + channel] * (1f - tx) + inputRgb[i11 + channel] * tx
            out[outOffset + channel] = top * (1f - ty) + bottom * ty
        }
    }
}
