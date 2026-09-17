package com.screensmith.android.ui.objects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic and the shapes a settable level needs, held to the same
 * answers the designer's own tests hold it to (its
 * e2e/settable-level.spec.ts, and docs/2026-09-17-settable-level.md for why
 * each rule is what it is).
 *
 * This is the one half of the Android renderer that can be checked without a
 * phone - the same reasoning as ArcRasterGoldenTest beside it. What it
 * protects: a finger's position has to become the same value here as in the
 * designer and in the firmware, or a tap on this app sets something else than
 * the same tap on a panel.
 */
class SettableLevelTest {
    private val linear = listOf(0.0 to 0.0, 100.0 to 100.0)

    // A real tank: the sensor's readings are not linear in its shape.
    private val tank = listOf(0.0 to 0.0, 30.0 to 50.0, 100.0 to 100.0)

    @Test
    fun `a position becomes the value the calibration says`() {
        assertEquals(0.0, valueForFillPercent(0.0, linear), 0.0001)
        assertEquals(42.0, valueForFillPercent(42.0, linear), 0.0001)
        assertEquals(100.0, valueForFillPercent(100.0, linear), 0.0001)
        // Half the bar is 30 on this tank, not 50.
        assertEquals(30.0, valueForFillPercent(50.0, tank), 0.0001)
        assertEquals(65.0, valueForFillPercent(75.0, tank), 0.0001)
    }

    @Test
    fun `outside the calibration it clamps to its outer points`() {
        assertEquals(0.0, valueForFillPercent(-10.0, linear), 0.0001)
        assertEquals(100.0, valueForFillPercent(150.0, linear), 0.0001)
        // The heater's 12 to 35: a finger cannot reach anything else.
        val heater = listOf(12.0 to 0.0, 35.0 to 100.0)
        assertEquals(12.0, valueForFillPercent(-1.0, heater), 0.0001)
        assertEquals(35.0, valueForFillPercent(101.0, heater), 0.0001)
    }

    @Test
    fun `a calibration that falls as the value rises inverts too`() {
        val falling = listOf(0.0 to 100.0, 100.0 to 0.0)
        assertEquals(100.0, valueForFillPercent(0.0, falling), 0.0001)
        assertEquals(75.0, valueForFillPercent(25.0, falling), 0.0001)
    }

    @Test
    fun `a step is the size of a step, and snapping is what a finger gets`() {
        assertEquals(35.0, snapToStep(37.0, 5.0), 0.0001)
        assertEquals(40.0, snapToStep(38.0, 5.0), 0.0001)
        assertEquals(37.4, snapToStep(37.4, 0.0), 0.0001)
        // A fan that only takes tens.
        assertEquals(40.0, snapToStep(42.0, 10.0), 0.0001)
    }

    @Test
    fun `a set value is written the way the other sides write it`() {
        assertEquals("80", formatSetValue(80.0))
        assertEquals("80", formatSetValue(79.9999))
        assertEquals("12.5", formatSetValue(12.5))
    }

    @Test
    fun `a line marker is the default and crosses the whole track`() {
        val line = MarkerShape.rects(20, 20, 200, 40, "left-to-right", 50.0, 4, "line")
        val dflt = MarkerShape.rects(20, 20, 200, 40, "left-to-right", 50.0, 4, "")
        assertEquals(line, dflt)
        assertEquals(1, line.size)
        assertEquals(40 - 8, line[0].h)
        assertEquals(4, line[0].w)
    }

    @Test
    fun `a knob is a disc around the same point`() {
        val line = MarkerShape.line(20, 20, 200, 40, "left-to-right", 50.0, 4)
        val round = MarkerShape.rects(20, 20, 200, 40, "left-to-right", 50.0, 4, "round")
        val x0 = round.minOf { it.x }
        val x1 = round.maxOf { it.x + it.w }
        val y0 = round.minOf { it.y }
        val y1 = round.maxOf { it.y + it.h }
        val centre = line.x + line.w / 2
        assertTrue(Math.abs((x0 + x1) / 2 - centre) <= 1)
        assertEquals(x1 - x0, y1 - y0)
        val area = round.sumOf { it.w * it.h }
        assertTrue("round, not square", area < (x1 - x0) * (y1 - y0))
        assertTrue("and not a sliver", area > (x1 - x0) * (y1 - y0) / 2)
    }

    @Test
    fun `a triangle narrows to one pixel from the track's edge`() {
        val rects = MarkerShape.rects(20, 20, 200, 40, "left-to-right", 50.0, 4, "triangle")
        assertEquals(20 + MarkerShape.PADDING, rects.first().y)
        assertEquals(1, rects.last().w)
        assertTrue(rects.first().w > rects.last().w)
        for (i in 1 until rects.size) {
            assertEquals(rects[i - 1].y + 1, rects[i].y)
            assertTrue(rects[i].w <= rects[i - 1].w)
        }
    }

    @Test
    fun `a vertical track turns every shape the other way`() {
        val rects = MarkerShape.rects(20, 20, 40, 200, "bottom-to-top", 50.0, 4, "triangle")
        assertTrue(rects.all { it.w == 1 })
        assertTrue(rects.first().h > rects.last().h)
    }
}
