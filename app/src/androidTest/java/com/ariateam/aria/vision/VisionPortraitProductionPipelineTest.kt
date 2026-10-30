package com.ariateam.aria.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class VisionPortraitProductionPipelineTest {

    private companion object {
        const val TAG = "VisionPortraitPipeline"
    }

    @Test
    fun productionAssetContractAndProvisioning_usesNativePortraitTensors() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = VisionModelStore(context, restoreOnInit = false)
        try {
            store.clearModelSync()
            val state = store.provisionDefaultAssetModelSync()
            assertTrue("Expected Ready state, got $state", state is VisionModelState.Ready)
            val ready = state as VisionModelState.Ready

            assertEquals(VisionContract.DEFAULT_ASSET_MODEL_NAME, ready.metadata.displayName)
            assertEquals(VisionContract.DEFAULT_ASSET_MODEL_BYTES, ready.metadata.sizeBytes)
            assertEquals(VisionContract.DEFAULT_ASSET_MODEL_SHA256, ready.metadata.sha256)
            assertTrue("Segmenter must be ready", store.segmenter.isReady)

            val activeFile = store.activeModelFile
            assertTrue(activeFile.isFile)
            assertEquals(VisionContract.DEFAULT_ASSET_MODEL_SHA256, sha256(activeFile.readBytes()))

            // Verify live interpreter spec matches native portrait contract [1, 512, 288, C]
            val mapped = LiteRtVisionSegmenter.mapReadOnly(activeFile)
            val options = org.tensorflow.lite.Interpreter.Options().apply {
                setNumThreads(1)
                setUseXNNPACK(true)
            }
            org.tensorflow.lite.Interpreter(mapped, options).use { interpreter ->
                assertEquals(1, interpreter.inputTensorCount)
                assertEquals(1, interpreter.outputTensorCount)
                assertArrayEquals(
                    intArrayOf(1, 512, 288, 3),
                    interpreter.getInputTensor(0).shape(),
                )
                assertEquals("FLOAT32", interpreter.getInputTensor(0).dataType().name)
                assertArrayEquals(
                    intArrayOf(1, 512, 288, 7),
                    interpreter.getOutputTensor(0).shape(),
                )
                assertEquals("FLOAT32", interpreter.getOutputTensor(0).dataType().name)
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun productionPreprocessorAndSegmenter_realFixtureOrSynthetic_preservesUprightFullFrame() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = VisionModelStore(context, restoreOnInit = false)
        try {
            val state = store.provisionDefaultAssetModelSync()
            assertTrue(state is VisionModelState.Ready)

            val preprocessor = VisionFramePreprocessor(
                targetWidth = VisionContract.MODEL_INPUT_WIDTH,
                targetHeight = VisionContract.MODEL_INPUT_HEIGHT,
                defaultStrategy = VisionPreprocessingStrategy.FULL_FRAME_UPRIGHT_LETTERBOX,
            )

            // Look for staged CamVid portrait fixture or fall back to high-fidelity synthetic upright scene
            val externalRoot = context.getExternalFilesDir(null)
                ?: File("/sdcard/Android/data/com.ariateam.aria/files")
            val fixtureDir = externalRoot.resolve("vision-orientation-evaluation/fixtures")
            val candidateRealFile = fixtureDir.resolve("0001TP_008760--portrait.png")

            val sourceBitmap: Bitmap = if (candidateRealFile.isFile) {
                Log.i(TAG, "Loading real staged CamVid portrait fixture: ${candidateRealFile.absolutePath}")
                BitmapFactory.decodeFile(candidateRealFile.absolutePath)
                    ?: createSyntheticUprightRoadBitmap(480, 640)
            } else {
                Log.i(TAG, "Staged fixture not found at ${candidateRealFile.absolutePath}; using synthetic upright road scene")
                createSyntheticUprightRoadBitmap(480, 640)
            }

            val uprightW = sourceBitmap.width
            val uprightH = sourceBitmap.height

            // In portrait phone orientation with sensor at 90 deg: buffer is uprightH x uprightW (640x480), rotation 90
            val bufferW = uprightH
            val bufferH = uprightW
            val rotation = 90

            // Convert upright Bitmap to sensor YUV buffers rotated 90 deg clockwise back to sensor orientation
            val (yBytes, uBytes, vBytes) = bitmapToSensorYuv(sourceBitmap, rotation)

            val processedCrop = preprocessor.preprocessYuv420Arrays(
                bufferWidth = bufferW,
                bufferHeight = bufferH,
                rotationDegrees = rotation,
                yBytes = yBytes,
                yRowStride = bufferW,
                yPixelStride = 1,
                uBytes = uBytes,
                uRowStride = (bufferW + 1) / 2,
                uPixelStride = 1,
                vBytes = vBytes,
                vRowStride = (bufferW + 1) / 2,
                vPixelStride = 1,
                strategy = VisionPreprocessingStrategy.FULL_FRAME_UPRIGHT_LETTERBOX,
            )

            assertTrue("Strategy must be full frame", processedCrop.isFullFrame)
            assertFalse("Must NOT rotate portrait frames clockwise", processedCrop.isRotatedClockwise)
            assertEquals(uprightW, processedCrop.uprightWidth)
            assertEquals(uprightH, processedCrop.uprightHeight)

            // Execute through production segmenter with transform
            val output = store.segmenter.runIfLoaded(
                inputByteBuffer = preprocessor.inputByteBuffer,
                produceOverlayBitmap = true,
                transform = processedCrop,
            )
            assertNotNull("Inference output must not be null", output)
            val nonNullOutput = output!!

            // Verify canonical output dimensions 512x288
            val bitmap = nonNullOutput.overlayBitmap
            assertNotNull("Overlay bitmap must be produced", bitmap)
            assertEquals(VisionContract.MODEL_WIDTH, bitmap!!.width)
            assertEquals(VisionContract.MODEL_HEIGHT, bitmap.height)
            assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)

            // Verify summary and ROI
            val summary = nonNullOutput.summary
            assertEquals(VisionContract.MODEL_CLASSES, summary.classPixelCounts.size)
            assertEquals(
                VisionContract.MODEL_WIDTH * VisionContract.MODEL_HEIGHT,
                summary.classPixelCounts.sum(),
            )
            assertTrue("Left walkable ratio in bounds", summary.roiLeftWalkableRatio in 0f..1f)
            assertTrue("Center walkable ratio in bounds", summary.roiCenterWalkableRatio in 0f..1f)
            assertTrue("Right walkable ratio in bounds", summary.roiRightWalkableRatio in 0f..1f)

            sourceBitmap.recycle()
        } finally {
            store.close()
        }
    }

    @Test
    fun productionPipeline_cpuGpuParity_onDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val diagnosticSegmenter = LiteRtVisionSegmenter(
            inferencePolicy = DiagnosticInferencePolicy(allowCpuReferenceForTesting = true),
        )
        val store = VisionModelStore(context, segmenter = diagnosticSegmenter, restoreOnInit = false)
        try {
            val state = store.provisionDefaultAssetModelSync()
            assertTrue(state is VisionModelState.Ready)

            val inputBuffer = ByteBuffer.allocateDirect(VisionContract.INPUT_BYTES)
                .order(ByteOrder.nativeOrder())
            val floatBuf = inputBuffer.asFloatBuffer()
            for (i in 0 until VisionContract.INPUT_FLOATS) {
                floatBuf.put((i % 256) / 255.0f)
            }

            // 1. Run CPU
            store.segmenter.configureBackend(VisionBackendType.CPU, threads = 4)
            val cpuProbs = FloatArray(VisionContract.OUTPUT_FLOATS)
            val cpuInvokeNanos = store.segmenter.runDirectRawProbabilities(inputBuffer, cpuProbs)

            val cpuMask = ByteArray(VisionContract.MODEL_WIDTH * VisionContract.MODEL_HEIGHT)
            val cpuSummary = LiteRtVisionSegmenter.decodeArgmaxAndMask(
                probabilities = cpuProbs,
                outClassMask = cpuMask,
                modelWidth = VisionContract.MODEL_INPUT_WIDTH,
                modelHeight = VisionContract.MODEL_INPUT_HEIGHT,
            )

            // 2. Configure GPU
            store.segmenter.configureBackend(VisionBackendType.GPU, threads = 4)
            val backendDesc = store.segmenter.activeBackendDescription
            val isGpuActive = backendDesc.contains("GpuDelegate") && !backendDesc.contains("폴백")

            // Strict GPU execution proof: if GPU is not active, skip with explicit diagnostic reason (never pass CPU-vs-CPU)
            assumeTrue("LiteRT GPU Delegate not active on this device: $backendDesc", isGpuActive)

            val gpuProbs = FloatArray(VisionContract.OUTPUT_FLOATS)
            val gpuInvokeNanos = store.segmenter.runDirectRawProbabilities(inputBuffer, gpuProbs)

            val gpuMask = ByteArray(VisionContract.MODEL_WIDTH * VisionContract.MODEL_HEIGHT)
            val gpuSummary = LiteRtVisionSegmenter.decodeArgmaxAndMask(
                probabilities = gpuProbs,
                outClassMask = gpuMask,
                modelWidth = VisionContract.MODEL_INPUT_WIDTH,
                modelHeight = VisionContract.MODEL_INPUT_HEIGHT,
            )

            // Calculate output float diffs
            var maxDiff = 0f
            var sumDiff = 0.0
            for (i in 0 until VisionContract.OUTPUT_FLOATS) {
                val diff = abs(cpuProbs[i] - gpuProbs[i])
                if (diff > maxDiff) maxDiff = diff
                sumDiff += diff
            }
            val meanDiff = (sumDiff / VisionContract.OUTPUT_FLOATS).toFloat()

            // Calculate class and walkable agreement on canonical 512x288 grid
            var matchingClassPixels = 0
            var matchingWalkablePixels = 0
            val totalPixels = cpuMask.size
            val walkableFlags = VisionContract.CLASS_WALKABLE
            for (p in 0 until totalPixels) {
                val cpuClass = cpuMask[p].toInt() and 0xFF
                val gpuClass = gpuMask[p].toInt() and 0xFF
                if (cpuClass == gpuClass) matchingClassPixels++
                if (walkableFlags[cpuClass] == walkableFlags[gpuClass]) matchingWalkablePixels++
            }
            val classAgreement = (matchingClassPixels.toDouble() / totalPixels) * 100.0
            val walkableAgreement = (matchingWalkablePixels.toDouble() / totalPixels) * 100.0

            // Descriptive thermal status check
            val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus ?: -1
            } else -1

            Log.i(
                TAG,
                "GPU Parity Verified: maxDiff=$maxDiff, meanDiff=$meanDiff, classAgreement=$classAgreement%, " +
                    "walkableAgreement=$walkableAgreement%, cpuDuration=${cpuInvokeNanos / 1_000_000}ms, " +
                    "gpuDuration=${gpuInvokeNanos / 1_000_000}ms, thermalStatus=$thermalStatus",
            )

            // Strict GPU FP32 parity tolerances
            assertTrue("Max output diff ($maxDiff) must be <= 0.001", maxDiff <= 0.001f)
            assertTrue("Mean output diff ($meanDiff) must be <= 0.0001", meanDiff <= 0.0001f)
            assertTrue("Class agreement ($classAgreement%) must be >= 99.9%", classAgreement >= 99.9)
            assertTrue("Walkable agreement ($walkableAgreement%) must be >= 99.9%", walkableAgreement >= 99.9)
        } finally {
            store.close()
        }
    }

    @Test
    fun controllerIntegration_defaultsToPortraitPreprocessorAndUprightLetterbox() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = VisionModelStore(context, restoreOnInit = false)
        try {
            val controller = VisionPipelineController(context, modelStore = store)
            try {
                // Verify default strategy is FULL_FRAME_UPRIGHT_LETTERBOX across controller and UiState
                assertEquals(
                    VisionPreprocessingStrategy.FULL_FRAME_UPRIGHT_LETTERBOX,
                    controller.preprocessingStrategy,
                )
                assertEquals(
                    VisionPreprocessingStrategy.FULL_FRAME_UPRIGHT_LETTERBOX,
                    controller.uiState.value.preprocessingStrategy,
                )

                // Verify preprocessor target dimensions match native portrait model
                assertEquals(VisionContract.MODEL_INPUT_WIDTH, controller.preprocessor.targetWidth)
                assertEquals(VisionContract.MODEL_INPUT_HEIGHT, controller.preprocessor.targetHeight)
                assertEquals(
                    VisionPreprocessingStrategy.FULL_FRAME_UPRIGHT_LETTERBOX,
                    controller.preprocessor.defaultStrategy,
                )
            } finally {
                controller.close()
            }
        } finally {
            store.close()
        }
    }

    private fun createSyntheticUprightRoadBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val isSky = y < (height * 0.4f)
            val isRoad = y >= (height * 0.6f)
            for (x in 0 until width) {
                pixels[y * width + x] = when {
                    isSky -> Color.rgb(100, 150, 240)
                    isRoad -> Color.rgb(80, 80, 80)
                    else -> Color.rgb(120, 200, 120) // sidewalk/trees
                }
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun bitmapToSensorYuv(uprightBitmap: Bitmap, rotationDegrees: Int): Triple<ByteArray, ByteArray, ByteArray> {
        val upW = uprightBitmap.width
        val upH = uprightBitmap.height
        val bufW = if (rotationDegrees == 90 || rotationDegrees == 270) upH else upW
        val bufH = if (rotationDegrees == 90 || rotationDegrees == 270) upW else upH

        val chromaW = (bufW + 1) / 2
        val chromaH = (bufH + 1) / 2

        val yBytes = ByteArray(bufW * bufH)
        val uBytes = ByteArray(chromaW * chromaH)
        val vBytes = ByteArray(chromaW * chromaH)

        val pixels = IntArray(upW * upH)
        uprightBitmap.getPixels(pixels, 0, upW, 0, 0, upW, upH)

        for (upY in 0 until upH) {
            for (upX in 0 until upW) {
                val (srcX, srcY) = VisionFramePreprocessor.mapUprightToSensor(upX, upY, bufW, bufH, rotationDegrees)
                val color = pixels[upY * upW + upX]
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)

                // BT.601 limited range
                val yVal = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(16, 235)
                val uVal = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(16, 240)
                val vVal = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(16, 240)

                yBytes[srcY * bufW + srcX] = yVal.toByte()
                if ((srcX and 1 == 0) && (srcY and 1 == 0)) {
                    val chromaIdx = (srcY / 2) * chromaW + (srcX / 2)
                    if (chromaIdx in uBytes.indices) {
                        uBytes[chromaIdx] = uVal.toByte()
                        vBytes[chromaIdx] = vVal.toByte()
                    }
                }
            }
        }
        return Triple(yBytes, uBytes, vBytes)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { "%02x".format(it) }
}
