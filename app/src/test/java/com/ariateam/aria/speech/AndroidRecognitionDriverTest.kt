package com.ariateam.aria.speech

import android.speech.SpeechRecognizer
import org.junit.Assert.*
import org.junit.Test

class AndroidRecognitionDriverTest {
    @Test fun onDeviceOnlyDiagnosticModeNeverFallsBackToDefaultProvider() {
        val platform = FakePlatform(onDeviceAvailable = true, defaultAvailable = true)
        val driver = AndroidRecognitionDriver(platform, mainThreadCheck = {})
        val events = Recorder()

        driver.start(events)
        assertEquals(RecognitionBackend.OnDevice, driver.activeBackend)
        assertTrue(driver.activeBackend.guaranteedOffline)
        assertEquals(listOf(RecognitionBackend.OnDevice), platform.createdBackends)

        // When the diagnostic on-device recognizer reports Korean unsupported, it fails without fallback.
        platform.recognizers.single().callback.onError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
        assertEquals(listOf(RecognitionFailure.Language), events.failures)
        assertEquals(listOf(RecognitionBackend.OnDevice), platform.createdBackends)
        assertFalse(driver.onDeviceFallbackLatched)
    }

    @Test fun onDeviceOnlyDiagnosticModeReportsUnavailableWhenOnDeviceServiceIsMissing() {
        val platform = FakePlatform(onDeviceAvailable = false, defaultAvailable = true)
        val driver = AndroidRecognitionDriver(platform, mainThreadCheck = {})
        val events = Recorder()

        driver.start(events)
        assertEquals(RecognitionBackend.Unavailable, driver.activeBackend)
        assertEquals(listOf(RecognitionFailure.Unavailable), events.failures)
        assertTrue(platform.createdBackends.isEmpty())
    }

    @Test fun diagnosticFallbackModeSwitchesToDefaultPreferOfflineAndMarksGuaranteedOfflineFalse() {
        val platform = FakePlatform(onDeviceAvailable = true, defaultAvailable = true)
        val driver = AndroidRecognitionDriver(
            platform = platform,
            preference = BackendPreference.PreferOnDeviceWithFallback,
            mainThreadCheck = {},
        )
        val events = Recorder()

        driver.start(events)
        assertEquals(RecognitionBackend.OnDevice, driver.activeBackend)
        platform.recognizers.first().callback.onError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)

        assertEquals(
            listOf(RecognitionBackend.OnDevice, RecognitionBackend.DefaultPreferOffline),
            platform.createdBackends,
        )
        assertEquals(RecognitionBackend.DefaultPreferOffline, driver.activeBackend)
        assertFalse(driver.activeBackend.guaranteedOffline)
        assertTrue(driver.onDeviceFallbackLatched)
        assertTrue(events.failures.isEmpty())

        platform.recognizers.last().callback.onReadyForSpeech()
        platform.recognizers.last().callback.onResults(listOf("헤뤼이아 설정 열어"))
        assertEquals(1, events.readyCount)
        assertEquals(listOf(listOf("헤뤼이아 설정 열어")), events.results)
    }

    @Test fun errorClassificationCoversLanguageUnavailableAndSupportCheckErrors() {
        assertEquals(
            RecognitionFailure.Language,
            AndroidRecognitionDriver.classify(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED),
        )
        assertEquals(
            RecognitionFailure.Language,
            AndroidRecognitionDriver.classify(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE),
        )
        assertEquals(
            RecognitionFailure.Unavailable,
            AndroidRecognitionDriver.classify(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT),
        )
        assertEquals(
            RecognitionFailure.NoMatch,
            AndroidRecognitionDriver.classify(SpeechRecognizer.ERROR_NO_MATCH),
        )
        assertEquals(
            RecognitionFailure.NoSpeech,
            AndroidRecognitionDriver.classify(SpeechRecognizer.ERROR_SPEECH_TIMEOUT),
        )
    }

    @Test fun sanitizeCandidatesTrimsDeduplicatesAndBoundsNBestList() {
        val raw = listOf(
            "  헤  ",
            "",
            "   ",
            "헤",
            "헤이 아리아 설정 열어",
            "후보 3",
            "후보 4",
            "후보 5",
            "후보 6",
        )
        assertEquals(
            listOf("헤", "헤이 아리아 설정 열어", "후보 3", "후보 4", "후보 5"),
            AndroidRecognitionDriver.sanitizeCandidates(raw),
        )
    }

    private class Recorder : RecognitionDriver.Listener {
        var readyCount = 0
        val partials = mutableListOf<List<String>>()
        val results = mutableListOf<List<String>>()
        val failures = mutableListOf<RecognitionFailure>()
        override fun ready() { readyCount++ }
        override fun speechStarted() = Unit
        override fun speechEnded() = Unit
        override fun partial(text: String) = partial(listOf(text))
        override fun partial(candidates: List<String>) { partials += candidates }
        override fun result(text: String) = result(listOf(text))
        override fun result(candidates: List<String>) { results += candidates }
        override fun failed(failure: RecognitionFailure) { failures += failure }
    }

    private class FakeRecognizer : AndroidRecognitionDriver.PlatformRecognizer {
        lateinit var callback: AndroidRecognitionDriver.PlatformCallback
        var cancelled = false
        var destroyed = false
        override fun startListening(
            backend: RecognitionBackend,
            callback: AndroidRecognitionDriver.PlatformCallback,
        ) {
            this.callback = callback
        }
        override fun stopListening() = Unit
        override fun cancel() { cancelled = true }
        override fun destroy() { destroyed = true }
    }

    private class FakePlatform(
        private val onDeviceAvailable: Boolean,
        private val defaultAvailable: Boolean,
    ) : AndroidRecognitionDriver.PlatformAdapter {
        val createdBackends = mutableListOf<RecognitionBackend>()
        val recognizers = mutableListOf<FakeRecognizer>()
        override fun isOnDeviceRecognitionAvailable(): Boolean = onDeviceAvailable
        override fun isDefaultRecognitionAvailable(): Boolean = defaultAvailable
        override fun createRecognizer(backend: RecognitionBackend): AndroidRecognitionDriver.PlatformRecognizer {
            createdBackends += backend
            return FakeRecognizer().also { recognizers += it }
        }
    }
}
