package com.ariateam.aria.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tensorflow.lite.schema.BufferT
import org.tensorflow.lite.schema.ModelT
import org.tensorflow.lite.schema.SubGraphT
import org.tensorflow.lite.schema.TensorT
import org.tensorflow.lite.schema.TensorType as SchemaTensorType
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LiteRtMetadataInspectionTest {

    private fun buildTfliteModelBytes(
        inputShape: IntArray = intArrayOf(1, 512, 288, 3),
        inputType: Byte = SchemaTensorType.FLOAT32,
        outputShape: IntArray = intArrayOf(1, 512, 288, 7),
        outputType: Byte = SchemaTensorType.FLOAT32,
        numInputs: Int = 1,
        numOutputs: Int = 1,
        hasSubgraphs: Boolean = true,
    ): ByteArray {
        val model = ModelT().apply {
            version = 3L
            operatorCodes = emptyArray()
            buffers = arrayOf(BufferT().apply { data = intArrayOf() })
            if (hasSubgraphs) {
                val tensorsList = mutableListOf<TensorT>()
                val inputIndices = mutableListOf<Int>()
                val outputIndices = mutableListOf<Int>()

                for (i in 0 until numInputs) {
                    tensorsList.add(TensorT().apply {
                        shape = inputShape
                        type = inputType
                        buffer = 0L
                        name = "input_$i"
                    })
                    inputIndices.add(tensorsList.size - 1)
                }

                for (i in 0 until numOutputs) {
                    tensorsList.add(TensorT().apply {
                        shape = outputShape
                        type = outputType
                        buffer = 0L
                        name = "output_$i"
                    })
                    outputIndices.add(tensorsList.size - 1)
                }

                val subgraph = SubGraphT().apply {
                    tensors = tensorsList.toTypedArray()
                    inputs = inputIndices.toIntArray()
                    outputs = outputIndices.toIntArray()
                }
                subgraphs = arrayOf(subgraph)
            } else {
                subgraphs = emptyArray()
            }
        }
        return model.serializeToBinary()
    }

    @Test
    fun validPortrait7ClassModel_withoutCustomMetadata_passesInspection() {
        val bytes = buildTfliteModelBytes()
        val buffer = ByteBuffer.wrap(bytes)

        val result = LiteRtVisionSegmenter.inspectMetadataSpec(buffer)
        assertTrue("Valid 7-class portrait spec must pass: $result", result is VisionContract.SpecValidationResult.Valid)
    }

    @Test
    fun bufferPositionAndOrder_arePreservedAfterInspection() {
        val bytes = buildTfliteModelBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buffer.position(0)
        val initialLimit = buffer.limit()

        val result = LiteRtVisionSegmenter.inspectMetadataSpec(buffer)
        assertTrue(result is VisionContract.SpecValidationResult.Valid)
        assertEquals("Caller buffer position must be preserved", 0, buffer.position())
        assertEquals("Caller buffer limit must be preserved", initialLimit, buffer.limit())
        assertEquals("Caller buffer byte order must be preserved", ByteOrder.BIG_ENDIAN, buffer.order())
    }

    @Test
    fun wrongInputTensorCount_isRejectedWithActionableCode() {
        // Zero inputs
        val zeroInputsBytes = buildTfliteModelBytes(numInputs = 0)
        val zeroResult = LiteRtVisionSegmenter.inspectMetadataSpec(ByteBuffer.wrap(zeroInputsBytes))
        assertTrue(zeroResult is VisionContract.SpecValidationResult.Invalid)
        assertEquals("input_tensor_count", (zeroResult as VisionContract.SpecValidationResult.Invalid).code)

        // Multiple inputs
        val twoInputsBytes = buildTfliteModelBytes(numInputs = 2)
        val twoResult = LiteRtVisionSegmenter.inspectMetadataSpec(ByteBuffer.wrap(twoInputsBytes))
        assertTrue(twoResult is VisionContract.SpecValidationResult.Invalid)
        assertEquals("input_tensor_count", (twoResult as VisionContract.SpecValidationResult.Invalid).code)
    }

    @Test
    fun wrongOutputTensorCount_isRejectedWithActionableCode() {
        // Zero outputs
        val zeroOutputsBytes = buildTfliteModelBytes(numOutputs = 0)
        val zeroResult = LiteRtVisionSegmenter.inspectMetadataSpec(ByteBuffer.wrap(zeroOutputsBytes))
        assertTrue(zeroResult is VisionContract.SpecValidationResult.Invalid)
        assertEquals("output_tensor_count", (zeroResult as VisionContract.SpecValidationResult.Invalid).code)

        // Two outputs
        val twoOutputsBytes = buildTfliteModelBytes(numOutputs = 2)
        val twoResult = LiteRtVisionSegmenter.inspectMetadataSpec(ByteBuffer.wrap(twoOutputsBytes))
        assertTrue(twoResult is VisionContract.SpecValidationResult.Invalid)
        assertEquals("output_tensor_count", (twoResult as VisionContract.SpecValidationResult.Invalid).code)
    }

    @Test
    fun wrongInputDataType_isRejectedWithActionableCode() {
        val uint8Bytes = buildTfliteModelBytes(inputType = SchemaTensorType.UINT8)
        val result = LiteRtVisionSegmenter.inspectMetadataSpec(ByteBuffer.wrap(uint8Bytes))
        assertTrue(result is VisionContract.SpecValidationResult.Invalid)
        val invalid = result as VisionContract.SpecValidationResult.Invalid
        assertEquals("input_dtype", invalid.code)
        assertTrue("Detail should note UINT8: ${invalid.detail}", invalid.detail.contains("UINT8"))
    }

    @Test
    fun wrongOutputDataType_isRejectedWithActionableCode() {
        val int32Bytes = buildTfliteModelBytes(outputType = SchemaTensorType.INT32)
        val result = LiteRtVisionSegmenter.inspectMetadataSpec(ByteBuffer.wrap(int32Bytes))
        assertTrue(result is VisionContract.SpecValidationResult.Invalid)
        val invalid = result as VisionContract.SpecValidationResult.Invalid
        assertEquals("output_dtype", invalid.code)
        assertTrue("Detail should note INT32: ${invalid.detail}", invalid.detail.contains("INT32"))
    }

    @Test
    fun wrongInputShape_isRejectedWithActionableCode() {
        // Landscape orientation [1, 288, 512, 3] instead of portrait [1, 512, 288, 3]
        val landscapeInputBytes = buildTfliteModelBytes(inputShape = intArrayOf(1, 288, 512, 3))
        val result = LiteRtVisionSegmenter.inspectMetadataSpec(ByteBuffer.wrap(landscapeInputBytes))
        assertTrue(result is VisionContract.SpecValidationResult.Invalid)
        assertEquals("input_shape", (result as VisionContract.SpecValidationResult.Invalid).code)
    }

    @Test
    fun wrongOutputShape_isRejectedWithActionableCode() {
        // 5-class model instead of 7-class portrait model
        val fiveClassBytes = buildTfliteModelBytes(outputShape = intArrayOf(1, 512, 288, 5))
        val fiveResult = LiteRtVisionSegmenter.inspectMetadataSpec(ByteBuffer.wrap(fiveClassBytes))
        assertTrue(fiveResult is VisionContract.SpecValidationResult.Invalid)
        assertEquals("output_shape", (fiveResult as VisionContract.SpecValidationResult.Invalid).code)

        // 19-class model (Cityscapes) instead of 7-class Aria
        val nineteenClassBytes = buildTfliteModelBytes(outputShape = intArrayOf(1, 512, 288, 19))
        val nineteenResult = LiteRtVisionSegmenter.inspectMetadataSpec(ByteBuffer.wrap(nineteenClassBytes))
        assertTrue(nineteenResult is VisionContract.SpecValidationResult.Invalid)
        assertEquals("output_shape", (nineteenResult as VisionContract.SpecValidationResult.Invalid).code)
    }

    @Test
    fun malformedOrEmptyBuffers_areHandledActionablyWithoutCrashing() {
        // Empty buffer
        val emptyResult = LiteRtVisionSegmenter.inspectMetadataSpec(ByteBuffer.allocate(0))
        assertTrue(emptyResult is VisionContract.SpecValidationResult.Invalid)
        assertEquals("model_init_failed", (emptyResult as VisionContract.SpecValidationResult.Invalid).code)

        // Corrupted random bytes
        val randomBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C)
        val corruptResult = LiteRtVisionSegmenter.inspectMetadataSpec(ByteBuffer.wrap(randomBytes))
        assertTrue(corruptResult is VisionContract.SpecValidationResult.Invalid)
        assertEquals("model_init_failed", (corruptResult as VisionContract.SpecValidationResult.Invalid).code)

        // Model without subgraphs
        val noSubgraphBytes = buildTfliteModelBytes(hasSubgraphs = false)
        val noSubgraphResult = LiteRtVisionSegmenter.inspectMetadataSpec(ByteBuffer.wrap(noSubgraphBytes))
        assertTrue(noSubgraphResult is VisionContract.SpecValidationResult.Invalid)
        assertEquals("model_init_failed", (noSubgraphResult as VisionContract.SpecValidationResult.Invalid).code)
    }

    @Test
    fun prepareModelSession_validatesRealFilesAndRejectsEmptyOrMissing() {
        val segmenter = LiteRtVisionSegmenter(inferencePolicy = ProductionGpuOnlyPolicy)
        try {
            // Missing file
            val missing = File("non_existent_model_file.tflite")
            val missingOutcome = segmenter.prepareModelSession(missing)
            assertTrue(missingOutcome is LiteRtVisionSegmenter.PrepareOutcome.Invalid)
            assertEquals("missing_or_empty", (missingOutcome as LiteRtVisionSegmenter.PrepareOutcome.Invalid).failure.code)

            // Zero byte file
            val emptyFile = File.createTempFile("empty_model_", ".tflite")
            try {
                val emptyOutcome = segmenter.prepareModelSession(emptyFile)
                assertTrue(emptyOutcome is LiteRtVisionSegmenter.PrepareOutcome.Invalid)
                assertEquals("missing_or_empty", (emptyOutcome as LiteRtVisionSegmenter.PrepareOutcome.Invalid).failure.code)
            } finally {
                emptyFile.delete()
            }

            // Valid synthetic model file
            val validFile = File.createTempFile("valid_portrait_", ".tflite")
            try {
                validFile.writeBytes(buildTfliteModelBytes())
                val validOutcome = segmenter.prepareModelSession(validFile)
                assertTrue("Valid file must produce PrepareOutcome.Valid: $validOutcome", validOutcome is LiteRtVisionSegmenter.PrepareOutcome.Valid)
                (validOutcome as LiteRtVisionSegmenter.PrepareOutcome.Valid).session.close()
            } finally {
                validFile.delete()
            }
        } finally {
            segmenter.close()
        }
    }

    @Test
    fun realPackagedPortraitModelAsset_passesMetadataValidation() {
        val modelFile = File("src/main/assets/models/walkable_seg_aria5_portrait_unfused.tflite")
            .takeIf { it.isFile }
            ?: File("app/src/main/assets/models/walkable_seg_aria5_portrait_unfused.tflite")
        assertTrue("Real portrait model file must exist: ${modelFile.absolutePath}", modelFile.isFile)

        val segmenter = LiteRtVisionSegmenter(inferencePolicy = ProductionGpuOnlyPolicy)
        try {
            val outcome = segmenter.prepareModelSession(modelFile)
            assertTrue(
                "Real packaged portrait model must pass prepareModelSession validation: $outcome",
                outcome is LiteRtVisionSegmenter.PrepareOutcome.Valid,
            )
            val session = (outcome as LiteRtVisionSegmenter.PrepareOutcome.Valid).session
            assertNotNull("Mapped buffer must be present", session.mappedBuffer)
            session.close()
        } finally {
            segmenter.close()
        }
    }

    @Test
    fun realPackagedLandscapeModelAsset_isRejectedByPortraitContract() {
        val landscapeFile = File("src/main/assets/models/walkable_seg_aria5.tflite")
            .takeIf { it.isFile }
            ?: File("app/src/main/assets/models/walkable_seg_aria5.tflite")
        assertTrue("Real landscape model file must exist: ${landscapeFile.absolutePath}", landscapeFile.isFile)

        val segmenter = LiteRtVisionSegmenter(inferencePolicy = ProductionGpuOnlyPolicy)
        try {
            val outcome = segmenter.prepareModelSession(landscapeFile)
            assertTrue("Landscape model must be rejected for portrait pipeline", outcome is LiteRtVisionSegmenter.PrepareOutcome.Invalid)
            val code = (outcome as LiteRtVisionSegmenter.PrepareOutcome.Invalid).failure.code
            assertTrue("Rejection code must indicate shape mismatch: $code", code == "input_shape" || code == "output_shape")
        } finally {
            segmenter.close()
        }
    }

    @Test
    fun resolveTensorTypeName_mapsKnownAndUnknownValues() {
        assertEquals("FLOAT32", LiteRtVisionSegmenter.resolveTensorTypeName(SchemaTensorType.FLOAT32))
        assertEquals("INT32", LiteRtVisionSegmenter.resolveTensorTypeName(SchemaTensorType.INT32))
        assertEquals("UINT8", LiteRtVisionSegmenter.resolveTensorTypeName(SchemaTensorType.UINT8))
        assertEquals("FLOAT16", LiteRtVisionSegmenter.resolveTensorTypeName(SchemaTensorType.FLOAT16))
        assertEquals("UNKNOWN(120)", LiteRtVisionSegmenter.resolveTensorTypeName(120.toByte()))
    }
}
