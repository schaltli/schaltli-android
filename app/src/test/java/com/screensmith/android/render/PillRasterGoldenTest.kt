package com.screensmith.android.render

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds this repo's copy of the pill rasterizer to the numbers the designer's
 * copy produces.
 *
 * The same arrangement as [ArcRasterGoldenTest], for the same reason. The
 * rasterizer exists here as [PillRaster.kt], in the designer as
 * `lib/pill-raster.ts`, and will exist in each firmware once the devices catch
 * up; every line of it is integer arithmetic so that the copies cannot
 * disagree. This test is what makes that checkable rather than merely
 * intended.
 *
 * The golden file is recorded from the designer itself by
 * `hil/android/fixtures/build-pill-golden.js` in the designer repo, which
 * drives the real `pillPixelBands` through a browser. So this is not two
 * implementations of a spec agreeing with each other's reading of it; it is
 * this implementation agreeing with the one the reference image is actually
 * rendered by.
 *
 * Regenerate after any change to either side:
 *
 *   (designer) npm run dev
 *   (designer) node hil/android/fixtures/build-pill-golden.js
 *
 * A HIL run would find the same disagreements eventually, as a handful of
 * stray pixels along one rounded cap, once a phone is connected and a bundle
 * has been imported by hand. This finds them in milliseconds and names the
 * pixel.
 */
class PillRasterGoldenTest {

    private fun loadGolden(): JSONObject {
        val stream = javaClass.classLoader!!.getResourceAsStream("pill-raster-golden.json")
            ?: error("pill-raster-golden.json is missing - regenerate it with build-pill-golden.js")
        return JSONObject(stream.bufferedReader().use { it.readText() })
    }

    private fun cases(): List<JSONObject> {
        val cases = loadGolden().getJSONArray("cases")
        assertTrue("the golden file records no cases at all", cases.length() > 0)
        return (0 until cases.length()).map { cases.getJSONObject(it) }
    }

    private fun bandsOf(case: JSONObject): List<PillBand> {
        val bands = case.getJSONArray("bands")
        return (0 until bands.length()).map { i ->
            val band = bands.getJSONObject(i)
            PillBand(
                x = band.getInt("x"),
                y = band.getInt("y"),
                w = band.getInt("w"),
                h = band.getInt("h"),
                rLow = band.getInt("rLow"),
                rHigh = band.getInt("rHigh"),
                // Absent rather than false wherever the run is horizontal:
                // the recorder writes the case as it declares it.
                vertical = band.optBoolean("vertical", false),
            )
        }
    }

    /** counts[band][pixel], the shape the recorder flattens each case into. */
    private fun countsOf(case: JSONObject): List<JSONArray> {
        val counts = case.getJSONArray("counts")
        return (0 until counts.length()).map { counts.getJSONArray(it) }
    }

    @Test
    fun `every recorded pixel classifies into the same runs`() {
        var checked = 0
        for (case in cases()) {
            val name = case.getString("name")
            val bands = bandsOf(case)
            val counts = countsOf(case)
            val pixels = case.getJSONArray("pixels")
            assertEquals("$name records a count array per run", bands.size, counts.size)

            for (p in 0 until pixels.length()) {
                val pixel = pixels.getJSONArray(p)
                val px = pixel.getInt(0)
                val py = pixel.getInt(1)
                val got = pillPixelBands(bands, px, py)

                // Reported per run per pixel rather than as "the case differs":
                // a rasterizer that is wrong is usually wrong along one edge,
                // and the first differing coordinate says which edge - while
                // the run it differs in says whether the shape is wrong or only
                // the order the runs were resolved in.
                for (b in bands.indices) {
                    assertEquals("$name run $b at ($px, $py)", counts[b].getInt(p), got[b])
                }
                checked++
            }
        }
        println("PillRasterGoldenTest: ${cases().size} case(s), $checked pixel(s) classified")
    }

    /**
     * A sub-sample belongs to exactly one run. This is the rule the whole
     * rasterizer exists for - it is why the runs are handed over all at once
     * instead of being painted one after another - and it is the one a port is
     * most likely to lose, because losing it looks almost right.
     */
    @Test
    fun `no sub-sample is counted into two runs`() {
        for (case in cases()) {
            val name = case.getString("name")
            val bands = bandsOf(case)
            val pixels = case.getJSONArray("pixels")
            for (p in 0 until pixels.length()) {
                val pixel = pixels.getJSONArray(p)
                val got = pillPixelBands(bands, pixel.getInt(0), pixel.getInt(1))
                assertTrue(
                    "$name at (${pixel.getInt(0)}, ${pixel.getInt(1)}) counts ${got.sum()} of $PILL_COVERAGE_MAX",
                    got.sum() <= PILL_COVERAGE_MAX,
                )
            }
        }
    }

    /**
     * The golden has to contain anti-aliased pixels, or the test above would
     * pass against a rasterizer with no anti-aliasing at all - which is exactly
     * the shortcut a port is most tempted to take, and exactly the thing the
     * whole integer-arithmetic design exists to prevent. And it has to contain
     * pixels no run reaches, or it would pass against one that fills its whole
     * bounding box.
     *
     * The recorder refuses to write a file that fails this. It is asserted
     * again on this side because a golden file can also arrive stale, hand-
     * edited or half-written, and then it is this test that is lying rather
     * than the port.
     */
    @Test
    fun `the golden covers partial and untouched pixels alike`() {
        for (case in cases()) {
            val name = case.getString("name")
            val counts = countsOf(case)
            var partial = 0
            var untouched = 0
            for (p in 0 until counts[0].length()) {
                val total = counts.sumOf { it.getInt(p) }
                if (total in 1 until PILL_COVERAGE_MAX) partial++
                if (total == 0) untouched++
            }
            assertTrue("$name records no partially-covered pixel", partial > 0)
            assertTrue("$name records no pixel outside its runs", untouched > 0)
        }
    }

    /**
     * [pillBandsBounds] has no probe of its own in the designer, so it is
     * pinned against the recording instead: every pixel the designer's
     * rasterizer put a sub-sample in has to lie inside the box this function
     * answers, and the recorder deliberately samples a margin outside it so the
     * claim has something to be wrong about. A port whose bounds came out one
     * pixel short would clip a rounded cap - which is precisely the half of the
     * shape that the rest of this file is about.
     */
    @Test
    fun `the bounds hold every pixel a run touches`() {
        for (case in cases()) {
            val name = case.getString("name")
            val bounds = pillBandsBounds(bandsOf(case))
            val box = case.getJSONObject("box")
            assertTrue(
                "$name was recorded without a margin, so its bounds cannot be wrong here",
                box.getInt("x") < bounds.x && box.getInt("y") < bounds.y &&
                    box.getInt("x") + box.getInt("w") > bounds.x + bounds.w &&
                    box.getInt("y") + box.getInt("h") > bounds.y + bounds.h,
            )

            val counts = countsOf(case)
            val pixels = case.getJSONArray("pixels")
            for (p in 0 until pixels.length()) {
                if (counts.sumOf { it.getInt(p) } == 0) continue
                val pixel = pixels.getJSONArray(p)
                val px = pixel.getInt(0)
                val py = pixel.getInt(1)
                assertTrue(
                    "$name: ($px, $py) is covered but outside $bounds",
                    px >= bounds.x && px < bounds.x + bounds.w && py >= bounds.y && py < bounds.y + bounds.h,
                )
            }
        }
    }
}
