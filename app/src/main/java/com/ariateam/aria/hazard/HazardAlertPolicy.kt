package com.ariateam.aria.hazard

sealed interface AlertDecision {
    data class Fire(val assessment: HazardAssessment, val reason: String) : AlertDecision
    data class Suppressed(val reason: AlertSuppressionReason = AlertSuppressionReason.CONFIRMATION_PENDING) : AlertDecision
}

enum class AlertSuppressionReason {
    SESSION_INACTIVE,
    INVALID_CAPTURE,
    DUPLICATE_OR_OUT_OF_ORDER_CAPTURE,
    FUTURE_CAPTURE,
    STALE_RESULT,
    NOT_A_HAZARD_CANDIDATE,
    CONFIRMATION_PENDING,
    COOLDOWN,
    CAUTION_DISABLED,
}

data class CandidateKey(val severity: AlertSeverity, val cause: HazardCause, val targetKey: Int)

/**
 * Pure, deterministic alert policy implementing temporal hysteresis, cooldown, and staleness gating.
 * Decoupled from Android Context/Vibrator/Toast for 100% JVM unit testability.
 */
class HazardAlertPolicy(
    val confirmationFramesRequired: Int = 2,
    alertCooldownMs: Long = 2_500L,
    val staleResultExpiryMs: Long = 1_500L,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    val cautionConfirmationFramesRequired: Int = 3,
    cautionCooldownMs: Long = 6_000L,
    val warningReleaseFrames: Int = 4,
    val cautionReleaseFrames: Int = 6,
) {
    private val normalAlertCooldownMs = alertCooldownMs
    private val normalCautionCooldownMs = cautionCooldownMs
    var alertCooldownMs: Long = alertCooldownMs
        private set
    var cautionCooldownMs: Long = cautionCooldownMs
        private set
    var cautionEnabled: Boolean = true
        private set

    fun effectiveSeverity(assessment: HazardAssessment): AlertSeverity = when {
        assessment.severity == AlertSeverity.WARNING || assessment.severity == AlertSeverity.CAUTION -> assessment.severity
        assessment.level == HazardLevel.PATH_CAUTION -> AlertSeverity.CAUTION
        assessment.level == HazardLevel.PATH_BLOCKED || assessment.level == HazardLevel.NEAR_PATH_HAZARD -> AlertSeverity.WARNING
        else -> assessment.severity
    }

    fun configure(frequency: com.ariateam.aria.settings.AlertFrequency, cautionEnabled: Boolean) {
        val multiplier = when (frequency) {
            com.ariateam.aria.settings.AlertFrequency.FREQUENT -> 0.5
            com.ariateam.aria.settings.AlertFrequency.NORMAL -> 1.0
            com.ariateam.aria.settings.AlertFrequency.QUIET -> 2.0
        }
        alertCooldownMs = (normalAlertCooldownMs * multiplier).toLong().coerceAtLeast(500L)
        cautionCooldownMs = (normalCautionCooldownMs * multiplier).toLong().coerceAtLeast(1_000L)
        this.cautionEnabled = cautionEnabled
    }

    private var lastAssessmentTimestampNanos: Long = 0L
    private var consecutiveRiskCount: Int = 0
    private var lastCandidateLevel: HazardLevel? = null
    private var lastCandidateCorridor: CorridorDirection? = null

    private var lastAlertRealtimeMs: Long = 0L
    private var lastAlertLevel: HazardLevel = HazardLevel.CLEAR_UNWARNED
    private var lastAlertCorridor: CorridorDirection? = null

    private var lastCandidateKey: CandidateKey? = null
    private var lastAlertSeverity: AlertSeverity = AlertSeverity.NONE
    private var lastAlertKey: CandidateKey? = null
    private var inactiveFrameCount: Int = 0

    private companion object {
        const val MAX_CONSECUTIVE_CAPTURE_GAP_NANOS = 500_000_000L
    }

    fun reset() {
        lastAssessmentTimestampNanos = 0L
        consecutiveRiskCount = 0
        lastCandidateLevel = null
        lastCandidateCorridor = null
        lastAlertRealtimeMs = 0L
        lastAlertLevel = HazardLevel.CLEAR_UNWARNED
        lastAlertCorridor = null

        lastCandidateKey = null
        lastAlertSeverity = AlertSeverity.NONE
        lastAlertKey = null
        inactiveFrameCount = 0
    }

    fun evaluate(
        assessment: HazardAssessment,
        nowRealtimeMs: Long = clockMs(),
        nowElapsedNanos: Long = nowRealtimeMs * 1_000_000L,
    ): AlertDecision {
        val captureNanos = assessment.timestampNanos
        if (effectiveSeverity(assessment) == AlertSeverity.CAUTION && !cautionEnabled) return AlertDecision.Suppressed(AlertSuppressionReason.CAUTION_DISABLED)
        if (captureNanos <= 0L) return AlertDecision.Suppressed(AlertSuppressionReason.INVALID_CAPTURE)

        // Reject duplicate or out-of-order capture timestamps
        if (lastAssessmentTimestampNanos > 0L && captureNanos <= lastAssessmentTimestampNanos) {
            return AlertDecision.Suppressed(AlertSuppressionReason.DUPLICATE_OR_OUT_OF_ORDER_CAPTURE)
        }

        // Reject future capture timestamps (> 500ms ahead of reference clock)
        if (nowElapsedNanos > 0L && captureNanos > nowElapsedNanos + 500_000_000L) {
            return AlertDecision.Suppressed(AlertSuppressionReason.FUTURE_CAPTURE)
        }

        // Reject stale inference results (> 1,500ms since camera capture)
        val ageMs = (nowElapsedNanos - captureNanos) / 1_000_000.0f
        if (ageMs > staleResultExpiryMs) {
            consecutiveRiskCount = 0
            lastCandidateLevel = null
            lastCandidateCorridor = null
            lastCandidateKey = null
            lastAssessmentTimestampNanos = captureNanos
            return AlertDecision.Suppressed(AlertSuppressionReason.STALE_RESULT)
        }
        val captureGapNanos = if (lastAssessmentTimestampNanos > 0L) {
            captureNanos - lastAssessmentTimestampNanos
        } else {
            0L
        }
        if (captureGapNanos > MAX_CONSECUTIVE_CAPTURE_GAP_NANOS) {
            consecutiveRiskCount = 0
            lastCandidateLevel = null
            lastCandidateCorridor = null
            lastCandidateKey = null
        }
        lastAssessmentTimestampNanos = captureNanos

        val isStructuredMode = assessment.cause != HazardCause.DEGRADED ||
            assessment.severity == AlertSeverity.WARNING ||
            assessment.severity == AlertSeverity.CAUTION ||
            assessment.severity == AlertSeverity.NONE

        if (!isStructuredMode) {
            val level = assessment.level
            if (!assessment.isAlertCandidate || level.priority < HazardLevel.PATH_BLOCKED.priority) {
                consecutiveRiskCount = 0
                lastCandidateLevel = null
                lastCandidateCorridor = null
                return AlertDecision.Suppressed(AlertSuppressionReason.NOT_A_HAZARD_CANDIDATE)
            }

            // Confirmation hysteresis: require same level and corridor across consecutive frames
            if (level == lastCandidateLevel && assessment.activeCorridor == lastCandidateCorridor) {
                consecutiveRiskCount++
            } else {
                consecutiveRiskCount = 1
                lastCandidateLevel = level
                lastCandidateCorridor = assessment.activeCorridor
            }

            if (consecutiveRiskCount >= confirmationFramesRequired) {
                val isEscalation = level.priority > lastAlertLevel.priority
                val isCooldownElapsed = (nowRealtimeMs - lastAlertRealtimeMs) >= alertCooldownMs
                val isCorridorChanged = assessment.activeCorridor != lastAlertCorridor

                if (isEscalation || isCooldownElapsed || isCorridorChanged) {
                    val reason = when {
                        isEscalation -> "ESCALATION"
                        isCorridorChanged -> "CORRIDOR_CHANGED"
                        else -> "COOLDOWN_ELAPSED"
                    }
                    lastAlertRealtimeMs = nowRealtimeMs
                    lastAlertLevel = level
                    lastAlertCorridor = assessment.activeCorridor
                    return AlertDecision.Fire(assessment, reason)
                }
            }

            return AlertDecision.Suppressed(
                if (consecutiveRiskCount >= confirmationFramesRequired) AlertSuppressionReason.COOLDOWN
                else AlertSuppressionReason.CONFIRMATION_PENDING,
            )
        }

        // Structured severity mode
        if (assessment.severity.rank < AlertSeverity.CAUTION.rank) {
            inactiveFrameCount++
            val releaseThreshold = if (lastAlertSeverity == AlertSeverity.WARNING) {
                warningReleaseFrames
            } else {
                cautionReleaseFrames
            }
            if (inactiveFrameCount >= releaseThreshold) {
                lastAlertSeverity = AlertSeverity.NONE
                lastAlertKey = null
            }
            consecutiveRiskCount = 0
            lastCandidateKey = null
            return AlertDecision.Suppressed(AlertSuppressionReason.NOT_A_HAZARD_CANDIDATE)
        }

        inactiveFrameCount = 0
        val targetKey = assessment.trackId ?: (assessment.direction ?: assessment.activeCorridor).ordinal
        val key = CandidateKey(assessment.severity, assessment.cause, targetKey)

        if (key == lastCandidateKey) {
            consecutiveRiskCount++
        } else {
            consecutiveRiskCount = 1
            lastCandidateKey = key
        }

        val requiredFrames = if (assessment.severity == AlertSeverity.WARNING) {
            confirmationFramesRequired
        } else {
            cautionConfirmationFramesRequired
        }

        if (consecutiveRiskCount >= requiredFrames) {
            val isEscalation = assessment.severity.rank > lastAlertSeverity.rank
            val cooldownForLevel = if (assessment.severity == AlertSeverity.WARNING) alertCooldownMs else cautionCooldownMs
            val isCooldownElapsed = lastAlertRealtimeMs == 0L || (nowRealtimeMs - lastAlertRealtimeMs) >= cooldownForLevel
            val isTargetChanged = key != lastAlertKey

            if (isEscalation || isCooldownElapsed || isTargetChanged) {
                val reason = when {
                    isEscalation -> "ESCALATION"
                    isTargetChanged -> "CORRIDOR_CHANGED"
                    else -> "COOLDOWN_ELAPSED"
                }
                lastAlertRealtimeMs = nowRealtimeMs
                lastAlertSeverity = assessment.severity
                lastAlertKey = key
                lastAlertLevel = assessment.level
                lastAlertCorridor = assessment.activeCorridor
                return AlertDecision.Fire(assessment, reason)
            }
        }

        return AlertDecision.Suppressed(
            if (consecutiveRiskCount >= requiredFrames) AlertSuppressionReason.COOLDOWN
            else AlertSuppressionReason.CONFIRMATION_PENDING,
        )
    }
}
