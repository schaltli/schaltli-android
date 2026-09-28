package com.schaltli.android.render

import com.schaltli.android.data.ScreenObject

/**
 * A level's fill as a gradient, and the weak glow around it - this repo's copy
 * of the designer's `lib/level-glow.ts` and of the gradient and glow in its
 * `lib/arc-raster.ts`, integer for integer. A theme gives them to a bar,
 * slider, gauge or dial whose fill is its accent (the designer's
 * `lib/themes.ts`, 2026-09-28); the export writes `fillEndColor` and `glow`,
 * and a project without them draws exactly as before.
 *
 * The glow is mixed into whatever lies under the control. The designer and the
 * firmware read that back from their canvas; a control here draws into its own
 * bitmap and cannot, so a glow pixel is left translucent - the glow's colour at
 * the glow's alpha - and composited over what is underneath when the bitmap is
 * blitted. That is the designer's own treatment on its zoomed editor canvas,
 * and it keeps an icon under the glow visible, which was the point.
 */

/** How far the glow reaches beyond the band, in whole pixels (the "weak" glow). */
const val GLOW_LEVELS = 8

/** How strongly the glow is mixed in, out of 256, by how many pixels from the band a pixel lies. */
val GLOW_ALPHA = intArrayOf(0, 128, 100, 76, 56, 40, 26, 15, 7)

/** Steps a ring's gradient is divided into along its scale. */
const val GRADIENT_STEPS = 128

/** `col` over `dst` at alpha/256, rounded, per 5/6/5 channel. */
fun blend565(dst: Rgb565, col: Rgb565, alpha: Int): Rgb565 {
    fun mix(d: Int, c: Int) = (d * (256 - alpha) + c * alpha + 128) shr 8
    return Rgb565.of(mix(dst.r, col.r), mix(dst.g, col.g), mix(dst.b, col.b))
}

/** Step [step] of [steps] between two colours, taken at the step's middle. */
fun gradient565(from: Rgb565, to: Rgb565, step: Int, steps: Int): Rgb565 {
    val s = step.coerceIn(0, steps - 1)
    val w = 2 * s + 1
    val n = 2 * steps
    // Every operand non-negative, so the division floors as the designer's does.
    fun at(a: Int, b: Int) = (a * (n - w) + b * w + steps) / n
    return Rgb565.of(at(from.r, to.r), at(from.g, to.g), at(from.b, to.b))
}

/** 1 inside or touching the band, up to [GLOW_LEVELS], 0 beyond - all in 1/8 pixel. */
fun glowLevelFromDistance2(d2: Long, half: Int): Int {
    if (d2 < half.toLong() * half) return 1
    for (k in 1..GLOW_LEVELS) {
        val r = (half + k * 8).toLong()
        if (d2 < r * r) return k
    }
    return 0
}

/** Squared distance from (x, y) to an axis-aligned segment (a bar's centre line). */
fun segmentDistance2(x: Int, y: Int, ax: Int, ay: Int, bx: Int, by: Int): Long {
    val vx = (bx - ax).toLong()
    val vy = (by - ay).toLong()
    val wx = (x - ax).toLong()
    val wy = (y - ay).toLong()
    val len2 = vx * vx + vy * vy
    val dot = wx * vx + wy * vy
    if (len2 == 0L || dot <= 0L) return wx * wx + wy * wy
    if (dot >= len2) {
        val ex = (x - bx).toLong()
        val ey = (y - by).toLong()
        return ex * ex + ey * ey
    }
    return if (vx == 0L) wx * wx else wy * wy
}

/** How far an object's glow reaches, in pixels, from its `glow` property. */
fun levelGlowPx(obj: ScreenObject): Int {
    val g = obj.properties.number("glow")?.toInt() ?: 0
    return if (g > 0) minOf(g, GLOW_LEVELS) else 0
}

// --- the ring ----------------------------------------------------------------

private const val DIAMOND_ONE = 65536L

/** The diamond pseudo-angle of (ux, uy), clockwise from +ux, in [0, 4 * 65536). */
fun diamond(ux: Long, uy: Long): Long {
    if (ux == 0L && uy == 0L) return 0
    // Every quotient has a non-negative numerator and a positive denominator,
    // so Kotlin's truncation is the designer's Math.floor.
    if (uy >= 0) {
        if (ux >= 0) return uy * DIAMOND_ONE / (ux + uy)
        return DIAMOND_ONE + (-ux * DIAMOND_ONE) / (-ux + uy)
    }
    if (ux < 0) return 2 * DIAMOND_ONE + (-uy * DIAMOND_ONE) / (-ux - uy)
    return 3 * DIAMOND_ONE + ux * DIAMOND_ONE / (ux - uy)
}

