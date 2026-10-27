package com.schaltli.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The text field's shared rules ([TextField]) - the same cases as the
 * designer's e2e/text-field.spec.ts «the rules every device shares», which
 * tests lib/text-field.ts, and the boards' test/test_text_field.
 */
class TextFieldTest {
    @Test
    fun lengthIsCharacters() {
        assertEquals(6, TextField.textLength("Grüezi"))
        assertEquals(6, TextField.textLength("Straße"))
        assertEquals(1, TextField.textLength("😀"))
        assertEquals(0, TextField.textLength(""))
    }

    @Test
    fun clampNeverHalves() {
        assertEquals("Grü", TextField.clampText("Grüezi", 3))
        assertEquals("a😀", TextField.clampText("a😀b", 2))
        assertEquals("ab", TextField.clampText("ab", 5))
    }

    @Test
    fun boundsAndSending() {
        assertEquals(TextField.Bounds(0, 255), TextField.bounds(null, null))
        assertEquals(TextField.Bounds(3, 10), TextField.bounds(3.0, 10.0))
        assertEquals(TextField.Bounds(10, 10), TextField.bounds(20.0, 10.0))
        assertEquals(TextField.Bounds(0, 255), TextField.bounds(null, 999.0))
        assertEquals(TextField.Bounds(0, 1), TextField.bounds(null, 0.0))
        assertFalse(TextField.canSend("ab", TextField.Bounds(3, 10)))
        assertTrue(TextField.canSend("abc", TextField.Bounds(3, 10)))
        assertTrue(TextField.canSend("", TextField.Bounds(0, 10)))
    }

    @Test
    fun hiddenIsDots() {
        assertEquals("Grüezi", TextField.shownText("Grüezi", false))
        assertEquals("••••", TextField.shownText("Grü😀", true))
    }

    @Test
    fun geometry() {
        assertEquals(TextField.Geometry(radius = 9, pad = 12, lineY = 46, lineHeight = 2), TextField.geometry(240, 48))
        assertEquals(TextField.Geometry(radius = 2, pad = 2, lineY = 8, lineHeight = 2), TextField.geometry(20, 10))
        assertEquals((10 - 18) / 2 + 14, TextField.baseline(10, 14, 4))
    }
}
