package com.ariateam.aria.hazard

import kotlin.math.abs
import kotlin.math.exp

/**
 * Pure Kotlin, time-based rapid-motion policy for gyroscope motion tolerance.
 *
 * Implements exponential rate smoothing (tau = 100ms) for restriction judgment only,
 * with separate entry and exit hysteresis:
 * - Immediate restriction when raw rate >= immediateThresholdDegPerSec (>= 120 deg/s).
 * - Entry restriction when smoothedRate > entryThresholdDegPerSec sustained for entrySustainNanos (150ms).
 * - Recovery/exit when smoothedRate < exitThresholdDegPerSec sustained for exitSustainNanos (300ms).
 * - Timers are reset immediately when their respective condition is broken.
 * - State is completely cleared on session reset, sensor faults, timestamp regression, or coordinate/geometry changes.
 */
class MotionRapidTurnPolicy(
    val entryThresholdDegPerSec: Float,
    val exitThresholdDegPerSec: Float,
    val entrySustainNanos: Long = DEFAULT_ENTRY_SUSTAIN_NANOS,
    val exitSustainNanos: Long = DEFAULT_EXIT_SUSTAIN_NANOS,
    val immediateThresholdDegPerSec: Float = DEFAULT_IMMEDIATE_THRESHOLD_DEG_PER_SEC,
    val tauSeconds: Float = DEFAULT_TAU_SECONDS,
    val streamFreshnessToleranceNanos: Long = DEFAULT_STREAM_FRESHNESS_TOLERANCE_NANOS,
) {
    init {
        require(entryThresholdDegPerSec > exitThresholdDegPerSec) {
            "Entry threshold ($entryThresholdDegPerSec) must be greater than exit threshold ($exitThresholdDegPerSec)"
        }
        require(entrySustainNanos > 0L)
        require(exitSustainNanos > 0L)
        require(immediateThresholdDegPerSec > entryThresholdDegPerSec)
        require(tauSeconds > 0f)
        require(streamFreshnessToleranceNanos > 0L)
    }

    var isRestricted: Boolean = false
        private set

    var rawRateDegPerSec: Float = 0f
        private set

    var smoothedRateDegPerSec: Float = 0f
        private set

    var lastTransitionReason: String? = null
        private set

    private var lastTimestampNanos: Long = 0L
    private var entryConditionStartNanos: Long? = null
    private var recoveryConditionStartNanos: Long? = null

    /**
     * Resets/clears the policy. Called on session reset, sensor faults, timestamp regression, or coordinate changes.
     */
    fun reset() {
        isRestricted = false
        rawRateDegPerSec = 0f
        smoothedRateDegPerSec = 0f
        lastTimestampNanos = 0L
        entryConditionStartNanos = null
        recoveryConditionStartNanos = null
        lastTransitionReason = null
    }

    /**
     * Updates the policy with a new gyroscope sample rate at [timestampNanos].
     *
     * @param timestampNanos Monotonic timestamp of the sensor sample.
     * @param rawRate Unsigned or signed rate in deg/s. Magnitude is evaluated.
     * @return Current restriction status (true if restricted, false otherwise).
     */
    fun update(timestampNanos: Long, rawRate: Float): Boolean {
        if (timestampNanos <= 0L || !rawRate.isFinite()) {
            reset()
            return false
        }

        // Timestamp regression: regression clears policy
        if (lastTimestampNanos > 0L && timestampNanos <= lastTimestampNanos) {
            reset()
            return false
        }

        val rate = abs(rawRate)
        rawRateDegPerSec = rate

        // First sample after reset or stream gap
        if (lastTimestampNanos == 0L || (timestampNanos - lastTimestampNanos) > streamFreshnessToleranceNanos) {
            val wasGap = lastTimestampNanos > 0L
            if (wasGap) {
                reset()
                rawRateDegPerSec = rate
            }
            smoothedRateDegPerSec = rate
            lastTimestampNanos = timestampNanos
            if (rate >= immediateThresholdDegPerSec) {
                isRestricted = true
                entryConditionStartNanos = null
                recoveryConditionStartNanos = null
                lastTransitionReason = REASON_IMMEDIATE_SEVERE
            } else if (rate > entryThresholdDegPerSec) {
                entryConditionStartNanos = timestampNanos
                recoveryConditionStartNanos = null
            }
            return isRestricted
        }

        val dtNanos = timestampNanos - lastTimestampNanos
        val dtSec = dtNanos / 1_000_000_000.0f
        lastTimestampNanos = timestampNanos

        // Exponential smoothing for restriction judgment only: y = (1 - alpha) * y + alpha * x
        val alpha = (1.0f - exp(-dtSec / tauSeconds)).coerceIn(0.0f, 1.0f)
        smoothedRateDegPerSec = (1.0f - alpha) * smoothedRateDegPerSec + alpha * rate

        // 1. Immediate restriction check (>= 120 deg/s)
        if (rate >= immediateThresholdDegPerSec) {
            if (!isRestricted) {
                isRestricted = true
                lastTransitionReason = REASON_IMMEDIATE_SEVERE
            }
            // Reset recovery and entry timers
            entryConditionStartNanos = null
            recoveryConditionStartNanos = null
            return true
        }

        if (!isRestricted) {
            // Evaluates entry condition: smoothedRate > entryThreshold sustained for entrySustainNanos
            if (smoothedRateDegPerSec > entryThresholdDegPerSec) {
                val startNanos = entryConditionStartNanos ?: timestampNanos.also { entryConditionStartNanos = it }
                if (timestampNanos - startNanos >= entrySustainNanos) {
                    isRestricted = true
                    entryConditionStartNanos = null
                    recoveryConditionStartNanos = null
                    lastTransitionReason = REASON_SUSTAINED_RAPID_TURN
                }
            } else {
                // Reset entry timer when broken
                entryConditionStartNanos = null
            }
        } else {
            // Restricted state: evaluates recovery condition: smoothedRate < exitThreshold sustained for exitSustainNanos
            if (smoothedRateDegPerSec < exitThresholdDegPerSec) {
                val startNanos = recoveryConditionStartNanos ?: timestampNanos.also { recoveryConditionStartNanos = it }
                if (timestampNanos - startNanos >= exitSustainNanos) {
                    isRestricted = false
                    recoveryConditionStartNanos = null
                    entryConditionStartNanos = null
                    lastTransitionReason = REASON_RECOVERED
                }
            } else {
                // Reset recovery timer when broken
                recoveryConditionStartNanos = null
            }
        }

        return isRestricted
    }

    companion object {
        const val DEFAULT_ENTRY_SUSTAIN_NANOS = 150_000_000L // 150ms
        const val DEFAULT_EXIT_SUSTAIN_NANOS = 300_000_000L // 300ms
        const val DEFAULT_IMMEDIATE_THRESHOLD_DEG_PER_SEC = 120.0f
        const val DEFAULT_TAU_SECONDS = 0.100f // 100ms
        const val DEFAULT_STREAM_FRESHNESS_TOLERANCE_NANOS = 200_000_000L // 200ms

        const val DIRECTION_ENTRY_THRESHOLD_DEG_PER_SEC = 35.0f
        const val DIRECTION_EXIT_THRESHOLD_DEG_PER_SEC = 25.0f

        const val GROUND_ENTRY_THRESHOLD_DEG_PER_SEC = 45.0f
        const val GROUND_EXIT_THRESHOLD_DEG_PER_SEC = 35.0f

        const val REASON_IMMEDIATE_SEVERE = "급격한 고속 회전 (120°/s 이상 즉시 제한)"
        const val REASON_SUSTAINED_RAPID_TURN = "지속 회전 (기준치 초과 150ms 지속)"
        const val REASON_RECOVERED = "안정 회복 (회복 기준 300ms 지속)"

        fun forDirection(
            entryThresholdDegPerSec: Float = DIRECTION_ENTRY_THRESHOLD_DEG_PER_SEC,
            exitThresholdDegPerSec: Float = DIRECTION_EXIT_THRESHOLD_DEG_PER_SEC,
            entrySustainNanos: Long = DEFAULT_ENTRY_SUSTAIN_NANOS,
            exitSustainNanos: Long = DEFAULT_EXIT_SUSTAIN_NANOS,
            immediateThresholdDegPerSec: Float = DEFAULT_IMMEDIATE_THRESHOLD_DEG_PER_SEC,
            tauSeconds: Float = DEFAULT_TAU_SECONDS,
        ): MotionRapidTurnPolicy = MotionRapidTurnPolicy(
            entryThresholdDegPerSec = entryThresholdDegPerSec,
            exitThresholdDegPerSec = exitThresholdDegPerSec,
            entrySustainNanos = entrySustainNanos,
            exitSustainNanos = exitSustainNanos,
            immediateThresholdDegPerSec = immediateThresholdDegPerSec,
            tauSeconds = tauSeconds,
        )

        fun forGroundContact(
            entryThresholdDegPerSec: Float = GROUND_ENTRY_THRESHOLD_DEG_PER_SEC,
            exitThresholdDegPerSec: Float = GROUND_EXIT_THRESHOLD_DEG_PER_SEC,
            entrySustainNanos: Long = DEFAULT_ENTRY_SUSTAIN_NANOS,
            exitSustainNanos: Long = DEFAULT_EXIT_SUSTAIN_NANOS,
            immediateThresholdDegPerSec: Float = DEFAULT_IMMEDIATE_THRESHOLD_DEG_PER_SEC,
            tauSeconds: Float = DEFAULT_TAU_SECONDS,
        ): MotionRapidTurnPolicy = MotionRapidTurnPolicy(
            entryThresholdDegPerSec = entryThresholdDegPerSec,
            exitThresholdDegPerSec = exitThresholdDegPerSec,
            entrySustainNanos = entrySustainNanos,
            exitSustainNanos = exitSustainNanos,
            immediateThresholdDegPerSec = immediateThresholdDegPerSec,
            tauSeconds = tauSeconds,
        )
    }
}
