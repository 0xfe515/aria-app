package com.ariateam.aria.command

import android.content.Context

enum class SpeechPriority(val rank: Int) {
    CloudResponse(0),
    NormalCommand(1),
    HazardWarning(2),
}

/** Owns playback exclusion. All callbacks and lifecycle calls are serialized on the scheduler. */
internal class CommandSpeech(
    context: Context? = null,
    private val suppress: (Boolean) -> Unit,
    private val changed: () -> Unit = {},
    engine: CommandSpeechPlaybackEngine? = null,
    private val scheduler: CommandSpeechScheduler = HandlerSpeechScheduler(),
) : AutoCloseable {
    private data class PendingSpeech(
        val text: String,
        val priority: SpeechPriority,
        val done: () -> Unit,
        val started: (() -> Unit)?,
        val token: Long,
    )

    private var initialized = false
    private var token = 0L
    private var closed = false
    private var completion: (() -> Unit)? = null
    private var speechStarted: (() -> Unit)? = null
    private var pending: PendingSpeech? = null
    private var pendingTimeout: Runnable? = null
    private var safetyTimeout: Runnable? = null
    private var drainCallback: Runnable? = null
    private val playback: CommandSpeechPlaybackEngine
    var available = false
        private set
    var currentPriority: SpeechPriority? = null
        private set

    init {
        playback = engine ?: AndroidSpeechPlaybackEngine(context, ::onEngineInitialized)
        playback.setUtteranceListener(object : CommandSpeechPlaybackEngine.UtteranceListener {
            override fun onDone(utteranceId: String) {
                scheduler.post(Runnable { finish(utteranceId) })
            }

            override fun onStart(utteranceId: String) {
                scheduler.post(Runnable {
                    if (utteranceId == token.toString() && completion != null) {
                        val callback = speechStarted
                        speechStarted = null
                        callback?.invoke()
                    }
                })
            }

            override fun onError(utteranceId: String) {
                scheduler.post(Runnable { finish(utteranceId) })
            }
        })
        if (engine != null) {
            initialized = playback.isReady
            refreshVoice()
        }
    }

    private fun onEngineInitialized(success: Boolean) {
        scheduler.post(Runnable {
            if (closed) return@Runnable
            initialized = true
            refreshVoice()
            val waiting = pending
            pending = null
            pendingTimeout?.let(scheduler::removeCallbacks)
            pendingTimeout = null
            if (waiting != null) speak(waiting.text, waiting.priority, started = waiting.started, done = waiting.done)
        })
    }

    private fun refreshVoice() {
        val wasAvailable = available
        available = playback.isReady && playback.refreshVoice()
        if (wasAvailable != available) changed()
    }

    fun speak(text: String, done: () -> Unit) = speak(text, SpeechPriority.NormalCommand, done = done)

    fun speak(
        text: String,
        priority: SpeechPriority = SpeechPriority.NormalCommand,
        started: (() -> Unit)? = null,
        done: () -> Unit = {},
    ) {
        val activePriority = currentPriority
        if (activePriority != null && priority.rank < activePriority.rank) {
            done()
            return
        }
        stop(unsuppress = false)
        if (closed) {
            done()
            return
        }
        currentPriority = priority
        if (!initialized) {
            val pendingToken = token
            pending = PendingSpeech(text, priority, done, started, pendingToken)
            val timeout = Runnable {
                if (pending?.token != pendingToken || closed) return@Runnable
                pending = null
                pendingTimeout = null
                currentPriority = null
                done()
            }
            pendingTimeout = timeout
            scheduler.postDelayed(timeout, INITIALIZATION_TIMEOUT_MILLIS)
            return
        }
        refreshVoice()
        if (!available) {
            currentPriority = null
            suppress(false)
            done()
            return
        }

        completion = done
        speechStarted = started
        suppress(true)
        val utteranceId = token.toString()
        val timeout = Runnable { finish(utteranceId) }
        safetyTimeout = timeout
        scheduler.postDelayed(timeout, SAFETY_TIMEOUT_MILLIS)
        if (playback.speak(text, utteranceId) == CommandSpeechPlaybackEngine.ERROR) finish(utteranceId)
    }

    private fun finish(utteranceId: String) {
        if (utteranceId != token.toString() || completion == null || closed) return
        safetyTimeout?.let(scheduler::removeCallbacks)
        safetyTimeout = null
        val done = completion
        completion = null
        speechStarted = null
        currentPriority = null
        playback.stop()
        val drain = Runnable {
            if (utteranceId == token.toString() && !closed) {
                drainCallback = null
                suppress(false)
                done?.invoke()
            }
        }
        drainCallback = drain
        scheduler.postDelayed(drain, DRAIN_DELAY_MILLIS)
    }

    fun stop(unsuppress: Boolean = true) {
        token++
        pending = null
        completion = null
        currentPriority = null
        listOfNotNull(pendingTimeout, safetyTimeout, drainCallback).forEach(scheduler::removeCallbacks)
        pendingTimeout = null
        safetyTimeout = null
        drainCallback = null
        playback.stop()
        if (unsuppress) suppress(false)
    }

    fun preemptCloudSpeech() {
        if (currentPriority == SpeechPriority.CloudResponse) stop()
    }

    fun preemptSpeech(minPriority: SpeechPriority = SpeechPriority.HazardWarning) {
        val activePriority = currentPriority
        if (activePriority != null && activePriority.rank < minPriority.rank) stop()
    }

    override fun close() {
        if (closed) return
        stop()
        closed = true
        playback.setUtteranceListener(null)
        playback.shutdown()
    }

    private companion object {
        const val INITIALIZATION_TIMEOUT_MILLIS = 5_000L
        const val SAFETY_TIMEOUT_MILLIS = 20_000L
        const val DRAIN_DELAY_MILLIS = 500L
    }
}
