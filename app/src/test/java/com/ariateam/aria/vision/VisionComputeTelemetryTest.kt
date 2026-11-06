package com.ariateam.aria.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

class VisionComputeTelemetryTest {

    private fun createMappedBuffer(size: Int = 64): Pair<File, java.nio.MappedByteBuffer> {
        val temp = File.createTempFile("telemetry_test_", ".tflite")
        temp.writeBytes(ByteArray(size))
        val raf = RandomAccessFile(temp, "r")
        val channel = raf.channel
        val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0L, temp.length())
        channel.close()
        raf.close()
        return temp to mapped
    }

    @Test
    fun gpuActive_reportsMixedComputationWithCpuPreAndPostprocessing() {
        val telemetry = VisionComputeTelemetry.build(
            isCameraRunning = true,
            pipelineMode = VisionPipelineMode.PREPROCESS_OR_INFERENCE,
            modelState = VisionModelState.Ready(
                VisionModelMetadata("model", 1000L, "hash", 0L, "LiteRT GPU FP32 (CompiledModel)")
            ),
            configuredBackend = VisionBackendType.GPU,
            configuredCpuThreads = 4,
            activeBackend = VisionBackendType.GPU,
            activeBackendDescription = "LiteRT GPU FP32 (CompiledModel)",
            isInvocationReady = true,
            gpuFallbackCause = null,
            npuFallbackCause = null,
        )

        assertTrue("Pipeline must be active", telemetry.isPipelineActive)
        assertTrue("Model inference must be active", telemetry.isModelInferenceActive)
        assertTrue("Must be mixed computation (GPU inference + CPU pre/post)", telemetry.isMixedComputation)
        assertEquals(setOf(VisionComputeDevice.CPU, VisionComputeDevice.GPU), telemetry.activeDevices)
        assertFalse("Must not be CPU fallback", telemetry.isCpuFallback)
        assertNull("Fallback reason must be null", telemetry.fallbackReason)
        assertNull("GPU does not have unverified partition uncertainty", telemetry.partitionUncertainty)
        assertEquals("CPU + GPU (추론 GPU·전후처리 CPU)", telemetry.badgeLabelKorean)

        // Check inference participants: 1 GPU participant with SESSION_INITIALIZED
        assertEquals(1, telemetry.inferenceParticipants.size)
        val infParticipant = telemetry.inferenceParticipants[0]
        assertEquals(VisionComputeDevice.GPU, infParticipant.device)
        assertEquals(VisionPipelineStage.INFERENCE, infParticipant.stage)
        assertEquals(ComputeEvidenceLevel.SESSION_INITIALIZED, infParticipant.evidenceLevel)
        assertTrue(infParticipant.isAccelerated)

        // Check full pipeline participants: CPU Preprocessing, GPU Inference, CPU Postprocessing, CPU Hazard
        val stages = telemetry.pipelineParticipants.map { it.stage }
        assertEquals(
            listOf(
                VisionPipelineStage.PREPROCESSING,
                VisionPipelineStage.INFERENCE,
                VisionPipelineStage.POSTPROCESSING,
                VisionPipelineStage.HAZARD_EVALUATION,
            ),
            stages,
        )
        val preParticipant = telemetry.pipelineParticipants.first { it.stage == VisionPipelineStage.PREPROCESSING }
        assertEquals(VisionComputeDevice.CPU, preParticipant.device)
        assertEquals(ComputeEvidenceLevel.STATIC_DETERMINISTIC, preParticipant.evidenceLevel)
    }

    @Test
    fun npuRequested_reportsUnverifiedPartitionWithoutFakedHardwareExecutionProof() {
        val telemetry = VisionComputeTelemetry.build(
            isCameraRunning = true,
            pipelineMode = VisionPipelineMode.PREPROCESS_OR_INFERENCE,
            modelState = VisionModelState.Ready(
                VisionModelMetadata("model", 1000L, "hash", 0L, "LiteRT NPU (CompiledModel)")
            ),
            configuredBackend = VisionBackendType.NPU,
            configuredCpuThreads = 4,
            activeBackend = VisionBackendType.NPU,
            activeBackendDescription = "LiteRT NPU (CompiledModel · NPU/CPU 가속기 [요청됨/혼합 미검증])",
            isInvocationReady = true,
            gpuFallbackCause = null,
            npuFallbackCause = null,
        )

        assertTrue(telemetry.isPipelineActive)
        assertTrue(telemetry.isModelInferenceActive)
        // Coordinator requirement: Unverified NPU must NOT count in activeDevices or isMixedComputation as confirmed actual participants
        assertFalse("Unverified NPU must NOT be counted as confirmed mixed computation", telemetry.isMixedComputation)
        assertEquals("Active devices must only contain confirmed CPU participant", setOf(VisionComputeDevice.CPU), telemetry.activeDevices)
        assertEquals(setOf(VisionComputeDevice.NPU, VisionComputeDevice.CPU), telemetry.requestedDevices)
        assertFalse(telemetry.isCpuFallback)
        assertEquals("NPU 요청 + CPU (실행 분할 미확인)", telemetry.badgeLabelKorean)

        // Explicit disclosure of LiteRT SDK partition limitation
        assertNotNull("Partition uncertainty must be present for NPU", telemetry.partitionUncertainty)
        assertTrue(telemetry.partitionUncertainty!!.contains("SDK"))
        assertTrue(telemetry.partitionUncertainty!!.contains("확인되지 않습니다"))

        // Inference participants must clearly state REQUESTED_UNVERIFIED_PARTITION
        assertEquals(2, telemetry.inferenceParticipants.size)
        val npuParticipant = telemetry.inferenceParticipants.first { it.device == VisionComputeDevice.NPU }
        assertEquals(ComputeEvidenceLevel.REQUESTED_UNVERIFIED_PARTITION, npuParticipant.evidenceLevel)
        assertTrue(npuParticipant.isAccelerated)

        val cpuInferenceParticipant = telemetry.inferenceParticipants.first { it.device == VisionComputeDevice.CPU }
        assertEquals(ComputeEvidenceLevel.REQUESTED_UNVERIFIED_PARTITION, cpuInferenceParticipant.evidenceLevel)
        assertFalse(cpuInferenceParticipant.isAccelerated)
    }

    @Test
    fun cpuFallback_replacesStaleAcceleratorStateAndReportsDegradedPureCpu() {
        val gpuError = IllegalStateException("OpenGL ES 3.1 context initialization failed")
        val npuError = UnsupportedOperationException("NPU accelerator unsupported or vendor runtime not ready")

        val telemetry = VisionComputeTelemetry.build(
            isCameraRunning = true,
            pipelineMode = VisionPipelineMode.PREPROCESS_OR_INFERENCE,
            modelState = VisionModelState.Ready(
                VisionModelMetadata("model", 1000L, "hash", 0L, "LiteRT CPU FP32 [저성능 폴백]")
            ),
            configuredBackend = VisionBackendType.NPU,
            configuredCpuThreads = 4,
            activeBackend = VisionBackendType.CPU,
            activeBackendDescription = "LiteRT CPU FP32 [저성능 폴백: NPU 초기화 실패, GPU 초기화 실패, 4스레드]",
            isInvocationReady = true,
            gpuFallbackCause = gpuError,
            npuFallbackCause = npuError,
        )

        assertTrue(telemetry.isPipelineActive)
        assertTrue(telemetry.isModelInferenceActive)
        assertTrue("CPU fallback must be true", telemetry.isCpuFallback)
        assertNotNull(telemetry.fallbackReason)
        assertTrue(telemetry.fallbackReason!!.contains("NPU 실패"))
        assertTrue(telemetry.fallbackReason!!.contains("GPU 실패"))

        // Pure CPU: both NPU and GPU MUST BE ABSENT from active devices!
        assertEquals("Active devices must contain only CPU during fallback", setOf(VisionComputeDevice.CPU), telemetry.activeDevices)
        assertFalse("Pure CPU pipeline must NOT be mixed computation", telemetry.isMixedComputation)
        assertEquals("CPU 폴백", telemetry.badgeLabelKorean)

        // Inference participant is CPU with EXECUTION_VERIFIED
        assertEquals(1, telemetry.inferenceParticipants.size)
        val inf = telemetry.inferenceParticipants[0]
        assertEquals(VisionComputeDevice.CPU, inf.device)
        assertEquals(ComputeEvidenceLevel.EXECUTION_VERIFIED, inf.evidenceLevel)
        assertFalse(inf.isAccelerated)
        assertTrue(inf.detailKorean.contains("저성능 폴백"))
    }

    @Test
    fun cameraStopped_resetsActiveComputeToEmpty() {
        val telemetry = VisionComputeTelemetry.build(
            isCameraRunning = false,
            pipelineMode = VisionPipelineMode.PREPROCESS_OR_INFERENCE,
            modelState = VisionModelState.Ready(
                VisionModelMetadata("model", 1000L, "hash", 0L, "LiteRT GPU FP32")
            ),
            configuredBackend = VisionBackendType.GPU,
            configuredCpuThreads = 4,
            activeBackend = VisionBackendType.GPU,
            activeBackendDescription = "LiteRT GPU FP32 (CompiledModel)",
            isInvocationReady = true,
            gpuFallbackCause = null,
            npuFallbackCause = null,
        )

        assertFalse("Pipeline must be inactive when camera is stopped", telemetry.isPipelineActive)
        assertFalse("Model inference must be inactive when camera is stopped", telemetry.isModelInferenceActive)
        assertTrue("Active devices must be empty when camera is stopped", telemetry.activeDevices.isEmpty())
        assertFalse(telemetry.isMixedComputation)
        assertTrue(telemetry.pipelineParticipants.isEmpty())
        assertTrue(telemetry.inferenceParticipants.isEmpty())
        assertNull(telemetry.activeBackend)
        assertEquals("카메라 정지 (비활성)", telemetry.badgeLabelKorean)
    }

    @Test
    fun modelLoadingOrErrorOrMissing_showsPreprocessingCpuOnlyAndNoInferenceCompute() {
        val missingTelemetry = VisionComputeTelemetry.build(
            isCameraRunning = true,
            pipelineMode = VisionPipelineMode.PREPROCESS_OR_INFERENCE,
            modelState = VisionModelState.Missing,
            configuredBackend = VisionBackendType.GPU,
            configuredCpuThreads = 4,
            activeBackend = null,
            activeBackendDescription = "N/A (모델 미적용)",
            isInvocationReady = false,
            gpuFallbackCause = null,
            npuFallbackCause = null,
        )

        assertTrue(missingTelemetry.isPipelineActive)
        assertFalse("Inference must be inactive when model is missing", missingTelemetry.isModelInferenceActive)
        assertEquals(setOf(VisionComputeDevice.CPU), missingTelemetry.activeDevices)
        assertFalse(missingTelemetry.isMixedComputation)
        assertTrue(missingTelemetry.inferenceParticipants.isEmpty())
        assertEquals(1, missingTelemetry.pipelineParticipants.size)
        assertEquals(VisionPipelineStage.PREPROCESSING, missingTelemetry.pipelineParticipants[0].stage)
        assertEquals("모델 미적용 (전처리 CPU)", missingTelemetry.badgeLabelKorean)

        val loadingTelemetry = VisionComputeTelemetry.build(
            isCameraRunning = true,
            pipelineMode = VisionPipelineMode.PREPROCESS_OR_INFERENCE,
            modelState = VisionModelState.Loading("model.tflite"),
            configuredBackend = VisionBackendType.GPU,
            configuredCpuThreads = 4,
            activeBackend = null,
            activeBackendDescription = "N/A (모델 준비 중)",
            isInvocationReady = false,
            gpuFallbackCause = null,
            npuFallbackCause = null,
        )

        assertFalse(loadingTelemetry.isModelInferenceActive)
        assertEquals(setOf(VisionComputeDevice.CPU), loadingTelemetry.activeDevices)
        assertTrue(loadingTelemetry.inferenceParticipants.isEmpty())
        assertEquals("모델 준비 중 (전처리 CPU)", loadingTelemetry.badgeLabelKorean)

        val errorTelemetry = VisionComputeTelemetry.build(
            isCameraRunning = true,
            pipelineMode = VisionPipelineMode.PREPROCESS_OR_INFERENCE,
            modelState = VisionModelState.Error("Init failed", "Detail"),
            configuredBackend = VisionBackendType.GPU,
            configuredCpuThreads = 4,
            activeBackend = null,
            activeBackendDescription = "N/A (모델 오류)",
            isInvocationReady = false,
            gpuFallbackCause = null,
            npuFallbackCause = null,
        )

        assertFalse(errorTelemetry.isModelInferenceActive)
        assertEquals(setOf(VisionComputeDevice.CPU), errorTelemetry.activeDevices)
        assertTrue(errorTelemetry.inferenceParticipants.isEmpty())
        assertEquals("모델 오류 발생 (전처리 CPU)", errorTelemetry.badgeLabelKorean)
    }

    @Test
    fun previewOnlyMode_omitsAnalysisAndShowsEmptyActiveDevices() {
        val telemetry = VisionComputeTelemetry.build(
            isCameraRunning = true,
            pipelineMode = VisionPipelineMode.PREVIEW_ONLY,
            modelState = VisionModelState.Ready(
                VisionModelMetadata("model", 1000L, "hash", 0L, "LiteRT GPU FP32")
            ),
            configuredBackend = VisionBackendType.GPU,
            configuredCpuThreads = 4,
            activeBackend = VisionBackendType.GPU,
            activeBackendDescription = "LiteRT GPU FP32",
            isInvocationReady = true,
            gpuFallbackCause = null,
            npuFallbackCause = null,
        )

        // Coordinator correction: preview binding omits analysis entirely; no analysis compute known
        assertFalse(telemetry.isPipelineActive)
        assertFalse(telemetry.isModelInferenceActive)
        assertTrue("Preview-only mode must have empty active devices", telemetry.activeDevices.isEmpty())
        assertFalse(telemetry.isMixedComputation)
        assertTrue(telemetry.inferenceParticipants.isEmpty())
        assertTrue(telemetry.pipelineParticipants.isEmpty())
        assertEquals("미리보기 전용 (분석기 비활성)", telemetry.badgeLabelKorean)
    }

    @Test
    fun segmenterCurrentSessionSnapshot_andUnloadModel_clearsStaleAcceleratorState() {
        val (tempFile, mapped) = createMappedBuffer()
        try {
            val segmenter = LiteRtVisionSegmenter(
                sessionBuilderForTesting = { buffer, backend, threads, _ ->
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
                // Session not built yet -> snapshot shows null active backend
                val initialSnapshot = segmenter.currentSessionSnapshot()
                assertNull(initialSnapshot.activeBackendType)
                assertEquals("N/A (모델 미적용)", initialSnapshot.activeBackendDescription)
                assertFalse(initialSnapshot.isInvocationReady)

                // Build and commit session
                val session = segmenter.buildSession(mapped, VisionBackendType.GPU, 4)
                segmenter.commitSession(session)

                val activeSnapshot = segmenter.currentSessionSnapshot()
                assertEquals(VisionBackendType.GPU, activeSnapshot.activeBackendType)
                assertTrue(activeSnapshot.isInvocationReady)
                assertEquals("Fake GPU Session", activeSnapshot.activeBackendDescription)

                val activeTelemetry = segmenter.currentComputeTelemetry(
                    isCameraRunning = true,
                    pipelineMode = VisionPipelineMode.PREPROCESS_OR_INFERENCE,
                    modelState = VisionModelState.Ready(
                        VisionModelMetadata("model", 1000L, "hash", 0L, "Fake GPU Session")
                    ),
                )
                assertTrue(activeTelemetry.isModelInferenceActive)
                assertTrue(activeTelemetry.isMixedComputation)
                assertEquals(setOf(VisionComputeDevice.CPU, VisionComputeDevice.GPU), activeTelemetry.activeDevices)

                // Unload model -> snapshot and telemetry must reset cleanly
                segmenter.unloadModel()
                val unloadedSnapshot = segmenter.currentSessionSnapshot()
                assertNull(unloadedSnapshot.activeBackendType)
                assertFalse(unloadedSnapshot.isInvocationReady)
                assertEquals("N/A (모델 미적용)", unloadedSnapshot.activeBackendDescription)

                val unloadedTelemetry = segmenter.currentComputeTelemetry(
                    isCameraRunning = true,
                    pipelineMode = VisionPipelineMode.PREPROCESS_OR_INFERENCE,
                    modelState = VisionModelState.Missing,
                )
                assertFalse(unloadedTelemetry.isModelInferenceActive)
                assertEquals(setOf(VisionComputeDevice.CPU), unloadedTelemetry.activeDevices)
                assertTrue(unloadedTelemetry.inferenceParticipants.isEmpty())
            } finally {
                segmenter.close()
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun closedSession_inSnapshotAndComputeTelemetry_reportsInactiveWithoutStaleAccelerator() {
        val (tempFile, mapped) = createMappedBuffer()
        try {
            val session = LiteRtVisionSegmenter.InferenceSession(
                interpreter = null,
                gpuDelegate = null,
                mappedBuffer = mapped,
                backendType = VisionBackendType.GPU,
                cpuThreads = 4,
                backendDescription = "GPU Session to close",
                runInferenceForTesting = { _, _ -> },
            )
            session.isInvocationReady = true

            // When open
            val openTelemetry = session.computeTelemetry()
            assertTrue(openTelemetry.isModelInferenceActive)
            assertEquals(VisionBackendType.GPU, openTelemetry.activeBackend)

            // When explicitly closed
            session.close()
            assertTrue(session.isClosed)

            // Closed session must immediately report INACTIVE without stale active compute
            val closedTelemetry = session.computeTelemetry()
            assertEquals(VisionComputeTelemetry.INACTIVE, closedTelemetry)
            assertFalse(closedTelemetry.isModelInferenceActive)
            assertTrue(closedTelemetry.activeDevices.isEmpty())
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun inferenceSessionComputeTelemetry_withoutSyntheticMetadata_reflectsSessionDirectly() {
        val (tempFile, mapped) = createMappedBuffer()
        try {
            val session = LiteRtVisionSegmenter.InferenceSession(
                interpreter = null,
                gpuDelegate = null,
                mappedBuffer = mapped,
                backendType = VisionBackendType.NPU,
                cpuThreads = 4,
                backendDescription = "LiteRT NPU (CompiledModel · NPU/CPU 가속기 [요청됨/혼합 미검증])",
                runInferenceForTesting = { _, _ -> },
            )
            session.isInvocationReady = true

            val telemetry = session.computeTelemetry()
            assertTrue(telemetry.isModelInferenceActive)
            assertEquals(VisionBackendType.NPU, telemetry.activeBackend)
            // Unverified NPU does not count as confirmed active participant in activeDevices
            assertEquals(setOf(VisionComputeDevice.CPU), telemetry.activeDevices)
            assertEquals(setOf(VisionComputeDevice.NPU, VisionComputeDevice.CPU), telemetry.requestedDevices)
            assertFalse(telemetry.isMixedComputation)
            assertNotNull(telemetry.partitionUncertainty)
            session.close()
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun uiStateDefaults_andComputeTelemetryInactiveInstance() {
        val uiState = VisionUiState()
        assertEquals(VisionComputeTelemetry.INACTIVE, uiState.computeTelemetry)
        assertFalse(uiState.computeTelemetry.isPipelineActive)
        assertFalse(uiState.computeTelemetry.isModelInferenceActive)
        assertTrue(uiState.computeTelemetry.activeDevices.isEmpty())
        assertEquals("컴퓨팅 비활성", uiState.computeTelemetry.badgeLabelKorean)
    }
}
