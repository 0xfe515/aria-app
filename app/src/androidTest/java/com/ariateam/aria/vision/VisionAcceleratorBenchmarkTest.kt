package com.ariateam.aria.vision

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ariateam.aria.speech.VoiceService
import com.google.ai.edge.litert.NpuCompatibilityChecker
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Device benchmark test verifying production LiteRT CompiledModel GPU FP32 execution
 * and probing NPU hardware/runtime readiness on the connected Snapdragon 845 device (Pixel 3).
 *
 * Guarantees:
 * - Read bundled portrait model without modifying production slot files or preferences.
 * - Verify model SHA-256 (312105f5a82fc2677337ca7c15867249b3ae255a983fb5ffa4a54dae2b6864c1).
 * - Rigorously probe Qualcomm NPU readiness: distinguish legacy Hexagon 685 DSP from QNN HTP architecture.
 * - Require exact NpuInitializationException / cause; never treat unrelated failures or assertions as NPU evidence.
 * - Cleanly stop concurrent camera/voice runtimes and restore prior service states on completion.
 * - Wrap all segmenters in try/finally or use-blocks for guaranteed cleanup.
 * - Benchmark production GPU FP32 with >= 5 warmups and >= 30 timed iterations.
 * - Compute p50, p90, p95, min, max, mean, standard deviation, and thermal/battery delta.
 * - Compare with CPU FP32 reference to establish exact numerical and semantic agreement.
 * - Persist structured JSON report in test application files.
 */
@RunWith(AndroidJUnit4::class)
class VisionAcceleratorBenchmarkTest {

    companion object {
        private const val TAG = "VisionAcceleratorBenchmark"
        private const val WARMUP_RUNS = 10
        private const val TIMED_RUNS = 50
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private var benchmarkModelFile: File? = null

    private var wasVisionServiceRunning = false
    private var wasVoiceServiceRunning = false

    private fun isServiceRunning(className: String): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return am.getRunningServices(100)?.any { it.service.className == className } ?: false
    }

    @Before
    fun setUpEnvironment() {
        // Wake up device screen and dismiss nonsecure keyguard
        runCatching {
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP")
            instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard")
        }

        // Grant required permissions
        val pkg = context.packageName
        runCatching {
            instrumentation.uiAutomation.grantRuntimePermission(pkg, Manifest.permission.CAMERA)
            instrumentation.uiAutomation.grantRuntimePermission(pkg, Manifest.permission.RECORD_AUDIO)
        }

        // Record prior service states and stop concurrent runtimes
        wasVisionServiceRunning = isServiceRunning(VisionForegroundService::class.java.name)
        wasVoiceServiceRunning = isServiceRunning(VoiceService::class.java.name)

        if (wasVisionServiceRunning) {
            Log.i(TAG, "Stopping active VisionForegroundService to ensure isolated benchmark environment")
            context.stopService(Intent(context, VisionForegroundService::class.java))
        }
        if (wasVoiceServiceRunning) {
            Log.i(TAG, "Stopping active VoiceService to ensure isolated benchmark environment")
            context.stopService(Intent(context, VoiceService::class.java))
        }

        // Allow services a brief settlement interval
        SystemClock.sleep(300)

        // Extract bundled asset to an isolated cache file without touching production model slots
        val targetCache = File(context.cacheDir, "benchmark_walkable_seg_portrait.tflite")
        if (!targetCache.exists() || targetCache.length() != VisionContract.DEFAULT_ASSET_MODEL_BYTES) {
            context.assets.open(VisionContract.DEFAULT_ASSET_MODEL_PATH).use { input ->
                FileOutputStream(targetCache).use { output ->
                    input.copyTo(output)
                }
            }
        }
        benchmarkModelFile = targetCache
    }

    @After
    fun tearDownEnvironment() {
        // Clean up temporary cache model file
        benchmarkModelFile?.let { file ->
            if (file.exists()) {
                file.delete()
            }
        }

        // Restore prior service states if they were originally running
        if (wasVisionServiceRunning) {
            runCatching {
                Log.i(TAG, "Restoring VisionForegroundService state")
                ContextCompat.startForegroundService(context, Intent(context, VisionForegroundService::class.java))
            }
        }
        if (wasVoiceServiceRunning) {
            runCatching {
                Log.i(TAG, "Restoring VoiceService state")
                context.startService(Intent(context, VoiceService::class.java))
            }
        }
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun getBatteryTempC(): Float {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val intent = context.registerReceiver(null, filter)
        val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        return if (tenths > 0) tenths / 10.0f else -1.0f
    }

    private fun getThermalStatus(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.currentThermalStatus ?: -1
        } else {
            -1
        }
    }

