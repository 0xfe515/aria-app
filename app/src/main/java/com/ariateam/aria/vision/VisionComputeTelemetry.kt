package com.ariateam.aria.vision

/**
 * Computational hardware devices participating in the vision pipeline.
 */
enum class VisionComputeDevice(val koreanName: String) {
    CPU("CPU"),
    GPU("GPU"),
    NPU("NPU"),
}

/**
 * Stages in the end-to-end vision processing pipeline.
 */
enum class VisionPipelineStage(val koreanName: String) {
    PREPROCESSING("입력 전처리 (YUV 변환·회전·정규화)"),
    INFERENCE("모델 추론 (CompiledModel)"),
    POSTPROCESSING("출력 후처리 (Argmax·마스크·시각화)"),
    HAZARD_EVALUATION("위험도 판단 (경로 분석·알림)"),
}

/**
 * Evidence strength / verification level for hardware participation telemetry.
 *
 * Distinguishes statically deterministic CPU stages, session-initialized accelerator delegates,
 * verified CPU executions, and unverified partition options in LiteRT.
 */
enum class ComputeEvidenceLevel(val koreanDescription: String) {
    /** Statically deterministic CPU execution (e.g. Kotlin/native YUV preprocessing, argmax). */
    STATIC_DETERMINISTIC("결정적 실행 (고정 CPU 코드)"),

    /** Explicitly verified runtime execution (e.g. CPU fallback execution). */
    EXECUTION_VERIFIED("실제 실행 검증됨 (CPU 세션)"),

    /** Accelerator delegate initialized and warmed up without fallback (e.g. GPU FP32 CompiledModel). */
    SESSION_INITIALIZED("세션 초기화 성공 (GPU 델리게이트 정상 기동)"),

    /**
     * Accelerator requested in session options (e.g. LiteRT NPU+CPU), but the underlying
     * LiteRT SDK provides NO partition or runtime delegate execution breakdown API.
     * Must NOT be reported as proven NPU execution.
     */
    REQUESTED_UNVERIFIED_PARTITION("가속기 요청됨 (SDK 분할 및 오프로드 정보 미제공/미검증)"),

    /** Component or stage is inactive, stopped, or unloaded. */
    NOT_APPLICABLE("비활성 (미실행)"),
}

/**
 * Telemetry record for an individual participant in computation.
 */
data class ComputeParticipant(
    val device: VisionComputeDevice,
    val stage: VisionPipelineStage,
    val evidenceLevel: ComputeEvidenceLevel,
    val isAccelerated: Boolean,
    val detailKorean: String,
)

internal data class VisionSessionSnapshot(
    val configuredBackend: VisionBackendType = VisionBackendType.GPU,
    val configuredCpuThreads: Int = VisionContract.DEFAULT_CPU_THREADS,
    val activeBackendType: VisionBackendType? = null,
    val activeBackendDescription: String = "N/A (모델 미적용)",
    val isInvocationReady: Boolean = false,
    val gpuFallbackCause: Throwable? = null,
    val npuFallbackCause: Throwable? = null,
)

/**
 * Truthful, evidence-aware compute participation telemetry for the vision pipeline.
 *
 * Strictly accounts for:
 * - Preprocessing and postprocessing running on CPU regardless of model accelerator.
 * - Model inference running on GPU, NPU (requested/unverified mixed partition), or CPU fallback.
 * - Explicit disclosure of LiteRT SDK limitations (inability to verify actual NPU partition).
 * - Unverified NPU not counting in activeDevices or confirmed mixed computation.
 * - Preview-only camera mode disabling analysis and showing empty activeDevices.
 * - Instant clearing of stale accelerator state on camera stop, model error/unload, or CPU fallback.
 */
