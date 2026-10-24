package com.ariateam.aria.speech

import com.ariateam.aria.command.CommandSnapshot

enum class VoicePhase(val wireValue: Int) {
    Stopped(0), Initializing(1), Waiting(2), Dictating(3), Stopping(4), Error(5),
    // 6 belonged to a retired diagnostic. Never reuse it.
    Reconnecting(7), Speaking(8);

    companion object {
        fun fromWire(value: Int) = entries.firstOrNull { it.wireValue == value } ?: Error
    }
}

enum class VoiceCue { Start, Stop, Detected, Error }

object VoiceError {
    const val NONE = 0
    const val PERMISSION = 1
    const val MICROPHONE = 3
    const val ENGINE = 4
    const val PROCESS_DIED = 5
    const val START_FAILED = 6
    const val TIMEOUT = 8
    const val RECOGNITION_UNAVAILABLE = 9
    const val RECOGNITION_NETWORK = 10
    const val RECOGNITION_LANGUAGE = 11
    const val RECOGNITION_FAILED = 12
    const val RECOGNITION_RATE_LIMIT = 13
}

data class VoiceState(
    val phase: VoicePhase = VoicePhase.Stopped,
    val transcript: String = "",
    val errorCode: Int = VoiceError.NONE,
    val command: CommandSnapshot = CommandSnapshot(),
    val retryCount: Int = 0,
    val retryAtMillis: Long = 0,
    val wakeFallback: Boolean = false,
) {
    val isActive: Boolean get() = phase !in listOf(VoicePhase.Stopped, VoicePhase.Error)
}
