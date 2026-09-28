package com.paintlab.app.frames

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import kotlin.math.abs
import kotlin.math.hypot

/** A split line drawn by the user, in document pixels. */
data class Seg(val ax: Float, val ay: Float, val bx: Float, val by: Float)

typealias Poly = List<PointF>

/**
 * Comic-style frame divider. Panels are convex polygons. They are rebuilt from
 * scratch (outer margin -> grid -> user splits) whenever a setting changes, so
 * gutter / margin sliders always apply to every split.
 */
object FrameDivider {

    fun buildPanels(
        w: Int, h: Int,
        margin: Float, gutter: Float,
        rows: Int, cols: Int,
        splits: List<Seg>,
    ): List<Poly> {
        val left = margin
        val top = margin
        val right = w - margin
        val bottom = h - margin
        if (right - left < 8f || bottom - top < 8f) return emptyList()

        var panels: List<Poly> = listOf(rect(left, top, right, bottom))

        if (cols > 1) {
            val colW = (right - left - (cols - 1) * gutter) / cols
            for (i in 1 until cols) {
                val x = left + i * colW + (i - 0.5f) * gutter
                panels = split(panels, Seg(x, top - 10f, x, bottom + 10f), gutter)
            }
        }
        if (rows > 1) {
            val rowH = (bottom - top - (rows - 1) * gutter) / rows
            for (i in 1 until rows) {
                val y = top + i * rowH + (i - 0.5f) * gutter
                panels = split(panels, Seg(left - 10f, y, right + 10f, y), gutter)
            }
        }
        for (s in splits) panels = split(panels, s, gutter)
        return panels
    }

    private fun rect(l: Float, t: Float, r: Float, b: Float): Poly =
        listOf(PointF(l, t), PointF(r, t), PointF(r, b), PointF(l, b))

    /** Splits every panel the segment touches along the segment's infinite line. */
    fun split(panels: List<Poly>, s: Seg, gutter: Float): List<Poly> {
        val dx = s.bx - s.ax
        val dy = s.by - s.ay
        val len = hypot(dx, dy)
        if (len < 1e-3f) return panels
        val nx = -dy / len
        val ny = dx / len
        val d = nx * s.ax + ny * s.ay
        val out = ArrayList<Poly>()
        for (p in panels) {
            if (!segmentTouches(p, s)) {
                out.add(p)
                continue
            }
            val a = clip(p, nx, ny, d - gutter / 2f, keepLess = true)
            val b = clip(p, nx, ny, d + gutter / 2f, keepLess = false)
            if (a.size >= 3 && b.size >= 3 && area(a) > 64f && area(b) > 64f) {
                out.add(a)
                out.add(b)
            } else {
                out.add(p)
            }
        }
        return out
    }

    private fun clip(p: Poly, nx: Float, ny: Float, c: Float, keepLess: Boolean): Poly {
        val out = ArrayList<PointF>()
        fun dist(q: PointF) = nx * q.x + ny * q.y - c
        fun inside(q: PointF) = if (keepLess) dist(q) <= 0f else dist(q) >= 0f
        for (i in p.indices) {
            val cur = p[i]
            val prev = p[(i + p.size - 1) % p.size]
            val ci = inside(cur)
            val pi = inside(prev)
            if (ci) {
                if (!pi) out.add(intersect(prev, cur, dist(prev), dist(cur)))
                out.add(PointF(cur.x, cur.y))
            } else if (pi) {
                out.add(intersect(prev, cur, dist(prev), dist(cur)))
            }
        }
        return out
    }

    private fun intersect(a: PointF, b: PointF, da: Float, db: Float): PointF {
        val t = if (abs(da - db) < 1e-6f) 0f else da / (da - db)
        return PointF(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y))
    }

    private fun area(p: Poly): Float {
        var s = 0f
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2f
    }

    fun contains(p: Poly, x: Float, y: Float): Boolean {
        var inside = false
        var j = p.size - 1
        for (i in p.indices) {
            val pi = p[i]
            val pj = p[j]
            if ((pi.y > y) != (pj.y > y) &&
                x < (pj.x - pi.x) * (y - pi.y) / (pj.y - pi.y) + pi.x
            ) inside = !inside
            j = i
        }
        return inside
    }

    private fun cross(ox: Float, oy: Float, ax: Float, ay: Float, bx: Float, by: Float) =
        (ax - ox) * (by - oy) - (ay - oy) * (bx - ox)

    private fun segmentsIntersect(
        p1x: Float, p1y: Float, p2x: Float, p2y: Float,
        q1x: Float, q1y: Float, q2x: Float, q2y: Float,
    ): Boolean {
        val d1 = cross(q1x, q1y, q2x, q2y, p1x, p1y)
        val d2 = cross(q1x, q1y, q2x, q2y, p2x, p2y)
        val d3 = cross(p1x, p1y, p2x, p2y, q1x, q1y)
        val d4 = cross(p1x, p1y, p2x, p2y, q2x, q2y)
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
            ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
    }

    private fun segmentTouches(p: Poly, s: Seg): Boolean {
        if (contains(p, s.ax, s.ay) || contains(p, s.bx, s.by)) return true
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            if (segmentsIntersect(s.ax, s.ay, s.bx, s.by, a.x, a.y, b.x, b.y)) return true
        }
        return false
    }

    fun toPath(p: Poly): Path = Path().apply {
        if (p.isEmpty()) return@apply
        moveTo(p[0].x, p[0].y)
        for (i in 1 until p.size) lineTo(p[i].x, p[i].y)
        close()
    }

    /** Draws the frame layer: gutters filled, panel interiors transparent, borders stroked. */
    fun render(
        target: Bitmap,
        panels: List<Poly>,
        borderWidth: Float,
        borderColor: Int,
        whiteGutters: Boolean,
    ) {
        target.eraseColor(if (whiteGutters) Color.WHITE else Color.TRANSPARENT)
        val c = Canvas(target)
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = borderWidth
            color = borderColor
            strokeJoin = Paint.Join.MITER
        }
        for (p in panels) c.drawPath(toPath(p), clear)
        if (borderWidth > 0f) for (p in panels) c.drawPath(toPath(p), border)
    }
}
