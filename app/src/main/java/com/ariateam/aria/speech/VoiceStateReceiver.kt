package com.ariateam.aria.speech

/** Reject stale delivery and preserve local request failures until an explicit retry. */
internal class VoiceStateReceiver {
    private var revision = -1L
    private var localFailure = VoiceError.NONE
    fun reconnect() { revision = -1L }
    fun fail(error: Int) { localFailure = error }
    fun clearFailure() { localFailure = VoiceError.NONE }
    fun accept(incomingRevision: Long): Boolean {
        if (incomingRevision < revision) return false
        revision = incomingRevision
        return localFailure == VoiceError.NONE
    }
}
