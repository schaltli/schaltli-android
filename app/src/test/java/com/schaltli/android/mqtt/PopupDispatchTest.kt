package com.schaltli.android.mqtt

import com.schaltli.android.data.ButtonAction
import com.schaltli.android.data.PopupFence
import com.schaltli.android.data.Project
import com.schaltli.android.data.collectTopicNames
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Popups (the designer's docs/device-contract.md §2.5): read from the bundle
 * apart from the screens, opened and closed through the action dispatcher,
 * their fence the same test as the designer's and the boards'.
 */
class PopupDispatchTest {
    private val json = Json { ignoreUnknownKeys = true }

    // The shape lib/android-export.ts writes, cut to what matters here.
    private val bundle = """
        {
          "name": "popups", "screenWidth": 360, "screenHeight": 800,
          "screens": [
            { "id": "main", "name": "Main", "objects": [] },
            { "id": "second", "name": "Second", "objects": [] }
          ],
          "popups": [
            { "id": "timer", "name": "Timer", "borderColor": "#cac4d0", "scrimColor": "#000000",
              "objects": [ { "id": "s", "type": "switch", "x": 40, "y": 100, "width": 80, "height": 40, "zIndex": 1,
                             "properties": { "topic": "heater/timer_on", "writeTopic": "cmnd/timer_on" } } ] },
            { "id": "fan", "name": "Fan", "objects": [] }
          ],
          "popupFence": { "shape": "rect", "x": 19, "y": 42, "width": 322, "height": 716 }
        }
    """.trimIndent()

    private val project: Project = json.decodeFromString(Project.serializer(), bundle)

    private fun dispatcher(log: MutableList<String>) = ButtonActionDispatcher(
        mqttRepository = MqttRepository(),
        onNavigate = { log += "navigate $it" },
        onOpenPopup = { log += "open $it" },
        onClosePopup = { log += "close" },
    )

    @Test
    fun `popups are read apart from the screens, with their fence and colours`() {
        assertEquals(listOf("main", "second"), project.screens.map { it.id })
        assertEquals(listOf("timer", "fan"), project.popups.map { it.id })
        assertEquals("#cac4d0", project.popups[0].borderColor)
        assertEquals(PopupFence("rect", 19, 42, 322, 716), project.popupFence)
    }

    @Test
    fun `open-popup opens it, a second one replaces it, close-popup closes`() {
        val log = mutableListOf<String>()
        val d = dispatcher(log)
        d.dispatch(ButtonAction("open-popup", targetScreenId = "timer"), project, "main")
        d.dispatch(ButtonAction("open-popup", targetScreenId = "fan"), project, "timer")
        d.dispatch(ButtonAction("close-popup"), project, "fan")
        assertEquals(listOf("open timer", "open fan", "close"), log)
    }

    @Test
    fun `an open-popup whose target is no popup does nothing`() {
        val log = mutableListOf<String>()
        val d = dispatcher(log)
        d.dispatch(ButtonAction("open-popup", targetScreenId = "second"), project, "main")
        d.dispatch(ButtonAction("open-popup", targetScreenId = "gone"), project, "main")
        d.dispatch(ButtonAction("open-popup"), project, "main")
        val withoutFence = project.copy(popupFence = null)
        d.dispatch(ButtonAction("open-popup", targetScreenId = "timer"), withoutFence, "main")
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun `paging never reaches a popup`() {
        assertEquals("second", ButtonActionDispatcher.navigationTarget(ButtonAction("next-screen"), project, "main"))
        assertEquals("main", ButtonActionDispatcher.navigationTarget(ButtonAction("next-screen"), project, "second"))
        assertEquals(null, ButtonActionDispatcher.navigationTarget(ButtonAction("goto-screen", targetScreenId = "timer"), project, "main"))
    }

    @Test
    fun `a popup's topics are subscribed`() {
        assertTrue("heater/timer_on" in project.collectTopicNames())
    }

    // The designer's insideFence (lib/popup.ts) and the boards' PopupFence::contains.
    @Test
    fun `the fence holds what the designer's holds`() {
        val rect = PopupFence("rect", 19, 42, 322, 716)
        assertTrue(rect.contains(19.0, 42.0))
        assertTrue(rect.contains(340.9, 757.9))
        assertFalse(rect.contains(341.0, 100.0))
        assertFalse(rect.contains(18.9, 100.0))
        val circle = PopupFence("circle", 19, 19, 322, 322)
        assertTrue(circle.contains(180.0, 180.0))
        assertTrue(circle.contains(19.0, 180.0))
        assertFalse(circle.contains(18.0, 180.0))
        assertFalse(circle.contains(23.0, 23.0))
    }
}
