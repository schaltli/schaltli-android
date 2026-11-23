package com.schaltli.android.render

/**
 * A filled polygon, anti-aliased, as the designer's lib/polygon-raster.ts and
 * the firmware's src/project/PolygonRaster fill it - a line-by-line port, so a
 * styled needle (the designer's device contract §2.15) is the same pixels here.
 *
 * - The outline's corners are in eighths of a pixel, whole numbers.
 * - Each pixel is sampled at 4 x 4 points, at eighths 1, 3, 5, 7 in x and y;
 *   its coverage is how many of the 16 are inside (1..16).
 * - Even-odd: a sub-sample is inside when a ray to its left crosses the
 *   outline an odd number of times.
 * - Where the outline crosses a sub-sample row is rounded to the nearest
 *   eighth, in whole numbers.
 */
object PolygonRaster {
    data class Point8(val x: Int, val y: Int)

    interface Sink {
        /** Pixels x0..x1 of row y wholly inside. */
        fun fullRun(y: Int, x0: Int, x1: Int)
        /** Pixel (x, y) partly inside: `coverage` of 16 sub-samples. */
        fun partial(y: Int, x: Int, coverage: Int)
    }

    private fun floorDiv(a: Int, b: Int): Int = Math.floorDiv(a, b)
    // Math.ceilDiv is newer than this app's minimum API.
    private fun ceilDiv(a: Int, b: Int): Int = -Math.floorDiv(-a, b)

    private fun crossings(pts: List<Point8>, y: Int): IntArray {
        val out = ArrayList<Int>(4)
        val n = pts.size
        for (i in 0 until n) {
            val a = pts[i]
            val b = pts[(i + 1) % n]
            if (a.y == b.y) continue
            val top = if (a.y < b.y) a else b
            val bottom = if (a.y < b.y) b else a
            if (y < top.y || y >= bottom.y) continue
            // x = top.x + (y - top.y) * dx / dy, rounded to the nearest eighth.
            val num = (y - top.y).toLong() * (bottom.x - top.x)
            val den = (bottom.y - top.y).toLong()
            val q = if (num >= 0) Math.floorDiv(2 * num + den, 2 * den) else -Math.floorDiv(-2 * num + den, 2 * den)
            out.add(top.x + q.toInt())
        }
        out.sort()
        return out.toIntArray()
    }

    /** Fills `points` (eighths) between pixel rows clipY0 and clipY1, telling `sink` what it covers. */
    fun fill(points: List<Point8>, clipY0: Int, clipY1: Int, sink: Sink) {
        if (points.size < 3) return
        var minX = points[0].x
        var maxX = points[0].x
        var minY = points[0].y
        var maxY = points[0].y
        for (p in points) {
            minX = minOf(minX, p.x); maxX = maxOf(maxX, p.x)
            minY = minOf(minY, p.y); maxY = maxOf(maxY, p.y)
        }
        val px0 = floorDiv(minX, 8)
        val px1 = floorDiv(maxX, 8)
        val py0 = maxOf(floorDiv(minY, 8), clipY0)
        val py1 = minOf(floorDiv(maxY, 8), clipY1)
        if (px1 < px0 || py1 < py0) return
        val width = px1 - px0 + 1
        val cover = IntArray(width)
        for (py in py0..py1) {
            cover.fill(0)
            var any = false
            for (sub in 0 until 4) {
                val xs = crossings(points, py * 8 + 1 + sub * 2)
                var k = 0
                while (k + 1 < xs.size) {
                    val from = xs[k]
                    val to = xs[k + 1]
                    k += 2
                    if (to <= from) continue
                    // Sub-sample columns at 2c + 1 inside [from, to).
                    val c0 = ceilDiv(from - 1, 2)
                    val c1 = ceilDiv(to - 1, 2) - 1
                    for (c in c0..c1) {
                        val pixel = floorDiv(c, 4)
                        if (pixel < px0 || pixel > px1) continue
                        cover[pixel - px0]++
                        any = true
                    }
                }
            }
            if (!any) continue
            var runStart = -1
            for (i in 0..width) {
                val c = if (i < width) cover[i] else 0
                if (c == 16) {
                    if (runStart < 0) runStart = i
                    continue
                }
                if (runStart >= 0) {
                    sink.fullRun(py, px0 + runStart, px0 + i - 1)
                    runStart = -1
                }
                if (i < width && c > 0) sink.partial(py, px0 + i, c)
            }
        }
    }
}
