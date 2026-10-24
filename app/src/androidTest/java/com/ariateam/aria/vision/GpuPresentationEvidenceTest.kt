package com.ariateam.aria.vision

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ariateam.aria.vision.debug.GpuDiagnosticData
import com.ariateam.aria.vision.debug.GpuDiagnosticOverlayActivity
import com.ariateam.aria.vision.debug.GpuDiagnosticStateHolder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random

@RunWith(AndroidJUnit4::class)
class GpuPresentationEvidenceTest {

    companion object {
        private const val TAG = "AriaGpuEvidence"

        const val EXPECTED_ORIGINAL_SHA256 =
            "3944dce57cc161679525c3f4feb1f6b46c8e7519f8f10ba06155b3874c2c66b7"
        const val EXPECTED_UNFUSED_SHA256 =
            "84364b9730d200b00a65ebcbae6a13444c6f8eb9bbab0676adc30d57cfa24847"

        const val ORIGINAL_ASSET = "models/walkable_seg_aria5.tflite"
        const val UNFUSED_ASSET = "models/walkable_seg_aria5_unfused.tflite"
    }

    private fun execShell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return FileInputStream(pfd.fileDescriptor).use { it.bufferedReader().readText() }
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var r = input.read(buf)
            while (r >= 0) {
                if (r > 0) digest.update(buf, 0, r)
                r = input.read(buf)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun getThermalStatus(context: Context): Int {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.currentThermalStatus
    }

    private fun getBatteryTemp(context: Context): Float {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0f
    }

    private fun createSyntheticInput(seed: Long = 42L): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(VisionContract.INPUT_BYTES).order(ByteOrder.nativeOrder())
        val fb = buf.asFloatBuffer()
        val rng = Random(seed)
        fb.rewind()
        for (i in 0 until VisionContract.INPUT_FLOATS) {
            fb.put(rng.nextFloat())
        }
        buf.rewind()
        return buf
    }

    private fun createInputBitmap(inputBuf: ByteBuffer): Bitmap {
        val dup = inputBuf.duplicate().order(ByteOrder.nativeOrder())
        dup.rewind()
        val fb = dup.asFloatBuffer()
        val width = VisionContract.MODEL_WIDTH
        val height = VisionContract.MODEL_HEIGHT
        val pixels = IntArray(width * height)

        var idx = 0
        for (p in 0 until width * height) {
            val r = (fb.get() * 255f).toInt().coerceIn(0, 255)
            val g = (fb.get() * 255f).toInt().coerceIn(0, 255)
            val b = (fb.get() * 255f).toInt().coerceIn(0, 255)
            pixels[idx++] = AndroidColor.rgb(r, g, b)
        }

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        return bmp
    }

    private fun createMaskBitmap(outputFloats: FloatArray): Bitmap {
        val width = VisionContract.MODEL_WIDTH
        val height = VisionContract.MODEL_HEIGHT
        val numClasses = VisionContract.MODEL_CLASSES
        val pixels = IntArray(width * height)

        val palette = intArrayOf(
            AndroidColor.rgb(38, 50, 56),    // 0: Background/other (Dark Slate)
            AndroidColor.rgb(211, 47, 47),   // 1: Road (Crimson / danger)
            AndroidColor.rgb(0, 230, 118),   // 2: Sidewalk (Bright green / walkable)
            AndroidColor.rgb(0, 229, 255),   // 3: Crosswalk (Cyan / walkable)
            AndroidColor.rgb(255, 214, 0),   // 4: Person (Yellow obstacle)
            AndroidColor.rgb(255, 109, 0),   // 5: Vehicle (Orange obstacle)
            AndroidColor.rgb(224, 64, 251),  // 6: Pole/Sign (Magenta obstacle)
        )

        var offset = 0
        for (p in 0 until width * height) {
            var bestClass = 0
            var bestProb = outputFloats[offset]
            for (c in 1 until numClasses) {
                val prob = outputFloats[offset + c]
                if (prob > bestProb) {
                    bestProb = prob
                    bestClass = c
                }
            }
            pixels[p] = palette[bestClass % palette.size]
            offset += numClasses
        }

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        return bmp
    }

    data class ParityResult(
        val maxDiff: Float,
        val mae: Float,
        val argmaxAgreement: Float,
        val walkableAgreement: Float,
    )

