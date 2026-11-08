package com.ariateam.aria.vision

import com.ariateam.aria.hazard.AlertSeverity
import com.ariateam.aria.hazard.CorridorDirection
import com.ariateam.aria.hazard.HazardAssessment
import com.ariateam.aria.hazard.HazardCause
import com.ariateam.aria.hazard.HazardLevel
import com.ariateam.aria.hazard.MotionState
import com.ariateam.aria.hazard.traffic.CrosswalkRelevanceContext
import com.ariateam.aria.hazard.traffic.CrosswalkSignalPresencePolicy
import com.ariateam.aria.hazard.traffic.TrafficPolicyInput
import com.ariateam.aria.hazard.traffic.TrafficSignalAlertCandidate
import com.ariateam.aria.hazard.traffic.TrafficSignalCause
import com.ariateam.aria.hazard.traffic.TrafficSignalDecision
import com.ariateam.aria.hazard.traffic.TrafficSignalObservation
import com.ariateam.aria.hazard.traffic.TrafficSignalPolicyConfig
import com.ariateam.aria.hazard.traffic.TrafficSignalType
import com.ariateam.aria.hazard.traffic.TrafficSuppressionReason
import com.ariateam.aria.vision.od.DetectedObject
import com.ariateam.aria.vision.od.OdEvidence
import com.ariateam.aria.vision.od.OdGeometryToken
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Result bundle produced by [CrosswalkSignalAdvisory.evaluateAndFuse] for a single frame.
 *
 * @property assessment Final [HazardAssessment] incorporating traffic signal presence if confirmed,
 *                      otherwise the untouched base assessment.
 * @property decision Underlying [TrafficSignalDecision] from the temporal policy.
 */
data class CrosswalkSignalAdvisoryResult(
    val assessment: HazardAssessment,
    val decision: TrafficSignalDecision,
)

/**
 * Vision pipeline adapter integrating raw crosswalk segmentation evidence and auxiliary object-detection
 * generic traffic light boxes (COCO class 9) into the existing hazard coordinator path.
 *
 * Safety Invariants:
 * 1. Scope is strictly presence-only: reminds the user to check signals ("횡단보도 앞 신호등을 확인하세요")
 *    without asserting red/green or pedestrian permission states.
 * 2. Emits caution at most once per actual crosswalk approach.
 * 3. Never weakens stronger base hazards (WARNING, DEGRADED, UNKNOWN_DEGRADED, or obstacle CAUTION C1/C2).
 * 4. Fails closed on stale observations, geometry/session mismatches, empty evidence, or disjoint competing signals.
 * 5. Handles two-rate camera (~8-15 Hz) vs OD sidecar (~5 Hz) sampling by sustaining eligible candidates
 *    across camera frames until downstream [acknowledgeDelivered].
 * 6. Single-track IoU and center continuity prevents ID jitter and ensures stable association independent of list order.
 */
