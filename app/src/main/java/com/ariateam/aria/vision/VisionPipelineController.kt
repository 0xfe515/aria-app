package com.ariateam.aria.vision

import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import com.ariateam.aria.startup.StartupVisionPreparationPolicy
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
import androidx.annotation.VisibleForTesting
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
import com.ariateam.aria.R
import com.ariateam.aria.command.CommandCoordinator
import com.ariateam.aria.hazard.CorridorDirection
import com.ariateam.aria.hazard.AlertDecision
import com.ariateam.aria.hazard.AlertSuppressionReason
import com.ariateam.aria.hazard.GroundPointProjector
import com.ariateam.aria.hazard.HazardAlertCoordinator
import com.ariateam.aria.hazard.HazardAssessment
import com.ariateam.aria.hazard.HazardDetector
import com.ariateam.aria.hazard.HazardLevel
import com.ariateam.aria.hazard.MotionDegradedReason
import com.ariateam.aria.hazard.MotionState
import com.ariateam.aria.hazard.MotionTracker
import com.ariateam.aria.hazard.ObjectTracker
import com.ariateam.aria.hazard.walkableCentroidOffsetX
import com.ariateam.aria.runtime.AriaRuntimeTimingTracker
import com.ariateam.aria.speech.HazardSpeechBridge
import com.ariateam.aria.vision.od.AuxiliaryObjectDetectionPipeline
import com.ariateam.aria.vision.od.ObjectDetectionHazardFusion
import com.ariateam.aria.vision.od.OdFusionRejection
import com.ariateam.aria.vision.od.OdGeometryToken
import com.ariateam.aria.vision.od.OdOverlaySnapshot
import com.ariateam.aria.vision.od.OdSidecarStatus
import com.ariateam.aria.vision.od.OdTelemetry
import com.ariateam.aria.walking.WalkingAssistAction
import com.ariateam.aria.walking.WalkingAssistAnnouncement
import com.ariateam.aria.walking.WalkingAssistCause
import com.ariateam.aria.walking.WalkingAssistCopy
import com.ariateam.aria.walking.WalkingAssistDiagnosticEvent
import com.ariateam.aria.walking.WalkingAssistDiagnostics
import com.ariateam.aria.walking.WalkingAssistInput
import com.ariateam.aria.walking.WalkingAssistPhase
import com.ariateam.aria.walking.WalkingAssistState
import com.ariateam.aria.walking.WalkingAssistStateMachine
import com.ariateam.aria.walking.WalkingAssistTransition
import com.ariateam.aria.walking.WalkingDiagnosticReason
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
    val computeTelemetry: VisionComputeTelemetry = VisionComputeTelemetry.INACTIVE,
    val walkingAssistState: WalkingAssistState = WalkingAssistState(
        phase = WalkingAssistPhase.PREPARING,
        cause = WalkingAssistCause.SESSION_STARTING,
        action = WalkingAssistAction.NONE,
    ),
    val walkingDiagnosticEvents: List<WalkingAssistDiagnosticEvent> = emptyList(),
    /** Bounded auxiliary object-detection sidecar telemetry; purely diagnostic, no Home action. */
    val odTelemetry: OdTelemetry = OdTelemetry.INACTIVE,
    /** Bounded display-only object-detection overlay snapshot; null when inactive, empty, or expired. */
    val odOverlay: OdOverlaySnapshot? = null,
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
    val isRunning: Boolean = false,
    val isClosed: Boolean = false,
    val sessionGeneration: Long = 0L,
    val boundTicket: Long = 0L,
    val requiresExplicitRestart: Boolean = false,
    val controllerIdentity: String = "",
    val activeSurfaceProviderSet: Boolean = false,
    val pipelineExecutorActive: Boolean = true,
    val pipelineThreadName: String? = null,
    val analyzerExecutorActive: Boolean = true,
    val analyzerThreadName: String? = null,
    val isBindingInFlight: Boolean = false,
    val isFrameStalled: Boolean = false,
    val lastFrameArrivalElapsedMs: Long = 0L,
    val isFrameInFlight: Boolean = false,
    val stallRecoveryAttempts: Int = 0,
    val isInvocationReady: Boolean = false,
    val isRebindInProgress: Boolean = false,
    val deviceErrorRecoveryAttempts: Int = 0,
    val odTelemetry: OdTelemetry = OdTelemetry.INACTIVE,
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
    val objectTracker: ObjectTracker = ObjectTracker(),
    val objectDetectionPipeline: AuxiliaryObjectDetectionPipeline = AuxiliaryObjectDetectionPipeline(
        engineFactory = { com.ariateam.aria.vision.od.LiteRtObjectDetector(context.applicationContext) },
        clockNanos = { SystemClock.elapsedRealtimeNanos() },
        config = com.ariateam.aria.vision.od.OdSidecarConfig(
            followSegmentationCadence = true,
            holdOverlayUntilNextResult = true,
        ),
    ),
    val objectDetectionFusion: ObjectDetectionHazardFusion = ObjectDetectionHazardFusion(),
    private val mutableUiState: MutableStateFlow<VisionUiState> = MutableStateFlow(VisionUiState()),
    analyzerExecutorOverride: ExecutorService? = null,
) : AutoCloseable {
    @Volatile
    private var analyzerThread: Thread? = null

    private var lastTrackedGeometry: OdGeometryToken? = null

    val analyzerExecutor: ExecutorService = analyzerExecutorOverride ?: Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AriaCameraAnalyzerThread").also { t ->
            t.isDaemon = true
            analyzerThread = t
        }
    }
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val sessionGeneration = AtomicLong(0L)
    private val walkingAssistStateMachine = WalkingAssistStateMachine()
    private val walkingAssistDiagnostics = WalkingAssistDiagnostics()
    private val walkingStatusSequence = AtomicLong(0L)
    private val hazardSpeechBridge = HazardSpeechBridge(context.applicationContext)
    private val lastWalkingFrameSequence = AtomicLong(0L)
    private val latestWalkingResultCaptureNanos = AtomicLong(0L)
    private val walkingAnnouncementFrameSequences = LinkedHashMap<Long, Long>()
    @Volatile
    private var walkingAssistTicket = -1L
    @Volatile
    private var walkingCameraTicket = -1L
    private var walkingAssistTickRunnable: Runnable? = null
    private var calibrationReceiverRegistered = false
    private val calibrationReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (closed.get()) return
            when (intent?.action) {
                CommandCoordinator.ACTION_FORWARD_CALIBRATION -> {
                    val requestId = intent.getLongExtra(CommandCoordinator.EXTRA_CALIBRATION_REQUEST_ID, 0L)
                    if (requestId > 0L) {
                        val calibrated = walkingCameraTicket >= 0L && running.get() && calibrateForward()
                        hazardSpeechBridge.reportForwardCalibrationResult(requestId, calibrated)
                    }
                }
                CommandCoordinator.ACTION_WALKING_SPEECH_STARTED -> recordWalkingSpeechStart(
                    session = intent.getLongExtra(CommandCoordinator.EXTRA_WALKING_SESSION, -1L),
                    sequence = intent.getLongExtra(CommandCoordinator.EXTRA_WALKING_SEQUENCE, -1L),
                    elapsedRealtimeNanos = intent.getLongExtra(
                        CommandCoordinator.EXTRA_WALKING_SPEECH_STARTED_NANOS,
                        0L,
                    ),
                )
            }
        }
    }
    private val lastPublishedNanos = AtomicLong(0L)
    private var lastOdLogElapsedNanos = 0L
    private var lastOdLogStatus: OdSidecarStatus = OdSidecarStatus.DISABLED
    private var lastOdLogRateHz = 0
    private var lastOdLogFollowsCadence = false
    /**
     * Explicit native-order view over the preprocessor's populated RGB float array.
     * Never derive this from `inputByteBuffer.duplicate().asFloatBuffer()`: a duplicated
     * ByteBuffer defaults to BIG_ENDIAN and would hand the detector garbage floats.
     */
    private val odRgbView: java.nio.FloatBuffer by lazy {
        java.nio.FloatBuffer.wrap(preprocessor.reusableFloatArray)
    }
    private val analyzerEntries = AtomicLong(0L)
    private val pipelineInputs = AtomicLong(0L)
    private val inferenceCallEntries = AtomicLong(0L)
    private val inferenceReturns = AtomicLong(0L)
    private val completedFrames = AtomicLong(0L)
    private val imageProxyCloses = AtomicLong(0L)
    private val cameraCaptureCallbacks = AtomicLong(0L)
    private val pendingFrameSnapshot = java.util.concurrent.atomic.AtomicReference<((android.graphics.Bitmap?) -> Unit)?>()
    private val lastBoundTicket = AtomicLong(0L)
    private val isBindingInFlight = AtomicBoolean(false)
    private val isRebindInProgress = AtomicBoolean(false)
    private val lastFrameArrivalNanos = AtomicLong(0L)
    private val lastFrameCompletionNanos = AtomicLong(0L)
    private val frameProcessingStartNanos = AtomicLong(0L)
    private val isFrameInFlight = AtomicBoolean(false)
    private val isFrameStalled = AtomicBoolean(false)
    private val continuousHealthyFrames = AtomicLong(0L)
    private val healthyStreamingStartNanos = AtomicLong(0L)
    private var streamingWatchdogRunnable: Runnable? = null
    private val stallRecoveryAttempts = AtomicInteger(0)
    private val deviceErrorRecoveryAttempts = AtomicInteger(0)

    init {
        alertCoordinator.setAlertSink(hazardSpeechBridge)
        try {
            ContextCompat.registerReceiver(
                context.applicationContext,
                calibrationReceiver,
                IntentFilter().apply {
                    addAction(CommandCoordinator.ACTION_FORWARD_CALIBRATION)
                    addAction(CommandCoordinator.ACTION_WALKING_SPEECH_STARTED)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            calibrationReceiverRegistered = true
        } catch (t: Throwable) {
            Log.w(TAG, "Could not register local forward calibration receiver", t)
        }
        modelStore.cameraStabilityDecider = {
            val isLiveBinding = isBindingInFlight.get()
            val isLiveRebind = isRebindInProgress.get()
            val isRunningLive = running.get()
            val status = mutableUiState.value.cameraStatus
            val isGraphActive = isRunningLive && (
                isLiveBinding ||
                isLiveRebind ||
                status == CameraSessionStatus.Starting ||
                status == CameraSessionStatus.Running ||
                activeLifecycleOwner != null
            )
            val isRecovering = retryRunnable != null || isLiveRebind || (isRunningLive && isLiveBinding)
            StartupVisionPreparationPolicy.evaluateGatingAction(
                isCameraGraphActive = isGraphActive,
                isCameraPending = false,
                isCameraRecovering = isRecovering,
                hasCameraPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED,
                isExplicitStopActive = VisionSession.requiresExplicitRestart(context),
                isClosed = closed.get(),
            )
        }
    }

    val isClosed: Boolean
        get() = closed.get()

    val isRunning: Boolean
        get() = running.get()

    val currentSessionTicket: Long
        get() = sessionGeneration.get()

    val activeOwner: LifecycleOwner?
        get() = activeLifecycleOwner

    private val mainHandler = Handler(Looper.getMainLooper())
    private var retryRunnable: Runnable? = null
    private var startupWatchdogRunnable: Runnable? = null
    private var retryCount = 0
    private var availabilityCallback: CameraManager.AvailabilityCallback? = null
    private var sessionStartNanos = 0L

    @Volatile
    private var selectedCameraId: String? = null
    @Volatile
    private var isTargetCameraAvailable = true
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
    private var rebindPriorState: androidx.lifecycle.LiveData<CameraState>? = null

    @Volatile
    private var rebindTempObserver: Observer<CameraState>? = null

    @Volatile
    private var rebindCloseDeadlineRunnable: Runnable? = null

    @Volatile
    private var rebindAvailabilityDeadlineRunnable: Runnable? = null

    @Volatile
    private var activeRebindCoordinator: RecoverySequencingCoordinator? = null

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
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val lastArrival = lastFrameArrivalNanos.get()
        val elapsedMs = if (lastArrival > 0L) (nowNanos - lastArrival) / 1_000_000L else 0L
        val inFlight = isFrameInFlight.get()
        val inFlightStart = frameProcessingStartNanos.get()
        val inFlightMs = if (inFlight && inFlightStart > 0L) (nowNanos - inFlightStart) / 1_000_000L else 0L
        val lastActive = maxOf(lastArrival, lastFrameCompletionNanos.get())
        val silentIdleMs = if (!inFlight && lastActive > 0L) (nowNanos - lastActive) / 1_000_000L else 0L
        val stalled = isFrameStalled.get() || (running.get() && (
            (inFlight && inFlightMs >= IN_FLIGHT_PROCESSING_TIMEOUT_MS) ||
            (!inFlight && lastArrival > 0L && silentIdleMs >= STREAMING_STALL_TIMEOUT_MS)
        ))
        val rawModelState = modelStore.state.value
        val isInvocationReady = modelStore.segmenter.isInvocationReady
        val effectiveModelName = resolveEffectiveModelStateName(rawModelState, isInvocationReady)
        val isRestoreComplete = modelStore.isInitialRestoreCompleted.value &&
            (rawModelState !is VisionModelState.Ready || isInvocationReady)
        return VisionPipelineDiagnosticSnapshot(
            lifecycleOwner = owner?.javaClass?.simpleName,
            lifecycleState = owner?.lifecycle?.currentState?.name ?: "NONE",
            cameraState = cameraState?.toString(),
            cameraStateType = cameraState?.type?.toString(),
            cameraError = cameraState?.error?.toString(),
            modelRestoreComplete = isRestoreComplete,
            modelState = effectiveModelName,
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
            isRunning = running.get(),
            isClosed = closed.get(),
            sessionGeneration = sessionGeneration.get(),
            boundTicket = lastBoundTicket.get(),
            requiresExplicitRestart = VisionSession.requiresExplicitRestart(context),
            controllerIdentity = Integer.toHexString(System.identityHashCode(this)),
            activeSurfaceProviderSet = activeSurfaceProvider != null,
            pipelineExecutorActive = !modelStore.segmenter.pipelineExecutor.isShutdown,
            pipelineThreadName = modelStore.segmenter.executionThread?.name,
            analyzerExecutorActive = !analyzerExecutor.isShutdown,
            analyzerThreadName = analyzerThread?.name,
            isBindingInFlight = isBindingInFlight.get(),
            isFrameStalled = stalled,
            lastFrameArrivalElapsedMs = elapsedMs,
            isFrameInFlight = inFlight,
            stallRecoveryAttempts = stallRecoveryAttempts.get(),
            isInvocationReady = isInvocationReady,
            isRebindInProgress = isRebindInProgress.get(),
            deviceErrorRecoveryAttempts = deviceErrorRecoveryAttempts.get(),
            odTelemetry = objectDetectionPipeline.telemetry(nowNanos),
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
        objectDetectionFusion.reset()
        hazardDetector.reset()
        objectTracker.reset()
        lastTrackedGeometry = null
        objectDetectionPipeline.setEnabled(false)
        objectDetectionPipeline.onSessionStopped("전처리 전략 변경")
        motionTracker.updateCameraGeometry(null)
        mutableUiState.update {
            it.copy(
                preprocessingStrategy = strategy,
                odTelemetry = objectDetectionPipeline.telemetry(),
                odOverlay = null,
            )
        }
    }

    fun setPipelineMode(mode: VisionPipelineMode) {
        if (currentMode == mode) return
        currentMode = mode
        timingTracker.reset()
        AriaRuntimeTimingTracker.reset()
        lastPublishedNanos.set(0L)
        groundContactDistanceEstimator.reset()
        objectDetectionFusion.reset()
        hazardDetector.reset()
        objectTracker.reset()
        lastTrackedGeometry = null
        objectDetectionPipeline.setEnabled(false)
        objectDetectionPipeline.onSessionStopped("파이프라인 모드 변경")
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
                    odTelemetry = objectDetectionPipeline.telemetry(),
                    odOverlay = null,
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

    fun calibrateForward(): Boolean {
        val calibrated = motionTracker.calibrateForward()
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val motion = motionTracker.stateAt(nowNanos)
        mutableUiState.update { it.copy(motionState = motion) }
        val ticket = walkingCameraTicket
        if (ticket >= 0L) updateWalkingAssist(ticket)
        return calibrated
    }

    private fun startWalkingAssistSession(ticket: Long, nowNanos: Long = SystemClock.elapsedRealtimeNanos()) {
        cancelWalkingAssistTick()
        walkingCameraTicket = ticket
        val assistGeneration = VisionSession.nextWalkingAssistGeneration()
        walkingAssistTicket = assistGeneration
        walkingStatusSequence.set(0L)
        lastWalkingFrameSequence.set(0L)
        latestWalkingResultCaptureNanos.set(0L)
        synchronized(walkingAnnouncementFrameSequences) {
            walkingAnnouncementFrameSequences.clear()
        }
        walkingAssistDiagnostics.startSession(
            generation = assistGeneration,
            elapsedRealtimeNanos = nowNanos,
            backend = modelStore.segmenter.activeBackendDescription,
            model = modelIdentifier(),
        )
        val nowMs = SystemClock.elapsedRealtime()
        val transition = walkingAssistStateMachine.startSession(assistGeneration, nowMs, nowNanos)
        mutableUiState.update {
            it.copy(
                computeTelemetry = VisionComputeTelemetry.INACTIVE,
                walkingAssistState = transition.current,
                walkingDiagnosticEvents = walkingAssistDiagnostics.snapshot(),
            )
        }
        hazardSpeechBridge.start()
        val statusSequence = walkingStatusSequence.incrementAndGet()
        val frameSequence = lastWalkingFrameSequence.get()
        synchronized(walkingAnnouncementFrameSequences) {
            walkingAnnouncementFrameSequences[statusSequence] = frameSequence
            while (walkingAnnouncementFrameSequences.size > 64) {
                walkingAnnouncementFrameSequences.remove(walkingAnnouncementFrameSequences.keys.first())
            }
        }
        hazardSpeechBridge.updateWalkingAssistState(
            assistGeneration,
            statusSequence,
            context.getString(WalkingAssistCopy.status(transition.current)),
            announce = false,
            stopped = transition.current.phase == WalkingAssistPhase.STOPPED,
        )
        scheduleWalkingAssistTick(ticket)
    }

    private fun updateWalkingAssist(
        ticket: Long,
        resultCaptureTimestampNanos: Long? = null,
        transientFault: WalkingAssistCause? = null,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { updateWalkingAssist(ticket, resultCaptureTimestampNanos, transientFault) }
            return
        }
        if (ticket != walkingCameraTicket) return
        val assistGeneration = walkingAssistTicket
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val nowMs = SystemClock.elapsedRealtime()
        val ui = mutableUiState.value
        val rawModelState = modelStore.state.value
        val motion = motionTracker.stateAt(nowNanos)
        val cameraPermissionGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        val isInvocationReady = rawModelState is VisionModelState.Ready &&
            modelStore.segmenter.isReady &&
            modelStore.segmenter.isInvocationReady
        val transition = walkingAssistStateMachine.update(
            WalkingAssistInput(
                sessionGeneration = assistGeneration,
                sessionActive = running.get(),
                cameraPermissionGranted = cameraPermissionGranted,
                cameraReady = ui.cameraStatus == CameraSessionStatus.Running,
                cameraLost = ui.cameraStatus in setOf(
                    CameraSessionStatus.CameraUnavailable,
                    CameraSessionStatus.Error,
                ),
                modelReady = isInvocationReady,
                modelFailed = rawModelState is VisionModelState.Error ||
                    (rawModelState is VisionModelState.Missing && modelStore.isInitialRestoreCompleted.value),
                motionSensorsAvailable = motionTracker.sensorsAvailable,
                motionCalibrated = motion.isCalibrated,
                motionReliable = motion.isReliable &&
                    timingTracker.sensorTimestampSource == SensorTimestampSource.REALTIME_VERIFIED,
                resultCaptureTimestampNanos = resultCaptureTimestampNanos,
                transientFault = transientFault ?: if (isFrameStalled.get()) {
                    WalkingAssistCause.PIPELINE_STALLED
                } else {
                    null
                },
            ),
            nowMs,
            nowNanos,
        )
        val telemetry = computeTelemetry(ui.cameraStatus == CameraSessionStatus.Running)
        publishWalkingTransition(assistGeneration, transition, nowNanos, telemetry)
    }

    private fun publishWalkingTransition(
        ticket: Long,
        transition: WalkingAssistTransition,
        nowNanos: Long,
        telemetry: VisionComputeTelemetry = computeTelemetry(
            mutableUiState.value.cameraStatus == CameraSessionStatus.Running,
        ),
    ) {
        if (ticket != walkingAssistTicket) return
        val announcement = transition.announcement
        if (announcement == WalkingAssistAnnouncement.LIMITED) {
            walkingAssistDiagnostics.recordLimited(
                generation = ticket,
                frameSequence = lastWalkingFrameSequence.get(),
                elapsedRealtimeNanos = nowNanos,
                reason = diagnosticReason(transition.current.cause),
            )
        }
        mutableUiState.update {
            if (ticket != walkingAssistTicket) it else {
                val keepOverlay = running.get() &&
                    it.cameraStatus == CameraSessionStatus.Running &&
                    currentMode != VisionPipelineMode.PREVIEW_ONLY &&
                    !isFrameStalled.get() &&
                    it.odOverlay != null &&
                    it.odOverlay.isFreshAt(nowNanos)
                it.copy(
                    walkingAssistState = transition.current,
                    computeTelemetry = telemetry,
                    walkingDiagnosticEvents = walkingAssistDiagnostics.snapshot(),
                    odOverlay = if (keepOverlay) it.odOverlay else null,
                )
            }
        }
        val statusSequence = walkingStatusSequence.incrementAndGet()
        val frameSequence = lastWalkingFrameSequence.get()
        synchronized(walkingAnnouncementFrameSequences) {
            walkingAnnouncementFrameSequences[statusSequence] = frameSequence
            while (walkingAnnouncementFrameSequences.size > 64) {
                walkingAnnouncementFrameSequences.remove(walkingAnnouncementFrameSequences.keys.first())
            }
        }
        hazardSpeechBridge.updateWalkingAssistState(
            ticket,
            statusSequence,
            context.getString(WalkingAssistCopy.status(transition.current)),
            announce = announcement != null,
            stopped = transition.current.phase == WalkingAssistPhase.STOPPED,
        )
        if (announcement != null) {
            walkingAssistDiagnostics.recordDispatch(ticket, frameSequence, nowNanos)
            mutableUiState.update { it.copy(walkingDiagnosticEvents = walkingAssistDiagnostics.snapshot()) }
        }
    }

    private fun scheduleWalkingAssistTick(ticket: Long) {
        cancelWalkingAssistTick()
        val tick = object : Runnable {
            override fun run() {
                if (ticket != walkingCameraTicket) return
                updateWalkingAssist(
                    ticket,
                    latestWalkingResultCaptureNanos.get().takeIf { it > 0L },
                )
                if (ticket == walkingCameraTicket && walkingAssistStateMachineStateIsNotStopped()) {
                    walkingAssistTickRunnable = this
                    mainHandler.postDelayed(this, WALKING_ASSIST_POLL_MS)
                }
            }
        }
        walkingAssistTickRunnable = tick
        mainHandler.postDelayed(tick, WALKING_ASSIST_POLL_MS)
    }

    private fun walkingAssistStateMachineStateIsNotStopped(): Boolean =
        mutableUiState.value.walkingAssistState.phase != WalkingAssistPhase.STOPPED

    private fun cancelWalkingAssistTick() {
        walkingAssistTickRunnable?.let(mainHandler::removeCallbacks)
        walkingAssistTickRunnable = null
    }

    private fun stopWalkingAssist(cameraTicket: Long, announceStopped: Boolean = true) {
        if (cameraTicket != walkingCameraTicket) return
        cancelWalkingAssistTick()
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val assistGeneration = walkingAssistTicket
        val transition = walkingAssistStateMachine.stopSession(assistGeneration)
        // Fence analyzer/TTS callbacks before publishing STOPPED; stop speech itself still runs.
        walkingAssistDiagnostics.stopSession(assistGeneration)
        publishWalkingTransition(
            assistGeneration,
            if (announceStopped) transition else transition.copy(announcement = null),
            nowNanos,
        )
    }

    internal fun recordWalkingSpeechStart(session: Long, sequence: Long, elapsedRealtimeNanos: Long) {
        if (session != walkingAssistTicket) return
        val frameSequence = synchronized(walkingAnnouncementFrameSequences) {
            walkingAnnouncementFrameSequences[sequence]
        } ?: 0L
        walkingAssistDiagnostics.recordSpeechStart(session, frameSequence, elapsedRealtimeNanos)
        mutableUiState.update { it.copy(walkingDiagnosticEvents = walkingAssistDiagnostics.snapshot()) }
    }

    private fun computeTelemetry(isCameraRunning: Boolean): VisionComputeTelemetry =
        modelStore.segmenter.currentComputeTelemetry(
            isCameraRunning = isCameraRunning,
            pipelineMode = currentMode,
            modelState = modelStore.state.value,
        )

    private fun modelIdentifier(): String = when (val state = modelStore.state.value) {
        is VisionModelState.Ready -> state.metadata.displayName
        is VisionModelState.Loading -> state.displayName
        is VisionModelState.Error -> "unavailable"
        VisionModelState.Missing -> "missing"
    }

    private fun diagnosticReason(cause: WalkingAssistCause): WalkingDiagnosticReason = when (cause) {
        WalkingAssistCause.CAMERA_PERMISSION_REQUIRED -> WalkingDiagnosticReason.PERMISSION_LOST
        WalkingAssistCause.CAMERA_UNAVAILABLE,
        WalkingAssistCause.CAMERA_STARTING -> WalkingDiagnosticReason.CAMERA_LOST
        WalkingAssistCause.MODEL_LOADING,
        WalkingAssistCause.MODEL_UNAVAILABLE -> WalkingDiagnosticReason.MODEL_UNAVAILABLE
        WalkingAssistCause.MOTION_UNAVAILABLE -> WalkingDiagnosticReason.MOTION_UNAVAILABLE
        WalkingAssistCause.MOTION_UNRELIABLE,
        WalkingAssistCause.CALIBRATION_REQUIRED -> WalkingDiagnosticReason.MOTION_UNRELIABLE
        WalkingAssistCause.RESULT_STALE -> WalkingDiagnosticReason.NO_FRESH_RESULT
        WalkingAssistCause.PIPELINE_STALLED -> WalkingDiagnosticReason.PIPELINE_STALLED
        WalkingAssistCause.PIPELINE_ERROR -> WalkingDiagnosticReason.PIPELINE_ERROR
        else -> WalkingDiagnosticReason.NO_FRESH_RESULT
    }

    private fun diagnosticReason(reason: AlertSuppressionReason): WalkingDiagnosticReason = when (reason) {
        AlertSuppressionReason.DUPLICATE_OR_OUT_OF_ORDER_CAPTURE -> WalkingDiagnosticReason.OUT_OF_ORDER_CAPTURE
        AlertSuppressionReason.STALE_RESULT -> WalkingDiagnosticReason.STALE_RESULT
        AlertSuppressionReason.FUTURE_CAPTURE -> WalkingDiagnosticReason.FUTURE_CAPTURE
        AlertSuppressionReason.INVALID_CAPTURE -> WalkingDiagnosticReason.INVALID_CAPTURE
        AlertSuppressionReason.COOLDOWN -> WalkingDiagnosticReason.COOLDOWN
        AlertSuppressionReason.CONFIRMATION_PENDING -> WalkingDiagnosticReason.CONFIRMATION_PENDING
        AlertSuppressionReason.NOT_A_HAZARD_CANDIDATE -> WalkingDiagnosticReason.NOT_A_HAZARD_CANDIDATE
        AlertSuppressionReason.SESSION_INACTIVE -> WalkingDiagnosticReason.ALERT_SESSION_INACTIVE
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
        isBindingInFlight.set(false)
        isRebindInProgress.set(false)
        cancelStartupWatchdog()
        cancelStreamingWatchdog()
        cancelScheduledRetry()
        retryCount = 0
        stallRecoveryAttempts.set(0)
        deviceErrorRecoveryAttempts.set(0)
        continuousHealthyFrames.set(0L)
        healthyStreamingStartNanos.set(0L)
        lastFrameArrivalNanos.set(0L)
        lastFrameCompletionNanos.set(0L)
        frameProcessingStartNanos.set(0L)
        isFrameInFlight.set(false)
        isFrameStalled.set(false)
        isTargetCameraAvailable = true
        modelStore.cancelCameraStabilityGate()
        sessionGeneration.incrementAndGet()
        val permissionTicket = sessionGeneration.get()
        running.set(false)
        activeLifecycleOwner = null
        activeSurfaceProvider = null
        motionTracker.stop()
        alertCoordinator.stop()
        groundContactMotionTracker.stop()
        groundContactDistanceEstimator.reset()
        objectDetectionFusion.reset()
        hazardDetector.reset()
        objectTracker.reset()
        lastTrackedGeometry = null
        objectDetectionPipeline.setEnabled(false)
        objectDetectionPipeline.onSessionStopped("카메라 권한 거부")
        modelStore.cancelCameraStabilityGate()
        unbindUseCasesOnMain()
        mutableUiState.update {
            it.copy(
                cameraStatus = CameraSessionStatus.PermissionDenied,
                cameraErrorDetail = "카메라 권한이 거부되었습니다. 권한을 허용한 뒤 다시 시작해 주세요.",
                latestSummary = null,
                latestOverlayBitmap = null,
                hazardAssessment = HazardAssessment(),
                motionState = MotionState(),
                computeTelemetry = VisionComputeTelemetry.INACTIVE,
                groundContactDistance = GroundContactDistance(),
                odTelemetry = objectDetectionPipeline.telemetry(),
                odOverlay = null,
            )
        }
        startWalkingAssistSession(permissionTicket)
        updateWalkingAssist(permissionTicket)
    }

    fun reportCameraServiceFailure(detail: String) {
        stopCamera(announceStopped = false)
        mutableUiState.update {
            it.copy(
                cameraStatus = CameraSessionStatus.Error,
                cameraErrorDetail = detail,
            )
        }
        val failureTicket = sessionGeneration.get()
        startWalkingAssistSession(failureTicket)
        updateWalkingAssist(failureTicket)
    }

    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider?,
        mode: VisionPipelineMode = currentMode,
        targetRotation: Int = Surface.ROTATION_0,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { startCamera(lifecycleOwner, surfaceProvider, mode, targetRotation) }
            return
        }
        if (closed.get()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionDenied()
            return
        }

        val currentTicket = sessionGeneration.get()
        val isSameSession = running.get() &&
            activeLifecycleOwner === lifecycleOwner &&
            currentMode == mode &&
            isSessionLive(currentTicket)

        if (isSameSession) {
            Log.i(TAG, "startCamera: active session matches owner and mode; coalescing update (ticket=$currentTicket)")
            updateSurfaceProvider(surfaceProvider, targetRotation)
            return
        }

        cancelScheduledRetry()
        val ticket = sessionGeneration.incrementAndGet()
        sessionStartNanos = SystemClock.elapsedRealtimeNanos()
        retryCount = 0
        stallRecoveryAttempts.set(0)
        deviceErrorRecoveryAttempts.set(0)
        continuousHealthyFrames.set(0L)
        healthyStreamingStartNanos.set(0L)
        lastFrameArrivalNanos.set(0L)
        lastFrameCompletionNanos.set(0L)
        frameProcessingStartNanos.set(0L)
        isFrameInFlight.set(false)
        isFrameStalled.set(false)
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
        isBindingInFlight.set(true)
        isRebindInProgress.set(false)
        timingTracker.reset()
        AriaRuntimeTimingTracker.reset()
        lastPublishedNanos.set(0L)

        motionTracker.start(targetRotation)
        alertCoordinator.start()
        groundContactMotionTracker.start(targetRotation)
        groundContactDistanceEstimator.reset()

        val modelLoaded = modelStore.segmenter.isReady
        objectDetectionFusion.reset()
        hazardDetector.reset()
        objectTracker.reset()
        lastTrackedGeometry = null
        objectDetectionPipeline.setEnabled(false)
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
                odTelemetry = objectDetectionPipeline.telemetry(),
                odOverlay = null,
            )
        }

        startWalkingAssistSession(ticket)

        scheduleStartupWatchdog(ticket)
        scheduleStreamingWatchdog(ticket)
        startCameraInternal(lifecycleOwner, ticket)
    }

    private fun startCameraInternal(
        lifecycleOwner: LifecycleOwner,
        ticket: Long,
    ) {
        if (!isSessionLive(ticket)) {
            isBindingInFlight.set(false)
            return
        }
        val existing = cameraProvider
        if (existing != null) {
            bindUseCases(existing, lifecycleOwner, ticket)
            return
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                if (!isSessionLive(ticket)) {
                    isBindingInFlight.set(false)
                    return@addListener
                }
                try {
                    val provider = future.get()
                    if (!isSessionLive(ticket)) {
                        isBindingInFlight.set(false)
                        return@addListener
                    }
                    cameraProvider = provider
                    bindUseCases(provider, lifecycleOwner, ticket)
                } catch (t: Throwable) {
                    isBindingInFlight.set(false)
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
        ticket: Long,
    ) {
        if (!isSessionLive(ticket)) {
            isBindingInFlight.set(false)
            return
        }
        val surfaceProvider = activeSurfaceProvider
        try {
            lastBoundTicket.set(ticket)
            val mode = currentMode
            val targetRotation = activeTargetRotation
            Log.i(TAG, "bindUseCases: ticket=$ticket, owner=${lifecycleOwner.javaClass.simpleName}, preview=${surfaceProvider != null}, mode=$mode")
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
                        cancelStartupWatchdog()
                        lastFrameArrivalNanos.set(nowNanos)
                        lastFrameCompletionNanos.set(nowNanos)
                        isFrameStalled.set(false)
                        val healthyCount = continuousHealthyFrames.incrementAndGet()
                        if (healthyStreamingStartNanos.get() == 0L) {
                            healthyStreamingStartNanos.set(nowNanos)
                        }
                        val healthyDurationMs = (nowNanos - healthyStreamingStartNanos.get()) / 1_000_000L
                        if (isWarmupStabilityPermitted(healthyCount, healthyDurationMs)) {
                            modelStore.signalCameraStability()
                        }
                        if (isHealthyStreamRecoverySustained(healthyCount, healthyDurationMs)) {
                            if (retryCount > 0 || stallRecoveryAttempts.get() > 0 || deviceErrorRecoveryAttempts.get() > 0) {
                                Log.i(
                                    TAG,
                                    "Camera preview stream sustained healthy for ${healthyDurationMs}ms ($healthyCount frames); resetting recovery counters (retryCount=$retryCount, stallRecoveryAttempts=${stallRecoveryAttempts.get()}, deviceErrorRecoveryAttempts=${deviceErrorRecoveryAttempts.get()})",
                                )
                                retryCount = 0
                                stallRecoveryAttempts.set(0)
                                deviceErrorRecoveryAttempts.set(0)
                            }
                        }
                        if (mutableUiState.value.cameraStatus != CameraSessionStatus.Running && isSessionLive(ticket)) {
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
                analysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                    try {
                        analyzerEntries.incrementAndGet()
                        analyzeFrame(imageProxy, ticket)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Unexpected exception in image analyzer callback", t)
                        runCatching { imageProxy.close() }
                    }
                }
            }

            val useCases = mutableListOf<UseCase>()
            preview?.let(useCases::add)
            useCases.add(analysis)
            val camera = provider.bindToLifecycle(lifecycleOwner, selector, *useCases.toTypedArray())
            var observedOpeningForThisBinding = false
            var observedOpenForThisBinding = false
            val stateObserver = Observer<CameraState> { cameraState ->
                val cameraError = cameraState.error
                if (cameraError != null) {
                    if (isSessionLive(ticket)) {
                        val classified = CameraErrorClassifier.classifyStateError(cameraError)
                        handleCameraFailure(ticket, classified)
                    }
                } else {
                    when (cameraState.type) {
                        CameraState.Type.PENDING_OPEN,
                        CameraState.Type.OPENING -> {
                            observedOpeningForThisBinding = true
                        }
                        CameraState.Type.OPEN -> {
                            if (observedOpeningForThisBinding) {
                                observedOpenForThisBinding = true
                            } else {
                                Log.i(TAG, "Ignoring initial cached OPEN state before OPENING transition for ticket $ticket")
                            }
                        }
                        CameraState.Type.CLOSED -> {
                            if (observedOpenForThisBinding && isSessionLive(ticket) && !isBindingInFlight.get() && !isRebindInProgress.get()) {
                                Log.w(TAG, "Camera transitioned to CLOSED after being verified OPEN without error; treating as transient disconnection")
                                handleCameraFailure(
                                    ticket,
                                    CameraFailureKind.TransientInUse(
                                        CameraState.ERROR_CAMERA_IN_USE,
                                        "카메라 연결이 비정상적으로 종료되었습니다.",
                                    ),
                                )
                            } else {
                                Log.i(TAG, "Ignoring expected CLOSED state (observedOpen=$observedOpenForThisBinding, bindingInFlight=${isBindingInFlight.get()}, rebindInProgress=${isRebindInProgress.get()}) for ticket $ticket")
                            }
                        }
                        else -> {}
                    }
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
                objectDetectionFusion.reset()
                hazardDetector.reset()
                objectTracker.reset()
                lastTrackedGeometry = null
                objectDetectionPipeline.setEnabled(false)
                objectDetectionPipeline.onSessionStarted(ticket, now)
                val modelLoaded = modelStore.segmenter.isReady
                mutableUiState.update { current ->
                    val status = if (current.cameraStatus == CameraSessionStatus.Running) {
                        CameraSessionStatus.Running
                    } else {
                        CameraSessionStatus.Starting
                    }
                    current.copy(
                        cameraStatus = status,
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
                        odTelemetry = objectDetectionPipeline.telemetry(now),
                        odOverlay = null,
                    )
                }
            }
        } catch (t: Throwable) {
            val classified = CameraErrorClassifier.classifyException(t)
            handleCameraFailure(ticket, classified)
        } finally {
            isBindingInFlight.set(false)
        }

        if (isSessionLive(ticket)) {
            val latestProvider = activeSurfaceProvider
            val isPreviewBound = boundPreview != null
            val shouldPreviewBeBound = latestProvider != null
            if (isPreviewBound != shouldPreviewBeBound) {
                Log.i(TAG, "activeSurfaceProvider changed while binding (bound=$isPreviewBound, target=$shouldPreviewBeBound); coalescing rebind")
                mainHandler.post {
                    if (isSessionLive(ticket) && !isRebindInProgress.get() && isBindingInFlight.compareAndSet(false, true)) {
                        unbindAndRebindUseCases(provider, lifecycleOwner, ticket)
                    }
                }
            } else if (isPreviewBound && latestProvider != null && latestProvider !== surfaceProvider) {
                boundPreview?.setSurfaceProvider(latestProvider)
            }
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
            is CameraFailureKind.Fatal -> {
                if (failure.code == CameraState.ERROR_CAMERA_FATAL_ERROR) {
                    val attempt = deviceErrorRecoveryAttempts.incrementAndGet()
                    if (!VisionPipelineController.canAttemptFatalDeviceRecovery(attempt)) {
                        Log.e(
                            TAG,
                            "Camera device fatal error recovery limit exceeded (attempt $attempt/$MAX_DEVICE_ERROR_RECOVERY_ATTEMPTS) for ticket $ticket",
                        )
                        modelStore.cancelCameraStabilityGate()
                        failCameraSession(
                            ticket,
                            "카메라 기기 치명적 오류 복구 한도를 초과했습니다 (${failure.description}). 앱 화면에서 다시 시작해 주세요.",
                        )
                    } else {
                        Log.w(
                            TAG,
                            "Camera device fatal error (ERROR_CAMERA_DEVICE) detected (attempt $attempt/$MAX_DEVICE_ERROR_RECOVERY_ATTEMPTS) for ticket $ticket; scheduling bounded recovery after confirmed close",
                        )
                        mutableUiState.update {
                            it.copy(
                                cameraStatus = CameraSessionStatus.CameraUnavailable,
                                cameraErrorDetail = "카메라 하드웨어 오류 감지 (기기 재설정 복구 시도 $attempt/$MAX_DEVICE_ERROR_RECOVERY_ATTEMPTS)...",
                            )
                        }
                        scheduleDeviceErrorRetry(ticket, failure.description)
                    }
                } else {
                    modelStore.cancelCameraStabilityGate()
                    failCameraSession(ticket, failure.description)
                }
            }
        }
    }

    private fun scheduleDeviceErrorRetry(ticket: Long, detail: String) {
        if (!isSessionLive(ticket)) return
        cancelStreamingWatchdog()
        cancelStartupWatchdog()
        val attemptToken = retryAttemptGeneration.incrementAndGet()
        val retryExecuted = AtomicBoolean(false)

        ensureAvailabilityCallbackRegistered()

        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            if (!isSessionLive(ticket)) return@Runnable
            if (retryAttemptGeneration.get() != attemptToken) return@Runnable
            if (retryExecuted.compareAndSet(false, true)) {
                retryCameraSession(ticket)
            }
        }
        retryRunnable = runnable
        mainHandler.postDelayed(runnable, DEVICE_ERROR_RETRY_DELAY_MS)
    }

    private fun scheduleTransientRetry(ticket: Long, detail: String) {
        if (!isSessionLive(ticket)) return
        cancelStreamingWatchdog()
        if (retryCount >= MAX_RETRY_COUNT) {
            failCameraSession(ticket, "카메라 연결 재시도 횟수를 초과했습니다 (${detail}). 앱 화면에서 다시 시작해 주세요.")
            return
        }
        val delayMs = BACKOFF_DELAYS_MS[retryCount.coerceIn(0, BACKOFF_DELAYS_MS.size - 1)]
        retryCount++
        val attemptToken = retryAttemptGeneration.incrementAndGet()
        val retryExecuted = AtomicBoolean(false)

        ensureAvailabilityCallbackRegistered()

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

    private fun ensureAvailabilityCallbackRegistered() {
        if (availabilityCallback != null) return
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        val targetCameraId = resolveSelectedCameraId()

        val callback = object : CameraManager.AvailabilityCallback() {
            override fun onCameraUnavailable(cameraId: String) {
                if (cameraId == targetCameraId) {
                    isTargetCameraAvailable = false
                    activeRebindCoordinator?.markCameraUnavailable()
                }
            }

            override fun onCameraAvailable(cameraId: String) {
                if (cameraId == targetCameraId) {
                    isTargetCameraAvailable = true
                    activeRebindCoordinator?.markCameraAvailable()
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
        cancelStartupWatchdog()
        cancelStreamingWatchdog()
        retryAttemptGeneration.incrementAndGet()
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        retryRunnable = null
        unregisterAvailabilityCallback()
    }

    private fun scheduleStartupWatchdog(ticket: Long) {
        if (!isSessionLive(ticket)) return
        if (isRebindInProgress.get()) {
            Log.i(TAG, "scheduleStartupWatchdog: rebind in progress for ticket $ticket; skipping duplicate scheduling")
            return
        }
        cancelStartupWatchdog()
        val runnable = Runnable {
            executeStartupWatchdogTimeout(ticket)
        }
        startupWatchdogRunnable = runnable
        mainHandler.postDelayed(runnable, STARTUP_TIMEOUT_MS)
    }

    private fun executeStartupWatchdogTimeout(ticket: Long) {
        if (!isSessionLive(ticket)) return
        val status = mutableUiState.value.cameraStatus
        if (status == CameraSessionStatus.Starting || status == CameraSessionStatus.CameraUnavailable) {
            val stallAttempts = stallRecoveryAttempts.get()
            Log.w(
                TAG,
                "Camera startup watchdog expired after ${STARTUP_TIMEOUT_MS}ms without frames (session $ticket, stallRecoveryAttempts=$stallAttempts, retryCount=$retryCount)",
            )
            if (stallAttempts > 0) {
                val nextAttempt = stallRecoveryAttempts.incrementAndGet()
                if (!canAttemptStallRecovery(nextAttempt)) {
                    Log.e(
                        TAG,
                        "Stall recovery failed: retries stuck in OPENING without frames after $nextAttempt attempts (session $ticket)",
                    )
                    failCameraSession(
                        ticket,
                        "카메라 프레임 스트림 복구 재시도 한도를 초과했습니다 (재바인딩 후 초기 프레임 미수신 ${nextAttempt}/$MAX_STALL_RECOVERY_ATTEMPTS). 앱 화면에서 다시 시작해 주세요.",
                    )
                    return
                }
            }
            handleCameraFailure(
                ticket,
                CameraFailureKind.TransientInUse(
                    CameraState.ERROR_OTHER_RECOVERABLE_ERROR,
                    if (stallAttempts > 0) {
                        "카메라 복구 재시도 후 초기 프레임 수신 대기 시간이 초과되었습니다 (시도 ${stallAttempts}/$MAX_STALL_RECOVERY_ATTEMPTS)."
                    } else {
                        "카메라 초기 프레임 수신 대기 시간이 초과되었습니다."
                    },
                ),
            )
        }
    }

    private fun cancelStartupWatchdog() {
        startupWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        startupWatchdogRunnable = null
    }

    private fun scheduleStreamingWatchdog(ticket: Long) {
        cancelStreamingWatchdog()
        if (!isSessionLive(ticket)) return
        val runnable = object : Runnable {
            override fun run() {
                if (!isSessionLive(ticket)) return
                checkStreamingLiveness(ticket)
                if (isSessionLive(ticket) && running.get()) {
                    mainHandler.postDelayed(this, STREAMING_WATCHDOG_INTERVAL_MS)
                }
            }
        }
        streamingWatchdogRunnable = runnable
        mainHandler.postDelayed(runnable, STREAMING_WATCHDOG_INTERVAL_MS)
    }

    private fun cancelStreamingWatchdog() {
        streamingWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        streamingWatchdogRunnable = null
    }

    private fun checkStreamingLiveness(ticket: Long) {
        if (!isSessionLive(ticket)) return
        if (isBindingInFlight.get()) return
        if (mutableUiState.value.cameraStatus != CameraSessionStatus.Running) return

        val lastArrival = lastFrameArrivalNanos.get()
        if (lastArrival <= 0L) {
            // Still waiting for initial frame after start or retry; startupWatchdog handles this phase
            return
        }
        val now = SystemClock.elapsedRealtimeNanos()
        val inFlight = isFrameInFlight.get()
        if (inFlight) {
            val inFlightStart = frameProcessingStartNanos.get()
            val inFlightElapsedMs = if (inFlightStart > 0L) (now - inFlightStart) / 1_000_000L else (now - lastArrival) / 1_000_000L
            if (inFlightElapsedMs >= IN_FLIGHT_PROCESSING_TIMEOUT_MS) {
                if (!isFrameStalled.compareAndSet(false, true)) {
                    return
                }
                cancelStreamingWatchdog()
                Log.e(TAG, "Camera frame processing hung in flight for ${inFlightElapsedMs}ms (session $ticket)")
                failCameraSession(
                    ticket,
                    "카메라 프레임 분석 또는 추론 처리가 완료되지 않고 지연되었습니다 (${inFlightElapsedMs}ms).",
                )
            }
        } else {
            val lastActive = maxOf(lastArrival, lastFrameCompletionNanos.get())
            val starvationElapsedMs = (now - lastActive) / 1_000_000L
            if (starvationElapsedMs >= STREAMING_STALL_TIMEOUT_MS) {
                if (!isFrameStalled.compareAndSet(false, true)) {
                    return
                }
                cancelStreamingWatchdog()
                val attempts = stallRecoveryAttempts.get()
                Log.w(
                    TAG,
                    "Camera streaming stall detected: no frames received for ${starvationElapsedMs}ms while Running (session $ticket, attempts=$attempts)",
                )
                continuousHealthyFrames.set(0L)
                healthyStreamingStartNanos.set(0L)
                objectDetectionFusion.reset()
                hazardDetector.reset()
                objectTracker.reset()
                lastTrackedGeometry = null
                objectDetectionPipeline.setEnabled(false)
                objectDetectionPipeline.onSessionStopped("프레임 스트림 정체")
                mutableUiState.update {
                    it.copy(
                        odTelemetry = objectDetectionPipeline.telemetry(),
                        odOverlay = null,
                    )
                }

                if (!canAttemptStallRecovery(attempts)) {
                    failCameraSession(
                        ticket,
                        "카메라 프레임 스트림 복구 재시도 한도를 초과했습니다 (${MAX_STALL_RECOVERY_ATTEMPTS}회 정체). 앱 화면에서 다시 시작해 주세요.",
                    )
                    return
                }
                val nextAttempt = stallRecoveryAttempts.incrementAndGet()
                handleCameraFailure(
                    ticket,
                    CameraFailureKind.TransientInUse(
                        CameraState.ERROR_OTHER_RECOVERABLE_ERROR,
                        "카메라 프레임 스트림 수신이 정체되었습니다 (${starvationElapsedMs}ms 미수신, 복구 시도 $nextAttempt/$MAX_STALL_RECOVERY_ATTEMPTS).",
                    ),
                )
            }
        }
    }

    private fun retryCameraSession(ticket: Long) {
        if (!isSessionLive(ticket)) return
        if (isRebindInProgress.get()) {
            Log.i(TAG, "retryCameraSession: rebind already in progress for ticket $ticket; skipping duplicate")
            return
        }
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        retryRunnable = null
        val owner = activeLifecycleOwner ?: return
        val provider = cameraProvider
        isBindingInFlight.set(true)
        lastFrameArrivalNanos.set(0L)
        isFrameInFlight.set(false)
        isFrameStalled.set(false)
        scheduleStartupWatchdog(ticket)
        scheduleStreamingWatchdog(ticket)
        if (provider != null) {
            unbindAndRebindUseCases(provider, owner, ticket)
        } else {
            startCameraInternal(owner, ticket)
        }
    }

    private fun failCameraSession(ticket: Long, detail: String) {
        if (sessionGeneration.get() != ticket || !running.getAndSet(false)) return
        isBindingInFlight.set(false)
        isRebindInProgress.set(false)
        cancelStartupWatchdog()
        cancelStreamingWatchdog()
        cancelScheduledRetry()
        cancelPendingRebindWait()
        retryCount = 0
        continuousHealthyFrames.set(0L)
        healthyStreamingStartNanos.set(0L)
        lastFrameArrivalNanos.set(0L)
        lastFrameCompletionNanos.set(0L)
        frameProcessingStartNanos.set(0L)
        isFrameInFlight.set(false)
        isFrameStalled.set(false)
        sessionGeneration.incrementAndGet()
        activeLifecycleOwner = null
        activeSurfaceProvider = null
        motionTracker.stop()
        alertCoordinator.stop()
        groundContactMotionTracker.stop()
        groundContactDistanceEstimator.reset()
        objectDetectionFusion.reset()
        hazardDetector.reset()
        objectTracker.reset()
        lastTrackedGeometry = null
        objectDetectionPipeline.setEnabled(false)
        objectDetectionPipeline.onSessionStopped("카메라 세션 종료")
        isTargetCameraAvailable = true
        modelStore.cancelCameraStabilityGate()
        unbindUseCasesOnMain()
        mutableUiState.update {
            it.copy(
                cameraStatus = CameraSessionStatus.Error,
                cameraErrorDetail = detail,
                latestSummary = null,
                latestOverlayBitmap = null,
                hazardAssessment = HazardAssessment(),
                motionState = MotionState(),
                computeTelemetry = VisionComputeTelemetry.INACTIVE,
                groundContactDistance = GroundContactDistance(),
                odTelemetry = objectDetectionPipeline.telemetry(),
                odOverlay = null,
            )
        }
    }

    private fun analyzeFrame(imageProxy: ImageProxy, ticket: Long) {
        cancelStartupWatchdog()
        val analyzerStartNanos = SystemClock.elapsedRealtimeNanos()
        lastFrameArrivalNanos.set(analyzerStartNanos)
        frameProcessingStartNanos.set(analyzerStartNanos)
        isFrameStalled.set(false)
        isFrameInFlight.set(true)

        val healthyCount = continuousHealthyFrames.incrementAndGet()
        if (healthyStreamingStartNanos.get() == 0L) {
            healthyStreamingStartNanos.set(analyzerStartNanos)
        }
        val healthyDurationMs = (analyzerStartNanos - healthyStreamingStartNanos.get()) / 1_000_000L
        if (isWarmupStabilityPermitted(healthyCount, healthyDurationMs)) {
            modelStore.signalCameraStability()
        }
        if (isHealthyStreamRecoverySustained(healthyCount, healthyDurationMs)) {
            if (retryCount > 0 || stallRecoveryAttempts.get() > 0 || deviceErrorRecoveryAttempts.get() > 0) {
                Log.i(
                    TAG,
                    "Camera stream sustained healthy for ${healthyDurationMs}ms ($healthyCount frames); resetting recovery counters (retryCount=$retryCount, stallRecoveryAttempts=${stallRecoveryAttempts.get()}, deviceErrorRecoveryAttempts=${deviceErrorRecoveryAttempts.get()})",
                )
                retryCount = 0
                stallRecoveryAttempts.set(0)
                deviceErrorRecoveryAttempts.set(0)
            }
        }
        try {
            if (!isSessionLive(ticket)) return
            if (mutableUiState.value.cameraStatus != CameraSessionStatus.Running) {
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
            val isModelReady = (modelStore.state.value is VisionModelState.Ready) && modelStore.segmenter.isReady
            val modelRestorePending = !modelStore.isInitialRestoreCompleted.value && !isModelReady
            val inferenceOutput = if (modelRestorePending) {
                null
            } else {
                inferenceCallEntries.incrementAndGet()
                val output = try {
                    modelStore.segmenter.runIfLoaded(
                        inputByteBuffer = preprocessor.inputByteBuffer,
                        produceOverlayBitmap = shouldPublishUi,
                        transform = cropRect,
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "Inference execution error on frame", t)
                    throw t
                }
                inferenceReturns.incrementAndGet()
                output
            }
            val analyzerEndNanos = SystemClock.elapsedRealtimeNanos()
            if (!isSessionLive(ticket)) return

            val alertStartNanos = SystemClock.elapsedRealtimeNanos()

            // Query aligned motion state and assess hazard
            val isClockRealtime = timingTracker.sensorTimestampSource == SensorTimestampSource.REALTIME_VERIFIED
            val diagnosticFrameSequence = if (isClockRealtime && inferenceOutput != null) {
                walkingAssistDiagnostics.recordCapture(
                    generation = walkingAssistTicket,
                    captureTimestampNanos = imageTimestampNanos,
                    elapsedRealtimeNanos = analyzerEndNanos,
                    backend = modelStore.segmenter.activeBackendDescription,
                    model = modelIdentifier(),
                )
            } else null
            if (diagnosticFrameSequence != null) {
                lastWalkingFrameSequence.set(diagnosticFrameSequence)
                latestWalkingResultCaptureNanos.set(imageTimestampNanos)
                walkingAssistDiagnostics.recordDecision(
                    walkingAssistTicket,
                    diagnosticFrameSequence,
                    SystemClock.elapsedRealtimeNanos(),
                )
            }
            if (inferenceOutput != null && isClockRealtime) {
                val centerFeatures = hazardDetector.extractCorridorFeatures(
                    modelStore.segmenter.reusableClassMask,
                    CorridorDirection.CENTER,
                )
                motionTracker.onVisualCorridorObservation(
                    timestampNanos = imageTimestampNanos,
                    roiWalkableRatio = centerFeatures.roiWalkable,
                    nearWalkableRatio = centerFeatures.nearWalkable,
                    walkableCentroidOffsetX = centerFeatures.walkableCentroidOffsetX,
                )
            }
            val motionRaw = motionTracker.stateAt(imageTimestampNanos)
            val motion = if (isClockRealtime) {
                motionRaw
            } else {
                motionRaw.copy(
                    confidence = 0f,
                    degradedReason = MotionDegradedReason.SENSORS_UNAVAILABLE,
                )
            }

            val currentProfile = bodyMountProfileStore.profile.value
            if (currentProfile != lastObservedProfile) {
                lastObservedProfile = currentProfile
                groundContactDistanceEstimator.reset()
                objectTracker.reset()
            }
            val groundOrientation = groundContactMotionTracker.orientationAt(imageTimestampNanos)
            val currentGeometry = OdGeometryToken.from(cropRect, rotation)
            if (lastTrackedGeometry != null && lastTrackedGeometry != currentGeometry) {
                objectTracker.reset()
            }
            lastTrackedGeometry = currentGeometry

            val baseAssessment = if (inferenceOutput != null) {
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
                    val pointProjector = GroundPointProjector.bind(
                        profile = currentProfile,
                        orientation = groundOrientation,
                        effectiveFov = activeEffectiveFov,
                        fullCameraModel = activeFullCameraModel,
                        cropLeftNorm = cropRect.normLeft,
                        cropTopNorm = cropRect.normTop,
                        cropWidthNorm = cropRect.normWidth,
                        cropHeightNorm = cropRect.normHeight,
                    )
                    val gatedOd = objectDetectionFusion.evidenceGate.accept(
                        evidence = objectDetectionPipeline.latestEvidence(),
                        frameGeometry = currentGeometry,
                        frameCaptureTimestampNanos = imageTimestampNanos,
                        nowElapsedNanos = SystemClock.elapsedRealtimeNanos(),
                        sessionToken = ticket,
                        isClockRealtime = isClockRealtime,
                        motion = motion,
                    )
                    if (gatedOd.clearTemporal && gatedOd.rejection != OdFusionRejection.NO_EVIDENCE) {
                        objectTracker.reset()
                    }
                    val odTracks = if (gatedOd.accepted != null) {
                        objectTracker.update(
                            detections = gatedOd.accepted.detections,
                            captureNanos = gatedOd.accepted.captureTimestampNanos,
                            corridor = motion.activeCorridor,
                            classMask = modelStore.segmenter.reusableClassMask,
                            maskWidth = VisionContract.MODEL_WIDTH,
                            maskHeight = VisionContract.MODEL_HEIGHT,
                            projector = pointProjector,
                        )
                    } else {
                        emptyList()
                    }
                    hazardDetector.assessHazard(
                        classMask = modelStore.segmenter.reusableClassMask,
                        motionState = motion,
                        timestampNanos = imageTimestampNanos,
                        tracks = odTracks,
                        lumaOrRgb = preprocessor.reusableFloatArray,
                        projector = pointProjector,
                        applyRuleUpgrade = true,
                    )
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

            // Conservative advisory fusion: only a copy of the base assessment may change, and the
            // existing alert coordinator still receives exactly one assessment per analyzed frame.
            val assessment = if (inferenceOutput != null && isClockRealtime) {
                val odOutcome = objectDetectionFusion.fuse(
                    base = baseAssessment,
                    motion = motion,
                    evidence = objectDetectionPipeline.latestEvidence(),
                    frameGeometry = currentGeometry,
                    frameCaptureTimestampNanos = imageTimestampNanos,
                    nowElapsedNanos = SystemClock.elapsedRealtimeNanos(),
                    sessionToken = ticket,
                    isClockRealtime = isClockRealtime,
                )
                val decision = alertCoordinator.processAssessment(odOutcome.assessment, imageTimestampNanos)
                if (decision is AlertDecision.Suppressed && diagnosticFrameSequence != null) {
                    walkingAssistDiagnostics.recordSuppression(
                        walkingAssistTicket,
                        diagnosticFrameSequence,
                        SystemClock.elapsedRealtimeNanos(),
                        diagnosticReason(decision.reason),
                    )
                }
                odOutcome.assessment
            } else {
                baseAssessment
            }

            // Estimate monocular ground-contact distance
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

            // Auxiliary object detection is advisory only: it is never awaited on this thread.
            // Eligibility follows the actual segmentation run (preview-only and model-unready frames
            // stay disabled); accepted frames are copied into the sidecar's bounded pending buffer.
            val isOdEligible = inferenceOutput != null &&
                currentMode != VisionPipelineMode.PREVIEW_ONLY &&
                isSessionLive(ticket)
            objectDetectionPipeline.setEnabled(isOdEligible)
            if (isOdEligible) {
                objectDetectionPipeline.trySubmit(
                    rgb = odRgbView,
                    transform = cropRect,
                    rotationDegrees = rotation,
                    captureTimestampNanos = imageTimestampNanos,
                    arrivalElapsedNanos = analyzerStartNanos,
                    nowElapsedNanos = SystemClock.elapsedRealtimeNanos(),
                )
            }
            // Pressure budget covers everything the helper adds to this analyzer frame (OD copy and
            // the alert/fusion pass), while timingTracker keeps its original analyzer-only metric.
            val helperInclusiveFrameNanos = (SystemClock.elapsedRealtimeNanos() - analyzerStartNanos).coerceAtLeast(0L)
            objectDetectionPipeline.onPrimaryAnalyzerFrame(helperInclusiveFrameNanos)
            val odNowNanos = SystemClock.elapsedRealtimeNanos()
            val odTelemetry = objectDetectionPipeline.telemetry(odNowNanos)
            val currentOdOverlay = if (isOdEligible) {
                objectDetectionPipeline.latestOverlaySnapshot(ticket, currentGeometry, odNowNanos)
            } else {
                null
            }
            logOdTelemetryIfChanged(odTelemetry, odNowNanos)

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
                        odTelemetry = odTelemetry,
                        odOverlay = currentOdOverlay,
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
                val postPublishOverlay = if (isOdEligible) {
                    objectDetectionPipeline.latestOverlaySnapshot(ticket, currentGeometry, publishCompletedNanos)
                } else {
                    null
                }
                mutableUiState.update { state ->
                    if (!isSessionLive(ticket)) return@update state
                    state.copy(
                        timing = updatedSnap,
                        odTelemetry = odTelemetry,
                        odOverlay = postPublishOverlay,
                    )
                }
            }
        } catch (t: Throwable) {
            if (!isSessionLive(ticket)) return
            cancelStartupWatchdog()
            cancelStreamingWatchdog()
            cancelScheduledRetry()
            sessionGeneration.incrementAndGet()
            running.set(false)
            motionTracker.stop()
            alertCoordinator.stop()
            groundContactMotionTracker.stop()
            groundContactDistanceEstimator.reset()
            objectDetectionFusion.reset()
            hazardDetector.reset()
            objectTracker.reset()
            lastTrackedGeometry = null
            objectDetectionPipeline.setEnabled(false)
            objectDetectionPipeline.onSessionStopped("프레임 분석 오류")
            unbindUseCasesOnMain()
            mutableUiState.update { state ->
                state.copy(
                    cameraStatus = CameraSessionStatus.Error,
                    cameraErrorDetail = "프레임 분석 중 오류가 발생하여 카메라 분석을 중지했습니다 (${t.javaClass.simpleName}).",
                    groundContactDistance = GroundContactDistance(),
                    odTelemetry = objectDetectionPipeline.telemetry(),
                    odOverlay = null,
                )
            }
        } finally {
            lastFrameCompletionNanos.set(SystemClock.elapsedRealtimeNanos())
            frameProcessingStartNanos.set(0L)
            isFrameInFlight.set(false)
            imageProxyCloses.incrementAndGet()
            imageProxy.close()
        }
    }

    private fun shouldPublishNow(nowNanos: Long): Boolean {
        val prev = lastPublishedNanos.get()
        return prev == 0L || (nowNanos - prev) >= METRICS_PUBLISH_INTERVAL_NANOS
    }

    private fun logOdTelemetryIfChanged(telemetry: OdTelemetry, nowElapsedNanos: Long) {
        val changed = telemetry.status != lastOdLogStatus ||
            telemetry.rateHz != lastOdLogRateHz ||
            telemetry.followsSegmentationCadence != lastOdLogFollowsCadence
        if (!changed && (nowElapsedNanos - lastOdLogElapsedNanos) < OD_TELEMETRY_LOG_INTERVAL_NANOS) return
        lastOdLogStatus = telemetry.status
        lastOdLogRateHz = telemetry.rateHz
        lastOdLogFollowsCadence = telemetry.followsSegmentationCadence
        lastOdLogElapsedNanos = nowElapsedNanos
        val line = telemetry.structuredLogLine()
        if (telemetry.status == OdSidecarStatus.UNAVAILABLE || telemetry.errors > 0L) {
            Log.w(TAG, line)
        } else {
            Log.d(TAG, line)
        }
    }

    private fun publishPreviewOnlyMetricsThrottled(ticket: Long, nowNanos: Long) {
        if (!isSessionLive(ticket) || !shouldPublishNow(nowNanos)) return
        lastPublishedNanos.set(nowNanos)
        objectDetectionPipeline.setEnabled(false)
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
                odTelemetry = objectDetectionPipeline.telemetry(nowNanos),
                odOverlay = null,
            )
        }
    }

    fun updateSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?, targetRotation: Int = activeTargetRotation) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { updateSurfaceProvider(surfaceProvider, targetRotation) }
            return
        }
        val previousProvider = activeSurfaceProvider
        val lifecycleOwner = activeLifecycleOwner
        activeSurfaceProvider = surfaceProvider
        if (activeTargetRotation != targetRotation) {
            activeTargetRotation = targetRotation
            motionTracker.setDisplayRotation(targetRotation)
            groundContactMotionTracker.setDisplayRotation(targetRotation)
            activeEffectiveFov = null
            activeFullCameraModel = null
            groundContactDistanceEstimator.reset()
            objectDetectionFusion.reset()
            hazardDetector.reset()
            objectTracker.reset()
            lastTrackedGeometry = null
            // Invalidate camera geometry until next frame with new rotation arrives
            lastObservedGeomWidth = -1
            lastObservedGeomHeight = -1
            lastObservedGeomRotation = -1
            lastObservedCropWidth = -1
            lastObservedUprightWidth = -1
            motionTracker.updateCameraGeometry(null)
            boundPreview?.targetRotation = targetRotation
            boundAnalysis?.targetRotation = targetRotation
            mutableUiState.update { it.copy(odOverlay = null) }
        }

        if (closed.get() || !running.get() || lifecycleOwner == null) {
            return
        }

        val ticket = sessionGeneration.get()
        if (!isSessionLive(ticket)) return

        if (isBindingInFlight.get() || isRebindInProgress.get()) {
            Log.i(TAG, "updateSurfaceProvider: binding/rebind is in flight; coalesced target (desiredPreview=${surfaceProvider != null})")
            return
        }

        val isPreviewBound = boundPreview != null
        val shouldPreviewBeBound = surfaceProvider != null

        if (isPreviewBound == shouldPreviewBeBound) {
            if (isPreviewBound && previousProvider !== surfaceProvider) {
                boundPreview?.setSurfaceProvider(surfaceProvider)
            }
            return
        }

        Log.i(TAG, "updateSurfaceProvider: rebinding for preview change (wasBound=$isPreviewBound, nowNeeds=$shouldPreviewBeBound)")
        val provider = cameraProvider
        if (provider != null) {
            isBindingInFlight.set(true)
            unbindAndRebindUseCases(provider, lifecycleOwner, ticket)
        } else {
            isBindingInFlight.set(true)
            scheduleStartupWatchdog(ticket)
            startCameraInternal(lifecycleOwner, ticket)
        }
    }

    fun stopCamera(announceStopped: Boolean = true) {
        isBindingInFlight.set(false)
        isRebindInProgress.set(false)
        cancelStartupWatchdog()
        cancelStreamingWatchdog()
        cancelScheduledRetry()
        cancelPendingRebindWait()
        retryCount = 0
        stallRecoveryAttempts.set(0)
        deviceErrorRecoveryAttempts.set(0)
        continuousHealthyFrames.set(0L)
        healthyStreamingStartNanos.set(0L)
        lastFrameArrivalNanos.set(0L)
        lastFrameCompletionNanos.set(0L)
        frameProcessingStartNanos.set(0L)
        isFrameInFlight.set(false)
        isFrameStalled.set(false)
        pendingFrameSnapshot.getAndSet(null)?.invoke(null)
        val activeWalkingTicket = walkingCameraTicket
        if (activeWalkingTicket >= 0L) stopWalkingAssist(activeWalkingTicket, announceStopped)
        sessionGeneration.incrementAndGet()
        running.set(false)
        activeLifecycleOwner = null
        activeSurfaceProvider = null
        motionTracker.stop()
        alertCoordinator.stop()
        groundContactMotionTracker.stop()
        groundContactDistanceEstimator.reset()
        objectDetectionFusion.reset()
        hazardDetector.reset()
        objectTracker.reset()
        lastTrackedGeometry = null
        objectDetectionPipeline.setEnabled(false)
        objectDetectionPipeline.onSessionStopped("카메라 세션 종료")
        isTargetCameraAvailable = true
        modelStore.cancelCameraStabilityGate()
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
                computeTelemetry = VisionComputeTelemetry.INACTIVE,
                groundContactDistance = GroundContactDistance(),
                odTelemetry = objectDetectionPipeline.telemetry(),
                odOverlay = null,
            )
        }
    }

    private fun unbindUseCasesOnMain() {
        cancelStartupWatchdog()
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
                runCatching { provider.unbindAll() }
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
        runCatching { analysis?.clearAnalyzer() }
        runCatching { preview?.setSurfaceProvider(null) }
        val useCases = listOfNotNull(preview, analysis)
        if (useCases.isNotEmpty()) {
            runCatching { provider.unbindAll() }
        }
    }

    private fun cancelPendingRebindWait(retainRebindFlag: Boolean = false) {
        rebindCloseDeadlineRunnable?.let { mainHandler.removeCallbacks(it) }
        rebindCloseDeadlineRunnable = null
        rebindAvailabilityDeadlineRunnable?.let { mainHandler.removeCallbacks(it) }
        rebindAvailabilityDeadlineRunnable = null
        val observer = rebindTempObserver
        val state = rebindPriorState
        if (state != null && observer != null) {
            state.removeObserver(observer)
        }
        rebindTempObserver = null
        rebindPriorState = null
        activeRebindCoordinator = null
        if (!retainRebindFlag) {
            isRebindInProgress.set(false)
        }
    }

    private fun unbindAndRebindUseCases(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        ticket: Long,
    ) {
        if (!isSessionLive(ticket)) {
            isBindingInFlight.set(false)
            isRebindInProgress.set(false)
            return
        }
        if (!isRebindInProgress.compareAndSet(false, true)) {
            Log.i(TAG, "unbindAndRebindUseCases: rebind already in progress for ticket $ticket; skipping duplicate")
            return
        }

        cancelPendingRebindWait()
        isRebindInProgress.set(true)
        objectDetectionFusion.reset()
        hazardDetector.reset()
        objectTracker.reset()
        lastTrackedGeometry = null
        objectDetectionPipeline.setEnabled(false)
        objectDetectionPipeline.onSessionStopped("카메라 리바인드")
        mutableUiState.update {
            it.copy(
                odTelemetry = objectDetectionPipeline.telemetry(),
                odOverlay = null,
            )
        }
        ensureAvailabilityCallbackRegistered()

        val priorState = observedCameraState
        val priorObserver = cameraStateObserver
        if (priorState != null && priorObserver != null) {
            priorState.removeObserver(priorObserver)
        }
        observedCameraState = null
        cameraStateObserver = null

        val preview = boundPreview
        val analysis = boundAnalysis
        boundPreview = null
        boundAnalysis = null
        runCatching { analysis?.clearAnalyzer() }
        runCatching { preview?.setSurfaceProvider(null) }

        try {
            provider.unbindAll()
        } catch (t: Throwable) {
            Log.w(TAG, "provider.unbindAll failed during rebind teardown", t)
        }

        val priorStateType = priorState?.value?.type
        val isPriorAlreadyClosed = priorState == null || priorStateType == CameraState.Type.CLOSED

        val coordinator = RecoverySequencingCoordinator(
            onRebind = {
                mainHandler.post {
                    if (!isSessionLive(ticket)) {
                        isBindingInFlight.set(false)
                        cancelPendingRebindWait()
                        return@post
                    }
                    Log.i(TAG, "Recovery gate satisfied (prior closed & camera available); rebinding for ticket $ticket")
                    cancelPendingRebindWait(retainRebindFlag = true)
                    try {
                        bindUseCases(provider, lifecycleOwner, ticket)
                    } finally {
                        isRebindInProgress.set(false)
                    }
                }
            },
            onPriorCloseTimeout = { failedState ->
                mainHandler.post {
                    if (!isSessionLive(ticket)) {
                        isBindingInFlight.set(false)
                        cancelPendingRebindWait()
                        return@post
                    }
                    Log.e(
                        TAG,
                        "Prior camera graph failed to close within bounded deadline (state=$failedState) for ticket $ticket; aborting rebind",
                    )
                    isBindingInFlight.set(false)
                    cancelPendingRebindWait()
                    failCameraSession(
                        ticket,
                        "이전 카메라 리소스 해제 확인 대기 시간이 초과되었습니다 (상태: $failedState). 앱 화면에서 다시 시작해 주세요.",
                    )
                }
            },
            onAvailabilityTimeout = {
                mainHandler.post {
                    if (!isSessionLive(ticket)) {
                        isBindingInFlight.set(false)
                        cancelPendingRebindWait()
                        return@post
                    }
                    Log.e(
                        TAG,
                        "Camera resource availability did not recover within bounded deadline after graph close for ticket $ticket; aborting rebind into dead HAL",
                    )
                    isBindingInFlight.set(false)
                    cancelPendingRebindWait()
                    failCameraSession(
                        ticket,
                        "카메라 리소스 사용 가능 상태 복구 대기 시간이 초과되었습니다. 앱 화면에서 다시 시작해 주세요.",
                    )
                }
            },
        )
        activeRebindCoordinator = coordinator

        fun armAvailabilityDeadlineIfNeeded() {
            if (!coordinator.isCameraAvailable && rebindAvailabilityDeadlineRunnable == null) {
                val availDeadline = Runnable {
                    coordinator.handleAvailabilityTimeout()
                }
                rebindAvailabilityDeadlineRunnable = availDeadline
                mainHandler.postDelayed(availDeadline, REBIND_AVAILABILITY_DEADLINE_MS)
            }
        }

        if (isTargetCameraAvailable) {
            coordinator.markCameraAvailable()
        }

        if (isPriorAlreadyClosed) {
            Log.i(TAG, "Prior camera graph already closed for ticket $ticket")
            val rebindFired = coordinator.markPriorClosed()
            if (!rebindFired) {
                armAvailabilityDeadlineIfNeeded()
            }
        } else {
            Log.i(TAG, "Waiting for prior camera graph ($priorStateType) to close before rebinding for ticket $ticket")
            val tempObserver = Observer<CameraState> { state ->
                if (state?.type == CameraState.Type.CLOSED) {
                    rebindCloseDeadlineRunnable?.let { mainHandler.removeCallbacks(it) }
                    rebindCloseDeadlineRunnable = null
                    Log.i(TAG, "Prior camera graph CLOSED observed for ticket $ticket")
                    val rebindFired = coordinator.markPriorClosed()
                    if (!rebindFired) {
                        armAvailabilityDeadlineIfNeeded()
                    }
                }
            }
            rebindTempObserver = tempObserver
            rebindPriorState = priorState
            priorState.observeForever(tempObserver)

            val closeDeadline = Runnable {
                val currentState = priorState.value?.type?.name
                coordinator.handlePriorCloseTimeout(currentState)
            }
            rebindCloseDeadlineRunnable = closeDeadline
            mainHandler.postDelayed(closeDeadline, REBIND_CLOSE_DEADLINE_MS)
        }
    }

    @VisibleForTesting
    internal fun triggerStartupWatchdogForTesting(ticket: Long = sessionGeneration.get()) {
        if (ticket != sessionGeneration.get() || !isSessionLive(ticket)) return
        cancelStartupWatchdog()
        cancelStreamingWatchdog()
        boundAnalysis?.clearAnalyzer()
        stallRecoveryAttempts.set(MAX_STALL_RECOVERY_ATTEMPTS - 1)
        mutableUiState.update {
            it.copy(
                cameraStatus = CameraSessionStatus.Starting,
                cameraErrorDetail = null,
            )
        }
        executeStartupWatchdogTimeout(ticket)
    }

    @VisibleForTesting
    internal fun triggerStallCheckForTesting(ticket: Long = sessionGeneration.get()) {
        if (ticket != sessionGeneration.get() || !isSessionLive(ticket)) return
        checkStreamingLiveness(ticket)
    }

    @VisibleForTesting
    internal fun handleCameraFailureForTesting(ticket: Long = sessionGeneration.get(), failure: CameraFailureKind) {
        if (ticket != sessionGeneration.get() || !isSessionLive(ticket)) return
        handleCameraFailure(ticket, failure)
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
        cancelStartupWatchdog()
        cancelStreamingWatchdog()
        cancelScheduledRetry()
        cancelPendingRebindWait()
        stopCamera(announceStopped = false)
        if (calibrationReceiverRegistered) {
            runCatching { context.applicationContext.unregisterReceiver(calibrationReceiver) }
            calibrationReceiverRegistered = false
        }
        hazardSpeechBridge.close()
        motionTracker.close()
        alertCoordinator.close()
        groundContactMotionTracker.close()
        bodyMountProfileStore.close()
        groundContactDistanceEstimator.reset()
        modelStore.close()
        objectDetectionFusion.reset()
        hazardDetector.reset()
        objectTracker.reset()
        lastTrackedGeometry = null
        objectDetectionPipeline.close()
        if (terminalStatus == CameraSessionStatus.Error || terminalStatus == CameraSessionStatus.PermissionDenied) {
            mutableUiState.update {
                it.copy(cameraStatus = terminalStatus, cameraErrorDetail = terminalDetail)
            }
        }
        if (!analyzerExecutor.isShutdown) {
            analyzerExecutor.shutdown()
        }
    }

    companion object {
        private const val WALKING_ASSIST_POLL_MS = 100L
        private const val METRICS_PUBLISH_INTERVAL_NANOS = 250_000_000L
        private const val OD_TELEMETRY_LOG_INTERVAL_NANOS = 5_000_000_000L
        internal const val STARTUP_TIMEOUT_MS = 10_000L
        internal const val STREAMING_STALL_TIMEOUT_MS = 5_000L
        internal const val IN_FLIGHT_PROCESSING_TIMEOUT_MS = 15_000L
        internal const val STREAMING_WATCHDOG_INTERVAL_MS = 1_000L
        internal const val MAX_STALL_RECOVERY_ATTEMPTS = 3
        internal const val MAX_DEVICE_ERROR_RECOVERY_ATTEMPTS = 1
        internal const val DEVICE_ERROR_RETRY_DELAY_MS = 1_000L
        internal const val SUSTAINED_HEALTHY_WINDOW_MS = 10_000L
        internal const val SUSTAINED_HEALTHY_FRAMES = 60L
        internal const val SUSTAINED_WARMUP_HEALTHY_FRAMES = 150L
        internal const val SUSTAINED_WARMUP_HEALTHY_WINDOW_MS = 5_000L
        internal const val REBIND_CLOSE_DEADLINE_MS = 1_000L
        internal const val REBIND_AVAILABILITY_DEADLINE_MS = 10_000L
        internal const val MAX_RETRY_COUNT = 5
        internal val BACKOFF_DELAYS_MS = longArrayOf(500L, 1000L, 2000L, 4000L, 8000L)
        private const val TAG = "AriaVisionPipeline"

        internal fun canAttemptFatalDeviceRecovery(attempts: Int): Boolean =
            attempts <= MAX_DEVICE_ERROR_RECOVERY_ATTEMPTS

        internal fun canAttemptStallRecovery(
            currentAttempts: Int,
        ): Boolean = CameraRecoverySequencingPolicy.canAttemptStallRecovery(currentAttempts)

        internal fun isHealthyStreamRecoverySustained(
            healthyFrames: Long,
            healthyDurationMs: Long,
        ): Boolean = CameraRecoverySequencingPolicy.isHealthyStreamRecoverySustained(healthyFrames, healthyDurationMs)

        internal fun isWarmupStabilityPermitted(
            healthyFrames: Long,
            healthyDurationMs: Long,
        ): Boolean = CameraRecoverySequencingPolicy.isWarmupStabilityPermitted(healthyFrames, healthyDurationMs)

        internal fun resolveEffectiveModelStateName(state: VisionModelState, isInvocationReady: Boolean): String {
            return if (state is VisionModelState.Ready && !isInvocationReady) {
                "Loading"
            } else {
                state.javaClass.simpleName
            }
        }
    }
}

