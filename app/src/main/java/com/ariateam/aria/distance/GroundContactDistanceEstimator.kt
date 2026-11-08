package com.ariateam.aria.distance

import com.ariateam.aria.hazard.CameraGeometry
import com.ariateam.aria.hazard.EffectiveFov
import com.ariateam.aria.hazard.FullUprightCameraModel
import com.ariateam.aria.hazard.GroundIntersection
import com.ariateam.aria.hazard.Vector3D
import com.ariateam.aria.vision.od.DetectedObject
import java.util.Locale
import kotlin.math.abs

/**
 * Candidate ground contact point extracted from semantic segmentation mask.
 */
data class GroundContactCandidate(
    val classId: Int,
    val pixelCount: Int,
    val xNorm: Float,
    val yNorm: Float,
    val fullUNorm: Float,
    val fullVNorm: Float,
    val isBottomClipped: Boolean,
    val isFloatingUnsupported: Boolean,
    val groundSupportPixels: Int = 0,
    val intersection: GroundIntersection? = null,
    val associatedDetection: DetectedObject? = null,
)

/**
 * Pure monocular ground-contact distance estimator.
 *
 * Implements stature/mount-based height projection onto flat ground plane:
 * - Ray generation from canonical upright coordinates (512x288 grid) mapped to full-frame optical center.
 * - Live gravity vector mapping into camera coordinates with bounded history alignment.
 * - Robust ground contact extraction from person (4), vehicle (5), and pole (6) masks.
 * - Rejection of frame-bottom clipped masks and floating unsupported objects.
 * - Strict lateral corridor (+/-0.45m) enforcement for forward walking path guidance.
 */
