package com.ariateam.aria.distance

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.camera.core.Preview
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.ariateam.aria.vision.CameraSessionStatus
import com.ariateam.aria.vision.VisionModelState
import com.ariateam.aria.vision.VisionPipelineController
import com.ariateam.aria.vision.VisionPipelineMode
import com.ariateam.aria.vision.VisionUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

data class GroundContactEvaluationInput(
    val groundContactDistance: GroundContactDistance,
    val isCameraRunning: Boolean,
    val isProfileSet: Boolean,
    val isModelReady: Boolean,
    val isSensorTracking: Boolean,
    val currentNanos: Long,
    val minValidTimestampNanos: Long = 0L,
    val cameraErrorDetail: String? = null,
    val modelErrorMessage: String? = null,
)

data class GroundContactEvaluationResult(
    val status: DistanceStatus,
    val risk: DistanceRisk,
    val reportedDistanceMeters: Float?,
    val message: String,
    val isNumericValid: Boolean,
)

object GroundContactDistanceEvaluator {
    const val FRESHNESS_TTL_NANOS = 1_500_000_000L // 1.5s

    fun evaluate(input: GroundContactEvaluationInput): GroundContactEvaluationResult {
        val gcd = input.groundContactDistance
        val frameNanos = gcd.timestampNanos
        val ageNanos = if (frameNanos > 0L) input.currentNanos - frameNanos else -1L
        val isPostFence = frameNanos >= input.minValidTimestampNanos
        val isFresh = frameNanos > 0L && isPostFence && ageNanos in 0L..FRESHNESS_TTL_NANOS

        val rawDistance = gcd.distanceMeters
        val isNumericValid = input.isCameraRunning && input.isProfileSet && input.isModelReady &&
            input.isSensorTracking && isFresh &&
            rawDistance != null && rawDistance.isFinite() && rawDistance > 0f

        val reportedDistance = if (isNumericValid) rawDistance else null

        val (risk, candidateDistance) = when {
            reportedDistance == null -> {
                if (input.isCameraRunning && input.isModelReady && input.isProfileSet && gcd.message.contains("경고 조건 미감지")) {
                    DistanceRisk.CLEAR to null
                } else {
                    DistanceRisk.UNKNOWN to null
                }
            }
            reportedDistance <= DepthDistanceEstimator.URGENT_THRESHOLD_METERS -> { // 1.50m
                DistanceRisk.URGENT to reportedDistance
            }
            reportedDistance <= DepthDistanceEstimator.CAUTION_THRESHOLD_METERS -> { // 3.00m
                DistanceRisk.CAUTION to reportedDistance
            }
            else -> {
                DistanceRisk.CLEAR to reportedDistance
            }
        }

        val status = when {
            input.cameraErrorDetail != null || input.modelErrorMessage != null -> DistanceStatus.UNAVAILABLE
            !input.isCameraRunning -> DistanceStatus.PREPARING
            candidateDistance != null -> DistanceStatus.TRACKING
            gcd.message.contains("경고 조건 미감지") -> DistanceStatus.TRACKING
            !input.isProfileSet -> DistanceStatus.PREPARING
            else -> DistanceStatus.PREPARING
        }

        val message = when {
            input.cameraErrorDetail != null -> input.cameraErrorDetail
            input.modelErrorMessage != null -> input.modelErrorMessage
            !input.isCameraRunning -> "비전 중지됨"
            !input.isProfileSet -> "키와 착용 위치를 설정하세요"
            !input.isSensorTracking -> "기기 센서 안정화 대기 중"
            !input.isModelReady -> "비전 모델 준비 중"
            gcd.message.isNotBlank() -> gcd.message
            candidateDistance != null -> "보행 장애물 감지 중"
            else -> "비전 준비 중"
        }

        return GroundContactEvaluationResult(
            status = status,
            risk = risk,
            reportedDistanceMeters = candidateDistance,
            message = message,
            isNumericValid = isNumericValid,
        )
    }
}

