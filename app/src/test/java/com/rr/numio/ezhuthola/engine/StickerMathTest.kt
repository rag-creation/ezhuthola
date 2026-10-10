package com.rr.numio.ezhuthola.engine

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StickerMathTest {

    private val white = 0xFFFFFFFF.toInt()
    private val wall = 0xFFF0F0F0.toInt()   // a slightly grey white wall
    private val red = 0xFFD03020.toInt()

    /** 10×10 white picture with a red 4×4 square in the middle (x, y 3..6). */
    private fun picture(background: Int = white) = IntArray(100) { i ->
        val x = i % 10
        val y = i / 10
        if (x in 3..6 && y in 3..6) red else background
    }

    private fun full(n: Int) = ByteArray(n) { -1 }  // all kept

    @Test fun wandRemovesBackgroundButNotSubject() {
        val mask = full(100)
        val removed = StickerMath.magicWand(picture(), mask, 10, 10, 0, 0, tolerance = 20)
        assertEquals(100 - 16, removed)
        for (i in 0 until 100) {
            val inSquare = i % 10 in 3..6 && i / 10 in 3..6
            assertEquals(inSquare, mask[i].toInt() != 0, "pixel $i")
        }
    }

    @Test fun wandToleratesSimilarShades() {
        // Background mixes white and a slightly grey wall: one tap should still clear both.
        val pixels = picture().mapIndexed { i, c -> if (c == white && i % 2 == 0) wall else c }.toIntArray()
        val mask = full(100)
        StickerMath.magicWand(pixels, mask, 10, 10, 0, 0, tolerance = 30)
        assertEquals(16, mask.count { it.toInt() != 0 })
    }

    @Test fun wandDoesNothingOnAlreadyRemovedPixel() {
        val mask = ByteArray(100)
        assertEquals(0, StickerMath.magicWand(picture(), mask, 10, 10, 0, 0, tolerance = 20))
    }

    @Test fun boundsOfShape() {
        val mask = ByteArray(100) { i -> if (i % 10 in 3..6 && i / 10 in 2..7) -1 else 0 }
        assertContentEquals(intArrayOf(3, 2, 7, 8), StickerMath.bounds(mask, 10, 10))
        assertNull(StickerMath.bounds(ByteArray(100), 10, 10))
    }

    @Test fun distanceIsEuclidean() {
        // One kept pixel at (5, 5) in a 11×11 image.
        val mask = ByteArray(121).also { it[5 * 11 + 5] = -1 }
        val d = StickerMath.distanceToShape(mask, 11, 11)
        assertEquals(0f, d[5 * 11 + 5])
        assertEquals(3f, d[5 * 11 + 8], 0.001f)            // 3 to the right
        assertEquals(5f, d[(5 + 4) * 11 + (5 + 3)], 0.001f) // 3-4-5 triangle
    }

    @Test fun outlineGrowsShapeByRadius() {
        val mask = ByteArray(121).also { it[5 * 11 + 5] = -1 }
        val out = StickerMath.outline(mask, 11, 11, radius = 3f)
        fun a(x: Int, y: Int) = out[y * 11 + x].toInt() and 0xFF
        assertEquals(255, a(5, 5))
        assertEquals(255, a(7, 5))   // 2 away: inside the border
        assertTrue(a(8, 5) in 100..160) // exactly 3 away: the soft edge, half covered
        assertEquals(0, a(10, 5))    // 5 away: outside
        assertTrue(a(7, 7) > 100)    // ~2.8 away: on the soft edge
    }

    @Test fun fitKeepsMarginAndCentres() {
        val (left, top, scale) = StickerMath.fit(200, 100, size = 512, margin = 20).toList()
        assertEquals(472f / 200, scale, 0.0001f)
        assertEquals(20f, left, 0.001f)
        assertEquals((512 - 100 * scale) / 2, top, 0.001f)
    }
}
