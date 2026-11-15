package com.schaltli.android.render

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Guard (the designer's docs/2026-11-15-guard.md): its lock and double
 * arrow against the designer's lib/fixtures/guard-spans.json (copied here;
 * re-copy it when the designer's changes), and the gesture's rules as the
 * designer's e2e/guard.spec.ts holds them.
 */
class GuardSpansTest {
    private fun runsOf(json: JSONArray): List<List<List<Int>>> = List(json.length()) { y ->
        val row = json.getJSONArray(y)
        List(row.length()) { i -> row.getJSONArray(i).let { listOf(it.getInt(0), it.getInt(1)) } }
    }

    private fun plain(runs: List<List<IntArray>>) = runs.map { row -> row.map { listOf(it[0], it[1]) } }

    @Test
    fun `lock and arrows match the designer's for every size`() {
        val stream = javaClass.classLoader!!.getResourceAsStream("guard-spans.json")
            ?: error("guard-spans.json missing - copy lib/fixtures/guard-spans.json from the designer")
        val cases = JSONObject(stream.bufferedReader().readText()).getJSONArray("cases")
        assertTrue(cases.length() > 5)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val size = c.getInt("size")
            assertEquals("lock $size", runsOf(c.getJSONArray("lock")), plain(Guard.lockRuns(size)))
            assertEquals("arrows $size", runsOf(c.getJSONArray("arrows")), plain(Guard.arrowRuns(size)))
        }
    }

    @Test
    fun `a plain tap shows the hint and the cover goes`() {
        val c = Guard.down(1000, 100f, 130f).move(1050, 102f, 129f, 27)
        val (up, hint) = c.up(1080)
        assertTrue(hint)
        assertEquals(Guard.Phase.Dropping, up.phase)
        assertNull(up.tick(1080 + Guard.ANIM_MS))
    }

    @Test
    fun `past the travel it locks in and the lift does nothing`() {
        val c = Guard.down(0, 100f, 130f).move(20, 100f, 102f, 27)
        assertTrue(c.unlocked)
        val (up, hint) = c.up(30)
        assertFalse(hint)
        assertTrue(up.unlocked)
    }

    @Test
    fun `it waits five seconds and a late change tops up to two`() {
        var c = Guard.down(0, 100f, 130f).move(100, 100f, 50f, 27).tick(300)!!
        assertEquals(Guard.Phase.Open, c.phase)
        assertEquals(100 + Guard.RELOCK_MS, c.changed(1100).until)
        c = c.changed(4600)
        assertEquals(6600L, c.until)
        assertTrue(c.tick(6599)!!.unlocked)
        assertEquals(Guard.Phase.Dropping, c.tick(6600)!!.phase)
    }
}
