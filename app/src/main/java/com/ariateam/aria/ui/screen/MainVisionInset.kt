package com.ariateam.aria.ui.screen

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.Surface
import androidx.camera.core.Preview
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ariateam.aria.R
import com.ariateam.aria.distance.BodyMountProfile
import com.ariateam.aria.distance.BodyMountProfileStore
import com.ariateam.aria.distance.GroundContactDistance
import com.ariateam.aria.ui.theme.AriaGreen
import com.ariateam.aria.ui.theme.AriaPanel
import com.ariateam.aria.ui.theme.AriaRed
import com.ariateam.aria.ui.theme.AriaSky
import com.ariateam.aria.ui.theme.AriaSlate
import com.ariateam.aria.vision.CameraSessionStatus
import com.ariateam.aria.vision.VisionModelState
import com.ariateam.aria.vision.VisionTimingSnapshot
import com.ariateam.aria.vision.VisionUiState
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

enum class MainVisionStatus {
    Initializing,
    Inferencing,
    Stopped,
    PermissionDenied,
    Error,
    ModelAbsent;

    fun statusStringRes(): Int = when (this) {
        Initializing -> R.string.vision_status_initializing
        Inferencing -> R.string.vision_status_inferencing
        Stopped -> R.string.vision_status_stopped
        PermissionDenied -> R.string.vision_status_permission
        Error -> R.string.vision_status_error
        ModelAbsent -> R.string.vision_status_model_absent
    }

    fun statusColor(): Color = when (this) {
        Inferencing -> AriaGreen
        Initializing, ModelAbsent -> AriaSky
        PermissionDenied, Error -> AriaRed
        Stopped -> AriaSlate
    }
}

/**
 * Derives the truthful status of the vision pipeline for display in the main inset.
 * Gated by active session, camera state, model readiness, and fresh inference frames.
 */
fun computeMainVisionStatus(
    cameraStatus: CameraSessionStatus,
    visionModelState: VisionModelState,
    timing: VisionTimingSnapshot,
    hasFreshOverlay: Boolean,
    hasCameraPermission: Boolean,
    isCameraEnabled: Boolean = true,
): MainVisionStatus {
    if (!hasCameraPermission || cameraStatus == CameraSessionStatus.PermissionDenied) {
        return MainVisionStatus.PermissionDenied
    }
    if (cameraStatus == CameraSessionStatus.Error ||
        cameraStatus == CameraSessionStatus.CameraUnavailable ||
        visionModelState is VisionModelState.Error
    ) {
        return MainVisionStatus.Error
    }
    if (!isCameraEnabled || cameraStatus == CameraSessionStatus.Stopped) {
        return MainVisionStatus.Stopped
    }
    if (cameraStatus == CameraSessionStatus.Starting || visionModelState is VisionModelState.Loading) {
        return MainVisionStatus.Initializing
    }
    if (cameraStatus == CameraSessionStatus.Running) {
        if (!timing.modelLoaded || visionModelState !is VisionModelState.Ready) {
            return MainVisionStatus.ModelAbsent
        }
        return if (hasFreshOverlay) {
            MainVisionStatus.Inferencing
        } else {
            MainVisionStatus.Initializing
        }
    }
    return MainVisionStatus.Stopped
}

fun formatCadenceOrLatency(timing: VisionTimingSnapshot): String? {
    val fps = timing.steadyProcessedFps
    val latencyMs = timing.totalAnalyzerMs?.p50Ms ?: timing.modelInvokeMs?.p50Ms
    return when {
        fps > 0.0 && latencyMs != null && latencyMs > 0.0 ->
            "${fps.roundToInt()} FPS · ${latencyMs.roundToInt()}ms"
        fps > 0.0 ->
            "${fps.roundToInt()} FPS"
        latencyMs != null && latencyMs > 0.0 ->
            "${latencyMs.roundToInt()}ms"
        else -> null
    }
}

/**
 * Pure evaluator checking validity of a ground contact distance measurement.
 *
 * Rejects non-finite/negative distance, future timestamps, stale frames (>1.5s),
 * camera not running, profile unconfigured/invalid, or Home not visible.
 */
