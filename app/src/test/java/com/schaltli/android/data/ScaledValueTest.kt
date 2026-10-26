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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The shared scaled value vectors against this repo's [ScaledValue] and the
 * scaled topics of [CombinedTopics] (the designer's device contract §2.11).
 *
 * `scaled-value-vectors.json` in the test resources is a byte-for-byte copy of
 * schaltli-designer's `lib/scaled-value/vectors.json`; with the designer
 * checked out beside this repo, a copy that differs fails here.
 */
class ScaledValueTest {

    private fun copyBytes(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("scaled-value-vectors.json")?.use { it.readBytes() }
            ?: error("scaled-value-vectors.json is missing from the test resources")

    private val doc: JsonObject by lazy { Json.parseToJsonElement(String(copyBytes(), Charsets.UTF_8)).jsonObject }

    // Gradle runs unit tests in the module directory (app/); look upward for
    // the designer checked out beside this repo.
    private fun designerVectors(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir.parentFile ?: return null, "schaltli-designer/lib/scaled-value/vectors.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }

    private fun cases(section: String, atLeast: Int): List<JsonObject> {
        val cases = doc[section]!!.jsonArray.map { it.jsonObject }
        // An empty or truncated copy must not pass by having nothing to check.
        assertTrue("fewer $section vectors than the designer has", cases.size > atLeast)
        return cases
    }

    private fun JsonObject.result(): String? = (this["result"] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun assertNoFailures(section: String, failures: List<String>) =
        assertTrue("$section vectors failed:\n" + failures.joinToString("\n"), failures.isEmpty())

    @Test
    fun scaleEveryVector() {
        val failures = mutableListOf<String>()
        for (v in cases("scale", 40)) {
            val scale = ScaledValue.Scale(
                v["numerator"]!!.jsonPrimitive.long,
                v["denominator"]!!.jsonPrimitive.long,
                v["offset"]!!.jsonPrimitive.content,
                v["decimals"]!!.jsonPrimitive.int,
            )
            val value = v["value"]!!.jsonPrimitive.content
            val got = ScaledValue.scale(value, scale)
            if (got != v.result()) failures += "\"$value\" $scale: expected ${v.result()}, actual $got"
        }
        assertNoFailures("scale", failures)
    }

    @Test
    fun writeEveryVector() {
        val failures = mutableListOf<String>()
        for (v in cases("write", 5)) {
            val format = ScaledValue.parseWriteFormat(v["format"])
            val value = v["value"]!!.jsonPrimitive.content
            val got = ScaledValue.formatWrite(value, format)
            if (got != v.result()) failures += "\"$value\" as $format: expected ${v.result()}, actual $got"
        }
        assertNoFailures("write", failures)
        assertEquals("21.5", ScaledValue.formatWrite("21.5", null))
    }

    @Test
    fun combinedEveryVector() {
        val failures = mutableListOf<String>()
        for (vector in cases("combined", 5)) {
            val name = (vector["name"] as JsonPrimitive).content
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
            val computed = CombinedTopics.compute(ordered) { path -> (values[path] as? JsonPrimitive)?.content }
            for ((key, want) in vector["expected"]!!.jsonObject) {
                val wanted = if (want is JsonNull) null else want.jsonPrimitive.content
                val got = computed[key]
                if (got != wanted) failures += "$name: $key expected $wanted, actual $got"
            }
        }
        assertNoFailures("combined", failures)
    }

    // A scaled topic's source is subscribed to, as a condition's is.
    @Test
    fun aScaledTopicReadsItsSource() {
        val topics = CombinedTopics.parse(
            Json.parseToJsonElement(
                """[
                  {"id":"c1","name":"celsius","mode":"scale","conditions":[],
                   "scale":{"source":{"namespace":"topic","path":"weather#kelvin"},"numerator":1,"denominator":1,"offset":"-273.15","decimals":2}},
                  {"id":"c2","name":"fahrenheit","mode":"scale","conditions":[],
                   "scale":{"source":{"namespace":"combined","path":"celsius"},"numerator":9,"denominator":5,"offset":"32","decimals":1}}
                ]""",
            ),
        )
        assertEquals(listOf("weather"), CombinedTopics.inputTopics(topics, "fahrenheit"))
    }

    // A factor that is not a whole number is not a scale the app guesses at.
    @Test
    fun aBrokenScaleGivesNoValue() {
        val format = ScaledValue.parseWriteFormat(Json.parseToJsonElement("""{"text":"{value}","numerator":2.5}"""))
        assertNull(ScaledValue.formatWrite("4", format))
    }

    @Test
    fun theCopyIsTheDesignersByteForByte() {
        val designer = designerVectors()
        assumeTrue("schaltli-designer is not checked out beside this repo - the copy is not compared", designer != null)
        assertTrue(
            "app/src/test/resources/scaled-value-vectors.json differs from the designer's lib/scaled-value/vectors.json - copy it again, never edit it",
            copyBytes().contentEquals(designer!!.readBytes()),
        )
    }
}
