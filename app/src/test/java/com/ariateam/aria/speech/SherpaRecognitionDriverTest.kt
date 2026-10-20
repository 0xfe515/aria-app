package com.ariateam.aria.speech

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaRecognitionDriverTest {

    private class DirectExecutorService : AbstractExecutorService() {
        private val terminated = AtomicBoolean(false)
        override fun shutdown() {
            terminated.set(true)
        }
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

    private data class ScriptedChunk(
        val samples: FloatArray = FloatArray(640) { 0.05f },
        val decodedText: String = "",
        val endpoint: Boolean = false,
    )

    private class ScriptedAsrStream(
        private val steps: List<ScriptedChunk>,
        private val finalFlushText: String? = null,
    ) : StreamingAsrStream {
        private var stepIndex = -1
        private var readyPollsRemaining = 0
        private var text = ""
        private var endpointFlag = false
        var resetCount = 0
            private set
        var tailPaddingSamplesAccepted = 0
            private set
        var inputFinishedCalled = false
            private set
        var closed = false
            private set

        override fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
            if (samples.size == sampleRate / 2 && samples.all { it == 0.0f }) {
                tailPaddingSamplesAccepted += samples.size
                readyPollsRemaining = 1
                return
            }
            if (stepIndex + 1 < steps.size) {
                stepIndex++
                readyPollsRemaining = 1
            }
        }

        override fun isReady(): Boolean = readyPollsRemaining > 0

        override fun decode() {
            if (readyPollsRemaining > 0) {
                readyPollsRemaining--
            }
            if (inputFinishedCalled && finalFlushText != null) {
                text = finalFlushText
            } else if (stepIndex in steps.indices) {
                text = steps[stepIndex].decodedText
                endpointFlag = steps[stepIndex].endpoint
            }
        }

        override fun isEndpoint(): Boolean = endpointFlag

        override fun reset() {
            resetCount++
            text = ""
            endpointFlag = false
        }

        override fun inputFinished() {
            inputFinishedCalled = true
            if (finalFlushText != null) {
                readyPollsRemaining = 1
            }
        }

        override fun currentText(): String = text

        override fun close() {
            closed = true
        }
    }

    private class RecordingListener : RecognitionDriver.Listener {
        var readyCount = 0
        var speechStartedCount = 0
        var speechEndedCount = 0
        val partials = mutableListOf<List<String>>()
        val results = mutableListOf<List<String>>()
        val failures = mutableListOf<RecognitionFailure>()

        override fun ready() {
            readyCount++
        }
        override fun speechStarted() {
            speechStartedCount++
        }
        override fun speechEnded() {
            speechEndedCount++
        }
        override fun partial(text: String) {
            partials += listOf(text)
        }
        override fun partial(candidates: List<String>) {
            partials += candidates
        }
        override fun result(text: String) {
            results += listOf(text)
        }
        override fun result(candidates: List<String>) {
            results += candidates
        }
        override fun failed(failure: RecognitionFailure) {
            failures += failure
        }
    }

    @Test
    fun reusesEngineAcrossMultipleAttemptsAndReleasesOnceOnShutdown() {
        val engineCreateCount = AtomicInteger(0)
        val engineCloseCount = AtomicInteger(0)
        val streamCreateCount = AtomicInteger(0)

        val steps = listOf(
            ScriptedChunk(samples = FloatArray(8_000) { 0.05f }, decodedText = "헤이 아리아", endpoint = true),
        )
        val driver = SherpaRecognitionDriver(
            audioSource = WaveAudioCaptureSource(FloatArray(8_000) { 0.05f }),
            engineFactory = {
                engineCreateCount.incrementAndGet()
                object : StreamingAsrEngine {
                    override fun createStream(): StreamingAsrStream {
                        streamCreateCount.incrementAndGet()
                        return ScriptedAsrStream(steps)
                    }
                    override fun close() {
                        engineCloseCount.incrementAndGet()
                    }
                }
            },
            mainPoster = { it() },
            workerExecutor = DirectExecutorService(),
            chunkSamples = 8_000,
            minEndpointUtteranceMillis = 400L,
        )

        val first = RecordingListener()
        driver.start(first)
        driver.close()

        val second = RecordingListener()
        driver.start(second)
        driver.close()

        assertEquals(1, engineCreateCount.get())
        assertEquals(2, streamCreateCount.get())
        assertEquals(0, engineCloseCount.get())
        assertEquals(listOf(listOf("헤이 아리아")), first.results)
        assertEquals(listOf(listOf("헤이 아리아")), second.results)

        driver.shutdown()
        assertEquals(1, engineCloseCount.get())
    }

    @Test
    fun resetsEmptySilenceEndpointsWithoutClosingAttemptAndFinalizesRealSpeech() {
        val steps = listOf(
            // 1st chunk: silence, no endpoint
            ScriptedChunk(samples = FloatArray(3_200), decodedText = "", endpoint = false),
            // 2nd chunk: native rule1 silence endpoint with empty text -> must reset in-place!
            ScriptedChunk(samples = FloatArray(3_200), decodedText = "", endpoint = true),
            // 3rd chunk: user starts saying wake phrase
            ScriptedChunk(samples = FloatArray(4_800), decodedText = "헤이 아리아", endpoint = false),
            // 4th chunk: user finishes command and hits rule2 endpoint
            ScriptedChunk(samples = FloatArray(4_800), decodedText = "헤이 아리아 설정 열어", endpoint = true),
        )
        val scriptedStream = ScriptedAsrStream(steps)
        var audioClosedCount = 0

        val driver = SherpaRecognitionDriver(
            audioSource = AudioCaptureSource { _, _ ->
                var index = 0
                object : AudioCaptureSession {
                    override fun read(buffer: FloatArray): Int {
                        if (index >= steps.size) return -1
                        val src = steps[index++].samples
                        System.arraycopy(src, 0, buffer, 0, src.size)
                        return src.size
                    }
                    override fun stopCapture() {}
                    override fun close() {
                        audioClosedCount++
                    }
                }
            },
            engineFactory = {
                object : StreamingAsrEngine {
                    override fun createStream(): StreamingAsrStream = scriptedStream
                    override fun close() {}
                }
            },
            mainPoster = { it() },
            workerExecutor = DirectExecutorService(),
            chunkSamples = 4_800,
            minEndpointUtteranceMillis = 400L,
        )

        val listener = RecordingListener()
        driver.start(listener)

        assertEquals(1, scriptedStream.resetCount)
        assertEquals(1, listener.readyCount)
        assertEquals(1, listener.speechStartedCount)
        assertEquals(1, listener.speechEndedCount)
        assertEquals(
            listOf(listOf("헤이 아리아"), listOf("헤이 아리아 설정 열어")),
            listener.partials,
        )
        assertEquals(listOf(listOf("헤이 아리아 설정 열어")), listener.results)
        assertTrue(listener.failures.isEmpty())
        assertTrue(audioClosedCount >= 1)
    }

    @Test
    fun doesNotPrematurelyFinalizeBeforeMinEndpointUtteranceDuration() {
        val steps = listOf(
            // 100ms chunk (1600 samples @ 16kHz) with early endpoint -> below 400ms threshold
            ScriptedChunk(samples = FloatArray(1_600), decodedText = "헤이", endpoint = true),
            // Next 400ms chunk (6400 samples) completes full phrase -> total 500ms >= 400ms
            ScriptedChunk(samples = FloatArray(6_400), decodedText = "헤이 아리아", endpoint = true),
        )
        val scriptedStream = ScriptedAsrStream(steps)

        val driver = SherpaRecognitionDriver(
            audioSource = AudioCaptureSource { _, _ ->
                var index = 0
                object : AudioCaptureSession {
                    override fun read(buffer: FloatArray): Int {
                        if (index >= steps.size) return -1
                        val src = steps[index++].samples
                        System.arraycopy(src, 0, buffer, 0, src.size)
                        return src.size
                    }
                    override fun stopCapture() {}
                    override fun close() {}
                }
            },
            engineFactory = {
                object : StreamingAsrEngine {
                    override fun createStream(): StreamingAsrStream = scriptedStream
                    override fun close() {}
                }
            },
            mainPoster = { it() },
            workerExecutor = DirectExecutorService(),
            chunkSamples = 6_400,
            minEndpointUtteranceMillis = 400L,
        )

        val listener = RecordingListener()
        driver.start(listener)

        assertEquals(0, scriptedStream.resetCount)
        assertEquals(listOf(listOf("헤이"), listOf("헤이 아리아")), listener.partials)
        assertEquals(listOf(listOf("헤이 아리아")), listener.results)
    }

    @Test
    fun closeStopsAudioCaptureAndSuppressesQueuedCallbacks() {
        val queuedMainCallbacks = mutableListOf<() -> Unit>()
        var stopCaptureCalled = false
        var closeAudioCalled = false

        val driver = SherpaRecognitionDriver(
            audioSource = AudioCaptureSource { _, _ ->
                var readCalls = 0
                object : AudioCaptureSession {
                    override fun read(buffer: FloatArray): Int {
                        if (readCalls++ > 0) return -1
                        return 640
                    }
                    override fun stopCapture() {
                        stopCaptureCalled = true
                    }
                    override fun close() {
                        closeAudioCalled = true
                    }
                }
            },
            engineFactory = {
                object : StreamingAsrEngine {
                    override fun createStream(): StreamingAsrStream = ScriptedAsrStream(
                        listOf(ScriptedChunk(decodedText = "헤이 아리아", endpoint = true)),
                    )
                    override fun close() {}
                }
            },
            mainPoster = { queuedMainCallbacks += it },
            workerExecutor = DirectExecutorService(),
            chunkSamples = 640,
            minEndpointUtteranceMillis = 0L,
        )

        val listener = RecordingListener()
        driver.start(listener)
        assertTrue(closeAudioCalled)

        // Close before queued main-thread callbacks execute (simulating TTS suppression or Stop).
        driver.close()
        queuedMainCallbacks.forEach { it() }

        assertEquals(0, listener.readyCount)
        assertTrue(listener.partials.isEmpty())
        assertTrue(listener.results.isEmpty())
        assertTrue(listener.failures.isEmpty())
    }

    @Test
    fun explicitFinishFlushesDecoderAndDeliversFinalResult() {
        var driverRef: SherpaRecognitionDriver? = null
        val steps = listOf(
            ScriptedChunk(samples = FloatArray(3_200), decodedText = "헤이 아리아", endpoint = false),
        )
        val scriptedStream = ScriptedAsrStream(
            steps = steps,
            finalFlushText = "헤이 아리아 도움말",
        )

        val driver = SherpaRecognitionDriver(
            audioSource = AudioCaptureSource { _, _ ->
                var call = 0
                object : AudioCaptureSession {
                    override fun read(buffer: FloatArray): Int {
                        call++
                        if (call == 1) {
                            return 3_200
                        }
                        // On 2nd read, simulate user pressing Finish Dictation.
                        driverRef?.finish()
                        return -1
                    }
                    override fun stopCapture() {}
                    override fun close() {}
                }
            },
            engineFactory = {
                object : StreamingAsrEngine {
                    override fun createStream(): StreamingAsrStream = scriptedStream
                    override fun close() {}
                }
            },
            mainPoster = { it() },
            workerExecutor = DirectExecutorService(),
            chunkSamples = 3_200,
        )
        driverRef = driver

        val listener = RecordingListener()
        driver.start(listener)

        assertEquals(8_000, scriptedStream.tailPaddingSamplesAccepted)
        assertTrue(scriptedStream.inputFinishedCalled)
        assertEquals(listOf(listOf("헤이 아리아")), listener.partials)
        assertEquals(listOf(listOf("헤이 아리아 도움말")), listener.results)
        assertEquals(1, listener.speechEndedCount)
    }

    @Test
    fun permissionAndHardwareErrorsReportExpectedRecognitionFailures() {
        val permissionDriver = SherpaRecognitionDriver(
            audioSource = AudioCaptureSource { _, _ ->
                throw SecurityException("RECORD_AUDIO denied")
            },
            engineFactory = {
                object : StreamingAsrEngine {
                    override fun createStream(): StreamingAsrStream = ScriptedAsrStream(emptyList())
                    override fun close() {}
                }
            },
            mainPoster = { it() },
            workerExecutor = DirectExecutorService(),
        )
        val permissionListener = RecordingListener()
        permissionDriver.start(permissionListener)
        assertEquals(listOf(RecognitionFailure.Permission), permissionListener.failures)

        val audioDriver = SherpaRecognitionDriver(
            audioSource = AudioCaptureSource { _, _ ->
                throw AudioCaptureException("AudioRecord init failed")
            },
            engineFactory = {
                object : StreamingAsrEngine {
                    override fun createStream(): StreamingAsrStream = ScriptedAsrStream(emptyList())
                    override fun close() {}
                }
            },
            mainPoster = { it() },
            workerExecutor = DirectExecutorService(),
        )
        val audioListener = RecordingListener()
        audioDriver.start(audioListener)
        assertEquals(listOf(RecognitionFailure.Audio), audioListener.failures)
    }

    @Test
    fun integratesWithVoiceListeningSessionForOfflineWakeAndCommand() {
        val steps = listOf(
            ScriptedChunk(samples = FloatArray(4_800), decodedText = "헤이 아리아", endpoint = false),
            ScriptedChunk(samples = FloatArray(4_800), decodedText = "헤이 아리아 설정 열어", endpoint = true),
        )
        var attemptCount = 0
        val driver = SherpaRecognitionDriver(
            audioSource = AudioCaptureSource { _, _ ->
                attemptCount++
                var idx = 0
                object : AudioCaptureSession {
                    override fun read(buffer: FloatArray): Int {
                        if (attemptCount > 1 || idx >= steps.size) return -1
                        val src = steps[idx++].samples
                        System.arraycopy(src, 0, buffer, 0, src.size)
                        return src.size
                    }
                    override fun stopCapture() {}
                    override fun close() {}
                }
            },
            engineFactory = {
                object : StreamingAsrEngine {
                    override fun createStream(): StreamingAsrStream = ScriptedAsrStream(steps)
                    override fun close() {}
                }
            },
            mainPoster = { it() },
            workerExecutor = DirectExecutorService(),
            chunkSamples = 4_800,
            minEndpointUtteranceMillis = 400L,
        )

        var wakeDetectedCount = 0
        var confirmedCommand: String? = null
        val scheduler = object : RecognitionScheduler {
            override fun now(): Long = 1_000L
            override fun schedule(delayMillis: Long, action: () -> Unit): ScheduledRecognitionTask =
                ScheduledRecognitionTask {}
        }
        val session = VoiceListeningSession(
            driver = driver,
            scheduler = scheduler,
            listener = object : VoiceListeningSession.Listener {
                override fun state(snapshot: ListeningSnapshot) {}
                override fun inputStarted() {}
                override fun wakeDetected() {
                    wakeDetectedCount++
                }
                override fun command(text: String) {
                    confirmedCommand = text
                }
                override fun emptyInput() {}
                override fun fatal(errorCode: Int) {}
            },
        )

        session.start(manual = false)
        assertEquals(1, wakeDetectedCount)
        assertEquals("설정 열어", confirmedCommand)
        assertFalse(
            "Ordinary sentence must not trigger wake matcher",
            KoreanWakePhraseMatcher().selectWakeAfterPhrase(
                listOf("걔는 괜찮은 척하려구 애 쓰는 거 같았다")
            ) != null,
        )
        assertNull(
            KoreanWakePhraseMatcher().selectWakeAfterPhrase(
                listOf("그는 괜찮은 척하려고 애쓰는 것 같았다")
            )
        )
        session.shutdown()
    }
}
