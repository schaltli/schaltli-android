package com.schaltli.android.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The shared screen graph vectors against this repo's [ScreenGraph] (the
 * designer's device contract §2.10).
 *
 * `screen-graph-vectors.json` in the test resources is a byte-for-byte copy
 * of schaltli-designer's `lib/screen-graph/vectors.json`; with the designer
 * checked out beside this repo, a copy that differs fails here. The tab's
 * colour is the designer's to work out (the export carries it); its cases are
 * not run here.
 */
class ScreenGraphTest {

    private fun copyBytes(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("screen-graph-vectors.json")?.use { it.readBytes() }
            ?: error("screen-graph-vectors.json is missing from the test resources")

    private val doc: JsonObject by lazy { Json.parseToJsonElement(String(copyBytes(), Charsets.UTF_8)).jsonObject }

    private fun designerVectors(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir.parentFile ?: return null, "schaltli-designer/lib/screen-graph/vectors.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun rectOf(o: JsonObject) =
        ScreenGraph.Rect(o["x"]!!.jsonPrimitive.int, o["y"]!!.jsonPrimitive.int, o["width"]!!.jsonPrimitive.int, o["height"]!!.jsonPrimitive.int)

    private fun strings(a: JsonArray) = a.map { it.jsonPrimitive.content }

    @Test
    fun theNumbersAreTheDesigners() {
        val t = doc["pullTab"]!!.jsonObject
        assertEquals(ScreenGraph.TAB_LENGTH, t["length"]!!.jsonPrimitive.int)
        assertEquals(ScreenGraph.TAB_THICKNESS, t["thickness"]!!.jsonPrimitive.int)
        assertEquals(ScreenGraph.TAB_RADIUS, t["radius"]!!.jsonPrimitive.int)
        assertEquals(ScreenGraph.TAB_INSET, t["inset"]!!.jsonPrimitive.int)
        assertEquals(ScreenGraph.TAB_NUDGE, t["nudge"]!!.jsonPrimitive.int)
        assertEquals(ScreenGraph.TAB_PERIOD_MS, t["period"]!!.jsonPrimitive.long)
        assertEquals(ScreenGraph.TAB_NUDGE_MS, t["nudgeMs"]!!.jsonPrimitive.long)
    }

    @Test
    fun everyVector() {
        val failures = mutableListOf<String>()
        for (c in doc["rowStep"]!!.jsonArray.map { it.jsonObject }) {
            val expected = c["expect"].let { if (it == null || it is JsonNull) null else it.jsonPrimitive.content }
            val actual = ScreenGraph.rowStep(strings(c["mainRow"]!!.jsonArray), c.str("current")!!, c.str("page"), c["delta"]!!.jsonPrimitive.int)
            if (actual != expected) failures += "rowStep $c: $actual"
        }
        for (c in doc["tabSides"]!!.jsonArray.map { it.jsonObject }) {
            val s = c["screen"]!!.jsonObject
            val screen = ScreenGraph.GraphScreen(
                id = s.str("id")!!,
                neighbours = (s["neighbours"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> ScreenGraph.edgeFrom(k)?.let { it to v.jsonPrimitive.content } }.toMap(),
                back = ScreenGraph.edgeFrom(s.str("back")),
                swipes = (s["buttonActions"] as? JsonObject).orEmpty().mapValues { (_, v) ->
                    val a = v.jsonObject
                    ScreenGraph.Action(a.str("type")!!, a.str("side"), a.str("targetScreenId"))
                },
            )
            val actual = ScreenGraph.tabSides(screen, strings(c["mainRow"]!!.jsonArray)).map { it.key }
            if (actual != strings(c["expect"]!!.jsonArray)) failures += "tabSides ${c.str("name")}: $actual"
        }
        for (c in doc["contentArea"]!!.jsonArray.map { it.jsonObject }) {
            val actual = ScreenGraph.contentArea(c["width"]!!.jsonPrimitive.int, c["height"]!!.jsonPrimitive.int, c.str("edge"), (c["thickness"] as? JsonPrimitive)?.int ?: 0)
            if (actual != rectOf(c["expect"]!!.jsonObject)) failures += "contentArea $c: $actual"
        }
        for (c in doc["tabRect"]!!.jsonArray.map { it.jsonObject }) {
            val actual = ScreenGraph.tabRect(ScreenGraph.edgeFrom(c.str("edge"))!!, rectOf(c["area"]!!.jsonObject), c["nudge"]!!.jsonPrimitive.int)
            if (actual != rectOf(c["expect"]!!.jsonObject)) failures += "tabRect $c: $actual"
        }
        for (c in doc["nudge"]!!.jsonArray.map { it.jsonObject }) {
            val ms = c["ms"]!!.jsonPrimitive.long
            if (ScreenGraph.nudgeAt(ms) != c["expect"]!!.jsonPrimitive.int) failures += "nudge $ms: ${ScreenGraph.nudgeAt(ms)}"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun aSwipeIdAndItsSideGoBothWays() {
        for (e in ScreenGraph.Edge.entries) assertEquals(e, ScreenGraph.sideOfSwipe(ScreenGraph.swipeFor(e)))
        assertEquals(null, ScreenGraph.sideOfSwipe("button-0"))
    }

    @Test
    fun theCopyIsTheDesignersByteForByte() {
        val designer = designerVectors()
        assumeTrue("schaltli-designer not checked out beside this repo - the copy is not compared", designer != null)
        assertTrue(
            "app/src/test/resources/screen-graph-vectors.json differs from the designer's lib/screen-graph/vectors.json - copy it again, never edit it",
            designer!!.readBytes().contentEquals(copyBytes()),
        )
    }
}
