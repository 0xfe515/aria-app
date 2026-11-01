package com.ariateam.aria.hazard

import kotlin.math.atan
import kotlin.math.sqrt

/**
 * Physical lens and sensor intrinsic parameters from Camera2 metadata.
 */
data class CameraIntrinsics(
    val focalLengthMm: Float,
    val sensorWidthMm: Float,
    val sensorHeightMm: Float,
    val activeArrayWidth: Int = 0,
    val activeArrayHeight: Int = 0,
) {
    val isValid: Boolean
        get() = focalLengthMm > 0f && focalLengthMm.isFinite() &&
                sensorWidthMm > 0f && sensorWidthMm.isFinite() &&
                sensorHeightMm > 0f && sensorHeightMm.isFinite()
}

/**
 * Effective upright horizontal and vertical field of view in degrees.
 */
data class EffectiveFov(
    val hfovDegrees: Float,
    val vfovDegrees: Float,
)

/**
 * Full uncropped upright camera sensor geometry model.
 *
 * Maps full-frame upright coordinates to physical angles free of symmetric-crop assumptions,
 * correctly supporting bottom-aligned crops (LEGACY_CROP) and letterboxed frames.
 */
data class FullUprightCameraModel(
    val focalLengthMm: Float,
    val uprightSensorWidthMm: Float,
    val uprightSensorHeightMm: Float,
    val fullHfovDegrees: Float,
    val fullVfovDegrees: Float,
)

/**
 * Pure 3D vector representation for camera ray and ground plane geometry.
 * Free of Android framework dependencies for 100% JVM unit testability.
 */
data class Vector3D(
    val x: Float,
    val y: Float,
    val z: Float,
) {
    val isFinite: Boolean
        get() = x.isFinite() && y.isFinite() && z.isFinite()

    fun dot(other: Vector3D): Float = x * other.x + y * other.y + z * other.z

    fun cross(other: Vector3D): Vector3D = Vector3D(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )

    fun length(): Float = sqrt(x * x + y * y + z * z)

    fun normalize(): Vector3D? {
        if (!isFinite) return null
        val len = length()
        return if (len.isFinite() && len > 1e-6f) Vector3D(x / len, y / len, z / len) else null
    }

    operator fun plus(other: Vector3D): Vector3D = Vector3D(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vector3D): Vector3D = Vector3D(x - other.x, y - other.y, z - other.z)
    operator fun times(scalar: Float): Vector3D = Vector3D(x * scalar, y * scalar, z * scalar)
}

/**
 * Computed intersection between a camera ray and a horizontal ground plane.
 *
 * @property forwardDistanceMeters Distance ahead in the user's forward walking direction along the ground plane.
 * @property lateralDistanceMeters Lateral offset (+ right, - left) relative to the forward path.
 * @property totalDistanceMeters Planar Euclidean distance on the ground from the camera nadir ground point.
 * @property rayDistanceMeters 3D Euclidean distance from camera lens to the intersection point.
 * @property intersectionPoint 3D point in camera coordinates.
 */
data class GroundIntersection(
    val forwardDistanceMeters: Float,
    val lateralDistanceMeters: Float,
    val totalDistanceMeters: Float,
    val rayDistanceMeters: Float,
    val intersectionPoint: Vector3D,
)

/**
 * Deterministic camera geometry and orientation-aware field of view helper.
 * Free of Android framework dependencies for 100% JVM unit testability.
 */
object CameraGeometry {

