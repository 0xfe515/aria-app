package com.ariateam.aria.speech

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaWakeWordDetectorTest {

    private class DirectExecutorService : AbstractExecutorService() {
        private val terminated = AtomicBoolean(false)
        override fun shutdown() { terminated.set(true) }
        override fun shutdownNow(): List<Runnable> {
            terminated.set(true)
            return emptyList()
        }
        override fun isShutdown(): Boolean = terminated.get()
        override fun isTerminated(): Boolean = terminated.get()
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
        override fun execute(command: Runnable) {
            command.run()
        }
    }

    private class FakeAudioSession(
        private val chunks: List<FloatArray>,
    ) : AudioCaptureSession {
        private var index = 0
        var stopCount = 0
            private set
        var closeCount = 0
            private set

        override fun read(buffer: FloatArray): Int {
            if (stopCount > 0 || closeCount > 0 || index >= chunks.size) {
                return -1
            }
            val chunk = chunks[index++]
            val count = minOf(buffer.size, chunk.size)
            System.arraycopy(chunk, 0, buffer, 0, count)
            return count
        }

        override fun stopCapture() {
            stopCount++
        }

        override fun close() {
            closeCount++
        }
    }

    private class FakeKwsStream(
        private val triggerAtStep: Int = 1,
        private val keywordToTrigger: String = "헤이아리아",
    ) : KwsStream {
        private var step = 0
        var resetCount = 0
            private set
        var closeCount = 0
            private set

        override fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
            step++
        }

        override fun isReady(): Boolean = true

        override fun decode() {
            // Decoded on each ready check
        }

        override fun getKeyword(): String {
            return if (step >= triggerAtStep) keywordToTrigger else ""
        }

        override fun reset() {
            resetCount++
        }

        override fun close() {
            closeCount++
        }
    }

    private class FakeKwsEngine(
        val stream: FakeKwsStream = FakeKwsStream(),
    ) : KwsEngine {
        var closed = false
            private set

        override fun createStream(): KwsStream = stream

        override fun close() {
            closed = true
        }
    }

    @Test
    fun wakeWordDetectedCallsListenerAndReleasesMicrophone() {
        val fakeSession = FakeAudioSession(
            listOf(
                FloatArray(640) { 0.1f },
                FloatArray(640) { 0.2f },
            )
        )
        val audioSource = AudioCaptureSource { _, _ -> fakeSession }
        val stream = FakeKwsStream(triggerAtStep = 1, keywordToTrigger = "헤이아리아")
        val engine = FakeKwsEngine(stream)
        val executor = DirectExecutorService()

        val detector = SherpaWakeWordDetector(
            engine = engine,
            audioSource = audioSource,
            executor = executor,
            mainPoster = { it.run() },
        )

        var detectedKeyword = ""
        var errorOccurred: Throwable? = null

        detector.start(object : WakeWordDetector.Listener {
            override fun onWakeWordDetected(keyword: String) {
                detectedKeyword = keyword
            }

            override fun onError(error: Throwable) {
                errorOccurred = error
            }
        })

        assertEquals("헤이아리아", detectedKeyword)
        assertEquals(null, errorOccurred)
        assertEquals(1, stream.resetCount)
        assertTrue("AudioSession must be stopped on hit to release mic", fakeSession.stopCount > 0)
        assertTrue("AudioSession must be closed on hit to release mic", fakeSession.closeCount > 0)
    }

    @Test
    fun audioSourceFailureDeliversErrorToListener() {
        val audioSource = AudioCaptureSource { _, _ ->
            throw AudioCaptureException("Mic unavailable")
        }
        val stream = FakeKwsStream()
        val engine = FakeKwsEngine(stream)
        val executor = DirectExecutorService()

        val detector = SherpaWakeWordDetector(
            engine = engine,
            audioSource = audioSource,
            executor = executor,
            mainPoster = { it.run() },
        )

        var errorOccurred: Throwable? = null
        detector.start(object : WakeWordDetector.Listener {
            override fun onWakeWordDetected(keyword: String) = Unit
            override fun onError(error: Throwable) {
                errorOccurred = error
            }
        })

        assertTrue(errorOccurred is AudioCaptureException)
        assertEquals("Mic unavailable", errorOccurred?.message)
    }

    @Test
    fun unexpectedEofOnLiveAttemptDeliversErrorToListener() {
        // Fake session with no chunks: read immediately returns -1 (EOF) without prior stop
        val emptySession = FakeAudioSession(emptyList())
        val audioSource = AudioCaptureSource { _, _ -> emptySession }
        val stream = FakeKwsStream()
        val engine = FakeKwsEngine(stream)
        val executor = DirectExecutorService()

        val detector = SherpaWakeWordDetector(
            engine = engine,
            audioSource = audioSource,
            executor = executor,
            mainPoster = { it.run() },
        )

        var errorOccurred: Throwable? = null
        detector.start(object : WakeWordDetector.Listener {
            override fun onWakeWordDetected(keyword: String) = Unit
            override fun onError(error: Throwable) {
                errorOccurred = error
            }
        })

        assertTrue("EOF on live attempt must deliver error to listener, not hang silently", errorOccurred is AudioCaptureException)
        assertTrue(emptySession.closeCount > 0)
    }

    @Test
    fun stopPromptlyCancelsActiveSession() {
        val fakeSession = FakeAudioSession(listOf(FloatArray(640)))
        val audioSource = AudioCaptureSource { _, _ -> fakeSession }
        val engine = FakeKwsEngine()
        val executor = DirectExecutorService()

        val detector = SherpaWakeWordDetector(
            engine = engine,
            audioSource = audioSource,
            executor = executor,
            mainPoster = { it.run() },
        )

        detector.start(object : WakeWordDetector.Listener {
            override fun onWakeWordDetected(keyword: String) = Unit
            override fun onError(error: Throwable) = Unit
        })

        detector.stop()
        assertTrue(fakeSession.stopCount > 0)
        assertTrue(fakeSession.closeCount > 0)
    }

    @Test
    fun releaseCleansUpEngineAndExecutor() {
        val fakeSession = FakeAudioSession(emptyList())
        val engine = FakeKwsEngine()
        val executor = DirectExecutorService()

        val detector = SherpaWakeWordDetector(
            engine = engine,
            audioSource = { _, _ -> fakeSession },
            executor = executor,
            mainPoster = { it.run() },
        )

        assertTrue(detector.isAvailable)
        detector.release()
        assertFalse(detector.isAvailable)
        assertTrue(engine.closed)
        assertTrue(executor.isShutdown)
    }

    @Test
    fun spotterConfigHasCorrectDefaults() {
        val config = SherpaWakeWordDetector.buildSpotterConfig(
            keywordsScore = 1.8f,
            keywordsThreshold = 0.3f,
            numThreads = 3,
        )
        assertEquals(1.8f, config.keywordsScore, 0.001f)
        assertEquals(0.3f, config.keywordsThreshold, 0.001f)
        assertEquals(SherpaWakeWordDetector.KEYWORDS_ASSET, config.keywordsFile)
        assertEquals(16_000, config.featConfig.sampleRate)
        assertEquals(80, config.featConfig.featureDim)
        assertEquals(SherpaWakeWordDetector.ENCODER_ASSET, config.modelConfig.transducer.encoder)
        assertEquals(SherpaWakeWordDetector.DECODER_ASSET, config.modelConfig.transducer.decoder)
        assertEquals(SherpaWakeWordDetector.JOINER_ASSET, config.modelConfig.transducer.joiner)
        assertEquals(SherpaWakeWordDetector.TOKENS_ASSET, config.modelConfig.tokens)
        assertEquals(3, config.modelConfig.numThreads)
    }

    @Test
    fun stopWhileOpenClosesSessionAndDoesNotPublishOrLeakCapture() {
        val enterOpen = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val session = FakeAudioSession(listOf(FloatArray(640)))
        val audioSource = AudioCaptureSource { _, _ ->
            enterOpen.countDown()
            releaseOpen.await(5, TimeUnit.SECONDS)
            session
        }
        val engine = FakeKwsEngine()
        val executor = DirectExecutorService()
        val detector = SherpaWakeWordDetector(
            engine = engine,
            audioSource = audioSource,
            executor = executor,
            mainPoster = { it.run() },
        )

        var listenerError: Throwable? = null
        val startThread = Thread {
            detector.start(object : WakeWordDetector.Listener {
                override fun onWakeWordDetected(keyword: String) = Unit
                override fun onError(error: Throwable) { listenerError = error }
            })
        }
        startThread.start()

        assertTrue(enterOpen.await(5, TimeUnit.SECONDS))
        detector.stop()
        releaseOpen.countDown()
        startThread.join(5000)

        assertTrue("Session opened during stop must be stopped", session.stopCount > 0)
        assertTrue("Session opened during stop must be closed", session.closeCount > 0)
        assertNull("Listener should not receive error on superseded open", listenerError)
    }

    @Test
    fun releaseWhileOpenClosesSessionAndDoesNotPublishOrLeakCapture() {
        val enterOpen = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val session = FakeAudioSession(listOf(FloatArray(640)))
        val audioSource = AudioCaptureSource { _, _ ->
            enterOpen.countDown()
            releaseOpen.await(5, TimeUnit.SECONDS)
            session
        }
        val engine = FakeKwsEngine()
        val executor = DirectExecutorService()
        val detector = SherpaWakeWordDetector(
            engine = engine,
            audioSource = audioSource,
            executor = executor,
            mainPoster = { it.run() },
        )

        val startThread = Thread {
            detector.start(object : WakeWordDetector.Listener {
                override fun onWakeWordDetected(keyword: String) = Unit
                override fun onError(error: Throwable) = Unit
            })
        }
        startThread.start()

        assertTrue(enterOpen.await(5, TimeUnit.SECONDS))
        detector.release()
        releaseOpen.countDown()
        startThread.join(5000)

        assertTrue(session.stopCount > 0)
        assertTrue(session.closeCount > 0)
        assertFalse(detector.isAvailable)
        assertTrue(engine.closed)
        assertTrue(executor.isShutdown)
    }

    @Test
    fun olderEofCleanupDoesNotClobberNewerAttemptSession() {
        class QueuedExecutor : AbstractExecutorService() {
            val tasks = mutableListOf<Runnable>()
            override fun shutdown() = Unit
            override fun shutdownNow(): List<Runnable> = emptyList()
            override fun isShutdown(): Boolean = false
            override fun isTerminated(): Boolean = false
            override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
            override fun execute(command: Runnable) { tasks += command }
            fun runNext() { if (tasks.isNotEmpty()) tasks.removeAt(0).run() }
        }

        val session1 = FakeAudioSession(emptyList())
        val session2 = FakeAudioSession(listOf(FloatArray(640)))
        var openCount = 0
        val audioSource = AudioCaptureSource { _, _ ->
            openCount++
            if (openCount == 1) session1 else session2
        }
        val engine = FakeKwsEngine()
        val queuedExecutor = QueuedExecutor()
        val detector = SherpaWakeWordDetector(
            engine = engine,
            audioSource = audioSource,
            executor = queuedExecutor,
            mainPoster = { it.run() },
        )

        var session1Errors = 0
        var session2Errors = 0

        detector.start(object : WakeWordDetector.Listener {
            override fun onWakeWordDetected(keyword: String) = Unit
            override fun onError(error: Throwable) { session1Errors++ }
        })
        assertEquals(1, queuedExecutor.tasks.size)

        detector.start(object : WakeWordDetector.Listener {
            override fun onWakeWordDetected(keyword: String) = Unit
            override fun onError(error: Throwable) { session2Errors++ }
        })
        assertEquals(2, queuedExecutor.tasks.size)

        queuedExecutor.runNext()

        detector.stop()
        assertTrue("Attempt 2's session must be closed by detector.stop()", session2.stopCount > 0)
        assertTrue("Attempt 2's session must be closed by detector.stop()", session2.closeCount > 0)
        assertEquals("Superseded attempt 1 must not deliver error callback", 0, session1Errors)
    }

    @Test
    fun pendingCallbackAfterStopIsDropped() {
        val session = FakeAudioSession(listOf(FloatArray(640)))
        val stream = FakeKwsStream(triggerAtStep = 1, keywordToTrigger = "헤이아리아")
        val engine = FakeKwsEngine(stream)
        val pendingMainPosts = mutableListOf<Runnable>()
        val detector = SherpaWakeWordDetector(
            engine = engine,
            audioSource = { _, _ -> session },
            executor = DirectExecutorService(),
            mainPoster = { pendingMainPosts += it },
        )

        var wakeWordDetected = 0
        detector.start(object : WakeWordDetector.Listener {
            override fun onWakeWordDetected(keyword: String) { wakeWordDetected++ }
            override fun onError(error: Throwable) = Unit
        })

        assertEquals(1, pendingMainPosts.size)
        detector.stop()

        pendingMainPosts.removeAt(0).run()
        assertEquals("Pending detection callback after stop must be dropped", 0, wakeWordDetected)
        assertTrue(session.stopCount > 0)
        assertTrue(session.closeCount > 0)
    }

    @Test
    fun regressionBlockCloseCallStopUnblockCloseDropsQueuedCallback() {
        val enterCloseLatch = CountDownLatch(1)
        val unblockCloseLatch = CountDownLatch(1)
        val backgroundDone = CountDownLatch(1)

        class BlockingCloseAudioSession : AudioCaptureSession {
            var closeCount = 0
            var stopCount = 0

            override fun read(buffer: FloatArray): Int = -1 // trigger EOF

            override fun stopCapture() { stopCount++ }

            override fun close() {
                closeCount++
                enterCloseLatch.countDown()
                unblockCloseLatch.await(5, TimeUnit.SECONDS)
            }
        }

        val session = BlockingCloseAudioSession()
        val engine = FakeKwsEngine()
        val pendingMainPosts = mutableListOf<Runnable>()
        val backgroundExecutor = Executors.newSingleThreadExecutor()
        val detector = SherpaWakeWordDetector(
            engine = engine,
            audioSource = { _, _ -> session },
            executor = backgroundExecutor,
            mainPoster = {
                pendingMainPosts += it
                backgroundDone.countDown()
            },
        )

        var listenerError: Throwable? = null
        detector.start(object : WakeWordDetector.Listener {
            override fun onWakeWordDetected(keyword: String) = Unit
            override fun onError(error: Throwable) { listenerError = error }
        })

        // Wait until background thread enters session.close()
        assertTrue(enterCloseLatch.await(5, TimeUnit.SECONDS))

        // While close() is blocked in flight, stop() is called!
        detector.stop()

        // Unblock close()
        unblockCloseLatch.countDown()
        assertTrue(backgroundDone.await(5, TimeUnit.SECONDS))
        backgroundExecutor.shutdown()

        // Drain any pending posts
        while (pendingMainPosts.isNotEmpty()) {
            pendingMainPosts.removeAt(0).run()
        }

        assertNull("Queued callback from attempt stopped during close must be dropped", listenerError)
        assertTrue(session.closeCount > 0)
    }

    @Test
    fun releaseDuringAudioSourceOpenMustNotCallEngineCreateStream() {
        val enterOpen = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val session = FakeAudioSession(listOf(FloatArray(640)))
        val audioSource = AudioCaptureSource { _, _ ->
            enterOpen.countDown()
            releaseOpen.await(5, TimeUnit.SECONDS)
            session
        }

        var createStreamCalled = 0
        val engine = object : KwsEngine {
            override fun createStream(): KwsStream {
                createStreamCalled++
                return FakeKwsStream()
            }
            override fun close() = Unit
        }

        val detector = SherpaWakeWordDetector(
            engine = engine,
            audioSource = audioSource,
            executor = DirectExecutorService(),
            mainPoster = { it.run() },
        )

        val startThread = Thread {
            detector.start(object : WakeWordDetector.Listener {
                override fun onWakeWordDetected(keyword: String) = Unit
                override fun onError(error: Throwable) = Unit
            })
        }
        startThread.start()

        assertTrue(enterOpen.await(5, TimeUnit.SECONDS))
        detector.release()
        releaseOpen.countDown()
        startThread.join(5000)

        assertEquals("engine.createStream must NEVER be called after release during open", 0, createStreamCalled)
        assertTrue(session.stopCount > 0)
        assertTrue(session.closeCount > 0)
    }
}
