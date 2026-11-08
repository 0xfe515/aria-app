package com.ariateam.aria.hazard.traffic

import com.ariateam.aria.hazard.AlertSeverity
import com.ariateam.aria.hazard.CorridorDirection
import com.ariateam.aria.hazard.HazardAssessment
import com.ariateam.aria.hazard.HazardCause

/**
 * Observed operational classification of a detected traffic light fixture.
 * Current production focuses on generic traffic light position evidence (COCO class 9)
 * paired with crosswalk relevance context.
 */
enum class TrafficSignalType {
    GENERIC_LIGHT,      // Generic traffic light (e.g. COCO class 9 box)
}

/**
 * Single traffic signal observation from detection pipeline.
 *
 * Coordinates are normalized [0.0, 1.0] in display/model space.
 * Default box (all zeros) is strictly invalid geometry as right must exceed left and bottom must exceed top.
 */
data class TrafficSignalObservation(
    val signalId: Int,
    val type: TrafficSignalType = TrafficSignalType.GENERIC_LIGHT,
    val confidence: Float,
    val boxLeft: Float = 0f,
    val boxTop: Float = 0f,
    val boxRight: Float = 0f,
    val boxBottom: Float = 0f,
    val distanceMeters: Float? = null,
) {
    val isValidGeometry: Boolean
        get() = !boxLeft.isNaN() && !boxTop.isNaN() && !boxRight.isNaN() && !boxBottom.isNaN() &&
                boxLeft.isFinite() && boxTop.isFinite() && boxRight.isFinite() && boxBottom.isFinite() &&
                boxLeft in 0f..1f && boxTop in 0f..1f && boxRight in 0f..1f && boxBottom in 0f..1f &&
                boxRight > boxLeft && boxBottom > boxTop

    val isValidConfidence: Boolean
        get() = !confidence.isNaN() && confidence.isFinite() && confidence in 0f..1f

    val isValid: Boolean
        get() = isValidConfidence && isValidGeometry
}

/**
 * Context regarding crosswalk presence and user approach relative to the walking path.
 *
 * @property hasCrosswalk Whether a crosswalk is currently present/relevant in the path.
 * @property crosswalkScore Relevance or coverage score of the crosswalk in [0.0, 1.0].
 * @property isApproaching Whether the user is approaching or standing at the crosswalk boundary.
 * @property approachId Monotonic ticket or identity for the crosswalk approach instance,
 *                     used to enforce once-per-approach caution emission.
 */
data class CrosswalkRelevanceContext(
    val hasCrosswalk: Boolean,
    val crosswalkScore: Float = 0f,
    val isApproaching: Boolean = true,
    val approachId: Long = 0L,
) {
    val isValid: Boolean
        get() = !crosswalkScore.isNaN() && crosswalkScore.isFinite() && crosswalkScore in 0f..1f
}

/**
 * Input bundle for a single frame evaluation in [CrosswalkSignalPresencePolicy].
 *
 * Uses two distinct clocks/timestamps to handle differing camera (~8-15 Hz) vs OD sidecar (~5 Hz) rates:
 * @property frameCaptureTimestampNanos Primary camera frame timestamp.
 * @property signalObservationCaptureTimestampNanos Sidecar OD inference snapshot timestamp.
 */
data class TrafficPolicyInput(
    val sessionToken: Long,
    val geometryToken: Long,
    val frameCaptureTimestampNanos: Long,
    val signalObservationCaptureTimestampNanos: Long,
    val signals: List<TrafficSignalObservation>,
    val crosswalk: CrosswalkRelevanceContext,
    val isMotionReliable: Boolean = true,
    val strongerHazardPresent: Boolean = false,
    val strongerHazardSeverityRank: Int = 0,
)

/**
 * Semantic cause for crosswalk traffic signal alerts.
 */
enum class TrafficSignalCause {
    NONE,
    CROSSWALK_SIGNAL_PRESENCE,
}

/**
 * Specific reason why a traffic signal alert was suppressed or skipped.
 */
