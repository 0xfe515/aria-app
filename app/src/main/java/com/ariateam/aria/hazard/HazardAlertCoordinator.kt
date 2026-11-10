package com.ariateam.aria.hazard

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.widget.Toast
import com.ariateam.aria.R
import com.ariateam.aria.walking.WalkingAssistCopy
import com.ariateam.aria.settings.FeedbackSettingsClient
import java.util.concurrent.atomic.AtomicLong

/**
 * Event delivered to registered sinks when a verified hazard alert condition is confirmed.
 *
 * @property session Monotonically increasing session generation ticket
 * @property sequence Monotonically increasing sequence number of alerts fired within this session
 * @property timestampNanos Elapsed realtime nanoseconds of the frame that triggered the alert
 * @property message Korean announcement text to speak or display
 * @property level Hazard severity level
 * @property severity Alert severity tier (WARNING or CAUTION)
 * @property cause Specific root cause
 */
data class HazardAlertEvent(
    val session: Long,
    val sequence: Long,
    val timestampNanos: Long,
    val message: String,
    val level: HazardLevel = HazardLevel.NEAR_PATH_HAZARD,
    val severity: AlertSeverity = AlertSeverity.WARNING,
    val cause: HazardCause = HazardCause.NONE,
)

/**
 * Consumer interface for background-safe hazard alert delivery (e.g., speech / TTS / audio cues).
 */
fun interface HazardAlertSink {
    fun onHazardAlert(event: HazardAlertEvent)
}

