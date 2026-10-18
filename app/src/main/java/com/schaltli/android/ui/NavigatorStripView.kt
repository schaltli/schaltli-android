package com.schaltli.android.ui

import com.schaltli.android.data.ScreenGraph
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect as GeometryRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.schaltli.android.data.ButtonAction
import com.schaltli.android.data.Navigator
import com.schaltli.android.data.NavigatorLayout
import com.schaltli.android.data.Project
import com.schaltli.android.data.Screen
import com.schaltli.android.data.ScreenObject
import com.schaltli.android.ui.objects.IconPathView
import com.schaltli.android.ui.objects.parseHexColor
import com.schaltli.android.ui.objects.stringOrNull
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** The navigator [screen] shows (§2.7), laid out on the project's screen; null for none. */
fun navigatorOf(project: Project, screen: Screen): Pair<Navigator, NavigatorLayout.Layout>? {
    val nav = screen.navigatorId?.let { id -> project.navigators.find { it.id == id } } ?: return null
    val edge = NavigatorLayout.edgeFrom(nav.edge)
    val strip = NavigatorLayout.stripOf(edge, nav.thickness, project.screenWidth, project.screenHeight)
    val sw = nav.themeSwitch
    // Off the main row the way back is its one entry, as long as the
    // entries' part of the strip (§2.10).
    if (backArrowOf(nav, screen) != null) {
        val one = NavigatorLayout.barLayout(edge, strip, 1, nav.entryLength, sw?.at, sw?.length ?: 0)
        return nav to one.copy(entryLength = one.length)
    }
    return nav to NavigatorLayout.barLayout(edge, strip, nav.entries.size, nav.entryLength, sw?.at, sw?.length ?: 0)
}

/** The way back's arrow on [screen] (§2.10): the edge it points to, whose objects [Navigator.back] has; null on the main row. */
fun backArrowOf(nav: Navigator, screen: Screen): String? {
    val back = ScreenGraph.edgeFrom(screen.back) ?: return null
    return ScreenGraph.opposite(back).key.takeIf { nav.back[it]?.isNotEmpty() == true }
}

/**
 * The navigator's marker on its way along the strip (the designer's #59):
 * [offset] units from entry [from]'s place, towards [to]'s.
 */
data class NavigatorMarker(val from: Int, val to: Int, val offset: Int)

/**
 * Where the marker is while the screen follows the finger from [screen] to
 * [target], [travel] of [span] units on - null where it does not glide: off
 * the main row, to a screen with another navigator, or one the strip
 * scrolls to (NavigatorLayout.markerGlides).
 */
fun navigatorMarkerFor(project: Project, screen: Screen, target: Screen?, scroll: Int, travel: Int, span: Int): NavigatorMarker? {
    if (target == null || target.navigatorId != screen.navigatorId) return null
    val (nav, layout) = navigatorOf(project, screen) ?: return null
    val (_, targetLayout) = navigatorOf(project, target) ?: return null
    if (backArrowOf(nav, screen) != null || backArrowOf(nav, target) != null) return null
    val from = nav.entries.indexOfFirst { it.screenId == screen.id }
    val to = nav.entries.indexOfFirst { it.screenId == target.id }
    val glides = NavigatorLayout.markerGlides(
        from, to, effectiveNavigatorScroll(nav, layout, screen, scroll), effectiveNavigatorScroll(nav, targetLayout, target, scroll),
    )
    return if (glides) NavigatorMarker(from, to, NavigatorLayout.markerOffset(layout, from, to, travel, span)) else null
}

/** The box an entry's active look begins with (§2.7): what glides. */
private fun markerBoxOf(nav: Navigator): ScreenObject? = nav.entries.firstOrNull()?.active?.firstOrNull { it.type == "box" }

/** A box's shape cut in row by row at its corners, as every device cuts the gliding marker (NavigatorLayout.cornerInset). */
private class MarkerShape(private val box: NavigatorLayout.Rect, private val radius: Int) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        val unit = density.density
        for (row in 0 until box.height) {
            val inset = NavigatorLayout.cornerInset(radius, box.width, box.height, row)
            if (box.width - 2 * inset <= 0) continue
            path.addRect(GeometryRect((box.x + inset) * unit, (box.y + row) * unit, (box.x + box.width - inset) * unit, (box.y + row + 1) * unit))
        }
        return Outline.Generic(path)
    }
}

