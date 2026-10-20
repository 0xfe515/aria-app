package com.ariateam.aria.command

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ariateam.aria.speech.toBundle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelCommandValidationTest {
    @Test fun voiceSnapshotCarriesRecoveryStateThroughTheSameCallbackBundle() {
        val original = com.ariateam.aria.speech.VoiceState(
            com.ariateam.aria.speech.VoicePhase.Reconnecting, errorCode = com.ariateam.aria.speech.VoiceError.RECOGNITION_NETWORK,
            retryCount = 3, retryAtMillis = 12345,
        )
        val bundle = original.toBundle(42)
        assertEquals(original, com.ariateam.aria.speech.voiceStateFromBundle(bundle))
        assertEquals(42L, bundle.getLong("revision"))
    }

    @Test fun snapshotRoundTripPreservesNavigationAndPersistedSoundState() {
        val original = CommandSnapshot(message = "안내 소리를 껐어요.", audible = false,
            model = "설치됨", navigationId = 42, destination = "settings")
        assertEquals(original, CommandSnapshot.fromBundle(original.toBundle()))
    }
}
