package com.ariateam.aria.ui.screen

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ariateam.aria.vision.CameraSessionStatus
import com.ariateam.aria.vision.UprightCropRect
import com.ariateam.aria.vision.VisionFramePreprocessor
import com.ariateam.aria.vision.od.DetectedObject
import com.ariateam.aria.vision.od.OdOverlaySnapshot
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Pixel-mapped rendering representation of an auxiliary object-detection bounding box.
 *
 * All coordinates are in container pixel space, strictly clipped to the visible crop rectangle.
 */
data class OdRenderedBox(
    val classId: Int,
    val koreanLabel: String,
    val formattedLabel: String,
    val score: Float,
    val rectLeftPx: Float,
    val rectTopPx: Float,
    val rectRightPx: Float,
    val rectBottomPx: Float,
) {
    val widthPx: Float get() = rectRightPx - rectLeftPx
    val heightPx: Float get() = rectBottomPx - rectTopPx
}

/**
 * Semantics key exposing the list of currently rendered OD bounding boxes for deterministic UI tests.
 */
val OdRenderedBoxesKey = SemanticsPropertyKey<List<OdRenderedBox>>("OdRenderedBoxes")

/**
 * Standard Korean labels for the model's supported COCO-90 category IDs.
 */
val KOREAN_OBSTACLE_LABELS: Map<Int, String> = mapOf(
    0 to "사람",
    1 to "자전거",
    2 to "자동차",
    3 to "오토바이",
    5 to "버스",
    7 to "트럭",
    9 to "신호등",
)

fun formatKoreanClassLabel(classId: Int, fallbackLabel: String = ""): String {
    return KOREAN_OBSTACLE_LABELS[classId]
        ?: if (fallbackLabel.isNotBlank()) fallbackLabel else "장애물"
}

fun formatOdLabel(detectedObject: DetectedObject): String {
    val label = formatKoreanClassLabel(detectedObject.classId, detectedObject.label)
    val pct = (detectedObject.score * 100f).roundToInt().coerceIn(0, 100)
    return "$label $pct%"
}

/**
 * Pure geometry mapping and freshness gating for auxiliary OD bounding boxes.
 */
object OdBoxGeometryCalculator {
    const val MAX_RENDERED_BOXES = 10
    const val MIN_BOX_WIDTH_FOR_LABEL_DP = 26f
    const val MIN_BOX_HEIGHT_FOR_LABEL_DP = 18f

    /**
     * Maps one [detection] from normalized `[0,1]` upright crop display space
     * into container pixel coordinates, strictly clipped to `[cropLeftPx, cropTopPx, cropWidthPx, cropHeightPx]`.
     */
    fun mapAndClip(
        detection: DetectedObject,
        cropLeftPx: Float,
        cropTopPx: Float,
        cropWidthPx: Float,
        cropHeightPx: Float,
    ): OdRenderedBox? {
        if (!detection.hasValidGeometry) return null
        if (cropWidthPx <= 0f || cropHeightPx <= 0f) return null

        val rawLeftPx = cropLeftPx + detection.left * cropWidthPx
        val rawTopPx = cropTopPx + detection.top * cropHeightPx
        val rawRightPx = cropLeftPx + detection.right * cropWidthPx
        val rawBottomPx = cropTopPx + detection.bottom * cropHeightPx

        val maxRightPx = cropLeftPx + cropWidthPx
        val maxBottomPx = cropTopPx + cropHeightPx

        val clampedLeft = rawLeftPx.coerceIn(cropLeftPx, maxRightPx)
        val clampedTop = rawTopPx.coerceIn(cropTopPx, maxBottomPx)
        val clampedRight = rawRightPx.coerceIn(cropLeftPx, maxRightPx)
        val clampedBottom = rawBottomPx.coerceIn(cropTopPx, maxBottomPx)

        if (clampedRight <= clampedLeft || clampedBottom <= clampedTop) return null

        return OdRenderedBox(
            classId = detection.classId,
            koreanLabel = formatKoreanClassLabel(detection.classId, detection.label),
            formattedLabel = formatOdLabel(detection),
            score = detection.score,
            rectLeftPx = clampedLeft,
            rectTopPx = clampedTop,
            rectRightPx = clampedRight,
            rectBottomPx = clampedBottom,
        )
    }

