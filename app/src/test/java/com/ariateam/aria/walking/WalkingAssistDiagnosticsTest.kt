package com.ariateam.aria.walking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkingAssistDiagnosticsTest {
    @Test
    fun recordsCaptureToSpeechStagesWithMonotonicSessionAndFrameSequences() {
        val diagnostics = WalkingAssistDiagnostics()
        assertTrue(diagnostics.startSession(4L, 1_000L, "GPU", "aria5"))

        val frame = diagnostics.recordCapture(4L, 1_100L, 1_150L, "GPU", "aria5")
        assertEquals(1L, frame)
        assertTrue(diagnostics.recordDecision(4L, frame!!, 1_200L))
        assertTrue(diagnostics.recordDispatch(4L, frame, 1_300L))
        assertTrue(diagnostics.recordSpeechStart(4L, frame, 1_400L))

        val events = diagnostics.snapshot()
        assertEquals(
            listOf(
                WalkingDiagnosticStage.CAPTURE,
                WalkingDiagnosticStage.DECISION,
                WalkingDiagnosticStage.DISPATCH,
                WalkingDiagnosticStage.SPEECH_START,
            ),
            events.map { it.stage },
        )
        assertEquals(listOf(1L, 2L, 3L, 4L), events.map { it.sequence })
        assertTrue(events.all { it.sessionGeneration == 4L && it.frameSequence == 1L })
        assertTrue(events.all { it.backend == "GPU" && it.model == "aria5" })
        assertTrue(events.all { it.captureTimestampNanos == 1_100L })
        assertTrue(events.zipWithNext().all { (left, right) ->
            left.elapsedRealtimeNanos <= right.elapsedRealtimeNanos
        })
    }

    @Test
    fun rejectedCapturesAndLateOrNonMonotonicEventsCannotCorruptHistory() {
        val diagnostics = WalkingAssistDiagnostics()
        diagnostics.startSession(1L, 1_000L, "CPU", "aria5")

        val firstFrame = diagnostics.recordCapture(1L, 1_100L, 1_150L, "CPU", "aria5")
        assertNull(diagnostics.recordCapture(1L, 1_050L, 1_200L, "CPU", "aria5"))
        assertFalse(diagnostics.recordDecision(1L, firstFrame!!, 1_099L))
        assertFalse(diagnostics.recordDecision(2L, firstFrame, 1_200L))
        assertEquals(1, diagnostics.snapshot().size)
    }

    @Test
    fun suppressionAndLimitedEventsKeepTypedReasonsWithoutPayloads() {
        val diagnostics = WalkingAssistDiagnostics()
        diagnostics.startSession(1L, 1_000L, "GPU", "aria5")
        val frame = diagnostics.recordCapture(1L, 1_100L, 1_100L, "GPU", "aria5")!!

        assertTrue(
            diagnostics.recordSuppression(
                1L,
                frame,
                1_200L,
                WalkingDiagnosticReason.COOLDOWN,
            ),
        )
        assertTrue(
            diagnostics.recordLimited(
                1L,
                frame,
                1_300L,
                WalkingDiagnosticReason.NO_FRESH_RESULT,
            ),
        )
        assertEquals(
            listOf(WalkingDiagnosticReason.COOLDOWN, WalkingDiagnosticReason.NO_FRESH_RESULT),
            diagnostics.snapshot().drop(1).map { it.reason },
        )
    }

    @Test
    fun aNewSessionFencesOldDispatchesAndResetsFrameSequence() {
        val diagnostics = WalkingAssistDiagnostics()
        diagnostics.startSession(1L, 1_000L, "GPU", "aria5")
        val oldFrame = diagnostics.recordCapture(1L, 1_100L, 1_100L, "GPU", "aria5")!!
        diagnostics.startSession(2L, 2_000L, "CPU", "aria5")

        assertFalse(diagnostics.recordDispatch(1L, oldFrame, 2_100L))
        val newFrame = diagnostics.recordCapture(2L, 2_100L, 2_100L, "CPU", "aria5")
        assertEquals(1L, newFrame)
        assertEquals(2L, diagnostics.snapshot().single().sessionGeneration)
        assertEquals("CPU", diagnostics.snapshot().single().backend)
    }

    @Test
    fun stoppedGenerationRejectsLateCaptureDecisionAndSpeechWithoutChangingHistory() {
        val diagnostics = WalkingAssistDiagnostics()
        diagnostics.startSession(7L, 1_000L, "GPU", "aria5")
        val frame = diagnostics.recordCapture(7L, 1_100L, 1_100L, "GPU", "aria5")!!
        assertTrue(diagnostics.recordDecision(7L, frame, 1_200L))
        val beforeStop = diagnostics.snapshot()

        assertTrue(diagnostics.stopSession(7L))
        assertFalse(diagnostics.recordSpeechStart(7L, frame, 1_300L))
        assertFalse(diagnostics.recordDecision(7L, frame, 1_400L))
        assertNull(diagnostics.recordCapture(7L, 1_500L, 1_500L, "GPU", "aria5"))
        assertEquals(beforeStop, diagnostics.snapshot())

        assertTrue(diagnostics.startSession(8L, 1_600L, "GPU", "aria5"))
        assertEquals(1L, diagnostics.recordCapture(8L, 1_700L, 1_700L, "GPU", "aria5"))
    }

    @Test
    fun historyRemainsBounded() {
        val diagnostics = WalkingAssistDiagnostics(capacity = 2)
        diagnostics.startSession(1L, 0L, "GPU", "aria5")
        val frame = diagnostics.recordCapture(1L, 1L, 1L, "GPU", "aria5")!!
        diagnostics.recordDecision(1L, frame, 2L)
        diagnostics.recordDispatch(1L, frame, 3L)

        assertEquals(2, diagnostics.snapshot().size)
        assertEquals(WalkingDiagnosticStage.DECISION, diagnostics.snapshot().first().stage)
    }
}
