package com.ariateam.aria.ui.screen

import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.camera.core.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ariateam.aria.R
import com.ariateam.aria.hazard.HazardLevel
import com.ariateam.aria.speech.VoiceError
import com.ariateam.aria.speech.VoicePhase
import com.ariateam.aria.speech.VoiceState
import com.ariateam.aria.vision.CameraSessionStatus
import com.ariateam.aria.vision.VisionModelState
import com.ariateam.aria.vision.VisionPipelineMode
import com.ariateam.aria.vision.VisionUiState
import java.util.Locale

/**
 * Integrated live demo with the camera viewport as the primary surface and secondary controls
 * layered over it. No inference or hazard result is represented unless it came from the pipeline.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun PresentationDemoScreen(
    voiceState: VoiceState,
    visionUiState: VisionUiState,
    visionModelState: VisionModelState,
    onBack: () -> Unit,
    onStartCamera: (Preview.SurfaceProvider?, VisionPipelineMode, Int) -> Unit,
    onStopCamera: () -> Unit,
    onStartVoiceWaiting: () -> Unit,
    onStopVoice: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onSurfaceProviderUpdated: (Preview.SurfaceProvider?, Int) -> Unit = { _, _ -> },
    onCalibrateForward: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val displayRotation = view.display?.rotation ?: Surface.ROTATION_0
    var previewSurfaceProvider by remember { mutableStateOf<Preview.SurfaceProvider?>(null) }
    var showDetails by remember { mutableStateOf(false) }

    BackHandler(onBack = {
        onStopCamera()
        onBack()
    })

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                onStopCamera()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            onStopCamera()
        }
    }

    LaunchedEffect(previewSurfaceProvider, displayRotation) {
        previewSurfaceProvider?.let { onSurfaceProviderUpdated(it, displayRotation) }
    }

    val isCameraActive = visionUiState.cameraStatus == CameraSessionStatus.Running ||
        visionUiState.cameraStatus == CameraSessionStatus.Starting
    val isVoiceActive = voiceState.isActive
    val isVoiceStopping = voiceState.phase == VoicePhase.Stopping
    val isCombinedControlStopping = isVoiceStopping && !isCameraActive
    val isCameraAndVoiceActive = isCameraActive && isVoiceActive
    val combinedControlDescription = stringResource(
        when {
            isCombinedControlStopping -> R.string.presentation_wait_for_voice_stopping
            isCameraAndVoiceActive -> R.string.presentation_stop_all_description
            else -> R.string.presentation_start_all_description
        },
    )
    val cameraControlDescription = stringResource(
        if (isCameraActive) R.string.presentation_stop_camera_description else R.string.presentation_start_camera_description,
    )
    val voiceControlDescription = stringResource(
        if (isVoiceActive) R.string.presentation_stop_voice_description else R.string.presentation_start_voice_description,
    )
    val canShowHazard = isCameraActive &&
        visionModelState is VisionModelState.Ready &&
        visionUiState.latestSummary != null &&
        visionUiState.hazardAssessment.timestampNanos > 0L

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF101418))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top + WindowInsetsSides.Bottom)),
    ) {
        VisionCameraPreviewBox(
            isCameraActive = isCameraActive,
            cropRect = visionUiState.timing.cropRect,
            previewUprightWidth = visionUiState.timing.previewUprightWidth,
            previewUprightHeight = visionUiState.timing.previewUprightHeight,
            overlayBitmap = visionUiState.latestOverlayBitmap,
            modelLoaded = visionUiState.timing.modelLoaded,
            onSurfaceProviderReady = { provider ->
                if (previewSurfaceProvider !== provider) previewSurfaceProvider = provider
            },
            fillViewport = true,
            modifier = Modifier
                .fillMaxSize()
                .testTag("presentationCameraViewport"),
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                color = Color(0xD9101722),
                contentColor = Color.White,
                shape = RoundedCornerShape(20.dp),
                shadowElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BackIconButton(
                        onClick = {
                            onStopCamera()
                            onBack()
                        },
                        modifier = Modifier.size(52.dp),
                        contentDescription = stringResource(R.string.presentation_back),
                        testTag = "presentationDemoBackButton",
                    )
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(
                            text = stringResource(R.string.presentation_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            text = stringResource(R.string.presentation_brand),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.74f),
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = stringResource(presentationCameraStatus(visionUiState.cameraStatus)),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(presentationVoiceStatus(voiceState.phase)),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.74f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (isCameraActive && canShowHazard) {
                HazardOverlay(
                    level = visionUiState.hazardAssessment.level,
                    text = visionUiState.hazardAssessment.messageKorean,
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!visionUiState.cameraErrorDetail.isNullOrBlank() ||
                visionUiState.cameraStatus == CameraSessionStatus.PermissionDenied
            ) {
                Surface(
                    color = Color(0xE61A1F27),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = if (visionUiState.cameraStatus == CameraSessionStatus.PermissionDenied) {
                                stringResource(R.string.presentation_camera_permission)
                            } else {
                                visionUiState.cameraErrorDetail.orEmpty()
                            },
                            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (visionUiState.cameraStatus == CameraSessionStatus.PermissionDenied) {
                            OutlinedButton(
                                onClick = onOpenAppSettings,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(R.string.open_app_settings), maxLines = 1)
                            }
                        }
                    }
                }
            }

            Surface(
                color = Color(0xE6101722),
                contentColor = Color.White,
                shape = RoundedCornerShape(22.dp),
                shadowElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = {
                                when {
                                    isCombinedControlStopping -> Unit
                                    isCameraAndVoiceActive -> {
                                        onStopCamera()
                                        if (!isVoiceStopping) onStopVoice()
                                    }
                                    else -> {
                                        if (!isCameraActive) {
                                            onStartCamera(
                                                previewSurfaceProvider,
                                                VisionPipelineMode.PREPROCESS_OR_INFERENCE,
                                                displayRotation,
                                            )
                                        }
                                        if (!isVoiceActive && !isVoiceStopping) onStartVoiceWaiting()
                                    }
                                }
                            },
                            enabled = !isCombinedControlStopping,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier
                                .weight(1.25f)
                                .heightIn(min = 48.dp)
                                .semantics(mergeDescendants = true) { contentDescription = combinedControlDescription }
                                .testTag("presentationCombinedControl"),
                        ) {
                            Text(
                                stringResource(
                                    when {
                                        isCombinedControlStopping -> R.string.presentation_voice_stopping
                                        isCameraAndVoiceActive -> R.string.presentation_stop_all
                                        else -> R.string.presentation_start_all
                                    },
                                ),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                if (isCameraActive) onStopCamera() else {
                                    onStartCamera(previewSurfaceProvider, VisionPipelineMode.PREPROCESS_OR_INFERENCE, displayRotation)
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .semantics(mergeDescendants = true) { contentDescription = cameraControlDescription }
                                .testTag("presentationCameraControl"),
                        ) {
                            Text(stringResource(R.string.presentation_camera), maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(
                            onClick = { if (isVoiceActive) onStopVoice() else onStartVoiceWaiting() },
                            enabled = !isVoiceStopping,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .semantics(mergeDescendants = true) { contentDescription = voiceControlDescription }
                                .testTag("presentationVoiceControl"),
                        ) {
                            Text(stringResource(R.string.presentation_voice), maxLines = 1, softWrap = false)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(
                                if (visionModelState is VisionModelState.Missing) {
                                    R.string.presentation_model_missing_short
                                } else {
                                    presentationCameraStatus(visionUiState.cameraStatus)
                                },
                            ),
                            modifier = Modifier.testTag("presentationModelStatus"),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (visionModelState is VisionModelState.Missing) {
                                MaterialTheme.colorScheme.error
                            } else {
                                Color.White.copy(alpha = 0.78f)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        OutlinedButton(
                            onClick = { showDetails = true },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("presentationDetailsButton"),
                        ) {
                            Text(stringResource(R.string.presentation_details), maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    if (showDetails) {
        ModalBottomSheet(
            onDismissRequest = { showDetails = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            PresentationDetails(
                voiceState = voiceState,
                visionUiState = visionUiState,
                visionModelState = visionModelState,
                onCalibrateForward = onCalibrateForward,
                onOpenAppSettings = onOpenAppSettings,
            )
        }
    }
}

private fun presentationCameraStatus(status: CameraSessionStatus): Int = when (status) {
    CameraSessionStatus.Stopped -> R.string.presentation_camera_off
    CameraSessionStatus.Starting -> R.string.presentation_camera_starting
    CameraSessionStatus.Running -> R.string.presentation_camera_live
    CameraSessionStatus.PermissionDenied -> R.string.presentation_camera_denied_short
    CameraSessionStatus.CameraUnavailable -> R.string.presentation_camera_unavailable
    CameraSessionStatus.Error -> R.string.presentation_camera_error
}

private fun presentationVoiceStatus(phase: VoicePhase): Int = when (phase) {
    VoicePhase.Stopped -> R.string.presentation_voice_off
    VoicePhase.Initializing -> R.string.presentation_voice_starting
    VoicePhase.Waiting -> R.string.presentation_voice_waiting
    VoicePhase.Dictating -> R.string.presentation_voice_listening
    VoicePhase.Stopping -> R.string.presentation_voice_stopping
    VoicePhase.Error -> R.string.presentation_voice_error
    VoicePhase.Reconnecting -> R.string.presentation_voice_reconnecting
    VoicePhase.Speaking -> R.string.presentation_voice_speaking
}

@Composable
private fun HazardOverlay(level: HazardLevel, text: String) {
    val color = when (level) {
        HazardLevel.PATH_BLOCKED, HazardLevel.NEAR_PATH_HAZARD -> MaterialTheme.colorScheme.errorContainer
        HazardLevel.PATH_CAUTION -> MaterialTheme.colorScheme.tertiaryContainer
        HazardLevel.CLEAR_UNWARNED -> MaterialTheme.colorScheme.primaryContainer
        HazardLevel.UNKNOWN_DEGRADED -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.94f)),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PresentationDetails(
    voiceState: VoiceState,
    visionUiState: VisionUiState,
    visionModelState: VisionModelState,
    onCalibrateForward: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val hazard = visionUiState.hazardAssessment
    val motion = visionUiState.motionState
    val latestSummary = visionUiState.latestSummary
    val cameraActive = visionUiState.cameraStatus == CameraSessionStatus.Starting ||
        visionUiState.cameraStatus == CameraSessionStatus.Running
    val hasCurrentSummary = cameraActive && visionModelState is VisionModelState.Ready && latestSummary != null
    val hasCurrentHazard = hasCurrentSummary && hazard.timestampNanos > 0L
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.presentation_details),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.presentation_camera_status, cameraStatusLabel(visionUiState.cameraStatus)),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!visionUiState.cameraErrorDetail.isNullOrBlank()) {
            Text(visionUiState.cameraErrorDetail, color = MaterialTheme.colorScheme.error)
        }
        if (visionUiState.cameraStatus == CameraSessionStatus.PermissionDenied) {
            OutlinedButton(onClick = onOpenAppSettings) {
                Text(stringResource(R.string.open_app_settings))
            }
        }
        when (visionModelState) {
            is VisionModelState.Missing -> Text(
                text = stringResource(R.string.presentation_model_missing_detail),
                modifier = Modifier.testTag("presentationModelDetails"),
                color = MaterialTheme.colorScheme.error,
            )
            is VisionModelState.Loading -> Text(stringResource(R.string.presentation_model_loading, visionModelState.displayName))
            is VisionModelState.Ready -> Text(stringResource(R.string.presentation_model_ready, visionModelState.metadata.displayName))
            is VisionModelState.Error -> {
                Text(stringResource(R.string.presentation_model_error, visionModelState.reason), color = MaterialTheme.colorScheme.error)
                if (visionModelState.detail.isNotBlank()) Text(visionModelState.detail, color = MaterialTheme.colorScheme.error)
            }
        }
        Text(stringResource(R.string.presentation_voice_status, stringResource(voiceStatus(voiceState.phase))))
        if (voiceState.command.message.isNotBlank()) {
            Text(stringResource(R.string.presentation_voice_message, voiceState.command.message))
        }
        Text(stringResource(R.string.presentation_voice_engine, voiceState.command.model, voiceState.command.runtime))
        if (voiceState.errorCode != VoiceError.NONE) {
            Text(
                stringResource(voiceError(voiceState.errorCode)),
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (hasCurrentSummary && latestSummary != null) {
            Text(
                stringResource(
                    R.string.presentation_roi_summary,
                    String.format(Locale.US, "%.0f", latestSummary.roiLeftWalkableRatio * 100f),
                    String.format(Locale.US, "%.0f", latestSummary.roiCenterWalkableRatio * 100f),
                    String.format(Locale.US, "%.0f", latestSummary.roiRightWalkableRatio * 100f),
                ),
            )
        }
        if (hasCurrentHazard) {
            Text(stringResource(R.string.presentation_hazard, hazard.level.koreanLabel, hazard.messageKorean))
        } else {
            Text(stringResource(R.string.presentation_hazard_pending))
        }
        Text(
            stringResource(
                R.string.presentation_motion_status,
                motion.activeCorridor.koreanLabel,
                stringResource(if (motion.isCalibrated) R.string.presentation_calibrated else R.string.presentation_calibration_needed),
            ),
        )
        if (motion.degradedReason != null) {
            Text(motion.degradedReason.koreanMessage, color = MaterialTheme.colorScheme.error)
        }
        OutlinedButton(onClick = onCalibrateForward, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.presentation_calibrate))
        }
    }
}
