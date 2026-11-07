package com.ariateam.aria.vision.od

import com.ariateam.aria.vision.UprightCropRect
import com.ariateam.aria.vision.VisionPreprocessingStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private fun odTestTransform(): UprightCropRect = UprightCropRect(
    bufferWidth = 640,
    bufferHeight = 480,
    rotationDegrees = 90,
    uprightWidth = 480,
    uprightHeight = 640,
    left = 0,
    top = 0,
    width = 480,
    height = 640,
    strategy = VisionPreprocessingStrategy.FULL_FRAME_UPRIGHT_LETTERBOX,
    contentLeft = 0,
    contentTop = 80,
    contentWidth = 288,
    contentHeight = 512,
    isRotatedClockwise = true,
)

private fun odTestFrameBuffer(fill: Float = 0.5f): FloatBuffer =
    FloatBuffer.wrap(FloatArray(com.ariateam.aria.vision.VisionContract.INPUT_FLOATS) { fill })

class AuxiliaryObjectDetectionPipelineTest {

    private class FakeClock(var nowNanos: Long = 1_000_000_000L) {
        fun advanceMillis(millis: Long) {
            nowNanos += millis * 1_000_000L
        }
    }
    private class ManualWorker : OdWorkerExecutor {
        private val queue = ArrayDeque<Runnable>()
        @Volatile
        var shutdownCalls = 0
        var executed = 0
        var rejectNext = false

        override fun execute(command: Runnable): Boolean = synchronized(this) {
            if (rejectNext) {
                rejectNext = false
                false
            } else {
                queue.addLast(command)
                true
            }
        }

        override fun shutdown() {
            shutdownCalls++
        }

        fun runNext() {
            val command = synchronized(this) {
                check(queue.isNotEmpty()) { "worker queue empty" }
                queue.removeFirst()
            }
            executed++
            command.run()
        }

        fun runAll() {
            while (hasQueued()) runNext()
        }

        fun queuedCount(): Int = synchronized(this) { queue.size }

        private fun hasQueued(): Boolean = synchronized(this) { queue.isNotEmpty() }
    }

    private class FakeEngine(
        private val clock: FakeClock,
        private val inferenceMillis: Long = 5L,
        private val results: MutableList<List<DetectedObject>> = mutableListOf(),
        private val throwOnDetect: Boolean = false,
        private val detectEntered: java.util.concurrent.CountDownLatch? = null,
        private val detectRelease: java.util.concurrent.CountDownLatch? = null,
        failureAfterCalls: Int = -1,
        private val closeEntered: CountDownLatch? = null,
        private val closeRelease: CountDownLatch? = null,
    ) : ObjectDetectorEngine {
        val receivedFrames = mutableListOf<FloatArray>()
        val receivedTransforms = mutableListOf<UprightCropRect>()
        var closeCalls = 0
        var closeThreadName: String? = null
        private var remainingCallsBeforeFailure = failureAfterCalls

        fun create(): ObjectDetectorEngine = this

        override fun detect(inputRgb: FloatArray, transform: UprightCropRect): List<DetectedObject> {
            detectEntered?.countDown()
            detectRelease?.await()
            if (throwOnDetect) throw IllegalStateException("detect boom")
            if (remainingCallsBeforeFailure == 0) throw IllegalStateException("detect boom later")
            if (remainingCallsBeforeFailure > 0) remainingCallsBeforeFailure--
            receivedFrames += inputRgb.copyOf()
            receivedTransforms += transform
            clock.advanceMillis(inferenceMillis)
            return if (results.isEmpty()) emptyList() else results.removeAt(0)
        }

        override fun close() {
            closeEntered?.countDown()
            closeRelease?.await()
            closeCalls++
            closeThreadName = Thread.currentThread().name
        }
    }

    private fun detection(
        classId: Int = 2,
        score: Float = 0.9f,
        left: Float = 0.35f,
        top: Float = 0.5f,
        right: Float = 0.55f,
        bottom: Float = 0.9f,
    ) = DetectedObject(classId, "car", score, left, top, right, bottom)

    private class Harness(
        val clock: FakeClock = FakeClock(),
        val worker: ManualWorker = ManualWorker(),
        val engine: FakeEngine = FakeEngine(clock = clock),
        config: OdSidecarConfig = OdSidecarConfig(),
    ) {
        val pipeline = AuxiliaryObjectDetectionPipeline(
            engineFactory = { engine.create() },
            worker = worker,
            clockNanos = { clock.nowNanos },
            config = config,
        )
    }

    private fun Harness.submit(
        captureNanos: Long = clock.nowNanos,
        transform: UprightCropRect = odTestTransform(),
        buffer: FloatBuffer = odTestFrameBuffer(),
    ): OdSubmitOutcome = pipeline.trySubmit(
        rgb = buffer,
        transform = transform,
        rotationDegrees = 90,
        captureTimestampNanos = captureNanos,
        arrivalElapsedNanos = clock.nowNanos,
        nowElapsedNanos = clock.nowNanos,
    )

    private fun AuxiliaryObjectDetectionPipeline.start(token: Long, clock: FakeClock) {
        setEnabled(true)
        onSessionStarted(token, clock.nowNanos)
    }

    @Test
    fun rateGateDefaultsToFiveHzAndCountsRateLimitedAttempts() {
        val harness = Harness()
        val pipeline = harness.pipeline
        pipeline.start(1L, harness.clock)

        assertEquals(OdSubmitOutcome.QUEUED, harness.submit())
        harness.clock.advanceMillis(100)
        assertEquals(OdSubmitOutcome.RATE_LIMITED, harness.submit())
        harness.clock.advanceMillis(120)
        assertEquals(OdSubmitOutcome.QUEUED, harness.submit())

        val telemetry = pipeline.telemetry(harness.clock.nowNanos)
        assertEquals(OdSidecarStatus.BUSY, telemetry.status)
        assertEquals(5, telemetry.rateHz)
        assertEquals(2L, telemetry.submitted)
        assertEquals(1L, telemetry.rateLimited)
    }

    @Test
    fun backpressureKeepsOneInFlightAndReplacesOnlyTheLatestPendingFrame() {
        // Freshness is relaxed here so the scheduling assertions are not masked by stale-frame drops.
        val harness = Harness(config = OdSidecarConfig(maxResultAgeNanos = 2_000_000_000L))
        val pipeline = harness.pipeline
        pipeline.start(1L, harness.clock)

        assertEquals(OdSubmitOutcome.QUEUED, harness.submit())
        assertEquals(1, harness.worker.queuedCount())

        harness.clock.advanceMillis(200)
        assertEquals(OdSubmitOutcome.QUEUED, harness.submit())
        assertEquals("a busy worker must not enqueue a second task", 1, harness.worker.queuedCount())

        harness.clock.advanceMillis(200)
        assertEquals(OdSubmitOutcome.REPLACED_PENDING, harness.submit())
        assertEquals(1, harness.worker.queuedCount())

        harness.worker.runAll()
        assertEquals(2, harness.engine.receivedFrames.size)

        val telemetry = pipeline.telemetry(harness.clock.nowNanos)
        assertEquals(3L, telemetry.submitted)
        assertEquals(1L, telemetry.replaced)
        assertEquals(2L, telemetry.busy)
        assertEquals(2L, telemetry.completed)
    }

    @Test
    fun mutableSourceBufferIsCopiedAtAcceptanceNotRetained() {
        val harness = Harness()
        harness.pipeline.start(1L, harness.clock)
        val source = FloatArray(com.ariateam.aria.vision.VisionContract.INPUT_FLOATS) { 0.25f }
        val buffer = FloatBuffer.wrap(source)

        assertEquals(OdSubmitOutcome.QUEUED, harness.submit(buffer = buffer))
        java.util.Arrays.fill(source, 0.75f)
        harness.worker.runAll()

        val received = harness.engine.receivedFrames.single()
        assertEquals(0.25f, received[0], 0.0f)
        assertEquals(0.25f, received[received.size - 1], 0.0f)
    }