data class VisionComputeTelemetry(
    val isPipelineActive: Boolean = false,
    val isModelInferenceActive: Boolean = false,
    val activeDevices: Set<VisionComputeDevice> = emptySet(),
    val requestedDevices: Set<VisionComputeDevice> = emptySet(),
    val isMixedComputation: Boolean = false,
    val inferenceParticipants: List<ComputeParticipant> = emptyList(),
    val pipelineParticipants: List<ComputeParticipant> = emptyList(),
    val configuredBackend: VisionBackendType = VisionBackendType.GPU,
    val activeBackend: VisionBackendType? = null,
    val isCpuFallback: Boolean = false,
    val fallbackReason: String? = null,
    val partitionUncertainty: String? = null,
    val activeBackendDescription: String = "N/A (모델 미적용)",
    val badgeLabelKorean: String = "컴퓨팅 비활성",
    val summaryKorean: String = "컴퓨팅 비활성",
) {
    companion object {
        const val LITERT_NPU_SDK_LIMITATION_NOTE: String =
            "LiteRT SDK 2.3.2는 런타임 서브그래프 분할 및 NPU 실제 오프로드 비율 API를 제공하지 않으므로, " +
                "NPU 실행 여부는 확인되지 않습니다."

        val INACTIVE: VisionComputeTelemetry = VisionComputeTelemetry(
            isPipelineActive = false,
            isModelInferenceActive = false,
            activeDevices = emptySet(),
            requestedDevices = emptySet(),
            isMixedComputation = false,
            inferenceParticipants = emptyList(),
            pipelineParticipants = emptyList(),
            configuredBackend = VisionBackendType.GPU,
            activeBackend = null,
            isCpuFallback = false,
            fallbackReason = null,
            partitionUncertainty = null,
            activeBackendDescription = "N/A (비활성)",
            badgeLabelKorean = "컴퓨팅 비활성",
            summaryKorean = "카메라가 정지되었거나 모델이 로드되지 않아 연산이 비활성화되었습니다.",
        )

        /**
         * Builds an immutable [VisionComputeTelemetry] snapshot from the current vision runtime state and [VisionModelState].
         */
        fun build(
            isCameraRunning: Boolean,
            pipelineMode: VisionPipelineMode,
            modelState: VisionModelState,
            configuredBackend: VisionBackendType,
            configuredCpuThreads: Int,
            activeBackend: VisionBackendType?,
            activeBackendDescription: String,
            isInvocationReady: Boolean,
            gpuFallbackCause: Throwable?,
            npuFallbackCause: Throwable?,
        ): VisionComputeTelemetry {
            val isModelReady = modelState is VisionModelState.Ready && isInvocationReady && activeBackend != null
            val stateDesc = when (modelState) {
                is VisionModelState.Missing -> "모델 미적용"
                is VisionModelState.Loading -> "모델 준비 중"
                is VisionModelState.Error -> "모델 오류 발생"
                is VisionModelState.Ready -> "모델 워밍업 대기"
            }
            return build(
                isCameraRunning = isCameraRunning,
                pipelineMode = pipelineMode,
                isModelReady = isModelReady,
                modelStateDescription = stateDesc,
                configuredBackend = configuredBackend,
                configuredCpuThreads = configuredCpuThreads,
                activeBackend = activeBackend,
                activeBackendDescription = activeBackendDescription,
                isInvocationReady = isInvocationReady,
                gpuFallbackCause = gpuFallbackCause,
                npuFallbackCause = npuFallbackCause,
            )
        }

        /**
         * Builds an immutable [VisionComputeTelemetry] snapshot without requiring a synthetic [VisionModelState].
         */
        fun build(
            isCameraRunning: Boolean,
            pipelineMode: VisionPipelineMode,
            isModelReady: Boolean,
            modelStateDescription: String,
            configuredBackend: VisionBackendType,
            configuredCpuThreads: Int,
            activeBackend: VisionBackendType?,
            activeBackendDescription: String,
            isInvocationReady: Boolean,
            gpuFallbackCause: Throwable?,
            npuFallbackCause: Throwable?,
        ): VisionComputeTelemetry {
            // Case 1: Camera is not running -> no active compute in pipeline
            if (!isCameraRunning) {
                return VisionComputeTelemetry(
                    isPipelineActive = false,
                    isModelInferenceActive = false,
                    activeDevices = emptySet(),
                    requestedDevices = emptySet(),
                    isMixedComputation = false,
                    inferenceParticipants = emptyList(),
                    pipelineParticipants = emptyList(),
                    configuredBackend = configuredBackend,
                    activeBackend = null,
                    isCpuFallback = false,
                    fallbackReason = null,
                    partitionUncertainty = null,
                    activeBackendDescription = "N/A (카메라 정지)",
                    badgeLabelKorean = "카메라 정지 (비활성)",
                    summaryKorean = "카메라가 정지되어 파이프라인 연산이 수행되지 않습니다.",
                )
            }

            // Case 2: Camera running in preview-only mode -> preview binding omits analysis; no analysis compute known
            if (pipelineMode == VisionPipelineMode.PREVIEW_ONLY) {
                return VisionComputeTelemetry(
                    isPipelineActive = false,
                    isModelInferenceActive = false,
                    activeDevices = emptySet(),
                    requestedDevices = emptySet(),
                    isMixedComputation = false,
                    inferenceParticipants = emptyList(),
                    pipelineParticipants = emptyList(),
                    configuredBackend = configuredBackend,
                    activeBackend = null,
                    isCpuFallback = false,
                    fallbackReason = null,
                    partitionUncertainty = null,
                    activeBackendDescription = "N/A (미리보기 전용)",
                    badgeLabelKorean = "미리보기 전용 (분석기 비활성)",
                    summaryKorean = "미리보기 전용 모드로 분석기 및 모델 추론이 비활성화되었습니다.",
                )
            }

            // Preprocessing and Postprocessing always deterministically run on CPU during active analysis
            val preprocessParticipant = ComputeParticipant(
                device = VisionComputeDevice.CPU,
                stage = VisionPipelineStage.PREPROCESSING,
                evidenceLevel = ComputeEvidenceLevel.STATIC_DETERMINISTIC,
                isAccelerated = false,
                detailKorean = "카메라 입력 YUV 정규화 및 크롭/레터박스 (CPU)",
            )
            val postprocessParticipant = ComputeParticipant(
                device = VisionComputeDevice.CPU,
                stage = VisionPipelineStage.POSTPROCESSING,
                evidenceLevel = ComputeEvidenceLevel.STATIC_DETERMINISTIC,
                isAccelerated = false,
                detailKorean = "세그멘테이션 마스크 합성 및 오버레이 생성 (CPU)",
            )
            val hazardParticipant = ComputeParticipant(
                device = VisionComputeDevice.CPU,
                stage = VisionPipelineStage.HAZARD_EVALUATION,
                evidenceLevel = ComputeEvidenceLevel.STATIC_DETERMINISTIC,
                isAccelerated = false,
                detailKorean = "보행 경로 안전성 분석 및 위험 경고 평가 (CPU)",
            )

            // Case 3: Model is not ready or active backend is absent
            val isReady = isModelReady && isInvocationReady && activeBackend != null
            if (!isReady) {
                return VisionComputeTelemetry(
                    isPipelineActive = true,
                    isModelInferenceActive = false,
                    activeDevices = setOf(VisionComputeDevice.CPU),
                    requestedDevices = emptySet(),
                    isMixedComputation = false,
                    inferenceParticipants = emptyList(),
                    pipelineParticipants = listOf(preprocessParticipant),
                    configuredBackend = configuredBackend,
                    activeBackend = null,
                    isCpuFallback = false,
                    fallbackReason = null,
                    partitionUncertainty = null,
                    activeBackendDescription = "N/A ($modelStateDescription)",
                    badgeLabelKorean = "$modelStateDescription (전처리 CPU)",
                    summaryKorean = "$modelStateDescription 상태로 모델 추론이 비활성화되었습니다 (전처리: CPU 전용).",
                )
            }

            // Case 4: Model is Ready and invocation-ready
            val isFallback = (gpuFallbackCause != null || npuFallbackCause != null) &&
                activeBackend == VisionBackendType.CPU
            val fallbackReasonStr = when {
                npuFallbackCause != null && gpuFallbackCause != null ->
                    "NPU 실패 (${npuFallbackCause.javaClass.simpleName}) 및 GPU 실패 (${gpuFallbackCause.javaClass.simpleName}) 후 CPU 폴백"
                gpuFallbackCause != null ->
                    "GPU 실패 (${gpuFallbackCause.javaClass.simpleName}: ${gpuFallbackCause.message.orEmpty().take(60)}) 후 CPU 폴백"
                npuFallbackCause != null ->
                    "NPU 실패 (${npuFallbackCause.javaClass.simpleName}: ${npuFallbackCause.message.orEmpty().take(60)}) 후 CPU 폴백"
                else -> null
            }

            val inferenceParticipants: List<ComputeParticipant>
            val activeDevices: Set<VisionComputeDevice>
            val requestedDevices: Set<VisionComputeDevice>
            val isMixed: Boolean
            val partitionUncertainty: String?
            val badgeLabel: String
            val summaryKorean: String

            when (activeBackend) {
                VisionBackendType.GPU -> {
                    val gpuParticipant = ComputeParticipant(
                        device = VisionComputeDevice.GPU,
                        stage = VisionPipelineStage.INFERENCE,
                        evidenceLevel = ComputeEvidenceLevel.SESSION_INITIALIZED,
                        isAccelerated = true,
                        detailKorean = "LiteRT GPU FP32 가속 추론 (OpenCL/Vulkan CompiledModel)",
                    )
                    inferenceParticipants = listOf(gpuParticipant)
                    activeDevices = setOf(VisionComputeDevice.CPU, VisionComputeDevice.GPU)
                    requestedDevices = setOf(VisionComputeDevice.GPU)
                    isMixed = true
                    partitionUncertainty = null
                    badgeLabel = "CPU + GPU (추론 GPU·전후처리 CPU)"
                    summaryKorean = "LiteRT GPU FP32 가속 추론과 CPU 전·후처리가 결합된 파이프라인입니다."
                }
                VisionBackendType.NPU -> {
                    val npuParticipant = ComputeParticipant(
                        device = VisionComputeDevice.NPU,
                        stage = VisionPipelineStage.INFERENCE,
                        evidenceLevel = ComputeEvidenceLevel.REQUESTED_UNVERIFIED_PARTITION,
                        isAccelerated = true,
                        detailKorean = "LiteRT NPU 가속기 [요청됨 / 런타임 분할 미검증]",
                    )
                    val cpuInferenceParticipant = ComputeParticipant(
                        device = VisionComputeDevice.CPU,
                        stage = VisionPipelineStage.INFERENCE,
                        evidenceLevel = ComputeEvidenceLevel.REQUESTED_UNVERIFIED_PARTITION,
                        isAccelerated = false,
                        detailKorean = "LiteRT NPU 보조 CPU 파티션 [잠재적 혼합 실행 / 분할 미검증]",
                    )
                    inferenceParticipants = listOf(npuParticipant, cpuInferenceParticipant)
                    // Unverified NPU must NOT be counted as confirmed actual participant in activeDevices or isMixedComputation
                    activeDevices = setOf(VisionComputeDevice.CPU)
                    requestedDevices = setOf(VisionComputeDevice.NPU, VisionComputeDevice.CPU)
                    isMixed = false
                    partitionUncertainty = LITERT_NPU_SDK_LIMITATION_NOTE
                    badgeLabel = "NPU 요청 + CPU (실행 분할 미확인)"
                    summaryKorean = "NPU 가속기가 요청되었으나 런타임 실행 분할은 확인되지 않습니다 (전·후처리 CPU)."
                }
                VisionBackendType.CPU -> {
                    val detail = if (isFallback) {
                        "LiteRT CPU 저성능 폴백 추론 (${configuredCpuThreads}스레드)"
                    } else {
                        "LiteRT CPU 진단 참조 추론 (${configuredCpuThreads}스레드)"
                    }
                    val cpuInferenceParticipant = ComputeParticipant(
                        device = VisionComputeDevice.CPU,
                        stage = VisionPipelineStage.INFERENCE,
                        evidenceLevel = ComputeEvidenceLevel.EXECUTION_VERIFIED,
                        isAccelerated = false,
                        detailKorean = detail,
                    )
                    inferenceParticipants = listOf(cpuInferenceParticipant)
                    activeDevices = setOf(VisionComputeDevice.CPU)
                    requestedDevices = setOf(VisionComputeDevice.CPU)
                    isMixed = false
                    partitionUncertainty = null
                    badgeLabel = if (isFallback) "CPU 폴백" else "CPU 진단 참조"
                    summaryKorean = if (isFallback) {
                        "가속기 초기화 실패로 인해 저성능 CPU 폴백(${configuredCpuThreads}스레드)으로 동작 중입니다."
                    } else {
                        "진단 전용 단일 CPU(${configuredCpuThreads}스레드) 모드로 동작 중입니다."
                    }
                }
            }

            val pipelineParticipants = listOf(preprocessParticipant) +
                inferenceParticipants +
                listOf(postprocessParticipant, hazardParticipant)

            return VisionComputeTelemetry(
                isPipelineActive = true,
                isModelInferenceActive = true,
                activeDevices = activeDevices,
                requestedDevices = requestedDevices,
                isMixedComputation = isMixed,
                inferenceParticipants = inferenceParticipants,
                pipelineParticipants = pipelineParticipants,
                configuredBackend = configuredBackend,
                activeBackend = activeBackend,
                isCpuFallback = isFallback,
                fallbackReason = fallbackReasonStr,
                partitionUncertainty = partitionUncertainty,
                activeBackendDescription = activeBackendDescription,
                badgeLabelKorean = badgeLabel,
                summaryKorean = summaryKorean,
            )
        }
    }
}
