package com.screensmith.android.render

import kotlin.math.roundToInt

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

/**
 * A rounded end of the band: a disc on the centreline at the end angle.
 *
 * Which is the whole definition of a pill, read literally - the band is every
 * point within half a thickness of its centreline arc, and past the last
 * angle that is a disc. The cost is that the ends reach half a thickness past
 * minAngle and maxAngle, exactly as a bar's pill reaches past its own run
 * (the designer's docs/2026-09-22-arc-look.md).
 *
 * All lengths are in 1/8 pixel from the object's centre, like every other
 * coordinate here; the tangent is a direction vector from the sine table,
 * scaled by [ARC_SIN_SCALE] - kept because it says which way the band was
 * heading when it stopped.
 */
data class ArcCap(
    val cx: Int,
    val cy: Int,
    /** Outward along the band at this end. */
    val tx: Int,
    val ty: Int,
    /** Half the band's thickness. */
    val r: Int,
)

/**
 * The setpoint handle: the bar's own, bent onto a ring.
 *
 * Same proportions as the slider's ([LevelShape.kt]): as long as eleven
 * quarters of the thickness, an eleventh of that wide, with a gap of three
 * twenty-seconds each side. It lies across the band on a straight line rather
 * than following the curve, which is what a handle 60 units long on a ring 22
 * thick looks like anyway - and what the bar does.
 */
data class ArcHandle(
    /** Centre, on the ring's centreline at the setpoint's angle. */
    val cx: Int,
    val cy: Int,
    /** Along the band (the handle's width runs this way). */
    val tx: Int,
    val ty: Int,
    /** Outwards from the centre (the handle's length runs this way). */
    val rx: Int,
    val ry: Int,
    val halfWidth: Int,
    val halfLength: Int,
    /** Cut out of the band on each side of the handle, on top of [halfWidth]. */
    val gap: Int,
)

data class ArcRingGeometry(
    /** Side of the (square) object in pixels. */
    val size: Int,
    /** Ring thickness in pixels. */
    val thickness: Int,
    /**
     * How far the ring sits inside the object's own edge, in pixels.
     *
     * Room for the handle, which lies across the band and stands out of it on
     * both sides: with the ring touching the object's edge the outer half of
     * the handle would fall outside the object - and an object that draws past
     * its own rectangle is clipped by the designer's buffer, by this app's box
     * and by the firmware's object rect alike.
     *
     * Reserved whenever the object CAN have a handle rather than when one is
     * being drawn, so the ring does not jump inwards the moment a setpoint
     * arrives.
     */
    val inset: Int,
    val track: ArcSector,
    val fill: ArcSector,
    /** Null where the scale goes all the way round: nothing to round. */
    val startCap: ArcCap? = null,
    val endCap: ArcCap? = null,
    /** Whether each cap belongs to the fill rather than to the track. */
    val startCapFilled: Boolean = false,
    val endCapFilled: Boolean = false,
    val handle: ArcHandle? = null,
    /**
     * The track is drawn as its own outline, one pixel wide, instead of as a
     * body - for a panel that cannot show the mixed colour the track would
     * otherwise be. The bar answers the same question the same way
     * ([levelTrackLook]'s `framed`, [levelFrameInner]).
     */
    val framed: Boolean = false,
)

/**
 * How many of a pixel's 16 sub-samples fall in each band, packed into one
 * Int: fill in bits 0-7, track in 8-15, handle in 16-23. Each count is at
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

    /**
     * The setpoint handle. Called `marker` until 2026-09-22, when it stopped
     * being a wedge of the ring and became the slider's own handle: a pill
     * lying across the band, standing out of it on both sides, with a gap cut
     * either side of it.
     */
    val handle: Int get() = (packed shr 16) and 0xFF

    companion object {
        val EMPTY = ArcPixelBands(0)
        fun of(fill: Int, track: Int, handle: Int) =
            ArcPixelBands(fill or (track shl 8) or (handle shl 16))
    }
}

