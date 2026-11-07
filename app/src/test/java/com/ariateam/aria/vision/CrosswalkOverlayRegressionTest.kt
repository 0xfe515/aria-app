package com.ariateam.aria.vision

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for crosswalk visibility improvements in OverlayComponentFilter.
 * These tests verify that changes to the filter preserve existing behavior while
 * allowing evaluation of potential improvements for crosswalk detection.
 */
class CrosswalkOverlayRegressionTest {

    /**
     * Helper to create an overlay int array from a class mask.
     */
    private fun overlayFor(mask: ByteArray): IntArray =
        IntArray(mask.size) { index ->
            val classId = mask[index].toInt() and 0xFF
            // Use a default color for unknown classes
            VisionContract.CLASS_OVERLAY_ARGB.getOrElse(classId) { 0x80123456.toInt() }
        }

    @Test
    fun `class 3 small component suppression`() {
        // Test that a small class 3 component (<32 pixels) is suppressed
        val width = 10
        val height = 10
        val mask = ByteArray(width * height) { 0 } // Background class 0
        // Insert a small 4-pixel component (2x2 square) of class 3
        mask[2 * width + 2] = 3
        mask[2 * width + 3] = 3
        mask[3 * width + 2] = 3
        mask[3 * width + 3] = 3
        val originalMask = mask.copyOf()
        val overlay = overlayFor(mask)

        OverlayComponentFilter().suppressSmallComponents(mask, overlay, width, height)

        // Class mask should be unchanged
        assertArrayEquals(originalMask, mask)
        // The 4-pixel component should be suppressed (made transparent)
        assertEquals(0, overlay[2 * width + 2])
        assertEquals(0, overlay[2 * width + 3])
        assertEquals(0, overlay[3 * width + 2])
        assertEquals(0, overlay[3 * width + 3])
        // All other pixels should retain their overlay color (background)
        for (index in 0 until overlay.size) {
            val coords = index / width to index % width
            if (!(coords.first in 2..3 && coords.second in 2..3)) {
                assertEquals(VisionContract.CLASS_OVERLAY_ARGB[0], overlay[index])
            }
        }
    }

    @Test
    fun `class 3 small component near zone insensitivity`() {
        // Test that class 3 small components are suppressed even in near zone
        val width = 20
        val height = 20
        val mask = ByteArray(width * height) { 0 } // Background class 0
        // Place a small class 3 component in the near zone
        // Near zone start in output coordinates: based on MODEL_HEIGHT=288, NEAR_ZONE_START_ROW=230
        val nearZoneStartRow = ((height.toLong() * OverlayComponentFilter.NEAR_ZONE_START_ROW + OverlayComponentFilter.MODEL_HEIGHT - 1) / OverlayComponentFilter.MODEL_HEIGHT).toInt()
        // Place component so it touches the near zone but doesn't go out of bounds
        val componentRow = nearZoneStartRow  // First row of near zone
        val componentCol = 5
        // Create a 3-pixel component (vertical line) starting at near zone boundary
        mask[componentRow * width + componentCol] = 3
        mask[(componentRow + 1) * width + componentCol] = 3
        mask[(componentRow + 2) * width + componentCol] = 3
        val originalMask = mask.copyOf()
        val overlay = overlayFor(mask)

        OverlayComponentFilter().suppressSmallComponents(mask, overlay, width, height)

        // Class mask should be unchanged
        assertArrayEquals(originalMask, mask)
        // The 3-pixel component should be suppressed despite touching near zone
        assertEquals(0, overlay[componentRow * width + componentCol])
        assertEquals(0, overlay[(componentRow + 1) * width + componentCol])
        assertEquals(0, overlay[(componentRow + 2) * width + componentCol])
    }

    @Test
    fun `class 4 near zone exemption`() {
        // Verify existing behavior: class 4 small components are preserved when touching near zone
        val width = 10
        val height = 10
        val mask = ByteArray(width * height) { 2 } // Default to sidewalk
        val nearZoneStartRow = ((height.toLong() * OverlayComponentFilter.NEAR_ZONE_START_ROW + OverlayComponentFilter.MODEL_HEIGHT - 1) / OverlayComponentFilter.MODEL_HEIGHT).toInt()
        val componentRow = nearZoneStartRow + 1
        val componentCol = 5
        mask[componentRow * width + componentCol] = 4 // person
        val originalMask = mask.copyOf()
        val overlay = overlayFor(mask)

        OverlayComponentFilter().suppressSmallComponents(mask, overlay, width, height)

        // Class mask unchanged
        assertArrayEquals(originalMask, mask)
        // Class 4 component should be preserved (not suppressed)
        assertEquals(VisionContract.CLASS_OVERLAY_ARGB[4], overlay[componentRow * width + componentCol])
    }

    @Test
    fun `class 5 near zone exemption`() {
        // Verify existing behavior: class 5 small components are preserved when touching near zone
        val width = 10
        val height = 10
        val mask = ByteArray(width * height) { 2 } // Default to sidewalk
        val nearZoneStartRow = ((height.toLong() * OverlayComponentFilter.NEAR_ZONE_START_ROW + OverlayComponentFilter.MODEL_HEIGHT - 1) / OverlayComponentFilter.MODEL_HEIGHT).toInt()
        val componentRow = nearZoneStartRow + 1
        val componentCol = 5
        mask[componentRow * width + componentCol] = 5 // vehicle
        val originalMask = mask.copyOf()
        val overlay = overlayFor(mask)

        OverlayComponentFilter().suppressSmallComponents(mask, overlay, width, height)

        // Class mask unchanged
        assertArrayEquals(originalMask, mask)
        // Class 5 component should be preserved
        assertEquals(VisionContract.CLASS_OVERLAY_ARGB[5], overlay[componentRow * width + componentCol])
    }

