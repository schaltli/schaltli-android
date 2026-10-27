package com.schaltli.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The time field's shared rules ([TimeField]) - the same cases as the
 * designer's e2e/time-field.spec.ts «the rules every device shares» and the
 * boards' test/test_time_field.
 */
class TimeFieldTest {
    private fun t(h: Int, m: Int) = TimeField.TimeOfDay(h, m)

    @Test
    fun readsWhatHomeAssistantWrites() {
        assertEquals(t(6, 45), TimeField.parse("06:45"))
        assertEquals(t(6, 45), TimeField.parse("6:45"))
        assertEquals(t(6, 45), TimeField.parse("06:45:00"))
        assertEquals(t(23, 59), TimeField.parse(" 23:59:59.123456 "))
        assertEquals(t(6, 45), TimeField.parse("2026-10-27T06:45:00+01:00"))
        assertEquals(t(18, 5), TimeField.parse("2026-10-27 18:05:10Z"))
        assertEquals(t(7, 30), TimeField.parse("2026-10-27T07:30-05:00"))
    }

    @Test
    fun anythingElseIsNoTime() {
        for (value in listOf(null, "", "unknown", "24:00", "12:60", "12:5", "123:00", "12:00:60", "12:00:", "12:00:00.", "7pm", "06h45", "2026-10-27")) {
            assertNull(value.toString(), TimeField.parse(value))
        }
    }

    @Test
    fun shownIn24Hours() {
        assertEquals("06:05", TimeField.format(t(6, 5)))
        assertEquals("--:--", TimeField.format(null))
    }

    @Test
    fun onTheStepToTheNearest() {
        assertEquals(t(6, 45), TimeField.onStep(t(6, 43), 5))
        assertEquals(t(6, 40), TimeField.onStep(t(6, 42), 5))
        assertEquals(t(7, 0), TimeField.onStep(t(6, 58), 5))
        assertEquals(t(0, 0), TimeField.onStep(t(23, 58), 5))
        assertEquals(t(6, 0), TimeField.onStep(t(6, 7), 15))
        assertEquals(t(6, 15), TimeField.onStep(t(6, 8), 15))
        assertEquals(t(6, 30), TimeField.onStep(t(6, 15), 30))
        assertEquals(t(6, 43), TimeField.onStep(t(6, 43), 1))
    }

    @Test
    fun sentAsHomeAssistantWrites() {
        assertEquals("06:45:00", TimeField.payload(t(6, 45)))
        assertEquals(5, TimeField.step(null))
        assertEquals(15, TimeField.step(15.0))
        assertEquals(5, TimeField.step(7.0))
    }
}
