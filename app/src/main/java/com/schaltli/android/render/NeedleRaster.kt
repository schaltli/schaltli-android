package com.schaltli.android.render

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * The moving part of a styled gauge or bar - the designer's
 * lib/needle-raster.ts, integer for integer (its device contract §2.15,
 * docs/2026-11-23-instrument-styles.md there). Every platform arrives at the
 * same pixels; the designer's lib/needle-raster/vectors.json is the reference
 * (NeedleRasterGoldenTest).
 *
 * - Coordinates are eighths of a pixel, in the object's own box.
 * - A rotating layer turns about the pivot by the shared sine table
 *   ([arcDirection]), 1/64 degree, 0 = up, clockwise:
 *   x' = px + ((rx*C - ry*S + 8192) shr 14).
 * - A body is filled with 4 x 4 sub-samples per pixel ([PolygonRaster] for
 *   polygons, the same sampling for circles and rings): alpha x coverage / 16.
 * - A soft layer (glow, shadow) is a field: soft[0] inside, soft[k] up to k
 *   whole pixels outside, compared as squared distances; nothing beyond.
 * - Each pixel is mixed into what is already there with [blend565].
 */
object NeedleRaster {

    @Serializable
    data class Layer(
        val motion: String = "static",
        val shapes: List<JsonElement> = emptyList(),
        val color: Int = 0,
        val alpha: Int = 256,
        val soft: List<Int>? = null,
        val dx: Int = 0,
        val dy: Int = 0,
    )

    @Serializable
    data class Pivot(val x: Int = 0, val y: Int = 0)

    @Serializable
    data class Description(val pivot: Pivot = Pivot(), val layers: List<Layer> = emptyList())

    data class Pose(val angle64: Int = 0, val shiftX: Int = 0, val shiftY: Int = 0)

    /** An RGB565 picture to draw into. */
    interface Target {
        val width: Int
        val height: Int
        fun get(x: Int, y: Int): Int
        fun set(x: Int, y: Int, c: Int)
    }

    /** The angle a needle stands at for [degrees]: floor(degrees x 64 + 0.5). */
    fun angle64Of(degrees: Double): Int = Math.floor(degrees * 64 + 0.5).toInt()

    /** floor(sqrt(n)) for a non-negative integer, without floating point. */
    fun isqrt(n: Long): Long {
        if (n < 2) return maxOf(0L, n)
        var x = n
        var y = (x + 1) / 2
        while (y < x) {
            x = y
            y = (x + n / x) / 2
        }
        return x
    }

    private sealed class Placed {
        class Poly(val pts: List<PolygonRaster.Point8>) : Placed()
        class Circle(val cx: Int, val cy: Int, val r: Int, val rIn: Int) : Placed()
    }

    private fun blendAt(t: Target, x: Int, y: Int, color: Int, alpha: Int) {
        if (alpha <= 0 || x < 0 || y < 0 || x >= t.width || y >= t.height) return
        if (alpha >= 256) {
            t.set(x, y, color)
            return
        }
        t.set(x, y, blend565(Rgb565(t.get(x, y)), Rgb565(color), alpha).packed)
    }

    private fun place(shape: JsonElement, layer: Layer, desc: Description, pose: Pose): Placed {
        val dx = layer.dx + if (layer.motion == "translate") pose.shiftX else 0
        val dy = layer.dy + if (layer.motion == "translate") pose.shiftY else 0
        var s = 0
        var c = 16384
        if (layer.motion == "rotate") {
            val d = arcDirection(pose.angle64)
            s = d.toInt()
            c = -(d shr 32).toInt()
        }
        fun at(x: Int, y: Int): Pair<Int, Int> {
            var tx = x
            var ty = y
            if (layer.motion == "rotate") {
                val rx = x - desc.pivot.x
                val ry = y - desc.pivot.y
                tx = desc.pivot.x + ((rx * c - ry * s + 8192) shr 14)
                ty = desc.pivot.y + ((rx * s + ry * c + 8192) shr 14)
            }
            return Pair(tx + dx, ty + dy)
        }
        val obj = shape as kotlinx.serialization.json.JsonObject
        obj["poly"]?.let { poly ->
            return Placed.Poly(poly.jsonArray.map { pt ->
                val a = pt.jsonArray
                val (x, y) = at(a[0].jsonPrimitive.int, a[1].jsonPrimitive.int)
                PolygonRaster.Point8(x, y)
            })
        }
        val circle = obj["circle"] as JsonArray
        val (cx, cy) = at(circle[0].jsonPrimitive.int, circle[1].jsonPrimitive.int)
        val rIn = if (circle.size > 3) circle[3].jsonPrimitive.int else 0
        return Placed.Circle(cx, cy, circle[2].jsonPrimitive.int, rIn)
    }

    private fun circleCoverage(px: Int, py: Int, cx: Int, cy: Int, r: Int, rIn: Int): Int {
        val r2 = r.toLong() * r
        val i2 = rIn.toLong() * rIn
        var n = 0
        for (sy in 0 until 4) {
            val dy = (py * 8 + 1 + sy * 2 - cy).toLong()
            for (sx in 0 until 4) {
                val dx = (px * 8 + 1 + sx * 2 - cx).toLong()
                val d2 = dx * dx + dy * dy
                if (d2 <= r2 && (rIn == 0 || d2 >= i2)) n++
            }
        }
        return n
    }

