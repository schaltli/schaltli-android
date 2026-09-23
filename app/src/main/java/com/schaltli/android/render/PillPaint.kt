package com.schaltli.android.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.roundToInt

/**
 * Paints a set of pill-shaped runs, anti-aliased, in one pass - this repo's
 * copy of the designer's `components/canvas/renderers/paint-pills.ts`.
 *
 * The runs are given in priority order - a handle before the track it lies on,
 * a hole before the ring it cuts - because every sub-sample is counted into
 * exactly one of them ([PillRaster.kt]). That is the whole reason this exists
 * rather than a sequence of [fillRoundRect] calls: where two runs meet,
 * painting one over the other leaves a seam of the lower one's colour along
 * the join.
 *
 * Rasterized at PROJECT resolution - one bitmap pixel per project unit - and
 * blitted with filtering and anti-aliasing both off, exactly the way
 * [com.schaltli.android.ui.objects.ArcLevelView] blits its ring and
 * [com.schaltli.android.ui.ScreenRenderer] blits a screen's flattened
 * background. That is not a shortcut: it is the only treatment
 * `hil/android/orchestrator.js`'s `matchDeviceScaling` was built to reproduce,
 * so a pill drawn this way lands on the same device pixels as the upscaled
 * reference. The designer does the same thing on its own side and for a second
 * reason as well - a soft edge drawn onto a scaled canvas is softened twice.
 *
 * **Nothing here is used below 24 bit.** On a 1-bit panel there is nothing to
 * mix into: every soft pixel snaps to black or white on the way to the glass,
 * and the device draws these shapes with whole pixels anyway. A 4-bit panel
 * could take a soft edge in its sixteen greys, but only once its own copy of
 * this quantises the blend the same way - a decision about the firmware, not
 * about the designer, and still open. The callers keep their existing
 * whole-pixel path for those, unchanged down to the byte.
 */

/**
 * One run and the colour it is painted in.
 *
 * A null [colour] is a hole: the run claims its pixels - nothing behind it
 * shows through - but paints nothing, which is how an outline is described
 * (the outer pill in the outline's colour, the inside knocked out of it).
 */
data class PaintedPill(val band: PillBand, val colour: String?)

/** A finished block of pixels and where on the screen it belongs, in project units. */
class PillPixels(val x: Int, val y: Int, val w: Int, val h: Int, val argb: IntArray)

/**
 * Sixteen sub-samples a pixel, mixed in RGB565 - the arc's rules exactly.
 *
 * Null when there is nothing to draw. Pixels no run reaches are left fully
 * transparent, so whatever is behind the control - a screen colour, a
 * background image - still shows through.
 */