/** One pixel of the frame, in 1/8 units. */
private const val ARC_FRAME = ARC_SUBPIXEL_SCALE

/** Whether a point is inside a cap's half-disc. */
private fun inArcCap(cap: ArcCap, x: Int, y: Int): Boolean {
    val dx = x - cap.cx
    val dy = y - cap.cy
    return dx * dx + dy * dy <= cap.r * cap.r
}

/** Whether a point inside a cap is within the frame's own pixel of its edge. */
private fun capEdge(cap: ArcCap, x: Int, y: Int): Boolean {
    val dx = x - cap.cx
    val dy = y - cap.cy
    val inner = cap.r - ARC_FRAME
    return dx * dx + dy * dy >= inner * inner
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
    val centre = (geom.size * s) / 2
    // The ring sits inside the object's edge by the room reserved for the
    // handle; with no handle possible that room is zero and the band touches
    // the edge, which is where a ring has always sat.
    val rOuter = (geom.size * s) / 2 - geom.inset * s
    val rInner = rOuter - geom.thickness * s
    val rOuter2 = rOuter * rOuter
    val rInner2 = if (rInner > 0) rInner * rInner else 0
    // Where the frame's own pixel ends, when the track is an outline.
    val rOuterInner2 = (rOuter - ARC_FRAME) * (rOuter - ARC_FRAME)
    val rInnerOuter2 = (rInner + ARC_FRAME) * (rInner + ARC_FRAME)

    var fill = 0
    var track = 0
    var handle = 0

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
    // The handle reaches out of the ring on both sides, so the short cuts have
    // to allow for it - otherwise the very pixels it overhangs into are thrown
    // away before it is ever tested, and the handle comes out as a sliver
    // inside the band.
    val h = geom.handle
    val reach = h?.halfLength ?: 0
    val rReachOuter = rOuter + reach
    val rReachInner = if (rInner - reach > 0) rInner - reach else 0
    if (minAbsX * minAbsX + minAbsY * minAbsY >= rReachOuter * rReachOuter) return ArcPixelBands.EMPTY
    if (maxAbsX * maxAbsX + maxAbsY * maxAbsY < rReachInner * rReachInner) return ArcPixelBands.EMPTY

    val startCap = geom.startCap
    val endCap = geom.endCap

    for (j in 0 until ARC_SUBSAMPLES) {
        // Sub-sample centres sit at (2k+1)/8 of a pixel, i.e. 1/8, 3/8, 5/8, 7/8.
        val y = py * s + 2 * j + 1 - centre
        for (i in 0 until ARC_SUBSAMPLES) {
            val x = px * s + 2 * i + 1 - centre
            val d2 = x * x + y * y

            // The handle first, and before the ring's own radii: it lies ACROSS
            // the band and stands out of it on both sides, which is what says
            // "a thing lying on top" rather than "a slice of the ring".
            if (h != null) {
                val dx = x - h.cx
                val dy = y - h.cy
                // Floating divides, not integer ones. The designer's are
                // doubles and both results are squared below, so truncating
                // here would move both of the handle's long edges.
                val along = (dx.toDouble() * h.tx + dy.toDouble() * h.ty) / ARC_SIN_SCALE
                val out = (dx.toDouble() * h.rx + dy.toDouble() * h.ry) / ARC_SIN_SCALE
                if (out >= -h.halfLength && out <= h.halfLength) {
                    val absAlong = if (along < 0) -along else along
                    val absOut = if (out < 0) -out else out
                    // A pill: straight sides, and a half-circle at each radial end.
                    val straight = h.halfLength - h.halfWidth
                    var inHandle = absAlong <= h.halfWidth
                    if (inHandle && absOut > straight) {
                        val over = absOut - straight
                        inHandle = over * over + along * along <= h.halfWidth.toDouble() * h.halfWidth
                    }
                    if (inHandle) {
                        handle++
                        continue
                    }
                    // The gap: background either side of the handle, cut out of
                    // the band rather than drawn over it.
                    if (absAlong <= h.halfWidth + h.gap) continue
                }
            }

            if (d2 >= rOuter2 || d2 < rInner2) continue

            // The band is every point within half a thickness of its centreline:
            // inside the scale's angles, or inside one of the end discs.
            val inStartCap = startCap != null && inArcCap(startCap, x, y)
            val inEndCap = endCap != null && inArcCap(endCap, x, y)
            val inside = inArcSector(geom.track, x, y)
            if (!inside && !inStartCap && !inEndCap) continue

            // A cap belongs to whichever band reaches that end of the scale.
            // Where a cap overlaps the band proper the sector decides -
            // otherwise the fill's own straight edge would be rounded off by
            // the track's cap.
            val filled = when {
                inside -> inArcSector(geom.fill, x, y)
                inStartCap -> geom.startCapFilled
                else -> geom.endCapFilled
            }
            if (filled) {
                fill++
                continue
            }
            if (!geom.framed) {
                track++
                continue
            }
            // An outline is the band's own outer pixel, and a pill's outline is
            // two long edges that STOP where the rounded end begins, plus the
            // half of that end which sticks out past it. Inside the scale's
            // angles the band is an ordinary band and its radii are its edges;
            // past them there is only the cap, and only its rim. Where the band
            // was cut - at the fill's edge, at the handle's gap - the frame is
            // left open, same as the bar's levelFrameInner.
            val onRadius = inside && (d2 >= rOuterInner2 || d2 <= rInnerOuter2)
            val onCapRim = !inside &&
                ((inStartCap && capEdge(startCap!!, x, y)) || (inEndCap && capEdge(endCap!!, x, y)))
            if (onRadius || onCapRim) track++
        }
    }

    return ArcPixelBands.of(fill, track, handle)
}

