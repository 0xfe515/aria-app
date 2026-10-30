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
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate

@RunWith(AndroidJUnit4::class)
class VisionOrientationEvaluationTest {

    @Test
    fun syntheticAsymmetricMarkersVerifyPairedOrientationTransforms() {
        assertArrayEquals(
            intArrayOf(
                0x00000000,
                0x80FF3939.toInt(),
                0x8039FF39.toInt(),
                0x803939FF.toInt(),
                0x80FFFF39.toInt(),
                0x80FF39FF.toInt(),
                0x8039FFFF.toInt(),
            ),
            VisionContract.CLASS_OVERLAY_ARGB,
        )
        val source = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
            setPixel(0, 0, Color.RED)
            setPixel(15, 0, Color.GREEN)
            setPixel(0, 7, Color.BLUE)
            setPixel(15, 7, Color.YELLOW)
            for (y in 1..6) setPixel(6, y, Color.MAGENTA)
            for (x in 1..14) setPixel(x, 5, Color.CYAN)
        }
        val landscapeRotatedControl = prepareInput(
            source,
            PORTRAIT_CW90,
            0,
            0,
            "landscape",
            false,
        )
        val landscapeUprightControl = prepareInput(
            source,
            UPRIGHT_FULL_FRAME,
            0,
            0,
            "landscape",
            false,
        )
        assertEquals(Rect(0, 16, 512, 272), landscapeRotatedControl.modelContentRect)
        assertEquals(
            landscapeRotatedControl.modelContentRect,
            landscapeUprightControl.modelContentRect,
        )
        val landscapeControlPixels = IntArray(512 * 288)
        val landscapeUprightPixels = IntArray(512 * 288)
        landscapeRotatedControl.bitmap.getPixels(
            landscapeControlPixels,
            0,
            512,
            0,
            0,
            512,
            288,
        )
        landscapeUprightControl.bitmap.getPixels(
            landscapeUprightPixels,
            0,
            512,
            0,
            0,
            512,
            288,
        )
        assertArrayEquals("Landscape full-frame controls must be identical", landscapeControlPixels, landscapeUprightPixels)
        assertColorNear(Color.RED, landscapeRotatedControl.bitmap.getPixel(10, 20))
        assertColorNear(Color.GREEN, landscapeRotatedControl.bitmap.getPixel(500, 20))
        assertColorNear(Color.BLUE, landscapeRotatedControl.bitmap.getPixel(10, 267))
        assertColorNear(Color.YELLOW, landscapeRotatedControl.bitmap.getPixel(500, 267))

        val portraitCrop = Bitmap.createBitmap(source, 6, 0, 4, 8)
        assertEquals(Color.MAGENTA, portraitCrop.getPixel(0, 3))
        val portraitLegacy = prepareInput(
            portraitCrop,
            LEGACY_BOTTOM_CROP,
            6,
            0,
            "portrait_center_crop",
            true,
        )
        val portraitRotated = prepareInput(
            portraitCrop,
            PORTRAIT_CW90,
            6,
            0,
            "portrait_center_crop",
            true,
        )
        val portraitUpright = prepareInput(
            portraitCrop,
            UPRIGHT_FULL_FRAME,
            6,
            0,
            "portrait_center_crop",
            true,
        )
        assertEquals(Rect(6, 6, 10, 8), portraitLegacy.sourceRect)
        assertEquals(Rect(6, 0, 10, 8), portraitRotated.sourceRect)
        assertEquals(270, portraitRotated.inverseRotationDegrees)
        assertEquals(Rect(6, 0, 10, 8), portraitUpright.sourceRect)
        assertEquals(Rect(0, 16, 512, 272), portraitRotated.modelContentRect)
        assertEquals(Rect(184, 0, 328, 288), portraitUpright.modelContentRect)
        assertEquals(Rect(0, 0, 512, 288), portraitLegacy.modelContentRect)

