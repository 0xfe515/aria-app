package com.ariateam.aria.vision

import java.util.Locale
import kotlin.math.ceil

enum class VisionPipelineMode {
    PREPROCESS_OR_INFERENCE,
    PREVIEW_ONLY,
}

/**
 * Hardware timestamp clock domain reported by `CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE`.
 *
 * - [REALTIME_VERIFIED]: `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME` (guaranteed same clock as `SystemClock.elapsedRealtimeNanos()`).
 * - [UNKNOWN_OR_UNVERIFIED]: `SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN` or not verified; `cameraToResultAgeMs` is not reported as valid.
 */
enum class SensorTimestampSource {
    UNKNOWN_OR_UNVERIFIED,
    REALTIME_VERIFIED,
}

data class StagePercentiles(
    val sampleCount: Int,
    val cumulativeCount: Int,
    val p50Ms: Double,
    val p95Ms: Double,
    val meanMs: Double,
    val minMs: Double,
    val maxMs: Double,
) {
    fun formatP50P95(): String {
        val countSuffix = if (cumulativeCount > sampleCount) {
            "n=$sampleCount, cum=$cumulativeCount"
        } else {
            "n=$sampleCount"
        }
        return String.format(Locale.US, "p50 %.2f ms / p95 %.2f ms (%s)", p50Ms, p95Ms, countSuffix)
    }
}

data class VisionTimingSnapshot(
    val mode: VisionPipelineMode = VisionPipelineMode.PREPROCESS_OR_INFERENCE,
    val modelLoaded: Boolean = false,
    val backendLabel: String = "N/A (모델 미적용 · 카메라 전처리 전용)",
    val warmupConfiguredMs: Long = VisionTimingTracker.DEFAULT_WARMUP_MS,
    val warmupCompleted: Boolean = false,
    val warmupElapsedMs: Double = 0.0,
    val steadyElapsedMs: Double = 0.0,
    val warmupFrames: Int = 0,
    val steadyFrames: Int = 0,
    val sensorWarmupFrames: Int = 0,
    val sensorSteadyFrames: Int = 0,
    val observedCaptureMinusAnalyzerDiffSteady: Int = 0,
    val steadyProcessedFps: Double = 0.0,
    val steadySensorFps: Double = 0.0,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val rotationDegrees: Int = 0,
    val previewUprightWidth: Int = 0,
    val previewUprightHeight: Int = 0,
    val cropRect: UprightCropRect? = null,
    val timestampSource: SensorTimestampSource = SensorTimestampSource.UNKNOWN_OR_UNVERIFIED,
    val warmupDeliveryCadenceMs: StagePercentiles? = null,
    val warmupPreprocessMs: StagePercentiles? = null,
    val warmupModelInvokeMs: StagePercentiles? = null,
    val warmupPostprocessMs: StagePercentiles? = null,
    val warmupTotalAnalyzerMs: StagePercentiles? = null,
    val sensorCaptureCadenceMs: StagePercentiles? = null,
    val cameraDeliveryCadenceMs: StagePercentiles? = null,
    val preprocessMs: StagePercentiles? = null,
    val modelInvokeMs: StagePercentiles? = null,
    val postprocessMs: StagePercentiles? = null,
    val totalAnalyzerMs: StagePercentiles? = null,
    val resultPublicationMs: StagePercentiles? = null,
    val cameraToResultAgeMs: StagePercentiles? = null,
) {
    fun formatModelInvokeLabel(): String =
        if (!modelLoaded) "N/A (모델 미적용)" else modelInvokeMs?.formatP50P95() ?: "N/A (샘플 대기)"

    fun formatPostprocessLabel(): String =
        if (!modelLoaded) "N/A (모델 미적용)" else postprocessMs?.formatP50P95() ?: "N/A (샘플 대기)"

    fun formatPreprocessLabel(): String =
        if (mode == VisionPipelineMode.PREVIEW_ONLY) "N/A (미리보기 전용)"
        else preprocessMs?.formatP50P95() ?: warmupPreprocessMs?.formatP50P95() ?: "대기 중"

    fun formatTotalAnalyzerLabel(): String =
        if (mode == VisionPipelineMode.PREVIEW_ONLY) "N/A (미리보기 전용)"
        else totalAnalyzerMs?.formatP50P95() ?: warmupTotalAnalyzerMs?.formatP50P95() ?: "대기 중"

    fun formatDeliveryCadenceLabel(): String =
        if (mode == VisionPipelineMode.PREVIEW_ONLY) {
            sensorCaptureCadenceMs?.formatP50P95() ?: "대기 중"
        } else {
            cameraDeliveryCadenceMs?.formatP50P95() ?: warmupDeliveryCadenceMs?.formatP50P95() ?: "대기 중"
        }

    fun formatPublicationLabel(): String =
        if (mode == VisionPipelineMode.PREVIEW_ONLY) "N/A (미리보기 전용)"
        else resultPublicationMs?.formatP50P95() ?: "대기 중"

    fun formatCameraToResultAgeLabel(): String = when {
        mode == VisionPipelineMode.PREVIEW_ONLY -> "N/A (미리보기 전용)"
        timestampSource != SensorTimestampSource.REALTIME_VERIFIED ->
            "N/A (센서 타임스탬프 REALTIME 미보장)"
        else -> cameraToResultAgeMs?.formatP50P95() ?: "N/A (샘플 대기)"
    }
}

