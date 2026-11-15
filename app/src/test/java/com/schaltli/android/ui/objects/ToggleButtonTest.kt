package com.schaltli.android.ui.objects

import com.schaltli.android.data.ButtonFace
import com.schaltli.android.data.ScreenObject
import com.schaltli.android.data.ToggleLight
import com.schaltli.android.data.ToggleLook
import com.schaltli.android.data.darkVariantOf
import com.schaltli.android.render.PaintedPill
import com.schaltli.android.render.PillBand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Toggle Button (the designer's contract «toggle-button»): the tap the
 * designer's toggleNextIndex makes (e2e/toggle-button-shape.spec.ts «a
 * tap»), the runs its toggleRuns builds, and the looks read from an export,
 * dark in place of light.
 */
class ToggleButtonTest {
    private fun state(on: Boolean = false) = ToggleState("v", "w", on, null, null)

    @Test
    fun aTapSendsTheNextStateWrappingAfterTheLast() {
        val two = listOf(state(), state(true))
        assertEquals(1, toggleNextIndex(two, 0))
        assertEquals(0, toggleNextIndex(two, 1))
        val three = listOf(state(), state(true), state())
        assertEquals(2, toggleNextIndex(three, 1))
        assertEquals(0, toggleNextIndex(three, 2))
    }

    @Test
    fun withNothingReportedTheFirstThatShowsAsOnElseTheFirst() {
        assertEquals(1, toggleNextIndex(listOf(state(), state(true)), -1))
        assertEquals(0, toggleNextIndex(listOf(state(), state()), -1))
        assertEquals(-1, toggleNextIndex(emptyList(), -1))
    }

    @Test
    fun theLightFirstThenTheTileItsEdgeAndItsFill() {
        // docs/2026-11-11-led-look.md: the tile calm, the light says the state.
        val face = ButtonFace(12, "#181c24", "#272d3b", "#f1f5f9")
        val look = ToggleLook(normal = face, light = ToggleLight(10, 40, 196, 22, 0, "#0ea5e9", "#0d5875", "#0b2f40"))
        assertEquals(
            listOf(
                PaintedPill(PillBand(10, 40, 196, 22, 0, 0), "#0ea5e9"),
                PaintedPill(PillBand(1, 1, 214, 82, 11, 11), "#181c24"),
                PaintedPill(PillBand(0, 0, 216, 84, 12, 12), "#272d3b"),
            ),
            togglePills(216, 84, look, face, null),
        )
        // While asked, the light in the asked fill the caller picked.
        assertEquals(PaintedPill(PillBand(10, 40, 196, 22, 0, 0), "#0d5875"), togglePills(216, 84, look, face, "#0d5875")[0])
    }

    @Test
    fun aLightTheTileHidesGetsARingInTheInk() {
        val face = ButtonFace(12, "#ffffff", "#000000", "#000000")
        val look = ToggleLook(normal = face, light = ToggleLight(10, 40, 196, 22, 0, "#ffffff", "#ffffff", "#ffffff", "#000000"))
        val runs = togglePills(216, 84, look, face, null)
        assertEquals(PaintedPill(PillBand(11, 41, 194, 20, 0, 0), "#ffffff"), runs[0])
        assertEquals(PaintedPill(PillBand(10, 40, 196, 22, 0, 0), "#000000"), runs[1])
    }

    @Test
    fun theLooksAreReadAndDarkReplacesThemWhole() {
        val json = Json { ignoreUnknownKeys = true }
        val raw = json.parseToJsonElement(
            """
            {"id":"t","type":"toggle-button","x":0,"y":0,"width":216,"height":84,
             "looks":[{"normal":{"r":10,"fill":"#ffffff","outline":"#e2e8f0","ink":"#0f172a"},"label":"Aus","textX":54,
                       "second":{"textX":54,"baseline":58,"fontId":"f"}}],
             "looksDark":[{"normal":{"r":10,"fill":"#181c24","outline":"#272d3b","ink":"#f1f5f9"},"label":"Aus","textX":54}],
             "noneLook":{"label":"?"},"ring":"#0284c7","ringDark":"#0ea5e9",
             "properties":{"states":[{"readValue":"off","writeValue":"off","iconPath":"assets/a.png","iconPathDark":"assets/a-dark.png"}]}}
            """,
        )
        val light = json.decodeFromJsonElement(ScreenObject.serializer(), raw)
        assertEquals("#ffffff", light.looks!![0].normal.fill)
        assertEquals("Aus", light.looks!![0].label)
        assertEquals(58, light.looks!![0].second!!.baseline)
        assertEquals("?", light.noneLook!!.label)
        assertEquals("#0284c7", light.ring)
        assertEquals("assets/a.png", parseToggleStates(light.properties)[0].iconPath)

        val dark = json.decodeFromJsonElement(ScreenObject.serializer(), darkVariantOf(raw))
        assertEquals("#181c24", dark.looks!![0].normal.fill)
        assertNull("the dark look whole: no light second line left behind", dark.looks!![0].second)
        assertEquals("#0ea5e9", dark.ring)
        assertEquals("assets/a-dark.png", parseToggleStates(dark.properties)[0].iconPath)
    }
}
