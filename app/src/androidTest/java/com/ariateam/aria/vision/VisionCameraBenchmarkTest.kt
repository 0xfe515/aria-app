package com.ariateam.aria.vision

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class VisionCameraBenchmarkTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun grantCameraPermission() {
        val inst = InstrumentationRegistry.getInstrumentation()
        runCatching {
            inst.uiAutomation.grantRuntimePermission(
                inst.targetContext.packageName,
                Manifest.permission.CAMERA,
            )
        }
    }

    @Test
    fun visionViewModelAndModelStore_lazyInitAndRejectInvalidImportsCleanly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as Application

        // Verify VisionViewModel does NOT initialize controller/buffers/store until ensureController() is called.
        val viewModel = VisionViewModel(app)
        assertFalse("VisionViewModel must remain uninitialized before entering debug/demo", viewModel.isInitialized)
        viewModel.stopCamera()
        assertFalse("stopCamera on uninitialized VisionViewModel must not initialize it", viewModel.isInitialized)

        val store = VisionModelStore(context, restoreOnInit = false)
        try {
            store.clearModelSync()
            assertEquals(VisionModelState.Missing, store.state.value)

            // 1. Empty stream rejected.
            val emptyState = store.importFromStreamSync(
                input = ByteArrayInputStream(ByteArray(0)),
                displayName = "empty.tflite",
            )
            assertTrue(emptyState is VisionModelState.Error)
            assertFalse(store.segmenter.isReady)

            // 2. Corrupted / non-TFLite bytes rejected without persisting invalid slot.
            val badBytes = "not-a-valid-tflite-flatbuffer-payload".toByteArray(Charsets.UTF_8)
            val rejectedState = store.importFromStreamSync(
                input = ByteArrayInputStream(badBytes),
                displayName = "corrupt.tflite",
            )
            assertTrue(rejectedState is VisionModelState.Error)
            assertFalse(store.segmenter.isReady)
            assertFalse(store.activeModelFile.exists())

            // 3. Fresh store after rejected import starts in Missing state.
            val freshStore = VisionModelStore(context, restoreOnInit = false)
            try {
                val restored = freshStore.restorePersistedModelSync()
                assertEquals(VisionModelState.Missing, restored)
            } finally {
                freshStore.close()
            }
        } finally {
            store.clearModelSync()
            store.close()
            viewModel.close()
        }
    }

    @Test
    fun cameraOnlyFiniteBenchmark_measuresWarmupAnd20SecondSteadyWindowAndPreviewOnlyComparison() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
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

        val store = VisionModelStore(context, restoreOnInit = false)
        store.clearModelSync()
        assertEquals(VisionModelState.Missing, store.state.value)
        val controller = VisionPipelineController(context, modelStore = store)
        try {
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

            // Wait up to 5 seconds for first analyzed frame, then wait 5s warmup + 21s steady window.
            val startWaitMs = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - startWaitMs < 5_000L) {
                if (controller.snapshotNow().warmupFrames > 0) break
                Thread.sleep(100L)
            }
            assertTrue(
                "CameraX analyzer did not deliver initial frames within 5s (status=${controller.uiState.value.cameraStatus}, detail=${controller.uiState.value.cameraErrorDetail})",
                controller.snapshotNow().warmupFrames > 0,
            )

            // Wait until steadyElapsedMs >= 21_000 ms (5s warmup + 21s steady measurement).
            val benchmarkDeadlineMs = SystemClock.elapsedRealtime() + 30_000L
            while (SystemClock.elapsedRealtime() < benchmarkDeadlineMs) {
                val snap = controller.snapshotNow()
                if (snap.warmupCompleted && snap.steadyElapsedMs >= 21_000.0) {
                    break
                }
                Thread.sleep(200L)
            }

            val preprocessSnap = controller.snapshotNow()
            assertTrue(
                "Expected LifecycleOwner to remain RESUMED after preprocess benchmark, was ${owner.lifecycle.currentState}",
                owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            )
            composeRule.runOnUiThread {
                controller.stopCamera()
            }
            Thread.sleep(400L)

            // Now run PREVIEW_ONLY comparison mode (5s warmup + 10s steady window).
            composeRule.runOnUiThread {
                controller.startCamera(
                    lifecycleOwner = owner,
                    surfaceProvider = surfaceProvider,
                    mode = VisionPipelineMode.PREVIEW_ONLY,
                    targetRotation = displayRotation,
                )
            }
            val previewStartWaitMs = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - previewStartWaitMs < 5_000L) {
                if (controller.snapshotNow().sensorWarmupFrames > 0) break
                Thread.sleep(100L)
            }
            assertTrue(
                "Preview-only session did not deliver initial sensor capture callbacks within 5s",
                controller.snapshotNow().sensorWarmupFrames > 0,
            )

            val previewDeadlineMs = SystemClock.elapsedRealtime() + 18_000L
            while (SystemClock.elapsedRealtime() < previewDeadlineMs) {
                val snap = controller.snapshotNow()
                if (snap.warmupCompleted && snap.steadyElapsedMs >= 10_000.0) {
                    break
                }
                Thread.sleep(200L)
            }
            val previewOnlySnap = controller.snapshotNow()
            assertTrue(
                "Expected LifecycleOwner to remain RESUMED after preview-only benchmark, was ${owner.lifecycle.currentState}",
                owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            )
            composeRule.runOnUiThread {
                controller.stopCamera()
            }

            val jsonReport = JSONObject().apply {
                put("deviceModel", android.os.Build.MODEL)
                put("sdkInt", android.os.Build.VERSION.SDK_INT)
                put(
                    "preprocessMode",
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
                        put("steadyProcessedFps", String.format(Locale.US, "%.2f", preprocessSnap.steadyProcessedFps))
                        put("steadySensorFps", String.format(Locale.US, "%.2f", preprocessSnap.steadySensorFps))
                        put("warmupDeliveryCadenceP50Ms", String.format(Locale.US, "%.2f", preprocessSnap.warmupDeliveryCadenceMs?.p50Ms ?: 0.0))
                        put("warmupDeliveryCadenceP95Ms", String.format(Locale.US, "%.2f", preprocessSnap.warmupDeliveryCadenceMs?.p95Ms ?: 0.0))
                        put("steadyDeliveryCadenceP50Ms", String.format(Locale.US, "%.2f", preprocessSnap.cameraDeliveryCadenceMs?.p50Ms ?: 0.0))
                        put("steadyDeliveryCadenceP95Ms", String.format(Locale.US, "%.2f", preprocessSnap.cameraDeliveryCadenceMs?.p95Ms ?: 0.0))
                        put("warmupPreprocessP50Ms", String.format(Locale.US, "%.2f", preprocessSnap.warmupPreprocessMs?.p50Ms ?: 0.0))
                        put("warmupPreprocessP95Ms", String.format(Locale.US, "%.2f", preprocessSnap.warmupPreprocessMs?.p95Ms ?: 0.0))
                        put("steadyPreprocessP50Ms", String.format(Locale.US, "%.2f", preprocessSnap.preprocessMs?.p50Ms ?: 0.0))
                        put("steadyPreprocessP95Ms", String.format(Locale.US, "%.2f", preprocessSnap.preprocessMs?.p95Ms ?: 0.0))
                        put("steadyPreprocessSampleCount", preprocessSnap.preprocessMs?.sampleCount ?: 0)
                        put("steadyPreprocessCumulativeCount", preprocessSnap.preprocessMs?.cumulativeCount ?: 0)
                        put("modelInvokeLabel", preprocessSnap.formatModelInvokeLabel())
                        put("postprocessLabel", preprocessSnap.formatPostprocessLabel())
                        put("warmupTotalAnalyzerP50Ms", String.format(Locale.US, "%.2f", preprocessSnap.warmupTotalAnalyzerMs?.p50Ms ?: 0.0))
                        put("warmupTotalAnalyzerP95Ms", String.format(Locale.US, "%.2f", preprocessSnap.warmupTotalAnalyzerMs?.p95Ms ?: 0.0))
                        put("steadyTotalAnalyzerP50Ms", String.format(Locale.US, "%.2f", preprocessSnap.totalAnalyzerMs?.p50Ms ?: 0.0))
                        put("steadyTotalAnalyzerP95Ms", String.format(Locale.US, "%.2f", preprocessSnap.totalAnalyzerMs?.p95Ms ?: 0.0))
                        put("steadyPublicationP50Ms", String.format(Locale.US, "%.2f", preprocessSnap.resultPublicationMs?.p50Ms ?: 0.0))
                        put("steadyPublicationP95Ms", String.format(Locale.US, "%.2f", preprocessSnap.resultPublicationMs?.p95Ms ?: 0.0))
                        put("steadyPublicationCumulativeCount", preprocessSnap.resultPublicationMs?.cumulativeCount ?: 0)
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
                        put("steadyFrames", previewOnlySnap.steadyFrames)
                        put("steadySensorFps", String.format(Locale.US, "%.2f", previewOnlySnap.steadySensorFps))
                        put("sensorCaptureCadenceP50Ms", String.format(Locale.US, "%.2f", previewOnlySnap.sensorCaptureCadenceMs?.p50Ms ?: 0.0))
                        put("sensorCaptureCadenceP95Ms", String.format(Locale.US, "%.2f", previewOnlySnap.sensorCaptureCadenceMs?.p95Ms ?: 0.0))
                    }
                )
            }

            val reportFile = File(context.filesDir, "vision_camera_benchmark_last.json")
            reportFile.writeText(jsonReport.toString(2), Charsets.UTF_8)
            Log.i("AriaVisionBenchmark", "BENCHMARK_JSON=${jsonReport}")

            assertTrue(preprocessSnap.warmupCompleted)
            assertTrue("Expected steadyElapsedMs >= 20000, got ${preprocessSnap.steadyElapsedMs}", preprocessSnap.steadyElapsedMs >= 20_000.0)
            assertTrue("Expected warmupFrames > 0", preprocessSnap.warmupFrames > 0)
            assertTrue("Expected steadyFrames >= 100, got ${preprocessSnap.steadyFrames}", preprocessSnap.steadyFrames >= 100)
            assertNotNull(preprocessSnap.preprocessMs)
            assertNotNull(preprocessSnap.totalAnalyzerMs)
            assertTrue("Expected preprocessMs.p50Ms > 0", (preprocessSnap.preprocessMs?.p50Ms ?: 0.0) > 0.0)
            assertTrue(
                "Expected totalAnalyzerMs.p50Ms >= preprocessMs.p50Ms",
                (preprocessSnap.totalAnalyzerMs?.p50Ms ?: 0.0) >= (preprocessSnap.preprocessMs?.p50Ms ?: 0.0),
            )
            assertEquals("N/A (모델 미적용)", preprocessSnap.formatModelInvokeLabel())
            assertEquals("N/A (모델 미적용)", preprocessSnap.formatPostprocessLabel())
            assertNotNull("Expected resultPublicationMs samples", preprocessSnap.resultPublicationMs)

            assertTrue(previewOnlySnap.warmupCompleted)
            assertTrue("Expected previewOnly sensorSteadyFrames >= 50", previewOnlySnap.sensorSteadyFrames >= 50)
            assertEquals(0, previewOnlySnap.steadyFrames)
        } finally {
            controller.close()
        }
    }

    private tailrec fun findActivity(context: Context?): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> findActivity(context.baseContext)
        else -> null
    }
}
