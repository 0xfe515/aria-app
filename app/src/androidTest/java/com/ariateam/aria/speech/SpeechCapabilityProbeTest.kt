package com.ariateam.aria.speech

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.WaveReader
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechCapabilityProbeTest {

    @Test fun platformSpeechCapabilitiesAndIntentContractsAreTruthful() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val defaultAvailable = SpeechRecognizer.isRecognitionAvailable(context)
        val onDeviceAvailable = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val defaultService = Settings.Secure.getString(
            context.contentResolver,
            "voice_recognition_service",
        ).orEmpty()
        val services = context.packageManager.queryIntentServices(
            Intent(RecognitionService.SERVICE_INTERFACE),
            0,
        ).mapNotNull { it.serviceInfo?.let { info -> "${info.packageName}/${info.name}" } }

        Log.i(
            TAG,
            "Capability probe: isOnDeviceRecognitionAvailable=$onDeviceAvailable, " +
                "isRecognitionAvailable=$defaultAvailable, " +
                "voice_recognition_service=$defaultService, services=$services",
        )

        assertTrue(RecognitionBackend.OnDevice.guaranteedOffline)
        assertFalse(RecognitionBackend.DefaultPreferOffline.guaranteedOffline)
        assertFalse(RecognitionBackend.Unavailable.guaranteedOffline)

        val onDeviceIntent = AndroidRecognitionDriver.createRecognitionIntent(RecognitionBackend.OnDevice)
        assertEquals(
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            onDeviceIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL),
        )
        assertEquals("ko-KR", onDeviceIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertEquals("ko-KR", onDeviceIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE))
        assertTrue(onDeviceIntent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
        assertEquals(
            VoiceLimits.MAX_RECOGNITION_CANDIDATES,
            onDeviceIntent.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 0),
        )
        assertFalse(onDeviceIntent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        assertEquals(
            VoiceLimits.MIN_SPEECH_INPUT_MILLIS,
            onDeviceIntent.getLongExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 0L),
        )
        assertEquals(
            VoiceLimits.POSSIBLY_COMPLETE_SILENCE_MILLIS,
            onDeviceIntent.getLongExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                0L,
            ),
        )
        assertEquals(
            VoiceLimits.COMPLETE_SILENCE_MILLIS,
            onDeviceIntent.getLongExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                0L,
            ),
        )
        assertEquals(
            KoreanWakePhraseMatcher.CANONICAL_WAKE_PHRASES,
            onDeviceIntent.getStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS),
        )

        val defaultIntent = AndroidRecognitionDriver.createRecognitionIntent(
            RecognitionBackend.DefaultPreferOffline,
        )
        assertTrue(defaultIntent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
    }

    @Test fun bundleCandidateExtractionAndMainThreadSessionIntegration() {
        val bundle = Bundle().apply {
            putStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION,
                arrayListOf("헤", "헤이 아리아 설정 열어"),
            )
        }
        val candidates = AndroidRecognitionDriver.extractCandidates(bundle)
        assertEquals(listOf("헤", "헤이 아리아 설정 열어"), candidates)

        val matcher = KoreanWakePhraseMatcher()
        // Wake-only N-best confirmation works when top candidate is truncated "헤".
        val wakeOnlyBundle = Bundle().apply {
            putStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION,
                arrayListOf("헤", "헤이 아리아"),
            )
        }
        assertEquals("", matcher.selectWakeAfterPhrase(AndroidRecognitionDriver.extractCandidates(wakeOnlyBundle)))

        // Unconfirmed command in lower candidate is rejected when top candidate is only "헤".
        assertNull(matcher.selectWakeAfterPhrase(candidates))
    }

    @Test fun onDeviceAndDefaultKoreanStartupProbe() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val optInLive = InstrumentationRegistry.getArguments()
            .getString("aria.liveSpeechProbe")?.toBoolean() == true
        if (!optInLive) {
            // Fast main-thread creation/destruction verification without binding the physical microphone.
            instrumentation.runOnMainSync {
                val driver = AndroidRecognitionDriver(context)
                val selected = driver.selectBackend()
                Log.i(TAG, "Default production selected backend on device: $selected")
                if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                    assertEquals(RecognitionBackend.OnDevice, selected)
                    val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    recognizer.destroy()
                } else {
                    assertEquals(RecognitionBackend.Unavailable, selected)
                }
            }
            return
        }

        // Opt-in live service handshake probe (-Pandroid.testInstrumentationRunnerArguments.aria.liveSpeechProbe=true).
        // Reports language/permission/unavailable/timeout handshake state only; not a proof of acoustic accuracy.
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.RECORD_AUDIO)
        try {
            if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                val onDeviceOutcome = probeBackend(RecognitionBackend.OnDevice)
                Log.i(TAG, "Live OnDevice ko-KR handshake diagnostic (non-acoustic): $onDeviceOutcome")
                assertTrue(onDeviceOutcome.isNotBlank())
            } else {
                Log.i(TAG, "Live OnDevice ko-KR handshake diagnostic: unavailable")
            }
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                val defaultOutcome = probeBackend(RecognitionBackend.DefaultPreferOffline)
                Log.i(TAG, "Live DefaultPreferOffline ko-KR handshake diagnostic (non-acoustic): $defaultOutcome")
                assertTrue(defaultOutcome.isNotBlank())
            } else {
                Log.i(TAG, "Live DefaultPreferOffline ko-KR handshake diagnostic: unavailable")
            }
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun probeBackend(backend: RecognitionBackend): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val latch = CountDownLatch(1)
        val outcome = AtomicReference("timeout(${RecognitionFailure.Timeout})")
        val holder = AtomicReference<SpeechRecognizer?>()

        instrumentation.runOnMainSync {
            try {
                val recognizer = when (backend) {
                    RecognitionBackend.OnDevice -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    RecognitionBackend.DefaultPreferOffline -> SpeechRecognizer.createSpeechRecognizer(context)
                    RecognitionBackend.Unavailable -> error("Unavailable")
                }
                holder.set(recognizer)
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        outcome.set("ready")
                        latch.countDown()
                    }
                    override fun onBeginningOfSpeech() = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onResults(results: Bundle?) = Unit
                    override fun onError(error: Int) {
                        val classified = AndroidRecognitionDriver.classify(error)
                        outcome.set("error=$error($classified,code=${classified.errorCode})")
                        latch.countDown()
                    }
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
                recognizer.startListening(AndroidRecognitionDriver.createRecognitionIntent(backend))
            } catch (_: SecurityException) {
                outcome.set("exception(${RecognitionFailure.Permission},code=${VoiceError.PERMISSION})")
                latch.countDown()
            } catch (e: Exception) {
                outcome.set("exception(${RecognitionFailure.Client}:${e.javaClass.simpleName})")
                latch.countDown()
            }
        }
        latch.await(3, TimeUnit.SECONDS)
        instrumentation.runOnMainSync {
            holder.get()?.let {
                runCatching { it.cancel() }
                runCatching { it.destroy() }
            }
        }
        return outcome.get()
    }

    @Test fun bundledSherpaKoreanStreamingWavDecodeAndWakeProbe() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SherpaRecognitionDriver.verifyBundledModelAssets(context.assets)

        val waveData = WaveReader.readWave(
            context.assets,
            SherpaRecognitionDriver.SAMPLE_WAV_ASSET,
        )
        assertEquals(SherpaRecognitionDriver.SAMPLE_RATE, waveData.sampleRate)
        assertTrue("Expected non-empty bundled Korean WAV samples", waveData.samples.isNotEmpty())

        val driver = SherpaRecognitionDriver(
            audioSource = WaveAudioCaptureSource(waveData.samples),
            engineFactory = { SherpaOnnxStreamingEngine(context.assets) },
        )
        try {
            val coldStart = SystemClock.elapsedRealtime()
            val coldOutcome = decodeOnceWithDriver(driver)
            val coldElapsedMs = SystemClock.elapsedRealtime() - coldStart

            Log.i(
                TAG,
                "Sherpa cold decode (${coldElapsedMs}ms): final='${coldOutcome.finalText}', " +
                    "partials=${coldOutcome.partials}, failure=${coldOutcome.failure}",
            )

            assertNull("Expected no recognition failure on bundled sample WAV", coldOutcome.failure)
            assertTrue("Expected driver.ready() on bundled sample WAV", coldOutcome.ready)
            assertTrue("Expected speechStarted() on bundled sample WAV", coldOutcome.speechStarted)
            assertTrue("Expected speechEnded() on bundled sample WAV", coldOutcome.speechEnded)
            assertTrue(
                "Expected Korean transcript containing '괜찮은' and '같았다', got '${coldOutcome.finalText}'",
                coldOutcome.finalText.contains("괜찮은") && coldOutcome.finalText.contains("같았다"),
            )
            assertNull(
                "Ordinary Korean sentence must not trigger wake phrase matcher",
                KoreanWakePhraseMatcher().selectWakeAfterPhrase(listOf(coldOutcome.finalText)),
            )

            // Verify warm-engine reuse on a second attempt without reloading the 111.5 MB model.
            val warmStart = SystemClock.elapsedRealtime()
            val warmOutcome = decodeOnceWithDriver(driver)
            val warmElapsedMs = SystemClock.elapsedRealtime() - warmStart

            Log.i(
                TAG,
                "Sherpa warm decode (${warmElapsedMs}ms): final='${warmOutcome.finalText}', " +
                    "failure=${warmOutcome.failure}",
            )
            assertNull("Expected no failure on warm reuse decode", warmOutcome.failure)
            assertEquals(coldOutcome.finalText, warmOutcome.finalText)
        } finally {
            driver.shutdown()
        }
    }

    private data class DriverDecodeOutcome(
        val ready: Boolean,
        val speechStarted: Boolean,
        val speechEnded: Boolean,
        val partials: List<String>,
        val finalText: String,
        val failure: RecognitionFailure?,
    )

    private fun decodeOnceWithDriver(driver: SherpaRecognitionDriver): DriverDecodeOutcome {
        val latch = CountDownLatch(1)
        val ready = AtomicBoolean(false)
        val speechStarted = AtomicBoolean(false)
        val speechEnded = AtomicBoolean(false)
        val partials = Collections.synchronizedList(mutableListOf<String>())
        val finalText = AtomicReference("")
        val failureRef = AtomicReference<RecognitionFailure?>(null)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            driver.start(object : RecognitionDriver.Listener {
                override fun ready() {
                    ready.set(true)
                }
                override fun speechStarted() {
                    speechStarted.set(true)
                }
                override fun speechEnded() {
                    speechEnded.set(true)
                }
                override fun partial(text: String) {
                    partials.add(text)
                }
                override fun partial(candidates: List<String>) {
                    candidates.firstOrNull()?.let { partials.add(it) }
                }
                override fun result(text: String) {
                    finalText.set(text)
                    latch.countDown()
                }
                override fun result(candidates: List<String>) {
                    finalText.set(candidates.firstOrNull().orEmpty())
                    latch.countDown()
                }
                override fun failed(failure: RecognitionFailure) {
                    failureRef.set(failure)
                    latch.countDown()
                }
            })
        }
        assertTrue("Timed out waiting for Sherpa streaming decode", latch.await(30, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            driver.close()
        }
        return DriverDecodeOutcome(
            ready = ready.get(),
            speechStarted = speechStarted.get(),
            speechEnded = speechEnded.get(),
            partials = partials.toList(),
            finalText = finalText.get(),
            failure = failureRef.get(),
        )
    }

    companion object {
        private const val TAG = "AriaSpeechProbe"
    }
}
