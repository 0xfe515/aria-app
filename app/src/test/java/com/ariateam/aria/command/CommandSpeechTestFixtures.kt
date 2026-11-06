package com.ariateam.aria.command

/**
 * Deterministic virtual-time scheduler for JVM unit tests.
 */
internal class DeterministicSpeechScheduler : CommandSpeechScheduler {
    private var currentTimeMillis: Long = 0L
    private val scheduledTasks = mutableListOf<ScheduledTask>()

    private data class ScheduledTask(
        val executeAt: Long,
        val action: Runnable,
        val sequence: Long
    )

    private var sequenceCounter = 0L

    override fun post(action: Runnable) {
        action.run()
    }

    override fun postDelayed(action: Runnable, delayMillis: Long) {
        scheduledTasks.add(ScheduledTask(currentTimeMillis + delayMillis, action, ++sequenceCounter))
    }

    override fun removeCallbacks(action: Runnable) {
        scheduledTasks.removeAll { it.action === action }
    }

    fun advanceTimeBy(millis: Long) {
        currentTimeMillis += millis
        while (true) {
            val dueTask = scheduledTasks.filter { it.executeAt <= currentTimeMillis }
                .minWithOrNull(compareBy({ it.executeAt }, { it.sequence })) ?: break
            scheduledTasks.remove(dueTask)
            dueTask.action.run()
        }
    }

    fun runAllPending() {
        while (scheduledTasks.isNotEmpty()) {
            val nextTask = scheduledTasks.minWithOrNull(compareBy({ it.executeAt }, { it.sequence })) ?: break
            scheduledTasks.remove(nextTask)
            currentTimeMillis = maxOf(currentTimeMillis, nextTask.executeAt)
            nextTask.action.run()
        }
    }

    val pendingCount: Int get() = scheduledTasks.size
    val currentTime: Long get() = currentTimeMillis
}

/**
 * Fake playback engine for JVM unit tests with controllable voice availability,
 * call logging, and manual utterance completion dispatch.
 */
internal class FakeSpeechPlaybackEngine(
    var available: Boolean = true,
    var ready: Boolean = true
) : CommandSpeechPlaybackEngine {
    private var listener: CommandSpeechPlaybackEngine.UtteranceListener? = null
    val spokenTexts = mutableListOf<String>()
    val spokenUtteranceIds = mutableListOf<String>()
    var stopCount = 0
    var shutdownCount = 0
    var speakResult: Int = CommandSpeechPlaybackEngine.SUCCESS

    override val isAvailable: Boolean get() = available
    override val isReady: Boolean get() = ready

    override fun setUtteranceListener(listener: CommandSpeechPlaybackEngine.UtteranceListener?) {
        this.listener = listener
    }

    override fun refreshVoice(): Boolean = available

    override fun speak(text: String, utteranceId: String): Int {
        spokenTexts.add(text)
        spokenUtteranceIds.add(utteranceId)
        return speakResult
    }

    override fun stop() {
        stopCount++
    }

    override fun shutdown() {
        shutdownCount++
    }

    fun notifyDone(utteranceId: String) {
        listener?.onDone(utteranceId)
    }

    fun notifyStart(utteranceId: String) {
        listener?.onStart(utteranceId)
    }

    fun notifyError(utteranceId: String) {
        listener?.onError(utteranceId)
    }
}