/**
 * Dedicated monocular state adapter for Aria's obstacle distance mode.
 *
 * Bridges the CameraX [VisionPipelineController] and [GroundContactDistanceEstimator] output
 * directly to [DistanceUiState] without requiring ARCore or Raw Depth hardware support.
 */
class GroundContactDistanceViewModel(
    application: Application,
    val pipelineController: VisionPipelineController,
    val vibrator: DistanceAlertVibrator,
    private val timeProvider: () -> Long = { SystemClock.elapsedRealtime() },
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        pipelineController = VisionPipelineController(context = application.applicationContext),
        vibrator = DistanceAlertVibrator(application),
        timeProvider = { SystemClock.elapsedRealtime() },
    )

    companion object {
        private const val PREFS_NAME = "aria_distance_prefs"
        private const val KEY_DISMISSED_ONBOARDING = "dismissed_onboarding"
        private const val WATCHDOG_INTERVAL_MS = 300L
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lastFreshTimestampNanos = AtomicLong(0L)
    private val minValidTimestampNanos = AtomicLong(0L)
    private var watchdogJob: Job? = null

    private val mutableUiState = MutableStateFlow(
        DistanceUiState(
            status = DistanceStatus.PREPARING,
            risk = DistanceRisk.UNKNOWN,
            distanceMeters = null,
            message = "비전 준비 중",
            setupStage = DistanceSetupStage.TRACKING,
            floorDetected = false,
            rawDepthActive = false,
            isMonocularMode = true,
            showOnboarding = !prefs.getBoolean(KEY_DISMISSED_ONBOARDING, false),
        )
    )
    val uiState: StateFlow<DistanceUiState> = mutableUiState.asStateFlow()

    init {
        startWatchdog()
        viewModelScope.launch {
            pipelineController.uiState.collect { visionState ->
                processVisionUpdate(visionState)
            }
        }
        viewModelScope.launch {
            pipelineController.bodyMountProfileStore.profile.collect { profile ->
                minValidTimestampNanos.set(SystemClock.elapsedRealtimeNanos())
                lastFreshTimestampNanos.set(0L)
                vibrator.cancel()
                fenceStaleState(if (profile == null) "키와 착용 위치를 설정하세요" else "프로필 변경됨 (재계산 대기)")
            }
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = viewModelScope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                val currentDist = mutableUiState.value.distanceMeters
                if (currentDist != null) {
                    val lastNanos = lastFreshTimestampNanos.get()
                    val nowNanos = SystemClock.elapsedRealtimeNanos()
                    val age = nowNanos - lastNanos
                    if (lastNanos <= 0L || age < 0L || age > GroundContactDistanceEvaluator.FRESHNESS_TTL_NANOS) {
                        vibrator.cancel()
                        mutableUiState.update { current ->
                            current.copy(
                                distanceMeters = null,
                                risk = DistanceRisk.UNKNOWN,
                                status = if (current.status == DistanceStatus.TRACKING) DistanceStatus.PREPARING else current.status,
                                message = "프레임 수신 지연",
                            )
                        }
                    }
                }
            }
        }
    }

    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider?,
        targetRotation: Int,
    ) {
        pipelineController.startCamera(
            lifecycleOwner = lifecycleOwner,
            surfaceProvider = surfaceProvider,
            mode = VisionPipelineMode.PREPROCESS_OR_INFERENCE,
            targetRotation = targetRotation,
        )
    }

    fun updateSurfaceProvider(
        surfaceProvider: Preview.SurfaceProvider?,
        targetRotation: Int,
    ) {
        pipelineController.updateSurfaceProvider(surfaceProvider, targetRotation)
    }

    fun stopCamera() {
        minValidTimestampNanos.set(SystemClock.elapsedRealtimeNanos())
        pipelineController.stopCamera()
        vibrator.cancel()
        lastFreshTimestampNanos.set(0L)
        fenceStaleState("비전 중지됨")
    }

    fun recalibrate() {
        minValidTimestampNanos.set(SystemClock.elapsedRealtimeNanos())
        pipelineController.calibrateForward()
        pipelineController.groundContactDistanceEstimator.reset()
        vibrator.cancel()
        lastFreshTimestampNanos.set(0L)
        fenceStaleState("재보정 중")
    }

    fun onPauseSession() {
        stopCamera()
    }

    fun dismissOnboarding(dontShowAgain: Boolean) {
        if (dontShowAgain) {
            prefs.edit().putBoolean(KEY_DISMISSED_ONBOARDING, true).apply()
        }
        mutableUiState.update { it.copy(showOnboarding = false) }
    }

    fun setSetupStage(stage: DistanceSetupStage, error: String? = null) {
        mutableUiState.update { current ->
            val isError = stage == DistanceSetupStage.ERROR_PERMISSION ||
                stage == DistanceSetupStage.ERROR_AR_UNAVAILABLE ||
                stage == DistanceSetupStage.ERROR_DEPTH_UNSUPPORTED ||
                stage == DistanceSetupStage.ERROR_INSTALL_REQUIRED

            current.copy(
                setupStage = stage,
                errorMessage = error,
                status = if (isError) DistanceStatus.UNAVAILABLE else current.status,
                risk = if (isError) DistanceRisk.UNKNOWN else current.risk,
                distanceMeters = if (isError) null else current.distanceMeters,
                message = error ?: current.message,
            )
        }
    }

    private fun fenceStaleState(message: String) {
        mutableUiState.update { current ->
            current.copy(
                status = DistanceStatus.PREPARING,
                risk = DistanceRisk.UNKNOWN,
                distanceMeters = null,
                validSampleCount = 0,
                message = message,
            )
        }
    }

    fun processVisionUpdate(visionState: VisionUiState) {
        val gcd = visionState.groundContactDistance
        val isCameraRunning = visionState.cameraStatus == CameraSessionStatus.Running
        val profile = pipelineController.bodyMountProfileStore.profile.value
        val isProfileSet = profile != null && profile.isValid
        val isModelReady = pipelineController.modelStore.segmenter.isReady

        val frameNanos = gcd.timestampNanos
        val orientation = pipelineController.groundContactMotionTracker.orientationAt(frameNanos)
        val isSensorTracking = orientation.isReliable

        val cameraError = if (visionState.cameraStatus == CameraSessionStatus.Error) {
            visionState.cameraErrorDetail ?: "카메라 오류"
        } else null

        val modelError = when (val ms = pipelineController.modelState.value) {
            is VisionModelState.Error -> ms.detail.ifBlank { ms.reason }
            else -> null
        }

        val evalInput = GroundContactEvaluationInput(
            groundContactDistance = gcd,
            isCameraRunning = isCameraRunning,
            isProfileSet = isProfileSet,
            isModelReady = isModelReady,
            isSensorTracking = isSensorTracking,
            currentNanos = SystemClock.elapsedRealtimeNanos(),
            minValidTimestampNanos = minValidTimestampNanos.get(),
            cameraErrorDetail = cameraError,
            modelErrorMessage = modelError,
        )

        val evalResult = GroundContactDistanceEvaluator.evaluate(evalInput)

        if (evalResult.isNumericValid && evalResult.reportedDistanceMeters != null) {
            lastFreshTimestampNanos.set(frameNanos)
            vibrator.onRiskEvaluated(evalResult.risk)
        } else {
            vibrator.cancel()
        }

        mutableUiState.update { current ->
            if (current.setupStage == DistanceSetupStage.ERROR_PERMISSION) {
                current
            } else {
                current.copy(
                    status = evalResult.status,
                    risk = evalResult.risk,
                    distanceMeters = evalResult.reportedDistanceMeters,
                    validSampleCount = if (evalResult.reportedDistanceMeters != null) 1 else 0,
                    message = evalResult.message,
                    setupStage = DistanceSetupStage.TRACKING,
                    floorDetected = false,
                    rawDepthActive = false,
                    isMonocularMode = true,
                    profileConfigured = isProfileSet,
                    sensorTrackingActive = isSensorTracking,
                    modelReady = isModelReady,
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        watchdogJob?.cancel()
        pipelineController.close()
        vibrator.release()
    }
}
