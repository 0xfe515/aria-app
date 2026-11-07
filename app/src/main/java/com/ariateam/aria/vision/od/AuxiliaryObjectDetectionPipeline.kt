package com.ariateam.aria.vision.od

import com.ariateam.aria.vision.UprightCropRect
import java.nio.FloatBuffer
import java.util.concurrent.Executors

/**
 * Immutable geometry identity of one accepted sidecar frame.
 *
 * Evidence produced for a different token must never fuse into the current frame
 * (different camera session, rotation, crop, letterbox, or preprocessing strategy).
 */
data class OdGeometryToken(
    val bufferWidth: Int,
    val bufferHeight: Int,
    val rotationDegrees: Int,
    val uprightWidth: Int,
    val uprightHeight: Int,
    val cropLeft: Int,
    val cropTop: Int,
    val cropWidth: Int,
    val cropHeight: Int,
    val strategyName: String,
    val contentLeft: Int,
    val contentTop: Int,
    val contentWidth: Int,
    val contentHeight: Int,
    val isRotatedClockwise: Boolean,
) {
    companion object {
        fun from(transform: UprightCropRect, rotationDegrees: Int): OdGeometryToken = OdGeometryToken(
            bufferWidth = transform.bufferWidth,
            bufferHeight = transform.bufferHeight,
            rotationDegrees = rotationDegrees,
            uprightWidth = transform.uprightWidth,
            uprightHeight = transform.uprightHeight,
            cropLeft = transform.left,
            cropTop = transform.top,
            cropWidth = transform.width,
            cropHeight = transform.height,
            strategyName = transform.strategy.name,
            contentLeft = transform.contentLeft,
            contentTop = transform.contentTop,
            contentWidth = transform.contentWidth,
            contentHeight = transform.contentHeight,
            isRotatedClockwise = transform.isRotatedClockwise,
        )
    }
}

/**
 * One completed sidecar observation, fenced by session token and geometry token.
 *
 * [resultAgeNanos] is measured from the original accepted frame arrival to inference completion,
 * so queued/replaced/inference time is included. It uses a monotonic elapsed clock only.
 */
data class OdEvidence(
    val sessionToken: Long,
    val geometry: OdGeometryToken,
    val captureTimestampNanos: Long,
    val arrivalElapsedNanos: Long,
    val completedElapsedNanos: Long,
    val resultAgeNanos: Long,
    val detections: List<DetectedObject>,
)

/** Result of a sidecar submission attempt, used for diagnostics and tests. */
enum class OdSubmitOutcome {
    QUEUED,
    REPLACED_PENDING,
    RATE_LIMITED,
    SUSPENDED,
    INACTIVE,
    ORDERING_REJECTED,
    INVALID,
    UNAVAILABLE,
    CLOSED,
}

/**
 * Sidecar scheduling policy.
 *
 * Default 5 Hz while the segmentation pipeline is actually running; one serial worker; at most one
 * in-flight frame and one replaceable pending frame. Results older than [maxResultAgeNanos] from
 * frame arrival are dropped instead of fused into hazard alerts, while [displayLifetimeNanos]
 * governs how long a completed detection snapshot remains visible in the UI overlay.
 */
data class OdSidecarConfig(
    val followSegmentationCadence: Boolean = false,
    val holdOverlayUntilNextResult: Boolean = false,
    val baseRateHz: Int = 5,
    val reducedRateHz: Int = 2,
    val maxResultAgeNanos: Long = 250_000_000L,
    val displayLifetimeNanos: Long = 750_000_000L,
    val maxDetections: Int = 12,
    val primaryBudgetNanos: Long = 50_000_000L,
    val primaryHysteresisNanos: Long = 45_000_000L,
    val primaryWarmupFrames: Int = 40,
    val primaryWarmupNanos: Long = 3_000_000_000L,
    val primaryOverFramesForReduced: Int = 20,
    val primaryOverFramesForSuspend: Int = 60,
    val primaryHealthyFramesForRecovery: Int = 100,
    val sidecarBudgetNanos: Long = 250_000_000L,
    val sidecarOverCompletionsForReduced: Int = 3,
    val sidecarOverCompletionsForSuspend: Int = 6,
    val sidecarHealthyCompletionsForRecovery: Int = 10,
    val minBaselineWarmupFrames: Int = 10,
    val maxSlowBaselinePrimaryNanos: Long = 220_000_000L,
    val maxSuspendPrimaryBudgetNanos: Long = 300_000_000L,
    val slowBaselineSuspendRatioPercent: Int = 135,
    val suspendRecoveryCooldownNanos: Long = 5_000_000_000L,
    val primaryHealthyFramesForSuspendRecovery: Int = 20,
) {
    init {
        require(baseRateHz > 0 && reducedRateHz > 0) { "Sidecar rates must be positive" }
        require(reducedRateHz <= baseRateHz) { "Reduced rate must not exceed base rate" }
        require(maxResultAgeNanos > 0L) { "Result age bound must be positive" }
        require(displayLifetimeNanos > 0L) { "Display lifetime must be positive" }
        require(maxDetections > 0) { "Detection cap must be positive" }
    }

    fun intervalNanosForRate(rateHz: Int): Long = 1_000_000_000L / rateHz.toLong()
}