    @Test
    fun replacementFrameDoesNotOverwriteTheInFlightSnapshot() {
        val harness = Harness()
        harness.pipeline.start(1L, harness.clock)

        assertEquals(OdSubmitOutcome.QUEUED, harness.submit(buffer = odTestFrameBuffer(0.1f)))
        harness.clock.advanceMillis(200)
        assertEquals(OdSubmitOutcome.QUEUED, harness.submit(buffer = odTestFrameBuffer(0.9f)))

        harness.worker.runNext()
        harness.worker.runNext()
        val received = harness.engine.receivedFrames
        assertEquals(2, received.size)
        assertEquals(0.1f, received[0][0], 0.0f)
        assertEquals(0.9f, received[1][0], 0.0f)
    }

    @Test
    fun factoryFailureDegradesSidecarOnlyAndRetriesOnNewSession() {
        val clock = FakeClock()
        val worker = ManualWorker()
        var shouldFail = true
        val engine = FakeEngine(clock = clock)
        val pipeline = AuxiliaryObjectDetectionPipeline(
            engineFactory = {
                if (shouldFail) throw IllegalStateException("cannot init") else engine.create()
            },
            worker = worker,
            clockNanos = { clock.nowNanos },
        )
        pipeline.setEnabled(true)
        pipeline.onSessionStarted(1L, clock.nowNanos)

        pipeline.trySubmit(odTestFrameBuffer(), odTestTransform(), 90, clock.nowNanos, clock.nowNanos, clock.nowNanos)
        worker.runAll()

        var telemetry = pipeline.telemetry(clock.nowNanos)
        assertEquals(OdSidecarStatus.UNAVAILABLE, telemetry.status)
        assertEquals(OdBackoffReason.ENGINE_ERROR, telemetry.backoffReason)
        assertEquals(1L, telemetry.errors)
        assertNull(pipeline.latestEvidence())

        shouldFail = false
        clock.advanceMillis(1_000)
        pipeline.onSessionStarted(2L, clock.nowNanos)
        pipeline.trySubmit(odTestFrameBuffer(), odTestTransform(), 90, clock.nowNanos, clock.nowNanos, clock.nowNanos)
        worker.runAll()

        telemetry = pipeline.telemetry(clock.nowNanos)
        assertNotNull(pipeline.latestEvidence())
        assertEquals(1L, telemetry.completed)
        assertEquals(2L, telemetry.sessionToken)
    }

    @Test
    fun invokeFailureIsContainedAndReleasesTheEngine() {
        val clock = FakeClock()
        val worker = ManualWorker()
        val engine = FakeEngine(clock = clock, throwOnDetect = true)
        val pipeline = AuxiliaryObjectDetectionPipeline(
            engineFactory = { engine.create() },
            worker = worker,
            clockNanos = { clock.nowNanos },
        )
        pipeline.setEnabled(true)
        pipeline.onSessionStarted(1L, clock.nowNanos)

        pipeline.trySubmit(odTestFrameBuffer(), odTestTransform(), 90, clock.nowNanos, clock.nowNanos, clock.nowNanos)
        worker.runAll()

        val telemetry = pipeline.telemetry(clock.nowNanos)
        assertEquals(OdSidecarStatus.UNAVAILABLE, telemetry.status)
        assertEquals(1L, telemetry.errors)
        assertEquals(1, engine.closeCalls)
        assertNull(pipeline.latestEvidence())
    }

    @Test
    fun completionOlderThanFreshnessBoundIsDroppedWithCounters() {
        val clock = FakeClock()
        val worker = ManualWorker()
        val engine = FakeEngine(clock = clock, inferenceMillis = 260L)
        val pipeline = AuxiliaryObjectDetectionPipeline(
            engineFactory = { engine.create() },
            worker = worker,
            clockNanos = { clock.nowNanos },
        )
        pipeline.setEnabled(true)
        pipeline.onSessionStarted(1L, clock.nowNanos)

        pipeline.trySubmit(odTestFrameBuffer(), odTestTransform(), 90, clock.nowNanos, clock.nowNanos, clock.nowNanos)
        worker.runAll()

        assertNull(pipeline.latestEvidence())
        val telemetry = pipeline.telemetry(clock.nowNanos)
        assertEquals(1L, telemetry.staleDropped)
        assertEquals(0L, telemetry.completed)
    }

    @Test
    fun resultAgeEndToEndAndDetectionCountAreExposed() {
        val clock = FakeClock()
        val worker = ManualWorker()
        val engine = FakeEngine(
            clock = clock,
            inferenceMillis = 30L,
            results = mutableListOf(listOf(detection())),
        )
        val pipeline = AuxiliaryObjectDetectionPipeline(
            engineFactory = { engine.create() },
            worker = worker,
            clockNanos = { clock.nowNanos },
        )
        pipeline.setEnabled(true)
        pipeline.onSessionStarted(1L, clock.nowNanos)

        pipeline.trySubmit(odTestFrameBuffer(), odTestTransform(), 90, clock.nowNanos, clock.nowNanos, clock.nowNanos)
        worker.runAll()
        clock.advanceMillis(20)

        val evidence = pipeline.latestEvidence()
        assertNotNull(evidence)
        assertEquals(30_000_000L, evidence!!.resultAgeNanos)
        val telemetry = pipeline.telemetry(clock.nowNanos)
        assertEquals(30L, telemetry.endToEndMs)
        assertEquals("age must run from original frame arrival (30ms inference + 20ms wait)", 50L, telemetry.resultAgeMs)
        assertEquals(1, telemetry.detectionCount)
        assertTrue(telemetry.structuredLogLine().contains("rateHz=5"))
    }

    @Test
    fun orderingAndInvalidCaptureAreRejected() {
        val harness = Harness()
        harness.pipeline.start(1L, harness.clock)
        val capture = harness.clock.nowNanos
        assertEquals(OdSubmitOutcome.INVALID, harness.submit(captureNanos = 0L))
        assertEquals(OdSubmitOutcome.QUEUED, harness.submit(captureNanos = capture))
        harness.worker.runAll()
        harness.clock.advanceMillis(200)
        assertEquals(OdSubmitOutcome.ORDERING_REJECTED, harness.submit(captureNanos = capture))

        val telemetry = harness.pipeline.telemetry(harness.clock.nowNanos)
        assertEquals(1L, telemetry.invalidDropped)
        assertEquals(1L, telemetry.orderingRejected)
    }

    @Test
    fun sessionStopInvalidatesInFlightResultWithoutPublishing() {
        val harness = Harness()
        harness.pipeline.start(1L, harness.clock)
        assertEquals(OdSubmitOutcome.QUEUED, harness.submit())

        harness.pipeline.onSessionStopped("test stop")
        harness.worker.runAll()

        assertNull(harness.pipeline.latestEvidence())
        assertEquals(0L, harness.pipeline.telemetry(harness.clock.nowNanos).completed)
    }

    @Test
    fun closeSuppressesLateResultsAndShutsTheWorkerOnce() {
        val harness = Harness()
        harness.pipeline.start(1L, harness.clock)
        assertEquals(OdSubmitOutcome.QUEUED, harness.submit())

        harness.pipeline.close()
        harness.worker.runAll()
        assertNull(harness.pipeline.latestEvidence())
        assertEquals(1, harness.worker.shutdownCalls)
        assertEquals(OdSidecarStatus.CLOSED, harness.pipeline.telemetry(harness.clock.nowNanos).status)

        harness.pipeline.close()
        assertEquals(1, harness.worker.shutdownCalls)
        assertEquals(OdSubmitOutcome.CLOSED, harness.submit())
    }

    @Test
    fun disablingInvalidatesEvidenceAndReleasesEngine() {
        val harness = Harness()
        harness.pipeline.start(1L, harness.clock)
        assertEquals(OdSubmitOutcome.QUEUED, harness.submit())
        harness.worker.runAll()
        assertNotNull(harness.pipeline.latestEvidence())

        harness.pipeline.setEnabled(false)
        harness.worker.runAll()
        assertNull(harness.pipeline.latestEvidence())
        assertEquals(1, harness.engine.closeCalls)
        assertEquals(OdSubmitOutcome.INACTIVE, harness.submit())
    }