internal fun isDistanceMeasurementValid(
    isHomeVisible: Boolean,
    isCameraRunning: Boolean,
    isProfileValid: Boolean,
    distanceMeters: Float?,
    timestampNanos: Long,
    currentElapsedRealtimeNanos: Long,
    maxFreshnessNanos: Long = 1_500_000_000L,
    minValidTimestampNanos: Long = 0L,
): Boolean {
    if (!isHomeVisible || !isCameraRunning || !isProfileValid) return false
    if (distanceMeters == null || distanceMeters.isNaN() || !distanceMeters.isFinite() || distanceMeters <= 0f) return false
    if (timestampNanos <= 0L) return false
    if (timestampNanos < minValidTimestampNanos) return false
    val ageNanos = currentElapsedRealtimeNanos - timestampNanos
    return ageNanos in 0L..maxFreshnessNanos
}

internal data class DistancePresentation(
    val badgeText: String,
    val distanceDescription: String,
    val isActionableSetupHint: Boolean,
    val isFreshNumeric: Boolean,
)

/**
 * Pure evaluator mapping ground contact distance and profile state to badge text and accessibility info.
 */
internal fun evaluateDistancePresentation(
    isHomeVisible: Boolean,
    cameraStatus: CameraSessionStatus,
    profile: BodyMountProfile?,
    groundContactDistance: GroundContactDistance,
    currentElapsedRealtimeNanos: Long,
    minValidTimestampNanos: Long = 0L,
    unconfiguredText: String = "키·착용 위치 설정",
    measuringText: String = "거리 확인 중",
    stoppedText: String = "비전 중지됨",
): DistancePresentation {
    val isProfileValid = profile != null && profile.isValid
    val isCameraRunning = cameraStatus == CameraSessionStatus.Running

    if (!isProfileValid) {
        return DistancePresentation(
            badgeText = unconfiguredText,
            distanceDescription = "키·착용 위치 미설정",
            isActionableSetupHint = true,
            isFreshNumeric = false,
        )
    }

    if (!isCameraRunning) {
        return DistancePresentation(
            badgeText = stoppedText,
            distanceDescription = stoppedText,
            isActionableSetupHint = false,
            isFreshNumeric = false,
        )
    }

    val isValid = isDistanceMeasurementValid(
        isHomeVisible = isHomeVisible,
        isCameraRunning = true,
        isProfileValid = true,
        distanceMeters = groundContactDistance.distanceMeters,
        timestampNanos = groundContactDistance.timestampNanos,
        currentElapsedRealtimeNanos = currentElapsedRealtimeNanos,
        minValidTimestampNanos = minValidTimestampNanos,
    )

    if (isValid && groundContactDistance.distanceMeters != null) {
        val dist = groundContactDistance.distanceMeters
        val formatted = String.format(Locale.KOREAN, "추정 약 %.1fm", dist)
        return DistancePresentation(
            badgeText = formatted,
            distanceDescription = "추정 약 ${String.format(Locale.KOREAN, "%.1f", dist)}미터",
            isActionableSetupHint = false,
            isFreshNumeric = true,
        )
    }

    val ageNanos = currentElapsedRealtimeNanos - groundContactDistance.timestampNanos
    val isFreshMessage = groundContactDistance.timestampNanos >= minValidTimestampNanos &&
        groundContactDistance.timestampNanos > 0L &&
        ageNanos in 0L..1_500_000_000L &&
        groundContactDistance.message.isNotBlank() &&
        groundContactDistance.message != "키와 착용 위치를 설정하세요"

    if (isFreshMessage) {
        return DistancePresentation(
            badgeText = groundContactDistance.message,
            distanceDescription = groundContactDistance.message,
            isActionableSetupHint = false,
            isFreshNumeric = false,
        )
    }

    return DistancePresentation(
        badgeText = measuringText,
        distanceDescription = measuringText,
        isActionableSetupHint = false,
        isFreshNumeric = false,
    )
}

/**
 * Enlarged, read-only live camera and segmentation vision dashboard in the Home screen.
 *
 * Displays:
 * 1. Live CameraX preview and real-time segmentation mask overlay
 * 2. Truthful relative IMU heading arrow originating from bottom-center
 * 3. Read-only relative heading telemetry badge and ground contact distance badge
 * 4. Model cadence/FPS and latency metrics
 *
 * Sizing:
 * - Responsively enlarged (~270..340dp wide, ~180..230dp tall) adapting to portrait, landscape,
 *   and accessibility font scaling.
 *
 * Read-Only & Accessibility:
 * - Strictly non-interactive; contains no buttons or clickable controls.
 * - Recovery controls are relocated to Home action surfaces outside the dashboard.
 * - Comprehensive TalkBack descriptions state relative device orientation rather than walking trajectory.
 */
