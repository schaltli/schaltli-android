package com.screensmith.android.render

/**
 * The shared rasterizer for pill-shaped runs - a direct port of the designer's
 * `lib/pill-raster.ts`, and a sibling of [ArcRaster.kt] written to the same
 * rules and for the same reason.
 *
 * These shapes are the bar's track, fill and handle and the switch's track,
 * knob, buttons and outlines, and they are drawn live on every target: the
 * designer, each firmware and this app. Android has [android.graphics.Canvas]'s
 * own `drawRoundRect`, and using it here would be the obvious move and the
 * wrong one - it rasterizes a Bezier path with Skia's anti-aliasing, which
 * neither of the other two can reproduce. `Adafruit_GFX::fillRoundRect` gave
 * all three the same HARD pixels, which is why it served until now; a
 * stair-stepped pill next to an anti-aliased ring is what asked the question
 * (the designer's docs/2026-09-22-pill-raster.md).
 *
 * The rules are the arc's, unchanged:
 *
 *   - Sub-pixel positions live on a 1/8-pixel grid, so every coordinate is an
 *     integer and every comparison is an integer square.
 *   - Coverage is a count out of 16, from a 4x4 lattice of sub-samples.
 *   - Colours are mixed in 5/6/5 with an explicit rounding rule, by
 *     [blendBands]' own arithmetic (see [PillPaint.kt]), because the device's
 *     framebuffer is RGB565 and mixing in 8-bit first would land a step or two
 *     away. That rule is worth spelling out on THIS target in particular,
 *     because it is the one that looks wrong here: a phone is 8 bits a channel
 *     and could mix at full precision. It quantises anyway, because a more
 *     accurate blend is a blend that disagrees with both other copies.
 *
 * And the arc's hardest-won rule most of all: **a sub-sample is counted into
 * exactly one band.** Where the bar's fill meets its track, compositing one
 * shape over the other leaves a seam of track colour along the value's edge -
 * fill*c + track*c*(1-c) + bg*(1-c)^2 instead of fill*c + bg*(1-c). Counting
 * once has no such seam, and the value's edge is the one place on a bar that
 * anybody looks at. That is why [pillPixelBands] takes all the runs at once
 * rather than being called once per run.
 *
 * `PillRasterGoldenTest` holds every number below to the designer's own.
 */

/** Sub-pixel grid: 4x4 samples per pixel, positions on a 1/8-pixel lattice. */
const val PILL_SUBSAMPLES = 4
const val PILL_COVERAGE_MAX = PILL_SUBSAMPLES * PILL_SUBSAMPLES
const val PILL_SUBPIXEL_SCALE = 8

/**
 * One run with rounded ends, in whole pixels.
 *
 * The two radii are the run's own ends along its long axis - a segment of a
 * connected button group is round on the outside and barely rounded where it
 * faces its neighbour, and a bar's filled run is round at the bar's end and
 * square where the value cuts it. Each end's radius rounds both of its
 * corners, which is what makes the shape a pill rather than a rectangle with
 * four independent corners.
 */
data class PillBand(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    /** The end at the lower coordinate: left on a horizontal run, top on a vertical one. */
    val rLow: Int,
    /** The end at the higher coordinate. */
    val rHigh: Int,
    /** True when the run's long axis is vertical. */
    val vertical: Boolean = false,
)

/**
 * Whether a sub-sample, in 1/8 pixel, lies inside the band.
 *
 * The radius is clamped the way fillRoundRect clamps it - to half the short
 * side - so an oversized radius self-corrects identically on both sides, and a
 * run shorter than it is thick ends in a half-circle rather than a wedge.
 *
 * Two divisions, and neither is the obvious one. The designer's clamp is
 * `Math.floor(Math.min(w, h) / 2)`, which floors; Kotlin's `/` truncates
 * toward zero, and the two disagree for a negative short side - so
 * [Math.floorDiv] is used rather than assumed to be the same. The radius is
 * then `Math.max(0, Math.min(maxRadius, r))` written out rather than
 * `coerceIn(0, maxRadius)`, which is not the same function: with a negative
 * maxRadius (a degenerate run) JS answers 0 and `coerceIn` throws.
 * `Math.trunc(band.rLow)` has no counterpart here at all - the designer's
 * radii arrive from JSON and can be fractional, this repo's are already Int.
 */