/** The scroll the navigator is drawn at: [scroll], or (-1) as far as shows the open entry. */
fun effectiveNavigatorScroll(nav: Navigator, layout: NavigatorLayout.Layout, screen: Screen, scroll: Int): Int {
    val active = nav.entries.indexOfFirst { it.screenId == screen.id }
    return if (scroll >= 0) NavigatorLayout.clampScroll(layout, scroll)
    else NavigatorLayout.scrollToShow(layout, maxOf(0, active), 0)
}

/**
 * The navigator over the screen (the designer's docs/2026-10-08-navigator.md):
 * a screen-sized layer with the strip at its edge, its ground covering what
 * lies under it, each entry's `normal` or `active` objects at the entry's
 * place, clipped to the strip. It stands outside the screens that slide
 * (FollowingScreens), so it stays put while they move.
 *
 * A touch that starts on the strip is its own - consumed at once, so the
 * paging around it never sees it, as a settable level's: a drag along it
 * scrolls ([onScroll]), a still one opens the entry's screen ([onNavigate]).
 */
@Composable
fun NavigatorStripView(
    project: Project,
    screen: Screen,
    scroll: Int,
    topicValues: Map<String, String>,
    assetFileOf: (String) -> File,
    onScroll: (Int) -> Unit,
    onNavigate: (String) -> Unit,
    onThemeSwitch: () -> Unit = {},
    marker: NavigatorMarker? = null,
) {
    val (nav, layout) = navigatorOf(project, screen) ?: return
    val shown = effectiveNavigatorScroll(nav, layout, screen, scroll)
    // Off the main row: the way back alone, a tap on it shows the page (§2.10).
    val arrow = backArrowOf(nav, screen)
    val backTo = ScreenGraph.edgeFrom(screen.back)?.let { screen.neighbours[it.key] }
    val shownEntries: List<Pair<String, List<ScreenObject>>> =
        if (arrow != null) listOf((backTo ?: "") to nav.back[arrow].orEmpty())
        else nav.entries.map { it.screenId to it.normal }
    val active = if (arrow != null) -1 else nav.entries.indexOfFirst { it.screenId == screen.id }
    // The whole bar is the navigator's; its entries scroll in their part of it.
    val strip = layout.bar
    val entries = layout.strip
    val density = LocalDensity.current.density
    // Read inside the gesture as it is now: as a key it would restart the
    // gesture at every step of the scroll it is making.
    val currentShown by rememberUpdatedState(shown)
    val currentScreenId by rememberUpdatedState(screen.id)
    val currentEntries by rememberUpdatedState(shownEntries)

    Box(modifier = Modifier.size(project.screenWidth.dp, project.screenHeight.dp)) {
        Box(
            modifier = Modifier
                .offset(strip.x.dp, strip.y.dp)
                .size(strip.width.dp, strip.height.dp)
                .clip(RoundedCornerShape(0.dp))
                .background(nav.backgroundColor?.let(::parseHexColor) ?: Color.White)
                .pointerInput(nav, layout) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val start = currentShown
                        var moved = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            val delta = change.position - down.position
                            val along = ((if (layout.horizontal) delta.x else delta.y) / density).roundToInt()
                            if (!moved && abs(along) > TAP_SLOP) moved = true
                            if (moved) onScroll(NavigatorLayout.clampScroll(layout, start - along))
                            change.consume()
                        }
                        if (!moved) {
                            val x = (down.position.x / density).roundToInt() + strip.x
                            val y = (down.position.y / density).roundToInt() + strip.y
                            // The light/dark switch asks for the other variant (§2.7, 1.6).
                            val sw = layout.themeSwitch
                            if (sw != null && x >= sw.x && y >= sw.y && x < sw.x + sw.width && y < sw.y + sw.height) {
                                onThemeSwitch()
                                return@awaitEachGesture
                            }
                            val entry = NavigatorLayout.entryAt(layout, x, y, start)
                            val target = currentEntries.getOrNull(entry)?.first
                            if (!target.isNullOrEmpty() && target != currentScreenId) onNavigate(target)
                        }
                    }
                },
        ) {
            // The entries, clipped to their part: they scroll, the switch stays.
            Box(
                modifier = Modifier
                    .offset((entries.x - strip.x).dp, (entries.y - strip.y).dp)
                    .size(entries.width.dp, entries.height.dp)
                    .clip(RoundedCornerShape(0.dp)),
            ) {
                // While the marker glides every entry is normal, and inside
                // the marker - on its colour - active.
                val gliding = marker?.takeIf { arrow == null && it.from in nav.entries.indices }
                val box = gliding?.let { markerBoxOf(nav) }
                @Composable
                fun drawEntries(lit: (Int) -> Boolean) = shownEntries.forEachIndexed { i, (_, normal) ->
                    val r = NavigatorLayout.entryRect(layout, i, shown)
                    if (r.x >= entries.x + entries.width || r.y >= entries.y + entries.height || r.x + r.width <= entries.x || r.y + r.height <= entries.y) return@forEachIndexed
                    Box(modifier = Modifier.offset((r.x - entries.x).dp, (r.y - entries.y).dp).size(r.width.dp, r.height.dp)) {
                        for (obj in (if (lit(i)) nav.entries[i].active else normal).sortedByZIndex()) {
                            EntryObjectView(obj, project, topicValues, assetFileOf, nav.backgroundColor)
                        }
                    }
                }
                drawEntries { i -> box == null && i == active }
                if (gliding != null && box != null) {
                    val at = NavigatorLayout.markerRect(layout, gliding.from, gliding.offset, shown, NavigatorLayout.Rect(box.x.roundToInt(), box.y.roundToInt(), box.width.roundToInt(), box.height.roundToInt()))
                    val radius = (box.properties["cornerRadius"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: 0
                    val within = NavigatorLayout.Rect(at.x - entries.x, at.y - entries.y, at.width, at.height)
                    Box(
                        modifier = Modifier
                            .size(entries.width.dp, entries.height.dp)
                            .clip(MarkerShape(within, radius))
                            .background(box.properties.stringOrNull("fillColor")?.let(::parseHexColor) ?: Color.Transparent),
                    ) {
                        drawEntries { true }
                    }
                }
            }
            layout.themeSwitch?.let { sw ->
                Box(modifier = Modifier.offset((sw.x - strip.x).dp, (sw.y - strip.y).dp).size(sw.width.dp, sw.height.dp)) {
                    for (obj in (nav.themeSwitch?.objects ?: emptyList()).sortedByZIndex()) {
                        EntryObjectView(obj, project, topicValues, assetFileOf, nav.backgroundColor)
                    }
                }
            }
            // The line towards the screen, over the entries (§2.7): so the
            // strip reads as a bar, not as one button on the screen's ground.
            nav.borderColor?.let(::parseHexColor)?.let { line ->
                val d = NavigatorLayout.dividerRect(layout.edge, strip)
                Box(modifier = Modifier.offset((d.x - strip.x).dp, (d.y - strip.y).dp).size(d.width.dp, d.height.dp).background(line))
            }
        }
    }
}

// An entry's objects are ordinary ones (§2.7), but on a phone a fixed icon
// and a box are otherwise baked into a screen's background - here there is
// none, so they are drawn.
@Composable
private fun EntryObjectView(obj: ScreenObject, project: Project, topicValues: Map<String, String>, assetFileOf: (String) -> File, ground: String?) {
    when {
        obj.type == "box" -> Box(
            modifier = Modifier
                .offset(obj.x.dp, obj.y.dp)
                .size(obj.width.dp, obj.height.dp)
                .background(
                    obj.properties.stringOrNull("fillColor")?.let(::parseHexColor) ?: Color.Transparent,
                    RoundedCornerShape(((obj.properties["cornerRadius"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0).dp),
                ),
        )
        obj.type == "icon" && obj.properties.stringOrNull("liveIconId").isNullOrEmpty() -> IconPathView(obj, obj.path, assetFileOf)
        else -> DynamicObjectView(obj, project, topicValues, assetFileOf, onAction = { _: ButtonAction -> }, screenBackgroundColor = ground)
    }
}

/** Movement, in project units, below which a press on the strip is a tap. */
private const val TAP_SLOP = 8
