package com.schaltli.android.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The shared placeholder vectors against this repo's [Placeholders].
 *
 * `placeholder-vectors.json` in the test resources is a byte-for-byte copy of
 * schaltli-designer's `lib/placeholders/vectors.json`; the designer, the
 * firmware and this app must all turn every case's text into the same
 * result. With the designer checked out beside this repo, a copy that differs
 * fails here - copy it again, never edit it. Without the designer that check
 * is skipped and says so.
 */
class PlaceholdersTest {

    private fun copyBytes(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("placeholder-vectors.json")?.use { it.readBytes() }
            ?: error("placeholder-vectors.json is missing from the test resources")

    // Gradle runs unit tests in the module directory (app/); look upward for
    // the designer checked out beside this repo.
    private fun designerVectors(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir.parentFile ?: return null, "schaltli-designer/lib/placeholders/vectors.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun everyVectorResolvesAsInTheDesigner() {
        val cases = JSONObject(String(copyBytes(), Charsets.UTF_8)).getJSONArray("cases")
        // An empty or truncated copy must not pass by having nothing to check.
        assertTrue("fewer vectors than the designer has", cases.length() > 30)

        val failures = mutableListOf<String>()
        for (n in 0 until cases.length()) {
            val vector = cases.getJSONObject(n)
            val values = mutableMapOf<String, String>()
            vector.optJSONObject("values")?.let { v -> v.keys().forEach { key -> values[key] = v.getString(key) } }
            val separators = Placeholders.Separators(
                decimal = vector.optString("decimal", "."),
                thousands = if (vector.has("thousands")) vector.getString("thousands") else "'",
            )
            val actual = Placeholders.resolve(
                vector.getString("text"),
                { reference -> values["${reference.namespace}:${reference.path}"] },
                separators,
            )
            val expected = vector.getString("expected")
            if (actual != expected) {
                failures += "${vector.getString("name")}\n  text     ${vector.getString("text")}\n  expected $expected\n  actual   $actual"
            }
        }
        assertTrue("vectors failed:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun theCopyIsTheDesignersByteForByte() {
        val designer = designerVectors()
        if (designer == null) println("schaltli-designer is not checked out beside this repo - the vectors' copy is not compared")
        assumeTrue("schaltli-designer is not checked out beside this repo - the copy is not compared", designer != null)
        assertTrue(
            "app/src/test/resources/placeholder-vectors.json differs from the designer's lib/placeholders/vectors.json - copy it again, never edit it",
            copyBytes().contentEquals(designer!!.readBytes()),
        )
    }

    @Test
    fun theTopicsATextRefersToEachOnce() {
        assertEquals(
            listOf("a/b", "c", "van/data#temp"),
            Placeholders.referencedTopics("{topic:a/b:F1} {topic:c ?? 0} {topic:a/b} {device:id} {screen} {topic:van/data#temp}"),
        )
    }
}