/**
 * Android system integration for hazard alerts: manages Vibrator haptics, transient Toasts,
 * external speech/audio event sinks, and session-generation fencing backed by [HazardAlertPolicy].
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
    private var alertSink: HazardAlertSink? = null,
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
    private val feedbackSettings = context?.let { FeedbackSettingsClient(it) }
    private val generation = AtomicLong(0L)
    private val lock = Any()
    private var sessionStartElapsedNanos: Long = 0L
    private var alertSequence: Long = 0L

    @Volatile
    var isSessionActive: Boolean = false
        private set

    fun setAlertSink(sink: HazardAlertSink?) {
        synchronized(lock) {
            this.alertSink = sink
        }
    }

    fun start() {
        synchronized(lock) {
            isSessionActive = true
            sessionStartElapsedNanos = clockNanos()
            alertSequence = 0L
            generation.incrementAndGet()
            policy.reset()
        }
    }

    fun processAssessment(
        assessment: HazardAssessment,
        captureTimestampNanos: Long = assessment.timestampNanos,
    ): AlertDecision {
        var eventToDispatch: HazardAlertEvent? = null
        var sinkToDispatch: HazardAlertSink? = null
        var decisionResult: AlertDecision = AlertDecision.Suppressed(AlertSuppressionReason.SESSION_INACTIVE)

        synchronized(lock) {
            if (!isSessionActive || !isEnabled) {
                policy.reset()
                return AlertDecision.Suppressed(AlertSuppressionReason.SESSION_INACTIVE)
            }
            if (captureTimestampNanos < sessionStartElapsedNanos) {
                // Reject late or crossed-session frames older than session start
                return AlertDecision.Suppressed(AlertSuppressionReason.DUPLICATE_OR_OUT_OF_ORDER_CAPTURE)
            }
            feedbackSettings?.settings?.value?.let { policy.configure(it.alertFrequency, it.cautionEnabled) }
            val ticket = generation.get()
            val nowElapsedNanos = clockNanos()
            val nowRealtime = nowElapsedNanos / 1_000_000L

            val decision = policy.evaluate(assessment, nowRealtime, nowElapsedNanos)
            decisionResult = decision
            when (decision) {
                is AlertDecision.Fire -> {
                    val staleExpiryNanos = policy.staleResultExpiryMs * 1_000_000L
                    val isFresh = (nowElapsedNanos - captureTimestampNanos) <= staleExpiryNanos

                    if (isFresh && isSessionActive && isEnabled && generation.get() == ticket) {
                        fireAlertLocked(decision.assessment, ticket, captureTimestampNanos)
                        val seq = ++alertSequence
                        val effectiveSeverity = policy.effectiveSeverity(decision.assessment)
                        val alertMessage = if (decision.assessment.cause != HazardCause.DEGRADED && decision.assessment.cause != HazardCause.NONE) {
                            if (decision.assessment.messageKorean.isNotBlank()) {
                                decision.assessment.messageKorean
                            } else {
                                context?.let { appContext ->
                                    WalkingAssistCopy.hazardWarning(decision.assessment.cause)?.let { resId ->
                                        appContext.getString(resId)
                                    }
                                } ?: decision.assessment.messageKorean
                            }
                        } else {
                            context?.let { appContext ->
                                val messageRes = when (decision.assessment.level) {
                                    HazardLevel.NEAR_PATH_HAZARD -> R.string.walking_hazard_near
                                    HazardLevel.PATH_BLOCKED -> R.string.walking_hazard_blocked
                                    else -> 0
                                }
                                if (messageRes != 0) appContext.getString(messageRes)
                                else decision.assessment.messageKorean
                            } ?: decision.assessment.messageKorean
                        }
                        eventToDispatch = HazardAlertEvent(
                            session = ticket,
                            sequence = seq,
                            timestampNanos = captureTimestampNanos,
                            message = alertMessage,
                            level = decision.assessment.level,
                            severity = effectiveSeverity,
                            cause = if (decision.assessment.cause != HazardCause.DEGRADED) decision.assessment.cause else HazardCause.NONE,
                        )
                        sinkToDispatch = this.alertSink ?: sharedAlertSink
                    }
                }
                is AlertDecision.Suppressed -> {
                    // Suppressed by hysteresis or cooldown
                }
            }
        }

        // Dispatch to external sink safely OUTSIDE the synchronization lock
        if (eventToDispatch != null && sinkToDispatch != null &&
            (eventToDispatch?.severity != AlertSeverity.CAUTION || feedbackSettings?.settings?.value?.cautionEnabled != false)) {
            try {
                sinkToDispatch.onHazardAlert(eventToDispatch)
            } catch (t: Throwable) {
                runCatching { Log.e(TAG, "Exception in HazardAlertSink", t) }
            }
        }
        return decisionResult
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
                        val effectiveSeverity = policy.effectiveSeverity(assessment)
                        val effect = when (effectiveSeverity) {
                            AlertSeverity.WARNING -> {
                                if (assessment.cause == HazardCause.W1_VEHICLE_APPROACH ||
                                    assessment.cause == HazardCause.W2_OBJECT_IMMINENT ||
                                    assessment.level == HazardLevel.NEAR_PATH_HAZARD
                                ) {
                                    // Double/triple strong pulse
                                    VibrationEffect.createWaveform(
                                        longArrayOf(0, 160, 80, 160),
                                        intArrayOf(0, 255, 0, 255),
                                        -1,
                                    )
                                } else {
                                    // Single strong buzz for other warnings (e.g. PATH_BLOCKED)
                                    VibrationEffect.createOneShot(200L, VibrationEffect.DEFAULT_AMPLITUDE)
                                }
                            }
                            AlertSeverity.CAUTION -> {
                                // Weak single tap for CAUTION: simultaneous voice + weak vibration
                                VibrationEffect.createOneShot(70L, 96)
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
                if (!isSessionActive || !isEnabled || generation.get() != ticket ||
                    (policy.effectiveSeverity(assessment) == AlertSeverity.CAUTION && feedbackSettings?.settings?.value?.cautionEnabled == false)) {
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
            alertSequence = 0L
            generation.incrementAndGet()
            mainHandler?.removeCallbacksAndMessages(null)
            vibrator?.cancel()
            policy.reset()
        }
    }

    override fun close() {
        stop()
        feedbackSettings?.close()
    }

    companion object {
        private const val TAG = "HazardAlertCoordinator"

        @Volatile
        private var sharedAlertSink: HazardAlertSink? = null

        /**
         * Sets a process-wide shared alert sink accessible by background services without UI dependency.
         */
        fun setSharedAlertSink(sink: HazardAlertSink?) {
            sharedAlertSink = sink
        }

        fun getSharedAlertSink(): HazardAlertSink? = sharedAlertSink
    }
}
