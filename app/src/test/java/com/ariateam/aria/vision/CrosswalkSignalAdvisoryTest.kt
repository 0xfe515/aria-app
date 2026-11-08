package com.ariateam.aria.vision

import com.ariateam.aria.hazard.AlertDecision
import com.ariateam.aria.hazard.AlertSeverity
import com.ariateam.aria.hazard.AlertSuppressionReason
import com.ariateam.aria.hazard.CorridorDirection
import com.ariateam.aria.hazard.HazardAlertPolicy
import com.ariateam.aria.hazard.HazardAssessment
import com.ariateam.aria.hazard.HazardCause
import com.ariateam.aria.hazard.HazardLevel
import com.ariateam.aria.hazard.MotionState
import com.ariateam.aria.hazard.traffic.CrosswalkSignalPresencePolicy
import com.ariateam.aria.hazard.traffic.TrafficSignalPolicyConfig
import com.ariateam.aria.hazard.traffic.TrafficSuppressionReason
import com.ariateam.aria.vision.od.DetectedObject
import com.ariateam.aria.vision.od.OdEvidence
import com.ariateam.aria.vision.od.OdGeometryToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CrosswalkSignalAdvisoryTest {

    private lateinit var advisory: CrosswalkSignalAdvisory
    private lateinit var alertPolicy: HazardAlertPolicy

    private val sessionToken = 1001L
    private val defaultGeometry = OdGeometryToken(
        bufferWidth = 640,
        bufferHeight = 480,
        rotationDegrees = 0,
        uprightWidth = 512,
        uprightHeight = 288,
        cropLeft = 0,
        cropTop = 0,
        cropWidth = 640,
        cropHeight = 480,
        strategyName = "CENTER_CROP",
        contentLeft = 0,
        contentTop = 0,
        contentWidth = 512,
        contentHeight = 288,
        isRotatedClockwise = false,
    )

    private val motion = MotionState(
        activeCorridor = CorridorDirection.CENTER,
        isCalibrated = true,
        degradedReason = null,
        confidence = 0.95f,
    )

    @Before
    fun setUp() {
        val config = TrafficSignalPolicyConfig(
            presenceConfirmationFramesRequired = 2,
            staleObservationExpiryNanos = 1_500_000_000L,
            maxFutureToleranceNanos = 500_000_000L,
            maxConsecutiveCaptureGapNanos = 750_000_000L,
            minPresenceConfidence = 0.50f,
            minCrosswalkScore = 0.20f,
            presenceFallbackMessage = "횡단보도 앞 신호등을 확인하세요",
        )
        advisory = CrosswalkSignalAdvisory(
            policy = CrosswalkSignalPresencePolicy(config),
            approachSettlementFrames = 5,
        )
        alertPolicy = HazardAlertPolicy(
            confirmationFramesRequired = 2,
            cautionConfirmationFramesRequired = 3,
            alertCooldownMs = 2_500L,
            cautionCooldownMs = 6_000L,
        )
    }

    private fun createClassMask(crosswalkRatio: Float = 0.5f, corridor: CorridorDirection = CorridorDirection.CENTER): ByteArray {
        val mask = ByteArray(VisionContract.MODEL_WIDTH * VisionContract.MODEL_HEIGHT)
        val startCol = corridor.startCol
        val endCol = corridor.endCol
        val nearStartRow = 230
        val totalNearCols = endCol - startCol + 1
        val nearRows = VisionContract.MODEL_HEIGHT - nearStartRow
        val totalNearPixels = totalNearCols * nearRows
        val crosswalkPixelsTarget = (totalNearPixels * crosswalkRatio).toInt()

        var filled = 0
        for (y in nearStartRow until VisionContract.MODEL_HEIGHT) {
            val rowOffset = y * VisionContract.MODEL_WIDTH
            for (x in startCol..endCol) {
                if (filled < crosswalkPixelsTarget) {
                    mask[rowOffset + x] = 3.toByte() // 3 = crosswalk
                    filled++
                } else {
                    mask[rowOffset + x] = 2.toByte() // 2 = sidewalk / walkable
                }
            }
        }
        return mask
    }

    private fun createOdEvidence(
        captureTimestampNanos: Long,
        score: Float = 0.75f,
        boxLeft: Float = 0.40f,
        boxTop: Float = 0.15f,
        boxRight: Float = 0.60f,
        boxBottom: Float = 0.35f,
        classId: Int = CrosswalkSignalAdvisory.TRAFFIC_LIGHT_CLASS_ID,
        session: Long = sessionToken,
        geometry: OdGeometryToken = defaultGeometry,
        extraDetections: List<DetectedObject> = emptyList(),
        completedElapsedNanos: Long = captureTimestampNanos + 30_000_000L,
        arrivalElapsedNanos: Long = captureTimestampNanos + 5_000_000L,
        resultAgeNanos: Long = completedElapsedNanos - arrivalElapsedNanos,
    ): OdEvidence {
        val primary = DetectedObject.createOrNull(
            classId = classId,
            label = "traffic light",
            score = score,
            left = boxLeft,
            top = boxTop,
            right = boxRight,
            bottom = boxBottom,
        )!!
        return OdEvidence(
            sessionToken = session,
            geometry = geometry,
            captureTimestampNanos = captureTimestampNanos,
            arrivalElapsedNanos = arrivalElapsedNanos,
            completedElapsedNanos = completedElapsedNanos,
            resultAgeNanos = resultAgeNanos,
            detections = listOf(primary) + extraDetections,
        )
    }

    private fun createBaseAssessment(
        timestampNanos: Long,
        severity: AlertSeverity = AlertSeverity.NONE,
        cause: HazardCause = HazardCause.NONE,
        level: HazardLevel = HazardLevel.CLEAR_UNWARNED,
        message: String = "",
    ): HazardAssessment = HazardAssessment(
        timestampNanos = timestampNanos,
        level = level,
        activeCorridor = CorridorDirection.CENTER,
        isAlertCandidate = severity != AlertSeverity.NONE,
        severity = severity,
        cause = cause,
        messageKorean = message,
    )

    // -------------------------------------------------------------------------
    // 1. Two-rate timestamps driving actual HazardAlertPolicy to fire once
    // -------------------------------------------------------------------------

    @Test
    fun twoRate_drivingHazardAlertPolicy_firesExactlyOnce_withoutStarvationOrReplay() {
        val classMask = createClassMask(crosswalkRatio = 0.40f)
        var cameraTime = 1_000_000_000L
        val camStep = 125_000_000L // ~8 Hz (125ms)
        var odTime = 1_000_000_000L
        val odStep = 200_000_000L  // 5 Hz (200ms)

        var odEvidence = createOdEvidence(captureTimestampNanos = odTime)
        var firedCount = 0
        var deliveredAckCount = 0

        // Run 10 camera frames across multiple OD snapshots
        for (i in 0 until 10) {
            cameraTime += camStep
            val nowElapsedNanos = cameraTime + 10_000_000L
            val nowRealtimeMs = nowElapsedNanos / 1_000_000L

            // Produce fresh OD every 200ms (~frame 1, 3, 5, etc.)
            if ((cameraTime - odTime) >= odStep) {
                odTime = cameraTime
                odEvidence = createOdEvidence(captureTimestampNanos = odTime)
            }

            val base = createBaseAssessment(timestampNanos = cameraTime)
            val advisoryResult = advisory.evaluateAndFuse(
                baseAssessment = base,
                odEvidence = odEvidence,
                classMask = classMask,
                motion = motion,
                currentGeometry = defaultGeometry,
                sessionToken = sessionToken,
                frameCaptureTimestampNanos = cameraTime,
                nowElapsedNanos = nowElapsedNanos,
                isClockRealtime = true,
            )

            val alertDecision = alertPolicy.evaluate(
                assessment = advisoryResult.assessment,
                nowRealtimeMs = nowRealtimeMs,
                nowElapsedNanos = nowElapsedNanos,
            )

            if (alertDecision is AlertDecision.Fire) {
                firedCount++
                assertEquals(HazardCause.C6_CROSSWALK_SIGNAL_PRESENCE, alertDecision.assessment.cause)
                assertEquals(AlertSeverity.CAUTION, alertDecision.assessment.severity)
                assertEquals(HazardLevel.PATH_CAUTION, alertDecision.assessment.level)
                assertEquals("횡단보도 앞 신호등을 확인하세요", alertDecision.assessment.messageKorean)

                // Downstream coordinator delivery acknowledgement
                advisory.acknowledgeDelivered(alertDecision.assessment)
                deliveredAckCount++
            }
        }

        // Must fire exactly ONCE across the entire approach: no downstream 3-frame starvation and no replay!
        assertEquals("HazardAlertPolicy must fire exactly once for the approach", 1, firedCount)
        assertEquals("Downstream delivery must acknowledge exactly once", 1, deliveredAckCount)
    }

    // -------------------------------------------------------------------------
    // 2. Single-track IoU & center continuity with boundary jitter and reordered boxes
    // -------------------------------------------------------------------------

    @Test
    fun singleTrackContinuity_boundaryJitterAndReorderedBoxes_preservesTargetId() {
        val classMask = createClassMask(crosswalkRatio = 0.50f)
        val t1 = 1_000_000_000L
        val t2 = t1 + 200_000_000L

        // Frame 1: traffic light alone at center (0.40, 0.15, 0.60, 0.35)
        val od1 = createOdEvidence(captureTimestampNanos = t1, boxLeft = 0.40f, boxRight = 0.60f)
        val res1 = advisory.evaluateAndFuse(
            baseAssessment = createBaseAssessment(t1),
            odEvidence = od1,
            classMask = classMask,
            motion = motion,
            currentGeometry = defaultGeometry,
            sessionToken = sessionToken,
            frameCaptureTimestampNanos = t1,
            nowElapsedNanos = t1 + 10_000_000L,
            isClockRealtime = true,
        )

        // Frame 2: same light with slight boundary jitter (0.41, 0.14, 0.61, 0.36) preceded by another object in detections list
        val obstaclePerson = DetectedObject.createOrNull(
            classId = 0,
            label = "person",
            score = 0.90f,
            left = 0.05f,
            top = 0.10f,
            right = 0.15f,
            bottom = 0.50f,
        )!!
        val jitteredLight = DetectedObject.createOrNull(
            classId = 9,
            label = "traffic light",
            score = 0.76f,
            left = 0.41f,
            top = 0.14f,
            right = 0.61f,
            bottom = 0.36f,
        )!!
        // Light is at index 1 (reordered)
        val od2 = OdEvidence(
            sessionToken = sessionToken,
            geometry = defaultGeometry,
            captureTimestampNanos = t2,
            arrivalElapsedNanos = t2 + 5_000_000L,
            completedElapsedNanos = t2 + 30_000_000L,
            resultAgeNanos = 25_000_000L,
            detections = listOf(obstaclePerson, jitteredLight),
        )

        val res2 = advisory.evaluateAndFuse(
            baseAssessment = createBaseAssessment(t2),
            odEvidence = od2,
            classMask = classMask,
            motion = motion,
            currentGeometry = defaultGeometry,
            sessionToken = sessionToken,
            frameCaptureTimestampNanos = t2,
            nowElapsedNanos = t2 + 10_000_000L,
            isClockRealtime = true,
        )

        // Must confirm presence on frame 2
        assertTrue(res2.decision.isAlertCandidate)
        val trackId2 = res2.assessment.trackId
        assertNotNull(trackId2)

        // Frame 3: further slight jitter (0.39, 0.16, 0.59, 0.34) placed after another detection
        val t3 = t2 + 200_000_000L
        val vehicleObstacle = DetectedObject.createOrNull(
            classId = 2,
            label = "car",
            score = 0.88f,
            left = 0.85f,
            top = 0.40f,
            right = 0.95f,
            bottom = 0.60f,
        )!!
        val jitteredLight3 = DetectedObject.createOrNull(
            classId = 9,
            label = "traffic light",
            score = 0.78f,
            left = 0.39f,
            top = 0.16f,
            right = 0.59f,
            bottom = 0.34f,
        )!!
        val od3 = OdEvidence(
            sessionToken = sessionToken,
            geometry = defaultGeometry,
            captureTimestampNanos = t3,
            arrivalElapsedNanos = t3 + 5_000_000L,
            completedElapsedNanos = t3 + 30_000_000L,
            resultAgeNanos = 25_000_000L,
            detections = listOf(vehicleObstacle, jitteredLight3),
        )

        val res3 = advisory.evaluateAndFuse(
            baseAssessment = createBaseAssessment(t3),
            odEvidence = od3,
            classMask = classMask,
            motion = motion,
            currentGeometry = defaultGeometry,
            sessionToken = sessionToken,
            frameCaptureTimestampNanos = t3,
            nowElapsedNanos = t3 + 10_000_000L,
            isClockRealtime = true,
        )

        assertTrue(res3.decision.isAlertCandidate)
        val trackId3 = res3.assessment.trackId
        assertNotNull(trackId3)
        assertEquals("TrackId must remain identical across boundary jitter and detection list reordering", trackId2, trackId3)
    }

    // -------------------------------------------------------------------------
    // 3. Disjoint competing lights and unrelated side lights fail-closed
    // -------------------------------------------------------------------------

    @Test
    fun disjointCompetingLights_and_unrelatedSideLights_failClosed() {
        val classMask = createClassMask(crosswalkRatio = 0.50f)
        val t = 1_000_000_000L
        val now = t + 10_000_000L
        val base = createBaseAssessment(timestampNanos = t)

        // Case A: Disjoint competing lights in same corridor (e.g. left 0.25..0.35 and right 0.65..0.75, IoU = 0)
        val lightA = DetectedObject.createOrNull(9, "traffic light", 0.85f, 0.25f, 0.10f, 0.35f, 0.30f)!!
        val lightB = DetectedObject.createOrNull(9, "traffic light", 0.82f, 0.65f, 0.10f, 0.75f, 0.30f)!!
        val competingOd = OdEvidence(
            sessionToken = sessionToken,
            geometry = defaultGeometry,
            captureTimestampNanos = t,
            arrivalElapsedNanos = t + 5_000_000L,
            completedElapsedNanos = t + 30_000_000L,
            resultAgeNanos = 25_000_000L,
            detections = listOf(lightA, lightB),
        )
        val resCompeting = advisory.evaluateAndFuse(
            baseAssessment = base,
            odEvidence = competingOd,
            classMask = classMask,
            motion = motion, // CENTER corridor
            currentGeometry = defaultGeometry,
            sessionToken = sessionToken,
            frameCaptureTimestampNanos = t,
            nowElapsedNanos = now,
            isClockRealtime = true,
        )
        assertFalse("Disjoint competing lights must fail closed", resCompeting.decision.isAlertCandidate)

        // Case B: Unrelated side light alone (far to the left centerX = 0.08 while walking in CENTER corridor)
        val sideLight = DetectedObject.createOrNull(9, "traffic light", 0.90f, 0.04f, 0.10f, 0.12f, 0.30f)!!
        val sideOd = OdEvidence(
            sessionToken = sessionToken,
            geometry = defaultGeometry,
            captureTimestampNanos = t,
            arrivalElapsedNanos = t + 5_000_000L,
            completedElapsedNanos = t + 30_000_000L,
            resultAgeNanos = 25_000_000L,
            detections = listOf(sideLight),
        )
        val resSide = advisory.evaluateAndFuse(
            baseAssessment = base,
            odEvidence = sideOd,
            classMask = classMask,
            motion = motion, // CENTER corridor requires 0.20..0.80
            currentGeometry = defaultGeometry,
            sessionToken = sessionToken,
            frameCaptureTimestampNanos = t,
            nowElapsedNanos = now,
            isClockRealtime = true,
        )
        assertFalse("Unrelated side light outside active corridor must not trigger caution", resSide.decision.isAlertCandidate)
    }

    // -------------------------------------------------------------------------
    // 4. Overlapping duplicate detections collapse cleanly
    // -------------------------------------------------------------------------

    @Test
    fun overlappingDuplicateDetections_collapseCleanly() {
        val classMask = createClassMask(crosswalkRatio = 0.50f)
        val t = 1_000_000_000L
        val now = t + 10_000_000L
        val base = createBaseAssessment(timestampNanos = t)

        // Two overlapping detections of the same physical fixture (IoU > 0.40)
        val dup1 = DetectedObject.createOrNull(9, "traffic light", 0.88f, 0.40f, 0.15f, 0.60f, 0.35f)!!
        val dup2 = DetectedObject.createOrNull(9, "traffic light", 0.70f, 0.42f, 0.16f, 0.58f, 0.34f)!!
        val dupOd = OdEvidence(
            sessionToken = sessionToken,
            geometry = defaultGeometry,
            captureTimestampNanos = t,
            arrivalElapsedNanos = t + 5_000_000L,
            completedElapsedNanos = t + 30_000_000L,
            resultAgeNanos = 25_000_000L,
            detections = listOf(dup1, dup2),
        )

        val res = advisory.evaluateAndFuse(
            baseAssessment = base,
            odEvidence = dupOd,
            classMask = classMask,
            motion = motion,
            currentGeometry = defaultGeometry,
            sessionToken = sessionToken,
            frameCaptureTimestampNanos = t,
            nowElapsedNanos = now,
            isClockRealtime = true,
        )

        // Must not be rejected as disjoint competing lights; extracts best (score 0.88)
        assertEquals(TrafficSuppressionReason.CONFIRMATION_PENDING, res.decision.suppressionReason)
    }

    // -------------------------------------------------------------------------
    // 5. Strict metadata checks
    // -------------------------------------------------------------------------

    @Test
    fun strictMetadataChecks_rejectInconsistentOrFutureEvidence() {
        val classMask = createClassMask(crosswalkRatio = 0.50f)
        val t = 1_000_000_000L
        val now = t + 10_000_000L
        val base = createBaseAssessment(timestampNanos = t)

        // Inconsistent: completedElapsed < arrivalElapsed
        val invalidArrivalOd = createOdEvidence(
            captureTimestampNanos = t,
            arrivalElapsedNanos = 500_000_000L,
            completedElapsedNanos = 400_000_000L,
        )
        val resArrival = advisory.evaluateAndFuse(
            baseAssessment = base,
            odEvidence = invalidArrivalOd,
            classMask = classMask,
            motion = motion,
            currentGeometry = defaultGeometry,
            sessionToken = sessionToken,
            frameCaptureTimestampNanos = t,
            nowElapsedNanos = now,
            isClockRealtime = true,
        )
        assertFalse(resArrival.decision.isAlertCandidate)

        // Inconsistent: resultAgeNanos != completedElapsedNanos - arrivalElapsedNanos
        val invalidAgeOd = createOdEvidence(
            captureTimestampNanos = t,
            arrivalElapsedNanos = t + 5_000_000L,
            completedElapsedNanos = t + 30_000_000L,
            resultAgeNanos = 99_000_000L,
        )
        val resAge = advisory.evaluateAndFuse(
            baseAssessment = base,
            odEvidence = invalidAgeOd,
            classMask = classMask,
            motion = motion,
            currentGeometry = defaultGeometry,
            sessionToken = sessionToken,
            frameCaptureTimestampNanos = t,
            nowElapsedNanos = now,
            isClockRealtime = true,
        )
        assertFalse(resAge.decision.isAlertCandidate)

        // Future relative to camera frame
        val futureOd = createOdEvidence(captureTimestampNanos = t + 600_000_000L)
        val resFuture = advisory.evaluateAndFuse(
            baseAssessment = base,
            odEvidence = futureOd,
            classMask = classMask,
            motion = motion,
            currentGeometry = defaultGeometry,
            sessionToken = sessionToken,
            frameCaptureTimestampNanos = t,
            nowElapsedNanos = now,
            isClockRealtime = true,
        )
        assertFalse(resFuture.decision.isAlertCandidate)
    }

    // -------------------------------------------------------------------------
    // 6. Stale held display snapshot gating
    // -------------------------------------------------------------------------

    @Test
    fun staleHeldOdEvidence_suppressesAdvisory() {
        val classMask = createClassMask(crosswalkRatio = 0.50f)
        val nowElapsedNanos = 2_000_000_000L
        val staleCaptureNanos = nowElapsedNanos - 2_000_000_000L // 2.0s old (> 1.5s expiry)

        val staleEvidence = createOdEvidence(captureTimestampNanos = staleCaptureNanos)
        val base = createBaseAssessment(timestampNanos = nowElapsedNanos)

        val result = advisory.evaluateAndFuse(
            baseAssessment = base,
            odEvidence = staleEvidence,
            classMask = classMask,
            motion = motion,
            currentGeometry = defaultGeometry,
            sessionToken = sessionToken,
            frameCaptureTimestampNanos = nowElapsedNanos,
            nowElapsedNanos = nowElapsedNanos,
            isClockRealtime = true,
        )

        assertFalse(result.decision.isAlertCandidate)
        assertEquals(AlertSeverity.NONE, result.assessment.severity)
        assertEquals(HazardCause.NONE, result.assessment.cause)
    }

    // -------------------------------------------------------------------------
    // 7. Session and Geometry token mismatch fail-closed
    // -------------------------------------------------------------------------

    @Test
    fun sessionMismatch_and_geometryMismatch_suppressesAdvisory() {
        val classMask = createClassMask(crosswalkRatio = 0.50f)
        val t = 1_000_000_000L
        val now = t + 10_000_000L
        val base = createBaseAssessment(timestampNanos = t)

        // Session mismatch
        val mismatchSessionOd = createOdEvidence(captureTimestampNanos = t, session = 9999L)
        val resSession = advisory.evaluateAndFuse(
            baseAssessment = base,
            odEvidence = mismatchSessionOd,
            classMask = classMask,
            motion = motion,
            currentGeometry = defaultGeometry,
            sessionToken = sessionToken,
            frameCaptureTimestampNanos = t,
            nowElapsedNanos = now,
            isClockRealtime = true,
        )
        assertFalse(resSession.decision.isAlertCandidate)

        // Geometry mismatch
        val otherGeometry = defaultGeometry.copy(rotationDegrees = 90)
        val mismatchGeomOd = createOdEvidence(captureTimestampNanos = t, geometry = otherGeometry)
        val resGeom = advisory.evaluateAndFuse(
            baseAssessment = base,
            odEvidence = mismatchGeomOd,
            classMask = classMask,
            motion = motion,
            currentGeometry = defaultGeometry,
            sessionToken = sessionToken,
            frameCaptureTimestampNanos = t,
            nowElapsedNanos = now,
            isClockRealtime = true,
        )
        assertFalse(resGeom.decision.isAlertCandidate)
    }

    // -------------------------------------------------------------------------
    // 8. Irrelevant crosswalk (no crosswalk in near corridor)
    // -------------------------------------------------------------------------

    @Test
    fun irrelevantCrosswalk_suppressesAdvisory() {
        val emptyMask = ByteArray(VisionContract.MODEL_WIDTH * VisionContract.MODEL_HEIGHT) // all 0 (other/none)
        val t = 1_000_000_000L
        val now = t + 10_000_000L
        val od = createOdEvidence(captureTimestampNanos = t)
        val base = createBaseAssessment(timestampNanos = t)

        val res = advisory.evaluateAndFuse(
            baseAssessment = base,
            odEvidence = od,
            classMask = emptyMask,
            motion = motion,
            currentGeometry = defaultGeometry,
            sessionToken = sessionToken,
            frameCaptureTimestampNanos = t,
            nowElapsedNanos = now,
            isClockRealtime = true,
        )

        assertFalse(res.decision.isAlertCandidate)
        assertEquals(TrafficSuppressionReason.NO_RELEVANT_CROSSWALK, res.decision.suppressionReason)
    }

    // -------------------------------------------------------------------------
    // 9. Low confidence or invalid box suppresses advisory
    // -------------------------------------------------------------------------

    @Test
    fun lowConfidence_or_invalidBox_suppressesAdvisory() {
        val classMask = createClassMask(crosswalkRatio = 0.50f)
        val t = 1_000_000_000L
        val now = t + 10_000_000L
        val base = createBaseAssessment(timestampNanos = t)

        // Low confidence: 0.40 (< 0.50)
        val lowConfOd = createOdEvidence(captureTimestampNanos = t, score = 0.40f)
        val resLow = advisory.evaluateAndFuse(
            baseAssessment = base,
            odEvidence = lowConfOd,
            classMask = classMask,
            motion = motion,
            currentGeometry = defaultGeometry,
            sessionToken = sessionToken,
            frameCaptureTimestampNanos = t,
            nowElapsedNanos = now,
            isClockRealtime = true,
        )
        assertFalse(resLow.decision.isAlertCandidate)

        // Invalid position: boxTop at bottom of screen (> 0.70f)
        val feetBoxOd = createOdEvidence(captureTimestampNanos = t, boxTop = 0.85f, boxBottom = 0.95f)
        val resFeet = advisory.evaluateAndFuse(
            baseAssessment = base,
            odEvidence = feetBoxOd,
            classMask = classMask,
            motion = motion,
            currentGeometry = defaultGeometry,
            sessionToken = sessionToken,
            frameCaptureTimestampNanos = t,
            nowElapsedNanos = now,
            isClockRealtime = true,
        )
        assertFalse(resFeet.decision.isAlertCandidate)
    }

    // -------------------------------------------------------------------------
    // 10. Stronger hazard priority: WARNING, DEGRADED, and obstacle CAUTION preserved intact
    // -------------------------------------------------------------------------

    @Test
    fun strongerHazard_warningAndObstacleCautionPreserved_c5EntryUpgraded() {
        val classMask = createClassMask(crosswalkRatio = 0.50f)
        val t = 1_000_000_000L
        val now = t + 10_000_000L
        val od = createOdEvidence(captureTimestampNanos = t)

        // Case A: Base is WARNING (W1_VEHICLE_APPROACH)
        val warningBase = createBaseAssessment(
            timestampNanos = t,
            severity = AlertSeverity.WARNING,
            cause = HazardCause.W1_VEHICLE_APPROACH,
            level = HazardLevel.NEAR_PATH_HAZARD,
            message = "정지. 전방 차량",
        )
        val resWarn = advisory.evaluateAndFuse(
            baseAssessment = warningBase,
            odEvidence = od,
            classMask = classMask,
            motion = motion,
            currentGeometry = defaultGeometry,
            sessionToken = sessionToken,
            frameCaptureTimestampNanos = t,
            nowElapsedNanos = now,
            isClockRealtime = true,
        )
        assertEquals(AlertSeverity.WARNING, resWarn.assessment.severity)
        assertEquals(HazardCause.W1_VEHICLE_APPROACH, resWarn.assessment.cause)
        assertEquals("정지. 전방 차량", resWarn.assessment.messageKorean)
        assertEquals(TrafficSuppressionReason.STRONGER_HAZARD_PRIORITY, resWarn.decision.suppressionReason)

        // Case B: Base is obstacle CAUTION (C1_OBJECT_IN_PATH)
        val obstacleCautionBase = createBaseAssessment(
            timestampNanos = t,
            severity = AlertSeverity.CAUTION,
            cause = HazardCause.C1_OBJECT_IN_PATH,
            level = HazardLevel.PATH_CAUTION,
            message = "전방 장애물 주의",
        )
        val resObstacle = advisory.evaluateAndFuse(
            baseAssessment = obstacleCautionBase,
            odEvidence = od,
            classMask = classMask,
            motion = motion,
            currentGeometry = defaultGeometry,
            sessionToken = sessionToken,
            frameCaptureTimestampNanos = t,
            nowElapsedNanos = now,
            isClockRealtime = true,
        )
        assertEquals(AlertSeverity.CAUTION, resObstacle.assessment.severity)
        assertEquals(HazardCause.C1_OBJECT_IN_PATH, resObstacle.assessment.cause)
        assertEquals("전방 장애물 주의", resObstacle.assessment.messageKorean)
        assertEquals(TrafficSuppressionReason.STRONGER_HAZARD_PRIORITY, resObstacle.decision.suppressionReason)

        // Case B2: Base is road edge CAUTION (C3_ROAD_EDGE) or path narrow (C4_PATH_NARROW)
        val roadEdgeBase = createBaseAssessment(
            timestampNanos = t,
            severity = AlertSeverity.CAUTION,
            cause = HazardCause.C3_ROAD_EDGE,
            level = HazardLevel.PATH_CAUTION,
            message = "오른쪽이 차도입니다",
        )
        val resRoadEdge = advisory.evaluateAndFuse(
            baseAssessment = roadEdgeBase,
            odEvidence = od,
            classMask = classMask,
            motion = motion,
            currentGeometry = defaultGeometry,
            sessionToken = sessionToken,
            frameCaptureTimestampNanos = t,
            nowElapsedNanos = now,
            isClockRealtime = true,
        )
        assertEquals(AlertSeverity.CAUTION, resRoadEdge.assessment.severity)
        assertEquals(HazardCause.C3_ROAD_EDGE, resRoadEdge.assessment.cause)
        assertEquals("오른쪽이 차도입니다", resRoadEdge.assessment.messageKorean)
        assertEquals(TrafficSuppressionReason.STRONGER_HAZARD_PRIORITY, resRoadEdge.decision.suppressionReason)

        // Case C: Base is UNKNOWN_DEGRADED
        val degradedBase = createBaseAssessment(
            timestampNanos = t,
            severity = AlertSeverity.DEGRADED,
            cause = HazardCause.DEGRADED,
            level = HazardLevel.UNKNOWN_DEGRADED,
            message = "주변 인식이 어렵습니다",
        )
        val resDegraded = advisory.evaluateAndFuse(
            baseAssessment = degradedBase,
            odEvidence = od,
            classMask = classMask,
            motion = motion,
            currentGeometry = defaultGeometry,
            sessionToken = sessionToken,
            frameCaptureTimestampNanos = t,
            nowElapsedNanos = now,
            isClockRealtime = true,
        )
        assertEquals(AlertSeverity.DEGRADED, resDegraded.assessment.severity)
        assertEquals(HazardLevel.UNKNOWN_DEGRADED, resDegraded.assessment.level)
        assertEquals("주변 인식이 어렵습니다", resDegraded.assessment.messageKorean)

        // Case D: Base is C5_CROSSWALK_ENTRY ("신호는 확인할 수 없습니다")
        // It must NOT be treated as a stronger obstacle hazard; when 2 fresh frames arrive, it is upgraded to C6!
        val c5Base = createBaseAssessment(
            timestampNanos = t,
            severity = AlertSeverity.CAUTION,
            cause = HazardCause.C5_CROSSWALK_ENTRY,
            level = HazardLevel.PATH_CAUTION,
            message = "횡단보도 앞입니다. 신호는 확인할 수 없습니다",
        )
        val resC5Frame1 = advisory.evaluateAndFuse(
            baseAssessment = c5Base,
            odEvidence = od,
            classMask = classMask,
            motion = motion,
            currentGeometry = defaultGeometry,
            sessionToken = sessionToken,
            frameCaptureTimestampNanos = t,
            nowElapsedNanos = now,
            isClockRealtime = true,
        )
        // Frame 1: confirmation pending
        assertNotEquals(TrafficSuppressionReason.STRONGER_HAZARD_PRIORITY, resC5Frame1.decision.suppressionReason)

        // Frame 2: fresh observation confirms presence
        val t2 = t + 200_000_000L
        val od2 = createOdEvidence(captureTimestampNanos = t2)
        val resC5Frame2 = advisory.evaluateAndFuse(
            baseAssessment = c5Base.copy(timestampNanos = t2),
            odEvidence = od2,
            classMask = classMask,
            motion = motion,
            currentGeometry = defaultGeometry,
            sessionToken = sessionToken,
            frameCaptureTimestampNanos = t2,
            nowElapsedNanos = t2 + 10_000_000L,
            isClockRealtime = true,
        )
        assertTrue(resC5Frame2.decision.isAlertCandidate)
        assertEquals(AlertSeverity.CAUTION, resC5Frame2.assessment.severity)
        assertEquals(HazardLevel.PATH_CAUTION, resC5Frame2.assessment.level)
        assertEquals(HazardCause.C6_CROSSWALK_SIGNAL_PRESENCE, resC5Frame2.assessment.cause)
        assertEquals("횡단보도 앞 신호등을 확인하세요", resC5Frame2.assessment.messageKorean)
    }

    // -------------------------------------------------------------------------
    // 11. Real approach ID increment after meaningful crossing loss
    // -------------------------------------------------------------------------

    @Test
    fun approachSettlement_requiresDistinctValidCameraFrames_rearmsAfterMeaningfulLoss() {
        val classMask = createClassMask(crosswalkRatio = 0.50f)
        val emptyMask = ByteArray(VisionContract.MODEL_WIDTH * VisionContract.MODEL_HEIGHT)
        var t = 1_000_000_000L

        // Approach 1: Frames 1 & 2 confirm presence
        val od1 = createOdEvidence(captureTimestampNanos = t)
        advisory.evaluateAndFuse(
            baseAssessment = createBaseAssessment(t),
            odEvidence = od1,
            classMask = classMask,
            motion = motion,
            currentGeometry = defaultGeometry,
            sessionToken = sessionToken,
            frameCaptureTimestampNanos = t,
            nowElapsedNanos = t + 10_000_000L,
            isClockRealtime = true,
        )

        t += 200_000_000L
        val od2 = createOdEvidence(captureTimestampNanos = t)
        val resApp1 = advisory.evaluateAndFuse(
            baseAssessment = createBaseAssessment(t),
            odEvidence = od2,
            classMask = classMask,
            motion = motion,
            currentGeometry = defaultGeometry,
            sessionToken = sessionToken,
            frameCaptureTimestampNanos = t,
            nowElapsedNanos = t + 10_000_000L,
            isClockRealtime = true,
        )
        assertTrue(resApp1.decision.isAlertCandidate)

        // Acknowledge Approach 1 delivery
        advisory.acknowledgeDelivered(resApp1.assessment)

        // Duplicate camera timestamp must NOT count toward loss settlement
        advisory.evaluateAndFuse(
            baseAssessment = createBaseAssessment(t),
            odEvidence = od2,
            classMask = emptyMask,
            motion = motion,
            currentGeometry = defaultGeometry,
            sessionToken = sessionToken,
            frameCaptureTimestampNanos = t, // duplicate!
            nowElapsedNanos = t + 10_000_000L,
            isClockRealtime = true,
        )

        // 1-frame crosswalk flicker does NOT settle approach (threshold is 5)
        t += 125_000_000L
        advisory.evaluateAndFuse(
            baseAssessment = createBaseAssessment(t),
            odEvidence = od2,
            classMask = emptyMask,
            motion = motion,
            currentGeometry = defaultGeometry,
            sessionToken = sessionToken,
            frameCaptureTimestampNanos = t,
            nowElapsedNanos = t + 10_000_000L,
            isClockRealtime = true,
        )

        // Crosswalk reappears immediately: still same approach -> suppressed!
        t += 125_000_000L
        val resFlicker = advisory.evaluateAndFuse(
            baseAssessment = createBaseAssessment(t),
            odEvidence = od2,
            classMask = classMask,
            motion = motion,
            currentGeometry = defaultGeometry,
            sessionToken = sessionToken,
            frameCaptureTimestampNanos = t,
            nowElapsedNanos = t + 10_000_000L,
            isClockRealtime = true,
        )
        assertEquals(TrafficSuppressionReason.APPROACH_ALREADY_WARNED, resFlicker.decision.suppressionReason)

        // Meaningful crossing loss: 6 consecutive distinct valid empty frames (> settlementFrames 5)
        for (i in 0 until 6) {
            t += 125_000_000L
            advisory.evaluateAndFuse(
                baseAssessment = createBaseAssessment(t),
                odEvidence = od2,
                classMask = emptyMask,
                motion = motion,
                currentGeometry = defaultGeometry,
                sessionToken = sessionToken,
                frameCaptureTimestampNanos = t,
                nowElapsedNanos = t + 10_000_000L,
                isClockRealtime = true,
            )
        }

        // Approach 2: New crosswalk entry! Fresh observation confirms presence again
        t += 200_000_000L
        val od3 = createOdEvidence(captureTimestampNanos = t)
        advisory.evaluateAndFuse(
            baseAssessment = createBaseAssessment(t),
            odEvidence = od3,
            classMask = classMask,
            motion = motion,
            currentGeometry = defaultGeometry,
            sessionToken = sessionToken,
            frameCaptureTimestampNanos = t,
            nowElapsedNanos = t + 10_000_000L,
            isClockRealtime = true,
        )

        t += 200_000_000L
        val od4 = createOdEvidence(captureTimestampNanos = t)
        val resApp2 = advisory.evaluateAndFuse(
            baseAssessment = createBaseAssessment(t),
            odEvidence = od4,
            classMask = classMask,
            motion = motion,
            currentGeometry = defaultGeometry,
            sessionToken = sessionToken,
            frameCaptureTimestampNanos = t,
            nowElapsedNanos = t + 10_000_000L,
            isClockRealtime = true,
        )

        assertTrue("New approach after meaningful loss must be allowed to warn again", resApp2.decision.isAlertCandidate)
        assertEquals(HazardCause.C6_CROSSWALK_SIGNAL_PRESENCE, resApp2.assessment.cause)
    }

    // -------------------------------------------------------------------------
    // 12. Lifecycle resets
    // -------------------------------------------------------------------------

    @Test
    fun lifecycleResets_cleanlyResetTemporalState() {
        val classMask = createClassMask(crosswalkRatio = 0.50f)
        val t = 1_000_000_000L
        val od = createOdEvidence(captureTimestampNanos = t)

        advisory.evaluateAndFuse(
            baseAssessment = createBaseAssessment(t),
            odEvidence = od,
            classMask = classMask,
            motion = motion,
            currentGeometry = defaultGeometry,
            sessionToken = sessionToken,
            frameCaptureTimestampNanos = t,
            nowElapsedNanos = t + 10_000_000L,
            isClockRealtime = true,
        )

        // onCameraStop
        advisory.onCameraStop()
        val resStop = advisory.evaluateAndFuse(
            baseAssessment = createBaseAssessment(t + 200_000_000L),
            odEvidence = createOdEvidence(t + 200_000_000L),
            classMask = classMask,
            motion = motion,
            currentGeometry = defaultGeometry,
            sessionToken = sessionToken,
            frameCaptureTimestampNanos = t + 200_000_000L,
            nowElapsedNanos = t + 210_000_000L,
            isClockRealtime = true,
        )
        // Must restart confirmation from frame 1
        assertEquals(TrafficSuppressionReason.CONFIRMATION_PENDING, resStop.decision.suppressionReason)

        // onPipelineError
        advisory.onPipelineError(RuntimeException("test error"))
        // onSessionChange
        advisory.onSessionChange(2002L)
        // onGeometryChange
        advisory.onGeometryChange(3003L)
        // reset
        advisory.reset()
    }
}
