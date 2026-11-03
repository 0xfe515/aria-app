package com.ariateam.aria.ui.screen

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ariateam.aria.hazard.CorridorDirection
import com.ariateam.aria.hazard.MotionDegradedReason
import com.ariateam.aria.hazard.MotionState
import com.ariateam.aria.vision.CameraSessionStatus
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Normalizes any heading angle in degrees to the [-180.0, 180.0] range.
 * Angle wrapping ensures that accumulating or spinning gyro readings
 * wrap safely without unbounded values or sign oscillation.
 */
fun normalizeHeadingAngleDeg(angleDeg: Float): Float {
    if (!angleDeg.isFinite()) return 0f
    var normalized = angleDeg % 360f
    if (normalized > 180f) {
        normalized -= 360f
    } else if (normalized < -180f) {
        normalized += 360f
    }
    // Prevent negative zero (-0.0f)
    return if (normalized == -0.0f) 0.0f else normalized
}

/**
 * Evaluated presentation state for relative IMU heading on the read-only dashboard.
 */
data class HeadingPresentation(
    val isReliable: Boolean,
    val rawRelativeHeadingDeg: Float,
    val normalizedHeadingDeg: Float,
    val badgeText: String,
    val talkBackDescription: String,
    val guidanceReason: String?,
    val isAuthoritativeArrowEligible: Boolean,
)

/**
 * Pure evaluator mapping sensor/fusion motion state and camera lifecycle to truthful
 * relative heading presentation.
 *
 * Enforces strict truthful reporting:
 * - Only calibrated, fresh, un-degraded heading produces an authoritative heading arrow.
 * - Stale samples (> 1.5s), uncalibrated, unassisted, or degraded sensors display truthful
 *   guidance and explicitly avoid an authoritative arrow.
 * - Labels explicitly state relative orientation (상대 방향) rather than walking trajectory
 *   or absolute compass/position coordinates.
 */
