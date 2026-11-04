package com.schaltli.android.ui.objects

import com.schaltli.android.data.ButtonFace
import com.schaltli.android.render.PaintedPill
import com.schaltli.android.render.PillBand
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A button face as the rasteriser's runs - the same runs the designer's
 * buttonPills (lib/button-shape.ts, e2e/button-shape.spec.ts) and the
 * firmware's renderSoftwareButton build, relative to the button.
 */
class ButtonPillsTest {
    @Test
    fun filledIsOneRunOverTheButton() {
        assertEquals(
            listOf(PaintedPill(PillBand(0, 0, 100, 40, 20, 20), "#112233")),
            buttonPills(100, 40, ButtonFace(20, "#112233", null, "#ffffff")),
        )
    }

    @Test
    fun outlinedIsTheInsideTakenBackThenTheRing() {
        assertEquals(
            listOf(
                PaintedPill(PillBand(1, 1, 98, 38, 19, 19), null),
                PaintedPill(PillBand(0, 0, 100, 40, 20, 20), "#445566"),
            ),
            buttonPills(100, 40, ButtonFace(20, null, "#445566", "#445566")),
        )
    }

    @Test
    fun neitherIsNoRun() {
        assertEquals(emptyList<PaintedPill>(), buttonPills(100, 40, ButtonFace(0, null, null, "#000000")))
    }
}
