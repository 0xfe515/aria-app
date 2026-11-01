package com.ariateam.aria.ui.screen

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.Surface
import androidx.camera.core.Preview
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
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
 * Meaningfully enlarged translucent live camera & inference preview inset in the main voice/home screen.
 *
 * Displays CameraX preview + segmentation overlay with metrics and real monocular ground distance
 * backed by [GroundContactDistance] and [BodyMountProfileStore].
 *
 * Sized ~210..224dp wide, ~140..150dp tall (responsive in landscape/portrait), readable with font scaling.
 */
@Composable
fun MainVisionInset(
    visionUiState: VisionUiState,
    visionModelState: VisionModelState,
    hasCameraPermission: Boolean,
    isHomeVisible: Boolean,
    onStartCamera: (Preview.SurfaceProvider?, Int) -> Unit,
    onStopCamera: () -> Unit,
    onSurfaceProviderUpdated: (Preview.SurfaceProvider?, Int) -> Unit,
    onRequestCameraPermission: () -> Unit,
    onOpenAppSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
    onOpenProfileSetup: () -> Unit = {},
    groundContactDistance: GroundContactDistance? = null,
    profile: BodyMountProfile? = null,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val displayRotation = view.display?.rotation ?: Surface.ROTATION_0
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

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
        visionModelState,
        displayRotation,
    ) {
        if (isHomeVisible && hasCameraPermission) {
            onStartCamera(previewSurfaceProvider, displayRotation)
        } else if (isCameraActive) {
            onSurfaceProviderUpdated(null, displayRotation)
            previewSurfaceProvider = null
            onStopCamera()
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
    // Periodic ticker ensures stale distance expires within <= 1.5s even if frames halt.
    val effectiveGcd = groundContactDistance ?: visionUiState.groundContactDistance
    var tickerTick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(effectiveGcd.timestampNanos, isHomeVisible, visionUiState.cameraStatus) {
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

    val badgeColor = when {
        presentation.isActionableSetupHint -> AriaSky
        presentation.isFreshNumeric -> AriaGreen
        visionUiState.cameraStatus != CameraSessionStatus.Running -> AriaSlate
        else -> AriaSlate
    }

    // Responsive dimensions: ~210..224dp wide, ~140..150dp tall
    val insetWidth = if (isLandscape) 210.dp else 224.dp
    val insetHeight = if (isLandscape) 140.dp else 150.dp

    Surface(
        modifier = modifier
            .size(width = insetWidth, height = insetHeight)
            .testTag("mainVisionInset")
            .semantics {
                val metricsInfo = cadenceLatency?.let { " · $it" } ?: ""
                contentDescription = "실시간 비전 · ${presentation.distanceDescription}$metricsInfo"
            },
        shape = RoundedCornerShape(14.dp),
        color = AriaPanel.copy(alpha = 0.9f),
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

                // Top metrics bar inside preview
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(start = 6.dp, top = 6.dp, end = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Cadence / FPS overlay
                    if (cadenceLatency != null) {
                        Box(
                            modifier = Modifier
                                .background(
                                    color = Color.Black.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(4.dp),
                                )
                                .padding(horizontal = 5.dp, vertical = 2.dp),
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
                                color = Color.Black.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 5.dp, vertical = 2.dp)
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

                // Bottom Distance & Status Badge
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.75f))
                        .then(
                            if (presentation.isActionableSetupHint) {
                                Modifier.clickable(role = Role.Button) { onOpenProfileSetup() }
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("mainVisionDistanceBadge"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = presentation.badgeText,
                        color = badgeColor,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                // Inactive placeholder / permission or error recovery when unavailable
                if (status == MainVisionStatus.PermissionDenied || status == MainVisionStatus.Error) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val statusLabel = stringResource(status.statusStringRes())
                        Text(
                            text = statusLabel,
                            color = AriaSlate,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        if (status == MainVisionStatus.PermissionDenied) {
                            Text(
                                text = stringResource(R.string.vision_inset_grant_permission),
                                color = AriaSky,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(AriaPanel)
                                    .clickable { onRequestCameraPermission() }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .semantics { role = Role.Button }
                                    .testTag("mainVisionPermissionRecoveryButton"),
                            )
                        } else {
                            Text(
                                text = "다시 시도",
                                color = AriaSky,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(AriaPanel)
                                    .clickable {
                                        onStartCamera(previewSurfaceProvider, displayRotation)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .semantics { role = Role.Button }
                                    .testTag("mainVisionRetryButton"),
                            )
                        }
                    }
                } else {
                    // Initializing / stopped placeholder with unconfigured hint if applicable
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = stringResource(status.statusStringRes()),
                            color = AriaSlate,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            modifier = Modifier.testTag("mainVisionStoppedStatus"),
                        )
                        if (presentation.isActionableSetupHint) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.vision_inset_distance_unconfigured),
                                color = AriaSky,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(AriaPanel)
                                    .clickable { onOpenProfileSetup() }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                    .semantics { role = Role.Button }
                                    .testTag("mainVisionDistanceSetupHint"),
                            )
                        }
                    }
                }
            }
        }
    }
}