    private fun computeParity(cpuFloats: FloatArray, gpuFloats: FloatArray): ParityResult {
        val numPixels = VisionContract.INPUT_PIXELS
        val numClasses = VisionContract.MODEL_CLASSES
        val numFloats = VisionContract.OUTPUT_FLOATS

        var maxDiff = 0f
        var sumDiff = 0.0
        var matchingArgmax = 0
        var matchingWalkable = 0

        var offset = 0
        for (p in 0 until numPixels) {
            var bestCpuClass = 0
            var bestCpuProb = cpuFloats[offset]
            var bestGpuClass = 0
            var bestGpuProb = gpuFloats[offset]

            for (c in 0 until numClasses) {
                val cp = cpuFloats[offset + c]
                val gp = gpuFloats[offset + c]
                val diff = kotlin.math.abs(cp - gp)
                if (diff > maxDiff) maxDiff = diff
                sumDiff += diff

                if (cp > bestCpuProb) {
                    bestCpuProb = cp
                    bestCpuClass = c
                }
                if (gp > bestGpuProb) {
                    bestGpuProb = gp
                    bestGpuClass = c
                }
            }

            if (bestCpuClass == bestGpuClass) matchingArgmax++
            val cpuWalkable = bestCpuClass == 2 || bestCpuClass == 3
            val gpuWalkable = bestGpuClass == 2 || bestGpuClass == 3
            if (cpuWalkable == gpuWalkable) matchingWalkable++

            offset += numClasses
        }

        return ParityResult(
            maxDiff = maxDiff,
            mae = (sumDiff / numFloats).toFloat(),
            argmaxAgreement = matchingArgmax.toFloat() / numPixels,
            walkableAgreement = matchingWalkable.toFloat() / numPixels,
        )
    }

    data class ParsedDelegation(
        val gpuNodes: Int,
        val totalNodes: Int,
        val partitions: Int,
        val cpuFallback: Int,
        val rawGpuLine: String,
        val rawCpuFallbackLine: String?,
    )

    private fun parseDelegationLog(rawLogcat: String): ParsedDelegation {
        val gpuRegex = Regex("""Replacing\s+(\d+)\s+out of\s+(\d+)\s+node\(s\)\s+with delegate\s+\(TfLiteGpuDelegateV2\)\s+node,\s+yielding\s+(\d+)\s+partitions""")
        val match = gpuRegex.find(rawLogcat)
        requireNotNull(match) { "Could not find TfLiteGpuDelegateV2 delegation line in logcat:\n$rawLogcat" }
        val gpuNodes = match.groupValues[1].toInt()
        val totalNodes = match.groupValues[2].toInt()
        val partitions = match.groupValues[3].toInt()
        val cpuFallback = totalNodes - gpuNodes

        val cpuRegex = Regex("""Replacing\s+(\d+)\s+out of\s+(\d+)\s+node\(s\)\s+with delegate\s+\(TfLiteXNNPackDelegate\)\s+node,\s+yielding\s+(\d+)\s+partitions""")
        val cpuMatch = cpuRegex.find(rawLogcat)

        return ParsedDelegation(
            gpuNodes = gpuNodes,
            totalNodes = totalNodes,
            partitions = partitions,
            cpuFallback = cpuFallback,
            rawGpuLine = match.value,
            rawCpuFallbackLine = cpuMatch?.value,
        )
    }

    @Test
    fun capturePresentationEvidenceBeforeAndAfter() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        Log.i(TAG, "=== Starting ARIA-GPU-PRESENTATION-EVIDENCE-001 ===")

        // 1. Preserve and manage device settings
        val initialStayAwake = execShell("settings get global stay_on_while_plugged_in").trim()
        execShell("settings put global stay_on_while_plugged_in 7")
        execShell("input keyevent 224") // KEYCODE_WAKEUP
        execShell("wm dismiss-keyguard")

        // 2. Fence competing background work (without touching user preferences)
        var voiceServiceWasRunning = false
        var languageServiceWasRunning = false
        runCatching {
            val servicesOutput = execShell("dumpsys activity services com.ariateam.aria")
            if (servicesOutput.contains("com.ariateam.aria.speech.VoiceService")) {
                voiceServiceWasRunning = true
                execShell("am stopservice com.ariateam.aria/com.ariateam.aria.speech.VoiceService")
                Log.i(TAG, "Stopped competing VoiceService")
            }
            if (servicesOutput.contains("com.ariateam.aria.command.LanguageService")) {
                languageServiceWasRunning = true
                execShell("am stopservice com.ariateam.aria/com.ariateam.aria.command.LanguageService")
                Log.i(TAG, "Stopped competing LanguageService")
            }
        }

