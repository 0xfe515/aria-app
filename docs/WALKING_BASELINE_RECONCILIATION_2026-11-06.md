# Distance baseline reconciliation (2026-11-06)

## Inspection

The tracked `DistanceViewModel.kt` contains the production `DistanceSetupStage`
and `DistanceUiState` declarations. The untracked `DistanceUiState.kt` is an
exact duplicate, and in-scope production callers use those shared types without
requiring a separate declaration.

The production `FloorPlaneTracker.kt` has the ARCore adapter and the pure
`evaluateDescriptors` implementation. The untracked test-source
`FloorPlaneTracker.kt` duplicates the pure implementation, but omits the
production ARCore adapter. All six existing `FloorPlaneTrackerTest` cases use
the same `PlaneDescriptor` and `evaluateDescriptors` API exposed by production,
so removing the test duplicate makes them exercise the actual production class
without changing their regression coverage.

## Reconciliation result

Retained the production declarations in `DistanceViewModel.kt` and production
`FloorPlaneTracker.kt` unchanged. Removed the duplicate untracked
`DistanceUiState.kt` and test-source `FloorPlaneTracker.kt`; kept all six
`FloorPlaneTrackerTest` cases unchanged so they resolve to the production class.
No Gradle task was run because the coordinator retained the separate build lease.

## Validation

- `git diff --check` — exit 0.
- Structural search found one `DistanceSetupStage`, one `DistanceUiState`, one
  production `FloorPlaneTracker`, and all six tracker test methods.
- JVM tests, assemble, lint, and androidTest compilation remain for the
  coordinator's authorized validation pass.

## Preserved original duplicate contents

The following are verbatim snapshots of both untracked files before removal.

### `app/src/main/java/com/ariateam/aria/distance/DistanceUiState.kt`

```kotlin
package com.ariateam.aria.distance

enum class DistanceSetupStage {
    CHECKING,
    INITIALIZING,
    FLOOR_SEARCH,
    TRACKING,
    ERROR_AR_UNAVAILABLE,
    ERROR_INSTALL_REQUIRED,
    ERROR_DEPTH_UNSUPPORTED,
    ERROR_PERMISSION,
}

data class DistanceUiState(
    val status: DistanceStatus = DistanceStatus.PREPARING,
    val risk: DistanceRisk = DistanceRisk.UNKNOWN,
    val distanceMeters: Float? = null,
    val validSampleCount: Int = 0,
    val message: String = "",
    val groundOffsetMeters: Float? = null,
    val floorDetected: Boolean = false,
    val setupStage: DistanceSetupStage = DistanceSetupStage.CHECKING,
    val errorMessage: String? = null,
    val rawDepthActive: Boolean = false,
    val showOnboarding: Boolean = false,
    val isMonocularMode: Boolean = true,
    val profileConfigured: Boolean = false,
    val sensorTrackingActive: Boolean = false,
    val modelReady: Boolean = false,
)
```

### `app/src/test/java/com/ariateam/aria/distance/FloorPlaneTracker.kt`

```kotlin
package com.ariateam.aria.distance

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Pure geometry test fixture preserving [FloorPlaneTracker] plane evaluation logic
 * without production ARCore dependencies.
 *
 * Tracks and estimates the floor plane height relative to the camera.
 *
 * Requirements:
 * - Must be a live tracked horizontal upward-facing plane below the camera.
 * - Filters tables and raised furniture by checking vertical offset (offset <= -0.6m) and proximity.
 * - Smooths world-space floor elevation (rather than camera-relative offset) to prevent lag
 *   when the user changes phone height.
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
```
