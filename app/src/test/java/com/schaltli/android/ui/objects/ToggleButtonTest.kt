package com.schaltli.android.ui.objects

import com.schaltli.android.data.ButtonFace
import com.schaltli.android.data.ScreenObject
import com.schaltli.android.data.ToggleDisc
import com.schaltli.android.data.ToggleDiscEdge
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
    fun theDiscFirstThenTheFaceItsOutlineTheRingWhileARequestWaits() {
        val face = ButtonFace(10, "#ffffff", "#e2e8f0", "#0f172a")
        val look = ToggleLook(normal = face, disc = ToggleDisc(25, 42, 17, "#eeeeee", "#dddddd"))
        assertEquals(
            listOf(
                PaintedPill(PillBand(8, 25, 34, 34, 17, 17), "#eeeeee"),
                PaintedPill(PillBand(1, 1, 214, 82, 9, 9), "#ffffff"),
                PaintedPill(PillBand(0, 0, 216, 84, 10, 10), "#e2e8f0"),
            ),
            togglePills(216, 84, look, face, false, null),
        )
        assertEquals(
            listOf(
                PaintedPill(PillBand(8, 25, 34, 34, 17, 17), "#eeeeee"),
                PaintedPill(PillBand(2, 2, 212, 80, 8, 8), "#ffffff"),
                PaintedPill(PillBand(0, 0, 216, 84, 10, 10), "#0284c7"),
            ),
            togglePills(216, 84, look, face, false, "#0284c7"),
        )
    }

    @Test
    fun aDiscWhoseTintIsLostGetsARingInTheInk() {
        val face = ButtonFace(10, "#000000", null, "#ffffff")
        val look = ToggleLook(normal = face, disc = ToggleDisc(25, 42, 17, "#000000", "#000000", ToggleDiscEdge("#ffffff", "#ffffff")))
        val runs = togglePills(216, 84, look, face, false, null)
        assertEquals(PaintedPill(PillBand(9, 26, 32, 32, 16, 16), "#000000"), runs[0])
        assertEquals(PaintedPill(PillBand(8, 25, 34, 34, 17, 17), "#ffffff"), runs[1])
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