    @Test
    fun sustainedPrimaryFrameOverrunStagesDownAndSuspendsTheSession() {
        val clock = FakeClock()
        val worker = ManualWorker()
        val engine = FakeEngine(clock = clock)
        val config = OdSidecarConfig(
            primaryWarmupFrames = 2,
            primaryWarmupNanos = 0L,
            primaryOverFramesForReduced = 3,
            primaryOverFramesForSuspend = 6,
            primaryHealthyFramesForRecovery = 4,
        )
        val pipeline = AuxiliaryObjectDetectionPipeline(
            engineFactory = { engine.create() },
            worker = worker,
            clockNanos = { clock.nowNanos },
            config = config,
        )
        pipeline.setEnabled(true)
        pipeline.onSessionStarted(1L, clock.nowNanos)

        repeat(2) { pipeline.onPrimaryAnalyzerFrame(80_000_000L, clock.nowNanos) }
        assertEquals("startup warmup must be ignored", 5, pipeline.telemetry(clock.nowNanos).rateHz)

        repeat(3) {
            clock.nowNanos += 50_000_000L
            pipeline.onPrimaryAnalyzerFrame(80_000_000L, clock.nowNanos)
        }
        var telemetry = pipeline.telemetry(clock.nowNanos)
        assertEquals(2, telemetry.rateHz)
        assertEquals(OdBackoffReason.PRIMARY_FRAME_OVER_BUDGET, telemetry.backoffReason)

        repeat(3) {
            clock.nowNanos += 50_000_000L
            pipeline.onPrimaryAnalyzerFrame(80_000_000L, clock.nowNanos)
        }
        telemetry = pipeline.telemetry(clock.nowNanos)
        assertEquals(0, telemetry.rateHz)
        assertEquals(OdSidecarStatus.SUSPENDED, telemetry.status)
        assertEquals(OdSubmitOutcome.SUSPENDED, harnessSubmit(pipeline, clock))

        clock.nowNanos += 1_000_000_000L
        pipeline.onSessionStarted(2L, clock.nowNanos)
        assertEquals(5, pipeline.telemetry(clock.nowNanos).rateHz)
    }

    @Test
    fun healthyPrimaryFramesRecoverReducedRate() {
        val clock = FakeClock()
        val worker = ManualWorker()
        val engine = FakeEngine(clock = clock)
        val config = OdSidecarConfig(
            primaryWarmupFrames = 0,
            primaryWarmupNanos = 0L,
            primaryOverFramesForReduced = 2,
            primaryOverFramesForSuspend = 100,
            primaryHealthyFramesForRecovery = 3,
        )
        val pipeline = AuxiliaryObjectDetectionPipeline(
            engineFactory = { engine.create() },
            worker = worker,
            clockNanos = { clock.nowNanos },
            config = config,
        )
        pipeline.setEnabled(true)
        pipeline.onSessionStarted(1L, clock.nowNanos)

        repeat(2) { pipeline.onPrimaryAnalyzerFrame(70_000_000L, clock.nowNanos) }
        assertEquals(2, pipeline.telemetry(clock.nowNanos).rateHz)

        repeat(3) { pipeline.onPrimaryAnalyzerFrame(40_000_000L, clock.nowNanos) }
        val telemetry = pipeline.telemetry(clock.nowNanos)
        assertEquals(5, telemetry.rateHz)
        assertEquals(OdBackoffReason.NONE, telemetry.backoffReason)
    }

    @Test
    fun persistentlySlowSidecarInferenceStagesDown() {
        val clock = FakeClock()
        val worker = ManualWorker()
        val engine = FakeEngine(clock = clock, inferenceMillis = 300L)
        val config = OdSidecarConfig(
            sidecarOverCompletionsForReduced = 2,
            sidecarOverCompletionsForSuspend = 4,
        )
        val pipeline = AuxiliaryObjectDetectionPipeline(
            engineFactory = { engine.create() },
            worker = worker,
            clockNanos = { clock.nowNanos },
            config = config,
        )
        pipeline.setEnabled(true)
        pipeline.onSessionStarted(1L, clock.nowNanos)

        var capture = clock.nowNanos
        repeat(2) {
            pipeline.trySubmit(odTestFrameBuffer(), odTestTransform(), 90, capture, clock.nowNanos, clock.nowNanos)
            worker.runAll()
            clock.advanceMillis(500)
            capture = clock.nowNanos
        }
        var telemetry = pipeline.telemetry(clock.nowNanos)
        assertEquals(2, telemetry.rateHz)
        assertEquals(OdBackoffReason.SIDECAR_OVER_BUDGET, telemetry.backoffReason)

        repeat(2) {
            pipeline.trySubmit(odTestFrameBuffer(), odTestTransform(), 90, capture, clock.nowNanos, clock.nowNanos)
            worker.runAll()
            clock.advanceMillis(500)
            capture = clock.nowNanos
        }
        telemetry = pipeline.telemetry(clock.nowNanos)
        assertEquals(OdSidecarStatus.SUSPENDED, telemetry.status)
    }

    @Test
    fun futureArrivalJobIsSkippedWithoutInference() {
        val harness = Harness()
        harness.pipeline.start(1L, harness.clock)
        val now = harness.clock.nowNanos

        val outcome = harness.pipeline.trySubmit(
            rgb = odTestFrameBuffer(),
            transform = odTestTransform(),
            rotationDegrees = 90,
            captureTimestampNanos = now,
            arrivalElapsedNanos = now + 100_000_000L,
            nowElapsedNanos = now,
        )
        assertEquals(OdSubmitOutcome.QUEUED, outcome)
        harness.worker.runAll()

        assertTrue("negative/future age must not run inference", harness.engine.receivedFrames.isEmpty())
        assertNull(harness.pipeline.latestEvidence())
        assertEquals(1L, harness.pipeline.telemetry(now).staleDropped)
    }

    @Test
    fun suspendedGovernorDropsQueuedJobBeforeInference() {
        val clock = FakeClock()
        val worker = ManualWorker()
        val engine = FakeEngine(clock = clock)
        val pipeline = AuxiliaryObjectDetectionPipeline(
            engineFactory = { engine },
            worker = worker,
            clockNanos = { clock.nowNanos },
            config = OdSidecarConfig(
                primaryWarmupFrames = 0,
                primaryWarmupNanos = 0L,
                primaryOverFramesForReduced = 1,
                primaryOverFramesForSuspend = 2,
            ),
        )
        pipeline.setEnabled(true)
        pipeline.onSessionStarted(1L, clock.nowNanos)
        pipeline.trySubmit(odTestFrameBuffer(), odTestTransform(), 90, clock.nowNanos, clock.nowNanos, clock.nowNanos)

        pipeline.onPrimaryAnalyzerFrame(80_000_000L, clock.nowNanos)
        pipeline.onPrimaryAnalyzerFrame(80_000_000L, clock.nowNanos)
        assertEquals(OdSidecarStatus.SUSPENDED, pipeline.telemetry(clock.nowNanos).status)

        worker.runAll()
        assertTrue("a suspended session must not run queued inference", engine.receivedFrames.isEmpty())
        assertNull(pipeline.latestEvidence())
        assertEquals(1L, pipeline.telemetry(clock.nowNanos).staleDropped)
    }

