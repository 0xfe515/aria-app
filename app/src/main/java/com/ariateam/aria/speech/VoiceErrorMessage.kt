package com.ariateam.aria.speech

import com.ariateam.aria.R

internal fun voiceErrorMessage(code: Int): Int = when (code) {
    VoiceError.PERMISSION -> R.string.error_permission
    VoiceError.MICROPHONE -> R.string.error_microphone
    VoiceError.PROCESS_DIED -> R.string.error_process
    VoiceError.START_FAILED -> R.string.error_start
    VoiceError.TIMEOUT -> R.string.error_timeout
    VoiceError.RECOGNITION_UNAVAILABLE -> R.string.error_recognition_unavailable
    VoiceError.RECOGNITION_NETWORK -> R.string.error_recognition_network
    VoiceError.RECOGNITION_LANGUAGE -> R.string.error_recognition_language
    VoiceError.RECOGNITION_RATE_LIMIT -> R.string.error_recognition_rate_limit
    VoiceError.RECOGNITION_FAILED -> R.string.error_recognition_failed
    else -> R.string.error_engine
}