fun pillPixels(painted: List<PaintedPill>, background: String): PillPixels? {
    val runs = painted.filter { it.band.w > 0 && it.band.h > 0 && it.colour != "transparent" }
    if (runs.isEmpty()) return null

    val bounds = pillBandsBounds(runs.map { it.band })
    if (bounds.w <= 0 || bounds.h <= 0) return null

    val bands = runs.map { it.band }
    val colours = runs.map { run -> run.colour?.let { toRgb565(it) } }
    val exact = runs.map { run -> run.colour?.let { exactArgb(it) } }
    val mixInto = toRgb565(background)

    val argb = IntArray(bounds.w * bounds.h)
    val counts = IntArray(bands.size)

    for (py in 0 until bounds.h) {
        for (px in 0 until bounds.w) {
            pillPixelBands(bands, bounds.x + px, bounds.y + py, counts)

            // The same arithmetic [blendBands] does, over a list rather than
            // over three fixed pairs: a ring has exactly three bands and never
            // will have a fourth, while a pill control has as many runs as it
            // has parts. Accumulated in one pass and rounded once - an
            // unrounded divide would bias every anti-aliased edge half a step
            // darker on one side, and rounding per run would bias it several
            // times over.
            var r = 0
            var g = 0
            var b = 0
            var covered = 0
            var inked = 0
            var only = -1
            for (i in bands.indices) {
                val count = counts[i]
                if (count == 0) continue
                val colour = colours[i] ?: continue
                r += colour.r * count
                g += colour.g * count
                b += colour.b * count
                covered += count
                inked++
                only = i
            }
            if (covered == 0) continue

            val at = py * bounds.w + px
            // A pixel that one run owns outright keeps that run's colour
            // exactly - no trip through 5/6/5 and back. Every other object
            // here paints the author's colour as it is, and a bar whose body
            // came back a step off would not match the box beside it. The
            // mixing above is only for the pixels an edge passes through,
            // where a step is what nobody can see anyway.
            if (inked == 1 && covered == PILL_COVERAGE_MAX) {
                argb[at] = exact[only]!!
                continue
            }

            val rest = PILL_COVERAGE_MAX - covered
            r += mixInto.r * rest
            g += mixInto.g * rest
            b += mixInto.b * rest
            val half = PILL_COVERAGE_MAX / 2
            // All operands are non-negative, so integer division floors - the
            // same thing Math.floor does on the designer's side.
            argb[at] = rgb565ToArgb(
                Rgb565.of(
                    (r + half) / PILL_COVERAGE_MAX,
                    (g + half) / PILL_COVERAGE_MAX,
                    (b + half) / PILL_COVERAGE_MAX,
                ),
            )
            // Opaque, with the background already mixed into the soft pixels -
            // not alpha, which would mix it a second time when the bitmap is
            // blitted. The arc does exactly this, for exactly this reason. Over
            // a background *image* it is an approximation, and the same one on
            // every side, so only the eye can tell.
        }
    }

    return PillPixels(bounds.x, bounds.y, bounds.w, bounds.h, argb)
}

/** A finished picture of a control and where on the screen it belongs. */
class PillBitmap(val x: Int, val y: Int, val bitmap: Bitmap)

/**
 * The runs, rasterized once.
 *
 * Held apart from [drawPills] so that a caller can keep the result across
 * frames - `remember`ed on what the picture depends on, exactly as
 * [com.schaltli.android.ui.objects.ArcLevelView] keeps its ring. This walks
 * sixteen sub-samples of every pixel of the control against every run of it,
 * which is a few hundred thousand integer comparisons for a bar the width of a
 * phone; doing that inside the draw pass would spend them again on every
 * recomposition, and a slider is dragged.
 */
fun pillBitmap(painted: List<PaintedPill>, background: String): PillBitmap? {
    val pixels = pillPixels(painted, background) ?: return null
    return PillBitmap(
        pixels.x,
        pixels.y,
        Bitmap.createBitmap(pixels.argb, pixels.w, pixels.h, Bitmap.Config.ARGB_8888),
    )
}

/**
 * A rasterized control, blitted onto [canvas] with filtering off.
 *
 * [ox]/[oy] are the object's own origin, since each object draws into a canvas
 * of its own; [scale] is device pixels per project unit.
 */
fun drawPills(canvas: Canvas, pills: PillBitmap?, ox: Int, oy: Int, scale: Float) {
    if (pills == null) return
    canvas.drawBitmap(
        pills.bitmap,
        null,
        Rect(
            ((pills.x - ox) * scale).roundToInt(),
            ((pills.y - oy) * scale).roundToInt(),
            ((pills.x - ox + pills.bitmap.width) * scale).roundToInt(),
            ((pills.y - oy + pills.bitmap.height) * scale).roundToInt(),
        ),
        Paint().apply {
            isAntiAlias = false
            isFilterBitmap = false
        },
    )
}

/**
 * A colour as the eight bits a bitmap takes, parsed the way [toRgb565] parses
 * it - the designer's `exactRgb`. Anything that is not a plain "#rrggbb" goes
 * through 5/6/5 and back, which is what the rest of this file would have done
 * with it anyway.
 */
private fun exactArgb(colour: String): Int {
    val c = colour.trim()
    if (c.startsWith("#") && c.length >= 7) {
        val r = c.substring(1, 3).toIntOrNull(16) ?: 0
        val g = c.substring(3, 5).toIntOrNull(16) ?: 0
        val b = c.substring(5, 7).toIntOrNull(16) ?: 0
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    return rgb565ToArgb(toRgb565(c))
}