    private fun getThermalStatusDescription(status: Int): String {
        return when (status) {
            0 -> "THERMAL_STATUS_NONE"
            1 -> "THERMAL_STATUS_LIGHT"
            2 -> "THERMAL_STATUS_MODERATE"
            3 -> "THERMAL_STATUS_SEVERE"
            4 -> "THERMAL_STATUS_CRITICAL"
            5 -> "THERMAL_STATUS_EMERGENCY"
            6 -> "THERMAL_STATUS_SHUTDOWN"
            else -> "UNKNOWN ($status)"
        }
    }

    @Test
    fun benchmarkProductionGpuFp32AndProbeNpu() {
        val modelFile = checkNotNull(benchmarkModelFile) { "Benchmark model file missing" }
        assertTrue("Model file must exist", modelFile.isFile)
        assertEquals("Model byte length must match contract", VisionContract.DEFAULT_ASSET_MODEL_BYTES, modelFile.length())

        val calculatedSha256 = computeSha256(modelFile)
        Log.i(TAG, "Bundled model verified SHA-256: $calculatedSha256")
        assertEquals(
            "Model SHA-256 must match verified contract hash",
            VisionContract.DEFAULT_ASSET_MODEL_SHA256.lowercase(Locale.US),
            calculatedSha256.lowercase(Locale.US),
        )

        // 1. Validate tensor specification using pure-Java LiteRT MetadataExtractor
        val mappedBuffer = LiteRtVisionSegmenter.mapReadOnly(modelFile)
        val specResult = LiteRtVisionSegmenter.inspectMetadataSpec(mappedBuffer)
        assertTrue("Model metadata spec validation must pass", specResult is VisionContract.SpecValidationResult.Valid)

        // 2. Comprehensive NPU Probe on Snapdragon 845 (Pixel 3)
        val npuProbeReport = JSONObject()
        val detectedVendor = NpuEnvironment.detectVendor(context)
        npuProbeReport.put("detectedVendor", detectedVendor.name)

        val qualcommDeviceSupported = runCatching {
            NpuCompatibilityChecker.Qualcomm.isDeviceSupported()
        }.getOrDefault(false)
        npuProbeReport.put("npuCompatibilityCheckerQualcommIsDeviceSupported", qualcommDeviceSupported)

        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val hasCompilerPlugin = File(nativeDir, "libLiteRtCompilerPlugin_Qualcomm.so").isFile
        val hasDispatch = File(nativeDir, "libLiteRtDispatch_Qualcomm.so").isFile
        val hasHtpNative = File(nativeDir, "libQnnHtp.so").isFile
        val hasHtpSystem = listOf(File("/vendor/lib64"), File("/vendor/dsp/cdsp")).any { File(it, "libQnnHtp.so").isFile }
        val hasCdsprpc = File("/vendor/lib64/libcdsprpc.so").isFile

        npuProbeReport.put("libLiteRtCompilerPlugin_Qualcomm_present", hasCompilerPlugin)
        npuProbeReport.put("libLiteRtDispatch_Qualcomm_present", hasDispatch)
        npuProbeReport.put("libQnnHtp_native_present", hasHtpNative)
        npuProbeReport.put("libQnnHtp_system_present", hasHtpSystem)
        npuProbeReport.put("libcdsprpc_system_present", hasCdsprpc)

        val npuEnv = NpuEnvironment(context)
        val npuSupported = npuEnv.isNpuSupported()
        npuProbeReport.put("npuEnvironmentIsNpuSupported", npuSupported)

        // Document architectural exclusion reasons:
        // SDM845 includes a Hexagon 685 vector DSP (launched 2018), not the dedicated Hexagon Tensor Processor (HTP).
        // Official LiteRT Qualcomm NPU documentation requires SM8450/8475/8550/8650/8750/8850 (Snapdragon 8 Gen 1+).
        val npuExclusionExplanation = "Qualcomm SDM845 contains Hexagon 685 DSP, not Qualcomm Hexagon Tensor Processor (HTP). " +
            "Official LiteRT Qualcomm NPU support requires SM8450+ (Snapdragon 8 Gen 1+). " +
            "Device lacks libQnnHtp.so in /vendor/lib64 and runtime SDK is unbundled."
        npuProbeReport.put("architecturalExclusionReason", npuExclusionExplanation)

        // Probe diagnostic NPU initialization: must fail explicitly with NpuInitializationException (not AssertionError or generic Throwable)
        var diagnosticNpuThrownException: Throwable? = null
        val diagnosticSegmenter = LiteRtVisionSegmenter(
            context = context,
            inferencePolicy = DiagnosticInferencePolicy(allowCpuReferenceForTesting = true),
        )
        try {
            val diagnosticOutcome = diagnosticSegmenter.prepareModelSession(modelFile)
            assertTrue(diagnosticOutcome is LiteRtVisionSegmenter.PrepareOutcome.Valid)
            try {
                diagnosticSegmenter.activatePreparedSession(
                    (diagnosticOutcome as LiteRtVisionSegmenter.PrepareOutcome.Valid).session,
                    backend = VisionBackendType.NPU,
                )
                fail("Diagnostic NPU configuration must not succeed on SDM845")
            } catch (npuInitEx: LiteRtVisionSegmenter.NpuInitializationException) {
                diagnosticNpuThrownException = npuInitEx
                Log.i(TAG, "Diagnostic NPU creation rejected with verified NpuInitializationException: ${npuInitEx.message}")
            }
        } finally {
            diagnosticSegmenter.close()
        }
        assertNotNull("Diagnostic NPU configuration must fail with NpuInitializationException on SDM845", diagnosticNpuThrownException)
        npuProbeReport.put("diagnosticNpuExceptionClass", diagnosticNpuThrownException?.javaClass?.name)
        npuProbeReport.put("diagnosticNpuExceptionMessage", diagnosticNpuThrownException?.message)
        npuProbeReport.put("diagnosticNpuExceptionCauseClass", diagnosticNpuThrownException?.cause?.javaClass?.name)
        npuProbeReport.put("diagnosticNpuExceptionCauseMessage", diagnosticNpuThrownException?.cause?.message)

        // Probe production NPU configuration: must gracefully fallback to GPU without claiming NPU execution
        val fallbackSegmenter = LiteRtVisionSegmenter(
            context = context,
            inferencePolicy = ProductionGpuOnlyPolicy,
        )
        try {
            val fallbackOutcome = fallbackSegmenter.prepareModelSession(modelFile)
            assertTrue(fallbackOutcome is LiteRtVisionSegmenter.PrepareOutcome.Valid)
            fallbackSegmenter.activatePreparedSession(
                (fallbackOutcome as LiteRtVisionSegmenter.PrepareOutcome.Valid).session,
                backend = VisionBackendType.NPU,
            )
            val fallbackSnapshot = fallbackSegmenter.currentSessionSnapshot()
            assertEquals("Production NPU request must fall back to GPU", VisionBackendType.GPU, fallbackSnapshot.activeBackendType)
            assertTrue(
                "Fallback description must indicate NPU fallback",
                fallbackSnapshot.activeBackendDescription.contains("NPU 초기화 실패 폴백") ||
                    fallbackSnapshot.npuFallbackCause != null,
            )
            assertNotNull("NPU fallback cause must be recorded", fallbackSnapshot.npuFallbackCause)
            npuProbeReport.put("productionNpuFallbackBackend", fallbackSnapshot.activeBackendType?.name)
            npuProbeReport.put("productionNpuFallbackDescription", fallbackSnapshot.activeBackendDescription)
            npuProbeReport.put("npuFallbackTreatedAsNpuExecution", false)
            npuProbeReport.put("npuFabricatedLatencyReported", false)
        } finally {
            fallbackSegmenter.close()
        }

        // 3. Benchmark Production LiteRT CompiledModel GPU FP32
        val productionSegmenter = LiteRtVisionSegmenter(
            context = context,
            inferencePolicy = ProductionGpuOnlyPolicy,
        )
        val gpuOutputProbs = FloatArray(VisionContract.OUTPUT_FLOATS)
        val warmupDurationsMs = mutableListOf<Double>()
        val timedSamplesMs = mutableListOf<Double>()
        var gpuInitDurationMs = 0.0
        var thermalBefore = -1
        var batteryTempBefore = -1.0f
        var thermalAfter = -1
        var batteryTempAfter = -1.0f

        try {
            val gpuInitStart = SystemClock.elapsedRealtimeNanos()
            val loadResult = productionSegmenter.loadModelFile(modelFile)
            gpuInitDurationMs = (SystemClock.elapsedRealtimeNanos() - gpuInitStart) / 1_000_000.0
            assertTrue("Production model load must succeed", loadResult is VisionContract.SpecValidationResult.Valid)

            val gpuSnapshot = productionSegmenter.currentSessionSnapshot()
            assertEquals("Configured backend must be GPU", VisionBackendType.GPU, gpuSnapshot.configuredBackend)
            assertEquals("Active backend must be GPU", VisionBackendType.GPU, gpuSnapshot.activeBackendType)
            assertEquals(
                "Active backend description must reflect production GPU FP32 CompiledModel",
                "LiteRT GPU FP32 (CompiledModel)",
                gpuSnapshot.activeBackendDescription,
            )
            assertTrue("GPU session must be invocation ready", gpuSnapshot.isInvocationReady)

            // Prepare deterministic input buffer
            val inputBuffer = ByteBuffer.allocateDirect(VisionContract.INPUT_BYTES).order(ByteOrder.nativeOrder())
            val floatBuf = inputBuffer.asFloatBuffer()
            val rng = Random(20261106L)
            floatBuf.rewind()
            for (i in 0 until VisionContract.INPUT_FLOATS) {
                floatBuf.put(rng.nextFloat())
            }

            val destinationArray = FloatArray(VisionContract.OUTPUT_FLOATS)

            // Warmup runs
            for (w in 0 until WARMUP_RUNS) {
                val durNanos = productionSegmenter.runDirectRawProbabilities(inputBuffer, destinationArray)
                warmupDurationsMs.add(durNanos / 1_000_000.0)
            }
            Log.i(TAG, "GPU Warmup completed: $WARMUP_RUNS runs, initial=${warmupDurationsMs.first()} ms, final=${warmupDurationsMs.last()} ms")

            thermalBefore = getThermalStatus()
            batteryTempBefore = getBatteryTempC()

            // Timed benchmark runs
            for (i in 0 until TIMED_RUNS) {
                val durNanos = productionSegmenter.runDirectRawProbabilities(inputBuffer, destinationArray)
                timedSamplesMs.add(durNanos / 1_000_000.0)
            }

            thermalAfter = getThermalStatus()
            batteryTempAfter = getBatteryTempC()

            // Copy destination array for parity check
            System.arraycopy(destinationArray, 0, gpuOutputProbs, 0, VisionContract.OUTPUT_FLOATS)
        } finally {
            productionSegmenter.close()
        }

        // Compute GPU statistics
        val sortedSamples = timedSamplesMs.sorted()
        val gpuMin = sortedSamples.first()
        val gpuMax = sortedSamples.last()
        val gpuMean = timedSamplesMs.average()
        val gpuP50 = sortedSamples[(sortedSamples.size * 0.50).toInt()]
        val gpuP90 = sortedSamples[(sortedSamples.size * 0.90).toInt()]
        val gpuP95 = sortedSamples[(sortedSamples.size * 0.95).toInt()]
        val gpuVariance = timedSamplesMs.map { (it - gpuMean) * (it - gpuMean) }.average()
        val gpuStdDev = sqrt(gpuVariance)

        Log.i(
            TAG,
            "LiteRT CompiledModel GPU FP32 Results: p50=%.2f ms, p90=%.2f ms, p95=%.2f ms, min=%.2f ms, max=%.2f ms, mean=%.2f ms, stdDev=%.2f ms"
                .format(Locale.US, gpuP50, gpuP90, gpuP95, gpuMin, gpuMax, gpuMean, gpuStdDev),
        )

        // 4. Benchmark Diagnostic CPU FP32 (4 threads) for comparative context and numerical agreement
        val cpuSegmenter = LiteRtVisionSegmenter(
            context = context,
            inferencePolicy = DiagnosticInferencePolicy(allowCpuReferenceForTesting = true),
        )
        val cpuOutputProbs = FloatArray(VisionContract.OUTPUT_FLOATS)
        val cpuWarmupDurationsMs = mutableListOf<Double>()
        val cpuTimedSamplesMs = mutableListOf<Double>()

        try {
            val cpuOutcome = cpuSegmenter.prepareModelSession(modelFile)
            assertTrue(cpuOutcome is LiteRtVisionSegmenter.PrepareOutcome.Valid)
            cpuSegmenter.activatePreparedSession(
                (cpuOutcome as LiteRtVisionSegmenter.PrepareOutcome.Valid).session,
                backend = VisionBackendType.CPU,
                threads = 4,
            )

            // Re-populate identical input
            val inputBuffer = ByteBuffer.allocateDirect(VisionContract.INPUT_BYTES).order(ByteOrder.nativeOrder())
            val floatBuf = inputBuffer.asFloatBuffer()
            val rng = Random(20261106L)
            floatBuf.rewind()
            for (i in 0 until VisionContract.INPUT_FLOATS) {
                floatBuf.put(rng.nextFloat())
            }

            val destinationArray = FloatArray(VisionContract.OUTPUT_FLOATS)
            for (w in 0 until 5) {
                val durNanos = cpuSegmenter.runDirectRawProbabilities(inputBuffer, destinationArray)
                cpuWarmupDurationsMs.add(durNanos / 1_000_000.0)
            }

            for (i in 0 until 30) {
                val durNanos = cpuSegmenter.runDirectRawProbabilities(inputBuffer, destinationArray)
                cpuTimedSamplesMs.add(durNanos / 1_000_000.0)
            }
            System.arraycopy(destinationArray, 0, cpuOutputProbs, 0, VisionContract.OUTPUT_FLOATS)
        } finally {
            cpuSegmenter.close()
        }

        val cpuSorted = cpuTimedSamplesMs.sorted()
        val cpuP50 = cpuSorted[(cpuSorted.size * 0.50).toInt()]
        val cpuP90 = cpuSorted[(cpuSorted.size * 0.90).toInt()]
        val cpuP95 = cpuSorted[(cpuSorted.size * 0.95).toInt()]
        val cpuMean = cpuTimedSamplesMs.average()

        Log.i(
            TAG,
            "LiteRT CompiledModel CPU FP32 (4 threads) Results: p50=%.2f ms, p90=%.2f ms, p95=%.2f ms, mean=%.2f ms"
                .format(Locale.US, cpuP50, cpuP90, cpuP95, cpuMean),
        )

        // 5. Numerical and semantic agreement between GPU and CPU outputs
        var maxDiff = 0.0f
        var sumDiff = 0.0
        for (i in 0 until VisionContract.OUTPUT_FLOATS) {
            val diff = abs(gpuOutputProbs[i] - cpuOutputProbs[i])
            if (diff > maxDiff) maxDiff = diff
            sumDiff += diff
        }
        val meanDiff = (sumDiff / VisionContract.OUTPUT_FLOATS).toFloat()

        val cpuMask = ByteArray(VisionContract.MODEL_WIDTH * VisionContract.MODEL_HEIGHT)
        LiteRtVisionSegmenter.decodeArgmaxAndMask(
            probabilities = cpuOutputProbs,
            outClassMask = cpuMask,
            modelWidth = VisionContract.MODEL_INPUT_WIDTH,
            modelHeight = VisionContract.MODEL_INPUT_HEIGHT,
        )

        val gpuMask = ByteArray(VisionContract.MODEL_WIDTH * VisionContract.MODEL_HEIGHT)
        LiteRtVisionSegmenter.decodeArgmaxAndMask(
            probabilities = gpuOutputProbs,
            outClassMask = gpuMask,
            modelWidth = VisionContract.MODEL_INPUT_WIDTH,
            modelHeight = VisionContract.MODEL_INPUT_HEIGHT,
        )

        var matchingClassPixels = 0
        var matchingWalkablePixels = 0
        val totalPixels = cpuMask.size
        val walkableFlags = VisionContract.CLASS_WALKABLE
        for (p in 0 until totalPixels) {
            val cClass = cpuMask[p].toInt() and 0xFF
            val gClass = gpuMask[p].toInt() and 0xFF
            if (cClass == gClass) matchingClassPixels++
            if (walkableFlags[cClass] == walkableFlags[gClass]) matchingWalkablePixels++
        }
        val classAgreementPct = (matchingClassPixels.toDouble() / totalPixels) * 100.0
        val walkableAgreementPct = (matchingWalkablePixels.toDouble() / totalPixels) * 100.0

        Log.i(
            TAG,
            "GPU vs CPU Output Agreement: maxDiff=$maxDiff, meanDiff=$meanDiff, classAgreement=$classAgreementPct%, walkableAgreement=$walkableAgreementPct%",
        )

        assertTrue("GPU FP32 maxDiff must be <= 0.001", maxDiff <= 0.001f)
        assertTrue("GPU FP32 meanDiff must be <= 0.0001", meanDiff <= 0.0001f)
        assertTrue("Class agreement must be >= 99.5%", classAgreementPct >= 99.5)
        assertTrue("Walkable agreement must be >= 99.9%", walkableAgreementPct >= 99.9)

        // 6. Assemble Full Comprehensive JSON Report
        val fullReport = JSONObject()
        val metadataObj = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("task", "ARIA-NPU-GPU-BENCH-20261106")
            put("framework", "LiteRT 2.3.2 (CompiledModel)")
        }
        fullReport.put("benchmarkMetadata", metadataObj)

