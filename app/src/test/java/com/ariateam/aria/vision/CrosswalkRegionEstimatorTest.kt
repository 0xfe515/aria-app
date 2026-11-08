package com.ariateam.aria.vision

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Focused tests for [CrosswalkRegionEstimator].
 *
 * Verifies:
 * - Gap filling across horizontal, perspective, and diagonal zebra stripes.
 * - Strict non-bridging across distant separate crossings.
 * - Rejection of isolated single patches, sparse noise, and tiny mildly elongated blobs.
 * - Bounded candidate capacity under dense component stress.
 * - Preservation of obstacle holes (classes 4, 5, 6) and invalid class IDs as false.
 * - Border edge handling and boundary clamping.
 * - Input classMask immutability.
 * - Stale output clearing, scratch buffer reuse, and dynamic dimension changes.
 * - Robust argument validation (zero/negative dimensions, integer overflow, undersized arrays).
 * - Real helper-result synthetic before/after visual fixture artifact generation.
 */
class CrosswalkRegionEstimatorTest {

    private val estimator = CrosswalkRegionEstimator()

    @Test
    fun `horizontal zebra region gap fill`() {
        val width = 40
        val height = 40
        val mask = ByteArray(width * height) { CrosswalkRegionEstimator.CLASS_ROAD.toByte() }

        // Create 3 horizontal stripes (elongated in X, spaced along Y)
        // Stripe 0: Y = 10..12, X = 10..30 (length = 21, thickness = 3)
        // Stripe 1: Y = 18..20, X = 10..30
        // Stripe 2: Y = 26..28, X = 10..30
        for (y in 10..12) for (x in 10..30) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
        for (y in 18..20) for (x in 10..30) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
        for (y in 26..28) for (x in 10..30) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()

        val outRegion = BooleanArray(width * height)
        estimator.estimate(mask, width, height, outRegion)

        // Verify that stripes themselves are marked in outRegion
        assertTrue("Stripe 0 center should be in region", outRegion[11 * width + 20])
        assertTrue("Stripe 1 center should be in region", outRegion[19 * width + 20])
        assertTrue("Stripe 2 center should be in region", outRegion[27 * width + 20])

        // Verify that inter-stripe gaps (Y=14..16 and Y=22..24) are filled
        assertTrue("Gap between stripe 0 and 1 should be filled", outRegion[15 * width + 20])
        assertTrue("Gap between stripe 1 and 2 should be filled", outRegion[23 * width + 20])

        // Verify that outside the crossing remains false
        assertFalse("Above crossing should remain false", outRegion[5 * width + 20])
        assertFalse("Below crossing should remain false", outRegion[35 * width + 20])
        assertFalse("Left of crossing should remain false", outRegion[19 * width + 5])
        assertFalse("Right of crossing should remain false", outRegion[19 * width + 35])
    }

    @Test
    fun `perspective zebra region gap fill`() {
        val width = 60
        val height = 60
        val mask = ByteArray(width * height) { CrosswalkRegionEstimator.CLASS_ROAD.toByte() }

        // Perspective crossing converging toward vanishing point (smaller/closer together near top)
        // Stripe 0 (near camera, wider/longer): Y = 40..50, X = 15..20
        // Stripe 1 (middle): Y = 30..44, X = 27..31
        // Stripe 2 (middle): Y = 22..38, X = 37..40
        // Stripe 3 (far, shorter): Y = 16..32, X = 45..47
        for (y in 40..50) for (x in 15..20) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
        for (y in 30..44) for (x in 27..31) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
        for (y in 22..38) for (x in 37..40) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
        for (y in 16..32) for (x in 45..47) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()

        val outRegion = BooleanArray(width * height)
        estimator.estimate(mask, width, height, outRegion)

        // Verify stripes
        assertTrue(outRegion[45 * width + 17])
        assertTrue(outRegion[36 * width + 29])
        assertTrue(outRegion[28 * width + 38])
        assertTrue(outRegion[22 * width + 46])

        // Verify perspective gap bridging between stripes
        assertTrue("Gap between stripe 0 and 1 should be bridged", outRegion[38 * width + 24])
        assertTrue("Gap between stripe 1 and 2 should be bridged", outRegion[31 * width + 34])
        assertTrue("Gap between stripe 2 and 3 should be bridged", outRegion[25 * width + 43])

        // Verify areas far outside the perspective corridor remain false
        assertFalse(outRegion[5 * width + 5])
        assertFalse(outRegion[55 * width + 55])
        assertFalse(outRegion[10 * width + 20])
    }