    @Test
    fun nativeFailureClearsEvidenceAndRejectsFurtherSubmissionsUntilReset() {
        val clock = FakeClock()
        val worker = ManualWorker()
        val engine = FakeEngine(clock = clock, failureAfterCalls = 1)
        val pipeline = AuxiliaryObjectDetectionPipeline(
            engineFactory = { engine },
            worker = worker,
            clockNanos = { clock.nowNanos },
        )
        pipeline.setEnabled(true)
        pipeline.onSessionStarted(1L, clock.nowNanos)

        pipeline.trySubmit(odTestFrameBuffer(), odTestTransform(), 90, clock.nowNanos, clock.nowNanos, clock.nowNanos)
        worker.runAll()
        assertNotNull(pipeline.latestEvidence())

        clock.advanceMillis(200)
        pipeline.trySubmit(odTestFrameBuffer(), odTestTransform(), 90, clock.nowNanos, clock.nowNanos, clock.nowNanos)
        worker.runAll()

        assertNull("native failure must clear published evidence", pipeline.latestEvidence())
        var telemetry = pipeline.telemetry(clock.nowNanos)
        assertEquals(OdSidecarStatus.UNAVAILABLE, telemetry.status)
        assertEquals(1L, telemetry.errors)
        assertEquals(2L, telemetry.submitted)

        clock.advanceMillis(200)
        assertEquals(OdSubmitOutcome.UNAVAILABLE, pipeline.trySubmit(odTestFrameBuffer(), odTestTransform(), 90, clock.nowNanos, clock.nowNanos, clock.nowNanos))
        telemetry = pipeline.telemetry(clock.nowNanos)
        assertEquals("no pointless copies while the engine is unavailable", 2L, telemetry.submitted)
    }

    @Test
    fun slowEngineCloseDoesNotBlockTheAnalyzerProducer() {
        val clock = FakeClock()
        val worker = ManualWorker()
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val engine = FakeEngine(clock = clock, closeEntered = closeEntered, closeRelease = releaseClose)
        val pipeline = AuxiliaryObjectDetectionPipeline(
            engineFactory = { engine },
            worker = worker,
            clockNanos = { clock.nowNanos },
        )
        pipeline.setEnabled(true)
        pipeline.onSessionStarted(1L, clock.nowNanos)
        pipeline.trySubmit(odTestFrameBuffer(), odTestTransform(), 90, clock.nowNanos, clock.nowNanos, clock.nowNanos)
        worker.runAll()

        pipeline.onSessionStopped("test")
        val closer = thread(name = "od-close-worker") { worker.runNext() }
        assertTrue("close must start on the worker", closeEntered.await(2, TimeUnit.SECONDS))

        var outcome: OdSubmitOutcome? = null
        val producer = thread { outcome = harnessSubmit(pipeline, clock) }
        producer.join(1_000)
        assertFalse("producer must not block on interpreter close", producer.isAlive)

        releaseClose.countDown()
        closer.join(2_000)
        assertEquals(OdSubmitOutcome.INACTIVE, outcome)
    }

    private fun harnessSubmit(pipeline: AuxiliaryObjectDetectionPipeline, clock: FakeClock): OdSubmitOutcome =
        pipeline.trySubmit(
            rgb = odTestFrameBuffer(),
            transform = odTestTransform(),
            rotationDegrees = 90,
            captureTimestampNanos = clock.nowNanos,
            arrivalElapsedNanos = clock.nowNanos,
            nowElapsedNanos = clock.nowNanos,
        )

    @Test
    fun queuedFrameBeyondFreshnessIsSkippedWithoutInference() {
        val harness = Harness()
        harness.pipeline.start(1L, harness.clock)
        assertEquals(OdSubmitOutcome.QUEUED, harness.submit())

        harness.clock.advanceMillis(300)
        harness.worker.runAll()

        assertTrue("an expired queued frame must not run inference", harness.engine.receivedFrames.isEmpty())
        assertNull(harness.pipeline.latestEvidence())
        val telemetry = harness.pipeline.telemetry(harness.clock.nowNanos)
        assertEquals(1L, telemetry.staleDropped)
        assertEquals(0L, telemetry.completed)
    }

    @Test
    fun rejectedEnqueueDoesNotWedgeTheSidecarAndIsCounted() {
        val harness = Harness()
        harness.pipeline.start(1L, harness.clock)
        harness.worker.rejectNext = true

        assertEquals(OdSubmitOutcome.QUEUED, harness.submit())
        var telemetry = harness.pipeline.telemetry(harness.clock.nowNanos)
        assertEquals(1L, telemetry.errors)
        assertEquals("rejected enqueue must not leave the in-flight slot stuck", OdSidecarStatus.IDLE, telemetry.status)
        assertNull(harness.pipeline.latestEvidence())

        harness.clock.advanceMillis(200)
        assertEquals(OdSubmitOutcome.QUEUED, harness.submit())
        harness.worker.runAll()
        assertEquals(1, harness.engine.receivedFrames.size)
        telemetry = harness.pipeline.telemetry(harness.clock.nowNanos)
        assertEquals(1L, telemetry.completed)
    }

    @Test
    fun stopDuringFactoryInitializationClosesTheNewEngineOnWorkerThread() {
        val clock = FakeClock()
        val worker = ManualWorker()
        val engine = FakeEngine(clock = clock)
        val factoryEntered = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        val pipeline = AuxiliaryObjectDetectionPipeline(
            engineFactory = {
                factoryEntered.countDown()
                releaseFactory.await()
                engine
            },
            worker = worker,
            clockNanos = { clock.nowNanos },
        )
        pipeline.setEnabled(true)
        pipeline.onSessionStarted(1L, clock.nowNanos)
        pipeline.trySubmit(odTestFrameBuffer(), odTestTransform(), 90, clock.nowNanos, clock.nowNanos, clock.nowNanos)

        val workerThreadName = "od-test-worker"
        val workerThread = thread(name = workerThreadName) { worker.runNext() }
        assertTrue("factory must start on the worker", factoryEntered.await(2, TimeUnit.SECONDS))

        pipeline.onSessionStopped("stop during factory")
        releaseFactory.countDown()
        workerThread.join(2_000)
        worker.runAll()

        assertNull(pipeline.latestEvidence())
        assertTrue("discarded job must not run inference", engine.receivedFrames.isEmpty())
        assertEquals(1, engine.closeCalls)
        assertEquals("interpreter must be closed on its owning worker", workerThreadName, engine.closeThreadName)
    }

    @Test
    fun disableWhileInFlightSkipsInferenceAndDropsResult() {
        val harness = Harness()
        harness.pipeline.start(1L, harness.clock)
        assertEquals(OdSubmitOutcome.QUEUED, harness.submit())

        harness.pipeline.setEnabled(false)
        harness.worker.runAll()

        assertTrue(harness.engine.receivedFrames.isEmpty())
        assertNull(harness.pipeline.latestEvidence())
        val telemetry = harness.pipeline.telemetry(harness.clock.nowNanos)
        assertEquals(0L, telemetry.completed)
        assertEquals(OdSidecarStatus.DISABLED, telemetry.status)
    }

    @Test
    fun staleJobFailureAfterNewSessionDoesNotDisableNewSession() {
        val clock = FakeClock()
        val worker = ManualWorker()
        val detectEntered = CountDownLatch(1)
        val detectRelease = CountDownLatch(1)
        val engine = FakeEngine(
            clock = clock,
            throwOnDetect = true,
            detectEntered = detectEntered,
            detectRelease = detectRelease,
        )
        val pipeline = AuxiliaryObjectDetectionPipeline(
            engineFactory = { engine },
            worker = worker,
            clockNanos = { clock.nowNanos },
        )
        pipeline.setEnabled(true)
        pipeline.onSessionStarted(1L, clock.nowNanos)
        pipeline.trySubmit(odTestFrameBuffer(), odTestTransform(), 90, clock.nowNanos, clock.nowNanos, clock.nowNanos)

        val workerThread = thread { worker.runNext() }
        assertTrue(detectEntered.await(2, TimeUnit.SECONDS))

        clock.advanceMillis(1_000)
        pipeline.onSessionStarted(2L, clock.nowNanos)
        detectRelease.countDown()
        workerThread.join(2_000)
        worker.runAll()

        val telemetry = pipeline.telemetry(clock.nowNanos)
        assertEquals("a stale failure must not mark the new session unavailable", 0L, telemetry.errors)
        assertEquals(OdSidecarStatus.IDLE, telemetry.status)
        assertNull(pipeline.latestEvidence())
    }

