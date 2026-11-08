package com.ariateam.aria.vision.od

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tensorflow.lite.schema.Model
import org.tensorflow.lite.schema.TensorType as SchemaTensorType
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Validates the packaged OD helper artifact itself (not a synthetic model): pinned bytes, pinned
 * SHA-256, real tensor contract, built-in postprocess op, embedded license metadata and label map.
 *
 * These checks run on the JVM because the flatbuffer schema classes are part of the existing
 * LiteRT metadata dependency; no native interpreter is required.
 */
class OdModelArtifactTest {

    private fun modelFile(): File = File("src/main/assets/models/${LiteRtObjectDetector.ASSET_MODEL_PATH.substringAfterLast('/')}")
        .takeIf { it.isFile }
        ?: File("app/src/main/assets/${LiteRtObjectDetector.ASSET_MODEL_PATH}")

    private fun labelsFile(): File = File("src/main/assets/models/${LiteRtObjectDetector.ASSET_LABELS_PATH.substringAfterLast('/')}")
        .takeIf { it.isFile }
        ?: File("app/src/main/assets/${LiteRtObjectDetector.ASSET_LABELS_PATH}")

    private fun provenanceFile(): File = File("src/main/assets/models/efficientdet_lite0_provenance.md")
        .takeIf { it.isFile }
        ?: File("app/src/main/assets/models/efficientdet_lite0_provenance.md")

    private fun licenseFile(): File = File("src/main/assets/models/efficientdet_lite0_LICENSE.txt")
        .takeIf { it.isFile }
        ?: File("app/src/main/assets/models/efficientdet_lite0_LICENSE.txt")

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun packagedModelBytesMatchThePinnedProvenance() {
        val file = modelFile()
        assertTrue("packaged OD model must exist: ${file.absolutePath}", file.isFile)
        val bytes = file.readBytes()
        assertEquals(LiteRtModelExpectation.BYTES, bytes.size.toLong())
        assertEquals(LiteRtModelExpectation.SHA256, sha256(bytes))
    }

    @Test
    fun packagedModelExposesThePinnedInputContract() {
        val bytes = modelFile().readBytes()
        val model = Model.getRootAsModel(ByteBuffer.wrap(bytes)).unpack()
        assertEquals("exactly one subgraph", 1, model.subgraphs.size)
        val subgraph = model.subgraphs[0]
        assertEquals("exactly one input", 1, subgraph.inputs.size)

        val input = subgraph.tensors[subgraph.inputs[0]]
        assertEquals(listOf(1, 320, 320, 3), input.shape.toList())
        assertEquals(SchemaTensorType.UINT8.toInt(), input.type.toInt())
        val quantization = input.quantization
        assertNotNull("input must declare quantization", quantization)
        assertEquals(1, quantization.scale.size)
        assertEquals(1.0f / 128.0f, quantization.scale[0], 1e-9f)
        assertEquals(1, quantization.zeroPoint.size)
        assertEquals(127L, quantization.zeroPoint[0])
    }

    @Test
    fun packagedModelExposesFourPinnedPostprocessOutputsByNameAndDtype() {
        val model = Model.getRootAsModel(ByteBuffer.wrap(modelFile().readBytes())).unpack()
        val subgraph = model.subgraphs[0]
        assertEquals("four postprocess outputs", 4, subgraph.outputs.size)

        val expectedNames = listOf(
            LiteRtObjectDetector.EXPECTED_BOXES_NAME,
            LiteRtObjectDetector.EXPECTED_CLASSES_NAME,
            LiteRtObjectDetector.EXPECTED_SCORES_NAME,
            LiteRtObjectDetector.EXPECTED_COUNT_NAME,
        )
        subgraph.outputs.forEachIndexed { index, tensorIndex ->
            val tensor = subgraph.tensors[tensorIndex]
            assertEquals("output $index dtype", SchemaTensorType.FLOAT32.toInt(), tensor.type.toInt())
            assertEquals("output $index name", expectedNames[index], tensor.name)
            // The four postprocess outputs carry dynamic shapes in the flatbuffer (empty
            // shape/shape_signature); the concrete [1,25,4]/[1,25]/[1,25]/[1] shapes are resolved by
            // the interpreter and validated by LiteRtObjectDetector at load time.
            assertTrue("output $index must be dynamic in the artifact", tensor.shape.isEmpty())
        }
    }

    @Test
    fun packagedModelContainsTheBuiltInDetectionPostprocessOp() {
        val model = Model.getRootAsModel(ByteBuffer.wrap(modelFile().readBytes())).unpack()
        val customCodes = model.operatorCodes.mapNotNull { it.customCode }
        assertTrue(
            "model must rely on its built-in postprocess/NMS op, found $customCodes",
            customCodes.contains(LiteRtObjectDetector.POSTPROCESS_OP_CODE),
        )
    }

