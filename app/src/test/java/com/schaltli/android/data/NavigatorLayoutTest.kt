package com.schaltli.android.data

import com.schaltli.android.ui.effectiveNavigatorScroll
import com.schaltli.android.ui.navigatorOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The shared navigator vectors against this repo's [NavigatorLayout], and
 * what the app reads of a navigator (the designer's device contract §2.7).
 *
 * `navigator-vectors.json` in the test resources is a byte-for-byte copy of
 * schaltli-designer's `lib/navigator/vectors.json`; with the designer checked
 * out beside this repo, a copy that differs fails here.
 */
class NavigatorLayoutTest {

    private fun copyBytes(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("navigator-vectors.json")?.use { it.readBytes() }
            ?: error("navigator-vectors.json is missing from the test resources")

    private val doc: JsonObject by lazy { Json.parseToJsonElement(String(copyBytes(), Charsets.UTF_8)).jsonObject }

    private fun designerVectors(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir.parentFile ?: return null, "schaltli-designer/lib/navigator/vectors.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.content

    private fun layoutOf(l: JsonObject) = NavigatorLayout.layout(
        NavigatorLayout.edgeFrom(l.str("edge")),
        l.str("shows") == "iconsAndText",
        l["screenWidth"]!!.jsonPrimitive.int,
        l["screenHeight"]!!.jsonPrimitive.int,
        l["count"]!!.jsonPrimitive.int,
    )

    private fun rectOf(o: JsonObject) =
        NavigatorLayout.Rect(o["x"]!!.jsonPrimitive.int, o["y"]!!.jsonPrimitive.int, o["width"]!!.jsonPrimitive.int, o["height"]!!.jsonPrimitive.int)

    @Test
    fun everyVector() {
        val failures = mutableListOf<String>()
        for (c in doc["screens"]!!.jsonArray.map { it.jsonObject }) {
            val screens = c["screens"]!!.jsonArray.map { it.jsonObject }
            val listed = NavigatorLayout.listedScreens(
                screens.map {
                    NavigatorLayout.Listed(
                        isMaster = (it["isMaster"] as? JsonPrimitive)?.boolean ?: false,
                        popup = it.str("screenType") == "popup",
                        hidden = (it["hidden"] as? JsonPrimitive)?.boolean ?: false,
                    )
                },
            ).map { screens[it].str("id") }
            val expected = c["expected"]!!.jsonArray.map { it.jsonPrimitive.content }
            if (listed != expected) failures += "screens ${c.str("name")}: $listed"
        }
        val layouts = doc["layout"]!!.jsonArray.map { it.jsonObject }
        assertTrue("fewer layout vectors than the designer has", layouts.size >= 7)
        for (c in layouts) {
            val l = layoutOf(c["layout"]!!.jsonObject)
            val e = c["expected"]!!.jsonObject
            if (l.strip != rectOf(e["strip"]!!.jsonObject) || l.entryLength != e["entryLength"]!!.jsonPrimitive.int ||
                NavigatorLayout.maxScroll(l) != e["maxScroll"]!!.jsonPrimitive.int
            ) failures += "layout ${c.str("name")}: $l"
        }
        var count = 0
        for (c in doc["entryRect"]!!.jsonArray.map { it.jsonObject }) {
            count++
            val r = NavigatorLayout.entryRect(layoutOf(c["layout"]!!.jsonObject), c["index"]!!.jsonPrimitive.int, c["scroll"]!!.jsonPrimitive.int)
            if (r != rectOf(c["expected"]!!.jsonObject)) failures += "entryRect ${c.str("name")}: $r"
        }
        for (c in doc["scrollToShow"]!!.jsonArray.map { it.jsonObject }) {
            count++
            val v = NavigatorLayout.scrollToShow(layoutOf(c["layout"]!!.jsonObject), c["index"]!!.jsonPrimitive.int, c["scroll"]!!.jsonPrimitive.int)
            if (v != c["expected"]!!.jsonPrimitive.int) failures += "scrollToShow ${c.str("name")}: $v"
        }
        for (c in doc["entryAt"]!!.jsonArray.map { it.jsonObject }) {
            count++
            val v = NavigatorLayout.entryAt(layoutOf(c["layout"]!!.jsonObject), c["x"]!!.jsonPrimitive.int, c["y"]!!.jsonPrimitive.int, c["scroll"]!!.jsonPrimitive.int)
            if (v != c["expected"]!!.jsonPrimitive.int) failures += "entryAt ${c.str("name")}: $v"
        }
        for (c in doc["pageScroll"]!!.jsonArray.map { it.jsonObject }) {
            count++
            val v = NavigatorLayout.pageScroll(layoutOf(c["layout"]!!.jsonObject), c["scroll"]!!.jsonPrimitive.int, c["direction"]!!.jsonPrimitive.int)
            if (v != c["expected"]!!.jsonPrimitive.int) failures += "pageScroll ${c.str("name")}: $v"
        }
        assertTrue("fewer geometry vectors than the designer has", count >= 20)
        assertTrue("vectors failed:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `paging passes over hidden screens and wraps, the app opens on the first shown one`() {
        val hidden = listOf(false, true, false, true)
        assertEquals(2, NavigatorLayout.pagedIndex(hidden, 0, 1))
        assertEquals(0, NavigatorLayout.pagedIndex(hidden, 2, 1))
        assertEquals(2, NavigatorLayout.pagedIndex(hidden, 0, -1))
        assertEquals(2, NavigatorLayout.pagedIndex(hidden, 1, 1))
        assertEquals(1, NavigatorLayout.pagedIndex(listOf(true, true), 1, 1))
        assertEquals(2, NavigatorLayout.firstShown(listOf(true, true, false)))
        assertEquals(0, NavigatorLayout.firstShown(listOf(true)))
    }

    @Test
    fun `a project's navigator is read and laid out on its screen`() {
        val project: Project = Json { ignoreUnknownKeys = true }.decodeFromString(
            """{"name":"N","screenWidth":800,"screenHeight":480,
               "navigators":[{"id":"nav","edge":"left","thickness":80,"entryLength":88,"backgroundColor":"#202020",
                 "entries":[{"screenId":"a","normal":[{"id":"i","type":"icon","x":24,"y":20,"width":32,"height":32}],
                             "active":[{"id":"b","type":"box","x":4,"y":4,"width":72,"height":80}]},
                            {"screenId":"c"}]}],
               "screens":[{"id":"a","name":"A","navigatorId":"nav"},{"id":"h","name":"H","hidden":true},{"id":"c","name":"C","navigatorId":"nav"},{"id":"x","name":"X"}]}""",
        )
        val (nav, layout) = navigatorOf(project, project.screens[0])!!
        assertEquals(NavigatorLayout.Rect(0, 0, 80, 480), layout.strip)
        assertEquals(88, layout.entryLength)
        assertEquals(listOf("a", "c"), nav.entries.map { it.screenId })
        assertEquals("box", nav.entries[0].active[0].type)
        assertEquals(0, effectiveNavigatorScroll(nav, layout, project.screens[0], -1))
        assertTrue(project.screens[1].hidden)
        assertNull(navigatorOf(project, project.screens[3]))
    }

    @Test
    fun theCopyIsTheDesignersByteForByte() {
        val designer = designerVectors()
        assumeTrue("schaltli-designer is not checked out beside this repo - the copy is not compared", designer != null)
        assertTrue(
            "app/src/test/resources/navigator-vectors.json differs from the designer's lib/navigator/vectors.json - copy it again, never edit it",
            copyBytes().contentEquals(designer!!.readBytes()),
        )
    }
}
