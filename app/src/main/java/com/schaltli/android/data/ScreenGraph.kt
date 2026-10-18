package com.schaltli.android.data

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/**
 * The screen graph as the export carries it (the designer's device contract
 * §2.10, docs/2026-10-17-screen-graph.md): next and previous over `mainRow`,
 * which edges have a pull tab, the content area beside a navigator's strip,
 * the tab's box, and the nudge. The app computes nothing else: the swipes'
 * actions come decided.
 *
 * A port of the designer's `lib/screen-graph-device.ts` and
 * `lib/pull-tabs.ts`, as the firmware's ScreenGraph is; all three are held
 * to the same cases, `screen-graph-vectors.json` in the test resources.
 * Whole units, integer arithmetic.
 */
object ScreenGraph {

    enum class Edge(val key: String) { UP("up"), DOWN("down"), LEFT("left"), RIGHT("right") }

    fun edgeFrom(name: String?): Edge? = Edge.entries.firstOrNull { it.key == name }

    fun opposite(edge: Edge): Edge = when (edge) {
        Edge.UP -> Edge.DOWN
        Edge.DOWN -> Edge.UP
        Edge.LEFT -> Edge.RIGHT
        Edge.RIGHT -> Edge.LEFT
    }

    /** The swipe that pulls in the neighbour on an edge: swiping up shows the screen below. */
    fun swipeFor(side: Edge): String = when (side) {
        Edge.DOWN -> "swipe-up"
        Edge.UP -> "swipe-down"
        Edge.RIGHT -> "swipe-left"
        Edge.LEFT -> "swipe-right"
    }

    fun sideOfSwipe(swipeId: String): Edge? = Edge.entries.firstOrNull { swipeFor(it) == swipeId }

    /** What a swipe does, as far as the graph cares. */
    data class Action(val type: String, val side: String? = null, val targetScreenId: String? = null)

    data class GraphScreen(
        val id: String,
        val neighbours: Map<Edge, String> = emptyMap(),
        val back: Edge? = null,
        // By swipe id ("swipe-up", …).
        val swipes: Map<String, Action> = emptyMap(),
    )

    /**
     * Whether the swipe towards [side] leads to the neighbour there: its
     * action is goto-neighbour that way, goto-screen naming it, or - both
     * screens in the main row - next-screen towards the right,
     * previous-screen towards the left. The pull tab stands, and the screen
     * follows the finger, exactly where this holds.
     */
    fun swipeLeadsTo(screen: GraphScreen, side: Edge, mainRow: List<String>): Boolean {
        val neighbour = screen.neighbours[side] ?: return false
        val action = screen.swipes[swipeFor(side)] ?: return false
        return when (action.type) {
            "goto-neighbour" -> action.side == side.key
            "goto-screen" -> action.targetScreenId == neighbour
            else -> screen.id in mainRow && neighbour in mainRow &&
                ((side == Edge.RIGHT && action.type == "next-screen") || (side == Edge.LEFT && action.type == "previous-screen"))
        }
    }

    /** The edges with a pull tab, in the order up, down, left, right. */
    fun tabSides(screen: GraphScreen, mainRow: List<String>): List<Edge> = Edge.entries.filter { swipeLeadsTo(screen, it, mainRow) }

    /**
     * Next ([delta] 1) or previous (-1) over the main row, wrapping: from the
     * screen itself when it is in the row, else from [pageId], the screen it
     * is docked on. Null for an empty row.
     */
    fun rowStep(mainRow: List<String>, currentId: String, pageId: String?, delta: Int): String? {
        if (mainRow.isEmpty()) return null
        val from = if (currentId in mainRow) currentId else pageId
        val index = maxOf(0, mainRow.indexOf(from))
        val n = mainRow.size
        return mainRow[((index + delta) % n + n) % n]
    }

    data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)

    // The tab's numbers (lib/pull-tabs.ts PULL_TAB).
    const val TAB_LENGTH = 32
    const val TAB_THICKNESS = 4
    const val TAB_RADIUS = 2
    const val TAB_INSET = 6
    const val TAB_NUDGE = 6
    const val TAB_PERIOD_MS = 5000L
    const val TAB_NUDGE_MS = 800L

    /** What a swipe moves: the screen less a navigator's strip along [edge], [thickness] deep; the whole screen otherwise. */
    fun contentArea(width: Int, height: Int, edge: String?, thickness: Int): Rect = when (edge) {
        "left" -> Rect(thickness, 0, width - thickness, height)
        "right" -> Rect(0, 0, width - thickness, height)
        "top" -> Rect(0, thickness, width, height - thickness)
        "bottom" -> Rect(0, 0, width, height - thickness)
        else -> Rect(0, 0, width, height)
    }

    /** The tab on one edge of [area], [nudge] units towards the middle: whole units, centred (rounded down). */
    fun tabRect(edge: Edge, area: Rect, nudge: Int): Rect {
        val cx = area.x + Math.floorDiv(area.width - TAB_LENGTH, 2)
        val cy = area.y + Math.floorDiv(area.height - TAB_LENGTH, 2)
        val near = TAB_INSET + nudge
        return when (edge) {
            Edge.UP -> Rect(cx, area.y + near, TAB_LENGTH, TAB_THICKNESS)
            Edge.DOWN -> Rect(cx, area.y + area.height - near - TAB_THICKNESS, TAB_LENGTH, TAB_THICKNESS)
            Edge.LEFT -> Rect(area.x + near, cy, TAB_THICKNESS, TAB_LENGTH)
            Edge.RIGHT -> Rect(area.x + area.width - near - TAB_THICKNESS, cy, TAB_THICKNESS, TAB_LENGTH)
        }
    }

    /** How far the tabs stand nudged [ms] into the animation, in whole units. */
    fun nudgeAt(ms: Long): Int {
        val t = ((ms % TAB_PERIOD_MS) + TAB_PERIOD_MS) % TAB_PERIOD_MS
        if (t >= TAB_NUDGE_MS) return 0
        // Math.round: halves away from zero for the positive values here.
        return floor(TAB_NUDGE * sin(PI * t / TAB_NUDGE_MS) + 0.5).toInt()
    }
}