        val rotatedPortrait = rotate(portraitCrop, 90)
        val restoredPortrait = rotate(rotatedPortrait, 270)
        val portraitPixels = IntArray(portraitCrop.width * portraitCrop.height)
        val restoredPixels = IntArray(portraitPixels.size)
        portraitCrop.getPixels(portraitPixels, 0, portraitCrop.width, 0, 0, portraitCrop.width, portraitCrop.height)
        restoredPortrait.getPixels(
            restoredPixels,
            0,
            restoredPortrait.width,
            0,
            0,
            restoredPortrait.width,
            restoredPortrait.height,
        )
        assertArrayEquals("Portrait objects must remain upright before the rotated arm", portraitPixels, restoredPixels)

        restoredPortrait.recycle()
        rotatedPortrait.recycle()
        portraitLegacy.bitmap.recycle()
        portraitRotated.bitmap.recycle()
        portraitUpright.bitmap.recycle()
        portraitCrop.recycle()
        landscapeRotatedControl.bitmap.recycle()
        landscapeUprightControl.bitmap.recycle()
        source.recycle()
    }

    @Test
    fun actualOptimizedModelRunsAllStrategiesOnCamVidFixtures() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val evaluationRoot = requireNotNull(context.getExternalFilesDir(null)) {
            "App-specific external storage is unavailable"
        }.resolve("vision-orientation-evaluation")
        val fixtureDir = evaluationRoot.resolve("fixtures")
        val resultDir = evaluationRoot.resolve("results").apply { mkdirs() }
        val fixtureManifest = fixtureDir.resolve("manifest.json")
        assertTrue(
            "Missing staged fixtures at ${fixtureManifest.absolutePath}; coordinator must stage build/vision-orientation-evaluation/fixtures",
            fixtureManifest.isFile,
        )
        val manifest = JSONObject(fixtureManifest.readText(Charsets.UTF_8))
        assertEquals(2, manifest.getInt("schemaVersion"))
        val fixtures = manifest.getJSONArray("frames")
        assertEquals("The evaluation requires exactly eight source scenes", 8, fixtures.length())

        val modelFile = File(context.cacheDir, VisionContract.LEGACY_OPTIMIZED_ASSET_MODEL_NAME)
        context.assets.open(VisionContract.LEGACY_OPTIMIZED_ASSET_MODEL_PATH).use { input ->
            modelFile.outputStream().use { output -> input.copyTo(output) }
        }
        assertEquals(VisionContract.LEGACY_OPTIMIZED_ASSET_MODEL_SHA256, sha256(modelFile))

        val runner = LegacyLandscapeRunner(modelFile, VisionContract.DEFAULT_CPU_THREADS)
        try {





            val frames = JSONArray()
            val runWarmups = 3
            val measuredRuns = 5
            for (frameIndex in 0 until fixtures.length()) {
                val fixture = fixtures.getJSONObject(frameIndex)
                val baseFrameId = fixture.getString("id")
                requireSafeFilename(fixture.getString("sourceImage"))
                requireSafeFilename(fixture.getString("sourceLabel"))
                requireSafeFilename(fixture.getString("portraitImage"))
                requireSafeFilename(fixture.getString("portraitLabel"))
                requireSafeFilename(baseFrameId)
                val imageFile = fixtureDir.resolve(fixture.getString("sourceImage"))
                val labelFile = fixtureDir.resolve(fixture.getString("sourceLabel"))
                val portraitImageFile = fixtureDir.resolve(fixture.getString("portraitImage"))
                val portraitLabelFile = fixtureDir.resolve(fixture.getString("portraitLabel"))
                assertEquals(fixture.getString("imageSha256"), sha256(imageFile))
                assertEquals(fixture.getString("labelSha256"), sha256(labelFile))
                assertEquals(fixture.getString("portraitImageSha256"), sha256(portraitImageFile))
                assertEquals(fixture.getString("portraitLabelSha256"), sha256(portraitLabelFile))
                val source = BitmapFactory.decodeFile(imageFile.absolutePath)
                    ?: throw AssertionError("Unable to decode ${fixture.getString("sourceImage")}")
                assertEquals(fixture.getInt("width"), source.width)
                assertEquals(fixture.getInt("height"), source.height)
                assertTrue("Source scene must be landscape", source.width > source.height)
                val labelBitmap = BitmapFactory.decodeFile(portraitLabelFile.absolutePath)
                    ?: throw AssertionError("Unable to decode ${fixture.getString("portraitLabel")}")
                val portrait = BitmapFactory.decodeFile(portraitImageFile.absolutePath)
                    ?: throw AssertionError("Unable to decode ${fixture.getString("portraitImage")}")
                val cropRect = fixture.getJSONArray("portraitCropRect")
                assertEquals(4, cropRect.length())
                val cropLeft = cropRect.getInt(0)
                val cropTop = cropRect.getInt(1)
                val cropWidth = cropRect.getInt(2)
                val cropHeight = cropRect.getInt(3)
                assertEquals((source.height * 9) / 16, cropWidth)
                assertEquals((source.width - cropWidth) / 2, cropLeft)
                assertEquals(0, cropTop)
                assertEquals(source.height, cropHeight)
                assertEquals(cropWidth, portrait.width)
                assertEquals(cropHeight, portrait.height)
                assertTrue("Portrait sample must remain upright", portrait.height > portrait.width)
                assertEquals(cropWidth, labelBitmap.width)
                assertEquals(cropHeight, labelBitmap.height)

                val expectedPortrait = Bitmap.createBitmap(
                    source,
                    cropLeft,
                    cropTop,
                    cropWidth,
                    cropHeight,
                )
                val expectedPixels = IntArray(cropWidth * cropHeight)
                val actualPixels = IntArray(cropWidth * cropHeight)
                expectedPortrait.getPixels(expectedPixels, 0, cropWidth, 0, 0, cropWidth, cropHeight)
                portrait.getPixels(actualPixels, 0, cropWidth, 0, 0, cropWidth, cropHeight)
                assertArrayEquals(
                    "Portrait fixture must be the upright center crop, not a rotated scene",
                    expectedPixels,
                    actualPixels,
                )
                expectedPortrait.recycle()
                labelBitmap.recycle()

                val samples = listOf(
                    OrientationSample("landscape", source, 0, 0, false),
                    OrientationSample(
                        "portrait_center_crop",
                        portrait,
                        cropLeft,
                        cropTop,
                        true,
                    ),
                )
                for (sample in samples) {
                    val fixtureId = "$baseFrameId--${sample.orientation}"
                    val frameResult = JSONObject()
                        .put("id", fixtureId)
                        .put("baseFrameId", baseFrameId)
                        .put("orientation", sample.orientation)
                        .put("orientationDescription", if (sample.orientationIsDerived) {
                            "upright 9:16 center crop; derived without rotating the source"
                        } else {
                            "original landscape source"
                        })
                        .put("sequence", fixture.getString("sequence"))
                        .put("split", fixture.getString("split"))
                        .put(
                            "orientationSourceRect",
                            rectArray(
                                Rect(
                                    sample.sourceOffsetX,
                                    sample.sourceOffsetY,
                                    sample.sourceOffsetX + sample.bitmap.width,
                                    sample.sourceOffsetY + sample.bitmap.height,
                                ),
                            ),
                        )
                        .put("sourceWidth", source.width)
                        .put("sourceHeight", source.height)
                    val strategies = JSONArray()

                    for (strategy in STRATEGIES) {
                        val transformStart = SystemClock.elapsedRealtimeNanos()
                        val prepared = prepareInput(
                            sample.bitmap,
                            strategy,
                            sample.sourceOffsetX,
                            sample.sourceOffsetY,
                            sample.orientation,
                            sample.orientationIsDerived,
                        )
                        val inputBuffer = bitmapToInput(prepared.bitmap)
                        val transformNanos = SystemClock.elapsedRealtimeNanos() - transformStart
                        val inputName = "$fixtureId--$strategy-input.png"
                        val predictionName = "$fixtureId--$strategy-prediction.png"
                        writePng(prepared.bitmap, resultDir.resolve(inputName))
                        val probabilities = FloatArray(VisionContract.OUTPUT_FLOATS)
                        repeat(runWarmups) {
                            runner.runDirectRawProbabilities(inputBuffer, probabilities)
                        }
                        val invokeTimes = JSONArray()
                        repeat(measuredRuns) {
                            invokeTimes.put(runner.runDirectRawProbabilities(inputBuffer, probabilities))
                        }
                        val productionClassMask = ByteArray(VisionContract.INPUT_PIXELS)
                        val productionDecodeTimes = JSONArray()
                        var productionSummary: VisionSegmentationSummary? = null
                        repeat(measuredRuns) {
                            val decodeStart = SystemClock.elapsedRealtimeNanos()
                            productionSummary = LiteRtVisionSegmenter.decodeArgmaxAndMask(
                                probabilities = probabilities,
                                outClassMask = productionClassMask,
                                transform = prepared.productionTransform,
                                modelWidth = VisionContract.MODEL_WIDTH,
                                modelHeight = VisionContract.MODEL_HEIGHT,
                            )
                            productionDecodeTimes.put(
                                SystemClock.elapsedRealtimeNanos() - decodeStart,
                            )
                        }
                        assertEquals(
                            VisionContract.INPUT_PIXELS,
                            requireNotNull(productionSummary).classPixelCounts.sum(),
                        )
                        writePredictionMask(probabilities, resultDir.resolve(predictionName))
                        strategies.put(
                            JSONObject()
                                .put("strategy", strategy)
                                .put("acquisitionOrientation", prepared.acquisitionOrientation)
                                .put("orientationIsDerived", prepared.orientationIsDerived)
                                .put("transformNanos", transformNanos)
                                .put("invokeTimesNanos", invokeTimes)
                                .put("inputImage", inputName)
                                .put("predictionImage", predictionName)
                                .put("modelContentRect", rectArray(prepared.modelContentRect))
                                .put("sourceRect", rectArray(prepared.sourceRect))
                                .put("productionTransform", uprightCropRectJson(prepared.productionTransform))
                                .put("productionDecodeArgmaxAndMaskTimesNanos", productionDecodeTimes)
                                .put("inverseRotationDegrees", prepared.inverseRotationDegrees),
                        )
                        prepared.bitmap.recycle()
                    }
                    frameResult.put("strategies", strategies)
                    frames.put(frameResult)
                }
                portrait.recycle()
                source.recycle()
            }

            val actualBackend = runner.configuredBackend
            val run = JSONObject()
                .put("schemaVersion", 2)
                .put("deviceModel", Build.MODEL)
                .put("sdkInt", Build.VERSION.SDK_INT)
                .put("requestedBackend", "LiteRT GPU FP32; CPU fallback is reported if GPU initialization fails")
                .put("actualBackend", actualBackend.name)
                .put("backendDescription", runner.activeBackendDescription)
                .put(
                    "modelInvokeTimingScope",
                    "LiteRT interpreter invocation from runDirectRawProbabilities; excludes output decoding",
                )
                .put(
                    "transformTimingScope",
                    "Bitmap fixture geometry and normalized RGB float conversion; excludes CameraX YUV preprocessing",
                )
                .put(
                    "productionDecodeTimingScope",
                    "LiteRtVisionSegmenter.decodeArgmaxAndMask on actual model probabilities with no overlay bitmap; excludes output buffer copy and bitmap creation",
                )
                .put("modelName", VisionContract.LEGACY_OPTIMIZED_ASSET_MODEL_NAME)
                .put("modelSha256", VisionContract.LEGACY_OPTIMIZED_ASSET_MODEL_SHA256)
                .put("modelBytes", modelFile.length())
                .put("precisionLossAllowed", false)
                .put("warmupRunsPerInput", runWarmups)
                .put("measuredRunsPerInput", measuredRuns)
                .put("sourceSceneCount", fixtures.length())
                .put("orientationFixtureCount", frames.length())
                .put("modelConditionCount", frames.length() * STRATEGIES.size)
                .put("frames", frames)
            resultDir.resolve("run.json").writeText(run.toString(2) + "\n", Charsets.UTF_8)

            assertEquals("Each scene must run at both orientations", fixtures.length() * 2, frames.length())
            assertEquals("The paired comparison must contain 48 conditions", 48, frames.length() * STRATEGIES.size)
            assertTrue("Backend description must identify actual execution backend", runner.activeBackendDescription.isNotBlank())
            assertTrue(
                "The device run must use the optimized bundled model",
                run.getString("modelName") == VisionContract.LEGACY_OPTIMIZED_ASSET_MODEL_NAME,
            )
        } finally {
            runner.close()
        }
    }

    private data class PreparedInput(
        val bitmap: Bitmap,
        val modelContentRect: Rect,
        val sourceRect: Rect,
        val productionTransform: UprightCropRect,
        val acquisitionOrientation: String,
        val orientationIsDerived: Boolean,
        val inverseRotationDegrees: Int = 0,
    )

    private data class OrientationSample(
        val orientation: String,
        val bitmap: Bitmap,
        val sourceOffsetX: Int,
        val sourceOffsetY: Int,
        val orientationIsDerived: Boolean,
    )

    private fun prepareInput(
        source: Bitmap,
        strategy: String,
        sourceOffsetX: Int,
        sourceOffsetY: Int,
        orientation: String,
        orientationIsDerived: Boolean,
    ): PreparedInput {
        val preprocessingStrategy = when (strategy) {
            LEGACY_BOTTOM_CROP -> VisionPreprocessingStrategy.LEGACY_CROP
            PORTRAIT_CW90 -> VisionPreprocessingStrategy.FULL_FRAME_ROTATED_LANDSCAPE
            UPRIGHT_FULL_FRAME -> VisionPreprocessingStrategy.FULL_FRAME_UPRIGHT_LETTERBOX
            else -> throw IllegalArgumentException("Unknown strategy: $strategy")
        }
        val productionTransform = VisionFramePreprocessor.computeUprightCropRect(
            bufferWidth = source.width,
            bufferHeight = source.height,
            rotationDegrees = 0,
            strategy = preprocessingStrategy,
        )
        val sourceRect = Rect(
            sourceOffsetX,
            sourceOffsetY,
            sourceOffsetX + source.width,
            sourceOffsetY + source.height,
        )
        return when (strategy) {
            LEGACY_BOTTOM_CROP -> {
                val crop = Bitmap.createBitmap(
                    source,
                    productionTransform.left,
                    productionTransform.top,
                    productionTransform.width,
                    productionTransform.height,
                )
                val scaled = Bitmap.createScaledBitmap(
                    crop,
                    VisionContract.MODEL_WIDTH,
                    VisionContract.MODEL_HEIGHT,
                    true,
                )
                if (scaled !== crop) crop.recycle()
                PreparedInput(
                    bitmap = scaled,
                    modelContentRect = Rect(0, 0, VisionContract.MODEL_WIDTH, VisionContract.MODEL_HEIGHT),
                    sourceRect = Rect(
                        sourceRect.left + productionTransform.left,
                        sourceRect.top + productionTransform.top,
                        sourceRect.left + productionTransform.left + productionTransform.width,
                        sourceRect.top + productionTransform.top + productionTransform.height,
                    ),
                    productionTransform = productionTransform,
                    acquisitionOrientation = orientation,
                    orientationIsDerived = orientationIsDerived,
                )
            }
            PORTRAIT_CW90 -> {
                val isRotated = productionTransform.isRotatedClockwise
                val modelOriented = if (isRotated) rotate(source, 90) else source
                val prepared = createLetterboxed(
                    modelOriented,
                    sourceRect,
                    orientation,
                    orientationIsDerived,
                    productionTransform,
                    inverseRotationDegrees = if (isRotated) 270 else 0,
                )
                if (modelOriented !== source) modelOriented.recycle()
                prepared
            }
            UPRIGHT_FULL_FRAME -> {
                createLetterboxed(
                    source,
                    sourceRect,
                    orientation,
                    orientationIsDerived,
                    productionTransform,
                )
            }
            else -> throw IllegalArgumentException("Unknown strategy: $strategy")
        }
    }

    private fun createLetterboxed(
        source: Bitmap,
        sourceRect: Rect,
        orientation: String,
        orientationIsDerived: Boolean,
        productionTransform: UprightCropRect,
        inverseRotationDegrees: Int = 0,
    ): PreparedInput {
        val targetWidth = VisionContract.MODEL_WIDTH
        val targetHeight = VisionContract.MODEL_HEIGHT
        val expectedWidth = if (productionTransform.isRotatedClockwise) {
            productionTransform.uprightHeight
        } else {
            productionTransform.uprightWidth
        }
        val expectedHeight = if (productionTransform.isRotatedClockwise) {
            productionTransform.uprightWidth
        } else {
            productionTransform.uprightHeight
        }
        assertEquals(expectedWidth, source.width)
        assertEquals(expectedHeight, source.height)
        val resized = Bitmap.createScaledBitmap(
            source,
            productionTransform.contentWidth,
            productionTransform.contentHeight,
            true,
        )
        val canvasBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        canvasBitmap.eraseColor(Color.BLACK)
        Canvas(canvasBitmap).drawBitmap(
            resized,
            productionTransform.contentLeft.toFloat(),
            productionTransform.contentTop.toFloat(),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        if (resized !== source) resized.recycle()
        return PreparedInput(
            bitmap = canvasBitmap,
            modelContentRect = Rect(
                productionTransform.contentLeft,
                productionTransform.contentTop,
                productionTransform.contentLeft + productionTransform.contentWidth,
                productionTransform.contentTop + productionTransform.contentHeight,
            ),
            sourceRect = Rect(sourceRect),
            productionTransform = productionTransform,
            acquisitionOrientation = orientation,
            orientationIsDerived = orientationIsDerived,
            inverseRotationDegrees = inverseRotationDegrees,
        )
    }

    private fun rotate(source: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { setRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, false)
    }

    private fun bitmapToInput(bitmap: Bitmap): ByteBuffer {
        assertEquals(VisionContract.MODEL_WIDTH, bitmap.width)
        assertEquals(VisionContract.MODEL_HEIGHT, bitmap.height)
        val pixels = IntArray(VisionContract.INPUT_PIXELS)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val input = ByteBuffer.allocateDirect(VisionContract.INPUT_BYTES).order(ByteOrder.nativeOrder())
        val floats = input.asFloatBuffer()
        for (pixel in pixels) {
            floats.put(Color.red(pixel) / 255.0f)
            floats.put(Color.green(pixel) / 255.0f)
            floats.put(Color.blue(pixel) / 255.0f)
        }
        input.rewind()
        return input
    }

    private fun writePredictionMask(probabilities: FloatArray, outputFile: File) {
        val pixels = IntArray(VisionContract.INPUT_PIXELS)
        var offset = 0
        for (pixel in pixels.indices) {
            var bestClass = 0
            var bestValue = probabilities[offset]
            require(bestValue.isFinite()) { "Non-finite model output at pixel $pixel, class 0" }
            for (classId in 1 until VisionContract.MODEL_CLASSES) {
                val value = probabilities[offset + classId]
                require(value.isFinite()) { "Non-finite model output at pixel $pixel, class $classId" }
                if (value > bestValue) {
                    bestValue = value
                    bestClass = classId
                }
            }
            pixels[pixel] = Color.rgb(bestClass, bestClass, bestClass)
            offset += VisionContract.MODEL_CLASSES
        }
        Bitmap.createBitmap(
            pixels,
            VisionContract.MODEL_WIDTH,
            VisionContract.MODEL_HEIGHT,
            Bitmap.Config.ARGB_8888,
        ).useBitmap { writePng(it, outputFile) }
    }

    private inline fun <T> Bitmap.useBitmap(block: (Bitmap) -> T): T {
        return try {
            block(this)
        } finally {
            recycle()
        }
    }

    private fun writePng(bitmap: Bitmap, file: File) {
        file.outputStream().use { output ->
            assertTrue("Failed to encode ${file.name}", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    private fun rectArray(rect: Rect): JSONArray = JSONArray()
        .put(rect.left)
        .put(rect.top)
        .put(rect.width())
        .put(rect.height())

    private fun uprightCropRectJson(transform: UprightCropRect): JSONObject = JSONObject()
        .put("strategy", transform.strategy.name)
        .put("bufferWidth", transform.bufferWidth)
        .put("bufferHeight", transform.bufferHeight)
        .put("rotationDegrees", transform.rotationDegrees)
        .put("uprightWidth", transform.uprightWidth)
        .put("uprightHeight", transform.uprightHeight)
        .put("cropRect", rectArray(Rect(
            transform.left,
            transform.top,
            transform.left + transform.width,
            transform.top + transform.height,
        )))
        .put("modelContentRect", JSONArray()
            .put(transform.contentLeft)
            .put(transform.contentTop)
            .put(transform.contentWidth)
            .put(transform.contentHeight))
        .put("isRotatedClockwise", transform.isRotatedClockwise)

    private fun assertColorNear(expected: Int, actual: Int) {
        assertTrue(kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= 16)
        assertTrue(kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= 16)
        assertTrue(kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) <= 16)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun requireSafeFilename(filename: String) {
        require(filename.matches(Regex("[A-Za-z0-9._-]+"))) { "Unsafe fixture filename: $filename" }
    }

    private class LegacyLandscapeRunner(
        modelFile: File,
        threads: Int = VisionContract.DEFAULT_CPU_THREADS,
    ) : AutoCloseable {
        private val mapped = LiteRtVisionSegmenter.mapReadOnly(modelFile)
        private var delegate: GpuDelegate? = null
        val interpreter: Interpreter
        val configuredBackend: VisionBackendType
        val activeBackendDescription: String
        private val outputBuffer = ByteBuffer.allocateDirect(VisionContract.OUTPUT_BYTES).order(ByteOrder.nativeOrder())

        init {
            var candidateDelegate: GpuDelegate? = null
            var candidateInterpreter: Interpreter? = null
            var backendType = VisionBackendType.CPU
            var backendDesc = "LiteRT CPU FP32 (XNNPACK, ${threads}스레드)"

            try {
                val isSupported: Boolean
                val delegateOptions: GpuDelegate.Options
                CompatibilityList().use { compat ->
                    isSupported = compat.isDelegateSupportedOnThisDevice
                    delegateOptions = if (isSupported) compat.bestOptionsForThisDevice else GpuDelegate.Options()
                }
                if (isSupported) {
                    delegateOptions.setPrecisionLossAllowed(false)
                    candidateDelegate = GpuDelegate(delegateOptions)
                    val options = Interpreter.Options().apply {
                        addDelegate(candidateDelegate)
                        setNumThreads(threads)
                    }
                    candidateInterpreter = Interpreter(mapped, options)
                    candidateInterpreter.allocateTensors()
                    backendType = VisionBackendType.GPU
                    backendDesc = "LiteRT GPU FP32 (GpuDelegate · 잔여 CPU ${threads}스레드)"
                }
            } catch (_: Throwable) {
                candidateInterpreter?.close()
                candidateDelegate?.close()
                candidateInterpreter = null
                candidateDelegate = null
            }

            if (candidateInterpreter == null) {
                val options = Interpreter.Options().apply {
                    setNumThreads(threads)
                    setUseXNNPACK(true)
                }
                candidateInterpreter = Interpreter(mapped, options)
                candidateInterpreter.allocateTensors()
            }

            interpreter = candidateInterpreter
            delegate = candidateDelegate
            configuredBackend = backendType
            activeBackendDescription = backendDesc
        }

        fun runDirectRawProbabilities(inputBuffer: ByteBuffer, destinationArray: FloatArray): Long {
            inputBuffer.rewind()
            outputBuffer.clear()
            val start = SystemClock.elapsedRealtimeNanos()
            interpreter.run(inputBuffer, outputBuffer)
            val duration = (SystemClock.elapsedRealtimeNanos() - start).coerceAtLeast(0L)
            val dup = outputBuffer.duplicate().order(ByteOrder.nativeOrder())
            dup.clear()
            dup.asFloatBuffer().get(destinationArray, 0, VisionContract.OUTPUT_FLOATS)
            return duration
        }

        override fun close() {
            interpreter.close()
            delegate?.close()
        }
    }

    private companion object {
        const val LEGACY_BOTTOM_CROP = "legacy_bottom_crop"
        const val PORTRAIT_CW90 = "portrait_cw90_full_frame_letterbox"
        const val UPRIGHT_FULL_FRAME = "upright_full_frame_letterbox"
        val STRATEGIES = listOf(LEGACY_BOTTOM_CROP, PORTRAIT_CW90, UPRIGHT_FULL_FRAME)
    }
}