private fun diamondFrom(sx: Int, sy: Int, x: Int, y: Int): Long =
    diamond(sx.toLong() * x + sy.toLong() * y, sx.toLong() * y - sy.toLong() * x)

/** A ring's gradient: the start ray and the diamond of every step boundary. */
class ArcGradient(val sx: Int, val sy: Int, val bounds: LongArray, val end: Long)

fun makeArcGradient(start64: Int, sweep64: Int): ArcGradient {
    val s = arcDirection(start64)
    val sx = s.toInt()
    val sy = (s shr 32).toInt()
    val bounds = LongArray(GRADIENT_STEPS - 1) { k ->
        val i = k + 1
        val d = arcDirection(start64 + ((sweep64.toLong() * i) / GRADIENT_STEPS).toInt())
        diamondFrom(sx, sy, d.toInt(), (d shr 32).toInt())
    }
    val end = if (sweep64 >= ARC_FULL_TURN) {
        4 * DIAMOND_ONE
    } else {
        val e = arcDirection(start64 + sweep64)
        diamondFrom(sx, sy, e.toInt(), (e shr 32).toInt())
    }
    return ArcGradient(sx, sy, bounds, end)
}

/** The step of a point relative to the centre in 1/8 pixel; a point in the gap takes the nearer end. */
fun arcGradientStep(g: ArcGradient, x: Int, y: Int): Int {
    val p = diamondFrom(g.sx, g.sy, x, y)
    if (p > g.end) return if (p - g.end > 4 * DIAMOND_ONE - p) 0 else GRADIENT_STEPS - 1
    var lo = 0
    var hi = g.bounds.size
    while (lo < hi) {
        val mid = (lo + hi) shr 1
        if (g.bounds[mid] <= p) lo = mid + 1 else hi = mid
    }
    return lo
}

/** The step an angle offset into the scale falls in. */
fun arcStepOfOffset(offset64: Int, sweep64: Int): Int {
    if (sweep64 <= 0) return 0
    return ((offset64.toLong() * GRADIENT_STEPS) / sweep64).toInt().coerceIn(0, GRADIENT_STEPS - 1)
}

/** The glow around a ring's fill, in 1/8 pixel from the centre. */
class ArcGlow(
    val rMid: Int,
    val half: Int,
    val fill: ArcSector,
    val endX: IntArray,
    val endY: IntArray,
    val stepFrom: Int,
    val stepTo: Int,
)

/** The designer's arcGlowFor: built from the ring's own centreline and ends. */
fun arcGlowFor(size: Int, thickness: Int, inset: Int, fillStart64: Int, filled64: Int, lastStep: Int): ArcGlow {
    val a = arcCentrelinePoint(size, thickness, inset, fillStart64)
    val b = arcCentrelinePoint(size, thickness, inset, fillStart64 + filled64)
    return ArcGlow(
        rMid = arcCentrelineRadius(size, thickness, inset),
        half = thickness * ARC_SUBPIXEL_SCALE / 2,
        fill = makeArcSector(fillStart64, filled64),
        endX = intArrayOf(a.toInt(), b.toInt()),
        endY = intArrayOf((a shr 32).toInt(), (b shr 32).toInt()),
        stepFrom = 0,
        stepTo = lastStep,
    )
}

/** 1 .. levels by how near the fill a point is, 0 beyond. */
fun arcGlowLevel(glow: ArcGlow, x: Int, y: Int, levels: Int): Int {
    val d2 = x.toLong() * x + y.toLong() * y
    var best = 0
    if (inArcSector(glow.fill, x, y)) {
        for (k in 1..levels) {
            val outer = (glow.rMid + glow.half + k * 8).toLong()
            val inner = (glow.rMid - glow.half - k * 8).toLong()
            if (d2 < outer * outer && (inner <= 0 || d2 >= inner * inner)) {
                best = k
                break
            }
        }
    }
    for (i in 0..1) {
        val dx = (x - glow.endX[i]).toLong()
        val dy = (y - glow.endY[i]).toLong()
        val c2 = dx * dx + dy * dy
        val limit = if (best == 0) levels else best - 1
        for (k in 1..limit) {
            val r = (glow.half + k * 8).toLong()
            if (c2 < r * r) {
                best = k
                break
            }
        }
    }
    return best
}

/** A glow pixel left for the blit to composite: the colour at the glow's alpha. */
fun glowArgb(colour: Rgb565, level: Int): Int = (GLOW_ALPHA[level] shl 24) or (rgb565ToArgb(colour) and 0xFFFFFF)
