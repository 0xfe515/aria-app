package com.ariateam.aria.speech

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
