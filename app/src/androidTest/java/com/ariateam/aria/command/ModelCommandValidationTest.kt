package com.ariateam.aria.command

import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ariateam.aria.speech.toBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ModelCommandValidationTest {
    @Test fun voiceSnapshotCarriesRecoveryStateThroughTheSameCallbackBundle() {
        val original = com.ariateam.aria.speech.VoiceState(
            com.ariateam.aria.speech.VoicePhase.Reconnecting, errorCode = com.ariateam.aria.speech.VoiceError.RECOGNITION_NETWORK,
            retryCount = 3, retryAtMillis = 12345,
        )
        val bundle = original.toBundle(42)
        assertEquals(original, com.ariateam.aria.speech.voiceStateFromBundle(bundle))
        assertEquals(42L, bundle.getLong("revision"))
    }

    @Test fun snapshotRoundTripPreservesNavigationAndPersistedSoundState() {
        val original = CommandSnapshot(message = "안내 소리를 껐어요.", audible = false,
            model = "설치됨", navigationId = 42, destination = "settings")
        assertEquals(original, CommandSnapshot.fromBundle(original.toBundle()))
    }

    /**
     * Opt-in real-device Gemma 4 E2B probe.
     * Enable via `-e runRealGemmaProbe true` (and optional `-e gemmaModelPath <absolute-path>`)
     * after pushing the verified 2,588,147,712-byte model file to the app external Download directory
     * (`.../Android/data/com.ariateam.aria/files/Download/gemma-e2b.litertlm`).
     */
    @Test fun realGemmaColdPrepareAndShortInferenceProbe() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val enabled = args.getString(ARG_RUN_REAL_PROBE)?.equals("true", ignoreCase = true) == true
        val targetContext = instrumentation.targetContext
        val downloadDir = requireNotNull(targetContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS))
        val explicitPath = args.getString(ARG_MODEL_PATH)?.trim().orEmpty()
        val candidates = buildList {
            if (explicitPath.isNotEmpty()) add(File(explicitPath))
            add(File(downloadDir, "gemma-e2b.litertlm"))
            add(File(downloadDir, "gemma-4-E2B-it.litertlm"))
        }
        val modelFile = candidates.firstOrNull { it.isFile && it.length() == GemmaModelStore.SIZE }
        assumeTrue(
            "Skipping opt-in real Gemma probe (enabled=$enabled, expectedSize=${GemmaModelStore.SIZE}, " +
                "defaultPath=${File(downloadDir, "gemma-e2b.litertlm").absolutePath})",
            enabled && modelFile != null,
        )
        val verifiedModel = requireNotNull(modelFile)

        val readyLatch = CountDownLatch(1)
        val terminalPrepareStatus = AtomicReference("")
        lateinit var client: LanguageClient
        val prepareStart = SystemClock.elapsedRealtime()
        instrumentation.runOnMainSync {
            client = LanguageClient(targetContext) { status ->
                if (client.ready || status.contains("실패") || status.contains("초과") || status.contains("오류") || status.contains("부족")) {
                    terminalPrepareStatus.set(status)
                    readyLatch.countDown()
                }
            }
            client.prepare(verifiedModel.absolutePath)
        }

        try {
            val preparedInTime = readyLatch.await(LanguageLimits.PREPARE_TIMEOUT_MILLIS + 5_000L, TimeUnit.MILLISECONDS)
            val prepareElapsedMs = SystemClock.elapsedRealtime() - prepareStart
            var isReady = false
            var statusAfterPrepare = ""
            instrumentation.runOnMainSync {
                isReady = client.ready
                statusAfterPrepare = client.lastStatus
            }
            Log.i(TAG, "phase=prepare completed=$preparedInTime ready=$isReady durationMs=$prepareElapsedMs status=$statusAfterPrepare")
            assertTrue(
                "Gemma prepare failed or timed out after ${prepareElapsedMs}ms (status=$statusAfterPrepare)",
                preparedInTime && isReady,
            )

            // 1. Cold first inference
            val coldOutcomeRef = AtomicReference<LanguageOutcome>()
            val coldLatch = CountDownLatch(1)
            val coldPrompt = CommandCoordinator.buildModelPrompt("소리가 좀 거슬려", null)
            val coldStart = SystemClock.elapsedRealtime()
            instrumentation.runOnMainSync {
                client.interpretOutcome(coldPrompt) { outcome ->
                    coldOutcomeRef.set(outcome)
                    coldLatch.countDown()
                }
            }
            val coldCompleted = coldLatch.await(LanguageLimits.COLD_INTERPRET_TIMEOUT_MILLIS + 5_000L, TimeUnit.MILLISECONDS)
            val coldElapsedMs = SystemClock.elapsedRealtime() - coldStart
            val coldOutcome = coldOutcomeRef.get()
            val coldParsed = (coldOutcome as? LanguageOutcome.Success)?.let { ModelCommandParser.parse(it.output) }
            Log.i(
                TAG,
                "phase=cold_interpret completed=$coldCompleted outcome=${describeOutcome(coldOutcome)} " +
                    "durationMs=$coldElapsedMs parsed=${describeInterpretation(coldParsed)}",
            )
            assertTrue(
                "Cold inference failed after ${coldElapsedMs}ms: ${describeOutcome(coldOutcome)}",
                coldCompleted && coldOutcome is LanguageOutcome.Success,
            )
            assertEquals(Interpretation.Execute(AppCommand.SoundOff), coldParsed)

            // 2. Warm second inference
            val warmOutcomeRef = AtomicReference<LanguageOutcome>()
            val warmLatch = CountDownLatch(1)
            val warmPrompt = CommandCoordinator.buildModelPrompt("설정 화면 보여줘", null)
            val warmStart = SystemClock.elapsedRealtime()
            instrumentation.runOnMainSync {
                client.interpretOutcome(warmPrompt) { outcome ->
                    warmOutcomeRef.set(outcome)
                    warmLatch.countDown()
                }
            }
            val warmCompleted = warmLatch.await(LanguageLimits.WARM_INTERPRET_TIMEOUT_MILLIS + 5_000L, TimeUnit.MILLISECONDS)
            val warmElapsedMs = SystemClock.elapsedRealtime() - warmStart
            val warmOutcome = warmOutcomeRef.get()
            val warmParsed = (warmOutcome as? LanguageOutcome.Success)?.let { ModelCommandParser.parse(it.output) }
            Log.i(
                TAG,
                "phase=warm_interpret completed=$warmCompleted outcome=${describeOutcome(warmOutcome)} " +
                    "durationMs=$warmElapsedMs parsed=${describeInterpretation(warmParsed)}",
            )
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putLong("gemma_prepare_ms", prepareElapsedMs)
                    putLong("gemma_cold_interpret_ms", coldElapsedMs)
                    putLong("gemma_warm_interpret_ms", warmElapsedMs)
                    putString("gemma_cold_parsed", describeInterpretation(coldParsed))
                    putString("gemma_warm_parsed", describeInterpretation(warmParsed))
                }
            )
            assertTrue(
                "Warm inference failed after ${warmElapsedMs}ms: ${describeOutcome(warmOutcome)}",
                warmCompleted && warmOutcome is LanguageOutcome.Success,
            )
            assertEquals(Interpretation.Execute(AppCommand.Settings), warmParsed)
        } finally {
            instrumentation.runOnMainSync {
                client.close()
            }
        }
    }

    private fun describeOutcome(outcome: LanguageOutcome?): String = when (outcome) {
        null -> "none"
        is LanguageOutcome.Success -> "success(${outcome.elapsedMillis}ms)"
        is LanguageOutcome.Failure -> "failure(${outcome.reason}:${outcome.detail})"
    }

    private fun describeInterpretation(interpretation: Interpretation?): String = when (interpretation) {
        null -> "none"
        is Interpretation.Execute -> "Execute(${interpretation.command})"
        is Interpretation.Clarify -> "Clarify"
        Interpretation.Unknown -> "Unknown"
    }

    companion object {
        private const val TAG = "AriaGemmaProbe"
        const val ARG_RUN_REAL_PROBE = "runRealGemmaProbe"
        const val ARG_MODEL_PATH = "gemmaModelPath"
    }
}
