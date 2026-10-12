package com.schaltli.android.data

import com.schaltli.android.data.EnergyDiagramLayout as E
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * The shared energy diagram vectors against this repo's [EnergyDiagramLayout]
 * (the designer's device contract §2.8).
 *
 * `energy-diagram-vectors.json` in the test resources is a byte-for-byte copy
 * of schaltli-designer's `lib/energy-diagram/vectors.json`; with the designer
 * checked out beside this repo, a copy that differs fails here.
 */
class EnergyDiagramLayoutTest {

    private fun copyBytes(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("energy-diagram-vectors.json")?.use { it.readBytes() }
            ?: error("energy-diagram-vectors.json is missing from the test resources")

    private val doc: JsonObject by lazy { Json.parseToJsonElement(String(copyBytes(), Charsets.UTF_8)).jsonObject }

    private fun designerVectors(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir.parentFile ?: return null, "schaltli-designer/lib/energy-diagram/vectors.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.content
    private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull

    /** The designer's toBeCloseTo(x, 1): within 0.05. */
    private fun near(a: Double, b: Double?, within: Double = 0.05) = b != null && abs(a - b) < within

    private fun nodesOf(lines: JsonObject? = null): List<E.Node> = doc["nodes"]!!.jsonArray.map { it.jsonObject }.map {
        val id = it.str("id")!!
        E.Node(id, E.roleFrom(it.str("role")), it.num("x")!!, it.num("y")!!, lines = lines?.get(id)?.jsonPrimitive?.int ?: 0)
    }

    private fun valuesOf(values: JsonObject?): Map<String, E.Value> =
        values?.mapValues { (_, v) -> E.Value(v.jsonObject.num("watts"), v.jsonObject.num("charge")) } ?: emptyMap()

    private fun sizeOf(o: JsonObject) = E.Size(o.num("width")!!, o.num("height")!!)

    @Test
    fun theCopyIsTheDesigners() {
        val designer = designerVectors()
        assumeTrue("schaltli-designer not checked out beside this repo: the copy is not compared", designer != null)
        assertArrayEquals("energy-diagram-vectors.json differs from the designer's - copy it again", designer!!.readBytes(), copyBytes())
    }

    @Test
    fun everyVector() {
        val failures = mutableListOf<String>()
        val nodes = nodesOf()

        val resolves = doc["resolve"]!!.jsonArray.map { it.jsonObject }
        assertTrue("fewer resolve vectors than the designer has", resolves.size >= 4)
        for (c in resolves) {
            for (r in E.resolve(nodes, valuesOf(c["values"]?.jsonObject))) {
                val e = c["expected"]!!.jsonObject[r.id]!!.jsonArray
                if (!near(r.watts, e[0].jsonPrimitive.double, 0.001) || r.gives != e[1].jsonPrimitive.boolean) failures += "resolve ${c.str("name")}: ${r.id}"
            }
        }

        for (c in doc["sankey"]!!.jsonArray.map { it.jsonObject }) {
            val l = E.sankeyLayout(E.resolve(nodes, valuesOf(c["values"]?.jsonObject)), sizeOf(c["size"]!!.jsonObject), c.num("fullScale")!!)
            val bars = c["expected"]!!.jsonObject["bars"]!!.jsonArray.map { it.jsonObject }
            var ok = l.bars.size == bars.size
            if (ok) {
                l.bars.zip(bars).forEach { (b, e) ->
                    ok = ok && b.id == e.str("id") && (b.side == E.Side.LEFT) == (e.str("side") == "left") &&
                        near(b.box.x, e.num("x")) && near(b.box.y, e.num("y")) && near(b.box.height, e.num("height"))
                }
            }
            val bus = c["expected"]!!.jsonObject["bus"]!!.jsonObject
            ok = ok && near(l.bus.x, bus.num("x")) && near(l.bus.y, bus.num("y")) && near(l.bus.width, bus.num("width")) && near(l.bus.height, bus.num("height"))
            if (!ok) failures += "sankey ${c.str("name")}: ${l.bars} ${l.bus}"
        }

        for (c in doc["flowLines"]!!.jsonArray.map { it.jsonObject }) {
            val line = E.flowLayout(nodes, E.resolve(nodes, valuesOf(c["values"]?.jsonObject))).lines.first { it.id == c.str("id") }
            val e = c["expected"]!!.jsonObject
            val ok = near(line.x0, e.num("x0")) && near(line.y0, e.num("y0")) && near(line.x1, e.num("x1")) && near(line.y1, e.num("y1")) &&
                near(line.width, e.num("width")) && line.live == e["live"]!!.jsonPrimitive.boolean
            if (!ok) failures += "flow ${c.str("name")}: $line"
        }
        for (c in doc["flowLabels"]!!.jsonArray.map { it.jsonObject }) {
            val n = E.flowLayout(nodes, E.resolve(nodes, emptyMap())).nodes.first { it.id == c.str("id") }
            val e = c["expected"]!!.jsonObject
            if (!near(n.labelX, e.num("x"), 0.001) || !near(n.labelY, e.num("y"), 0.001) || n.labelBeside != (e.str("align") == "start")) {
                failures += "flow label ${c.str("name")}: $n"
            }
        }

        val boxes = doc["boxes"]!!.jsonArray.map { it.jsonObject }
        assertTrue("fewer boxes vectors than the designer has", boxes.size >= 4)
        for (c in boxes) {
            val withLines = nodesOf(c["lines"]?.jsonObject)
            val r = E.resolve(withLines, valuesOf(c["values"]?.jsonObject))
            val metrics = c["metrics"]!!.jsonObject
            val m = E.LabelMetrics(metrics.num("fontSize")!!, metrics.num("valueFontSize")!!)
            val b = if (c.str("mode") == "flow") {
                E.flowBoxes(E.flowLayout(withLines, r), m)
            } else {
                val size = sizeOf(c["size"]!!.jsonObject)
                E.sankeyBoxes(E.sankeyLayout(r, size, c.num("fullScale")!!), withLines, size, m)
            }
            fun label(l: E.LabelBox?, e: JsonObject?) = if (e == null) {
                l == null
            } else {
                l != null && near(l.x, e.num("x"), 0.001) && near(l.y, e.num("y"), 0.001) && near(l.width, e.num("width"), 0.001) &&
                    l.align.name.lowercase() == e.str("align")
            }
            fun box(x: E.Box, e: JsonObject) =
                near(x.x, e.num("x"), 0.001) && near(x.y, e.num("y"), 0.001) && near(x.width, e.num("width"), 0.001) && near(x.height, e.num("height"), 0.001)
            val expected = c["expected"]!!.jsonObject
            var ok = label(b.bus, expected["bus"]?.jsonObject)
            for ((id, e) in expected["nodes"]!!.jsonObject) {
                val n = b.nodes.firstOrNull { it.id == id }
                ok = ok && n != null && box(n.icon, e.jsonObject["icon"]!!.jsonObject) && label(n.label, e.jsonObject["label"]!!.jsonObject)
            }
            if (!ok) failures += "boxes ${c.str("name")}: $b"
        }

        for (c in doc["dotGap"]!!.jsonArray.map { it.jsonObject }) {
            if (!near(E.dotGap(c.num("watts")!!), c.num("expected"))) failures += "dotGap ${c.num("watts")}"
        }
        for (c in doc["dots"]!!.jsonArray.map { it.jsonObject }) {
            val l = c["line"]!!.jsonObject
            val line = E.FlowLine("l", l.num("x0")!!, l.num("y0")!!, l.num("x1")!!, l.num("y1")!!, 3.0, true)
            val pts = E.dotsOnLine(line, c.num("phase")!!, c["gives"]!!.jsonPrimitive.boolean, c.num("watts")!!)
            val e = c["expected"]!!.jsonArray.map { it.jsonArray }
            val ok = pts.size == e.size && pts.zip(e).all { (p, q) -> near(p.x, q[0].jsonPrimitive.double, 0.006) && near(p.y, q[1].jsonPrimitive.double, 0.006) }
            if (!ok) failures += "dots phase ${c.num("phase")}: $pts"
        }
        for (c in doc["wave"]!!.jsonArray.map { it.jsonObject }) {
            fun rgb(key: String) = c[key]!!.jsonArray.map { it.jsonPrimitive.int }.toIntArray()
            val x = c.num("x")!!
            val phase = c.num("phase")!!
            val got = E.waveColour(rgb("band"), rgb("ground"), x, phase)
            if (!near(E.waveMix(x, phase), c.num("mix")) || !got.contentEquals(rgb("colour"))) failures += "wave $x $phase: ${got.toList()}"
        }

        for (c in doc["storageDots"]!!.jsonArray.map { it.jsonObject }) {
            val r = E.resolve(nodes, valuesOf(c["values"]?.jsonObject))
            val l = E.flowLayout(nodes, r)
            val rb = r.first { it.id == "battery" }
            val dots = E.storageDots(l.lines.first { it.id == "battery" }, l.nodes.first { it.id == "battery" }, c.num("phase")!!, rb.gives, rb.watts)
            val e = c["expected"]!!.jsonArray.map { it.jsonArray }
            val ok = dots.size == e.size && dots.zip(e).all { (d, q) ->
                near(d.x, q[0].jsonPrimitive.double) && near(d.y, q[1].jsonPrimitive.double) && near(d.alpha, q[2].jsonPrimitive.double) &&
                    (if (d.onBar) 1 else 0) == q[3].jsonPrimitive.int && (if (d.onLine) 1 else 0) == q[4].jsonPrimitive.int
            }
            if (!ok) failures += "storage dots ${c.str("name")}: ${dots.size}"
        }
        for (c in doc["trackColour"]!!.jsonArray.map { it.jsonObject }) {
            fun rgb(key: String) = c[key]!!.jsonArray.map { it.jsonPrimitive.int }.toIntArray()
            if (!E.trackColour(rgb("bar"), rgb("ground")).contentEquals(rgb("expected"))) failures += "track colour"
        }

        assertEquals("energy diagram vectors failed", emptyList<String>(), failures)
    }

    @Test
    fun `a diagram's topics are subscribed - watts, charge and what its lines read`() {
        val diagram = Json.parseToJsonElement(
            """{"nodes":[{"id":"bus","role":"bus"},{"id":"s","role":"source","topic":"van/solar#p","lines":[{"text":"{watts} W"}]},
               {"id":"b","role":"storage","topic":"van/shunt","chargeTopic":"van/soc","lines":[{"text":"{topic:van/volt:F1} V"},
               {"text":"x","liveText":"{live:t}","liveValues":[{"id":"t","source":{"namespace":"topic","path":"van/temp"},"rules":[]}]}]}]}""",
        ).jsonObject
        val p = Project(
            name = "E", screenWidth = 100, screenHeight = 100,
            screens = listOf(Screen(id = "s", name = "S", objects = listOf(ScreenObject("e", "energy-diagram", 0.0, 0.0, 100.0, 100.0, properties = diagram)))),
        )
        assertEquals(setOf("van/solar", "van/shunt", "van/soc", "van/volt", "van/temp"), p.collectTopicNames())
    }

    @Test
    fun wattsInALine() {
        assertEquals("92 W", E.withWatts("{watts} W", 92.4))
        assertEquals("93 W", E.withWatts("{watts} W", 92.5))
        assertEquals("-40 W", E.withWatts("{watts} W", -40.0))
        assertEquals("- W", E.withWatts("{watts} W", null))
        assertEquals("0 W · 0", E.withWatts("{watts} W · {watts}", 0.0))
        assertEquals("{live:soc} %", E.withWatts("{live:soc} %", 5.0))
    }

    @Test
    fun aTap() {
        val nodes = nodesOf()
        val r = E.resolve(nodes, mapOf("solar" to E.Value(watts = 120.0)))
        val size = E.Size(676.0, 396.0)
        assertEquals("solar", E.nodeAtTap(false, nodes, r, size, 1000.0, 114.0 + 31, 52.0))
        assertNull(E.nodeAtTap(false, nodes, r, size, 1000.0, 114.0 + 33, 52.0))
        assertNull(E.nodeAtTap(false, nodes, r, size, 1000.0, 338.0, 186.0))
        for (s in E.sankeyLayout(r, size, 1000.0).slots) {
            val x = if (s.side == E.Side.LEFT) 10.0 else size.width - 10
            assertEquals(s.id, E.nodeAtTap(true, nodes, r, size, 1000.0, x, s.y + s.height / 2))
            assertNull(E.nodeAtTap(true, nodes, r, size, 1000.0, size.width / 2, s.y + s.height / 2))
        }
    }
}
