package com.ariateam.aria.vision

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
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
import androidx.lifecycle.Observer
import com.ariateam.aria.hazard.CorridorDirection
import com.ariateam.aria.hazard.HazardAlertCoordinator
import com.ariateam.aria.hazard.HazardAssessment
import com.ariateam.aria.hazard.HazardDetector
import com.ariateam.aria.hazard.HazardLevel
import com.ariateam.aria.hazard.MotionDegradedReason
import com.ariateam.aria.hazard.MotionState
import com.ariateam.aria.hazard.MotionTracker
import com.ariateam.aria.runtime.AriaRuntimeTimingTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.ariateam.aria.distance.BodyMountProfile
import com.ariateam.aria.distance.BodyMountProfileStore
import com.ariateam.aria.distance.GroundContactDistance
import com.ariateam.aria.distance.GroundContactDistanceEstimator
import com.ariateam.aria.distance.GroundContactMotionTracker
import com.ariateam.aria.hazard.EffectiveFov
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
    val activeBackendType: VisionBackendType? = null,
    val activeBackendDescription: String = "N/A (모델 미적용)",
    val groundContactDistance: GroundContactDistance = GroundContactDistance(),
)

data class VisionPipelineDiagnosticSnapshot(
    val lifecycleOwner: String?,
    val lifecycleState: String,
    val cameraState: String?,
    val cameraStateType: String?,
    val cameraError: String?,
    val modelRestoreComplete: Boolean,
    val modelState: String,
    val analyzerEntries: Long,
    val pipelineInputs: Long,
    val inferenceCallEntries: Long,
    val inferenceReturns: Long,
    val completedFrames: Long,
    val imageProxyCloses: Long,
    val cameraCaptureCallbacks: Long,
    val motionSensorCallbacks: Long,
    val previewBound: Boolean,
    val analysisBound: Boolean,
)