    @Test
    fun `class 6 near zone exemption`() {
        // Verify existing behavior: class 6 small components are preserved when touching near zone
        val width = 10
        val height = 10
        val mask = ByteArray(width * height) { 2 } // Default to sidewalk
        val nearZoneStartRow = ((height.toLong() * OverlayComponentFilter.NEAR_ZONE_START_ROW + OverlayComponentFilter.MODEL_HEIGHT - 1) / OverlayComponentFilter.MODEL_HEIGHT).toInt()
        val componentRow = nearZoneStartRow + 1
        val componentCol = 5
        mask[componentRow * width + componentCol] = 6 // pole_sign
        val originalMask = mask.copyOf()
        val overlay = overlayFor(mask)

        OverlayComponentFilter().suppressSmallComponents(mask, overlay, width, height)

        // Class mask unchanged
        assertArrayEquals(originalMask, mask)
        // Class 6 component should be preserved
        assertEquals(VisionContract.CLASS_OVERLAY_ARGB[6], overlay[componentRow * width + componentCol])
    }

    @Test
    fun `non near zone class 4 suppression`() {
        // Verify that class 4 small components are suppressed when NOT in near zone
        val width = 10
        val height = 10
        val mask = ByteArray(width * height) { 2 } // Default to sidewalk
        // Place component well above near zone
        val componentRow = 2
        val componentCol = 5
        mask[componentRow * width + componentCol] = 4 // person
        val originalMask = mask.copyOf()
        val overlay = overlayFor(mask)

        OverlayComponentFilter().suppressSmallComponents(mask, overlay, width, height)

        // Class mask unchanged
        assertArrayEquals(originalMask, mask)
        // Single pixel component should be suppressed
        assertEquals(0, overlay[componentRow * width + componentCol])
    }

    @Test
    fun `large component preservation`() {
        // Verify that large components (>=32 pixels) are preserved
        val width = 10
        val height = 10
        val mask = ByteArray(width * height) { 0 } // Background class 0
        // Create a 6x6 component (36 pixels) of class 3
        for (row in 2..7) {
            for (col in 2..7) {
                mask[row * width + col] = 3
            }
        }
        val originalMask = mask.copyOf()
        val overlay = overlayFor(mask)

        OverlayComponentFilter().suppressSmallComponents(mask, overlay, width, height)

        // Class mask should be unchanged
        assertArrayEquals(originalMask, mask)
        // All pixels in the component should be preserved
        for (row in 2..7) {
            for (col in 2..7) {
                assertEquals(VisionContract.CLASS_OVERLAY_ARGB[3], overlay[row * width + col])
            }
        }
        // Background pixels should remain unchanged
        for (index in 0 until overlay.size) {
            val coords = index / width to index % width
            if (!(coords.first in 2..7 && coords.second in 2..7)) {
                assertEquals(VisionContract.CLASS_OVERLAY_ARGB[0], overlay[index])
            }
        }
    }

    @Test
    fun `diagonal connectivity`() {
        // Verify diagonal connections are considered (based on existing test)
        val mask = ByteArray(4) { 0 } // Background class 0
        mask[0] = 3  // Top-left corner
        mask[3] = 3  // Bottom-right corner (diagonal)
        val overlay = overlayFor(mask)

        OverlayComponentFilter().suppressSmallComponents(mask, overlay, width = 2, height = 2, minimumComponentPixels = 2)

        // With 2-pixel minimum, the two diagonal pixels should be considered connected
        // and thus preserved (since component size=2 >= minimum)
        assertEquals(VisionContract.CLASS_OVERLAY_ARGB[3], overlay[0])
        assertEquals(VisionContract.CLASS_OVERLAY_ARGB[3], overlay[3])
        // Other pixels should remain background
        assertEquals(VisionContract.CLASS_OVERLAY_ARGB[0], overlay[1]) // Top-right
        assertEquals(VisionContract.CLASS_OVERLAY_ARGB[0], overlay[2]) // Bottom-left
    }

    @Test
    fun `class isolation`() {
        // Verify different classes don't connect (based on existing test).
        // Start from background so each inserted class forms an isolated single-pixel
        // component; otherwise leftover class-3 pixels merge and exceed the minimum.
        val mask = ByteArray(4) { 0 }
        mask[0] = 3
        mask[1] = 4 // Different class
        val originalMask = mask.copyOf()
        val overlay = overlayFor(mask)

        OverlayComponentFilter().suppressSmallComponents(mask, overlay, width = 2, height = 2, minimumComponentPixels = 2)

        assertArrayEquals(originalMask, mask)
        assertEquals(0, overlay[0]) // Class 3 pixel suppressed (size=1 < 2)
        assertEquals(0, overlay[1]) // Class 4 pixel suppressed (size=1 < 2)
        assertEquals(VisionContract.CLASS_OVERLAY_ARGB[0], overlay[2]) // Background unchanged
        assertEquals(VisionContract.CLASS_OVERLAY_ARGB[0], overlay[3]) // Background unchanged
    }
}