    /**
     * Evaluates whether OD boxes should be rendered, returning the mapped and clipped boxes.
     *
     * Inactive when:
     * - Camera session is not [CameraSessionStatus.Running]
     * - Segmentation model is not loaded or has no fresh image
     * - Snapshot is null, expired (for legacy finite snapshots), or contains no detections
     *
     * Held snapshots ([OdOverlaySnapshot.holdUntilNextResult] = true) remain visually fresh across
     * elapsed time until replaced by the next completed result or cleared by empty/null/camera-stop.
     */
    fun evaluateBoxes(
        cameraStatus: CameraSessionStatus,
        modelLoaded: Boolean,
        hasFreshImage: Boolean,
        odOverlay: OdOverlaySnapshot?,
        nowElapsedRealtimeNanos: Long,
        cropLeftPx: Float,
        cropTopPx: Float,
        cropWidthPx: Float,
        cropHeightPx: Float,
        maxBoxes: Int = MAX_RENDERED_BOXES,
    ): List<OdRenderedBox> {
        if (cameraStatus != CameraSessionStatus.Running) return emptyList()
        if (!modelLoaded || !hasFreshImage) return emptyList()
        if (odOverlay == null) return emptyList()
        if (!odOverlay.isFreshAt(nowElapsedRealtimeNanos)) return emptyList()
        if (odOverlay.detections.isEmpty()) return emptyList()

        return odOverlay.detections
            .take(maxBoxes)
            .mapNotNull { detection ->
                mapAndClip(detection, cropLeftPx, cropTopPx, cropWidthPx, cropHeightPx)
            }
    }

    /**
     * Builds a concise accessibility summary for TalkBack without spamming every frame.
     */
    fun buildAccessibilityDescription(boxes: List<OdRenderedBox>): String? {
        if (boxes.isEmpty()) return null
        val sample = boxes.take(3).joinToString(", ") { it.formattedLabel }
        return "보조 탐지 객체 ${boxes.size}개: $sample"
    }
}

/**
 * Non-interactive, display-only bounding box overlay for auxiliary object detections.
 *
 * Features:
 * - Contrasting dual-stroke rectangles (dark backdrop + amber stroke)
 * - Concise Korean class labels and confidence percentages where legible
 * - Strictly clipped to the visible crop rectangle
 * - Explicitly held visual snapshots ([OdOverlaySnapshot.holdUntilNextResult] = true): visual boxes
 *   are retained continuously across frames without 750 ms disappearance until the next completed
 *   detection result replaces them, or until empty detections / camera stoppage / model unloading clears them.
 * - Legacy finite snapshot expiry timer ([OdOverlaySnapshot.holdUntilNextResult] = false): guarantees
 *   immediate box disappearance upon [OdOverlaySnapshot.expiresAtElapsedNanos] even when the camera
 *   analyzer does not publish new frames
 * - Exposes [OdRenderedBoxesKey] in Compose semantics for deterministic UI testing
 */