/**
 * Coordinates CameraX `Preview` + `ImageAnalysis`, [VisionFramePreprocessor], [VisionModelStore],
 * [VisionTimingTracker], and IMU-based [MotionTracker] / [HazardDetector] / [HazardAlertCoordinator],
 * and monocular ground-contact distance estimation.
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
    val bodyMountProfileStore: BodyMountProfileStore = BodyMountProfileStore(context.applicationContext),
    val groundContactMotionTracker: GroundContactMotionTracker = GroundContactMotionTracker(context),
    val groundContactDistanceEstimator: GroundContactDistanceEstimator = GroundContactDistanceEstimator(),
    private val mutableUiState: MutableStateFlow<VisionUiState> = MutableStateFlow(VisionUiState()),
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val sessionGeneration = AtomicLong(0L)
    private val lastPublishedNanos = AtomicLong(0L)
    private val analyzerEntries = AtomicLong(0L)
    private val pipelineInputs = AtomicLong(0L)
    private val inferenceCallEntries = AtomicLong(0L)
    private val inferenceReturns = AtomicLong(0L)
    private val completedFrames = AtomicLong(0L)
    private val imageProxyCloses = AtomicLong(0L)
    private val cameraCaptureCallbacks = AtomicLong(0L)
    private val pendingFrameSnapshot = java.util.concurrent.atomic.AtomicReference<((android.graphics.Bitmap?) -> Unit)?>()

    val isClosed: Boolean
        get() = closed.get()

    val isRunning: Boolean
        get() = running.get()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var retryRunnable: Runnable? = null
    private var retryCount = 0
    private var availabilityCallback: CameraManager.AvailabilityCallback? = null
    private var sessionStartNanos = 0L

    @Volatile
    private var selectedCameraId: String? = null
    private val retryAttemptGeneration = AtomicLong(0L)

    internal fun resolveSelectedCameraId(): String {
        selectedCameraId?.let { return it }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (manager != null) {
            try {
                for (id in manager.cameraIdList) {
                    val chars = manager.getCameraCharacteristics(id)
                    val facing = chars.get(CameraCharacteristics.LENS_FACING)
                    if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        selectedCameraId = id
                        return id
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to inspect CameraCharacteristics for lens facing", t)
            }
        }
        return selectedCameraId ?: "0"
    }

    internal fun setSelectedCameraIdForTesting(id: String?) {
        selectedCameraId = id
    }

    fun captureSingleFrame(callback: (android.graphics.Bitmap?) -> Unit) {
        if (!running.get()) {
            callback(null)
            return
        }
        pendingFrameSnapshot.set(callback)
    }

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
    private var observedCameraState: androidx.lifecycle.LiveData<CameraState>? = null

    @Volatile
    private var cameraStateObserver: Observer<CameraState>? = null

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

    @Volatile
    private var activeEffectiveFov: EffectiveFov? = null

    @Volatile
    private var activeFullCameraModel: com.ariateam.aria.hazard.FullUprightCameraModel? = null

    @Volatile
    private var lastObservedProfile: BodyMountProfile? = null

    private var lastObservedGeomWidth = -1
    private var lastObservedGeomHeight = -1
    private var lastObservedGeomRotation = -1
    private var lastObservedCropWidth = -1
    private var lastObservedUprightWidth = -1

    val uiState: StateFlow<VisionUiState> = mutableUiState.asStateFlow()

    val modelState: StateFlow<VisionModelState>
        get() = modelStore.state

    fun diagnosticSnapshot(): VisionPipelineDiagnosticSnapshot {
        val owner = activeLifecycleOwner
        val cameraState = observedCameraState?.value
        return VisionPipelineDiagnosticSnapshot(
            lifecycleOwner = owner?.javaClass?.simpleName,
            lifecycleState = owner?.lifecycle?.currentState?.name ?: "NONE",
            cameraState = cameraState?.toString(),
            cameraStateType = cameraState?.type?.toString(),
            cameraError = cameraState?.error?.toString(),
            modelRestoreComplete = modelStore.isInitialRestoreCompleted.value,
            modelState = modelStore.state.value::class.java.simpleName,
            analyzerEntries = analyzerEntries.get(),
            pipelineInputs = pipelineInputs.get(),
            inferenceCallEntries = inferenceCallEntries.get(),
            inferenceReturns = inferenceReturns.get(),
            completedFrames = completedFrames.get(),
            imageProxyCloses = imageProxyCloses.get(),
            cameraCaptureCallbacks = cameraCaptureCallbacks.get(),
            motionSensorCallbacks = motionTracker.sensorCallbackCount,
            previewBound = boundPreview != null,
            analysisBound = boundAnalysis != null,
        )
    }

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
        activeEffectiveFov = null
        activeFullCameraModel = null
        groundContactDistanceEstimator.reset()
        motionTracker.updateCameraGeometry(null)
        mutableUiState.update { it.copy(preprocessingStrategy = strategy) }
    }

    fun setPipelineMode(mode: VisionPipelineMode) {
        if (currentMode == mode) return
        currentMode = mode
        timingTracker.reset()
        AriaRuntimeTimingTracker.reset()
        lastPublishedNanos.set(0L)
        groundContactDistanceEstimator.reset()
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
                    groundContactDistance = GroundContactDistance(),
                )
            }
        }
    }

    fun resetMetrics() {
        timingTracker.reset()
        AriaRuntimeTimingTracker.reset()
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
                "Manual CPU backend configuration is disabled in production; CPU is reserved for GPU-startup fallback."
            )
        }
        modelStore.segmenter.pipelineExecutor.execute {
            try {
                modelStore.setPreferredBackend(backend, threads)
            } catch (t: Throwable) {
                val desc = modelStore.segmenter.activeBackendDescription
                mutableUiState.update {
                    it.copy(
                        configuredBackend = modelStore.segmenter.configuredBackend,
                        configuredCpuThreads = modelStore.segmenter.configuredCpuThreads,
                        activeBackendType = modelStore.segmenter.activeBackendType,
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
                    activeBackendType = modelStore.segmenter.activeBackendType,
                    activeBackendDescription = desc,
                    cameraErrorDetail = null,
                )
            }
        }
    }

    fun permissionDenied() {
        cancelScheduledRetry()
        sessionGeneration.incrementAndGet()
        running.set(false)
        activeLifecycleOwner = null
        activeSurfaceProvider = null
        motionTracker.stop()
        alertCoordinator.stop()
        groundContactMotionTracker.stop()
        groundContactDistanceEstimator.reset()
        unbindUseCasesOnMain()
        mutableUiState.update {
            it.copy(
                cameraStatus = CameraSessionStatus.PermissionDenied,
                cameraErrorDetail = "카메라 권한이 거부되었습니다. 권한을 허용한 뒤 다시 시작해 주세요.",
                latestSummary = null,
                latestOverlayBitmap = null,
                hazardAssessment = HazardAssessment(),
                motionState = MotionState(),
                groundContactDistance = GroundContactDistance(),
            )
        }
    }

    fun reportCameraServiceFailure(detail: String) {
        stopCamera()
        mutableUiState.update {
            it.copy(
                cameraStatus = CameraSessionStatus.Error,
                cameraErrorDetail = detail,
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

        cancelScheduledRetry()
        val ticket = sessionGeneration.incrementAndGet()
        sessionStartNanos = SystemClock.elapsedRealtimeNanos()
        retryCount = 0
        analyzerEntries.set(0L)
        pipelineInputs.set(0L)
        inferenceCallEntries.set(0L)
        inferenceReturns.set(0L)
        completedFrames.set(0L)
        imageProxyCloses.set(0L)
        cameraCaptureCallbacks.set(0L)
        currentMode = mode
        activeLifecycleOwner = lifecycleOwner
        activeSurfaceProvider = surfaceProvider
        activeTargetRotation = targetRotation
        running.set(true)
        timingTracker.reset()
        AriaRuntimeTimingTracker.reset()
        lastPublishedNanos.set(0L)

        motionTracker.start(targetRotation)
        alertCoordinator.start()
        groundContactMotionTracker.start(targetRotation)
        groundContactDistanceEstimator.reset()

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
                activeBackendType = modelStore.segmenter.activeBackendType,
                activeBackendDescription = modelStore.segmenter.activeBackendDescription,
                isHazardAlertEnabled = alertCoordinator.isEnabled,
                groundContactDistance = GroundContactDistance(),
            )
        }

        startCameraInternal(lifecycleOwner, surfaceProvider, mode, targetRotation, ticket)
    }

    private fun startCameraInternal(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider?,
        mode: VisionPipelineMode,
        targetRotation: Int,
        ticket: Long,
    ) {
        if (!isSessionLive(ticket)) return
        val existing = cameraProvider
        if (existing != null) {
            bindUseCases(existing, lifecycleOwner, surfaceProvider, mode, targetRotation, ticket)
            return
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
                    val classified = CameraErrorClassifier.classifyException(t)
                    handleCameraFailure(ticket, classified)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun isSessionLive(ticket: Long): Boolean =
        running.get() && !closed.get() && sessionGeneration.get() == ticket && !VisionSession.requiresExplicitRestart(context)

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
            unbindBoundUseCases(provider)

            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            val cameraInfo = provider.getCameraInfo(selector)
            val c2Info = Camera2CameraInfo.from(cameraInfo)
            selectedCameraId = c2Info.cameraId
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
            activeEffectiveFov = null
            activeFullCameraModel = null
            groundContactDistanceEstimator.reset()
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
                    cameraCaptureCallbacks.incrementAndGet()
                    timingTracker.recordSensorCapture(nowNanos)
                    if (currentMode == VisionPipelineMode.PREVIEW_ONLY) {
                        if (mutableUiState.value.cameraStatus != CameraSessionStatus.Running && isSessionLive(ticket)) {
                            retryCount = 0
                            val startLatency = if (sessionStartNanos > 0L) (nowNanos - sessionStartNanos).coerceAtLeast(0L) else 0L
                            timingTracker.recordCameraStart(startLatency)
                            AriaRuntimeTimingTracker.recordStart(startLatency / 1_000_000.0)
                            mutableUiState.update {
                                if (isSessionLive(ticket)) it.copy(cameraStatus = CameraSessionStatus.Running, cameraErrorDetail = null)
                                else it
                            }
                        }
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

            // Attach the callback to exactly one bound use case.
            if (mode == VisionPipelineMode.PREVIEW_ONLY && surfaceProvider != null) {
                Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(captureCallback)
            } else {
                Camera2Interop.Extender(analysisBuilder).setSessionCaptureCallback(captureCallback)
            }

            val preview = surfaceProvider?.let {
                previewBuilder.build().also { useCase ->
                    useCase.setSurfaceProvider(it)
                }
            }
            boundPreview = preview

            val analysis = analysisBuilder.build()
            boundAnalysis = analysis

            if (mode == VisionPipelineMode.PREVIEW_ONLY) {
                analysis.clearAnalyzer()
            } else {
                analysis.setAnalyzer(modelStore.segmenter.pipelineExecutor) { imageProxy ->
                    analyzerEntries.incrementAndGet()
                    analyzeFrame(imageProxy, ticket)
                }
            }

            val useCases = mutableListOf<UseCase>()
            preview?.let(useCases::add)
            useCases.add(analysis)
            val camera = provider.bindToLifecycle(lifecycleOwner, selector, *useCases.toTypedArray())
            val stateObserver = Observer<CameraState> { cameraState ->
                val cameraError = cameraState.error ?: return@Observer
                if (isSessionLive(ticket)) {
                    val classified = CameraErrorClassifier.classifyStateError(cameraError)
                    handleCameraFailure(ticket, classified)
                }
            }
            observedCameraState = camera.cameraInfo.cameraState
            cameraStateObserver = stateObserver
            observedCameraState?.observe(lifecycleOwner, stateObserver)

            val previewRes = preview?.resolutionInfo?.resolution
            val previewRot = preview?.resolutionInfo?.rotationDegrees ?: targetRotation
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
                        cameraStatus = CameraSessionStatus.Starting, // Avoid false Running until fresh frame arrives!
                        cameraErrorDetail = null,
                        timing = timingTracker.snapshot(
                            mode = mode,
                            modelLoaded = modelLoaded,
                            backendLabel = activeBackendLabel(mode, modelLoaded),
                            nowElapsedNanos = now,
                        ),
                        configuredBackend = modelStore.segmenter.configuredBackend,
                        configuredCpuThreads = modelStore.segmenter.configuredCpuThreads,
                        activeBackendType = modelStore.segmenter.activeBackendType,
                        activeBackendDescription = modelStore.segmenter.activeBackendDescription,
                    )
                }
            }
        } catch (t: Throwable) {
            val classified = CameraErrorClassifier.classifyException(t)
            handleCameraFailure(ticket, classified)
        }
    }

    private fun handleCameraFailure(ticket: Long, failure: CameraFailureKind) {
        if (!isSessionLive(ticket)) return
        when (failure) {
            is CameraFailureKind.Permission -> permissionDenied()
            is CameraFailureKind.TransientInUse -> {
                mutableUiState.update {
                    it.copy(
                        cameraStatus = CameraSessionStatus.CameraUnavailable,
                        cameraErrorDetail = failure.description,
                    )
                }
                scheduleTransientRetry(ticket, failure.description)
            }
            is CameraFailureKind.Fatal -> failCameraSession(ticket, failure.description)
        }
    }

    private fun scheduleTransientRetry(ticket: Long, detail: String) {
        if (!isSessionLive(ticket)) return
        if (retryCount >= MAX_RETRY_COUNT) {
            failCameraSession(ticket, "카메라 연결 재시도 횟수를 초과했습니다 (${detail}). 앱 화면에서 다시 시작해 주세요.")
            return
        }
        val delayMs = BACKOFF_DELAYS_MS[retryCount.coerceIn(0, BACKOFF_DELAYS_MS.size - 1)]
        retryCount++
        val attemptToken = retryAttemptGeneration.incrementAndGet()
        val retryExecuted = AtomicBoolean(false)

        registerAvailabilityCallback(ticket, attemptToken, retryExecuted)

        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            if (!isSessionLive(ticket)) return@Runnable
            if (retryAttemptGeneration.get() != attemptToken) return@Runnable
            if (retryExecuted.compareAndSet(false, true)) {
                retryCameraSession(ticket)
            }
        }
        retryRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun registerAvailabilityCallback(
        ticket: Long,
        attemptToken: Long,
        retryExecuted: AtomicBoolean,
    ) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        unregisterAvailabilityCallback()
        val targetCameraId = resolveSelectedCameraId()

        val callback = object : CameraManager.AvailabilityCallback() {
            private var isInitialCallback = true
            private var sawUnavailable = false

            override fun onCameraUnavailable(cameraId: String) {
                if (cameraId == targetCameraId) {
                    sawUnavailable = true
                    isInitialCallback = false
                }
            }

            override fun onCameraAvailable(cameraId: String) {
                if (!isSessionLive(ticket)) return
                if (cameraId != targetCameraId) {
                    // Filter: ignore availability events from other cameras (e.g. front camera)
                    return
                }

                if (isInitialCallback) {
                    // Initial status delivered on registration: do not bypass intended backoff delay
                    isInitialCallback = false
                    return
                }

                // Legitimate transition back to available: execute single retry for this attempt
                if (retryAttemptGeneration.get() == attemptToken && retryExecuted.compareAndSet(false, true)) {
                    Log.i(TAG, "Selected camera $cameraId became available, retrying attempt $attemptToken (session $ticket)")
                    mainHandler.post {
                        if (isSessionLive(ticket) && retryAttemptGeneration.get() == attemptToken) {
                            retryCameraSession(ticket)
                        }
                    }
                }
            }
        }
        availabilityCallback = callback
        try {
            manager.registerAvailabilityCallback(ContextCompat.getMainExecutor(context), callback)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to register CameraManager availability callback", t)
        }
    }

    private fun unregisterAvailabilityCallback() {
        val callback = availabilityCallback ?: return
        availabilityCallback = null
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        runCatching { manager.unregisterAvailabilityCallback(callback) }
    }

    private fun cancelScheduledRetry() {
        retryAttemptGeneration.incrementAndGet()
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        retryRunnable = null
        unregisterAvailabilityCallback()
    }

    private fun retryCameraSession(ticket: Long) {
        if (!isSessionLive(ticket)) return
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        retryRunnable = null
        unregisterAvailabilityCallback()
        val owner = activeLifecycleOwner ?: return
        val provider = activeSurfaceProvider
        val mode = currentMode
        val rotation = activeTargetRotation
        startCameraInternal(owner, provider, mode, rotation, ticket)
    }



    private fun failCameraSession(ticket: Long, detail: String) {
        if (sessionGeneration.get() != ticket || !running.getAndSet(false)) return
        cancelScheduledRetry()
        sessionGeneration.incrementAndGet()
        activeLifecycleOwner = null
        activeSurfaceProvider = null
        motionTracker.stop()
        alertCoordinator.stop()
        groundContactMotionTracker.stop()
        groundContactDistanceEstimator.reset()
        unbindUseCasesOnMain()
        mutableUiState.update {
            it.copy(
                cameraStatus = CameraSessionStatus.Error,
                cameraErrorDetail = detail,
                latestSummary = null,
                latestOverlayBitmap = null,
                hazardAssessment = HazardAssessment(),
                motionState = MotionState(),
                groundContactDistance = GroundContactDistance(),
            )
        }
    }

    private fun analyzeFrame(imageProxy: ImageProxy, ticket: Long) {
        val analyzerStartNanos = SystemClock.elapsedRealtimeNanos()
        try {
            if (!isSessionLive(ticket)) return
            if (mutableUiState.value.cameraStatus != CameraSessionStatus.Running) {
                retryCount = 0
                val nowNanos = SystemClock.elapsedRealtimeNanos()
                val startLatency = if (sessionStartNanos > 0L) (nowNanos - sessionStartNanos).coerceAtLeast(0L) else 0L
                timingTracker.recordCameraStart(startLatency)
                AriaRuntimeTimingTracker.recordStart(startLatency / 1_000_000.0)
                mutableUiState.update {
                    if (isSessionLive(ticket)) it.copy(cameraStatus = CameraSessionStatus.Running, cameraErrorDetail = null)
                    else it
                }
            }
            val planes = imageProxy.planes
            require(planes.size >= 3) { "YUV_420_888 requires 3 planes" }

            val width = imageProxy.width
            val height = imageProxy.height
            val rotation = ((imageProxy.imageInfo.rotationDegrees % 360) + 360) % 360
            val imageTimestampNanos = imageProxy.imageInfo.timestamp

            val yPlane = planes[0]
            val uPlane = planes[1]
            val vPlane = planes[2]

            val snapshotCallback = pendingFrameSnapshot.getAndSet(null)
            if (snapshotCallback != null) {
                try {
                    val bmp = imageProxy.toBitmap()
                    snapshotCallback(bmp)
                } catch (t: Throwable) {
                    android.util.Log.w("VisionPipelineController", "Failed to capture single frame bitmap", t)
                    snapshotCallback(null)
                }
            }

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
            pipelineInputs.incrementAndGet()
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
                val effectiveFov = com.ariateam.aria.hazard.CameraGeometry.computeEffectiveUprightFov(
                    intrinsics = activeCameraIntrinsics,
                    bufferWidth = width,
                    bufferHeight = height,
                    rotationDegrees = rotation,
                    cropWidth = cropRect.width,
                    cropHeight = cropRect.height,
                    uprightWidth = cropRect.uprightWidth,
                    uprightHeight = cropRect.uprightHeight,
                )
                val fullModel = com.ariateam.aria.hazard.CameraGeometry.computeFullUprightCameraModel(
                    intrinsics = activeCameraIntrinsics,
                    bufferWidth = width,
                    bufferHeight = height,
                    rotationDegrees = rotation,
                )
                activeEffectiveFov = effectiveFov
                activeFullCameraModel = fullModel
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
            val modelRestorePending = !modelStore.isInitialRestoreCompleted.value
            val inferenceOutput = if (modelRestorePending) {
                null
            } else {
                inferenceCallEntries.incrementAndGet()
                modelStore.segmenter.runIfLoaded(
                    inputByteBuffer = preprocessor.inputByteBuffer,
                    produceOverlayBitmap = shouldPublishUi,
                    transform = cropRect,
                ).also { inferenceReturns.incrementAndGet() }
            }
            val analyzerEndNanos = SystemClock.elapsedRealtimeNanos()
            if (!isSessionLive(ticket)) return

            val alertStartNanos = SystemClock.elapsedRealtimeNanos()

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
                    } else if (modelRestorePending) {
                        "비전 모델 복원 중 (위험 분석 대기)"
                    } else {
                        "세그멘테이션 모델 미적용"
                    },
                    isAlertCandidate = false,
                )
            }

            // Invalidate distance hysteresis if user modified profile in settings
            val currentProfile = bodyMountProfileStore.profile.value
            if (currentProfile != lastObservedProfile) {
                lastObservedProfile = currentProfile
                groundContactDistanceEstimator.reset()
            }

            // Estimate monocular ground-contact distance
            val groundOrientation = groundContactMotionTracker.orientationAt(imageTimestampNanos)
            val groundContactDist = if (inferenceOutput != null) {
                groundContactDistanceEstimator.estimate(
                    classMask = modelStore.segmenter.reusableClassMask,
                    width = VisionContract.MODEL_WIDTH,
                    height = VisionContract.MODEL_HEIGHT,
                    profile = currentProfile,
                    effectiveFov = activeEffectiveFov,
                    orientation = groundOrientation,
                    timestampNanos = imageTimestampNanos,
                    isClockRealtime = isClockRealtime,
                    fullCameraModel = activeFullCameraModel,
                    cropLeftNorm = cropRect.normLeft,
                    cropTopNorm = cropRect.normTop,
                    cropWidthNorm = cropRect.normWidth,
                    cropHeightNorm = cropRect.normHeight,
                )
            } else {
                groundContactDistanceEstimator.reset()
                GroundContactDistance(
                    distanceMeters = null,
                    message = if (currentMode == VisionPipelineMode.PREVIEW_ONLY) {
                        "미리보기 전용 (거리 분석 비활성)"
                    } else if (modelRestorePending) {
                        "비전 모델 복원 중 (거리 분석 대기)"
                    } else {
                        "세그멘테이션 모델 미적용"
                    },
                    timestampNanos = imageTimestampNanos,
                )
            }
            val alertDurationNanos = (SystemClock.elapsedRealtimeNanos() - alertStartNanos).coerceAtLeast(0L)
            timingTracker.recordAlertProcessing(alertDurationNanos)
            AriaRuntimeTimingTracker.recordAlerts(alertDurationNanos / 1_000_000.0)

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
            val analyzerDurationNanos = (analyzerEndNanos - analyzerStartNanos).coerceAtLeast(0L)
            AriaRuntimeTimingTracker.recordAnalyzer(analyzerDurationNanos / 1_000_000.0)
            AriaRuntimeTimingTracker.recordPreprocess(preprocessDurationNanos / 1_000_000.0)
            inferenceOutput?.invokeDurationNanos?.let { AriaRuntimeTimingTracker.recordInvoke(it / 1_000_000.0) }
            inferenceOutput?.postprocessDurationNanos?.let { AriaRuntimeTimingTracker.recordPost(it / 1_000_000.0) }
            completedFrames.incrementAndGet()

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
                        groundContactDistance = groundContactDist,
                        configuredBackend = modelStore.segmenter.configuredBackend,
                        configuredCpuThreads = modelStore.segmenter.configuredCpuThreads,
                        activeBackendType = modelStore.segmenter.activeBackendType,
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
                val uiDurationNanos = (publishCompletedNanos - analyzerEndNanos).coerceAtLeast(0L)
                AriaRuntimeTimingTracker.recordUi(uiDurationNanos / 1_000_000.0)
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
            groundContactMotionTracker.stop()
            groundContactDistanceEstimator.reset()
            unbindUseCasesOnMain()
            mutableUiState.update { state ->
                state.copy(
                    cameraStatus = CameraSessionStatus.Error,
                    cameraErrorDetail = "프레임 분석 중 오류가 발생하여 카메라 분석을 중지했습니다 (${t.javaClass.simpleName}).",
                    groundContactDistance = GroundContactDistance(),
                )
            }
        } finally {
            imageProxyCloses.incrementAndGet()
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
                activeBackendType = modelStore.segmenter.activeBackendType,
                activeBackendDescription = modelStore.segmenter.activeBackendDescription,
                groundContactDistance = GroundContactDistance(
                    distanceMeters = null,
                    message = "미리보기 전용 (거리 분석 비활성)",
                    timestampNanos = nowNanos,
                ),
            )
        }
    }

    fun updateSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?, targetRotation: Int = activeTargetRotation) {
        val previewAvailabilityChanged = (activeSurfaceProvider == null) != (surfaceProvider == null)
        val lifecycleOwner = activeLifecycleOwner
        activeSurfaceProvider = surfaceProvider
        if (activeTargetRotation != targetRotation) {
            activeTargetRotation = targetRotation
            motionTracker.setDisplayRotation(targetRotation)
            groundContactMotionTracker.setDisplayRotation(targetRotation)
            activeEffectiveFov = null
            activeFullCameraModel = null
            groundContactDistanceEstimator.reset()
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
        if (previewAvailabilityChanged && running.get() && lifecycleOwner != null) {
            startCamera(lifecycleOwner, surfaceProvider, currentMode, targetRotation)
            return
        }
        ContextCompat.getMainExecutor(context).execute {
            boundPreview?.setSurfaceProvider(surfaceProvider)
        }
    }

    fun stopCamera() {
        cancelScheduledRetry()
        retryCount = 0
        pendingFrameSnapshot.getAndSet(null)?.invoke(null)
        sessionGeneration.incrementAndGet()
        running.set(false)
        activeLifecycleOwner = null
        activeSurfaceProvider = null
        motionTracker.stop()
        alertCoordinator.stop()
        groundContactMotionTracker.stop()
        groundContactDistanceEstimator.reset()
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
                latestSummary = null,
                latestOverlayBitmap = null,
                hazardAssessment = HazardAssessment(),
                motionState = MotionState(),
                groundContactDistance = GroundContactDistance(),
            )
        }
    }

    private fun unbindUseCasesOnMain() {
        val provider = cameraProvider
        val preview = boundPreview
        val analysis = boundAnalysis
        val state = observedCameraState
        val observer = cameraStateObserver
        boundAnalysis = null
        boundPreview = null
        observedCameraState = null
        cameraStateObserver = null
        val cleanup = Runnable {
            if (state != null && observer != null) state.removeObserver(observer)
            runCatching { analysis?.clearAnalyzer() }
            runCatching { preview?.setSurfaceProvider(null) }
            val useCases = listOfNotNull(preview, analysis)
            if (provider != null && useCases.isNotEmpty()) {
                runCatching { provider.unbind(*useCases.toTypedArray()) }
            }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            cleanup.run()
        } else {
            ContextCompat.getMainExecutor(context).execute(cleanup)
        }
    }

    private fun unbindBoundUseCases(provider: ProcessCameraProvider) {
        val state = observedCameraState
        val observer = cameraStateObserver
        if (state != null && observer != null) state.removeObserver(observer)
        observedCameraState = null
        cameraStateObserver = null
        val preview = boundPreview
        val analysis = boundAnalysis
        boundPreview = null
        boundAnalysis = null
        analysis?.clearAnalyzer()
        preview?.setSurfaceProvider(null)
        val useCases = listOfNotNull(preview, analysis)
        if (useCases.isNotEmpty()) provider.unbind(*useCases.toTypedArray())
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
        val terminalStatus = mutableUiState.value.cameraStatus
        val terminalDetail = mutableUiState.value.cameraErrorDetail
        stopCamera()
        motionTracker.close()
        alertCoordinator.close()
        groundContactMotionTracker.close()
        bodyMountProfileStore.close()
        groundContactDistanceEstimator.reset()
        modelStore.close()
        if (terminalStatus == CameraSessionStatus.Error || terminalStatus == CameraSessionStatus.PermissionDenied) {
            mutableUiState.update {
                it.copy(cameraStatus = terminalStatus, cameraErrorDetail = terminalDetail)
            }
        }
    }

    companion object {
        private const val METRICS_PUBLISH_INTERVAL_NANOS = 250_000_000L
        internal const val MAX_RETRY_COUNT = 5
        internal val BACKOFF_DELAYS_MS = longArrayOf(500L, 1000L, 2000L, 4000L, 8000L)
        private const val TAG = "AriaVisionPipeline"
    }
}

class VisionViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    val uiState: StateFlow<VisionUiState> = VisionSession.uiState
    val modelState: StateFlow<VisionModelState> = VisionSession.modelState
    val isInitialRestoreCompleted: StateFlow<Boolean> = VisionSession.isInitialRestoreCompleted

    val isInitialized: Boolean
        get() = VisionSession.isInitialized

    fun ensureController(): VisionPipelineController = VisionSession.ensureController(appContext)

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

    fun stopCamera() = VisionForegroundService.requestStop(appContext)

    val requiresExplicitRestart: Boolean
        get() = VisionSession.requiresExplicitRestart(appContext)

    fun close() = VisionSession.closeController()

    override fun onCleared() {
        super.onCleared()
    }
}
