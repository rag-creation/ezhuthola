package com.rr.numio.ezhuthola.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The image maths behind "make your own sticker". Plain Kotlin on pixel arrays,
 * so it runs offline, needs no library, and can be unit-tested on a laptop.
 *
 * A mask is one byte per pixel: 0 = cut away, 255 = kept.
 */
object StickerMath {

    /**
     * Magic wand: tap the background and every connected pixel of a similar colour disappears.
     * Compares against the tapped colour (not the neighbour), so it stops at real edges
     * instead of creeping through a slow gradient. Only pixels still kept can be removed.
     *
     * @param tolerance 0..255: how different a colour may be and still count as "the same".
     * @return how many pixels were removed.
     */
    fun magicWand(pixels: IntArray, mask: ByteArray, width: Int, height: Int, x: Int, y: Int, tolerance: Int): Int {
        if (x !in 0 until width || y !in 0 until height) return 0
        val start = y * width + x
        if (mask[start].toInt() == 0) return 0
        val seed = pixels[start]
        val visited = BooleanArray(width * height)
        val queue = IntArray(width * height)
        var head = 0
        var tail = 0
        queue[tail++] = start
        visited[start] = true
        var removed = 0
        while (head < tail) {
            val i = queue[head++]
            mask[i] = 0
            removed++
            val px = i % width
            val py = i / width
            fun visit(n: Int) {
                if (!visited[n]) {
                    visited[n] = true
                    if (mask[n].toInt() != 0 && colorDistance(pixels[n], seed) <= tolerance) queue[tail++] = n
                }
            }
            if (px > 0) visit(i - 1)
            if (px < width - 1) visit(i + 1)
            if (py > 0) visit(i - width)
            if (py < height - 1) visit(i + width)
        }
        return removed
    }

    /** 0..255. Black vs white = 255; two shades of the same wall ≈ 10–30. */
    fun colorDistance(a: Int, b: Int): Int {
        val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        // Weighted towards green like the eye, scaled so the largest difference is 255.
        return ((dr * 3 + dg * 4 + db * 2) / 9)
    }

    /** Smallest box holding every kept pixel: [left, top, right, bottom] (right/bottom exclusive), or null if empty. */
    fun bounds(alpha: ByteArray, width: Int, height: Int, threshold: Int = 16): IntArray? {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if ((alpha[row + x].toInt() and 0xFF) > threshold) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    bottom = y
                }
            }
        }
        return if (right < 0) null else intArrayOf(left, top, right + 1, bottom + 1)
    }

    /**
     * Distance from every pixel to the nearest kept pixel (0 inside the shape).
     * Exact Euclidean distance (Felzenszwalb & Huttenlocher), fast enough for 512×512.
     */
    fun distanceToShape(alpha: ByteArray, width: Int, height: Int, threshold: Int = 128): FloatArray {
        val far = 1e20
        val grid = DoubleArray(width * height) { if ((alpha[it].toInt() and 0xFF) >= threshold) 0.0 else far }
        val n = max(width, height)
        val f = DoubleArray(n)
        val d = DoubleArray(n)
        val v = IntArray(n)
        val z = DoubleArray(n + 1)
        // Columns, then rows.
        for (x in 0 until width) {
            for (y in 0 until height) f[y] = grid[y * width + x]
            transform1d(f, height, d, v, z)
            for (y in 0 until height) grid[y * width + x] = d[y]
        }
        for (y in 0 until height) {
            for (x in 0 until width) f[x] = grid[y * width + x]
            transform1d(f, width, d, v, z)
            for (x in 0 until width) grid[y * width + x] = d[x]
        }
        return FloatArray(width * height) { sqrt(grid[it]).toFloat() }
    }

    private fun transform1d(f: DoubleArray, n: Int, d: DoubleArray, v: IntArray, z: DoubleArray) {
        var k = 0
        v[0] = 0
        z[0] = Double.NEGATIVE_INFINITY
        z[1] = Double.POSITIVE_INFINITY
        for (q in 1 until n) {
            var s = intersection(f, q, v[k])
            while (s <= z[k]) {
                k--
                s = intersection(f, q, v[k])
            }
            k++
            v[k] = q
            z[k] = s
            z[k + 1] = Double.POSITIVE_INFINITY
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val dq = (q - v[k]).toDouble()
            d[q] = dq * dq + f[v[k]]
        }
    }

    private fun intersection(f: DoubleArray, q: Int, p: Int): Double =
        ((f[q] + q.toDouble() * q) - (f[p] + p.toDouble() * p)) / (2.0 * q - 2.0 * p)

    /**
     * The white border around a sticker: the shape grown by [radius] pixels,
     * with a soft one-pixel edge so it looks smooth, not jagged.
     */
    fun outline(alpha: ByteArray, width: Int, height: Int, radius: Float): ByteArray {
        val dist = distanceToShape(alpha, width, height)
        return ByteArray(width * height) { i ->
            val edge = min(1f, max(0f, radius + 0.5f - dist[i]))
            max((edge * 255).toInt(), alpha[i].toInt() and 0xFF).toByte()
        }
    }

    /**
     * The outside shape only: see-through holes *inside* the cut-out (between letters, inside a
     * ring) are filled in, so the white border follows the outer edge instead of every letter.
     */
    fun fillHoles(alpha: ByteArray, width: Int, height: Int, threshold: Int = 16): ByteArray {
        val outside = BooleanArray(width * height)
        val queue = IntArray(width * height)
        var tail = 0
        fun seed(i: Int) {
            if (!outside[i] && (alpha[i].toInt() and 0xFF) <= threshold) {
                outside[i] = true
                queue[tail++] = i
            }
        }
        for (x in 0 until width) { seed(x); seed((height - 1) * width + x) }
        for (y in 0 until height) { seed(y * width); seed(y * width + width - 1) }
        var head = 0
        while (head < tail) {
            val i = queue[head++]
            val x = i % width
            val y = i / width
            if (x > 0) seed(i - 1)
            if (x < width - 1) seed(i + 1)
            if (y > 0) seed(i - width)
            if (y < height - 1) seed(i + width)
        }
        return ByteArray(width * height) { if (outside[it]) alpha[it] else -1 }
    }

    /**
     * Where a cut-out of size [w]×[h] goes in a square sticker of side [size], leaving
     * [margin] on every side for the outline: [left, top, scale].
     */
    fun fit(w: Int, h: Int, size: Int, margin: Int): FloatArray {
        val room = (size - 2 * margin).toFloat()
        val scale = room / max(w, h)
        val left = (size - w * scale) / 2f
        val top = (size - h * scale) / 2f
        return floatArrayOf(left, top, scale)
    }
}
