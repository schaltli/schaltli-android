package com.schaltli.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The close button on an open popup's edge (designer docs/device-contract.md
 * §2.5): the same numbers as the designer's popupCloseBadge and the boards'
 * popupCloseBadgeFor, so a tap the preview closes on is one the app closes on.
 */
class PopupCloseBadgeTest {

    @Test
    fun `on a rectangle it sits on the top right corner and takes taps round it`() {
        val badge = PopupFence(x = 42, y = 26, width = 716, height = 429).closeBadge(24)
        assertEquals(PopupCloseBadge(cx = 758, cy = 26, radius = 24, hitRadius = 38), badge)
        assertTrue(badge.contains(758.0 - 38, 26.0))
        assertFalse(badge.contains(758.0 - 39, 26.0))
        assertFalse(badge.contains(400.0, 240.0))
    }

    @Test
    fun `on a circle it sits on the rim at the top right`() {
        val badge = PopupFence(shape = "circle", x = 19, y = 19, width = 322, height = 322).closeBadge(20)
        assertEquals(294, badge.cx)
        assertEquals(66, badge.cy)
    }
}
