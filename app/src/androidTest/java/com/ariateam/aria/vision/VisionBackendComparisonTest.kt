package com.ariateam.aria.vision

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.Random

@RunWith(AndroidJUnit4::class)
class VisionBackendComparisonTest {

    @Test
    fun probeGpuDelegateAndCpuThreadScaling() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = VisionModelStore(context, restoreOnInit = false)
        val ready = store.provisionDefaultAssetModelSync()
        assertTrue("Model must be provisioned", ready is VisionModelState.Ready)
        val modelFile = store.activeModelFile
        assertTrue("Active model file must exist", modelFile.isFile)

        val modelBuffer = RandomAccessFile(modelFile, "r").use { raf ->
            raf.channel.map(FileChannel.MapMode.READ_ONLY, 0L, raf.length())
        }

        val inputBuf = ByteBuffer.allocateDirect(VisionContract.INPUT_BYTES)
            .order(ByteOrder.nativeOrder())
        val floatInput = inputBuf.asFloatBuffer()
        val rng = Random(42L)
        floatInput.rewind()
        for (i in 0 until VisionContract.INPUT_FLOATS) {
            floatInput.put(rng.nextFloat())
        }

        val outBuf = ByteBuffer.allocateDirect(VisionContract.OUTPUT_BYTES)
            .order(ByteOrder.nativeOrder())

        val jsonResult = JSONObject()
        jsonResult.put("modelSizeBytes", modelFile.length())
        jsonResult.put("modelSha256", VisionContract.DEFAULT_ASSET_MODEL_SHA256)

        // 1. CPU Reference (4 threads, XNNPACK)
        val cpu4Options = Interpreter.Options().apply {
            setNumThreads(4)
            setUseXNNPACK(true)
        }
        val cpu4Interpreter = Interpreter(modelBuffer, cpu4Options)
        cpu4Interpreter.allocateTensors()

        // Warmup 1 run
        inputBuf.rewind()
        outBuf.rewind()
        cpu4Interpreter.run(inputBuf, outBuf)

        // 3 measured runs
        val cpu4Times = mutableListOf<Long>()
        val cpu4ReferenceFloats = FloatArray(VisionContract.OUTPUT_FLOATS)
        for (r in 0 until 3) {
            inputBuf.rewind()
            outBuf.rewind()
            val start = SystemClock.elapsedRealtimeNanos()
            cpu4Interpreter.run(inputBuf, outBuf)
            val dur = SystemClock.elapsedRealtimeNanos() - start
            cpu4Times.add(dur)
            if (r == 0) {
                val dup = outBuf.duplicate().order(ByteOrder.nativeOrder())
                dup.rewind()
                dup.asFloatBuffer().get(cpu4ReferenceFloats)
            }
        }
        cpu4Interpreter.close()

        val cpu4P50Ms = cpu4Times.sorted()[1] / 1_000_000.0
        jsonResult.put("cpu4P50Ms", String.format(Locale.US, "%.2f", cpu4P50Ms))
        Log.i("AriaVisionBackend", "CPU4 p50: $cpu4P50Ms ms")

        // 2. CPU 2 threads
        val cpu2Options = Interpreter.Options().apply {
            setNumThreads(2)
            setUseXNNPACK(true)
        }
        val cpu2Interpreter = Interpreter(modelBuffer, cpu2Options)
        cpu2Interpreter.allocateTensors()
        inputBuf.rewind()
        outBuf.rewind()
        cpu2Interpreter.run(inputBuf, outBuf)
        val cpu2Times = mutableListOf<Long>()
        for (r in 0 until 3) {
            inputBuf.rewind()
            outBuf.rewind()
            val start = SystemClock.elapsedRealtimeNanos()
            cpu2Interpreter.run(inputBuf, outBuf)
            cpu2Times.add(SystemClock.elapsedRealtimeNanos() - start)
        }
        cpu2Interpreter.close()
        val cpu2P50Ms = cpu2Times.sorted()[1] / 1_000_000.0
        jsonResult.put("cpu2P50Ms", String.format(Locale.US, "%.2f", cpu2P50Ms))
        Log.i("AriaVisionBackend", "CPU2 p50: $cpu2P50Ms ms")

