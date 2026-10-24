package com.ariateam.aria.licenses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LicenseRepositoryTest {

    @Test
    fun inventoryContainsAllRequiredComponents() {
        val licenses = LicenseRepository.allLicenses
        assertFalse("License inventory must not be empty", licenses.isEmpty())

        val ids = licenses.map { it.id }.toSet()

        // Native & On-Device ML engines
        assertTrue("sherpa-onnx must be present", ids.contains("sherpa-onnx"))
        assertTrue("kaldifst must be present", ids.contains("kaldifst"))
        assertTrue("sentencepiece must be present", ids.contains("sentencepiece"))
        assertTrue("onnxruntime must be present", ids.contains("onnxruntime"))
        assertTrue("litert-lm must be present", ids.contains("litert-lm"))
        assertTrue("litert must be present", ids.contains("litert"))

        // Models
        assertTrue("zipformer-model must be present", ids.contains("zipformer-model"))
        assertTrue("gemma-model must be present", ids.contains("gemma-model"))
        assertTrue("team-vision-model must be present", ids.contains("team-vision-model"))
        assertTrue("user-vision-model must be present", ids.contains("user-vision-model"))

        // AndroidX and Core
        assertTrue("androidx-compose must be present", ids.contains("androidx-compose"))
        assertTrue("androidx-camerax must be present", ids.contains("androidx-camerax"))
        assertTrue("androidx-lifecycle must be present", ids.contains("androidx-lifecycle"))
        assertTrue("androidx-core must be present", ids.contains("androidx-core"))

        // Kotlin and runtime dependencies
        assertTrue("kotlin-stdlib must be present", ids.contains("kotlin-stdlib"))
        assertTrue("kotlinx-serialization must be present", ids.contains("kotlinx-serialization"))
        assertTrue("kotlinx-atomicfu must be present", ids.contains("kotlinx-atomicfu"))
        assertTrue("google-dagger must be present", ids.contains("google-dagger"))
        assertTrue("jakarta-inject must be present", ids.contains("jakarta-inject"))
        assertTrue("javax-inject must be present", ids.contains("javax-inject"))
        assertTrue("google-guava must be present", ids.contains("google-guava"))
        assertTrue("google-failureaccess must be present", ids.contains("google-failureaccess"))
        assertTrue("google-gson must be present", ids.contains("google-gson"))
        assertTrue("google-jsr305 must be present", ids.contains("google-jsr305"))
        assertTrue("checker-qual must be present", ids.contains("checker-qual"))
        assertTrue("jspecify must be present", ids.contains("jspecify"))
        assertTrue("jetbrains-annotations must be present", ids.contains("jetbrains-annotations"))
        assertTrue("google-errorprone must be present", ids.contains("google-errorprone"))
        assertTrue("google-autovalue must be present", ids.contains("google-autovalue"))
        assertTrue("google-j2objc must be present", ids.contains("google-j2objc"))

        // Assets
        assertTrue("audio-cues must be present", ids.contains("audio-cues"))
    }

    @Test
    fun jsr305LicenseIsApache20PerPublishedPom() {
        val jsr305 = LicenseRepository.allLicenses.first { it.id == "google-jsr305" }
        assertEquals("JSR-305 declared license must be Apache-2.0 per Maven POM", "Apache-2.0", jsr305.licenseName)
    }

    @Test
    fun teamVisionModelHasCorrectReferenceAndNotProvidedLicense() {
        val teamModel = LicenseRepository.allLicenses.first { it.id == "team-vision-model" }
        assertEquals("NOT PROVIDED", teamModel.licenseName)
        assertEquals("https://github.com/tulg580/vision_model", teamModel.projectUrl)
    }

    @Test
    fun authoritativeAssetFilesExistInAssets() {
        val assetsDir = listOf(File("app/src/main/assets"), File("src/main/assets")).firstOrNull { it.exists() }
        assertNotNull("assets directory must exist", assetsDir)

        val apacheFile = File(assetsDir!!, "licenses/apache-2.0.txt")
        assertTrue("apache-2.0.txt must exist", apacheFile.exists() && apacheFile.length() > 10_000)

        val onnxMitFile = File(assetsDir, "licenses/onnx-mit.txt")
        assertTrue("onnx-mit.txt must exist", onnxMitFile.exists() && onnxMitFile.length() > 500)

        val checkerQualFile = File(assetsDir, "licenses/checker-qual-mit.txt")
        assertTrue("checker-qual-mit.txt must exist", checkerQualFile.exists() && checkerQualFile.length() > 500)

        val jakartaNoticeFile = File(assetsDir, "licenses/jakarta_inject_NOTICE.md")
        assertTrue("jakarta_inject_NOTICE.md must exist", jakartaNoticeFile.exists() && jakartaNoticeFile.length() > 200)

        val jakartaCombinedFile = File(assetsDir, "licenses/jakarta_inject_notice_and_license.txt")
        assertTrue("jakarta_inject_notice_and_license.txt must exist and contain both notice and Apache terms",
            jakartaCombinedFile.exists() && jakartaCombinedFile.length() > 10_000)

        val nativeNoticeFile = File(assetsDir, "licenses/litertlm_third_party_notice.txt")
        assertTrue("litertlm_third_party_notice.txt must exist and be ~2MB", nativeNoticeFile.exists() && nativeNoticeFile.length() > 1_500_000)
    }

    @Test
    fun allItemsHaveValidFields() {
        for (item in LicenseRepository.allLicenses) {
            assertTrue("ID must not be blank for ${item.id}", item.id.isNotBlank())
            assertTrue("Name must not be blank for ${item.id}", item.name.isNotBlank())
            assertTrue("Version must not be blank for ${item.id}", item.version.isNotBlank())
            assertTrue("Project URL must not be blank for ${item.id}", item.projectUrl.isNotBlank())
            assertTrue("License name must not be blank for ${item.id}", item.licenseName.isNotBlank())
            assertNotNull("Category must be defined for ${item.id}", item.category)
        }
    }
}
