package com.ariateam.aria.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeRecoveryRegressionTest {

    private class FakeAudioSession : AudioCaptureSession {
        var closed = false
        var stopCount = 0

        override fun read(buffer: FloatArray): Int {
            if (closed || stopCount > 0) return -1
            return buffer.size
        }

        override fun stopCapture() {
            stopCount++
        }

        override fun close() {
            closed = true
            stopCapture()
        }
    }

    private class BoundedRetryAudioSource(
        private val failuresBeforeSuccess: Int,
    ) : AudioCaptureSource {
        var attempts = 0

        override fun open(sampleRate: Int, chunkSamples: Int): AudioCaptureSession {
            attempts++
            if (attempts <= failuresBeforeSuccess) {
                throw AudioCaptureException("AudioRecord not ready, attempt $attempts")
            }
            return FakeAudioSession()
        }
    }

    private class FakeScheduler : RecognitionScheduler {
        private data class Task(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
        private var clock = 0L
        private val tasks = mutableListOf<Task>()

        override fun now(): Long = clock

        override fun schedule(delayMillis: Long, action: () -> Unit): ScheduledRecognitionTask {
            val task = Task(clock + delayMillis, action)
            tasks += task
            return ScheduledRecognitionTask { task.cancelled = true }
        }

        fun advance(delta: Long) {
            val target = clock + delta
            while (true) {
                val next = tasks.filter { !it.cancelled && it.at <= target }.minByOrNull { it.at } ?: break
                tasks.remove(next)
                clock = next.at
                next.action()
            }
            clock = target
        }
    }

    private class TestDriver : RecognitionDriver {
        lateinit var listener: RecognitionDriver.Listener
        var starts = 0
        var closes = 0
        var active = false

        override fun start(listener: RecognitionDriver.Listener) {
            active = true
            starts++
            this.listener = listener
        }

        override fun finish() = Unit

        override fun close() {
            if (active) {
                active = false
                closes++
            }
        }
    }

    @Test
    fun matcherSupportsAllCanonicalAndAcousticAliases() {
        val matcher = KoreanWakePhraseMatcher()

        // Canonical Korean
        assertTrue(matcher.containsWakePhrase("헤이 아리아"))
        assertTrue(matcher.containsWakePhrase("헤이 아리야"))
        assertEquals("홈으로 가", matcher.afterWakePhrase("헤이 아리아 홈으로 가"))
        assertEquals("홈으로 가", matcher.afterWakePhrase("헤이 아리야 홈으로 가"))

        // English Latin
        assertTrue(matcher.containsWakePhrase("Hey Aria"))
        assertTrue(matcher.containsWakePhrase("hey aria"))
        assertTrue(matcher.containsWakePhrase("HEY ARIA"))
        assertEquals("설정 열어", matcher.afterWakePhrase("Hey Aria 설정 열어"))
        assertEquals("설정 열어", matcher.afterWakePhrase("hey aria, 설정 열어"))

        // Mixed Korean/Latin
        assertTrue(matcher.containsWakePhrase("헤이 Aria"))
        assertTrue(matcher.containsWakePhrase("Hey 아리아"))
        assertTrue(matcher.containsWakePhrase("Hey 아리야"))
        assertEquals("소리 켜", matcher.afterWakePhrase("헤이 Aria 소리 켜"))
        assertEquals("소리 꺼", matcher.afterWakePhrase("Hey 아리아 소리 꺼"))

        // Liquid coda assimilation phonetic variants
        assertTrue(matcher.containsWakePhrase("헤이 알리아"))
        assertTrue(matcher.containsWakePhrase("헤이 알리야"))
        assertEquals("정면 보정", matcher.afterWakePhrase("헤이 알리아 정면 보정"))
        assertEquals("정면 보정", matcher.afterWakePhrase("헤이 알리야 정면 보정"))
        // Pure Korean compact command without spaces
        assertEquals("설정열어", matcher.afterWakePhrase("헤이아리아설정열어"))
        assertEquals("설정 열어", matcher.afterWakePhrase("헤이아리아 설정 열어"))
    }

    @Test
    fun matcherAllowsParticlesAfterWakeNameForRecall() {
        val matcher = KoreanWakePhraseMatcher()

        assertEquals("는 누구야", matcher.afterWakePhrase("헤이 아리아는 누구야"))
        assertEquals("가 뭐야", matcher.afterWakePhrase("헤이 아리아가 뭐야"))
        assertEquals("라고 했어", matcher.afterWakePhrase("헤이 아리아라고 했어"))
        assertEquals("는 누구야", matcher.afterWakePhrase("헤이 알리아는 누구야"))
        assertEquals("는 누구야", matcher.afterWakePhrase("Hey Aria는 누구야"))
    }

    @Test
    fun standaloneNamesAreAcceptedAndUnrelatedSpeechIsRejected() {
        val matcher = KoreanWakePhraseMatcher()

        assertEquals("설정 열어", matcher.afterWakePhrase("아리아 설정 열어"))
        assertNull(matcher.afterWakePhrase("헤이"))
        assertNull(matcher.afterWakePhrase("설정 열어"))
        assertNull(matcher.afterWakePhrase("오늘 날씨 어때"))
        assertEquals("홈으로 가", matcher.afterWakePhrase("알리아 홈으로 가"))
    }

    @Test
    fun sessionSurvivesConsecutiveStandbyFailuresWithoutFatalTermination() {
        val time = FakeScheduler()
        val driver = TestDriver()
        var fatalCode: Int? = null

        val session = VoiceListeningSession(
            driver = driver,
            scheduler = time,
            listener = object : VoiceListeningSession.Listener {
                override fun state(snapshot: ListeningSnapshot) = Unit
                override fun inputStarted() = Unit
                override fun wakeDetected() = Unit
                override fun command(text: String) = Unit
                override fun emptyInput() = Unit
                override fun fatal(errorCode: Int) { fatalCode = errorCode }
            },
            wakeDetector = null, // Fallback ASR wake path
        )

        session.start()
        assertEquals(VoicePhase.Initializing, session.snapshot.phase)

        // Simulate 10 consecutive SpeechRecognizer busy / client errors during standby wake listening
        for (i in 1..10) {
            time.advance(300)
            driver.listener.failed(RecognitionFailure.Busy)
            time.advance(15_000)
        }

        // Must NOT trigger fatal error in Mode.Wake
        assertNull("Standby errors must not cause fatal service death", fatalCode)
        assertTrue(session.snapshot.phase != VoicePhase.Error)

        // When mic becomes free and driver reports ready, session recovers cleanly
        driver.listener.ready()
        assertEquals(VoicePhase.Waiting, session.snapshot.phase)
        assertEquals(0, session.snapshot.retryCount)
    }

    @Test
    fun ttsInterruptionSafelyPausesAndResumesWakeListening() {
        val time = FakeScheduler()
        val driver = TestDriver()
        var wakeDetectedCount = 0
        var commandReceived: String? = null

        val session = VoiceListeningSession(
            driver = driver,
            scheduler = time,
            listener = object : VoiceListeningSession.Listener {
                override fun state(snapshot: ListeningSnapshot) = Unit
                override fun inputStarted() = Unit
                override fun wakeDetected() { wakeDetectedCount++ }
                override fun command(text: String) { commandReceived = text }
                override fun emptyInput() = Unit
                override fun fatal(errorCode: Int) = Unit
            },
            wakeDetector = null,
        )

        session.start()
        time.advance(300)
        driver.listener.ready()

        // TTS starts speaking cloud response
        session.playbackChanged(true)
        assertEquals(VoicePhase.Speaking, session.snapshot.phase)

        // Recognition results during TTS are fenced / rejected
        driver.listener.result("헤이 아리아")
        assertEquals(0, wakeDetectedCount)

        // TTS finishes
        session.playbackChanged(false)
        time.advance(300)
        driver.listener.ready()
        assertEquals(VoicePhase.Waiting, session.snapshot.phase)

        // User speaks wake phrase and command
        driver.listener.result("Hey Aria 설정 열어")
        assertEquals(1, wakeDetectedCount)
        assertEquals("설정 열어", commandReceived)
    }

    @Test
    fun cancellationSafelyReturnsToWakeWaiting() {
        val time = FakeScheduler()
        val driver = TestDriver()

        val session = VoiceListeningSession(
            driver = driver,
            scheduler = time,
            listener = object : VoiceListeningSession.Listener {
                override fun state(snapshot: ListeningSnapshot) = Unit
                override fun inputStarted() = Unit
                override fun wakeDetected() = Unit
                override fun command(text: String) = Unit
                override fun emptyInput() = Unit
                override fun fatal(errorCode: Int) = Unit
            },
            wakeDetector = null,
        )

        session.start()
        time.advance(300)
        driver.listener.ready()

        session.cancelInput()
        time.advance(300)
        driver.listener.ready()
        assertEquals(VoicePhase.Waiting, session.snapshot.phase)
    }

    @Test
    fun safeDiagnosticsDoNotContainRawAudioOrCredentials() {
        val captured = mutableListOf<Pair<WakeDiagnostics.Event, String>>()
        WakeDiagnostics.diagnosticListener = { event, detail -> captured += (event to detail) }

        WakeDiagnostics.emit(WakeDiagnostics.Event.NO_AUDIO, "AUDIO_EOF")
        WakeDiagnostics.emit(WakeDiagnostics.Event.MODEL_NOT_READY, "ASSETS_MISSING")
        WakeDiagnostics.emit(WakeDiagnostics.Event.NO_KEYWORD, "NO_MATCH")
        WakeDiagnostics.emit(WakeDiagnostics.Event.STOPPED_WAITING, "STOP")
        WakeDiagnostics.emit(WakeDiagnostics.Event.WAKE_DETECTED, "KWS_HIT")
        WakeDiagnostics.emit(WakeDiagnostics.Event.RESTARTING, "RESTART")

        assertEquals(6, captured.size)
        captured.forEach { (_, detail) ->
            assertFalse(detail.contains("sk-"))
            assertFalse(detail.contains("Bearer"))
            assertFalse(detail.contains("token"))
            assertFalse(detail.contains("password"))
        }

        WakeDiagnostics.diagnosticListener = null
    }
}