// --- the band's own geometry ------------------------------------------------

/** The ring's centreline radius, in 1/8 pixel - where caps and handle sit. */
private fun arcMidRadius(size: Int, thickness: Int, inset: Int): Int =
    size * ARC_SUBPIXEL_SCALE / 2 - inset * ARC_SUBPIXEL_SCALE - thickness * ARC_SUBPIXEL_SCALE / 2

/**
 * A point on the centreline, in 1/8 pixel from the object's centre, packed
 * x-then-y like [arcDirection]'s result.
 *
 * The divide is a floating one rounded half toward positive infinity, which is
 * what JS's `Math.round` does and what [roundToInt] does; an integer divide
 * here would truncate toward zero and put every cap and handle up to an eighth
 * of a pixel out on the negative side of the dial.
 */
private fun arcPointAt(size: Int, thickness: Int, inset: Int, angle64: Int): Long {
    val d = arcDirection(angle64)
    val rMid = arcMidRadius(size, thickness, inset)
    return packXY(
        (unpackX(d).toDouble() * rMid / ARC_SIN_SCALE).roundToInt(),
        (unpackY(d).toDouble() * rMid / ARC_SIN_SCALE).roundToInt(),
    )
}

/** The two rounded ends of a scale - both null where it goes all the way round. */
data class ArcCaps(val startCap: ArcCap?, val endCap: ArcCap?)

fun arcCaps(size: Int, thickness: Int, inset: Int, start64: Int, sweep64: Int): ArcCaps {
    if (sweep64 >= ARC_FULL_TURN) return ArcCaps(null, null)
    val r = thickness * ARC_SUBPIXEL_SCALE / 2
    val startTangent = arcDirection(start64 - 90 * ARC_ANGLE_SCALE)
    val endTangent = arcDirection(start64 + sweep64 + 90 * ARC_ANGLE_SCALE)
    val startAt = arcPointAt(size, thickness, inset, start64)
    val endAt = arcPointAt(size, thickness, inset, start64 + sweep64)
    return ArcCaps(
        startCap = ArcCap(
            cx = unpackX(startAt),
            cy = unpackY(startAt),
            tx = unpackX(startTangent),
            ty = unpackY(startTangent),
            r = r,
        ),
        endCap = ArcCap(
            cx = unpackX(endAt),
            cy = unpackY(endAt),
            tx = unpackX(endTangent),
            ty = unpackY(endTangent),
            r = r,
        ),
    )
}