@Composable
internal fun OdBoundingBoxesOverlay(
    cropRect: UprightCropRect?,
    previewUprightWidth: Int,
    previewUprightHeight: Int,
    modelLoaded: Boolean,
    hasFreshImage: Boolean,
    odOverlay: OdOverlaySnapshot?,
    cameraStatus: CameraSessionStatus,
    modifier: Modifier = Modifier,
    clockNanos: () -> Long = { SystemClock.elapsedRealtimeNanos() },
) {
    var expiryTick by remember { mutableLongStateOf(0L) }
    val currentSnapshot = odOverlay

    // Freshness timer for legacy finite snapshots (holdUntilNextResult = false).
    // For held snapshots (holdUntilNextResult = true), the expiry timer is completely bypassed:
    // boxes persist until snapshot is replaced with new results, cleared with empty/null,
    // or invalidated by camera stop, error, or model/image unreadiness.
    LaunchedEffect(currentSnapshot, cameraStatus, modelLoaded, hasFreshImage) {
        if (cameraStatus == CameraSessionStatus.Running &&
            modelLoaded &&
            hasFreshImage &&
            currentSnapshot != null &&
            !currentSnapshot.holdUntilNextResult
        ) {
            while (true) {
                val now = clockNanos()
                val remainingNanos = currentSnapshot.expiresAtElapsedNanos - now
                if (remainingNanos < 0L) {
                    expiryTick = now
                    break
                }
                // When remainingNanos >= 0L (including exact equality where isFreshAt is still true),
                // delay past expiresAtElapsedNanos by adding 1 ms.
                val delayMillis = (remainingNanos / 1_000_000L) + 1L
                delay(delayMillis)
            }
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }

        val boxes = remember(
            cropRect,
            previewUprightWidth,
            previewUprightHeight,
            modelLoaded,
            hasFreshImage,
            currentSnapshot,
            cameraStatus,
            containerWidthPx,
            containerHeightPx,
            expiryTick,
        ) {
            if (cropRect == null || containerWidthPx <= 0f || containerHeightPx <= 0f) {
                emptyList()
            } else {
                val vp = VisionFramePreprocessor.computeFitCenterOverlayViewport(
                    containerWidthPx = containerWidthPx,
                    containerHeightPx = containerHeightPx,
                    cropRect = cropRect,
                    previewUprightWidth = previewUprightWidth,
                    previewUprightHeight = previewUprightHeight,
                )
                OdBoxGeometryCalculator.evaluateBoxes(
                    cameraStatus = cameraStatus,
                    modelLoaded = modelLoaded,
                    hasFreshImage = hasFreshImage,
                    odOverlay = currentSnapshot,
                    nowElapsedRealtimeNanos = clockNanos(),
                    cropLeftPx = vp.cropLeftPx,
                    cropTopPx = vp.cropTopPx,
                    cropWidthPx = vp.cropWidthPx,
                    cropHeightPx = vp.cropHeightPx,
                )
            }
        }

        val accessibilityDesc = remember(boxes) {
            OdBoxGeometryCalculator.buildAccessibilityDescription(boxes)
        }

        val textMeasurer = rememberTextMeasurer()

        val minLabelWidthPx = with(density) { OdBoxGeometryCalculator.MIN_BOX_WIDTH_FOR_LABEL_DP.dp.toPx() }
        val minLabelHeightPx = with(density) { OdBoxGeometryCalculator.MIN_BOX_HEIGHT_FOR_LABEL_DP.dp.toPx() }
        val strokeWidthPx = with(density) { 2.dp.toPx() }
        val shadowStrokeWidthPx = with(density) { 3.5.dp.toPx() }
        val cornerRadiusPx = with(density) { 3.dp.toPx() }
        val labelPadHPx = with(density) { 4.dp.toPx() }
        val labelPadVPx = with(density) { 1.5f.dp.toPx() }

        val rect = cropRect

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag("visionOdOverlay")
                .semantics {
                    set(OdRenderedBoxesKey, boxes)
                    if (accessibilityDesc != null) {
                        contentDescription = accessibilityDesc
                    }
                },
        ) {
            if (boxes.isEmpty() || rect == null) return@Canvas

            val vp = VisionFramePreprocessor.computeFitCenterOverlayViewport(
                containerWidthPx = size.width,
                containerHeightPx = size.height,
                cropRect = rect,
                previewUprightWidth = previewUprightWidth,
                previewUprightHeight = previewUprightHeight,
            )
            if (vp.cropWidthPx <= 0f || vp.cropHeightPx <= 0f) return@Canvas

            // Clip all drawing (strokes, badges, and text) strictly to the crop rectangle
            clipRect(
                left = vp.cropLeftPx,
                top = vp.cropTopPx,
                right = vp.cropLeftPx + vp.cropWidthPx,
                bottom = vp.cropTopPx + vp.cropHeightPx,
            ) {
                val strokeColor = Color(0xFFFFB703) // Contrasting Amber Yellow
                val shadowColor = Color(0x99000000) // Dark shadow stroke for high contrast
                val badgeBgColor = Color(0xDD0F172A) // Dark slate/navy backdrop

                for (box in boxes) {
                    val boxWidth = box.widthPx
                    val boxHeight = box.heightPx
                    if (boxWidth <= 0f || boxHeight <= 0f) continue

                    val topLeft = Offset(box.rectLeftPx, box.rectTopPx)
                    val boxSize = Size(boxWidth, boxHeight)

                    // 1. Dual-stroke rectangle (shadow + contrasting amber)
                    drawRect(
                        color = shadowColor,
                        topLeft = topLeft,
                        size = boxSize,
                        style = Stroke(width = shadowStrokeWidthPx),
                    )
                    drawRect(
                        color = strokeColor,
                        topLeft = topLeft,
                        size = boxSize,
                        style = Stroke(width = strokeWidthPx),
                    )

                    // 2. Concise Korean label badge where legible
                    if (boxWidth >= minLabelWidthPx && boxHeight >= minLabelHeightPx) {
                        val textLayoutResult = textMeasurer.measure(
                            text = box.formattedLabel,
                            style = TextStyle(
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        )

                        val textW = textLayoutResult.size.width.toFloat()
                        val textH = textLayoutResult.size.height.toFloat()
                        val badgeW = textW + labelPadHPx * 2f
                        val badgeH = textH + labelPadVPx * 2f

                        // Anchor top-left, prefer sitting inside or above the box, strictly within crop bounds
                        val badgeLeft = box.rectLeftPx.coerceIn(
                            vp.cropLeftPx,
                            (vp.cropLeftPx + vp.cropWidthPx - badgeW).coerceAtLeast(vp.cropLeftPx),
                        )
                        val preferredTop = if (box.rectTopPx - badgeH >= vp.cropTopPx) {
                            box.rectTopPx - badgeH
                        } else {
                            box.rectTopPx
                        }
                        val badgeTop = preferredTop.coerceIn(
                            vp.cropTopPx,
                            (vp.cropTopPx + vp.cropHeightPx - badgeH).coerceAtLeast(vp.cropTopPx),
                        )

                        drawRoundRect(
                            color = badgeBgColor,
                            topLeft = Offset(badgeLeft, badgeTop),
                            size = Size(badgeW, badgeH),
                            cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                        )
                        drawText(
                            textLayoutResult = textLayoutResult,
                            topLeft = Offset(badgeLeft + labelPadHPx, badgeTop + labelPadVPx),
                        )
                    }
                }
            }
        }
    }
}
