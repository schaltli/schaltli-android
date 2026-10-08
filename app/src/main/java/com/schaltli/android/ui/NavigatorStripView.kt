package com.schaltli.android.ui

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
import androidx.compose.ui.graphics.Color
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
    return nav to NavigatorLayout.layoutInStrip(edge, strip, nav.entries.size, nav.entryLength)
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
) {
    val (nav, layout) = navigatorOf(project, screen) ?: return
    val shown = effectiveNavigatorScroll(nav, layout, screen, scroll)
    val active = nav.entries.indexOfFirst { it.screenId == screen.id }
    val strip = layout.strip
    val density = LocalDensity.current.density
    // Read inside the gesture as it is now: as a key it would restart the
    // gesture at every step of the scroll it is making.
    val currentShown by rememberUpdatedState(shown)
    val currentScreenId by rememberUpdatedState(screen.id)

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
                            val entry = NavigatorLayout.entryAt(layout, x, y, start)
                            if (entry >= 0 && nav.entries[entry].screenId != currentScreenId) onNavigate(nav.entries[entry].screenId)
                        }
                    }
                },
        ) {
            nav.entries.forEachIndexed { i, entry ->
                val r = NavigatorLayout.entryRect(layout, i, shown)
                if (r.x >= strip.x + strip.width || r.y >= strip.y + strip.height || r.x + r.width <= strip.x || r.y + r.height <= strip.y) return@forEachIndexed
                Box(modifier = Modifier.offset((r.x - strip.x).dp, (r.y - strip.y).dp).size(r.width.dp, r.height.dp)) {
                    for (obj in (if (i == active) entry.active else entry.normal).sortedByZIndex()) {
                        EntryObjectView(obj, project, topicValues, assetFileOf, nav.backgroundColor)
                    }
                }
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