/**
 * Bounded, allocation-free ring buffer for nanosecond duration samples.
 *
 * [StagePercentiles.sampleCount] always equals the active ring window size (`size <= capacity`)
 * from which `p50` and `p95` are computed, while [StagePercentiles.cumulativeCount] reports total samples seen.
 */
internal class BoundedNanoSampleRing(val capacity: Int = VisionTimingTracker.DEFAULT_RING_CAPACITY) {
    private val values = LongArray(capacity)
    private var writeIndex = 0
    var size = 0
        private set
    var totalRecordedCount = 0
        private set

    fun add(nanos: Long) {
        if (nanos < 0L) return
        values[writeIndex] = nanos
        writeIndex = (writeIndex + 1) % capacity
        if (size < capacity) size++
        totalRecordedCount++
    }

    fun clear() {
        writeIndex = 0
        size = 0
        totalRecordedCount = 0
    }

    fun computePercentiles(): StagePercentiles? {
        if (size == 0) return null
        val copy = LongArray(size)
        for (i in 0 until size) {
            copy[i] = values[i]
        }
        copy.sort()
        val p50Index = ((size - 1) * 0.50).toInt().coerceIn(0, size - 1)
        val p95Index = (ceil(size * 0.95).toInt() - 1).coerceIn(0, size - 1)
        var sumNanos = 0.0
        for (v in copy) sumNanos += v.toDouble()
        return StagePercentiles(
            sampleCount = size,
            cumulativeCount = totalRecordedCount,
            p50Ms = copy[p50Index] / NANOS_PER_MS,
            p95Ms = copy[p95Index] / NANOS_PER_MS,
            meanMs = (sumNanos / size) / NANOS_PER_MS,
            minMs = copy.first() / NANOS_PER_MS,
            maxMs = copy.last() / NANOS_PER_MS,
        )
    }

    companion object {
        private const val NANOS_PER_MS = 1_000_000.0
    }
}

