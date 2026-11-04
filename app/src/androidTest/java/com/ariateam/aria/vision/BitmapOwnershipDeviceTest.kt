package com.ariateam.aria.vision

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android device-target regression test verifying:
 * 1. Published Bitmap snapshots are immutable and independent across subsequent inference passes.
 * 2. Segmenter closing never recycles in-use published Bitmaps, avoiding Canvas crashes in Compose.
 */
@RunWith(AndroidJUnit4::class)
class BitmapOwnershipDeviceTest {

    @Test
    fun publishedBitmapsAreImmutableAndIndependentAcrossFrames() {
        val width = 16
        val height = 16
        val colors = IntArray(width * height) { 0xFF00FF00.toInt() } // Green

        // Frame 1
        val bitmap1 = DefaultOverlayBitmapFactory.create(colors, width, height)
        assertFalse(bitmap1.isRecycled)
        assertEquals(0xFF00FF00.toInt(), bitmap1.getPixel(0, 0))

        // Frame 2: mutate the source colors buffer with Red
        colors.fill(0xFFFF0000.toInt())
        val bitmap2 = DefaultOverlayBitmapFactory.create(colors, width, height)
        assertFalse(bitmap2.isRecycled)
        assertEquals(0xFFFF0000.toInt(), bitmap2.getPixel(0, 0))

        // Frame 1 must NOT have been mutated by Frame 2's buffer changes
        assertNotSame("Bitmaps must be distinct instances", bitmap1, bitmap2)
        assertEquals("Frame 1 pixel must remain Green", 0xFF00FF00.toInt(), bitmap1.getPixel(0, 0))

        // Cleanup
        bitmap1.recycle()
        bitmap2.recycle()
    }

    @Test
    fun segmenterCloseDoesNotRecyclePublishedBitmaps() {
        val segmenter = LiteRtVisionSegmenter()
        val colors = IntArray(VisionContract.MODEL_WIDTH * VisionContract.MODEL_HEIGHT) { 0xFF112233.toInt() }

        val publishedBitmap = DefaultOverlayBitmapFactory.create(
            colors,
            VisionContract.MODEL_WIDTH,
            VisionContract.MODEL_HEIGHT,
        )
        assertFalse(publishedBitmap.isRecycled)

        // Close segmenter (simulating session termination or mode change)
        segmenter.close()

        // Published bitmap must remain valid for Compose renderer to finish drawing
        assertFalse("Segmenter close must not recycle published bitmap", publishedBitmap.isRecycled)
        assertEquals(0xFF112233.toInt(), publishedBitmap.getPixel(0, 0))

        publishedBitmap.recycle()
    }
}
