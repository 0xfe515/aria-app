package com.ariateam.aria.vision

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import com.ariateam.aria.hazard.CorridorDirection
import com.ariateam.aria.hazard.HazardAlertCoordinator
import com.ariateam.aria.hazard.HazardAssessment
import com.ariateam.aria.hazard.HazardDetector
import com.ariateam.aria.hazard.HazardLevel
import com.ariateam.aria.hazard.MotionDegradedReason
import com.ariateam.aria.hazard.MotionState
import com.ariateam.aria.hazard.MotionTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class CameraSessionStatus {
    Stopped,
    Starting,
    Running,
    PermissionDenied,
    CameraUnavailable,
    Error,
}

data class VisionUiState(
    val cameraStatus: CameraSessionStatus = CameraSessionStatus.Stopped,
    val cameraErrorDetail: String? = null,
    val pipelineMode: VisionPipelineMode = VisionPipelineMode.PREPROCESS_OR_INFERENCE,
    val preprocessingStrategy: VisionPreprocessingStrategy = VisionPreprocessingStrategy.FULL_FRAME_UPRIGHT_LETTERBOX,
    val timing: VisionTimingSnapshot = VisionTimingSnapshot(),
    val latestSummary: VisionSegmentationSummary? = null,
    val latestOverlayBitmap: Bitmap? = null,
    val motionState: MotionState = MotionState(),
    val hazardAssessment: HazardAssessment = HazardAssessment(),
    val isHazardAlertEnabled: Boolean = true,
    val configuredBackend: VisionBackendType = VisionBackendType.GPU,
    val configuredCpuThreads: Int = VisionContract.DEFAULT_CPU_THREADS,
    val activeBackendDescription: String = "N/A (모델 미적용)",
)

/**
 * Coordinates CameraX `Preview` + `ImageAnalysis`, [VisionFramePreprocessor], [VisionModelStore],
 * [VisionTimingTracker], and IMU-based [MotionTracker] / [HazardDetector] / [HazardAlertCoordinator].
 */
