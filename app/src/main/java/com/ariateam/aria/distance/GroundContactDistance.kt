package com.ariateam.aria.distance

/**
 * Estimated ground-contact distance snapshot produced by monocular camera-ray and ground-plane intersection.
 *
 * @property distanceMeters Forward horizontal ground distance in meters along the horizontal walking ground plane,
 *                         or null when distance is unknown, unconfigured, or degraded.
 * @property message Informational reason or guidance in Korean (e.g., "키와 착용 위치를 설정하세요", "추정 거리 2.3m").
 * @property timestampNanos Monotonic camera frame capture timestamp in nanoseconds (elapsedRealtime domain when verified).
 *                         Used by the UI freshness gate (TTL <= 1.5s).
 * @property contactXNormalized Normalized upright horizontal coordinate in [0.0, 1.0] of the estimated ground contact point.
 * @property contactYNormalized Normalized upright vertical coordinate in [0.0, 1.0] of the estimated ground contact point.
 */
data class GroundContactDistance(
    val distanceMeters: Float? = null,
    val message: String = "키와 착용 위치를 설정하세요",
    val timestampNanos: Long = 0L,
    val contactXNormalized: Float? = null,
    val contactYNormalized: Float? = null,
    val rawGyroDegPerSec: Float = 0f,
    val smoothedGyroDegPerSec: Float = 0f,
    val restrictionReason: String? = null,
)