    /**
     * Derives effective upright horizontal field of view (in degrees) for a model input
     * given camera intrinsics, camera buffer dimensions, display/sensor rotation,
     * and upright crop dimensions.
     */
    fun computeEffectiveUprightHfovDegrees(
        intrinsics: CameraIntrinsics?,
        bufferWidth: Int,
        bufferHeight: Int,
        rotationDegrees: Int,
        cropWidth: Int,
        uprightWidth: Int,
    ): Float? {
        if (intrinsics == null || !intrinsics.isValid) return null
        if (bufferWidth <= 0 || bufferHeight <= 0) return null
        if (rotationDegrees !in setOf(0, 90, 180, 270)) return null
        if (cropWidth <= 0 || uprightWidth <= 0 || cropWidth > uprightWidth) return null

        val f = intrinsics.focalLengthMm
        val sw = intrinsics.sensorWidthMm
        val sh = intrinsics.sensorHeightMm

        val sensorAspect = if (intrinsics.activeArrayWidth > 0 && intrinsics.activeArrayHeight > 0) {
            intrinsics.activeArrayWidth.toDouble() / intrinsics.activeArrayHeight.toDouble()
        } else {
            sw.toDouble() / sh.toDouble()
        }
        val bufferAspect = bufferWidth.toDouble() / bufferHeight.toDouble()

        val usedSensorWidth: Double
        val usedSensorHeight: Double
        if (bufferAspect > sensorAspect + 1e-4) {
            usedSensorWidth = sw.toDouble()
            usedSensorHeight = sw.toDouble() / bufferAspect
        } else if (bufferAspect < sensorAspect - 1e-4) {
            usedSensorWidth = sh.toDouble() * bufferAspect
            usedSensorHeight = sh.toDouble()
        } else {
            usedSensorWidth = sw.toDouble()
            usedSensorHeight = sh.toDouble()
        }

        val uprightSensorWidthMm = when (rotationDegrees) {
            90, 270 -> usedSensorHeight
            else -> usedSensorWidth
        }

        val cropScaleX = cropWidth.toDouble() / uprightWidth.toDouble()
        val effectiveModelSensorWidthMm = uprightSensorWidthMm * cropScaleX

        val hfovRad = 2.0 * atan(effectiveModelSensorWidthMm / (2.0 * f))
        val hfovDeg = Math.toDegrees(hfovRad).toFloat()

        if (!hfovDeg.isFinite() || hfovDeg <= 5f || hfovDeg >= 170f) {
            return null
        }
        return hfovDeg
    }

    /**
     * Derives both effective upright horizontal and vertical field of view (in degrees)
     * given camera intrinsics, buffer dimensions, rotation, and crop geometry.
     */
    fun computeEffectiveUprightFov(
        intrinsics: CameraIntrinsics?,
        bufferWidth: Int,
        bufferHeight: Int,
        rotationDegrees: Int,
        cropWidth: Int,
        cropHeight: Int,
        uprightWidth: Int,
        uprightHeight: Int,
    ): EffectiveFov? {
        if (intrinsics == null || !intrinsics.isValid) return null
        if (bufferWidth <= 0 || bufferHeight <= 0) return null
        if (rotationDegrees !in setOf(0, 90, 180, 270)) return null
        if (cropWidth <= 0 || cropHeight <= 0 || uprightWidth <= 0 || uprightHeight <= 0) return null
        if (cropWidth > uprightWidth || cropHeight > uprightHeight) return null

        val f = intrinsics.focalLengthMm
        val sw = intrinsics.sensorWidthMm
        val sh = intrinsics.sensorHeightMm

        val sensorAspect = if (intrinsics.activeArrayWidth > 0 && intrinsics.activeArrayHeight > 0) {
            intrinsics.activeArrayWidth.toDouble() / intrinsics.activeArrayHeight.toDouble()
        } else {
            sw.toDouble() / sh.toDouble()
        }
        val bufferAspect = bufferWidth.toDouble() / bufferHeight.toDouble()

        val usedSensorWidth: Double
        val usedSensorHeight: Double
        if (bufferAspect > sensorAspect + 1e-4) {
            usedSensorWidth = sw.toDouble()
            usedSensorHeight = sw.toDouble() / bufferAspect
        } else if (bufferAspect < sensorAspect - 1e-4) {
            usedSensorWidth = sh.toDouble() * bufferAspect
            usedSensorHeight = sh.toDouble()
        } else {
            usedSensorWidth = sw.toDouble()
            usedSensorHeight = sh.toDouble()
        }

        val uprightSensorWidthMm = when (rotationDegrees) {
            90, 270 -> usedSensorHeight
            else -> usedSensorWidth
        }
        val uprightSensorHeightMm = when (rotationDegrees) {
            90, 270 -> usedSensorWidth
            else -> usedSensorHeight
        }

        val cropScaleX = cropWidth.toDouble() / uprightWidth.toDouble()
        val cropScaleY = cropHeight.toDouble() / uprightHeight.toDouble()

        val effectiveModelSensorWidthMm = uprightSensorWidthMm * cropScaleX
        val effectiveModelSensorHeightMm = uprightSensorHeightMm * cropScaleY

        val hfovRad = 2.0 * atan(effectiveModelSensorWidthMm / (2.0 * f))
        val vfovRad = 2.0 * atan(effectiveModelSensorHeightMm / (2.0 * f))

        val hfovDeg = Math.toDegrees(hfovRad).toFloat()
        val vfovDeg = Math.toDegrees(vfovRad).toFloat()

        if (!hfovDeg.isFinite() || hfovDeg <= 5f || hfovDeg >= 170f) return null
        if (!vfovDeg.isFinite() || vfovDeg <= 5f || vfovDeg >= 170f) return null

        return EffectiveFov(hfovDegrees = hfovDeg, vfovDegrees = vfovDeg)
    }