/** Minimal serial worker abstraction so scheduling tests stay pure JVM (no Android/Looper). */
interface OdWorkerExecutor {
    /** Enqueues one task. Returns false when the executor rejects it (e.g. already shut down). */
    fun execute(command: Runnable): Boolean
    fun shutdown()

    companion object {
        fun singleThread(threadName: String): OdWorkerExecutor = object : OdWorkerExecutor {
            private val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, threadName).apply { isDaemon = true }
            }

            override fun execute(command: Runnable): Boolean =
                runCatching { executor.execute(command) }.isSuccess

            override fun shutdown() {
                executor.shutdown()
            }
        }
    }
}

/**
 * Bounded pressure governor protecting the primary segmentation pipeline.
 *
 * - Primary analyzer frames sustained above the 50ms budget step the sidecar 5 Hz -> 2 Hz and then
 *   suspend it if frames exceed the active suspend budget (startup warmup is ignored).
 * - On slower devices where observed startup frame duration already exceeds 50ms, the observed
 *   startup frame baseline bounds the `REDUCED (2 Hz) -> SUSPENDED (0 Hz)` threshold so the
 *   sidecar stays at a rate-limited 2 Hz rather than permanently suspending, while never
 *   recovering back to 5 Hz unless primary frames drop below 45ms.
 * - If suspended, a bounded cooldown + consecutive frames within the recovery ceiling allow
 *   recovery to `REDUCED (2 Hz)` (never straight to 5 Hz) to probe without flooding.
 */
internal class OdPressureGovernor(private val config: OdSidecarConfig) {
    enum class Level { FULL, REDUCED, SUSPENDED }

    @Volatile
    var level: Level = Level.FULL
        private set

    @Volatile
    var reason: OdBackoffReason = OdBackoffReason.NONE
        private set

    private var sessionStartElapsedNanos = 0L
    private var warmupFramesSeen = 0
    private var warmupDurationSumNanos = 0L
    private var slowBaselinePrimaryNanos = 0L
    private var primaryOverFrames = 0
    private var primaryHealthyFrames = 0
    private var sidecarOverCompletions = 0
    private var sidecarHealthyCompletions = 0
    private var lastPrimaryFrameElapsedNanos = 0L
    private var suspendedAtElapsedNanos = 0L
    private var suspendedRecoveryFrames = 0
    private var suspendedDurationSumNanos = 0L

    @Synchronized
    fun onSessionStarted(nowElapsedNanos: Long) {
        level = Level.FULL
        reason = OdBackoffReason.NONE
        sessionStartElapsedNanos = nowElapsedNanos
        warmupFramesSeen = 0
        warmupDurationSumNanos = 0L
        slowBaselinePrimaryNanos = 0L
        primaryOverFrames = 0
        primaryHealthyFrames = 0
        sidecarOverCompletions = 0
        sidecarHealthyCompletions = 0
        lastPrimaryFrameElapsedNanos = nowElapsedNanos
        suspendedAtElapsedNanos = 0L
        suspendedRecoveryFrames = 0
        suspendedDurationSumNanos = 0L
    }

    @Synchronized
    fun onSessionStopped() {
        level = Level.FULL
        reason = OdBackoffReason.NONE
        sessionStartElapsedNanos = 0L
        warmupFramesSeen = 0
        warmupDurationSumNanos = 0L
        slowBaselinePrimaryNanos = 0L
        primaryOverFrames = 0
        primaryHealthyFrames = 0
        sidecarOverCompletions = 0
        sidecarHealthyCompletions = 0
        lastPrimaryFrameElapsedNanos = 0L
        suspendedAtElapsedNanos = 0L
        suspendedRecoveryFrames = 0
        suspendedDurationSumNanos = 0L
    }

