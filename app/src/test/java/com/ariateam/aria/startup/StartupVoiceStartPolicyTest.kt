package com.ariateam.aria.startup

import com.ariateam.aria.speech.VoicePhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupVoiceStartPolicyTest {

    @Test
    fun firstKnownInactiveColdStart_withMicPermission_allowsStartingVoice() {
        val shouldStart = StartupPolicy.shouldStartVoiceDuringStartup(
            hasBeenAdmitted = false,
            hasMicPermission = true,
            voicePhase = VoicePhase.Stopped,
            isVoiceActive = false,
            hasAttemptedStartupVoiceStart = false,
        )
        assertTrue("Cold launch with mic permission and inactive service must initiate voice start", shouldStart)

        // Verify default arguments also evaluate to true for cold launch
        assertTrue(
            "Default arguments for isVoiceActive and hasAttemptedStartupVoiceStart must allow first cold start",
            StartupPolicy.shouldStartVoiceDuringStartup(
                hasBeenAdmitted = false,
                hasMicPermission = true,
                voicePhase = VoicePhase.Stopped,
            ),
        )
    }

    @Test
    fun runtimeActiveUnsynced_skipsStartingVoiceToWaitForGenuineSync() {
        val shouldStart = StartupPolicy.shouldStartVoiceDuringStartup(
            hasBeenAdmitted = false,
            hasMicPermission = true,
            voicePhase = VoicePhase.Stopped,
            isVoiceActive = true,
            hasAttemptedStartupVoiceStart = false,
        )
        assertFalse("Active runtime with unsynced connection must wait for genuine sync and not duplicate start", shouldStart)
    }

    @Test
    fun secondAttemptStopped_skipsStartingVoiceToPreventLoop() {
        val shouldStart = StartupPolicy.shouldStartVoiceDuringStartup(
            hasBeenAdmitted = false,
            hasMicPermission = true,
            voicePhase = VoicePhase.Stopped,
            isVoiceActive = false,
            hasAttemptedStartupVoiceStart = true,
        )
        assertFalse("Subsequent attempt returning to Stopped before admission must not auto-restart in a loop", shouldStart)
    }

    @Test
    fun admittedHomeStopped_excludesStartingVoice() {
        val shouldStart = StartupPolicy.shouldStartVoiceDuringStartup(
            hasBeenAdmitted = true,
            hasMicPermission = true,
            voicePhase = VoicePhase.Stopped,
            isVoiceActive = false,
            hasAttemptedStartupVoiceStart = false,
        )
        assertFalse("Admitted Home screen must never automatically start voice upon Stop", shouldStart)

        val shouldStartAfterAttempt = StartupPolicy.shouldStartVoiceDuringStartup(
            hasBeenAdmitted = true,
            hasMicPermission = true,
            voicePhase = VoicePhase.Stopped,
            isVoiceActive = false,
            hasAttemptedStartupVoiceStart = true,
        )
        assertFalse("Admitted Home screen with prior attempt must never automatically start voice", shouldStartAfterAttempt)
    }

    @Test
    fun voiceError_excludesAutoRestart() {
        val shouldStart = StartupPolicy.shouldStartVoiceDuringStartup(
            hasBeenAdmitted = false,
            hasMicPermission = true,
            voicePhase = VoicePhase.Error,
            isVoiceActive = false,
            hasAttemptedStartupVoiceStart = false,
        )
        assertFalse("Error phase must not be auto-restarted; user must use explicit retry", shouldStart)
    }

    @Test
    fun nonStoppedVoicePhases_excludeDuplicateStart() {
        val excludedPhases = listOf(
            VoicePhase.Initializing,
            VoicePhase.Waiting,
            VoicePhase.Speaking,
            VoicePhase.Stopping,
            VoicePhase.Dictating,
            VoicePhase.Reconnecting,
        )
        for (phase in excludedPhases) {
            val shouldStart = StartupPolicy.shouldStartVoiceDuringStartup(
                hasBeenAdmitted = false,
                hasMicPermission = true,
                voicePhase = phase,
                isVoiceActive = false,
                hasAttemptedStartupVoiceStart = false,
            )
            assertFalse("Phase $phase must not trigger a duplicate voice start", shouldStart)
        }
    }

    @Test
    fun missingMicPermission_excludesStartingVoice() {
        val shouldStart = StartupPolicy.shouldStartVoiceDuringStartup(
            hasBeenAdmitted = false,
            hasMicPermission = false,
            voicePhase = VoicePhase.Stopped,
            isVoiceActive = false,
            hasAttemptedStartupVoiceStart = false,
        )
        assertFalse("Missing microphone permission must prevent voice initiation", shouldStart)
    }

    @Test
    fun activeObserved_thenStoppedBeforeAdmission_mustNotAutoRestart() {
        var hasAttempted = false

        // 1. Startup begins with an existing active runtime/phase
        val isVoiceActive = true
        val voicePhase = VoicePhase.Waiting

        if (StartupPolicy.shouldLatchStartupVoiceHandled(isVoiceActive, voicePhase)) {
            hasAttempted = true
        }
        assertTrue("Active runtime/phase must latch startup voice as handled", hasAttempted)

        val shouldStartInitial = StartupPolicy.shouldStartVoiceDuringStartup(
            hasBeenAdmitted = false,
            hasMicPermission = true,
            voicePhase = voicePhase,
            isVoiceActive = isVoiceActive,
            hasAttemptedStartupVoiceStart = hasAttempted,
        )
        assertFalse("Must not start while active runtime exists", shouldStartInitial)

        // 2. Existing voice is stopped before Home admission
        val isVoiceActiveAfterStop = false
        val voicePhaseAfterStop = VoicePhase.Stopped

        val shouldStartAfterStop = StartupPolicy.shouldStartVoiceDuringStartup(
            hasBeenAdmitted = false,
            hasMicPermission = true,
            voicePhase = voicePhaseAfterStop,
            isVoiceActive = isVoiceActiveAfterStop,
            hasAttemptedStartupVoiceStart = hasAttempted,
        )
        assertFalse("Stopped before admission after active-observed must NOT auto-restart", shouldStartAfterStop)
    }

    @Test
    fun activeObservedWithStoppedPhase_latchesHandledAndDoesNotRestart() {
        // When runtime is active in bridge (e.g. flag 0 binding in progress), even if local phase is Stopped
        assertTrue(
            "Active runtime with Stopped phase must latch as handled",
            StartupPolicy.shouldLatchStartupVoiceHandled(isVoiceActive = true, voicePhase = VoicePhase.Stopped),
        )

        // Cold inactive launch must NOT latch as handled until start is attempted
        assertFalse(
            "Cold inactive launch must not latch as handled before start attempt",
            StartupPolicy.shouldLatchStartupVoiceHandled(isVoiceActive = false, voicePhase = VoicePhase.Stopped),
        )
    }
}