@Composable
fun MainVisionInset(
    visionUiState: VisionUiState,
    visionModelState: VisionModelState,
    hasCameraPermission: Boolean,
    isHomeVisible: Boolean,
    onStartCamera: (Preview.SurfaceProvider?, Int) -> Unit,
    @Suppress("UNUSED_PARAMETER") onStopCamera: () -> Unit = {},
    onSurfaceProviderUpdated: (Preview.SurfaceProvider?, Int) -> Unit,
    @Suppress("UNUSED_PARAMETER") onRequestCameraPermission: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") requiresExplicitRestart: Boolean = false,
    @Suppress("UNUSED_PARAMETER") onExplicitRestartCamera: (Preview.SurfaceProvider?, Int) -> Unit = { _, _ -> },
    @Suppress("UNUSED_PARAMETER") onOpenAppSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") onOpenProfileSetup: () -> Unit = {},
    groundContactDistance: GroundContactDistance? = null,
    profile: BodyMountProfile? = null,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val displayRotation = view.display?.rotation ?: Surface.ROTATION_0
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val fontScale = density.fontScale

    val profileStore = remember(context) { BodyMountProfileStore(context) }
    DisposableEffect(profileStore) {
        onDispose {
            profileStore.close()
        }
    }
    val storedProfile by profileStore.profile.collectAsStateWithLifecycle()
    val effectiveProfile = profile ?: storedProfile

    var previousProfile by remember { mutableStateOf(effectiveProfile) }
    var minValidTimestampNanos by remember { mutableLongStateOf(0L) }

    if (previousProfile != effectiveProfile) {
        minValidTimestampNanos = SystemClock.elapsedRealtimeNanos()
        previousProfile = effectiveProfile
    }

    var previewSurfaceProvider by remember { mutableStateOf<Preview.SurfaceProvider?>(null) }

    val isCameraActive = visionUiState.cameraStatus == CameraSessionStatus.Running ||
        visionUiState.cameraStatus == CameraSessionStatus.Starting

    // Session and periodic freshness gating: expire stale results if camera stalls (~3.5s)
    var lastFrameElapsedRealtime by remember { mutableLongStateOf(0L) }
    var lastObservedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var lastObservedFrames by remember { mutableIntStateOf(-1) }
    var isResultFresh by remember { mutableStateOf(false) }

    val totalFrames = visionUiState.timing.steadyFrames + visionUiState.timing.warmupFrames

    LaunchedEffect(visionUiState.cameraStatus) {
        if (visionUiState.cameraStatus != CameraSessionStatus.Running) {
            lastFrameElapsedRealtime = 0L
            lastObservedBitmap = null
            lastObservedFrames = -1
            isResultFresh = false
        }
    }

    LaunchedEffect(visionUiState.latestOverlayBitmap, totalFrames, visionUiState.cameraStatus) {
        if (visionUiState.cameraStatus == CameraSessionStatus.Running && visionUiState.latestOverlayBitmap != null) {
            val hasFrameAdvance = totalFrames > lastObservedFrames ||
                visionUiState.latestOverlayBitmap !== lastObservedBitmap
            if (hasFrameAdvance) {
                lastObservedBitmap = visionUiState.latestOverlayBitmap
                lastObservedFrames = totalFrames
                lastFrameElapsedRealtime = SystemClock.elapsedRealtime()
                isResultFresh = true
            }
        }
    }

    // Periodic check to expire stale inference if camera freezes or stops producing frames
    LaunchedEffect(isCameraActive, visionUiState.cameraStatus) {
        while (visionUiState.cameraStatus == CameraSessionStatus.Running) {
            val elapsed = SystemClock.elapsedRealtime() - lastFrameElapsedRealtime
            if (lastFrameElapsedRealtime > 0L && elapsed > 3_500L) {
                isResultFresh = false
            }
            delay(1_000L)
        }
    }

    val activeOverlayBitmap: Bitmap? = if (isResultFresh && visionUiState.cameraStatus == CameraSessionStatus.Running) {
        visionUiState.latestOverlayBitmap
    } else {
        null
    }

    val status = computeMainVisionStatus(
        cameraStatus = visionUiState.cameraStatus,
        visionModelState = visionModelState,
        timing = visionUiState.timing,
        hasFreshOverlay = isResultFresh,
        hasCameraPermission = hasCameraPermission,
    )

    // Automatically manage camera start/stop when Home visibility or permissions change
    LaunchedEffect(
        isHomeVisible,
        hasCameraPermission,
        displayRotation,
    ) {
        if (isHomeVisible && hasCameraPermission) {
            onStartCamera(previewSurfaceProvider, displayRotation)
        } else if (isCameraActive) {
            onSurfaceProviderUpdated(null, displayRotation)
            previewSurfaceProvider = null
        }
    }

    // Keep surface provider synced if preview view updates while camera is active
    LaunchedEffect(previewSurfaceProvider, displayRotation, isCameraActive) {
        if (isCameraActive) {
            previewSurfaceProvider?.let { provider ->
                onSurfaceProviderUpdated(provider, displayRotation)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            onSurfaceProviderUpdated(null, displayRotation)
            previewSurfaceProvider = null
        }
    }

    val cadenceLatency = remember(visionUiState.timing, status) {
        if (status == MainVisionStatus.Inferencing) {
            formatCadenceOrLatency(visionUiState.timing)
        } else {
            null
        }
    }

    // Ground contact distance contract:
    // Timestamp is always elapsedRealtime NANOSECONDS.
    // Periodic ticker ensures stale distance and heading expire within <= 1.5s even if frames halt.
    val effectiveGcd = groundContactDistance ?: visionUiState.groundContactDistance
    var tickerTick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(effectiveGcd.timestampNanos, visionUiState.motionState.timestampNanos, isHomeVisible, visionUiState.cameraStatus) {
        while (isHomeVisible && visionUiState.cameraStatus == CameraSessionStatus.Running) {
            tickerTick = SystemClock.elapsedRealtimeNanos()
            delay(200L)
        }
    }

    val currentNowNanos = if (tickerTick > 0L) tickerTick else SystemClock.elapsedRealtimeNanos()
    val unconfiguredLabel = stringResource(R.string.vision_inset_distance_unconfigured)
    val measuringLabel = stringResource(R.string.vision_inset_distance_measuring)
    val stoppedLabel = stringResource(status.statusStringRes())

    val presentation = remember(
        isHomeVisible,
        visionUiState.cameraStatus,
        effectiveProfile,
        effectiveGcd,
        currentNowNanos,
        minValidTimestampNanos,
        unconfiguredLabel,
        measuringLabel,
        stoppedLabel,
    ) {
        evaluateDistancePresentation(
            isHomeVisible = isHomeVisible,
            cameraStatus = visionUiState.cameraStatus,
            profile = effectiveProfile,
            groundContactDistance = effectiveGcd,
            currentElapsedRealtimeNanos = currentNowNanos,
            minValidTimestampNanos = minValidTimestampNanos,
            unconfiguredText = unconfiguredLabel,
            measuringText = measuringLabel,
            stoppedText = stoppedLabel,
        )
    }

    // Relative IMU heading evaluation
    val headingPresentation = remember(
        visionUiState.motionState,
        visionUiState.cameraStatus,
        isHomeVisible,
        currentNowNanos,
    ) {
        evaluateHeadingPresentation(
            motionState = visionUiState.motionState,
            cameraStatus = visionUiState.cameraStatus,
            isHomeVisible = isHomeVisible,
            currentElapsedRealtimeNanos = currentNowNanos,
        )
    }

    val badgeColor = when {
        presentation.isActionableSetupHint -> AriaSky
        presentation.isFreshNumeric -> AriaGreen
        visionUiState.cameraStatus != CameraSessionStatus.Running -> AriaSlate
        else -> AriaSlate
    }

    // Adaptive enlarged dashboard sizing
    val dashboardDimensions = remember(
        isLandscape,
        configuration.screenWidthDp,
        configuration.screenHeightDp,
        fontScale,
    ) {
        DashboardSizing.calculate(
            isLandscape = isLandscape,
            screenWidthDp = configuration.screenWidthDp.dp,
            screenHeightDp = configuration.screenHeightDp.dp,
            fontScale = fontScale,
        )
    }

    Surface(
        modifier = modifier
            .size(width = dashboardDimensions.widthDp, height = dashboardDimensions.heightDp)
            .testTag("mainVisionInset")
            .semantics {
                val metricsInfo = cadenceLatency?.let { " · $it" } ?: ""
                val dashboardTitle = "실시간 비전 대시보드 (읽기 전용)"
                contentDescription = "$dashboardTitle · ${presentation.distanceDescription} · ${headingPresentation.talkBackDescription}$metricsInfo"
            },
        shape = RoundedCornerShape(14.dp),
        color = AriaPanel.copy(alpha = 0.90f),
        border = BorderStroke(1.dp, AriaSlate.copy(alpha = 0.35f)),
        shadowElevation = 6.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (isCameraActive) {
                // Visual layer rendered at clearer opacity (~70% alpha)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(0.7f),
                ) {
                    VisionCameraPreviewBox(
                        isCameraActive = true,
                        cropRect = visionUiState.timing.cropRect,
                        previewUprightWidth = visionUiState.timing.previewUprightWidth,
                        previewUprightHeight = visionUiState.timing.previewUprightHeight,
                        overlayBitmap = activeOverlayBitmap,
                        modelLoaded = visionUiState.timing.modelLoaded,
                        onSurfaceProviderReady = { provider ->
                            previewSurfaceProvider = provider
                            onSurfaceProviderUpdated(provider, displayRotation)
                        },
                        fillViewport = true,
                        overlayAlpha = 0.65f,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // Direction line originating at horizontal center at bottom of actual preview area
                DashboardHeadingArrow(
                    presentation = headingPresentation,
                    bottomInsetDp = 2.dp,
                    modifier = Modifier.fillMaxSize(),
                )

                // Top metrics bar inside preview
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Cadence / FPS overlay
                    if (cadenceLatency != null) {
                        Box(
                            modifier = Modifier
                                .background(
                                    color = Color.Black.copy(alpha = 0.65f),
                                    shape = RoundedCornerShape(4.dp),
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = cadenceLatency,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = Color.White,
                                maxLines = 1,
                                modifier = Modifier.testTag("mainVisionCadenceLatency"),
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.size(1.dp))
                    }

                    // "추정" indicator tag pill
                    Box(
                        modifier = Modifier
                            .background(
                                color = Color.Black.copy(alpha = 0.65f),
                                shape = RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .testTag("mainVisionEstimateTag"),
                    ) {
                        Text(
                            text = stringResource(R.string.vision_inset_estimate_tag),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = AriaSlate,
                        )
                    }
                }

                // Bottom Read-Only Telemetry Bar (Distance + Relative Heading)
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Distance readout pill (Read-only)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.78f))
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                            .testTag("mainVisionDistanceBadge"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = presentation.badgeText,
                            color = badgeColor,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // Relative Heading readout pill (Read-only)
                    val headingColor = if (headingPresentation.isAuthoritativeArrowEligible) AriaSky else AriaSlate
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.78f))
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                            .testTag("mainVisionHeadingBadge"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = headingPresentation.badgeText,
                            color = headingColor,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                // Inactive placeholder / stopped or error status (Read-only presentation)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .testTag("mainVisionInactiveContent"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val statusLabel = stringResource(status.statusStringRes())
                    Text(
                        text = statusLabel,
                        color = status.statusColor(),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("mainVisionStoppedStatus"),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = headingPresentation.badgeText,
                        color = AriaSlate,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("mainVisionInactiveHeadingStatus"),
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val guidance = when {
                        status == MainVisionStatus.PermissionDenied ->
                            "카메라 권한을 허용해야 비전 모니터링이 시작됩니다."
                        requiresExplicitRestart ->
                            "음성 명령 또는 설정에서 다시 시작할 수 있습니다."
                        presentation.isActionableSetupHint ->
                            "설정에서 키와 착용 위치를 등록할 수 있습니다."
                        else ->
                            "음성 명령으로 활성화할 수 있습니다."
                    }
                    Text(
                        text = guidance,
                        color = AriaSlate.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
