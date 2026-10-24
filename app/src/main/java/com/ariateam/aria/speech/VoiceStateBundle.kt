package com.ariateam.aria.speech

import android.os.Bundle
import com.ariateam.aria.command.CommandSnapshot

internal fun VoiceState.toBundle(revision: Long) = Bundle().apply {
    putInt("phase", phase.wireValue)
    putString("transcript", transcript)
    putInt("errorCode", errorCode)
    putBundle("command", command.toBundle())
    putInt("retryCount", retryCount)
    putLong("retryAtMillis", retryAtMillis)
    putBoolean("wakeFallback", wakeFallback)
    putLong("revision", revision)
}

internal fun voiceStateFromBundle(value: Bundle) = VoiceState(
    phase = VoicePhase.fromWire(value.getInt("phase")),
    transcript = value.getString("transcript").orEmpty().take(VoiceLimits.MAX_TEXT_CHARS),
    errorCode = value.getInt("errorCode"),
    command = CommandSnapshot.fromBundle(value.getBundle("command")),
    retryCount = value.getInt("retryCount"),
    retryAtMillis = value.getLong("retryAtMillis"),
    wakeFallback = value.getBoolean("wakeFallback", false),
)