fun evaluateHeadingPresentation(
    motionState: MotionState,
    cameraStatus: CameraSessionStatus,
    isHomeVisible: Boolean,
    currentElapsedRealtimeNanos: Long,
    staleThresholdNanos: Long = 1_500_000_000L,
): HeadingPresentation {
    if (!isHomeVisible || cameraStatus != CameraSessionStatus.Running) {
        return HeadingPresentation(
            isReliable = false,
            rawRelativeHeadingDeg = 0f,
            normalizedHeadingDeg = 0f,
            badgeText = "상대 방향: 비전 중지됨",
            talkBackDescription = "상대 방향: 비전 중지됨 (기기 상대 방위, 이동 경로 아님)",
            guidanceReason = "비전 중지됨",
            isAuthoritativeArrowEligible = false,
        )
    }

    if (motionState.timestampNanos <= 0L) {
        return HeadingPresentation(
            isReliable = false,
            rawRelativeHeadingDeg = 0f,
            normalizedHeadingDeg = 0f,
            badgeText = "상대 방향: 보정 대기 중",
            talkBackDescription = "상대 방향: 보정 대기 중 (기기 상대 방위, 이동 경로 아님)",
            guidanceReason = MotionDegradedReason.CALIBRATION_PENDING.koreanMessage,
            isAuthoritativeArrowEligible = false,
        )
    }

    val sampleAgeNanos = currentElapsedRealtimeNanos - motionState.timestampNanos
    if (sampleAgeNanos > staleThresholdNanos || sampleAgeNanos < -staleThresholdNanos) {
        return HeadingPresentation(
            isReliable = false,
            rawRelativeHeadingDeg = 0f,
            normalizedHeadingDeg = 0f,
            badgeText = "상대 방향: 신호 지연",
            talkBackDescription = "상대 방향: 센서 신호 지연 (기기 상대 방위, 이동 경로 아님)",
            guidanceReason = MotionDegradedReason.SAMPLES_STALE.koreanMessage,
            isAuthoritativeArrowEligible = false,
        )
    }

    if (!motionState.isCalibrated || motionState.degradedReason == MotionDegradedReason.CALIBRATION_PENDING) {
        return HeadingPresentation(
            isReliable = false,
            rawRelativeHeadingDeg = 0f,
            normalizedHeadingDeg = 0f,
            badgeText = "상대 방향: 보정 대기 중",
            talkBackDescription = "상대 방향: 보행 정면 캘리브레이션 대기 중 (기기 상대 방위, 이동 경로 아님)",
            guidanceReason = MotionDegradedReason.CALIBRATION_PENDING.koreanMessage,
            isAuthoritativeArrowEligible = false,
        )
    }

    // Explicit rejection of non-finite numeric inputs (NaN, +Infinity, -Infinity)
    if (!motionState.relativeHeadingDeg.isFinite() || !motionState.confidence.isFinite()) {
        return HeadingPresentation(
            isReliable = false,
            rawRelativeHeadingDeg = motionState.relativeHeadingDeg,
            normalizedHeadingDeg = 0f,
            badgeText = "상대 방향: 센서 수치 이상",
            talkBackDescription = "상대 방향: 센서 수치 이상 (${MotionDegradedReason.NON_FINITE_DATA.koreanMessage}, 기기 상대 방위, 이동 경로 아님)",
            guidanceReason = MotionDegradedReason.NON_FINITE_DATA.koreanMessage,
            isAuthoritativeArrowEligible = false,
        )
    }

    if (motionState.degradedReason != null) {
        val reason = motionState.degradedReason
        val badge = when (reason) {
            MotionDegradedReason.SENSORS_UNAVAILABLE -> "상대 방향: 센서 미지원"
            MotionDegradedReason.SAMPLES_STALE, MotionDegradedReason.SAMPLES_GAPPED -> "상대 방향: 신호 지연"
            MotionDegradedReason.NON_FINITE_DATA -> "상대 방향: 센서 수치 이상"
            MotionDegradedReason.CAMERA_TILT_INVALID -> "상대 방향: 카메라 각도 과도"
            MotionDegradedReason.RAPID_TURN_TRANSIENT -> "상대 방향: 급격한 회전 중"
            MotionDegradedReason.EXCESSIVE_DRIFT -> "상대 방향: 편차 과도 (재보정 필요)"
            MotionDegradedReason.CAMERA_FOV_UNKNOWN -> "상대 방향: 시야각 미확인"
            MotionDegradedReason.CALIBRATION_PENDING -> "상대 방향: 보정 대기 중"
        }
        return HeadingPresentation(
            isReliable = false,
            rawRelativeHeadingDeg = motionState.relativeHeadingDeg,
            normalizedHeadingDeg = if (motionState.relativeHeadingDeg.isFinite()) {
                normalizeHeadingAngleDeg(motionState.relativeHeadingDeg)
            } else {
                0f
            },
            badgeText = badge,
            talkBackDescription = "$badge (${reason.koreanMessage}, 기기 상대 방위, 이동 경로 아님)",
            guidanceReason = reason.koreanMessage,
            isAuthoritativeArrowEligible = false,
        )
    }

    // Strict confidence validation: minimum 0.5 threshold and valid contract bounds [0.5f, 1.0f]
    if (motionState.confidence < 0.5f || motionState.confidence > 1.0f) {
        val guidance = if (motionState.confidence in 0f..1f) {
            val confidencePct = (motionState.confidence * 100).toInt()
            "방향 신뢰도 부족 ($confidencePct%)"
        } else {
            "방향 신뢰도 범위 이상"
        }
        return HeadingPresentation(
            isReliable = false,
            rawRelativeHeadingDeg = motionState.relativeHeadingDeg,
            normalizedHeadingDeg = normalizeHeadingAngleDeg(motionState.relativeHeadingDeg),
            badgeText = "상대 방향: 신뢰도 부족",
            talkBackDescription = "상대 방향: 신뢰도 부족 ($guidance, 기기 상대 방위, 이동 경로 아님)",
            guidanceReason = guidance,
            isAuthoritativeArrowEligible = false,
        )
    }

    val normalized = normalizeHeadingAngleDeg(motionState.relativeHeadingDeg)
    val roundedDeg = abs(normalized.roundToInt())

    // In Aria's MotionFusionEngine, positive relativeHeadingDeg is Left yaw, negative is Right yaw.
    val (badgeText, directionDesc) = when {
        abs(normalized) < 3.0f -> {
            "상대 방향: 정면 (0°)" to "정면 0도"
        }
        normalized > 0f -> {
            "상대 방향: 좌측 $roundedDeg°" to "좌측 ${roundedDeg}도"
        }
        else -> {
            "상대 방향: 우측 $roundedDeg°" to "우측 ${roundedDeg}도"
        }
    }

    val talkBack = "$badgeText ($directionDesc, 기기 상대 방위, 이동 경로 아님)"

    return HeadingPresentation(
        isReliable = true,
        rawRelativeHeadingDeg = motionState.relativeHeadingDeg,
        normalizedHeadingDeg = normalized,
        badgeText = badgeText,
        talkBackDescription = talkBack,
        guidanceReason = null,
        isAuthoritativeArrowEligible = true,
    )
}

/**
 * Adaptive sizing calculator for the enlarged Home vision dashboard.
 *
 * Ensures expanded usable dimensions while accommodating portrait, landscape,
 * small screens, and large accessibility font scaling.
 */
