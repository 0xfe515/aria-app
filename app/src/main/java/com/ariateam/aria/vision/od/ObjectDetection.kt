package com.ariateam.aria.vision.od

import com.ariateam.aria.vision.UprightCropRect

/**
 * One auxiliary object-detection result in **upright crop display space**.
 *
 * Coordinate contract (frozen, see `docs/AUXILIARY_OD_IMPLEMENTATION_2026-11-07.md`):
 * - [left], [top], [right], [bottom] are normalized to `[0.0f, 1.0f]` relative to the upright crop
 *   rectangle described by [UprightCropRect] (the same `512x288` display grid used by the
 *   segmentation `reusableClassMask`). They are never metric distances.
 * - Boxes produced by an [ObjectDetectorEngine] are always finite, ordered
 *   (`left < right`, `top < bottom`) and clipped to the non-padding content region.
 * - [classId] is the model's own zero-based category index (COCO-90 labelmap, which has gaps),
 *   not a compacted index.
 */
data class DetectedObject(
    val classId: Int,
    val label: String,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width * height

    /** True only for a finite, positively sized, unit-clipped box. */
    val hasValidGeometry: Boolean
        get() = score.isFinite() &&
            left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f &&
            left < right && top < bottom

    companion object {
        /**
         * Builds a [DetectedObject] only when every invariant holds, otherwise returns `null`.
         *
         * Engines must use this factory (or an equivalent check) so invalid raw tensor output can
         * never surface as a detected obstacle.
         */
        fun createOrNull(
            classId: Int,
            label: String,
            score: Float,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
        ): DetectedObject? {
            if (classId < 0) return null
            if (!score.isFinite() || score <= 0f || score > 1f) return null
            val l = left
            val t = top
            val r = right
            val b = bottom
            if (!l.isFinite() || !t.isFinite() || !r.isFinite() || !b.isFinite()) return null
            if (r <= l || b <= t) return null
            val cl = l.coerceIn(0f, 1f)
            val ct = t.coerceIn(0f, 1f)
            val cr = r.coerceIn(0f, 1f)
            val cb = b.coerceIn(0f, 1f)
            if (cr <= cl || cb <= ct) return null
            return DetectedObject(
                classId = classId,
                label = label,
                score = score,
                left = cl,
                top = ct,
                right = cr,
                bottom = cb,
            )
        }
    }
}

/**
 * Lightweight object-detection backend consumed by the auxiliary sidecar pipeline.
 *
 * Implementations are created, invoked, and closed on a single sidecar worker thread; they are not
 * required to be thread-safe. The camera analyzer never calls them.
 */
interface ObjectDetectorEngine : AutoCloseable {
    /**
     * Runs one detection pass over a frozen upright-crop RGB tensor.
     *
     * @param inputRgb `[0.0f, 1.0f]` RGB tensor in the existing preprocessing layout
     *   `[height][width][channel]` with `height = VisionContract.MODEL_INPUT_HEIGHT` (512) and
     *   `width = VisionContract.MODEL_INPUT_WIDTH` (288). The caller owns this array; the engine
     *   samples it read-only and must never mutate it.
     * @param transform geometry of the frame that produced [inputRgb]; used to reconstruct the
     *   upright crop (including rotation and letterbox padding) for the detector and to map
     *   detector boxes back into upright crop display space.
     * @return detections already filtered to supported obstacle classes, ordered by descending
     *   score, empty when nothing qualifies. Implementations fail closed: they return an empty
     *   list instead of throwing for invalid input, and throw only for a closed engine.
     */
    fun detect(inputRgb: FloatArray, transform: UprightCropRect): List<DetectedObject>
}