fun insidePillBand(band: PillBand, x: Int, y: Int): Boolean {
    val s = PILL_SUBPIXEL_SCALE
    val x0 = band.x * s
    val y0 = band.y * s
    val x1 = x0 + band.w * s
    val y1 = y0 + band.h * s
    if (x < x0 || x >= x1 || y < y0 || y >= y1) return false

    val maxRadius = Math.floorDiv(minOf(band.w, band.h), 2)
    val rLow = maxOf(0, minOf(maxRadius, band.rLow)) * s
    val rHigh = maxOf(0, minOf(maxRadius, band.rHigh)) * s
    if (rLow == 0 && rHigh == 0) return true

    // Along the run and across it, so one piece of arithmetic serves both
    // directions.
    val along = if (band.vertical) y else x
    val across = if (band.vertical) x else y
    val alongLow = if (band.vertical) y0 else x0
    val alongHigh = if (band.vertical) y1 else x1
    val acrossLow = if (band.vertical) x0 else y0
    val acrossHigh = if (band.vertical) x1 else y1

    fun insideCorner(cAlong: Int, cAcross: Int, r: Int): Boolean {
        val dAlong = along - cAlong
        val dAcross = across - cAcross
        return dAlong * dAlong + dAcross * dAcross <= r * r
    }

    if (rLow > 0 && along < alongLow + rLow) {
        if (across < acrossLow + rLow) return insideCorner(alongLow + rLow, acrossLow + rLow, rLow)
        if (across >= acrossHigh - rLow) return insideCorner(alongLow + rLow, acrossHigh - rLow, rLow)
    }
    if (rHigh > 0 && along >= alongHigh - rHigh) {
        if (across < acrossLow + rHigh) return insideCorner(alongHigh - rHigh, acrossLow + rHigh, rHigh)
        if (across >= acrossHigh - rHigh) return insideCorner(alongHigh - rHigh, acrossHigh - rHigh, rHigh)
    }
    return true
}

/**
 * How many of a pixel's 16 sub-samples fall in each band.
 *
 * Bands are tried in order and the first one that contains a sub-sample keeps
 * it, so the caller states its own priority by the order it passes them in: a
 * handle before the runs it lies on, a cut before what it cuts.
 *
 * [into] is filled in place and returned, so a caller walking a whole object
 * can hand the same array back every pixel. The arc packs its three counts
 * into one Int for the same reason - a per-pixel allocation on a phone is a
 * per-pixel opportunity for the collector to run in the middle of a frame -
 * but a pill has as many bands as the control has parts, so there is no fixed
 * width to pack into. It must be at least as long as [bands].
 */
fun pillPixelBands(bands: List<PillBand>, px: Int, py: Int, into: IntArray = IntArray(bands.size)): IntArray {
    val s = PILL_SUBPIXEL_SCALE
    for (b in bands.indices) into[b] = 0

    for (j in 0 until PILL_SUBSAMPLES) {
        // Sub-sample centres sit at (2k+1)/8 of a pixel, i.e. 1/8, 3/8, 5/8, 7/8.
        val y = py * s + 2 * j + 1
        for (i in 0 until PILL_SUBSAMPLES) {
            val x = px * s + 2 * i + 1
            for (b in bands.indices) {
                if (insidePillBand(bands[b], x, y)) {
                    into[b]++
                    break
                }
            }
        }
    }

    return into
}

/** A box in whole pixels: what [pillBandsBounds] answers. */
data class PillBox(val x: Int, val y: Int, val w: Int, val h: Int)

/** The pixels a set of bands can possibly touch, as a box in whole pixels. */
fun pillBandsBounds(bands: List<PillBand>): PillBox {
    if (bands.isEmpty()) return PillBox(0, 0, 0, 0)
    // The designer starts at +/-Infinity and asks `Number.isFinite` afterwards;
    // Int has no infinity, so the same question is asked with a flag rather
    // than with a sentinel that a real coordinate could reach.
    var any = false
    var x0 = 0
    var y0 = 0
    var x1 = 0
    var y1 = 0
    for (band in bands) {
        if (band.w <= 0 || band.h <= 0) continue
        if (!any) {
            x0 = band.x
            y0 = band.y
            x1 = band.x + band.w
            y1 = band.y + band.h
            any = true
            continue
        }
        x0 = minOf(x0, band.x)
        y0 = minOf(y0, band.y)
        x1 = maxOf(x1, band.x + band.w)
        y1 = maxOf(y1, band.y + band.h)
    }
    if (!any) return PillBox(0, 0, 0, 0)
    return PillBox(x0, y0, x1 - x0, y1 - y0)
}
