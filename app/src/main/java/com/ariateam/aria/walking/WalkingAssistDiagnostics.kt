package com.ariateam.aria.walking

import java.util.ArrayDeque
import java.util.LinkedHashMap

enum class WalkingDiagnosticStage {
    CAPTURE,
    DECISION,
    DISPATCH,
    SPEECH_START,
    SUPPRESSION,
    LIMITED,
}

enum class WalkingDiagnosticReason {
    CONFIRMATION_PENDING,
    COOLDOWN,
    DUPLICATE_CAPTURE,
    OUT_OF_ORDER_CAPTURE,
    INVALID_CAPTURE,
    FUTURE_CAPTURE,
    STALE_RESULT,
    NOT_A_HAZARD_CANDIDATE,
    ALERT_SESSION_INACTIVE,
    PERMISSION_LOST,
    CAMERA_LOST,
    MODEL_UNAVAILABLE,
    MOTION_UNAVAILABLE,
    MOTION_UNRELIABLE,
    PIPELINE_STALLED,
    PIPELINE_ERROR,
    NO_FRESH_RESULT,
    USER_CAUTION_DISABLED,
}

data class WalkingAssistDiagnosticEvent(
    val sessionGeneration: Long,
    val sequence: Long,
    val frameSequence: Long,
    val stage: WalkingDiagnosticStage,
    val elapsedRealtimeNanos: Long,
    val captureTimestampNanos: Long,
    val backend: String,
    val model: String,
    val reason: WalkingDiagnosticReason? = null,
)