    /** Squared distance, eighths squared, from the point to a convex polygon; 0 inside. */
    private fun polyDistance2(x: Int, y: Int, pts: List<PolygonRaster.Point8>): Long {
        val n = pts.size
        var area = 0L
        for (i in 0 until n) {
            val a = pts[i]
            val b = pts[(i + 1) % n]
            area += a.x.toLong() * b.y - b.x.toLong() * a.y
        }
        val sign = if (area >= 0) 1L else -1L
        var inside = true
        var best = Long.MAX_VALUE
        for (i in 0 until n) {
            val a = pts[i]
            val b = pts[(i + 1) % n]
            val vx = (b.x - a.x).toLong()
            val vy = (b.y - a.y).toLong()
            val wx = (x - a.x).toLong()
            val wy = (y - a.y).toLong()
            val cross = vx * wy - vy * wx
            if (cross * sign < 0) inside = false
            val len2 = vx * vx + vy * vy
            val dot = wx * vx + wy * vy
            val d2 = when {
                len2 == 0L || dot <= 0 -> wx * wx + wy * wy
                dot >= len2 -> {
                    val ex = (x - b.x).toLong()
                    val ey = (y - b.y).toLong()
                    ex * ex + ey * ey
                }
                else -> (cross * cross) / len2 // both non-negative: the floor
            }
            if (d2 < best) best = d2
        }
        return if (inside) 0 else best
    }

    private fun bounds(p: Placed, reach8: Int): IntArray {
        val (minX, minY, maxX, maxY) = when (p) {
            is Placed.Poly -> listOf(p.pts.minOf { it.x }, p.pts.minOf { it.y }, p.pts.maxOf { it.x }, p.pts.maxOf { it.y })
            is Placed.Circle -> listOf(p.cx - p.r, p.cy - p.r, p.cx + p.r, p.cy + p.r)
        }
        return intArrayOf(
            Math.floorDiv(minX - reach8, 8), Math.floorDiv(minY - reach8, 8),
            Math.floorDiv(maxX + reach8, 8), Math.floorDiv(maxY + reach8, 8),
        )
    }

    private fun drawSoft(t: Target, p: Placed, layer: Layer) {
        val soft = layer.soft!!
        val levels = soft.size - 1
        val b = bounds(p, levels * 8 + 8)
        for (y in maxOf(0, b[1])..minOf(t.height - 1, b[3])) {
            for (x in maxOf(0, b[0])..minOf(t.width - 1, b[2])) {
                val cx = x * 8 + 4
                val cy = y * 8 + 4
                val d2: Long = when (p) {
                    is Placed.Poly -> polyDistance2(cx, cy, p.pts)
                    is Placed.Circle -> {
                        val ddx = (cx - p.cx).toLong()
                        val ddy = (cy - p.cy).toLong()
                        val dc2 = ddx * ddx + ddy * ddy
                        val r2 = p.r.toLong() * p.r
                        val i2 = p.rIn.toLong() * p.rIn
                        if (dc2 <= r2 && (p.rIn == 0 || dc2 >= i2)) 0L else {
                            val dc = isqrt(dc2)
                            val e = if (p.rIn > 0 && dc2 < i2) p.rIn - dc else dc - p.r
                            if (e > 0) e * e else 0L
                        }
                    }
                }
                var k = 0
                if (d2 > 0) {
                    k = levels + 1
                    for (j in 1..levels) {
                        if (d2 < (j * 8).toLong() * (j * 8)) {
                            k = j
                            break
                        }
                    }
                }
                if (k <= levels) blendAt(t, x, y, layer.color, soft[k])
            }
        }
    }

    private fun drawBody(t: Target, p: Placed, layer: Layer) {
        val a = layer.alpha.coerceIn(0, 256)
        when (p) {
            is Placed.Poly -> PolygonRaster.fill(p.pts, 0, t.height - 1, object : PolygonRaster.Sink {
                override fun fullRun(y: Int, x0: Int, x1: Int) {
                    for (x in x0..x1) blendAt(t, x, y, layer.color, a)
                }
                override fun partial(y: Int, x: Int, coverage: Int) = blendAt(t, x, y, layer.color, (a * coverage + 8) shr 4)
            })
            is Placed.Circle -> {
                val b = bounds(p, 0)
                for (y in maxOf(0, b[1])..minOf(t.height - 1, b[3])) {
                    for (x in maxOf(0, b[0])..minOf(t.width - 1, b[2])) {
                        val cov = circleCoverage(x, y, p.cx, p.cy, p.r, p.rIn)
                        if (cov > 0) blendAt(t, x, y, layer.color, if (cov == 16) a else (a * cov + 8) shr 4)
                    }
                }
            }
        }
    }

    /** Draws every layer of [desc] in [pose] into [t], back to front. */
    fun draw(t: Target, desc: Description, pose: Pose) {
        for (layer in desc.layers) {
            for (shape in layer.shapes) {
                val p = place(shape, layer, desc, pose)
                if (p is Placed.Poly && p.pts.size < 3) continue
                if (!layer.soft.isNullOrEmpty()) drawSoft(t, p, layer) else drawBody(t, p, layer)
            }
        }
    }
}
