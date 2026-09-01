package com.screensmith.android.render

/**
 * The shared rasterizer for arc-level objects - a direct port of the
 * designer's `lib/arc-raster.ts`, which also exists as `ArcRaster.cpp` in
 * each firmware. Everything in it is integer arithmetic, chosen so that the
 * copies cannot produce different pixels.
 *
 * Porting it rather than drawing a ring with `Canvas.drawArc` is the whole
 * point. `drawArc` would anti-alias with Skia's own rasterizer, which the
 * designer cannot reproduce and no firmware can either - and an arc-level's
 * long, shallow outer edge is where a HIL comparison lives or dies. The
 * rules that make the copies agree are fixed in the shared algorithm:
 *
 *   - Angles are whole 1/64 degrees, 0 at twelve o'clock, clockwise
 *     positive. Direction vectors come from the generated integer sine
 *     table ([ARC_SIN_TABLE]), never from sin()/cos().
 *   - Sub-pixel positions live on a 1/8-pixel grid, so every coordinate is
 *     an integer and every radius comparison is an integer square.
 *   - "Is this sub-pixel inside the sector" is decided by the sign of a
 *     cross product. No atan2, nothing to round.
 *   - Coverage is a count out of 16, and colours are mixed in 5/6/5 space
 *     with an explicit rounding rule.
 *
 * That last rule is worth spelling out on *this* target in particular,
 * because it is the one that looks wrong here. An Android phone has no
 * RGB565 framebuffer; it is 8 bits per channel and could mix at full
 * precision. It quantises anyway, because the comparison this exists to
 * survive is against the designer's render, and the designer quantises to
 * match the firmware. A more accurate blend here would be a blend that
 * disagrees with both, which is the only outcome that matters.
 */

/** Angles are carried in 1/64 degree units. */
const val ARC_ANGLE_SCALE = 64
const val ARC_FULL_TURN = 360 * ARC_ANGLE_SCALE

/** Sub-pixel grid: 4x4 samples per pixel, positions on a 1/8-pixel lattice. */
const val ARC_SUBSAMPLES = 4
const val ARC_COVERAGE_MAX = ARC_SUBSAMPLES * ARC_SUBSAMPLES
const val ARC_SUBPIXEL_SCALE = 8

/**
 * A sector's two boundary rays, prepared once per band rather than per
 * sub-pixel. [wide] selects the union test: a sector wider than a half turn
 * is not the intersection of two half-planes but the union of them.
 */
data class ArcSector(
    val ux: Int,
    val uy: Int,
    val vx: Int,
    val vy: Int,
    val wide: Boolean,
    val full: Boolean,
    val empty: Boolean,
)

/**
 * Direction vector for an angle in 1/64 degrees, with 0 at twelve o'clock and
 * clockwise positive, in screen coordinates (y downwards).
 *
 * Angles between whole degrees are interpolated linearly between two table
 * entries instead of being looked up. That matters for the fill edge, which
 * has to move smoothly: a whole degree at radius 168 is nearly three pixels
 * of arc, so a table-only lookup would make the filled arc advance in
 * visible jumps. The lerp is not exactly on the unit circle, but only the
 * *direction* is ever used - it feeds cross products, whose sign is
 * unaffected by length - so the small shortening is harmless, and it is
 * identical on every side, which is the only property that matters.
 *
 * The `shr 6` is a floor, identically here, in JS and in C++ (arithmetic
 * shift on a value that fits in int32) - written as a shift rather than a
 * divide because Kotlin's and C++'s integer division truncate toward zero
 * and would disagree with JS's floor for negative values.
 *
 * Returned packed rather than as a pair: x in the low half, y in the high
 * half. See [arcPixelBands] for the same reasoning about allocation.
 */
fun arcDirection(angle64: Int): Long {
    var a = angle64 % ARC_FULL_TURN
    if (a < 0) a += ARC_FULL_TURN

    val deg = a / ARC_ANGLE_SCALE
    val frac = a - deg * ARC_ANGLE_SCALE

    val sin0 = ARC_SIN_TABLE[deg]
    val sin1 = ARC_SIN_TABLE[(deg + 1) % 360]
    // cos(d) is sin(d + 90), so the same table serves both.
    val cos0 = ARC_SIN_TABLE[(deg + 90) % 360]
    val cos1 = ARC_SIN_TABLE[(deg + 91) % 360]

    val sinA = (sin0 * (ARC_ANGLE_SCALE - frac) + sin1 * frac + 32) shr 6
    val cosA = (cos0 * (ARC_ANGLE_SCALE - frac) + cos1 * frac + 32) shr 6

    // Twelve o'clock is straight up, which is -y on a screen.
    return packXY(sinA, -cosA)
}

private fun packXY(x: Int, y: Int): Long = (x.toLong() and 0xFFFFFFFFL) or (y.toLong() shl 32)

private fun unpackX(packed: Long): Int = packed.toInt()

private fun unpackY(packed: Long): Int = (packed shr 32).toInt()