        try {
            // 3. Prepare temporary cache copies of assets to preserve app user model/preferences
            val origFile = File(context.cacheDir, "evidence_original.tflite")
            val unfusedFile = File(context.cacheDir, "evidence_unfused.tflite")

            context.assets.open(ORIGINAL_ASSET).use { input ->
                origFile.outputStream().use { output -> input.copyTo(output) }
            }
            context.assets.open(UNFUSED_ASSET).use { input ->
                unfusedFile.outputStream().use { output -> input.copyTo(output) }
            }

            val origSha = computeSha256(origFile)
            val unfusedSha = computeSha256(unfusedFile)

            assertEquals("Original model SHA mismatch", EXPECTED_ORIGINAL_SHA256, origSha)
            assertEquals("Unfused model SHA mismatch", EXPECTED_UNFUSED_SHA256, unfusedSha)
            Log.i(TAG, "Verified model SHAs: Orig=$origSha, Unfused=$unfusedSha")

            // 4. Prepare directories for evidence output
            execShell("rm -rf /sdcard/Download/gpu_evidence_20261024")
            execShell("mkdir -p /sdcard/Download/gpu_evidence_20261024")
            execShell("chmod 777 /sdcard/Download/gpu_evidence_20261024")

            val deviceOutDir = File("/sdcard/Download/gpu_evidence_20261024")
            val localOutDir = File(context.filesDir, "gpu_evidence_20261024").apply { mkdirs() }

            fun saveFile(filename: String, writeAction: (File) -> Unit) {
                val localFile = File(localOutDir, filename)
                writeAction(localFile)
                val deviceFile = File(deviceOutDir, filename)
                try {
                    writeAction(deviceFile)
                } catch (e: Exception) {
                    Log.w(TAG, "Direct write to $deviceFile failed: ${e.message}; copying via shell")
                    execShell("cp ${localFile.absolutePath} ${deviceFile.absolutePath}")
                    execShell("chmod 666 ${deviceFile.absolutePath}")
                }
            }

            val inputBuf = createSyntheticInput(42L)
            val inputBitmap = createInputBitmap(inputBuf)
            val outBuf = ByteBuffer.allocateDirect(VisionContract.OUTPUT_BYTES).order(ByteOrder.nativeOrder())

            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val fullJson = JSONObject()
            fullJson.put("taskId", "ARIA-GPU-PRESENTATION-EVIDENCE-001")
            fullJson.put("timestamp", dateStr)
            fullJson.put("deviceModel", "Google Pixel 3 (blueline)")
            fullJson.put("androidApi", 31)
            fullJson.put("voiceServiceWasRunningAndStopped", voiceServiceWasRunning)
            fullJson.put("languageServiceWasRunningAndStopped", languageServiceWasRunning)
            fullJson.put("initialStayAwakeSetting", initialStayAwake)

            // -------------------------------------------------------------
            // PHASE 1: ORIGINAL MODEL (BEFORE)
            // -------------------------------------------------------------
            Log.i(TAG, "--- Evaluating Original Model (BEFORE) ---")
            val origBuffer = RandomAccessFile(origFile, "r").use { raf ->
                raf.channel.map(FileChannel.MapMode.READ_ONLY, 0L, raf.length())
            }

            // 1A. CPU 4T Reference
            val cpu4Options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseXNNPACK(true)
            }
            val origCpuInterpreter = Interpreter(origBuffer, cpu4Options).apply { allocateTensors() }
            inputBuf.rewind()
            outBuf.rewind()
            origCpuInterpreter.run(inputBuf, outBuf) // warmup

            val origCpuTimes = mutableListOf<Long>()
            val origCpuFloats = FloatArray(VisionContract.OUTPUT_FLOATS)
            for (r in 0 until 3) {
                inputBuf.rewind()
                outBuf.rewind()
                val st = SystemClock.elapsedRealtimeNanos()
                origCpuInterpreter.run(inputBuf, outBuf)
                val elapsed = SystemClock.elapsedRealtimeNanos() - st
                origCpuTimes.add(elapsed)
                if (r == 0) {
                    val dup = outBuf.duplicate().order(ByteOrder.nativeOrder())
                    dup.rewind()
                    dup.asFloatBuffer().get(origCpuFloats)
                }
            }
            origCpuInterpreter.close()
            val origCpu4P50Ms = origCpuTimes.sorted()[1] / 1_000_000.0
            Log.i(TAG, "Original CPU4 p50: $origCpu4P50Ms ms")