class WalkingAssistDiagnostics(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val events = ArrayDeque<WalkingAssistDiagnosticEvent>()
    private val frameCaptureTimestamps = LinkedHashMap<Long, Long>()
    private var sessionGeneration: Long? = null
    private var sessionStartedAtNanos: Long = 0L
    private var lastEventAtNanos: Long = 0L
    private var lastCaptureAtNanos: Long = 0L
    private var eventSequence: Long = 0L
    private var frameSequence: Long = 0L
    private var stopped = false
    private var backend = UNKNOWN_IDENTITY
    private var model = UNKNOWN_IDENTITY

    init {
        require(capacity > 0)
    }

    @Synchronized
    fun startSession(
        generation: Long,
        elapsedRealtimeNanos: Long,
        backend: String,
        model: String,
    ): Boolean {
        require(generation >= 0L)
        require(elapsedRealtimeNanos >= 0L)
        if (sessionGeneration?.let { generation <= it } == true ||
            elapsedRealtimeNanos < lastEventAtNanos
        ) {
            return false
        }

        sessionGeneration = generation
        sessionStartedAtNanos = elapsedRealtimeNanos
        lastEventAtNanos = elapsedRealtimeNanos
        lastCaptureAtNanos = 0L
        eventSequence = 0L
        frameSequence = 0L
        stopped = false
        frameCaptureTimestamps.clear()
        this.backend = backend.nonBlankOrUnknown()
        this.model = model.nonBlankOrUnknown()
        events.clear()
        return true
    }

    @Synchronized
    fun recordCapture(
        generation: Long,
        captureTimestampNanos: Long,
        elapsedRealtimeNanos: Long,
        backend: String,
        model: String,
    ): Long? {
        if (stopped || generation != sessionGeneration ||
            captureTimestampNanos < sessionStartedAtNanos ||
            captureTimestampNanos <= lastCaptureAtNanos ||
            elapsedRealtimeNanos < lastEventAtNanos ||
            elapsedRealtimeNanos < captureTimestampNanos
        ) {
            return null
        }
        this.backend = backend.nonBlankOrUnknown()
        this.model = model.nonBlankOrUnknown()
        lastCaptureAtNanos = captureTimestampNanos
        val nextFrameSequence = ++frameSequence
        frameCaptureTimestamps[nextFrameSequence] = captureTimestampNanos
        append(
            frameSequence = nextFrameSequence,
            stage = WalkingDiagnosticStage.CAPTURE,
            elapsedRealtimeNanos = elapsedRealtimeNanos,
        )
        while (frameCaptureTimestamps.size > capacity) {
            frameCaptureTimestamps.remove(frameCaptureTimestamps.keys.first())
        }
        return nextFrameSequence
    }

    @Synchronized
    fun recordDecision(
        generation: Long,
        frameSequence: Long,
        elapsedRealtimeNanos: Long,
    ) = recordStage(generation, frameSequence, WalkingDiagnosticStage.DECISION, elapsedRealtimeNanos)

    /** Fences delayed callbacks for this generation while leaving its history readable. */
    @Synchronized
    fun stopSession(generation: Long): Boolean {
        if (generation != sessionGeneration || stopped) return false
        stopped = true
        return true
    }

    @Synchronized
    fun recordDispatch(
        generation: Long,
        frameSequence: Long,
        elapsedRealtimeNanos: Long,
    ) = recordStage(generation, frameSequence, WalkingDiagnosticStage.DISPATCH, elapsedRealtimeNanos)

    @Synchronized
    fun recordSpeechStart(
        generation: Long,
        frameSequence: Long,
        elapsedRealtimeNanos: Long,
    ) = recordStage(generation, frameSequence, WalkingDiagnosticStage.SPEECH_START, elapsedRealtimeNanos)

    @Synchronized
    fun recordSuppression(
        generation: Long,
        frameSequence: Long,
        elapsedRealtimeNanos: Long,
        reason: WalkingDiagnosticReason,
    ) = recordStage(
        generation,
        frameSequence,
        WalkingDiagnosticStage.SUPPRESSION,
        elapsedRealtimeNanos,
        reason,
    )

    @Synchronized
    fun recordLimited(
        generation: Long,
        frameSequence: Long,
        elapsedRealtimeNanos: Long,
        reason: WalkingDiagnosticReason,
    ) = recordStage(
        generation,
        frameSequence,
        WalkingDiagnosticStage.LIMITED,
        elapsedRealtimeNanos,
        reason,
    )

    @Synchronized
    fun snapshot(): List<WalkingAssistDiagnosticEvent> = events.toList()

    private fun recordStage(
        generation: Long,
        frameSequence: Long,
        stage: WalkingDiagnosticStage,
        elapsedRealtimeNanos: Long,
        reason: WalkingDiagnosticReason? = null,
    ): Boolean {
        if (stopped || generation != sessionGeneration ||
            (frameSequence <= 0L && stage !in setOf(
                WalkingDiagnosticStage.DISPATCH,
                WalkingDiagnosticStage.SPEECH_START,
                WalkingDiagnosticStage.SUPPRESSION,
                WalkingDiagnosticStage.LIMITED,
            )) ||
            (frameSequence > 0L && frameSequence !in frameCaptureTimestamps) ||
            elapsedRealtimeNanos < lastEventAtNanos ||
            ((stage == WalkingDiagnosticStage.SUPPRESSION || stage == WalkingDiagnosticStage.LIMITED) &&
                reason == null)
        ) {
            return false
        }
        append(frameSequence, stage, elapsedRealtimeNanos, reason)
        return true
    }

    private fun append(
        frameSequence: Long,
        stage: WalkingDiagnosticStage,
        elapsedRealtimeNanos: Long,
        reason: WalkingDiagnosticReason? = null,
    ) {
        events.addLast(
            WalkingAssistDiagnosticEvent(
                sessionGeneration = checkNotNull(sessionGeneration),
                sequence = ++eventSequence,
                frameSequence = frameSequence,
                stage = stage,
                elapsedRealtimeNanos = elapsedRealtimeNanos,
                captureTimestampNanos = frameCaptureTimestamps[frameSequence] ?: 0L,
                backend = backend,
                model = model,
                reason = reason,
            ),
        )
        lastEventAtNanos = elapsedRealtimeNanos
        while (events.size > capacity) events.removeFirst()
    }

    private fun String.nonBlankOrUnknown(): String = trim().ifEmpty { UNKNOWN_IDENTITY }

    companion object {
        const val DEFAULT_CAPACITY = 256
        private const val UNKNOWN_IDENTITY = "unknown"
    }
}