/**
 * Tracks warmup vs steady-state camera pipeline stage timings with bounded memory.
 *
 * Strictly separates:
 * - Camera delivery cadence (`cameraDeliveryCadenceMs`) and sensor capture cadence (`sensorCaptureCadenceMs`)
 * - Preprocessing (`preprocessMs`: YUV plane read + rotation + 512:288 crop + scaling + RGB 0..1 float32 fill)
 * - Pure model `Interpreter.run` invocation (`modelInvokeMs`, reported as `null` -> `N/A` when no model is loaded)
 * - Postprocessing (`postprocessMs`: argmax + overlay mask generation, reported as `null` -> `N/A` when no model is loaded)
 * - Total analyzer execution time (`totalAnalyzerMs`)
 * - Result publication delay (`resultPublicationMs`: recorded ONLY when an actual UI state publication completes via [recordPublication])
 * - Camera-timestamp-to-result-publication age (`cameraToResultAgeMs`: recorded ONLY on actual UI state publication when `CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE == REALTIME`; never labeled as display scanout latency).
 */
class VisionTimingTracker(
    val warmupDurationMs: Long = DEFAULT_WARMUP_MS,
    ringCapacity: Int = DEFAULT_RING_CAPACITY,
) {
    private val lock = Any()
    private val warmupDurationNanos = warmupDurationMs.coerceAtLeast(0L) * 1_000_000L

    private var sessionStartNanos = 0L
    private var lastEventNanos = 0L
    private var lastAnalyzerStartNanos = 0L
    private var lastSensorCaptureNanos = 0L

    private var warmupFrames = 0
    private var steadyFrames = 0
    private var sensorWarmupFrames = 0
    private var sensorSteadyFrames = 0

    private var sourceWidth = 0
    private var sourceHeight = 0
    private var rotationDegrees = 0
    private var previewUprightWidth = 0
    private var previewUprightHeight = 0
    private var cropRect: UprightCropRect? = null
    private var timestampSource = SensorTimestampSource.UNKNOWN_OR_UNVERIFIED

    val sensorTimestampSource: SensorTimestampSource
        get() = synchronized(lock) { timestampSource }

    private val warmupDeliveryRing = BoundedNanoSampleRing(ringCapacity)
    private val warmupPreprocessRing = BoundedNanoSampleRing(ringCapacity)
    private val warmupInvokeRing = BoundedNanoSampleRing(ringCapacity)
    private val warmupPostprocessRing = BoundedNanoSampleRing(ringCapacity)
    private val warmupTotalAnalyzerRing = BoundedNanoSampleRing(ringCapacity)

    private val sensorCaptureCadenceRing = BoundedNanoSampleRing(ringCapacity)
    private val steadyDeliveryRing = BoundedNanoSampleRing(ringCapacity)
    private val steadyPreprocessRing = BoundedNanoSampleRing(ringCapacity)
    private val steadyInvokeRing = BoundedNanoSampleRing(ringCapacity)
    private val steadyPostprocessRing = BoundedNanoSampleRing(ringCapacity)
    private val steadyTotalAnalyzerRing = BoundedNanoSampleRing(ringCapacity)
    private val steadyPublicationRing = BoundedNanoSampleRing(ringCapacity)
    private val steadyCameraToResultAgeRing = BoundedNanoSampleRing(ringCapacity)

    fun reset() {
        synchronized(lock) {
            sessionStartNanos = 0L
            lastEventNanos = 0L
            lastAnalyzerStartNanos = 0L
            lastSensorCaptureNanos = 0L
            warmupFrames = 0
            steadyFrames = 0
            sensorWarmupFrames = 0
            sensorSteadyFrames = 0
            sourceWidth = 0
            sourceHeight = 0
            rotationDegrees = 0
            previewUprightWidth = 0
            previewUprightHeight = 0
            cropRect = null
            timestampSource = SensorTimestampSource.UNKNOWN_OR_UNVERIFIED

            warmupDeliveryRing.clear()
            warmupPreprocessRing.clear()
            warmupInvokeRing.clear()
            warmupPostprocessRing.clear()
            warmupTotalAnalyzerRing.clear()

            sensorCaptureCadenceRing.clear()
            steadyDeliveryRing.clear()
            steadyPreprocessRing.clear()
            steadyInvokeRing.clear()
            steadyPostprocessRing.clear()
            steadyTotalAnalyzerRing.clear()
            steadyPublicationRing.clear()
            steadyCameraToResultAgeRing.clear()
        }
    }

    /**
     * Populates negotiated stream resolution, rotation, preview upright dimensions, and hardware timestamp source
     * (used in both Preview-Only and Preprocess/Inference modes).
     */
    fun recordStreamConfiguration(
        width: Int,
        height: Int,
        rotation: Int,
        sensorTimestampSource: SensorTimestampSource,
        previewStreamWidth: Int = width,
        previewStreamHeight: Int = height,
        previewStreamRotation: Int = rotation,
    ) {
        synchronized(lock) {
            timestampSource = sensorTimestampSource
            if (previewStreamWidth > 0 && previewStreamHeight > 0) {
                val normPreviewRot = ((previewStreamRotation % 360) + 360) % 360
                val swap = normPreviewRot == 90 || normPreviewRot == 270
                previewUprightWidth = if (swap) previewStreamHeight else previewStreamWidth
                previewUprightHeight = if (swap) previewStreamWidth else previewStreamHeight
            }
            if (width > 0 && height > 0 && (sourceWidth == 0 || sourceHeight == 0)) {
                sourceWidth = width
                sourceHeight = height
                val normalizedRot = ((rotation % 360) + 360) % 360
                if (normalizedRot in setOf(0, 90, 180, 270)) {
                    rotationDegrees = normalizedRot
                    cropRect = VisionFramePreprocessor.computeUprightCropRect(width, height, normalizedRot)
                }
            }
        }
    }

    /**
     * Records a camera sensor frame capture from `CameraCaptureSession.CaptureCallback.onCaptureCompleted`.
     */
    fun recordSensorCapture(captureElapsedNanos: Long) {
        synchronized(lock) {
            if (sessionStartNanos == 0L) {
                sessionStartNanos = captureElapsedNanos
            }
            val inSteady = (captureElapsedNanos - sessionStartNanos) >= warmupDurationNanos
            if (lastSensorCaptureNanos > 0L && captureElapsedNanos > lastSensorCaptureNanos) {
                val interval = captureElapsedNanos - lastSensorCaptureNanos
                if (inSteady) {
                    sensorCaptureCadenceRing.add(interval)
                }
            }
            lastSensorCaptureNanos = captureElapsedNanos
            lastEventNanos = maxOf(lastEventNanos, captureElapsedNanos)
            if (inSteady) {
                sensorSteadyFrames++
            } else {
                sensorWarmupFrames++
            }
        }
    }

    /**
     * Records a completed `ImageAnalysis.Analyzer` frame (preprocess, optional invoke, optional postprocess, and total analyzer time).
     *
     * When `modelLoaded == false`, `invokeNanos` and `postprocessNanos` MUST be passed as `null`
     * so missing-model runs never record 0 ms inference values.
     * Note: UI publication timing and camera-to-result age are recorded separately in [recordPublication]
     * only when a result is actually published to state.
     */
    fun recordAnalyzerFrame(
        analyzerStartElapsedNanos: Long,
        analyzerEndElapsedNanos: Long,
        preprocessNanos: Long,
        invokeNanos: Long?,
        postprocessNanos: Long?,
        modelLoaded: Boolean,
        frameWidth: Int,
        frameHeight: Int,
        frameRotationDegrees: Int,
        frameCropRect: UprightCropRect,
        sensorTimestampSource: SensorTimestampSource = timestampSource,
    ) {
        synchronized(lock) {
            if (sessionStartNanos == 0L) {
                sessionStartNanos = analyzerStartElapsedNanos
            }
            sourceWidth = frameWidth
            sourceHeight = frameHeight
            rotationDegrees = frameRotationDegrees
            cropRect = frameCropRect
            timestampSource = sensorTimestampSource

            val inSteady = (analyzerStartElapsedNanos - sessionStartNanos) >= warmupDurationNanos

            val deliveryInterval = if (lastAnalyzerStartNanos > 0L && analyzerStartElapsedNanos > lastAnalyzerStartNanos) {
                analyzerStartElapsedNanos - lastAnalyzerStartNanos
            } else {
                null
            }
            lastAnalyzerStartNanos = analyzerStartElapsedNanos
            lastEventNanos = maxOf(lastEventNanos, analyzerEndElapsedNanos)

            val totalAnalyzerNanos = (analyzerEndElapsedNanos - analyzerStartElapsedNanos).coerceAtLeast(0L)

            if (!inSteady) {
                warmupFrames++
                if (deliveryInterval != null) warmupDeliveryRing.add(deliveryInterval)
                warmupPreprocessRing.add(preprocessNanos)
                if (modelLoaded && invokeNanos != null) warmupInvokeRing.add(invokeNanos)
                if (modelLoaded && postprocessNanos != null) warmupPostprocessRing.add(postprocessNanos)
                warmupTotalAnalyzerRing.add(totalAnalyzerNanos)
            } else {
                steadyFrames++
                if (deliveryInterval != null) {
                    steadyDeliveryRing.add(deliveryInterval)
                }
                steadyPreprocessRing.add(preprocessNanos)
                if (modelLoaded && invokeNanos != null) steadyInvokeRing.add(invokeNanos)
                if (modelLoaded && postprocessNanos != null) steadyPostprocessRing.add(postprocessNanos)
                steadyTotalAnalyzerRing.add(totalAnalyzerNanos)
            }
        }
    }

    /**
     * Records actual UI/state publication delay and camera-to-result age AFTER the state update has occurred.
     * Called only for frames that actually publish a state update.
     */
    fun recordPublication(
        analyzerStartElapsedNanos: Long,
        analyzerEndElapsedNanos: Long,
        publishCompletedElapsedNanos: Long,
        imageTimestampNanos: Long,
        sensorTimestampSource: SensorTimestampSource = timestampSource,
    ) {
        synchronized(lock) {
            if (sessionStartNanos == 0L) return
            timestampSource = sensorTimestampSource
            lastEventNanos = maxOf(lastEventNanos, publishCompletedElapsedNanos)
            val inSteady = (analyzerStartElapsedNanos - sessionStartNanos) >= warmupDurationNanos
            if (!inSteady) return

            val publicationNanos = (publishCompletedElapsedNanos - analyzerEndElapsedNanos).coerceAtLeast(0L)
            steadyPublicationRing.add(publicationNanos)

            val ageNanos = publishCompletedElapsedNanos - imageTimestampNanos
            if (timestampSource == SensorTimestampSource.REALTIME_VERIFIED &&
                imageTimestampNanos > 0L &&
                ageNanos in 0L..MAX_VALID_CLOCK_AGE_NANOS
            ) {
                steadyCameraToResultAgeRing.add(ageNanos)
            }
        }
    }

    /**
     * Computes a timing snapshot at observation time [nowElapsedNanos].
     *
     * Using observation wall time (`maxOf(nowElapsedNanos, lastEventNanos)`) for the steady-window
     * denominator ensures that if frame delivery stalls, reported FPS decays with wall time
     * rather than freezing at a stale high value.
     */
    fun snapshot(
        mode: VisionPipelineMode,
        modelLoaded: Boolean,
        backendLabel: String,
        nowElapsedNanos: Long = lastEventNanos,
    ): VisionTimingSnapshot {
        synchronized(lock) {
            val observationEndNanos = maxOf(nowElapsedNanos, lastEventNanos)
            val totalElapsedNanos = if (sessionStartNanos > 0L && observationEndNanos > sessionStartNanos) {
                observationEndNanos - sessionStartNanos
            } else {
                0L
            }
            val warmupDone = sessionStartNanos > 0L && totalElapsedNanos >= warmupDurationNanos
            val warmupElapsedMs = (totalElapsedNanos.coerceAtMost(warmupDurationNanos)) / 1_000_000.0
            val steadyWindowStartNanos = sessionStartNanos + warmupDurationNanos
            val steadyElapsedMs = if (warmupDone && observationEndNanos > steadyWindowStartNanos) {
                (observationEndNanos - steadyWindowStartNanos) / 1_000_000.0
            } else {
                0.0
            }

            val steadyFps = if (steadyElapsedMs > 0.0 && steadyFrames > 0) {
                steadyFrames * 1000.0 / steadyElapsedMs
            } else {
                0.0
            }
            val sensorFps = if (steadyElapsedMs > 0.0 && sensorSteadyFrames > 0) {
                sensorSteadyFrames * 1000.0 / steadyElapsedMs
            } else {
                0.0
            }
            val observedCaptureMinusAnalyzerDiff =
                if (mode == VisionPipelineMode.PREPROCESS_OR_INFERENCE && sensorSteadyFrames > steadyFrames) {
                    sensorSteadyFrames - steadyFrames
                } else {
                    0
                }

            return VisionTimingSnapshot(
                mode = mode,
                modelLoaded = modelLoaded,
                backendLabel = backendLabel,
                warmupConfiguredMs = warmupDurationMs,
                warmupCompleted = warmupDone,
                warmupElapsedMs = warmupElapsedMs,
                steadyElapsedMs = steadyElapsedMs,
                warmupFrames = warmupFrames,
                steadyFrames = steadyFrames,
                sensorWarmupFrames = sensorWarmupFrames,
                sensorSteadyFrames = sensorSteadyFrames,
                observedCaptureMinusAnalyzerDiffSteady = observedCaptureMinusAnalyzerDiff,
                steadyProcessedFps = steadyFps,
                steadySensorFps = sensorFps,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                rotationDegrees = rotationDegrees,
                previewUprightWidth = previewUprightWidth,
                previewUprightHeight = previewUprightHeight,
                cropRect = cropRect,
                timestampSource = timestampSource,
                warmupDeliveryCadenceMs = warmupDeliveryRing.computePercentiles(),
                warmupPreprocessMs = warmupPreprocessRing.computePercentiles(),
                warmupModelInvokeMs = if (modelLoaded) warmupInvokeRing.computePercentiles() else null,
                warmupPostprocessMs = if (modelLoaded) warmupPostprocessRing.computePercentiles() else null,
                warmupTotalAnalyzerMs = warmupTotalAnalyzerRing.computePercentiles(),
                sensorCaptureCadenceMs = sensorCaptureCadenceRing.computePercentiles(),
                cameraDeliveryCadenceMs = steadyDeliveryRing.computePercentiles(),
                preprocessMs = if (mode == VisionPipelineMode.PREVIEW_ONLY) null else steadyPreprocessRing.computePercentiles(),
                modelInvokeMs = if (modelLoaded && mode != VisionPipelineMode.PREVIEW_ONLY) steadyInvokeRing.computePercentiles() else null,
                postprocessMs = if (modelLoaded && mode != VisionPipelineMode.PREVIEW_ONLY) steadyPostprocessRing.computePercentiles() else null,
                totalAnalyzerMs = if (mode == VisionPipelineMode.PREVIEW_ONLY) null else steadyTotalAnalyzerRing.computePercentiles(),
                resultPublicationMs = if (mode == VisionPipelineMode.PREVIEW_ONLY) null else steadyPublicationRing.computePercentiles(),
                cameraToResultAgeMs = if (timestampSource == SensorTimestampSource.REALTIME_VERIFIED && mode != VisionPipelineMode.PREVIEW_ONLY) {
                    steadyCameraToResultAgeRing.computePercentiles()
                } else {
                    null
                },
            )
        }
    }

    companion object {
        const val DEFAULT_WARMUP_MS = 5_000L
        const val DEFAULT_RING_CAPACITY = 1_024
        private const val MAX_VALID_CLOCK_AGE_NANOS = 5_000_000_000L
    }
}
