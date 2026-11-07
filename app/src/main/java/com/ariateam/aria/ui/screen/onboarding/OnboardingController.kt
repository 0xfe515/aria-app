package com.ariateam.aria.ui.screen.onboarding

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import com.ariateam.aria.speech.AndroidRecognitionDriver
import com.ariateam.aria.speech.BackendPreference
import com.ariateam.aria.speech.RecognitionDriver
import com.ariateam.aria.speech.RecognitionFailure
import java.util.Locale
import java.util.UUID

/**
 * Controller for TTS and ASR during onboarding.
 *
 * - AndroidRecognitionDriver(applicationContext, BackendPreference.OnDeviceOnly) — local-only recognizer
 * - TextToSpeech on applicationContext, initialized asynchronously with explicit nullable field
 * - TTS onDone/error posted to main Handler via UtteranceProgressListener; generation token = unique utterance ID
 * - TTS init failure, speak() failure and onError surface a resource-backed error (never treated as a
 *   successful prompt); the state machine does not advance on a TTS error. A permanent TTS failure
 *   ([ttsFailed]) makes every subsequent speak show the manual error instead of staying pending forever.
 * - Prompt callbacks must not start after pause/dispose or a newer prompt; cancel pending handlers,
 *   close ASR, stop TTS on pause, shutdown on clear; release() drops pending Handler callbacks.
 * - A fresh recognition generation is issued on every speak/pause so stale recognition results are
 *   rejected. The controller retains only applicationContext (never the Activity).
 */