    /**
     * Builds full-frame upright camera sensor model for mapping arbitrary crop coordinates back to
     * full lens optical angles without assuming symmetric crop.
     */
    fun computeFullUprightCameraModel(
        intrinsics: CameraIntrinsics?,
        bufferWidth: Int,
        bufferHeight: Int,
        rotationDegrees: Int,
    ): FullUprightCameraModel? {
        if (intrinsics == null || !intrinsics.isValid) return null
        if (bufferWidth <= 0 || bufferHeight <= 0) return null
        if (rotationDegrees !in setOf(0, 90, 180, 270)) return null

        val f = intrinsics.focalLengthMm
        val sw = intrinsics.sensorWidthMm
        val sh = intrinsics.sensorHeightMm

        val sensorAspect = if (intrinsics.activeArrayWidth > 0 && intrinsics.activeArrayHeight > 0) {
            intrinsics.activeArrayWidth.toDouble() / intrinsics.activeArrayHeight.toDouble()
        } else {
            sw.toDouble() / sh.toDouble()
        }
        val bufferAspect = bufferWidth.toDouble() / bufferHeight.toDouble()

        val usedSensorWidth: Double
        val usedSensorHeight: Double
        if (bufferAspect > sensorAspect + 1e-4) {
            usedSensorWidth = sw.toDouble()
            usedSensorHeight = sw.toDouble() / bufferAspect
        } else if (bufferAspect < sensorAspect - 1e-4) {
            usedSensorWidth = sh.toDouble() * bufferAspect
            usedSensorHeight = sh.toDouble()
        } else {
            usedSensorWidth = sw.toDouble()
            usedSensorHeight = sh.toDouble()
        }

        val uprightSensorWidthMm = when (rotationDegrees) {
            90, 270 -> usedSensorHeight
            else -> usedSensorWidth
        }.toFloat()
        val uprightSensorHeightMm = when (rotationDegrees) {
            90, 270 -> usedSensorWidth
            else -> usedSensorHeight
        }.toFloat()

        val fullHfovRad = 2.0 * atan(uprightSensorWidthMm.toDouble() / (2.0 * f))
        val fullVfovRad = 2.0 * atan(uprightSensorHeightMm.toDouble() / (2.0 * f))

        val hfovDeg = Math.toDegrees(fullHfovRad).toFloat()
        val vfovDeg = Math.toDegrees(fullVfovRad).toFloat()

        if (!hfovDeg.isFinite() || hfovDeg <= 5f || hfovDeg >= 170f) return null
        if (!vfovDeg.isFinite() || vfovDeg <= 5f || vfovDeg >= 170f) return null

        return FullUprightCameraModel(
            focalLengthMm = f,
            uprightSensorWidthMm = uprightSensorWidthMm,
            uprightSensorHeightMm = uprightSensorHeightMm,
            fullHfovDegrees = hfovDeg,
            fullVfovDegrees = vfovDeg,
        )
    }

