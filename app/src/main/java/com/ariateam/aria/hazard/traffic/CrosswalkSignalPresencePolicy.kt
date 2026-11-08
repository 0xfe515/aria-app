package com.ariateam.aria.hazard.traffic

import com.ariateam.aria.hazard.AlertSeverity
import com.ariateam.aria.hazard.CorridorDirection

/**
 * Pure Kotlin, deterministic temporal decision policy for crosswalk traffic signal presence.
 *
 * Implements strict safety invariants:
 * 1. Current production scope is frozen to generic traffic light (COCO category 9) presence near a relevant
 *    crosswalk, emitting a factual reminder ("횡단보도 앞 신호등을 확인하세요").
 * 2. Does NOT claim or fabricate RED or GREEN state without a validated typed pedestrian signal model.
 * 3. Handles differing rates between primary camera frames (~8-15 Hz) and sidecar OD inference (~5 Hz)
 *    via two-clock inputs and stable eligible candidate replay until downstream delivery acknowledgement.
 * 4. Stale, future, duplicate, or out-of-order frames, session/geometry mismatches, and sensor degradations fail-closed.
 * 5. Stronger walking hazards (e.g. vehicles, path blocked) unconditionally suppress traffic caution.
 * 6. Enforces once-per-approach delivery acknowledgement via [acknowledgeDelivered].
 */
