package com.ariateam.aria.command

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import com.ariateam.aria.settings.FeedbackSettingsClient
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Minimal abstraction seam for TTS engine operations, allowing testable playback
 * without real TTS hardware or network billing.
 */
internal interface CommandSpeechPlaybackEngine {
    val isAvailable: Boolean
    val isReady: Boolean
    fun setUtteranceListener(listener: UtteranceListener?)
    fun refreshVoice(): Boolean
    fun speak(text: String, utteranceId: String): Int
    fun stop()
    fun shutdown()

    interface UtteranceListener {
        fun onStart(utteranceId: String) = Unit
        fun onDone(utteranceId: String)
        fun onError(utteranceId: String)
    }

    companion object {
        const val SUCCESS = 0
        const val ERROR = -1
    }
}

/**
 * Scheduling abstraction for serializing callbacks, safety timeouts, and drain delays.
 */
internal interface CommandSpeechScheduler {
    fun post(action: Runnable)
    fun postDelayed(action: Runnable, delayMillis: Long)
    fun removeCallbacks(action: Runnable)
}

/**
 * Production scheduler delegating to the Android main thread Looper Handler.
 */
internal open class HandlerSpeechScheduler(
    private val handler: Handler? = try { Handler(Looper.getMainLooper()) } catch (_: Throwable) { null }
) : CommandSpeechScheduler {
    override fun post(action: Runnable) {
        handler?.post(action)
    }

    override fun postDelayed(action: Runnable, delayMillis: Long) {
        handler?.postDelayed(action, delayMillis)
    }

    override fun removeCallbacks(action: Runnable) {
        handler?.removeCallbacks(action)
    }
}

/**
 * Default Android TTS implementation wrapping [TextToSpeech].
 * Callbacks from TextToSpeech are forwarded directly without extra scheduling hops;
 * CommandSpeech handles single-layer serialization on its scheduler.
 */
internal class AndroidSpeechPlaybackEngine(
    context: Context?,
    private val onInit: (Boolean) -> Unit
) : CommandSpeechPlaybackEngine {
    private val feedbackSettings = context?.let { FeedbackSettingsClient(it) }
    private var tts: TextToSpeech? = null
    private var ready = false
    private var listener: CommandSpeechPlaybackEngine.UtteranceListener? = null

    init {
        if (context != null) {
            try {
                tts = TextToSpeech(context) { status ->
                    ready = status == TextToSpeech.SUCCESS
                    onInit(ready)
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) {
                        listener?.onStart(utteranceId)
                    }
                    override fun onDone(utteranceId: String) {
                        listener?.onDone(utteranceId)
                    }
                    @Deprecated("Legacy callback")
                    override fun onError(utteranceId: String) {
                        listener?.onError(utteranceId)
                    }
                })
            } catch (_: Throwable) {
                ready = false
                onInit(false)
            }
        }
    }

    override val isAvailable: Boolean get() = refreshVoice()
    override val isReady: Boolean get() = ready && tts != null

    override fun setUtteranceListener(listener: CommandSpeechPlaybackEngine.UtteranceListener?) {
        this.listener = listener
    }

    override fun refreshVoice(): Boolean {
        return runCatching {
            val localTts = tts ?: return@runCatching false
            if (!ready || localTts.setLanguage(Locale.KOREAN) < 0) false
            else {
                val voice = localTts.voices?.firstOrNull {
                    it.locale.language == "ko" && !it.isNetworkConnectionRequired
                }
                voice != null && localTts.setVoice(voice) == TextToSpeech.SUCCESS
            }
        }.getOrDefault(false)
    }

    override fun speak(text: String, utteranceId: String): Int {
        val localTts = tts ?: return CommandSpeechPlaybackEngine.ERROR
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, (feedbackSettings?.settings?.value?.volumePercent ?: 100) / 100f) }
        return localTts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    override fun stop() {
        tts?.stop()
    }

    override fun shutdown() {
        feedbackSettings?.close()
        tts?.shutdown()
        tts = null
    }
}
