package com.schaltli.android.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dark variant of a bundle (the designer's docs/2026-09-26-device-switch
 * .md): the rule held to what the designer makes of the same input, and the
 * project the app draws while the theme is dark.
 */
class ThemeVariantTest {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Recorded by the designer (hil/android/fixtures/build-theme-variant-
     * golden.js) from its own darkVariantOf, the rule's first implementation.
     * Three readers of one rule - designer, firmware, this app - are three
     * chances to disagree; this is where they cannot.
     */
    @Test
    fun matchesTheDesignersRuleCaseForCase() {
        val resource = javaClass.getResource("/theme-variant-golden.json")
        assertNotNull("theme-variant-golden.json missing - run the designer's build-theme-variant-golden.js", resource)
        val cases = json.parseToJsonElement(resource!!.readText()) as JsonArray
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val c = case.jsonObject
            val name = c["name"]!!.jsonPrimitive.content
            assertEquals(name, c["output"], darkVariantOf(c["input"]!!))
        }
    }

    @Test
    fun theDarkProjectTakesTheDarkBackgroundPathsAndColours() {
        val bundle = """
            {"name":"p","screenWidth":360,"screenHeight":640,
             "screens":[{"id":"s","name":"S",
               "backgroundColor":"#ffffff","backgroundColorDark":"#101418",
               "backgroundImage":"assets/s.png","backgroundImageDark":"assets/s-dark.png",
               "objects":[
                 {"id":"b","type":"button","x":0,"y":0,"width":80,"height":40,
                  "look":{"normal":{"r":20,"fill":"#6750a4","ink":"#ffffff"},"pressed":{"r":8,"fill":"#7662ad","ink":"#ffffff"},
                          "textX":24,"baseline":26,"clipX":10,"clipW":60,"icon":{"x":10,"y":16,"size":10}},
                  "lookDark":{"normal":{"r":20,"fill":"#d0bcff","ink":"#381e72"},"pressed":{"r":8,"fill":"#c4b0f0","ink":"#381e72"},
                          "textX":24,"baseline":26,"clipX":10,"clipW":60,"icon":{"x":10,"y":16,"size":10}},
                  "iconPath":"assets/buttons/s_b-icon.png","iconPathDark":"assets/buttons/s_b-icon-dark.png",
                  "properties":{}},
                 {"id":"sw","type":"switch","x":0,"y":50,"width":120,"height":40,
                  "properties":{"switchColor":"#2f6f9f","switchColorDark":"#6aa8d8",
                    "states":[{"id":"on","path":"assets/icons/on.png","pathDark":"assets/icons/on-dark.png"}]}}
               ]}]}
        """.trimIndent()
        val element = json.parseToJsonElement(bundle)
        val light = json.decodeFromJsonElement(Project.serializer(), element)
        val dark = json.decodeFromJsonElement(Project.serializer(), darkVariantOf(element))

        val screen = dark.screens.single()
        assertEquals("#101418", screen.backgroundColor)
        assertEquals("assets/s-dark.png", screen.backgroundImage)
        // A button's look is swapped whole (since 2026-11-04): the dark
        // faces, and the layout with them.
        val button = screen.objects.first { it.id == "b" }
        assertEquals("#d0bcff", button.look!!.normal.fill)
        assertEquals("#381e72", button.look!!.pressed.ink)
        assertEquals(24, button.look!!.textX)
        assertEquals(10, button.look!!.icon!!.size)
        assertEquals("assets/buttons/s_b-icon-dark.png", button.iconPath)
        assertEquals("#6750a4", light.screens.single().objects.first { it.id == "b" }.look!!.normal.fill)
        val sw = screen.objects.first { it.id == "sw" }
        assertEquals("#6aa8d8", sw.properties["switchColor"]!!.jsonPrimitive.content)
        assertEquals(
            "assets/icons/on-dark.png",
            sw.properties["states"]!!.jsonArray.single().jsonObject["path"]!!.jsonPrimitive.content,
        )
        // Light is what it always was.
        assertEquals("#ffffff", light.screens.single().backgroundColor)
        assertEquals("assets/s.png", light.screens.single().backgroundImage)
    }

    @Test
    fun onlyDarkMeansDark() {
        assertTrue(isDarkTheme("dark"))
        assertTrue(isDarkTheme(" DARK\n"))
        assertFalse(isDarkTheme("light"))
        assertFalse(isDarkTheme(""))
        assertFalse(isDarkTheme(null))
        assertFalse(isDarkTheme("dim"))
    }
}
