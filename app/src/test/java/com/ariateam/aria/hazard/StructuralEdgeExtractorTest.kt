package com.ariateam.aria.hazard

import com.ariateam.aria.vision.VisionContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StructuralEdgeExtractorTest {

    private lateinit var extractor: StructuralEdgeExtractor
    private lateinit var engine: HazardRuleEngine

    private val width = VisionContract.MODEL_WIDTH
    private val height = VisionContract.MODEL_HEIGHT
    private val roiStartRow = VisionContract.ROI_START_ROW

    private val luma = FloatArray(width * height)
    private val mask = ByteArray(width * height)

    private val reliableWalkingMotion = MotionState(
        isCalibrated = true,
        confidence = 1.0f,
        degradedReason = null,
        activity = UserMovementActivity.WALKING,
        activeCorridor = CorridorDirection.CENTER,
    )

    private fun baseFeatures(
        corridor: CorridorDirection = CorridorDirection.CENTER,
        nearWalkable: Float = 0.8f,
        roiWalkable: Float = 0.8f,
        nearRoad: Float = 0.0f,
        sideRoadLeft: Float = 0.0f,
        sideRoadRight: Float = 0.0f,
        nearCrosswalk: Float = 0.0f,
        otherRatio: Float = 0.1f,
        dominantNearClass: Int = 2,
        dominantRoiClass: Int = 2,
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
        dominantNearClass = dominantNearClass,
        dominantRoiClass = dominantRoiClass,
        poleBlobs = poleBlobs,
    )

    private fun stubProjector(forwardMeters: Float, lateralMeters: Float = 0f) = PointProjector { _, _ ->
        GroundIntersection(
            forwardDistanceMeters = forwardMeters,
            lateralDistanceMeters = lateralMeters,
            totalDistanceMeters = forwardMeters,
            rayDistanceMeters = forwardMeters,
            intersectionPoint = Vector3D(lateralMeters, 0f, forwardMeters),
        )
    }

    @Before
    fun setUp() {
        extractor = StructuralEdgeExtractor()
        engine = HazardRuleEngine()
        luma.fill(0.5f)
        mask.fill(2) // Default all sidewalk
    }

    @Test
    fun wallFootline_sharpHorizontalStep_detectsAndConfirmsSecondFrame() {
        val projector1m = stubProjector(1.0f)
        val features = baseFeatures(corridor = CorridorDirection.CENTER)

        // Horizontal boundary at row 200 in center corridor (cols 171..341):
        // Rows roiStartRow..200: other (class 0), luma = 0.1f
        // Rows 201 until height: sidewalk (class 2), luma = 0.85f
        for (y in roiStartRow..200) {
            val offset = y * width
            for (x in 0 until width) {
                mask[offset + x] = 0 // other
                luma[offset + x] = 0.10f
            }
        }
        for (y in 201 until height) {
            val offset = y * width
            for (x in 0 until width) {
                mask[offset + x] = 2 // sidewalk
                luma[offset + x] = 0.85f
            }
        }

        // Frame 1: Initial detection (unconfirmed)
        val t1 = 100_000_000L
        val analysis1 = extractor.analyze(
            lumaOrRgb = luma,
            classMask = mask,
            features = features,
            captureNanos = t1,
            isWalking = true,
            projector = projector1m,
        )

        assertNotNull("Footline candidate must be detected on frame 1", analysis1.wallFootline)
        val footline1 = analysis1.wallFootline!!
        assertFalse("Frame 1 footline must not be confirmed yet", footline1.confirmed)
        assertEquals(1.0f, footline1.spanRatio, 1e-3f)
        assertEquals(1.0f, footline1.distanceMeters!!, 1e-3f)
        assertEquals(1, analysis1.syntheticTracks.size)
        val track1 = analysis1.syntheticTracks[0]
        assertEquals(-100, track1.id)
        assertEquals(ObjectKind.UNKNOWN, track1.kind)
        assertFalse("Frame 1 track must not be confirmed", track1.confirmed)

        // HazardRuleEngine filters unconfirmed tracks -> no static hazard yet
        val decision1 = engine.evaluate(analysis1.adjustedFeatures, analysis1.syntheticTracks, reliableWalkingMotion, t1)
        assertFalse("Unconfirmed track must not trigger W4_STATIC_IMMINENT", decision1.cause == HazardCause.W4_STATIC_IMMINENT)

        // Frame 2: 100ms later (gap <= 700ms, same boundary) -> confirms
        val t2 = 200_000_000L
        val analysis2 = extractor.analyze(
            lumaOrRgb = luma,
            classMask = mask,
            features = features,
            captureNanos = t2,
            isWalking = true,
            projector = projector1m,
        )

        assertNotNull("Footline candidate must persist on frame 2", analysis2.wallFootline)
        val footline2 = analysis2.wallFootline!!
        assertTrue("Frame 2 footline must be confirmed", footline2.confirmed)
        assertEquals(1, analysis2.syntheticTracks.size)
        val track2 = analysis2.syntheticTracks[0]
        assertTrue("Frame 2 track must be confirmed", track2.confirmed)

        // Evaluated by HazardRuleEngine with 1.0m (< 1.2m staticWarnMeters) -> triggers W4_STATIC_IMMINENT
        val decision2 = engine.evaluate(analysis2.adjustedFeatures, analysis2.syntheticTracks, reliableWalkingMotion, t2)
        assertEquals(AlertSeverity.WARNING, decision2.severity)
        assertEquals(HazardCause.W4_STATIC_IMMINENT, decision2.cause)
        assertTrue(decision2.messageKorean.contains("정지") && decision2.messageKorean.contains("장애물"))

        // Frame 2 with ~2.0m projector -> triggers C1_OBJECT_IN_PATH
        val projector2m = stubProjector(2.0f)
        extractor.reset()
        extractor.analyze(luma, mask, features, t1, isWalking = true, projector = projector2m)
        val analysisCaution = extractor.analyze(luma, mask, features, t2, isWalking = true, projector = projector2m)
        val decisionCaution = engine.evaluate(analysisCaution.adjustedFeatures, analysisCaution.syntheticTracks, reliableWalkingMotion, t2)
        assertEquals(AlertSeverity.CAUTION, decisionCaution.severity)
        assertEquals(HazardCause.C1_OBJECT_IN_PATH, decisionCaution.cause)
    }

    @Test
    fun texturedSidewalk_doesNotProduceFalseWallFootline() {
        // Entire ROI is walkable sidewalk (class 2) with high-frequency checkerboard texture
        mask.fill(2)
        for (y in roiStartRow until height) {
            val offset = y * width
            for (x in 0 until width) {
                luma[offset + x] = if (((x / 8) + (y / 8)) % 2 == 0) 0.15f else 0.85f
            }
        }

        val features = baseFeatures(corridor = CorridorDirection.CENTER)
        val analysis = extractor.analyze(
            lumaOrRgb = luma,
            classMask = mask,
            features = features,
            captureNanos = 100_000_000L,
            isWalking = true,
        )

        // Edges exist inside sidewalk, but no other boundary exists
        assertTrue("High texture creates edges", analysis.roiEdgeDensity > 0.05f)
        assertNull("Textured sidewalk must NOT produce a false WallFootlineCandidate", analysis.wallFootline)
        assertTrue("No synthetic tracks for false wall footline", analysis.syntheticTracks.isEmpty())
    }

    @Test
    fun verticalPillarEdge_inOtherGroundedOnSidewalk_producesConfirmedPoleTrack() {
        val projector2m = stubProjector(2.0f)
        val features = baseFeatures(corridor = CorridorDirection.CENTER)

        // Sidewalk ground at bottom rows 242..287 (class 2)
        // Background and pillar rows roiStartRow..241 are class 0 (other)
        for (y in 0..241) {
            val offset = y * width
            for (x in 0 until width) {
                mask[offset + x] = 0 // other
                luma[offset + x] = 0.2f
            }
        }
        for (y in 242 until height) {
            val offset = y * width
            for (x in 0 until width) {
                mask[offset + x] = 2 // sidewalk
                luma[offset + x] = 0.5f
            }
        }

        // Vertical pillar edge at column 256 from row 160 until row 240
        // Length = 81 rows >= 0.35 * 130 = 45.5 rows
        // Touches sidewalk at row 242 (gap 2 px <= boundaryBandPx = 6)
        for (y in 160..240) {
            val offset = y * width
            for (x in 256..270) {
                luma[offset + x] = 0.85f // high contrast step at col 256
            }
        }

        // Frame 1: Detection
        val t1 = 100_000_000L
        val analysis1 = extractor.analyze(
            lumaOrRgb = luma,
            classMask = mask,
            features = features,
            captureNanos = t1,
            isWalking = true,
            projector = projector2m,
        )
        assertFalse("At least one vertical candidate detected", analysis1.verticalCandidates.isEmpty())
        assertFalse("Frame 1 vertical candidate unconfirmed", analysis1.verticalCandidates[0].confirmed)

        // Frame 2: Confirmed after 2 consecutive frames
        val t2 = 200_000_000L
        val analysis2 = extractor.analyze(
            lumaOrRgb = luma,
            classMask = mask,
            features = features,
            captureNanos = t2,
            isWalking = true,
            projector = projector2m,
        )
        assertFalse(analysis2.verticalCandidates.isEmpty())
        val vertCand = analysis2.verticalCandidates[0]
        assertTrue("Frame 2 vertical candidate must be confirmed", vertCand.confirmed)

        // Must produce a confirmed POLE synthetic track
        val poleTrack = analysis2.syntheticTracks.firstOrNull { it.kind == ObjectKind.POLE }
        assertNotNull("Must produce synthetic POLE track", poleTrack)
        assertTrue("POLE track must be confirmed", poleTrack!!.confirmed)
        assertEquals(1.0f, poleTrack.corridorOverlap, 1e-4f)

        // HazardRuleEngine evaluates confirmed POLE track at 2.0m -> C1_OBJECT_IN_PATH
        val decision = engine.evaluate(analysis2.adjustedFeatures, analysis2.syntheticTracks, reliableWalkingMotion, t2)
        assertEquals(AlertSeverity.CAUTION, decision.severity)
        assertEquals(HazardCause.C1_OBJECT_IN_PATH, decision.cause)
        assertEquals(ObjectKind.POLE, decision.objectKind)
        assertTrue("Message must indicate pole", decision.messageKorean.contains("기둥"))
    }

    @Test
    fun blankWallLoomingSequence_triggersPathLostInsteadOfDegraded() {
        val t1 = 1_000_000_000L
        val walkingFeatures = baseFeatures(nearWalkable = 0.7f, otherRatio = 0.1f)

        // Frame 1: Walking with good walkable path
        extractor.analyze(luma, mask, walkingFeatures, t1, isWalking = true)

        // Frame 2: Approaching wall (nearWalkable 0.40f)
        val t2 = 1_200_000_000L
        val approachingFeatures = baseFeatures(nearWalkable = 0.40f, otherRatio = 0.3f)
        extractor.analyze(luma, mask, approachingFeatures, t2, isWalking = true)

        // Frame 3: Blank wall looming (200ms later, within 1.5s window)
        // Entire ROI is uniform blank wall (zero variance, zero edges, otherRatio = 0.85f > 0.6f)
        val t3 = 1_400_000_000L
        val blankWallLuma = FloatArray(width * height) { 0.40f }
        val blankWallMask = ByteArray(width * height) { 0 } // all other
        val loomingFeatures = baseFeatures(
            nearWalkable = 0.05f,
            roiWalkable = 0.05f,
            otherRatio = 0.85f,
        )

        val analysis3 = extractor.analyze(
            lumaOrRgb = blankWallLuma,
            classMask = blankWallMask,
            features = loomingFeatures,
            captureNanos = t3,
            isWalking = true,
        )

        assertTrue("isCloseWallOcclusion must be true for looming blank wall", analysis3.isCloseWallOcclusion)
        assertTrue("Luma variance must be <= threshold", analysis3.roiLumaVariance <= extractor.blankWallMaxVariance)
        assertTrue("Edge density must be <= threshold", analysis3.roiEdgeDensity <= extractor.blankWallMaxEdgeDensity)
        assertEquals("otherRatio must be adjusted to 0.5f to bypass DEGRADED", 0.5f, analysis3.adjustedFeatures.otherRatio, 1e-4f)

        // HazardRuleEngine evaluates adjusted features -> triggers W5_PATH_LOST instead of DEGRADED!
        val decision = engine.evaluate(analysis3.adjustedFeatures, analysis3.syntheticTracks, reliableWalkingMotion, t3)
        assertEquals(AlertSeverity.WARNING, decision.severity)
        assertEquals(HazardCause.W5_PATH_LOST, decision.cause)
        assertEquals("앞길이 막혔습니다", decision.messageKorean)
    }

    @Test
    fun immediateOccludedStartupFrame_remainsDegradedWithoutPriorHistory() {
        val t1 = 1_000_000_000L
        val darkLuma = FloatArray(width * height) { 0.05f }
        val darkMask = ByteArray(width * height) { 0 } // all other
        val occludedFeatures = baseFeatures(
            nearWalkable = 0.0f,
            roiWalkable = 0.0f,
            otherRatio = 0.90f,
        )

        // Fresh extractor without prior history
        val freshExtractor = StructuralEdgeExtractor()
        val analysis = freshExtractor.analyze(
            lumaOrRgb = darkLuma,
            classMask = darkMask,
            features = occludedFeatures,
            captureNanos = t1,
            isWalking = true,
        )

        assertFalse("Without prior walkable history, isCloseWallOcclusion must stay false", analysis.isCloseWallOcclusion)
        assertEquals(0.90f, analysis.adjustedFeatures.otherRatio, 1e-4f)

        // HazardRuleEngine evaluates unadjusted features -> DEGRADED
        val decision = engine.evaluate(analysis.adjustedFeatures, analysis.syntheticTracks, reliableWalkingMotion, t1)
        assertEquals(AlertSeverity.DEGRADED, decision.severity)
        assertEquals(HazardCause.DEGRADED, decision.cause)
        assertEquals("주변 인식이 어렵습니다", decision.messageKorean)
    }

    @Test
    fun analyze_acceptsUprightRgbInputForm() {
        val rgb = FloatArray(width * height * 3)
        // Fill RGB with known values: r = 0.5f, g = 0.5f, b = 0.5f -> luma = 0.5f
        for (i in 0 until width * height) {
            rgb[i * 3] = 0.5f
            rgb[i * 3 + 1] = 0.5f
            rgb[i * 3 + 2] = 0.5f
        }

        val features = baseFeatures()
        val analysis = extractor.analyze(
            lumaOrRgb = rgb,
            classMask = mask,
            features = features,
            captureNanos = 100_000_000L,
            isWalking = true,
        )
        assertNotNull(analysis)
        assertEquals(0f, analysis.roiLumaVariance, 1e-4f)
    }

    @Test
    fun reset_clearsTemporalTracking() {
        val projector1m = stubProjector(1.0f)
        val features = baseFeatures()

        // Setup horizontal edge
        for (y in roiStartRow..200) {
            val offset = y * width
            for (x in 0 until width) {
                mask[offset + x] = 0
                luma[offset + x] = 0.1f
            }
        }
        for (y in 201 until height) {
            val offset = y * width
            for (x in 0 until width) {
                mask[offset + x] = 2
                luma[offset + x] = 0.85f
            }
        }

        // Frame 1
        extractor.analyze(luma, mask, features, 100_000_000L, isWalking = true, projector = projector1m)

        // Reset
        extractor.reset()

        // Frame 2 after reset should be unconfirmed
        val analysisAfterReset = extractor.analyze(luma, mask, features, 200_000_000L, isWalking = true, projector = projector1m)
        assertNotNull(analysisAfterReset.wallFootline)
        assertFalse("Footline must NOT be confirmed on first frame after reset", analysisAfterReset.wallFootline!!.confirmed)
    }
}
