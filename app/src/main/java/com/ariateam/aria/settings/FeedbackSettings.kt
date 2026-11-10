package com.ariateam.aria.settings

data class FeedbackSettings(
    val volumePercent: Int = 100,
    val alertFrequency: AlertFrequency = AlertFrequency.NORMAL,
    val cautionEnabled: Boolean = true,
)

enum class AlertFrequency { FREQUENT, NORMAL, QUIET }

/** Sanitizes persisted values before they are exposed to app processes. */
internal fun sanitizeFeedbackSettings(volume: Any?, frequency: Any?, caution: Any?): FeedbackSettings {
    val safeVolume = ((volume as? Int) ?: 100).coerceIn(0, 100)
    val safeFrequency = (frequency as? String)?.let { runCatching { AlertFrequency.valueOf(it) }.getOrNull() } ?: AlertFrequency.NORMAL
    val safeCaution = caution as? Boolean ?: true
    return FeedbackSettings(safeVolume, safeFrequency, safeCaution)
}
