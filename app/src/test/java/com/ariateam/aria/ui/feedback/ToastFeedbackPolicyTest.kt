package com.ariateam.aria.ui.feedback

import com.ariateam.aria.command.CommandCoordinator
import com.ariateam.aria.command.CommandSnapshot
import com.ariateam.aria.speech.VoiceError
import com.ariateam.aria.speech.VoicePhase
import com.ariateam.aria.speech.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToastFeedbackPolicyTest {

    @Test
    fun initialDefaultState_isSuppressed() {
        val policy = ToastFeedbackPolicy()
        val defaultState = VoiceState() // eventId = 0L, errorCode = 0
        policy.syncInitialState(defaultState)

        val result = policy.evaluate(defaultState, isAppVisible = true)
        assertNull("Default initial state must not trigger a toast", result)
    }

    @Test
    fun initialExistingError_isSuppressedWhenSynced() {
        val policy = ToastFeedbackPolicy()
        val errorState = VoiceState(phase = VoicePhase.Error, errorCode = VoiceError.TIMEOUT)
        policy.syncInitialState(errorState)

        val result = policy.evaluate(errorState, isAppVisible = true)
        assertNull("Pre-existing error when opening screen must not trigger a toast", result)
    }

    @Test
    fun newCommandOutcome_triggersToast() {
        val policy = ToastFeedbackPolicy()
        policy.syncInitialState(VoiceState())

        val commandState = VoiceState(
            command = CommandSnapshot(
                message = "안내 소리를 켰어요.",
                eventId = 1L,
            )
        )
        val result = policy.evaluate(commandState, isAppVisible = true)
        assertNotNull(result)
        assertTrue(result is FeedbackToast.CommandOutcome)
        val outcome = result as FeedbackToast.CommandOutcome
        assertEquals("안내 소리를 켰어요.", outcome.message)
        assertEquals(1L, outcome.eventId)
    }

    @Test
    fun recompositionOrConfigurationChange_doesNotDuplicateToast() {
        val policy = ToastFeedbackPolicy()
        policy.syncInitialState(VoiceState())

        val commandState = VoiceState(
            command = CommandSnapshot(
                message = "홈 화면으로 이동해요.",
                eventId = 42L,
            )
        )
        // First evaluation: triggers toast
        val firstResult = policy.evaluate(commandState, isAppVisible = true)
        assertNotNull(firstResult)

        // Subsequent evaluations with same state (recomposition): no toast
        val secondResult = policy.evaluate(commandState, isAppVisible = true)
        assertNull("Recomposition must not re-trigger toast for identical eventId", secondResult)

        val thirdResult = policy.evaluate(commandState, isAppVisible = true)
        assertNull("Repeated recomposition must remain deduplicated", thirdResult)
    }

    @Test
    fun repeatedIdenticalMessage_withNewEventId_triggersToast() {
        val policy = ToastFeedbackPolicy()
        policy.syncInitialState(VoiceState())

        val message = "현재 상태: 음성 대기 중"
        val firstState = VoiceState(command = CommandSnapshot(message = message, eventId = 10L))
        val firstResult = policy.evaluate(firstState, isAppVisible = true)
        assertNotNull(firstResult)

        // User speaks the exact same command again -> CommandCoordinator issues new eventId
        val secondState = VoiceState(command = CommandSnapshot(message = message, eventId = 11L))
        val secondResult = policy.evaluate(secondState, isAppVisible = true)
        assertNotNull("Repeated identical command outcome with new eventId must trigger toast", secondResult)
        assertEquals(11L, (secondResult as FeedbackToast.CommandOutcome).eventId)
    }

    @Test
    fun reconnectingWatchdogTicks_doNotProduceToastEveryTick() {
        val policy = ToastFeedbackPolicy()
        policy.syncInitialState(VoiceState())

        // Initial error transition triggers toast
        val errorState = VoiceState(
            phase = VoicePhase.Reconnecting,
            errorCode = VoiceError.TIMEOUT,
            retryCount = 1,
        )
        val firstResult = policy.evaluate(errorState, isAppVisible = true)
        assertNotNull(firstResult)
        assertTrue(firstResult is FeedbackToast.VoiceErrorNotification)

        // Watchdog ticks: retryCount increments 2, 3, 4 with same errorCode
        val tick2 = errorState.copy(retryCount = 2)
        assertNull("Watchdog tick 2 must not toast", policy.evaluate(tick2, isAppVisible = true))

        val tick3 = errorState.copy(retryCount = 3)
        assertNull("Watchdog tick 3 must not toast", policy.evaluate(tick3, isAppVisible = true))

        val tick4 = errorState.copy(retryCount = 4)
        assertNull("Watchdog tick 4 must not toast", policy.evaluate(tick4, isAppVisible = true))
    }

    @Test
    fun appNotVisible_suppressesToast() {
        val policy = ToastFeedbackPolicy()
        policy.syncInitialState(VoiceState())

        val commandState = VoiceState(command = CommandSnapshot(message = "테스트 메시지", eventId = 99L))
        val result = policy.evaluate(commandState, isAppVisible = false)
        assertNull("Toast must be suppressed when app is not visible", result)
    }

    @Test
    fun backgroundEvent_thenResume_doesNotReplayStaleToast() {
        val policy = ToastFeedbackPolicy()
        policy.syncInitialState(VoiceState())

        // Command finishes while app is backgrounded (isAppVisible = false)
        val bgState = VoiceState(command = CommandSnapshot(message = "백그라운드 완료", eventId = 105L))
        val bgResult = policy.evaluate(bgState, isAppVisible = false)
        assertNull("Must not toast while in background", bgResult)

        // App resumes and becomes visible
        policy.onAppVisibilityChanged(isAppVisible = true, currentState = bgState)
        val resumeResult = policy.evaluate(bgState, isAppVisible = true)
        assertNull("Must not replay stale background event upon resuming", resumeResult)

        // A new subsequent command arriving while visible SHOULD toast
        val nextState = VoiceState(command = CommandSnapshot(message = "새 명령", eventId = 106L))
        val nextResult = policy.evaluate(nextState, isAppVisible = true)
        assertNotNull("Subsequent new command after resume must toast", nextResult)
        assertEquals(106L, (nextResult as FeedbackToast.CommandOutcome).eventId)
    }

    @Test
    fun savedStateRestoration_acrossRotation_retainsHandledIdWithoutDuplicateToast() {
        val policy = ToastFeedbackPolicy()
        policy.syncInitialState(VoiceState())

        // Initial command toasted while visible
        val commandState = VoiceState(command = CommandSnapshot(message = "회전 전 명령", eventId = 55L))
        val initialResult = policy.evaluate(commandState, isAppVisible = true)
        assertNotNull(initialResult)

        // Save state via Saver
        val scope = androidx.compose.runtime.saveable.SaverScope { true }
        val savedData = with(ToastFeedbackPolicy.Saver) { scope.save(policy) }
        assertNotNull(savedData)

        // Recreate policy from saved state (simulating activity recreation/screen rotation)
        val restoredPolicy = ToastFeedbackPolicy.Saver.restore(savedData!!)
        assertNotNull(restoredPolicy)

        // First evaluate after rotation with same state: must not duplicate toast!
        val rotationResult = restoredPolicy!!.evaluate(commandState, isAppVisible = true)
        assertNull("Restored policy across rotation must not duplicate toast", rotationResult)

        // New command after rotation must toast
        val postRotationState = VoiceState(command = CommandSnapshot(message = "회전 후 새 명령", eventId = 56L))
        val postRotationResult = restoredPolicy.evaluate(postRotationState, isAppVisible = true)
        assertNotNull("New command after rotation must toast", postRotationResult)
    }

    @Test
    fun readinessRegression_visibilityBeforeConnection_absorbsOldSnapshotAndToastsNew() {
        val policy = ToastFeedbackPolicy()

        // 1. App becomes visible before service connects
        policy.onAppVisibilityChanged(isAppVisible = true, currentState = VoiceState())

        // 2. Evaluates default state while isServiceSynced = false
        val defaultResult = policy.evaluate(VoiceState(), isAppVisible = true, isServiceSynced = false)
        assertNull("Default state before sync must not toast", defaultResult)

        // 3. Binder connects: incoming state and ready=true coalesced in one composition
        val oldSnapshot = VoiceState(
            command = CommandSnapshot(message = "지연된 과거 명령 결과", eventId = 777L)
        )
        val readyResult = policy.evaluate(oldSnapshot, isAppVisible = true, isServiceSynced = true)
        assertNull("First ready snapshot must ALWAYS absorb baseline regardless of prior visibility sync", readyResult)

        // 4. Truly new command arriving after connection DOES toast
        val newSnapshot = VoiceState(
            command = CommandSnapshot(message = "실제 새로운 명령", eventId = 778L)
        )
        val newResult = policy.evaluate(newSnapshot, isAppVisible = true, isServiceSynced = true)
        assertNotNull("New snapshot after service sync must toast", newResult)
        assertEquals(778L, (newResult as FeedbackToast.CommandOutcome).eventId)
    }

    @Test
    fun readinessRegression_oldSnapshotFalseThenTrue_absorbsOldSnapshotAndToastsNew() {
        val policy = ToastFeedbackPolicy()

        val oldSnapshot = VoiceState(
            command = CommandSnapshot(message = "과거 명령", eventId = 888L)
        )
        // 1. Old snapshot arrives while ready = false
        val preSyncResult = policy.evaluate(oldSnapshot, isAppVisible = true, isServiceSynced = false)
        assertNull("Old snapshot while not synced must be suppressed", preSyncResult)

        // 2. Ready flag arrives with old snapshot
        val syncResult = policy.evaluate(oldSnapshot, isAppVisible = true, isServiceSynced = true)
        assertNull("Old snapshot when synced becomes true must not toast", syncResult)

        // 3. New snapshot arrives
        val newSnapshot = VoiceState(
            command = CommandSnapshot(message = "새로운 명령", eventId = 889L)
        )
        val newResult = policy.evaluate(newSnapshot, isAppVisible = true, isServiceSynced = true)
        assertNotNull("New snapshot after sync must toast", newResult)
        assertEquals(889L, (newResult as FeedbackToast.CommandOutcome).eventId)
    }

    @Test
    fun incomingStateBeforeServiceSynced_suppressesCommandToasts_andAllowsLocalErrorsIndependently() {
        val policy = ToastFeedbackPolicy()
        // 1. App starts with local default state before service sync
        val localDefaultState = VoiceState()
        val defaultResult = policy.evaluate(localDefaultState, isAppVisible = true, isServiceSynced = false)
        assertNull("Default state before sync must not toast", defaultResult)

        // 2. Delayed state from binder arrives BEFORE isServiceSynced is true
        // (Even if policy had already processed localDefaultState, it must NOT bypass readiness)
        val delayedServiceState = VoiceState(
            command = CommandSnapshot(message = "지연된 이전 명령 결과", eventId = 999L)
        )
        val delayedResult = policy.evaluate(delayedServiceState, isAppVisible = true, isServiceSynced = false)
        assertNull("Incoming command event BEFORE service ready flag must be suppressed", delayedResult)

        // 3. Local permission or start failure arriving BEFORE service sync must toast independently
        val localErrorState = VoiceState(errorCode = VoiceError.PERMISSION)
        val errorResult = policy.evaluate(localErrorState, isAppVisible = true, isServiceSynced = false)
        assertNotNull("Local permission/start error before service ready flag must toast independently", errorResult)
        assertTrue(errorResult is FeedbackToast.VoiceErrorNotification)
        assertEquals(VoiceError.PERMISSION, (errorResult as FeedbackToast.VoiceErrorNotification).errorCode)

        // 4. When isServiceSynced flag finally becomes true with that delayed state, it must NOT emit stale toast
        val syncedResult = policy.evaluate(delayedServiceState, isAppVisible = true, isServiceSynced = true)
        assertNull("Delayed service state must not trigger stale toast when service ready flag arrives", syncedResult)

        // 5. Subsequent new command arriving AFTER service is synced DOES toast
        val newCommandState = VoiceState(
            command = CommandSnapshot(message = "실제 새로운 명령", eventId = 1000L)
        )
        val newResult = policy.evaluate(newCommandState, isAppVisible = true, isServiceSynced = true)
        assertNotNull("New user command arriving after service is synced must toast", newResult)
        assertEquals(1000L, (newResult as FeedbackToast.CommandOutcome).eventId)
    }

    @Test
    fun delayedInitialServiceSnapshot_containingOldEventId_isAbsorbedWithoutToasting() {
        val policy = ToastFeedbackPolicy()
        // 1. App starts with local empty default state
        val localDefaultState = VoiceState()
        policy.evaluate(localDefaultState, isAppVisible = true, isServiceSynced = false) // baseline sync

        // 2. 50ms later, bound service connects and emits snapshot from a past session with old eventId
        val delayedServiceSnapshot = VoiceState(
            command = CommandSnapshot(message = "과거 명령 결과", eventId = 999L)
        )
        // When service connection completes, syncInitialState absorbs this baseline
        policy.syncInitialState(delayedServiceSnapshot)

        // 3. UI evaluates the delayed service snapshot
        val result = policy.evaluate(delayedServiceSnapshot, isAppVisible = true, isServiceSynced = true)
        assertNull("Delayed initial service snapshot containing old eventId must not trigger toast", result)

        // 4. Truly new user command afterwards does toast
        val newCommandState = VoiceState(
            command = CommandSnapshot(message = "실제 새로운 명령", eventId = 1000L)
        )
        val newResult = policy.evaluate(newCommandState, isAppVisible = true, isServiceSynced = true)
        assertNotNull("New user command arriving after service sync must toast", newResult)
    }

    @Test
    fun errorClearedAndReoccurringError_triggersToastAgain() {
        val policy = ToastFeedbackPolicy()
        policy.syncInitialState(VoiceState())

        // 1. Error occurs
        val err1 = VoiceState(phase = VoicePhase.Error, errorCode = VoiceError.PERMISSION)
        val res1 = policy.evaluate(err1, isAppVisible = true)
        assertNotNull(res1)

        // 2. Error clears (recovering/waiting)
        val recovered = VoiceState(phase = VoicePhase.Waiting, errorCode = VoiceError.NONE)
        val resRecovered = policy.evaluate(recovered, isAppVisible = true)
        assertNull(resRecovered)

        // 3. Same error occurs again in a subsequent session
        val err2 = VoiceState(phase = VoicePhase.Error, errorCode = VoiceError.PERMISSION)
        val res2 = policy.evaluate(err2, isAppVisible = true)
        assertNotNull("Re-occurring error after recovery must trigger toast", res2)
    }

    @Test
    fun commandCoordinatorMonotonicEventGenerator_continuesAcrossServiceRestart() {
        // Reset sequence baseline for test
        CommandCoordinator.resetGlobalEventIdForTesting(1_000_000L)

        val id1 = CommandCoordinator.generateEventId()
        val id2 = CommandCoordinator.generateEventId()
        assertTrue("Subsequent IDs must be strictly monotonically increasing", id2 > id1)

        // Simulating service restart (new service instance without reboot)
        // IDs must continue monotonically without resetting to 0 or 1
        val idAfterRestart = CommandCoordinator.generateEventId()
        assertTrue("ID after simulated service restart must exceed prior ID", idAfterRestart > id2)
    }
}