        // 3. CPU 1 thread
        val cpu1Options = Interpreter.Options().apply {
            setNumThreads(1)
            setUseXNNPACK(true)
        }
        val cpu1Interpreter = Interpreter(modelBuffer, cpu1Options)
        cpu1Interpreter.allocateTensors()
        inputBuf.rewind()
        outBuf.rewind()
        cpu1Interpreter.run(inputBuf, outBuf)
        val cpu1Times = mutableListOf<Long>()
        for (r in 0 until 3) {
            inputBuf.rewind()
            outBuf.rewind()
            val start = SystemClock.elapsedRealtimeNanos()
            cpu1Interpreter.run(inputBuf, outBuf)
            cpu1Times.add(SystemClock.elapsedRealtimeNanos() - start)
        }
        cpu1Interpreter.close()
        val cpu1P50Ms = cpu1Times.sorted()[1] / 1_000_000.0
        jsonResult.put("cpu1P50Ms", String.format(Locale.US, "%.2f", cpu1P50Ms))
        Log.i("AriaVisionBackend", "CPU1 p50: $cpu1P50Ms ms")

        // 4. GPU Delegate probe
        val compatList = CompatibilityList()
        val gpuSupported = compatList.isDelegateSupportedOnThisDevice
        jsonResult.put("gpuCompatibilityListSupported", gpuSupported)
        Log.i("AriaVisionBackend", "GPU compat list supported: $gpuSupported")

        var gpuDelegate: GpuDelegate? = null
        var gpuInterpreter: Interpreter? = null
        try {
            val delegateOptions = compatList.bestOptionsForThisDevice
            gpuDelegate = GpuDelegate(delegateOptions)
            val gpuOptions = Interpreter.Options().apply {
                addDelegate(gpuDelegate)
            }
            gpuInterpreter = Interpreter(modelBuffer, gpuOptions)
            gpuInterpreter.allocateTensors()

            // Warmup 1 run
            inputBuf.rewind()
            outBuf.rewind()
            gpuInterpreter.run(inputBuf, outBuf)

            val gpuTimes = mutableListOf<Long>()
            val gpuOutputFloats = FloatArray(VisionContract.OUTPUT_FLOATS)
            for (r in 0 until 5) {
                inputBuf.rewind()
                outBuf.rewind()
                val start = SystemClock.elapsedRealtimeNanos()
                gpuInterpreter.run(inputBuf, outBuf)
                val dur = SystemClock.elapsedRealtimeNanos() - start
                gpuTimes.add(dur)
                if (r == 0) {
                    val dup = outBuf.duplicate().order(ByteOrder.nativeOrder())
                    dup.rewind()
                    dup.asFloatBuffer().get(gpuOutputFloats)
                }
            }

            val gpuP50Ms = gpuTimes.sorted()[2] / 1_000_000.0
            jsonResult.put("gpuSuccess", true)
            jsonResult.put("gpuP50Ms", String.format(Locale.US, "%.2f", gpuP50Ms))
            Log.i("AriaVisionBackend", "GPU p50: $gpuP50Ms ms")

            // Parity comparison vs CPU reference
            var maxDiff = 0.0f
            var matchingArgmax = 0
            val numClasses = VisionContract.MODEL_CLASSES
            val numPixels = VisionContract.INPUT_PIXELS
            var offset = 0
            for (p in 0 until numPixels) {
                var bestCpuClass = 0
                var bestCpuProb = cpu4ReferenceFloats[offset]
                var bestGpuClass = 0
                var bestGpuProb = gpuOutputFloats[offset]
                for (c in 0 until numClasses) {
                    val cpuP = cpu4ReferenceFloats[offset + c]
                    val gpuP = gpuOutputFloats[offset + c]
                    val diff = kotlin.math.abs(cpuP - gpuP)
                    if (diff > maxDiff) maxDiff = diff
                    if (cpuP > bestCpuProb) {
                        bestCpuProb = cpuP
                        bestCpuClass = c
                    }
                    if (gpuP > bestGpuProb) {
                        bestGpuProb = gpuP
                        bestGpuClass = c
                    }
                }
                if (bestCpuClass == bestGpuClass) matchingArgmax++
                offset += numClasses
            }
            val argmaxAgreement = matchingArgmax.toFloat() / numPixels
            jsonResult.put("gpuMaxDiffVsCpu", String.format(Locale.US, "%.5f", maxDiff))
            jsonResult.put("gpuArgmaxAgreementVsCpu", String.format(Locale.US, "%.4f", argmaxAgreement))
            Log.i("AriaVisionBackend", "GPU vs CPU maxDiff: $maxDiff, argmax agreement: $argmaxAgreement")
        } catch (t: Throwable) {
            jsonResult.put("gpuSuccess", false)
            jsonResult.put("gpuFailureReason", "${t.javaClass.simpleName}: ${t.message.orEmpty().take(120)}")
            Log.w("AriaVisionBackend", "GPU execution failed", t)
        } finally {
            // Close interpreter before delegate on the SAME thread
            runCatching { gpuInterpreter?.close() }
            runCatching { gpuDelegate?.close() }
            store.close()
        }

