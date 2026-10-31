package com.ariateam.aria.distance

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

enum class VibrationDecision {
    NONE,
    URGENT,
    CAUTION,
}

/**
 * Pure policy logic for distance alert vibration cooldown and immediate escalation.
 * Separated from Android system Vibrator service to allow direct, exhaustive JVM unit testing.
 */
class DistanceAlertPolicy(
    val urgentCooldownMs: Long = URGENT_COOLDOWN_MS,
    val cautionCooldownMs: Long = CAUTION_COOLDOWN_MS,
) {
    companion object {
        const val URGENT_COOLDOWN_MS = 1_200L
        const val CAUTION_COOLDOWN_MS = 2_500L
    }

    var lastRisk: DistanceRisk = DistanceRisk.UNKNOWN
        private set

    var lastVibrateTimestamp: Long = 0L
        private set

    /**
     * Evaluates a risk assessment against previous state and timestamp.
     */
    fun evaluate(risk: DistanceRisk, currentTimeMillis: Long): VibrationDecision {
        val decision = when (risk) {
            DistanceRisk.URGENT -> {
                val isEscalation = lastRisk != DistanceRisk.URGENT
                val cooldownPassed = (currentTimeMillis - lastVibrateTimestamp) >= urgentCooldownMs
                if (isEscalation || cooldownPassed) {
                    lastVibrateTimestamp = currentTimeMillis
                    VibrationDecision.URGENT
                } else {
                    VibrationDecision.NONE
                }
            }
            DistanceRisk.CAUTION -> {
                val isEscalation = lastRisk == DistanceRisk.CLEAR || lastRisk == DistanceRisk.UNKNOWN
                val cooldownPassed = (currentTimeMillis - lastVibrateTimestamp) >= cautionCooldownMs
                if (isEscalation || cooldownPassed) {
                    lastVibrateTimestamp = currentTimeMillis
                    VibrationDecision.CAUTION
                } else {
                    VibrationDecision.NONE
                }
            }
            DistanceRisk.CLEAR, DistanceRisk.UNKNOWN -> {
                VibrationDecision.NONE
            }
        }
        lastRisk = risk
        return decision
    }

    fun reset() {
        lastRisk = DistanceRisk.UNKNOWN
        lastVibrateTimestamp = 0L
    }
}

/**
 * Handles tactile vibration alerts for obstacle proximity risk.
 *
 * Implements cooldown intervals between recurring vibrations and immediate escalation
 * when risk level increases (e.g., from CAUTION or CLEAR directly to URGENT).
 */
class DistanceAlertVibrator(
    context: Context,
    private val timeProvider: () -> Long = { SystemClock.elapsedRealtime() },
    val policy: DistanceAlertPolicy = DistanceAlertPolicy(),
) {
    companion object {
        val URGENT_PATTERN = longArrayOf(0, 160, 80, 160)
        val URGENT_AMPLITUDES = intArrayOf(0, 255, 0, 255)

        val CAUTION_PATTERN = longArrayOf(0, 120)
        val CAUTION_AMPLITUDES = intArrayOf(0, 180)
    }

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /**
     * Evaluates a risk update and triggers haptic feedback if criteria (escalation or cooldown) are met.
     *
     * @return true if vibration was triggered, false otherwise.
     */
    fun onRiskEvaluated(risk: DistanceRisk): Boolean {
        val now = timeProvider()
        val decision = policy.evaluate(risk, now)
        return when (decision) {
            VibrationDecision.URGENT -> {
                performVibrate(URGENT_PATTERN, URGENT_AMPLITUDES)
                true
            }
            VibrationDecision.CAUTION -> {
                performVibrate(CAUTION_PATTERN, CAUTION_AMPLITUDES)
                true
            }
            VibrationDecision.NONE -> false
        }
    }

    private fun performVibrate(timings: LongArray, amplitudes: IntArray) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (v.hasAmplitudeControl()) {
                    VibrationEffect.createWaveform(timings, amplitudes, -1)
                } else {
                    VibrationEffect.createWaveform(timings, -1)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val attributes = VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_ACCESSIBILITY)
                        .build()
                    v.vibrate(effect, attributes)
                } else {
                    @Suppress("DEPRECATION")
                    val audioAttributes = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    @Suppress("DEPRECATION")
                    v.vibrate(effect, audioAttributes)
                }
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(timings, -1)
            }
        } catch (_: Throwable) {
            // Non-fatal if device vibrator service is temporarily unavailable
        }
    }

    fun cancel() {
        try {
            vibrator?.cancel()
        } catch (_: Throwable) {
        }
        policy.reset()
    }

    fun release() {
        cancel()
    }
}