    @Synchronized
    fun onPrimaryFrame(durationNanos: Long, nowElapsedNanos: Long) {
        lastPrimaryFrameElapsedNanos = nowElapsedNanos
        val clampedDuration = durationNanos.coerceAtLeast(0L)
        if (level == Level.SUSPENDED) {
            if (suspendedAtElapsedNanos <= 0L) {
                suspendedAtElapsedNanos = nowElapsedNanos
            }
            if ((nowElapsedNanos - suspendedAtElapsedNanos) < config.suspendRecoveryCooldownNanos) {
                return
            }
            val recoveryCeiling = if (slowBaselinePrimaryNanos > 0L) {
                activeSuspendBudgetNanos()
            } else {
                config.maxSlowBaselinePrimaryNanos
            }
            if (clampedDuration <= recoveryCeiling) {
                suspendedRecoveryFrames++
                suspendedDurationSumNanos += clampedDuration
                if (suspendedRecoveryFrames >= config.primaryHealthyFramesForSuspendRecovery) {
                    val observedAvg = suspendedDurationSumNanos / suspendedRecoveryFrames.toLong()
                    if (slowBaselinePrimaryNanos == 0L && observedAvg > config.primaryBudgetNanos) {
                        slowBaselinePrimaryNanos = observedAvg.coerceAtMost(config.maxSlowBaselinePrimaryNanos)
                    }
                    level = Level.REDUCED
                    reason = OdBackoffReason.PRIMARY_FRAME_OVER_BUDGET
                    primaryOverFrames = config.primaryOverFramesForReduced
                    primaryHealthyFrames = 0
                    sidecarOverCompletions = 0
                    sidecarHealthyCompletions = 0
                    suspendedRecoveryFrames = 0
                    suspendedDurationSumNanos = 0L
                }
            } else {
                suspendedRecoveryFrames = 0
                suspendedDurationSumNanos = 0L
            }
            return
        }
        val inWarmup = warmupFramesSeen < config.primaryWarmupFrames ||
            sessionStartElapsedNanos == 0L ||
            (nowElapsedNanos - sessionStartElapsedNanos) < config.primaryWarmupNanos
        if (inWarmup) {
            warmupFramesSeen++
            warmupDurationSumNanos += clampedDuration
            if (warmupFramesSeen >= config.minBaselineWarmupFrames) {
                val avgWarmup = warmupDurationSumNanos / warmupFramesSeen.toLong()
                if (avgWarmup > config.primaryBudgetNanos) {
                    slowBaselinePrimaryNanos = avgWarmup.coerceAtMost(config.maxSlowBaselinePrimaryNanos)
                }
            }
            primaryOverFrames = 0
            return
        }
        if (clampedDuration > config.primaryBudgetNanos) {
            primaryHealthyFrames = 0
            when (level) {
                Level.FULL -> {
                    primaryOverFrames++
                    if (primaryOverFrames >= config.primaryOverFramesForReduced) {
                        level = Level.REDUCED
                        reason = OdBackoffReason.PRIMARY_FRAME_OVER_BUDGET
                    }
                }
                Level.REDUCED -> {
                    val suspendBudget = activeSuspendBudgetNanos()
                    if (clampedDuration > suspendBudget) {
                        primaryOverFrames++
                        if (reason == OdBackoffReason.PRIMARY_FRAME_OVER_BUDGET &&
                            primaryOverFrames >= config.primaryOverFramesForSuspend
                        ) {
                            level = Level.SUSPENDED
                            reason = OdBackoffReason.PRIMARY_FRAME_OVER_BUDGET
                            suspendedAtElapsedNanos = nowElapsedNanos
                            suspendedRecoveryFrames = 0
                            suspendedDurationSumNanos = 0L
                        }
                    } else {
                        // Frame is above 50ms but within the device's slow baseline suspend bound:
                        // stay at REDUCED (2 Hz) without accumulating toward suspension.
                        primaryOverFrames = config.primaryOverFramesForReduced
                    }
                }
                Level.SUSPENDED -> Unit
            }
        } else {
            if (clampedDuration <= config.primaryHysteresisNanos) {
                primaryOverFrames = 0
                primaryHealthyFrames++
                if (level == Level.REDUCED && reason == OdBackoffReason.PRIMARY_FRAME_OVER_BUDGET &&
                    primaryHealthyFrames >= config.primaryHealthyFramesForRecovery
                ) {
                    level = Level.FULL
                    reason = OdBackoffReason.NONE
                    primaryHealthyFrames = 0
                }
            }
        }
    }

    @Synchronized
    fun onSidecarCompletion(inferenceNanos: Long) {
        if (level == Level.SUSPENDED) return
        if (inferenceNanos > config.sidecarBudgetNanos) {
            sidecarOverCompletions++
            sidecarHealthyCompletions = 0
            when {
                level == Level.FULL && sidecarOverCompletions >= config.sidecarOverCompletionsForReduced -> {
                    level = Level.REDUCED
                    reason = OdBackoffReason.SIDECAR_OVER_BUDGET
                }
                level == Level.REDUCED &&
                    sidecarOverCompletions >= config.sidecarOverCompletionsForSuspend -> {
                    level = Level.SUSPENDED
                    reason = OdBackoffReason.SIDECAR_OVER_BUDGET
                    suspendedAtElapsedNanos = lastPrimaryFrameElapsedNanos
                    suspendedRecoveryFrames = 0
                    suspendedDurationSumNanos = 0L
                }
            }
        } else if (inferenceNanos * 4L <= config.sidecarBudgetNanos * 3L) {
            sidecarOverCompletions = 0
            sidecarHealthyCompletions++
            if (level == Level.REDUCED && reason == OdBackoffReason.SIDECAR_OVER_BUDGET &&
                sidecarHealthyCompletions >= config.sidecarHealthyCompletionsForRecovery &&
                slowBaselinePrimaryNanos == 0L
            ) {
                level = Level.FULL
                reason = OdBackoffReason.NONE
                sidecarHealthyCompletions = 0
            }
        }
    }