internal class RecoverySequencingCoordinator(
    private val onRebind: () -> Unit,
    private val onPriorCloseTimeout: (state: String?) -> Unit,
    private val onAvailabilityTimeout: () -> Unit,
) {
    @Volatile
    var isPriorClosed: Boolean = false
        private set

    @Volatile
    var isCameraAvailable: Boolean = false
        private set

    private val rebindFired = AtomicBoolean(false)

    fun markPriorClosed(): Boolean {
        isPriorClosed = true
        return checkAndTrigger()
    }

    fun markCameraAvailable(): Boolean {
        isCameraAvailable = true
        return checkAndTrigger()
    }

    fun markCameraUnavailable() {
        isCameraAvailable = false
    }

    fun checkAndTrigger(): Boolean {
        if (isPriorClosed && isCameraAvailable) {
            if (rebindFired.compareAndSet(false, true)) {
                onRebind()
                return true
            }
        }
        return false
    }

    fun handlePriorCloseTimeout(currentState: String?) {
        if (!isPriorClosed && !rebindFired.get()) {
            onPriorCloseTimeout(currentState)
        }
    }

    fun handleAvailabilityTimeout() {
        if (!isCameraAvailable && !rebindFired.get()) {
            onAvailabilityTimeout()
        }
    }
}

