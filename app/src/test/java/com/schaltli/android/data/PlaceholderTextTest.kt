package com.schaltli.android.data

import com.schaltli.android.SYSTEM_GENERATION
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a text's placeholders need beyond the evaluator itself ([PlaceholdersTest]):
 * its topics subscribed, the project's separators read, the values looked up
 * the way the designer's preview looks them up, and generation 1.2 announced
 * (the designer's docs/2026-10-05-placeholder-devices.md).
 */
class PlaceholderTextTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun text(id: String, value: String, children: List<ScreenObject> = emptyList()) =
        ScreenObject(id, "text", 0.0, 0.0, 100.0, 20.0, properties = JsonObject(mapOf("text" to JsonPrimitive(value))), children = children)

    private fun project(vararg objects: ScreenObject, decimal: String = ".", thousands: String = "'") =
        Project(
            name = "Bus", screenWidth = 100, screenHeight = 100,
            decimalSeparator = decimal, thousandsSeparator = thousands,
            screens = listOf(Screen(id = "s", name = "S", objects = objects.toList())),
        )

    @Test
    fun `the topics a text names are subscribed, without their JSON path`() {
        val p = project(
            text("a", "{topic:van/level:F0} {device:id} {{topic:not/one}}"),
            text("b", "", children = listOf(text("c", "{topic:van/data#temp ?? 0:F1}"))),
        )
        assertEquals(setOf("van/level", "van/data"), p.collectTopicNames())
    }

    @Test
    fun `the separators are read, and Switzerland's when an export has none`() {
        val with = json.decodeFromString<Project>(
            """{"name":"x","screenWidth":1,"screenHeight":1,"decimalSeparator":",","thousandsSeparator":" "}""",
        )
        assertEquals("," to " ", with.decimalSeparator to with.thousandsSeparator)
        val without = json.decodeFromString<Project>("""{"name":"x","screenWidth":1,"screenHeight":1}""")
        assertEquals("." to "'", without.decimalSeparator to without.thousandsSeparator)
    }

    @Test
    fun `a text resolves its topics, the device and the project's separators`() {
        val p = project(decimal = ",", thousands = "'")
        val values = mapOf("van/level" to "1234.567", "van/data" to """{"temp":21.25}""", "van/empty" to "")
        fun resolved(text: String) = resolvePlaceholders(text, p, values, "HUAWEI P20 Pro", "android-1234abcd")

        assertEquals("Tank 1'234,57 %", resolved("Tank {topic:van/level:N2} %"))
        assertEquals("21,3", resolved("{topic:van/data#temp:F1}"))
        assertEquals("HUAWEI P20 Pro android-1234abcd", resolved("{device:model} {device:id}"))
        // Never arrived, and a field the payload lacks: the fallback.
        assertEquals("leer", resolved("""{topic:van/none ?? "leer"}"""))
        assertEquals("0,0", resolved("{topic:van/data#humidity ?? 0:F1}"))
        // An empty message that did arrive shows empty; `??` does not apply.
        assertEquals("[]", resolved("""[{topic:van/empty ?? "leer"}]"""))
        // A text without a placeholder is the text.
        assertEquals("Grüße", resolved("Grüße"))
    }

    @Test
    fun `the app announces generation 1_2`() {
        assertEquals("1.2", SYSTEM_GENERATION)
    }
}