    @Test
    fun `diagonal zebra region gap fill`() {
        val width = 50
        val height = 50
        val mask = ByteArray(width * height) { CrosswalkRegionEstimator.CLASS_ROAD.toByte() }

        // Three parallel diagonal stripes running at ~45 degrees (Y ~= X + offset)
        // Stripe 0: line from (10, 10) to (25, 25), thickness ~3
        // Stripe 1: line from (18, 10) to (33, 25), thickness ~3 (offset +8 in X)
        // Stripe 2: line from (26, 10) to (41, 25), thickness ~3 (offset +8 in X)
        for (t in 0..15) {
            for (w in 0..2) {
                val y = 10 + t
                val x0 = 10 + t + w
                val x1 = 18 + t + w
                val x2 = 26 + t + w
                mask[y * width + x0] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
                mask[y * width + x1] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
                mask[y * width + x2] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
            }
        }

        val outRegion = BooleanArray(width * height)
        estimator.estimate(mask, width, height, outRegion)

        // Verify stripes
        assertTrue(outRegion[(10 + 7) * width + (10 + 7 + 1)])
        assertTrue(outRegion[(10 + 7) * width + (18 + 7 + 1)])
        assertTrue(outRegion[(10 + 7) * width + (26 + 7 + 1)])

        // Verify diagonal inter-stripe gaps are filled
        assertTrue("Diagonal gap between stripe 0 and 1 should be filled", outRegion[17 * width + 21])
        assertTrue("Diagonal gap between stripe 1 and 2 should be filled", outRegion[17 * width + 29])

        // Verify far regions remain false
        assertFalse(outRegion[5 * width + 5])
        assertFalse(outRegion[45 * width + 45])
    }

    @Test
    fun `no far bridge across separate crossings`() {
        val width = 60
        val height = 100
        val mask = ByteArray(width * height) { CrosswalkRegionEstimator.CLASS_ROAD.toByte() }

        // Crossing A (near foreground, Y = 70..90)
        for (y in 72..74) for (x in 15..45) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
        for (y in 80..82) for (x in 15..45) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
        for (y in 88..90) for (x in 15..45) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()

        // Crossing B (distant background, Y = 10..30)
        for (y in 12..14) for (x in 15..45) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
        for (y in 20..22) for (x in 15..45) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
        for (y in 28..30) for (x in 15..45) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()

        val outRegion = BooleanArray(width * height)
        estimator.estimate(mask, width, height, outRegion)

        // Internal gaps within Crossing A must be filled
        assertTrue("Crossing A gap should be filled", outRegion[77 * width + 30])
        assertTrue("Crossing A gap should be filled", outRegion[85 * width + 30])

        // Internal gaps within Crossing B must be filled
        assertTrue("Crossing B gap should be filled", outRegion[17 * width + 30])
        assertTrue("Crossing B gap should be filled", outRegion[25 * width + 30])

        // Wide road region between Crossing A and B (Y = 35..65) must NEVER be bridged
        for (y in 35..65) {
            for (x in 10..50) {
                assertFalse("Road gap at y=$y, x=$x between distinct crossings must not be filled", outRegion[y * width + x])
            }
        }
    }

    @Test
    fun `noise and single patch rejection`() {
        val width = 40
        val height = 40
        val mask = ByteArray(width * height) { CrosswalkRegionEstimator.CLASS_ROAD.toByte() }

        // Sparse noise: 1-2 pixel isolated specs
        mask[5 * width + 5] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
        mask[5 * width + 6] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
        mask[12 * width + 30] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()

        // Single isolated patch (e.g. 1 stripe, no partners)
        for (y in 20..22) {
            for (x in 10..30) {
                mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
            }
        }

        val outRegion = BooleanArray(width * height)
        estimator.estimate(mask, width, height, outRegion)

        // Single isolated patches and noise must produce no filled region
        for (i in 0 until width * height) {
            assertFalse("Pixel $i should not be marked in outRegion for isolated/noise input", outRegion[i])
        }
    }

