package com.ariateam.aria.vision.od

import com.ariateam.aria.hazard.AlertSeverity
import com.ariateam.aria.hazard.HazardAssessment
import com.ariateam.aria.hazard.HazardCause
import com.ariateam.aria.hazard.HazardLevel
import com.ariateam.aria.hazard.MotionState
import com.ariateam.aria.hazard.ObjectKind
import com.ariateam.aria.vision.VisionContract
import kotlin.math.max
import kotlin.math.min

/**
 * Conservative thresholds for advisory hazard fusion.
 *
 * Values are heuristics on normalized crop-display boxes; they never imply metric distance,
 * full-path blockage, or safe-to-cross conclusions.
 */
data class OdFusionConfig(
    val minScore: Float = 0.65f,
    /** Ground-contact proximity heuristic: box bottom must sit in the lower frame band. */
    val minBoxBottomNorm: Float = 0.80f,
    /** Fraction of the box width that must overlap the active corridor. */
    val minCorridorOverlapRatio: Float = 0.60f,
    /** IoU needed to treat two observations as the same object (class continuity also required). */
    val minIouForContinuity: Float = 0.30f,
    /** Maximum time between the two distinct confirming observations. */
    val maxConfirmationGapNanos: Long = 700_000_000L,
    /** Sidecar result age bound measured from the accepted frame arrival. */
    val maxEvidenceAgeNanos: Long = 250_000_000L,
    /** Maximum time between the base camera frame and the OD snapshot. */
    val maxEvidenceLagNanos: Long = 250_000_000L,
    /** Small tolerance for camera-timestamp skew before an OD snapshot is treated as future. */
    val futureToleranceNanos: Long = 50_000_000L,
    val corridorWidth: Int = VisionContract.MODEL_WIDTH,
    val allowedClassIds: Set<Int> = ALLOWED_CLASS_IDS,
) {
    init {
        require(corridorWidth > 0) { "Corridor width must be positive" }
        require(minBoxBottomNorm in 0f..1f) { "Bottom threshold must be normalized" }
        require(minCorridorOverlapRatio in 0f..1f) { "Overlap ratio must be normalized" }
        require(minIouForContinuity in 0f..1f) { "IoU threshold must be normalized" }
        require(maxEvidenceAgeNanos > 0L && maxEvidenceLagNanos > 0L) { "Age bounds must be positive" }
    }

    companion object {
        /**
         * MediaPipe COCO labelmap category IDs for person, bicycle, car, motorcycle, bus, truck.
         * The labelmap is zero-based with gaps, so these are explicit IDs, not a contiguous range.
         */
        val ALLOWED_CLASS_IDS: Set<Int> = setOf(0, 1, 2, 3, 5, 7)

        val KOREAN_LABELS: Map<Int, String> = mapOf(
            0 to "사람",
            1 to "자전거",
            2 to "자동차",
            3 to "오토바이",
            5 to "버스",
            7 to "트럭",
        )
    }
}

/** Why fusion did or did not add an advisory hazard. */
enum class OdFusionRejection {
    NONE,
    CLOCK_NOT_VERIFIED,
    NO_EVIDENCE,
    SESSION_MISMATCH,
    GEOMETRY_MISMATCH,
    MOTION_UNRELIABLE,
    BASE_UNKNOWN_DEGRADED,
    STALE_EVIDENCE,
    FUTURE_EVIDENCE,
    INVALID_CAPTURE,
    EMPTY_DETECTIONS,
    NO_ELIGIBLE_OBJECT,
    CONTINUITY_PENDING,
    BASE_ALREADY_WORSE,
    ADVISORY_APPLIED,
}

data class OdEvidenceGateResult(
    val accepted: OdEvidence?,
    val rejection: OdFusionRejection,
    val clearTemporal: Boolean,
)