class GroundContactDistanceEstimator(
    val corridorHalfWidthMeters: Float = CORRIDOR_HALF_WIDTH_METERS,
    val minClusterPixels: Int = MIN_CLUSTER_PIXELS,
) {
    private var lastSmoothedDistance: Float? = null
    private val matcher = OdMaskMatcher(minClusterPixels = minClusterPixels)

    fun reset() {
        lastSmoothedDistance = null
    }

    /**
     * Estimates ground-contact distance from segmentation classMask, camera geometry,
     * user mount profile, and live IMU gravity orientation.
     */
    fun estimate(
        classMask: ByteArray?,
        width: Int,
        height: Int,
        profile: BodyMountProfile?,
        effectiveFov: EffectiveFov?,
        orientation: GroundContactOrientation?,
        timestampNanos: Long,
        isClockRealtime: Boolean = true,
        fullCameraModel: FullUprightCameraModel? = null,
        cropLeftNorm: Float = 0f,
        cropTopNorm: Float = 0f,
        cropWidthNorm: Float = 1f,
        cropHeightNorm: Float = 1f,
        detections: List<DetectedObject>? = null,
    ): GroundContactDistance {
        if (!isClockRealtime) {
            reset()
            return GroundContactDistance(
                distanceMeters = null,
                message = "카메라 센서 클럭 비호환 (거리 추정 비활성)",
                timestampNanos = timestampNanos,
            )
        }

        if (profile == null || !profile.isValid) {
            reset()
            return GroundContactDistance(
                distanceMeters = null,
                message = "키와 착용 위치를 설정하세요",
                timestampNanos = timestampNanos,
            )
        }

        val cameraHeightMeters = profile.estimatedCameraHeightMeters
        if (cameraHeightMeters == null || cameraHeightMeters <= 0f) {
            reset()
            return GroundContactDistance(
                distanceMeters = null,
                message = "키와 착용 위치를 설정하세요",
                timestampNanos = timestampNanos,
            )
        }

        if (effectiveFov == null && fullCameraModel == null) {
            reset()
            return GroundContactDistance(
                distanceMeters = null,
                message = "카메라 내부 보정값(초점거리) 미확인",
                timestampNanos = timestampNanos,
            )
        }

        if (orientation == null || !orientation.isReliable) {
            reset()
            val reason = orientation?.degradedReason ?: "센서(자세) 신호 미수신"
            return GroundContactDistance(
                distanceMeters = null,
                message = reason,
                timestampNanos = timestampNanos,
                rawGyroDegPerSec = orientation?.rawGyroDegPerSec ?: 0f,
                smoothedGyroDegPerSec = orientation?.smoothedGyroDegPerSec ?: 0f,
                restrictionReason = orientation?.restrictionReason,
            )
        }

        if (classMask == null || classMask.size < width * height) {
            reset()
            return GroundContactDistance(
                distanceMeters = null,
                message = "전방 보행 경로 장애물 미감지",
                timestampNanos = timestampNanos,
                rawGyroDegPerSec = orientation.rawGyroDegPerSec,
                smoothedGyroDegPerSec = orientation.smoothedGyroDegPerSec,
                restrictionReason = orientation.restrictionReason,
            )
        }

        val candidates = extractCandidates(
            classMask = classMask,
            width = width,
            height = height,
            effectiveFov = effectiveFov,
            cameraHeightMeters = cameraHeightMeters,
            upVector = orientation.upVector,
            fullCameraModel = fullCameraModel,
            cropLeftNorm = cropLeftNorm,
            cropTopNorm = cropTopNorm,
            cropWidthNorm = cropWidthNorm,
            cropHeightNorm = cropHeightNorm,
            detections = detections,
        )

        if (candidates.isEmpty()) {
            reset()
            return GroundContactDistance(
                distanceMeters = null,
                message = "전방 보행 경로 장애물 미감지",
                timestampNanos = timestampNanos,
                rawGyroDegPerSec = orientation.rawGyroDegPerSec,
                smoothedGyroDegPerSec = orientation.smoothedGyroDegPerSec,
                restrictionReason = orientation.restrictionReason,
            )
        }

        // Candidates that are not bottom-clipped and not floating in the air
        val validGeometryCandidates = candidates.filter { !it.isBottomClipped && !it.isFloatingUnsupported }

        if (validGeometryCandidates.isEmpty()) {
            reset()
            val message = when {
                candidates.any { it.isBottomClipped } -> "접점 화면 경계 잘림 (거리 추정 불가)"
                candidates.any { it.isFloatingUnsupported } -> "지면 접점 미확인 (공중 객체)"
                else -> "카메라 각도 부적합 (지면 시야 이탈)"
            }
            return GroundContactDistance(
                distanceMeters = null,
                message = message,
                timestampNanos = timestampNanos,
                rawGyroDegPerSec = orientation.rawGyroDegPerSec,
                smoothedGyroDegPerSec = orientation.smoothedGyroDegPerSec,
                restrictionReason = orientation.restrictionReason,
            )
        }

        val intersectingCandidates = validGeometryCandidates.filter { it.intersection != null }
        if (intersectingCandidates.isEmpty()) {
            reset()
            return GroundContactDistance(
                distanceMeters = null,
                message = "카메라 각도 부적합 (지면 시야 이탈)",
                timestampNanos = timestampNanos,
            )
        }

        // Enforce walking corridor (+/-0.45m) strictly
        val inCorridor = intersectingCandidates.filter {
            abs(it.intersection!!.lateralDistanceMeters) <= corridorHalfWidthMeters
        }

        if (inCorridor.isEmpty()) {
            reset()
            return GroundContactDistance(
                distanceMeters = null,
                message = "전방 보행 경로 장애물 미감지",
                timestampNanos = timestampNanos,
            )
        }

        val bestCandidate = inCorridor.minByOrNull { it.intersection!!.forwardDistanceMeters }!!
        val rawDist = bestCandidate.intersection!!.forwardDistanceMeters

        // Temporal smoothing: 2-frame alpha filter when consecutive frames are close
        val prev = lastSmoothedDistance
        val smoothed = if (prev != null && abs(rawDist - prev) < 1.2f) {
            0.5f * prev + 0.5f * rawDist
        } else {
            rawDist
        }
        lastSmoothedDistance = smoothed

        val formatted = String.format(Locale.US, "%.1f", smoothed)
        return GroundContactDistance(
            distanceMeters = smoothed,
            message = "추정 거리 ${formatted}m",
            timestampNanos = timestampNanos,
            contactXNormalized = bestCandidate.xNorm,
            contactYNormalized = bestCandidate.yNorm,
            rawGyroDegPerSec = orientation.rawGyroDegPerSec,
            smoothedGyroDegPerSec = orientation.smoothedGyroDegPerSec,
            restrictionReason = orientation.restrictionReason,
        )
    }

    /**
     * Extracts ground contact candidates for target classes (4=person, 5=vehicle, 6=pole).
     *
     * When [detections] is provided (non-null), associates qualified OD detections with segmentation
     * components, enforcing lower-boundary agreement, ground support, and clipping checks.
     * Retains pole (6) as explicit fallback from segmentation.
     * When [detections] is null, falls back to pure segmentation-only extraction.
     */
    fun extractCandidates(
        classMask: ByteArray,
        width: Int,
        height: Int,
        effectiveFov: EffectiveFov? = null,
        cameraHeightMeters: Float? = null,
        upVector: Vector3D? = null,
        fullCameraModel: FullUprightCameraModel? = null,
        cropLeftNorm: Float = 0f,
        cropTopNorm: Float = 0f,
        cropWidthNorm: Float = 1f,
        cropHeightNorm: Float = 1f,
        detections: List<DetectedObject>? = null,
    ): List<GroundContactCandidate> {
        val pixelCount = width * height
        if (classMask.size < pixelCount || width <= 0 || height <= 0) return emptyList()

        val contactPoints = matcher.matchAndExtract(
            classMask = classMask,
            width = width,
            height = height,
            detections = detections,
        )

        val candidates = mutableListOf<GroundContactCandidate>()
        for (cp in contactPoints) {
            val xNorm = (cp.contactX + 0.5f) / width.toFloat()
            val yNorm = (cp.contactY + 0.5f) / height.toFloat()

            // Map to full uncropped upright coordinate space
            val fullUNorm = (cropLeftNorm + xNorm * cropWidthNorm).coerceIn(0f, 1f)
            val fullVNorm = (cropTopNorm + yNorm * cropHeightNorm).coerceIn(0f, 1f)

            val intersection = if (cameraHeightMeters != null && upVector != null && !cp.isBottomClipped && !cp.isFloatingUnsupported) {
                val ray = if (fullCameraModel != null) {
                    CameraGeometry.computeRayFromFullUprightNorm(fullUNorm, fullVNorm, fullCameraModel)
                } else if (effectiveFov != null) {
                    CameraGeometry.computeCameraRay(xNorm, yNorm, effectiveFov.hfovDegrees, effectiveFov.vfovDegrees)
                } else null

                if (ray != null) {
                    CameraGeometry.intersectGroundPlane(ray, cameraHeightMeters, upVector)
                } else null
            } else null

            candidates.add(
                GroundContactCandidate(
                    classId = cp.classId,
                    pixelCount = cp.pixelCount,
                    xNorm = xNorm,
                    yNorm = yNorm,
                    fullUNorm = fullUNorm,
                    fullVNorm = fullVNorm,
                    isBottomClipped = cp.isBottomClipped,
                    isFloatingUnsupported = cp.isFloatingUnsupported,
                    groundSupportPixels = cp.groundSupportPixels,
                    intersection = intersection,
                    associatedDetection = cp.associatedDetection,
                )
            )
        }

        return candidates
    }

    companion object {
        const val CORRIDOR_HALF_WIDTH_METERS = 0.45f
        const val MIN_CLUSTER_PIXELS = 20
        const val SUPPORT_SEARCH_DEPTH_PIXELS = 4
        const val MIN_GROUND_SUPPORT_PIXELS = 3
        val TARGET_CLASSES = setOf(4, 5, 6) // person=4, vehicle=5, pole_sign=6
        val GROUND_CLASSES = setOf(1, 2, 3) // road=1, sidewalk=2, crosswalk=3
    }
}