    /**
     * Computes the normalized camera ray direction vector in upright camera coordinates
     * (+X right, +Y down, +Z forward along optical axis) given normalized full-frame coordinates [0, 1].
     */
    fun computeRayFromFullUprightNorm(
        fullUNorm: Float,
        fullVNorm: Float,
        cameraModel: FullUprightCameraModel,
    ): Vector3D? {
        if (!fullUNorm.isFinite() || !fullVNorm.isFinite()) return null
        if (fullUNorm !in 0f..1f || fullVNorm !in 0f..1f) return null

        val x = (fullUNorm - 0.5f) * (cameraModel.uprightSensorWidthMm / cameraModel.focalLengthMm)
        val y = (fullVNorm - 0.5f) * (cameraModel.uprightSensorHeightMm / cameraModel.focalLengthMm)
        val z = 1.0f

        val len = sqrt(x * x + y * y + z * z)
        if (!len.isFinite() || len < 1e-4f) return null
        return Vector3D(x / len, y / len, z / len)
    }

    /**
     * Computes the normalized camera ray direction vector in upright camera coordinates
     * (+X right, +Y down, +Z forward along optical axis) for normalized coordinates (uNorm, vNorm) in [0, 1].
     */
    fun computeCameraRay(
        uNorm: Float,
        vNorm: Float,
        hfovDegrees: Float,
        vfovDegrees: Float,
    ): Vector3D {
        val halfHfovRad = Math.toRadians(hfovDegrees / 2.0)
        val halfVfovRad = Math.toRadians(vfovDegrees / 2.0)
        val x = ((uNorm - 0.5f) * 2.0f * kotlin.math.tan(halfHfovRad)).toFloat()
        val y = ((vNorm - 0.5f) * 2.0f * kotlin.math.tan(halfVfovRad)).toFloat()
        val z = 1.0f
        val len = sqrt(x * x + y * y + z * z)
        return if (len > 1e-6f) Vector3D(x / len, y / len, z / len) else Vector3D(0f, 0f, 1f)
    }

    /**
     * Intersects a camera ray with a horizontal ground plane at height [cameraHeightMeters] below the camera,
     * given [upVector] (the unit vector pointing upward away from the ground in camera coordinates).
     *
     * Returns null if:
     * - Vector is non-finite or degenerate.
     * - Ray points above horizon or is parallel to the ground (denom >= -0.0175f, approx < 1 deg depression).
     * - Computed distance is negative, non-finite, or outside physical walking bounds.
     */
    fun intersectGroundPlane(
        ray: Vector3D,
        cameraHeightMeters: Float,
        upVector: Vector3D,
    ): GroundIntersection? {
        if (!ray.isFinite || !upVector.isFinite) return null
        if (!cameraHeightMeters.isFinite() || cameraHeightMeters <= 0f) return null

        val nUp = upVector.normalize() ?: return null

        val denom = ray.dot(nUp)
        // Ray must point downward toward the ground (opposite to nUp, so denom < 0)
        // Reject rays nearly parallel to horizon (denom >= -0.0175f, depression angle < ~1 degree)
        if (denom >= -0.0175f) return null

        val t = -cameraHeightMeters / denom
        if (!t.isFinite() || t <= 0.2f || t > 50f) return null

        val p = ray * t
        // Vector on the ground plane relative to camera nadir point (-cameraHeightMeters * nUp)
        val vGround = p + (nUp * cameraHeightMeters)

        val fwdCam = Vector3D(0f, 0f, 1f)
        val fwdHorizontal = fwdCam - (nUp * fwdCam.dot(nUp))
        val fwdGround = fwdHorizontal.normalize() ?: return null
        val rightGround = fwdGround.cross(nUp).normalize() ?: return null

        val fwdDist = vGround.dot(fwdGround)
        val latDist = vGround.dot(rightGround)
        val totalDist = vGround.length()

        if (!fwdDist.isFinite() || fwdDist <= 0.1f || fwdDist > 35f) return null

        return GroundIntersection(
            forwardDistanceMeters = fwdDist,
            lateralDistanceMeters = latDist,
            totalDistanceMeters = totalDist,
            rayDistanceMeters = t,
            intersectionPoint = p,
        )
    }
}
