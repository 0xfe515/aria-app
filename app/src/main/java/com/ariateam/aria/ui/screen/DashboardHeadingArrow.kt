package com.ariateam.aria.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ariateam.aria.ui.theme.AriaGreen
import com.ariateam.aria.ui.theme.AriaSky
import com.ariateam.aria.ui.theme.AriaSlate
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Canvas overlay rendering a truthful relative IMU heading arrow originating at the bottom center.
 *
 * Visual geometry:
 * - Origin: Bottom center (x = width / 2, y = height - bottomOffset)
 * - Calibrated Straight Forward (0°): Vertical upward direction
 * - Left yaw (+deg): Arrow angles toward the screen's left
 * - Right yaw (-deg): Arrow angles toward the screen's right
 *
 * Truthful gating:
 * - If [presentation.isAuthoritativeArrowEligible] is false (uncalibrated, stale, sensor missing),
 *   the authoritative directional arrow is avoided. A subtle non-directional neutral anchor is shown.
 * - Outlines and shadows ensure high contrast against any live camera preview backdrop.
 */
@Composable
fun DashboardHeadingArrow(
    presentation: HeadingPresentation,
    modifier: Modifier = Modifier,
    bottomInsetDp: androidx.compose.ui.unit.Dp = 34.dp,
) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag("dashboardHeadingCanvas"),
    ) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas

        val bottomInset = bottomInsetDp.toPx()
        val headLength = 14.dp.toPx()
        val geometry = HeadingArrowGeometryCalculator.calculate(
            width = width,
            height = height,
            bottomInset = bottomInset,
            normalizedAngleDeg = presentation.normalizedHeadingDeg,
            headLength = headLength,
            safePadding = 12.dp.toPx(),
        )

        val originX = geometry.originX
        val originY = geometry.originY

        // Neutral 0-degree baseline reference guide (faint dashed vertical line)
        val availableHeight = (height - bottomInset).coerceAtLeast(60.dp.toPx())
        val guideLength = min(width, availableHeight) * 0.36f
        val guideEnd = Offset(originX, (originY - guideLength).coerceAtLeast(12.dp.toPx()))
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)

        drawLine(
            color = Color.Black.copy(alpha = 0.45f),
            start = Offset(originX, originY),
            end = guideEnd,
            strokeWidth = 3.dp.toPx(),
            pathEffect = dashEffect,
        )
        drawLine(
            color = AriaSlate.copy(alpha = 0.65f),
            start = Offset(originX, originY),
            end = guideEnd,
            strokeWidth = 1.5.dp.toPx(),
            pathEffect = dashEffect,
        )

        // Observer anchor circle
        drawCircle(
            color = Color.Black.copy(alpha = 0.7f),
            radius = 6.dp.toPx(),
            center = Offset(originX, originY),
        )
        drawCircle(
            color = if (presentation.isAuthoritativeArrowEligible) AriaSky else AriaSlate,
            radius = 4.dp.toPx(),
            center = Offset(originX, originY),
        )

        if (presentation.isAuthoritativeArrowEligible) {
            drawAuthoritativeHeadingArrow(
                geometry = geometry,
                normalizedAngleDeg = presentation.normalizedHeadingDeg,
            )
        } else {
            // Draw a subtle non-authoritative dashed ring indicating pending calibration / inactive
            drawCircle(
                color = AriaSlate.copy(alpha = 0.4f),
                radius = 18.dp.toPx(),
                center = Offset(originX, originY),
                style = Stroke(width = 1.5.dp.toPx(), pathEffect = dashEffect),
            )
        }
    }
}

private fun DrawScope.drawAuthoritativeHeadingArrow(
    geometry: HeadingArrowGeometry,
    normalizedAngleDeg: Float,
) {
    val originX = geometry.originX
    val originY = geometry.originY
    val tipX = geometry.tipX
    val tipY = geometry.tipY

    val strokeWidth = 3.5.dp.toPx()
    val outlineWidth = strokeWidth + 2.dp.toPx()

    // 1. Black shadow / outline for the shaft to ensure contrast against light camera backgrounds
    drawLine(
        color = Color.Black.copy(alpha = 0.85f),
        start = Offset(originX, originY),
        end = Offset(tipX, tipY),
        strokeWidth = outlineWidth,
        cap = StrokeCap.Round,
    )

    // 2. High-contrast foreground shaft
    val shaftColor = if (kotlin.math.abs(normalizedAngleDeg) < 3.0f) AriaGreen else AriaSky
    drawLine(
        color = shaftColor,
        start = Offset(originX, originY),
        end = Offset(tipX, tipY),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )

    // 3. Arrowhead at (tipX, tipY)
    val headPath = Path().apply {
        moveTo(tipX, tipY)
        lineTo(geometry.leftWingX, geometry.leftWingY)
        lineTo(geometry.rightWingX, geometry.rightWingY)
        close()
    }

    // Shadow/outline for head
    drawPath(
        path = headPath,
        color = Color.Black.copy(alpha = 0.85f),
        style = Stroke(width = 2.dp.toPx()),
    )
    // Solid arrowhead fill
    drawPath(
        path = headPath,
        color = shaftColor,
    )
}
