package com.schaltli.android.data

import kotlin.math.abs
import kotlin.math.sqrt

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
        // The entries' part of the bar: all of it, or what the light/dark
        // switch leaves. Entries scroll and are hit within it.
        val strip: Rect,
        val horizontal: Boolean,
        val length: Int,
        val count: Int,
        val entryLength: Int,
        // The whole bar: its ground, its line, what a touch on it belongs to.
        val bar: Rect = strip,
        // The light/dark switch's place, if the navigator has one.
        val themeSwitch: Rect? = null,
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

    /** The line towards the screen (the designer's NAVIGATOR_DIVIDER): the strip's last pixels on the screen's side. */
    const val DIVIDER = 2

    fun dividerRect(edge: Edge, strip: Rect): Rect = when (edge) {
        Edge.LEFT -> Rect(strip.x + strip.width - DIVIDER, strip.y, DIVIDER, strip.height)
        Edge.RIGHT -> Rect(strip.x, strip.y, DIVIDER, strip.height)
        Edge.TOP -> Rect(strip.x, strip.y + strip.height - DIVIDER, strip.width, DIVIDER)
        Edge.BOTTOM -> Rect(strip.x, strip.y, strip.width, DIVIDER)
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

    /**
     * The bar split as the designer's barLayout: the switch's place, [length]
     * long, cut off at its start ([at] "near") or end ("far"); the entries in
     * the rest. Any other [at]: no switch.
     */
    fun barLayout(edge: Edge, bar: Rect, count: Int, entryLength: Int, at: String?, length: Int): Layout {
        if (at != "near" && at != "far") return layoutInStrip(edge, bar, count, entryLength)
        val horizontal = edge == Edge.TOP || edge == Edge.BOTTOM
        val l = minOf(length, if (horizontal) bar.width else bar.height)
        val near = at == "near"
        val sw = if (horizontal) Rect(if (near) bar.x else bar.x + bar.width - l, bar.y, l, bar.height)
        else Rect(bar.x, if (near) bar.y else bar.y + bar.height - l, bar.width, l)
        val entries = if (horizontal) Rect(if (near) bar.x + l else bar.x, bar.y, bar.width - l, bar.height)
        else Rect(bar.x, if (near) bar.y + l else bar.y, bar.width, bar.height - l)
        return layoutInStrip(edge, entries, count, entryLength).copy(bar = bar, themeSwitch = sw)
    }

    fun maxScroll(l: Layout) = maxOf(0, l.count * l.entryLength - l.length)

    fun clampScroll(l: Layout, scroll: Int) = minOf(maxOf(0, scroll), maxScroll(l))

    fun entryRect(l: Layout, index: Int, scroll: Int): Rect {
        val along = index * l.entryLength - scroll
        return if (l.horizontal) Rect(l.strip.x + along, l.strip.y, l.entryLength, l.strip.height)
        else Rect(l.strip.x, l.strip.y + along, l.strip.width, l.entryLength)
    }

    // The marker gliding with a swipe (the designer's #59, lib/navigator.ts):
    // while a swipe leads from one entry's screen to another's, the box an
    // entry's active look begins with moves along the strip with the finger
    // and settles a little past the entry arrived at and back.

    /** How long the marker takes to come to rest once the finger has lifted, in ms. */
    const val MARKER_SETTLE_MS = 320

    /** Two different entries, the strip at the same scroll on both screens. */
    fun markerGlides(from: Int, to: Int, scrollFrom: Int, scrollTo: Int): Boolean =
        from >= 0 && to >= 0 && from != to && scrollFrom == scrollTo

    /** px along the strip from entry [from]'s place, a swipe [travel] of [span] on towards [to]. */
    fun markerOffset(l: Layout, from: Int, to: Int, travel: Int, span: Int): Int {
        if (span <= 0) return 0
        val t = minOf(abs(travel), span)
        return ((to - from).toLong() * l.entryLength * t / span).toInt()
    }

    /** [elapsedMs] after the finger lifted at [start], on the way to [end]: past it by about a tenth and back. */
    fun markerSettle(start: Int, end: Int, elapsedMs: Long): Int {
        val t = (elapsedMs * 1000 / MARKER_SETTLE_MS).coerceIn(0L, 1000L)
        val u = t - 1000
        val f = 1000 + 27 * u * u * u / 10_000_000 + 17 * u * u / 10_000
        return start + ((end - start).toLong() * f / 1000).toInt()
    }

    /** The box ([box], within an entry) [offset] px on from entry [from]'s place at [scroll]. */
    fun markerRect(l: Layout, from: Int, offset: Int, scroll: Int, box: Rect): Rect {
        val entry = entryRect(l, from, scroll - offset)
        return Rect(entry.x + box.x, entry.y + box.y, box.width, box.height)
    }

    /** How far row [row] of a box with corners of [radius] is cut in at each end, whole pixels. */
    fun cornerInset(radius: Int, width: Int, height: Int, row: Int): Int {
        val r = maxOf(0, minOf(radius, width / 2, height / 2))
        val d = minOf(row, height - 1 - row)
        if (d < 0 || d >= r) return 0
        val k = 2 * r - 2 * d - 1
        return r - (sqrt((4 * r * r - k * k).toDouble()).toInt() + 1) / 2
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
