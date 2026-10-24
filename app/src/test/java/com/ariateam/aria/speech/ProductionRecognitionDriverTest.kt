package com.ariateam.aria.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionRecognitionDriverTest {
    @Test fun wakeManualAndFollowUpInputAllUseDefaultPreferOfflineProvider() {
        val platform = FakePlatform()
        val driver = AndroidRecognitionDriver.production(platform, mainThreadCheck = {})
        val clock = FakeScheduler()
        val commands = mutableListOf<String>()
        val session = VoiceListeningSession(driver, clock, object : VoiceListeningSession.Listener {
            override fun state(snapshot: ListeningSnapshot) = Unit
            override fun inputStarted() = Unit
            override fun wakeDetected() = Unit
            override fun command(text: String) { commands += text }
            override fun emptyInput() = Unit
            override fun fatal(errorCode: Int) = Unit
        })

        session.start()
        platform.recognizers.last().callback.onResults(listOf("헤이 아리아"))
        clock.advance(VoiceLimits.RESTART_MILLIS)
        platform.recognizers.last().callback.onReadyForSpeech()
        val commandCallback = platform.recognizers.last().callback
        commandCallback.onResults(listOf("안내 소리 꺼"))
        commandCallback.onResults(listOf("안내 소리 켜")) // Final callback is stale after the first result.

        clock.advance(VoiceLimits.RESTART_MILLIS)
        session.beginManual()
        clock.advance(VoiceLimits.RESTART_MILLIS)
        platform.recognizers.last().callback.onReadyForSpeech()
        platform.recognizers.last().callback.onResults(listOf("설정 열어"))

        session.beginFollowUp()
        clock.advance(VoiceLimits.RESTART_MILLIS)
        platform.recognizers.last().callback.onReadyForSpeech()
        platform.recognizers.last().callback.onResults(listOf("홈으로 가"))

        assertEquals(listOf("안내 소리 꺼", "설정 열어", "홈으로 가"), commands)
        assertEquals(5, platform.recognizers.size)
        assertTrue(platform.backends.all { it == RecognitionBackend.DefaultPreferOffline })
        assertTrue(platform.backends.none { it == RecognitionBackend.OnDevice })
    }

    private class FakePlatform : AndroidRecognitionDriver.PlatformAdapter {
        val backends = mutableListOf<RecognitionBackend>()
        val recognizers = mutableListOf<FakeRecognizer>()
        override fun isOnDeviceRecognitionAvailable() = true
        override fun isDefaultRecognitionAvailable() = true
        override fun createRecognizer(backend: RecognitionBackend): AndroidRecognitionDriver.PlatformRecognizer {
            backends += backend
            return FakeRecognizer().also { recognizers += it }
        }
    }

    private class FakeRecognizer : AndroidRecognitionDriver.PlatformRecognizer {
        lateinit var callback: AndroidRecognitionDriver.PlatformCallback
        override fun startListening(
            backend: RecognitionBackend,
            callback: AndroidRecognitionDriver.PlatformCallback,
        ) { this.callback = callback }
        override fun stopListening() = Unit
        override fun cancel() = Unit
        override fun destroy() = Unit
    }

    private class FakeScheduler : RecognitionScheduler {
        private data class Event(val due: Long, val action: () -> Unit, var cancelled: Boolean = false)
        private val events = mutableListOf<Event>()
        private var time = 0L
        override fun now() = time
        override fun schedule(delayMillis: Long, action: () -> Unit): ScheduledRecognitionTask {
            val event = Event(time + delayMillis, action)
            events += event
            return ScheduledRecognitionTask { event.cancelled = true }
        }
        fun advance(delta: Long) {
            val target = time + delta
            while (true) {
                val next = events.filter { !it.cancelled && it.due <= target }.minByOrNull { it.due } ?: break
                events.remove(next)
                time = next.due
                next.action()
            }
            time = target
        }
    }
}
