package com.ariateam.aria.hazard

import com.ariateam.aria.vision.od.DetectedObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectTrackerTest {

    private fun makeDet(
        classId: Int,
        score: Float = 0.8f,
        left: Float = 0.2f,
        top: Float = 0.2f,
        right: Float = 0.4f,
        bottom: Float = 0.5f,
        label: String = "obj",
    ): DetectedObject = DetectedObject(
        classId = classId,
        label = label,
        score = score,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
    )

    @Test
    fun update_kindMappingAndIgnoredDetections() {
        val tracker = ObjectTracker()

        val detections = listOf(
            makeDet(classId = 0, score = 0.9f, left = 0.1f, right = 0.2f), // PERSON
            makeDet(classId = 1, score = 0.85f, left = 0.25f, right = 0.35f), // TWO_WHEELER (bicycle)
            makeDet(classId = 3, score = 0.8f, left = 0.4f, right = 0.5f), // TWO_WHEELER (motorcycle)
            makeDet(classId = 2, score = 0.75f, left = 0.55f, right = 0.65f), // VEHICLE (car)
            makeDet(classId = 5, score = 0.7f, left = 0.7f, right = 0.8f), // VEHICLE (bus)
            makeDet(classId = 7, score = 0.65f, left = 0.85f, right = 0.95f), // VEHICLE (truck)
            makeDet(classId = 4, score = 0.95f), // Unsupported class (e.g. airplane) -> ignored
            makeDet(classId = 0, score = 0.4f), // Low score (< 0.5) -> ignored
            DetectedObject(classId = 0, label = "bad", score = 0.9f, left = 0.5f, top = 0.5f, right = 0.4f, bottom = 0.6f), // Invalid geom -> ignored
        )

        val tracks = tracker.update(
            detections = detections,
            captureNanos = 100_000_000L,
            corridor = CorridorDirection.CENTER,
            classMask = null,
        )

        assertEquals(6, tracks.size)
        assertEquals(ObjectKind.PERSON, tracks[0].kind)
        assertEquals(ObjectKind.TWO_WHEELER, tracks[1].kind)
        assertEquals(ObjectKind.TWO_WHEELER, tracks[2].kind)
        assertEquals(ObjectKind.VEHICLE, tracks[3].kind)
        assertEquals(ObjectKind.VEHICLE, tracks[4].kind)
        assertEquals(ObjectKind.VEHICLE, tracks[5].kind)
    }

    @Test
    fun update_iouContinuityVsClassSwitch() {
        val tracker = ObjectTracker(minIou = 0.3f)

        // Frame 1: Person at [0.2, 0.2, 0.4, 0.5]
        val frame1 = tracker.update(
            detections = listOf(makeDet(classId = 0, left = 0.2f, top = 0.2f, right = 0.4f, bottom = 0.5f)),
            captureNanos = 100_000_000L,
            corridor = CorridorDirection.CENTER,
            classMask = null,
        )
        assertEquals(1, frame1.size)
        assertEquals(1, frame1[0].id)
        assertEquals(ObjectKind.PERSON, frame1[0].kind)
        assertFalse(frame1[0].confirmed)

        // Frame 2: Identical bounding box, but class switched from PERSON (0) to VEHICLE (2)
        val frame2 = tracker.update(
            detections = listOf(makeDet(classId = 2, left = 0.2f, top = 0.2f, right = 0.4f, bottom = 0.5f)),
            captureNanos = 200_000_000L,
            corridor = CorridorDirection.CENTER,
            classMask = null,
        )

        // Class switch must break continuity: old PERSON track dropped, new VEHICLE track created
        assertEquals(1, frame2.size)
        assertEquals(2, frame2[0].id)
        assertEquals(ObjectKind.VEHICLE, frame2[0].kind)
        assertFalse("New track from class switch must start unconfirmed", frame2[0].confirmed)
    }

    @Test
    fun update_sameSnapshotReuseNotConfirming() {
        val tracker = ObjectTracker()

        // Frame 1
        val frame1 = tracker.update(
            detections = listOf(makeDet(classId = 0, left = 0.2f, top = 0.2f, right = 0.4f, bottom = 0.5f)),
            captureNanos = 100_000_000L,
            corridor = CorridorDirection.CENTER,
            classMask = null,
        )
        assertEquals(1, frame1.size)
        assertFalse(frame1[0].confirmed)

        // Frame 2: Same timestamp (reused OD snapshot across multiple display frames)
        val frame2 = tracker.update(
            detections = listOf(makeDet(classId = 0, left = 0.2f, top = 0.2f, right = 0.4f, bottom = 0.5f)),
            captureNanos = 100_000_000L,
            corridor = CorridorDirection.CENTER,
            classMask = null,
        )
        assertEquals(1, frame2.size)
        assertFalse("Reused snapshot must not increment observation or confirm track", frame2[0].confirmed)

        // Frame 3: New timestamp -> now confirmed
        val frame3 = tracker.update(
            detections = listOf(makeDet(classId = 0, left = 0.2f, top = 0.2f, right = 0.4f, bottom = 0.5f)),
            captureNanos = 200_000_000L,
            corridor = CorridorDirection.CENTER,
            classMask = null,
        )
        assertEquals(1, frame3.size)
        assertTrue("Second distinct accepted observation confirms track", frame3[0].confirmed)
    }

    @Test
    fun update_outOfOrderAndNonPositiveTimestampsIgnored() {
        val tracker = ObjectTracker()

        // Non-positive timestamp
        val nonPos = tracker.update(
            detections = listOf(makeDet(classId = 0)),
            captureNanos = 0L,
            corridor = CorridorDirection.CENTER,
            classMask = null,
        )
        assertTrue(nonPos.isEmpty())

        // Frame 1: accepted
        val frame1 = tracker.update(
            detections = listOf(makeDet(classId = 0)),
            captureNanos = 200_000_000L,
            corridor = CorridorDirection.CENTER,
            classMask = null,
        )
        assertEquals(1, frame1.size)
        assertEquals(1, frame1[0].id)

        // Frame 2: out of order timestamp (< 200ms)
        val outOfOrder = tracker.update(
            detections = listOf(makeDet(classId = 2)),
            captureNanos = 150_000_000L,
            corridor = CorridorDirection.CENTER,
            classMask = null,
        )
        assertEquals(1, outOfOrder.size)
        assertEquals("Out-of-order frame returns current tracks unchanged", 1, outOfOrder[0].id)
        assertEquals(ObjectKind.PERSON, outOfOrder[0].kind)
    }

    @Test
    fun update_gapResetDropsOldTracks() {
        val tracker = ObjectTracker(maxGapNanos = 700_000_000L)

        // Frame 1
        val frame1 = tracker.update(
            detections = listOf(makeDet(classId = 0, left = 0.2f, right = 0.4f)),
            captureNanos = 100_000_000L,
            corridor = CorridorDirection.CENTER,
            classMask = null,
        )
        assertEquals(1, frame1[0].id)

        // Frame 2: Gap > 700ms
        val frame2 = tracker.update(
            detections = listOf(makeDet(classId = 0, left = 0.2f, right = 0.4f)),
            captureNanos = 100_000_000L + 700_000_001L,
            corridor = CorridorDirection.CENTER,
            classMask = null,
        )

        // Old track was dropped; new track created with monotonic ID 2 and unconfirmed
        assertEquals(1, frame2.size)
        assertEquals(2, frame2[0].id)
        assertFalse(frame2[0].confirmed)
    }

    @Test
    fun idMonotonicityAcrossResetBoundaries() {
        val tracker = ObjectTracker(maxGapNanos = 500_000_000L)

        val t1 = tracker.update(listOf(makeDet(0)), 100_000_000L, CorridorDirection.CENTER, null)
        assertEquals(1, t1[0].id)

        // Gap drop -> track 2
        val t2 = tracker.update(listOf(makeDet(0)), 800_000_000L, CorridorDirection.CENTER, null)
        assertEquals(2, t2[0].id)

        // Reset
        tracker.reset()

        // After reset, ID generation restarts at 1
        val t3 = tracker.update(listOf(makeDet(0)), 100_000_000L, CorridorDirection.CENTER, null)
        assertEquals(1, t3[0].id)
    }

    @Test
    fun update_ttcApproachingRecedingAndMinObservations() {
        val tracker = ObjectTracker(ttcMinObservations = 3, ttcEmaAlpha = 0.5f)

        // Obs 1: h = 0.20, dt = 0
        val f1 = tracker.update(
            listOf(makeDet(classId = 0, top = 0.30f, bottom = 0.50f)),
            captureNanos = 100_000_000L,
            corridor = CorridorDirection.CENTER,
            classMask = null,
        )
        assertNull("TTC must be null on first observation", f1[0].ttcSeconds)

        // Obs 2: h = 0.22 (approaching, dh = 0.02 > 0), obs count = 2 (< 3)
        val f2 = tracker.update(
            listOf(makeDet(classId = 0, top = 0.30f, bottom = 0.52f)),
            captureNanos = 200_000_000L,
            corridor = CorridorDirection.CENTER,
            classMask = null,
        )
        assertNull("TTC must be null before ttcMinObservations (3)", f2[0].ttcSeconds)

        // Obs 3: h = 0.24 (approaching, dh = 0.02 > 0), obs count = 3
        val f3 = tracker.update(
            listOf(makeDet(classId = 0, top = 0.30f, bottom = 0.54f)),
            captureNanos = 300_000_000L,
            corridor = CorridorDirection.CENTER,
            classMask = null,
        )
        assertNotNull("TTC must be available when observations >= 3 and approaching", f3[0].ttcSeconds)
        val ttc = f3[0].ttcSeconds!!
        assertTrue("TTC must be within [0.1, 30] range", ttc in 0.1f..30f)

        // Obs 4: h = 0.23 (receding, dh = -0.01 <= 0)
        val f4 = tracker.update(
            listOf(makeDet(classId = 0, top = 0.30f, bottom = 0.53f)),
            captureNanos = 400_000_000L,
            corridor = CorridorDirection.CENTER,
            classMask = null,
        )
        assertNull("TTC must be null when receding", f4[0].ttcSeconds)

        // Obs 5: h = 0.23 (stationary, dh = 0 <= 0)
        val f5 = tracker.update(
            listOf(makeDet(classId = 0, top = 0.30f, bottom = 0.53f)),
            captureNanos = 500_000_000L,
            corridor = CorridorDirection.CENTER,
            classMask = null,
        )
        assertNull("TTC must be null when stationary", f5[0].ttcSeconds)
    }

    @Test
    fun update_lateralVelocitySignOnCorridors() {
        // Frame 1 for CENTER corridor: box on left (center = 0.20)
        val trackerCenter = ObjectTracker()
        val c1 = trackerCenter.update(
            listOf(makeDet(classId = 0, left = 0.15f, right = 0.25f)),
            captureNanos = 100_000_000L,
            corridor = CorridorDirection.CENTER,
            classMask = null,
        )
        assertEquals("Lateral velocity must be 0 on first observation", 0f, c1[0].lateralVelocityNorm, 1e-5f)

        // Moving right toward CENTER corridor center (~0.501) -> positive
        val c2 = trackerCenter.update(
            listOf(makeDet(classId = 0, left = 0.20f, right = 0.30f)), // center 0.25
            captureNanos = 200_000_000L,
            corridor = CorridorDirection.CENTER,
            classMask = null,
        )
        assertTrue("Moving towards corridor center must have positive lateral velocity", c2[0].lateralVelocityNorm > 0f)

        // Moving left away from CENTER corridor center -> negative
        val c3 = trackerCenter.update(
            listOf(makeDet(classId = 0, left = 0.16f, right = 0.26f)), // center 0.21
            captureNanos = 300_000_000L,
            corridor = CorridorDirection.CENTER,
            classMask = null,
        )
        assertTrue("Moving away from corridor center must have negative lateral velocity", c3[0].lateralVelocityNorm < 0f)

        // LEFT corridor: center is ~0.167
        // Box at center 0.50 (to the right of LEFT corridor) moving left toward LEFT corridor -> positive
        val trackerLeft = ObjectTracker()
        trackerLeft.update(
            listOf(makeDet(classId = 0, left = 0.45f, right = 0.55f)), // center 0.50
            captureNanos = 100_000_000L,
            corridor = CorridorDirection.LEFT,
            classMask = null,
        )
        val l2 = trackerLeft.update(
            listOf(makeDet(classId = 0, left = 0.40f, right = 0.50f)), // center 0.45 (moving left)
            captureNanos = 200_000_000L,
            corridor = CorridorDirection.LEFT,
            classMask = null,
        )
        assertTrue("Moving left toward LEFT corridor must have positive lateral velocity", l2[0].lateralVelocityNorm > 0f)

        // RIGHT corridor: center is ~0.834
        // Box at center 0.50 (to the left of RIGHT corridor) moving right toward RIGHT corridor -> positive
        val trackerRight = ObjectTracker()
        trackerRight.update(
            listOf(makeDet(classId = 0, left = 0.45f, right = 0.55f)), // center 0.50
            captureNanos = 100_000_000L,
            corridor = CorridorDirection.RIGHT,
            classMask = null,
        )
        val r2 = trackerRight.update(
            listOf(makeDet(classId = 0, left = 0.50f, right = 0.60f)), // center 0.55 (moving right)
            captureNanos = 200_000_000L,
            corridor = CorridorDirection.RIGHT,
            classMask = null,
        )
        assertTrue("Moving right toward RIGHT corridor must have positive lateral velocity", r2[0].lateralVelocityNorm > 0f)
    }

    @Test
    fun update_corridorOverlap() {
        val tracker = ObjectTracker(frameWidth = 512)

        // CENTER corridor is cols 171..341 -> [171/512, 342/512] = [0.33398, 0.66797]
        // Full overlap: box [0.40, 0.60] is completely inside [0.334, 0.668]
        val full = tracker.update(
            listOf(makeDet(classId = 0, left = 0.40f, right = 0.60f)),
            captureNanos = 100_000_000L,
            corridor = CorridorDirection.CENTER,
            classMask = null,
        )
        assertEquals(1.0f, full[0].corridorOverlap, 1e-4f)

        // None overlap: box [0.05, 0.20] completely outside
        tracker.reset()
        val none = tracker.update(
            listOf(makeDet(classId = 0, left = 0.05f, right = 0.20f)),
            captureNanos = 100_000_000L,
            corridor = CorridorDirection.CENTER,
            classMask = null,
        )
        assertEquals(0.0f, none[0].corridorOverlap, 1e-4f)

        // Partial overlap: box [0.30, 0.40] intersects [0.333984, 0.667969]
        // overlap width = 0.40 - 0.333984 = 0.066016; box width = 0.10 -> ~0.660
        tracker.reset()
        val partial = tracker.update(
            listOf(makeDet(classId = 0, left = 0.30f, right = 0.40f)),
            captureNanos = 100_000_000L,
            corridor = CorridorDirection.CENTER,
            classMask = null,
        )
        assertTrue("Corridor overlap must be strictly between 0 and 1", partial[0].corridorOverlap in 0.1f..0.9f)
    }

    @Test
    fun update_segAgreeTrueFalseAndMaskNull() {
        val tracker = ObjectTracker()
        val maskWidth = 512
        val maskHeight = 288
        val maskSize = maskWidth * maskHeight

        // Null mask
        val nullMaskRes = tracker.update(
            listOf(makeDet(classId = 0)),
            captureNanos = 100_000_000L,
            corridor = CorridorDirection.CENTER,
            classMask = null,
        )
        assertFalse(nullMaskRes[0].segAgree)

        // Mask size too small
        val smallMaskRes = tracker.update(
            listOf(makeDet(classId = 0)),
            captureNanos = 200_000_000L,
            corridor = CorridorDirection.CENTER,
            classMask = ByteArray(100),
            maskWidth = maskWidth,
            maskHeight = maskHeight,
        )
        assertFalse(smallMaskRes[0].segAgree)

        // Matching mask: PERSON (classId 0) matches segmentation class 4
        // Box: left=0.2, right=0.4, top=0.4, bottom=0.6. Bottom 10% is rows 0.58..0.60
        val agreeMask = ByteArray(maskSize)
        val rowStart = (0.58f * maskHeight).toInt()
        val rowEnd = (0.60f * maskHeight).toInt()
        val colStart = (0.2f * maskWidth).toInt()
        val colEnd = (0.4f * maskWidth).toInt()
        for (r in rowStart..rowEnd) {
            for (c in colStart..colEnd) {
                agreeMask[r * maskWidth + c] = 4.toByte() // 100% agreement
            }
        }
        val agreeRes = tracker.update(
            listOf(makeDet(classId = 0, left = 0.2f, top = 0.4f, right = 0.4f, bottom = 0.6f)),
            captureNanos = 300_000_000L,
            corridor = CorridorDirection.CENTER,
            classMask = agreeMask,
            maskWidth = maskWidth,
            maskHeight = maskHeight,
        )
        assertTrue("Matching segmentation class in bottom 10% must yield segAgree = true", agreeRes[0].segAgree)

        // Disagreeing mask: VEHICLE (classId 2) requires segmentation class 5, but mask has only class 0
        val disagreeMask = ByteArray(maskSize)
        val disagreeRes = tracker.update(
            listOf(makeDet(classId = 2, left = 0.2f, top = 0.4f, right = 0.4f, bottom = 0.6f)),
            captureNanos = 400_000_000L,
            corridor = CorridorDirection.CENTER,
            classMask = disagreeMask,
            maskWidth = maskWidth,
            maskHeight = maskHeight,
        )
        assertFalse("Disagreeing segmentation class must yield segAgree = false", disagreeRes[0].segAgree)
    }

    @Test
    fun update_projectorIntegration() {
        val tracker = ObjectTracker()

        // Null projector
        val noProj = tracker.update(
            listOf(makeDet(classId = 0)),
            captureNanos = 100_000_000L,
            corridor = CorridorDirection.CENTER,
            classMask = null,
            projector = null,
        )
        assertNull(noProj[0].distanceMeters)
        assertNull(noProj[0].lateralMeters)

        // Valid projector
        val mockProjector = PointProjector { _, _ ->
            GroundIntersection(
                forwardDistanceMeters = 3.45f,
                lateralDistanceMeters = -0.25f,
                totalDistanceMeters = 3.46f,
                rayDistanceMeters = 3.75f,
                intersectionPoint = Vector3D(-0.25f, 0f, 3.45f),
            )
        }
        val withProj = tracker.update(
            listOf(makeDet(classId = 0)),
            captureNanos = 200_000_000L,
            corridor = CorridorDirection.CENTER,
            classMask = null,
            projector = mockProjector,
        )
        assertEquals(3.45f, withProj[0].distanceMeters!!, 1e-4f)
        assertEquals(-0.25f, withProj[0].lateralMeters!!, 1e-4f)

        // Projector returning null
        val nullReturningProjector = PointProjector { _, _ -> null }
        val nullProj = tracker.update(
            listOf(makeDet(classId = 0)),
            captureNanos = 300_000_000L,
            corridor = CorridorDirection.CENTER,
            classMask = null,
            projector = nullReturningProjector,
        )
        assertNull(nullProj[0].distanceMeters)
        assertNull(nullProj[0].lateralMeters)
    }

    @Test
    fun update_maxTracksCapByScore() {
        val tracker = ObjectTracker(maxTracks = 3)

        val detections = listOf(
            makeDet(classId = 0, score = 0.55f, left = 0.05f, right = 0.15f),
            makeDet(classId = 0, score = 0.95f, left = 0.20f, right = 0.30f),
            makeDet(classId = 0, score = 0.65f, left = 0.35f, right = 0.45f),
            makeDet(classId = 0, score = 0.85f, left = 0.50f, right = 0.60f),
            makeDet(classId = 0, score = 0.75f, left = 0.65f, right = 0.75f),
        )

        val tracks = tracker.update(
            detections = detections,
            captureNanos = 100_000_000L,
            corridor = CorridorDirection.CENTER,
            classMask = null,
        )

        assertEquals("Track list must be capped to maxTracks", 3, tracks.size)
        // Scores retained must be 0.95, 0.85, 0.75
        val scores = tracks.map { it.score }.toSet()
        assertTrue(scores.contains(0.95f))
        assertTrue(scores.contains(0.85f))
        assertTrue(scores.contains(0.75f))
        // Must be ordered by ID ascending
        for (i in 0 until tracks.size - 1) {
            assertTrue(tracks[i].id < tracks[i + 1].id)
        }
    }
}
