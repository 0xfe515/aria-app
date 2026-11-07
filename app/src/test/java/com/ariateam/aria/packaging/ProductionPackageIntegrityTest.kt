package com.ariateam.aria.packaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Validates production model asset separation between main APK and androidTest APK.
 *
 * Pins the source asset inventory: the current portrait model and two known legacy models
 * remain in main assets, while evaluation fixture copies are also retained in androidTest.
 */
class ProductionPackageIntegrityTest {

    private fun resolveProjectDir(): File {
        val cwd = File(".").canonicalFile
        return if (File(cwd, "app").isDirectory) cwd else cwd.parentFile
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun mainAssets_containsExactlyKnownModelsWithPinnedContents() {
        val root = resolveProjectDir()
        val mainModelsDir = File(root, "app/src/main/assets/models")
        assertTrue("Main models dir must exist: ${mainModelsDir.absolutePath}", mainModelsDir.isDirectory)

        val modelFiles = mainModelsDir.listFiles { f -> f.isFile && f.name.endsWith(".tflite") } ?: emptyArray()
        val expected = mapOf(
            "walkable_seg_aria5.tflite" to (10_837_912L to "3944dce57cc161679525c3f4feb1f6b46c8e7519f8f10ba06155b3874c2c66b7"),
            "walkable_seg_aria5_unfused.tflite" to (10_839_936L to "84364b9730d200b00a65ebcbae6a13444c6f8eb9bbab0676adc30d57cfa24847"),
            "walkable_seg_aria5_portrait_unfused.tflite" to (10_839_936L to "312105f5a82fc2677337ca7c15867249b3ae255a983fb5ffa4a54dae2b6864c1"),
            // Optional auxiliary OD helper (not a segmentation model); pinned in
            // assets/models/efficientdet_lite0_provenance.md.
            "efficientdet_lite0_int8.tflite" to (4_563_519L to "2e04c53bfeac0ac2a30c057c7e2a777594ce39baaac35a92f74fb1e8c4fc4e0b"),
        )
        assertEquals("Unexpected source model inventory", expected.keys, modelFiles.map { it.name }.toSet())
        for (model in modelFiles) {
            val (expectedLength, expectedSha256) = expected.getValue(model.name)
            assertEquals("Unexpected length for ${model.name}", expectedLength, model.length())
            assertEquals("Unexpected SHA-256 for ${model.name}", expectedSha256, sha256(model))
        }
    }

    @Test
    fun androidTestAssets_retainsMovedLegacyEvaluationModels() {
        val root = resolveProjectDir()
        val androidTestModelsDir = File(root, "app/src/androidTest/assets/models")
        assertTrue("androidTest models dir must exist: ${androidTestModelsDir.absolutePath}", androidTestModelsDir.isDirectory)

        val origModel = File(androidTestModelsDir, "walkable_seg_aria5.tflite")
        val unfusedModel = File(androidTestModelsDir, "walkable_seg_aria5_unfused.tflite")

        assertTrue("Original legacy model must exist in androidTest assets", origModel.isFile)
        assertEquals(10_837_912L, origModel.length())
        assertEquals("3944dce57cc161679525c3f4feb1f6b46c8e7519f8f10ba06155b3874c2c66b7", sha256(origModel))

        assertTrue("Unfused legacy model must exist in androidTest assets", unfusedModel.isFile)
        assertEquals(10_839_936L, unfusedModel.length())
        assertEquals("84364b9730d200b00a65ebcbae6a13444c6f8eb9bbab0676adc30d57cfa24847", sha256(unfusedModel))
    }
}
