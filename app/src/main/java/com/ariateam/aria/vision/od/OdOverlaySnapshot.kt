package com.ariateam.aria.vision.od

/**
 * Immutable, display-only object-detection overlay snapshot for the current camera session and
 * frame geometry.
 *
 * Kept strictly separate from [ObjectDetectionHazardFusion]: visual boxes may remain visible until
 * [expiresAtElapsedNanos] (monotonic elapsed-realtime nanoseconds) so 5 Hz / 2 Hz sidecar updates
 * do not flicker between camera frames, while safety hazard fusion continues to enforce its strict
 * `<= 250 ms` freshness bound.
 *
 * Every [DetectedObject] in [detections] is normalized to `[0.0f, 1.0f]` in upright crop display
 * space (the same grid as the segmentation display overlay).
 */
data class OdOverlaySnapshot(
    val detections: List<DetectedObject>,
    val expiresAtElapsedNanos: Long,
    val holdUntilNextResult: Boolean = false,
) {
    /**
     * Returns true while [nowElapsedNanos] is within `[1, expiresAtElapsedNanos]`, or for any
     * positive [nowElapsedNanos] when [holdUntilNextResult] is true.
     */
    fun isFreshAt(nowElapsedNanos: Long): Boolean =
        if (holdUntilNextResult) {
            nowElapsedNanos > 0L
        } else {
            nowElapsedNanos > 0L && expiresAtElapsedNanos > 0L && nowElapsedNanos <= expiresAtElapsedNanos
        }
}
