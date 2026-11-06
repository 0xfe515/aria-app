package com.ariateam.aria.command

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CommandSpeechPlaybackTest {

    private lateinit var fakeEngine: FakeSpeechPlaybackEngine
    private lateinit var fakeScheduler: DeterministicSpeechScheduler
    private var suppressionState = false
    private var changedCallCount = 0

    @Before
    fun setUp() {
        fakeEngine = FakeSpeechPlaybackEngine(available = true, ready = true)
        fakeScheduler = DeterministicSpeechScheduler()
        suppressionState = false
        changedCallCount = 0
        CommandCoordinator.resetGlobalEventIdForTesting(2_000L)
    }

    private fun createSpeech(
        engine: CommandSpeechPlaybackEngine = fakeEngine,
        scheduler: CommandSpeechScheduler = fakeScheduler,
        onChanged: () -> Unit = { changedCallCount++ }
    ): CommandSpeech {
        return CommandSpeech(
            suppress = { suppressionState = it },
            engine = engine,
            scheduler = scheduler,
            changed = onChanged,
        )
    }

    @Test
    fun canceledOrdinarySpeechCannotReportALateSpeechStartAfterHazardPreemption() {
        val speech = createSpeech()
        var walkingSpeechStarted = false
        speech.speak("보행 보조 상태", SpeechPriority.NormalCommand, started = { walkingSpeechStarted = true })
        val canceledToken = fakeEngine.spokenUtteranceIds.last()
        speech.speak("가까운 경로 장애물 주의", SpeechPriority.HazardWarning)

        fakeEngine.notifyStart(canceledToken)
        assertFalse(walkingSpeechStarted)
        assertEquals(SpeechPriority.HazardWarning, speech.currentPriority)
    }

    @Test
    fun activeCloudResponse_interruptedAndStoppedBeforeHazardWarning() {
        val speech = createSpeech()
        var cloudDoneCalled = false
        var hazardDoneCalled = false

        // 1. Start speaking cloud response
        speech.speak("클라우드 답변 중입니다.", SpeechPriority.CloudResponse) { cloudDoneCalled = true }

        assertEquals(1, fakeEngine.spokenTexts.size)
        assertEquals("클라우드 답변 중입니다.", fakeEngine.spokenTexts[0])
        val cloudToken = fakeEngine.spokenUtteranceIds[0]
        assertEquals(SpeechPriority.CloudResponse, speech.currentPriority)
        assertTrue(suppressionState)
        val stopsBeforePreempt = fakeEngine.stopCount

        // 2. High-priority Hazard Warning arrives
        speech.speak("전방 턱 주의!", SpeechPriority.HazardWarning) { hazardDoneCalled = true }

        // Cloud playback was stopped before hazard playback started
        assertTrue(fakeEngine.stopCount > stopsBeforePreempt)
        assertEquals(2, fakeEngine.spokenTexts.size)
        assertEquals("전방 턱 주의!", fakeEngine.spokenTexts[1])
        val hazardToken = fakeEngine.spokenUtteranceIds[1]
        assertTrue(hazardToken != cloudToken)
        assertEquals(SpeechPriority.HazardWarning, speech.currentPriority)
        assertTrue(suppressionState)

        // 3. Hazard Warning finishes playback
        fakeEngine.notifyDone(hazardToken)
        // Drain delay 500ms
        fakeScheduler.advanceTimeBy(500)

        assertTrue(hazardDoneCalled)
        assertFalse(suppressionState)
        assertNull(speech.currentPriority)
    }

    @Test
    fun lowerPriorityCloudResponse_rejectedWhileHazardActive() {
        val speech = createSpeech()
        var hazardDoneCalled = false
        var cloudDoneCalled = false

        // 1. Hazard Warning starts
        speech.speak("전방 장애물 주의", SpeechPriority.HazardWarning) { hazardDoneCalled = true }
        assertEquals(1, fakeEngine.spokenTexts.size)
        assertEquals("전방 장애물 주의", fakeEngine.spokenTexts[0])
        assertEquals(SpeechPriority.HazardWarning, speech.currentPriority)
        val stopsBefore = fakeEngine.stopCount

        // 2. Lower priority Cloud Response arrives while Hazard is still active
        speech.speak("추가 정보입니다.", SpeechPriority.CloudResponse) { cloudDoneCalled = true }

        // Cloud speech rejected immediately: done() called, not passed to engine
        assertTrue(cloudDoneCalled)
        assertEquals(1, fakeEngine.spokenTexts.size) // no second speak on engine
        assertEquals(stopsBefore, fakeEngine.stopCount) // active hazard was NOT stopped
        assertEquals(SpeechPriority.HazardWarning, speech.currentPriority) // priority remains HazardWarning

        // 3. Hazard Warning finishes normally
        fakeEngine.notifyDone(fakeEngine.spokenUtteranceIds[0])
        fakeScheduler.advanceTimeBy(500)
        assertTrue(hazardDoneCalled)
        assertFalse(suppressionState)
        assertNull(speech.currentPriority)
    }

    @Test
    fun staleCompletionFromCanceledCloud_cannotClearHazardPriorityOrUnsuppressPrematurely() {
        val speech = createSpeech()
        var cloudDoneCalled = false
        var hazardDoneCalled = false

        // 1. Cloud response starts
        speech.speak("지하철역 출구 안내입니다.", SpeechPriority.CloudResponse) { cloudDoneCalled = true }
        val staleCloudToken = fakeEngine.spokenUtteranceIds[0]

        // 2. Preempted by Hazard Warning
        speech.speak("차량 접근 주의!", SpeechPriority.HazardWarning) { hazardDoneCalled = true }
        val activeHazardToken = fakeEngine.spokenUtteranceIds[1]
        assertEquals(SpeechPriority.HazardWarning, speech.currentPriority)
        assertTrue(suppressionState)

        // 3. Stale completion from the canceled cloud arrives
        fakeEngine.notifyDone(staleCloudToken)
        fakeScheduler.advanceTimeBy(600)

        // Stale completion must be ignored
        assertFalse("Canceled cloud completion callback must not be invoked", cloudDoneCalled)
        assertTrue("Suppression must remain active for ongoing hazard", suppressionState)
        assertEquals("Priority must remain HazardWarning", SpeechPriority.HazardWarning, speech.currentPriority)

        // 4. Now active hazard completes
        fakeEngine.notifyDone(activeHazardToken)
        fakeScheduler.advanceTimeBy(500)

        assertTrue(hazardDoneCalled)
        assertFalse(suppressionState)
        assertNull(speech.currentPriority)
    }

    @Test
    fun closeAndStop_cancelsPendingCompletionAndResetsSuppression() {
        val speech = createSpeech()
        var doneCalled = false

        speech.speak("테스트 음성입니다.", SpeechPriority.NormalCommand) { doneCalled = true }
        val utteranceId = fakeEngine.spokenUtteranceIds[0]
        assertTrue(suppressionState)

        // Speech finishes on engine, entering 500ms drain delay
        fakeEngine.notifyDone(utteranceId)

        // Before drain delay completes, user / system requests stop()
        speech.stop()

        assertFalse(suppressionState)
        assertNull(speech.currentPriority)

        // Advance drain time: callback must not fire
        fakeScheduler.advanceTimeBy(1_000)
        assertFalse(doneCalled)

        // Close shuts down engine
        speech.close()
        assertTrue(fakeEngine.shutdownCount > 0)
    }

    @Test
    fun coordinatorHazardPath_invokesRealPriorityLogicThroughSeam() {
        val speech = createSpeech()
        val emittedSnapshots = mutableListOf<CommandSnapshot>()
        var vibrationCount = 0

        val coordinator = CommandCoordinator(
            context = null,
            update = { emittedSnapshots.add(it) },
            soundChanged = {},
            suppress = {},
            followUp = {},
            finishFollowUp = {},
            stopListening = {},
            vibrate = { vibrationCount++ },
            clockRealtimeMs = { 10_000L },
            clockElapsedNanos = { 50_000_000_000L },
            speechOverride = speech,
        )
        coordinator.started()

        // 1. Establish cloud question state
        coordinator.finalized("앞에 장애물이 있나요?")
        val qId = coordinator.snapshot.cloudQuestionId
        assertTrue(qId > 0L)

        // Normal-priority question intake confirmation completes before cloud response
        val confirmationUtterance = fakeEngine.spokenUtteranceIds[0]
        fakeEngine.notifyDone(confirmationUtterance)
        fakeScheduler.advanceTimeBy(500)
        assertNull(speech.currentPriority)

        val baseline = fakeEngine.spokenTexts.size

        // 2. Cloud response arrives and begins speech through the real CommandSpeech seam
        coordinator.speakCloudResponse(qId, "앞에 횡단보도가 있습니다.")
        assertEquals(baseline + 1, fakeEngine.spokenTexts.size)
        assertEquals("앞에 횡단보도가 있습니다.", fakeEngine.spokenTexts[baseline])
        val cloudToken = fakeEngine.spokenUtteranceIds[baseline]
        assertEquals(SpeechPriority.CloudResponse, speech.currentPriority)
        assertTrue(suppressionState)

        // 3. Coordinator posts hazard alert -> real priority logic preempts cloud response
        val stopsBeforeHazard = fakeEngine.stopCount
        val vibrationsBeforeHazard = vibrationCount
        coordinator.postHazardAlert(
            session = 5L,
            sequence = 1L,
            timestampNanos = 50_000_000_000L,
            message = "전방 계단 위험",
        )

        // Real priority preemption verified:
        // Cloud was stopped on engine, hazard was spoken, priority elevated to HazardWarning
        assertTrue(fakeEngine.stopCount > stopsBeforeHazard)
        assertEquals(baseline + 2, fakeEngine.spokenTexts.size)
        assertEquals("전방 계단 위험", fakeEngine.spokenTexts[baseline + 1])
        val hazardToken = fakeEngine.spokenUtteranceIds[baseline + 1]
        assertTrue(hazardToken != cloudToken)
        assertEquals(SpeechPriority.HazardWarning, speech.currentPriority)
        assertEquals(1, vibrationCount - vibrationsBeforeHazard)

        // 4. While hazard is active, another cloud response is rejected by real priority ordering
        coordinator.speakCloudResponse(qId, "지연된 클라우드 응답")
        assertEquals(baseline + 2, fakeEngine.spokenTexts.size) // Not added to playback
        assertEquals(SpeechPriority.HazardWarning, speech.currentPriority)

        // 5. Hazard completes and cleans up
        val hazardUtterance = fakeEngine.spokenUtteranceIds[baseline + 1]
        fakeEngine.notifyDone(hazardUtterance)
        fakeScheduler.advanceTimeBy(500)
        assertFalse(suppressionState)
        assertNull(speech.currentPriority)

        coordinator.close()
    }

    @Test
    fun voiceAvailabilityChanges_andUnavailableEngineCompletesImmediatelyWithoutSuppression() {
        fakeEngine.available = false
        val speech = createSpeech()
        assertFalse(speech.available)

        var doneCalled = false
        speech.speak("음성 불가 테스트", SpeechPriority.NormalCommand) { doneCalled = true }

        // Must complete immediately without suppressing microphone
        assertTrue(doneCalled)
        assertFalse(suppressionState)
        assertNull(speech.currentPriority)
        assertEquals(0, fakeEngine.spokenTexts.size)

        // Availability recovery triggers changed callback
        fakeEngine.available = true
        speech.speak("음성 복구 테스트", SpeechPriority.NormalCommand)
        assertTrue(speech.available)
        assertTrue(changedCallCount > 0)
        assertEquals(1, fakeEngine.spokenTexts.size)
    }

    @Test
    fun safetyTimeout_recoversHangingTtsEngine() {
        val speech = createSpeech()
        var doneCalled = false

        speech.speak("멈춘 엔진 테스트", SpeechPriority.NormalCommand) { doneCalled = true }
        assertTrue(suppressionState)
        assertEquals(SpeechPriority.NormalCommand, speech.currentPriority)
        val stopsBeforeTimeout = fakeEngine.stopCount

        // TTS engine hangs: no onDone or onError for 20 seconds
        fakeScheduler.advanceTimeBy(20_000)

        // Safety timeout triggers finish(), stops engine, drains 500ms
        assertTrue(fakeEngine.stopCount > stopsBeforeTimeout)
        fakeScheduler.advanceTimeBy(500)

        assertTrue(doneCalled)
        assertFalse(suppressionState)
        assertNull(speech.currentPriority)
    }
}
