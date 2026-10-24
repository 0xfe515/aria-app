package com.ariateam.aria.ui.screen

import android.graphics.Bitmap
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ariateam.aria.R
import com.ariateam.aria.hazard.CorridorDirection
import com.ariateam.aria.hazard.HazardAssessment
import com.ariateam.aria.hazard.HazardLevel
import com.ariateam.aria.hazard.MotionState
import com.ariateam.aria.vision.CameraSessionStatus
import com.ariateam.aria.vision.SensorTimestampSource
import com.ariateam.aria.vision.UprightCropRect
import com.ariateam.aria.vision.VisionBackendType
import com.ariateam.aria.vision.VisionContract
import com.ariateam.aria.vision.VisionFramePreprocessor
import com.ariateam.aria.vision.VisionModelState
import com.ariateam.aria.vision.VisionPipelineMode
import com.ariateam.aria.vision.VisionSegmentationSummary
import com.ariateam.aria.vision.VisionUiState
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun VisionDiagnosticsScreen(
    uiState: VisionUiState,
    modelState: VisionModelState,
    onBack: () -> Unit,
    onStartCamera: (Preview.SurfaceProvider?, VisionPipelineMode, Int) -> Unit,
    onStopCamera: () -> Unit,
    onSelectPipelineMode: (VisionPipelineMode) -> Unit,
    onResetMetrics: () -> Unit,
    onImportModelClick: () -> Unit,
    onClearModelClick: () -> Unit,
    onRestoreDefaultModelClick: () -> Unit = {},
    onApplyOptimizedModelClick: () -> Unit = {},
    onOpenAppSettings: () -> Unit,
    onSurfaceProviderUpdated: (Preview.SurfaceProvider?, Int) -> Unit = { _, _ -> },
    onCalibrateForward: () -> Unit = {},
    onToggleHazardAlert: (Boolean) -> Unit = {},
    onConfigureBackend: (VisionBackendType, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val displayRotation = view.display?.rotation ?: Surface.ROTATION_0
    var previewSurfaceProvider by remember { mutableStateOf<Preview.SurfaceProvider?>(null) }

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

    val isCameraActive = uiState.cameraStatus == CameraSessionStatus.Running ||
        uiState.cameraStatus == CameraSessionStatus.Starting
    val timing = uiState.timing

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackIconButton(
                onClick = {
                    onStopCamera()
                    onBack()
                },
                testTag = "visionDiagnosticsBackButton",
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.debug_open_vision),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
        }

        // 1. Model Importer & Validation Card
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "오프라인 세그멘테이션 모델 (.tflite)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "규격: 입력 [1, 288, 512, 3] FLOAT32 (RGB 0~1) · 출력 [1, 288, 512, 7] FLOAT32 (7클래스 softmax)",
                    style = MaterialTheme.typography.bodySmall,
                )
                when (modelState) {
                    is VisionModelState.Missing -> {
                        Text(
                            text = "모델 상태: 모델 미적용 (카메라 전처리 512×288 전용 모드)",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "실제 .tflite 모델 없이 카메라 프레임 수집 및 512:288 하단 정렬 크롭·정규화 병목을 측정할 수 있습니다.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    is VisionModelState.Loading -> {
                        Text(
                            text = "모델 검증 및 준비 중: ${modelState.displayName}…",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    is VisionModelState.Ready -> {
                        val meta = modelState.metadata
                        Text(
                            text = "모델 상태: 적용 완료 (${meta.displayName})",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "크기: ${formatBytes(meta.sizeBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    text = "SHA-256: ${meta.sha256}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    is VisionModelState.Error -> {
                        Text(
                            text = "모델 적용 실패: ${modelState.reason}",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (modelState.detail.isNotBlank()) {
                            SelectionContainer {
                                Text(
                                    text = "상세 원인: ${modelState.detail}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onApplyOptimizedModelClick) {
                        Text("GPU 최적화 모델 (FP32)")
                    }
                    OutlinedButton(onClick = onRestoreDefaultModelClick) {
                        Text("기본 원본 복원")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onImportModelClick) {
                        Text("새 모델 불러오기 (.tflite)")
                    }
                    if (modelState is VisionModelState.Ready) {
                        OutlinedButton(onClick = onClearModelClick) {
                            Text("모델 해제")
                        }
                    }
                }
            }
        }

        // 2. Camera Controls & Viewport
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "실시간 카메라 시야 (512×288 하단 정렬 ROI)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "상태: ${cameraStatusLabel(uiState.cameraStatus)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (uiState.cameraErrorDetail != null) {
                    Text(
                        text = uiState.cameraErrorDetail,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                VisionCameraPreviewBox(
                    isCameraActive = isCameraActive,
                    cropRect = timing.cropRect,
                    previewUprightWidth = timing.previewUprightWidth,
                    previewUprightHeight = timing.previewUprightHeight,
                    overlayBitmap = uiState.latestOverlayBitmap,
                    modelLoaded = timing.modelLoaded,
                    onSurfaceProviderReady = { provider ->
                        previewSurfaceProvider = provider
                        onSurfaceProviderUpdated(provider, displayRotation)
                    },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!isCameraActive) {
                        Button(
                            onClick = {
                                onStartCamera(previewSurfaceProvider, uiState.pipelineMode, displayRotation)
                            },
                        ) {
                            Text("카메라 시작")
                        }
                    } else {
                        Button(onClick = onStopCamera) {
                            Text("카메라 중지")
                        }
                    }
                    OutlinedButton(onClick = onResetMetrics) {
                        Text("측정치 초기화")
                    }
                }

                if (uiState.cameraStatus == CameraSessionStatus.PermissionDenied) {
                    OutlinedButton(onClick = onOpenAppSettings) {
                        Text(stringResource(R.string.open_app_settings))
                    }
                }
            }
        }

        // 3. Backend Acceleration & Thread Selection Card
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "추론 가속 백엔드 설정 (LiteRT)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "현재 활성 백엔드: ${uiState.activeBackendDescription}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        onClick = { onConfigureBackend(VisionBackendType.CPU, 4) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("CPU 4스레드", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = { onConfigureBackend(VisionBackendType.CPU, 2) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("CPU 2스레드", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = { onConfigureBackend(VisionBackendType.CPU, 1) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("CPU 1스레드", style = MaterialTheme.typography.labelSmall)
                    }
                    Button(
                        onClick = { onConfigureBackend(VisionBackendType.GPU, 4) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("GPU 대리자", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // 4. Direction-Aware IMU Motion & Orientation Card
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "보행 모션 추정 & 방향 인식 (가속도·자이로 융합)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                val motion = uiState.motionState
                val calibText = if (motion.isCalibrated) "보행 정면 캘리브레이션 완료" else "캘리브레이션 필요 (보행 정면 맞춤)"
                Text(
                    text = "캘리브레이션: $calibText",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = if (motion.isCalibrated) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = String.format(Locale.US, "피치(기울기): %.1f° | 롤: %.1f°", motion.pitchDegrees, motion.rollDegrees),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = String.format(Locale.US, "회전속도: %.1f°/s", motion.yawRateDegPerSec),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = String.format(Locale.US, "정면 대비 편각: %.1f°", motion.relativeHeadingDeg),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "진행 복도: ${motion.activeCorridor.koreanLabel} (중심: ${motion.continuousCorridorCenterPx.toInt()}px)",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (motion.degradedReason != null) {
                    Text(
                        text = "상태 제한: ${motion.degradedReason.koreanMessage}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = onCalibrateForward,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("현재 방향을 '보행 정면'으로 캘리브레이션")
                }
            }
        }

        // 5. Direction-Aware Hazard Assessment & Alert Card
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "방향 인식 위험 판단 & 햅틱 알림",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                val hazard = uiState.hazardAssessment
                val levelColor = when (hazard.level) {
                    HazardLevel.PATH_BLOCKED -> MaterialTheme.colorScheme.error
                    HazardLevel.NEAR_PATH_HAZARD -> MaterialTheme.colorScheme.error
                    HazardLevel.PATH_CAUTION -> MaterialTheme.colorScheme.tertiary
                    HazardLevel.CLEAR_UNWARNED -> MaterialTheme.colorScheme.primary
                    HazardLevel.UNKNOWN_DEGRADED -> MaterialTheme.colorScheme.outline
                }
                Text(
                    text = "위험 등급: ${hazard.level.koreanLabel}",
                    style = MaterialTheme.typography.titleSmall,
                    color = levelColor,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = hazard.messageKorean,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "진행 복도 보행 가능률: %.1f%% | 근접 위험 점유율: %.1f%%",
                        hazard.corridorWalkableRatio * 100f,
                        hazard.nearObstacleOccupancy * 100f,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "위험 경고 알림(진동/토스트): ${if (uiState.isHazardAlertEnabled) "켜짐" else "꺼짐"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = { onToggleHazardAlert(!uiState.isHazardAlertEnabled) },
                    ) {
                        Text(if (uiState.isHazardAlertEnabled) "알림 끄기" else "알림 켜기")
                    }
                }
            }
        }

        // 6. Detailed Timing & Profiling Breakdown Card
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "실시간 성능 측정치 (Wall-clock 실측)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "프레임 수: 워밍업 %d장 (센서 %d장) | 안정 구간 처리 %d장 (센서 콜백 %d장)",
                        timing.warmupFrames,
                        timing.sensorWarmupFrames,
                        timing.steadyFrames,
                        timing.sensorSteadyFrames,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "처리량 (벽시계 경과 기준): 분석 처리 %.2f FPS | 센서 캡처 %.2f FPS",
                        timing.steadyProcessedFps,
                        timing.steadySensorFps,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "관측된 센서 캡처-분석기 전달 차이 (안정 구간): ${timing.observedCaptureMinusAnalyzerDiffSteady}장 (센서 콜백 수 - 분석 완료 프레임 수)",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "1. 프레임 전달 주기 (Delivery Cadence): ${timing.formatDeliveryCadenceLabel()}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "2. 전처리 (Preprocess YUV→512×288 RGB 0..1): ${timing.formatPreprocessLabel()}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "3. 순수 모델 추론 (Model Invoke): ${timing.formatModelInvokeLabel()}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "4. 후처리 (Postprocess argmax·마스크): ${timing.formatPostprocessLabel()}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "5. 분석기 총 소요 (Total Analyzer): ${timing.formatTotalAnalyzerLabel()}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "6. 결과 게시 지연 (Result Publication): ${timing.formatPublicationLabel()}",
                    style = MaterialTheme.typography.bodySmall,
                )
                val clockNote = if (timing.timestampSource == SensorTimestampSource.REALTIME_VERIFIED) {
                    "SENSOR_INFO_TIMESTAMP_SOURCE=REALTIME 검증됨 · 화면 스캔아웃 표시 지연 아님"
                } else {
                    "클록 미검증"
                }
                Text(
                    text = "7. 센서 타임스탬프→결과 게시 경과 (Camera-to-Result Age): ${timing.formatCameraToResultAgeLabel()} ($clockNote)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // 7. Segmentation Output Summary Card
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "세그멘테이션 출력 요약 (참고용)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                val summary: VisionSegmentationSummary? = uiState.latestSummary
                if (!timing.modelLoaded || summary == null) {
                    Text(
                        text = "모델 미적용 상태입니다. 세그멘테이션 추론 결과 및 마스크 오버레이는 검증된 .tflite 모델을 불러온 뒤에만 생성됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        text = String.format(
                            Locale.US,
                            "하단 45%% ROI 보행 가능(인도·횡단보도) 비율: 좌 %.1f%% | 중 %.1f%% | 우 %.1f%%",
                            summary.roiLeftWalkableRatio * 100f,
                            summary.roiCenterWalkableRatio * 100f,
                            summary.roiRightWalkableRatio * 100f,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val dominantName = VisionContract.CLASSES.getOrNull(summary.dominantNonWalkableCenterClassId)?.koreanLabel ?: "없음"
                    Text(
                        text = "중앙 ROI 최다 비보행 클래스: $dominantName",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
internal fun VisionCameraPreviewBox(
    isCameraActive: Boolean,
    cropRect: UprightCropRect?,
    previewUprightWidth: Int,
    previewUprightHeight: Int,
    overlayBitmap: Bitmap?,
    modelLoaded: Boolean,
    onSurfaceProviderReady: (Preview.SurfaceProvider) -> Unit,
    modifier: Modifier = Modifier,
    fillViewport: Boolean = false,
) {
    Box(
        modifier = modifier
            .then(
                if (fillViewport) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.fillMaxWidth().height(240.dp)
                },
            )
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF101418)),
        contentAlignment = Alignment.Center,
    ) {
        if (isCameraActive) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        keepScreenOn = true
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FIT_CENTER
                        onSurfaceProviderReady(surfaceProvider)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            val imageBitmap = remember(overlayBitmap) { overlayBitmap?.asImageBitmap() }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val rect = cropRect
                if (rect != null) {
                    val vp = VisionFramePreprocessor.computeFitCenterOverlayViewport(
                        containerWidthPx = size.width,
                        containerHeightPx = size.height,
                        cropRect = rect,
                        previewUprightWidth = previewUprightWidth,
                        previewUprightHeight = previewUprightHeight,
                    )
                    if (modelLoaded && imageBitmap != null && vp.cropWidthPx > 0f && vp.cropHeightPx > 0f) {
                        drawImage(
                            image = imageBitmap,
                            dstOffset = IntOffset(vp.cropLeftPx.roundToInt(), vp.cropTopPx.roundToInt()),
                            dstSize = IntSize(vp.cropWidthPx.roundToInt(), vp.cropHeightPx.roundToInt()),
                            alpha = 0.55f,
                            filterQuality = FilterQuality.None,
                        )
                    }
                    drawRect(
                        color = Color(0xFF38BDF8),
                        topLeft = Offset(vp.cropLeftPx, vp.cropTopPx),
                        size = Size(vp.cropWidthPx, vp.cropHeightPx),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
        } else {
            Text(
                text = "카메라가 중지되어 있습니다.\n시작 버튼을 누를 때만 카메라가 활성화됩니다.",
                color = Color(0xFFB0BEC5),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

internal fun cameraStatusLabel(status: CameraSessionStatus): String = when (status) {
    CameraSessionStatus.Stopped -> "중지됨 (카메라 리소스 해제됨)"
    CameraSessionStatus.Starting -> "카메라 시작 중…"
    CameraSessionStatus.Running -> "실행 중"
    CameraSessionStatus.PermissionDenied -> "카메라 권한 거부됨"
    CameraSessionStatus.CameraUnavailable -> "카메라 장치 사용 불가"
    CameraSessionStatus.Error -> "카메라 오류"
}

private fun formatBytes(bytes: Long): String =
    String.format(Locale.US, "%.2f MB (%,d bytes)", bytes / (1024.0 * 1024.0), bytes)
