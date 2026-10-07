package com.schaltli.android.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.schaltli.android.data.POPUP_CLOSE_RADIUS
import com.schaltli.android.data.PopupFence
import com.schaltli.android.data.Project
import com.schaltli.android.data.Screen
import com.schaltli.android.ui.objects.parseHexColor
import kotlin.math.abs

/**
 * A popup over the screen (the designer's docs/device-contract.md §2.5,
 * docs/2026-10-06-popup-screens.md).
 *
 * Drawn above everything else in the window, so it takes every touch: the
 * screen underneath and its swipe navigation see none while it is open. The
 * screen is dimmed toward the popup's scrim colour, and the popup zooms out of
 * the button that opened it into its fence - Compose can scale, which the
 * boards cannot; they grow a rectangle instead. The scrim reaches up to the
 * window at every size on the way, as the user asked of the boards.
 *
 * Closing (§2.5): a tap on the round close button on the fence's top right
 * corner, which takes the touch before the popup's controls; a tap beside the
 * fence, or any swipe a control did not take - a level owns its drag by
 * consuming it, a button its tap - and «Close this popup», which arrives
 * through the action dispatcher.
 *
 * [content] draws the popup itself, given the shape its ground is clipped to.
 */
@Composable
fun PopupOverlay(
    popup: Screen,
    project: Project,
    fence: PopupFence,
    // Where the button that opened it is, in project units - the zoom grows
    // out of it. Null: from the fence's own middle, a little smaller.
    origin: Rect?,
    onClose: () -> Unit,
    content: @Composable (backgroundClip: Shape) -> Unit,
) {
    val progress = remember(popup.id) { Animatable(0f) }
    LaunchedEffect(popup.id) { progress.animateTo(1f, tween(durationMillis = 220)) }

    val scrim = popup.scrimColor?.let(::parseHexColor) ?: Color.Black
    val edge = popup.borderColor?.let(::parseHexColor)
    val badge = fence.closeBadge(POPUP_CLOSE_RADIUS)
    // Dark whatever the theme, as on the boards (designer lib/popup.ts
    // POPUP_CLOSE_DISC): in the theme's outline it was pale.
    val badgeDisc = Color(0xFF303030)
    val badgeCross = Color.White
    // Where the popup's own box sits in this one, for turning a touch into
    // project units.
    var boxOffset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(popup.id, fence) {
                awaitEachGesture {
                    // The final pass: the popup's controls have had the touch
                    // first, and what they consumed is theirs.
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                    val startMs = System.currentTimeMillis()
                    var taken = down.isConsumed
                    var last = down.position
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) taken = true
                        last = change.position
                        if (!change.pressed) break
                    }
                    if (taken) return@awaitEachGesture
                    val dx = (last.x - down.position.x) / density
                    val dy = (last.y - down.position.y) / density
                    val quick = System.currentTimeMillis() - startMs <= SWIPE_MAX_MS
                    if (quick && nameSwipe(dx, dy) != null) {
                        onClose()
                        return@awaitEachGesture
                    }
                    if (abs(dx) <= TAP_SLOP && abs(dy) <= TAP_SLOP) {
                        val ux = (down.position.x - boxOffset.x) / density
                        val uy = (down.position.y - boxOffset.y) / density
                        if (!fence.contains(ux.toDouble(), uy.toDouble())) onClose()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(scrim.copy(alpha = 0.5f * progress.value))
        }
        Box(
            modifier = Modifier
                .size(width = project.screenWidth.dp, height = project.screenHeight.dp)
                .onGloballyPositioned { boxOffset = it.positionInParent() }
                .graphicsLayer {
                    val p = progress.value
                    val fenceCentre = Offset(fence.x + fence.width / 2f, fence.y + fence.height / 2f)
                    val from = origin ?: Rect(
                        center = fenceCentre,
                        radius = minOf(fence.width, fence.height) * 0.4f,
                    )
                    val startScaleX = (from.width / fence.width).coerceIn(0.05f, 1f)
                    val startScaleY = (from.height / fence.height).coerceIn(0.05f, 1f)
                    transformOrigin = TransformOrigin(
                        fenceCentre.x / project.screenWidth,
                        fenceCentre.y / project.screenHeight,
                    )
                    scaleX = startScaleX + (1f - startScaleX) * p
                    scaleY = startScaleY + (1f - startScaleY) * p
                    translationX = (from.center.x - fenceCentre.x) * (1f - p) * density
                    translationY = (from.center.y - fenceCentre.y) * (1f - p) * density
                }
                // The close button first, before the popup's controls see the
                // touch: a tap on it closes the popup whatever lies under it.
                .pointerInput(popup.id, fence) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val unit = size.width.toFloat() / project.screenWidth
                        if (!badge.contains((down.position.x / unit).toDouble(), (down.position.y / unit).toDouble())) {
                            return@awaitEachGesture
                        }
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                            change.consume()
                            if (!change.pressed) break
                        }
                        onClose()
                    }
                },
        ) {
            content(FenceShape(fence, project))
            if (edge != null) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val unit = size.width / project.screenWidth
                    val stroke = 2.dp.toPx()
                    val topLeft = Offset(fence.x * unit + stroke / 2, fence.y * unit + stroke / 2)
                    val area = Size(fence.width * unit - stroke, fence.height * unit - stroke)
                    if (fence.isCircle) {
                        drawCircle(edge, radius = area.width / 2, center = topLeft + Offset(area.width / 2, area.height / 2), style = Stroke(stroke))
                    } else {
                        drawRect(edge, topLeft = topLeft, size = area, style = Stroke(stroke))
                    }
                }
            }
            // The close button over everything else: a dark disc, a white X.
            Canvas(modifier = Modifier.fillMaxSize()) {
                val unit = size.width / project.screenWidth
                val centre = Offset(badge.cx * unit, badge.cy * unit)
                val r = badge.radius * unit
                val arm = r * 0.4f
                drawCircle(badgeDisc, radius = r, center = centre)
                val pen = maxOf(1f, r / 5f)
                drawLine(badgeCross, centre + Offset(-arm, -arm), centre + Offset(arm, arm), strokeWidth = pen, cap = StrokeCap.Round)
                drawLine(badgeCross, centre + Offset(-arm, arm), centre + Offset(arm, -arm), strokeWidth = pen, cap = StrokeCap.Round)
            }
        }
    }
}

/** The fence as a clip, in the popup box's own coordinates. */
private class FenceShape(private val fence: PopupFence, private val project: Project) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val unit = size.width / project.screenWidth
        val rect = Rect(fence.x * unit, fence.y * unit, (fence.x + fence.width) * unit, (fence.y + fence.height) * unit)
        if (!fence.isCircle) return Outline.Rectangle(rect)
        return Outline.Generic(Path().apply { addOval(rect) })
    }
}

// A swipe on a popup is the same gesture as on a screen (nameSwipe), and as
// quick as the boards ask (the knob: 900 ms). A tap travels less than a swipe
// begins - the boards' TAP_MAX_DRIFT.
private const val SWIPE_MAX_MS = 900L
private const val TAP_SLOP = 10f
