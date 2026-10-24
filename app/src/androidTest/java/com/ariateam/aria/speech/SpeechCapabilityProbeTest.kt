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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
                val driver = AndroidRecognitionDriver.production(context)
                val selected = driver.selectBackend()
                Log.i(TAG, "Production selected backend on device: $selected")
                if (SpeechRecognizer.isRecognitionAvailable(context)) {
                    assertEquals(RecognitionBackend.DefaultPreferOffline, selected)
                    val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
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

    companion object {
        private const val TAG = "AriaSpeechProbe"
    }
}
