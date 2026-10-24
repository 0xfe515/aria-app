package com.ariateam.aria.hazard

sealed interface AlertDecision {
    data class Fire(val assessment: HazardAssessment, val reason: String) : AlertDecision
    object Suppressed : AlertDecision
}

/**
 * Pure, deterministic alert policy implementing temporal hysteresis, cooldown, and staleness gating.
 * Decoupled from Android Context/Vibrator/Toast for 100% JVM unit testability.
 */
class HazardAlertPolicy(
    val confirmationFramesRequired: Int = 2,
    val alertCooldownMs: Long = 2_500L,
    val staleResultExpiryMs: Long = 1_500L,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private var lastAssessmentTimestampNanos: Long = 0L
    private var consecutiveRiskCount: Int = 0
    private var lastCandidateLevel: HazardLevel? = null
    private var lastCandidateCorridor: CorridorDirection? = null

    private var lastAlertRealtimeMs: Long = 0L
    private var lastAlertLevel: HazardLevel = HazardLevel.CLEAR_UNWARNED
    private var lastAlertCorridor: CorridorDirection? = null

    fun reset() {
        lastAssessmentTimestampNanos = 0L
        consecutiveRiskCount = 0
        lastCandidateLevel = null
        lastCandidateCorridor = null
        lastAlertRealtimeMs = 0L
        lastAlertLevel = HazardLevel.CLEAR_UNWARNED
        lastAlertCorridor = null
    }

    fun evaluate(
        assessment: HazardAssessment,
        nowRealtimeMs: Long = clockMs(),
        nowElapsedNanos: Long = nowRealtimeMs * 1_000_000L,
    ): AlertDecision {
        val captureNanos = assessment.timestampNanos
        if (captureNanos <= 0L) return AlertDecision.Suppressed

        // Reject duplicate or out-of-order capture timestamps
        if (lastAssessmentTimestampNanos > 0L && captureNanos <= lastAssessmentTimestampNanos) {
            return AlertDecision.Suppressed
        }

        // Reject future capture timestamps (> 500ms ahead of reference clock)
        if (nowElapsedNanos > 0L && captureNanos > nowElapsedNanos + 500_000_000L) {
            return AlertDecision.Suppressed
        }

        // Reject stale inference results (> 1,500ms since camera capture)
        val ageMs = (nowElapsedNanos - captureNanos) / 1_000_000.0f
        if (ageMs > staleResultExpiryMs) {
            consecutiveRiskCount = 0
            lastCandidateLevel = null
            lastCandidateCorridor = null
            lastAssessmentTimestampNanos = captureNanos
            return AlertDecision.Suppressed
        }
        lastAssessmentTimestampNanos = captureNanos

        val level = assessment.level
        if (!assessment.isAlertCandidate || level.priority < HazardLevel.PATH_BLOCKED.priority) {
            consecutiveRiskCount = 0
            lastCandidateLevel = null
            lastCandidateCorridor = null
            return AlertDecision.Suppressed
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

        return AlertDecision.Suppressed
    }
}