    @Test
    fun internalEngineFailuresIncludeSanitizedCauseWithoutPrivatePaths() {
        val clock = FakeClock()
        val worker = ManualWorker()
        val pipeline = AuxiliaryObjectDetectionPipeline(
            engineFactory = {
                throw IllegalStateException(
                    "OD output 0 shape mismatch at /data/user/0/com.ariateam.aria/files/secret.tflite",
                    java.nio.BufferOverflowException(),
                )
            },
            worker = worker,
            clockNanos = { clock.nowNanos },
        )
        pipeline.setEnabled(true)
        pipeline.onSessionStarted(1L, clock.nowNanos)
        pipeline.trySubmit(odTestFrameBuffer(), odTestTransform(), 90, clock.nowNanos, clock.nowNanos, clock.nowNanos)
        worker.runAll()

        val telemetry = pipeline.telemetry(clock.nowNanos)
        assertEquals(OdSidecarStatus.UNAVAILABLE, telemetry.status)
        assertEquals(OdBackoffReason.ENGINE_ERROR, telemetry.backoffReason)
        assertTrue("detail must include exception class: ${telemetry.detail}", telemetry.detail.contains("IllegalStateException"))
        assertTrue("detail must include root cause class: ${telemetry.detail}", telemetry.detail.contains("BufferOverflowException"))
        assertFalse("detail must strip private file paths: ${telemetry.detail}", telemetry.detail.contains("/data/user"))
        assertTrue("detail must replace path with placeholder: ${telemetry.detail}", telemetry.detail.contains("<path>"))
    }

    @Test
    fun displayOverlaySnapshotLifetimeIsIndependentOfHazardFreshnessAndClearsOnEmptyOrLifecycleChanges() {
        val clock = FakeClock()
        val worker = ManualWorker()
        val car = detection(classId = 2, score = 0.88f)
        val engine = FakeEngine(
            clock = clock,
            inferenceMillis = 40L,
            results = mutableListOf(listOf(car), listOf(car), emptyList()),
        )
        val pipeline = AuxiliaryObjectDetectionPipeline(
            engineFactory = { engine },
            worker = worker,
            clockNanos = { clock.nowNanos },
        )
        val transform = odTestTransform()
        val geometry = OdGeometryToken.from(transform, 90)
        pipeline.setEnabled(true)
        pipeline.onSessionStarted(1L, clock.nowNanos)

        val arrival1 = clock.nowNanos
        assertEquals(OdSubmitOutcome.QUEUED, pipeline.trySubmit(odTestFrameBuffer(), transform, 90, arrival1, arrival1, arrival1))
        worker.runAll()

        // At t = arrival1 + 40ms: both hazard evidence and display overlay are present.
        assertNotNull(pipeline.latestEvidence())
        val initialOverlay = pipeline.latestOverlaySnapshot(1L, geometry, clock.nowNanos)
        assertNotNull(initialOverlay)
        assertEquals(listOf(car), initialOverlay!!.detections)
        assertEquals(arrival1 + 750_000_000L, initialOverlay.expiresAtElapsedNanos)

        // Advance to t = arrival1 + 350ms (> 250ms hazard freshness, < 750ms display expiry).
        clock.nowNanos = arrival1 + 350_000_000L
        val fusion = ObjectDetectionHazardFusion()
        val baseHazard = com.ariateam.aria.hazard.HazardAssessment(
            timestampNanos = arrival1 + 350_000_000L,
            level = com.ariateam.aria.hazard.HazardLevel.CLEAR_UNWARNED,
            activeCorridor = com.ariateam.aria.hazard.CorridorDirection.CENTER,
            motionConfidence = 1f,
        )
        val motion = com.ariateam.aria.hazard.MotionState(
            timestampNanos = arrival1 + 350_000_000L,
            isCalibrated = true,
            activeCorridor = com.ariateam.aria.hazard.CorridorDirection.CENTER,
            confidence = 1f,
            degradedReason = null,
        )
        val fusionOutcome = fusion.fuse(
            base = baseHazard,
            motion = motion,
            evidence = pipeline.latestEvidence(),
            frameGeometry = geometry,
            frameCaptureTimestampNanos = clock.nowNanos,
            nowElapsedNanos = clock.nowNanos,
            sessionToken = 1L,
            isClockRealtime = true,
        )
        assertEquals("hazard fusion must reject >250ms evidence", OdFusionRejection.STALE_EVIDENCE, fusionOutcome.rejection)
        assertNotNull(
            "display snapshot must remain visible at 350ms (<750ms expiry)",
            pipeline.latestOverlaySnapshot(1L, geometry, clock.nowNanos),
        )

        // Geometry mismatch must clear the stored snapshot so it cannot resurface if geometry flips back.
        val rotatedGeometry = OdGeometryToken.from(transform.copy(rotationDegrees = 0), 0)
        assertNull(pipeline.latestOverlaySnapshot(1L, rotatedGeometry, clock.nowNanos))
        assertNull(
            "geometry mismatch must clear previous display snapshot even if geometry flips back",
            pipeline.latestOverlaySnapshot(1L, geometry, clock.nowNanos),
        )

        // Submit another positive frame, then verify empty detection result on the following frame clears boxes.
        val arrivalMid = clock.nowNanos
        assertEquals(OdSubmitOutcome.QUEUED, pipeline.trySubmit(odTestFrameBuffer(), transform, 90, arrivalMid, arrivalMid, arrivalMid))
        worker.runAll()
        assertNotNull(pipeline.latestOverlaySnapshot(1L, geometry, clock.nowNanos))
        assertNull("wrong session token must not return snapshot", pipeline.latestOverlaySnapshot(99L, geometry, clock.nowNanos))

        // Empty detection result on the next frame immediately clears previous boxes.
        clock.advanceMillis(200)
        val arrival2 = clock.nowNanos
        assertEquals(OdSubmitOutcome.QUEUED, pipeline.trySubmit(odTestFrameBuffer(), transform, 90, arrival2, arrival2, arrival2))
        worker.runAll()
        assertNull(
            "empty detections must immediately clear previous display boxes",
            pipeline.latestOverlaySnapshot(1L, geometry, clock.nowNanos),
        )
    }

    @Test
    fun resultTooSlowForHazardStillPublishesDisplaySnapshotWithinDisplayWindowOnly() {
        val clock = FakeClock()
        val worker = ManualWorker()
        val person = DetectedObject(0, "person", 0.91f, 0.2f, 0.2f, 0.5f, 0.9f)
        var currentInferenceMillis = 600L
        val engine = object : ObjectDetectorEngine {
            override fun detect(inputRgb: FloatArray, transform: UprightCropRect): List<DetectedObject> {
                clock.advanceMillis(currentInferenceMillis)
                return listOf(person)
            }

            override fun close() = Unit
        }
        val pipeline = AuxiliaryObjectDetectionPipeline(
            engineFactory = { engine },
            worker = worker,
            clockNanos = { clock.nowNanos },
        )
        val transform = odTestTransform()
        val geometry = OdGeometryToken.from(transform, 90)
        pipeline.setEnabled(true)
        pipeline.onSessionStarted(1L, clock.nowNanos)

        val arrival = clock.nowNanos
        assertEquals(OdSubmitOutcome.QUEUED, pipeline.trySubmit(odTestFrameBuffer(), transform, 90, arrival, arrival, arrival))
        worker.runAll()

        // 600ms > 250ms hazard bound: never feeds hazard evidence.
        assertNull("completion >250ms must never populate hazard latestEvidence", pipeline.latestEvidence())
        assertEquals(1L, pipeline.telemetry(clock.nowNanos).staleDropped)

        // 600ms < 750ms display bound from original frame arrival: 150ms of display lifetime remains (until arrival + 750ms).
        val snapshot = pipeline.latestOverlaySnapshot(1L, geometry, clock.nowNanos)
        assertNotNull("completion at 600ms must still publish display snapshot expiring at arrival + 750ms", snapshot)
        assertEquals(arrival + 750_000_000L, snapshot!!.expiresAtElapsedNanos)
        assertEquals(150_000_000L, snapshot.expiresAtElapsedNanos - clock.nowNanos)

        // At exact arrival + 750ms, inclusive isFreshAt contract remains valid; past expiry (+1ns) it is cleared.
        clock.nowNanos = arrival + 750_000_000L
        assertNotNull(pipeline.latestOverlaySnapshot(1L, geometry, clock.nowNanos))
        clock.nowNanos = arrival + 750_000_001L
        assertNull(pipeline.latestOverlaySnapshot(1L, geometry, clock.nowNanos))

        // Completion > 750ms (e.g. 760ms) is already past original-arrival display expiry and must not publish.
        currentInferenceMillis = 760L
        val arrivalLate = clock.nowNanos
        assertEquals(
            OdSubmitOutcome.QUEUED,
            pipeline.trySubmit(odTestFrameBuffer(), transform, 90, arrivalLate, arrivalLate, arrivalLate),
        )
        worker.runAll()
        assertNull(pipeline.latestEvidence())
        assertNull(
            "completion >750ms from original arrival must not publish a display snapshot",
            pipeline.latestOverlaySnapshot(1L, geometry, clock.nowNanos),
        )
    }

