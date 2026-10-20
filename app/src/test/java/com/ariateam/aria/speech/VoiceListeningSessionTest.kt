package com.ariateam.aria.speech

import org.junit.Assert.*
import org.junit.Test

class VoiceListeningSessionTest {
    @Test fun sameSessionWakeAndCommandExecuteOnlyAfterFinalConfirmation() {
        val h = Harness()
        h.start()
        val startCount = h.driver.starts
        h.driver.current.partial("헤이 아리아 설정")
        h.driver.current.partial("헤이 아리아 설정 열어")
        assertEquals(1, h.wakes)
        assertEquals(1, h.inputs)
        assertTrue(h.commands.isEmpty())
        assertEquals(startCount, h.driver.starts)
        val old = h.driver.current
        old.result("헤이 아리아 설정 열어")
        old.result("헤이 아리아 홈으로 가")
        old.partial("헤이 아리아 소리 꺼")
        assertEquals(listOf("설정 열어"), h.commands)
        h.time.advance(300)
        assertEquals(startCount + 1, h.driver.starts)
    }

    @Test fun withdrawnPartialWakeNeverExecutesOrLeavesTheSessionActivated() {
        val h = Harness()
        h.start()
        h.driver.current.partial("헤이 아리아 설정")
        h.driver.current.result("다른 사람에게 한 이야기")
        assertTrue(h.commands.isEmpty())
        assertEquals("", h.session.snapshot.transcript)
        h.time.advance(300)
        h.driver.current.ready()
        assertEquals(VoicePhase.Waiting, h.session.snapshot.phase)
    }

    @Test fun ordinaryConversationIsNotPublishedOrForwarded() {
        val h = Harness()
        h.start()
        h.driver.current.partial("개인적인 일반 대화")
        h.driver.current.result("오늘 헤이 아리아라고 불렀어")
        assertEquals(0, h.wakes)
        assertEquals(0, h.inputs)
        assertTrue(h.commands.isEmpty())
        assertTrue(h.states.all { it.transcript.isEmpty() })
    }

    @Test fun finalOnlyProviderStillHandlesWakeAndCommand() {
        val h = Harness()
        h.start()
        h.driver.current.result("헤이 아리야 안내 소리 꺼")
        assertEquals(1, h.wakes)
        assertEquals(listOf("안내 소리 꺼"), h.commands)
    }

    @Test fun wakeOnlyOpensOneBoundedCommandWindowAfterNextReady() {
        val h = Harness()
        h.start()
        h.driver.current.result("헤이 아리아")
        h.time.advance(300)
        h.time.advance(2_000) // Waiting for provider preparation does not consume the speech window.
        h.driver.current.ready()
        h.time.advance(7_999)
        assertEquals(0, h.empty)
        h.time.advance(1)
        assertEquals(1, h.empty)
        assertTrue(h.commands.isEmpty())
    }

    @Test fun commandFollowingWakeDoesNotNeedAnotherWakePhrase() {
        val h = Harness()
        h.start()
        h.driver.current.result("헤이 아리아")
        h.time.advance(300)
        h.driver.current.ready()
        h.driver.current.speechStarted()
        h.driver.current.result("설정 열어")
        assertEquals(listOf("설정 열어"), h.commands)
        assertEquals(1, h.inputs)
        assertEquals(1, h.wakes)
    }

    @Test fun failedCommandRetryDoesNotExtendItsOriginalInputWindow() {
        val h = Harness()
        h.start()
        h.driver.current.result("헤이 아리아")
        h.time.advance(300)
        h.driver.current.ready()
        h.time.advance(7_500)
        h.driver.current.failed(RecognitionFailure.Network)
        h.time.advance(1_000)
        assertEquals(1, h.empty)
        assertTrue(h.commands.isEmpty())
    }

    @Test fun normalNoMatchAndSilenceRestartWithoutErrorOrInputFeedback() {
        val h = Harness()
        h.start()
        repeat(3) {
            h.driver.current.failed(if (it % 2 == 0) RecognitionFailure.NoMatch else RecognitionFailure.NoSpeech)
            assertEquals(0, h.session.snapshot.errorCode)
            assertEquals(0, h.session.snapshot.retryCount)
            h.time.advance(300)
            h.driver.current.ready()
        }
        assertEquals(0, h.wakes)
        assertEquals(0, h.empty)
        assertTrue(h.fatals.isEmpty())
    }

