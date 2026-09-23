package com.schaltli.android.ui

import com.schaltli.android.data.ButtonAction
import com.schaltli.android.data.Project
import com.schaltli.android.data.Screen
import com.schaltli.android.mqtt.ButtonActionDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two pieces of swipe paging that can be decided without a screen: which
 * gesture was made, and which screen it leads to.
 *
 * What the picture does while the gesture is being made needs a phone, and is
 * checked by `hil/android/orchestrator.js` in the designer's repo - it drives
 * a slow `adb input swipe` and looks at the glass halfway through.
 */
class SwipeNavigationTest {

    private fun project(vararg ids: String) = Project(
        name = "test",
        screenWidth = 360,
        screenHeight = 679,
        screens = ids.map { Screen(id = it, name = it) },
    )

    @Test
    fun `a gesture is named by which way it went, and only if it went far enough`() {
        assertEquals("swipe-left", nameSwipe(-80f, 0f))
        assertEquals("swipe-right", nameSwipe(80f, 0f))
        assertEquals("swipe-up", nameSwipe(0f, -80f))
        assertEquals("swipe-down", nameSwipe(0f, 80f))

        // 60 units, well past a jittery tap. A tap travels nothing at all,
        // which is what lets this detector sit on the same node as a
        // SoftwareButton's without either knowing about the other.
        assertNull(nameSwipe(-59f, 0f))
        assertNull(nameSwipe(0f, 0f))
    }

    @Test
    fun `drift is judged in proportion to travel, not against a fixed cap`() {
        // The firmware ran an absolute 70px cap and it contradicted itself on
        // real gestures (2026-08-22): a long swipe with proportionally modest
        // drift was rejected while a shorter, visibly wonkier one was taken.
        // A finger pivoting from the wrist drifts in proportion to how far it
        // travels, so a 2:1 ratio is the rule.
        assertEquals("swipe-up", nameSwipe(90f, -309f))
        assertNull(nameSwipe(51f, -80f))
    }

    @Test
    fun `where a swipe leads is worked out once, for sliding and for landing`() {
        val p = project("a", "b", "c")

        assertEquals("b", ButtonActionDispatcher.navigationTarget(ButtonAction(type = "next-screen"), p, "a"))
        assertEquals("c", ButtonActionDispatcher.navigationTarget(ButtonAction(type = "previous-screen"), p, "a"))
        assertEquals(
            "c",
            ButtonActionDispatcher.navigationTarget(ButtonAction(type = "goto-screen", targetScreenId = "c"), p, "a"),
        )

        // The wrap-around is the reason this is one function rather than two.
        // A follow-the-finger swipe draws the incoming screen before it knows
        // whether the gesture will be finished; a second copy of this
        // arithmetic would eventually slide in one screen and land on another.
        assertEquals("a", ButtonActionDispatcher.navigationTarget(ButtonAction(type = "next-screen"), p, "c"))

        // Not every swipe goes anywhere, and those are the ones with no
        // second picture to bring in - they are named on release instead.
        assertNull(
            ButtonActionDispatcher.navigationTarget(
                ButtonAction(type = "send-mqtt", mqttTopic = "t", mqttMessage = "m"), p, "a",
            ),
        )
        assertNull(
            ButtonActionDispatcher.navigationTarget(
                ButtonAction(type = "device-action", deviceActionId = "showScreenMenu"), p, "a",
            ),
        )
        assertNull(
            ButtonActionDispatcher.navigationTarget(
                ButtonAction(type = "goto-screen", targetScreenId = "nowhere"), p, "a",
            ),
        )
    }
}