        val reportFile = File(context.filesDir, "backend-comparison.json")
        reportFile.writeText(jsonResult.toString(2), Charsets.UTF_8)
        runCatching {
            val sdDir = File("/sdcard/Download/final_reports").apply { mkdirs() }
            File(sdDir, "backend-comparison.json").writeText(jsonResult.toString(2), Charsets.UTF_8)
        }
        Log.i("AriaVisionBackend", "BACKEND_COMPARISON_JSON=${jsonResult}")
    }

    @Test
    fun probeTransformedUnfusedModelOnGpuAndCpu() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assetName = "models/walkable_seg_aria5_unfused.tflite"

        val cacheFile = File(context.cacheDir, "walkable_seg_aria5_unfused.tflite")
        context.assets.open(assetName).use { input ->
            cacheFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        assertTrue("Unfused model must be copied", cacheFile.isFile && cacheFile.length() > 0)

        val unfusedBuffer = RandomAccessFile(cacheFile, "r").use { raf ->
            raf.channel.map(FileChannel.MapMode.READ_ONLY, 0L, raf.length())
        }

        val inputBuf = ByteBuffer.allocateDirect(VisionContract.INPUT_BYTES)
            .order(ByteOrder.nativeOrder())
        val floatInput = inputBuf.asFloatBuffer()
        val rng = Random(42L)
        floatInput.rewind()
        for (i in 0 until VisionContract.INPUT_FLOATS) {
            floatInput.put(rng.nextFloat())
        }

        val outBuf = ByteBuffer.allocateDirect(VisionContract.OUTPUT_BYTES)
            .order(ByteOrder.nativeOrder())

        val jsonResult = JSONObject()
        jsonResult.put("unfusedModelSizeBytes", cacheFile.length())

        // 1. Unfused Model CPU 4 threads
        val cpu4Options = Interpreter.Options().apply {
            setNumThreads(4)
            setUseXNNPACK(true)
        }
        val cpu4Interpreter = Interpreter(unfusedBuffer, cpu4Options)
        cpu4Interpreter.allocateTensors()

        // Warmup
        inputBuf.rewind()
        outBuf.rewind()
        cpu4Interpreter.run(inputBuf, outBuf)

        val cpu4Times = mutableListOf<Long>()
        val cpu4Floats = FloatArray(VisionContract.OUTPUT_FLOATS)
        for (r in 0 until 5) {
            inputBuf.rewind()
            outBuf.rewind()
            val start = SystemClock.elapsedRealtimeNanos()
            cpu4Interpreter.run(inputBuf, outBuf)
            val dur = SystemClock.elapsedRealtimeNanos() - start
            cpu4Times.add(dur)
            if (r == 0) {
                val dup = outBuf.duplicate().order(ByteOrder.nativeOrder())
                dup.rewind()
                dup.asFloatBuffer().get(cpu4Floats)
            }
        }
        cpu4Interpreter.close()

        val cpu4P50Ms = cpu4Times.sorted()[2] / 1_000_000.0
        jsonResult.put("unfusedCpu4P50Ms", String.format(Locale.US, "%.2f", cpu4P50Ms))
        Log.i("AriaVisionBackend", "Unfused CPU4 p50: $cpu4P50Ms ms")

        // 2. Unfused Model on GPU delegate
        val compatList = CompatibilityList()
        var gpuDelegate: GpuDelegate? = null
        var gpuInterpreter: Interpreter? = null
        try {
            val delegateOptions = compatList.bestOptionsForThisDevice
            gpuDelegate = GpuDelegate(delegateOptions)
            val gpuOptions = Interpreter.Options().apply {
                addDelegate(gpuDelegate)
            }
            gpuInterpreter = Interpreter(unfusedBuffer, gpuOptions)
            gpuInterpreter.allocateTensors()

            // Warmup
            inputBuf.rewind()
            outBuf.rewind()
            gpuInterpreter.run(inputBuf, outBuf)

            val gpuTimes = mutableListOf<Long>()
            val gpuFloats = FloatArray(VisionContract.OUTPUT_FLOATS)
            for (r in 0 until 5) {
                inputBuf.rewind()
                outBuf.rewind()
                val start = SystemClock.elapsedRealtimeNanos()
                gpuInterpreter.run(inputBuf, outBuf)
                val dur = SystemClock.elapsedRealtimeNanos() - start
                gpuTimes.add(dur)
                if (r == 0) {
                    val dup = outBuf.duplicate().order(ByteOrder.nativeOrder())
                    dup.rewind()
                    dup.asFloatBuffer().get(gpuFloats)
                }
            }

            val gpuP50Ms = gpuTimes.sorted()[2] / 1_000_000.0
            jsonResult.put("unfusedGpuSuccess", true)
            jsonResult.put("unfusedGpuP50Ms", String.format(Locale.US, "%.2f", gpuP50Ms))
            Log.i("AriaVisionBackend", "Unfused GPU p50: $gpuP50Ms ms")

            // Parity: GPU vs CPU on Unfused model
            var maxDiff = 0.0f
            var matchingArgmax = 0
            val numClasses = VisionContract.MODEL_CLASSES
            val numPixels = VisionContract.INPUT_PIXELS
            var offset = 0
            for (p in 0 until numPixels) {
                var bestCpuClass = 0
                var bestCpuProb = cpu4Floats[offset]
                var bestGpuClass = 0
                var bestGpuProb = gpuFloats[offset]
                for (c in 0 until numClasses) {
                    val cpuP = cpu4Floats[offset + c]
                    val gpuP = gpuFloats[offset + c]
                    val diff = kotlin.math.abs(cpuP - gpuP)
                    if (diff > maxDiff) maxDiff = diff
                    if (cpuP > bestCpuProb) {
                        bestCpuProb = cpuP
                        bestCpuClass = c
                    }
                    if (gpuP > bestGpuProb) {
                        bestGpuProb = gpuP
                        bestGpuClass = c
                    }
                }
                if (bestCpuClass == bestGpuClass) matchingArgmax++
                offset += numClasses
            }
            val argmaxAgreement = matchingArgmax.toFloat() / numPixels
            jsonResult.put("unfusedGpuMaxDiffVsCpu", String.format(Locale.US, "%.5f", maxDiff))
            jsonResult.put("unfusedGpuArgmaxAgreementVsCpu", String.format(Locale.US, "%.4f", argmaxAgreement))
            Log.i("AriaVisionBackend", "Unfused GPU vs CPU maxDiff: $maxDiff, argmax: $argmaxAgreement")
        } catch (t: Throwable) {
            jsonResult.put("unfusedGpuSuccess", false)
            jsonResult.put("unfusedGpuFailureReason", "${t.javaClass.simpleName}: ${t.message.orEmpty().take(120)}")
            Log.w("AriaVisionBackend", "Unfused GPU failed", t)
        } finally {
            runCatching { gpuInterpreter?.close() }
            runCatching { gpuDelegate?.close() }
            cacheFile.delete()
        }

        val reportFile = File(context.filesDir, "unfused-comparison.json")
        reportFile.writeText(jsonResult.toString(2), Charsets.UTF_8)
        runCatching {
            val sdDir = File("/sdcard/Download/final_reports").apply { mkdirs() }
            File(sdDir, "unfused-comparison.json").writeText(jsonResult.toString(2), Charsets.UTF_8)
        }
        Log.i("AriaVisionBackend", "UNFUSED_COMPARISON_JSON=${jsonResult}")
    }

    @Test
    fun probePrecisionTradeoffOnTransformedModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assetName = "models/walkable_seg_aria5_unfused.tflite"

        val cacheFile = File(context.cacheDir, "unfused_precision_test.tflite")
        context.assets.open(assetName).use { input ->
            cacheFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        assertTrue("Unfused model must be copied", cacheFile.isFile && cacheFile.length() > 0)

        val unfusedBuffer = RandomAccessFile(cacheFile, "r").use { raf ->
            raf.channel.map(FileChannel.MapMode.READ_ONLY, 0L, raf.length())
        }

        val numPixels = VisionContract.INPUT_PIXELS
        val numClasses = VisionContract.MODEL_CLASSES
        val numFloats = VisionContract.OUTPUT_FLOATS

        data class Fixture(val name: String, val generate: () -> ByteBuffer)

        val fixtures = listOf(
            Fixture("constant_0.5") {
                val buf = ByteBuffer.allocateDirect(VisionContract.INPUT_BYTES).order(ByteOrder.nativeOrder())
                val fb = buf.asFloatBuffer()
                for (i in 0 until VisionContract.INPUT_FLOATS) fb.put(0.5f)
                buf.rewind()
                buf
            },
            Fixture("gradient_ramp") {
                val buf = ByteBuffer.allocateDirect(VisionContract.INPUT_BYTES).order(ByteOrder.nativeOrder())
                val fb = buf.asFloatBuffer()
                for (y in 0 until VisionContract.MODEL_HEIGHT) {
                    val yNorm = y.toFloat() / VisionContract.MODEL_HEIGHT
                    for (x in 0 until VisionContract.MODEL_WIDTH) {
                        val xNorm = x.toFloat() / VisionContract.MODEL_WIDTH
                        for (c in 0 until VisionContract.MODEL_CHANNELS) {
                            val v = ((xNorm + yNorm + c * 0.25f) / 2.25f).coerceIn(0f, 1f)
                            fb.put(v)
                        }
                    }
                }
                buf.rewind()
                buf
            },
            Fixture("seeded_random") {
                val buf = ByteBuffer.allocateDirect(VisionContract.INPUT_BYTES).order(ByteOrder.nativeOrder())
                val fb = buf.asFloatBuffer()
                val rng = Random(42L)
                for (i in 0 until VisionContract.INPUT_FLOATS) fb.put(rng.nextFloat())
                buf.rewind()
                buf
            },
        )

        val jsonResult = JSONObject()
        val fixturesArray = org.json.JSONArray()

        // Evaluator helper
        fun runEvaluation(
            interpreter: Interpreter,
            inputBuf: ByteBuffer,
            outBuf: ByteBuffer,
            runs: Int = 5,
        ): Pair<Double, FloatArray> {
            val times = mutableListOf<Long>()
            val outFloats = FloatArray(numFloats)
            for (r in 0 until runs) {
                inputBuf.rewind()
                outBuf.rewind()
                val start = SystemClock.elapsedRealtimeNanos()
                interpreter.run(inputBuf, outBuf)
                val dur = SystemClock.elapsedRealtimeNanos() - start
                times.add(dur)
                if (r == 0) {
                    val dup = outBuf.duplicate().order(ByteOrder.nativeOrder())
                    dup.rewind()
                    dup.asFloatBuffer().get(outFloats)
                }
            }
            val p50Ms = times.sorted()[runs / 2] / 1_000_000.0
            return Pair(p50Ms, outFloats)
        }

        // 1. Build CPU reference Interpreter
        val cpuOptions = Interpreter.Options().apply {
            setNumThreads(4)
            setUseXNNPACK(true)
        }
        val cpuInterpreter = Interpreter(unfusedBuffer, cpuOptions).apply { allocateTensors() }

        // 2. Build GPU FP32 Default (precisionLossAllowed = false)
        val gpuFp32Options = GpuDelegate.Options().apply {
            setPrecisionLossAllowed(false)
        }
        val gpuFp32Delegate = GpuDelegate(gpuFp32Options)
        val gpuFp32Interpreter = Interpreter(unfusedBuffer, Interpreter.Options().addDelegate(gpuFp32Delegate)).apply { allocateTensors() }

        // 2b. Build GPU FP32 Sustained (precisionLossAllowed = false, INFERENCE_PREFERENCE_SUSTAINED_SPEED)
        val gpuFp32SustainedOptions = GpuDelegate.Options().apply {
            setPrecisionLossAllowed(false)
            setInferencePreference(org.tensorflow.lite.gpu.GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
        }
        val gpuFp32SustainedDelegate = GpuDelegate(gpuFp32SustainedOptions)
        val gpuFp32SustainedInterpreter = Interpreter(unfusedBuffer, Interpreter.Options().addDelegate(gpuFp32SustainedDelegate)).apply { allocateTensors() }

        // 3. Build GPU FP16 (precisionLossAllowed = true)
        val gpuFp16Options = GpuDelegate.Options().apply {
            setPrecisionLossAllowed(true)
        }
        val gpuFp16Delegate = GpuDelegate(gpuFp16Options)
        val gpuFp16Interpreter = Interpreter(unfusedBuffer, Interpreter.Options().addDelegate(gpuFp16Delegate)).apply { allocateTensors() }

        val outBuf = ByteBuffer.allocateDirect(VisionContract.OUTPUT_BYTES).order(ByteOrder.nativeOrder())

        // Warm up all 4 interpreters with fixture 0
        val warmupBuf = fixtures[0].generate()
        warmupBuf.rewind()
        outBuf.rewind()
        cpuInterpreter.run(warmupBuf, outBuf)
        warmupBuf.rewind()
        outBuf.rewind()
        gpuFp32Interpreter.run(warmupBuf, outBuf)
        warmupBuf.rewind()
        outBuf.rewind()
        gpuFp32SustainedInterpreter.run(warmupBuf, outBuf)
        warmupBuf.rewind()
        outBuf.rewind()
        gpuFp16Interpreter.run(warmupBuf, outBuf)

        for (fixture in fixtures) {
            val inputBuf = fixture.generate()
            val fixtureJson = JSONObject()
            fixtureJson.put("fixtureName", fixture.name)

            // Evaluate CPU FP32
            val (cpuP50, cpuFloats) = runEvaluation(cpuInterpreter, inputBuf, outBuf)
            fixtureJson.put("cpuFp32P50Ms", String.format(Locale.US, "%.2f", cpuP50))

            // Evaluate GPU FP32
            val (gpuFp32P50, gpuFp32Floats) = runEvaluation(gpuFp32Interpreter, inputBuf, outBuf)
            fixtureJson.put("gpuFp32P50Ms", String.format(Locale.US, "%.2f", gpuFp32P50))

            // Evaluate GPU FP32 Sustained
            val (gpuFp32SustainedP50, gpuFp32SustainedFloats) = runEvaluation(gpuFp32SustainedInterpreter, inputBuf, outBuf)
            fixtureJson.put("gpuFp32SustainedP50Ms", String.format(Locale.US, "%.2f", gpuFp32SustainedP50))

            // Evaluate GPU FP16
            val (gpuFp16P50, gpuFp16Floats) = runEvaluation(gpuFp16Interpreter, inputBuf, outBuf)
            fixtureJson.put("gpuFp16P50Ms", String.format(Locale.US, "%.2f", gpuFp16P50))

            // Compare GPU FP32 vs CPU FP32
            fun compare(gpuFloats: FloatArray, label: String) {
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

                val mae = (sumDiff / numFloats).toFloat()
                val argmaxAgreement = matchingArgmax.toFloat() / numPixels
                val walkableAgreement = matchingWalkable.toFloat() / numPixels

                if (label == "gpuFp32" || label == "gpuFp32Sustained") {
                    assertTrue("$label maxDiff must be < 0.001f on ${fixture.name}, was $maxDiff", maxDiff < 0.001f)
                    assertTrue("$label MAE must be < 1e-4f on ${fixture.name}, was $mae", mae < 1e-4f)
                    assertTrue("$label argmaxAgreement must be >= 0.9999f on ${fixture.name}, was $argmaxAgreement", argmaxAgreement >= 0.9999f)
                    assertTrue("$label walkableAgreement must be >= 0.9999f on ${fixture.name}, was $walkableAgreement", walkableAgreement >= 0.9999f)

                    // Assert softmax sum across classes per pixel is ~1.0 and all outputs are finite
                    var offCheck = 0
                    for (p in 0 until numPixels) {
                        var sumCpu = 0f
                        var sumGpu = 0f
                        for (c in 0 until numClasses) {
                            val cp = cpuFloats[offCheck + c]
                            val gp = gpuFloats[offCheck + c]
                            assertTrue("CPU output at pixel $p class $c must be finite", cp.isFinite())
                            assertTrue("$label output at pixel $p class $c must be finite", gp.isFinite())
                            sumCpu += cp
                            sumGpu += gp
                        }
                        assertTrue("CPU softmax sum near 1.0 at pixel $p", sumCpu in 0.98f..1.02f)
                        assertTrue("$label softmax sum near 1.0 at pixel $p", sumGpu in 0.98f..1.02f)
                        offCheck += numClasses
                    }
                }

                fixtureJson.put("${label}_maxDiff", String.format(Locale.US, "%.6f", maxDiff))
                fixtureJson.put("${label}_mae", String.format(Locale.US, "%.6f", mae))
                fixtureJson.put("${label}_argmaxAgreement", String.format(Locale.US, "%.4f", argmaxAgreement))
                fixtureJson.put("${label}_walkableAgreement", String.format(Locale.US, "%.4f", walkableAgreement))

                Log.i(
                    "AriaVisionBackend",
                    "[Fixture ${fixture.name}] $label vs CPU: maxDiff=$maxDiff, MAE=$mae, argmax=${argmaxAgreement * 100}%, walkable=${walkableAgreement * 100}%"
                )
            }

            compare(gpuFp32Floats, "gpuFp32")
            compare(gpuFp32SustainedFloats, "gpuFp32Sustained")
            compare(gpuFp16Floats, "gpuFp16")

            fixturesArray.put(fixtureJson)
        }

        cpuInterpreter.close()
        gpuFp32Interpreter.close()
        gpuFp32Delegate.close()
        gpuFp32SustainedInterpreter.close()
        gpuFp32SustainedDelegate.close()
        gpuFp16Interpreter.close()
        gpuFp16Delegate.close()
        cacheFile.delete()

        jsonResult.put("precisionTradeoffResults", fixturesArray)
        val reportFile = File(context.filesDir, "precision-tradeoff.json")
        reportFile.writeText(jsonResult.toString(2), Charsets.UTF_8)
        runCatching {
            val sdDir = File("/sdcard/Download/final_reports").apply { mkdirs() }
            File(sdDir, "precision-tradeoff.json").writeText(jsonResult.toString(2), Charsets.UTF_8)
        }
        Log.i("AriaVisionBackend", "PRECISION_TRADEOFF_JSON=${jsonResult}")
    }

    @Test
    fun probeOriginalVsTransformedCpuParity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val origAssetName = "models/walkable_seg_aria5.tflite"
        val unfusedAssetName = "models/walkable_seg_aria5_unfused.tflite"

        val origFile = File(context.cacheDir, "orig_parity_test.tflite")
        val unfusedFile = File(context.cacheDir, "unfused_parity_test.tflite")

        context.assets.open(origAssetName).use { input ->
            origFile.outputStream().use { output -> input.copyTo(output) }
        }
        context.assets.open(unfusedAssetName).use { input ->
            unfusedFile.outputStream().use { output -> input.copyTo(output) }
        }

        val origBuffer = RandomAccessFile(origFile, "r").use { raf ->
            raf.channel.map(FileChannel.MapMode.READ_ONLY, 0L, raf.length())
        }
        val unfusedBuffer = RandomAccessFile(unfusedFile, "r").use { raf ->
            raf.channel.map(FileChannel.MapMode.READ_ONLY, 0L, raf.length())
        }

        val cpuOptions = Interpreter.Options().apply {
            setNumThreads(4)
            setUseXNNPACK(true)
        }
        val origInterpreter = Interpreter(origBuffer, cpuOptions).apply { allocateTensors() }
        val unfusedInterpreter = Interpreter(unfusedBuffer, cpuOptions).apply { allocateTensors() }

        val numPixels = VisionContract.INPUT_PIXELS
        val numClasses = VisionContract.MODEL_CLASSES
        val numFloats = VisionContract.OUTPUT_FLOATS

        data class Fixture(val name: String, val generate: () -> ByteBuffer)
        val fixtures = listOf(
            Fixture("constant_0.5") {
                val buf = ByteBuffer.allocateDirect(VisionContract.INPUT_BYTES).order(ByteOrder.nativeOrder())
                val fb = buf.asFloatBuffer()
                for (i in 0 until VisionContract.INPUT_FLOATS) fb.put(0.5f)
                buf.rewind()
                buf
            },
            Fixture("gradient_ramp") {
                val buf = ByteBuffer.allocateDirect(VisionContract.INPUT_BYTES).order(ByteOrder.nativeOrder())
                val fb = buf.asFloatBuffer()
                for (y in 0 until VisionContract.MODEL_HEIGHT) {
                    val yNorm = y.toFloat() / VisionContract.MODEL_HEIGHT
                    for (x in 0 until VisionContract.MODEL_WIDTH) {
                        val xNorm = x.toFloat() / VisionContract.MODEL_WIDTH
                        for (c in 0 until VisionContract.MODEL_CHANNELS) {
                            val v = ((xNorm + yNorm + c * 0.25f) / 2.25f).coerceIn(0f, 1f)
                            fb.put(v)
                        }
                    }
                }
                buf.rewind()
                buf
            },
            Fixture("seeded_random") {
                val buf = ByteBuffer.allocateDirect(VisionContract.INPUT_BYTES).order(ByteOrder.nativeOrder())
                val fb = buf.asFloatBuffer()
                val rng = Random(42L)
                for (i in 0 until VisionContract.INPUT_FLOATS) fb.put(rng.nextFloat())
                buf.rewind()
                buf
            },
        )

        val outBufOrig = ByteBuffer.allocateDirect(VisionContract.OUTPUT_BYTES).order(ByteOrder.nativeOrder())
        val outBufUnfused = ByteBuffer.allocateDirect(VisionContract.OUTPUT_BYTES).order(ByteOrder.nativeOrder())

        val jsonResult = JSONObject()
        val resultsArray = org.json.JSONArray()

        for (fixture in fixtures) {
            val inputBuf1 = fixture.generate()
            val inputBuf2 = fixture.generate()

            outBufOrig.rewind()
            origInterpreter.run(inputBuf1, outBufOrig)

            outBufUnfused.rewind()
            unfusedInterpreter.run(inputBuf2, outBufUnfused)

            val origFloats = FloatArray(numFloats)
            val unfusedFloats = FloatArray(numFloats)

            outBufOrig.rewind()
            outBufOrig.asFloatBuffer().get(origFloats)

            outBufUnfused.rewind()
            outBufUnfused.asFloatBuffer().get(unfusedFloats)

            var maxDiff = 0f
            var sumDiff = 0.0
            var matchingArgmax = 0
            var matchingWalkable = 0
            var origSum = 0.0
            var unfusedSum = 0.0
            var anyNonFinite = false

            var offset = 0
            for (p in 0 until numPixels) {
                var bestOrigClass = 0
                var bestOrigProb = origFloats[offset]
                var bestUnfusedClass = 0
                var bestUnfusedProb = unfusedFloats[offset]

                for (c in 0 until numClasses) {
                    val op = origFloats[offset + c]
                    val up = unfusedFloats[offset + c]
                    if (!op.isFinite() || !up.isFinite()) anyNonFinite = true
                    origSum += op
                    unfusedSum += up

                    val diff = kotlin.math.abs(op - up)
                    if (diff > maxDiff) maxDiff = diff
                    sumDiff += diff

                    if (op > bestOrigProb) {
                        bestOrigProb = op
                        bestOrigClass = c
                    }
                    if (up > bestUnfusedProb) {
                        bestUnfusedProb = up
                        bestUnfusedClass = c
                    }
                }

                if (bestOrigClass == bestUnfusedClass) matchingArgmax++
                val origWalkable = bestOrigClass == 2 || bestOrigClass == 3
                val unfusedWalkable = bestUnfusedClass == 2 || bestUnfusedClass == 3
                if (origWalkable == unfusedWalkable) matchingWalkable++

                offset += numClasses
            }

            val mae = (sumDiff / numFloats).toFloat()
            val argmaxAgreement = matchingArgmax.toFloat() / numPixels
            val walkableAgreement = matchingWalkable.toFloat() / numPixels

            assertFalse("Non-finite numbers found in CPU parity test on ${fixture.name}", anyNonFinite)
            assertEquals("Original CPU vs Transformed CPU maxDiff must be 0.0 on ${fixture.name}", 0.0f, maxDiff, 1e-6f)
            assertEquals("Original CPU vs Transformed CPU MAE must be 0.0 on ${fixture.name}", 0.0f, mae, 1e-6f)
            assertEquals("Original CPU vs Transformed CPU argmaxAgreement must be 1.0 on ${fixture.name}", 1.0f, argmaxAgreement, 1e-6f)
            assertEquals("Original CPU vs Transformed CPU walkableAgreement must be 1.0 on ${fixture.name}", 1.0f, walkableAgreement, 1e-6f)

            val fJson = JSONObject().apply {
                put("fixture", fixture.name)
                put("origSum", origSum)
                put("unfusedSum", unfusedSum)
                put("anyNonFinite", anyNonFinite)
                put("maxDiff", maxDiff)
                put("mae", mae)
                put("argmaxAgreement", argmaxAgreement)
                put("walkableAgreement", walkableAgreement)
            }
            resultsArray.put(fJson)
            Log.i("AriaVisionBackend", "CPU Parity [${fixture.name}]: maxDiff=$maxDiff, MAE=$mae, argmaxAgreement=${argmaxAgreement * 100}%, walkableAgreement=${walkableAgreement * 100}%")
        }

        origInterpreter.close()
        unfusedInterpreter.close()
        origFile.delete()
        unfusedFile.delete()

        jsonResult.put("cpuParityResults", resultsArray)
        val reportFile = File(context.filesDir, "cpu-parity-comparison.json")
        reportFile.writeText(jsonResult.toString(2), Charsets.UTF_8)
        runCatching {
            val sdDir = File("/sdcard/Download/final_reports").apply { mkdirs() }
            File(sdDir, "cpu-parity-comparison.json").writeText(jsonResult.toString(2), Charsets.UTF_8)
        }
        Log.i("AriaVisionBackend", "CPU_PARITY_JSON=$jsonResult")
    }
}
