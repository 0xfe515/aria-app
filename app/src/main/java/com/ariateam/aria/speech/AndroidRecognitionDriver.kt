package com.ariateam.aria.speech

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/** Thin main-thread adapter. Restart/watchdog policy belongs to VoiceListeningSession. */
internal class AndroidRecognitionDriver internal constructor(
    private val platform: PlatformAdapter,
    private val preference: BackendPreference = BackendPreference.OnDeviceOnly,
    private val mainThreadCheck: () -> Unit = { check(Looper.myLooper() == Looper.getMainLooper()) },
) : RecognitionDriver {

    constructor(
        context: Context,
        preference: BackendPreference = BackendPreference.OnDeviceOnly,
    ) : this(AndroidPlatformAdapter(context.applicationContext), preference)

    internal interface PlatformRecognizer {
        fun startListening(backend: RecognitionBackend, callback: PlatformCallback)
        fun stopListening()
        fun cancel()
        fun destroy()
    }

    internal interface PlatformCallback {
        fun onReadyForSpeech()
        fun onBeginningOfSpeech()
        fun onEndOfSpeech()
        fun onPartialResults(candidates: List<String>)
        fun onResults(candidates: List<String>)
        fun onError(error: Int)
    }

    internal interface PlatformAdapter {
        fun isOnDeviceRecognitionAvailable(): Boolean
        fun isDefaultRecognitionAvailable(): Boolean
        fun createRecognizer(backend: RecognitionBackend): PlatformRecognizer
        fun logFallback(reason: RecognitionFailure) = Unit
    }

    var activeBackend: RecognitionBackend = RecognitionBackend.Unavailable
        private set
    var onDeviceFallbackLatched: Boolean = false
        private set

    private var recognizer: PlatformRecognizer? = null
    private var generation = 0L

    internal fun selectBackend(): RecognitionBackend {
        val onDevice = platform.isOnDeviceRecognitionAvailable()
        val defaultAvailable = platform.isDefaultRecognitionAvailable()
        return when (preference) {
            BackendPreference.OnDeviceOnly ->
                if (onDevice) RecognitionBackend.OnDevice else RecognitionBackend.Unavailable
            BackendPreference.DefaultPreferOfflineOnly ->
                if (defaultAvailable) RecognitionBackend.DefaultPreferOffline else RecognitionBackend.Unavailable
            BackendPreference.PreferOnDeviceWithFallback -> when {
                onDevice && !onDeviceFallbackLatched -> RecognitionBackend.OnDevice
                defaultAvailable -> RecognitionBackend.DefaultPreferOffline
                onDevice -> RecognitionBackend.OnDevice
                else -> RecognitionBackend.Unavailable
            }
        }
    }

    override fun start(listener: RecognitionDriver.Listener) {
        mainThreadCheck()
        close()
        val selected = selectBackend()
        if (selected == RecognitionBackend.Unavailable) {
            activeBackend = RecognitionBackend.Unavailable
            listener.failed(RecognitionFailure.Unavailable)
            return
        }
        startWithBackend(
            backend = selected,
            listener = listener,
            allowFallback = selected == RecognitionBackend.OnDevice &&
                preference == BackendPreference.PreferOnDeviceWithFallback &&
                platform.isDefaultRecognitionAvailable(),
        )
    }

    private fun startWithBackend(
        backend: RecognitionBackend,
        listener: RecognitionDriver.Listener,
        allowFallback: Boolean,
    ) {
        val ticket = ++generation
        activeBackend = backend
        try {
            val instance = platform.createRecognizer(backend)
            recognizer = instance
            var readyOrSpeechObserved = false
            instance.startListening(backend, object : PlatformCallback {
                private fun live() = ticket == generation && recognizer === instance
                override fun onReadyForSpeech() {
                    if (!live()) return
                    readyOrSpeechObserved = true
                    listener.ready()
                }
                override fun onBeginningOfSpeech() {
                    if (!live()) return
                    readyOrSpeechObserved = true
                    listener.speechStarted()
                }
                override fun onEndOfSpeech() { if (live()) listener.speechEnded() }
                override fun onPartialResults(candidates: List<String>) {
                    if (!live()) return
                    readyOrSpeechObserved = true
                    listener.partial(candidates)
                }
                override fun onResults(candidates: List<String>) {
                    if (!live()) return
                    readyOrSpeechObserved = true
                    listener.result(candidates)
                }
                override fun onError(error: Int) {
                    if (!live()) return
                    val failure = classify(error)
                    if (!readyOrSpeechObserved && allowFallback && shouldFallbackFromOnDevice(failure)) {
                        fallbackToDefault(failure, listener)
                        return
                    }
                    if (!failure.retryable && failure != RecognitionFailure.NoMatch &&
                        failure != RecognitionFailure.NoSpeech) {
                        onDeviceFallbackLatched = false
                    }
                    listener.failed(failure)
                }
            })
        } catch (_: SecurityException) {
            listener.failed(RecognitionFailure.Permission)
        } catch (_: Exception) {
            if (allowFallback) {
                fallbackToDefault(RecognitionFailure.Client, listener)
            } else {
                listener.failed(RecognitionFailure.Client)
            }
        }
    }

    private fun fallbackToDefault(reason: RecognitionFailure, listener: RecognitionDriver.Listener) {
        onDeviceFallbackLatched = true
        platform.logFallback(reason)
        close()
        startWithBackend(RecognitionBackend.DefaultPreferOffline, listener, allowFallback = false)
    }

    override fun finish() {
        mainThreadCheck()
        // The session owns exception recovery, including synchronous provider failures.
        recognizer?.stopListening()
    }

    override fun close() {
        mainThreadCheck()
        generation++
        val old = recognizer
        recognizer = null
        runCatching { old?.cancel() }
        runCatching { old?.destroy() }
    }

    companion object {
        internal const val KOREAN_LOCALE = "ko-KR"
        private const val TAG = "AriaRecognitionDriver"

        internal fun production(context: Context): AndroidRecognitionDriver =
            AndroidRecognitionDriver(context, BackendPreference.DefaultPreferOfflineOnly)

        internal fun production(
            platform: PlatformAdapter,
            mainThreadCheck: () -> Unit = { check(Looper.myLooper() == Looper.getMainLooper()) },
        ): AndroidRecognitionDriver = AndroidRecognitionDriver(
            platform,
            BackendPreference.DefaultPreferOfflineOnly,
            mainThreadCheck,
        )

        internal fun shouldFallbackFromOnDevice(failure: RecognitionFailure): Boolean =
            failure in setOf(
                RecognitionFailure.Language,
                RecognitionFailure.Unavailable,
                RecognitionFailure.Server,
                RecognitionFailure.Client,
            )

        @SuppressLint("InlinedApi")
        internal fun classify(error: Int): RecognitionFailure = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> RecognitionFailure.NoMatch
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> RecognitionFailure.NoSpeech
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> RecognitionFailure.Permission
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> RecognitionFailure.Language
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT,
            SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> RecognitionFailure.Unavailable
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> RecognitionFailure.RateLimit
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> RecognitionFailure.Network
            SpeechRecognizer.ERROR_AUDIO -> RecognitionFailure.Audio
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> RecognitionFailure.Busy
            SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> RecognitionFailure.Server
            else -> RecognitionFailure.Client
        }

        internal fun sanitizeCandidates(raw: List<String>?): List<String> = raw
            ?.asSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.map { it.take(VoiceLimits.MAX_TEXT_CHARS) }
            ?.distinct()
            ?.take(VoiceLimits.MAX_RECOGNITION_CANDIDATES)
            ?.toList()
            .orEmpty()

        internal fun extractCandidates(value: Bundle?): List<String> =
            sanitizeCandidates(value?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))

        @SuppressLint("InlinedApi")
        internal fun createRecognitionIntent(backend: RecognitionBackend): Intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, KOREAN_LOCALE)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, KOREAN_LOCALE)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, VoiceLimits.MAX_RECOGNITION_CANDIDATES)
                if (backend == RecognitionBackend.DefaultPreferOffline) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                    VoiceLimits.MIN_SPEECH_INPUT_MILLIS,
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    VoiceLimits.POSSIBLY_COMPLETE_SILENCE_MILLIS,
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    VoiceLimits.COMPLETE_SILENCE_MILLIS,
                )
                putStringArrayListExtra(
                    RecognizerIntent.EXTRA_BIASING_STRINGS,
                    ArrayList(KoreanWakePhraseMatcher.CANONICAL_WAKE_PHRASES),
                )
            }
    }
}

