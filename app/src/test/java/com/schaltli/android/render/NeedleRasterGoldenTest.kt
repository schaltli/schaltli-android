package com.schaltli.android.render

import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The designer's lib/needle-raster/vectors.json against [NeedleRaster] - a
 * styled gauge's or bar's needle drawn here pixel for pixel as the designer
 * and the firmware draw it (the designer's device contract §2.15).
 *
 * needle-raster-vectors.json in the test resources is a copy of the
 * designer's; with the designer checked out beside this repo, a copy that
 * differs fails here - copy it again, never edit it.
 */
class NeedleRasterGoldenTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun text(): String {
        val stream = javaClass.classLoader!!.getResourceAsStream("needle-raster-vectors.json")
            ?: error("needle-raster-vectors.json is missing - copy schaltli-designer/lib/needle-raster/vectors.json")
        return stream.bufferedReader().use { it.readText() }
    }

    @Test
    fun `the copy is the designer's`() {
        // From app/ (gradle's working directory for unit tests) or the repo root.
        val designer = listOf("../../schaltli-designer", "../schaltli-designer")
            .map { File(it, "lib/needle-raster/vectors.json") }
            .firstOrNull { it.exists() } ?: return
        assertEquals(
            "needle-raster-vectors.json differs from the designer's - copy it again",
            designer.readText().replace("\r", ""),
            text().replace("\r", ""),
        )
    }

    @Test
    fun `every vector draws the designer's picture`() {
        val cases = JSONObject(text()).getJSONArray("cases")
        assertTrue("fewer vectors than the designer has", cases.length() >= 9)
        val failures = mutableListOf<String>()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val w = c.getInt("width")
            val h = c.getInt("height")
            val pixels = IntArray(w * h) { c.getInt("ground") }
            val desc = json.decodeFromString(NeedleRaster.Description.serializer(), c.getJSONObject("desc").toString())
            val p = c.getJSONObject("pose")
            val target = object : NeedleRaster.Target {
                override val width = w
                override val height = h
                override fun get(x: Int, y: Int) = pixels[y * w + x]
                override fun set(x: Int, y: Int, c: Int) {
                    pixels[y * w + x] = c
                }
            }
            NeedleRaster.draw(target, desc, NeedleRaster.Pose(p.getInt("angle64"), p.getInt("shiftX"), p.getInt("shiftY")))
            val got = pixels.joinToString("") { String.format("%04x", it) }
            if (got != c.getString("expected")) {
                val first = (0 until w * h).first { got.substring(it * 4, it * 4 + 4) != c.getString("expected").substring(it * 4, it * 4 + 4) }
                failures += "${c.getString("name")}: first difference at (${first % w}, ${first / w})"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `angles round as the designer's do`() {
        assertEquals(2880, NeedleRaster.angle64Of(45.0))
        assertEquals(-8147, NeedleRaster.angle64Of(-127.3))
    }
}
