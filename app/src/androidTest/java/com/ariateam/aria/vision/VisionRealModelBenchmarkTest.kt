package com.ariateam.aria.vision

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.ViewGroup
import android.view.WindowManager
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ariateam.aria.speech.VoiceService
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class VisionRealModelBenchmarkTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun wakeUpAndGrantPermissions() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val pkg = inst.targetContext.packageName
        runCatching {
            inst.uiAutomation.grantRuntimePermission(pkg, Manifest.permission.CAMERA)
        }
        runCatching {
            inst.uiAutomation.grantRuntimePermission(pkg, Manifest.permission.RECORD_AUDIO)
        }
        runCatching {
            inst.uiAutomation.executeShellCommand("pm grant $pkg android.permission.CAMERA")
            inst.uiAutomation.executeShellCommand("pm grant $pkg android.permission.RECORD_AUDIO")
            inst.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP")
            inst.uiAutomation.executeShellCommand("wm dismiss-keyguard")
        }
    }

    private fun checkVoiceServiceRunning(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return am?.getRunningServices(50)?.any {
            it.service.className == VoiceService::class.java.name
        } ?: false
    }

    private fun getCandidateModelArg(): String? =
        InstrumentationRegistry.getArguments().getString("candidateModelPath")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun resolveCandidateFile(context: Context, path: String): File {
        val supplied = File(path)
        return (if (supplied.isAbsolute) supplied else File(context.filesDir, path)).canonicalFile
    }

    private fun getCandidateReportFileName(defaultName: String): String =
        InstrumentationRegistry.getArguments().getString("candidateReportFileName")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: defaultName

    private fun assertGpuActive(store: VisionModelStore) {
        assertEquals(VisionBackendType.GPU, store.segmenter.configuredBackend)
        assertEquals(
            "GPU benchmark must not report a CPU fallback as GPU",
            VisionBackendType.GPU,
            store.segmenter.activeBackendType,
        )
    }

    private tailrec fun findActivity(context: Context?): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> findActivity(context.baseContext)
        else -> null
    }

    @Test
    fun realModelGpuBaselineBenchmark_measuresWarmupAnd20SecondSteadyWindow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // 1. Isolate vision baseline by stopping concurrent VoiceService if running
        val speechStateBefore = if (checkVoiceServiceRunning(context)) {
            runCatching {
                context.startService(Intent(context, VoiceService::class.java).setAction(VoiceService.ACTION_STOP))
            }
            var stopped = false
            val stopWaitStart = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - stopWaitStart < 2_500L) {
                if (!checkVoiceServiceRunning(context)) {
                    stopped = true
                    break
                }
                Thread.sleep(100L)
            }
            if (stopped) "STOPPED_VERIFIED" else "STOP_REQUESTED_STILL_RUNNING"
        } else {
            "ALREADY_STOPPED"
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryIntent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val initialBatteryTempC = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val initialThermalStatus = powerManager?.currentThermalStatus ?: -1

        val lifecycleOwnerRef = AtomicReference<LifecycleOwner?>(null)
        val previewViewRef = AtomicReference<PreviewView?>(null)

        composeRule.setContent {
            val owner = LocalLifecycleOwner.current
            lifecycleOwnerRef.set(owner)
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            keepScreenOn = true
                            findActivity(ctx)?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FIT_CENTER
                            previewViewRef.set(this)
                        }
                    },
                )
            }
        }
        composeRule.waitForIdle()

        val owner = requireNotNull(lifecycleOwnerRef.get()) { "LifecycleOwner not captured" }
        val previewView = requireNotNull(previewViewRef.get()) { "PreviewView not captured" }

        // Inject store with restoreOnInit = false to prevent asynchronous race on startup
        val store = VisionModelStore(context, restoreOnInit = false)
        val controller = VisionPipelineController(context, modelStore = store)
        try {
            // Provision the pinned default real model into store synchronously
            val provisionOutcome = store.provisionDefaultAssetModelSync()
            assertTrue("Default model must load, was $provisionOutcome", provisionOutcome is VisionModelState.Ready)
            assertTrue("Segmenter must be ready", store.segmenter.isReady)
            assertGpuActive(store)

            var surfaceProvider: androidx.camera.core.Preview.SurfaceProvider? = null
            var displayRotation = Surface.ROTATION_0
            composeRule.runOnUiThread {
                surfaceProvider = previewView.surfaceProvider
                displayRotation = previewView.display?.rotation ?: Surface.ROTATION_0
                controller.startCamera(
                    lifecycleOwner = owner,
                    surfaceProvider = surfaceProvider,
                    mode = VisionPipelineMode.PREPROCESS_OR_INFERENCE,
                    targetRotation = displayRotation,
                )
            }

            // Wait up to 8s for analyzer to deliver first frame
            val startWaitMs = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - startWaitMs < 8_000L) {
                if (controller.snapshotNow().warmupFrames > 0) break
                Thread.sleep(100L)
            }
            assertTrue(
                "CameraX analyzer did not deliver frames (status=${controller.uiState.value.cameraStatus}, detail=${controller.uiState.value.cameraErrorDetail})",
                controller.snapshotNow().warmupFrames > 0,
            )

            // Measure steady window (5s warmup + >= 60s steady window and >= 50 frames, deadline 95s total)
            val benchmarkDeadlineMs = SystemClock.elapsedRealtime() + 95_000L
            while (SystemClock.elapsedRealtime() < benchmarkDeadlineMs) {
                val snap = controller.snapshotNow()
                if (snap.warmupCompleted && snap.steadyElapsedMs >= 60_000.0 && snap.steadyFrames >= 50) {
                    break
                }
                Thread.sleep(200L)
            }

            val preprocessSnap = controller.snapshotNow()
            assertTrue(
                "Expected LifecycleOwner to remain RESUMED, was ${owner.lifecycle.currentState}",
                owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            )
            composeRule.runOnUiThread {
                controller.setPipelineMode(VisionPipelineMode.PREVIEW_ONLY)
            }
            Thread.sleep(4_000L)
            val previewOnlySnap = controller.snapshotNow()
            composeRule.runOnUiThread {
                controller.stopCamera()
            }

            val finalBatteryIntent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val finalBatteryTempC = (finalBatteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
            val finalThermalStatus = powerManager?.currentThermalStatus ?: -1

            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val pssKb = am?.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))?.firstOrNull()?.totalPss ?: -1
            val pssMb = if (pssKb > 0) pssKb / 1024.0 else -1.0

            val jsonReport = JSONObject().apply {
                put("benchmarkType", "REAL_MODEL_GPU_BASELINE")
                put("deviceModel", Build.MODEL)
                put("sdkInt", Build.VERSION.SDK_INT)
                put("modelName", VisionContract.DEFAULT_ASSET_MODEL_NAME)
                put("modelSizeBytes", VisionContract.DEFAULT_ASSET_MODEL_BYTES)
                put("modelSha256", VisionContract.DEFAULT_ASSET_MODEL_SHA256)
                put("modelCommit", VisionContract.DEFAULT_ASSET_MODEL_UPSTREAM_COMMIT)
                put("backendConfig", store.segmenter.activeBackendDescription)
                put("configuredBackend", store.segmenter.configuredBackend.name)
                put("activeBackendType", store.segmenter.activeBackendType?.name)
                put("gpuFallbackReason", store.segmenter.gpuFallbackCause?.let { "${it.javaClass.simpleName}: ${it.message}" })
                put("configuredCpuThreads", store.segmenter.configuredCpuThreads)
                put("activeBackendDescription", store.segmenter.activeBackendDescription)
                put("concurrentSpeechService", speechStateBefore)
                put("initialThermalStatus", initialThermalStatus)
                put("finalThermalStatus", finalThermalStatus)
                put("initialBatteryTempC", initialBatteryTempC)
                put("finalBatteryTempC", finalBatteryTempC)
                put("memoryPssKb", pssKb)
                put("memoryPssMb", String.format(Locale.US, "%.2f", pssMb))
                put("effectiveHfovDegrees", controller.motionTracker.engine.effectiveHfovDegrees?.let { String.format(Locale.US, "%.2f", it) } ?: "null")
                put(
                    "preprocessAndInferenceMode",
                    JSONObject().apply {
                        put("sourceWidth", preprocessSnap.sourceWidth)
                        put("sourceHeight", preprocessSnap.sourceHeight)
                        put("rotationDegrees", preprocessSnap.rotationDegrees)
                        put("previewUprightWidth", preprocessSnap.previewUprightWidth)
                        put("previewUprightHeight", preprocessSnap.previewUprightHeight)
                        put("cropLeft", preprocessSnap.cropRect?.left ?: 0)
                        put("cropTop", preprocessSnap.cropRect?.top ?: 0)
                        put("cropWidth", preprocessSnap.cropRect?.width ?: 0)
                        put("cropHeight", preprocessSnap.cropRect?.height ?: 0)
                        put("timestampSource", preprocessSnap.timestampSource.name)
                        put("warmupElapsedMs", String.format(Locale.US, "%.1f", preprocessSnap.warmupElapsedMs))
                        put("steadyElapsedMs", String.format(Locale.US, "%.1f", preprocessSnap.steadyElapsedMs))
                        put("warmupFrames", preprocessSnap.warmupFrames)
                        put("steadyFrames", preprocessSnap.steadyFrames)
                        put("sensorWarmupFrames", preprocessSnap.sensorWarmupFrames)
                        put("sensorSteadyFrames", preprocessSnap.sensorSteadyFrames)
                        put("observedCaptureMinusAnalyzerDiffSteady", preprocessSnap.observedCaptureMinusAnalyzerDiffSteady)
                        put("steadyAnalyzedFps", String.format(Locale.US, "%.2f", preprocessSnap.steadyProcessedFps))
                        put("steadySensorFps", String.format(Locale.US, "%.2f", preprocessSnap.steadySensorFps))
                        put("cameraDeliveryCadenceP50Ms", String.format(Locale.US, "%.2f", preprocessSnap.cameraDeliveryCadenceMs?.p50Ms ?: 0.0))
                        put("cameraDeliveryCadenceP95Ms", String.format(Locale.US, "%.2f", preprocessSnap.cameraDeliveryCadenceMs?.p95Ms ?: 0.0))
                        put("steadyPreprocessP50Ms", String.format(Locale.US, "%.2f", preprocessSnap.preprocessMs?.p50Ms ?: 0.0))
                        put("steadyPreprocessP95Ms", String.format(Locale.US, "%.2f", preprocessSnap.preprocessMs?.p95Ms ?: 0.0))
                        put("steadyModelInvokeP50Ms", String.format(Locale.US, "%.2f", preprocessSnap.modelInvokeMs?.p50Ms ?: 0.0))
                        put("steadyModelInvokeP95Ms", String.format(Locale.US, "%.2f", preprocessSnap.modelInvokeMs?.p95Ms ?: 0.0))
                        put("steadyPostprocessP50Ms", String.format(Locale.US, "%.2f", preprocessSnap.postprocessMs?.p50Ms ?: 0.0))
                        put("steadyPostprocessP95Ms", String.format(Locale.US, "%.2f", preprocessSnap.postprocessMs?.p95Ms ?: 0.0))
                        put("steadyTotalAnalyzerP50Ms", String.format(Locale.US, "%.2f", preprocessSnap.totalAnalyzerMs?.p50Ms ?: 0.0))
                        put("steadyTotalAnalyzerP95Ms", String.format(Locale.US, "%.2f", preprocessSnap.totalAnalyzerMs?.p95Ms ?: 0.0))
                        put("steadyPublicationP50Ms", String.format(Locale.US, "%.2f", preprocessSnap.resultPublicationMs?.p50Ms ?: 0.0))
                        put("steadyPublicationP95Ms", String.format(Locale.US, "%.2f", preprocessSnap.resultPublicationMs?.p95Ms ?: 0.0))
                        put("steadyCameraToResultAgeP50Ms", String.format(Locale.US, "%.2f", preprocessSnap.cameraToResultAgeMs?.p50Ms ?: 0.0))
                        put("steadyCameraToResultAgeP95Ms", String.format(Locale.US, "%.2f", preprocessSnap.cameraToResultAgeMs?.p95Ms ?: 0.0))
                    }
                )
                put(
                    "previewOnlyMode",
                    JSONObject().apply {
                        put("warmupElapsedMs", String.format(Locale.US, "%.1f", previewOnlySnap.warmupElapsedMs))
                        put("steadyElapsedMs", String.format(Locale.US, "%.1f", previewOnlySnap.steadyElapsedMs))
                        put("sensorWarmupFrames", previewOnlySnap.sensorWarmupFrames)
                        put("sensorSteadyFrames", previewOnlySnap.sensorSteadyFrames)
                        put("steadySensorFps", String.format(Locale.US, "%.2f", previewOnlySnap.steadySensorFps))
                        put("sensorCaptureCadenceP50Ms", String.format(Locale.US, "%.2f", previewOnlySnap.sensorCaptureCadenceMs?.p50Ms ?: 0.0))
                        put("sensorCaptureCadenceP95Ms", String.format(Locale.US, "%.2f", previewOnlySnap.sensorCaptureCadenceMs?.p95Ms ?: 0.0))
                    }
                )
            }

            val reportFile = File(context.filesDir, "baseline-gpu-model.json")
            reportFile.writeText(jsonReport.toString(2), StandardCharsets.UTF_8)
            runCatching {
                val sdDir = File("/sdcard/Download/final_reports").apply { mkdirs() }
                File(sdDir, "baseline-gpu-model.json").writeText(jsonReport.toString(2), StandardCharsets.UTF_8)
            }
            Log.i("AriaVisionBenchmark", "BASELINE_GPU_JSON=${jsonReport}")

            assertTrue(preprocessSnap.warmupCompleted)
            assertTrue("Expected steadyElapsedMs >= 60000.0, was ${preprocessSnap.steadyElapsedMs}", preprocessSnap.steadyElapsedMs >= 60_000.0)
            assertTrue(preprocessSnap.warmupFrames > 0)
            assertTrue("Expected steadyFrames >= 50, was ${preprocessSnap.steadyFrames}", preprocessSnap.steadyFrames >= 50)
            assertNotNull(preprocessSnap.preprocessMs)
            assertNotNull(preprocessSnap.modelInvokeMs)
            assertNotNull(preprocessSnap.postprocessMs)
            assertNotNull(preprocessSnap.totalAnalyzerMs)
            assertTrue("Expected modelInvokeMs.p50Ms > 0", (preprocessSnap.modelInvokeMs?.p50Ms ?: 0.0) > 0.0)
            assertTrue("Expected postprocessMs.p50Ms > 0", (preprocessSnap.postprocessMs?.p50Ms ?: 0.0) > 0.0)
            assertTrue("Expected preprocessMs.p50Ms > 0", (preprocessSnap.preprocessMs?.p50Ms ?: 0.0) > 0.0)

            assertTrue(preprocessSnap.warmupCompleted)
            assertTrue(preprocessSnap.sensorSteadyFrames >= 50)
        } finally {
            controller.close()
        }
    }

    @Test
    fun realModelGpuCandidateBenchmark_measuresWarmupAnd20SecondSteadyWindow() {
        val candidateArg = getCandidateModelArg()
        assumeTrue(
            "Candidate benchmark skipped: no candidate model path argument supplied (pass -e candidateModelPath <path>)",
            candidateArg != null,
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val candidateFile = resolveCandidateFile(context, requireNotNull(candidateArg))
        assertTrue(
            "Candidate model file does not exist or is not a file: ${candidateFile.absolutePath}",
            candidateFile.isFile,
        )
        assertTrue(
            "Candidate model file must not be empty: ${candidateFile.absolutePath}",
            candidateFile.length() > 0L,
        )

        // 1. Isolate vision baseline by stopping concurrent VoiceService if running
        val speechStateBefore = if (checkVoiceServiceRunning(context)) {
            runCatching {
                context.startService(Intent(context, VoiceService::class.java).setAction(VoiceService.ACTION_STOP))
            }
            var stopped = false
            val stopWaitStart = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - stopWaitStart < 2_500L) {
                if (!checkVoiceServiceRunning(context)) {
                    stopped = true
                    break
                }
                Thread.sleep(100L)
            }
            if (stopped) "STOPPED_VERIFIED" else "STOP_REQUESTED_STILL_RUNNING"
        } else {
            "ALREADY_STOPPED"
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryIntent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val initialBatteryTempC = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val initialThermalStatus = powerManager?.currentThermalStatus ?: -1

        val lifecycleOwnerRef = AtomicReference<LifecycleOwner?>(null)
        val previewViewRef = AtomicReference<PreviewView?>(null)

        composeRule.setContent {
            val owner = LocalLifecycleOwner.current
            lifecycleOwnerRef.set(owner)
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            keepScreenOn = true
                            findActivity(ctx)?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FIT_CENTER
                            previewViewRef.set(this)
                        }
                    },
                )
            }
        }
        composeRule.waitForIdle()

        val owner = requireNotNull(lifecycleOwnerRef.get()) { "LifecycleOwner not captured" }
        val previewView = requireNotNull(previewViewRef.get()) { "PreviewView not captured" }

        // Inject store with restoreOnInit = false to prevent asynchronous race on startup
        val store = VisionModelStore(context, restoreOnInit = false)
        val controller = VisionPipelineController(context, modelStore = store)
        try {
            // Import candidate model through VisionModelStore's validated stream import
            val importOutcome = candidateFile.inputStream().use { stream ->
                store.importFromStreamSync(
                    input = stream,
                    displayName = candidateFile.name,
                )
            }
            assertTrue("Candidate model import failed, was $importOutcome", importOutcome is VisionModelState.Ready)
            val readyState = importOutcome as VisionModelState.Ready
            val candidateMetadata = readyState.metadata
            assertTrue("Segmenter must be ready before camera start", store.segmenter.isReady)
            assertGpuActive(store)

            var surfaceProvider: androidx.camera.core.Preview.SurfaceProvider? = null
            var displayRotation = Surface.ROTATION_0
            composeRule.runOnUiThread {
                surfaceProvider = previewView.surfaceProvider
                displayRotation = previewView.display?.rotation ?: Surface.ROTATION_0
                controller.startCamera(
                    lifecycleOwner = owner,
                    surfaceProvider = surfaceProvider,
                    mode = VisionPipelineMode.PREPROCESS_OR_INFERENCE,
                    targetRotation = displayRotation,
                )
            }

            // Wait up to 8s for analyzer to deliver first frame
            val startWaitMs = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - startWaitMs < 8_000L) {
                if (controller.snapshotNow().warmupFrames > 0) break
                Thread.sleep(100L)
            }
            assertTrue(
                "CameraX analyzer did not deliver frames (status=${controller.uiState.value.cameraStatus}, detail=${controller.uiState.value.cameraErrorDetail})",
                controller.snapshotNow().warmupFrames > 0,
            )

            // Measure steady window (5s warmup + >= 60s steady window and >= 50 frames, deadline 95s total)
            val benchmarkDeadlineMs = SystemClock.elapsedRealtime() + 95_000L
            while (SystemClock.elapsedRealtime() < benchmarkDeadlineMs) {
                val snap = controller.snapshotNow()
                if (snap.warmupCompleted && snap.steadyElapsedMs >= 60_000.0 && snap.steadyFrames >= 50) {
                    break
                }
                Thread.sleep(200L)
            }

            val preprocessSnap = controller.snapshotNow()
            assertTrue(
                "Expected LifecycleOwner to remain RESUMED, was ${owner.lifecycle.currentState}",
                owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            )
            composeRule.runOnUiThread {
                controller.setPipelineMode(VisionPipelineMode.PREVIEW_ONLY)
            }
            Thread.sleep(4_000L)
            val previewOnlySnap = controller.snapshotNow()
            composeRule.runOnUiThread {
                controller.stopCamera()
            }

            val finalBatteryIntent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val finalBatteryTempC = (finalBatteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
            val finalThermalStatus = powerManager?.currentThermalStatus ?: -1

            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val pssKb = am?.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))?.firstOrNull()?.totalPss ?: -1
            val pssMb = if (pssKb > 0) pssKb / 1024.0 else -1.0

            val jsonReport = JSONObject().apply {
                put("benchmarkType", "REAL_MODEL_GPU_CANDIDATE")
                put("deviceModel", Build.MODEL)
                put("sdkInt", Build.VERSION.SDK_INT)
                put("modelName", candidateMetadata.displayName)
                put("modelSizeBytes", candidateMetadata.sizeBytes)
                put("modelSha256", candidateMetadata.sha256)
                put("modelSourcePath", candidateFile.absolutePath)
                put("backendConfig", store.segmenter.activeBackendDescription)
                put("configuredBackend", store.segmenter.configuredBackend.name)
                put("activeBackendType", store.segmenter.activeBackendType?.name)
                put("gpuFallbackReason", store.segmenter.gpuFallbackCause?.let { "${it.javaClass.simpleName}: ${it.message}" })
                put("configuredCpuThreads", store.segmenter.configuredCpuThreads)
                put("activeBackendDescription", store.segmenter.activeBackendDescription)
                put("concurrentSpeechService", speechStateBefore)
                put("initialThermalStatus", initialThermalStatus)
                put("finalThermalStatus", finalThermalStatus)
                put("initialBatteryTempC", initialBatteryTempC)
                put("finalBatteryTempC", finalBatteryTempC)
                put("memoryPssKb", pssKb)
                put("memoryPssMb", String.format(Locale.US, "%.2f", pssMb))
                put("effectiveHfovDegrees", controller.motionTracker.engine.effectiveHfovDegrees?.let { String.format(Locale.US, "%.2f", it) } ?: "null")
                put(
                    "preprocessAndInferenceMode",
                    JSONObject().apply {
                        put("sourceWidth", preprocessSnap.sourceWidth)
                        put("sourceHeight", preprocessSnap.sourceHeight)
                        put("rotationDegrees", preprocessSnap.rotationDegrees)
                        put("previewUprightWidth", preprocessSnap.previewUprightWidth)
                        put("previewUprightHeight", preprocessSnap.previewUprightHeight)
                        put("cropLeft", preprocessSnap.cropRect?.left ?: 0)
                        put("cropTop", preprocessSnap.cropRect?.top ?: 0)
                        put("cropWidth", preprocessSnap.cropRect?.width ?: 0)
                        put("cropHeight", preprocessSnap.cropRect?.height ?: 0)
                        put("timestampSource", preprocessSnap.timestampSource.name)
                        put("warmupElapsedMs", String.format(Locale.US, "%.1f", preprocessSnap.warmupElapsedMs))
                        put("steadyElapsedMs", String.format(Locale.US, "%.1f", preprocessSnap.steadyElapsedMs))
                        put("warmupFrames", preprocessSnap.warmupFrames)
                        put("steadyFrames", preprocessSnap.steadyFrames)
                        put("sensorWarmupFrames", preprocessSnap.sensorWarmupFrames)
                        put("sensorSteadyFrames", preprocessSnap.sensorSteadyFrames)
                        put("observedCaptureMinusAnalyzerDiffSteady", preprocessSnap.observedCaptureMinusAnalyzerDiffSteady)
                        put("steadyAnalyzedFps", String.format(Locale.US, "%.2f", preprocessSnap.steadyProcessedFps))
                        put("steadySensorFps", String.format(Locale.US, "%.2f", preprocessSnap.steadySensorFps))
                        put("cameraDeliveryCadenceP50Ms", String.format(Locale.US, "%.2f", preprocessSnap.cameraDeliveryCadenceMs?.p50Ms ?: 0.0))
                        put("cameraDeliveryCadenceP95Ms", String.format(Locale.US, "%.2f", preprocessSnap.cameraDeliveryCadenceMs?.p95Ms ?: 0.0))
                        put("steadyPreprocessP50Ms", String.format(Locale.US, "%.2f", preprocessSnap.preprocessMs?.p50Ms ?: 0.0))
                        put("steadyPreprocessP95Ms", String.format(Locale.US, "%.2f", preprocessSnap.preprocessMs?.p95Ms ?: 0.0))
                        put("steadyModelInvokeP50Ms", String.format(Locale.US, "%.2f", preprocessSnap.modelInvokeMs?.p50Ms ?: 0.0))
                        put("steadyModelInvokeP95Ms", String.format(Locale.US, "%.2f", preprocessSnap.modelInvokeMs?.p95Ms ?: 0.0))
                        put("steadyPostprocessP50Ms", String.format(Locale.US, "%.2f", preprocessSnap.postprocessMs?.p50Ms ?: 0.0))
                        put("steadyPostprocessP95Ms", String.format(Locale.US, "%.2f", preprocessSnap.postprocessMs?.p95Ms ?: 0.0))
                        put("steadyTotalAnalyzerP50Ms", String.format(Locale.US, "%.2f", preprocessSnap.totalAnalyzerMs?.p50Ms ?: 0.0))
                        put("steadyTotalAnalyzerP95Ms", String.format(Locale.US, "%.2f", preprocessSnap.totalAnalyzerMs?.p95Ms ?: 0.0))
                        put("steadyPublicationP50Ms", String.format(Locale.US, "%.2f", preprocessSnap.resultPublicationMs?.p50Ms ?: 0.0))
                        put("steadyPublicationP95Ms", String.format(Locale.US, "%.2f", preprocessSnap.resultPublicationMs?.p95Ms ?: 0.0))
                        put("steadyCameraToResultAgeP50Ms", String.format(Locale.US, "%.2f", preprocessSnap.cameraToResultAgeMs?.p50Ms ?: 0.0))
                        put("steadyCameraToResultAgeP95Ms", String.format(Locale.US, "%.2f", preprocessSnap.cameraToResultAgeMs?.p95Ms ?: 0.0))
                    }
                )
                put(
                    "previewOnlyMode",
                    JSONObject().apply {
                        put("warmupElapsedMs", String.format(Locale.US, "%.1f", previewOnlySnap.warmupElapsedMs))
                        put("steadyElapsedMs", String.format(Locale.US, "%.1f", previewOnlySnap.steadyElapsedMs))
                        put("sensorWarmupFrames", previewOnlySnap.sensorWarmupFrames)
                        put("sensorSteadyFrames", previewOnlySnap.sensorSteadyFrames)
                        put("steadySensorFps", String.format(Locale.US, "%.2f", previewOnlySnap.steadySensorFps))
                        put("sensorCaptureCadenceP50Ms", String.format(Locale.US, "%.2f", previewOnlySnap.sensorCaptureCadenceMs?.p50Ms ?: 0.0))
                        put("sensorCaptureCadenceP95Ms", String.format(Locale.US, "%.2f", previewOnlySnap.sensorCaptureCadenceMs?.p95Ms ?: 0.0))
                    }
                )
            }

            val args = InstrumentationRegistry.getArguments()
            val reportFileName = getCandidateReportFileName("candidate-gpu-model.json")
            val reportFile = File(context.filesDir, reportFileName)
            reportFile.writeText(jsonReport.toString(2), StandardCharsets.UTF_8)
            runCatching {
                val sdDir = File("/sdcard/Download/final_reports").apply { mkdirs() }
                File(sdDir, reportFileName).writeText(jsonReport.toString(2), StandardCharsets.UTF_8)
            }
            Log.i("AriaVisionBenchmark", "CANDIDATE_GPU_JSON=${jsonReport}")

            assertTrue(preprocessSnap.warmupCompleted)
            assertTrue("Expected steadyElapsedMs >= 60000.0, was ${preprocessSnap.steadyElapsedMs}", preprocessSnap.steadyElapsedMs >= 60_000.0)
            assertTrue(preprocessSnap.warmupFrames > 0)
            assertTrue("Expected steadyFrames >= 50, was ${preprocessSnap.steadyFrames}", preprocessSnap.steadyFrames >= 50)
            assertNotNull(preprocessSnap.preprocessMs)
            assertNotNull(preprocessSnap.modelInvokeMs)
            assertNotNull(preprocessSnap.postprocessMs)
            assertNotNull(preprocessSnap.totalAnalyzerMs)
            assertTrue("Expected modelInvokeMs.p50Ms > 0", (preprocessSnap.modelInvokeMs?.p50Ms ?: 0.0) > 0.0)
            assertTrue("Expected postprocessMs.p50Ms > 0", (preprocessSnap.postprocessMs?.p50Ms ?: 0.0) > 0.0)
            assertTrue("Expected preprocessMs.p50Ms > 0", (preprocessSnap.preprocessMs?.p50Ms ?: 0.0) > 0.0)

            assertTrue(preprocessSnap.warmupCompleted)
            assertTrue(preprocessSnap.sensorSteadyFrames >= 50)
        } finally {
            runCatching {
                store.clearModelSync()
                store.provisionDefaultAssetModelSync()
            }
            controller.close()
        }
    }

    @Test
    fun realModelOptimizedGpuBenchmark_measuresWarmupAnd20SecondSteadyWindow() {
        val candidateArg = getCandidateModelArg()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val candidateFile = candidateArg?.let { resolveCandidateFile(context, it) }
        val store = VisionModelStore(context, restoreOnInit = false)
        val controller = VisionPipelineController(context, modelStore = store)

        // Handle candidate model if provided, otherwise use optimized asset model
        val controllerSetup: () -> Unit = if (candidateFile != null) {
            assertTrue(
                "Candidate model file does not exist or is not a file: ${candidateFile.absolutePath}",
                candidateFile.isFile,
            )
            assertTrue(
                "Candidate model file must not be empty: ${candidateFile.absolutePath}",
                candidateFile.length() > 0L,
            )
            val setup: () -> Unit = {
                // Import candidate model through VisionModelStore's validated stream import
                val importOutcome = candidateFile.inputStream().use { stream ->
                    store.importFromStreamSync(
                        input = stream,
                        displayName = candidateFile.name,
                    )
                }
                assertTrue("Candidate model import failed, was $importOutcome", importOutcome is VisionModelState.Ready)
                assertTrue("Segmenter must be ready", store.segmenter.isReady)
            }
            setup
        } else {
            {
                // Provision the optimized model from assets
                val provisionOutcome = store.provisionOptimizedAssetModelSync()
                assertTrue("Optimized model must load, was $provisionOutcome", provisionOutcome is VisionModelState.Ready)
                assertTrue("Segmenter must be ready", store.segmenter.isReady)
            }
        }

        // 1. Isolate vision baseline by stopping concurrent VoiceService if running
        val speechStateBefore = if (checkVoiceServiceRunning(context)) {
            runCatching {
                context.startService(Intent(context, VoiceService::class.java).setAction(VoiceService.ACTION_STOP))
            }
            var stopped = false
            val stopWaitStart = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - stopWaitStart < 2_500L) {
                if (!checkVoiceServiceRunning(context)) {
                    stopped = true
                    break
                }
                Thread.sleep(100L)
            }
            if (stopped) "STOPPED_VERIFIED" else "STOP_REQUESTED_STILL_RUNNING"
        } else {
            "ALREADY_STOPPED"
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryIntent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val initialBatteryTempC = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val initialThermalStatus = powerManager?.currentThermalStatus ?: -1

        val lifecycleOwnerRef = AtomicReference<LifecycleOwner?>(null)
        val previewViewRef = AtomicReference<PreviewView?>(null)

        composeRule.setContent {
            val owner = LocalLifecycleOwner.current
            lifecycleOwnerRef.set(owner)
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            keepScreenOn = true
                            findActivity(ctx)?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FIT_CENTER
                            previewViewRef.set(this)
                        }
                    },
                )
            }
        }
        composeRule.waitForIdle()

        val owner = requireNotNull(lifecycleOwnerRef.get()) { "LifecycleOwner not captured" }
        val previewView = requireNotNull(previewViewRef.get()) { "PreviewView not captured" }

        try {
            controllerSetup()

            // Assert GPU backend after model loading
            assertGpuActive(store)

            var surfaceProvider: androidx.camera.core.Preview.SurfaceProvider? = null
            var displayRotation = Surface.ROTATION_0
            composeRule.runOnUiThread {
                surfaceProvider = previewView.surfaceProvider
                displayRotation = previewView.display?.rotation ?: Surface.ROTATION_0
                controller.startCamera(
                    lifecycleOwner = owner,
                    surfaceProvider = surfaceProvider,
                    mode = VisionPipelineMode.PREPROCESS_OR_INFERENCE,
                    targetRotation = displayRotation,
                )
            }

            // Wait up to 8s for analyzer to deliver first frame
            val startWaitMs = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - startWaitMs < 8_000L) {
                if (controller.snapshotNow().warmupFrames > 0) break
                Thread.sleep(100L)
            }
            assertTrue(
                "CameraX analyzer did not deliver frames (status=${controller.uiState.value.cameraStatus}, detail=${controller.uiState.value.cameraErrorDetail})",
                controller.snapshotNow().warmupFrames > 0,
            )

            // Measure steady window (5s warmup + >= 21s steady window and >= 50 frames, deadline 40s total)
            val benchmarkDeadlineMs = SystemClock.elapsedRealtime() + 40_000L
            while (SystemClock.elapsedRealtime() < benchmarkDeadlineMs) {
                val snap = controller.snapshotNow()
                if (snap.warmupCompleted && snap.steadyElapsedMs >= 21_000.0 && snap.steadyFrames >= 50) {
                    break
                }
                Thread.sleep(200L)
            }

            val gpuSnap = controller.snapshotNow()
            assertTrue(
                "Expected LifecycleOwner to remain RESUMED, was ${owner.lifecycle.currentState}",
                owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            )
            composeRule.runOnUiThread {
                controller.setPipelineMode(VisionPipelineMode.PREVIEW_ONLY)
            }
            Thread.sleep(4_000L)
            val previewOnlySnap = controller.snapshotNow()
            composeRule.runOnUiThread {
                controller.stopCamera()
            }

            val finalBatteryIntent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val finalBatteryTempC = (finalBatteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
            val finalThermalStatus = powerManager?.currentThermalStatus ?: -1

            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val pssKb = am?.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))?.firstOrNull()?.totalPss ?: -1
            val pssMb = if (pssKb > 0) pssKb / 1024.0 else -1.0

            val jsonReport = JSONObject().apply {
                put("benchmarkType", if (candidateArg != null) "REAL_MODEL_GPU_CANDIDATE" else "REAL_MODEL_OPTIMIZED_GPU_VOICE_OFF")
                put("deviceModel", Build.MODEL)
                put("sdkInt", Build.VERSION.SDK_INT)
                put("modelName", if (candidateFile != null) candidateFile.name else VisionContract.OPTIMIZED_ASSET_MODEL_NAME)
                put("modelSizeBytes", if (candidateFile != null) candidateFile.length() else VisionContract.OPTIMIZED_ASSET_MODEL_BYTES)
                put("modelSha256", if (candidateFile != null) {
                    // Calculate SHA-256 of candidate file
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    candidateFile.inputStream().use { it ->
                        digest.update(it.readBytes())
                    }
                    digest.digest().joinToString("") { "%02x".format(it) }
                } else VisionContract.OPTIMIZED_ASSET_MODEL_SHA256)
                put("modelSourcePath", candidateFile?.absolutePath)
                put("backendConfig", store.segmenter.activeBackendDescription)
                put("configuredBackend", store.segmenter.configuredBackend.name)
                put("activeBackendType", store.segmenter.activeBackendType?.name)
                put("gpuFallbackReason", store.segmenter.gpuFallbackCause?.let { "${it.javaClass.simpleName}: ${it.message}" })
                put("configuredCpuThreads", store.segmenter.configuredCpuThreads)
                 put("activeBackendDescription", store.segmenter.activeBackendDescription)
                put("concurrentSpeechService", speechStateBefore)
                put("initialThermalStatus", initialThermalStatus)
                put("finalThermalStatus", finalThermalStatus)
                put("initialBatteryTempC", initialBatteryTempC)
                put("finalBatteryTempC", finalBatteryTempC)
                put("memoryPssKb", pssKb)
                put("memoryPssMb", String.format(Locale.US, "%.2f", pssMb))
                put("effectiveHfovDegrees", controller.motionTracker.engine.effectiveHfovDegrees?.let { String.format(Locale.US, "%.2f", it) } ?: "null")
                put(
                    "preprocessAndInferenceMode",
                    JSONObject().apply {
                        put("sourceWidth", gpuSnap.sourceWidth)
                        put("sourceHeight", gpuSnap.sourceHeight)
                        put("rotationDegrees", gpuSnap.rotationDegrees)
                        put("previewUprightWidth", gpuSnap.previewUprightWidth)
                        put("previewUprightHeight", gpuSnap.previewUprightHeight)
                        put("cropLeft", gpuSnap.cropRect?.left ?: 0)
                        put("cropTop", gpuSnap.cropRect?.top ?: 0)
                        put("cropWidth", gpuSnap.cropRect?.width ?: 0)
                        put("cropHeight", gpuSnap.cropRect?.height ?: 0)
                        put("timestampSource", gpuSnap.timestampSource.name)
                        put("warmupElapsedMs", String.format(Locale.US, "%.1f", gpuSnap.warmupElapsedMs))
                        put("steadyElapsedMs", String.format(Locale.US, "%.1f", gpuSnap.steadyElapsedMs))
                        put("warmupFrames", gpuSnap.warmupFrames)
                        put("steadyFrames", gpuSnap.steadyFrames)
                        put("sensorWarmupFrames", gpuSnap.sensorWarmupFrames)
                        put("sensorSteadyFrames", gpuSnap.sensorSteadyFrames)
                        put("observedCaptureMinusAnalyzerDiffSteady", gpuSnap.observedCaptureMinusAnalyzerDiffSteady)
                        put("steadyAnalyzedFps", String.format(Locale.US, "%.2f", gpuSnap.steadyProcessedFps))
                        put("steadySensorFps", String.format(Locale.US, "%.2f", gpuSnap.steadySensorFps))
                        put("cameraDeliveryCadenceP50Ms", String.format(Locale.US, "%.2f", gpuSnap.cameraDeliveryCadenceMs?.p50Ms ?: 0.0))
                        put("cameraDeliveryCadenceP95Ms", String.format(Locale.US, "%.2f", gpuSnap.cameraDeliveryCadenceMs?.p95Ms ?: 0.0))
                        put("steadyPreprocessP50Ms", String.format(Locale.US, "%.2f", gpuSnap.preprocessMs?.p50Ms ?: 0.0))
                        put("steadyPreprocessP95Ms", String.format(Locale.US, "%.2f", gpuSnap.preprocessMs?.p95Ms ?: 0.0))
                        put("steadyModelInvokeP50Ms", String.format(Locale.US, "%.2f", gpuSnap.modelInvokeMs?.p50Ms ?: 0.0))
                        put("steadyModelInvokeP95Ms", String.format(Locale.US, "%.2f", gpuSnap.modelInvokeMs?.p95Ms ?: 0.0))
                        put("steadyPostprocessP50Ms", String.format(Locale.US, "%.2f", gpuSnap.postprocessMs?.p50Ms ?: 0.0))
                        put("steadyPostprocessP95Ms", String.format(Locale.US, "%.2f", gpuSnap.postprocessMs?.p95Ms ?: 0.0))
                        put("steadyTotalAnalyzerP50Ms", String.format(Locale.US, "%.2f", gpuSnap.totalAnalyzerMs?.p50Ms ?: 0.0))
                        put("steadyTotalAnalyzerP95Ms", String.format(Locale.US, "%.2f", gpuSnap.totalAnalyzerMs?.p95Ms ?: 0.0))
                        put("steadyPublicationP50Ms", String.format(Locale.US, "%.2f", gpuSnap.resultPublicationMs?.p50Ms ?: 0.0))
                        put("steadyPublicationP95Ms", String.format(Locale.US, "%.2f", gpuSnap.resultPublicationMs?.p95Ms ?: 0.0))
                        put("steadyCameraToResultAgeP50Ms", String.format(Locale.US, "%.2f", gpuSnap.cameraToResultAgeMs?.p50Ms ?: 0.0))
                        put("steadyCameraToResultAgeP95Ms", String.format(Locale.US, "%.2f", gpuSnap.cameraToResultAgeMs?.p95Ms ?: 0.0))
                    }
                )
                put(
                    "previewOnlyMode",
                    JSONObject().apply {
                        put("warmupElapsedMs", String.format(Locale.US, "%.1f", previewOnlySnap.warmupElapsedMs))
                        put("steadyElapsedMs", String.format(Locale.US, "%.1f", previewOnlySnap.steadyElapsedMs))
                        put("sensorWarmupFrames", previewOnlySnap.sensorWarmupFrames)
                        put("sensorSteadyFrames", previewOnlySnap.sensorSteadyFrames)
                        put("steadySensorFps", String.format(Locale.US, "%.2f", previewOnlySnap.steadySensorFps))
                        put("sensorCaptureCadenceP50Ms", String.format(Locale.US, "%.2f", previewOnlySnap.sensorCaptureCadenceMs?.p50Ms ?: 0.0))
                        put("sensorCaptureCadenceP95Ms", String.format(Locale.US, "%.2f", previewOnlySnap.sensorCaptureCadenceMs?.p95Ms ?: 0.0))
                    }
                )
            }

            val reportFileName = if (candidateFile != null) {
                getCandidateReportFileName("candidate-gpu-model.json")
            } else {
                "optimized-gpu-model.json"
            }
            val reportFile = File(context.filesDir, reportFileName)
            reportFile.writeText(jsonReport.toString(2), StandardCharsets.UTF_8)
            runCatching {
                val sdDir = File("/sdcard/Download/final_reports").apply { mkdirs() }
                File(sdDir, reportFileName).writeText(jsonReport.toString(2), StandardCharsets.UTF_8)
            }
            Log.i("AriaVisionBenchmark", "${if (candidateArg != null) "CANDIDATE_GPU_JSON" else "OPTIMIZED_GPU_JSON"}=${jsonReport}")

            assertTrue(gpuSnap.warmupCompleted)
            assertTrue("Expected steadyElapsedMs >= 20000.0, was ${gpuSnap.steadyElapsedMs}", gpuSnap.steadyElapsedMs >= 20_000.0)
            assertTrue(gpuSnap.warmupFrames > 0)
            assertTrue("Expected steadyFrames >= 50, was ${gpuSnap.steadyFrames}", gpuSnap.steadyFrames >= 50)
            assertNotNull(gpuSnap.preprocessMs)
            assertNotNull(gpuSnap.modelInvokeMs)
            assertNotNull(gpuSnap.postprocessMs)
            assertNotNull(gpuSnap.totalAnalyzerMs)
            assertTrue("Expected modelInvokeMs.p50Ms > 0", (gpuSnap.modelInvokeMs?.p50Ms ?: 0.0) > 0.0)
            assertTrue("Expected GPU invoke < 150ms on Pixel 3", (gpuSnap.modelInvokeMs?.p50Ms ?: 0.0) < 150.0)
        } finally {
            // Restore default model if we imported a candidate
            if (candidateArg != null) {
                runCatching {
                    store.clearModelSync()
                    store.provisionDefaultAssetModelSync()
                }
            }
            controller.close()
        }
    }

    @Test
    fun realModelOptimizedGpuBenchmark_measuresWarmupAnd20SecondSteadyWindow_voiceActive() {
        val candidateArg = getCandidateModelArg()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val candidateFile = candidateArg?.let { resolveCandidateFile(context, it) }
        val inst = InstrumentationRegistry.getInstrumentation()
        val pkg = inst.targetContext.packageName
        val store = VisionModelStore(context, restoreOnInit = false)
        val controller = VisionPipelineController(context, modelStore = store)

        // Handle candidate model if provided, otherwise use optimized asset model
        val controllerSetup: () -> Unit = if (candidateFile != null) {
            assertTrue(
                "Candidate model file does not exist or is not a file: ${candidateFile.absolutePath}",
                candidateFile.isFile,
            )
            assertTrue(
                "Candidate model file must not be empty: ${candidateFile.absolutePath}",
                candidateFile.length() > 0L,
            )
            val setup: () -> Unit = {
                // Import candidate model through VisionModelStore's validated stream import
                val importOutcome = candidateFile.inputStream().use { stream ->
                    store.importFromStreamSync(
                        input = stream,
                        displayName = candidateFile.name,
                    )
                }
                assertTrue("Candidate model import failed, was $importOutcome", importOutcome is VisionModelState.Ready)
                assertTrue("Segmenter must be ready", store.segmenter.isReady)
            }
            setup
        } else {
            {
                // Provision the optimized model from assets
                val provisionOutcome = store.provisionOptimizedAssetModelSync()
                assertTrue("Optimized model must load, was $provisionOutcome", provisionOutcome is VisionModelState.Ready)
                assertTrue("Segmenter must be ready", store.segmenter.isReady)
            }
        }

        val lifecycleOwnerRef = AtomicReference<LifecycleOwner?>(null)
        val previewViewRef = AtomicReference<PreviewView?>(null)

        // 1. Mount test Activity UI and ensure it is resumed first
        composeRule.setContent {
            val owner = LocalLifecycleOwner.current
            lifecycleOwnerRef.set(owner)
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            keepScreenOn = true
                            findActivity(ctx)?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FIT_CENTER
                            previewViewRef.set(this)
                        }
                    },
                )
            }
        }
        composeRule.waitForIdle()

        // 2. Explicitly grant RECORD_AUDIO and CAMERA runtime permissions
        runCatching { inst.uiAutomation.grantRuntimePermission(pkg, Manifest.permission.RECORD_AUDIO) }
        runCatching { inst.uiAutomation.grantRuntimePermission(pkg, Manifest.permission.CAMERA) }
        runCatching { inst.uiAutomation.executeShellCommand("pm grant $pkg android.permission.RECORD_AUDIO") }
        runCatching { inst.uiAutomation.executeShellCommand("pm grant $pkg android.permission.CAMERA") }

        // 3. Start VoiceService in ACTION_WAIT and bind to IVoiceService to verify real mic listening
        var remoteVoiceService: com.ariateam.aria.speech.IVoiceService? = null
        val serviceConn = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
                remoteVoiceService = com.ariateam.aria.speech.IVoiceService.Stub.asInterface(service)
            }
            override fun onServiceDisconnected(name: android.content.ComponentName?) {
                remoteVoiceService = null
            }
        }

        try {
        androidx.core.content.ContextCompat.startForegroundService(
            context,
            Intent(context, VoiceService::class.java).setAction(VoiceService.ACTION_WAIT),
        )
        context.bindService(
            Intent(context, VoiceService::class.java),
            serviceConn,
            Context.BIND_AUTO_CREATE,
        )

        // Poll up to 6s for service to bind and enter phase=Waiting with errorCode=0, wakeFallback=false
        val bindStartMs = SystemClock.elapsedRealtime()
        var phaseBefore = ""
        var errorBefore = -1
        var wakeFallbackBefore = true
        while (SystemClock.elapsedRealtime() - bindStartMs < 6_000L) {
            val rvs = remoteVoiceService
            if (rvs != null) {
                val b = rvs.state
                val p = com.ariateam.aria.speech.VoicePhase.fromWire(b.getInt("phase"))
                val ec = b.getInt("errorCode")
                val wf = b.getBoolean("wakeFallback", false)
                phaseBefore = p.name
                errorBefore = ec
                wakeFallbackBefore = wf
                if (p == com.ariateam.aria.speech.VoicePhase.Waiting && ec == 0 && !wf) {
                    break
                }
            }
            Thread.sleep(100L)
        }

        assertEquals("VoiceService must be in Waiting phase before steady window", "Waiting", phaseBefore)
        assertEquals("VoiceService errorCode must be 0 (NONE) before steady window", 0, errorBefore)
        assertFalse("VoiceService wakeFallback must be false before steady window", wakeFallbackBefore)

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryIntent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val initialBatteryTempC = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val initialThermalStatus = powerManager?.currentThermalStatus ?: -1

        val owner = requireNotNull(lifecycleOwnerRef.get()) { "LifecycleOwner not captured" }
        val previewView = requireNotNull(previewViewRef.get()) { "PreviewView not captured" }

            controllerSetup()

            // Assert GPU backend after model loading
            assertGpuActive(store)

            var surfaceProvider: androidx.camera.core.Preview.SurfaceProvider? = null
            var displayRotation = Surface.ROTATION_0
            composeRule.runOnUiThread {
                surfaceProvider = previewView.surfaceProvider
                displayRotation = previewView.display?.rotation ?: Surface.ROTATION_0
                controller.startCamera(
                    lifecycleOwner = owner,
                    surfaceProvider = surfaceProvider,
                    mode = VisionPipelineMode.PREPROCESS_OR_INFERENCE,
                    targetRotation = displayRotation,
                )
            }

            val startWaitMs = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - startWaitMs < 8_000L) {
                if (controller.snapshotNow().warmupFrames > 0) break
                Thread.sleep(100L)
            }
            assertTrue(
                "CameraX analyzer did not deliver frames",
                controller.snapshotNow().warmupFrames > 0,
            )

            val benchmarkDeadlineMs = SystemClock.elapsedRealtime() + 40_000L
            while (SystemClock.elapsedRealtime() < benchmarkDeadlineMs) {
                val snap = controller.snapshotNow()
                if (snap.warmupCompleted && snap.steadyElapsedMs >= 21_000.0 && snap.steadyFrames >= 50) {
                    break
                }
                Thread.sleep(200L)
            }

            val effectiveHfovAfter = controller.motionTracker.engine.effectiveHfovDegrees
            assertNotNull("effectiveHfovDegrees must not be null while camera is active", effectiveHfovAfter)
            val hfovVal = requireNotNull(effectiveHfovAfter)
            assertTrue("Derived portrait HFOV on Pixel 3 (rotation 90/270) must be narrower than 60 deg, was $hfovVal deg", hfovVal < 60f)
            assertTrue("Derived portrait HFOV on Pixel 3 must be > 40 deg, was $hfovVal deg", hfovVal > 40f)

            val gpuSnap = controller.snapshotNow()
            composeRule.runOnUiThread {
                controller.setPipelineMode(VisionPipelineMode.PREVIEW_ONLY)
            }
            Thread.sleep(4_000L)
            val previewOnlySnap = controller.snapshotNow()
            composeRule.runOnUiThread {
                controller.stopCamera()
            }

            // Verify VoiceService state remained Waiting, errorCode=0, wakeFallback=false throughout steady window
            val stateBundleAfter = remoteVoiceService?.state
            val phaseAfter = if (stateBundleAfter != null) com.ariateam.aria.speech.VoicePhase.fromWire(stateBundleAfter.getInt("phase")).name else "UNKNOWN"
            val errorAfter = stateBundleAfter?.getInt("errorCode") ?: -1
            val wakeFallbackAfter = stateBundleAfter?.getBoolean("wakeFallback", false) ?: true

            assertEquals("VoiceService must remain in Waiting phase after steady window", "Waiting", phaseAfter)
            assertEquals("VoiceService errorCode must remain 0 after steady window", 0, errorAfter)
            assertFalse("VoiceService wakeFallback must remain false after steady window", wakeFallbackAfter)

            val finalBatteryIntent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val finalBatteryTempC = (finalBatteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
            val finalThermalStatus = powerManager?.currentThermalStatus ?: -1

            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val pssKb = am?.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))?.firstOrNull()?.totalPss ?: -1
            val pssMb = if (pssKb > 0) pssKb / 1024.0 else -1.0

            val jsonReport = JSONObject().apply {
                put("benchmarkType", if (candidateArg != null) "REAL_MODEL_GPU_CANDIDATE_VOICE_ACTIVE" else "REAL_MODEL_OPTIMIZED_GPU_VOICE_ACTIVE")
                put("deviceModel", Build.MODEL)
                put("sdkInt", Build.VERSION.SDK_INT)
                put("modelName", if (candidateFile != null) candidateFile.name else VisionContract.OPTIMIZED_ASSET_MODEL_NAME)
                put("modelSizeBytes", if (candidateFile != null) candidateFile.length() else VisionContract.OPTIMIZED_ASSET_MODEL_BYTES)
                put("modelSha256", if (candidateFile != null) {
                    // Calculate SHA-256 of candidate file
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    candidateFile.inputStream().use { it ->
                        digest.update(it.readBytes())
                    }
                    digest.digest().joinToString("") { "%02x".format(it) }
                } else VisionContract.OPTIMIZED_ASSET_MODEL_SHA256)
                put("modelSourcePath", candidateFile?.absolutePath)
                put("backendConfig", store.segmenter.activeBackendDescription)
                put("configuredBackend", store.segmenter.configuredBackend.name)
                put("activeBackendType", store.segmenter.activeBackendType?.name)
                put("gpuFallbackReason", store.segmenter.gpuFallbackCause?.let { "${it.javaClass.simpleName}: ${it.message}" })
                put("configuredCpuThreads", store.segmenter.configuredCpuThreads)
                 put("activeBackendDescription", store.segmenter.activeBackendDescription)
                put("concurrentSpeechService", "ACTIVE_VERIFIED")
                put("speechServicePhaseBefore", phaseBefore)
                put("speechServiceErrorCodeBefore", errorBefore)
                put("speechServiceWakeFallbackBefore", wakeFallbackBefore)
                put("speechServicePhaseAfter", phaseAfter)
                put("speechServiceErrorCodeAfter", errorAfter)
                put("speechServiceWakeFallbackAfter", wakeFallbackAfter)
                put("initialThermalStatus", initialThermalStatus)
                put("finalThermalStatus", finalThermalStatus)
                put("initialBatteryTempC", initialBatteryTempC)
                put("finalBatteryTempC", finalBatteryTempC)
                put("memoryPssKb", pssKb)
                put("memoryPssMb", String.format(Locale.US, "%.2f", pssMb))
                put("effectiveHfovDegrees", controller.motionTracker.engine.effectiveHfovDegrees?.let { String.format(Locale.US, "%.2f", it) } ?: "null")
                put(
                    "preprocessAndInferenceMode",
                    JSONObject().apply {
                        put("sourceWidth", gpuSnap.sourceWidth)
                        put("sourceHeight", gpuSnap.sourceHeight)
                        put("rotationDegrees", gpuSnap.rotationDegrees)
                        put("previewUprightWidth", gpuSnap.previewUprightWidth)
                        put("previewUprightHeight", gpuSnap.previewUprightHeight)
                        put("cropLeft", gpuSnap.cropRect?.left ?: 0)
                        put("cropTop", gpuSnap.cropRect?.top ?: 0)
                        put("cropWidth", gpuSnap.cropRect?.width ?: 0)
                        put("cropHeight", gpuSnap.cropRect?.height ?: 0)
                        put("timestampSource", gpuSnap.timestampSource.name)
                        put("warmupElapsedMs", String.format(Locale.US, "%.1f", gpuSnap.warmupElapsedMs))
                        put("steadyElapsedMs", String.format(Locale.US, "%.1f", gpuSnap.steadyElapsedMs))
                        put("warmupFrames", gpuSnap.warmupFrames)
                        put("steadyFrames", gpuSnap.steadyFrames)
                        put("sensorWarmupFrames", gpuSnap.sensorWarmupFrames)
                        put("sensorSteadyFrames", gpuSnap.sensorSteadyFrames)
                        put("observedCaptureMinusAnalyzerDiffSteady", gpuSnap.observedCaptureMinusAnalyzerDiffSteady)
                        put("steadyAnalyzedFps", String.format(Locale.US, "%.2f", gpuSnap.steadyProcessedFps))
                        put("steadySensorFps", String.format(Locale.US, "%.2f", gpuSnap.steadySensorFps))
                        put("cameraDeliveryCadenceP50Ms", String.format(Locale.US, "%.2f", gpuSnap.cameraDeliveryCadenceMs?.p50Ms ?: 0.0))
                        put("cameraDeliveryCadenceP95Ms", String.format(Locale.US, "%.2f", gpuSnap.cameraDeliveryCadenceMs?.p95Ms ?: 0.0))
                        put("steadyPreprocessP50Ms", String.format(Locale.US, "%.2f", gpuSnap.preprocessMs?.p50Ms ?: 0.0))
                        put("steadyPreprocessP95Ms", String.format(Locale.US, "%.2f", gpuSnap.preprocessMs?.p95Ms ?: 0.0))
                        put("steadyModelInvokeP50Ms", String.format(Locale.US, "%.2f", gpuSnap.modelInvokeMs?.p50Ms ?: 0.0))
                        put("steadyModelInvokeP95Ms", String.format(Locale.US, "%.2f", gpuSnap.modelInvokeMs?.p95Ms ?: 0.0))
                        put("steadyPostprocessP50Ms", String.format(Locale.US, "%.2f", gpuSnap.postprocessMs?.p50Ms ?: 0.0))
                        put("steadyPostprocessP95Ms", String.format(Locale.US, "%.2f", gpuSnap.postprocessMs?.p95Ms ?: 0.0))
                        put("steadyTotalAnalyzerP50Ms", String.format(Locale.US, "%.2f", gpuSnap.totalAnalyzerMs?.p50Ms ?: 0.0))
                        put("steadyTotalAnalyzerP95Ms", String.format(Locale.US, "%.2f", gpuSnap.totalAnalyzerMs?.p95Ms ?: 0.0))
                        put("steadyPublicationP50Ms", String.format(Locale.US, "%.2f", gpuSnap.resultPublicationMs?.p50Ms ?: 0.0))
                        put("steadyPublicationP95Ms", String.format(Locale.US, "%.2f", gpuSnap.resultPublicationMs?.p95Ms ?: 0.0))
                        put("steadyCameraToResultAgeP50Ms", String.format(Locale.US, "%.2f", gpuSnap.cameraToResultAgeMs?.p50Ms ?: 0.0))
                        put("steadyCameraToResultAgeP95Ms", String.format(Locale.US, "%.2f", gpuSnap.cameraToResultAgeMs?.p95Ms ?: 0.0))
                    }
                )
                put(
                    "previewOnlyMode",
                    JSONObject().apply {
                        put("warmupElapsedMs", String.format(Locale.US, "%.1f", previewOnlySnap.warmupElapsedMs))
                        put("steadyElapsedMs", String.format(Locale.US, "%.1f", previewOnlySnap.steadyElapsedMs))
                        put("sensorWarmupFrames", previewOnlySnap.sensorWarmupFrames)
                        put("sensorSteadyFrames", previewOnlySnap.sensorSteadyFrames)
                        put("steadySensorFps", String.format(Locale.US, "%.2f", previewOnlySnap.steadySensorFps))
                        put("sensorCaptureCadenceP50Ms", String.format(Locale.US, "%.2f", previewOnlySnap.sensorCaptureCadenceMs?.p50Ms ?: 0.0))
                        put("sensorCaptureCadenceP95Ms", String.format(Locale.US, "%.2f", previewOnlySnap.sensorCaptureCadenceMs?.p95Ms ?: 0.0))
                    }
                )
            }

            val reportFileName = if (candidateFile != null) {
                getCandidateReportFileName("candidate-gpu-model.json")
            } else {
                "optimized-gpu-model.json"
            }
            val reportFile = File(context.filesDir, reportFileName)
            reportFile.writeText(jsonReport.toString(2), StandardCharsets.UTF_8)
            runCatching {
                val sdDir = File("/sdcard/Download/final_reports").apply { mkdirs() }
                File(sdDir, reportFileName).writeText(jsonReport.toString(2), StandardCharsets.UTF_8)
            }
            Log.i("AriaVisionBenchmark", "${if (candidateArg != null) "CANDIDATE_GPU_JSON" else "OPTIMIZED_GPU_JSON"}=${jsonReport}")

            assertTrue(gpuSnap.warmupCompleted)
            assertTrue("Expected steadyElapsedMs >= 20000.0, was ${gpuSnap.steadyElapsedMs}", gpuSnap.steadyElapsedMs >= 20_000.0)
            assertTrue(gpuSnap.warmupFrames > 0)
            assertTrue("Expected steadyFrames >= 50, was ${gpuSnap.steadyFrames}", gpuSnap.steadyFrames >= 50)
            assertNotNull(gpuSnap.preprocessMs)
            assertNotNull(gpuSnap.modelInvokeMs)
            assertNotNull(gpuSnap.postprocessMs)
            assertNotNull(gpuSnap.totalAnalyzerMs)
            assertTrue("Expected modelInvokeMs.p50Ms > 0", (gpuSnap.modelInvokeMs?.p50Ms ?: 0.0) > 0.0)
            assertTrue("Expected GPU invoke < 150ms on Pixel 3", (gpuSnap.modelInvokeMs?.p50Ms ?: 0.0) < 150.0)
        } finally {
            // Restore default model if we imported a candidate
            if (candidateArg != null) {
                runCatching {
                    store.clearModelSync()
                    store.provisionDefaultAssetModelSync()
                }
            }
            runCatching { context.unbindService(serviceConn) }
            runCatching {
                context.startService(Intent(context, VoiceService::class.java).setAction(VoiceService.ACTION_STOP))
            }
            controller.close()
        }
    }
}