    @Test
    fun `negative test two tiny mildly elongated blobs separated by large gap`() {
        val width = 50
        val height = 50
        val mask = ByteArray(width * height) { CrosswalkRegionEstimator.CLASS_ROAD.toByte() }

        // Blob 1: size 2x5 (area 10) at (X=10..14, Y=10..11)
        for (y in 10..11) for (x in 10..14) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()

        // Blob 2: size 2x5 (area 10) at (X=10..14, Y=35..36), separated by 24 pixels (much larger than 1.5x blob length)
        for (y in 35..36) for (x in 10..14) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()

        val outRegion = BooleanArray(width * height)
        estimator.estimate(mask, width, height, outRegion)

        // The gap of 24 pixels between tiny 5-pixel blobs must NOT be bridged
        for (y in 12..34) {
            for (x in 5..20) {
                assertFalse("Gap at y=$y, x=$x between tiny distant blobs must not be bridged", outRegion[y * width + x])
            }
        }
    }

    @Test
    fun `dense components bounded to candidate capacity without overflow`() {
        val width = 100
        val height = 100
        val mask = ByteArray(width * height) { CrosswalkRegionEstimator.CLASS_ROAD.toByte() }

        // Create 80 separate small elongated components across a grid (exceeding MAX_CANDIDATE_STRIPES = 64)
        var count = 0
        for (gy in 0 until 9) {
            for (gx in 0 until 9) {
                if (count >= 80) break
                val y = 5 + gy * 10
                val x = 5 + gx * 10
                for (dy in 0..1) {
                    for (dx in 0..6) {
                        mask[(y + dy) * width + (x + dx)] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
                    }
                }
                count++
            }
        }

        val outRegion = BooleanArray(width * height)
        // Must complete deterministically without buffer overflow or exception
        estimator.estimate(mask, width, height, outRegion)
    }

    @Test
    fun `obstacle holes preserved as false`() {
        val width = 40
        val height = 40
        val mask = ByteArray(width * height) { CrosswalkRegionEstimator.CLASS_ROAD.toByte() }

        // 3 horizontal stripes
        for (y in 10..12) for (x in 10..30) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
        for (y in 18..20) for (x in 10..30) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
        for (y in 26..28) for (x in 10..30) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()

        // Place obstacle classes inside the inter-stripe gap
        // Person (class 4) in gap 0 at (15, 20)
        mask[15 * width + 20] = CrosswalkRegionEstimator.CLASS_PERSON.toByte()
        // Vehicle (class 5) in gap 1 at (23, 20)
        mask[23 * width + 20] = CrosswalkRegionEstimator.CLASS_VEHICLE.toByte()
        // Pole/sign (class 6) in gap 0 at (15, 25)
        mask[15 * width + 25] = CrosswalkRegionEstimator.CLASS_POLE_SIGN.toByte()

        // Also place obstacle directly on stripe 1 at (19, 20)
        mask[19 * width + 20] = CrosswalkRegionEstimator.CLASS_PERSON.toByte()

        val outRegion = BooleanArray(width * height)
        estimator.estimate(mask, width, height, outRegion)

        // Surrounding gap pixels must be filled
        assertTrue("Road gap next to obstacle should be filled", outRegion[15 * width + 18])
        assertTrue("Road gap next to obstacle should be filled", outRegion[15 * width + 22])

        // Obstacle pixels themselves MUST remain false (obstacle holes)
        assertFalse("Person obstacle in gap must remain false", outRegion[15 * width + 20])
        assertFalse("Vehicle obstacle in gap must remain false", outRegion[23 * width + 20])
        assertFalse("Pole obstacle in gap must remain false", outRegion[15 * width + 25])
        assertFalse("Person obstacle on stripe must remain false", outRegion[19 * width + 20])
    }

