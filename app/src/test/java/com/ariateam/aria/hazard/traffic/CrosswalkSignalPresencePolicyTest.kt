package com.ariateam.aria.hazard.traffic

import com.ariateam.aria.hazard.AlertDecision
import com.ariateam.aria.hazard.AlertSeverity
import com.ariateam.aria.hazard.AlertSuppressionReason
import com.ariateam.aria.hazard.CorridorDirection
import com.ariateam.aria.hazard.HazardAlertPolicy
import com.ariateam.aria.hazard.HazardAssessment
import com.ariateam.aria.hazard.HazardCause
import com.ariateam.aria.hazard.HazardLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CrosswalkSignalPresencePolicyTest {

    private lateinit var policy: CrosswalkSignalPresencePolicy
    private val config = TrafficSignalPolicyConfig(
        presenceConfirmationFramesRequired = 2,
        staleObservationExpiryNanos = 1_500_000_000L, // 1.5s
        maxFutureToleranceNanos = 500_000_000L,
        maxConsecutiveCaptureGapNanos = 750_000_000L,
        minPresenceConfidence = 0.50f,
        minCrosswalkScore = 0.20f,
        presenceFallbackMessage = "횡단보도 앞 신호등을 확인하세요",
    )

    private val sessionToken = 101L
    private val geometryToken = 202L

    @Before
    fun setUp() {
        policy = CrosswalkSignalPresencePolicy(config)
    }

    private fun createCrosswalk(
        hasCrosswalk: Boolean = true,
        score: Float = 0.5f,
        approachId: Long = 1L,
    ) = CrosswalkRelevanceContext(
        hasCrosswalk = hasCrosswalk,
        crosswalkScore = score,
        isApproaching = true,
        approachId = approachId,
    )

    private fun createGenericSignal(
        signalId: Int = 2,
        confidence: Float = 0.75f,
        boxLeft: Float = 0.4f,
        boxRight: Float = 0.6f,
    ) = TrafficSignalObservation(
        signalId = signalId,
        type = TrafficSignalType.GENERIC_LIGHT,
        confidence = confidence,
        boxLeft = boxLeft,
        boxTop = 0.1f,
        boxRight = boxRight,
        boxBottom = 0.3f,
    )

    private fun makeInput(
        frameTimestampNanos: Long,
        obsTimestampNanos: Long = frameTimestampNanos,
        signals: List<TrafficSignalObservation> = listOf(createGenericSignal()),
        crosswalk: CrosswalkRelevanceContext = createCrosswalk(),
        isMotionReliable: Boolean = true,
        strongerHazardPresent: Boolean = false,
        strongerHazardSeverityRank: Int = 0,
        session: Long = sessionToken,
        geometry: Long = geometryToken,
    ) = TrafficPolicyInput(
        sessionToken = session,
        geometryToken = geometry,
        frameCaptureTimestampNanos = frameTimestampNanos,
        signalObservationCaptureTimestampNanos = obsTimestampNanos,
        signals = signals,
        crosswalk = crosswalk,
        isMotionReliable = isMotionReliable,
        strongerHazardPresent = strongerHazardPresent,
        strongerHazardSeverityRank = strongerHazardSeverityRank,
    )

    // -------------------------------------------------------------
    // 1. Two-clock rate difference: Camera (~8 Hz) vs OD Sidecar (5 Hz)
    // -------------------------------------------------------------

    @Test
    fun twoClock_odReusedAcrossCameraFrames_sustainsEligibleCandidateWithoutDoubleCounting() {
        val baseTime = 1_000_000_000L
        val camInterval = 125_000_000L // 125ms (~8 Hz)
        val odInterval = 200_000_000L  // 200ms (5 Hz)

        // Cam Frame 0: OD snapshot 0 (Fresh OD frame 1) -> Pending
        val d0 = policy.evaluate(
            makeInput(frameTimestampNanos = baseTime, obsTimestampNanos = baseTime),
            nowElapsedNanos = baseTime + 10_000_000L,
        )
        assertEquals(TrafficSuppressionReason.CONFIRMATION_PENDING, d0.suppressionReason)
        assertFalse(d0.isAlertCandidate)

        // Cam Frame 1 (125ms): OD snapshot 0 reused -> Still pending, duplicate OD not counted
        val tCam1 = baseTime + camInterval
        val d1 = policy.evaluate(
            makeInput(frameTimestampNanos = tCam1, obsTimestampNanos = baseTime),
            nowElapsedNanos = tCam1 + 10_000_000L,
        )
        assertEquals(TrafficSuppressionReason.CONFIRMATION_PENDING, d1.suppressionReason)

        // Cam Frame 2 (250ms): OD snapshot 1 arrives (200ms) (Fresh OD frame 2) -> ELIGIBLE!
        val tCam2 = baseTime + (camInterval * 2)
        val tOd1 = baseTime + odInterval
        val d2 = policy.evaluate(
            makeInput(frameTimestampNanos = tCam2, obsTimestampNanos = tOd1),
            nowElapsedNanos = tCam2 + 10_000_000L,
        )
        assertTrue(d2 is TrafficSignalDecision.CautionPresence)
        val caution = d2 as TrafficSignalDecision.CautionPresence
        assertEquals("횡단보도 앞 신호등을 확인하세요", caution.messageKorean)

        // Cam Frame 3 (375ms): OD snapshot 1 reused -> Candidate SUSTAINED for downstream
        val tCam3 = baseTime + (camInterval * 3)
        val d3 = policy.evaluate(
            makeInput(frameTimestampNanos = tCam3, obsTimestampNanos = tOd1),
            nowElapsedNanos = tCam3 + 10_000_000L,
        )
        assertTrue(d3 is TrafficSignalDecision.CautionPresence)
    }

    // -------------------------------------------------------------
    // 2. End-to-End Regression with real HazardAlertPolicy
    // -------------------------------------------------------------

    @Test
    fun endToEndRegression_deliversAlertThroughHazardAlertPolicy_andEnforcesOncePerApproach() {
        val hazardAlertPolicy = HazardAlertPolicy(
            confirmationFramesRequired = 2,
            cautionConfirmationFramesRequired = 3,
            alertCooldownMs = 2_500L,
            cautionCooldownMs = 6_000L,
            clockMs = { 10_000L },
        )

        val baseTime = 1_000_000_000L
        val camInterval = 125_000_000L // 125ms (~8 Hz)
        val odInterval = 200_000_000L  // 200ms (5 Hz)

        var firedAlert: AlertDecision.Fire? = null

        // Run across camera frames until delivered
        for (camIdx in 0..4) {
            val camTime = baseTime + (camIdx * camInterval)
            val odIdx = (camIdx * 125) / 200
            val odTime = baseTime + (odIdx * odInterval)
            val nowTime = camTime + 10_000_000L

            val trafficDecision = policy.evaluate(
                makeInput(frameTimestampNanos = camTime, obsTimestampNanos = odTime),
                nowElapsedNanos = nowTime,
            )

            val baseAssessment = HazardAssessment(
                timestampNanos = camTime,
                level = HazardLevel.CLEAR_UNWARNED,
                activeCorridor = CorridorDirection.CENTER,
                isAlertCandidate = false,
                severity = AlertSeverity.NONE,
                cause = HazardCause.NONE,
                messageKorean = "",
            )

            val assessmentToEvaluate = if (trafficDecision.isAlertCandidate && trafficDecision.candidate != null) {
                trafficDecision.candidate!!.applyTo(baseAssessment, HazardCause.C5_CROSSWALK_ENTRY)
            } else {
                baseAssessment
            }

            val alertDecision = hazardAlertPolicy.evaluate(
                assessment = assessmentToEvaluate,
                nowRealtimeMs = 10_000L + (camIdx * 125L),
                nowElapsedNanos = nowTime,
            )

            if (alertDecision is AlertDecision.Fire) {
                firedAlert = alertDecision
                policy.acknowledgeDelivered(
                    cause = trafficDecision.candidate!!.cause,
                    approachId = trafficDecision.candidate!!.approachId,
                )
            }
        }

        assertNotNull("HazardAlertPolicy must fire an alert", firedAlert)
        val fired = firedAlert!!
        assertEquals(AlertSeverity.CAUTION, fired.assessment.severity)
        assertEquals("횡단보도 앞 신호등을 확인하세요", fired.assessment.messageKorean)

        // Subsequent camera frame on same approach: Suppressed with APPROACH_ALREADY_WARNED
        val nextCam = baseTime + (5 * camInterval)
        val nextOd = baseTime + (2 * odInterval)
        val nextNow = nextCam + 10_000_000L
        val dAfterWarned = policy.evaluate(
            makeInput(frameTimestampNanos = nextCam, obsTimestampNanos = nextOd),
            nowElapsedNanos = nextNow,
        )
        assertEquals(TrafficSuppressionReason.APPROACH_ALREADY_WARNED, dAfterWarned.suppressionReason)
        assertFalse(dAfterWarned.isAlertCandidate)

        // New approach (approachId = 2L): Can fire again once!
        val newApproachCam0 = nextCam + camInterval
        val newApproachOd0 = nextOd + odInterval
        val dNew0 = policy.evaluate(
            makeInput(
                frameTimestampNanos = newApproachCam0,
                obsTimestampNanos = newApproachOd0,
                crosswalk = createCrosswalk(approachId = 2L),
            ),
            nowElapsedNanos = newApproachCam0 + 10_000_000L,
        )
        assertEquals(TrafficSuppressionReason.CONFIRMATION_PENDING, dNew0.suppressionReason)

        val newApproachCam1 = newApproachCam0 + camInterval
        val newApproachOd1 = newApproachOd0 + odInterval
        val dNew1 = policy.evaluate(
            makeInput(
                frameTimestampNanos = newApproachCam1,
                obsTimestampNanos = newApproachOd1,
                crosswalk = createCrosswalk(approachId = 2L),
            ),
            nowElapsedNanos = newApproachCam1 + 10_000_000L,
        )
        assertTrue(dNew1 is TrafficSignalDecision.CautionPresence)
    }

    // -------------------------------------------------------------
    // 3. Strict Geometry and Score Validation
    // -------------------------------------------------------------

    @Test
    fun strictGeometryValidation_requiresRightExceedsLeftAndBottomExceedsTop() {
        val zeroBox = TrafficSignalObservation(
            signalId = 1,
            confidence = 0.8f,
        )
        assertFalse(zeroBox.isValidGeometry)
        assertFalse(zeroBox.isValid)

        val validBox = TrafficSignalObservation(
            signalId = 1,
            confidence = 0.8f,
            boxLeft = 0.1f,
            boxTop = 0.1f,
            boxRight = 0.5f,
            boxBottom = 0.5f,
        )
        assertTrue(validBox.isValidGeometry)
        assertTrue(validBox.isValid)

        val invertedBox = validBox.copy(boxLeft = 0.5f, boxRight = 0.1f)
        assertFalse(invertedBox.isValidGeometry)
    }

    @Test
    fun crosswalkScoreValidation_mustBeInZeroToOneRange() {
        assertTrue(CrosswalkRelevanceContext(hasCrosswalk = true, crosswalkScore = 0.5f).isValid)
        assertTrue(CrosswalkRelevanceContext(hasCrosswalk = true, crosswalkScore = 0.0f).isValid)
        assertTrue(CrosswalkRelevanceContext(hasCrosswalk = true, crosswalkScore = 1.0f).isValid)

        assertFalse(CrosswalkRelevanceContext(hasCrosswalk = true, crosswalkScore = -0.01f).isValid)
        assertFalse(CrosswalkRelevanceContext(hasCrosswalk = true, crosswalkScore = 1.01f).isValid)
        assertFalse(CrosswalkRelevanceContext(hasCrosswalk = true, crosswalkScore = Float.NaN).isValid)
    }

    // -------------------------------------------------------------
    // 4. Timestamp validity, future, stale, gaps
    // -------------------------------------------------------------

    @Test
    fun timestampValidation_zeroNegativeFutureStale_rejectedFailClosed() {
        val now = 10_000_000_000L

        // Zero/negative timestamps
        val d0 = policy.evaluate(makeInput(0L, 0L), nowElapsedNanos = now)
        assertEquals(TrafficSuppressionReason.INVALID_CAPTURE_TIME, d0.suppressionReason)

        val dNeg = policy.evaluate(makeInput(-1L, 100L), nowElapsedNanos = now)
        assertEquals(TrafficSuppressionReason.INVALID_CAPTURE_TIME, dNeg.suppressionReason)

        val dNowZero = policy.evaluate(makeInput(100L, 100L), nowElapsedNanos = 0L)
        assertEquals(TrafficSuppressionReason.INVALID_CAPTURE_TIME, dNowZero.suppressionReason)

        // Future (> 500ms)
        val futureTime = now + config.maxFutureToleranceNanos + 100_000_000L
        val dFuture = policy.evaluate(makeInput(futureTime, futureTime), nowElapsedNanos = now)
        assertEquals(TrafficSuppressionReason.FUTURE_CAPTURE, dFuture.suppressionReason)

        // Stale (> 1.5s)
        val staleTime = now - config.staleObservationExpiryNanos - 100_000_000L
        val dStale = policy.evaluate(makeInput(now, staleTime), nowElapsedNanos = now)
        assertEquals(TrafficSuppressionReason.STALE_OBSERVATION, dStale.suppressionReason)
    }

    @Test
    fun frameDuplicateOrOutOfOrder_rejected() {
        val t1 = 1_000_000_000L
        policy.evaluate(makeInput(t1, t1), nowElapsedNanos = t1 + 10_000_000L)

        // Duplicate camera frame timestamp
        val dDup = policy.evaluate(makeInput(t1, t1), nowElapsedNanos = t1 + 20_000_000L)
        assertEquals(TrafficSuppressionReason.DUPLICATE_OR_OUT_OF_ORDER, dDup.suppressionReason)

        // Past camera frame timestamp
        val dPast = policy.evaluate(makeInput(t1 - 10_000_000L, t1), nowElapsedNanos = t1 + 30_000_000L)
        assertEquals(TrafficSuppressionReason.DUPLICATE_OR_OUT_OF_ORDER, dPast.suppressionReason)
    }

    @Test
    fun observationGap_exceedingMaxGap_resetsConsecutiveCount() {
        val t1 = 1_000_000_000L
        policy.evaluate(makeInput(t1, t1), nowElapsedNanos = t1 + 10_000_000L)

        // Gap of 900ms (> 750ms) between fresh observations
        val t2 = t1 + 900_000_000L
        val d2 = policy.evaluate(makeInput(t2, t2), nowElapsedNanos = t2 + 10_000_000L)
        assertEquals(TrafficSuppressionReason.CONFIRMATION_PENDING, d2.suppressionReason)
    }

    // -------------------------------------------------------------
    // 5. Session and Geometry migration & Lifecycle resets
    // -------------------------------------------------------------

    @Test
    fun sessionAndGeometryMigration_resetStateFailClosed() {
        val t1 = 1_000_000_000L
        policy.evaluate(makeInput(t1, t1, session = 101L, geometry = 202L), nowElapsedNanos = t1 + 10_000_000L)

        // Session change
        val t2 = t1 + 200_000_000L
        val dSession = policy.evaluate(makeInput(t2, t2, session = 102L, geometry = 202L), nowElapsedNanos = t2 + 10_000_000L)
        assertEquals(TrafficSuppressionReason.SESSION_MISMATCH, dSession.suppressionReason)

        // Geometry change
        val t3 = t2 + 200_000_000L
        val dGeometry = policy.evaluate(makeInput(t3, t3, session = 102L, geometry = 203L), nowElapsedNanos = t3 + 10_000_000L)
        assertEquals(TrafficSuppressionReason.GEOMETRY_MISMATCH, dGeometry.suppressionReason)
    }

    @Test
    fun cameraStopAndPipelineError_resetState() {
        val t1 = 1_000_000_000L
        policy.evaluate(makeInput(t1, t1), nowElapsedNanos = t1 + 10_000_000L)

        policy.onCameraStop()

        val t2 = t1 + 200_000_000L
        val d2 = policy.evaluate(makeInput(t2, t2), nowElapsedNanos = t2 + 10_000_000L)
        assertEquals(TrafficSuppressionReason.CONFIRMATION_PENDING, d2.suppressionReason)
    }

    // -------------------------------------------------------------
    // 6. Stronger hazard priority & Motion unreliability
    // -------------------------------------------------------------

    @Test
    fun strongerHazardPresent_suppressesTrafficCaution() {
        val t = 1_000_000_000L
        val dStronger = policy.evaluate(
            makeInput(t, t, strongerHazardPresent = true),
            nowElapsedNanos = t + 10_000_000L,
        )
        assertEquals(TrafficSuppressionReason.STRONGER_HAZARD_PRIORITY, dStronger.suppressionReason)
        assertFalse(dStronger.isAlertCandidate)
    }

    @Test
    fun unreliableMotion_suppressesTrafficCaution() {
        val t = 1_000_000_000L
        val dMotion = policy.evaluate(
            makeInput(t, t, isMotionReliable = false),
            nowElapsedNanos = t + 10_000_000L,
        )
        assertEquals(TrafficSuppressionReason.UNRELIABLE_MOTION, dMotion.suppressionReason)
        assertFalse(dMotion.isAlertCandidate)
    }

    // -------------------------------------------------------------
    // 7. Crosswalk relevance gate
    // -------------------------------------------------------------

    @Test
    fun crosswalkAbsentOrScoreTooLow_suppressesCaution() {
        val t = 1_000_000_000L
        val dNoCrosswalk = policy.evaluate(
            makeInput(t, t, crosswalk = createCrosswalk(hasCrosswalk = false, score = 0f)),
            nowElapsedNanos = t + 10_000_000L,
        )
        assertEquals(TrafficSuppressionReason.NO_RELEVANT_CROSSWALK, dNoCrosswalk.suppressionReason)
        assertFalse(dNoCrosswalk.isAlertCandidate)

        val t2 = t + 200_000_000L
        val dLowScore = policy.evaluate(
            makeInput(t2, t2, crosswalk = createCrosswalk(hasCrosswalk = true, score = 0.10f)),
            nowElapsedNanos = t2 + 10_000_000L,
        )
        assertEquals(TrafficSuppressionReason.NO_RELEVANT_CROSSWALK, dLowScore.suppressionReason)
    }

    // -------------------------------------------------------------
    // 8. Candidate applyTo HazardAssessment preserves stronger base
    // -------------------------------------------------------------

    @Test
    fun candidate_preservesStrongerBaseAssessment_andAppliesWhenClear() {
        val candidate = TrafficSignalAlertCandidate(
            severity = AlertSeverity.CAUTION,
            cause = TrafficSignalCause.CROSSWALK_SIGNAL_PRESENCE,
            messageKorean = "횡단보도 앞 신호등을 확인하세요",
            signalId = 42,
            direction = CorridorDirection.RIGHT,
            distanceMeters = 5.2f,
            confidence = 0.85f,
            riskScore = 0.50f,
            approachId = 1L,
        )

        // Case A: Base is CLEAR -> applied cleanly
        val clearBase = HazardAssessment(
            timestampNanos = 1_000_000_000L,
            level = HazardLevel.CLEAR_UNWARNED,
            activeCorridor = CorridorDirection.CENTER,
            isAlertCandidate = false,
            severity = AlertSeverity.NONE,
            cause = HazardCause.NONE,
            messageKorean = "",
        )
        val updated = candidate.applyTo(clearBase, HazardCause.C5_CROSSWALK_ENTRY)
        assertTrue(updated.isAlertCandidate)
        assertEquals(AlertSeverity.CAUTION, updated.severity)
        assertEquals(HazardCause.C5_CROSSWALK_ENTRY, updated.cause)
        assertEquals("횡단보도 앞 신호등을 확인하세요", updated.messageKorean)
        assertEquals(42, updated.trackId)
        assertEquals(CorridorDirection.RIGHT, updated.direction)

        // Case B: Base has existing WARNING (e.g. W1_VEHICLE_APPROACH) -> preserved intact!
        val warningBase = HazardAssessment(
            timestampNanos = 1_000_000_000L,
            level = HazardLevel.PATH_BLOCKED,
            activeCorridor = CorridorDirection.CENTER,
            isAlertCandidate = true,
            severity = AlertSeverity.WARNING,
            cause = HazardCause.W1_VEHICLE_APPROACH,
            messageKorean = "정지. 전방 3.0미터 차량",
        )
        val notWeakened = candidate.applyTo(warningBase, HazardCause.C5_CROSSWALK_ENTRY)
        assertEquals(AlertSeverity.WARNING, notWeakened.severity)
        assertEquals(HazardCause.W1_VEHICLE_APPROACH, notWeakened.cause)
        assertEquals("정지. 전방 3.0미터 차량", notWeakened.messageKorean)

        // Case C: Base is DEGRADED -> preserved intact!
        val degradedBase = HazardAssessment(
            timestampNanos = 1_000_000_000L,
            level = HazardLevel.UNKNOWN_DEGRADED,
            activeCorridor = CorridorDirection.CENTER,
            isAlertCandidate = false,
            severity = AlertSeverity.DEGRADED,
            cause = HazardCause.DEGRADED,
            messageKorean = "주변 인식이 어렵습니다",
        )
        val notOverridden = candidate.applyTo(degradedBase, HazardCause.C5_CROSSWALK_ENTRY)
        assertEquals(AlertSeverity.DEGRADED, notOverridden.severity)
        assertEquals(HazardCause.DEGRADED, notOverridden.cause)
    }
}