fun makeArcSector(startA64: Int, sweepA64: Int): ArcSector {
    if (sweepA64 <= 0) {
        return ArcSector(0, 0, 0, 0, wide = false, full = false, empty = true)
    }
    if (sweepA64 >= ARC_FULL_TURN) {
        return ArcSector(0, 0, 0, 0, wide = false, full = true, empty = false)
    }
    val u = arcDirection(startA64)
    val v = arcDirection(startA64 + sweepA64)
    return ArcSector(
        ux = unpackX(u),
        uy = unpackY(u),
        vx = unpackX(v),
        vy = unpackY(v),
        wide = sweepA64 >= ARC_FULL_TURN / 2,
        full = false,
        empty = false,
    )
}

/**
 * Whether the point (x, y), given relative to the ring's centre in 1/8-pixel
 * units, lies inside the sector.
 *
 * cross(u, p) > 0 means p is clockwise of u, in a coordinate system with y
 * downwards. For a sector up to a half turn that gives an intersection -
 * clockwise of the start ray and not yet past the end ray. Beyond a half
 * turn the same two half-planes have to be unioned instead, which is the
 * whole reason [ArcSector.wide] exists.
 */
fun inArcSector(s: ArcSector, x: Int, y: Int): Boolean {
    if (s.full) return true
    if (s.empty) return false
    val crossU = s.ux * y - s.uy * x
    val crossV = s.vx * y - s.vy * x
    return if (s.wide) crossU >= 0 || crossV < 0 else crossU >= 0 && crossV < 0
}

data class ArcRingGeometry(
    /** Side of the (square) object in pixels. */
    val size: Int,
    /** Ring thickness in pixels, measured inwards from the object's edge. */
    val thickness: Int,
    val track: ArcSector,
    val fill: ArcSector,
    val marker: ArcSector,
)

/**
 * How many of a pixel's 16 sub-samples fall in each band, packed into one
 * Int: fill in bits 0-7, track in 8-15, marker in 16-23. Each count is at
 * most 16, so a byte each is generous.
 *
 * The designer returns a small object here and is right to. This one is
 * called once per pixel of the object - 129600 times for a 360x360 ring -
 * and a per-pixel allocation on a phone is a per-pixel opportunity for the
 * collector to run in the middle of a frame. The arithmetic is identical
 * either way; only the container differs.
 */
@JvmInline
value class ArcPixelBands(private val packed: Int) {
    val fill: Int get() = packed and 0xFF
    val track: Int get() = (packed shr 8) and 0xFF
    val marker: Int get() = (packed shr 16) and 0xFF

    companion object {
        val EMPTY = ArcPixelBands(0)
        fun of(fill: Int, track: Int, marker: Int) =
            ArcPixelBands(fill or (track shl 8) or (marker shl 16))
    }
}

/**
 * Classifies one pixel's sub-samples.
 *
 * Bands are resolved per sub-sample rather than by rasterising each band
 * separately and blending the results in order. That ordering looks
 * equivalent and is not: where the fill's radial edge meets the ring's outer
 * edge, both bands have the same partial coverage, and compositing one over
 * the other leaves a seam of track colour that should not be there
 * (fill*c + track*c*(1-c) + bg*(1-c)^2 instead of fill*c + bg*(1-c)).
 * Counting each sub-sample once, into exactly one band, has no such seam.
 */
fun arcPixelBands(geom: ArcRingGeometry, px: Int, py: Int): ArcPixelBands {
    val s = ARC_SUBPIXEL_SCALE
    // The ring touches the object's edge, so the outer radius is half the side.
    val centre = (geom.size * s) / 2
    val rOuter = (geom.size * s) / 2
    val rInner = rOuter - geom.thickness * s
    val rOuter2 = rOuter * rOuter
    val rInner2 = if (rInner > 0) rInner * rInner else 0

    var fill = 0
    var track = 0
    var marker = 0

    // Two exact short cuts before sampling - not approximations, so they can
    // live in the shared algorithm without either side having to reproduce a
    // judgement call. A pixel whose nearest point is already past the outer
    // radius cannot contain any sub-sample, and one whose farthest point is
    // still inside the hole cannot either. Between them they dismiss the
    // entire middle of the dial and all four corners, which on a 360px ring is
    // most of the object.
    val xLo = px * s + 1 - centre
    val xHi = px * s + (2 * ARC_SUBSAMPLES - 1) - centre
    val yLo = py * s + 1 - centre
    val yHi = py * s + (2 * ARC_SUBSAMPLES - 1) - centre
    val minAbsX = if (xLo <= 0 && xHi >= 0) 0 else minOf(kotlin.math.abs(xLo), kotlin.math.abs(xHi))
    val minAbsY = if (yLo <= 0 && yHi >= 0) 0 else minOf(kotlin.math.abs(yLo), kotlin.math.abs(yHi))
    val maxAbsX = maxOf(kotlin.math.abs(xLo), kotlin.math.abs(xHi))
    val maxAbsY = maxOf(kotlin.math.abs(yLo), kotlin.math.abs(yHi))
    if (minAbsX * minAbsX + minAbsY * minAbsY >= rOuter2) return ArcPixelBands.EMPTY
    if (maxAbsX * maxAbsX + maxAbsY * maxAbsY < rInner2) return ArcPixelBands.EMPTY

    for (j in 0 until ARC_SUBSAMPLES) {
        // Sub-sample centres sit at (2k+1)/8 of a pixel, i.e. 1/8, 3/8, 5/8, 7/8.
        val y = py * s + 2 * j + 1 - centre
        for (i in 0 until ARC_SUBSAMPLES) {
            val x = px * s + 2 * i + 1 - centre
            val d2 = x * x + y * y
            if (d2 >= rOuter2 || d2 < rInner2) continue

            if (inArcSector(geom.marker, x, y)) marker++
            else if (inArcSector(geom.fill, x, y)) fill++
            else if (inArcSector(geom.track, x, y)) track++
            // Inside the annulus but outside the track - the gap at the bottom of
            // a 270 degree dial. Stays background.
        }
    }

    return ArcPixelBands.of(fill, track, marker)
}

