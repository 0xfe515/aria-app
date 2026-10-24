package com.ariateam.aria.hazard

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import java.util.concurrent.atomic.AtomicLong

/**
 * Android system integration for hazard alerts: manages Vibrator haptics, transient Toasts,
 * and session-generation fencing backed by [HazardAlertPolicy].
 */
class HazardAlertCoordinator(
    private val context: Context? = null,
    @Volatile var isEnabled: Boolean = true,
    val policy: HazardAlertPolicy = HazardAlertPolicy(
        confirmationFramesRequired = 2,
        alertCooldownMs = 2_500L,
        staleResultExpiryMs = 1_500L,
        clockMs = { SystemClock.elapsedRealtime() },
    ),
    private val clockNanos: () -> Long = { SystemClock.elapsedRealtimeNanos() },
    private val vibratorDispatcher: ((HazardLevel) -> Unit)? = null,
    private val toastDispatcher: ((String) -> Unit)? = null,
    private val toastPoster: ((Runnable) -> Unit)? = null,
) : AutoCloseable {

    private val vibrator: Vibrator? by lazy {
        context?.let { ctx ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }
    }

    private val mainHandler: Handler? by lazy {
        runCatching { Handler(Looper.getMainLooper()) }.getOrNull()
    }
    private val generation = AtomicLong(0L)
    private val lock = Any()
    private var sessionStartElapsedNanos: Long = 0L

    @Volatile
    var isSessionActive: Boolean = false
        private set

    fun start() {
        synchronized(lock) {
            isSessionActive = true
            sessionStartElapsedNanos = clockNanos()
            generation.incrementAndGet()
            policy.reset()
        }
    }

    fun processAssessment(assessment: HazardAssessment, captureTimestampNanos: Long = assessment.timestampNanos) {
        synchronized(lock) {
            if (!isSessionActive || !isEnabled) {
                policy.reset()
                return
            }
            if (captureTimestampNanos < sessionStartElapsedNanos) {
                // Reject late or crossed-session frames older than session start
                return
            }
            val ticket = generation.get()
            val nowElapsedNanos = clockNanos()
            val nowRealtime = nowElapsedNanos / 1_000_000L

            when (val decision = policy.evaluate(assessment, nowRealtime, nowElapsedNanos)) {
                is AlertDecision.Fire -> {
                    fireAlertLocked(decision.assessment, ticket, captureTimestampNanos)
                }
                is AlertDecision.Suppressed -> {
                    // Suppressed by hysteresis or cooldown
                }
            }
        }
    }

    private fun fireAlertLocked(assessment: HazardAssessment, ticket: Long, captureTimestampNanos: Long) {
        // Strict triple gate: check session active, enabled, AND generation match BEFORE vibrating
        if (!isSessionActive || !isEnabled || generation.get() != ticket) {
            return
        }
        if (captureTimestampNanos < sessionStartElapsedNanos) {
            return
        }

        // 1. Haptic vibration (gated)
        if (vibratorDispatcher != null) {
            vibratorDispatcher.invoke(assessment.level)
        } else {
            vibrator?.let { vib ->
                if (vib.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val effect = when (assessment.level) {
                            HazardLevel.NEAR_PATH_HAZARD -> {
                                // Double pulse for near hazard
                                VibrationEffect.createWaveform(
                                    longArrayOf(0, 160, 80, 160),
                                    intArrayOf(0, 255, 0, 255),
                                    -1,
                                )
                            }
                            HazardLevel.PATH_BLOCKED -> {
                                // Single strong buzz for path blocked
                                VibrationEffect.createOneShot(200L, VibrationEffect.DEFAULT_AMPLITUDE)
                            }
                            else -> {
                                VibrationEffect.createOneShot(80L, VibrationEffect.DEFAULT_AMPLITUDE)
                            }
                        }
                        vib.vibrate(effect)
                    } else {
                        @Suppress("DEPRECATION")
                        vib.vibrate(200L)
                    }
                }
            }
        }

        // 2. Transient Toast on main thread (fenced by generation, session active, and timestamp freshness)
        val staleExpiryNanos = policy.staleResultExpiryMs * 1_000_000L
        val toastTask = Runnable {
            synchronized(lock) {
                if (!isSessionActive || !isEnabled || generation.get() != ticket) {
                    return@synchronized
                }
                if (captureTimestampNanos < sessionStartElapsedNanos) {
                    return@synchronized
                }
                if ((clockNanos() - captureTimestampNanos) > staleExpiryNanos) {
                    return@synchronized
                }
                if (toastDispatcher != null) {
                    toastDispatcher.invoke(assessment.messageKorean)
                } else if (context != null) {
                    runCatching {
                        Toast.makeText(context, assessment.messageKorean, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        if (toastPoster != null) {
            toastPoster.invoke(toastTask)
        } else if (toastDispatcher != null && mainHandler == null) {
            toastTask.run()
        } else {
            mainHandler?.post(toastTask)
        }
    }

    fun stop() {
        synchronized(lock) {
            isSessionActive = false
            sessionStartElapsedNanos = 0L
            generation.incrementAndGet()
            mainHandler?.removeCallbacksAndMessages(null)
            vibrator?.cancel()
            policy.reset()
        }
    }

    override fun close() {
        stop()
    }
}
