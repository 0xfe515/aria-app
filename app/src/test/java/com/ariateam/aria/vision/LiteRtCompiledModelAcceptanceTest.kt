package com.ariateam.aria.vision

import com.google.ai.edge.litert.CompiledModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicInteger

class LiteRtCompiledModelAcceptanceTest {

    private fun createMappedBuffer(size: Int = 64): Pair<File, java.nio.MappedByteBuffer> {
        val temp = File.createTempFile("acceptance_test_", ".tflite")
        temp.writeBytes(ByteArray(size))
        val raf = RandomAccessFile(temp, "r")
        val channel = raf.channel
        val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0L, temp.length())
        channel.close()
        raf.close()
        return temp to mapped
    }

    @Test
    fun boundedCascade_attemptsNpuThenGpuThenCpuAtMostOnce() {
        val (tempFile, mapped) = createMappedBuffer()
        try {
            val attempts = mutableListOf<VisionBackendType>()

            val supportedNpu = object : NpuEnvironment() {
                override fun isNpuSupported(): Boolean = true
            }

            val segmenter = LiteRtVisionSegmenter(
                npuEnvironment = supportedNpu,
                sessionBuilderForTesting = { buffer, backend, threads, _ ->
                    attempts.add(backend)
                    when (backend) {
                        VisionBackendType.NPU -> {
                            LiteRtVisionSegmenter.InferenceSession(
                                interpreter = null,
                                gpuDelegate = null,
                                mappedBuffer = buffer,
                                backendType = backend,
                                cpuThreads = threads,
                                backendDescription = "Fake NPU",
                                runInferenceForTesting = { _, _ ->
                                    throw IllegalStateException("NPU execution failed")
                                },
                            )
                        }
                        VisionBackendType.GPU -> {
                            LiteRtVisionSegmenter.InferenceSession(
                                interpreter = null,
                                gpuDelegate = null,
                                mappedBuffer = buffer,
                                backendType = backend,
                                cpuThreads = threads,
                                backendDescription = "Fake GPU",
                                runInferenceForTesting = { _, _ ->
                                    throw IllegalStateException("GPU execution failed")
                                },
                            )
                        }
                        VisionBackendType.CPU -> {
                            LiteRtVisionSegmenter.InferenceSession(
                                interpreter = null,
                                gpuDelegate = null,
                                mappedBuffer = buffer,
                                backendType = backend,
                                cpuThreads = threads,
                                backendDescription = "Fake CPU",
                                runInferenceForTesting = { _, _ -> },
                            )
                        }
                    }
                },
            )

            try {
                val session = segmenter.buildSession(mapped, VisionBackendType.NPU, 4)
                assertEquals(listOf(VisionBackendType.NPU, VisionBackendType.GPU, VisionBackendType.CPU), attempts)
                assertEquals(VisionBackendType.CPU, session.backendType)
                assertNotNull(session.npuFallbackCause)
                assertNotNull(session.gpuFallbackCause)
                assertTrue(session.backendDescription.startsWith("LiteRT CPU FP32 [저성능 폴백:"))
                assertTrue(session.backendDescription.contains("NPU 초기화 실패"))
                assertTrue(session.backendDescription.contains("GPU 초기화 실패"))
                session.close()
            } finally {
                segmenter.close()
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun modelValidationException_stopsImmediatelyWithoutFallback() {
        val (tempFile, mapped) = createMappedBuffer()
        try {
            val attempts = mutableListOf<VisionBackendType>()
            val supportedNpu = object : NpuEnvironment() {
                override fun isNpuSupported(): Boolean = true
            }

            val segmenter = LiteRtVisionSegmenter(
                npuEnvironment = supportedNpu,
                sessionBuilderForTesting = { _, backend, _, _ ->
                    attempts.add(backend)
                    throw LiteRtVisionSegmenter.ModelValidationException("Invalid model tensor dimension")
                },
            )

            try {
                segmenter.buildSession(mapped, VisionBackendType.NPU, 4)
                fail("ModelValidationException should have been thrown")
            } catch (e: LiteRtVisionSegmenter.ModelValidationException) {
                assertEquals("Invalid model tensor dimension", e.message)
                assertEquals(listOf(VisionBackendType.NPU), attempts)
            } finally {
                segmenter.close()
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun fatalError_propagatesImmediatelyWithoutFallback() {
        val (tempFile, mapped) = createMappedBuffer()
        try {
            val attempts = mutableListOf<VisionBackendType>()
            val supportedNpu = object : NpuEnvironment() {
                override fun isNpuSupported(): Boolean = true
            }

            val segmenter = LiteRtVisionSegmenter(
                npuEnvironment = supportedNpu,
                sessionBuilderForTesting = { _, backend, _, _ ->
                    attempts.add(backend)
                    throw OutOfMemoryError("Native buffer allocation failed")
                },
            )

            try {
                segmenter.buildSession(mapped, VisionBackendType.NPU, 4)
                fail("OutOfMemoryError should have propagated directly")
            } catch (e: OutOfMemoryError) {
                assertEquals("Native buffer allocation failed", e.message)
                assertEquals(listOf(VisionBackendType.NPU), attempts)
            } finally {
                segmenter.close()
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun sessionClose_isIdempotentAndInvokesCallbackOnce() {
        val (tempFile, mapped) = createMappedBuffer()
        try {
            val closeCount = AtomicInteger(0)
            val session = LiteRtVisionSegmenter.InferenceSession(
                interpreter = null,
                gpuDelegate = null,
                mappedBuffer = mapped,
                backendType = VisionBackendType.GPU,
                cpuThreads = 4,
                backendDescription = "Test Session",
                runInferenceForTesting = { _, _ ->
                    throw IllegalStateException("Warmup failed intentionally")
                },
                closeForTesting = {
                    closeCount.incrementAndGet()
                },
            )

            try {
                session.warmup()
                fail("Warmup should have failed")
            } catch (_: IllegalStateException) {
                // warmup caught error and called close()
            }

            assertTrue("Session should be closed after failed warmup", session.isClosed)
            assertEquals("Callback should be invoked exactly once on warmup failure", 1, closeCount.get())

            // Second explicit close must be a no-op
            session.close()
            assertEquals("Callback must not be invoked again on repeated close", 1, closeCount.get())
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun missingNpuNativeFiles_bypassesNpuAndDefaultsToGpu() {
        val (tempFile, mapped) = createMappedBuffer()
        try {
            val attempts = mutableListOf<VisionBackendType>()

            val unreadyNpu = object : NpuEnvironment() {
                override fun isNpuSupported(): Boolean = false
            }

            val segmenter = LiteRtVisionSegmenter(
                npuEnvironment = unreadyNpu,
                sessionBuilderForTesting = { buffer, backend, threads, _ ->
                    attempts.add(backend)
                    LiteRtVisionSegmenter.InferenceSession(
                        interpreter = null,
                        gpuDelegate = null,
                        mappedBuffer = buffer,
                        backendType = backend,
                        cpuThreads = threads,
                        backendDescription = "Fake GPU Session",
                        runInferenceForTesting = { _, _ -> },
                    )
                },
            )

            try {
                val session = segmenter.buildSession(mapped, VisionBackendType.NPU, 4)
                assertEquals(VisionBackendType.GPU, session.backendType)
                assertEquals(listOf(VisionBackendType.GPU), attempts)
                assertNotNull(session.npuFallbackCause)
                assertTrue(session.backendDescription.contains("NPU 초기화 실패"))
                assertTrue(session.backendDescription.startsWith("LiteRT GPU FP32"))
                session.close()
            } finally {
                segmenter.close()
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun originalModelFile_isPreservedOnClose() {
        val (tempFile, mapped) = createMappedBuffer()
        try {
            assertTrue("Original model file must exist before session", tempFile.exists())

            val session = LiteRtVisionSegmenter.InferenceSession(
                interpreter = null,
                gpuDelegate = null,
                mappedBuffer = mapped,
                backendType = VisionBackendType.GPU,
                cpuThreads = 4,
                backendDescription = "Preserve Original Test",
                runInferenceForTesting = { _, _ -> },
                modelFile = tempFile,
                ownedTempFile = null,
            )

            session.close()
            assertTrue("Original model file must NOT be deleted on close", tempFile.exists())
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun ownedTempFile_isCleanedUpOnClose() {
        val (origFile, mapped) = createMappedBuffer()
        val tempOwned = File.createTempFile("temp_owned_", ".tflite")
        tempOwned.writeBytes(ByteArray(16))
        try {
            assertTrue("Temp owned file must exist before close", tempOwned.exists())

            val session = LiteRtVisionSegmenter.InferenceSession(
                interpreter = null,
                gpuDelegate = null,
                mappedBuffer = mapped,
                backendType = VisionBackendType.CPU,
                cpuThreads = 4,
                backendDescription = "Clean Temp Test",
                runInferenceForTesting = { _, _ -> },
                modelFile = null,
                ownedTempFile = tempOwned,
            )

            session.close()
            assertFalse("Owned temp file must be deleted on close", tempOwned.exists())
        } finally {
            origFile.delete()
            if (tempOwned.exists()) {
                tempOwned.delete()
            }
        }
    }

    @Test
    fun npuVendorPairAndRuntimeReadiness_qualcommRequiresBothPairAndHtpRuntime() {
        val tempDir = File.createTempFile("native_libs_", "").apply {
            delete()
            mkdirs()
        }
        try {
            // Case 1: Missing both
            assertFalse(
                NpuEnvironment.isVendorPairAndRuntimeReady(
                    SupportedNpuVendor.QUALCOMM,
                    tempDir,
                    emptyList(),
                )
            )

            // Case 2: Matching Qualcomm pair present, but no Qnn HTP runtime (even if QnnSystem is present)
            File(tempDir, "libLiteRtCompilerPlugin_Qualcomm.so").createNewFile()
            File(tempDir, "libLiteRtDispatch_Qualcomm.so").createNewFile()
            File(tempDir, "libQnnSystem.so").createNewFile()

            assertFalse(
                "QnnSystem alone cannot establish HTP readiness",
                NpuEnvironment.isVendorPairAndRuntimeReady(
                    SupportedNpuVendor.QUALCOMM,
                    tempDir,
                    emptyList(),
                )
            )

            // Case 3: libQnnHtp.so present in nativeDir
            val htpFile = File(tempDir, "libQnnHtp.so")
            htpFile.createNewFile()
            assertTrue(
                "Matching pair + local libQnnHtp.so must report ready",
                NpuEnvironment.isVendorPairAndRuntimeReady(
                    SupportedNpuVendor.QUALCOMM,
                    tempDir,
                    emptyList(),
                )
            )

            // Case 4: libQnnHtp.so in systemLibDirs instead of nativeDir
            htpFile.delete()
            val sysDir = File.createTempFile("sys_libs_", "").apply {
                delete()
                mkdirs()
            }
            try {
                File(sysDir, "libQnnHtp.so").createNewFile()
                assertTrue(
                    "Matching pair + system libQnnHtp.so must report ready",
                    NpuEnvironment.isVendorPairAndRuntimeReady(
                        SupportedNpuVendor.QUALCOMM,
                        tempDir,
                        listOf(sysDir),
                    )
                )
            } finally {
                sysDir.deleteRecursively()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun npuVendorPairAndRuntimeReadiness_mismatchedPairsAndUnestablishedVendorsRejected() {
        val tempDir = File.createTempFile("native_libs_mismatch_", "").apply {
            delete()
            mkdirs()
        }
        try {
            // Mismatched pair: Qualcomm plugin + MediaTek dispatch
            File(tempDir, "libLiteRtCompilerPlugin_Qualcomm.so").createNewFile()
            File(tempDir, "libLiteRtDispatch_MediaTek.so").createNewFile()
            File(tempDir, "libQnnHtp.so").createNewFile()

            assertFalse(
                "Mismatched vendor plugin and dispatch must be rejected",
                NpuEnvironment.isVendorPairAndRuntimeReady(
                    SupportedNpuVendor.QUALCOMM,
                    tempDir,
                    emptyList(),
                )
            )

            // MediaTek matching pair: rejected because MediaTek vendor runtime is unestablished
            tempDir.listFiles()?.forEach { it.delete() }
            File(tempDir, "libLiteRtCompilerPlugin_MediaTek.so").createNewFile()
            File(tempDir, "libLiteRtDispatch_MediaTek.so").createNewFile()
            assertFalse(
                "MediaTek must be conservatively disabled until vendor runtime is established",
                NpuEnvironment.isVendorPairAndRuntimeReady(
                    SupportedNpuVendor.MEDIATEK,
                    tempDir,
                    emptyList(),
                )
            )

            // Google Tensor matching pair: rejected because Tensor vendor runtime is unestablished
            tempDir.listFiles()?.forEach { it.delete() }
            File(tempDir, "libLiteRtCompilerPlugin_google_tensor.so").createNewFile()
            File(tempDir, "libLiteRtDispatch_GoogleTensor.so").createNewFile()
            assertFalse(
                "Google Tensor must be conservatively disabled until vendor runtime is established",
                NpuEnvironment.isVendorPairAndRuntimeReady(
                    SupportedNpuVendor.GOOGLE_TENSOR,
                    tempDir,
                    emptyList(),
                )
            )

            // Unknown vendor
            assertFalse(
                NpuEnvironment.isVendorPairAndRuntimeReady(
                    SupportedNpuVendor.UNKNOWN,
                    tempDir,
                    emptyList(),
                )
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun gpuPrecisionPolicy_resolvesActualFp32InProductionAndFp16OnlyInDiagnosticExplicit() {
        // 1. Production policy: regardless of precisionLossAllowed (false or true), actual options must be FP32
        val prodSegmenter = LiteRtVisionSegmenter(inferencePolicy = ProductionGpuOnlyPolicy)
        try {
            val prodFalseOpts = prodSegmenter.resolveGpuOptions(precisionLossAllowed = false)
            assertEquals(
                "Production with precisionLossAllowed=false must configure FP32",
                CompiledModel.GpuOptions.Precision.FP32,
                prodFalseOpts.precision,
            )
            assertEquals(
                "LiteRT GPU FP32 (CompiledModel)",
                prodSegmenter.resolveGpuBackendDescription(precisionLossAllowed = false),
            )

            val prodTrueOpts = prodSegmenter.resolveGpuOptions(precisionLossAllowed = true)
            assertEquals(
                "Production with precisionLossAllowed=true must still enforce FP32",
                CompiledModel.GpuOptions.Precision.FP32,
                prodTrueOpts.precision,
            )
            assertEquals(
                "Production description must remain FP32 regardless of precisionLossAllowed",
                "LiteRT GPU FP32 (CompiledModel)",
                prodSegmenter.resolveGpuBackendDescription(precisionLossAllowed = true),
            )
        } finally {
            prodSegmenter.close()
        }

        // 2. Diagnostic policy: FP32 when false, FP16 only when explicitly true
        val diagSegmenter = LiteRtVisionSegmenter(inferencePolicy = DiagnosticInferencePolicy())
        try {
            val diagFalseOpts = diagSegmenter.resolveGpuOptions(precisionLossAllowed = false)
            assertEquals(
                "Diagnostic with precisionLossAllowed=false must configure FP32",
                CompiledModel.GpuOptions.Precision.FP32,
                diagFalseOpts.precision,
            )
            assertEquals(
                "LiteRT GPU FP32 (CompiledModel)",
                diagSegmenter.resolveGpuBackendDescription(precisionLossAllowed = false),
            )

            val diagTrueOpts = diagSegmenter.resolveGpuOptions(precisionLossAllowed = true)
            assertEquals(
                "Diagnostic with precisionLossAllowed=true must configure FP16",
                CompiledModel.GpuOptions.Precision.FP16,
                diagTrueOpts.precision,
            )
            assertEquals(
                "LiteRT GPU FP16 [진단 전용] (CompiledModel)",
                diagSegmenter.resolveGpuBackendDescription(precisionLossAllowed = true),
            )
        } finally {
            diagSegmenter.close()
        }

        // 3. Direct GpuPrecisionPolicy factory validation
        assertEquals(
            CompiledModel.GpuOptions.Precision.FP32,
            GpuPrecisionPolicy.resolveEffectivePrecision(isDiagnosticPolicy = false, precisionLossAllowed = true),
        )
        assertEquals(
            CompiledModel.GpuOptions.Precision.FP32,
            GpuPrecisionPolicy.resolveEffectivePrecision(isDiagnosticPolicy = false, precisionLossAllowed = false),
        )
        assertEquals(
            CompiledModel.GpuOptions.Precision.FP32,
            GpuPrecisionPolicy.resolveEffectivePrecision(isDiagnosticPolicy = true, precisionLossAllowed = false),
        )
        assertEquals(
            CompiledModel.GpuOptions.Precision.FP16,
            GpuPrecisionPolicy.resolveEffectivePrecision(isDiagnosticPolicy = true, precisionLossAllowed = true),
        )
    }
}