class OdEvidenceGate(
    private val config: OdFusionConfig = OdFusionConfig(),
) {
    fun accept(
        evidence: OdEvidence?,
        frameGeometry: OdGeometryToken,
        frameCaptureTimestampNanos: Long,
        nowElapsedNanos: Long,
        sessionToken: Long,
        isClockRealtime: Boolean,
        motion: MotionState,
        isBaseDegraded: Boolean = false,
    ): OdEvidenceGateResult {
        if (!isClockRealtime) {
            return OdEvidenceGateResult(null, OdFusionRejection.CLOCK_NOT_VERIFIED, clearTemporal = true)
        }
        if (evidence == null) {
            return OdEvidenceGateResult(null, OdFusionRejection.NO_EVIDENCE, clearTemporal = true)
        }
        if (evidence.sessionToken != sessionToken) {
            return OdEvidenceGateResult(null, OdFusionRejection.SESSION_MISMATCH, clearTemporal = true)
        }
        if (evidence.geometry != frameGeometry) {
            return OdEvidenceGateResult(null, OdFusionRejection.GEOMETRY_MISMATCH, clearTemporal = true)
        }
        if (!motion.isReliable || motion.degradedReason != null) {
            return OdEvidenceGateResult(null, OdFusionRejection.MOTION_UNRELIABLE, clearTemporal = true)
        }
        if (isBaseDegraded) {
            return OdEvidenceGateResult(null, OdFusionRejection.BASE_UNKNOWN_DEGRADED, clearTemporal = true)
        }
        // Freshness uses the current monotonic clock, not the static completion age, so a snapshot
        // cannot stay fusable indefinitely when the camera keeps running.
        if (nowElapsedNanos <= 0L || evidence.arrivalElapsedNanos <= 0L ||
            evidence.captureTimestampNanos <= 0L || frameCaptureTimestampNanos <= 0L ||
            nowElapsedNanos < evidence.arrivalElapsedNanos
        ) {
            return OdEvidenceGateResult(null, OdFusionRejection.INVALID_CAPTURE, clearTemporal = true)
        }
        val currentAgeNanos = nowElapsedNanos - evidence.arrivalElapsedNanos
        if (evidence.resultAgeNanos < 0L ||
            evidence.resultAgeNanos > config.maxEvidenceAgeNanos ||
            currentAgeNanos > config.maxEvidenceAgeNanos
        ) {
            return OdEvidenceGateResult(null, OdFusionRejection.STALE_EVIDENCE, clearTemporal = true)
        }
        val lagNanos = frameCaptureTimestampNanos - evidence.captureTimestampNanos
        if (lagNanos < -config.futureToleranceNanos) {
            return OdEvidenceGateResult(null, OdFusionRejection.FUTURE_EVIDENCE, clearTemporal = true)
        }
        if (lagNanos > config.maxEvidenceLagNanos) {
            return OdEvidenceGateResult(null, OdFusionRejection.STALE_EVIDENCE, clearTemporal = true)
        }

        return OdEvidenceGateResult(
            accepted = evidence,
            rejection = OdFusionRejection.NONE,
            clearTemporal = false,
        )
    }
}

/**
 * Outcome of one fusion pass. [assessment] is the only value that may be routed to the alert policy;
 * the base assessment's mask-derived fields are preserved byte-for-byte unless [advisoryApplied].
 */
data class OdFusionOutcome(
    val assessment: HazardAssessment,
    val advisoryApplied: Boolean,
    val rejection: OdFusionRejection,
    val objectLabelKorean: String? = null,
)

/**
 * Conservative, advisory-only fusion of sidecar object detections into the segmentation hazard.
 *
 * It operates on a copy of [HazardAssessment] and never rewrites mask/summary/overlay/distance
 * semantics. An advisory requires two distinct, temporally ordered observations of the same class
 * with continuous boxes; it never downgrades a base hazard and never upgrades UNKNOWN_DEGRADED.
 */
