package com.ariateam.aria.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageClientTest {
    @Test fun preparingAnAlreadyReadyModelPreservesStructuredReadyStatus() {
        val h = Harness()
        val statuses = mutableListOf<LanguageStatus>()
        val client = LanguageClient(
            transport = h.transport,
            scheduler = h.time,
            changed = {},
            statusChanged = { status, _ -> statuses += status },
        )
        client.prepare(MODEL_PATH)
        h.transport.connectCurrent()
        h.transport.prepareReady()

        client.prepare(MODEL_PATH)

        assertTrue(client.ready)
        assertEquals(LanguageStatus.Ready, client.currentStatus)
        assertEquals(listOf(LanguageStatus.Preparing, LanguageStatus.Ready), statuses)
    }

    @Test fun statusCallbackTracksStructuredReadinessTransitions() {
        val h = Harness()
        val structured = mutableListOf<LanguageStatus>()
        val client = LanguageClient(
            transport = h.transport,
            scheduler = h.time,
            changed = {},
            statusChanged = { status, _ -> structured += status }
        )
        assertEquals(LanguageStatus.Missing, client.currentStatus)
        client.prepare(MODEL_PATH)
        assertEquals(LanguageStatus.Preparing, client.currentStatus)
        assertEquals(listOf(LanguageStatus.Preparing), structured)

        h.transport.connectCurrent()
        h.transport.prepareReady()
        assertEquals(LanguageStatus.Ready, client.currentStatus)
        assertEquals(listOf(LanguageStatus.Preparing, LanguageStatus.Ready), structured)
    }

    @Test fun statusCallbackTracksPreparationFailure() {
        val h = Harness()
        val structured = mutableListOf<LanguageStatus>()
        val client = LanguageClient(
            transport = h.transport,
            scheduler = h.time,
            changed = {},
            statusChanged = { status, _ -> structured += status }
        )
        client.prepare(MODEL_PATH)
        h.transport.connectCurrent()
        h.transport.listener.onResult(h.transport.currentTicket, 0L, "", "memory")
        assertEquals(LanguageStatus.Failed, client.currentStatus)
        assertEquals(listOf(LanguageStatus.Preparing, LanguageStatus.Failed), structured)
    }

    @Test fun successfulPreparationAndColdThenWarmInferenceSwitchDeadlines() {
        val h = Harness()
        h.client.prepare(MODEL_PATH)
        assertTrue(h.client.preparing)
        h.transport.connectCurrent()
        h.time.advance(1_200)
        h.transport.prepareReady()
        assertTrue(h.client.ready)
        assertFalse(h.client.busy)
        assertFalse(h.client.warmed)
        assertEquals("Gemma 사용 가능 · 준비 1200ms", h.client.lastStatus)

        val coldOutcomes = mutableListOf<LanguageOutcome>()
        h.client.interpretOutcome("""{"utterance":"소리가 거슬려","lastTarget":null}""") { coldOutcomes += it }
        assertTrue(h.client.busy)
        assertEquals("Gemma 해석 중 · 최대 30초", h.client.lastStatus)

        // Cold first inference is allowed past 20s up to 30s without timing out.
        h.time.advance(25_000)
        assertTrue(coldOutcomes.isEmpty())
        h.transport.replyCurrent("""{"command":"SoundOff"}""")
        assertEquals(listOf(LanguageOutcome.Success("""{"command":"SoundOff"}""", 25_000)), coldOutcomes)
        assertTrue(h.client.ready)
        assertFalse(h.client.busy)
        assertTrue(h.client.warmed)
        assertEquals("Gemma 사용 가능 · 해석 25000ms", h.client.lastStatus)

        // Timer was cancelled on success.
        h.time.advance(60_000)
        assertEquals(1, coldOutcomes.size)

        // Subsequent warm inference uses the 20s warm deadline.
        val warmOutcomes = mutableListOf<LanguageOutcome>()
        h.client.interpretOutcome("""{"utterance":"설정 보여줘","lastTarget":null}""") { warmOutcomes += it }
        assertEquals("Gemma 해석 중 · 최대 20초", h.client.lastStatus)
        h.time.advance(4_500)
        h.transport.replyCurrent("""{"command":"Settings"}""")
        assertEquals(listOf(LanguageOutcome.Success("""{"command":"Settings"}""", 4_500)), warmOutcomes)
    }

    @Test fun inferenceTimeoutGatesRetryUntilOldProcessTerminatesAndIgnoresLateCallbacks() {
        val h = Harness()
        h.transport.autoConfirmRelease = false
        h.prepareReady()

        val outcomes = mutableListOf<LanguageOutcome>()
        h.client.interpretOutcome("""{"utterance":"소리가 좀 시끄러워","lastTarget":null}""") { outcomes += it }
        val timedOutTicket = h.transport.currentTicket
        val timedOutRequestId = h.transport.currentRequestId

        h.time.advance(29_999)
        assertTrue(outcomes.isEmpty())
        h.time.advance(1)

        assertEquals(listOf(LanguageOutcome.Failure(FailureReason.ResponseTimeout)), outcomes)
        assertFalse(h.client.ready)
        assertFalse(h.client.busy)
        assertEquals(listOf(timedOutTicket), h.transport.releasedTickets)
        assertEquals("Gemma 응답 시간 초과 · 다음 명령 시 다시 준비", h.client.lastStatus)

        // No automatic background restart loop occurs when time passes.
        h.time.advance(120_000)
        assertEquals(1, h.transport.connectAttempts)

        // Late result or late disconnect from the timed-out session is ignored.
        h.transport.listener.onResult(timedOutTicket, timedOutRequestId, """{"command":"SoundOff"}""", "")
        h.transport.listener.onDisconnected(timedOutTicket, "service_disconnected")
        assertEquals(1, outcomes.size)
        assertFalse(h.client.ready)

        // Explicit retry requested BEFORE the retiring native process has terminated must wait
        // for confirmed binder death so the queued oneway release() cannot kill the replacement.
        h.client.prepare(MODEL_PATH)
        assertTrue(h.client.preparing)
        assertEquals(1, h.transport.connectAttempts)
        assertEquals("Gemma 이전 프로세스 정리 중 · 완료 후 준비", h.client.lastStatus)

        // Once the old process binder death is confirmed, the replacement connection starts.
        h.transport.confirmReleased(timedOutTicket)
        val replacementTicket = h.transport.currentTicket
        assertEquals(2, h.transport.connectAttempts)
        h.transport.connectCurrent()
        // Late disconnect from the old ticket must not tear down the replacement ticket.
        h.transport.listener.onDisconnected(timedOutTicket, "binding_died")
        h.transport.prepareReady()
        assertTrue(h.client.ready)
        assertEquals(listOf(timedOutTicket), h.transport.releasedTickets)

        var retryOutput: String? = "unset"
        h.client.interpret("""{"utterance":"설정 열어줘","lastTarget":null}""") { retryOutput = it }
        h.transport.listener.onResult(timedOutTicket, timedOutRequestId, """{"command":"Home"}""", "")
        assertEquals("unset", retryOutput)
        h.transport.replyCurrent("""{"command":"Settings"}""")
        assertEquals("""{"command":"Settings"}""", retryOutput)
        assertEquals(replacementTicket, h.transport.currentTicket)
    }

    @Test fun preparationTimeoutReleasesProcessRejectsLateReadyAndAllowsExplicitRetry() {
        val h = Harness()
        h.client.prepare(MODEL_PATH)
        val firstTicket = h.transport.currentTicket
        h.transport.connectCurrent()

        h.time.advance(59_999)
        assertTrue(h.client.preparing)
        h.time.advance(1)

        assertFalse(h.client.preparing)
        assertFalse(h.client.ready)
        assertFalse(h.client.busy)
        assertEquals(listOf(firstTicket), h.transport.releasedTickets)
        assertEquals("Gemma 준비 시간 초과 · 다음 명령 시 다시 준비", h.client.lastStatus)

        // Late ready callback from the timed-out preparation must not mark client ready.
        h.transport.listener.onResult(firstTicket, 0L, "ready", "")
        assertFalse(h.client.ready)

        // Explicit re-prepare succeeds.
        h.prepareReady()
        assertTrue(h.client.ready)
        assertEquals(2, h.transport.connectAttempts)
    }

    @Test fun preparationErrorAndBindFailureDisconnectCleanlyAndAllowRetry() {
        val h = Harness()
        h.client.prepare(MODEL_PATH)
        val firstTicket = h.transport.currentTicket
        h.transport.connectCurrent()
        h.transport.listener.onResult(firstTicket, 0L, "", "initialization")

        assertFalse(h.client.ready)
        assertFalse(h.client.busy)
        assertFalse(h.client.preparing)
        assertEquals(listOf(firstTicket), h.transport.releasedTickets)
        assertEquals("Gemma 준비 실패 · 다음 명령 시 다시 준비", h.client.lastStatus)

        // Next prepare fails immediately on bindService returning false.
        h.transport.connectResult = false
        h.client.prepare(MODEL_PATH)
        val secondTicket = h.transport.currentTicket
        assertFalse(h.client.ready)
        assertFalse(h.client.busy)
        assertEquals(listOf(firstTicket, secondTicket), h.transport.releasedTickets)
        assertEquals("Gemma 연결 오류 · 다음 명령 시 다시 준비", h.client.lastStatus)

        // Subsequent explicit prepare succeeds when bindService recovers.
        h.transport.connectResult = true
        h.prepareReady()
        assertTrue(h.client.ready)
    }

    @Test fun processDeathDuringInferenceNotifiesOnceAndRecoversAfterExplicitPrepare() {
        val h = Harness()
        h.prepareReady()

        val outcomes = mutableListOf<LanguageOutcome>()
        h.client.interpretOutcome("""{"utterance":"상태 좀 봐줘","lastTarget":null}""") { outcomes += it }
        val diedTicket = h.transport.currentTicket

        h.transport.listener.onDisconnected(diedTicket, "binding_died")
        assertEquals(
            listOf(LanguageOutcome.Failure(FailureReason.ConnectionError, "binding_died")),
            outcomes,
        )
        assertFalse(h.client.ready)
        assertFalse(h.client.busy)
        assertEquals(listOf(diedTicket), h.transport.releasedTickets)

        // Timeout timer was cancelled on disconnect and does not fire later.
        h.time.advance(60_000)
        assertEquals(1, outcomes.size)

        // Recoverable via explicit prepare.
        h.prepareReady()
        var recovered: String? = null
        h.client.interpret("""{"utterance":"상태 좀 봐줘","lastTarget":null}""") { recovered = it }
        h.transport.replyCurrent("""{"command":"Status"}""")
        assertEquals("""{"command":"Status"}""", recovered)
    }

    @Test fun overlappingRequestIsDeniedWithoutCorruptingInFlightRequestAndCloseCancelsPending() {
        val h = Harness()
        h.prepareReady()

        val firstOutcomes = mutableListOf<LanguageOutcome>()
        val overlapOutcomes = mutableListOf<LanguageOutcome>()
        h.client.interpretOutcome("""{"utterance":"소리가 거슬려","lastTarget":null}""") { firstOutcomes += it }
        h.client.interpretOutcome("""{"utterance":"설정 열어","lastTarget":null}""") { overlapOutcomes += it }

        assertEquals(listOf(LanguageOutcome.Failure(FailureReason.Busy)), overlapOutcomes)
        assertEquals(1, h.transport.interpretCalls)
        assertTrue(firstOutcomes.isEmpty())

        // Original in-flight request still completes normally.
        h.transport.replyCurrent("""{"command":"SoundOff"}""")
        assertEquals(listOf(LanguageOutcome.Success("""{"command":"SoundOff"}""", 0)), firstOutcomes)

        // Starting another request and closing client cancels timer, drops callback, and releases service.
        val closedOutcomes = mutableListOf<LanguageOutcome>()
        var closedLegacy: String? = "initial"
        h.client.interpret("""{"utterance":"홈 화면","lastTarget":null}""") { closedLegacy = it }
        val closedTicket = h.transport.currentTicket
        val closedRequestId = h.transport.currentRequestId
        h.client.close()

        assertFalse(h.client.ready)
        assertFalse(h.client.busy)
        assertEquals(listOf(closedTicket), h.transport.releasedTickets)

        h.transport.listener.onResult(closedTicket, closedRequestId, """{"command":"Home"}""", "")
        h.time.advance(60_000)
        assertEquals("initial", closedLegacy)
        assertTrue(closedOutcomes.isEmpty())

        // Calling interpret while not ready fails immediately with NotReady / null.
        var unreadyOutput: String? = "non-null"
        h.client.interpret("""{"utterance":"홈 화면","lastTarget":null}""") { unreadyOutput = it }
        assertNull(unreadyOutput)
    }

    @Test fun cpuThreadSelectionAndLatencyBoundsStayWithinConfiguredLimits() {
        assertEquals(2, LanguageService.cpuThreadCount(1))
        assertEquals(2, LanguageService.cpuThreadCount(2))
        assertEquals(4, LanguageService.cpuThreadCount(4))
        assertEquals(4, LanguageService.cpuThreadCount(8))
        assertEquals(2048, LanguageLimits.MIN_COMPATIBLE_CONTEXT_TOKENS)
        assertEquals(2048, LanguageLimits.MAX_CONTEXT_TOKENS)
        assertTrue(LanguageLimits.MAX_CONTEXT_TOKENS >= LanguageLimits.MIN_COMPATIBLE_CONTEXT_TOKENS)
        assertEquals(2048, LanguageService.contextTokenBudget(512))
        assertEquals(2048, LanguageService.contextTokenBudget())
        assertTrue(LanguageLimits.MAX_OUTPUT_TOKENS in 16..48)
        assertTrue(LanguageService.SYSTEM_PROMPT.length <= 450)
    }

    private class Harness {
        val time = FakeLanguageScheduler()
        val transport = FakeLanguageTransport()
        val statuses = mutableListOf<String>()
        val client = LanguageClient(
            transport = transport,
            scheduler = time,
            changed = { statuses += it },
        )

        fun prepareReady() {
            client.prepare(MODEL_PATH)
            transport.connectCurrent()
            transport.prepareReady()
        }
    }

    private class FakeLanguageTransport : LanguageTransport {
        lateinit var listener: LanguageTransport.Listener
        var connectResult = true
        var autoConfirmRelease = true
        var connectAttempts = 0
        var prepareCalls = 0
        var interpretCalls = 0
        var currentTicket = 0L
        var currentRequestId = 0L
        var lastPrompt = ""
        val releasedTickets = mutableListOf<Long>()

        override fun connect(ticket: Long, path: String, listener: LanguageTransport.Listener): Boolean {
            connectAttempts++
            currentTicket = ticket
            this.listener = listener
            return connectResult
        }

        override fun prepare(ticket: Long, path: String) {
            prepareCalls++
        }

        override fun interpret(ticket: Long, requestId: Long, prompt: String) {
            interpretCalls++
            currentRequestId = requestId
            lastPrompt = prompt
        }

        override fun release(ticket: Long) {
            releasedTickets += ticket
            if (autoConfirmRelease) {
                listener.onReleased(ticket)
            }
        }

        fun confirmReleased(ticket: Long) {
            listener.onReleased(ticket)
        }

        fun connectCurrent() {
            listener.onConnected(currentTicket)
        }

        fun prepareReady() {
            listener.onResult(currentTicket, 0L, "ready", "")
        }

        fun replyCurrent(output: String, error: String = "") {
            listener.onResult(currentTicket, currentRequestId, output, error)
        }
    }

    private class FakeLanguageScheduler : LanguageScheduler {
        private data class Task(
            val at: Long,
            val order: Int,
            val action: () -> Unit,
            var cancelled: Boolean = false,
        )

        private var clock = 0L
        private var order = 0
        private val tasks = mutableListOf<Task>()

        override fun now(): Long = clock

        override fun schedule(delayMillis: Long, action: () -> Unit): ScheduledLanguageTask {
            val task = Task(clock + delayMillis, order++, action)
            tasks += task
            return ScheduledLanguageTask { task.cancelled = true }
        }

        fun advance(deltaMillis: Long) {
            val target = clock + deltaMillis
            while (true) {
                val next = tasks
                    .filter { !it.cancelled && it.at <= target }
                    .minWithOrNull(compareBy<Task> { it.at }.thenBy { it.order })
                    ?: break
                tasks.remove(next)
                clock = next.at
                next.action()
            }
            clock = target
        }
    }

    companion object {
        private const val MODEL_PATH = "/data/user/0/com.ariateam.aria/files/gemma-e2b.litertlm"
    }
}
