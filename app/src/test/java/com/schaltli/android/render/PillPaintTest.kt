package com.schaltli.android.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The half of the pill rasterizer the golden file cannot see: what a pixel's
 * band counts turn into once they are mixed.
 *
 * [PillRasterGoldenTest] pins the geometry against the designer's own
 * recording. There is no probe in the designer for the colours - what a set of
 * counts mixes into is [blendBands]' arithmetic, which `build-arc-golden.js`
 * already pins on this target - so the three claims below are the ones the
 * designer makes about the finished picture in `e2e/pill-raster.spec.ts`,
 * ported to the place this app decides them. They are change detectors rather
 * than cross-port proofs, and they are worth having because each of them is a
 * shortcut a port would otherwise take without noticing.
 */
class PillPaintTest {

    private fun pixelAt(pixels: PillPixels, x: Int, y: Int): Int =
        pixels.argb[(y - pixels.y) * pixels.w + (x - pixels.x)]

    private fun channels(argb: Int) = Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    /**
     * A pixel one run covers outright keeps that run's colour EXACTLY - no trip
     * through 5/6/5 and back.
     *
     * Every other object here paints the author's colour as it is, and a bar
     * whose body came back a step off would not match the box beside it. The
     * colour is chosen so that the shortcut is visible: #4CAF50 does not
     * survive the round trip, which would hand back #4AAE52.
     */
    @Test
    fun `a pixel one run owns outright keeps its colour exactly`() {
        val band = PillBand(x = 10, y = 10, w = 100, h = 16, rLow = 8, rHigh = 8)
        val pixels = pillPixels(listOf(PaintedPill(band, "#4CAF50")), "#ffffff")!!

        assertEquals("the middle of the run", 0xFF4CAF50.toInt(), pixelAt(pixels, 50, 17))
        // The same colour taken through the framebuffer and back, to show that
        // the assertion above is not one the shortcut would also satisfy.
        assertEquals(0xFF4AAE52.toInt(), rgb565ToArgb(toRgb565("#4CAF50")))
    }

    /**
     * Where the knob meets the track, the mixture is of THOSE TWO - never of
     * the knob and the screen behind it.
     *
     * This is the one thing a second pass of painting cannot do, and the whole
     * reason the runs are counted in one pass. The knob is white, the track is
     * the switch's colour and the screen behind both is nearly black: a knob
     * blended with the SCREEN instead of with the track would leave a dark rim
     * around it, and on any other background a rim of that background's colour.
     */
    @Test
    fun `where the knob meets the track the mixture is of those two`() {
        val track = PillBand(x = 0, y = 0, w = 60, h = 20, rLow = 10, rHigh = 10)
        val knob = PillBand(x = 20, y = 0, w = 20, h = 20, rLow = 10, rHigh = 10)
        val pixels = pillPixels(
            // The knob first: it wins every sub-sample it covers.
            listOf(PaintedPill(knob, "#ffffff"), PaintedPill(track, "#6750A4")),
            "#101010",
        )!!

        // The track's straight middle only, so every pixel looked at is one the
        // track really covers - its own rounded ends are where it meets the
        // screen, and those are allowed to be dark.
        val half = track.h / 2
        var mixed = 0
        for (y in track.y + 1 until track.y + track.h - 1) {
            for (x in track.x + half until track.x + track.w - half) {
                val (r, g, b) = channels(pixelAt(pixels, x, y))
                assertTrue("($x, $y) is darker than the track: $r,$g,$b", r >= 0x67 && g >= 0x50 && b >= 0xA4)
                val white = r == 0xFF && g == 0xFF && b == 0xFF
                val purple = r == 0x67 && g == 0x50 && b == 0xA4
                if (!white && !purple) mixed++
            }
        }
        assertTrue("the knob's edge is not soft at all", mixed > 8)
    }

    /**
     * A run with no colour claims its pixels and paints nothing in them, which
     * is how an outline is described: the outer pill in the outline's colour,
     * the inside knocked out of it.
     *
     * Knocked out rather than painted over, so that whatever is behind the
     * control still shows through it - a screen colour, a background image -
     * exactly as [fillRoundRectRing] has always made its ring.
     */
    @Test
    fun `a run with no colour takes its pixels without painting them`() {
        val outer = PillBand(x = 10, y = 10, w = 100, h = 16, rLow = 8, rHigh = 8)
        val inner = PillBand(x = 11, y = 11, w = 98, h = 14, rLow = 7, rHigh = 7)
        val pixels = pillPixels(
            listOf(PaintedPill(inner, null), PaintedPill(outer, "#6750A4")),
            "#101010",
        )!!

        assertEquals("inside the ring", 0, pixelAt(pixels, 60, 17))
        assertEquals("the ring's own top edge", 0xFF6750A4.toInt(), pixelAt(pixels, 60, 10))
        assertEquals("the ring's own bottom edge", 0xFF6750A4.toInt(), pixelAt(pixels, 60, 25))
    }
}