    @Test
    fun packagedModelPostprocessOperatorProducesAllFourOutputs() {
        val model = Model.getRootAsModel(ByteBuffer.wrap(modelFile().readBytes())).unpack()
        val subgraph = model.subgraphs[0]
        val outputSet = subgraph.outputs.toSet()
        val producer = subgraph.operators.firstOrNull { op -> op.outputs.any { it in outputSet } }
        assertNotNull("a postprocess operator must produce the model outputs", producer)
        val opcode = model.operatorCodes[producer!!.opcodeIndex.toInt()]
        assertEquals(
            "outputs must come from the built-in detection postprocess custom op",
            LiteRtObjectDetector.POSTPROCESS_OP_CODE,
            opcode.customCode,
        )
        val produced = producer.outputs.toSet()
        for (index in 0 until LiteRtObjectDetector.EXPECTED_OUTPUT_COUNT) {
            assertTrue(
                "subgraph output $index must be produced by the postprocess operator",
                subgraph.outputs[index] in produced,
            )
        }
    }

    @Test
    fun packagedModelMetadataCarriesLicenseNormalizationAndAuthor() {
        val model = Model.getRootAsModel(ByteBuffer.wrap(modelFile().readBytes())).unpack()
        val metadataEntry = model.metadata.firstOrNull { it.name == "TFLITE_METADATA" }
        assertNotNull("TFLITE_METADATA buffer must be present", metadataEntry)
        val buffer = model.buffers[metadataEntry!!.buffer.toInt()]
        val bytes = buffer.data?.map { it.toByte() }?.toByteArray() ?: ByteArray(0)
        assertTrue("metadata must not be empty", bytes.isNotEmpty())
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue("embedded Apache-2.0 license", text.contains("Apache License"))
        assertTrue("embedded author", text.contains("TensorFlow"))
        assertTrue("input description mentions 0..255 bytes", text.contains("single byte between 0 and 255"))
        val meanStdHits = countOccurrences(bytes, byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0x42))
        assertTrue("metadata must declare 127.5 mean/std (found $meanStdHits)", meanStdHits >= 2)
    }

    @Test
    fun packagedLabelMapMatchesThePinnedCocoCategories() {
        val file = labelsFile()
        assertTrue("label map must exist: ${file.absolutePath}", file.isFile)
        val labels = file.readText(Charsets.UTF_8).split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(LiteRtObjectDetector.CLASS_COUNT, labels.size)
        assertEquals("person", labels[0])
        assertEquals("bicycle", labels[1])
        assertEquals("car", labels[2])
        assertEquals("motorcycle", labels[3])
        assertEquals("airplane", labels[4])
        assertEquals("bus", labels[5])
        assertEquals("train", labels[6])
        assertEquals("truck", labels[7])
        assertEquals("traffic light", labels[9])
        assertEquals("toothbrush", labels[89])
        for (id in LiteRtObjectDetector.ALLOWED_CLASS_IDS) {
            assertTrue("allowed class $id must exist in the label map", id in labels.indices)
        }
    }

    @Test
    fun pinnedDetectorAllowlistIncludesTrafficLightForDisplayAndRejectsOtherCocoClasses() {
        val allowed = LiteRtObjectDetector.ALLOWED_CLASS_IDS.toSet()
        val expected = setOf(0, 1, 2, 3, 5, 7, 9)
        assertEquals("allowed class IDs must match expected obstacle + traffic light set", expected, allowed)
        assertTrue("traffic light (index 9) must be allowed", allowed.contains(9))
        assertFalse("airplane (index 4) must be excluded", allowed.contains(4))
        assertFalse("train (index 6) must be excluded", allowed.contains(6))
        assertFalse("boat (index 8) must be excluded", allowed.contains(8))
        assertFalse("stop sign (index 12) must be excluded", allowed.contains(12))
    }

    @Test
    fun packagedLicenseFileContainsTheFullApache2Text() {
        val file = licenseFile()
        assertTrue("full Apache-2.0 text must ship next to the model: ${file.absolutePath}", file.isFile)
        val text = file.readText(Charsets.UTF_8)
        assertTrue("license file must be the full text, not a URL stub", text.length > 10_000)
        assertTrue("Apache License heading", text.contains("Apache License"))
        assertTrue("Version 2.0 heading", text.contains("Version 2.0"))
        assertTrue("license terms body", text.contains("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION"))
    }

    @Test
    fun provenanceDocumentRecordsSourceShaLicenseAndExclusions() {
        val file = provenanceFile()
        assertTrue("provenance document must ship next to the model: ${file.absolutePath}", file.isFile)
        val text = file.readText(Charsets.UTF_8)
        assertTrue("provenance must pin the source URL", text.contains("lite-model_efficientdet_lite0_detection_metadata_1.tflite"))
        assertTrue("provenance must pin the SHA-256", text.contains(LiteRtObjectDetector.MODEL_SHA256))
        assertTrue("provenance must name the license", text.contains("Apache", ignoreCase = true))
        val normalized = text.replace(Regex("\\s+"), " ")
        assertTrue(
            "provenance must state that no device benchmark was run",
            normalized.contains("No device benchmark was run in this iteration"),
        )
    }

    private fun countOccurrences(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return 0
        var count = 0
        outer@ for (start in 0..(haystack.size - needle.size)) {
            for (index in needle.indices) {
                if (haystack[start + index] != needle[index]) continue@outer
            }
            count++
        }
        return count
    }

    private object LiteRtModelExpectation {
        const val SHA256 = LiteRtObjectDetector.MODEL_SHA256
        const val BYTES = LiteRtObjectDetector.MODEL_BYTE_COUNT
    }
}