internal class OnboardingController(
    private val applicationContext: Context,
    private val onPromptFinished: () -> Unit,
    private val onRecognitionResult: (String) -> Unit,
    private val onRecognitionFailure: (RecognitionFailure) -> Unit,
    private val onTtsError: () -> Unit,
) : TextToSpeech.OnInitListener {

    private val recognizer = AndroidRecognitionDriver(applicationContext, BackendPreference.OnDeviceOnly)

    // TextToSpeech initialized asynchronously with explicit nullable field
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    // Permanent TTS failure: once set, every subsequent speak surfaces the manual error immediately.
    private var ttsFailed = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentUtteranceId: String? = null
    private var isPaused = false
    private var isReleased = false
    private var pendingPrompt: PendingPrompt? = null
    private var currentPromptGeneration = 0L
    // Recognition generation: incremented on every speak/pause so in-flight listeners are invalidated.
    private var recognitionGeneration = 0L
    // TTS init generation: incremented for each TextTeach creation so stale init callbacks are rejected.
    private var ttsInitGeneration = 0L

    init {
        ttsInitGeneration++
        tts = TextToSpeech(applicationContext, this)
    }

    private data class PendingPrompt(
        val generation: Long,
        val utteranceId: String,
        val text: String,
        val onDone: () -> Unit
    )

    override fun onInit(status: Int) {
        val initGen = ttsInitGeneration
        if (isReleased) {
            // Already released: shut down any late TTS instance and do not touch UI.
            tts?.shutdown()
            tts = null
            return
        }
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            // Check language support explicitly; missing/unsupported Korean is a permanent TTS failure.
            val langResult = tts?.setLanguage(Locale.KOREAN) ?: TextToSpeech.LANG_MISSING_DATA
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                ttsFailed = true
                cancelPendingPrompt()
                mainHandler.post {
                    if (initGen == ttsInitGeneration && !isReleased && !isPaused) {
                        onTtsError()
                    }
                }
                return
            }
            tts?.setOnUtteranceProgressListener(utteranceListener)
            if (!isPaused) {
                // Prepared and able to speak: deliver any prompt queued during initialization.
                processPendingPrompt()
            }
            // If paused: prepared but intentionally silent until an explicit resumed prompt.
        } else {
            // TTS failed to initialize: permanent failure, surface a resource-backed error.
            ttsReady = false
            ttsFailed = true
            cancelPendingPrompt()
            mainHandler.post {
                if (initGen == ttsInitGeneration && !isReleased && !isPaused) {
                    onTtsError()
                }
            }
        }
    }

    /**
     * Speak the given text via TTS. Any in-flight recognition is cancelled (no recognizer while speaking)
     * and a new recognition generation is issued so stale listeners are rejected. A speak() failure or a
     * permanent TTS failure is surfaced as a TTS error rather than waiting for a never-arriving onDone.
     */
    fun speak(text: String, onDone: () -> Unit = {}) {
        if (isReleased || isPaused) return

        if (ttsFailed) {
            // Permanent TTS failure: surface the manual error immediately instead of staying pending.
            mainHandler.post { onTtsError() }
            return
        }

        // No recognizer while speaking.
        cancelRecognition()

        // New prompt generation supersedes any previous pending prompt.
        currentPromptGeneration++
        val generation = currentPromptGeneration
        val utteranceId = UUID.randomUUID().toString()
        currentUtteranceId = utteranceId

        // Invalidate any recognition started for a previous prompt.
        recognitionGeneration++

        val pending = PendingPrompt(generation, utteranceId, text, onDone)
        pendingPrompt = pending

        if (!ttsReady || tts == null) {
            // TTS not ready yet; will be spoken in onInit (pendingPrompt retained until matching onDone).
            return
        }

        val result = tts?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId
        )
        if (result != TextToSpeech.SUCCESS) {
            // speak() failed: permanent failure; surface an error, do not wait for a never-arriving onDone.
            ttsFailed = true
            pendingPrompt = null
            currentUtteranceId = null
            mainHandler.post { onTtsError() }
        }
    }

    /** Start listening for user speech under a fresh recognition generation. */
    fun startListening() {
        if (isReleased || isPaused || !hasMicrophonePermission()) return
        val gen = ++recognitionGeneration
        recognizer.start(object : RecognitionDriver.Listener {
            override fun ready() {}
            override fun speechStarted() {}
            override fun speechEnded() {}
            override fun partial(text: String) {}
            override fun result(text: String) {
                if (isReleased || isPaused) return
                if (recognitionGeneration != gen) return // stale recognition from an older prompt
                onRecognitionResult(text)
            }
            override fun failed(failure: RecognitionFailure) {
                if (isReleased || isPaused) return
                if (recognitionGeneration != gen) return // stale recognition from an older prompt
                onRecognitionFailure(failure)
            }
        })
    }

    /** Stop listening (finish the current recognition session) without destroying the recognizer. */
    fun stopListening() {
        if (isReleased) return
        recognizer.finish()
    }

    /** Close/cancel the recognizer so no stale results can affect a new step. Bumps the recognition
     *  generation so any in-flight listener from before the cancel is rejected as stale. */
    fun cancelRecognition() {
        if (isReleased) return
        recognitionGeneration++
        recognizer.close()
    }

    /** Check if microphone permission is granted. */
    fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            applicationContext,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Pause: close ASR, stop TTS, cancel any pending prompt and pending Handler callbacks. */
    fun onPause() {
        isPaused = true
        cancelRecognition()
        tts?.stop()
        cancelPendingPrompt()
        mainHandler.removeCallbacksAndMessages(null)
    }

    /** Resume: clear the pause flag. The screen re-speaks the current question when appropriate. */
    fun onResume() {
        isPaused = false
    }

    /** Release all resources: stop listening, shutdown TTS, close recognizer, drop pending callbacks. Final disposal. */
    fun release() {
        isReleased = true
        isPaused = true
        cancelRecognition()
        cancelPendingPrompt()
        mainHandler.removeCallbacksAndMessages(null)
        tts?.shutdown()
        tts = null
        recognizer.close()
    }

    /** UtteranceProgressListener: real TTS completion via engine callback, not a timer. */
    private val utteranceListener: UtteranceProgressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        override fun onDone(utteranceId: String?) {
            if (utteranceId == null) return@onDone
            mainHandler.post {
                if (isReleased || isPaused) return@post
                val pending = this@OnboardingController.pendingPrompt
                if (pending == null) return@post
                if (pending.utteranceId != utteranceId) return@post
                if (pending.generation != currentPromptGeneration) return@post
                // Retain until matched; now invoke and clear.
                pendingPrompt = null
                currentUtteranceId = null
                pending.onDone()
            }
        }

        override fun onError(utteranceId: String?) {
            if (utteranceId == null) return@onError
            mainHandler.post {
                if (isReleased || isPaused) return@post
                val pending = this@OnboardingController.pendingPrompt
                if (pending == null) return@post
                if (pending.utteranceId != utteranceId) return@post
                if (pending.generation != currentPromptGeneration) return@post
                // TTS error: surface it, never treat as a successful prompt / do not advance.
                ttsFailed = true
                pendingPrompt = null
                currentUtteranceId = null
                onTtsError()
            }
        }
    }

    private fun cancelPendingPrompt() {
        // Invalidate the pending TTS prompt so its onDone/onError can no longer fire after a cancel
        // (pause/release/init-failure); a new prompt gets a fresh generation.
        pendingPrompt = null
        currentUtteranceId = null
        currentPromptGeneration++
    }

    private fun processPendingPrompt() {
        // Speak the queued prompt without clearing pendingPrompt: onDone must find it to match and
        // invoke the callback (retain active until matching done).
        val pending = pendingPrompt
        if (pending != null && ttsReady && tts != null && !ttsFailed) {
            val result = tts?.speak(
                pending.text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                pending.utteranceId
            )
            if (result != TextToSpeech.SUCCESS) {
                // Queued speak failed: surface as TTS error, never treat as a successful prompt.
                ttsFailed = true
                pendingPrompt = null
                currentUtteranceId = null
                mainHandler.post { onTtsError() }
            }
        }
    }
}