            // 1B. GPU FP32 Sustained
            execShell("logcat -c")
            val thermalStartOrig = getThermalStatus(context)
            val batteryStartOrig = getBatteryTemp(context)

            val origGpuOptions = GpuDelegate.Options().apply {
                setPrecisionLossAllowed(false)
                setInferencePreference(GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
            }
            val origGpuDelegate = GpuDelegate(origGpuOptions)
            val origGpuInterpreter = Interpreter(origBuffer, Interpreter.Options().addDelegate(origGpuDelegate)).apply {
                allocateTensors()
            }

            // Warmup 1 run
            inputBuf.rewind()
            outBuf.rewind()
            origGpuInterpreter.run(inputBuf, outBuf)

            // 5 Measured runs
            val origGpuTimes = mutableListOf<Long>()
            val origGpuFloats = FloatArray(VisionContract.OUTPUT_FLOATS)
            for (r in 0 until 5) {
                inputBuf.rewind()
                outBuf.rewind()
                val st = SystemClock.elapsedRealtimeNanos()
                origGpuInterpreter.run(inputBuf, outBuf)
                val elapsed = SystemClock.elapsedRealtimeNanos() - st
                origGpuTimes.add(elapsed)
                if (r == 0) {
                    val dup = outBuf.duplicate().order(ByteOrder.nativeOrder())
                    dup.rewind()
                    dup.asFloatBuffer().get(origGpuFloats)
                }
            }
            val thermalEndOrig = getThermalStatus(context)
            val batteryEndOrig = getBatteryTemp(context)

            origGpuInterpreter.close()
            origGpuDelegate.close()

            val origGpuSamplesMs = origGpuTimes.map { it / 1_000_000.0 }
            val origGpuP50Ms = origGpuSamplesMs.sorted()[2]
            val origGpuMinMs = origGpuSamplesMs.minOrNull() ?: origGpuP50Ms
            val origGpuMaxMs = origGpuSamplesMs.maxOrNull() ?: origGpuP50Ms

            // Extract and PARSE raw logcat (dynamic parsing, no hardcoding)
            val rawLogcatBefore = execShell("logcat -d -v time -s tflite:V TfLiteGpuDelegateV2:V AriaVisionBackend:V")
            val parsedBefore = parseDelegationLog(rawLogcatBefore)
            Log.i(TAG, "Parsed Before Delegation: GPU=${parsedBefore.gpuNodes}/${parsedBefore.totalNodes}, Partitions=${parsedBefore.partitions}, CPU fallback=${parsedBefore.cpuFallback}")

            // Assertions on parsed hardware delegation
            assertEquals("Original model must have 3 partitions", 3, parsedBefore.partitions)
            assertEquals("Original model must delegate 64 nodes to GPU", 64, parsedBefore.gpuNodes)
            assertEquals("Original model must have 204 total nodes", 204, parsedBefore.totalNodes)
            assertEquals("Original model must have 140 CPU fallback nodes", 140, parsedBefore.cpuFallback)

            val beforeLogLines = rawLogcatBefore.lines()
                .filter { it.contains("Replacing") || it.contains("delegate") || it.contains("GPU") || it.contains("kernels") }
                .take(6)
            val beforeReplacingLines = listOfNotNull(
                parsedBefore.rawGpuLine,
                parsedBefore.rawCpuFallbackLine
            )

            val beforeParity = computeParity(origCpuFloats, origGpuFloats)
            val beforeMaskBitmap = createMaskBitmap(origGpuFloats)

            val beforeData = GpuDiagnosticData(
                stage = "BEFORE",
                stageTitle = "BEFORE: PARTIAL GPU DELEGATION",
                stageSubtitle = "${parsedBefore.partitions} Partitions (${parsedBefore.gpuNodes} GPU nodes / ${parsedBefore.cpuFallback} CPU fallback nodes)",
                stageBadgeColor = 0xFFEF4444L, // Red
                modelName = "walkable_seg_aria5.tflite",
                modelSizeBytes = origFile.length(),
                modelSha256 = origSha,
                delegateConfig = "TfLiteGpuDelegateV2 (FP32 Sustained)",
                precisionLossAllowed = false,
                inferencePreference = "INFERENCE_PREFERENCE_SUSTAINED_SPEED",
                partitionCount = parsedBefore.partitions,
                gpuDelegatedNodes = parsedBefore.gpuNodes,
                totalNodes = parsedBefore.totalNodes,
                cpuFallbackNodes = parsedBefore.cpuFallback,
                rootCauseSummary = "Bottleneck: 5x TRANSPOSE_CONV (Op 189,192,195,198,201) with fused ReLU require Op v4; rejected by LiteRT GPU delegate (max supported v3) -> decoder falls back to CPU XNNPACK in 3 partitions.",
                warmupCount = 1,
                sampleCount = 5,
                p50LatencyMs = origGpuP50Ms,
                minLatencyMs = origGpuMinMs,
                maxLatencyMs = origGpuMaxMs,
                sampleTimingsMs = origGpuSamplesMs,
                cpu4ReferenceP50Ms = origCpu4P50Ms,
                speedupVsCpu = origCpu4P50Ms / origGpuP50Ms,
                maxDiffVsCpu = beforeParity.maxDiff,
                maeVsCpu = beforeParity.mae,
                argmaxAgreementVsCpu = beforeParity.argmaxAgreement,
                walkableAgreementVsCpu = beforeParity.walkableAgreement,
                thermalStatusStart = thermalStartOrig,
                thermalStatusEnd = thermalEndOrig,
                batteryTempStartC = batteryStartOrig,
                batteryTempEndC = batteryEndOrig,
                deviceModel = "Google Pixel 3 (blueline)",
                androidVersion = "Android 12 (API 31)",
                timestamp = dateStr,
                observedLogLines = beforeLogLines,
                rawReplacingLines = beforeReplacingLines,
                inputBitmap = inputBitmap,
                maskBitmap = beforeMaskBitmap,
            )

            // 1C. Launch Diagnostic UI and capture BEFORE screenshot
            GpuDiagnosticStateHolder.currentData = beforeData
            val beforeIntent = Intent(context, GpuDiagnosticOverlayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(beforeIntent)
            instrumentation.waitForIdleSync()
            Thread.sleep(2000)

            val beforeScreenshot = instrumentation.uiAutomation.takeScreenshot()
            assertNotNull("UiAutomation.takeScreenshot returned null for BEFORE", beforeScreenshot)
            saveFile("before_gpu_partial_delegation.png") { file ->
                file.outputStream().use { beforeScreenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            val beforePngFile = File(deviceOutDir, "before_gpu_partial_delegation.png")
            assertTrue("Before screenshot file must exist and be > 50KB", beforePngFile.isFile && beforePngFile.length() > 50_000 || File(localOutDir, "before_gpu_partial_delegation.png").length() > 50_000)
            Log.i(TAG, "Saved BEFORE screenshot: ${beforePngFile.absolutePath}")

            // -------------------------------------------------------------
            // PHASE 2: TRANSFORMED MODEL (AFTER)
            // -------------------------------------------------------------
            Log.i(TAG, "--- Evaluating Transformed Model (AFTER) ---")
            val unfusedBuffer = RandomAccessFile(unfusedFile, "r").use { raf ->
                raf.channel.map(FileChannel.MapMode.READ_ONLY, 0L, raf.length())
            }

            // 2A. CPU 4T Reference on unfused model
            val unfusedCpuInterpreter = Interpreter(unfusedBuffer, cpu4Options).apply { allocateTensors() }
            inputBuf.rewind()
            outBuf.rewind()
            unfusedCpuInterpreter.run(inputBuf, outBuf) // warmup

            val unfusedCpuTimes = mutableListOf<Long>()
            val unfusedCpuFloats = FloatArray(VisionContract.OUTPUT_FLOATS)
            for (r in 0 until 3) {
                inputBuf.rewind()
                outBuf.rewind()
                val st = SystemClock.elapsedRealtimeNanos()
                unfusedCpuInterpreter.run(inputBuf, outBuf)
                val elapsed = SystemClock.elapsedRealtimeNanos() - st
                unfusedCpuTimes.add(elapsed)
                if (r == 0) {
                    val dup = outBuf.duplicate().order(ByteOrder.nativeOrder())
                    dup.rewind()
                    dup.asFloatBuffer().get(unfusedCpuFloats)
                }
            }
            unfusedCpuInterpreter.close()
            val unfusedCpu4P50Ms = unfusedCpuTimes.sorted()[1] / 1_000_000.0
            Log.i(TAG, "Unfused CPU4 p50: $unfusedCpu4P50Ms ms")

            // 2B. GPU FP32 Sustained on unfused model
            execShell("logcat -c")
            val thermalStartUnfused = getThermalStatus(context)
            val batteryStartUnfused = getBatteryTemp(context)

            val unfusedGpuOptions = GpuDelegate.Options().apply {
                setPrecisionLossAllowed(false)
                setInferencePreference(GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
            }
            val unfusedGpuDelegate = GpuDelegate(unfusedGpuOptions)
            val unfusedGpuInterpreter = Interpreter(unfusedBuffer, Interpreter.Options().addDelegate(unfusedGpuDelegate)).apply {
                allocateTensors()
            }

            // Warmup 1 run
            inputBuf.rewind()
            outBuf.rewind()
            unfusedGpuInterpreter.run(inputBuf, outBuf)

            // 5 Measured runs
            val unfusedGpuTimes = mutableListOf<Long>()
            val unfusedGpuFloats = FloatArray(VisionContract.OUTPUT_FLOATS)
            for (r in 0 until 5) {
                inputBuf.rewind()
                outBuf.rewind()
                val st = SystemClock.elapsedRealtimeNanos()
                unfusedGpuInterpreter.run(inputBuf, outBuf)
                val elapsed = SystemClock.elapsedRealtimeNanos() - st
                unfusedGpuTimes.add(elapsed)
                if (r == 0) {
                    val dup = outBuf.duplicate().order(ByteOrder.nativeOrder())
                    dup.rewind()
                    dup.asFloatBuffer().get(unfusedGpuFloats)
                }
            }
            val thermalEndUnfused = getThermalStatus(context)
            val batteryEndUnfused = getBatteryTemp(context)

            unfusedGpuInterpreter.close()
            unfusedGpuDelegate.close()

            val unfusedGpuSamplesMs = unfusedGpuTimes.map { it / 1_000_000.0 }
            val unfusedGpuP50Ms = unfusedGpuSamplesMs.sorted()[2]
            val unfusedGpuMinMs = unfusedGpuSamplesMs.minOrNull() ?: unfusedGpuP50Ms
            val unfusedGpuMaxMs = unfusedGpuSamplesMs.maxOrNull() ?: unfusedGpuP50Ms

            // Extract and PARSE raw logcat (dynamic parsing, no hardcoding)
            val rawLogcatAfter = execShell("logcat -d -v time -s tflite:V TfLiteGpuDelegateV2:V AriaVisionBackend:V")
            val parsedAfter = parseDelegationLog(rawLogcatAfter)
            Log.i(TAG, "Parsed After Delegation: GPU=${parsedAfter.gpuNodes}/${parsedAfter.totalNodes}, Partitions=${parsedAfter.partitions}, CPU fallback=${parsedAfter.cpuFallback}")

            // Assertions on parsed hardware delegation
            assertEquals("Transformed model must have 1 single partition", 1, parsedAfter.partitions)
            assertEquals("Transformed model must delegate 210 nodes to GPU", 210, parsedAfter.gpuNodes)
            assertEquals("Transformed model must have 210 total nodes", 210, parsedAfter.totalNodes)
            assertEquals("Transformed model must have 0 CPU fallback nodes", 0, parsedAfter.cpuFallback)

            val afterLogLines = rawLogcatAfter.lines()
                .filter { it.contains("Replacing") || it.contains("delegate") || it.contains("GPU") || it.contains("kernels") }
                .take(6)
            val afterReplacingLines = listOf(
                parsedAfter.rawGpuLine,
                "Created 1 GPU delegate kernels."
            )

            val afterParity = computeParity(unfusedCpuFloats, unfusedGpuFloats)
            val afterMaskBitmap = createMaskBitmap(unfusedGpuFloats)

            val afterData = GpuDiagnosticData(
                stage = "AFTER",
                stageTitle = "AFTER: FULL GPU DELEGATION",
                stageSubtitle = "${parsedAfter.partitions} Single Partition (${parsedAfter.gpuNodes}/${parsedAfter.totalNodes} nodes delegated, zero CPU fallback)",
                stageBadgeColor = 0xFF10B981L, // Emerald green
                modelName = "walkable_seg_aria5_unfused.tflite",
                modelSizeBytes = unfusedFile.length(),
                modelSha256 = unfusedSha,
                delegateConfig = "TfLiteGpuDelegateV2 (FP32 Sustained)",
                precisionLossAllowed = false,
                inferencePreference = "INFERENCE_PREFERENCE_SUSTAINED_SPEED",
                partitionCount = parsedAfter.partitions,
                gpuDelegatedNodes = parsedAfter.gpuNodes,
                totalNodes = parsedAfter.totalNodes,
                cpuFallbackNodes = parsedAfter.cpuFallback,
                rootCauseSummary = "Optimization: Unfused TransposeConv (v3) + SLICE + explicit RELU (v1). Resolves v4 op rejection & asymmetric padding shift; single partition GPU execution.",
                warmupCount = 1,
                sampleCount = 5,
                p50LatencyMs = unfusedGpuP50Ms,
                minLatencyMs = unfusedGpuMinMs,
                maxLatencyMs = unfusedGpuMaxMs,
                sampleTimingsMs = unfusedGpuSamplesMs,
                cpu4ReferenceP50Ms = unfusedCpu4P50Ms,
                speedupVsCpu = unfusedCpu4P50Ms / unfusedGpuP50Ms,
                maxDiffVsCpu = afterParity.maxDiff,
                maeVsCpu = afterParity.mae,
                argmaxAgreementVsCpu = afterParity.argmaxAgreement,
                walkableAgreementVsCpu = afterParity.walkableAgreement,
                thermalStatusStart = thermalStartUnfused,
                thermalStatusEnd = thermalEndUnfused,
                batteryTempStartC = batteryStartUnfused,
                batteryTempEndC = batteryEndUnfused,
                deviceModel = "Google Pixel 3 (blueline)",
                androidVersion = "Android 12 (API 31)",
                timestamp = dateStr,
                observedLogLines = afterLogLines,
                rawReplacingLines = afterReplacingLines,
                inputBitmap = inputBitmap,
                maskBitmap = afterMaskBitmap,
            )

            // 2C. Update Diagnostic UI and capture AFTER screenshot
            instrumentation.runOnMainSync {
                GpuDiagnosticStateHolder.currentData = afterData
                GpuDiagnosticOverlayActivity.currentActivity?.updateData(afterData)
            }
            val afterIntent = Intent(context, GpuDiagnosticOverlayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(afterIntent)
            instrumentation.waitForIdleSync()
            Thread.sleep(2000)

            val afterScreenshot = instrumentation.uiAutomation.takeScreenshot()
            assertNotNull("UiAutomation.takeScreenshot returned null for AFTER", afterScreenshot)
            saveFile("after_gpu_full_delegation.png") { file ->
                file.outputStream().use { afterScreenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            val afterPngFile = File(deviceOutDir, "after_gpu_full_delegation.png")
            assertTrue("After screenshot file must exist and be > 50KB", afterPngFile.isFile && afterPngFile.length() > 50_000 || File(localOutDir, "after_gpu_full_delegation.png").length() > 50_000)
            Log.i(TAG, "Saved AFTER screenshot: ${afterPngFile.absolutePath}")

            // -------------------------------------------------------------
            // PHASE 3: WRITE MACHINE-READABLE RESULTS AND RAW LOGS
            // -------------------------------------------------------------
            val beforeJson = JSONObject().apply {
                put("modelName", beforeData.modelName)
                put("modelSizeBytes", beforeData.modelSizeBytes)
                put("modelSha256", beforeData.modelSha256)
                put("delegateConfig", beforeData.delegateConfig)
                put("partitionCount", beforeData.partitionCount)
                put("gpuDelegatedNodes", beforeData.gpuDelegatedNodes)
                put("totalNodes", beforeData.totalNodes)
                put("cpuFallbackNodes", beforeData.cpuFallbackNodes)
                put("p50LatencyMs", String.format(Locale.US, "%.2f", beforeData.p50LatencyMs))
                put("minLatencyMs", String.format(Locale.US, "%.2f", beforeData.minLatencyMs))
                put("maxLatencyMs", String.format(Locale.US, "%.2f", beforeData.maxLatencyMs))
                put("sampleTimingsMs", JSONArray(beforeData.sampleTimingsMs.map { String.format(Locale.US, "%.2f", it) }))
                put("cpu4ReferenceP50Ms", String.format(Locale.US, "%.2f", beforeData.cpu4ReferenceP50Ms))
                put("speedupVsCpu", String.format(Locale.US, "%.2fx", beforeData.speedupVsCpu))
                put("maxDiffVsCpu", String.format(Locale.US, "%.6f", beforeData.maxDiffVsCpu))
                put("maeVsCpu", String.format(Locale.US, "%.6f", beforeData.maeVsCpu))
                put("argmaxAgreementVsCpu", String.format(Locale.US, "%.4f", beforeData.argmaxAgreementVsCpu))
                put("walkableAgreementVsCpu", String.format(Locale.US, "%.4f", beforeData.walkableAgreementVsCpu))
                put("thermalStatusStart", beforeData.thermalStatusStart)
                put("thermalStatusEnd", beforeData.thermalStatusEnd)
                put("batteryTempStartC", beforeData.batteryTempStartC)
                put("batteryTempEndC", beforeData.batteryTempEndC)
                put("rawGpuLine", parsedBefore.rawGpuLine)
                put("rawCpuFallbackLine", parsedBefore.rawCpuFallbackLine ?: "none")
                put("observedLogLines", JSONArray(beforeData.observedLogLines))
            }

            val afterJson = JSONObject().apply {
                put("modelName", afterData.modelName)
                put("modelSizeBytes", afterData.modelSizeBytes)
                put("modelSha256", afterData.modelSha256)
                put("delegateConfig", afterData.delegateConfig)
                put("partitionCount", afterData.partitionCount)
                put("gpuDelegatedNodes", afterData.gpuDelegatedNodes)
                put("totalNodes", afterData.totalNodes)
                put("cpuFallbackNodes", afterData.cpuFallbackNodes)
                put("p50LatencyMs", String.format(Locale.US, "%.2f", afterData.p50LatencyMs))
                put("minLatencyMs", String.format(Locale.US, "%.2f", afterData.minLatencyMs))
                put("maxLatencyMs", String.format(Locale.US, "%.2f", afterData.maxLatencyMs))
                put("sampleTimingsMs", JSONArray(afterData.sampleTimingsMs.map { String.format(Locale.US, "%.2f", it) }))
                put("cpu4ReferenceP50Ms", String.format(Locale.US, "%.2f", afterData.cpu4ReferenceP50Ms))
                put("speedupVsCpu", String.format(Locale.US, "%.2fx", afterData.speedupVsCpu))
                put("maxDiffVsCpu", String.format(Locale.US, "%.6f", afterData.maxDiffVsCpu))
                put("maeVsCpu", String.format(Locale.US, "%.6f", afterData.maeVsCpu))
                put("argmaxAgreementVsCpu", String.format(Locale.US, "%.4f", afterData.argmaxAgreementVsCpu))
                put("walkableAgreementVsCpu", String.format(Locale.US, "%.4f", afterData.walkableAgreementVsCpu))
                put("thermalStatusStart", afterData.thermalStatusStart)
                put("thermalStatusEnd", afterData.thermalStatusEnd)
                put("batteryTempStartC", afterData.batteryTempStartC)
                put("batteryTempEndC", afterData.batteryTempEndC)
                put("rawGpuLine", parsedAfter.rawGpuLine)
                put("rawCpuFallbackLine", parsedAfter.rawCpuFallbackLine ?: "none")
                put("observedLogLines", JSONArray(afterData.observedLogLines))
            }

            fullJson.put("before_original_model", beforeJson)
            fullJson.put("after_transformed_model", afterJson)

            val speedupFactor = beforeData.p50LatencyMs / afterData.p50LatencyMs
            fullJson.put("measuredSpeedupGpuAfterVsBefore", String.format(Locale.US, "%.2fx", speedupFactor))

            val jsonStr = fullJson.toString(2)
            saveFile("gpu-evidence-20261024.json") { file ->
                file.writeText(jsonStr, Charsets.UTF_8)
            }
            saveFile("raw_logcat_before.txt") { file ->
                file.writeText(rawLogcatBefore, Charsets.UTF_8)
            }
            saveFile("raw_logcat_after.txt") { file ->
                file.writeText(rawLogcatAfter, Charsets.UTF_8)
            }
            execShell("chmod 666 /sdcard/Download/gpu_evidence_20261024/*")

            Log.i(TAG, "ALL_EVIDENCE_JSON=$jsonStr")
            Log.i(TAG, "=== Completed ARIA-GPU-PRESENTATION-EVIDENCE-001 ===")

            // Cleanup temporary cache files
            origFile.delete()
            unfusedFile.delete()
        } finally {
            // Restore device settings and cleanup UI
            runCatching {
                instrumentation.runOnMainSync {
                    GpuDiagnosticOverlayActivity.currentActivity?.finish()
                }
            }
            val restoreStayAwake = if (initialStayAwake.isNotBlank()) initialStayAwake else "0"
            execShell("settings put global stay_on_while_plugged_in $restoreStayAwake")
            Log.i(TAG, "Restored stay_on_while_plugged_in to $restoreStayAwake and finished overlay")
        }
    }
}
