package com.schaltli.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/**
 * The intro's choreography, held to what it is supposed to be.
 *
 * Why this is worth a test at all: an animation is judged by eye, and an eye
 * that has seen it fifty times stops noticing that the pill no longer quite
 * reaches its place or that the letters are still fading when it stops. These
 * are the three moments that carry the meaning - it starts lying down, it ends
 * standing where the wordmark's pill belongs, and it ends STOPPED - and none
 * of them is visible in a screenshot taken at the wrong instant.
 */
class SchaltliIntroTest {

    private val reach = (BrandGlyphs.CAP_H - BrandGlyphs.CAP_W) / 2f
    private val restingCx = BrandGlyphs.CAP_X + BrandGlyphs.CAP_W / 2f

    @Test
    fun `it starts lying below the baseline with the ball at the near end`() {
        val p = poseAt(0f)
        assertEquals("angle", 0f, p.angle, 1e-4f)
        assertEquals("ball at the near end", -1f, p.ballT, 1e-4f)
        assertEquals("no letters yet", 0f, p.letters, 1e-4f)
        // Below the baseline, which is y = 0: the run-up happens off the word.
        assertTrue("lies below the baseline (cy=${p.cy})", p.cy > 0f)
    }

    @Test
    fun `it ends upright, in the wordmark's own place, with the letters fully in`() {
        val end = poseAt(IntroTiming.TOTAL)
        assertEquals("upright", (PI / 2).toFloat(), end.angle, 1e-4f)
        assertEquals("at the pill's x", restingCx, end.cx, 1e-3f)
        assertEquals(
            "at the pill's y",
            BrandGlyphs.CAP_Y + BrandGlyphs.CAP_H / 2f,
            end.cy,
            1e-3f,
        )
        assertEquals("letters fully in", 1f, end.letters, 1e-4f)
    }

    /**
     * The thing the user asked for in so many words: it runs, and then it
     * stands still. A loop, or a last frame that kept drifting, would pass
     * every other check here.
     */
    @Test
    fun `it has stopped by the end and stays where it landed`() {
        val end = poseAt(IntroTiming.TOTAL)
        for (extra in listOf(0.1f, 1f, 10f, 600f)) {
            val later = poseAt(IntroTiming.TOTAL + extra)
            assertEquals("angle at +${extra}s", end.angle, later.angle, 1e-5f)
            assertEquals("cx at +${extra}s", end.cx, later.cx, 1e-5f)
            assertEquals("cy at +${extra}s", end.cy, later.cy, 1e-5f)
            assertEquals("ball at +${extra}s", end.ballT, later.ballT, 1e-5f)
            assertEquals("letters at +${extra}s", end.letters, later.letters, 1e-5f)
        }
    }

    @Test
    fun `the ball rolls the whole way before the pill starts to tip`() {
        val justBeforeTipping = poseAt(IntroTiming.T2 - 0.001f)
        assertEquals("still lying", 0f, justBeforeTipping.angle, 1e-4f)
        assertTrue(
            "ball has reached the far end (${justBeforeTipping.ballT})",
            justBeforeTipping.ballT > 0.99f,
        )
    }

    /**
     * While the pill turns upright it pivots around the ball, which stays put.
     * Getting this wrong makes the pill skate sideways instead of standing up,
     * and it is the one part of the movement a still picture cannot show.
     */
    @Test
    fun `the pill turns around the resting ball`() {
        val lieCy = BrandGlyphs.CAP_Y + BrandGlyphs.CAP_H + 34f - BrandGlyphs.CAP_W / 2f
        for (t in listOf(IntroTiming.T2 + 0.05f, IntroTiming.T2 + 0.2f, IntroTiming.T2 + 0.4f)) {
            val p = poseAt(t)
            val ballX = p.cx + kotlin.math.cos(p.angle) * p.ballT * reach
            val ballY = p.cy + kotlin.math.sin(p.angle) * p.ballT * reach
            assertEquals("ball stays put in x at t=$t", restingCx, ballX, 1e-3f)
            assertEquals("ball stays put in y at t=$t", lieCy, ballY, 1e-3f)
        }
    }

    /**
     * Every letter fully in by the time the pill stops - the check that found
     * the defect intro.js had carried since it was written: the last of seven
     * letters ended at 98 % opacity and stayed there.
     */
    @Test
    fun `every letter is fully in by the time the pill has risen`() {
        val n = BrandGlyphs.LETTERS.size
        for (i in 0 until n) {
            val alpha = (1f - letterStart(i, n)) / LETTER_FADE
            assertTrue("letter $i is only ${alpha} in when the pill stops", alpha >= 1f)
        }
        // And they still arrive one after another, not all at once.
        assertEquals("the first starts at once", 0f, letterStart(0, n), 1e-6f)
        assertTrue("the last starts after the first", letterStart(n - 1, n) > letterStart(0, n))
    }
}