    private fun activeSuspendBudgetNanos(): Long {
        if (slowBaselinePrimaryNanos <= config.primaryBudgetNanos) {
            return config.primaryBudgetNanos
        }
        val scaled = (slowBaselinePrimaryNanos * config.slowBaselineSuspendRatioPercent.toLong()) / 100L
        return maxOf(config.primaryBudgetNanos, scaled).coerceAtMost(config.maxSuspendPrimaryBudgetNanos)
    }

    fun effectiveRateHz(): Int = when (level) {
        Level.FULL -> config.baseRateHz
        Level.REDUCED -> config.reducedRateHz
        Level.SUSPENDED -> 0
    }

    fun effectiveIntervalNanos(): Long = when (level) {
        Level.FULL -> config.intervalNanosForRate(config.baseRateHz)
        Level.REDUCED -> config.intervalNanosForRate(config.reducedRateHz)
        Level.SUSPENDED -> Long.MAX_VALUE
    }
}

/**
 * Bounded auxiliary object-detection sidecar.
 *
 * Design invariants (frozen contract, see docs/AUXILIARY_OD_IMPLEMENTATION_2026-11-07.md):
 * - Never awaited by the camera analyzer; [trySubmit] only rate-gates and copies the frozen RGB
 *   frame into a reserved buffer.
 * - One serial worker owns engine creation, invocation, and close; at most one in-flight frame and
 *   one replaceable pending frame (no unbounded executor queue).
 * - Generation/geometry/capture/arrival tokens fence stale completions; results older than
 *   [OdSidecarConfig.maxResultAgeNanos] from frame arrival are dropped.
 * - Stop/reset/close immediately invalidate published evidence and suppress callbacks.
 * - Every failure degrades OD only; the segmentation mask/summary/overlay path is untouched.
 */
