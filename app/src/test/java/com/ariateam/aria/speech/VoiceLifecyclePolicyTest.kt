package com.ariateam.aria.speech

import org.junit.Assert.*
import org.junit.Test

class VoiceLifecyclePolicyTest {
    @Test fun snapshotOlderThanTheCallbackCannotReplaceIt() {
        val receiver = VoiceStateReceiver()
        assertTrue(receiver.accept(5))
        assertFalse(receiver.accept(4))
        assertTrue(receiver.accept(5))
    }

    @Test fun localFailureSurvivesDeliveryAndReconnectUntilExplicitRetry() {
        val receiver = VoiceStateReceiver()
        receiver.fail(VoiceError.START_FAILED)
        assertFalse(receiver.accept(10))
        receiver.reconnect()
        assertFalse(receiver.accept(0))
        receiver.clearFailure()
        assertTrue(receiver.accept(1))
    }

    @Test fun reconnectAcceptsNewProcessRevisions() {
        val receiver = VoiceStateReceiver()
        assertTrue(receiver.accept(100))
        receiver.reconnect()
        assertTrue(receiver.accept(0))
        assertEquals(VoicePhase.Error, VoicePhase.fromWire(6))
        assertEquals(7, VoicePhase.Reconnecting.wireValue)
        assertEquals(8, VoicePhase.Speaking.wireValue)
        assertTrue(VoiceState(VoicePhase.Reconnecting).isActive)
        assertTrue(VoiceState(VoicePhase.Speaking).isActive)
        assertFalse(VoiceState(VoicePhase.Error).isActive)
    }
}
