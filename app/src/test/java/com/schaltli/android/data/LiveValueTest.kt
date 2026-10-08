package com.schaltli.android.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The shared live value vectors against this repo's [LiveValues] and
 * [CombinedTopics].
 *
 * `live-value-vectors.json` in the test resources is a byte-for-byte copy of
 * schaltli-designer's `lib/live-value/vectors.json`; the designer, the
 * firmware and this app must agree on every case. With the designer checked
 * out beside this repo, a copy that differs fails here - copy it again, never
 * edit it. Without the designer that check is skipped and says so.
 */
class LiveValueTest {

    private fun copyBytes(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("live-value-vectors.json")?.use { it.readBytes() }
            ?: error("live-value-vectors.json is missing from the test resources")

    private val doc: JsonObject by lazy { Json.parseToJsonElement(String(copyBytes(), Charsets.UTF_8)).jsonObject }

    // Gradle runs unit tests in the module directory (app/); look upward for
    // the designer checked out beside this repo.
    private fun designerVectors(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir.parentFile ?: return null, "schaltli-designer/lib/live-value/vectors.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun separatorsOf(vector: JsonObject) = Placeholders.Separators(
        decimal = vector.string("decimal") ?: ".",
        thousands = vector.string("thousands") ?: "'",
    )

    private fun cases(section: String, atLeast: Int): List<JsonObject> {
        val cases = doc[section]!!.jsonArray.map { it.jsonObject }
        // An empty or truncated copy must not pass by having nothing to check.
        assertTrue("fewer $section vectors than the designer has", cases.size > atLeast)
        return cases
    }

    private fun assertNoFailures(section: String, failures: List<String>) =
        assertTrue("$section vectors failed:\n" + failures.joinToString("\n"), failures.isEmpty())

    @Test
    fun everyEvaluateVector() {
        val failures = mutableListOf<String>()
        for (vector in cases("evaluate", 30)) {
            val rules = vector["rules"]!!.jsonArray.map { it.jsonObject }.map { rule ->
                LiveValues.Rule(rule.string("op") ?: "==", rule.string("operand") ?: "", LiveValues.Result.Text(emptyList()))
            }
            val lv = LiveValues.LiveValue("lv1", LiveValues.Source("topic", "t"), rules = rules)
            val actual = when (val applies = LiveValues.evaluate(lv, vector.string("value"))) {
                is LiveValues.Applies.NoValueYet -> "noValueYet"
                is LiveValues.Applies.Otherwise -> "otherwise"
                is LiveValues.Applies.RuleBranch -> applies.rule.toString()
            }
            val expected = vector["applies"]!!.jsonPrimitive.let { it.intOrNull?.toString() ?: it.content }
            if (actual != expected) failures += "${vector.string("name")}: expected $expected, actual $actual"
        }
        assertNoFailures("evaluate", failures)
    }

    @Test
    fun everyTextVector() {
        val failures = mutableListOf<String>()
        for (vector in cases("text", 20)) {
            val lv = LiveValues.parseLiveValue(vector["liveValue"])
            val actual = LiveValues.textOf(lv, vector.string("value"), separatorsOf(vector))
            val expected = vector.string("expected")
            if (actual != expected) failures += "${vector.string("name")}: expected [$expected], actual [$actual]"
        }
        assertNoFailures("text", failures)
    }

    @Test
    fun everyResolveVector() {
        val failures = mutableListOf<String>()
        for (vector in cases("resolve", 5)) {
            val liveValues = LiveValues.parseLiveValues(vector["liveValues"])
            val values = vector["values"] as? JsonObject ?: JsonObject(emptyMap())
            val actual = LiveValues.resolveLiveText(
                vector.string("text")!!,
                liveValues,
                { source -> values.string("${source.namespace}:${source.path}") },
            )
            val expected = vector.string("expected")
            if (actual != expected) failures += "${vector.string("name")}: expected [$expected], actual [$actual]"
        }
        assertNoFailures("resolve", failures)
    }

    @Test
    fun everyCombinedVector() {
        val failures = mutableListOf<String>()
        for (vector in cases("combined", 10)) {
            val name = vector.string("name")
            val topics = CombinedTopics.parse(vector["combinedTopics"])
            val circular = mutableListOf<String>()
            val ordered = CombinedTopics.evaluationOrder(topics, circular)
            val expectedCircular = vector["circular"] as? JsonArray
            if (expectedCircular != null) {
                val expected = expectedCircular.map { it.jsonPrimitive.content }
                if (circular != expected) failures += "$name: circular expected $expected, actual $circular"
                continue
            }
            val values = vector["values"] as? JsonObject ?: JsonObject(emptyMap())
            val computed = CombinedTopics.compute(ordered) { path -> values.string(path) }
            for ((key, want) in vector["expected"]!!.jsonObject) {
                val wanted = if (want is JsonNull) null else want.jsonPrimitive.content
                val got = computed[key]
                if (got != wanted) failures += "$name: $key expected $wanted, actual $got"
            }
        }
        assertNoFailures("combined", failures)
    }

    // What the app subscribes to: a combined topic's own topics and those of
    // the combined topics it reads, bare, each once.
    @Test
    fun inputTopicsFollowCombinedTopicsDown() {
        val topics = CombinedTopics.parse(
            Json.parseToJsonElement(
                """[
                  {"id":"c1","name":"anyLight","mode":"any","conditions":[
                    {"source":{"namespace":"topic","path":"light/a"},"op":"yes"},
                    {"source":{"namespace":"topic","path":"light/b#state"},"op":"yes"}]},
                  {"id":"c2","name":"alarm","mode":"all","conditions":[
                    {"source":{"namespace":"combined","path":"anyLight"},"op":"yes"},
                    {"source":{"namespace":"topic","path":"light/a"},"op":"yes"},
                    {"source":{"namespace":"topic","path":"night"},"op":"yes"}]}
                ]""",
            ),
        )
        assertEquals(listOf("light/a", "light/b", "night"), CombinedTopics.inputTopics(topics, "alarm"))
        assertEquals(emptyList<String>(), CombinedTopics.inputTopics(topics, "missing"))
    }

    @Test
    fun theCopyIsTheDesignersByteForByte() {
        val designer = designerVectors()
        if (designer == null) println("schaltli-designer is not checked out beside this repo - the vectors' copy is not compared")
        assumeTrue("schaltli-designer is not checked out beside this repo - the copy is not compared", designer != null)
        assertTrue(
            "app/src/test/resources/live-value-vectors.json differs from the designer's lib/live-value/vectors.json - copy it again, never edit it",
            copyBytes().contentEquals(designer!!.readBytes()),
        )
    }
}