class AuxiliaryObjectDetectionPipeline(
    private val engineFactory: () -> ObjectDetectorEngine,
    private val worker: OdWorkerExecutor = OdWorkerExecutor.singleThread("aria-od-sidecar"),
    private val clockNanos: () -> Long = { java.lang.System.nanoTime() },
    private val config: OdSidecarConfig = OdSidecarConfig(),
) : AutoCloseable {

    private val lock = Any()
    private val governor = OdPressureGovernor(config)

    private var closed = false
    private var enabled = false
    private var sessionActive = false
    private var sessionToken = 0L
    private var epoch = 0L

    private var engine: ObjectDetectorEngine? = null
    private var engineFailedThisSession = false

    private var inflight: Job? = null
    private var pending: Job? = null
    private var pendingBuffer: FloatArray? = null
    private var inflightBuffer: FloatArray? = null

    private data class DisplayEntry(
        val sessionToken: Long,
        val geometry: OdGeometryToken,
        val captureTimestampNanos: Long,
        val arrivalElapsedNanos: Long,
        val completedElapsedNanos: Long,
        val snapshot: OdOverlaySnapshot,
    )

    @Volatile
    private var latestEvidence: OdEvidence? = null

    @Volatile
    private var latestDisplay: DisplayEntry? = null

    private var lastAcceptedCaptureNanos = 0L
    private var lastSubmitElapsedNanos = 0L
    private var lastSubmitElapsedValid = false

    private var submitted = 0L
    private var completed = 0L
    private var replaced = 0L
    private var busy = 0L
    private var rateLimited = 0L
    private var staleDropped = 0L
    private var orderingRejected = 0L
    private var invalidDropped = 0L
    private var errors = 0L
    private var lastInferenceNanos = 0L
    private var lastEndToEndNanos = 0L
    private var detail = ""

    private class Job(
        val epoch: Long,
        val sessionToken: Long,
        val captureTimestampNanos: Long,
        val arrivalElapsedNanos: Long,
        val transform: UprightCropRect,
        val geometry: OdGeometryToken,
        val buffer: FloatArray,
    )

    /** Enables/disables sidecar work. Disabling invalidates pending/in-flight evidence immediately. */
    fun setEnabled(enabled: Boolean) {
        val shouldRelease = synchronized(lock) {
            if (closed || this.enabled == enabled) return
            this.enabled = enabled
            // Any in-flight result from the previous enable state must never publish.
            epoch++
            if (!enabled) {
                invalidateLocked("보조 객체 감지 비활성 (미리보기 또는 모델 미준비)")
            }
            !enabled
        }
        if (shouldRelease) requestEngineClose()
    }

    /** Starts a new camera session. Any prior in-flight result is fenced by the new epoch. */
    fun onSessionStarted(token: Long, nowElapsedNanos: Long = clockNanos()) {
        synchronized(lock) {
            if (closed) return
            epoch++
            sessionActive = true
            sessionToken = token
            engineFailedThisSession = false
            invalidateLocked("")
            governor.onSessionStarted(nowElapsedNanos)
        }
    }

    /** Stops the current session; immediately drops evidence and releases the interpreter. */
    fun onSessionStopped(reason: String = "세그멘테이션 세션 종료") {
        val shouldRelease = synchronized(lock) {
            if (closed) return
            epoch++
            sessionActive = false
            governor.onSessionStopped()
            invalidateLocked(reason)
            true
        }
        if (shouldRelease) requestEngineClose()
    }

    /**
     * Offers one analyzed frame to the sidecar.
     *
     * The caller passes a read-only view over its live preprocessing buffer; the buffer is copied
     * only when the frame is actually accepted, so no shared/reused array is retained.
     */
    fun trySubmit(
        rgb: FloatBuffer,
        transform: UprightCropRect,
        rotationDegrees: Int,
        captureTimestampNanos: Long,
        arrivalElapsedNanos: Long,
        nowElapsedNanos: Long = clockNanos(),
    ): OdSubmitOutcome {
        synchronized(lock) {
            if (closed) return OdSubmitOutcome.CLOSED
            // A failed engine stays unavailable until the next session reset; do not copy frames.
            if (engineFailedThisSession) return OdSubmitOutcome.UNAVAILABLE
            if (!enabled || !sessionActive) return OdSubmitOutcome.INACTIVE
            if (!config.followSegmentationCadence && governor.level == OdPressureGovernor.Level.SUSPENDED) {
                rateLimited++
                return OdSubmitOutcome.SUSPENDED
            }
            if (captureTimestampNanos <= 0L || arrivalElapsedNanos <= 0L || nowElapsedNanos <= 0L) {
                invalidDropped++
                return OdSubmitOutcome.INVALID
            }
            if (lastAcceptedCaptureNanos > 0L && captureTimestampNanos <= lastAcceptedCaptureNanos) {
                orderingRejected++
                return OdSubmitOutcome.ORDERING_REJECTED
            }
            if (!config.followSegmentationCadence &&
                lastSubmitElapsedValid &&
                (nowElapsedNanos - lastSubmitElapsedNanos) < governor.effectiveIntervalNanos()
            ) {
                rateLimited++
                return OdSubmitOutcome.RATE_LIMITED
            }
            val copyTarget = pendingBuffer
                ?: FloatArray(com.ariateam.aria.vision.VisionContract.INPUT_FLOATS).also { pendingBuffer = it }
            val duplicate = rgb.duplicate()
            duplicate.position(0)
            if (duplicate.remaining() < copyTarget.size) {
                invalidDropped++
                return OdSubmitOutcome.INVALID
            }
            duplicate.get(copyTarget, 0, copyTarget.size)

            lastAcceptedCaptureNanos = captureTimestampNanos
            lastSubmitElapsedNanos = nowElapsedNanos
            lastSubmitElapsedValid = true
            submitted++

            val replacedPending = pending != null
            if (inflight != null) busy++
            if (replacedPending) replaced++

            pending = Job(
                epoch = epoch,
                sessionToken = sessionToken,
                captureTimestampNanos = captureTimestampNanos,
                arrivalElapsedNanos = arrivalElapsedNanos,
                transform = transform,
                geometry = OdGeometryToken.from(transform, rotationDegrees),
                buffer = copyTarget,
            )
            if (inflight == null) dispatchNextLocked()
            return if (replacedPending) OdSubmitOutcome.REPLACED_PENDING else OdSubmitOutcome.QUEUED
        }
    }

    /** Feeds one completed primary analyzer-frame duration to the pressure governor. */
    fun onPrimaryAnalyzerFrame(durationNanos: Long, nowElapsedNanos: Long = clockNanos()) {
        synchronized(lock) {
            if (closed || !sessionActive) return
            if (!config.followSegmentationCadence) {
                governor.onPrimaryFrame(durationNanos, nowElapsedNanos)
            }
        }
    }

    /** Latest completed evidence, or null when none is valid for the current session. */
    fun latestEvidence(): OdEvidence? = latestEvidence

    /**
     * Latest display-only overlay snapshot fenced by [sessionToken] and [geometry], or `null` when
     * the sidecar is inactive/unavailable, the geometry/session does not match, the timestamp is in
     * the future, the snapshot has expired, or the latest completed frame had zero detections.
     */
    fun latestOverlaySnapshot(
        sessionToken: Long,
        geometry: OdGeometryToken,
        nowElapsedNanos: Long = clockNanos(),
    ): OdOverlaySnapshot? = synchronized(lock) {
        val entry = latestDisplay ?: return null
        if (closed || !enabled || !sessionActive || engineFailedThisSession) {
            latestDisplay = null
            return null
        }
        if (this.sessionToken != sessionToken || entry.sessionToken != sessionToken) {
            return null
        }
        if (entry.geometry != geometry) {
            latestDisplay = null
            return null
        }
        if (nowElapsedNanos <= 0L ||
            nowElapsedNanos < entry.arrivalElapsedNanos ||
            nowElapsedNanos < entry.completedElapsedNanos
        ) {
            return null
        }
        if (!entry.snapshot.isFreshAt(nowElapsedNanos)) {
            latestDisplay = null
            return null
        }
        entry.snapshot
    }

    fun telemetry(nowElapsedNanos: Long = clockNanos()): OdTelemetry = synchronized(lock) {
        val evidence = latestEvidence
        val display = latestDisplay
        val isFollowCadence = config.followSegmentationCadence
        val isHeldMode = config.holdOverlayUntilNextResult
        val status = when {
            closed -> OdSidecarStatus.CLOSED
            !enabled || !sessionActive -> OdSidecarStatus.DISABLED
            !isFollowCadence && governor.level == OdPressureGovernor.Level.SUSPENDED -> OdSidecarStatus.SUSPENDED
            engineFailedThisSession -> OdSidecarStatus.UNAVAILABLE
            inflight != null -> OdSidecarStatus.BUSY
            else -> OdSidecarStatus.IDLE
        }
        val backoff = when {
            engineFailedThisSession -> OdBackoffReason.ENGINE_ERROR
            isFollowCadence -> OdBackoffReason.NONE
            else -> governor.reason
        }
        val effectiveAgeMs = if (isFollowCadence || isHeldMode) {
            display?.let {
                ((nowElapsedNanos - it.arrivalElapsedNanos).coerceAtLeast(0L)) / 1_000_000L
            } ?: evidence?.let {
                ((nowElapsedNanos - it.arrivalElapsedNanos).coerceAtLeast(0L)) / 1_000_000L
            } ?: 0L
        } else {
            evidence?.let {
                ((nowElapsedNanos - it.arrivalElapsedNanos).coerceAtLeast(0L)) / 1_000_000L
            } ?: 0L
        }
        val effectiveDetectionCount = if (isFollowCadence || isHeldMode) {
            display?.snapshot?.detections?.size ?: (evidence?.detections?.size ?: 0)
        } else {
            evidence?.detections?.size ?: 0
        }
        OdTelemetry(
            status = status,
            backoffReason = backoff,
            rateHz = if (isFollowCadence) 0 else governor.effectiveRateHz(),
            submitted = submitted,
            completed = completed,
            replaced = replaced,
            busy = busy,
            rateLimited = rateLimited,
            staleDropped = staleDropped,
            orderingRejected = orderingRejected,
            invalidDropped = invalidDropped,
            errors = errors,
            inferenceMs = lastInferenceNanos / 1_000_000L,
            endToEndMs = lastEndToEndNanos / 1_000_000L,
            resultAgeMs = effectiveAgeMs,
            detectionCount = effectiveDetectionCount,
            sessionToken = sessionToken,
            detail = buildDetailLocked(status),
            followsSegmentationCadence = isFollowCadence,
        )
    }

    private fun buildDetailLocked(status: OdSidecarStatus): String {
        if (config.followSegmentationCadence) {
            return when (status) {
                OdSidecarStatus.UNAVAILABLE -> if (detail.isNotEmpty()) {
                    "보조 객체 감지를 사용할 수 없습니다 (세그멘테이션은 계속 동작): $detail"
                } else {
                    "보조 객체 감지를 사용할 수 없습니다 (세그멘테이션은 계속 동작)."
                }
                OdSidecarStatus.CLOSED -> "종료"
                OdSidecarStatus.DISABLED -> detail.ifEmpty { "비활성" }
                else -> if (detail.isNotEmpty()) {
                    "세그멘테이션 프레임마다 요청 · 별도 주기 제한 없음 ($detail)"
                } else {
                    "세그멘테이션 프레임마다 요청 · 별도 주기 제한 없음"
                }
            }
        }
        val backoffDetail = when (governor.reason) {
            OdBackoffReason.NONE -> ""
            OdBackoffReason.PRIMARY_FRAME_OVER_BUDGET ->
                "세그멘테이션 프레임 예산 초과로 보조 감지 속도를 낮춤 (${governor.effectiveRateHz()}Hz)"
            OdBackoffReason.SIDECAR_OVER_BUDGET ->
                "보조 추론 지연으로 감지 속도를 낮춤 (${governor.effectiveRateHz()}Hz)"
            OdBackoffReason.ENGINE_ERROR -> "보조 감지 엔진 오류"
        }
        val statusDetail = when (status) {
            OdSidecarStatus.SUSPENDED -> "이번 세션에서는 보조 객체 감지를 중단했습니다."
            OdSidecarStatus.UNAVAILABLE -> if (detail.isNotEmpty()) {
                "보조 객체 감지를 사용할 수 없습니다 (세그멘테이션은 계속 동작): $detail"
            } else {
                "보조 객체 감지를 사용할 수 없습니다 (세그멘테이션은 계속 동작)."
            }
            else -> detail
        }
        return listOf(backoffDetail, statusDetail).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun invalidateLocked(reason: String) {
        pending = null
        latestEvidence = null
        latestDisplay = null
        lastAcceptedCaptureNanos = 0L
        lastSubmitElapsedValid = false
        if (reason.isNotEmpty()) detail = reason
    }

    private fun dispatchNextLocked() {
        if (inflight != null || closed) return
        val job = pending ?: return
        pending = null
        val swap = pendingBuffer ?: FloatArray(com.ariateam.aria.vision.VisionContract.INPUT_FLOATS)
        pendingBuffer = inflightBuffer ?: FloatArray(com.ariateam.aria.vision.VisionContract.INPUT_FLOATS)
        inflightBuffer = swap
        inflight = job
        // A rejected enqueue must not leave the single in-flight slot permanently stuck.
        val accepted = worker.execute(Runnable { runJob(job) })
        if (!accepted) {
            inflight = null
            errors++
            detail = "보조 감지 작업 실행 거부 (세그멘테이션 영향 없음)"
        }
    }

    private fun runJob(job: Job) {
        val startNanos = clockNanos()
        if (!isJobStillCurrent(job, startNanos)) {
            dropJobAndContinue(job, countStale = true)
            return
        }

        var detections: List<DetectedObject>? = null
        var failure: Throwable? = null
        try {
            val engineRef = ensureEngine()
            if (engineRef != null) {
                // Slow factory initialization can consume the freshness budget; never run inference
                // for a frame that has already expired or whose session was suspended.
                if (!isJobStillCurrent(job, clockNanos())) {
                    dropJobAndContinue(job, countStale = true)
                    return
                }
                detections = engineRef.detect(job.buffer, job.transform)
            }
        } catch (t: Throwable) {
            failure = t
        }
        val endNanos = clockNanos()
        completeJob(job, detections, failure, startNanos, endNanos)

        if (failure != null) {
            val sanitizedCause = sanitizeFailureCause(failure)
            // Only the owning session may mark the engine failed; a stale job must never disable a
            // freshly restarted session.
            val ownsSession = synchronized(lock) {
                if (closed || job.epoch != epoch) {
                    false
                } else {
                    errors++
                    engineFailedThisSession = true
                    latestEvidence = null
                    latestDisplay = null
                    detail = "보조 감지 추론 오류 ($sanitizedCause; 세그멘테이션 영향 없음)"
                    true
                }
            }
            if (ownsSession) {
                val broken = synchronized(lock) { engine.also { engine = null } }
                runCatching { broken?.close() }
            }
        }

        synchronized(lock) {
            if (inflight === job) inflight = null
            if (!closed) dispatchNextLocked()
        }
    }

    private fun isJobStillCurrent(job: Job, nowNanos: Long): Boolean = synchronized(lock) {
        if (closed || !enabled || !sessionActive || job.epoch != epoch) return false
        if (nowNanos <= 0L || job.arrivalElapsedNanos <= 0L) return false
        if (nowNanos < job.arrivalElapsedNanos) return false
        if (!config.followSegmentationCadence && governor.level == OdPressureGovernor.Level.SUSPENDED) {
            return false
        }
        if (config.holdOverlayUntilNextResult) {
            true
        } else {
            (nowNanos - job.arrivalElapsedNanos) <= config.maxResultAgeNanos
        }
    }

    private fun dropJobAndContinue(job: Job, countStale: Boolean) {
        synchronized(lock) {
            if (countStale && !closed && enabled && sessionActive) staleDropped++
            if (inflight === job) inflight = null
            if (!closed) dispatchNextLocked()
        }
    }

    private fun completeJob(
        job: Job,
        detections: List<DetectedObject>?,
        failure: Throwable?,
        startNanos: Long,
        endNanos: Long,
    ) {
        synchronized(lock) {
            if (closed) return
            if (job.epoch != epoch || !sessionActive || !enabled || engineFailedThisSession) {
                if (failure == null) staleDropped++
                return
            }
            if (failure != null) return
            val raw = detections ?: return
            if (endNanos <= 0L || startNanos <= 0L || job.arrivalElapsedNanos <= 0L ||
                endNanos < job.arrivalElapsedNanos || endNanos < startNanos
            ) {
                staleDropped++
                return
            }
            val ageNanos = (endNanos - job.arrivalElapsedNanos).coerceAtLeast(0L)
            lastInferenceNanos = (endNanos - startNanos).coerceAtLeast(0L)
            if (!config.followSegmentationCadence) {
                governor.onSidecarCompletion(lastInferenceNanos)
            }
            val bounded = if (raw.size > config.maxDetections) {
                raw.subList(0, config.maxDetections).toList()
            } else {
                raw.toList()
            }
            val displayExpiresAt = if (config.holdOverlayUntilNextResult) {
                Long.MAX_VALUE
            } else {
                job.arrivalElapsedNanos + config.displayLifetimeNanos
            }
            if (config.holdOverlayUntilNextResult || endNanos < displayExpiresAt) {
                latestDisplay = if (bounded.isEmpty()) {
                    null
                } else {
                    DisplayEntry(
                        sessionToken = job.sessionToken,
                        geometry = job.geometry,
                        captureTimestampNanos = job.captureTimestampNanos,
                        arrivalElapsedNanos = job.arrivalElapsedNanos,
                        completedElapsedNanos = endNanos,
                        snapshot = OdOverlaySnapshot(
                            detections = bounded,
                            expiresAtElapsedNanos = displayExpiresAt,
                            holdUntilNextResult = config.holdOverlayUntilNextResult,
                        ),
                    )
                }
            } else {
                latestDisplay = null
            }
            if (ageNanos > config.maxResultAgeNanos) {
                latestEvidence = null
                staleDropped++
                if (!config.holdOverlayUntilNextResult) return
                lastEndToEndNanos = ageNanos
                completed++
                return
            }
            latestEvidence = OdEvidence(
                sessionToken = job.sessionToken,
                geometry = job.geometry,
                captureTimestampNanos = job.captureTimestampNanos,
                arrivalElapsedNanos = job.arrivalElapsedNanos,
                completedElapsedNanos = endNanos,
                resultAgeNanos = ageNanos,
                detections = bounded,
            )
            lastEndToEndNanos = ageNanos
            completed++
        }
    }

    private fun ensureEngine(): ObjectDetectorEngine? {
        synchronized(lock) { engine?.let { return it } }
        val startEpoch = synchronized(lock) {
            if (closed || engineFailedThisSession || !sessionActive || !enabled) return null
            epoch
        }
        var initFailure: Throwable? = null
        val created = try {
            engineFactory()
        } catch (t: Throwable) {
            initFailure = t
            null
        }
        var discard: ObjectDetectorEngine? = null
        val adopted = synchronized(lock) {
            // Fence both before and after factory initialization: a reset/disable/stop that raced the
            // factory must close the new interpreter on this worker thread instead of adopting it.
            val staleEpoch = startEpoch != epoch
            when {
                closed || staleEpoch || !sessionActive || !enabled -> {
                    discard = created
                    null
                }
                created == null -> {
                    val sanitizedCause = initFailure?.let(::sanitizeFailureCause) ?: "unknown"
                    engineFailedThisSession = true
                    latestEvidence = null
                    latestDisplay = null
                    errors++
                    detail = "보조 감지 엔진 초기화 실패 ($sanitizedCause; 세그멘테이션 영향 없음)"
                    null
                }
                else -> {
                    engine = created
                    created
                }
            }
        }
        // Interpreter release must never run while holding the scheduling lock.
        runCatching { discard?.close() }
        return adopted
    }

    /**
     * Requests interpreter release. The lookup and close always happen on the sidecar worker so the
     * analyzer/main thread never touches the interpreter and a factory that is still initializing
     * cannot leak an adopted engine.
     */
    private fun requestEngineClose() {
        val accepted = worker.execute(Runnable {
            val reference = synchronized(lock) { engine.also { engine = null } }
            runCatching { reference?.close() }
        })
        if (!accepted) {
            synchronized(lock) {
                errors++
                detail = "보조 감지 엔진 해제 지연 (작업자 종료됨)"
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            epoch++
            sessionActive = false
            enabled = false
            pending = null
            latestEvidence = null
            latestDisplay = null
            lastSubmitElapsedValid = false
            pendingBuffer = null
            inflightBuffer = null
        }
        requestEngineClose()
        worker.shutdown()
    }

    companion object {
        private val PATH_PATTERN = Regex(
            """(?:[A-Za-z]:\\[^\s,;)]+|file://[^\s,;)]+|(?:/data|/storage|/sdcard|/proc|/sys|/system|/vendor|/apex|/mnt|/tmp|/var|/Users|/home)[^\s,;)]*)""",
        )
        private val WHITESPACE_PATTERN = Regex("""\s+""")

        /**
         * Formats a single-line, sanitized internal cause string (`ClassName: message`) without
         * stack traces, file paths, or private URI paths.
         */
        internal fun sanitizeFailureCause(throwable: Throwable): String {
            val primary = formatSingleThrowable(throwable)
            val root = generateSequence(throwable.cause) { it.cause }
                .take(4)
                .lastOrNull()
            val combined = if (root != null && root !== throwable) {
                val rootFormatted = formatSingleThrowable(root)
                if (primary.contains(rootFormatted)) primary else "$primary <- $rootFormatted"
            } else {
                primary
            }
            return combined.take(200)
        }

        private fun formatSingleThrowable(t: Throwable): String {
            val typeName = t.javaClass.simpleName.ifEmpty { "Throwable" }
            val rawMessage = t.message.orEmpty()
                .replace(PATH_PATTERN, "<path>")
                .replace(WHITESPACE_PATTERN, " ")
                .trim()
                .take(140)
            return if (rawMessage.isEmpty()) typeName else "$typeName: $rawMessage"
        }
    }
}
