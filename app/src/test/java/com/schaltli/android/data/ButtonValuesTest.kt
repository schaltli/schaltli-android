package com.schaltli.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A button's message with values and «Only when» ([ButtonValues]): the same
 * cases as the designer's e2e/button-values.spec.ts «the rules», which tests
 * lib/button-values.ts - the original this is ported from.
 */
class ButtonValuesTest {
    private val message = """{"action":"DISARM","code":"{topic:local/e2e-alarm/code}"}"""

    @Test
    fun onlyTopicPlaceholdersAreFilled() {
        val values = mapOf("a/b" to "12", "c#d" to "x")
        assertEquals("""{"action":"DISARM","code":"1234"}""", ButtonValues.fillMessage(message) { "1234" })
        assertEquals("12-x", ButtonValues.fillMessage("{topic:a/b}-{topic:c#d}") { values[it] })
        assertEquals("", ButtonValues.fillMessage("{topic:none}") { values[it] })
        assertEquals("""{"x":{{value}}}""", ButtonValues.fillMessage("""{"x":{{value}}}""") { values[it] })
        assertEquals("{topic:a/b", ButtonValues.fillMessage("{topic:a/b") { values[it] })
    }

    @Test
    fun sentOnlyWhenYes() {
        val topic = { _: String -> "1234" }
        assertNull(ButtonValues.pressedMessage(message, "code_ok", { "false" }, topic))
        assertNull(ButtonValues.pressedMessage(message, "code_ok", { null }, topic))
        assertEquals("""{"action":"DISARM","code":"1234"}""", ButtonValues.pressedMessage(message, "code_ok", { "true" }, topic))
        // Without «Only when» it always sends.
        assertEquals("ARM_AWAY", ButtonValues.pressedMessage("ARM_AWAY", null, { null }, { null }))
    }
}
