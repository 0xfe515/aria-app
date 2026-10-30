package com.ariateam.aria.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class VisionPortraitModelEvaluationTest {

    @Test
    fun outputReaderRewindsInterpreterBufferBeforeReading() {
        val expected = floatArrayOf(0.25f, 0.5f, 0.75f)
        val output = ByteBuffer.allocateDirect(expected.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        output.asFloatBuffer().put(expected)
        output.position(output.limit())

        assertArrayEquals(expected, floatsFrom(output, expected.size), 0.0f)
    }

    @Test
    fun syntheticPortraitShapeGeometryAndPaddingAreCorrect() {
        val source = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
            setPixel(0, 0, Color.RED)
            setPixel(15, 0, Color.GREEN)
            setPixel(0, 7, Color.BLUE)
            setPixel(15, 7, Color.YELLOW)
        }
        val upright = prepareInput(
            source = source,
            arm = PORTRAIT_MODEL,
            sourceOffsetX = 0,
            sourceOffsetY = 0,
            modelWidth = 288,
            modelHeight = 512,
        )
        assertEquals(288, upright.bitmap.width)
        assertEquals(512, upright.bitmap.height)
        assertEquals(0, upright.inverseRotationDegrees)
        assertEquals(Rect(0, 0, 16, 8), upright.sourceRect)
        assertEquals(Rect(0, 184, 288, 328), upright.modelContentRect)
        assertColorNear(Color.RED, upright.bitmap.getPixel(8, 190))
        assertColorNear(Color.GREEN, upright.bitmap.getPixel(279, 190))
        assertColorNear(Color.BLUE, upright.bitmap.getPixel(8, 321))
        assertColorNear(Color.YELLOW, upright.bitmap.getPixel(279, 321))
        assertEquals(Color.BLACK, upright.bitmap.getPixel(100, 8))

        val syntheticPrediction = Bitmap.createBitmap(288, 512, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(6, 6, 6))
            for (y in upright.modelContentRect.top until upright.modelContentRect.bottom) {
                for (x in upright.modelContentRect.left until upright.modelContentRect.right) {
                    setPixel(x, y, Color.rgb(2, 2, 2))
                }
            }
        }
        val mapped = inversePredictionForTest(
            prediction = syntheticPrediction,
            modelContentRect = upright.modelContentRect,
            sourceRect = upright.sourceRect,
            sourceSize = source.width to source.height,
            inverseRotationDegrees = upright.inverseRotationDegrees,
        )
        assertTrue(mapped.visible.all { row -> row.all { it } })
        assertTrue(mapped.prediction.all { row -> row.all { it == 2 } })
        assertFalse(mapped.visible[0][0] && mapped.prediction[0][0] == 6)

        upright.bitmap.recycle()
        syntheticPrediction.recycle()
        source.recycle()
    }

    @Test
    fun actualModelsRunPairedFourArmCamVidEvaluation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val externalRoot = requireNotNull(context.getExternalFilesDir(null)) {
            "App-specific external storage is unavailable"
        }
        val fixtureDir = externalRoot.resolve("vision-orientation-evaluation/fixtures")
        val modelDir = externalRoot.resolve("vision-portrait-evaluation/model")
        val resultDir = externalRoot.resolve("vision-portrait-evaluation/results").apply { mkdirs() }
        val fixtureManifest = fixtureDir.resolve("manifest.json")
        val portraitModel = modelDir.resolve("portrait.tflite")
        val portraitAudit = modelDir.resolve("audit.json")
        assertTrue("Missing staged fixtures: ${fixtureManifest.absolutePath}", fixtureManifest.isFile)
        assertTrue("Missing staged portrait model: ${portraitModel.absolutePath}", portraitModel.isFile)
        assertTrue("Missing staged portrait audit: ${portraitAudit.absolutePath}", portraitAudit.isFile)

        val manifest = JSONObject(fixtureManifest.readText(Charsets.UTF_8))
        assertEquals(2, manifest.getInt("schemaVersion"))
        val fixtures = manifest.getJSONArray("frames")
        assertEquals("The paired run requires exactly eight source scenes", 8, fixtures.length())

        val legacyModel = context.cacheDir.resolve(VisionContract.LEGACY_OPTIMIZED_ASSET_MODEL_NAME)
        context.assets.open(VisionContract.LEGACY_OPTIMIZED_ASSET_MODEL_PATH).use { input ->
            legacyModel.outputStream().use { output -> input.copyTo(output) }
        }
        val legacyHash = sha256(legacyModel)
        assertEquals(VisionContract.LEGACY_OPTIMIZED_ASSET_MODEL_SHA256, legacyHash)
        val portraitHash = sha256(portraitModel)
        val auditText = portraitAudit.readText(Charsets.UTF_8)
        assertTrue(
            "Portrait model audit must identify the exact staged model SHA-256",
            auditText.contains(portraitHash, ignoreCase = true),
        )
        val auditJson = JSONObject(auditText)

        val legacyCpu = createCpuInterpreter(legacyModel)
        val legacyGpuAttempt = tryCreateGpuInterpreter(legacyModel)
        val legacyGpu = legacyGpuAttempt.session
        val legacyActive = legacyGpu ?: legacyCpu
        val legacyBackend = if (legacyGpu != null) "GPU_FP32" else "CPU_FP32"
        val portraitCpu = createCpuInterpreter(portraitModel)
        val portraitGpuAttempt = tryCreateGpuInterpreter(portraitModel)
        val parityReports = JSONArray()
        var portraitGpuAccepted = false
        var portraitGpuRejectReason: String? = portraitGpuAttempt.failure
        val portraitGpu = portraitGpuAttempt.session

        try {
            val legacyShape = tensorShape(legacyActive.interpreter, 512, 288)
            val portraitShape = tensorShape(portraitCpu.interpreter, 288, 512)
            assertArrayEquals(intArrayOf(1, 288, 512, 3), legacyShape.inputShape)
            assertArrayEquals(intArrayOf(1, 288, 512, 7), legacyShape.outputShape)
            assertArrayEquals(intArrayOf(1, 512, 288, 3), portraitShape.inputShape)
            assertArrayEquals(intArrayOf(1, 512, 288, 7), portraitShape.outputShape)
            assertEquals("FLOAT32", legacyShape.inputType)
            assertEquals("FLOAT32", legacyShape.outputType)
            assertEquals("FLOAT32", portraitShape.inputType)
            assertEquals("FLOAT32", portraitShape.outputType)

            if (portraitGpu != null) {
                val parityInputs = mutableListOf<Pair<String, Bitmap>>()
                parityInputs += "synthetic_blank" to Bitmap.createBitmap(
                    32,
                    64,
                    Bitmap.Config.ARGB_8888,
                ).apply { eraseColor(Color.BLACK) }
                parityInputs += "synthetic_asymmetric" to asymmetricBitmap()
                val firstFixtures = (0 until minOf(2, fixtures.length())).map { index ->
                    val fixture = fixtures.getJSONObject(index)
                    val portrait = decodeVerifiedFixture(
                        fixtureDir,
                        fixture.getString("portraitImage"),
                        fixture.getString("portraitImageSha256"),
                    )
                    "real_${fixture.getString("id")}" to portrait
                }
                parityInputs += firstFixtures
                try {
                    for ((name, bitmap) in parityInputs) {
                        val prepared = prepareInput(
                            source = bitmap,
                            arm = PORTRAIT_MODEL,
                            sourceOffsetX = 0,
                            sourceOffsetY = 0,
                            modelWidth = 288,
                            modelHeight = 512,
                        )
                        try {
                            val input = bitmapToInput(prepared.bitmap)
                            val cpuBuffer = outputBuffer(portraitShape)
                            val gpuBuffer = outputBuffer(portraitShape)
                            runInto(portraitCpu.interpreter, input, cpuBuffer)
                            runInto(portraitGpu.interpreter, input, gpuBuffer)
                            val cpuOutput = floatsFrom(cpuBuffer, portraitShape.outputFloats)
                            val gpuOutput = floatsFrom(gpuBuffer, portraitShape.outputFloats)
                            val report = compareOutputs(name, cpuOutput, gpuOutput)
                            parityReports.put(report)
                            val passed = parityPassed(report)
                            if (!passed) {
                                portraitGpuRejectReason =
                                    "FP32 parity threshold failed for $name: ${report.toString()}"
                            }
                        } finally {
                            prepared.bitmap.recycle()
                        }
                    }
                    portraitGpuAccepted = parityReports.length() > 0 &&
                        (0 until parityReports.length()).all {
                            parityPassed(parityReports.getJSONObject(it))
                        }
                    if (!portraitGpuAccepted && portraitGpuRejectReason == null) {
                        portraitGpuRejectReason = "FP32 parity threshold failed"
                    }
                } catch (error: Exception) {
                    portraitGpuRejectReason =
                        "GPU parity invocation failed: ${error.javaClass.simpleName}: ${error.message}"
                } finally {
                    parityInputs.forEach { it.second.recycle() }
                }
            }

            val portraitActive = if (portraitGpuAccepted) portraitGpu!! else portraitCpu
            val portraitBackend = if (portraitGpuAccepted) "GPU_FP32" else "CPU_FP32"
            Log.i(TAG, "portrait GPU FP32 parity: accepted=$portraitGpuAccepted reason=$portraitGpuRejectReason")
            val modelSpecs = JSONObject()
                .put(
                    "legacy",
                    modelJson(legacyHash, legacyModel.length(), legacyShape, legacyBackend, 4)
                        .put("gpuFallbackReason", legacyGpuAttempt.failure),
                )
                .put(
                    "portrait",
                    modelJson(portraitHash, portraitModel.length(), portraitShape, portraitBackend, 4)
                        .put("auditSha256", sha256(portraitAudit))
                        .put("audit", auditJson),
                )
            val resultFrames = JSONArray()
            val controlEquivalence = JSONArray()
            val warmups = 3
            val measurements = 5

            for (frameIndex in 0 until fixtures.length()) {
                val fixture = fixtures.getJSONObject(frameIndex)
                val frameId = fixture.getString("id")
                val sourceImage = decodeVerifiedFixture(
                    fixtureDir,
                    fixture.getString("sourceImage"),
                    fixture.getString("imageSha256"),
                )
                val sourceLabel = decodeVerifiedFixture(
                    fixtureDir,
                    fixture.getString("sourceLabel"),
                    fixture.getString("labelSha256"),
                    isLabel = true,
                )
                val portraitImage = decodeVerifiedFixture(
                    fixtureDir,
                    fixture.getString("portraitImage"),
                    fixture.getString("portraitImageSha256"),
                )
                val portraitLabel = decodeVerifiedFixture(
                    fixtureDir,
                    fixture.getString("portraitLabel"),
                    fixture.getString("portraitLabelSha256"),
                    isLabel = true,
                )
                val crop = fixture.getJSONArray("portraitCropRect")
                val cropLeft = crop.getInt(0)
                val cropTop = crop.getInt(1)
                val cropWidth = crop.getInt(2)
                val cropHeight = crop.getInt(3)
                assertEquals((sourceImage.height * 9) / 16, cropWidth)
                assertEquals((sourceImage.width - cropWidth) / 2, cropLeft)
                assertEquals(0, cropTop)
                assertEquals(sourceImage.height, cropHeight)
                assertEquals(cropWidth, portraitImage.width)
                assertEquals(cropHeight, portraitImage.height)
                assertEquals(cropWidth, portraitLabel.width)
                assertEquals(cropHeight, portraitLabel.height)
                assertEquals(sourceImage.width, fixture.getInt("width"))
                assertEquals(sourceImage.height, fixture.getInt("height"))
                assertTrue(sourceImage.width > sourceImage.height)
                assertTrue(portraitImage.height > portraitImage.width)
                assertPortraitFixtureMatchesSource(
                    sourceImage,
                    portraitImage,
                    cropLeft,
                    cropTop,
                    cropWidth,
                    cropHeight,
                )
                assertPortraitFixtureMatchesSource(
                    sourceLabel,
                    portraitLabel,
                    cropLeft,
                    cropTop,
                    cropWidth,
                    cropHeight,
                )

                val samples = listOf(
                    Sample("landscape", sourceImage, 0, 0),
                    Sample("portrait_center_crop", portraitImage, cropLeft, cropTop),
                )
                for (sample in samples) {
                    val fixtureId = "$frameId--${sample.orientation}"
                    val strategies = JSONArray()
                    val landscapeControlInputs = mutableMapOf<String, ByteArray>()
                    val landscapeControlPredictions = mutableMapOf<String, ByteArray>()
                    for (arm in ARMS) {
                        val transformStart = SystemClock.elapsedRealtimeNanos()
                        val isPortraitArm = arm == PORTRAIT_MODEL
                        val modelShape = if (isPortraitArm) portraitShape else legacyShape
                        val interpreter = if (isPortraitArm) portraitActive.interpreter else legacyActive.interpreter
                        val prepared = prepareInput(
                            source = sample.bitmap,
                            arm = arm,
                            sourceOffsetX = sample.offsetX,
                            sourceOffsetY = sample.offsetY,
                            modelWidth = modelShape.width,
                            modelHeight = modelShape.height,
                        )
                        val inputBuffer = bitmapToInput(prepared.bitmap)
                        val transformNanos = SystemClock.elapsedRealtimeNanos() - transformStart
                        val inputName = "$fixtureId--$arm-input.png"
                        val predictionName = "$fixtureId--$arm-prediction.png"
                        writePng(prepared.bitmap, resultDir.resolve(inputName))
                        if (sample.orientation == "landscape" && arm in LANDSCAPE_CONTROLS) {
                            landscapeControlInputs[arm] = bitmapPixels(prepared.bitmap)
                        }
                        val output = outputBuffer(modelShape)
                        repeat(warmups) { runInto(interpreter, inputBuffer, output) }
                        val invokeTimes = JSONArray()
                        val measuredOutputs = ArrayList<FloatArray>(measurements)
                        repeat(measurements) {
                            val start = SystemClock.elapsedRealtimeNanos()
                            runInto(interpreter, inputBuffer, output)
                            invokeTimes.put(SystemClock.elapsedRealtimeNanos() - start)
                            measuredOutputs += floatsFrom(output, modelShape.outputFloats)
                        }
                        val argmaxTimes = JSONArray()
                        var prediction = ByteArray(modelShape.width * modelShape.height)
                        for (measuredOutput in measuredOutputs) {
                            val start = SystemClock.elapsedRealtimeNanos()
                            prediction = classMask(measuredOutput, modelShape)
                            argmaxTimes.put(SystemClock.elapsedRealtimeNanos() - start)
                        }
                        if (sample.orientation == "landscape" && arm in LANDSCAPE_CONTROLS) {
                            landscapeControlPredictions[arm] = prediction
                        }
                        writePredictionMask(prediction, modelShape, resultDir.resolve(predictionName))
                        strategies.put(
                            JSONObject()
                                .put("arm", arm)
                                .put("modelKey", if (isPortraitArm) "portrait" else "legacy")
                                .put("inputImage", inputName)
                                .put("predictionImage", predictionName)
                                .put("modelContentRect", rectArray(prepared.modelContentRect))
                                .put("sourceRect", rectArray(prepared.sourceRect))
                                .put("inverseRotationDegrees", prepared.inverseRotationDegrees)
                                .put("transformNanos", transformNanos)
                                .put("invokeTimesNanos", invokeTimes)
                                .put("rawArgmaxPostprocessTimesNanos", argmaxTimes)
                                .put("modelWidth", modelShape.width)
                                .put("modelHeight", modelShape.height)
                                .put("productionTransform", transformJson(prepared.productionTransform)),
                        )
                        prepared.bitmap.recycle()
                    }
                    if (sample.orientation == "landscape") {
                        val controlsEqual = LANDSCAPE_CONTROLS.all { landscapeControlInputs[it] != null } &&
                            landscapeControlInputs[LANDSCAPE_CONTROLS[0]]!!.contentEquals(
                                landscapeControlInputs[LANDSCAPE_CONTROLS[1]]!!,
                            )
                        val masksEqual = LANDSCAPE_CONTROLS.all {
                            landscapeControlPredictions[it] != null
                        } && landscapeControlPredictions[LANDSCAPE_CONTROLS[0]]!!.contentEquals(
                            landscapeControlPredictions[LANDSCAPE_CONTROLS[1]]!!,
                        )
                        assertTrue("Landscape full-frame controls must have identical inputs", controlsEqual)
                        assertTrue("Landscape full-frame controls must have identical masks", masksEqual)
                        controlEquivalence.put(
                            JSONObject()
                                .put("fixtureId", fixtureId)
                                .put("modelInputsEqual", controlsEqual)
                                .put("predictionMasksEqual", masksEqual),
                        )
                    }
                    resultFrames.put(
                        JSONObject()
                            .put("id", fixtureId)
                            .put("baseFrameId", frameId)
                            .put("orientation", sample.orientation)
                            .put("orientationSourceRect", rectArray(
                                Rect(
                                    sample.offsetX,
                                    sample.offsetY,
                                    sample.offsetX + sample.bitmap.width,
                                    sample.offsetY + sample.bitmap.height,
                                ),
                            ))
                            .put("width", sample.bitmap.width)
                            .put("height", sample.bitmap.height)
                            .put("sourceWidth", sourceImage.width)
                            .put("sourceHeight", sourceImage.height)
                            .put("strategies", strategies),
                    )
                }
                sourceImage.recycle()
                sourceLabel.recycle()
                portraitImage.recycle()
                portraitLabel.recycle()
            }

            val run = JSONObject()
                .put("schemaVersion", 1)
                .put("deviceModel", Build.MODEL)
                .put("deviceManufacturer", Build.MANUFACTURER)
                .put("deviceAbi", Build.SUPPORTED_ABIS.firstOrNull())
                .put("sdkInt", Build.VERSION.SDK_INT)
                .put("androidRelease", Build.VERSION.RELEASE)
                .put("runtime", "LiteRT Java Interpreter 1.4.2")
                .put("runtimeArtifact", "com.google.ai.edge.litert:litert:1.4.2")
                .put("gpuArtifact", "com.google.ai.edge.litert:litert-gpu:1.4.2")
                .put("models", modelSpecs)
                .put("legacyRequestedBackend", "GPU FP32, CPU XNNPACK fallback")
                .put("portraitGpuParityStatus", if (portraitGpuAccepted) "accepted" else "rejected_or_unavailable")
                .put("portraitGpuParityRejectReason", portraitGpuRejectReason)
                .put("portraitGpuParityThresholds", parityThresholdJson())
                .put("portraitGpuParity", parityReports)
                .put("cpuThreads", 4)
                .put("warmupRunsPerInput", warmups)
                .put("measuredRunsPerInput", measurements)
                .put("sourceSceneCount", fixtures.length())
                .put("orientationFixtureCount", resultFrames.length())
                .put("armCount", ARMS.size)
                .put("conditionCount", resultFrames.length() * ARMS.size)
                .put("modelInvokeTimingScope", "Direct LiteRT Interpreter.run; output decoding excluded")
                .put("transformTimingScope", "Android Bitmap geometry, resize, RGB conversion; no CameraX YUV preprocessing")
                .put("frames", resultFrames)
                .put("landscapeControlEquivalence", controlEquivalence)
            resultDir.resolve("run.json").writeText(run.toString(2) + "\n", Charsets.UTF_8)

            assertEquals(16, resultFrames.length())
            assertEquals(64, resultFrames.length() * ARMS.size)
            assertTrue(
                "The portrait model must use the CPU fallback when its GPU candidate is rejected",
                portraitGpuAccepted || portraitBackend == "CPU_FP32",
            )
        } finally {
            legacyGpu?.close()
            legacyCpu.close()
            portraitGpu?.close()
            portraitCpu.close()
        }
    }

    private data class ModelShape(
        val width: Int,
        val height: Int,
        val classes: Int,
        val inputShape: IntArray,
        val outputShape: IntArray,
        val inputType: String,
        val outputType: String,
    ) {
        val inputFloats: Int get() = width * height * 3
        val outputFloats: Int get() = width * height * classes
    }

    private data class InterpreterSession(
        val interpreter: Interpreter,
        val delegate: GpuDelegate?,
    ) : AutoCloseable {
        override fun close() {
            interpreter.close()
            delegate?.close()
        }
    }

    private data class GpuAttempt(
        val session: InterpreterSession?,
        val failure: String?,
    )

    private data class Sample(
        val orientation: String,
        val bitmap: Bitmap,
        val offsetX: Int,
        val offsetY: Int,
    )

    private data class PreparedInput(
        val bitmap: Bitmap,
        val modelContentRect: Rect,
        val sourceRect: Rect,
        val productionTransform: UprightCropRect,
        val inverseRotationDegrees: Int,
    )

    private data class MappedPrediction(
        val prediction: Array<IntArray>,
        val visible: Array<BooleanArray>,
    )

    private fun createCpuInterpreter(file: File): InterpreterSession {
        val options = Interpreter.Options().setNumThreads(4).setUseXNNPACK(true)
        val interpreter = Interpreter(file, options)
        interpreter.allocateTensors()
        return InterpreterSession(interpreter, null)
    }

    private fun tryCreateGpuInterpreter(file: File): GpuAttempt {
        var delegate: GpuDelegate? = null
        var interpreter: Interpreter? = null
        return try {
            CompatibilityList().use { compatibility ->
                if (!compatibility.isDelegateSupportedOnThisDevice) {
                    return GpuAttempt(null, "GPU delegate unsupported on this device")
                }
                delegate = GpuDelegate(compatibility.bestOptionsForThisDevice.apply {
                    setPrecisionLossAllowed(false)
                })
            }
            interpreter = Interpreter(
                file,
                Interpreter.Options().apply {
                    addDelegate(requireNotNull(delegate))
                    setNumThreads(4)
                },
            )
            interpreter.allocateTensors()
            GpuAttempt(InterpreterSession(interpreter, delegate), null)
        } catch (error: Exception) {
            runCatching { interpreter?.close() }
            runCatching { delegate?.close() }
            GpuAttempt(
                null,
                "GPU FP32 initialization failed: ${error.javaClass.simpleName}: ${error.message}",
            )
        }
    }

    private fun tensorShape(interpreter: Interpreter, expectedWidth: Int, expectedHeight: Int): ModelShape {
        assertEquals(1, interpreter.inputTensorCount)
        assertEquals(1, interpreter.outputTensorCount)
        val input = interpreter.getInputTensor(0)
        val output = interpreter.getOutputTensor(0)
        val inputShape = input.shape()
        val outputShape = output.shape()
        assertEquals("Expected NHWC input rank 4", 4, inputShape.size)
        assertEquals("Expected NHWC output rank 4", 4, outputShape.size)
        assertArrayEquals(intArrayOf(1, expectedHeight, expectedWidth, 3), inputShape)
        assertArrayEquals(intArrayOf(1, expectedHeight, expectedWidth, 7), outputShape)
        return ModelShape(
            width = inputShape[2],
            height = inputShape[1],
            classes = outputShape[3],
            inputShape = inputShape,
            outputShape = outputShape,
            inputType = input.dataType().name,
            outputType = output.dataType().name,
        )
    }

    private fun modelJson(
        hash: String,
        bytes: Long,
        shape: ModelShape,
        backend: String,
        threads: Int,
    ) = JSONObject()
        .put("sha256", hash)
        .put("bytes", bytes)
        .put("inputShape", JSONArray(shape.inputShape.toList()))
        .put("outputShape", JSONArray(shape.outputShape.toList()))
        .put("inputType", shape.inputType)
        .put("outputType", shape.outputType)
        .put("width", shape.width)
        .put("height", shape.height)
        .put("classes", shape.classes)
        .put("backend", backend)
        .put("cpuThreads", threads)
        .put("precisionLossAllowed", false)

    private fun outputBuffer(shape: ModelShape): ByteBuffer =
        ByteBuffer.allocateDirect(shape.outputFloats * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())

    private fun runInto(interpreter: Interpreter, input: ByteBuffer, output: ByteBuffer) {
        input.rewind()
        output.rewind()
        interpreter.run(input, output)
    }

    private fun floatsFrom(output: ByteBuffer, count: Int): FloatArray {
        val requiredBytes = count * Float.SIZE_BYTES
        require(count >= 0 && output.capacity() >= requiredBytes) {
            "Output buffer too small: capacity=${output.capacity()}, required=$requiredBytes"
        }
        val bytes = output.duplicate().order(ByteOrder.nativeOrder()).apply { clear() }
        require(bytes.remaining() >= requiredBytes) {
            "Output buffer has insufficient readable bytes: remaining=${bytes.remaining()}, required=$requiredBytes"
        }
        return FloatArray(count).also(bytes.asFloatBuffer()::get)
    }

    private fun compareOutputs(name: String, cpu: FloatArray, gpu: FloatArray): JSONObject {
        assertEquals(cpu.size, gpu.size)
        var maxAbsDiff = 0.0
        var sumAbsDiff = 0.0
        var classAgreement = 0
        var walkableAgreement = 0
        val classes = VisionContract.MODEL_CLASSES
        for (pixel in 0 until cpu.size / classes) {
            var cpuClass = 0
            var gpuClass = 0
            var cpuBest = Float.NEGATIVE_INFINITY
            var gpuBest = Float.NEGATIVE_INFINITY
            for (classId in 0 until classes) {
                val index = pixel * classes + classId
                val difference = abs(cpu[index] - gpu[index]).toDouble()
                maxAbsDiff = maxOf(maxAbsDiff, difference)
                sumAbsDiff += difference
                if (cpu[index] > cpuBest) {
                    cpuBest = cpu[index]
                    cpuClass = classId
                }
                if (gpu[index] > gpuBest) {
                    gpuBest = gpu[index]
                    gpuClass = classId
                }
            }
            if (cpuClass == gpuClass) classAgreement++
            if (VisionContract.CLASS_WALKABLE[cpuClass] == VisionContract.CLASS_WALKABLE[gpuClass]) {
                walkableAgreement++
            }
        }
        val pixelCount = cpu.size / classes
        return JSONObject()
            .put("input", name)
            .put("maxAbsDiff", maxAbsDiff)
            .put("meanAbsDiff", sumAbsDiff / cpu.size)
            .put("classAgreement", classAgreement.toDouble() / pixelCount)
            .put("walkableAgreement", walkableAgreement.toDouble() / pixelCount)
            .put("pixelCount", pixelCount)
    }

    private fun parityPassed(report: JSONObject): Boolean =
        report.getDouble("maxAbsDiff") <= MAX_PARITY_MAX_ABS_DIFF &&
            report.getDouble("meanAbsDiff") <= MAX_PARITY_MEAN_ABS_DIFF &&
            report.getDouble("classAgreement") >= MIN_PARITY_CLASS_AGREEMENT &&
            report.getDouble("walkableAgreement") >= MIN_PARITY_WALKABLE_AGREEMENT

    private fun parityThresholdJson() = JSONObject()
        .put("maxAbsDiffAtMost", MAX_PARITY_MAX_ABS_DIFF)
        .put("meanAbsDiffAtMost", MAX_PARITY_MEAN_ABS_DIFF)
        .put("classAgreementAtLeast", MIN_PARITY_CLASS_AGREEMENT)
        .put("walkableAgreementAtLeast", MIN_PARITY_WALKABLE_AGREEMENT)

    private fun prepareInput(
        source: Bitmap,
        arm: String,
        sourceOffsetX: Int,
        sourceOffsetY: Int,
        modelWidth: Int,
        modelHeight: Int,
    ): PreparedInput {
        val strategy = when (arm) {
            LEGACY_MODEL -> VisionPreprocessingStrategy.LEGACY_CROP
            ROTATED_LETTERBOX -> VisionPreprocessingStrategy.FULL_FRAME_ROTATED_LANDSCAPE
            UPRIGHT_LETTERBOX -> VisionPreprocessingStrategy.FULL_FRAME_UPRIGHT_LETTERBOX
            PORTRAIT_MODEL -> VisionPreprocessingStrategy.FULL_FRAME_UPRIGHT_LETTERBOX
            else -> error("Unknown evaluation arm: $arm")
        }
        val transform = VisionFramePreprocessor.computeUprightCropRect(
            bufferWidth = source.width,
            bufferHeight = source.height,
            rotationDegrees = 0,
            targetWidth = modelWidth,
            targetHeight = modelHeight,
            strategy = strategy,
        )
        val sourceRect = Rect(
            sourceOffsetX,
            sourceOffsetY,
            sourceOffsetX + source.width,
            sourceOffsetY + source.height,
        )
        val prepared: PreparedInput
        if (arm == LEGACY_MODEL) {
            val crop = Bitmap.createBitmap(
                source,
                transform.left,
                transform.top,
                transform.width,
                transform.height,
            )
            val scaled = Bitmap.createScaledBitmap(crop, modelWidth, modelHeight, true)
            if (scaled !== crop) crop.recycle()
            prepared = PreparedInput(
                bitmap = scaled,
                modelContentRect = Rect(0, 0, modelWidth, modelHeight),
                sourceRect = Rect(
                    sourceOffsetX + transform.left,
                    sourceOffsetY + transform.top,
                    sourceOffsetX + transform.left + transform.width,
                    sourceOffsetY + transform.top + transform.height,
                ),
                productionTransform = transform,
                inverseRotationDegrees = 0,
            )
        } else {
            val rotated = arm == ROTATED_LETTERBOX && transform.isRotatedClockwise
            val modelOriented = if (rotated) rotate(source, 90) else source
            val resized = Bitmap.createScaledBitmap(
                modelOriented,
                transform.contentWidth,
                transform.contentHeight,
                true,
            )
            val canvas = Bitmap.createBitmap(modelWidth, modelHeight, Bitmap.Config.ARGB_8888)
            canvas.eraseColor(Color.BLACK)
            Canvas(canvas).drawBitmap(
                resized,
                transform.contentLeft.toFloat(),
                transform.contentTop.toFloat(),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            if (resized !== modelOriented) resized.recycle()
            if (modelOriented !== source) modelOriented.recycle()
            prepared = PreparedInput(
                bitmap = canvas,
                modelContentRect = Rect(
                    transform.contentLeft,
                    transform.contentTop,
                    transform.contentLeft + transform.contentWidth,
                    transform.contentTop + transform.contentHeight,
                ),
                sourceRect = sourceRect,
                productionTransform = transform,
                inverseRotationDegrees = if (rotated) 270 else 0,
            )
        }
        return prepared
    }

    private fun bitmapToInput(bitmap: Bitmap): ByteBuffer {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val input = ByteBuffer.allocateDirect(pixels.size * 3 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        val floats = input.asFloatBuffer()
        pixels.forEach { pixel ->
            floats.put(Color.red(pixel) / 255.0f)
            floats.put(Color.green(pixel) / 255.0f)
            floats.put(Color.blue(pixel) / 255.0f)
        }
        input.rewind()
        return input
    }

    private fun classMask(values: FloatArray, shape: ModelShape): ByteArray {
        val mask = ByteArray(shape.width * shape.height)
        var offset = 0
        for (pixel in mask.indices) {
            var bestClass = 0
            var bestValue = values[offset]
            require(bestValue.isFinite()) { "Non-finite model output at pixel $pixel, class 0" }
            for (classId in 1 until shape.classes) {
                val value = values[offset + classId]
                require(value.isFinite()) { "Non-finite model output at pixel $pixel, class $classId" }
                if (value > bestValue) {
                    bestValue = value
                    bestClass = classId
                }
            }
            mask[pixel] = bestClass.toByte()
            offset += shape.classes
        }
        return mask
    }

    private fun writePredictionMask(mask: ByteArray, shape: ModelShape, file: File) {
        val pixels = IntArray(mask.size) { index ->
            val classId = mask[index].toInt() and 0xff
            Color.rgb(classId, classId, classId)
        }
        Bitmap.createBitmap(pixels, shape.width, shape.height, Bitmap.Config.ARGB_8888).useBitmap {
            writePng(it, file)
        }
    }

    private fun writePng(bitmap: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        file.outputStream().use { output ->
            assertTrue("Failed to encode ${file.name}", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    private fun decodeVerifiedFixture(
        fixtureDir: File,
        name: String,
        expectedSha: String,
        isLabel: Boolean = false,
    ): Bitmap {
        require(name.matches(Regex("[A-Za-z0-9._-]+"))) { "Unsafe fixture name: $name" }
        val file = fixtureDir.resolve(name)
        assertEquals("Fixture checksum mismatch for $name", expectedSha, sha256(file))
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            ?: throw AssertionError("Unable to decode fixture $name")
        if (isLabel) assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
        return bitmap
    }

    private fun assertPortraitFixtureMatchesSource(
        source: Bitmap,
        portrait: Bitmap,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ) {
        val expected = Bitmap.createBitmap(source, left, top, width, height)
        try {
            assertArrayEquals(bitmapPixels(expected), bitmapPixels(portrait))
        } finally {
            expected.recycle()
        }
    }

    private fun bitmapPixels(bitmap: Bitmap): ByteArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val bytes = ByteArray(pixels.size * 4)
        for (index in pixels.indices) {
            val color = pixels[index]
            val offset = index * 4
            bytes[offset] = Color.red(color).toByte()
            bytes[offset + 1] = Color.green(color).toByte()
            bytes[offset + 2] = Color.blue(color).toByte()
            bytes[offset + 3] = Color.alpha(color).toByte()
        }
        return bytes
    }

    private fun transformJson(transform: UprightCropRect): JSONObject = JSONObject()
        .put("strategy", transform.strategy.name)
        .put("bufferWidth", transform.bufferWidth)
        .put("bufferHeight", transform.bufferHeight)
        .put("rotationDegrees", transform.rotationDegrees)
        .put("uprightWidth", transform.uprightWidth)
        .put("uprightHeight", transform.uprightHeight)
        .put("cropRect", rectArray(Rect(transform.left, transform.top, transform.left + transform.width, transform.top + transform.height)))
        .put("modelContentRect", JSONArray()
            .put(transform.contentLeft)
            .put(transform.contentTop)
            .put(transform.contentWidth)
            .put(transform.contentHeight))
        .put("isRotatedClockwise", transform.isRotatedClockwise)

    private fun rectArray(rect: Rect): JSONArray = JSONArray()
        .put(rect.left)
        .put(rect.top)
        .put(rect.width())
        .put(rect.height())

    private fun inversePredictionForTest(
        prediction: Bitmap,
        modelContentRect: Rect,
        sourceRect: Rect,
        sourceSize: Pair<Int, Int>,
        inverseRotationDegrees: Int,
    ): MappedPrediction {
        val (sourceWidth, sourceHeight) = sourceSize
        val crop = Bitmap.createBitmap(
            prediction,
            modelContentRect.left,
            modelContentRect.top,
            modelContentRect.width(),
            modelContentRect.height(),
        )
        val resizeWidth = if (inverseRotationDegrees in setOf(90, 270)) sourceRect.height() else sourceRect.width()
        val resizeHeight = if (inverseRotationDegrees in setOf(90, 270)) sourceRect.width() else sourceRect.height()
        val scaled = Bitmap.createScaledBitmap(crop, resizeWidth, resizeHeight, false)
        if (scaled !== crop) crop.recycle()
        val mapped = if (inverseRotationDegrees == 0) scaled else rotate(scaled, inverseRotationDegrees)
        if (mapped !== scaled) scaled.recycle()
        val predicted = Array(sourceHeight) { IntArray(sourceWidth) }
        val visible = Array(sourceHeight) { BooleanArray(sourceWidth) }
        val pixels = IntArray(mapped.width * mapped.height)
        mapped.getPixels(pixels, 0, mapped.width, 0, 0, mapped.width, mapped.height)
        for (y in 0 until sourceRect.height()) {
            for (x in 0 until sourceRect.width()) {
                val index = y * mapped.width + x
                predicted[sourceRect.top + y][sourceRect.left + x] = Color.red(pixels[index])
                visible[sourceRect.top + y][sourceRect.left + x] = true
            }
        }
        mapped.recycle()
        return MappedPrediction(predicted, visible)
    }

    private fun asymmetricBitmap(): Bitmap = Bitmap.createBitmap(32, 64, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.BLACK)
        setPixel(0, 0, Color.RED)
        setPixel(width - 1, 0, Color.GREEN)
        setPixel(0, height - 1, Color.BLUE)
        setPixel(width - 1, height - 1, Color.YELLOW)
        for (y in 0 until height) setPixel(width / 3, y, Color.MAGENTA)
        for (x in 0 until width) setPixel(x, height / 4, Color.CYAN)
    }

    private fun rotate(source: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { setRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, false)
    }

    private fun assertColorNear(expected: Int, actual: Int) {
        assertTrue(abs(Color.red(expected) - Color.red(actual)) <= 16)
        assertTrue(abs(Color.green(expected) - Color.green(actual)) <= 16)
        assertTrue(abs(Color.blue(expected) - Color.blue(actual)) <= 16)
    }

    private fun sha256(file: File): String {
        assertTrue("Missing file ${file.absolutePath}", file.isFile)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private inline fun <T> Bitmap.useBitmap(block: (Bitmap) -> T): T {
        return try {
            block(this)
        } finally {
            recycle()
        }
    }

    private companion object {
        const val TAG = "VisionPortraitEval"
        const val LEGACY_MODEL = "legacy_bottom_crop"
        const val ROTATED_LETTERBOX = "portrait_cw90_full_frame_letterbox"
        const val UPRIGHT_LETTERBOX = "upright_full_frame_letterbox"
        const val PORTRAIT_MODEL = "portrait_model_upright_letterbox"
        const val MAX_PARITY_MAX_ABS_DIFF = 0.001
        const val MAX_PARITY_MEAN_ABS_DIFF = 0.0001
        const val MIN_PARITY_CLASS_AGREEMENT = 0.9999
        const val MIN_PARITY_WALKABLE_AGREEMENT = 0.9999
        val ARMS = listOf(LEGACY_MODEL, ROTATED_LETTERBOX, UPRIGHT_LETTERBOX, PORTRAIT_MODEL)
        val LANDSCAPE_CONTROLS = listOf(ROTATED_LETTERBOX, UPRIGHT_LETTERBOX)
    }
}