class ObjectDetectionHazardFusion(
    private val config: OdFusionConfig = OdFusionConfig(),
) {
    val evidenceGate: OdEvidenceGate = OdEvidenceGate(config)

    private data class SeenObject(
        val classId: Int,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val captureTimestampNanos: Long,
    )

    private var lastSeen: SeenObject? = null
    private var confirmed: Boolean = false
    private var lastSessionToken: Long? = null

    fun reset() {
        lastSeen = null
        confirmed = false
        lastSessionToken = null
    }

    fun fuse(
        base: HazardAssessment,
        motion: MotionState,
        evidence: OdEvidence?,
        frameGeometry: OdGeometryToken,
        frameCaptureTimestampNanos: Long,
        nowElapsedNanos: Long,
        sessionToken: Long,
        isClockRealtime: Boolean,
    ): OdFusionOutcome {
        if (lastSessionToken != sessionToken) {
            reset()
            lastSessionToken = sessionToken
        }
        fun reject(reason: OdFusionRejection, clearTemporal: Boolean): OdFusionOutcome {
            if (clearTemporal) {
                lastSeen = null
                confirmed = false
            }
            return OdFusionOutcome(base, advisoryApplied = false, rejection = reason)
        }

        val gateResult = evidenceGate.accept(
            evidence = evidence,
            frameGeometry = frameGeometry,
            frameCaptureTimestampNanos = frameCaptureTimestampNanos,
            nowElapsedNanos = nowElapsedNanos,
            sessionToken = sessionToken,
            isClockRealtime = isClockRealtime,
            motion = motion,
            isBaseDegraded = (base.level == HazardLevel.UNKNOWN_DEGRADED),
        )
        if (gateResult.rejection != OdFusionRejection.NONE) {
            return reject(gateResult.rejection, clearTemporal = gateResult.clearTemporal)
        }
        val validEvidence = gateResult.accepted ?: return reject(OdFusionRejection.NO_EVIDENCE, clearTemporal = true)

        val candidate = selectEligibleObject(validEvidence.detections, motion)
            ?: return reject(
                if (validEvidence.detections.isEmpty()) {
                    OdFusionRejection.EMPTY_DETECTIONS
                } else {
                    OdFusionRejection.NO_ELIGIBLE_OBJECT
                },
                clearTemporal = true,
            )

        val observationCaptureNanos = validEvidence.captureTimestampNanos
        val seen = SeenObject(
            classId = candidate.classId,
            left = candidate.left,
            top = candidate.top,
            right = candidate.right,
            bottom = candidate.bottom,
            captureTimestampNanos = observationCaptureNanos,
        )
        val label = OdFusionConfig.KOREAN_LABELS[candidate.classId]
            ?: candidate.label.ifBlank { "장애물" }

        val previous = lastSeen
        if (previous == null) {
            lastSeen = seen
            confirmed = false
            return reject(OdFusionRejection.CONTINUITY_PENDING, clearTemporal = false)
        }
        if (observationCaptureNanos == previous.captureTimestampNanos) {
            // The same OD snapshot is reused by several 20 Hz camera frames. It can never be a second
            // observation on its own, but an already-confirmed snapshot may sustain the advisory so
            // the existing two-frame alert policy can announce it.
            return if (confirmed && previous.classId == candidate.classId) {
                advisoryOutcome(base, label, candidate.classId)
            } else {
                reject(OdFusionRejection.CONTINUITY_PENDING, clearTemporal = false)
            }
        }
        if (observationCaptureNanos < previous.captureTimestampNanos) {
            // Out-of-order/stale snapshot: ignore it without discarding a confirmed chain.
            return reject(OdFusionRejection.CONTINUITY_PENDING, clearTemporal = false)
        }

        val continues = previous.classId == candidate.classId &&
            (observationCaptureNanos - previous.captureTimestampNanos) <= config.maxConfirmationGapNanos &&
            iou(previous, candidate) >= config.minIouForContinuity
        lastSeen = seen
        confirmed = continues
        if (!continues) {
            return reject(OdFusionRejection.CONTINUITY_PENDING, clearTemporal = false)
        }
        return advisoryOutcome(base, label, candidate.classId)
    }

    private fun advisoryOutcome(base: HazardAssessment, label: String, classId: Int = -1): OdFusionOutcome {
        val isStructured = base.cause != HazardCause.DEGRADED ||
            base.severity != AlertSeverity.DEGRADED
        if (base.level.priority >= HazardLevel.PATH_BLOCKED.priority ||
            (isStructured && base.severity.rank >= AlertSeverity.WARNING.rank)
        ) {
            return OdFusionOutcome(
                assessment = base,
                advisoryApplied = false,
                rejection = OdFusionRejection.BASE_ALREADY_WORSE,
            )
        }
        val advisory = if (isStructured && base.severity.rank < AlertSeverity.CAUTION.rank) {
            val mappedKind = when (classId) {
                0 -> ObjectKind.PERSON
                1, 3 -> ObjectKind.TWO_WHEELER
                2, 5, 7 -> ObjectKind.VEHICLE
                else -> base.objectKind
            }
            base.copy(
                level = HazardLevel.NEAR_PATH_HAZARD,
                messageKorean = "[주의] 전방 장애물 감지 ($label 추정)",
                isAlertCandidate = true,
                severity = AlertSeverity.CAUTION,
                cause = HazardCause.C1_OBJECT_IN_PATH,
                objectKind = mappedKind,
            )
        } else {
            base.copy(
                level = HazardLevel.NEAR_PATH_HAZARD,
                messageKorean = "[주의] 전방 장애물 감지 ($label 추정)",
                isAlertCandidate = true,
            )
        }
        return OdFusionOutcome(
            assessment = advisory,
            advisoryApplied = true,
            rejection = OdFusionRejection.ADVISORY_APPLIED,
            objectLabelKorean = label,
        )
    }

    private fun selectEligibleObject(
        detections: List<DetectedObject>,
        motion: MotionState,
    ): DetectedObject? {
        var best: DetectedObject? = null
        for (detection in detections) {
            if (!isEligible(detection, motion)) continue
            if (best == null || detection.score > best.score) best = detection
        }
        return best
    }

    private fun isEligible(detection: DetectedObject, motion: MotionState): Boolean {
        if (detection.classId !in config.allowedClassIds) return false
        if (!detection.score.isFinite() || detection.score < config.minScore) return false
        val left = detection.left
        val top = detection.top
        val right = detection.right
        val bottom = detection.bottom
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return false
        if (left < 0f || top < 0f || right > 1f || bottom > 1f) return false
        if (right <= left || bottom <= top) return false
        if (bottom < config.minBoxBottomNorm) return false

        val corridorStartU = motion.activeCorridor.startCol.toFloat() / config.corridorWidth
        val corridorEndU = (motion.activeCorridor.endCol + 1).toFloat() / config.corridorWidth
        val centerU = (left + right) / 2f
        if (centerU < corridorStartU || centerU > corridorEndU) return false
        val overlap = (min(right, corridorEndU) - max(left, corridorStartU)).coerceAtLeast(0f)
        val overlapRatio = overlap / (right - left)
        return overlapRatio >= config.minCorridorOverlapRatio
    }

    private fun iou(previous: SeenObject, current: DetectedObject): Float {
        val interLeft = max(previous.left, current.left)
        val interTop = max(previous.top, current.top)
        val interRight = min(previous.right, current.right)
        val interBottom = min(previous.bottom, current.bottom)
        val interWidth = interRight - interLeft
        val interHeight = interBottom - interTop
        if (interWidth <= 0f || interHeight <= 0f) return 0f
        val intersection = interWidth * interHeight
        val previousArea = (previous.right - previous.left) * (previous.bottom - previous.top)
        val currentArea = (current.right - current.left) * (current.bottom - current.top)
        val union = previousArea + currentArea - intersection
        return if (union > 0f) intersection / union else 0f
    }
}