// --- colour -----------------------------------------------------------------

/**
 * A colour reduced to the device's framebuffer, as separate 5/6/5 channels,
 * packed the way a firmware's `uint16_t` carries it.
 */
@JvmInline
value class Rgb565(val packed: Int) {
    val r: Int get() = (packed shr 11) and 0x1F
    val g: Int get() = (packed shr 5) and 0x3F
    val b: Int get() = packed and 0x1F

    companion object {
        fun of(r: Int, g: Int, b: Int) = Rgb565((r shl 11) or (g shl 5) or b)
    }
}

/**
 * Mirrors ColorScreenRenderer::parseHexColor()'s truncation exactly:
 * `(r and 0xF8) shl 8 or (g and 0xFC) shl 3 or b shr 3`. Truncation, not
 * rounding - matching the device's actual behaviour is the point, not being
 * more correct than it.
 *
 * Anything unparseable is black, matching the designer's own fallback: a
 * colour string that reached this point has already been through the export,
 * so a malformed one is a data bug, and a loud magenta would only make it
 * look like a rendering bug instead.
 */
fun toRgb565(color: String): Rgb565 {
    val c = color.trim()
    var r = 0
    var g = 0
    var b = 0
    if (c.startsWith("#") && c.length >= 7) {
        r = c.substring(1, 3).toIntOrNull(16) ?: 0
        g = c.substring(3, 5).toIntOrNull(16) ?: 0
        b = c.substring(5, 7).toIntOrNull(16) ?: 0
    } else if (c.lowercase() == "white") {
        r = 255
        g = 255
        b = 255
    }
    return Rgb565.of(r shr 3, g shr 2, b shr 3)
}

/**
 * Expands 5/6/5 back to 8 bits per channel by bit replication - the high bits
 * repeated into the low ones - which is what the firmware's BMP writer does
 * when it hands a snapshot to the HIL comparison. Getting this wrong (a
 * plain left shift) would put a constant few-LSB bias into every compared
 * pixel.
 *
 * Returns an opaque ARGB int, ready for a Bitmap: this is always the last
 * step, and a separate r/g/b holder would only be unpacked again.
 */
fun rgb565ToArgb(c: Rgb565): Int {
    val r = (c.r shl 3) or (c.r shr 2)
    val g = (c.g shl 2) or (c.g shr 4)
    val b = (c.b shl 3) or (c.b shr 2)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * Mixes up to three bands plus the background by coverage, in 5/6/5 space.
 *
 * The rounding is written out rather than inherited: an unrounded divide
 * would bias every anti-aliased edge half a step darker on one side.
 *
 * Takes the bands as three explicit colour/count pairs rather than a list,
 * for the same per-pixel-allocation reason as [arcPixelBands] - there are
 * exactly three bands and there never will be a fourth.
 */
fun blendBands(
    fillColour: Rgb565,
    fillCount: Int,
    trackColour: Rgb565,
    trackCount: Int,
    markerColour: Rgb565,
    markerCount: Int,
    background: Rgb565,
    backgroundCount: Int,
): Rgb565 {
    val r = background.r * backgroundCount +
        fillColour.r * fillCount + trackColour.r * trackCount + markerColour.r * markerCount
    val g = background.g * backgroundCount +
        fillColour.g * fillCount + trackColour.g * trackCount + markerColour.g * markerCount
    val b = background.b * backgroundCount +
        fillColour.b * fillCount + trackColour.b * trackCount + markerColour.b * markerCount
    val half = ARC_COVERAGE_MAX / 2
    // All operands are non-negative, so integer division floors - the same
    // thing Math.floor does on the designer's side.
    return Rgb565.of(
        (r + half) / ARC_COVERAGE_MAX,
        (g + half) / ARC_COVERAGE_MAX,
        (b + half) / ARC_COVERAGE_MAX,
    )
}