class CrosswalkSignalPresencePolicy(
    val config: TrafficSignalPolicyConfig = TrafficSignalPolicyConfig(),
    private val clockNanos: () -> Long = { System.nanoTime() },
) {
    private var lastSessionToken: Long? = null
    private var lastGeometryToken: Long? = null
    private var lastFrameCaptureNanos: Long = 0L
    private var lastSignalObservationNanos: Long = 0L

    private var candidateSignalId: Int? = null
    private var consecutivePresenceCount: Int = 0
    private var isEligible: Boolean = false

    private var lastWarnedApproachId: Long? = null
    private var currentApproachId: Long = 0L

    companion object {
        private const val LEFT_CORRIDOR_RATIO = 171f / 512f
        private const val RIGHT_CORRIDOR_RATIO = 341f / 512f
    }

    /**
     * Resets all internal state, history, candidate tracking, and approach tickets.
     */
    fun reset() {
        lastSessionToken = null
        lastGeometryToken = null
        lastFrameCaptureNanos = 0L
        lastSignalObservationNanos = 0L
        candidateSignalId = null
        consecutivePresenceCount = 0
        isEligible = false
        lastWarnedApproachId = null
        currentApproachId = 0L
    }

    /**
     * Resets state when camera streaming stops.
     */
    fun onCameraStop() {
        reset()
    }

    /**
     * Resets state when pipeline encounters an error.
     */
    fun onPipelineError(error: Throwable? = null) {
        reset()
    }

    /**
     * Resets state on camera session migration.
     */
    fun onSessionChange(newSessionToken: Long) {
        reset()
        lastSessionToken = newSessionToken
    }

    /**
     * Resets state on camera geometry/crop/rotation migration.
     */
    fun onGeometryChange(newGeometryToken: Long) {
        reset()
        lastGeometryToken = newGeometryToken
    }

    /**
     * Acknowledges that downstream alert handling (e.g. [com.ariateam.aria.hazard.HazardAlertPolicy])
     * has confirmed and fired an alert for this candidate. Consumes the once-per-approach ticket.
     */
    fun acknowledgeDelivered(
        cause: TrafficSignalCause,
        approachId: Long = currentApproachId,
    ) {
        if (cause == TrafficSignalCause.CROSSWALK_SIGNAL_PRESENCE) {
            lastWarnedApproachId = approachId
            isEligible = false
            resetCandidateTracking()
        }
    }

    /**
     * Evaluates a single frame of traffic observations and crosswalk context.
     *
     * @param input Frame data including camera capture time, OD observation capture time, signals, crosswalk context.
     * @param nowElapsedNanos Current monotonic clock timestamp in nanoseconds.
     * @return [TrafficSignalDecision] indicating caution alert or specific suppression reason.
     */
    fun evaluate(
        input: TrafficPolicyInput,
        nowElapsedNanos: Long = clockNanos(),
    ): TrafficSignalDecision {
        val frameCaptureNanos = input.frameCaptureTimestampNanos
        val obsCaptureNanos = input.signalObservationCaptureTimestampNanos

        // 1. Capture timestamp sanity checks
        if (frameCaptureNanos <= 0L || obsCaptureNanos <= 0L || nowElapsedNanos <= 0L) {
            return TrafficSignalDecision.Suppressed(TrafficSuppressionReason.INVALID_CAPTURE_TIME)
        }

        // 2. Session token gating: reset state if session changes
        if (lastSessionToken != null && input.sessionToken != lastSessionToken) {
            reset()
            lastSessionToken = input.sessionToken
            lastGeometryToken = input.geometryToken
            lastFrameCaptureNanos = frameCaptureNanos
            lastSignalObservationNanos = obsCaptureNanos
            return TrafficSignalDecision.Suppressed(TrafficSuppressionReason.SESSION_MISMATCH)
        }

        // 3. Geometry token gating: reset state if geometry changes
        if (lastGeometryToken != null && input.geometryToken != lastGeometryToken) {
            reset()
            lastSessionToken = input.sessionToken
            lastGeometryToken = input.geometryToken
            lastFrameCaptureNanos = frameCaptureNanos
            lastSignalObservationNanos = obsCaptureNanos
            return TrafficSignalDecision.Suppressed(TrafficSuppressionReason.GEOMETRY_MISMATCH)
        }

        // 4. Reject duplicate or out-of-order camera frame capture timestamps
        if (lastFrameCaptureNanos > 0L && frameCaptureNanos <= lastFrameCaptureNanos) {
            return TrafficSignalDecision.Suppressed(TrafficSuppressionReason.DUPLICATE_OR_OUT_OF_ORDER)
        }

        // 5. Reject future capture timestamps exceeding tolerance
        if (frameCaptureNanos > nowElapsedNanos + config.maxFutureToleranceNanos ||
            obsCaptureNanos > nowElapsedNanos + config.maxFutureToleranceNanos ||
            obsCaptureNanos > frameCaptureNanos + config.maxFutureToleranceNanos
        ) {
            return TrafficSignalDecision.Suppressed(TrafficSuppressionReason.FUTURE_CAPTURE)
        }

        // 6. Reject stale observation exceeding expiry threshold
        if ((nowElapsedNanos - obsCaptureNanos) > config.staleObservationExpiryNanos) {
            resetCandidateTracking()
            lastFrameCaptureNanos = frameCaptureNanos
            return TrafficSignalDecision.Suppressed(TrafficSuppressionReason.STALE_OBSERVATION)
        }

        // 7. Reject backwards observation timestamps
        if (lastSignalObservationNanos > 0L && obsCaptureNanos < lastSignalObservationNanos) {
            return TrafficSignalDecision.Suppressed(TrafficSuppressionReason.DUPLICATE_OR_OUT_OF_ORDER)
        }

        val isFreshObservation = (obsCaptureNanos > lastSignalObservationNanos)

        // 8. Large observation gap resets consecutive evidence counts
        if (lastSignalObservationNanos > 0L && isFreshObservation &&
            (obsCaptureNanos - lastSignalObservationNanos) > config.maxConsecutiveCaptureGapNanos
        ) {
            resetCandidateTracking()
        }

        lastFrameCaptureNanos = frameCaptureNanos
        lastSessionToken = input.sessionToken
        lastGeometryToken = input.geometryToken

        // 9. Motion reliability gating
        if (!input.isMotionReliable) {
            resetCandidateTracking()
            return TrafficSignalDecision.Suppressed(TrafficSuppressionReason.UNRELIABLE_MOTION)
        }

        // 10. Stronger hazard priority (vehicle approach, imminent obstacles, warning severity)
        if (input.strongerHazardPresent || input.strongerHazardSeverityRank >= AlertSeverity.WARNING.rank) {
            resetCandidateTracking()
            return TrafficSignalDecision.Suppressed(TrafficSuppressionReason.STRONGER_HAZARD_PRIORITY)
        }

        // 11. Crosswalk presence/relevance gate
        val crosswalk = input.crosswalk
        if (!crosswalk.isValid || !crosswalk.hasCrosswalk || crosswalk.crosswalkScore < config.minCrosswalkScore) {
            resetCandidateTracking()
            return TrafficSignalDecision.Suppressed(TrafficSuppressionReason.NO_RELEVANT_CROSSWALK)
        }

        currentApproachId = crosswalk.approachId

        // 12. Check once-per-approach suppression
        if (lastWarnedApproachId != null && lastWarnedApproachId == crosswalk.approachId) {
            return TrafficSignalDecision.Suppressed(TrafficSuppressionReason.APPROACH_ALREADY_WARNED)
        }

        // 13. Filter valid traffic light observations
        val validSignals = input.signals.filter { it.isValid && it.confidence >= config.minPresenceConfidence }
        if (validSignals.isEmpty()) {
            resetCandidateTracking()
            return TrafficSignalDecision.Suppressed(TrafficSuppressionReason.NO_SIGNAL_CANDIDATE)
        }

        val primary = validSignals.maxByOrNull { it.confidence }!!

        // 14. Target association: must match same signal target
        if (candidateSignalId != primary.signalId) {
            candidateSignalId = primary.signalId
            consecutivePresenceCount = 0
            isEligible = false
        }

        if (isFreshObservation) {
            consecutivePresenceCount++
            lastSignalObservationNanos = obsCaptureNanos
        }

        if (consecutivePresenceCount >= config.presenceConfirmationFramesRequired) {
            isEligible = true
        }

        if (!isEligible) {
            return TrafficSignalDecision.Suppressed(TrafficSuppressionReason.CONFIRMATION_PENDING)
        }

        // 15. Candidate is eligible: sustained across camera frames until delivered
        val direction = determineDirection(primary)
        return TrafficSignalDecision.CautionPresence(
            signalId = primary.signalId,
            confidence = primary.confidence,
            messageKorean = config.presenceFallbackMessage,
            approachId = currentApproachId,
            direction = direction,
            distanceMeters = primary.distanceMeters,
        )
    }

    fun clearCandidate() {
        resetCandidateTracking()
    }

    private fun resetCandidateTracking() {
        candidateSignalId = null
        consecutivePresenceCount = 0
        isEligible = false
    }

    private fun determineDirection(signal: TrafficSignalObservation): CorridorDirection {
        val centerX = (signal.boxLeft + signal.boxRight) / 2f
        return when {
            centerX < LEFT_CORRIDOR_RATIO -> CorridorDirection.LEFT
            centerX > RIGHT_CORRIDOR_RATIO -> CorridorDirection.RIGHT
            else -> CorridorDirection.CENTER
        }
    }
}

/** Backward compatibility alias for coordinator references. */
typealias PedestrianCrosswalkSignalPolicy = CrosswalkSignalPresencePolicy