    @Test
    fun slowStartupBaselineStaysAtReducedRateAndRecoversFromSuspendToReducedWithoutFlooding() {
        val clock = FakeClock()
        val worker = ManualWorker()
        val engine = FakeEngine(clock = clock)
        val config = OdSidecarConfig(
            primaryWarmupFrames = 10,
            primaryWarmupNanos = 0L,
            minBaselineWarmupFrames = 10,
            primaryOverFramesForReduced = 4,
            primaryOverFramesForSuspend = 8,
            primaryHealthyFramesForRecovery = 10,
            suspendRecoveryCooldownNanos = 2_000_000_000L,
            primaryHealthyFramesForSuspendRecovery = 5,
        )
        val pipeline = AuxiliaryObjectDetectionPipeline(
            engineFactory = { engine },
            worker = worker,
            clockNanos = { clock.nowNanos },
            config = config,
        )
        pipeline.setEnabled(true)
        pipeline.onSessionStarted(1L, clock.nowNanos)

        // 10 startup warmup frames at 150ms establish the observed startup baseline (~150ms, suspend threshold ~202.5ms).
        repeat(10) {
            clock.advanceMillis(150)
            pipeline.onPrimaryAnalyzerFrame(150_000_000L, clock.nowNanos)
        }
        assertEquals(5, pipeline.telemetry(clock.nowNanos).rateHz)

        // 4 post-warmup frames at 150ms (> 50ms budget) step the sidecar down to REDUCED (2 Hz).
        repeat(4) {
            clock.advanceMillis(150)
            pipeline.onPrimaryAnalyzerFrame(150_000_000L, clock.nowNanos)
        }
        assertEquals(2, pipeline.telemetry(clock.nowNanos).rateHz)

        // 30 more steady frames at 160ms (within the 135% baseline suspend bound of 202.5ms) keep OD at 2 Hz instead of suspending,
        // and never recover to 5 Hz because 160ms > 45ms hysteresis.
        repeat(30) {
            clock.advanceMillis(160)
            pipeline.onPrimaryAnalyzerFrame(160_000_000L, clock.nowNanos)
        }
        var telemetry = pipeline.telemetry(clock.nowNanos)
        assertEquals("steady slow-baseline frames must stay at 2 Hz, not suspend or flood to 5 Hz", 2, telemetry.rateHz)
        assertEquals(OdSidecarStatus.IDLE, telemetry.status)

        // Sustained severe spikes (250ms > 202.5ms suspend ceiling) still suspend to 0 Hz.
        repeat(4) {
            clock.advanceMillis(250)
            pipeline.onPrimaryAnalyzerFrame(250_000_000L, clock.nowNanos)
        }
        telemetry = pipeline.telemetry(clock.nowNanos)
        assertEquals(OdSidecarStatus.SUSPENDED, telemetry.status)
        assertEquals(0, telemetry.rateHz)

        // After cooldown + 5 frames back at the 150ms baseline, governor recovers to REDUCED (2 Hz), never straight to 5 Hz.
        clock.advanceMillis(2_100)
        repeat(5) {
            clock.advanceMillis(150)
            pipeline.onPrimaryAnalyzerFrame(150_000_000L, clock.nowNanos)
        }
        telemetry = pipeline.telemetry(clock.nowNanos)
        assertEquals(OdSidecarStatus.IDLE, telemetry.status)
        assertEquals("recovery on slow baseline must probe at 2 Hz, not flood at 5 Hz", 2, telemetry.rateHz)
    }

    @Test
    fun followSegmentationCadenceSub200msSubmissionsAreNotRateLimitedAndBoundedLatestReplacesPending() {
        val clock = FakeClock()
        val worker = ManualWorker()
        val person = DetectedObject(0, "person", 0.95f, 0.1f, 0.1f, 0.4f, 0.8f)
        val engine = FakeEngine(clock = clock, inferenceMillis = 10L, results = mutableListOf(listOf(person), listOf(person)))
        val config = OdSidecarConfig(
            followSegmentationCadence = true,
            holdOverlayUntilNextResult = true,
        )
        val pipeline = AuxiliaryObjectDetectionPipeline(
            engineFactory = { engine.create() },
            worker = worker,
            clockNanos = { clock.nowNanos },
            config = config,
        )
        val transform = odTestTransform()
        pipeline.setEnabled(true)
        pipeline.onSessionStarted(1L, clock.nowNanos)

        val t1 = clock.nowNanos
        assertEquals(OdSubmitOutcome.QUEUED, pipeline.trySubmit(odTestFrameBuffer(0.1f), transform, 90, t1, t1, t1))

        clock.advanceMillis(50)
        val t2 = clock.nowNanos
        assertEquals(OdSubmitOutcome.QUEUED, pipeline.trySubmit(odTestFrameBuffer(0.2f), transform, 90, t2, t2, t2))

        clock.advanceMillis(50)
        val t3 = clock.nowNanos
        assertEquals(OdSubmitOutcome.REPLACED_PENDING, pipeline.trySubmit(odTestFrameBuffer(0.3f), transform, 90, t3, t3, t3))

        clock.advanceMillis(50)
        val t4 = clock.nowNanos
        assertEquals(OdSubmitOutcome.REPLACED_PENDING, pipeline.trySubmit(odTestFrameBuffer(0.4f), transform, 90, t4, t4, t4))

        val telemetryBeforeRun = pipeline.telemetry(clock.nowNanos)
        assertEquals(0L, telemetryBeforeRun.rateLimited)
        assertEquals(4L, telemetryBeforeRun.submitted)
        assertEquals(3L, telemetryBeforeRun.busy)
        assertEquals(2L, telemetryBeforeRun.replaced)

        worker.runAll()

        assertEquals(2, engine.receivedFrames.size)
        assertEquals(0.4f, engine.receivedFrames[1][0], 0.001f)

        val telemetryAfterRun = pipeline.telemetry(clock.nowNanos)
        assertEquals(2L, telemetryAfterRun.completed)
        assertEquals(0L, telemetryAfterRun.rateLimited)
        assertTrue(telemetryAfterRun.followsSegmentationCadence)
        assertEquals(0, telemetryAfterRun.rateHz)
    }

    @Test
    fun pressureSpikesDoNotArtificiallyCapOrSuspendFollowSegmentationCadenceMode() {
        val clock = FakeClock()
        val worker = ManualWorker()
        val engine = FakeEngine(clock = clock)
        val config = OdSidecarConfig(
            followSegmentationCadence = true,
            holdOverlayUntilNextResult = true,
        )
        val pipeline = AuxiliaryObjectDetectionPipeline(
            engineFactory = { engine.create() },
            worker = worker,
            clockNanos = { clock.nowNanos },
            config = config,
        )
        pipeline.setEnabled(true)
        pipeline.onSessionStarted(1L, clock.nowNanos)

        repeat(100) {
            clock.advanceMillis(300)
            pipeline.onPrimaryAnalyzerFrame(300_000_000L, clock.nowNanos)
        }

        val telemetry = pipeline.telemetry(clock.nowNanos)
        assertEquals(OdSidecarStatus.IDLE, telemetry.status)
        assertEquals(0, telemetry.rateHz)
        assertTrue(telemetry.followsSegmentationCadence)
        assertEquals(OdBackoffReason.NONE, telemetry.backoffReason)
        assertEquals("세그멘테이션 프레임마다 요청 · 별도 주기 제한 없음", telemetry.detail)
        assertTrue(telemetry.structuredLogLine().contains("segmentation cadence/uncapped not suspended"))

        val t = clock.nowNanos
        val outcome = pipeline.trySubmit(odTestFrameBuffer(), odTestTransform(), 90, t, t, t)
        assertEquals(OdSubmitOutcome.QUEUED, outcome)
        assertEquals(0L, pipeline.telemetry(clock.nowNanos).rateLimited)
    }

