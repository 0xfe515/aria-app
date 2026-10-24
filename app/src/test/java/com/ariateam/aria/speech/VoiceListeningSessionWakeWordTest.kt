package com.ariateam.aria.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceListeningSessionWakeWordTest {

    private class FakeWakeDetector(
        override var isAvailable: Boolean = true,
    ) : WakeWordDetector {
        var listener: WakeWordDetector.Listener? = null
        private val startedListeners = mutableListOf<WakeWordDetector.Listener>()
        var starts = 0
        var stops = 0
        var releases = 0
        var failOnStart: Throwable? = null

        override fun start(listener: WakeWordDetector.Listener) {
            starts++
            val failure = failOnStart
            if (failure != null) {
                throw failure
            }
            this.listener = listener
            startedListeners += listener
        }

        override fun stop() {
            stops++
            listener = null
        }

        override fun release() {
            releases++
            stop()
            isAvailable = false
        }

        fun triggerWakeHit(keyword: String = "헤이아리아") {
            val callback = listener ?: return
            listener = null
            callback.onWakeWordDetected(keyword)
        }

        fun triggerStaleWakeHit(keyword: String = "헤이아리아") {
            startedListeners.lastOrNull()?.onWakeWordDetected(keyword)
        }

        fun triggerError(error: Throwable) {
            listener?.onError(error)
        }
    }

    private class FakeDriver : RecognitionDriver {
        lateinit var current: RecognitionDriver.Listener
        var starts = 0
        var finishes = 0
        var closes = 0
        var shutdowns = 0
        private var active = false

        override fun start(listener: RecognitionDriver.Listener) {
            check(!active) { "Overlapping driver start" }
            active = true
            starts++
            current = listener
        }

        override fun finish() {
            finishes++
        }

        override fun close() {
            if (active) {
                active = false
                closes++
            }
        }

        override fun shutdown() {
            close()
            shutdowns++
        }
    }

    private class FakeScheduler : RecognitionScheduler {
        private data class Task(val at: Long, val order: Int, val action: () -> Unit, var cancelled: Boolean = false)
        private var clock = 0L
        private var order = 0
        private val tasks = mutableListOf<Task>()

        override fun now(): Long = clock

        override fun schedule(delayMillis: Long, action: () -> Unit): ScheduledRecognitionTask {
            val task = Task(clock + delayMillis, order++, action)
            tasks += task
            return ScheduledRecognitionTask { task.cancelled = true }
        }

        fun advance(delta: Long) {
            val target = clock + delta
            while (true) {
                val next = tasks.filter { !it.cancelled && it.at <= target }
                    .minWithOrNull(compareBy<Task> { it.at }.thenBy { it.order }) ?: break
                tasks.remove(next)
                clock = next.at
                next.action()
            }
            clock = target
        }
    }

    private class Harness(
        val detector: FakeWakeDetector? = FakeWakeDetector(),
        val cooldownMillis: Long = 1_500L,
    ) {
        val time = FakeScheduler()
        val driver = FakeDriver()
        val states = mutableListOf<ListeningSnapshot>()
        val commands = mutableListOf<String>()
        val fatals = mutableListOf<Int>()
        var wakes = 0
        var inputs = 0
        var empty = 0

        val session = VoiceListeningSession(
            driver = driver,
            scheduler = time,
            listener = object : VoiceListeningSession.Listener {
                override fun state(snapshot: ListeningSnapshot) { states += snapshot }
                override fun inputStarted() { inputs++ }
                override fun wakeDetected() { wakes++ }
                override fun command(text: String) { commands += text }
                override fun emptyInput() { empty++ }
                override fun fatal(errorCode: Int) { fatals += errorCode }
            },
            wakeDetector = detector,
            wakeCooldownMillis = cooldownMillis,
        )

        fun start() {
            session.start()
        }
    }

    @Test
    fun wakeWordHitTransitionsToCommandRecognitionOnDriver() {
        val h = Harness()
        h.start()

        // In wake phase, detector is active, driver is NOT yet started (mic free for KWS)
        assertEquals(1, h.detector?.starts)
        assertEquals(0, h.driver.starts)
        assertEquals(VoicePhase.Waiting, h.session.snapshot.phase)
        assertFalse(h.session.snapshot.wakeFallback)

        // Wake word hits
        h.detector?.triggerWakeHit("헤이아리아")
        h.detector?.triggerStaleWakeHit("헤이아리아")

        assertEquals(1, h.wakes)
        assertEquals(1, h.inputs)
        // Detector must be stopped to release mic before driver starts
        assertTrue((h.detector?.stops ?: 0) >= 1)
        // Driver must now be started for command dictation
        assertEquals(1, h.driver.starts)

        // Command recognizer reports ready
        h.driver.current.ready()
        assertEquals(VoicePhase.Dictating, h.session.snapshot.phase)

        // Command text spoken and confirmed
        h.driver.current.result("설정 열어")
        assertEquals(listOf("설정 열어"), h.commands)

        // After command confirmed, session schedules restart and returns to KWS wake listening
        h.time.advance(300)
        assertEquals(2, h.detector?.starts)
    }

    @Test
    fun cooldownHitRearmsOneShotDetectorAfterRemainingCooldown() {
        val h = Harness(cooldownMillis = 2_000L)
        h.start()

        h.detector?.triggerWakeHit("헤이아리아")
        assertEquals(1, h.wakes)

        // Command completes
        h.driver.current.ready()
        h.driver.current.result("홈으로 가")
        assertEquals(listOf("홈으로 가"), h.commands)

        h.time.advance(300)
        // A second one-shot detection is rejected, then rearmed after the remaining 1700ms.
        h.detector?.triggerWakeHit("헤이아리아")
        assertEquals(1, h.wakes)
        assertEquals(2, h.detector?.starts)
        assertEquals(VoicePhase.Reconnecting, h.session.snapshot.phase)
        assertEquals(null, h.detector?.listener)

        h.time.advance(1_699)
        assertEquals(2, h.detector?.starts)
        h.time.advance(1)
        assertEquals(3, h.detector?.starts)
        assertEquals(VoicePhase.Waiting, h.session.snapshot.phase)

        // Exactly at the cooldown boundary, a fresh one-shot detection is accepted.
        h.detector?.triggerWakeHit("헤이아리아")
        assertEquals(2, h.wakes)
        assertEquals(2, h.driver.starts)
    }

    @Test
    fun stopCancelsCooldownRearm() {
        val h = Harness()
        h.start()
        h.detector?.triggerWakeHit()
        h.driver.current.ready()
        h.driver.current.result("설정 열어")
        h.time.advance(VoiceLimits.RESTART_MILLIS)
        h.detector?.triggerWakeHit()

        assertEquals(VoicePhase.Reconnecting, h.session.snapshot.phase)
        assertEquals(2, h.detector?.starts)
        h.session.stop()
        h.time.advance(VoiceListeningSession.DEFAULT_WAKE_COOLDOWN_MILLIS)

        assertEquals(VoicePhase.Stopped, h.session.snapshot.phase)
        assertEquals(2, h.detector?.starts)
        assertEquals(null, h.detector?.listener)
    }

    @Test
    fun ttsDefersCooldownRearmUntilPlaybackEnds() {
        val h = Harness()
        h.start()
        h.detector?.triggerWakeHit()
        h.driver.current.ready()
        h.driver.current.result("설정 열어")
        h.time.advance(VoiceLimits.RESTART_MILLIS)
        h.detector?.triggerWakeHit()

        assertEquals(VoicePhase.Reconnecting, h.session.snapshot.phase)
        h.session.playbackChanged(true)
        h.time.advance(VoiceListeningSession.DEFAULT_WAKE_COOLDOWN_MILLIS * 2)

        assertEquals(VoicePhase.Speaking, h.session.snapshot.phase)
        assertEquals(2, h.detector?.starts)

        h.session.playbackChanged(false)
        h.time.advance(VoiceLimits.RESTART_MILLIS)
        assertEquals(3, h.detector?.starts)
        h.detector?.triggerWakeHit()

        assertEquals(2, h.wakes)
        assertEquals(2, h.driver.starts)
    }

    @Test
    fun ttsPlaybackSuppressesWakeWordDetection() {
        val h = Harness()
        h.start()

        // TTS starts speaking
        h.session.playbackChanged(true)
        assertEquals(VoicePhase.Speaking, h.session.snapshot.phase)

        // Wake trigger during speaking is ignored
        h.detector?.triggerWakeHit("헤이아리아")
        assertEquals(0, h.wakes)
        assertEquals(0, h.driver.starts)

        // TTS ends
        h.session.playbackChanged(false)
        h.time.advance(300)

        // Wake detector resumes
        assertEquals(VoicePhase.Waiting, h.session.snapshot.phase)
        h.detector?.triggerWakeHit("헤이아리아")
        assertEquals(1, h.wakes)
        assertEquals(1, h.driver.starts)
    }

    @Test
    fun gracefulFallbackWhenWakeDetectorThrowsOnStart() {
        val detector = FakeWakeDetector().apply {
            failOnStart = IllegalStateException("Native library missing")
        }
        val h = Harness(detector = detector)
        h.start()

        // Should fall back to Android driver without crashing
        assertTrue(h.session.snapshot.wakeFallback)
        h.time.advance(300)
        assertEquals(1, h.driver.starts)

        // Android driver wake path works with KoreanWakePhraseMatcher
        h.driver.current.ready()
        h.driver.current.result("헤이 아리아 설정 열어")
        assertEquals(1, h.wakes)
        assertEquals(listOf("설정 열어"), h.commands)
    }

    @Test
    fun gracefulFallbackWhenWakeDetectorInvokesError() {
        val h = Harness()
        h.start()

        assertEquals(0, h.driver.starts)
        assertFalse(h.session.snapshot.wakeFallback)

        // Runtime error in detector (e.g. mic permission revoked)
        h.detector?.triggerError(RuntimeException("AudioRecord read failed"))

        // Falls back to Android driver
        assertTrue(h.session.snapshot.wakeFallback)
        h.time.advance(300)
        assertEquals(1, h.driver.starts)
    }

    @Test
    fun gracefulFallbackWhenWakeDetectorIsNull() {
        val h = Harness(detector = null)
        h.start()

        assertTrue(h.session.snapshot.wakeFallback)
        assertEquals(1, h.driver.starts)
        h.driver.current.ready()
        h.driver.current.result("헤이 아리야 소리 켜")
        assertEquals(1, h.wakes)
        assertEquals(listOf("소리 켜"), h.commands)
    }

    @Test
    fun manualDictationBypassesWakeDetectorAndUsesAndroidDriverDirectly() {
        val h = Harness()
        h.session.beginManual()

        // Wake detector is not used for manual dictation
        assertEquals(0, h.detector?.starts)
        h.time.advance(300)
        assertEquals(1, h.driver.starts)

        h.driver.current.ready()
        assertEquals(VoicePhase.Dictating, h.session.snapshot.phase)
        h.driver.current.result("받아쓰기 텍스트")
        assertEquals(listOf("받아쓰기 텍스트"), h.commands)
    }

    @Test
    fun followUpDictationBypassesWakeDetectorAndUsesAndroidDriverDirectly() {
        val h = Harness()
        h.start()
        h.session.beginFollowUp()

        assertEquals(1, h.detector?.stops)
        h.time.advance(300)
        assertEquals(1, h.driver.starts)

        h.driver.current.ready()
        h.driver.current.result("후속 답변")
        assertEquals(listOf("후속 답변"), h.commands)
    }

    @Test
    fun shutdownReleasesBothDetectorAndDriver() {
        val h = Harness()
        h.start()

        h.session.shutdown()
        assertEquals(1, h.detector?.releases)
        assertEquals(1, h.driver.shutdowns)
        assertEquals(VoicePhase.Stopped, h.session.snapshot.phase)
    }
}
