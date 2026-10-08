package com.schaltli.android.data

/**
 * The navigator's geometry (the designer's docs/2026-10-08-navigator.md,
 * device contract §2.7): which screens it lists, its strip, where each entry
 * is at a scroll offset, how far to scroll to show an entry, which entry is
 * under a point, a page of scrolling.
 *
 * A line-by-line port of the designer's `lib/navigator.ts`, as the
 * firmware's NavigatorLayout.cpp is; all three are held to the same cases,
 * `navigator-vectors.json` in the test resources. Whole units, integer
 * arithmetic.
 */
object NavigatorLayout {

    enum class Edge { TOP, BOTTOM, LEFT, RIGHT }

    data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)

    data class Layout(
        val edge: Edge,
        val strip: Rect,
        val horizontal: Boolean,
        val length: Int,
        val count: Int,
        val entryLength: Int,
    )

    data class Listed(val isMaster: Boolean = false, val popup: Boolean = false, val hidden: Boolean = false)

    fun edgeFrom(name: String?): Edge = when (name) {
        "top" -> Edge.TOP
        "bottom" -> Edge.BOTTOM
        "right" -> Edge.RIGHT
        else -> Edge.LEFT
    }

    /** The indices of the screens with an entry: main screens that are not hidden. */
    fun listedScreens(screens: List<Listed>): List<Int> =
        screens.indices.filter { !screens[it].isMaster && !screens[it].popup && !screens[it].hidden }

    fun thicknessFor(withText: Boolean) = if (withText) 80 else 64
    fun minEntryFor(withText: Boolean) = if (withText) 88 else 64

    fun stripOf(edge: Edge, thickness: Int, screenWidth: Int, screenHeight: Int): Rect = when (edge) {
        Edge.TOP -> Rect(0, 0, screenWidth, thickness)
        Edge.BOTTOM -> Rect(0, screenHeight - thickness, screenWidth, thickness)
        Edge.LEFT -> Rect(0, 0, thickness, screenHeight)
        Edge.RIGHT -> Rect(screenWidth - thickness, 0, thickness, screenHeight)
    }

    fun strip(edge: Edge, withText: Boolean, screenWidth: Int, screenHeight: Int): Rect =
        stripOf(edge, thicknessFor(withText), screenWidth, screenHeight)

    fun layout(edge: Edge, withText: Boolean, screenWidth: Int, screenHeight: Int, count: Int): Layout {
        val s = strip(edge, withText, screenWidth, screenHeight)
        val horizontal = edge == Edge.TOP || edge == Edge.BOTTOM
        val length = if (horizontal) s.width else s.height
        val min = minEntryFor(withText)
        val entryLength = if (count > 0 && count * min <= length) length / count else min
        return layoutInStrip(edge, s, count, entryLength)
    }

    fun layoutInStrip(edge: Edge, strip: Rect, count: Int, entryLength: Int): Layout {
        val horizontal = edge == Edge.TOP || edge == Edge.BOTTOM
        return Layout(edge, strip, horizontal, if (horizontal) strip.width else strip.height, count, entryLength)
    }

    fun maxScroll(l: Layout) = maxOf(0, l.count * l.entryLength - l.length)

    fun clampScroll(l: Layout, scroll: Int) = minOf(maxOf(0, scroll), maxScroll(l))

    fun entryRect(l: Layout, index: Int, scroll: Int): Rect {
        val along = index * l.entryLength - scroll
        return if (l.horizontal) Rect(l.strip.x + along, l.strip.y, l.entryLength, l.strip.height)
        else Rect(l.strip.x, l.strip.y + along, l.strip.width, l.entryLength)
    }

    fun scrollToShow(l: Layout, index: Int, scroll: Int): Int {
        val start = index * l.entryLength
        val end = start + l.entryLength
        var next = scroll
        if (start < next) next = start
        else if (end > next + l.length) next = end - l.length
        return clampScroll(l, next)
    }

    fun entryAt(l: Layout, x: Int, y: Int, scroll: Int): Int {
        val s = l.strip
        if (x < s.x || y < s.y || x >= s.x + s.width || y >= s.y + s.height) return -1
        if (l.entryLength <= 0) return -1
        val along = (if (l.horizontal) x - s.x else y - s.y) + scroll
        val index = along / l.entryLength
        return if (index < l.count) index else -1
    }

    fun pageScroll(l: Layout, scroll: Int, direction: Int): Int {
        val page = maxOf(1, if (l.entryLength > 0) l.length / l.entryLength else 1) * l.entryLength
        return clampScroll(l, scroll + direction * page)
    }

    /**
     * Paging past hidden screens (§2.7): from [current], [delta] steps over
     * the screens that are not hidden, wrapping; [current] itself may be
     * hidden (reached by a goto). With none shown, [current].
     */
    fun pagedIndex(hidden: List<Boolean>, current: Int, delta: Int): Int {
        val count = hidden.size
        if (count == 0) return current
        var index = current
        repeat(count) {
            index = ((index + delta) % count + count) % count
            if (!hidden[index]) return index
        }
        return current
    }

    /** The screen the app opens on: the first that is not hidden, else 0. */
    fun firstShown(hidden: List<Boolean>): Int = hidden.indexOfFirst { !it }.let { if (it < 0) 0 else it }
}