    @Test
    fun `invalid and unknown class IDs preserved as false`() {
        val width = 30
        val height = 30
        val mask = ByteArray(width * height) { CrosswalkRegionEstimator.CLASS_ROAD.toByte() }

        for (y in 8..10) for (x in 5..25) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
        for (y in 16..18) for (x in 5..25) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()

        // Invalid class IDs in the gap
        mask[13 * width + 10] = 7.toByte()
        mask[13 * width + 15] = 99.toByte()
        mask[13 * width + 20] = (-1).toByte() // 255 unsigned

        val outRegion = BooleanArray(width * height)
        estimator.estimate(mask, width, height, outRegion)

        // Normal road gap pixels filled
        assertTrue(outRegion[13 * width + 12])
        assertTrue(outRegion[13 * width + 17])

        // Invalid class IDs must remain false
        assertFalse("Class 7 must remain false", outRegion[13 * width + 10])
        assertFalse("Class 99 must remain false", outRegion[13 * width + 15])
        assertFalse("Class -1/255 must remain false", outRegion[13 * width + 20])
    }

    @Test
    fun `stripes touching image borders`() {
        val width = 20
        val height = 20
        val mask = ByteArray(width * height) { CrosswalkRegionEstimator.CLASS_ROAD.toByte() }

        // Stripes touching left and right edges (X = 0..19)
        // Stripe 0 touches top edge (Y = 0..2)
        // Stripe 1 at Y = 8..10
        // Stripe 2 touches bottom edge (Y = 17..19)
        for (y in 0..2) for (x in 0 until width) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
        for (y in 8..10) for (x in 0 until width) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
        for (y in 17..19) for (x in 0 until width) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()

        val outRegion = BooleanArray(width * height)
        estimator.estimate(mask, width, height, outRegion)

        // Should not crash and should fill border pixels
        assertTrue(outRegion[0 * width + 0])
        assertTrue(outRegion[0 * width + 19])
        assertTrue(outRegion[5 * width + 10])
        assertTrue(outRegion[14 * width + 10])
        assertTrue(outRegion[19 * width + 0])
        assertTrue(outRegion[19 * width + 19])
    }

    @Test
    fun `mask immutability`() {
        val width = 25
        val height = 25
        val mask = ByteArray(width * height) { CrosswalkRegionEstimator.CLASS_ROAD.toByte() }

        for (y in 5..7) for (x in 5..20) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
        for (y in 12..14) for (x in 5..20) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
        mask[9 * width + 12] = CrosswalkRegionEstimator.CLASS_PERSON.toByte()

        val maskCopy = mask.copyOf()
        val outRegion = BooleanArray(width * height)

        estimator.estimate(mask, width, height, outRegion)

        assertArrayEquals("Input classMask must remain completely immutable", maskCopy, mask)
    }

    @Test
    fun `stale output clearing and reuse`() {
        val width = 20
        val height = 20
        val emptyMask = ByteArray(width * height) { CrosswalkRegionEstimator.CLASS_ROAD.toByte() }

        // Pre-fill outRegion with true everywhere (stale state from prior frame)
        val outRegion = BooleanArray(width * height) { true }

        estimator.estimate(emptyMask, width, height, outRegion)

        // outRegion must be completely cleared to false
        for (i in 0 until width * height) {
            assertFalse("Stale output must be cleared at index $i", outRegion[i])
        }
    }