enum class TrafficSuppressionReason {
    INVALID_CAPTURE_TIME,
    DUPLICATE_OR_OUT_OF_ORDER,
    FUTURE_CAPTURE,
    STALE_OBSERVATION,
    SESSION_MISMATCH,
    GEOMETRY_MISMATCH,
    UNRELIABLE_MOTION,
    STRONGER_HAZARD_PRIORITY,
    NO_RELEVANT_CROSSWALK,
    NO_SIGNAL_CANDIDATE,
    CONFIRMATION_PENDING,
    APPROACH_ALREADY_WARNED,
}

/**
 * Alert candidate emitted when policy confirms a caution condition.
 * Can be mapped or applied to a base [HazardAssessment] without external TTS paths.
 */
data class TrafficSignalAlertCandidate(
    val severity: AlertSeverity,
    val cause: TrafficSignalCause,
    val messageKorean: String,
    val signalId: Int?,
    val direction: CorridorDirection?,
    val distanceMeters: Float?,
    val confidence: Float,
    val riskScore: Float,
    val approachId: Long,
) {
    /**
     * Applies this candidate to an existing base [HazardAssessment] for single-assessment pipeline fusion.
     *
     * Invariants:
     * 1. Preserves existing stronger [AlertSeverity.WARNING] or [AlertSeverity.DEGRADED] assessments without weakening them.
     * 2. Requires an explicit integration-owned [HazardCause] to avoid misleading cause attribution.
     */
    fun applyTo(base: HazardAssessment, hazardCause: HazardCause): HazardAssessment {
        if (base.severity == AlertSeverity.WARNING ||
            base.severity == AlertSeverity.DEGRADED ||
            base.level == com.ariateam.aria.hazard.HazardLevel.UNKNOWN_DEGRADED
        ) {
            return base
        }
        return base.copy(
            isAlertCandidate = true,
            severity = severity,
            level = com.ariateam.aria.hazard.HazardLevel.PATH_CAUTION,
            cause = hazardCause,
            messageKorean = messageKorean,
            trackId = signalId,
            distanceMeters = distanceMeters,
            direction = direction ?: base.direction,
            riskScore = riskScore,
        )
    }
}

/**
 * Result of [CrosswalkSignalPresencePolicy.evaluate].
 */
sealed interface TrafficSignalDecision {
    val isAlertCandidate: Boolean
    val candidate: TrafficSignalAlertCandidate?
    val suppressionReason: TrafficSuppressionReason?

    /**
     * Factual generic presence caution fallback when traffic light is detected near relevant crosswalk.
     * Emitted to sustain downstream confirmation until delivered once per crosswalk approach.
     */
    data class CautionPresence(
        val signalId: Int?,
        val confidence: Float,
        val messageKorean: String,
        val approachId: Long,
        val direction: CorridorDirection? = null,
        val distanceMeters: Float? = null,
        val reason: String = "CROSSWALK_SIGNAL_PRESENCE_FACTUAL",
    ) : TrafficSignalDecision {
        override val isAlertCandidate: Boolean get() = true
        override val suppressionReason: TrafficSuppressionReason? get() = null
        override val candidate: TrafficSignalAlertCandidate get() = TrafficSignalAlertCandidate(
            severity = AlertSeverity.CAUTION,
            cause = TrafficSignalCause.CROSSWALK_SIGNAL_PRESENCE,
            messageKorean = messageKorean,
            signalId = signalId,
            direction = direction,
            distanceMeters = distanceMeters,
            confidence = confidence,
            riskScore = 0.50f,
            approachId = approachId,
        )
    }

    /**
     * Decision suppressed due to safety gates, hysteresis, cooldown, or lack of evidence.
     */
    data class Suppressed(
        override val suppressionReason: TrafficSuppressionReason,
    ) : TrafficSignalDecision {
        override val isAlertCandidate: Boolean get() = false
        override val candidate: TrafficSignalAlertCandidate? get() = null
    }
}
