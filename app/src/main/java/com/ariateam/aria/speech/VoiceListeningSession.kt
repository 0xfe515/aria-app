package com.ariateam.aria.speech

import android.util.Log

internal data class ListeningSnapshot(
    val phase: VoicePhase = VoicePhase.Stopped,
    val transcript: String = "",
    val errorCode: Int = VoiceError.NONE,
    val retryCount: Int = 0,
    val retryAtMillis: Long = 0,
    val wakeFallback: Boolean = false,
)

/** Pure, serialized session policy. Android, time and all external effects are injected. */
internal class VoiceListeningSession(
    private val driver: RecognitionDriver,
    private val scheduler: RecognitionScheduler,
    private val listener: Listener,
    private val wakeDetector: WakeWordDetector? = null,
    private val wakeCooldownMillis: Long = DEFAULT_WAKE_COOLDOWN_MILLIS,
) {
    interface Listener {
        fun state(snapshot: ListeningSnapshot)
        fun inputStarted()
        fun wakeDetected()
        fun command(text: String)
        fun emptyInput()
        fun fatal(errorCode: Int)
    }

    private enum class Mode { Wake, Command, Manual, FollowUp }
    private val matcher = KoreanWakePhraseMatcher()
    private var running = false
    private var speaking = false
    private var mode = Mode.Wake
    private var generation = 0L
    private var attempt = 0L
    private var active = false
    private var isReady = false
    private var ending = false
    private var activated = false
    private var inputAnnounced = false
    private var draft = ""
    private var lastFinal = ""
    private var failures = 0
    private var retryNotBefore = 0L
    private var inputReadyAt: Long? = null
    private var beganSpeech = false
    private var restart: ScheduledRecognitionTask? = null
    private val timers = mutableListOf<ScheduledRecognitionTask>()

    private var wakeDetectorActive = false
    private var wakeTicket = 0L
    private var lastWakeDetectedAt: Long? = null
    private var wakeFallback = (wakeDetector == null || !wakeDetector.isAvailable)

    var snapshot = ListeningSnapshot()
        private set

    private fun logWarn(message: String, error: Throwable? = null) {
        runCatching { Log.w(TAG, message, error) }
    }

    private fun publish(phase: VoicePhase, error: Int = VoiceError.NONE, retryAt: Long = 0) {
        snapshot = ListeningSnapshot(
            phase = phase,
            transcript = if (mode == Mode.Wake && !activated) lastFinal else draft,
            errorCode = error,
            retryCount = failures,
            retryAtMillis = retryAt,
            wakeFallback = wakeFallback,
        )
        listener.state(snapshot)
    }

    fun start(manual: Boolean = false) {
        if (running) {
            if (manual) beginManual()
            return
        }
        running = true
        speaking = false
        failures = 0
        retryNotBefore = 0
        resetInput(if (manual) Mode.Manual else Mode.Wake)
        if (manual) announceInput()
        beginAttempt(initial = true)
    }

    fun beginManual() {
        if (!running) { start(manual = true); return }
        if (mode == Mode.Manual && active) return
        invalidate()
        speaking = false
        failures = 0
        retryNotBefore = 0
        resetInput(Mode.Manual)
        announceInput()
        scheduleAttempt(VoiceLimits.RESTART_MILLIS)
    }

    fun beginFollowUp() {
        if (!running) return
        invalidate()
        speaking = false
        resetInput(Mode.FollowUp)
        announceInput()
        scheduleAttempt(VoiceLimits.RESTART_MILLIS)
    }

    fun cancelInput() {
        if (!running) return
        invalidate()
        resetInput(Mode.Wake)
        if (speaking) publish(VoicePhase.Speaking) else scheduleAttempt(VoiceLimits.RESTART_MILLIS)
    }

    fun finishInput() {
        if (!running || !active || mode == Mode.Wake && !activated || ending) return
        ending = true
        if (isReady) requestFinal()
    }

    fun playbackChanged(value: Boolean) {
        if (!running || value == speaking) return
        speaking = value
        if (value) {
            invalidate()
            resetInput(Mode.Wake)
            publish(VoicePhase.Speaking)
        } else {
            scheduleAttempt(VoiceLimits.RESTART_MILLIS)
        }
    }

    fun stop() {
        running = false
        speaking = false
        invalidate()
        resetInput(Mode.Wake)
        failures = 0
        retryNotBefore = 0
        publish(VoicePhase.Stopped)
    }

    fun shutdown() {
        stop()
        wakeDetector?.release()
        driver.shutdown()
    }

    private fun announceInput() {
        if (inputAnnounced) return
        inputAnnounced = true
        listener.inputStarted()
    }

    private fun resetInput(next: Mode) {
        mode = next
        activated = false
        inputAnnounced = false
        draft = ""
        inputReadyAt = null
        beganSpeech = false
    }

    private fun invalidate() {
        generation++
        restart?.cancel()
        restart = null
        closeAttempt()
        stopWakeDetector()
    }

    private fun closeAttempt() {
        active = false // Old callbacks cannot participate even if close invokes a listener.
        attempt++
        timers.forEach { it.cancel() }
        timers.clear()
        driver.close()
        isReady = false
        ending = false
    }

    private fun timer(delay: Long, action: () -> Unit) {
        val ticket = attempt
        timers += scheduler.schedule(delay.coerceAtLeast(0)) {
            if (running && active && attempt == ticket && !speaking) action()
        }
    }

    private fun scheduleAttempt(delay: Long, error: Int = VoiceError.NONE) {
        restart?.cancel()
        if (!running || speaking) return
        val ticket = ++generation
        val wait = maxOf(delay, retryNotBefore - scheduler.now())
        val at = scheduler.now() + wait
        if (error != VoiceError.NONE) retryNotBefore = at
        publish(VoicePhase.Reconnecting, error, at)
        restart = scheduler.schedule(wait) {
            if (running && !speaking && generation == ticket) {
                restart = null
                beginAttempt()
            }
        }
    }

    private fun shouldUseWakeDetector(): Boolean =
        mode == Mode.Wake && wakeDetector != null && wakeDetector.isAvailable && !wakeFallback

    private fun startWakeListening() {
        if (!running || speaking || mode != Mode.Wake) return
        closeAttempt()
        val ticket = ++wakeTicket
        wakeDetectorActive = true
        publish(VoicePhase.Waiting)
        try {
            wakeDetector?.start(object : WakeWordDetector.Listener {
                override fun onWakeWordDetected(keyword: String) {
                    if (!running || speaking || mode != Mode.Wake || !wakeDetectorActive || wakeTicket != ticket) {
                        return
                    }
                    val now = scheduler.now()
                    val lastWake = lastWakeDetectedAt
                    if (lastWake != null && now - lastWake < wakeCooldownMillis) {
                        stopWakeDetector()
                        scheduleAttempt(wakeCooldownMillis - (now - lastWake))
                        return
                    }
                    lastWakeDetectedAt = now
                    stopWakeDetector()
                    resetInput(Mode.Command)
                    activated = true
                    announceInput()
                    listener.wakeDetected()
                    beginAttempt()
                }

                override fun onError(error: Throwable) {
                    if (!running || !wakeDetectorActive || wakeTicket != ticket) return
                    logWarn("WakeWordDetector error; falling back to Android SpeechRecognizer wake path", error)
                    stopWakeDetector()
                    wakeFallback = true
                    if (mode == Mode.Wake) {
                        scheduleAttempt(VoiceLimits.RESTART_MILLIS)
                    }
                }
            })
        } catch (e: Throwable) {
            logWarn("Failed to start WakeWordDetector; falling back to Android SpeechRecognizer wake path", e)
            stopWakeDetector()
            wakeFallback = true
            scheduleAttempt(VoiceLimits.RESTART_MILLIS)
        }
    }

    private fun stopWakeDetector() {
        wakeDetectorActive = false
        wakeTicket++
        wakeDetector?.stop()
    }

    private fun beginAttempt(initial: Boolean = false) {
        if (!running || speaking) return

        if (shouldUseWakeDetector()) {
            startWakeListening()
            return
        }

        // Do not extend a confirmed command's original input window across retries.
        val sinceReady = inputReadyAt?.let { scheduler.now() - it }
        if (mode != Mode.Wake && sinceReady != null &&
            (sinceReady >= VoiceLimits.CAPTURE_MILLIS || !beganSpeech && sinceReady >= VoiceLimits.NO_SPEECH_MILLIS)) {
            emptyInput()
            return
        }
        closeAttempt()
        active = true
        val id = attempt
        publish(if (initial) VoicePhase.Initializing else VoicePhase.Reconnecting)
        timer(VoiceLimits.STARTUP_MILLIS) { failure(RecognitionFailure.Timeout) }
        driver.start(object : RecognitionDriver.Listener {
            private fun live() = running && active && !speaking && attempt == id
            override fun ready() {
                if (!live() || isReady) return
                isReady = true
                timers.forEach { it.cancel() }
                timers.clear()
                if (inputReadyAt == null && mode != Mode.Wake) inputReadyAt = scheduler.now()
                publish(if (mode == Mode.Wake) VoicePhase.Waiting else VoicePhase.Dictating)
                if (ending) requestFinal() else armCaptureTimers()
            }
            override fun speechStarted() {
                if (!live()) return
                beganSpeech = true
            }
            override fun speechEnded() {
                if (!live()) return
                timers.forEach { it.cancel() }
                timers.clear()
                ending = true
                timer(VoiceLimits.FINAL_MILLIS) { failure(RecognitionFailure.Timeout) }
            }
            override fun partial(text: String) = partial(if (text.isBlank()) emptyList() else listOf(text))
            override fun partial(candidates: List<String>) {
                if (!live()) return
                if (mode == Mode.Wake) {
                    val after = matcher.selectWakeAfterPhrase(candidates) ?: return
                    if (!activated) {
                        activated = true
                        announceInput()
                        listener.wakeDetected()
                    }
                    draft = after.take(VoiceLimits.MAX_TEXT_CHARS)
                } else {
                    val text = candidates.firstOrNull { it.isNotBlank() }.orEmpty()
                    beganSpeech = beganSpeech || text.isNotBlank()
                    val after = matcher.afterWakePhrase(text) ?: text.trim()
                    if (after.isNotBlank()) draft = after.take(VoiceLimits.MAX_TEXT_CHARS)
                }
                publish(VoicePhase.Dictating)
            }
            override fun result(text: String) = result(if (text.isBlank()) emptyList() else listOf(text))
            override fun result(candidates: List<String>) { if (live()) finalResult(candidates) }
            override fun failed(failure: RecognitionFailure) { if (live()) this@VoiceListeningSession.failure(failure) }
        })
    }

    private fun armCaptureTimers() {
        val elapsed = inputReadyAt?.let { scheduler.now() - it } ?: 0
        timer(VoiceLimits.CAPTURE_MILLIS - elapsed) {
            ending = true
            requestFinal()
        }
        if (mode != Mode.Wake && !beganSpeech) {
            timer(VoiceLimits.NO_SPEECH_MILLIS - elapsed) {
                if (!beganSpeech) emptyInput()
            }
        }
    }

    private fun requestFinal() {
        timers.forEach { it.cancel() }
        timers.clear()
        timer(VoiceLimits.FINAL_MILLIS) { failure(RecognitionFailure.Timeout) }
        try { driver.finish() }
        catch (_: SecurityException) { failure(RecognitionFailure.Permission) }
        catch (_: Exception) { failure(RecognitionFailure.Client) }
    }

    private fun finalResult(candidates: List<String>) {
        if (mode == Mode.Wake) {
            val afterWake = matcher.selectWakeAfterPhrase(candidates)
            if (afterWake == null) {
                normalWakeRestart() // A partial wake may have been withdrawn in the final result.
                return
            }
            if (!activated) {
                activated = true
                announceInput()
                listener.wakeDetected()
            }
            if (afterWake.isBlank()) {
                closeAttempt()
                failures = 0
                retryNotBefore = 0
                resetInput(Mode.Command)
                inputAnnounced = true // Already announced when the wake phrase was found.
                scheduleAttempt(VoiceLimits.RESTART_MILLIS)
                return
            }
            confirmed(afterWake)
        } else {
            val text = candidates.firstOrNull { it.isNotBlank() }.orEmpty()
            val afterWake = matcher.afterWakePhrase(text)
            val command = (afterWake ?: text).trim()
            if (command.isBlank()) {
                normalEmptyResult()
            } else confirmed(command)
        }
    }

    private fun confirmed(text: String) {
        lastFinal = text.take(VoiceLimits.MAX_TEXT_CHARS)
        closeAttempt()
        failures = 0
        retryNotBefore = 0
        resetInput(Mode.Wake)
        // Schedule first: command feedback may synchronously cancel recognition for TTS.
        scheduleAttempt(VoiceLimits.RESTART_MILLIS)
        listener.command(lastFinal)
    }

    private fun normalWakeRestart() {
        closeAttempt()
        failures = 0
        retryNotBefore = 0
        resetInput(Mode.Wake)
        scheduleAttempt(VoiceLimits.RESTART_MILLIS)
    }

    private fun normalEmptyResult() {
        failures = 0
        retryNotBefore = 0
        if (mode == Mode.Manual) { emptyInput(); return }
        closeAttempt()
        scheduleAttempt(VoiceLimits.RESTART_MILLIS)
    }

    private fun emptyInput() {
        closeAttempt()
        resetInput(Mode.Wake)
        scheduleAttempt(VoiceLimits.RESTART_MILLIS)
        listener.emptyInput()
    }

    private fun failure(failure: RecognitionFailure) {
        if (failure == RecognitionFailure.NoMatch || failure == RecognitionFailure.NoSpeech) {
            if (mode == Mode.Wake) normalWakeRestart() else normalEmptyResult()
            return
        }
        closeAttempt()
        if (mode == Mode.Wake) resetInput(Mode.Wake)
        if (!failure.retryable || failures >= RETRY_DELAYS.size) {
            running = false
            invalidate()
            publish(VoicePhase.Error, failure.errorCode)
            listener.fatal(failure.errorCode)
            return
        }
        val delay = RETRY_DELAYS[failures++]
        scheduleAttempt(delay, failure.errorCode)
    }

    companion object {
        const val DEFAULT_WAKE_COOLDOWN_MILLIS = 1_500L
        private const val TAG = "VoiceListeningSession"
        private val RETRY_DELAYS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)
    }
}
