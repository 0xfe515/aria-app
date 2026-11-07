package com.ariateam.aria.hazard

import com.ariateam.aria.vision.VisionContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SegmentationFeatureExtractorTest {

    private lateinit var extractor: SegmentationFeatureExtractor
    private val width = VisionContract.MODEL_WIDTH
    private val height = VisionContract.MODEL_HEIGHT
    private val mask = ByteArray(width * height)

    @Before
    fun setUp() {
        extractor = SegmentationFeatureExtractor()
        // Default mask: all sidewalk (class 2: walkable)
        mask.fill(2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun extract_rejectsSmallerMaskBuffer() {
        extractor.extract(ByteArray(100), CorridorDirection.CENTER)
    }

    @Test
    fun allSidewalk_returnsHighWalkableZeroRoadZeroOther() {
        val features = extractor.extract(mask, CorridorDirection.CENTER)
        assertEquals(CorridorDirection.CENTER, features.corridor)
        assertEquals(1.0f, features.roiWalkable, 1e-4f)
        assertEquals(1.0f, features.nearWalkable, 1e-4f)
        assertEquals(0.0f, features.nearRoad, 1e-4f)
        assertEquals(0.0f, features.sideRoadLeft, 1e-4f)
        assertEquals(0.0f, features.sideRoadRight, 1e-4f)
        assertEquals(0.0f, features.nearCrosswalk, 1e-4f)
        assertEquals(0.0f, features.otherRatio, 1e-4f)
        assertEquals(-1, features.dominantRoiClass)
        assertEquals(-1, features.dominantNearClass)
        assertTrue(features.poleBlobs.isEmpty())
    }

    @Test
    fun sideRoadBands_leftAndRightCorridorEdgeClipping() {
        // LEFT corridor: startCol = 0, so left band is empty -> sideRoadLeft must be 0f
        // Fill right band (171..234) in near rows (230 until 288) with road (1)
        for (y in 230 until height) {
            val rowOffset = y * width
            for (x in 171..234) {
                mask[rowOffset + x] = 1 // road
            }
        }
        val leftFeatures = extractor.extract(mask, CorridorDirection.LEFT)
        assertEquals(0.0f, leftFeatures.sideRoadLeft, 1e-4f)
        assertEquals(1.0f, leftFeatures.sideRoadRight, 1e-4f)

        // Reset mask
        mask.fill(2)

        // RIGHT corridor: endCol = 511, so right band is empty -> sideRoadRight must be 0f
        // Fill left band (278..341) in near rows with road (1)
        for (y in 230 until height) {
            val rowOffset = y * width
            for (x in 278..341) {
                mask[rowOffset + x] = 1 // road
            }
        }
        val rightFeatures = extractor.extract(mask, CorridorDirection.RIGHT)
        assertEquals(1.0f, rightFeatures.sideRoadLeft, 1e-4f)
        assertEquals(0.0f, rightFeatures.sideRoadRight, 1e-4f)
    }

    @Test
    fun outOfRangeClassBytes_mappedToZeroAndCountsAsOther() {
        // Fill ROI center corridor with 0xFF (-1 or 255 unsigned)
        for (y in 158 until height) {
            val rowOffset = y * width
            for (x in 171..341) {
                mask[rowOffset + x] = 0xFF.toByte()
            }
        }
        val features = extractor.extract(mask, CorridorDirection.CENTER)
        assertEquals(0.0f, features.roiWalkable, 1e-4f)
        assertEquals(0.0f, features.nearWalkable, 1e-4f)
        assertEquals(1.0f, features.otherRatio, 1e-4f)
        assertEquals(0, features.dominantRoiClass)
        assertEquals(0, features.dominantNearClass)
    }

    @Test
    fun crosswalkAndRoadFractions_detectedAccurately() {
        // In near band (230 until 288, 58 rows), center corridor has 171 cols (171..341).
        // First 85 cols filled with road (1), remaining 86 cols filled with crosswalk (3).
        for (y in 230 until height) {
            val rowOffset = y * width
            for (x in 171..255) {
                mask[rowOffset + x] = 1 // road
            }
            for (x in 256..341) {
                mask[rowOffset + x] = 3 // crosswalk (walkable)
            }
        }
        val features = extractor.extract(mask, CorridorDirection.CENTER)
        val expectedRoad = (58 * 85).toFloat() / (58 * 171)
        val expectedCrosswalk = (58 * 86).toFloat() / (58 * 171)
        assertEquals(expectedRoad, features.nearRoad, 1e-3f)
        assertEquals(expectedCrosswalk, features.nearCrosswalk, 1e-3f)
        assertEquals(expectedCrosswalk, features.nearWalkable, 1e-3f)
        assertEquals(1, features.dominantNearClass)
    }

    @Test
    fun dominantClass_picksMostFrequentNonWalkable() {
        // In ROI rows (158..287), fill center corridor:
        // 100 pixels person (4), 200 pixels vehicle (5)
        var personPlaced = 0
        var vehiclePlaced = 0
        for (y in 158 until height) {
            val rowOffset = y * width
            for (x in 171..341) {
                if (personPlaced < 100) {
                    mask[rowOffset + x] = 4
                    personPlaced++
                } else if (vehiclePlaced < 200) {
                    mask[rowOffset + x] = 5
                    vehiclePlaced++
                }
            }
        }
        val features = extractor.extract(mask, CorridorDirection.CENTER)
        assertEquals(5, features.dominantRoiClass)
    }

    @Test
    fun poleBlobs_4ConnectedComponentDetectionAndThreshold() {
        // Blob 1: 7x7 block = 49 pixels (>= 40) at (x=200..206, y=200..206) inside ROI
        for (y in 200..206) {
            val rowOffset = y * width
            for (x in 200..206) {
                mask[rowOffset + x] = 6 // pole_sign
            }
        }

        // Blob 2: small 3x3 block = 9 pixels (< 40) at (x=300..302, y=200..202) -> must be ignored
        for (y in 200..202) {
            val rowOffset = y * width
            for (x in 300..302) {
                mask[rowOffset + x] = 6
            }
        }

        // Blob 3: 7x7 block = 49 pixels above ROI (y=100..106 < 158) -> must not be extracted
        for (y in 100..106) {
            val rowOffset = y * width
            for (x in 100..106) {
                mask[rowOffset + x] = 6
            }
        }

        val features = extractor.extract(mask, CorridorDirection.CENTER)
        assertEquals(1, features.poleBlobs.size)
        val blob = features.poleBlobs[0]
        assertEquals(6, blob.classId)
        assertEquals(49, blob.pixelCount)
        assertEquals(200, blob.minX)
        assertEquals(206, blob.maxX)
        assertEquals(200, blob.minY)
        assertEquals(206, blob.maxY)
    }
}
