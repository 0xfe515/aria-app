package com.ariateam.aria.speech

import android.util.Log

/**
 * Safe, structured diagnostics for wake phrase recognition and audio lifecycle.
 *
 * Emits diagnostic events distinguishing:
 * - NO_AUDIO: zero samples read, silent buffer, or AudioRecord read error
 * - MODEL_NOT_READY: bundled KWS assets missing or SpeechRecognizer unavailable
 * - NO_KEYWORD: non-wake speech heard or timed out without keyword
 * - STOPPED_WAITING: paused for TTS, explicit Stop, or cancellation
 * - WAKE_DETECTED: wake keyword detected by on-device KWS or SpeechRecognizer
 * - RESTARTING: wake listening resuming after TTS, response, or backoff recovery
 *
 * Guarantees privacy: NEVER logs raw audio PCM data, private speech transcripts,
 * Throwable.message contents, or credentials. Only event enum and sanitized reason tag.
 */
internal object WakeDiagnostics {
    private const val TAG = "AriaWakeDiagnostics"

    enum class Event {
        NO_AUDIO,
        MODEL_NOT_READY,
        NO_KEYWORD,
        STOPPED_WAITING,
        WAKE_DETECTED,
        RESTARTING,
    }

    @Volatile
    var diagnosticListener: ((Event, String) -> Unit)? = null

    fun emit(event: Event, reasonCode: String = "") {
        val sanitized = reasonCode.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }.take(64)
        val msg = if (sanitized.isBlank()) event.name else "${event.name}: $sanitized"
        runCatching {
            Log.i(TAG, msg)
        }
        diagnosticListener?.invoke(event, sanitized)
    }
}