    @Test
    fun heldOverlaySnapshotPersistsBeyond750msUntilNextCompletedResultAndEmptyClears() {
        val clock = FakeClock()
        val worker = ManualWorker()
        val person = DetectedObject(0, "person", 0.92f, 0.1f, 0.1f, 0.5f, 0.8f)
        var nextResults: List<DetectedObject> = listOf(person)
        val engine = object : ObjectDetectorEngine {
            override fun detect(inputRgb: FloatArray, transform: UprightCropRect): List<DetectedObject> = nextResults
            override fun close() = Unit
        }
        val config = OdSidecarConfig(
            followSegmentationCadence = true,
            holdOverlayUntilNextResult = true,
        )
        val pipeline = AuxiliaryObjectDetectionPipeline(
            engineFactory = { engine },
            worker = worker,
            clockNanos = { clock.nowNanos },
            config = config,
        )
        val transform = odTestTransform()
        val geometry = OdGeometryToken.from(transform, 90)
        pipeline.setEnabled(true)
        pipeline.onSessionStarted(1L, clock.nowNanos)

        val t1 = clock.nowNanos
        assertEquals(OdSubmitOutcome.QUEUED, pipeline.trySubmit(odTestFrameBuffer(), transform, 90, t1, t1, t1))
        worker.runAll()

        val snap1 = pipeline.latestOverlaySnapshot(1L, geometry, clock.nowNanos)
        assertNotNull(snap1)
        assertTrue(snap1!!.holdUntilNextResult)
        assertEquals(1, snap1.detections.size)

        clock.advanceMillis(5_000)
        val snapHeld = pipeline.latestOverlaySnapshot(1L, geometry, clock.nowNanos)
        assertNotNull("held snapshot must persist beyond 750ms until next completed result", snapHeld)
        assertTrue(snapHeld!!.holdUntilNextResult)
        assertEquals(1, snapHeld.detections.size)

        clock.advanceMillis(10_000)
        assertNotNull(pipeline.latestOverlaySnapshot(1L, geometry, clock.nowNanos))

        nextResults = emptyList()
        val t2 = clock.nowNanos
        assertEquals(OdSubmitOutcome.QUEUED, pipeline.trySubmit(odTestFrameBuffer(), transform, 90, t2, t2, t2))
        worker.runAll()

        assertNull("completed frame with empty detections must clear held overlay", pipeline.latestOverlaySnapshot(1L, geometry, clock.nowNanos))
    }

    @Test
    fun heldOverlaySnapshotClearedOnStopDisableErrorAndGeometryChange() {
        val clock = FakeClock()
        val worker = ManualWorker()
        val person = DetectedObject(0, "person", 0.90f, 0.2f, 0.2f, 0.6f, 0.8f)
        var throwInEngine = false
        val engine = object : ObjectDetectorEngine {
            override fun detect(inputRgb: FloatArray, transform: UprightCropRect): List<DetectedObject> {
                if (throwInEngine) throw RuntimeException("engine explosion")
                return listOf(person)
            }
            override fun close() = Unit
        }
        val config = OdSidecarConfig(
            followSegmentationCadence = true,
            holdOverlayUntilNextResult = true,
        )
        val pipeline = AuxiliaryObjectDetectionPipeline(
            engineFactory = { engine },
            worker = worker,
            clockNanos = { clock.nowNanos },
            config = config,
        )
        val transform = odTestTransform()
        val geometry = OdGeometryToken.from(transform, 90)
        pipeline.setEnabled(true)
        pipeline.onSessionStarted(1L, clock.nowNanos)

        val t1 = clock.nowNanos
        pipeline.trySubmit(odTestFrameBuffer(), transform, 90, t1, t1, t1)
        worker.runAll()
        assertNotNull(pipeline.latestOverlaySnapshot(1L, geometry, clock.nowNanos))

        val rotatedGeometry = OdGeometryToken.from(transform.copy(rotationDegrees = 0), 0)
        assertNull(pipeline.latestOverlaySnapshot(1L, rotatedGeometry, clock.nowNanos))
        assertNull(pipeline.latestOverlaySnapshot(1L, geometry, clock.nowNanos))

        clock.advanceMillis(100)
        val t2 = clock.nowNanos
        pipeline.trySubmit(odTestFrameBuffer(), transform, 90, t2, t2, t2)
        worker.runAll()
        assertNotNull(pipeline.latestOverlaySnapshot(1L, geometry, clock.nowNanos))
        pipeline.setEnabled(false)
        assertNull(pipeline.latestOverlaySnapshot(1L, geometry, clock.nowNanos))

        pipeline.setEnabled(true)
        pipeline.onSessionStarted(2L, clock.nowNanos)
        clock.advanceMillis(100)
        val t3 = clock.nowNanos
        pipeline.trySubmit(odTestFrameBuffer(), transform, 90, t3, t3, t3)
        worker.runAll()
        assertNotNull(pipeline.latestOverlaySnapshot(2L, geometry, clock.nowNanos))
        pipeline.onSessionStopped("camera stopped")
        assertNull(pipeline.latestOverlaySnapshot(2L, geometry, clock.nowNanos))

        pipeline.onSessionStarted(3L, clock.nowNanos)
        throwInEngine = true
        clock.advanceMillis(100)
        val t4 = clock.nowNanos
        pipeline.trySubmit(odTestFrameBuffer(), transform, 90, t4, t4, t4)
        worker.runAll()
        assertNull(pipeline.latestOverlaySnapshot(3L, geometry, clock.nowNanos))
        val telemetry = pipeline.telemetry(clock.nowNanos)
        assertEquals(OdSidecarStatus.UNAVAILABLE, telemetry.status)
        assertEquals(1L, telemetry.errors)
    }

    @Test
    fun inferenceOver250msStillPublishesHeldDisplaySnapshotWhileHazardEvidenceIsNull() {
        val clock = FakeClock()
        val worker = ManualWorker()
        val obstacle = DetectedObject(1, "bicycle", 0.88f, 0.2f, 0.2f, 0.5f, 0.7f)
        val engine = object : ObjectDetectorEngine {
            override fun detect(inputRgb: FloatArray, transform: UprightCropRect): List<DetectedObject> {
                clock.advanceMillis(300)
                return listOf(obstacle)
            }
            override fun close() = Unit
        }
        val config = OdSidecarConfig(
            followSegmentationCadence = true,
            holdOverlayUntilNextResult = true,
        )
        val pipeline = AuxiliaryObjectDetectionPipeline(
            engineFactory = { engine },
            worker = worker,
            clockNanos = { clock.nowNanos },
            config = config,
        )
        val transform = odTestTransform()
        val geometry = OdGeometryToken.from(transform, 90)
        pipeline.setEnabled(true)
        pipeline.onSessionStarted(1L, clock.nowNanos)

        val arrival = clock.nowNanos
        assertEquals(OdSubmitOutcome.QUEUED, pipeline.trySubmit(odTestFrameBuffer(), transform, 90, arrival, arrival, arrival))
        worker.runAll()

        assertNull("stale completion >250ms must never publish hazard latestEvidence", pipeline.latestEvidence())
        val telemetry = pipeline.telemetry(clock.nowNanos)
        assertEquals(1L, telemetry.staleDropped)
        assertEquals(1L, telemetry.completed)
        assertEquals("held telemetry must reflect true elapsed age from arrival", 300L, telemetry.resultAgeMs)
        assertEquals(1, telemetry.detectionCount)

        val snapshot = pipeline.latestOverlaySnapshot(1L, geometry, clock.nowNanos)
        assertNotNull("held snapshot must still publish even when hazard evidence is dropped due to age >250ms", snapshot)
        assertTrue(snapshot!!.holdUntilNextResult)
        assertEquals(1, snapshot.detections.size)
        assertEquals("bicycle", snapshot.detections[0].label)

        clock.advanceMillis(2_000)
        assertNotNull(pipeline.latestOverlaySnapshot(1L, geometry, clock.nowNanos))
        assertNull(pipeline.latestEvidence())
        val heldTelemetry = pipeline.telemetry(clock.nowNanos)
        assertEquals(2_300L, heldTelemetry.resultAgeMs)
        assertEquals(1, heldTelemetry.detectionCount)
    }

