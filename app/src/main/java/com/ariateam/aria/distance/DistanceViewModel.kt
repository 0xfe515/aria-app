package com.ariateam.aria.distance

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class DistanceSetupStage {
    CHECKING,
    INITIALIZING,
    FLOOR_SEARCH,
    TRACKING,
    ERROR_AR_UNAVAILABLE,
    ERROR_INSTALL_REQUIRED,
    ERROR_DEPTH_UNSUPPORTED,
    ERROR_PERMISSION,
}

data class DistanceUiState(
    val status: DistanceStatus = DistanceStatus.PREPARING,
    val risk: DistanceRisk = DistanceRisk.UNKNOWN,
    val distanceMeters: Float? = null,
    val validSampleCount: Int = 0,
    val message: String = "",
    val groundOffsetMeters: Float? = null,
    val floorDetected: Boolean = false,
    val setupStage: DistanceSetupStage = DistanceSetupStage.CHECKING,
    val errorMessage: String? = null,
    val rawDepthActive: Boolean = false,
    val showOnboarding: Boolean = false,
)

class DistanceViewModel(
    application: Application,
    val floorTracker: FloorPlaneTracker = FloorPlaneTracker(),
    val rawDepthProcessor: RawDepthProcessor = RawDepthProcessor(),
    val estimator: DepthDistanceEstimator = DepthDistanceEstimator(),
    val vibrator: DistanceAlertVibrator = DistanceAlertVibrator(application),
    private val timeProvider: () -> Long = { SystemClock.elapsedRealtime() },
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        floorTracker = FloorPlaneTracker(),
        rawDepthProcessor = RawDepthProcessor(),
        estimator = DepthDistanceEstimator(),
        vibrator = DistanceAlertVibrator(application),
        timeProvider = { SystemClock.elapsedRealtime() },
    )

    companion object {
        private const val PREFS_NAME = "aria_distance_prefs"
        private const val KEY_DISMISSED_ONBOARDING = "dismissed_onboarding"
        private const val DEPTH_WATCHDOG_TIMEOUT_MS = 1_500L
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val sessionGeneration = AtomicLong(0L)
    private val isSessionActive = AtomicBoolean(false)

    private var lastFreshDepthTimestampMs: Long = 0L
    private var lastCameraCallbackTimestampMs: Long = 0L
    private var watchdogJob: Job? = null

    private val mutableUiState = MutableStateFlow(
        DistanceUiState(
            showOnboarding = !prefs.getBoolean(KEY_DISMISSED_ONBOARDING, false),
        )
    )
    val uiState: StateFlow<DistanceUiState> = mutableUiState.asStateFlow()

    init {
        startWatchdog()
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = viewModelScope.launch {
            while (isActive) {
                delay(300)
                if (!isSessionActive.get()) continue

                val now = timeProvider()
                val depthElapsed = now - lastFreshDepthTimestampMs
                val cameraElapsed = now - lastCameraCallbackTimestampMs

                // Age out stale depth measurements if fresh depth frames or camera feed stops
                if ((lastFreshDepthTimestampMs > 0 && depthElapsed > DEPTH_WATCHDOG_TIMEOUT_MS) ||
                    (lastCameraCallbackTimestampMs > 0 && cameraElapsed > DEPTH_WATCHDOG_TIMEOUT_MS)
                ) {
                    vibrator.cancel()
                    mutableUiState.update { current ->
                        if (current.status == DistanceStatus.TRACKING) {
                            current.copy(
                                status = DistanceStatus.UNAVAILABLE,
                                risk = DistanceRisk.UNKNOWN,
                                distanceMeters = null,
                                message = if (cameraElapsed > DEPTH_WATCHDOG_TIMEOUT_MS) "카메라 프레임 지연" else "깊이 센서 대기 중",
                            )
                        } else {
                            current
                        }
                    }
                }
            }
        }
    }

    fun setSetupStage(stage: DistanceSetupStage, error: String? = null) {
        mutableUiState.update { current ->
            val isError = stage == DistanceSetupStage.ERROR_AR_UNAVAILABLE ||
                stage == DistanceSetupStage.ERROR_INSTALL_REQUIRED ||
                stage == DistanceSetupStage.ERROR_DEPTH_UNSUPPORTED ||
                stage == DistanceSetupStage.ERROR_PERMISSION

            current.copy(
                setupStage = stage,
                errorMessage = error,
                status = when {
                    stage == DistanceSetupStage.TRACKING -> DistanceStatus.TRACKING
                    isError -> DistanceStatus.UNAVAILABLE
                    else -> DistanceStatus.PREPARING
                },
                risk = if (isError) DistanceRisk.UNKNOWN else current.risk,
                distanceMeters = if (isError) null else current.distanceMeters,
                message = error ?: current.message,
            )
        }
    }

    fun setRawDepthActive(active: Boolean) {
        mutableUiState.update { it.copy(rawDepthActive = active) }
    }

    fun dismissOnboarding(dontShowAgain: Boolean) {
        if (dontShowAgain) {
            prefs.edit().putBoolean(KEY_DISMISSED_ONBOARDING, true).apply()
        }
        mutableUiState.update { it.copy(showOnboarding = false) }
    }

    fun onResumeSession(hasActiveSession: Boolean = false) {
        sessionGeneration.incrementAndGet()
        isSessionActive.set(true)
        lastFreshDepthTimestampMs = timeProvider()
        lastCameraCallbackTimestampMs = timeProvider()

        if (hasActiveSession) {
            mutableUiState.update { current ->
                current.copy(
                    rawDepthActive = true,
                    setupStage = DistanceSetupStage.FLOOR_SEARCH,
                )
            }
        }
    }

    fun onPauseSession() {
        isSessionActive.set(false)
        sessionGeneration.incrementAndGet()
        synchronized(this) {
            vibrator.cancel()
            floorTracker.reset()
            rawDepthProcessor.reset()
            estimator.reset()
        }
        lastFreshDepthTimestampMs = 0L
        lastCameraCallbackTimestampMs = 0L
        mutableUiState.update { current ->
            current.copy(
                status = DistanceStatus.PREPARING,
                risk = DistanceRisk.UNKNOWN,
                distanceMeters = null,
                validSampleCount = 0,
                floorDetected = false,
                rawDepthActive = false,
                groundOffsetMeters = null,
            )
        }
    }

    fun recalibrate() {
        sessionGeneration.incrementAndGet()
        synchronized(this) {
            floorTracker.reset()
            rawDepthProcessor.reset()
            estimator.reset()
            vibrator.cancel()
        }
        lastFreshDepthTimestampMs = 0L
        mutableUiState.update {
            it.copy(
                status = DistanceStatus.PREPARING,
                risk = DistanceRisk.UNKNOWN,
                distanceMeters = null,
                validSampleCount = 0,
                groundOffsetMeters = null,
                floorDetected = false,
                message = "재보정 중",
                setupStage = if (it.setupStage == DistanceSetupStage.TRACKING) {
                    DistanceSetupStage.FLOOR_SEARCH
                } else {
                    it.setupStage
                },
            )
        }
    }

    fun onRendererError(throwable: Throwable) {
        vibrator.cancel()
        mutableUiState.update { current ->
            current.copy(
                status = DistanceStatus.UNAVAILABLE,
                risk = DistanceRisk.UNKNOWN,
                distanceMeters = null,
                message = throwable.localizedMessage ?: "프레임 렌더 오류",
            )
        }
    }

    /**
     * Processes an incoming AR frame on the GL thread with live [Session].
     */
    fun onFrameAvailable(frame: Frame, session: Session) {
        if (!isSessionActive.get()) return
        val currentGen = sessionGeneration.get()

        val nowMs = timeProvider()
        lastCameraCallbackTimestampMs = nowMs

        val trackingState = frame.camera.trackingState
        if (trackingState != TrackingState.TRACKING) {
            synchronized(this) {
                vibrator.cancel()
                estimator.reset()
                floorTracker.reset()
                rawDepthProcessor.reset()
            }
            mutableUiState.update { current ->
                current.copy(
                    status = DistanceStatus.PREPARING,
                    risk = DistanceRisk.UNKNOWN,
                    distanceMeters = null,
                    validSampleCount = 0,
                    message = "공간 추적 준비 중",
                    setupStage = if (current.setupStage != DistanceSetupStage.CHECKING &&
                        !current.setupStage.name.startsWith("ERROR")
                    ) {
                        DistanceSetupStage.FLOOR_SEARCH
                    } else {
                        current.setupStage
                    },
                )
            }
            return
        }

        synchronized(this) {
            // Guard against pause/recalibrate concurrent mutations
            if (currentGen != sessionGeneration.get() || !isSessionActive.get()) return

            // 1. Evaluate Floor Plane from live session
            val planes = session.getAllTrackables(Plane::class.java)
            val floorEstimate = floorTracker.evaluate(planes, frame.camera.pose)
            val groundY = floorEstimate.groundY

            // 2. Process Raw Depth Samples
            val processResult = rawDepthProcessor.processFrame(frame)

            // Handle lower cadence duplicate raw depth frames:
            // Do NOT re-evaluate estimator or publish UNKNOWN on duplicates; preserve existing estimate
            if (processResult.isDuplicate) {
                return
            }

            if (processResult.isFresh && processResult.samples.isNotEmpty()) {
                lastFreshDepthTimestampMs = nowMs
            }

            // Quality gate: require tracked ground plane and fresh samples before estimating
            val estimate = if (!processResult.isFresh || processResult.samples.isEmpty()) {
                DistanceEstimate(
                    status = DistanceStatus.PREPARING,
                    risk = DistanceRisk.UNKNOWN,
                    distanceMeters = null,
                    validSampleCount = 0,
                    message = if (groundY != null) "깊이 센서 동기화 중" else "바닥 인식 대기 중",
                )
            } else if (groundY != null) {
                // Pass the distinct raw depth timestamp to avoid duplicate confirmation
                estimator.evaluate(processResult.samples, processResult.timestampNanos, groundY)
            } else {
                DistanceEstimate(
                    status = DistanceStatus.PREPARING,
                    risk = DistanceRisk.UNKNOWN,
                    distanceMeters = null,
                    validSampleCount = processResult.samples.size,
                    message = "바닥 인식 대기 중",
                )
            }

            // Fencing check before triggering side-effects (haptics and UI updates)
            if (currentGen != sessionGeneration.get() || !isSessionActive.get()) return

            // 3. Trigger vibration alerts on confirmed risk
            vibrator.onRiskEvaluated(estimate.risk)

            // 4. Update UI State
            mutableUiState.update { current ->
                current.copy(
                    status = estimate.status,
                    risk = estimate.risk,
                    distanceMeters = estimate.distanceMeters,
                    validSampleCount = estimate.validSampleCount,
                    message = estimate.message,
                    groundOffsetMeters = groundY,
                    floorDetected = groundY != null,
                    setupStage = if (groundY != null && estimate.status == DistanceStatus.TRACKING) {
                        DistanceSetupStage.TRACKING
                    } else if (current.setupStage.name.startsWith("ERROR")) {
                        current.setupStage
                    } else {
                        DistanceSetupStage.FLOOR_SEARCH
                    },
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        watchdogJob?.cancel()
        vibrator.release()
    }
}
