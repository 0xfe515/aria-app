package com.ariateam.aria.speech

/**
 * On-device keyword spotting (KWS) abstraction for wake-word listening.
 *
 * Designed to run on a dedicated audio thread while in waiting phase,
 * and promptly release microphone resources before command recognition starts.
 */
internal interface WakeWordDetector {

    interface Listener {
        /**
         * Invoked on the main thread when a wake phrase is spotted.
         */
        fun onWakeWordDetected(keyword: String)

        /**
         * Invoked on the main thread when a non-recoverable error occurs in the detector.
         */
        fun onError(error: Throwable)
    }

    /**
     * True if the underlying engine and model are available for wake-word detection.
     */
    val isAvailable: Boolean

    /**
     * Starts listening for wake-word keywords.
     */
    fun start(listener: Listener)

    /**
     * Stops listening and promptly releases audio capture (microphone).
     */
    fun stop()

    /**
     * Completely releases native engine and background thread resources.
     */
    fun release()
}
