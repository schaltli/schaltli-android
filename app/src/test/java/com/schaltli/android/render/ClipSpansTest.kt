package com.schaltli.android.render

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shape a card clips to (device contract §2.1 `clip`): the runs
 * [roundRectSpans] lays down, row by row, against the designer's
 * lib/fixtures/clip-spans.json - the same file its e2e spec and the boards
 * (through Adafruit GFX itself, in the HIL conformance) are held to. Copied
 * here from the designer; re-copy it when the designer's changes.
 */
class ClipSpansTest {
    @Test
    fun `row spans match the designer's for every case`() {
        val stream = javaClass.classLoader!!.getResourceAsStream("clip-spans.json")
            ?: error("clip-spans.json missing - copy lib/fixtures/clip-spans.json from the designer")
        val cases = JSONObject(stream.bufferedReader().readText()).getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val w = c.getInt("width")
            val h = c.getInt("height")
            val r = c.getInt("radius")
            // Each row's covered columns, as the union of the runs.
            val lo = IntArray(h) { w }
            val hi = IntArray(h) { -1 }
            roundRectSpans(0, 0, w, h, r, r) { x, y, sw, sh ->
                for (row in maxOf(0, y) until minOf(h, y + sh)) {
                    if (x < lo[row]) lo[row] = x
                    if (x + sw - 1 > hi[row]) hi[row] = x + sw - 1
                }
            }
            val want = c.getJSONArray("spans")
            for (row in 0 until h) {
                val span = want.getJSONArray(row)
                val got = if (hi[row] < 0) listOf(0, 0) else listOf(lo[row], hi[row] + 1)
                assertEquals("${w}x$h r$r row $row", listOf(span.getInt(0), span.getInt(1)), got)
            }
        }
    }
}
