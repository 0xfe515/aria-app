package com.ariateam.aria.distance

import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Tracks and estimates the floor plane height relative to the camera.
 *
 * Requirements:
 * - Must be a live tracked horizontal upward-facing plane below the camera.
 * - Filters tables and raised furniture by checking vertical offset (offset <= -0.6m) and proximity.
 *   Note on Table Ambiguity: While elevated surfaces (> -0.6m) are rejected, low furniture (e.g.,
 *   coffee tables or low stools at ~0.4m height) may produce horizontal planes. The tracker relies
 *   on lowest world elevation and route proximity, but users should confirm clear floor paths.
 * - Smooths world-space floor elevation (rather than camera-relative offset) to prevent lag
 *   when the user changes phone height (e.g. raising or lowering arms).
 */
class FloorPlaneTracker(
    private val minFloorAreaSqMeters: Float = 0.20f,
    private val minVerticalOffsetBelowCamera: Float = -2.50f,
    private val maxVerticalOffsetBelowCamera: Float = -0.60f,
    private val maxRouteDistanceMeters: Float = 4.50f,
    private val smoothingFactor: Float = 0.20f,
) {
    /**
     * Decoupled descriptor for plane geometry and tracking attributes.
     * Enables comprehensive Android-independent JVM unit testing.
     */
    data class PlaneDescriptor(
        val isHorizontalUpward: Boolean,
        val isTracking: Boolean,
        val isSubsumed: Boolean,
        val extentX: Float,
        val extentZ: Float,
        val centerX: Float,
        val centerY: Float,
        val centerZ: Float,
    )

    data class FloorEstimate(
        val groundY: Float?,
        val candidatePlaneCount: Int,
        val areaSqMeters: Float,
        val worldFloorElevation: Float? = null,
    )

    private var smoothedWorldFloorElevation: Float? = null

    /**
     * Evaluates active ARCore planes against current camera pose.
     */
    fun evaluate(planes: Collection<Plane>, cameraPose: Pose?): FloorEstimate {
        if (cameraPose == null) {
            smoothedWorldFloorElevation = null
            return FloorEstimate(null, 0, 0f, null)
        }

        val descriptors = planes.map { plane ->
            PlaneDescriptor(
                isHorizontalUpward = plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING,
                isTracking = plane.trackingState == TrackingState.TRACKING,
                isSubsumed = plane.subsumedBy != null,
                extentX = plane.extentX,
                extentZ = plane.extentZ,
                centerX = plane.centerPose.tx(),
                centerY = plane.centerPose.ty(),
                centerZ = plane.centerPose.tz(),
            )
        }

        return evaluateDescriptors(
            descriptors = descriptors,
            cameraX = cameraPose.tx(),
            cameraY = cameraPose.ty(),
            cameraZ = cameraPose.tz(),
        )
    }

    /**
     * Pure-logic evaluation of plane descriptors against camera position.
     */
    fun evaluateDescriptors(
        descriptors: Collection<PlaneDescriptor>,
        cameraX: Float,
        cameraY: Float,
        cameraZ: Float,
    ): FloorEstimate {
        val validCandidates = descriptors.mapNotNull { plane ->
            if (plane.isSubsumed || !plane.isHorizontalUpward || !plane.isTracking) {
                return@mapNotNull null
            }
            val area = plane.extentX * plane.extentZ
            if (area < minFloorAreaSqMeters) return@mapNotNull null

            // Distance to route/camera
            val distToCamera = hypot(plane.centerX - cameraX, plane.centerZ - cameraZ)
            if (distToCamera > maxRouteDistanceMeters) return@mapNotNull null

            val verticalOffset = plane.centerY - cameraY // Negative value when plane is below camera
            if (verticalOffset in minVerticalOffsetBelowCamera..maxVerticalOffsetBelowCamera) {
                Candidate(
                    worldElevation = plane.centerY,
                    verticalOffset = verticalOffset,
                    area = area,
                )
            } else {
                null
            }
        }

        if (validCandidates.isEmpty()) {
            smoothedWorldFloorElevation = null
            return FloorEstimate(
                groundY = null,
                candidatePlaneCount = 0,
                areaSqMeters = 0f,
                worldFloorElevation = null,
            )
        }

        // Select the candidate with the lowest world elevation (actual floor vs raised surfaces)
        val best = validCandidates.minByOrNull { it.worldElevation }!!

        val rawWorldElevation = best.worldElevation
        val currentSmoothed = smoothedWorldFloorElevation
        val newSmoothed = if (currentSmoothed == null) {
            rawWorldElevation
        } else {
            // Discontinuous jump threshold (e.g. going down a step)
            if (abs(rawWorldElevation - currentSmoothed) > 0.35f) {
                rawWorldElevation
            } else {
                currentSmoothed * (1f - smoothingFactor) + rawWorldElevation * smoothingFactor
            }
        }
        smoothedWorldFloorElevation = newSmoothed

        // Camera-relative ground offset: calculated dynamically each frame against live cameraY
        val currentGroundY = newSmoothed - cameraY

        return FloorEstimate(
            groundY = currentGroundY,
            candidatePlaneCount = validCandidates.size,
            areaSqMeters = best.area,
            worldFloorElevation = newSmoothed,
        )
    }

    fun reset() {
        smoothedWorldFloorElevation = null
    }

    private data class Candidate(
        val worldElevation: Float,
        val verticalOffset: Float,
        val area: Float,
    )
}
