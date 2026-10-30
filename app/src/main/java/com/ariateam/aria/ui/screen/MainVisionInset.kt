package com.ariateam.aria.ui.screen

import android.graphics.Bitmap
import android.view.Surface
import androidx.camera.core.Preview
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import com.ariateam.aria.R
import com.ariateam.aria.ui.theme.AriaGreen
import com.ariateam.aria.ui.theme.AriaPanel
import com.ariateam.aria.ui.theme.AriaRed
import com.ariateam.aria.ui.theme.AriaSky
import com.ariateam.aria.ui.theme.AriaSlate
import com.ariateam.aria.vision.CameraSessionStatus
import com.ariateam.aria.vision.VisionModelState
import com.ariateam.aria.vision.VisionTimingSnapshot
import com.ariateam.aria.vision.VisionUiState
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
 * Minimal translucent live 2D inference preview inset in the main voice/home screen.
 *
 * Renders CameraX preview + mapped segmentation overlay at ~60% transparency (alpha 0.4)
 * with top-left FPS · latency metrics overlay.
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
) {
    val view = LocalView.current
    val displayRotation = view.display?.rotation ?: Surface.ROTATION_0

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
                lastFrameElapsedRealtime = android.os.SystemClock.elapsedRealtime()
                isResultFresh = true
            }
        }
    }

    // Periodic check to expire stale inference if camera freezes or stops producing frames
    LaunchedEffect(isCameraActive, visionUiState.cameraStatus) {
        while (visionUiState.cameraStatus == CameraSessionStatus.Running) {
            val elapsed = android.os.SystemClock.elapsedRealtime() - lastFrameElapsedRealtime
            if (lastFrameElapsedRealtime > 0L && elapsed > 3_500L) {
                isResultFresh = false
            }
            kotlinx.coroutines.delay(1_000L)
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

    Surface(
        modifier = modifier
            .size(width = 148.dp, height = 84.dp)
            .testTag("mainVisionInset")
            .semantics {
                contentDescription = cadenceLatency?.let { "실시간 비전: $it" } ?: "실시간 비전"
            },
        shape = RoundedCornerShape(12.dp),
        color = AriaPanel.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, AriaSlate.copy(alpha = 0.35f)),
        shadowElevation = 4.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (isCameraActive) {
                // Visual layer rendered at ~60% transparency (alpha 0.4)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(0.4f),
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
                        overlayAlpha = 0.55f,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // Crisp top-left metrics overlay inside preview
                if (cadenceLatency != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 6.dp, top = 5.dp)
                            .background(
                                color = Color.Black.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 4.dp, vertical = 2.dp),
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
                }
            } else {
                // Inactive placeholder / permission or error recovery when unavailable
                if (status == MainVisionStatus.PermissionDenied || status == MainVisionStatus.Error) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val statusLabel = stringResource(status.statusStringRes())
                        Text(
                            text = statusLabel,
                            color = AriaSlate,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        if (status == MainVisionStatus.PermissionDenied) {
                            Text(
                                text = stringResource(R.string.vision_inset_grant_permission),
                                color = AriaSky,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(AriaPanel)
                                    .clickable { onRequestCameraPermission() }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                    .semantics { role = Role.Button }
                                    .testTag("mainVisionPermissionRecoveryButton"),
                            )
                        } else {
                            Text(
                                text = "다시 시도",
                                color = AriaSky,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(AriaPanel)
                                    .clickable {
                                        onStartCamera(previewSurfaceProvider, displayRotation)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                    .semantics { role = Role.Button }
                                    .testTag("mainVisionRetryButton"),
                            )
                        }
                    }
                }
            }
        }
    }
}