/** The handle's own measurements, in 1/8 pixel. */
private data class ArcHandleSize(val length: Int, val width: Int, val gap: Int)

/**
 * Eleven quarters of the thickness long, an eleventh of that wide, with a gap
 * of three twenty-seconds each side: exactly [levelHandleLength]/[levelHandleWidth]/
 * [levelHandleGap], because "looks like the slider's" was the whole point.
 *
 * Two clamps a straight bar never needs. A handle longer than twice the
 * centreline's radius would reach through the middle of the dial and out the
 * other side; one longer than a third of the scale's own run leaves the fill
 * nowhere to show. Width and gap follow the length the thickness ASKS for -
 * what a small dial is short of is room along the handle, not across it.
 */
private fun arcHandleSize(thickness: Int, midRadius: Int, runLength: Int): ArcHandleSize {
    val wanted = thickness * 11 / 4
    return ArcHandleSize(
        length = maxOf(2, minOf(wanted, 2 * midRadius, runLength / 3)),
        width = maxOf(3, wanted / 11),
        gap = maxOf(2, wanted * 3 / 22),
    )
}

/** The handle lying across the band at [angle64]. See [arcHandleSize]. */
fun arcHandleBand(size: Int, thickness: Int, inset: Int, angle64: Int, sweep64: Int): ArcHandle {
    val rMid = arcMidRadius(size, thickness, inset)
    // The scale's own length along the centreline: 2*pi*r * sweep/turn, in
    // whole 1/8 pixels. 355/113 is pi to seven digits, in integers, so every
    // copy of this arrives at the same number.
    //
    // In Long, not Int: a 360 px dial reaches 1.66e10 here, which overflows
    // int32 and would hand the handle a length out of a wrapped-round number.
    val runLength = ((2L * 355L * rMid * sweep64) / (113L * ARC_FULL_TURN)).toInt()
    val measured = arcHandleSize(thickness * ARC_SUBPIXEL_SCALE, rMid, runLength)
    val radial = arcDirection(angle64)
    val tangent = arcDirection(angle64 + 90 * ARC_ANGLE_SCALE)
    val at = arcPointAt(size, thickness, inset, angle64)
    return ArcHandle(
        cx = unpackX(at),
        cy = unpackY(at),
        tx = unpackX(tangent),
        ty = unpackY(tangent),
        rx = unpackX(radial),
        ry = unpackY(radial),
        halfWidth = maxOf(1, measured.width / 2),
        halfLength = maxOf(1, measured.length / 2),
        gap = measured.gap,
    )
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
    handleColour: Rgb565,
    handleCount: Int,
    background: Rgb565,
    backgroundCount: Int,
): Rgb565 {
    val r = background.r * backgroundCount +
        fillColour.r * fillCount + trackColour.r * trackCount + handleColour.r * handleCount
    val g = background.g * backgroundCount +
        fillColour.g * fillCount + trackColour.g * trackCount + handleColour.g * handleCount
    val b = background.b * backgroundCount +
        fillColour.b * fillCount + trackColour.b * trackCount + handleColour.b * handleCount
    val half = ARC_COVERAGE_MAX / 2
    // All operands are non-negative, so integer division floors - the same
    // thing Math.floor does on the designer's side.
    return Rgb565.of(
        (r + half) / ARC_COVERAGE_MAX,
        (g + half) / ARC_COVERAGE_MAX,
        (b + half) / ARC_COVERAGE_MAX,
    )
}