object DashboardSizing {

    data class Dimensions(
        val widthDp: Dp,
        val heightDp: Dp,
    )

    fun calculate(
        isLandscape: Boolean,
        screenWidthDp: Dp,
        screenHeightDp: Dp,
        fontScale: Float = 1.0f,
    ): Dimensions {
        return if (isLandscape) {
            val targetWidth = (screenWidthDp * 0.36f).coerceIn(250.dp, 320.dp)
            val fontHeightAdjust = if (fontScale > 1.25f) 24.dp else if (fontScale > 1.15f) 16.dp else 0.dp
            val targetHeight = (screenHeightDp * 0.54f + fontHeightAdjust).coerceIn(160.dp, 240.dp)
            Dimensions(widthDp = targetWidth, heightDp = targetHeight)
        } else {
            // Enlarged portrait dashboard: ~280..340dp wide, ~180..240dp tall
            val targetWidth = (screenWidthDp - 28.dp).coerceIn(270.dp, 340.dp)
            val fontHeightAdjust = if (fontScale > 1.35f) 32.dp else if (fontScale > 1.25f) 24.dp else if (fontScale > 1.1f) 12.dp else 0.dp
            val targetHeight = (188.dp + fontHeightAdjust).coerceIn(180.dp, 240.dp)
            Dimensions(widthDp = targetWidth, heightDp = targetHeight)
        }
    }
}

/**
 * Geometric model for the relative IMU heading arrow.
 *
 * Guarantees that at any heading angle (including rear-facing angles 90°..180° / -90°..-180°),
 * the arrow tip and arrowhead wings are fully enclosed within the visible canvas bounds
 * and never clip or overlap the bottom telemetry bar.
 */
data class HeadingArrowGeometry(
    val originX: Float,
    val originY: Float,
    val tipX: Float,
    val tipY: Float,
    val leftWingX: Float,
    val leftWingY: Float,
    val rightWingX: Float,
    val rightWingY: Float,
    val arrowLength: Float,
)

object HeadingArrowGeometryCalculator {
    fun calculate(
        width: Float,
        height: Float,
        bottomInset: Float,
        normalizedAngleDeg: Float,
        headLength: Float = 36f,
        headAngleRad: Double = Math.toRadians(28.0),
        safePadding: Float = 14f,
    ): HeadingArrowGeometry {
        val originX = width / 2f
        val availableHeight = (height - bottomInset).coerceAtLeast(60f)
        val originY = availableHeight * 0.58f

        val safeTop = safePadding
        val safeBottom = (height - bottomInset - 4f).coerceAtLeast(originY + 16f)
        val safeLeft = safePadding
        val safeRight = (width - safePadding).coerceAtLeast(originX + 16f)

        val angleRad = Math.toRadians(normalizedAngleDeg.toDouble())
        val dx = -sin(angleRad).toFloat()
        val dy = -cos(angleRad).toFloat()

        val effectiveLeft = safeLeft + headLength
        val effectiveRight = safeRight - headLength
        val effectiveTop = safeTop + headLength
        val effectiveBottom = safeBottom - headLength

        val maxRayX = when {
            dx > 1e-4f -> (effectiveRight - originX) / dx
            dx < -1e-4f -> (effectiveLeft - originX) / dx
            else -> Float.MAX_VALUE
        }
        val maxRayY = when {
            dy > 1e-4f -> (effectiveBottom - originY) / dy
            dy < -1e-4f -> (effectiveTop - originY) / dy
            else -> Float.MAX_VALUE
        }

        val maxRadius = minOf(width, availableHeight) * 0.40f
        val maxAllowed = minOf(maxRayX, maxRayY).coerceAtLeast(18f)
        val arrowLength = minOf(maxRadius, maxAllowed)

        val tipX = originX + dx * arrowLength
        val tipY = originY + dy * arrowLength

        val leftWingAngle = angleRad + Math.PI - headAngleRad
        val leftWingX = tipX + (-sin(leftWingAngle).toFloat() * headLength)
        val leftWingY = tipY + (-cos(leftWingAngle).toFloat() * headLength)

        val rightWingAngle = angleRad + Math.PI + headAngleRad
        val rightWingX = tipX + (-sin(rightWingAngle).toFloat() * headLength)
        val rightWingY = tipY + (-cos(rightWingAngle).toFloat() * headLength)

        return HeadingArrowGeometry(
            originX = originX,
            originY = originY,
            tipX = tipX,
            tipY = tipY,
            leftWingX = leftWingX,
            leftWingY = leftWingY,
            rightWingX = rightWingX,
            rightWingY = rightWingY,
            arrowLength = arrowLength,
        )
    }
}
