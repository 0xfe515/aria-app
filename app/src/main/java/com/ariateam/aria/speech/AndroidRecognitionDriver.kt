package com.ariateam.aria.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Thin main-thread adapter. Restart/watchdog policy belongs to VoiceListeningSession. */
internal class AndroidRecognitionDriver(private val context: Context) : RecognitionDriver {
    private var recognizer: SpeechRecognizer? = null
    private var generation = 0L

    override fun start(listener: RecognitionDriver.Listener) {
        checkMainThread()
        close()
        val ticket = generation
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                listener.failed(RecognitionFailure.Unavailable)
                return
            }
            val platform = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = platform
            platform.setRecognitionListener(object : RecognitionListener {
                private fun live() = ticket == generation && recognizer === platform
                override fun onReadyForSpeech(params: Bundle?) { if (live()) listener.ready() }
                override fun onBeginningOfSpeech() { if (live()) listener.speechStarted() }
                override fun onEndOfSpeech() { if (live()) listener.speechEnded() }
                override fun onPartialResults(partialResults: Bundle?) {
                    if (live()) listener.partial(bestResult(partialResults))
                }
                override fun onResults(results: Bundle?) { if (live()) listener.result(bestResult(results)) }
                override fun onError(error: Int) { if (live()) listener.failed(classify(error)) }
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            platform.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
        } catch (_: SecurityException) {
            listener.failed(RecognitionFailure.Permission)
        } catch (_: Exception) {
            listener.failed(RecognitionFailure.Client)
        }
    }

    override fun finish() {
        checkMainThread()
        // The session owns exception recovery, including synchronous provider failures.
        recognizer?.stopListening()
    }

    override fun close() {
        checkMainThread()
        generation++
        val old = recognizer
        recognizer = null
        runCatching { old?.cancel() }
        runCatching { old?.destroy() }
    }

    private fun bestResult(value: Bundle?): String = value
        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull { it.isNotBlank() }?.take(VoiceLimits.MAX_TEXT_CHARS).orEmpty()

    private fun checkMainThread() { check(Looper.myLooper() == Looper.getMainLooper()) }

    companion object {
        internal fun classify(error: Int): RecognitionFailure = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> RecognitionFailure.NoMatch
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> RecognitionFailure.NoSpeech
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> RecognitionFailure.Permission
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> RecognitionFailure.Language
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> RecognitionFailure.RateLimit
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> RecognitionFailure.Network
            SpeechRecognizer.ERROR_AUDIO -> RecognitionFailure.Audio
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> RecognitionFailure.Busy
            SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> RecognitionFailure.Server
            else -> RecognitionFailure.Client
        }
    }
}

internal class MainRecognitionScheduler : RecognitionScheduler {
    private val handler = Handler(Looper.getMainLooper())
    override fun now(): Long = SystemClock.elapsedRealtime()
    override fun schedule(delayMillis: Long, action: () -> Unit): ScheduledRecognitionTask {
        val task = Runnable { action() }
        handler.postDelayed(task, delayMillis)
        return ScheduledRecognitionTask { handler.removeCallbacks(task) }
    }
}
