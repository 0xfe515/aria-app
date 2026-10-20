package com.ariateam.aria.command

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Owns playback exclusion. All callbacks and lifecycle calls are serialized on main. */
class CommandSpeech(context: Context, private val suppress: (Boolean) -> Unit, private val changed: () -> Unit = {}) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var token = 0L
    private var closed = false
    private var completion: (() -> Unit)? = null
    private var pending: Pair<String, () -> Unit>? = null
    private var initialized = false
    var available = false
        private set
    private lateinit var tts: TextToSpeech
    private val safety = Runnable { finish(token) }

    init {
        tts = TextToSpeech(context) { status ->
            main.post {
                if (closed) return@post
                initialized = true
                ready = status == TextToSpeech.SUCCESS
                refreshVoice()
                val waiting = pending
                pending = null
                if (waiting != null) speak(waiting.first, waiting.second)
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) { main.post { finish(utteranceId.toLongOrNull() ?: -1) } }
            @Deprecated("Legacy callback")
            override fun onError(utteranceId: String) { main.post { finish(utteranceId.toLongOrNull() ?: -1) } }
        })
    }

    private fun refreshVoice() {
        val wasAvailable = available
        available = runCatching {
            if (!ready || tts.setLanguage(Locale.KOREAN) < 0) false
            else {
                val voice = tts.voices?.firstOrNull {
                    it.locale.language == "ko" && !it.isNetworkConnectionRequired
                }
                voice != null && tts.setVoice(voice) == TextToSpeech.SUCCESS
            }
        }.getOrDefault(false)
        if (wasAvailable != available) changed()
    }

    fun speak(text: String, done: () -> Unit = {}) {
        stop()
        if (closed) return
        if (!initialized) {
            pending = text to done
            // Do not leave microphone suppression dependent on a TTS initialization callback.
            val pendingToken = token
            main.postDelayed({
                if (pendingToken != token || closed) return@postDelayed
                val waiting = pending
                pending = null
                waiting?.second?.invoke()
            }, 5_000)
            return
        }
        refreshVoice()
        if (!available) { done(); return }
        completion = done
        suppress(true)
        val id = token
        main.postDelayed(safety, 20_000)
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id.toString()) == TextToSpeech.ERROR) finish(id)
    }

    private fun finish(id: Long) {
        if (id != token || completion == null || closed) return
        main.removeCallbacks(safety)
        val done = completion
        completion = null
        // Stop even on timeout, then drain the microphone before reopening recognition.
        tts.stop()
        main.postDelayed({ if (id == token && !closed) { suppress(false); done?.invoke() } }, 500)
    }

    fun stop() {
        token++
        pending = null
        completion = null
        main.removeCallbacks(safety)
        if (::tts.isInitialized) tts.stop()
        suppress(false)
    }

    override fun close() {
        stop()
        closed = true
        tts.shutdown()
    }
}