internal object CameraRecoverySequencingPolicy {
    internal const val OBSERVED_POST_CLOSE_GPU_COMPLETION_MS = 3_400L
    internal const val OBSERVED_POST_CLOSE_HAL_DRAIN_MS = 6_400L

    fun canRebind(isPriorClosed: Boolean, isCameraAvailable: Boolean): Boolean {
        return isPriorClosed && isCameraAvailable
    }

    fun isWarmupStabilityPermitted(
        healthyFrames: Long,
        healthyDurationMs: Long,
        requiredFrames: Long = VisionPipelineController.SUSTAINED_WARMUP_HEALTHY_FRAMES,
        requiredDurationMs: Long = VisionPipelineController.SUSTAINED_WARMUP_HEALTHY_WINDOW_MS,
    ): Boolean {
        return healthyFrames >= requiredFrames && healthyDurationMs >= requiredDurationMs
    }

    fun isHealthyStreamRecoverySustained(
        healthyFrames: Long,
        healthyDurationMs: Long,
        requiredFrames: Long = VisionPipelineController.SUSTAINED_HEALTHY_FRAMES,
        requiredDurationMs: Long = VisionPipelineController.SUSTAINED_HEALTHY_WINDOW_MS,
    ): Boolean {
        return healthyFrames >= requiredFrames && healthyDurationMs >= requiredDurationMs
    }

    fun canAttemptStallRecovery(
        currentAttempts: Int,
        maxAttempts: Int = VisionPipelineController.MAX_STALL_RECOVERY_ATTEMPTS,
    ): Boolean {
        return currentAttempts < maxAttempts
    }

    fun isAvailabilityDeadlineSufficient(
        deadlineMs: Long = VisionPipelineController.REBIND_AVAILABILITY_DEADLINE_MS,
        minRequiredMs: Long = OBSERVED_POST_CLOSE_GPU_COMPLETION_MS,
    ): Boolean {
        return deadlineMs > minRequiredMs
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
