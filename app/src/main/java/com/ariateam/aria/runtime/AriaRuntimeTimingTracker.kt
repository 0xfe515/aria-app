package com.ariateam.aria.runtime

import com.ariateam.aria.vision.StagePercentiles
import kotlin.math.ceil

/**
 * Bounded stage timings across the 8 critical runtime pipeline stages:
 * start, recognition, analyzer, preprocess, invoke, post, UI, alerts.
 */
object AriaRuntimeTimingTracker {
    private const val RING_SIZE = 100
    private val lock = Any()

    private class BoundedSampleRing(private val capacity: Int = RING_SIZE) {
        private val samples = DoubleArray(capacity)
        private var head = 0
        private var count = 0
        private var cumulative = 0

        fun record(value: Double) {
            samples[head] = value
            head = (head + 1) % capacity
            if (count < capacity) count++
            cumulative++
        }

        fun snapshot(): StagePercentiles? {
            if (count == 0) return null
            val copy = samples.copyOf(count).apply { sort() }
            val min = copy.first()
            val max = copy.last()
            val mean = copy.average()
            val p50 = percentile(copy, 0.50)
            val p95 = percentile(copy, 0.95)
            return StagePercentiles(
                sampleCount = count,
                cumulativeCount = cumulative,
                p50Ms = p50,
                p95Ms = p95,
                meanMs = mean,
                minMs = min,
                maxMs = max,
            )
        }

        private fun percentile(sorted: DoubleArray, rank: Double): Double {
            if (sorted.isEmpty()) return 0.0
            val index = (ceil(rank * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)
            return sorted[index]
        }

        fun reset() {
            head = 0
            count = 0
            cumulative = 0
        }
    }

    private val startRing = BoundedSampleRing()
    private val recognitionRing = BoundedSampleRing()
    private val analyzerRing = BoundedSampleRing()
    private val preprocessRing = BoundedSampleRing()
    private val invokeRing = BoundedSampleRing()
    private val postRing = BoundedSampleRing()
    private val uiRing = BoundedSampleRing()
    private val alertsRing = BoundedSampleRing()

    fun recordStart(ms: Double) = synchronized(lock) { startRing.record(ms) }
    fun recordRecognition(ms: Double) = synchronized(lock) { recognitionRing.record(ms) }
    fun recordAnalyzer(ms: Double) = synchronized(lock) { analyzerRing.record(ms) }
    fun recordPreprocess(ms: Double) = synchronized(lock) { preprocessRing.record(ms) }
    fun recordInvoke(ms: Double) = synchronized(lock) { invokeRing.record(ms) }
    fun recordPost(ms: Double) = synchronized(lock) { postRing.record(ms) }
    fun recordUi(ms: Double) = synchronized(lock) { uiRing.record(ms) }
    fun recordAlerts(ms: Double) = synchronized(lock) { alertsRing.record(ms) }

    fun reset() = synchronized(lock) {
        startRing.reset()
        recognitionRing.reset()
        analyzerRing.reset()
        preprocessRing.reset()
        invokeRing.reset()
        postRing.reset()
        uiRing.reset()
        alertsRing.reset()
    }

    data class RuntimeStageTimingsSnapshot(
        val start: StagePercentiles?,
        val recognition: StagePercentiles?,
        val analyzer: StagePercentiles?,
        val preprocess: StagePercentiles?,
        val invoke: StagePercentiles?,
        val post: StagePercentiles?,
        val ui: StagePercentiles?,
        val alerts: StagePercentiles?,
    )

    fun snapshot(): RuntimeStageTimingsSnapshot = synchronized(lock) {
        RuntimeStageTimingsSnapshot(
            start = startRing.snapshot(),
            recognition = recognitionRing.snapshot(),
            analyzer = analyzerRing.snapshot(),
            preprocess = preprocessRing.snapshot(),
            invoke = invokeRing.snapshot(),
            post = postRing.snapshot(),
            ui = uiRing.snapshot(),
            alerts = alertsRing.snapshot(),
        )
    }
}