private class AndroidPlatformAdapter(private val context: Context) : AndroidRecognitionDriver.PlatformAdapter {
    override fun isOnDeviceRecognitionAvailable(): Boolean =
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override fun isDefaultRecognitionAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context)

    override fun createRecognizer(backend: RecognitionBackend): AndroidRecognitionDriver.PlatformRecognizer {
        val delegate = when (backend) {
            RecognitionBackend.OnDevice -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            RecognitionBackend.DefaultPreferOffline -> SpeechRecognizer.createSpeechRecognizer(context)
            RecognitionBackend.Unavailable -> error("Cannot create recognizer for Unavailable backend")
        }
        return object : AndroidRecognitionDriver.PlatformRecognizer {
            override fun startListening(
                backend: RecognitionBackend,
                callback: AndroidRecognitionDriver.PlatformCallback,
            ) {
                delegate.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = callback.onReadyForSpeech()
                    override fun onBeginningOfSpeech() = callback.onBeginningOfSpeech()
                    override fun onEndOfSpeech() = callback.onEndOfSpeech()
                    override fun onPartialResults(partialResults: Bundle?) =
                        callback.onPartialResults(AndroidRecognitionDriver.extractCandidates(partialResults))
                    override fun onResults(results: Bundle?) =
                        callback.onResults(AndroidRecognitionDriver.extractCandidates(results))
                    override fun onError(error: Int) = callback.onError(error)
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
                delegate.startListening(AndroidRecognitionDriver.createRecognitionIntent(backend))
            }

            override fun stopListening() = delegate.stopListening()
            override fun cancel() = delegate.cancel()
            override fun destroy() = delegate.destroy()
        }
    }

    override fun logFallback(reason: RecognitionFailure) {
        Log.i(
            "AriaRecognitionDriver",
            "On-device recognizer unavailable for ${AndroidRecognitionDriver.KOREAN_LOCALE} " +
                "(reason=$reason); falling back to default recognizer with EXTRA_PREFER_OFFLINE hint " +
                "(guaranteedOffline=false)",
        )
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
