package com.schaltli.android.data

import com.schaltli.android.mqtt.ButtonActionDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen graph as the app reads it off a project (the designer's device
 * contract §2.10): next and previous over the main row, goto-neighbour, the
 * start, where a swipe leads - and an export from before the graph, whose
 * row neighbours the app derives.
 */
class ScreenGraphProjectTest {

    private val next = ButtonAction(type = "next-screen")
    private val previous = ButtonAction(type = "previous-screen")

    // A, B, C in the row, H hidden between A and B; A1 below A.
    private val graph = Project(
        name = "graph",
        screenWidth = 800,
        screenHeight = 480,
        mainRow = listOf("a", "b", "c"),
        screens = listOf(
            Screen(
                id = "a", name = "A", neighbours = mapOf("right" to "b", "down" to "a1"), pullTabColor = "#807d84",
                buttonActions = mapOf("swipe-left" to next, "swipe-right" to previous, "swipe-up" to ButtonAction(type = "goto-neighbour", side = "down")),
            ),
            Screen(id = "h", name = "H", hidden = true),
            Screen(id = "b", name = "B", neighbours = mapOf("left" to "a", "right" to "c")),
            Screen(id = "c", name = "C", neighbours = mapOf("left" to "b")),
            Screen(
                id = "a1", name = "A1", hidden = true, back = "up", neighbours = mapOf("up" to "a"),
                buttonActions = mapOf("swipe-down" to ButtonAction(type = "goto-neighbour", side = "up"), "swipe-left" to next),
            ),
        ),
    )

    @Test
    fun `next and previous walk the main row, from a history from its page`() {
        assertEquals("b", ButtonActionDispatcher.navigationTarget(next, graph, "a"))
        assertEquals("a", ButtonActionDispatcher.navigationTarget(next, graph, "c"))
        assertEquals("c", ButtonActionDispatcher.navigationTarget(previous, graph, "a"))
        assertEquals("b", ButtonActionDispatcher.navigationTarget(next, graph, "a1"))
    }

    @Test
    fun `goto-neighbour shows the screen on that side, and nothing where none lies`() {
        assertEquals("a1", ButtonActionDispatcher.navigationTarget(ButtonAction(type = "goto-neighbour", side = "down"), graph, "a"))
        assertEquals("a", ButtonActionDispatcher.navigationTarget(ButtonAction(type = "goto-neighbour", side = "up"), graph, "a1"))
        assertNull(ButtonActionDispatcher.navigationTarget(ButtonAction(type = "goto-neighbour", side = "up"), graph, "a"))
    }

    @Test
    fun `the start is the row's first, and the tabs stand where a swipe leads`() {
        assertEquals("a", ScreenGraphProject.startScreenId(graph))
        val a = graph.screens.first { it.id == "a" }
        val a1 = graph.screens.first { it.id == "a1" }
        assertEquals(listOf(ScreenGraph.Edge.DOWN, ScreenGraph.Edge.RIGHT), ScreenGraphProject.tabSides(graph, a))
        assertEquals(listOf(ScreenGraph.Edge.UP), ScreenGraphProject.tabSides(graph, a1))
        assertTrue(ScreenGraphProject.swipeLeadsToNeighbour(graph, a, "swipe-up"))
        // Next on the history leads to no neighbour of its own: no slide, a jump.
        assertFalse(ScreenGraphProject.swipeLeadsToNeighbour(graph, a1, "swipe-left"))
    }

    @Test
    fun `an export from before the screen graph has the screens not hidden as its row, each with its row neighbours`() {
        val old = Project(
            name = "old",
            screenWidth = 800,
            screenHeight = 480,
            screens = listOf(
                Screen(id = "a", name = "A", buttonActions = mapOf("swipe-left" to next, "swipe-right" to previous)),
                Screen(id = "h", name = "H", hidden = true),
                Screen(id = "b", name = "B", buttonActions = mapOf("swipe-left" to next, "swipe-right" to previous)),
            ),
        )
        assertEquals("b", ButtonActionDispatcher.navigationTarget(next, old, "a"))
        assertEquals("a", ScreenGraphProject.startScreenId(old))
        val a = old.screens.first()
        assertEquals("b", ScreenGraphProject.neighbourId(old, a, ScreenGraph.Edge.RIGHT))
        assertNull(ScreenGraphProject.neighbourId(old, a, ScreenGraph.Edge.LEFT))
        // It slides as it did, to the screen beside...
        assertTrue(ScreenGraphProject.swipeLeadsToNeighbour(old, a, "swipe-left"))
        // ...but the wrap from the row's first is a jump now.
        assertFalse(ScreenGraphProject.swipeLeadsToNeighbour(old, a, "swipe-right"))
    }
}
