package com.ariateam.aria.vision.od

import com.ariateam.aria.hazard.AlertSeverity
import com.ariateam.aria.hazard.CorridorDirection
import com.ariateam.aria.hazard.HazardAlertCoordinator
import com.ariateam.aria.hazard.HazardAlertPolicy
import com.ariateam.aria.hazard.HazardAssessment
import com.ariateam.aria.hazard.HazardCause
import com.ariateam.aria.hazard.HazardLevel
import com.ariateam.aria.hazard.MotionDegradedReason
import com.ariateam.aria.hazard.MotionState
import com.ariateam.aria.hazard.ObjectKind
import com.ariateam.aria.vision.UprightCropRect
import com.ariateam.aria.vision.VisionPreprocessingStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectDetectionHazardFusionTest {

    private val sessionToken = 42L

    private fun transform(): UprightCropRect = UprightCropRect(
        bufferWidth = 640,
        bufferHeight = 480,
        rotationDegrees = 90,
        uprightWidth = 480,
        uprightHeight = 640,
        left = 0,
        top = 0,
        width = 480,
        height = 640,
        strategy = VisionPreprocessingStrategy.FULL_FRAME_UPRIGHT_LETTERBOX,
        contentLeft = 0,
        contentTop = 80,
        contentWidth = 288,
        contentHeight = 512,
        isRotatedClockwise = true,
    )

    private fun geometry(): OdGeometryToken = OdGeometryToken.from(transform(), 90)

    private fun motion(
        corridor: CorridorDirection = CorridorDirection.CENTER,
        degradedReason: MotionDegradedReason? = null,
        confidence: Float = 0.9f,
    ): MotionState = MotionState(
        timestampNanos = 1_000L,
        isCalibrated = true,
        activeCorridor = corridor,
        confidence = confidence,
        degradedReason = degradedReason,
    )

    private fun base(
        level: HazardLevel = HazardLevel.CLEAR_UNWARNED,
        message: String = "base assessment",
    ): HazardAssessment = HazardAssessment(
        timestampNanos = 5_000_000_000L,
        level = level,
        activeCorridor = CorridorDirection.CENTER,
        dominantNonWalkableClass = 1,
        dominantNearClass = 1,
        corridorWalkableRatio = 0.82f,
        nearObstacleOccupancy = 0.1f,
        motionConfidence = 0.9f,
        messageKorean = message,
        isAlertCandidate = level.priority >= HazardLevel.PATH_BLOCKED.priority,
    )

    private fun detection(
        classId: Int = 2,
        label: String = "car",
        score: Float = 0.9f,
        left: Float = 0.35f,
        top: Float = 0.55f,
        right: Float = 0.55f,
        bottom: Float = 0.9f,
    ): DetectedObject = DetectedObject(classId, label, score, left, top, right, bottom)

    private fun evidence(
        captureTimestampNanos: Long,
        detections: List<DetectedObject>,
        geometry: OdGeometryToken = geometry(),
        session: Long = sessionToken,
        resultAgeNanos: Long = 30_000_000L,
    ): OdEvidence = OdEvidence(
        sessionToken = session,
        geometry = geometry,
        captureTimestampNanos = captureTimestampNanos,
        arrivalElapsedNanos = captureTimestampNanos,
        completedElapsedNanos = captureTimestampNanos + resultAgeNanos,
        resultAgeNanos = resultAgeNanos,
        detections = detections,
    )

    private fun fusion() = ObjectDetectionHazardFusion()

    private fun ObjectDetectionHazardFusion.pass(
        base: HazardAssessment = base(),
        motion: MotionState = motion(),
        evidence: OdEvidence?,
        frameCaptureTimestampNanos: Long,
        nowElapsedNanos: Long = frameCaptureTimestampNanos,
        geometry: OdGeometryToken = geometry(),
        sessionToken: Long = this@ObjectDetectionHazardFusionTest.sessionToken,
        isClockRealtime: Boolean = true,
    ): OdFusionOutcome = fuse(
        base = base,
        motion = motion,
        evidence = evidence,
        frameGeometry = geometry,
        frameCaptureTimestampNanos = frameCaptureTimestampNanos,
        nowElapsedNanos = nowElapsedNanos,
        sessionToken = sessionToken,
        isClockRealtime = isClockRealtime,
    )

    @Test
    fun singleObservationDoesNotProduceAdvisory() {
        val fusion = fusion()
        val t = 1_000_000_000L
        val outcome = fusion.pass(evidence = evidence(t, listOf(detection())), frameCaptureTimestampNanos = t + 50_000_000L)

        assertEquals(OdFusionRejection.CONTINUITY_PENDING, outcome.rejection)
        assertFalse(outcome.advisoryApplied)
        assertEquals(base(), outcome.assessment)
    }

    @Test
    fun twoDistinctOrderedObservationsApplyAdvisoryAndPreserveMaskFields() {
        val fusion = fusion()
        val t1 = 1_000_000_000L
        val t2 = t1 + 200_000_000L
        assertEquals(
            OdFusionRejection.CONTINUITY_PENDING,
            fusion.pass(evidence = evidence(t1, listOf(detection())), frameCaptureTimestampNanos = t1 + 50_000_000L).rejection,
        )
        val base = base()
        val outcome = fusion.pass(
            base = base,
            evidence = evidence(t2, listOf(detection())),
            frameCaptureTimestampNanos = t2 + 50_000_000L,
        )

        assertTrue(outcome.advisoryApplied)
        assertEquals(OdFusionRejection.ADVISORY_APPLIED, outcome.rejection)
        assertEquals(HazardLevel.NEAR_PATH_HAZARD, outcome.assessment.level)
        assertTrue(outcome.assessment.isAlertCandidate)
        assertTrue(outcome.assessment.messageKorean.contains("전방 장애물"))
        assertEquals(base.timestampNanos, outcome.assessment.timestampNanos)
        assertEquals(base.corridorWalkableRatio, outcome.assessment.corridorWalkableRatio, 0.0f)
        assertEquals(base.nearObstacleOccupancy, outcome.assessment.nearObstacleOccupancy, 0.0f)
        assertEquals(base.dominantNonWalkableClass, outcome.assessment.dominantNonWalkableClass)
        assertEquals(base.activeCorridor, outcome.assessment.activeCorridor)
    }

    @Test
    fun duplicateTimestampDoesNotCountTwice() {
        val fusion = fusion()
        val t = 1_000_000_000L
        fusion.pass(evidence = evidence(t, listOf(detection())), frameCaptureTimestampNanos = t + 50_000_000L)
        val outcome = fusion.pass(evidence = evidence(t, listOf(detection())), frameCaptureTimestampNanos = t + 50_000_000L)

        assertEquals(OdFusionRejection.CONTINUITY_PENDING, outcome.rejection)
        assertFalse(outcome.advisoryApplied)
    }

    @Test
    fun outOfOrderObservationBreaksConfirmation() {
        val fusion = fusion()
        val t2 = 1_100_000_000L
        val t1 = 1_000_000_000L
        fusion.pass(evidence = evidence(t2, listOf(detection())), frameCaptureTimestampNanos = t2 + 50_000_000L)
        val outcome = fusion.pass(evidence = evidence(t1, listOf(detection())), frameCaptureTimestampNanos = t1 + 50_000_000L)

        assertEquals(OdFusionRejection.CONTINUITY_PENDING, outcome.rejection)
        assertFalse(outcome.advisoryApplied)
    }

    @Test
    fun futureEvidenceIsRejected() {
        val fusion = fusion()
        val t = 1_000_000_000L
        val snapshot = evidence(t + 200_000_000L, listOf(detection()))
        val outcome = fusion.pass(
            evidence = snapshot,
            frameCaptureTimestampNanos = t,
            nowElapsedNanos = snapshot.arrivalElapsedNanos,
        )
        assertEquals(OdFusionRejection.FUTURE_EVIDENCE, outcome.rejection)
    }

    @Test
    fun tooOldEvidenceIsRejected() {
        val fusion = fusion()
        val t = 1_000_000_000L
        val outcome = fusion.pass(
            evidence = evidence(t, listOf(detection())),
            frameCaptureTimestampNanos = t + 900_000_000L,
        )
        assertEquals(OdFusionRejection.STALE_EVIDENCE, outcome.rejection)
    }

    @Test
    fun staleResultAgeIsRejected() {
        val fusion = fusion()
        val t = 1_000_000_000L
        val outcome = fusion.pass(
            evidence = evidence(t, listOf(detection()), resultAgeNanos = 300_000_000L),
            frameCaptureTimestampNanos = t + 50_000_000L,
        )
        assertEquals(OdFusionRejection.STALE_EVIDENCE, outcome.rejection)
    }

    @Test
    fun degradedMotionIsRejected() {
        val fusion = fusion()
        val t = 1_000_000_000L
        val outcome = fusion.pass(
            motion = motion(degradedReason = MotionDegradedReason.SAMPLES_STALE),
            evidence = evidence(t, listOf(detection())),
            frameCaptureTimestampNanos = t + 50_000_000L,
        )
        assertEquals(OdFusionRejection.MOTION_UNRELIABLE, outcome.rejection)
    }

    @Test
    fun nonRealtimeClockIsRejected() {
        val fusion = fusion()
        val t = 1_000_000_000L
        val outcome = fusion.pass(
            evidence = evidence(t, listOf(detection())),
            frameCaptureTimestampNanos = t + 50_000_000L,
            isClockRealtime = false,
        )
        assertEquals(OdFusionRejection.CLOCK_NOT_VERIFIED, outcome.rejection)
    }

    @Test
    fun unknownDegradedBaseIsNeverUpgraded() {
        val fusion = fusion()
        val t1 = 1_000_000_000L
        val t2 = t1 + 200_000_000L
        val unknown = base(HazardLevel.UNKNOWN_DEGRADED, "degraded")
        fusion.pass(base = unknown, evidence = evidence(t1, listOf(detection())), frameCaptureTimestampNanos = t1 + 50_000_000L)
        val outcome = fusion.pass(
            base = unknown,
            evidence = evidence(t2, listOf(detection())),
            frameCaptureTimestampNanos = t2 + 50_000_000L,
        )

        assertEquals(OdFusionRejection.BASE_UNKNOWN_DEGRADED, outcome.rejection)
        assertEquals(unknown, outcome.assessment)
    }

    @Test
    fun geometryMismatchIsRejectedAndResetsConfirmation() {
        val fusion = fusion()
        val t1 = 1_000_000_000L
        val t2 = t1 + 200_000_000L
        fusion.pass(evidence = evidence(t1, listOf(detection())), frameCaptureTimestampNanos = t1 + 50_000_000L)

        val otherTransform = transform().copy(rotationDegrees = 0, isRotatedClockwise = false)
        val otherGeometry = OdGeometryToken.from(otherTransform, 0)
        val outcome = fusion.pass(
            evidence = evidence(t2, listOf(detection()), geometry = otherGeometry),
            frameCaptureTimestampNanos = t2 + 50_000_000L,
        )
        assertEquals(OdFusionRejection.GEOMETRY_MISMATCH, outcome.rejection)
    }

    @Test
    fun differentSessionEvidenceIsRejected() {
        val fusion = fusion()
        val t = 1_000_000_000L
        val outcome = fusion.pass(
            evidence = evidence(t, listOf(detection()), session = sessionToken + 1),
            frameCaptureTimestampNanos = t + 50_000_000L,
        )
        assertEquals(OdFusionRejection.SESSION_MISMATCH, outcome.rejection)
    }

    @Test
    fun classChangeBreaksContinuity() {
        val fusion = fusion()
        val t1 = 1_000_000_000L
        val t2 = t1 + 200_000_000L
        val t3 = t2 + 200_000_000L
        fusion.pass(evidence = evidence(t1, listOf(detection(classId = 2))), frameCaptureTimestampNanos = t1 + 50_000_000L)
        val second = fusion.pass(
            evidence = evidence(t2, listOf(detection(classId = 0, label = "person"))),
            frameCaptureTimestampNanos = t2 + 50_000_000L,
        )
        assertEquals(OdFusionRejection.CONTINUITY_PENDING, second.rejection)

        val third = fusion.pass(
            evidence = evidence(t3, listOf(detection(classId = 0, label = "person"))),
            frameCaptureTimestampNanos = t3 + 50_000_000L,
        )
        assertTrue(third.advisoryApplied)
    }

    @Test
    fun lowIouBreaksBoxContinuity() {
        val fusion = fusion()
        val t1 = 1_000_000_000L
        val t2 = t1 + 200_000_000L
        fusion.pass(evidence = evidence(t1, listOf(detection())), frameCaptureTimestampNanos = t1 + 50_000_000L)
        val moved = detection(left = 0.55f, right = 0.68f)
        val outcome = fusion.pass(
            evidence = evidence(t2, listOf(moved)),
            frameCaptureTimestampNanos = t2 + 50_000_000L,
        )
        assertEquals(OdFusionRejection.CONTINUITY_PENDING, outcome.rejection)
    }

    @Test
    fun objectOutsideActiveCorridorIsIgnored() {
        val fusion = fusion()
        val t = 1_000_000_000L
        val outcome = fusion.pass(
            evidence = evidence(t, listOf(detection(left = 0.02f, right = 0.18f))),
            frameCaptureTimestampNanos = t + 50_000_000L,
        )
        assertEquals(OdFusionRejection.NO_ELIGIBLE_OBJECT, outcome.rejection)
    }

    @Test
    fun objectAboveLowerFrameBandIsIgnored() {
        val fusion = fusion()
        val t = 1_000_000_000L
        val outcome = fusion.pass(
            evidence = evidence(t, listOf(detection(top = 0.4f, bottom = 0.79f))),
            frameCaptureTimestampNanos = t + 50_000_000L,
        )
        assertEquals(OdFusionRejection.NO_ELIGIBLE_OBJECT, outcome.rejection)
    }

    @Test
    fun confidenceBelowThresholdIsIgnored() {
        val fusion = fusion()
        val t = 1_000_000_000L
        val outcome = fusion.pass(
            evidence = evidence(t, listOf(detection(score = 0.64f))),
            frameCaptureTimestampNanos = t + 50_000_000L,
        )
        assertEquals(OdFusionRejection.NO_ELIGIBLE_OBJECT, outcome.rejection)
    }

    @Test
    fun disallowedClassIsIgnored() {
        val fusion = fusion()
        val t = 1_000_000_000L
        val outcome = fusion.pass(
            evidence = evidence(t, listOf(detection(classId = 4, label = "bottle"))),
            frameCaptureTimestampNanos = t + 50_000_000L,
        )
        assertEquals(OdFusionRejection.NO_ELIGIBLE_OBJECT, outcome.rejection)
    }

    @Test
    fun nonFiniteBoxIsIgnored() {
        val fusion = fusion()
        val t = 1_000_000_000L
        val outcome = fusion.pass(
            evidence = evidence(t, listOf(detection(left = Float.NaN))),
            frameCaptureTimestampNanos = t + 50_000_000L,
        )
        assertEquals(OdFusionRejection.NO_ELIGIBLE_OBJECT, outcome.rejection)
    }

    @Test
    fun worseBaseHazardIsPreservedByteForByte() {
        val fusion = fusion()
        val t1 = 1_000_000_000L
        val t2 = t1 + 200_000_000L
        val blocked = base(HazardLevel.PATH_BLOCKED, "[위험] 진행 경로 차단")
        fusion.pass(base = blocked, evidence = evidence(t1, listOf(detection())), frameCaptureTimestampNanos = t1 + 50_000_000L)
        val outcome = fusion.pass(
            base = blocked,
            evidence = evidence(t2, listOf(detection())),
            frameCaptureTimestampNanos = t2 + 50_000_000L,
        )

        assertEquals(OdFusionRejection.BASE_ALREADY_WORSE, outcome.rejection)
        assertEquals(blocked, outcome.assessment)
        assertEquals(HazardLevel.PATH_BLOCKED, outcome.assessment.level)
    }

    @Test
    fun cautionBaseMayBeUpgradedToAdvisory() {
        val fusion = fusion()
        val t1 = 1_000_000_000L
        val t2 = t1 + 200_000_000L
        val caution = base(HazardLevel.PATH_CAUTION, "[주의] 진행 경로 협소")
        fusion.pass(base = caution, evidence = evidence(t1, listOf(detection())), frameCaptureTimestampNanos = t1 + 50_000_000L)
        val outcome = fusion.pass(
            base = caution,
            evidence = evidence(t2, listOf(detection())),
            frameCaptureTimestampNanos = t2 + 50_000_000L,
        )

        assertTrue(outcome.advisoryApplied)
        assertEquals(HazardLevel.NEAR_PATH_HAZARD, outcome.assessment.level)
        assertEquals(caution.corridorWalkableRatio, outcome.assessment.corridorWalkableRatio, 0.0f)
    }

    @Test
    fun emptyDetectionsResetTemporalConfirmation() {
        val fusion = fusion()
        val t1 = 1_000_000_000L
        val t2 = t1 + 200_000_000L
        val t3 = t2 + 200_000_000L
        fusion.pass(evidence = evidence(t1, listOf(detection())), frameCaptureTimestampNanos = t1 + 50_000_000L)
        val empty = fusion.pass(
            evidence = evidence(t2, emptyList()),
            frameCaptureTimestampNanos = t2 + 50_000_000L,
        )
        assertEquals(OdFusionRejection.EMPTY_DETECTIONS, empty.rejection)

        val afterEmpty = fusion.pass(
            evidence = evidence(t3, listOf(detection())),
            frameCaptureTimestampNanos = t3 + 50_000_000L,
        )
        assertEquals(OdFusionRejection.CONTINUITY_PENDING, afterEmpty.rejection)
    }

    @Test
    fun advisoryWordingAvoidsDistanceBlockageAndCollisionClaims() {
        val fusion = fusion()
        val t1 = 1_000_000_000L
        val t2 = t1 + 200_000_000L
        fusion.pass(evidence = evidence(t1, listOf(detection())), frameCaptureTimestampNanos = t1 + 50_000_000L)
        val outcome = fusion.pass(
            evidence = evidence(t2, listOf(detection())),
            frameCaptureTimestampNanos = t2 + 50_000_000L,
        )

        val message = outcome.assessment.messageKorean
        assertFalse(message.any { it.isDigit() })
        assertFalse(message.contains("미터"))
        assertFalse(message.contains("차단"))
        assertFalse(message.contains("충돌"))
        assertFalse(message.contains("횡단"))
    }

    @Test
    fun confirmedSnapshotSustainsAdvisoryOnFollowingCameraFrames() {
        val fusion = fusion()
        val t1 = 1_000_000_000L
        val t2 = t1 + 200_000_000L
        fusion.pass(evidence = evidence(t1, listOf(detection())), frameCaptureTimestampNanos = t1 + 50_000_000L)
        val confirmedOutcome = fusion.pass(
            evidence = evidence(t2, listOf(detection())),
            frameCaptureTimestampNanos = t2 + 50_000_000L,
        )
        assertTrue(confirmedOutcome.advisoryApplied)

        // Same OD snapshot reused by the next 20 Hz camera frame must sustain, not reset.
        val sustained = fusion.pass(
            evidence = evidence(t2, listOf(detection())),
            frameCaptureTimestampNanos = t2 + 100_000_000L,
            nowElapsedNanos = t2 + 100_000_000L,
        )
        assertTrue(sustained.advisoryApplied)
        assertEquals(HazardLevel.NEAR_PATH_HAZARD, sustained.assessment.level)
    }

    @Test
    fun nearPathBaseHazardIsPreserved() {
        val fusion = fusion()
        val t1 = 1_000_000_000L
        val t2 = t1 + 200_000_000L
        val near = base(HazardLevel.NEAR_PATH_HAZARD, "[주의] 근접 경로 장애물 추정")
        fusion.pass(base = near, evidence = evidence(t1, listOf(detection())), frameCaptureTimestampNanos = t1 + 50_000_000L)
        val outcome = fusion.pass(
            base = near,
            evidence = evidence(t2, listOf(detection())),
            frameCaptureTimestampNanos = t2 + 50_000_000L,
        )

        assertEquals(OdFusionRejection.BASE_ALREADY_WORSE, outcome.rejection)
        assertEquals(near, outcome.assessment)
        assertFalse(outcome.advisoryApplied)
    }

    @Test
    fun missingEvidenceResetsTemporalConfirmation() {
        val fusion = fusion()
        val t1 = 1_000_000_000L
        val t2 = t1 + 200_000_000L
        fusion.pass(evidence = evidence(t1, listOf(detection())), frameCaptureTimestampNanos = t1 + 50_000_000L)
        val missing = fusion.pass(evidence = null, frameCaptureTimestampNanos = t2)
        assertEquals(OdFusionRejection.NO_EVIDENCE, missing.rejection)

        val afterMissing = fusion.pass(
            evidence = evidence(t2, listOf(detection())),
            frameCaptureTimestampNanos = t2 + 50_000_000L,
        )
        assertEquals(OdFusionRejection.CONTINUITY_PENDING, afterMissing.rejection)
    }

    @Test
    fun currentMonotonicAgeRejectsExpiredEvidenceEvenWhenStaticAgeIsSmall() {
        val fusion = fusion()
        val t = 1_000_000_000L
        val snapshot = evidence(t, listOf(detection()), resultAgeNanos = 20_000_000L)
        val outcome = fusion.pass(
            evidence = snapshot,
            frameCaptureTimestampNanos = t + 50_000_000L,
            nowElapsedNanos = snapshot.arrivalElapsedNanos + 300_000_000L,
        )
        assertEquals(OdFusionRejection.STALE_EVIDENCE, outcome.rejection)
    }

    @Test
    fun alertPolicyAnnouncesOnceAcrossTwentyHzCameraAndFiveHzOd() {
        var nowNanos = 1_000_000_000L
        var vibrationCount = 0
        val coordinator = HazardAlertCoordinator(
            context = null,
            policy = HazardAlertPolicy(
                confirmationFramesRequired = 2,
                alertCooldownMs = 2_500L,
                staleResultExpiryMs = 1_500L,
                clockMs = { nowNanos / 1_000_000L },
            ),
            clockNanos = { nowNanos },
            vibratorDispatcher = { vibrationCount++ },
            toastDispatcher = { },
        )
        coordinator.start()
        try {
            val fusion = fusion()
            var latestEvidence: OdEvidence? = null
            val snapshotOffsetsMillis = listOf(0L, 200L, 400L, 600L)
            var nextSnapshot = 0
            val startNanos = nowNanos

            for (frame in 0..12) {
                nowNanos = startNanos + frame * 50L * 1_000_000L
                while (nextSnapshot < snapshotOffsetsMillis.size) {
                    val capture = startNanos + snapshotOffsetsMillis[nextSnapshot] * 1_000_000L
                    if (capture + 20_000_000L > nowNanos) break
                    latestEvidence = evidence(capture, listOf(detection()), resultAgeNanos = 20_000_000L)
                    nextSnapshot++
                }
                val outcome = fusion.fuse(
                    base = base().copy(timestampNanos = nowNanos),
                    motion = motion(),
                    evidence = latestEvidence,
                    frameGeometry = geometry(),
                    frameCaptureTimestampNanos = nowNanos,
                    nowElapsedNanos = nowNanos,
                    sessionToken = sessionToken,
                    isClockRealtime = true,
                )
                coordinator.processAssessment(outcome.assessment, nowNanos)
            }

            assertEquals("OD advisory must be announced exactly once per cooldown window", 1, vibrationCount)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun evidenceGate_acceptsValidEvidence_andRejectsInvalidOrDegraded() {
        val gate = OdEvidenceGate()
        val t = 1_000_000_000L
        val ev = evidence(t, listOf(detection()))

        // CLOCK_NOT_VERIFIED
        val resClock = gate.accept(
            evidence = ev,
            frameGeometry = geometry(),
            frameCaptureTimestampNanos = t + 50_000_000L,
            nowElapsedNanos = t + 50_000_000L,
            sessionToken = sessionToken,
            isClockRealtime = false,
            motion = motion(),
        )
        assertEquals(OdFusionRejection.CLOCK_NOT_VERIFIED, resClock.rejection)
        assertTrue(resClock.clearTemporal)

        // NO_EVIDENCE
        val resNoEv = gate.accept(
            evidence = null,
            frameGeometry = geometry(),
            frameCaptureTimestampNanos = t + 50_000_000L,
            nowElapsedNanos = t + 50_000_000L,
            sessionToken = sessionToken,
            isClockRealtime = true,
            motion = motion(),
        )
        assertEquals(OdFusionRejection.NO_EVIDENCE, resNoEv.rejection)
        assertTrue(resNoEv.clearTemporal)

        // SESSION_MISMATCH
        val resSession = gate.accept(
            evidence = ev,
            frameGeometry = geometry(),
            frameCaptureTimestampNanos = t + 50_000_000L,
            nowElapsedNanos = t + 50_000_000L,
            sessionToken = 9999L,
            isClockRealtime = true,
            motion = motion(),
        )
        assertEquals(OdFusionRejection.SESSION_MISMATCH, resSession.rejection)
        assertTrue(resSession.clearTemporal)

        // BASE_UNKNOWN_DEGRADED
        val resBaseDegraded = gate.accept(
            evidence = ev,
            frameGeometry = geometry(),
            frameCaptureTimestampNanos = t + 50_000_000L,
            nowElapsedNanos = t + 50_000_000L,
            sessionToken = sessionToken,
            isClockRealtime = true,
            motion = motion(),
            isBaseDegraded = true,
        )
        assertEquals(OdFusionRejection.BASE_UNKNOWN_DEGRADED, resBaseDegraded.rejection)
        assertTrue(resBaseDegraded.clearTemporal)

        // Valid evidence
        val resValid = gate.accept(
            evidence = ev,
            frameGeometry = geometry(),
            frameCaptureTimestampNanos = t + 50_000_000L,
            nowElapsedNanos = t + 50_000_000L,
            sessionToken = sessionToken,
            isClockRealtime = true,
            motion = motion(),
            isBaseDegraded = false,
        )
        assertEquals(OdFusionRejection.NONE, resValid.rejection)
        assertFalse(resValid.clearTemporal)
        assertEquals(ev, resValid.accepted)
    }

    @Test
    fun structuredBaseAssessment_severityNone_isUpgradedToCautionC1() {
        val fusion = fusion()
        val t1 = 1_000_000_000L
        val t2 = t1 + 200_000_000L
        val structuredBase = base().copy(
            severity = AlertSeverity.NONE,
            cause = HazardCause.NONE,
            level = HazardLevel.CLEAR_UNWARNED,
        )

        // Frame 1: first observation
        fusion.pass(
            base = structuredBase,
            evidence = evidence(t1, listOf(detection(classId = 2))), // car
            frameCaptureTimestampNanos = t1 + 50_000_000L,
        )

        // Frame 2: second confirming observation
        val outcome = fusion.pass(
            base = structuredBase,
            evidence = evidence(t2, listOf(detection(classId = 2))),
            frameCaptureTimestampNanos = t2 + 50_000_000L,
        )

        assertTrue(outcome.advisoryApplied)
        assertEquals(OdFusionRejection.ADVISORY_APPLIED, outcome.rejection)
        assertEquals(HazardLevel.NEAR_PATH_HAZARD, outcome.assessment.level)
        assertTrue(outcome.assessment.isAlertCandidate)
        assertEquals(AlertSeverity.CAUTION, outcome.assessment.severity)
        assertEquals(HazardCause.C1_OBJECT_IN_PATH, outcome.assessment.cause)
        assertEquals(ObjectKind.VEHICLE, outcome.assessment.objectKind)
    }

    @Test
    fun structuredBaseAssessment_severityWarning_isPreservedWithBaseAlreadyWorse() {
        val fusion = fusion()
        val t1 = 1_000_000_000L
        val t2 = t1 + 200_000_000L
        val warningBase = base().copy(
            severity = AlertSeverity.WARNING,
            cause = HazardCause.W3_ROAD_UNDERFOOT,
            level = HazardLevel.CLEAR_UNWARNED,
        )

        // Frame 1
        fusion.pass(
            base = warningBase,
            evidence = evidence(t1, listOf(detection(classId = 2))),
            frameCaptureTimestampNanos = t1 + 50_000_000L,
        )

        // Frame 2
        val outcome = fusion.pass(
            base = warningBase,
            evidence = evidence(t2, listOf(detection(classId = 2))),
            frameCaptureTimestampNanos = t2 + 50_000_000L,
        )

        assertFalse(outcome.advisoryApplied)
        assertEquals(OdFusionRejection.BASE_ALREADY_WORSE, outcome.rejection)
        assertEquals(warningBase, outcome.assessment)
    }
}
