package com.paintlab.app.model

import kotlin.math.abs

object FloodFill {

    private class IntStack {
        var data = IntArray(1024)
        var size = 0
        fun push(v: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }
        fun pop(): Int = data[--size]
    }

    private fun close(a: Int, b: Int, tol: Int): Boolean {
        if (abs((a ushr 24) - (b ushr 24)) > tol) return false
        if (abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) > tol) return false
        if (abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) > tol) return false
        return abs((a and 0xFF) - (b and 0xFF)) <= tol
    }

    /**
     * Scanline flood fill over [px] starting at (sx, sy). Returns a mask
     * (1 = filled), grown by one pixel so anti-aliased line edges are covered.
     */
    fun mask(px: IntArray, w: Int, h: Int, sx: Int, sy: Int, tolerance: Int): ByteArray {
        val mark = ByteArray(w * h)
        val target = px[sy * w + sx]
        val stack = IntStack()

        fun matches(i: Int): Boolean = mark[i].toInt() == 0 && close(px[i], target, tolerance)

        stack.push(sy * w + sx)
        while (stack.size > 0) {
            val idx = stack.pop()
            if (!matches(idx)) continue
            val y = idx / w
            val row = y * w
            var xl = idx - row
            var xr = xl
            while (xl > 0 && matches(row + xl - 1)) xl--
            while (xr < w - 1 && matches(row + xr + 1)) xr++
            for (x in xl..xr) mark[row + x] = 1
            for (ny in intArrayOf(y - 1, y + 1)) {
                if (ny < 0 || ny >= h) continue
                val nrow = ny * w
                var inSpan = false
                for (x in xl..xr) {
                    val j = nrow + x
                    if (matches(j)) {
                        if (!inSpan) {
                            stack.push(j)
                            inSpan = true
                        }
                    } else {
                        inSpan = false
                    }
                }
            }
        }

        // Grow by 1 px (4-neighbourhood) to cover anti-aliased edges.
        val grown = mark.copyOf()
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                if (mark[i].toInt() != 0) continue
                if ((x > 0 && mark[i - 1].toInt() != 0) ||
                    (x < w - 1 && mark[i + 1].toInt() != 0) ||
                    (y > 0 && mark[i - w].toInt() != 0) ||
                    (y < h - 1 && mark[i + w].toInt() != 0)
                ) grown[i] = 1
            }
        }
        return grown
    }
}
