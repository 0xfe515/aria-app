package com.ariateam.aria.distance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [FloorPlaneTracker] plane filtering, table rejection, and world elevation tracking.
 */
class FloorPlaneTrackerTest {

    private val tracker = FloorPlaneTracker(
        minFloorAreaSqMeters = 0.20f,
        minVerticalOffsetBelowCamera = -2.50f,
        maxVerticalOffsetBelowCamera = -0.60f,
        maxRouteDistanceMeters = 4.50f,
        smoothingFactor = 0.20f,
    )

    @Test
    fun testValidFloorPlaneIsSelected() {
        // Camera at height 1.40m, at origin (0, 1.40, 0)
        val cameraX = 0f
        val cameraY = 1.40f
        val cameraZ = 0f

        // Plane 1: Floor at height 0.05m, 1.5m ahead
        val floorPlane = FloorPlaneTracker.PlaneDescriptor(
            isHorizontalUpward = true,
            isTracking = true,
            isSubsumed = false,
            extentX = 1.5f,
            extentZ = 2.0f,
            centerX = 0f,
            centerY = 0.05f,
            centerZ = -1.5f,
        )

        val estimate = tracker.evaluateDescriptors(listOf(floorPlane), cameraX, cameraY, cameraZ)
        assertNotNull(estimate.groundY)
        assertEquals(-1.35f, estimate.groundY!!, 1e-4f)
        assertEquals(1, estimate.candidatePlaneCount)
        assertEquals(3.0f, estimate.areaSqMeters, 1e-4f)
    }

    @Test
    fun testTableIsRejectedByVerticalOffset() {
        val cameraX = 0f
        val cameraY = 1.20f
        val cameraZ = 0f

        // Table surface at height 0.75m (offset = 0.75 - 1.20 = -0.45m > -0.60m)
        val tablePlane = FloorPlaneTracker.PlaneDescriptor(
            isHorizontalUpward = true,
            isTracking = true,
            isSubsumed = false,
            extentX = 1.0f,
            extentZ = 1.2f,
            centerX = 0f,
            centerY = 0.75f,
            centerZ = -1.0f,
        )

        val estimate = tracker.evaluateDescriptors(listOf(tablePlane), cameraX, cameraY, cameraZ)
        assertNull("Table with vertical offset > -0.60m must be rejected", estimate.groundY)
        assertEquals(0, estimate.candidatePlaneCount)
    }

    @Test
    fun testLowestWorldElevationIsSelectedWhenBothTableAndFloorArePresent() {
        val cameraX = 0f
        val cameraY = 1.50f
        val cameraZ = 0f

        // Floor at world elevation 0.10m
        val floorPlane = FloorPlaneTracker.PlaneDescriptor(
            isHorizontalUpward = true,
            isTracking = true,
            isSubsumed = false,
            extentX = 2.0f,
            extentZ = 3.0f,
            centerX = 0f,
            centerY = 0.10f,
            centerZ = -2.0f,
        )

        // Low bench at world elevation 0.80m (offset = 0.80 - 1.50 = -0.70m)
        val benchPlane = FloorPlaneTracker.PlaneDescriptor(
            isHorizontalUpward = true,
            isTracking = true,
            isSubsumed = false,
            extentX = 0.6f,
            extentZ = 1.0f,
            centerX = 0f,
            centerY = 0.80f,
            centerZ = -1.0f,
        )

        val estimate = tracker.evaluateDescriptors(listOf(floorPlane, benchPlane), cameraX, cameraY, cameraZ)
        assertNotNull(estimate.groundY)
        assertEquals(2, estimate.candidatePlaneCount)
        // Must select the lowest world elevation plane (0.10m -> groundY = 0.10 - 1.50 = -1.40m)
        assertEquals(-1.40f, estimate.groundY!!, 1e-4f)
    }

    @Test
    fun testSubsumedPlaneIsIgnored() {
        val cameraX = 0f
        val cameraY = 1.40f
        val cameraZ = 0f

        val subsumedPlane = FloorPlaneTracker.PlaneDescriptor(
            isHorizontalUpward = true,
            isTracking = true,
            isSubsumed = true,
            extentX = 2.0f,
            extentZ = 2.0f,
            centerX = 0f,
            centerY = 0.0f,
            centerZ = -1.0f,
        )

        val estimate = tracker.evaluateDescriptors(listOf(subsumedPlane), cameraX, cameraY, cameraZ)
        assertNull(estimate.groundY)
        assertEquals(0, estimate.candidatePlaneCount)
    }

    @Test
    fun testDistantPlaneOutsideRouteIsRejected() {
        val cameraX = 0f
        val cameraY = 1.40f
        val cameraZ = 0f

        // Plane 10m away (> maxRouteDistanceMeters 4.5m)
        val distantPlane = FloorPlaneTracker.PlaneDescriptor(
            isHorizontalUpward = true,
            isTracking = true,
            isSubsumed = false,
            extentX = 2.0f,
            extentZ = 2.0f,
            centerX = 0f,
            centerY = 0.0f,
            centerZ = -10.0f,
        )

        val estimate = tracker.evaluateDescriptors(listOf(distantPlane), cameraX, cameraY, cameraZ)
        assertNull("Plane outside route proximity must be rejected", estimate.groundY)
    }

    @Test
    fun testPhoneHeightChangeImmediatelyUpdatesGroundYWithoutSmoothingLag() {
        val cameraX = 0f
        var cameraY = 1.40f // Chest height
        val cameraZ = 0f

        val floorPlane = FloorPlaneTracker.PlaneDescriptor(
            isHorizontalUpward = true,
            isTracking = true,
            isSubsumed = false,
            extentX = 2.0f,
            extentZ = 2.0f,
            centerX = 0f,
            centerY = 0.0f, // Floor is at world height 0.0m
            centerZ = -1.5f,
        )

        // Initial frame: groundY = 0.0 - 1.40 = -1.40m
        val est1 = tracker.evaluateDescriptors(listOf(floorPlane), cameraX, cameraY, cameraZ)
        assertEquals(-1.40f, est1.groundY!!, 1e-4f)

        // User lowers arms to waist height (cameraY = 1.00m) while floor elevation stays 0.0m
        cameraY = 1.00f
        val est2 = tracker.evaluateDescriptors(listOf(floorPlane), cameraX, cameraY, cameraZ)
        // groundY must instantaneously reflect -1.00m without being dragged by camera-offset smoothing!
        assertEquals(-1.00f, est2.groundY!!, 1e-4f)
    }
}
