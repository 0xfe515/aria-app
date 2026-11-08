package com.ariateam.aria.hazard.traffic

/**
 * Configuration thresholds for crosswalk traffic light presence policy.
 *
 * All time durations are expressed in nanoseconds for direct compatibility with capture timestamps
 * and monotonic elapsed realtime clocks.
 *
 * @property presenceConfirmationFramesRequired Consecutive fresh frames of generic light presence required before emitting candidate.
 * @property staleObservationExpiryNanos Maximum allowable delay between observation capture timestamp and evaluation time.
 * @property maxFutureToleranceNanos Maximum allowed future drift for capture timestamps relative to evaluation clock.
 * @property maxConsecutiveCaptureGapNanos Maximum allowed gap between consecutive observations before reset.
 * @property minPresenceConfidence Minimum detection confidence for generic traffic light presence.
 * @property minCrosswalkScore Minimum crosswalk relevance/coverage score in [0.0, 1.0].
 * @property presenceFallbackMessage Factual neutral warning text for generic traffic light presence.
 */
data class TrafficSignalPolicyConfig(
    val presenceConfirmationFramesRequired: Int = 2,
    val staleObservationExpiryNanos: Long = 1_500_000_000L,
    val maxFutureToleranceNanos: Long = 500_000_000L,
    val maxConsecutiveCaptureGapNanos: Long = 750_000_000L,
    val minPresenceConfidence: Float = 0.50f,
    val minCrosswalkScore: Float = 0.20f,
    val presenceFallbackMessage: String = "횡단보도 앞 신호등을 확인하세요",
)