    @Test
    fun `dimension changes between frames`() {
        // Frame 1: 15x15
        val mask1 = ByteArray(15 * 15) { CrosswalkRegionEstimator.CLASS_ROAD.toByte() }
        val out1 = BooleanArray(15 * 15)
        estimator.estimate(mask1, 15, 15, out1)

        // Frame 2: 512x288 (canonical model resolution)
        val w2 = 512
        val h2 = 288
        val mask2 = ByteArray(w2 * h2) { CrosswalkRegionEstimator.CLASS_ROAD.toByte() }
        for (y in 100..115) for (x in 150..350) mask2[y * w2 + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
        for (y in 140..155) for (x in 150..350) mask2[y * w2 + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
        for (y in 180..195) for (x in 150..350) mask2[y * w2 + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
        val out2 = BooleanArray(w2 * h2)
        estimator.estimate(mask2, w2, h2, out2)

        assertTrue("Frame 2 gap should be filled", out2[125 * w2 + 250])
        assertTrue("Frame 2 gap should be filled", out2[165 * w2 + 250])

        // Frame 3: 30x20 (shrink again)
        val mask3 = ByteArray(30 * 20) { CrosswalkRegionEstimator.CLASS_ROAD.toByte() }
        val out3 = BooleanArray(30 * 20)
        estimator.estimate(mask3, 30, 20, out3)
        assertFalse(out3[0])
    }

    @Test
    fun `robust bounds and overflow validation`() {
        val outRegion = BooleanArray(100)
        val mask = ByteArray(100)

        // Zero / negative dimensions
        assertThrows(IllegalArgumentException::class.java) {
            estimator.estimate(mask, 0, 10, outRegion)
        }
        assertThrows(IllegalArgumentException::class.java) {
            estimator.estimate(mask, 10, -5, outRegion)
        }

        // Integer overflow dimensions
        assertThrows(IllegalArgumentException::class.java) {
            estimator.estimate(mask, Int.MAX_VALUE / 2, 3, outRegion)
        }

        // Undersized classMask
        assertThrows(IllegalArgumentException::class.java) {
            estimator.estimate(ByteArray(50), 10, 10, outRegion)
        }

        // Undersized outRegion
        assertThrows(IllegalArgumentException::class.java) {
            estimator.estimate(mask, 10, 10, BooleanArray(50))
        }
    }

    @Test
    fun `generate synthetic visual fixture report artifact`() {
        // Build 4 synthetic benchmark scenes:
        // 1. Horizontal crosswalk with obstacle hole
        // 2. Perspective crosswalk
        // 3. Diagonal crosswalk
        // 4. Two separate crossings with isolation barrier
        val width = 120
        val height = 80

        val scenes = listOf(
            "Horizontal_With_Obstacle" to createHorizontalScene(width, height),
            "Perspective_Convergence" to createPerspectiveScene(width, height),
            "Diagonal_Orientation" to createDiagonalScene(width, height),
            "Separate_Crossings_Isolation" to createSeparateCrossingsScene(width, height),
        )

        val baseDir = if (File("build").exists()) File("build/reports/crosswalk") else File("app/build/reports/crosswalk")
        baseDir.mkdirs()
        val svgFile = File(baseDir, "crosswalk_display_fixture.svg")

        val svgBuilder = StringBuilder()
        val totalWidth = 2 * (width * 3 + 40) + 40
        val totalHeight = scenes.size * (height * 3 + 60) + 80

        svgBuilder.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$totalWidth" height="$totalHeight" style="background:#1e1e1e; font-family:sans-serif;">""")
        svgBuilder.append("""<text x="20" y="35" fill="#ffffff" font-size="20" font-weight="bold">CrosswalkRegionEstimator: Synthetic Visual Evidence Fixture</text>""")

        var sceneOffsetY = 60
        val scale = 3

        for ((name, mask) in scenes) {
            val outRegion = BooleanArray(width * height)
            estimator.estimate(mask, width, height, outRegion)

            svgBuilder.append("""<text x="20" y="${sceneOffsetY + 20}" fill="#81c784" font-size="14" font-weight="bold">Scenario: $name</text>""")
            svgBuilder.append("""<text x="20" y="${sceneOffsetY + 38}" fill="#aaaaaa" font-size="11">Left: Raw Segmentation ClassMask | Right: Output Crosswalk Region Supplement (Filled Green + Preserved Obstacles)</text>""")

            val leftX = 20
            val rightX = leftX + width * scale + 30
            val drawY = sceneOffsetY + 45

            // Draw left (raw mask)
            svgBuilder.append("""<rect x="$leftX" y="$drawY" width="${width * scale}" height="${height * scale}" fill="#2a2a2a" stroke="#444444"/>""")
            // Draw right (output region overlay)
            svgBuilder.append("""<rect x="$rightX" y="$drawY" width="${width * scale}" height="${height * scale}" fill="#2a2a2a" stroke="#444444"/>""")

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val idx = y * width + x
                    val cid = mask[idx].toInt() and 0xFF

                    // Raw input color
                    val rawColor = when (cid) {
                        CrosswalkRegionEstimator.CLASS_CROSSWALK -> "#388e3c" // White/green stripe
                        CrosswalkRegionEstimator.CLASS_ROAD -> "#424242"      // Road
                        CrosswalkRegionEstimator.CLASS_PERSON -> "#fbc02d"    // Person yellow
                        CrosswalkRegionEstimator.CLASS_VEHICLE -> "#e91e63"   // Vehicle magenta
                        CrosswalkRegionEstimator.CLASS_POLE_SIGN -> "#00e5ff" // Pole cyan
                        else -> "#212121"
                    }
                    if (cid != CrosswalkRegionEstimator.CLASS_BACKGROUND && cid != CrosswalkRegionEstimator.CLASS_ROAD) {
                        svgBuilder.append("""<rect x="${leftX + x * scale}" y="${drawY + y * scale}" width="$scale" height="$scale" fill="$rawColor"/>""")
                    }

                    // Supplemented output region color
                    if (outRegion[idx]) {
                        svgBuilder.append("""<rect x="${rightX + x * scale}" y="${drawY + y * scale}" width="$scale" height="$scale" fill="#00e676"/>""")
                    } else if (cid == CrosswalkRegionEstimator.CLASS_PERSON || cid == CrosswalkRegionEstimator.CLASS_VEHICLE) {
                        // Highlight obstacle hole
                        svgBuilder.append("""<rect x="${rightX + x * scale}" y="${drawY + y * scale}" width="$scale" height="$scale" fill="$rawColor"/>""")
                    }
                }
            }

            sceneOffsetY += height * scale + 65
        }

        svgBuilder.append("</svg>")
        svgFile.writeText(svgBuilder.toString())
        assertTrue("Visual SVG fixture must exist", svgFile.exists() && svgFile.length() > 0)
    }

    private fun createHorizontalScene(width: Int, height: Int): ByteArray {
        val mask = ByteArray(width * height) { CrosswalkRegionEstimator.CLASS_ROAD.toByte() }
        for (y in 20..25) for (x in 20..100) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
        for (y in 35..40) for (x in 20..100) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
        for (y in 50..55) for (x in 20..100) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
        // Obstacle (person) in middle gap
        for (y in 28..32) for (x in 55..65) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_PERSON.toByte()
        return mask
    }

    private fun createPerspectiveScene(width: Int, height: Int): ByteArray {
        val mask = ByteArray(width * height) { CrosswalkRegionEstimator.CLASS_ROAD.toByte() }
        for (y in 50..65) for (x in 15..30) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
        for (y in 40..58) for (x in 42..52) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
        for (y in 30..50) for (x in 64..72) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
        for (y in 22..42) for (x in 82..88) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
        return mask
    }

    private fun createDiagonalScene(width: Int, height: Int): ByteArray {
        val mask = ByteArray(width * height) { CrosswalkRegionEstimator.CLASS_ROAD.toByte() }
        for (t in 0..30) {
            for (w in 0..4) {
                val y = 20 + t
                mask[y * width + (20 + t + w)] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
                mask[y * width + (40 + t + w)] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
                mask[y * width + (60 + t + w)] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
            }
        }
        return mask
    }

    private fun createSeparateCrossingsScene(width: Int, height: Int): ByteArray {
        val mask = ByteArray(width * height) { CrosswalkRegionEstimator.CLASS_ROAD.toByte() }
        // Foreground Crossing (Y = 55..75)
        for (y in 55..58) for (x in 30..90) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
        for (y in 65..68) for (x in 30..90) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
        // Distant Crossing (Y = 10..25)
        for (y in 10..13) for (x in 30..90) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
        for (y in 20..23) for (x in 30..90) mask[y * width + x] = CrosswalkRegionEstimator.CLASS_CROSSWALK.toByte()
        return mask
    }
}