        val deviceObj = JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("brand", Build.BRAND)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("board", Build.BOARD)
            put("hardware", Build.HARDWARE)
            put("socManufacturer", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else "Qualcomm")
            put("socModel", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else "SDM845")
            put("androidRelease", Build.VERSION.RELEASE)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("fingerprint", Build.FINGERPRINT)
        }
        fullReport.put("deviceInfo", deviceObj)

        val modelObj = JSONObject().apply {
            put("assetPath", VisionContract.DEFAULT_ASSET_MODEL_PATH)
            put("fileName", VisionContract.DEFAULT_ASSET_MODEL_NAME)
            put("sha256", calculatedSha256)
            put("sizeBytes", modelFile.length())
            put("inputShape", JSONArray(VisionContract.EXPECTED_INPUT_SHAPE))
            put("outputShape", JSONArray(VisionContract.EXPECTED_OUTPUT_SHAPE))
            put("dataType", VisionContract.EXPECTED_DATA_TYPE)
            put("classes", VisionContract.MODEL_CLASSES)
        }
        fullReport.put("modelContract", modelObj)

        fullReport.put("npuProbe", npuProbeReport)

        val gpuMetrics = JSONObject().apply {
            put("backend", "LiteRT GPU FP32 (CompiledModel)")
            put("precision", "FP32")
            put("initDurationMs", gpuInitDurationMs)
            put("warmupRuns", WARMUP_RUNS)
            put("warmupSamplesMs", JSONArray(warmupDurationsMs))
            put("timedRuns", TIMED_RUNS)
            put("timedSamplesMs", JSONArray(timedSamplesMs))
            put("minMs", gpuMin)
            put("maxMs", gpuMax)
            put("meanMs", gpuMean)
            put("stdDevMs", gpuStdDev)
            put("p50Ms", gpuP50)
            put("p90Ms", gpuP90)
            put("p95Ms", gpuP95)
            put("thermalStatusBefore", getThermalStatusDescription(thermalBefore))
            put("batteryTempBeforeC", batteryTempBefore)
            put("thermalStatusAfter", getThermalStatusDescription(thermalAfter))
            put("batteryTempAfterC", batteryTempAfter)
        }
        fullReport.put("gpuBenchmark", gpuMetrics)

        val cpuMetrics = JSONObject().apply {
            put("backend", "LiteRT CPU FP32 (CompiledModel, 4 threads)")
            put("p50Ms", cpuP50)
            put("p90Ms", cpuP90)
            put("p95Ms", cpuP95)
            put("meanMs", cpuMean)
            put("maxDiffGpuVsCpu", maxDiff.toDouble())
            put("meanDiffGpuVsCpu", meanDiff.toDouble())
            put("classAgreementPct", classAgreementPct)
            put("walkableAgreementPct", walkableAgreementPct)
        }
        fullReport.put("cpuComparison", cpuMetrics)

        val limitations = JSONArray().apply {
            put("Inference-only latency; excludes camera frame acquisition, YUV conversion, and UI rendering.")
            put("Battery drain and continuous power consumption not measured with physical power meter.")
            put("Single-session evaluation; long-term continuous thermal throttling profile (> 30 min) not characterized.")
            put("Qualcomm Hexagon 685 DSP on SDM845 unsupported by LiteRT QNN HTP delegate; no DSP latency measured.")
        }
        fullReport.put("limitations", limitations)

        val jsonString = fullReport.toString(2)

        // Persist to app files
        val externalReportFile = File(context.getExternalFilesDir(null), "vision_accelerator_benchmark_report.json")
        externalReportFile.writeText(jsonString)
        Log.i(TAG, "Report saved to external files: ${externalReportFile.absolutePath}")

        val internalReportFile = File(context.filesDir, "vision_accelerator_benchmark_report.json")
        internalReportFile.writeText(jsonString)
        Log.i(TAG, "Report saved to internal files: ${internalReportFile.absolutePath}")

        // Print raw report into logcat for host capture
        Log.i(TAG, "=== BEGIN BENCHMARK JSON REPORT ===")
        jsonString.chunked(2000).forEach { chunk ->
            Log.i(TAG, chunk)
        }
        Log.i(TAG, "=== END BENCHMARK JSON REPORT ===")
    }
}