class VisionPipelineController(
    private val context: Context,
    val modelStore: VisionModelStore = VisionModelStore(context.applicationContext),
    val timingTracker: VisionTimingTracker = VisionTimingTracker(),
    val preprocessor: VisionFramePreprocessor = VisionFramePreprocessor(
        targetWidth = VisionContract.MODEL_INPUT_WIDTH,
        targetHeight = VisionContract.MODEL_INPUT_HEIGHT,
        defaultStrategy = VisionPreprocessingStrategy.FULL_FRAME_UPRIGHT_LETTERBOX,
    ),
    val motionTracker: MotionTracker = MotionTracker(context),
    val hazardDetector: HazardDetector = HazardDetector(),
    val alertCoordinator: HazardAlertCoordinator = HazardAlertCoordinator(context),
    private val mutableUiState: MutableStateFlow<VisionUiState> = MutableStateFlow(VisionUiState()),
) : AutoCloseable {
    private val analyzerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "aria-vision-analyzer").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val sessionGeneration = AtomicLong(0L)
    private val lastPublishedNanos = AtomicLong(0L)

    @Volatile
    private var cameraProvider: ProcessCameraProvider? = null

    @Volatile
    private var boundPreview: Preview? = null

    @Volatile
    private var boundAnalysis: ImageAnalysis? = null

    @Volatile
    private var activeSurfaceProvider: Preview.SurfaceProvider? = null

    @Volatile
    private var activeLifecycleOwner: LifecycleOwner? = null

    @Volatile
    private var activeTargetRotation: Int = Surface.ROTATION_0

    @Volatile
    private var currentMode: VisionPipelineMode = VisionPipelineMode.PREPROCESS_OR_INFERENCE

    @Volatile
    var preprocessingStrategy: VisionPreprocessingStrategy = VisionPreprocessingStrategy.FULL_FRAME_UPRIGHT_LETTERBOX
        private set

    @Volatile
    private var sensorTimestampSource: SensorTimestampSource = SensorTimestampSource.UNKNOWN_OR_UNVERIFIED

    @Volatile
    private var activeCameraIntrinsics: com.ariateam.aria.hazard.CameraIntrinsics? = null

    private var lastObservedGeomWidth = -1
    private var lastObservedGeomHeight = -1
    private var lastObservedGeomRotation = -1
    private var lastObservedCropWidth = -1
    private var lastObservedUprightWidth = -1

    val uiState: StateFlow<VisionUiState> = mutableUiState.asStateFlow()

    val modelState: StateFlow<VisionModelState>
        get() = modelStore.state

    val isInitialRestoreCompleted: StateFlow<Boolean>
        get() = modelStore.isInitialRestoreCompleted

    fun setPreprocessingStrategy(strategy: VisionPreprocessingStrategy) {
        if (preprocessingStrategy == strategy) return
        preprocessingStrategy = strategy
        lastObservedGeomWidth = -1
        lastObservedGeomHeight = -1
        lastObservedGeomRotation = -1
        lastObservedCropWidth = -1
        lastObservedUprightWidth = -1
        motionTracker.updateCameraGeometry(null)
        mutableUiState.update { it.copy(preprocessingStrategy = strategy) }
    }

    fun setPipelineMode(mode: VisionPipelineMode) {
        if (currentMode == mode) return
        currentMode = mode
        timingTracker.reset()
        lastPublishedNanos.set(0L)
        val owner = activeLifecycleOwner
        if (running.get() && owner != null) {
            startCamera(owner, activeSurfaceProvider, mode, activeTargetRotation)
        } else {
            val modelLoaded = modelStore.segmenter.isReady
            mutableUiState.update {
                it.copy(
                    pipelineMode = mode,
                    timing = timingTracker.snapshot(mode, modelLoaded, activeBackendLabel(mode, modelLoaded)),
                    latestSummary = null,
                    latestOverlayBitmap = null,
                )
            }
        }
    }

    fun resetMetrics() {
        timingTracker.reset()
        lastPublishedNanos.set(0L)
        val now = SystemClock.elapsedRealtimeNanos()
        val modelLoaded = modelStore.segmenter.isReady
        mutableUiState.update {
            it.copy(
                timing = timingTracker.snapshot(
                    mode = currentMode,
                    modelLoaded = modelLoaded,
                    backendLabel = activeBackendLabel(currentMode, modelLoaded),
                    nowElapsedNanos = now,
                ),
            )
        }
    }

    fun calibrateForward() {
        motionTracker.calibrateForward()
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val motion = motionTracker.stateAt(nowNanos)
        mutableUiState.update { it.copy(motionState = motion) }
    }

    fun setHazardAlertEnabled(enabled: Boolean) {
        alertCoordinator.isEnabled = enabled
        mutableUiState.update { it.copy(isHazardAlertEnabled = enabled) }
    }

    fun configureBackend(
        backend: VisionBackendType,
        threads: Int = modelStore.segmenter.configuredCpuThreads,
    ) {
        if (backend == VisionBackendType.CPU && !modelStore.segmenter.inferencePolicy.allowCpuReferenceForTesting) {
            throw IllegalArgumentException(
                "CPU backend is disabled in production. Aria enforces LiteRT GPU-only inference."
            )
        }
        analyzerExecutor.execute {
            try {
                modelStore.setPreferredBackend(backend, threads)
            } catch (t: Throwable) {
                val desc = modelStore.segmenter.activeBackendDescription
                mutableUiState.update {
                    it.copy(
                        configuredBackend = modelStore.segmenter.configuredBackend,
                        configuredCpuThreads = modelStore.segmenter.configuredCpuThreads,
                        activeBackendDescription = desc,
                        cameraErrorDetail = "백엔드 재구성 실패 (기존 유효 세션 유지: ${t.javaClass.simpleName}: ${t.message.orEmpty().take(120)})",
                    )
                }
                return@execute
            }
            val desc = modelStore.segmenter.activeBackendDescription
            mutableUiState.update {
                it.copy(
                    configuredBackend = modelStore.segmenter.configuredBackend,
                    configuredCpuThreads = modelStore.segmenter.configuredCpuThreads,
                    activeBackendDescription = desc,
                    cameraErrorDetail = null,
                )
            }
        }
    }

    fun permissionDenied() {
        sessionGeneration.incrementAndGet()
        running.set(false)
        activeLifecycleOwner = null
        activeSurfaceProvider = null
        motionTracker.stop()
        alertCoordinator.stop()
        unbindUseCasesOnMain()
        mutableUiState.update {
            it.copy(
                cameraStatus = CameraSessionStatus.PermissionDenied,
                cameraErrorDetail = "카메라 권한이 거부되었습니다. 권한을 허용한 뒤 다시 시작해 주세요.",
            )
        }
    }

    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider?,
        mode: VisionPipelineMode = currentMode,
        targetRotation: Int = Surface.ROTATION_0,
    ) {
        if (closed.get()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionDenied()
            return
        }

        val ticket = sessionGeneration.incrementAndGet()
        currentMode = mode
        activeLifecycleOwner = lifecycleOwner
        activeSurfaceProvider = surfaceProvider
        activeTargetRotation = targetRotation
        running.set(true)
        timingTracker.reset()
        lastPublishedNanos.set(0L)

        motionTracker.start(targetRotation)
        alertCoordinator.start()

        val modelLoaded = modelStore.segmenter.isReady
        mutableUiState.update {
            it.copy(
                cameraStatus = CameraSessionStatus.Starting,
                cameraErrorDetail = null,
                pipelineMode = mode,
                preprocessingStrategy = preprocessingStrategy,
                timing = timingTracker.snapshot(mode, modelLoaded, activeBackendLabel(mode, modelLoaded)),
                latestSummary = null,
                latestOverlayBitmap = null,
                configuredBackend = modelStore.segmenter.configuredBackend,
                configuredCpuThreads = modelStore.segmenter.configuredCpuThreads,
                activeBackendDescription = modelStore.segmenter.activeBackendDescription,
                isHazardAlertEnabled = alertCoordinator.isEnabled,
            )
        }

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                if (!isSessionLive(ticket)) return@addListener
                try {
                    val provider = future.get()
                    if (!isSessionLive(ticket)) return@addListener
                    cameraProvider = provider
                    bindUseCases(provider, lifecycleOwner, surfaceProvider, mode, targetRotation, ticket)
                } catch (t: Throwable) {
                    if (!isSessionLive(ticket)) return@addListener
                    running.set(false)
                    motionTracker.stop()
                    alertCoordinator.stop()
                    unbindUseCasesOnMain()
                    mutableUiState.update {
                        it.copy(
                            cameraStatus = CameraSessionStatus.Error,
                            cameraErrorDetail = "카메라 초기화 실패 (${t.javaClass.simpleName})",
                        )
                    }
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun isSessionLive(ticket: Long): Boolean =
        running.get() && !closed.get() && sessionGeneration.get() == ticket

    @OptIn(ExperimentalCamera2Interop::class)
    private fun bindUseCases(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider?,
        mode: VisionPipelineMode,
        targetRotation: Int,
        ticket: Long,
    ) {
        try {
            provider.unbindAll()

            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            val cameraInfo = provider.getCameraInfo(selector)
            val c2Info = Camera2CameraInfo.from(cameraInfo)
            val source = c2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
            sensorTimestampSource = when (source) {
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> SensorTimestampSource.REALTIME_VERIFIED
                else -> SensorTimestampSource.UNKNOWN_OR_UNVERIFIED
            }

            val focalLengths = c2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val focalLength = focalLengths?.firstOrNull() ?: 0f
            val sensorPhysicalSize = c2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val sensorActiveArray = c2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val intrinsics = if (focalLength > 0f && sensorPhysicalSize != null && sensorPhysicalSize.width > 0f && sensorPhysicalSize.height > 0f) {
                com.ariateam.aria.hazard.CameraIntrinsics(
                    focalLengthMm = focalLength,
                    sensorWidthMm = sensorPhysicalSize.width,
                    sensorHeightMm = sensorPhysicalSize.height,
                    activeArrayWidth = sensorActiveArray?.width() ?: 0,
                    activeArrayHeight = sensorActiveArray?.height() ?: 0,
                )
            } else {
                null
            }
            activeCameraIntrinsics = intrinsics
            // Invalidate camera geometry until first actual frame is preprocessed
            lastObservedGeomWidth = -1
            lastObservedGeomHeight = -1
            lastObservedGeomRotation = -1
            lastObservedCropWidth = -1
            lastObservedUprightWidth = -1
            motionTracker.updateCameraGeometry(null)

            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(VisionContract.DEFAULT_ANALYSIS_WIDTH, VisionContract.DEFAULT_ANALYSIS_HEIGHT),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    )
                )
                .build()

            val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    if (!isSessionLive(ticket)) return
                    val nowNanos = SystemClock.elapsedRealtimeNanos()
                    timingTracker.recordSensorCapture(nowNanos)
                    if (currentMode == VisionPipelineMode.PREVIEW_ONLY) {
                        publishPreviewOnlyMetricsThrottled(ticket, nowNanos)
                    }
                }
            }

            val previewBuilder = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .setTargetRotation(targetRotation)

            val analysisBuilder = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setTargetRotation(targetRotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)

            // Attach sessionCaptureCallback to exactly ONE active use case to prevent double counting
            // and ensure callbacks fire even when ImageAnalysis is inactive in PREVIEW_ONLY mode.
            if (mode == VisionPipelineMode.PREVIEW_ONLY) {
                Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(captureCallback)
            } else {
                Camera2Interop.Extender(analysisBuilder).setSessionCaptureCallback(captureCallback)
            }

            val preview = previewBuilder.build()
            boundPreview = preview
            preview.setSurfaceProvider(surfaceProvider)

            val analysis = analysisBuilder.build()
            boundAnalysis = analysis

            if (mode == VisionPipelineMode.PREVIEW_ONLY) {
                analysis.clearAnalyzer()
            } else {
                analysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                    analyzeFrame(imageProxy, ticket)
                }
            }

            val useCases = mutableListOf<UseCase>(preview, analysis)
            provider.bindToLifecycle(lifecycleOwner, selector, *useCases.toTypedArray())

            val previewRes = preview.resolutionInfo?.resolution
            val previewRot = preview.resolutionInfo?.rotationDegrees ?: targetRotation
            val analysisRes = analysis.resolutionInfo?.resolution
            val analysisRot = analysis.resolutionInfo?.rotationDegrees ?: targetRotation
            if (previewRes != null) {
                timingTracker.recordStreamConfiguration(
                    width = analysisRes?.width ?: previewRes.width,
                    height = analysisRes?.height ?: previewRes.height,
                    rotation = analysisRot,
                    sensorTimestampSource = sensorTimestampSource,
                    previewStreamWidth = previewRes.width,
                    previewStreamHeight = previewRes.height,
                    previewStreamRotation = previewRot,
                )
            }

            if (isSessionLive(ticket)) {
                val now = SystemClock.elapsedRealtimeNanos()
                val modelLoaded = modelStore.segmenter.isReady
                mutableUiState.update {
                    it.copy(
                        cameraStatus = CameraSessionStatus.Running,
                        cameraErrorDetail = null,
                        timing = timingTracker.snapshot(
                            mode = mode,
                            modelLoaded = modelLoaded,
                            backendLabel = activeBackendLabel(mode, modelLoaded),
                            nowElapsedNanos = now,
                        ),
                        configuredBackend = modelStore.segmenter.configuredBackend,
                        configuredCpuThreads = modelStore.segmenter.configuredCpuThreads,
                        activeBackendDescription = modelStore.segmenter.activeBackendDescription,
                    )
                }
            }
        } catch (t: Throwable) {
            running.set(false)
            motionTracker.stop()
            alertCoordinator.stop()
            runCatching { boundAnalysis?.clearAnalyzer() }
            runCatching { boundPreview?.setSurfaceProvider(null) }
            runCatching { provider.unbindAll() }
            boundAnalysis = null
            boundPreview = null
            if (sessionGeneration.get() == ticket) {
                mutableUiState.update {
                    it.copy(
                        cameraStatus = CameraSessionStatus.Error,
                        cameraErrorDetail = "카메라 스트림 바인딩 실패 (${t.javaClass.simpleName})",
                    )
                }
            }
        }
    }

    private fun analyzeFrame(imageProxy: ImageProxy, ticket: Long) {
        val analyzerStartNanos = SystemClock.elapsedRealtimeNanos()
        try {
            if (!isSessionLive(ticket)) return
            val planes = imageProxy.planes
            require(planes.size >= 3) { "YUV_420_888 requires 3 planes" }

            val width = imageProxy.width
            val height = imageProxy.height
            val rotation = ((imageProxy.imageInfo.rotationDegrees % 360) + 360) % 360
            val imageTimestampNanos = imageProxy.imageInfo.timestamp

            val yPlane = planes[0]
            val uPlane = planes[1]
            val vPlane = planes[2]

            val preprocessStartNanos = SystemClock.elapsedRealtimeNanos()
            val cropRect = preprocessor.preprocessYuv420(
                bufferWidth = width,
                bufferHeight = height,
                rotationDegrees = rotation,
                yBuffer = yPlane.buffer,
                yRowStride = yPlane.rowStride,
                yPixelStride = yPlane.pixelStride,
                uBuffer = uPlane.buffer,
                uRowStride = uPlane.rowStride,
                uPixelStride = uPlane.pixelStride,
                vBuffer = vPlane.buffer,
                vRowStride = vPlane.rowStride,
                vPixelStride = vPlane.pixelStride,
                strategy = preprocessingStrategy,
            )
            val preprocessDurationNanos = (SystemClock.elapsedRealtimeNanos() - preprocessStartNanos).coerceAtLeast(0L)

            // Update camera geometry with actual buffer dimensions, actual ImageProxy rotationDegrees, and crop geometry
            if (lastObservedGeomWidth != width ||
                lastObservedGeomHeight != height ||
                lastObservedGeomRotation != rotation ||
                lastObservedCropWidth != cropRect.width ||
                lastObservedUprightWidth != cropRect.uprightWidth
            ) {
                lastObservedGeomWidth = width
                lastObservedGeomHeight = height
                lastObservedGeomRotation = rotation
                lastObservedCropWidth = cropRect.width
                lastObservedUprightWidth = cropRect.uprightWidth

                val hfov = com.ariateam.aria.hazard.CameraGeometry.computeEffectiveUprightHfovDegrees(
                    intrinsics = activeCameraIntrinsics,
                    bufferWidth = width,
                    bufferHeight = height,
                    rotationDegrees = rotation,
                    cropWidth = cropRect.width,
                    uprightWidth = cropRect.uprightWidth,
                )
                motionTracker.updateCameraGeometry(hfov)
                timingTracker.recordStreamConfiguration(
                    width = width,
                    height = height,
                    rotation = rotation,
                    sensorTimestampSource = sensorTimestampSource,
                    previewStreamWidth = width,
                    previewStreamHeight = height,
                    previewStreamRotation = rotation,
                )
            }

            val shouldPublishUi = shouldPublishNow(SystemClock.elapsedRealtimeNanos())
            val inferenceOutput = modelStore.segmenter.runIfLoaded(
                inputByteBuffer = preprocessor.inputByteBuffer,
                produceOverlayBitmap = shouldPublishUi,
                transform = cropRect,
            )
            val analyzerEndNanos = SystemClock.elapsedRealtimeNanos()
            if (!isSessionLive(ticket)) return

            // Query aligned motion state and assess hazard
            val isClockRealtime = timingTracker.sensorTimestampSource == SensorTimestampSource.REALTIME_VERIFIED
            val motionRaw = motionTracker.stateAt(imageTimestampNanos)
            val motion = if (isClockRealtime) {
                motionRaw
            } else {
                motionRaw.copy(
                    confidence = 0f,
                    degradedReason = MotionDegradedReason.SENSORS_UNAVAILABLE,
                )
            }
            val assessment = if (inferenceOutput != null) {
                if (!isClockRealtime) {
                    HazardAssessment(
                        timestampNanos = imageTimestampNanos,
                        level = HazardLevel.UNKNOWN_DEGRADED,
                        activeCorridor = motion.activeCorridor,
                        dominantNearClass = 0,
                        corridorWalkableRatio = 0f,
                        nearObstacleOccupancy = 0f,
                        motionConfidence = 0f,
                        messageKorean = "카메라 클럭 비호환으로 위험 감지가 비활성화되었습니다.",
                        isAlertCandidate = false,
                    )
                } else {
                    hazardDetector.assessHazard(
                        classMask = modelStore.segmenter.reusableClassMask,
                        motionState = motion,
                        timestampNanos = imageTimestampNanos,
                    ).also { alertCoordinator.processAssessment(it, imageTimestampNanos) }
                }
            } else {
                HazardAssessment(
                    timestampNanos = imageTimestampNanos,
                    level = HazardLevel.UNKNOWN_DEGRADED,
                    activeCorridor = motion.activeCorridor,
                    motionConfidence = motion.confidence,
                    messageKorean = if (currentMode == VisionPipelineMode.PREVIEW_ONLY) {
                        "미리보기 전용 (위험 분석 비활성)"
                    } else {
                        "세그멘테이션 모델 미적용"
                    },
                    isAlertCandidate = false,
                )
            }

            val modelLoaded = inferenceOutput != null
            timingTracker.recordAnalyzerFrame(
                analyzerStartElapsedNanos = analyzerStartNanos,
                analyzerEndElapsedNanos = analyzerEndNanos,
                preprocessNanos = preprocessDurationNanos,
                invokeNanos = inferenceOutput?.invokeDurationNanos,
                postprocessNanos = inferenceOutput?.postprocessDurationNanos,
                modelLoaded = modelLoaded,
                frameWidth = width,
                frameHeight = height,
                frameRotationDegrees = rotation,
                frameCropRect = cropRect,
                sensorTimestampSource = sensorTimestampSource,
            )

            if (shouldPublishUi && isSessionLive(ticket)) {
                lastPublishedNanos.set(analyzerEndNanos)
                val initialSnap = timingTracker.snapshot(
                    mode = currentMode,
                    modelLoaded = modelLoaded,
                    backendLabel = activeBackendLabel(currentMode, modelLoaded),
                    nowElapsedNanos = analyzerEndNanos,
                )
                mutableUiState.update { state ->
                    if (!isSessionLive(ticket)) return@update state
                    state.copy(
                        cameraStatus = CameraSessionStatus.Running,
                        timing = initialSnap,
                        latestSummary = inferenceOutput?.summary,
                        latestOverlayBitmap = inferenceOutput?.overlayBitmap ?: if (!modelLoaded) null else state.latestOverlayBitmap,
                        motionState = motion,
                        hazardAssessment = assessment,
                        configuredBackend = modelStore.segmenter.configuredBackend,
                        configuredCpuThreads = modelStore.segmenter.configuredCpuThreads,
                        activeBackendDescription = modelStore.segmenter.activeBackendDescription,
                    )
                }
                val publishCompletedNanos = SystemClock.elapsedRealtimeNanos()
                timingTracker.recordPublication(
                    analyzerStartElapsedNanos = analyzerStartNanos,
                    analyzerEndElapsedNanos = analyzerEndNanos,
                    publishCompletedElapsedNanos = publishCompletedNanos,
                    imageTimestampNanos = imageTimestampNanos,
                    sensorTimestampSource = sensorTimestampSource,
                )
                val updatedSnap = timingTracker.snapshot(
                    mode = currentMode,
                    modelLoaded = modelLoaded,
                    backendLabel = activeBackendLabel(currentMode, modelLoaded),
                    nowElapsedNanos = publishCompletedNanos,
                )
                mutableUiState.update { state ->
                    if (!isSessionLive(ticket)) return@update state
                    state.copy(timing = updatedSnap)
                }
            }
        } catch (t: Throwable) {
            if (!isSessionLive(ticket)) return
            sessionGeneration.incrementAndGet()
            running.set(false)
            motionTracker.stop()
            alertCoordinator.stop()
            unbindUseCasesOnMain()
            mutableUiState.update { state ->
                state.copy(
                    cameraStatus = CameraSessionStatus.Error,
                    cameraErrorDetail = "프레임 분석 중 오류가 발생하여 카메라 분석을 중지했습니다 (${t.javaClass.simpleName}).",
                )
            }
        } finally {
            imageProxy.close()
        }
    }

    private fun shouldPublishNow(nowNanos: Long): Boolean {
        val prev = lastPublishedNanos.get()
        return prev == 0L || (nowNanos - prev) >= METRICS_PUBLISH_INTERVAL_NANOS
    }

    private fun publishPreviewOnlyMetricsThrottled(ticket: Long, nowNanos: Long) {
        if (!isSessionLive(ticket) || !shouldPublishNow(nowNanos)) return
        lastPublishedNanos.set(nowNanos)
        val modelLoaded = modelStore.segmenter.isReady
        val snap = timingTracker.snapshot(
            mode = currentMode,
            modelLoaded = modelLoaded,
            backendLabel = activeBackendLabel(currentMode, modelLoaded),
            nowElapsedNanos = nowNanos,
        )
        val motion = motionTracker.stateAt(nowNanos)
        mutableUiState.update { state ->
            if (!isSessionLive(ticket)) return@update state
            state.copy(
                timing = snap,
                motionState = motion,
                configuredBackend = modelStore.segmenter.configuredBackend,
                configuredCpuThreads = modelStore.segmenter.configuredCpuThreads,
                activeBackendDescription = modelStore.segmenter.activeBackendDescription,
            )
        }
    }

    fun updateSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?, targetRotation: Int = activeTargetRotation) {
        activeSurfaceProvider = surfaceProvider
        if (activeTargetRotation != targetRotation) {
            activeTargetRotation = targetRotation
            motionTracker.setDisplayRotation(targetRotation)
            // Invalidate camera geometry until next frame with new rotation arrives
            lastObservedGeomWidth = -1
            lastObservedGeomHeight = -1
            lastObservedGeomRotation = -1
            lastObservedCropWidth = -1
            lastObservedUprightWidth = -1
            motionTracker.updateCameraGeometry(null)
            boundPreview?.targetRotation = targetRotation
            boundAnalysis?.targetRotation = targetRotation
        }
        ContextCompat.getMainExecutor(context).execute {
            boundPreview?.setSurfaceProvider(surfaceProvider)
        }
    }

    fun stopCamera() {
        sessionGeneration.incrementAndGet()
        running.set(false)
        activeLifecycleOwner = null
        activeSurfaceProvider = null
        motionTracker.stop()
        alertCoordinator.stop()
        unbindUseCasesOnMain()
        val now = SystemClock.elapsedRealtimeNanos()
        val modelLoaded = modelStore.segmenter.isReady
        mutableUiState.update { state ->
            state.copy(
                cameraStatus = if (state.cameraStatus == CameraSessionStatus.PermissionDenied) {
                    CameraSessionStatus.PermissionDenied
                } else {
                    CameraSessionStatus.Stopped
                },
                timing = timingTracker.snapshot(
                    mode = currentMode,
                    modelLoaded = modelLoaded,
                    backendLabel = activeBackendLabel(currentMode, modelLoaded),
                    nowElapsedNanos = now,
                ),
            )
        }
    }

    private fun unbindUseCasesOnMain() {
        ContextCompat.getMainExecutor(context).execute {
            runCatching { boundAnalysis?.clearAnalyzer() }
            runCatching { boundPreview?.setSurfaceProvider(null) }
            runCatching { cameraProvider?.unbindAll() }
            boundAnalysis = null
            boundPreview = null
        }
    }

    fun snapshotNow(): VisionTimingSnapshot {
        val modelLoaded = modelStore.segmenter.isReady
        return timingTracker.snapshot(
            mode = currentMode,
            modelLoaded = modelLoaded,
            backendLabel = activeBackendLabel(currentMode, modelLoaded),
            nowElapsedNanos = SystemClock.elapsedRealtimeNanos(),
        )
    }

    private fun activeBackendLabel(
        mode: VisionPipelineMode,
        modelLoaded: Boolean = modelStore.segmenter.isReady,
    ): String = when {
        mode == VisionPipelineMode.PREVIEW_ONLY -> "미리보기 전용 (분석기 비활성)"
        modelLoaded -> modelStore.segmenter.activeBackendDescription
        else -> "N/A (모델 미적용 · 카메라 전처리 512×288 전용)"
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopCamera()
        motionTracker.close()
        alertCoordinator.close()
        analyzerExecutor.shutdown()
        modelStore.close()
    }

    companion object {
        private const val METRICS_PUBLISH_INTERVAL_NANOS = 250_000_000L
    }
}

class VisionViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val initLock = Any()

    private val mutableUiState = MutableStateFlow(VisionUiState())
    private val mutableModelState = MutableStateFlow<VisionModelState>(VisionModelState.Missing)
    private val mutableInitialRestoreCompleted = MutableStateFlow(false)

    val uiState: StateFlow<VisionUiState> = mutableUiState.asStateFlow()
    val modelState: StateFlow<VisionModelState> = mutableModelState.asStateFlow()
    val isInitialRestoreCompleted: StateFlow<Boolean> = mutableInitialRestoreCompleted.asStateFlow()

    @Volatile
    private var controllerInstance: VisionPipelineController? = null

    val isInitialized: Boolean
        get() = controllerInstance != null

    fun ensureController(): VisionPipelineController {
        controllerInstance?.let { return it }
        return synchronized(initLock) {
            controllerInstance ?: VisionPipelineController(
                context = appContext,
                modelStore = VisionModelStore(
                    context = appContext,
                    mutableState = mutableModelState,
                    mutableInitialRestoreCompleted = mutableInitialRestoreCompleted,
                ),
                mutableUiState = mutableUiState,
            ).also {
                controllerInstance = it
            }
        }
    }

    fun importModel(uri: Uri) = ensureController().modelStore.importFromUri(uri)
    fun clearModel() = ensureController().modelStore.clearModel()
    fun restoreDefaultModel() = ensureController().modelStore.restoreDefaultModel()
    fun retryModelRestore() = ensureController().modelStore.restorePersistedModelAsync()
    fun applyOptimizedModel() = ensureController().modelStore.provisionOptimizedAssetModelAsync()
    fun setPipelineMode(mode: VisionPipelineMode) = ensureController().setPipelineMode(mode)
    fun setPreprocessingStrategy(strategy: VisionPreprocessingStrategy) = ensureController().setPreprocessingStrategy(strategy)
    fun resetMetrics() = ensureController().resetMetrics()
    fun calibrateForward() = ensureController().calibrateForward()
    fun setHazardAlertEnabled(enabled: Boolean) = ensureController().setHazardAlertEnabled(enabled)
    fun configureBackend(backend: VisionBackendType, threads: Int = VisionContract.DEFAULT_CPU_THREADS) =
        ensureController().configureBackend(backend, threads)

    fun stopCamera() {
        controllerInstance?.stopCamera()
    }

    fun close() {
        controllerInstance?.close()
    }

    override fun onCleared() {
        close()
        super.onCleared()
    }
}
