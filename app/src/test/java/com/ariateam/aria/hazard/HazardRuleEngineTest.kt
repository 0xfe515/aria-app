package com.ariateam.aria.hazard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HazardRuleEngineTest {

    private lateinit var engine: HazardRuleEngine
    private val reliableMotion = MotionState(
        isCalibrated = true,
        confidence = 1.0f,
        degradedReason = null,
        activity = UserMovementActivity.WALKING,
        activeCorridor = CorridorDirection.CENTER,
    )

    private fun baseFeatures(
        corridor: CorridorDirection = CorridorDirection.CENTER,
        nearWalkable: Float = 1.0f,
        roiWalkable: Float = 1.0f,
        nearRoad: Float = 0.0f,
        sideRoadLeft: Float = 0.0f,
        sideRoadRight: Float = 0.0f,
        nearCrosswalk: Float = 0.0f,
        otherRatio: Float = 0.0f,
        poleBlobs: List<MaskBlob> = emptyList(),
    ) = SegmentationFeatures(
        corridor = corridor,
        nearWalkable = nearWalkable,
        roiWalkable = roiWalkable,
        nearRoad = nearRoad,
        sideRoadLeft = sideRoadLeft,
        sideRoadRight = sideRoadRight,
        nearCrosswalk = nearCrosswalk,
        otherRatio = otherRatio,
        dominantNearClass = -1,
        dominantRoiClass = -1,
        poleBlobs = poleBlobs,
    )

    private fun createTrack(
        id: Int = 1,
        kind: ObjectKind = ObjectKind.VEHICLE,
        score: Float = 0.9f,
        left: Float = 0.35f,
        top: Float = 0.5f,
        right: Float = 0.65f,
        bottom: Float = 0.85f,
        distanceMeters: Float? = 3.0f,
        lateralMeters: Float? = 0.0f,
        ttcSeconds: Float? = null,
        lateralVelocityNorm: Float = 0.0f,
        corridorOverlap: Float = 1.0f,
        confirmed: Boolean = true,
        segAgree: Boolean = true,
    ) = ObjectTrack(
        id = id,
        kind = kind,
        score = score,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        distanceMeters = distanceMeters,
        lateralMeters = lateralMeters,
        ttcSeconds = ttcSeconds,
        lateralVelocityNorm = lateralVelocityNorm,
        corridorOverlap = corridorOverlap,
        confirmed = confirmed,
        segAgree = segAgree,
        lastCaptureNanos = 1_000_000_000L,
    )

    @Before
    fun setUp() {
        engine = HazardRuleEngine()
    }

    @Test
    fun degradedPrecedence_triggersForUnreliableMotionOrHighOtherRatio() {
        // 1. Motion unreliable
        val uncalibratedMotion = reliableMotion.copy(isCalibrated = false)
        val decision1 = engine.evaluate(baseFeatures(), emptyList(), uncalibratedMotion, 1_000_000_000L)
        assertEquals(AlertSeverity.DEGRADED, decision1.severity)
        assertEquals(HazardCause.DEGRADED, decision1.cause)
        assertFalse(decision1.messageKorean.isEmpty())

        // 2. Motion degradedReason set
        val degradedReasonMotion = reliableMotion.copy(degradedReason = MotionDegradedReason.CAMERA_TILT_INVALID)
        val decision2 = engine.evaluate(baseFeatures(), emptyList(), degradedReasonMotion, 1_000_000_000L)
        assertEquals(AlertSeverity.DEGRADED, decision2.severity)
        assertEquals(HazardCause.DEGRADED, decision2.cause)
        assertTrue(decision2.messageKorean.contains("시야 이탈"))

        // 3. Obscured segmentation (otherRatio > 0.60)
        val highOtherFeatures = baseFeatures(otherRatio = 0.65f)
        val decision3 = engine.evaluate(highOtherFeatures, emptyList(), reliableMotion, 1_000_000_000L)
        assertEquals(AlertSeverity.DEGRADED, decision3.severity)
        assertEquals(HazardCause.DEGRADED, decision3.cause)
        assertEquals("주변 인식이 어렵습니다", decision3.messageKorean)

        // Boundary: otherRatio = 0.59 <= 0.60 should NOT be degraded
        val okOtherFeatures = baseFeatures(otherRatio = 0.59f)
        val decision4 = engine.evaluate(okOtherFeatures, emptyList(), reliableMotion, 1_000_000_000L)
        assertNotEquals(AlertSeverity.DEGRADED, decision4.severity)
    }

    @Test
    fun w1_vehicleApproach_firesOnInPathOrApproachingDistanceAndTtc() {
        // In path vehicle at 3.5m (< 4.0m)
        val track1 = createTrack(kind = ObjectKind.VEHICLE, distanceMeters = 3.5f, corridorOverlap = 0.8f)
        val decision1 = engine.evaluate(baseFeatures(), listOf(track1), reliableMotion, 1_000_000_000L)
        assertEquals(AlertSeverity.WARNING, decision1.severity)
        assertEquals(HazardCause.W1_VEHICLE_APPROACH, decision1.cause)
        assertTrue(decision1.messageKorean.contains("정지. 정면"))
        assertTrue(decision1.messageKorean.contains("차량"))

        // Boundary: distance 4.1m (>= 4.0m) without ttc or lateral approach -> does not trigger W1
        val trackBoundary = createTrack(kind = ObjectKind.VEHICLE, distanceMeters = 4.1f, corridorOverlap = 0.8f)
        val decisionBoundary = engine.evaluate(baseFeatures(), listOf(trackBoundary), reliableMotion, 1_000_000_000L)
        assertNotEquals(HazardCause.W1_VEHICLE_APPROACH, decisionBoundary.cause)

        // Lateral approach: not in path (overlap 0.2f), but approachingLaterally (lateralVelocityNorm = 0.5 > 0)
        val trackLateral = createTrack(
            kind = ObjectKind.TWO_WHEELER,
            distanceMeters = 3.5f,
            corridorOverlap = 0.2f,
            lateralVelocityNorm = 0.5f,
        )
        val decisionLateral = engine.evaluate(baseFeatures(), listOf(trackLateral), reliableMotion, 1_000_000_000L)
        assertEquals(HazardCause.W1_VEHICLE_APPROACH, decisionLateral.cause)

        // Crosswalk tightening: vehicle at 5.5m fires W1 when onCrosswalk (nearCrosswalk = 0.4 >= 0.3)
        val crosswalkFeatures = baseFeatures(nearCrosswalk = 0.4f)
        val trackFarVehicle = createTrack(kind = ObjectKind.VEHICLE, distanceMeters = 5.5f, corridorOverlap = 0.8f)
        val decisionCw = engine.evaluate(crosswalkFeatures, listOf(trackFarVehicle), reliableMotion, 1_000_000_000L)
        assertEquals(HazardCause.W1_VEHICLE_APPROACH, decisionCw.cause)
    }

    @Test
    fun w2_dynamicTrackImminent_firesWhenTtcBelowThreshold() {
        val personTrack = createTrack(
            kind = ObjectKind.PERSON,
            corridorOverlap = 0.8f,
            distanceMeters = 2.0f,
            ttcSeconds = 1.5f, // < 2.0s
            left = 0.70f,
            right = 0.90f, // horizontal centre > 341/512 -> RIGHT
        )
        val decision = engine.evaluate(baseFeatures(), listOf(personTrack), reliableMotion, 1_000_000_000L)
        assertEquals(AlertSeverity.WARNING, decision.severity)
        assertEquals(HazardCause.W2_OBJECT_IMMINENT, decision.cause)
        assertEquals(CorridorDirection.RIGHT, decision.direction)
        assertTrue(decision.messageKorean.contains("오른쪽 2.0미터 사람"))

        // Boundary: ttc 2.1s (>= 2.0s) does not trigger W2
        val personTrackFarTtc = personTrack.copy(ttcSeconds = 2.1f)
        val decisionBoundary = engine.evaluate(baseFeatures(), listOf(personTrackFarTtc), reliableMotion, 1_000_000_000L)
        assertNotEquals(HazardCause.W2_OBJECT_IMMINENT, decisionBoundary.cause)
    }

    @Test
    fun w3_roadUnderfoot_firesWhenWalkingOffCrosswalk_andCrosswalkSuppressesIt() {
        // Walking, not on crosswalk, nearRoad = 0.55 (> 0.50)
        val roadFeatures = baseFeatures(nearRoad = 0.55f, nearCrosswalk = 0.1f)
        val decision = engine.evaluate(roadFeatures, emptyList(), reliableMotion, 1_000_000_000L)
        assertEquals(AlertSeverity.WARNING, decision.severity)
        assertEquals(HazardCause.W3_ROAD_UNDERFOOT, decision.cause)
        assertEquals("차도입니다. 멈추세요", decision.messageKorean)

        // Boundary: nearRoad = 0.49 (<= 0.50) does not trigger W3
        val boundaryRoad = baseFeatures(nearRoad = 0.49f, nearCrosswalk = 0.1f)
        val decisionBoundary = engine.evaluate(boundaryRoad, emptyList(), reliableMotion, 1_000_000_000L)
        assertNotEquals(HazardCause.W3_ROAD_UNDERFOOT, decisionBoundary.cause)

        // Crosswalk suppression: onCrosswalk (nearCrosswalk = 0.35 >= 0.3) suppresses W3 even with nearRoad = 0.80
        val cwRoadFeatures = baseFeatures(nearRoad = 0.80f, nearCrosswalk = 0.35f)
        val decisionCw = engine.evaluate(cwRoadFeatures, emptyList(), reliableMotion, 1_000_000_000L)
        assertNotEquals(HazardCause.W3_ROAD_UNDERFOOT, decisionCw.cause)
    }

    @Test
    fun w4_staticImminent_firesForPoleOrUnknownInPath_withDistanceOrBoxBottomFallback() {
        // With distance < 1.2m
        val poleTrack = createTrack(kind = ObjectKind.POLE, distanceMeters = 1.0f, corridorOverlap = 0.7f)
        val decision = engine.evaluate(baseFeatures(), listOf(poleTrack), reliableMotion, 1_000_000_000L)
        assertEquals(AlertSeverity.WARNING, decision.severity)
        assertEquals(HazardCause.W4_STATIC_IMMINENT, decision.cause)
        assertTrue(decision.messageKorean.contains("정지. 정면 1.0미터 기둥"))

        // Distance null fallback: bottom >= 0.92
        val poleTrackNullDist = createTrack(
            kind = ObjectKind.POLE,
            distanceMeters = null,
            bottom = 0.95f,
            corridorOverlap = 0.7f,
        )
        val decisionNull = engine.evaluate(baseFeatures(), listOf(poleTrackNullDist), reliableMotion, 1_000_000_000L)
        assertEquals(AlertSeverity.WARNING, decisionNull.severity)
        assertEquals(HazardCause.W4_STATIC_IMMINENT, decisionNull.cause)
        assertTrue(decisionNull.messageKorean.contains("기둥"))

        // Boundary: distance 1.3m (>= 1.2m) does not trigger W4 (triggers C1 instead)
        val poleTrackFar = createTrack(kind = ObjectKind.POLE, distanceMeters = 1.3f, corridorOverlap = 0.7f)
        val decisionFar = engine.evaluate(baseFeatures(), listOf(poleTrackFar), reliableMotion, 1_000_000_000L)
        assertNotEquals(HazardCause.W4_STATIC_IMMINENT, decisionFar.cause)
        assertEquals(HazardCause.C1_OBJECT_IN_PATH, decisionFar.cause)
    }

    @Test
    fun w5_pathLost_firesWhenNearWalkableBelowThreshold() {
        val lostFeatures = baseFeatures(nearWalkable = 0.15f) // < 0.20
        val decision = engine.evaluate(lostFeatures, emptyList(), reliableMotion, 1_000_000_000L)
        assertEquals(AlertSeverity.WARNING, decision.severity)
        assertEquals(HazardCause.W5_PATH_LOST, decision.cause)
        assertEquals("앞길이 막혔습니다", decision.messageKorean)

        // Boundary: nearWalkable = 0.25 (>= 0.20) does not trigger W5
        val okFeatures = baseFeatures(nearWalkable = 0.25f)
        val decisionBoundary = engine.evaluate(okFeatures, emptyList(), reliableMotion, 1_000_000_000L)
        assertNotEquals(HazardCause.W5_PATH_LOST, decisionBoundary.cause)
    }

    @Test
    fun c1_objectInPath_firesInCautionDistanceRangeOrBoxBottom() {
        // Track at 2.0m (in [1.2, 3.8))
        val poleTrack = createTrack(kind = ObjectKind.POLE, distanceMeters = 2.0f, corridorOverlap = 0.8f)
        val decision = engine.evaluate(baseFeatures(), listOf(poleTrack), reliableMotion, 1_000_000_000L)
        assertEquals(AlertSeverity.CAUTION, decision.severity)
        assertEquals(HazardCause.C1_OBJECT_IN_PATH, decision.cause)
        assertTrue(decision.messageKorean.contains("정면 2.0미터 기둥"))

        // Distance null fallback: bottom 0.85 (in [0.80, 0.92))
        val poleNullDist = createTrack(kind = ObjectKind.POLE, distanceMeters = null, bottom = 0.85f, corridorOverlap = 0.8f)
        val decisionNull = engine.evaluate(baseFeatures(), listOf(poleNullDist), reliableMotion, 1_000_000_000L)
        assertEquals(AlertSeverity.CAUTION, decisionNull.severity)
        assertEquals(HazardCause.C1_OBJECT_IN_PATH, decisionNull.cause)

        // Boundary: bottom 0.75 (< 0.80) does not trigger C1
        val poleSmall = createTrack(kind = ObjectKind.POLE, distanceMeters = null, bottom = 0.75f, corridorOverlap = 0.8f)
        val decisionSmall = engine.evaluate(baseFeatures(), listOf(poleSmall), reliableMotion, 1_000_000_000L)
        assertNotEquals(HazardCause.C1_OBJECT_IN_PATH, decisionSmall.cause)
    }

    @Test
    fun c2_vehicleNearby_firesForVehicleNotDirectlyInPath() {
        val vehicleNearby = createTrack(
            kind = ObjectKind.VEHICLE,
            distanceMeters = 4.5f, // < 6.0m
            corridorOverlap = 0.2f, // < 0.5 (not inPath)
            left = 0.1f,
            right = 0.2f, // LEFT
        )
        val decision = engine.evaluate(baseFeatures(), listOf(vehicleNearby), reliableMotion, 1_000_000_000L)
        assertEquals(AlertSeverity.CAUTION, decision.severity)
        assertEquals(HazardCause.C2_VEHICLE_NEARBY, decision.cause)
        assertEquals(CorridorDirection.LEFT, decision.direction)
        assertTrue(decision.messageKorean.contains("왼쪽 4.5미터 차량 주의"))

        // Boundary: distance 6.5m (>= 6.0m) does not trigger C2
        val vehicleFar = vehicleNearby.copy(distanceMeters = 6.5f)
        val decisionFar = engine.evaluate(baseFeatures(), listOf(vehicleFar), reliableMotion, 1_000_000_000L)
        assertNotEquals(HazardCause.C2_VEHICLE_NEARBY, decisionFar.cause)
    }

    @Test
    fun c3_roadEdge_firesWhenSideRoadExceedsThreshold() {
        // Right side road = 0.35 (> 0.30)
        val rightFeatures = baseFeatures(sideRoadRight = 0.35f, sideRoadLeft = 0.1f)
        val decisionRight = engine.evaluate(rightFeatures, emptyList(), reliableMotion, 1_000_000_000L)
        assertEquals(AlertSeverity.CAUTION, decisionRight.severity)
        assertEquals(HazardCause.C3_ROAD_EDGE, decisionRight.cause)
        assertEquals(CorridorDirection.RIGHT, decisionRight.direction)
        assertEquals("오른쪽이 차도입니다", decisionRight.messageKorean)

        // Left side road = 0.40 (> 0.30)
        val leftFeatures = baseFeatures(sideRoadRight = 0.1f, sideRoadLeft = 0.40f)
        val decisionLeft = engine.evaluate(leftFeatures, emptyList(), reliableMotion, 1_000_000_000L)
        assertEquals(AlertSeverity.CAUTION, decisionLeft.severity)
        assertEquals(HazardCause.C3_ROAD_EDGE, decisionLeft.cause)
        assertEquals(CorridorDirection.LEFT, decisionLeft.direction)
        assertEquals("왼쪽이 차도입니다", decisionLeft.messageKorean)

        // Boundary: 0.29 (<= 0.30) does not trigger C3
        val okFeatures = baseFeatures(sideRoadRight = 0.29f, sideRoadLeft = 0.29f)
        val decisionOk = engine.evaluate(okFeatures, emptyList(), reliableMotion, 1_000_000_000L)
        assertNotEquals(HazardCause.C3_ROAD_EDGE, decisionOk.cause)
    }

    @Test
    fun c4_pathNarrow_firesWhenNearOrRoiWalkableBelowThreshold() {
        // nearWalkable = 0.45 (< 0.50)
        val nearNarrow = baseFeatures(nearWalkable = 0.45f, roiWalkable = 0.80f)
        val decisionNear = engine.evaluate(nearNarrow, emptyList(), reliableMotion, 1_000_000_000L)
        assertEquals(AlertSeverity.CAUTION, decisionNear.severity)
        assertEquals(HazardCause.C4_PATH_NARROW, decisionNear.cause)
        assertEquals("길이 좁아집니다", decisionNear.messageKorean)

        // roiWalkable = 0.60 (< 0.65)
        val roiNarrow = baseFeatures(nearWalkable = 0.80f, roiWalkable = 0.60f)
        val decisionRoi = engine.evaluate(roiNarrow, emptyList(), reliableMotion, 1_000_000_000L)
        assertEquals(AlertSeverity.CAUTION, decisionRoi.severity)
        assertEquals(HazardCause.C4_PATH_NARROW, decisionRoi.cause)

        // Boundary: nearWalkable = 0.55, roiWalkable = 0.70 does not trigger C4
        val okFeatures = baseFeatures(nearWalkable = 0.55f, roiWalkable = 0.70f)
        val decisionOk = engine.evaluate(okFeatures, emptyList(), reliableMotion, 1_000_000_000L)
        assertNotEquals(HazardCause.C4_PATH_NARROW, decisionOk.cause)
    }

    @Test
    fun c5_crosswalkEntry_firesOnlyOnRisingEdge_andResetClearsState() {
        // Frame 1: off crosswalk
        val offCw = baseFeatures(nearCrosswalk = 0.1f)
        val decision1 = engine.evaluate(offCw, emptyList(), reliableMotion, 1_000_000_000L)
        assertNotEquals(HazardCause.C5_CROSSWALK_ENTRY, decision1.cause)

        // Frame 2: enters crosswalk (0.4 >= 0.3) -> rising edge fires C5!
        val onCw = baseFeatures(nearCrosswalk = 0.4f)
        val decision2 = engine.evaluate(onCw, emptyList(), reliableMotion, 2_000_000_000L)
        assertEquals(AlertSeverity.CAUTION, decision2.severity)
        assertEquals(HazardCause.C5_CROSSWALK_ENTRY, decision2.cause)
        assertEquals("횡단보도 앞입니다. 신호는 확인할 수 없습니다", decision2.messageKorean)

        // Frame 3: remains on crosswalk -> should NOT fire C5 again
        val decision3 = engine.evaluate(onCw, emptyList(), reliableMotion, 3_000_000_000L)
        assertNotEquals(HazardCause.C5_CROSSWALK_ENTRY, decision3.cause)

        // Reset clears crosswalk state -> Frame 4 fires C5 again
        engine.reset()
        val decision4 = engine.evaluate(onCw, emptyList(), reliableMotion, 4_000_000_000L)
        assertEquals(HazardCause.C5_CROSSWALK_ENTRY, decision4.cause)
    }

    @Test
    fun stationarySuppression_suppressesW4W5C4StaticC1_whileW1W2C2StillFire() {
        val stationaryMotion = reliableMotion.copy(activity = UserMovementActivity.STATIONARY)

        // W4 suppressed when stationary
        val poleTrack = createTrack(kind = ObjectKind.POLE, distanceMeters = 1.0f, corridorOverlap = 0.8f)
        val decisionW4 = engine.evaluate(baseFeatures(), listOf(poleTrack), stationaryMotion, 1_000_000_000L)
        assertNotEquals(HazardCause.W4_STATIC_IMMINENT, decisionW4.cause)
        assertNotEquals(HazardCause.C1_OBJECT_IN_PATH, decisionW4.cause) // static C1 also suppressed

        // W5 suppressed when stationary
        val lostFeatures = baseFeatures(nearWalkable = 0.15f)
        val decisionW5 = engine.evaluate(lostFeatures, emptyList(), stationaryMotion, 1_000_000_000L)
        assertNotEquals(HazardCause.W5_PATH_LOST, decisionW5.cause)

        // C4 suppressed when stationary
        val narrowFeatures = baseFeatures(nearWalkable = 0.45f)
        val decisionC4 = engine.evaluate(narrowFeatures, emptyList(), stationaryMotion, 1_000_000_000L)
        assertNotEquals(HazardCause.C4_PATH_NARROW, decisionC4.cause)

        // W1 STILL FIRES when stationary!
        val vehicleApproaching = createTrack(kind = ObjectKind.VEHICLE, distanceMeters = 3.0f, corridorOverlap = 0.8f)
        val decisionW1 = engine.evaluate(baseFeatures(), listOf(vehicleApproaching), stationaryMotion, 1_000_000_000L)
        assertEquals(HazardCause.W1_VEHICLE_APPROACH, decisionW1.cause)

        // W2 STILL FIRES when stationary!
        val imminentPerson = createTrack(kind = ObjectKind.PERSON, corridorOverlap = 0.8f, ttcSeconds = 1.0f)
        val decisionW2 = engine.evaluate(baseFeatures(), listOf(imminentPerson), stationaryMotion, 1_000_000_000L)
        assertEquals(HazardCause.W2_OBJECT_IMMINENT, decisionW2.cause)

        // C2 STILL FIRES when stationary!
        val nearbyVehicle = createTrack(kind = ObjectKind.VEHICLE, distanceMeters = 4.0f, corridorOverlap = 0.1f)
        val decisionC2 = engine.evaluate(baseFeatures(), listOf(nearbyVehicle), stationaryMotion, 1_000_000_000L)
        assertEquals(HazardCause.C2_VEHICLE_NEARBY, decisionC2.cause)
    }

    @Test
    fun unconfirmedTracks_areIgnored() {
        val unconfirmedVehicle = createTrack(
            kind = ObjectKind.VEHICLE,
            distanceMeters = 2.0f,
            corridorOverlap = 0.9f,
            confirmed = false,
        )
        val decision = engine.evaluate(baseFeatures(), listOf(unconfirmedVehicle), reliableMotion, 1_000_000_000L)
        assertEquals(AlertSeverity.NONE, decision.severity)
        assertEquals(HazardCause.NONE, decision.cause)
    }

    @Test
    fun simultaneousCauses_choosesWarningOverCaution_andHighestRiskScore() {
        // Both C2 (nearby vehicle at 4.0m) and W1 (approaching vehicle at 2.0m) present
        val vehicleW1 = createTrack(id = 1, kind = ObjectKind.VEHICLE, distanceMeters = 2.0f, corridorOverlap = 0.8f)
        val vehicleC2 = createTrack(id = 2, kind = ObjectKind.VEHICLE, distanceMeters = 4.0f, corridorOverlap = 0.1f)

        val decision = engine.evaluate(baseFeatures(), listOf(vehicleW1, vehicleC2), reliableMotion, 1_000_000_000L)
        assertEquals(AlertSeverity.WARNING, decision.severity)
        assertEquals(HazardCause.W1_VEHICLE_APPROACH, decision.cause)
        assertEquals(1, decision.trackId)
    }

    @Test
    fun segPoleBlobs_convertIntoSyntheticCandidatesAndTriggerW4AndC1() {
        // Blob in center corridor with maxY = 280 (bottom = 281/288 = 0.975 >= 0.92) -> triggers W4
        val w4Blob = MaskBlob(classId = 6, pixelCount = 50, minX = 200, maxX = 250, minY = 240, maxY = 280)
        val w4Features = baseFeatures(poleBlobs = listOf(w4Blob))
        val decisionW4 = engine.evaluate(w4Features, emptyList(), reliableMotion, 1_000_000_000L)
        assertEquals(AlertSeverity.WARNING, decisionW4.severity)
        assertEquals(HazardCause.W4_STATIC_IMMINENT, decisionW4.cause)
        assertNotNull(decisionW4.trackId) // Synthetic track id assigned

        // Blob in center corridor with maxY = 245 (bottom = 246/288 = 0.854 in [0.80, 0.92)) -> triggers C1
        val c1Blob = MaskBlob(classId = 6, pixelCount = 50, minX = 200, maxX = 250, minY = 200, maxY = 245)
        val c1Features = baseFeatures(poleBlobs = listOf(c1Blob))
        val decisionC1 = engine.evaluate(c1Features, emptyList(), reliableMotion, 1_000_000_000L)
        assertEquals(AlertSeverity.CAUTION, decisionC1.severity)
        assertEquals(HazardCause.C1_OBJECT_IN_PATH, decisionC1.cause)
    }
}
