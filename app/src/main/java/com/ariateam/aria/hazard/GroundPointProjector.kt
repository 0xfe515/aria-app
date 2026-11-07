package com.ariateam.aria.hazard

import com.ariateam.aria.distance.BodyMountProfile
import com.ariateam.aria.distance.GroundContactOrientation

/**
 * Functional interface for projecting normalized upright crop coordinates to a 3D ground intersection.
 */
fun interface PointProjector {
    fun project(xNorm: Float, yNorm: Float): GroundIntersection?
}

/**
 * Pure JVM ground point distance projector.
 *
 * Maps normalized coordinates [0.0, 1.0] to metric ground intersections via camera geometry,
 * lens intrinsics/models, body mount stature profiles, and orientation sensors.
 */
open class GroundPointProjector {

    companion object {
        /**
         * Projects a normalized 2D point ([xNorm], [yNorm]) to a horizontal ground plane intersection.
         *
         * @param xNorm Normalized X coordinate in crop space [0f, 1f].
         * @param yNorm Normalized Y coordinate in crop space [0f, 1f].
         * @param profile User body mount profile for estimated camera height.
         * @param orientation Monocular IMU orientation snapshot containing upright camera UP vector.
         * @param effectiveFov Effective crop field of view (fallback if [fullCameraModel] is null).
         * @param fullCameraModel Full uncropped upright camera sensor model (preferred).
         * @param cropLeftNorm Normalized crop rect left [0f, 1f].
         * @param cropTopNorm Normalized crop rect top [0f, 1f].
         * @param cropWidthNorm Normalized crop rect width (0f, 1f].
         * @param cropHeightNorm Normalized crop rect height (0f, 1f].
         * @return [GroundIntersection] if the projected ray hits the ground within walking bounds, or null.
         */
        fun project(
            xNorm: Float,
            yNorm: Float,
            profile: BodyMountProfile?,
            orientation: GroundContactOrientation?,
            effectiveFov: EffectiveFov?,
            fullCameraModel: FullUprightCameraModel?,
            cropLeftNorm: Float = 0f,
            cropTopNorm: Float = 0f,
            cropWidthNorm: Float = 1f,
            cropHeightNorm: Float = 1f,
        ): GroundIntersection? {
            if (!xNorm.isFinite() || xNorm !in 0f..1f) return null
            if (!yNorm.isFinite() || yNorm !in 0f..1f) return null
            if (!cropLeftNorm.isFinite() || cropLeftNorm !in 0f..1f) return null
            if (!cropTopNorm.isFinite() || cropTopNorm !in 0f..1f) return null
            if (!cropWidthNorm.isFinite() || cropWidthNorm !in 0f..1f || cropWidthNorm <= 0f) return null
            if (!cropHeightNorm.isFinite() || cropHeightNorm !in 0f..1f || cropHeightNorm <= 0f) return null

            if (profile == null || !profile.isValid) return null
            val cameraHeightMeters = profile.estimatedCameraHeightMeters
            if (cameraHeightMeters == null || !cameraHeightMeters.isFinite() || cameraHeightMeters <= 0f) return null

            if (orientation == null || !orientation.isReliable) return null

            if (effectiveFov == null && fullCameraModel == null) return null

            val rawRay: Vector3D? = if (fullCameraModel != null) {
                val fullUNorm = (cropLeftNorm + xNorm * cropWidthNorm).coerceIn(0f, 1f)
                val fullVNorm = (cropTopNorm + yNorm * cropHeightNorm).coerceIn(0f, 1f)
                CameraGeometry.computeRayFromFullUprightNorm(fullUNorm, fullVNorm, fullCameraModel)
            } else if (effectiveFov != null) {
                CameraGeometry.computeCameraRay(xNorm, yNorm, effectiveFov.hfovDegrees, effectiveFov.vfovDegrees)
            } else {
                null
            }
            val ray: Vector3D = rawRay ?: return null
            val upVec: Vector3D = orientation.upVector ?: return null

            return CameraGeometry.intersectGroundPlane(
                ray = ray,
                cameraHeightMeters = cameraHeightMeters,
                upVector = upVec,
            )
        }

        /**
         * Binds geometry and sensor state into a per-frame [PointProjector] closure.
         */
        fun bind(
            profile: BodyMountProfile?,
            orientation: GroundContactOrientation?,
            effectiveFov: EffectiveFov?,
            fullCameraModel: FullUprightCameraModel?,
            cropLeftNorm: Float = 0f,
            cropTopNorm: Float = 0f,
            cropWidthNorm: Float = 1f,
            cropHeightNorm: Float = 1f,
        ): PointProjector = PointProjector { xNorm, yNorm ->
            project(
                xNorm = xNorm,
                yNorm = yNorm,
                profile = profile,
                orientation = orientation,
                effectiveFov = effectiveFov,
                fullCameraModel = fullCameraModel,
                cropLeftNorm = cropLeftNorm,
                cropTopNorm = cropTopNorm,
                cropWidthNorm = cropWidthNorm,
                cropHeightNorm = cropHeightNorm,
            )
        }
    }

    fun project(
        xNorm: Float,
        yNorm: Float,
        profile: BodyMountProfile?,
        orientation: GroundContactOrientation?,
        effectiveFov: EffectiveFov?,
        fullCameraModel: FullUprightCameraModel?,
        cropLeftNorm: Float = 0f,
        cropTopNorm: Float = 0f,
        cropWidthNorm: Float = 1f,
        cropHeightNorm: Float = 1f,
    ): GroundIntersection? = Companion.project(
        xNorm = xNorm,
        yNorm = yNorm,
        profile = profile,
        orientation = orientation,
        effectiveFov = effectiveFov,
        fullCameraModel = fullCameraModel,
        cropLeftNorm = cropLeftNorm,
        cropTopNorm = cropTopNorm,
        cropWidthNorm = cropWidthNorm,
        cropHeightNorm = cropHeightNorm,
    )

    fun bind(
        profile: BodyMountProfile?,
        orientation: GroundContactOrientation?,
        effectiveFov: EffectiveFov?,
        fullCameraModel: FullUprightCameraModel?,
        cropLeftNorm: Float = 0f,
        cropTopNorm: Float = 0f,
        cropWidthNorm: Float = 1f,
        cropHeightNorm: Float = 1f,
    ): PointProjector = Companion.bind(
        profile = profile,
        orientation = orientation,
        effectiveFov = effectiveFov,
        fullCameraModel = fullCameraModel,
        cropLeftNorm = cropLeftNorm,
        cropTopNorm = cropTopNorm,
        cropWidthNorm = cropWidthNorm,
        cropHeightNorm = cropHeightNorm,
    )
}
