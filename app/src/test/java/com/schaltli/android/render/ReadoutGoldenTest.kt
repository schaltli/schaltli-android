package com.schaltli.android.render

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The designer's lib/instrument-readout/vectors.json against [Readout] - a
 * styled instrument's digits formatted and written here character for
 * character and pixel for pixel as the designer and the firmware do (the
 * designer's device contract §2.15, «The readout»).
 *
 * instrument-readout-vectors.json in the test resources is a copy of the
 * designer's; with the designer checked out beside this repo, a copy that
 * differs fails here - copy it again, never edit it.
 */
class ReadoutGoldenTest {

    private fun text(): String {
        val stream = javaClass.classLoader!!.getResourceAsStream("instrument-readout-vectors.json")
            ?: error("instrument-readout-vectors.json is missing - copy schaltli-designer/lib/instrument-readout/vectors.json")
        return stream.bufferedReader().use { it.readText() }
    }

    @Test
    fun `the copy is the designer's`() {
        // From app/ (gradle's working directory for unit tests) or the repo root.
        val designer = listOf("../../schaltli-designer", "../schaltli-designer")
            .map { File(it, "lib/instrument-readout/vectors.json") }
            .firstOrNull { it.exists() } ?: return
        assertEquals(
            "instrument-readout-vectors.json differs from the designer's - copy it again",
            designer.readText().replace("\r", ""),
            text().replace("\r", ""),
        )
    }

    @Test
    fun `every value formats as the designer's`() {
        val formats = JSONObject(text()).getJSONArray("formats")
        assertTrue("fewer formats than the designer has", formats.length() >= 16)
        val failures = mutableListOf<String>()
        for (i in 0 until formats.length()) {
            val f = formats.getJSONObject(i)
            val got = Readout.format(f.getString("raw"), f.getInt("decimals"))
            if (got != f.getString("expected")) failures += "\"${f.getString("raw")}\" (${f.getInt("decimals")}): \"$got\", not \"${f.getString("expected")}\""
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `no value writes nothing`() {
        assertEquals("", Readout.format(null, 1))
        assertEquals("", Readout.format("   ", 1))
        assertEquals("", Readout.format("Infinity", 1))
        assertEquals("", Readout.format("1f", 0))
        assertEquals("", Readout.format("0x10", 0))
    }

    private fun cells(a: JSONArray): List<List<Int>> =
        (0 until a.length()).map { i -> a.getJSONArray(i).let { c -> (0 until c.length()).map { c.getInt(it) } } }

    @Test
    fun `every draw writes the designer's picture`() {
        val root = JSONObject(text())
        val s = root.getJSONObject("strip")
        val alphaJson = s.getJSONArray("alpha")
        val strip = Readout.Strip(s.getInt("width"), s.getInt("height"), IntArray(alphaJson.length()) { alphaJson.getInt(it) })
        val draws = root.getJSONArray("draws")
        assertTrue("fewer draws than the designer has", draws.length() >= 4)
        val failures = mutableListOf<String>()
        for (i in 0 until draws.length()) {
            val d = draws.getJSONObject(i)
            val w = d.getInt("w")
            val h = d.getInt("h")
            val pixels = IntArray(w * h) { d.getInt("ground") }
            val target = object : NeedleRaster.Target {
                override val width = w
                override val height = h
                override fun get(x: Int, y: Int) = pixels[y * w + x]
                override fun set(x: Int, y: Int, c: Int) {
                    pixels[y * w + x] = c
                }
            }
            val desc = Readout.Description(
                chars = s.getString("chars"),
                cells = cells(s.getJSONArray("cells")),
                pad = s.getInt("pad"),
                height = s.getInt("height"),
                x = d.getInt("x"),
                y = d.getInt("y"),
                align = d.getString("align"),
                color = d.getInt("color"),
            )
            Readout.draw(target, desc, strip, d.getString("text"), d.getInt("opacity"))
            val got = pixels.joinToString("") { String.format("%04x", it) }
            val expected = d.getString("expected")
            if (got != expected) {
                val first = (0 until w * h).first { got.substring(it * 4, it * 4 + 4) != expected.substring(it * 4, it * 4 + 4) }
                failures += "${d.getString("name")}: first difference at (${first % w}, ${first / w})"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
