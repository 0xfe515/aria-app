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
            VisionContract.CLASS_OVERLAY_ARGB.getOrElse(classId) { VisionContract.OVERLAY_COLOR_RED }
        }

    @Test
    fun `class 3 small component suppression`() {
        // Test that a small class 3 component (<32 pixels) is suppressed to red (no transparent holes)
        val width = 10
        val height = 10
        val mask = ByteArray(width * height) { 0 } // Background class 0 (red)
        // Insert a small 4-pixel component (2x2 square) of class 3 (green)
        mask[2 * width + 2] = 3
        mask[2 * width + 3] = 3
        mask[3 * width + 2] = 3
        mask[3 * width + 3] = 3
        val originalMask = mask.copyOf()
        val overlay = overlayFor(mask)

        OverlayComponentFilter().suppressSmallComponents(mask, overlay, width, height)

        // Class mask should be unchanged
        assertArrayEquals(originalMask, mask)
        // The 4-pixel green component should be suppressed to red
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[2 * width + 2])
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[2 * width + 3])
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[3 * width + 2])
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[3 * width + 3])
        // All other pixels should retain their red overlay color
        for (index in 0 until overlay.size) {
            val coords = index / width to index % width
            if (!(coords.first in 2..3 && coords.second in 2..3)) {
                assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[index])
            }
        }
    }

    @Test
    fun `class 3 small component near zone insensitivity`() {
        // Test that class 3 small components are suppressed to red even in near zone
        val width = 20
        val height = 20
        val mask = ByteArray(width * height) { 0 } // Background class 0
        val nearZoneStartRow = ((height.toLong() * OverlayComponentFilter.NEAR_ZONE_START_ROW + OverlayComponentFilter.MODEL_HEIGHT - 1) / OverlayComponentFilter.MODEL_HEIGHT).toInt()
        val componentRow = nearZoneStartRow
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
        // The 3-pixel component should be suppressed to red despite touching near zone
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[componentRow * width + componentCol])
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[(componentRow + 1) * width + componentCol])
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[(componentRow + 2) * width + componentCol])
    }

    @Test
    fun `class 4 near zone exemption`() {
        // Obstacles (class 4 = person) remain red even tiny; no transparent hole
        val width = 10
        val height = 10
        val mask = ByteArray(width * height) { 2 } // Default to sidewalk (green)
        val nearZoneStartRow = ((height.toLong() * OverlayComponentFilter.NEAR_ZONE_START_ROW + OverlayComponentFilter.MODEL_HEIGHT - 1) / OverlayComponentFilter.MODEL_HEIGHT).toInt()
        val componentRow = nearZoneStartRow + 1
        val componentCol = 5
        mask[componentRow * width + componentCol] = 4 // person
        val originalMask = mask.copyOf()
        val overlay = overlayFor(mask)

        OverlayComponentFilter().suppressSmallComponents(mask, overlay, width, height)

        assertArrayEquals(originalMask, mask)
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[componentRow * width + componentCol])
    }

    @Test
    fun `class 5 near zone exemption`() {
        // Obstacles (class 5 = vehicle) remain red even tiny
        val width = 10
        val height = 10
        val mask = ByteArray(width * height) { 2 } // Default to sidewalk (green)
        val nearZoneStartRow = ((height.toLong() * OverlayComponentFilter.NEAR_ZONE_START_ROW + OverlayComponentFilter.MODEL_HEIGHT - 1) / OverlayComponentFilter.MODEL_HEIGHT).toInt()
        val componentRow = nearZoneStartRow + 1
        val componentCol = 5
        mask[componentRow * width + componentCol] = 5 // vehicle
        val originalMask = mask.copyOf()
        val overlay = overlayFor(mask)

        OverlayComponentFilter().suppressSmallComponents(mask, overlay, width, height)

        assertArrayEquals(originalMask, mask)
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[componentRow * width + componentCol])
    }

    @Test
    fun `class 6 near zone exemption`() {
        // Obstacles (class 6 = pole_sign) remain red even tiny
        val width = 10
        val height = 10
        val mask = ByteArray(width * height) { 2 } // Default to sidewalk (green)
        val nearZoneStartRow = ((height.toLong() * OverlayComponentFilter.NEAR_ZONE_START_ROW + OverlayComponentFilter.MODEL_HEIGHT - 1) / OverlayComponentFilter.MODEL_HEIGHT).toInt()
        val componentRow = nearZoneStartRow + 1
        val componentCol = 5
        mask[componentRow * width + componentCol] = 6 // pole_sign
        val originalMask = mask.copyOf()
        val overlay = overlayFor(mask)

        OverlayComponentFilter().suppressSmallComponents(mask, overlay, width, height)

        assertArrayEquals(originalMask, mask)
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[componentRow * width + componentCol])
    }

    @Test
    fun `non near zone class 4 suppression`() {
        // In binary display, obstacles (person) anywhere in the frame remain red even tiny
        val width = 10
        val height = 10
        val mask = ByteArray(width * height) { 2 } // Default to sidewalk (green)
        val componentRow = 2
        val componentCol = 5
        mask[componentRow * width + componentCol] = 4 // person
        val originalMask = mask.copyOf()
        val overlay = overlayFor(mask)

        OverlayComponentFilter().suppressSmallComponents(mask, overlay, width, height)

        assertArrayEquals(originalMask, mask)
        // Obstacle remains red; no transparent hole punched
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[componentRow * width + componentCol])
    }

    @Test
    fun `large component preservation`() {
        // Verify that large components (>=32 pixels) of class 3 are preserved as green
        val width = 10
        val height = 10
        val mask = ByteArray(width * height) { 0 } // Background class 0 (red)
        // Create a 6x6 component (36 pixels) of class 3 (green)
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
        // All pixels in the component should be preserved as green
        for (row in 2..7) {
            for (col in 2..7) {
                assertEquals(VisionContract.OVERLAY_COLOR_GREEN, overlay[row * width + col])
            }
        }
        // Background pixels should remain red
        for (index in 0 until overlay.size) {
            val coords = index / width to index % width
            if (!(coords.first in 2..7 && coords.second in 2..7)) {
                assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[index])
            }
        }
    }

    @Test
    fun `diagonal connectivity`() {
        // Verify diagonal connections are considered
        val mask = ByteArray(4) { 0 } // Background class 0 (red)
        mask[0] = 3  // Top-left corner (green)
        mask[3] = 3  // Bottom-right corner (diagonal, green)
        val overlay = overlayFor(mask)

        OverlayComponentFilter().suppressSmallComponents(mask, overlay, width = 2, height = 2, minimumComponentPixels = 2)

        // With 2-pixel minimum, the two diagonal pixels are connected and preserved as green
        assertEquals(VisionContract.OVERLAY_COLOR_GREEN, overlay[0])
        assertEquals(VisionContract.OVERLAY_COLOR_GREEN, overlay[3])
        // Other pixels remain red
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[1])
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[2])
    }

    @Test
    fun `class isolation`() {
        val mask = ByteArray(4) { 0 }
        mask[0] = 3 // class 3 (green, size 1 < 2 -> suppressed to red)
        mask[1] = 4 // class 4 (obstacle -> remains red)
        val originalMask = mask.copyOf()
        val overlay = overlayFor(mask)

        OverlayComponentFilter().suppressSmallComponents(mask, overlay, width = 2, height = 2, minimumComponentPixels = 2)

        assertArrayEquals(originalMask, mask)
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[0])
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[1])
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[2])
        assertEquals(VisionContract.OVERLAY_COLOR_RED, overlay[3])
    }
}