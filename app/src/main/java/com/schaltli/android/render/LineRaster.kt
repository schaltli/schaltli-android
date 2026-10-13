package com.schaltli.android.render

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A line, pixel for pixel as the designer draws it: a port of
 * `components/canvas/renderers/render-line.ts` (and of the firmware's
 * `ColorScreenRenderer::renderLine`, which is the same port in C++).
 *
 * Bresenham for a 1-unit line, a filled rotated rectangle for a thicker one,
 * a fillet stepped in 16 straight hops, and filled triangles for the arrows -
 * all whole pixels, no anti-aliasing, because a smoothed line can never match
 * the designer's or a board's. Until 2026-10-13 the app drew no line of its
 * own (a line came baked into the screen's background picture) and its live
 * line went through Android's own path, without fillets.
 *
 * Works in project units; [plot] gets each pixel once per primitive that
 * covers it (a pixel two segments share may come twice). Held to the
 * designer's own output by LineRasterGoldenTest.
 */
object LineRaster {
    data class Point(val x: Double, val y: Double)

    private const val FILLET_CURVE_STEPS = 16

    /** JavaScript's Math.round: halves go up, also for negatives. */
    private fun jsRound(v: Double): Double = floor(v + 0.5)

    /** A whole line: its body, shortened where an arrow is, and the arrows. */
    fun plotLine(
        points: List<Point>,
        strokeWidth: Double,
        filletRadius: Double,
        arrowStart: Boolean,
        arrowEnd: Boolean,
        plot: (Int, Int) -> Unit,
    ) {
        if (points.size < 2) return
        val body = points.toMutableList()
        if (arrowStart) body[0] = shortenForArrow(points[0], points[1], strokeWidth)
        if (arrowEnd) body[body.size - 1] = shortenForArrow(points[points.size - 1], points[points.size - 2], strokeWidth)
        plotBody(body, strokeWidth, max(0.0, filletRadius), plot)
        if (arrowStart) plotArrowhead(points[0], points[1], strokeWidth, plot)
        if (arrowEnd) plotArrowhead(points[points.size - 1], points[points.size - 2], strokeWidth, plot)
    }

    /** The body alone: straight segments and stepped fillets (render-line.ts drawLineBody). */
    fun plotBody(points: List<Point>, strokeWidth: Double, filletRadius: Double, plot: (Int, Int) -> Unit) {
        val segment = { x0: Double, y0: Double, x1: Double, y1: Double ->
            if (strokeWidth <= 1) bresenham(x0.toInt(), y0.toInt(), x1.toInt(), y1.toInt(), plot)
            else fillThickLine(x0, y0, x1, y1, strokeWidth, plot)
        }
        if (filletRadius == 0.0 || points.size < 3) {
            for (i in 0 until points.size - 1) segment(points[i].x, points[i].y, points[i + 1].x, points[i + 1].y)
            return
        }
        var segStart = points[0]
        for (i in 1 until points.size - 1) {
            val fillet = filletTangent(points[i - 1], points[i], points[i + 1], filletRadius)
            segment(segStart.x, segStart.y, fillet.t1.x, fillet.t1.y)
            if (fillet.effectiveRadius > 0 && fillet.center != null) {
                filletArc(fillet.t1, fillet.center, fillet.t2, fillet.effectiveRadius, segment)
            }
            segStart = fillet.t2
        }
        val last = points[points.size - 1]
        segment(segStart.x, segStart.y, last.x, last.y)
    }

    /** Adafruit_GFX::drawLine: a run for straight lines, integer Bresenham otherwise. */
    fun bresenham(ax0: Int, ay0: Int, ax1: Int, ay1: Int, plot: (Int, Int) -> Unit) {
        var x0 = ax0; var y0 = ay0; var x1 = ax1; var y1 = ay1
        if (x0 == x1) {
            for (y in min(y0, y1)..max(y0, y1)) plot(x0, y)
            return
        }
        if (y0 == y1) {
            for (x in min(x0, x1)..max(x0, x1)) plot(x, y0)
            return
        }
        val steep = abs(y1 - y0) > abs(x1 - x0)
        if (steep) {
            var t = x0; x0 = y0; y0 = t
            t = x1; x1 = y1; y1 = t
        }
        if (x0 > x1) {
            var t = x0; x0 = x1; x1 = t
            t = y0; y0 = y1; y1 = t
        }
        val dx = x1 - x0
        val dy = abs(y1 - y0)
        var err = dx / 2
        val ystep = if (y0 < y1) 1 else -1
        var y = y0
        for (x in x0..x1) {
            if (steep) plot(y, x) else plot(x, y)
            err -= dy
            if (err < 0) {
                y += ystep
                err += dx
            }
        }
    }

    /** A thick segment as a rotated rectangle with butt caps, by pixel centres (fillThickLine). */
    fun fillThickLine(x0: Double, y0: Double, x1: Double, y1: Double, width: Double, plot: (Int, Int) -> Unit) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = sqrt(dx * dx + dy * dy)
        if (len == 0.0) return
        val nx = -dy / len
        val ny = dx / len
        val hw = width / 2
        val cx = doubleArrayOf(x0 + nx * hw, x0 - nx * hw, x1 - nx * hw, x1 + nx * hw)
        val cy = doubleArrayOf(y0 + ny * hw, y0 - ny * hw, y1 - ny * hw, y1 + ny * hw)
        val minX = floor(cx.min()).toInt()
        val maxX = ceil(cx.max()).toInt()
        val minY = floor(cy.min()).toInt()
        val maxY = ceil(cy.max()).toInt()
        for (py in minY..maxY) {
            for (px in minX..maxX) {
                val tx = px + 0.5
                val ty = py + 0.5
                var sign = 0
                var inside = true
                for (i in 0 until 4) {
                    val j = (i + 1) % 4
                    val cross = (cx[j] - cx[i]) * (ty - cy[i]) - (cy[j] - cy[i]) * (tx - cx[i])
                    if (cross != 0.0) {
                        val s = if (cross > 0) 1 else -1
                        if (sign == 0) sign = s
                        else if (s != sign) { inside = false; break }
                    }
                }
                if (inside) plot(px, py)
            }
        }
    }

    private class Fillet(val t1: Point, val t2: Point, val effectiveRadius: Double, val center: Point?)

    private fun filletTangent(prev: Point, curr: Point, next: Point, filletRadius: Double): Fillet {
        val lenIn = hypot(curr.x - prev.x, curr.y - prev.y)
        val lenOut = hypot(next.x - curr.x, next.y - curr.y)
        val maxDist = min(lenIn / 2, lenOut / 2)
        if (lenIn == 0.0 || lenOut == 0.0 || maxDist == 0.0) return Fillet(curr, curr, 0.0, null)

        val v1x = (prev.x - curr.x) / lenIn
        val v1y = (prev.y - curr.y) / lenIn
        val v2x = (next.x - curr.x) / lenOut
        val v2y = (next.y - curr.y) / lenOut
        val dot = max(-1.0, min(1.0, v1x * v2x + v1y * v2y))
        val halfAngle = acos(dot) / 2
        val tanHalf = tan(halfAngle)
        val sinHalf = sin(halfAngle)

        var tangentDist = if (tanHalf > 1e-6) filletRadius / tanHalf else maxDist
        var effectiveRadius = filletRadius
        if (tangentDist > maxDist) {
            tangentDist = maxDist
            effectiveRadius = tangentDist * tanHalf
        }
        val t1 = Point(jsRound(curr.x + v1x * tangentDist), jsRound(curr.y + v1y * tangentDist))
        val t2 = Point(jsRound(curr.x + v2x * tangentDist), jsRound(curr.y + v2y * tangentDist))

        var center: Point? = null
        if (effectiveRadius > 0 && sinHalf > 1e-6) {
            val bx = v1x + v2x
            val by = v1y + v2y
            val bLen = hypot(bx, by)
            if (bLen > 1e-6) {
                val centerDist = effectiveRadius / sinHalf
                center = Point(curr.x + (bx / bLen) * centerDist, curr.y + (by / bLen) * centerDist)
            }
        }
        return Fillet(t1, t2, effectiveRadius, center)
    }

    private fun filletArc(
        t1: Point,
        center: Point,
        t2: Point,
        radius: Double,
        segment: (Double, Double, Double, Double) -> Unit,
    ) {
        val startAngle = atan2(t1.y - center.y, t1.x - center.x)
        val endAngle = atan2(t2.y - center.y, t2.x - center.x)
        var delta = endAngle - startAngle
        while (delta > Math.PI) delta -= 2 * Math.PI
        while (delta < -Math.PI) delta += 2 * Math.PI
        var prev = t1
        for (s in 1..FILLET_CURVE_STEPS) {
            val angle = startAngle + delta * (s.toDouble() / FILLET_CURVE_STEPS)
            val p = Point(jsRound(center.x + radius * cos(angle)), jsRound(center.y + radius * sin(angle)))
            segment(prev.x, prev.y, p.x, p.y)
            prev = p
        }
    }

    /** An arrowhead at [tip], pointing away from [from] (render-line.ts drawArrowhead). */
    fun plotArrowhead(tip: Point, from: Point, strokeWidth: Double, plot: (Int, Int) -> Unit) {
        val dx = tip.x - from.x
        val dy = tip.y - from.y
        val len = hypot(dx, dy)
        if (len == 0.0) return
        val dirX = dx / len
        val dirY = dy / len
        val perpX = -dirY
        val perpY = dirX
        val arrowLength = max(6.0, strokeWidth * 3)
        val halfWidth = max(4.0, strokeWidth * 2)
        val backX = tip.x - dirX * arrowLength
        val backY = tip.y - dirY * arrowLength
        fillTriangle(
            jsRound(tip.x).toInt(), jsRound(tip.y).toInt(),
            jsRound(backX + perpX * halfWidth).toInt(), jsRound(backY + perpY * halfWidth).toInt(),
            jsRound(backX - perpX * halfWidth).toInt(), jsRound(backY - perpY * halfWidth).toInt(),
            plot,
        )
    }

    /** Where a line ends under its arrow, so a thick stroke does not poke out (shortenForArrow). */
    fun shortenForArrow(tip: Point, from: Point, strokeWidth: Double): Point {
        val dx = tip.x - from.x
        val dy = tip.y - from.y
        val len = hypot(dx, dy)
        if (len == 0.0) return tip
        val arrowLength = max(6.0, strokeWidth * 3)
        val shortenBy = min(arrowLength * 0.25, len * 0.9)
        return Point(jsRound(tip.x - dx / len * shortenBy), jsRound(tip.y - dy / len * shortenBy))
    }

    /** Adafruit_GFX::fillTriangle, scanline for scanline. */
    fun fillTriangle(ax0: Int, ay0: Int, ax1: Int, ay1: Int, ax2: Int, ay2: Int, plot: (Int, Int) -> Unit) {
        var x0 = ax0; var y0 = ay0; var x1 = ax1; var y1 = ay1; var x2 = ax2; var y2 = ay2
        var t: Int
        if (y0 > y1) { t = x0; x0 = x1; x1 = t; t = y0; y0 = y1; y1 = t }
        if (y1 > y2) { t = x1; x1 = x2; x2 = t; t = y1; y1 = y2; y2 = t }
        if (y0 > y1) { t = x0; x0 = x1; x1 = t; t = y0; y0 = y1; y1 = t }

        val hline = { xa: Int, xb: Int, y: Int -> for (x in min(xa, xb)..max(xa, xb)) plot(x, y) }

        if (y0 == y2) {
            var a = x0
            var b = x0
            if (x1 < a) a = x1 else if (x1 > b) b = x1
            if (x2 < a) a = x2 else if (x2 > b) b = x2
            hline(a, b, y0)
            return
        }
        val dx01 = x1 - x0; val dy01 = y1 - y0; val dx02 = x2 - x0; val dy02 = y2 - y0; val dx12 = x2 - x1; val dy12 = y2 - y1
        var sa = 0
        var sb = 0
        var y = y0
        val last = if (y1 == y2) y1 else y1 - 1
        while (y <= last) {
            hline(x0 + sa / dy01, x0 + sb / dy02, y)
            sa += dx01
            sb += dx02
            y++
        }
        sa = dx12 * (y - y1)
        sb = dx02 * (y - y0)
        while (y <= y2) {
            hline(x1 + sa / dy12, x0 + sb / dy02, y)
            sa += dx12
            sb += dx02
            y++
        }
    }
}
