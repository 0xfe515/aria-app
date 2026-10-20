import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

data class VerifiedAssetSpec(
    val relativePath: String,
    val expectedBytes: Long,
    val expectedSha256: String,
)

object SherpaOfflineProvisioning {
    const val AAR_FILENAME = "sherpa-onnx-1.13.8.aar"
    const val AAR_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar"
    const val AAR_BYTES = 50_129_134L
    const val AAR_SHA256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"

    const val MODEL_DIR_NAME = "sherpa-onnx-streaming-zipformer-korean-2024-06-16-mobile"
    const val MODEL_ARCHIVE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-korean-2024-06-16-mobile.tar.bz2"
    const val MODEL_ARCHIVE_BYTES = 377_905_301L
    const val MODEL_ARCHIVE_SHA256 = "911187cb71d94df8d8b9bbc1f7684db2d847e4b0259e6f0fb3141e4991711595"
    const val FORBIDDEN_FP32_ENCODER = "encoder-epoch-99-avg-1.onnx"

    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 120_000

    val MODEL_ASSET_SPECS = listOf(
        VerifiedAssetSpec(
            relativePath = "encoder-epoch-99-avg-1.int8.onnx",
            expectedBytes = 111_502_643L,
            expectedSha256 = "428ab4791357bcef639ea8d59f98667e97a52ed76b6a241ba9dbd5582db8c634",
        ),
        VerifiedAssetSpec(
            relativePath = "decoder-epoch-99-avg-1.onnx",
            expectedBytes = 11_309_084L,
            expectedSha256 = "b29cfb4575141e50a30a22b2c4579934f3d4f45b83c9c8c08c3aef5a3fa7abfc",
        ),
        VerifiedAssetSpec(
            relativePath = "joiner-epoch-99-avg-1.int8.onnx",
            expectedBytes = 2_581_421L,
            expectedSha256 = "128b80a66a1f718488af8560f9d15895109b99ff3e573f0a0130e03774ef1ced",
        ),
        VerifiedAssetSpec(
            relativePath = "tokens.txt",
            expectedBytes = 60_246L,
            expectedSha256 = "016bdf0965029263b7ad01b742366ee542ef0bef38261510e8176ff6f2e9e668",
        ),
        VerifiedAssetSpec(
            relativePath = "bpe.model",
            expectedBytes = 314_212L,
            expectedSha256 = "1491bc92c47dfda4225f5b8930fba3cfa34c3b1ccd25e7d96c630a262f3e918d",
        ),
        VerifiedAssetSpec(
            relativePath = "notes.md",
            expectedBytes = 213L,
            expectedSha256 = "94cff0508aa249cddc375a7ab61d54cc19833a8780753a6da99f743cdcc4a8cf",
        ),
        VerifiedAssetSpec(
            relativePath = "test_wavs/0.wav",
            expectedBytes = 112_892L,
            expectedSha256 = "0faf0b037efe428e5e561195f4d2aa148b2a0a2a5fc540b2c184b9d5c241e984",
        ),
        VerifiedAssetSpec(
            relativePath = "test_wavs/trans.txt",
            expectedBytes = 283L,
            expectedSha256 = "7dafa4e5fcdeae1552e4144494e83de6d88ead237f7fc3e851999c61b71f9f75",
        ),
    )

    fun resolveCacheRoot(configuredDir: String?, defaultPersistentCachePath: String): File {
        val tempValidationDir = File(System.getProperty("java.io.tmpdir"), "aria-sherpa-validation")
        val root = configuredDir
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: tempValidationDir.takeIf { it.isDirectory }
            ?: File(defaultPersistentCachePath)
        root.mkdirs()
        return root
    }

