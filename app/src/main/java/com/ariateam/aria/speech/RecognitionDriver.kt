package com.ariateam.aria.speech

internal interface RecognitionDriver {
    interface Listener {
        fun ready()
        fun speechStarted()
        fun speechEnded()
        fun partial(text: String)
        fun partial(candidates: List<String>) = partial(candidates.firstOrNull().orEmpty())
        fun result(text: String)
        fun result(candidates: List<String>) = result(candidates.firstOrNull().orEmpty())
        fun failed(failure: RecognitionFailure)
    }
    fun start(listener: Listener)
    fun finish()
    fun close()
    fun shutdown() = close()
}

internal enum class RecognitionBackend(val guaranteedOffline: Boolean) {
    OnDevice(guaranteedOffline = true),
    DefaultPreferOffline(guaranteedOffline = false),
    Unavailable(guaranteedOffline = false),
}

internal enum class BackendPreference {
    PreferOnDeviceWithFallback,
    OnDeviceOnly,
    DefaultPreferOfflineOnly,
}

internal enum class RecognitionFailure(val errorCode: Int, val retryable: Boolean) {
    NoMatch(VoiceError.NONE, false), NoSpeech(VoiceError.NONE, false),
    Unavailable(VoiceError.RECOGNITION_UNAVAILABLE, false), Permission(VoiceError.PERMISSION, false),
    Language(VoiceError.RECOGNITION_LANGUAGE, false), RateLimit(VoiceError.RECOGNITION_RATE_LIMIT, false),
    Network(VoiceError.RECOGNITION_NETWORK, true), Audio(VoiceError.MICROPHONE, true),
    Server(VoiceError.RECOGNITION_FAILED, true), Busy(VoiceError.RECOGNITION_FAILED, true),
    Client(VoiceError.RECOGNITION_FAILED, true), Timeout(VoiceError.TIMEOUT, true),
}

internal fun interface ScheduledRecognitionTask { fun cancel() }
internal interface RecognitionScheduler {
    fun now(): Long
    fun schedule(delayMillis: Long, action: () -> Unit): ScheduledRecognitionTask
}

internal object VoiceLimits {
    const val MAX_TEXT_CHARS = 4_096
    const val MAX_RECOGNITION_CANDIDATES = 5
    const val MIN_SPEECH_INPUT_MILLIS = 1_500L
    const val POSSIBLY_COMPLETE_SILENCE_MILLIS = 1_000L
    const val COMPLETE_SILENCE_MILLIS = 1_500L
    const val STARTUP_MILLIS = 8_000L
    const val NO_SPEECH_MILLIS = 8_000L
    const val CAPTURE_MILLIS = 60_000L
    const val FINAL_MILLIS = 5_000L
    const val RESTART_MILLIS = 300L
}
