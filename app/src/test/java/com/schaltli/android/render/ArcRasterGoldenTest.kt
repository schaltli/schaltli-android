package com.schaltli.android.render

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds this repo's copy of the arc rasterizer to the numbers the designer's
 * copy produces.
 *
 * The rasterizer exists three times over - `lib/arc-raster.ts` in the
 * designer, `ArcRaster.cpp` in each firmware, and [ArcRaster.kt] here - and
 * every line of it is written in integer arithmetic for one reason: the
 * copies must not be able to disagree. This test is what makes that claim
 * checkable rather than merely intended.
 *
 * The golden file is recorded from the designer itself by
 * `hil/android/fixtures/build-arc-golden.js` in the designer repo, which
 * drives the real `arcPixelBands`/`blendBands` through a browser. So this is
 * not two implementations of a spec agreeing with each other's reading of
 * it; it is this implementation agreeing with the one the reference image is
 * actually rendered by.
 *
 * Regenerate after any change to either side:
 *
 *   (designer) npm run dev
 *   (designer) node hil/android/fixtures/build-arc-golden.js
 *
 * A HIL run would find the same disagreements eventually, as a handful of
 * stray pixels along one edge, once a phone is connected and a bundle has
 * been imported by hand. This finds them in milliseconds and names the pixel.
 */
class ArcRasterGoldenTest {

    private fun loadGolden(): JSONObject {
        val stream = javaClass.classLoader!!.getResourceAsStream("arc-raster-golden.json")
            ?: error("arc-raster-golden.json is missing - regenerate it with build-arc-golden.js")
        return JSONObject(stream.bufferedReader().use { it.readText() })
    }

