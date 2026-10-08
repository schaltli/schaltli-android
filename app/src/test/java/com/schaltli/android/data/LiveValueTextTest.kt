package com.schaltli.android.data

import com.schaltli.android.SYSTEM_GENERATION
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What live values need in the app beyond the evaluator itself
 * ([LiveValueTest]): the project's combined topics read, every topic a live
 * value reads subscribed - a combined topic's down to the topics under it -
 * a text and a live icon read through them, and generation 1.4 announced
 * (the designer's docs/device-contract.md §2.6).
 */
class LiveValueTextTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val combined = """
        "combinedTopics": [
          {"id":"c1","name":"warm","mode":"any","conditions":[
            {"source":{"namespace":"topic","path":"van/heat"},"op":"yes"},
            {"source":{"namespace":"topic","path":"van/boiler#on"},"op":"yes"}]},
          {"id":"c2","name":"alarm","mode":"all","conditions":[
            {"source":{"namespace":"combined","path":"warm"},"op":"yes"},
            {"source":{"namespace":"topic","path":"van/night"},"op":"yes"}]}
        ]
    """

    private val textProperties = """
        {"text":"Heizung","liveText":"Heizung {live:heat} {live:timer} {live:alarm}","liveValues":[
          {"id":"heat","source":{"namespace":"topic","path":"van/heat"},"rules":[
            {"op":"yes","result":{"kind":"text","parts":["läuft"]}},
            {"op":"no","result":{"kind":"text","parts":["aus"]}}]},
          {"id":"timer","source":{"namespace":"topic","path":"van/timer"},"format":{"kind":"duration","pattern":"h:mm:ss"},
           "rules":[{"op":"<=","operand":"0","result":{"kind":"text","parts":[]}}]},
          {"id":"alarm","source":{"namespace":"combined","path":"alarm"},"rules":[
            {"op":"yes","result":{"kind":"text","parts":["!"]}}],"otherwise":{"kind":"text","parts":[]},
           "noValueYet":{"kind":"text","parts":["?"]}}
        ]}
    """

    private val iconProperties = """
        {"assetId":"a-thermo","liveIconId":"frost","liveValues":[
          {"id":"frost","source":{"namespace":"topic","path":"van/outside"},"rules":[
            {"op":"<","operand":"1","result":{"kind":"icon","icon":"a-flake","path":"assets/icons/flake.svg"}}],
           "otherwise":{"kind":"icon","icon":"a-thermo","path":"assets/icons/thermo.svg"}}
        ]}
    """

    private fun project(): Project = json.decodeFromString(
        """{"name":"Bus","screenWidth":100,"screenHeight":100,$combined,
           "screens":[{"id":"s","name":"S","objects":[
             {"id":"t","type":"text","x":0,"y":0,"width":100,"height":20,"properties":$textProperties},
             {"id":"i","type":"icon","x":0,"y":20,"width":24,"height":24,"properties":$iconProperties}]}]}""",
    )

    private fun props(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `combined topics are read in evaluation order`() {
        assertEquals(listOf("warm", "alarm"), project().combinedOrder.map { it.name })
    }

    @Test
    fun `every topic a live value reads is subscribed, through combined topics too`() {
        assertEquals(setOf("van/heat", "van/timer", "van/boiler", "van/night", "van/outside"), project().collectTopicNames())
    }

    @Test
    fun `a text reads through its live values and a combined topic`() {
        val p = project()
        val properties = p.screens[0].objects[0].properties
        val liveText = (properties["liveText"] as JsonPrimitive).content
        val read = { values: Map<String, String> -> resolveLiveText(liveText, properties, p, values, "phone", "id") }
        assertEquals("Heizung   ?", read(emptyMap()))
        assertEquals("Heizung läuft 3:23:18 !", read(mapOf("van/heat" to "true", "van/timer" to "12198", "van/night" to "on")))
        assertEquals("Heizung aus  ", read(mapOf("van/heat" to "false", "van/timer" to "0", "van/boiler" to """{"on":false}""", "van/night" to "on")))
        // Only the boiler, a JSON field two levels under `alarm`, turns it on.
        assertEquals("Heizung aus  !", read(mapOf("van/heat" to "false", "van/timer" to "0", "van/boiler" to """{"on":true}""", "van/night" to "on")))
    }

    @Test
    fun `a live icon shows its branch's picture, none before a value`() {
        val p = project()
        val icon = props(iconProperties)
        assertNull(liveIconPath(icon, p, emptyMap(), "phone", "id"))
        assertEquals("assets/icons/flake.svg", liveIconPath(icon, p, mapOf("van/outside" to "-2"), "phone", "id"))
        assertEquals("assets/icons/thermo.svg", liveIconPath(icon, p, mapOf("van/outside" to "8"), "phone", "id"))
        // A fixed icon is not live: it is baked into the background.
        assertNull(liveIconPath(props("""{"assetId":"a-thermo"}"""), p, mapOf("van/outside" to "8"), "phone", "id"))
    }

    @Test
    fun `a project without combined topics computes none`() {
        val p = Project(name = "Bus", screenWidth = 100, screenHeight = 100)
        assertEquals(emptyMap<String, String?>(), combinedValues(p, mapOf("a" to "1")))
    }

    @Test
    fun `generation 1_4 or later is announced`() {
        assertTrue(SYSTEM_GENERATION.split(".").let { (major, minor) -> major.toInt() > 1 || minor.toInt() >= 4 })
    }
}
