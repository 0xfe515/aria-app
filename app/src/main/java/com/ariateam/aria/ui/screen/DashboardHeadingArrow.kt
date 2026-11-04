package com.ariateam.aria.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ariateam.aria.ui.theme.AriaGreen
import com.ariateam.aria.ui.theme.AriaSky

/**
 * Canvas overlay rendering exactly ONE plain solid direction line originating at the
 * horizontal center at the bottom of the actual preview area near the user's implied position.
 *
 * Strict visual contract:
 * - Exactly ONE line: no outlines, multi-strokes, arrowheads, circles, rings, or dashed guides.
 * - Truthful invalid/stale/uncalibrated state hides the line.
 * - Rear directions (> 90° or < -90°) naturally hide the line, leaving truthful communication
 *   to the non-interactive status text badge.
 */
@Composable
fun DashboardHeadingArrow(
    presentation: HeadingPresentation,
    modifier: Modifier = Modifier,
    bottomInsetDp: Dp = 2.dp,
) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag("dashboardHeadingCanvas"),
    ) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas

        val geometry = HeadingArrowGeometryCalculator.calculate(
            width = width,
            height = height,
            normalizedAngleDeg = presentation.normalizedHeadingDeg,
            isAuthoritativeEligible = presentation.isAuthoritativeArrowEligible,
            safePadding = 8.dp.toPx(),
            strokeInset = bottomInsetDp.toPx(),
        )

        // Truthful invalid, stale, or rearward state naturally hides the line
        if (!geometry.isVisible) return@Canvas

        val strokeWidth = 3.5.dp.toPx()
        val lineColor = if (kotlin.math.abs(presentation.normalizedHeadingDeg) < 3.0f) {
            AriaGreen
        } else {
            AriaSky
        }

        // Exactly ONE plain solid line
        drawLine(
            color = lineColor,
            start = Offset(geometry.originX, geometry.originY),
            end = Offset(geometry.tipX, geometry.tipY),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}
