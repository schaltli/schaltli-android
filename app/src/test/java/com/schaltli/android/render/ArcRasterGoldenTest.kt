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
        val background = toRgb565(colours.getString("background"))

        val cases = golden.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            val pixels = case.getJSONArray("pixels")
            val fill = case.getJSONArray("fill")
            val track = case.getJSONArray("track")
            val handle = case.getJSONArray("handle")
            val rgb = case.getJSONArray("rgb")

            for (p in 0 until pixels.length()) {
                val f = fill.getInt(p)
                val t = track.getInt(p)
                val m = handle.getInt(p)
                val covered = f + t + m
                val mixed = blendBands(
                    fillColour, f,
                    trackColour, t,
                    handleColour, m,
                    background, ARC_COVERAGE_MAX - covered,
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
            var partial = 0
            for (p in 0 until fill.length()) {
                val covered = fill.getInt(p) + track.getInt(p) + handle.getInt(p)
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
            framed = case.optBoolean("framed", false),
        )
    }
}