class CrosswalkSignalAdvisory(
    val policy: CrosswalkSignalPresencePolicy = CrosswalkSignalPresencePolicy(),
    private val approachSettlementFrames: Int = 10,
    private val nearStartRow: Int = 230,
    private val modelWidth: Int = VisionContract.MODEL_WIDTH,
    private val modelHeight: Int = VisionContract.MODEL_HEIGHT,
) {
    companion object {
        const val TRAFFIC_LIGHT_CLASS_ID = 9
        private const val MIN_BOX_WIDTH = 0.015f
        private const val MAX_BOX_WIDTH = 0.85f
        private const val MIN_BOX_HEIGHT = 0.025f
        private const val MAX_BOX_HEIGHT = 0.85f
        private const val MAX_BOX_TOP = 0.70f
        private const val MIN_BOX_LEFT = 0.02f
        private const val MAX_BOX_RIGHT = 0.98f
        private const val COLLAPSE_IOU_THRESHOLD = 0.40f
        private const val TARGET_CONTINUITY_IOU = 0.30f
        private const val TARGET_CONTINUITY_MAX_CENTER_DIFF = 0.08f
    }

    private var currentApproachId: Long = 0L
    private var hasActiveApproach: Boolean = false
    private var consecutiveLossFrames: Int = 0
    private var lastValidFrameCaptureNanos: Long = 0L
    private var lastPendingCandidate: TrafficSignalAlertCandidate? = null

    // Geometry token tracking via object equality and monotonic generation
    private var lastObservedGeometry: OdGeometryToken? = null
    private var monotonicGeometryToken: Long = 1L

    // Single-track continuity
    private var activeTargetTrackId: Int = 1001
    private var activeTargetLeft: Float = 0f
    private var activeTargetTop: Float = 0f
    private var activeTargetRight: Float = 0f
    private var activeTargetBottom: Float = 0f
    private var hasActiveTarget: Boolean = false

    /**
     * Resets all internal temporal state, approach tickets, and candidate tracking.
     */
    fun reset() {
        policy.reset()
        hasActiveApproach = false
        consecutiveLossFrames = 0
        lastValidFrameCaptureNanos = 0L
        lastPendingCandidate = null
        hasActiveTarget = false
    }

    /**
     * Resets state when camera streaming stops.
     */
    fun onCameraStop() {
        policy.onCameraStop()
        hasActiveApproach = false
        consecutiveLossFrames = 0
        lastValidFrameCaptureNanos = 0L
        lastPendingCandidate = null
        hasActiveTarget = false
    }

    /**
     * Resets state when the vision pipeline encounters an error.
     */
    fun onPipelineError(error: Throwable? = null) {
        policy.onPipelineError(error)
        hasActiveApproach = false
        consecutiveLossFrames = 0
        lastValidFrameCaptureNanos = 0L
        lastPendingCandidate = null
        hasActiveTarget = false
    }

    /**
     * Resets state on camera session migration.
     */
    fun onSessionChange(newSessionToken: Long) {
        policy.onSessionChange(newSessionToken)
        hasActiveApproach = false
        consecutiveLossFrames = 0
        currentApproachId = 0L
        lastValidFrameCaptureNanos = 0L
        lastPendingCandidate = null
        hasActiveTarget = false
    }

    /**
     * Resets state on camera geometry/crop/rotation migration.
     */
    fun onGeometryChange(newGeometryToken: Long) {
        monotonicGeometryToken = newGeometryToken
        policy.onGeometryChange(newGeometryToken)
        hasActiveApproach = false
        consecutiveLossFrames = 0
        lastPendingCandidate = null
        hasActiveTarget = false
    }

    /**
     * Evaluates crosswalk and signal evidence for the current camera frame and applies confirmed caution
     * to the base assessment without mutating base WARNING, DEGRADED, or obstacle CAUTION alerts.
     */
    fun evaluateAndFuse(
        baseAssessment: HazardAssessment,
        odEvidence: OdEvidence?,
        classMask: ByteArray?,
        motion: MotionState,
        currentGeometry: OdGeometryToken,
        sessionToken: Long,
        frameCaptureTimestampNanos: Long,
        nowElapsedNanos: Long,
        isClockRealtime: Boolean,
    ): CrosswalkSignalAdvisoryResult {
        // Geometry token migration check via object equality
        if (lastObservedGeometry == null) {
            lastObservedGeometry = currentGeometry
        } else if (lastObservedGeometry != currentGeometry) {
            lastObservedGeometry = currentGeometry
            monotonicGeometryToken++
            policy.onGeometryChange(monotonicGeometryToken)
            hasActiveTarget = false
        }

        // Clock, mask, and capture time fail-closed checks
        if (!isClockRealtime || classMask == null || classMask.size < modelWidth * modelHeight ||
            frameCaptureTimestampNanos <= 0L || nowElapsedNanos <= 0L
        ) {
            policy.clearCandidate()
            hasActiveTarget = false
            lastPendingCandidate = null
            return CrosswalkSignalAdvisoryResult(
                assessment = baseAssessment,
                decision = TrafficSignalDecision.Suppressed(TrafficSuppressionReason.NO_RELEVANT_CROSSWALK),
            )
        }

        // 1. Raw crosswalk segmentation extraction from classMask (class 3 in active corridor near-ROI)
        val crosswalkScore = extractCrosswalkScore(classMask, motion.activeCorridor)
        val hasCrosswalk = crosswalkScore >= policy.config.minCrosswalkScore

        // Rearm approach tracking: count ONLY on distinct, fresh, non-stale, non-future camera frames
        val isDistinctValidCameraFrame = frameCaptureTimestampNanos > lastValidFrameCaptureNanos &&
            (nowElapsedNanos - frameCaptureTimestampNanos) <= policy.config.staleObservationExpiryNanos &&
            frameCaptureTimestampNanos <= nowElapsedNanos + policy.config.maxFutureToleranceNanos

        if (isDistinctValidCameraFrame) {
            lastValidFrameCaptureNanos = frameCaptureTimestampNanos
            if (hasCrosswalk) {
                if (!hasActiveApproach) {
                    hasActiveApproach = true
                    currentApproachId++
                }
                consecutiveLossFrames = 0
            } else {
                consecutiveLossFrames++
                if (consecutiveLossFrames >= approachSettlementFrames) {
                    hasActiveApproach = false
                }
            }
        }

        // 2. Strict metadata and freshness gating for auxiliary OD evidence
        val isEvidenceValid = odEvidence != null && isOdEvidenceValid(
            odEvidence = odEvidence,
            currentGeometry = currentGeometry,
            sessionToken = sessionToken,
            frameCaptureTimestampNanos = frameCaptureTimestampNanos,
            nowElapsedNanos = nowElapsedNanos,
        )

        // 3. Extract, filter, disambiguate, and track generic traffic lights
        val signals = if (isEvidenceValid) {
            extractAndTrackTrafficSignals(odEvidence.detections, motion.activeCorridor)
        } else {
            policy.clearCandidate()
            hasActiveTarget = false
            emptyList()
        }

        // 4. Priority gating: stronger hazards (WARNING, DEGRADED, UNKNOWN_DEGRADED, or meaningful path/obstacle
        // cautions C1, C2, C3, C4) must unconditionally win and clear any pending traffic light caution.
        // Presence reminder only supplements NONE or replaces same-crosswalk C5.
        val isStrongerHazard = baseAssessment.severity == AlertSeverity.WARNING ||
            baseAssessment.severity == AlertSeverity.DEGRADED ||
            baseAssessment.level == HazardLevel.UNKNOWN_DEGRADED ||
            baseAssessment.cause == HazardCause.C1_OBJECT_IN_PATH ||
            baseAssessment.cause == HazardCause.C2_VEHICLE_NEARBY ||
            baseAssessment.cause == HazardCause.C3_ROAD_EDGE ||
            baseAssessment.cause == HazardCause.C4_PATH_NARROW

        if (isStrongerHazard) {
            policy.clearCandidate()
            lastPendingCandidate = null
            return CrosswalkSignalAdvisoryResult(
                assessment = baseAssessment,
                decision = TrafficSignalDecision.Suppressed(TrafficSuppressionReason.STRONGER_HAZARD_PRIORITY),
            )
        }

        val policyInput = TrafficPolicyInput(
            sessionToken = sessionToken,
            geometryToken = monotonicGeometryToken,
            frameCaptureTimestampNanos = frameCaptureTimestampNanos,
            signalObservationCaptureTimestampNanos = odEvidence?.captureTimestampNanos ?: 0L,
            signals = signals,
            crosswalk = CrosswalkRelevanceContext(
                hasCrosswalk = hasCrosswalk,
                crosswalkScore = crosswalkScore,
                isApproaching = true,
                approachId = currentApproachId,
            ),
            isMotionReliable = motion.isReliable,
            strongerHazardPresent = false,
            strongerHazardSeverityRank = 0,
        )

        val decision = policy.evaluate(policyInput, nowElapsedNanos)

        val finalAssessment = when (decision) {
            is TrafficSignalDecision.CautionPresence -> {
                lastPendingCandidate = decision.candidate
                decision.candidate.applyTo(baseAssessment, HazardCause.C6_CROSSWALK_SIGNAL_PRESENCE)
            }
            is TrafficSignalDecision.Suppressed -> {
                lastPendingCandidate = null
                baseAssessment
            }
        }

        return CrosswalkSignalAdvisoryResult(
            assessment = finalAssessment,
            decision = decision,
        )
    }

    /**
     * Acknowledges that downstream alert handling (e.g. [com.ariateam.aria.hazard.HazardAlertCoordinator])
     * has confirmed and fired an alert for this candidate. Consumes the once-per-approach ticket.
     */
    fun acknowledgeDelivered(firedAssessment: HazardAssessment) {
        if (firedAssessment.cause == HazardCause.C6_CROSSWALK_SIGNAL_PRESENCE) {
            val pending = lastPendingCandidate
            if (pending != null && (firedAssessment.trackId == null || firedAssessment.trackId == pending.signalId)) {
                policy.acknowledgeDelivered(
                    cause = TrafficSignalCause.CROSSWALK_SIGNAL_PRESENCE,
                    approachId = pending.approachId,
                )
                lastPendingCandidate = null
            }
        }
    }

    /**
     * Extracts raw class 3 crosswalk pixel fraction from the near-ROI of the active corridor.
     */
    fun extractCrosswalkScore(classMask: ByteArray, corridor: CorridorDirection): Float {
        val startCol = corridor.startCol
        val endCol = corridor.endCol
        var totalPixels = 0
        var crosswalkPixels = 0

        for (y in nearStartRow until modelHeight) {
            val rowOffset = y * modelWidth
            for (x in startCol..endCol) {
                totalPixels++
                val classId = classMask[rowOffset + x].toInt() and 0xFF
                if (classId == 3) { // 3 = crosswalk in Aria segmentation contract
                    crosswalkPixels++
                }
            }
        }
        return if (totalPixels > 0) crosswalkPixels.toFloat() / totalPixels else 0f
    }

    /**
     * Strict metadata validation for auxiliary OD evidence.
     */
    private fun isOdEvidenceValid(
        odEvidence: OdEvidence,
        currentGeometry: OdGeometryToken,
        sessionToken: Long,
        frameCaptureTimestampNanos: Long,
        nowElapsedNanos: Long,
    ): Boolean {
        if (odEvidence.sessionToken != sessionToken) return false
        if (odEvidence.geometry != currentGeometry) return false
        if (odEvidence.captureTimestampNanos <= 0L || odEvidence.arrivalElapsedNanos <= 0L) return false
        if (odEvidence.completedElapsedNanos < odEvidence.arrivalElapsedNanos) return false
        if (odEvidence.resultAgeNanos != (odEvidence.completedElapsedNanos - odEvidence.arrivalElapsedNanos)) return false
        if (odEvidence.resultAgeNanos < 0L || odEvidence.resultAgeNanos > policy.config.staleObservationExpiryNanos) return false
        if (odEvidence.completedElapsedNanos > nowElapsedNanos + policy.config.maxFutureToleranceNanos) return false
        // OD capture must not be from the future relative to the camera frame or system reference clock
        if (odEvidence.captureTimestampNanos > frameCaptureTimestampNanos + policy.config.maxFutureToleranceNanos) return false
        val ageFromCapture = nowElapsedNanos - odEvidence.captureTimestampNanos
        if (ageFromCapture < -policy.config.maxFutureToleranceNanos || ageFromCapture > policy.config.staleObservationExpiryNanos) return false
        return true
    }

    /**
     * Extracts, validates, bounds, disambiguates, and tracks generic traffic lights from fresh OD detections.
     */
    private fun extractAndTrackTrafficSignals(
        detections: List<DetectedObject>,
        corridor: CorridorDirection,
    ): List<TrafficSignalObservation> {
        // Coarse alignment window bounds for active corridor
        val alignMin = when (corridor) {
            CorridorDirection.LEFT -> 0.02f
            CorridorDirection.CENTER -> 0.20f
            CorridorDirection.RIGHT -> 0.35f
        }
        val alignMax = when (corridor) {
            CorridorDirection.LEFT -> 0.65f
            CorridorDirection.CENTER -> 0.80f
            CorridorDirection.RIGHT -> 0.98f
        }

        // Filter valid category 9 detections
        val candidates = detections.filter { det ->
            val centerX = (det.left + det.right) / 2f
            det.classId == TRAFFIC_LIGHT_CLASS_ID &&
                det.hasValidGeometry &&
                det.score >= policy.config.minPresenceConfidence &&
                det.score <= 1.0f &&
                det.width in MIN_BOX_WIDTH..MAX_BOX_WIDTH &&
                det.height in MIN_BOX_HEIGHT..MAX_BOX_HEIGHT &&
                det.top <= MAX_BOX_TOP &&
                det.left >= MIN_BOX_LEFT &&
                det.right <= MAX_BOX_RIGHT &&
                centerX in alignMin..alignMax
        }

        if (candidates.isEmpty()) {
            hasActiveTarget = false
            return emptyList()
        }

        val primary: DetectedObject
        if (candidates.size == 1) {
            primary = candidates[0]
        } else {
            // Sort by score descending
            val sorted = candidates.sortedByDescending { it.score }
            primary = sorted[0]

            // Check competing boxes: must not have disjoint competing fixtures
            for (i in 1 until sorted.size) {
                val other = sorted[i]
                val iou = computeIoU(primary, other)
                if (iou < COLLAPSE_IOU_THRESHOLD) {
                    // Disjoint competing lights in scene -> fail closed!
                    hasActiveTarget = false
                    return emptyList()
                }
            }
        }

        // Single-track IoU and center continuity
        val targetId = updateTargetTrack(primary)

        return listOf(
            TrafficSignalObservation(
                signalId = targetId,
                type = TrafficSignalType.GENERIC_LIGHT,
                confidence = primary.score,
                boxLeft = primary.left,
                boxTop = primary.top,
                boxRight = primary.right,
                boxBottom = primary.bottom,
            )
        )
    }

    private fun updateTargetTrack(det: DetectedObject): Int {
        if (!hasActiveTarget) {
            hasActiveTarget = true
            activeTargetTrackId++
        } else {
            val iou = computeBoxIoU(
                l1 = activeTargetLeft, t1 = activeTargetTop, r1 = activeTargetRight, b1 = activeTargetBottom,
                l2 = det.left, t2 = det.top, r2 = det.right, b2 = det.bottom,
            )
            val centerTargetX = (activeTargetLeft + activeTargetRight) / 2f
            val centerTargetY = (activeTargetTop + activeTargetBottom) / 2f
            val centerDetX = (det.left + det.right) / 2f
            val centerDetY = (det.top + det.bottom) / 2f
            val diffX = abs(centerTargetX - centerDetX)
            val diffY = abs(centerTargetY - centerDetY)

            val isSame = iou >= TARGET_CONTINUITY_IOU || (diffX <= TARGET_CONTINUITY_MAX_CENTER_DIFF && diffY <= TARGET_CONTINUITY_MAX_CENTER_DIFF)
            if (!isSame) {
                activeTargetTrackId++
            }
        }

        activeTargetLeft = det.left
        activeTargetTop = det.top
        activeTargetRight = det.right
        activeTargetBottom = det.bottom

        return activeTargetTrackId
    }

    private fun computeIoU(a: DetectedObject, b: DetectedObject): Float =
        computeBoxIoU(a.left, a.top, a.right, a.bottom, b.left, b.top, b.right, b.bottom)

    private fun computeBoxIoU(
        l1: Float, t1: Float, r1: Float, b1: Float,
        l2: Float, t2: Float, r2: Float, b2: Float,
    ): Float {
        val interL = max(l1, l2)
        val interT = max(t1, t2)
        val interR = min(r1, r2)
        val interB = min(b1, b2)

        val interW = max(0f, interR - interL)
        val interH = max(0f, interB - interT)
        val interArea = interW * interH

        val area1 = max(0f, r1 - l1) * max(0f, b1 - t1)
        val area2 = max(0f, r2 - l2) * max(0f, b2 - t2)
        val unionArea = area1 + area2 - interArea

        return if (unionArea > 0f) interArea / unionArea else 0f
    }
}