    @Test
    fun `every recorded pixel classifies into the same bands`() {
        val golden = loadGolden()
        val cases = golden.getJSONArray("cases")
        assertTrue("the golden file records no cases at all", cases.length() > 0)

        var checked = 0
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            val geom = geometryOf(case)

            val pixels = case.getJSONArray("pixels")
            val fill = case.getJSONArray("fill")
            val track = case.getJSONArray("track")
            val handle = case.getJSONArray("handle")
            val pointer = case.optJSONArray("pointer")

            for (p in 0 until pixels.length()) {
                val pixel = pixels.getJSONArray(p)
                val px = pixel.getInt(0)
                val py = pixel.getInt(1)
                val bands = arcPixelBands(geom, px, py)

                // Reported per pixel rather than as "the case differs": a
                // rasterizer that is wrong is usually wrong along one edge,
                // and the first differing coordinate says which edge.
                assertEquals("$name fill at ($px, $py)", fill.getInt(p), bands.fill)
                assertEquals("$name track at ($px, $py)", track.getInt(p), bands.track)
                assertEquals("$name handle at ($px, $py)", handle.getInt(p), bands.handle)
                assertEquals("$name pointer at ($px, $py)", pointer?.getInt(p) ?: 0, bands.pointer)
                checked++
            }
        }
        println("ArcRasterGoldenTest: ${cases.length()} case(s), $checked pixel(s) classified")
    }

    @Test
    fun `every recorded pixel mixes to the same colour`() {
        val golden = loadGolden()
        val colours = golden.getJSONObject("colours")
        val trackColour = toRgb565(colours.getString("track"))
        val fillColour = toRgb565(colours.getString("fill"))
        val handleColour = toRgb565(colours.getString("handle"))
        val pointerColour = toRgb565(colours.optString("pointer", colours.getString("handle")))
        val background = toRgb565(colours.getString("background"))

        val cases = golden.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            val pixels = case.getJSONArray("pixels")
            val fill = case.getJSONArray("fill")
            val track = case.getJSONArray("track")
            val handle = case.getJSONArray("handle")
            val pointer = case.optJSONArray("pointer")
            val rgb = case.getJSONArray("rgb")

            for (p in 0 until pixels.length()) {
                val f = fill.getInt(p)
                val t = track.getInt(p)
                val m = handle.getInt(p)
                val n = pointer?.getInt(p) ?: 0
                val covered = f + t + m + n
                val mixed = blendBands(
                    fillColour, f,
                    trackColour, t,
                    handleColour, m,
                    background, ARC_COVERAGE_MAX - covered,
                    pointerColour, n,
                )
                // The golden carries 0xRRGGBB; rgb565ToArgb adds an opaque
                // alpha this comparison does not care about.
                val actual = rgb565ToArgb(mixed) and 0xFFFFFF
                val pixel = pixels.getJSONArray(p)
                assertEquals(
                    "$name colour at (${pixel.getInt(0)}, ${pixel.getInt(1)})",
                    String.format("#%06x", rgb.getInt(p)),
                    String.format("#%06x", actual),
                )
            }
        }
    }

    /**
     * The golden has to contain anti-aliased pixels, or both tests above
     * would pass against a rasterizer with no anti-aliasing at all - which
     * is exactly the shortcut a port is most tempted to take, and exactly
     * the thing the whole integer-arithmetic design exists to prevent.
     */
    @Test
    fun `the golden actually covers partially-covered pixels`() {
        val cases = loadGolden().getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val fill = case.getJSONArray("fill")
            val track = case.getJSONArray("track")
            val handle = case.getJSONArray("handle")
            val pointer = case.optJSONArray("pointer")
            var partial = 0
            for (p in 0 until fill.length()) {
                val covered = fill.getInt(p) + track.getInt(p) + handle.getInt(p) + (pointer?.getInt(p) ?: 0)
                if (covered in 1 until ARC_COVERAGE_MAX) partial++
            }
            assertTrue(
                "${case.getString("name")} records no partially-covered pixel",
                partial > 0,
            )
        }
    }

    /**
     * The same inputs the recorder builds, and built the same way:
     * `__arcRasterForTest` in the designer's app/test-render/page.tsx drives
     * the renderer's own `arcCaps` and `arcHandleBand` rather than a copy of
     * them, so this side does too. Half a pixel of cap is exactly what the
     * comparison exists to catch, and a second implementation of it in a test
     * could only ever agree with itself.
     */
    @Test
    fun `every recorded pixel takes the same gradient step and glow level`() {
        // The ring's gradient and glow (LevelGlow.kt), 2026-09-28: the two
        // numbers every port has to arrive at before it mixes a colour.
        val cases = loadGolden().optJSONArray("glowCases")
            ?: error("the golden file has no glowCases - regenerate it with build-arc-golden.js")
        var glowing = 0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val size = c.getInt("size")
            val sweep64 = c.getInt("sweep64")
            val start64 = c.getInt("start64")
            val filled64 = c.getInt("filled64")
            val fromEnd = c.getBoolean("fromEnd")
            val levels = c.getInt("levels")
            val gradient = makeArcGradient(start64, sweep64)
            val lastStep = arcStepOfOffset(maxOf(0, filled64 - 1), sweep64)
            val fillStart64 = if (fromEnd) start64 + sweep64 - filled64 else start64
            val glow = arcGlowFor(size, c.getInt("thickness"), c.getInt("inset"), fillStart64, filled64, lastStep)
            val centre = size * 8 / 2
            val pixels = c.getJSONArray("pixels")
            val steps = c.getJSONArray("step")
            val levelsWanted = c.getJSONArray("level")
            for (p in 0 until pixels.length()) {
                val px = pixels.getJSONArray(p).getInt(0)
                val py = pixels.getJSONArray(p).getInt(1)
                val cx = px * 8 + 4 - centre
                val cy = py * 8 + 4 - centre
                val raw = arcGradientStep(gradient, cx, cy)
                val step = if (fromEnd) GRADIENT_STEPS - 1 - raw else raw
                val level = if (filled64 > 0) arcGlowLevel(glow, cx, cy, levels) else 0
                assertEquals("$name step at ($px, $py)", steps.getInt(p), step)
                assertEquals("$name glow at ($px, $py)", levelsWanted.getInt(p), level)
                if (level > 0) glowing++
            }
        }
        assertTrue("no recorded pixel lies in a glow", glowing > 0)
    }

    @Test
    fun `the gradient and the glow mix colours as the designer does`() {
        val samples = loadGolden().optJSONArray("glowColours")
            ?: error("the golden file has no glowColours - regenerate it with build-arc-golden.js")
        for (i in 0 until samples.length()) {
            val q = samples.getJSONObject(i)
            fun rgb(key: String) = q.getJSONArray(key).let { Rgb565.of(it.getInt(0), it.getInt(1), it.getInt(2)) }
            val g = gradient565(rgb("from"), rgb("to"), q.getInt("step"), q.getInt("steps"))
            val m = blend565(rgb("under"), g, q.getInt("alpha"))
            assertEquals("sample $i gradient", rgb("gradient"), g)
            assertEquals("sample $i blended", rgb("blended"), m)
        }
    }

    private fun geometryOf(case: JSONObject): ArcRingGeometry {
        val size = case.getInt("size")
        val thickness = case.getInt("thickness")
        val inset = case.optInt("inset", 0)
        val trackStart64 = case.getInt("trackStart64")
        val trackSweep64 = case.getInt("trackSweep64")
        val caps = arcCaps(size, thickness, inset, trackStart64, trackSweep64)
        return ArcRingGeometry(
            size = size,
            thickness = thickness,
            inset = inset,
            track = makeArcSector(trackStart64, trackSweep64),
            fill = makeArcSector(case.getInt("fillStart64"), case.getInt("fillSweep64")),
            startCap = caps.startCap,
            endCap = caps.endCap,
            startCapFilled = case.optBoolean("startCapFilled", false),
            endCapFilled = case.optBoolean("endCapFilled", false),
            handle = if (case.has("handleAt64")) {
                arcHandleBand(size, thickness, inset, case.getInt("handleAt64"), trackSweep64)
            } else {
                null
            },
            pointer = if (case.has("pointerAt64")) {
                arcPointerBand(size, thickness, inset, case.getInt("pointerAt64"))
            } else {
                null
            },
            framed = case.optBoolean("framed", false),
        )
    }
}