    fun downloadAndVerify(
        url: String,
        destination: File,
        expectedBytes: Long,
        expectedSha256: String,
    ) {
        destination.parentFile.mkdirs()
        val tempFile = File(destination.parentFile, "${destination.name}.part")
        val connection = URI.create(url).toURL().openConnection().apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        connection.getInputStream().use { input ->
            Files.copy(input, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        requireVerifiedFile(tempFile, expectedBytes, expectedSha256)
        Files.move(tempFile.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    fun isVerifiedFile(file: File, expectedBytes: Long, expectedSha256: String): Boolean {
        if (!file.isFile || file.length() != expectedBytes) return false
        return sha256Hex(file).equals(expectedSha256, ignoreCase = true)
    }

    fun requireVerifiedFile(file: File, expectedBytes: Long, expectedSha256: String) {
        if (!file.isFile) {
            throw GradleException("Required offline ASR artifact missing: ${file.absolutePath}")
        }
        val actualBytes = file.length()
        if (actualBytes != expectedBytes) {
            throw GradleException(
                "Size mismatch for ${file.name}: expected $expectedBytes bytes, got $actualBytes bytes"
            )
        }
        val actualSha = sha256Hex(file)
        if (!actualSha.equals(expectedSha256, ignoreCase = true)) {
            throw GradleException(
                "SHA-256 mismatch for ${file.name}: expected $expectedSha256, got $actualSha"
            )
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

@DisableCachingByDefault(because = "Provisions and SHA-256 verifies local offline Sherpa-ONNX AAR")
abstract class ProvisionSherpaOfflineAarTask : DefaultTask() {
    @get:Input
    @get:Optional
    abstract val configuredCacheDir: Property<String>

    @get:Input
    abstract val defaultCacheDirPath: Property<String>

    @get:OutputFile
    abstract val outputAarFile: RegularFileProperty

    @TaskAction
    fun provisionAar() {
        val cacheRoot = SherpaOfflineProvisioning.resolveCacheRoot(
            configuredCacheDir.orNull,
            defaultCacheDirPath.get(),
        )
        val targetAar = outputAarFile.get().asFile
        targetAar.parentFile.mkdirs()
        if (SherpaOfflineProvisioning.isVerifiedFile(
                targetAar,
                SherpaOfflineProvisioning.AAR_BYTES,
                SherpaOfflineProvisioning.AAR_SHA256,
            )
        ) {
            return
        }
        val cachedAar = File(cacheRoot, SherpaOfflineProvisioning.AAR_FILENAME)
        if (!SherpaOfflineProvisioning.isVerifiedFile(
                cachedAar,
                SherpaOfflineProvisioning.AAR_BYTES,
                SherpaOfflineProvisioning.AAR_SHA256,
            )
        ) {
            SherpaOfflineProvisioning.downloadAndVerify(
                url = SherpaOfflineProvisioning.AAR_URL,
                destination = cachedAar,
                expectedBytes = SherpaOfflineProvisioning.AAR_BYTES,
                expectedSha256 = SherpaOfflineProvisioning.AAR_SHA256,
            )
        }
        Files.copy(cachedAar.toPath(), targetAar.toPath(), StandardCopyOption.REPLACE_EXISTING)
        SherpaOfflineProvisioning.requireVerifiedFile(
            targetAar,
            SherpaOfflineProvisioning.AAR_BYTES,
            SherpaOfflineProvisioning.AAR_SHA256,
        )
    }
}

@DisableCachingByDefault(because = "Provisions and SHA-256 verifies local offline Korean ASR model assets")
abstract class ProvisionSherpaOfflineAssetsTask : DefaultTask() {
    @get:Input
    @get:Optional
    abstract val configuredCacheDir: Property<String>

    @get:Input
    abstract val defaultCacheDirPath: Property<String>

    @get:OutputDirectory
    abstract val outputAssetsDir: DirectoryProperty

    @get:Inject
    abstract val archiveOperations: ArchiveOperations

    @TaskAction
    fun provisionAssets() {
        val cacheRoot = SherpaOfflineProvisioning.resolveCacheRoot(
            configuredCacheDir.orNull,
            defaultCacheDirPath.get(),
        )
        val assetsRoot = outputAssetsDir.get().asFile
        val modelDirName = SherpaOfflineProvisioning.MODEL_DIR_NAME
        val targetModelDir = File(assetsRoot, modelDirName)
        targetModelDir.mkdirs()

        val forbiddenFp32 = File(targetModelDir, SherpaOfflineProvisioning.FORBIDDEN_FP32_ENCODER)
        if (forbiddenFp32.exists() && !forbiddenFp32.delete()) {
            throw GradleException(
                "Failed to remove forbidden unquantized FP32 encoder: ${forbiddenFp32.absolutePath}"
            )
        }

        val allTargetAssetsReady = SherpaOfflineProvisioning.MODEL_ASSET_SPECS.all { spec ->
            SherpaOfflineProvisioning.isVerifiedFile(
                File(targetModelDir, spec.relativePath),
                spec.expectedBytes,
                spec.expectedSha256,
            )
        }
        if (allTargetAssetsReady) return

        val cachedModelDir = File(cacheRoot, modelDirName)
        val allCachedExtractedReady = SherpaOfflineProvisioning.MODEL_ASSET_SPECS.all { spec ->
            SherpaOfflineProvisioning.isVerifiedFile(
                File(cachedModelDir, spec.relativePath),
                spec.expectedBytes,
                spec.expectedSha256,
            )
        }

        if (!allCachedExtractedReady) {
            val archiveFile = listOf(
                File(cacheRoot, "korean-mobile.tar.bz2"),
                File(cacheRoot, "$modelDirName.tar.bz2"),
            ).firstOrNull {
                SherpaOfflineProvisioning.isVerifiedFile(
                    it,
                    SherpaOfflineProvisioning.MODEL_ARCHIVE_BYTES,
                    SherpaOfflineProvisioning.MODEL_ARCHIVE_SHA256,
                )
            } ?: File(cacheRoot, "$modelDirName.tar.bz2").also { dest ->
                SherpaOfflineProvisioning.downloadAndVerify(
                    url = SherpaOfflineProvisioning.MODEL_ARCHIVE_URL,
                    destination = dest,
                    expectedBytes = SherpaOfflineProvisioning.MODEL_ARCHIVE_BYTES,
                    expectedSha256 = SherpaOfflineProvisioning.MODEL_ARCHIVE_SHA256,
                )
            }

            val allowedRelativePaths =
                SherpaOfflineProvisioning.MODEL_ASSET_SPECS.associateBy { it.relativePath }
            val canonicalCacheModelRoot = cachedModelDir.canonicalFile.toPath()
            archiveOperations.tarTree(archiveOperations.bzip2(archiveFile)).visit {
                if (isDirectory) return@visit
                val normalized = relativePath.pathString.replace('\\', '/')
                if (normalized.contains("..")) {
                    throw GradleException("Refusing archive entry with parent path traversal: $normalized")
                }
                val prefix = "$modelDirName/"
                if (!normalized.startsWith(prefix)) return@visit
                val innerPath = normalized.removePrefix(prefix)
                if (innerPath !in allowedRelativePaths) return@visit
                val outFile = File(cachedModelDir, innerPath)
                val canonicalOut = outFile.canonicalFile.toPath()
                if (!canonicalOut.startsWith(canonicalCacheModelRoot)) {
                    throw GradleException("Refusing archive entry escaping model root: $normalized")
                }
                outFile.parentFile.mkdirs()
                copyTo(outFile)
            }
        }

        for (spec in SherpaOfflineProvisioning.MODEL_ASSET_SPECS) {
            val sourceFile = File(cachedModelDir, spec.relativePath)
            SherpaOfflineProvisioning.requireVerifiedFile(
                sourceFile,
                spec.expectedBytes,
                spec.expectedSha256,
            )
            val destFile = File(targetModelDir, spec.relativePath)
            destFile.parentFile.mkdirs()
            Files.copy(sourceFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            SherpaOfflineProvisioning.requireVerifiedFile(
                destFile,
                spec.expectedBytes,
                spec.expectedSha256,
            )
        }
        if (File(targetModelDir, SherpaOfflineProvisioning.FORBIDDEN_FP32_ENCODER).exists()) {
            throw GradleException("Forbidden unquantized FP32 encoder must not be packaged in assets")
        }
    }
}

val offlineAssetCacheDirProvider = providers.gradleProperty("ariaOfflineAssetCacheDir")
    .orElse(providers.environmentVariable("ARIA_OFFLINE_ASSET_CACHE_DIR"))
val persistentCacheDirPath = gradle.gradleUserHomeDir.resolve("caches/aria-offline-speech").absolutePath
val generatedSherpaAar = layout.buildDirectory.file("generated/sherpa-libs/sherpa-onnx-1.13.8.aar")

val provisionSherpaOfflineAar = tasks.register<ProvisionSherpaOfflineAarTask>("provisionSherpaOfflineAar") {
    configuredCacheDir.set(offlineAssetCacheDirProvider)
    defaultCacheDirPath.set(persistentCacheDirPath)
    outputAarFile.set(generatedSherpaAar)
}

android {
    namespace = "com.ariateam.aria"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.ariateam.aria"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        aidl = true
    }
    androidResources {
        noCompress += listOf("onnx", "wav", "model")
    }
}

androidComponents {
    onVariants { variant ->
        val capitalizedVariant = variant.name.replaceFirstChar { it.uppercaseChar() }
        val provisionAssetsTask = tasks.register<ProvisionSherpaOfflineAssetsTask>(
            "provision${capitalizedVariant}SherpaOfflineAssets"
        ) {
            configuredCacheDir.set(offlineAssetCacheDirProvider)
            defaultCacheDirPath.set(persistentCacheDirPath)
        }
        variant.sources.assets?.addGeneratedSourceDirectory(
            provisionAssetsTask,
            ProvisionSherpaOfflineAssetsTask::outputAssetsDir,
        )
    }
}

tasks.named("preBuild") {
    dependsOn(provisionSherpaOfflineAar)
}

dependencies {
    implementation(files(provisionSherpaOfflineAar.flatMap { it.outputAarFile }).builtBy(provisionSherpaOfflineAar))
    implementation(libs.litert.lm)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