    @Test
    fun pendingBusyOrStaleInFlightFrameDoesNotErasePriorValidHeldDisplaySnapshot() {
        val clock = FakeClock()
        val worker = ManualWorker()
        val initialPerson = DetectedObject(0, "person", 0.93f, 0.1f, 0.1f, 0.4f, 0.8f)
        val engine = FakeEngine(clock = clock, inferenceMillis = 5L, results = mutableListOf(listOf(initialPerson)))
        val config = OdSidecarConfig(
            followSegmentationCadence = true,
            holdOverlayUntilNextResult = true,
        )
        val pipeline = AuxiliaryObjectDetectionPipeline(
            engineFactory = { engine.create() },
            worker = worker,
            clockNanos = { clock.nowNanos },
            config = config,
        )
        val transform = odTestTransform()
        val geometry = OdGeometryToken.from(transform, 90)
        pipeline.setEnabled(true)
        pipeline.onSessionStarted(1L, clock.nowNanos)

        val t1 = clock.nowNanos
        pipeline.trySubmit(odTestFrameBuffer(), transform, 90, t1, t1, t1)
        worker.runAll()
        val initialDisplay = pipeline.latestOverlaySnapshot(1L, geometry, clock.nowNanos)
        assertNotNull(initialDisplay)
        assertEquals("person", initialDisplay!!.detections[0].label)

        clock.advanceMillis(50)
        val t2 = clock.nowNanos
        assertEquals(OdSubmitOutcome.QUEUED, pipeline.trySubmit(odTestFrameBuffer(), transform, 90, t2, t2, t2))

        val duringInflight = pipeline.latestOverlaySnapshot(1L, geometry, clock.nowNanos)
        assertNotNull("in-flight job must not erase prior valid held display", duringInflight)
        assertEquals("person", duringInflight!!.detections[0].label)

        clock.advanceMillis(50)
        val t3 = clock.nowNanos
        assertEquals(OdSubmitOutcome.QUEUED, pipeline.trySubmit(odTestFrameBuffer(), transform, 90, t3, t3, t3))

        val duringPending = pipeline.latestOverlaySnapshot(1L, geometry, clock.nowNanos)
        assertNotNull("pending job must not erase prior valid held display", duringPending)
        assertEquals("person", duringPending!!.detections[0].label)

        clock.advanceMillis(50)
        val t4 = clock.nowNanos
        assertEquals(OdSubmitOutcome.REPLACED_PENDING, pipeline.trySubmit(odTestFrameBuffer(), transform, 90, t4, t4, t4))

        val duringReplaced = pipeline.latestOverlaySnapshot(1L, geometry, clock.nowNanos)
        assertNotNull("pending replacement must not erase prior valid held display", duringReplaced)
        assertEquals("person", duringReplaced!!.detections[0].label)
    }

    @Test
    fun odOverlaySnapshotHoldUntilNextResultIsFreshForAnyPositiveTimestamp() {
        val person = DetectedObject(0, "person", 0.9f, 0f, 0f, 1f, 1f)
        val heldSnapshot = OdOverlaySnapshot(
            detections = listOf(person),
            expiresAtElapsedNanos = Long.MAX_VALUE,
            holdUntilNextResult = true,
        )
        assertFalse(heldSnapshot.isFreshAt(0L))
        assertFalse(heldSnapshot.isFreshAt(-1L))
        assertTrue(heldSnapshot.isFreshAt(1L))
        assertTrue(heldSnapshot.isFreshAt(1_000_000_000L))
        assertTrue(heldSnapshot.isFreshAt(100_000_000_000L))

        val legacySnapshot = OdOverlaySnapshot(
            detections = listOf(person),
            expiresAtElapsedNanos = 1_000_000_000L,
            holdUntilNextResult = false,
        )
        assertTrue(legacySnapshot.isFreshAt(1_000_000_000L))
        assertFalse(legacySnapshot.isFreshAt(1_000_000_001L))
    }

    @Test
    fun odTelemetryStructuredLogLineContainsFollowCadenceMarkerWhenEnabled() {
        val telemetry = OdTelemetry(
            status = OdSidecarStatus.IDLE,
            rateHz = 0,
            followsSegmentationCadence = true,
            detail = "세그멘테이션 프레임마다 요청 · 별도 주기 제한 없음",
        )
        val line = telemetry.structuredLogLine()
        assertTrue(line.contains("cadence=segmentation_frames"))
        assertTrue(line.contains("segmentation cadence/uncapped not suspended"))
        assertTrue(line.contains("rateHz=0"))
    }

    @Test
    fun heldModeRejectsNonpositiveAndFutureElapsedTimestampsWithoutInferenceOrDisplay() {
        val clock = FakeClock()
        val worker = ManualWorker()
        val person = DetectedObject(0, "person", 0.90f, 0.1f, 0.1f, 0.5f, 0.8f)
        val engine = FakeEngine(clock = clock, results = mutableListOf(listOf(person)))
        val config = OdSidecarConfig(
            followSegmentationCadence = true,
            holdOverlayUntilNextResult = true,
        )
        val pipeline = AuxiliaryObjectDetectionPipeline(
            engineFactory = { engine.create() },
            worker = worker,
            clockNanos = { clock.nowNanos },
            config = config,
        )
        val transform = odTestTransform()
        val geometry = OdGeometryToken.from(transform, 90)
        pipeline.setEnabled(true)
        pipeline.onSessionStarted(1L, clock.nowNanos)

        val outcomeZeroArrival = pipeline.trySubmit(
            odTestFrameBuffer(),
            transform,
            90,
            captureTimestampNanos = clock.nowNanos,
            arrivalElapsedNanos = 0L,
            nowElapsedNanos = clock.nowNanos,
        )
        assertEquals(OdSubmitOutcome.INVALID, outcomeZeroArrival)
        assertEquals(1L, pipeline.telemetry(clock.nowNanos).invalidDropped)

        val outcomeNegativeNow = pipeline.trySubmit(
            odTestFrameBuffer(),
            transform,
            90,
            captureTimestampNanos = clock.nowNanos,
            arrivalElapsedNanos = clock.nowNanos,
            nowElapsedNanos = -100L,
        )
        assertEquals(OdSubmitOutcome.INVALID, outcomeNegativeNow)
        assertEquals(2L, pipeline.telemetry(clock.nowNanos).invalidDropped)
        assertTrue("no frame must reach engine for invalid submit timestamps", engine.receivedFrames.isEmpty())

        val tFutureArrival = clock.nowNanos + 500_000_000L
        val outcomeFuture = pipeline.trySubmit(
            odTestFrameBuffer(),
            transform,
            90,
            captureTimestampNanos = clock.nowNanos,
            arrivalElapsedNanos = tFutureArrival,
            nowElapsedNanos = clock.nowNanos,
        )
        assertEquals(OdSubmitOutcome.QUEUED, outcomeFuture)
        worker.runAll()
        assertTrue("future arrival frame must not run inference even in held mode", engine.receivedFrames.isEmpty())
        assertNull("no held overlay should be published for dropped future arrival", pipeline.latestOverlaySnapshot(1L, geometry, clock.nowNanos))
        assertEquals(1L, pipeline.telemetry(clock.nowNanos).staleDropped)
    }
}