    @Test fun transientErrorsBackOffAndStopAfterFiveRetriesEvenIfReadyIsReceived() {
        val h = Harness()
        h.start()
        listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L).forEachIndexed { index, delay ->
            h.driver.current.failed(RecognitionFailure.Busy)
            assertEquals(index + 1, h.session.snapshot.retryCount)
            assertEquals(h.time.now() + delay, h.session.snapshot.retryAtMillis)
            val before = h.driver.starts
            h.time.advance(delay - 1)
            assertEquals(before, h.driver.starts)
            h.time.advance(1)
            h.driver.current.ready() // Readiness alone must not reset failure history.
        }
        h.driver.current.failed(RecognitionFailure.Busy)
        assertEquals(VoicePhase.Error, h.session.snapshot.phase)
        assertEquals(listOf(VoiceError.RECOGNITION_FAILED), h.fatals)
        val before = h.driver.starts
        h.time.advance(60_000)
        assertEquals(before, h.driver.starts)
    }

    @Test fun playbackDoesNotBypassAnExistingErrorBackoff() {
        val h = Harness()
        h.start()
        h.driver.current.failed(RecognitionFailure.Network)
        h.session.playbackChanged(true)
        h.time.advance(100)
        h.session.playbackChanged(false)
        h.time.advance(899)
        assertEquals(1, h.driver.starts)
        h.time.advance(1)
        assertEquals(2, h.driver.starts)
    }

    @Test fun identicalTextInNewSessionsCanExecuteAgain() {
        val h = Harness()
        h.start()
        repeat(2) {
            h.driver.current.result("헤이 아리아 설정 열어")
            h.time.advance(300)
            h.driver.current.ready()
        }
        assertEquals(listOf("설정 열어", "설정 열어"), h.commands)
    }

    @Test fun normalTerminalEventResetsConsecutiveFailureCount() {
        val h = Harness()
        h.start()
        h.driver.current.failed(RecognitionFailure.Network)
        h.time.advance(1_000)
        h.driver.current.ready()
        h.driver.current.failed(RecognitionFailure.NoMatch)
        h.time.advance(300)
        h.driver.current.ready()
        h.driver.current.failed(RecognitionFailure.Network)
        assertEquals(1, h.session.snapshot.retryCount)
        assertEquals(h.time.now() + 1_000, h.session.snapshot.retryAtMillis)
    }

    @Test fun permanentFailuresNeverRetry() {
        listOf(RecognitionFailure.Permission, RecognitionFailure.Language,
            RecognitionFailure.Unavailable, RecognitionFailure.RateLimit).forEach { error ->
            val h = Harness()
            h.start()
            h.driver.current.failed(error)
            assertEquals(error.errorCode, h.session.snapshot.errorCode)
            assertEquals(VoicePhase.Error, h.session.snapshot.phase)
            h.time.advance(60_000)
            assertEquals(1, h.driver.starts)
        }
    }

    @Test fun stopCancelsBackoffAndRejectsLateEventsEvenAfterExplicitRestart() {
        val h = Harness()
        h.start()
        val old = h.driver.current
        old.failed(RecognitionFailure.Network)
        h.session.stop()
        h.time.advance(60_000)
        old.result("헤이 아리아 설정 열어")
        assertEquals(1, h.driver.starts)
        assertTrue(h.commands.isEmpty())
        h.start()
        old.partial("헤이 아리아 설정")
        old.result("헤이 아리아 설정 열어")
        assertEquals(0, h.wakes)
        assertTrue(h.commands.isEmpty())
    }

    @Test fun manualCompletionWaitsForFinalAndOnlyRequestsStopOnce() {
        val h = Harness()
        h.session.start(manual = true)
        h.session.finishInput() // Can be requested before ready.
        h.driver.current.ready()
        h.session.finishInput()
        assertEquals(1, h.driver.finishes)
        h.driver.current.partial("설정")
        assertTrue(h.commands.isEmpty())
        h.driver.current.result("설정 열어")
        assertEquals(listOf("설정 열어"), h.commands)
    }

    @Test fun manualPartialIsNeverExecutedAfterAnErrorOrFinalTimeout() {
        val h = Harness()
        h.session.start(manual = true)
        h.driver.current.ready()
        h.driver.current.partial("듣기 중지")
        h.session.finishInput()
        h.time.advance(5_000)
        assertTrue(h.commands.isEmpty())
        assertEquals(1, h.session.snapshot.retryCount)
    }

    @Test fun playbackCancelsActiveRecognitionAndManualInputCanInterruptIt() {
        val h = Harness()
        h.start()
        val old = h.driver.current
        h.session.playbackChanged(true)
        assertEquals(VoicePhase.Speaking, h.session.snapshot.phase)
        old.result("헤이 아리아 설정 열어")
        h.time.advance(60_000)
        assertEquals(1, h.driver.starts)
        h.session.beginManual()
        h.time.advance(300)
        h.driver.current.ready()
        h.driver.current.result("설정 열어")
        assertEquals(listOf("설정 열어"), h.commands)
    }

    @Test fun synchronousCommandTtsCancelsTheAlreadyScheduledWakeRestart() {
        val h = Harness()
        h.onCommand = { h.session.playbackChanged(true) }
        h.start()
        h.driver.current.result("헤이 아리아 도움말")
        h.time.advance(60_000)
        assertEquals(1, h.driver.starts)
        assertEquals(VoicePhase.Speaking, h.session.snapshot.phase)
        h.session.playbackChanged(false)
        h.time.advance(300)
        h.driver.current.ready()
        assertEquals(VoicePhase.Waiting, h.session.snapshot.phase)
    }

    @Test fun followUpWindowStartsAtReadyAndStopPreventsResumptionAfterTts() {
        val h = Harness()
        h.start()
        h.session.playbackChanged(true)
        h.session.playbackChanged(false)
        h.session.beginFollowUp()
        h.time.advance(300)
        h.time.advance(2_000)
        h.driver.current.ready()
        h.time.advance(7_000)
        assertEquals(0, h.empty)
        h.driver.current.result("안내 소리 꺼")
        assertEquals(listOf("안내 소리 꺼"), h.commands)
        h.session.playbackChanged(true)
        h.session.stop()
        val before = h.driver.starts
        h.session.playbackChanged(false)
        h.time.advance(60_000)
        assertEquals(before, h.driver.starts)
    }

    @Test fun startupAndFinalWatchdogsRecoverWithoutExecutingPartialText() {
        val h = Harness()
        h.session.start()
        h.time.advance(8_000)
        assertEquals(VoiceError.TIMEOUT, h.session.snapshot.errorCode)
        h.time.advance(1_000)
        h.driver.current.ready()
        h.driver.current.partial("헤이 아리아 설정 열어")
        h.driver.current.speechEnded()
        h.time.advance(5_000)
        assertTrue(h.commands.isEmpty())
    }

    private class Harness {
        val time = FakeScheduler()
        val driver = FakeDriver()
        val states = mutableListOf<ListeningSnapshot>()
        val commands = mutableListOf<String>()
        val fatals = mutableListOf<Int>()
        var wakes = 0
        var inputs = 0
        var empty = 0
        var onCommand: () -> Unit = {}
        val session = VoiceListeningSession(driver, time, object : VoiceListeningSession.Listener {
            override fun state(snapshot: ListeningSnapshot) { states += snapshot }
            override fun inputStarted() { inputs++ }
            override fun wakeDetected() { wakes++ }
            override fun command(text: String) { commands += text; onCommand() }
            override fun emptyInput() { empty++ }
            override fun fatal(errorCode: Int) { fatals += errorCode }
        })
        fun start() { session.start(); driver.current.ready() }
    }

    private class FakeDriver : RecognitionDriver {
        lateinit var current: RecognitionDriver.Listener
        var starts = 0
        var finishes = 0
        private var active = false
        override fun start(listener: RecognitionDriver.Listener) {
            check(!active) { "Overlapping recognizers" }
            active = true
            starts++
            current = listener
        }
        override fun finish() { finishes++ }
        override fun close() { active = false }
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
}
