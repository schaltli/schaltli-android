package com.schaltli.android.ui.objects

import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import com.schaltli.android.data.ScreenObject
import com.schaltli.android.render.Guard
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The guarded controls of one screen (the designer's docs/2026-11-15-guard.md,
 * device contract §2.14): one cover per control that has one, drawn over all
 * of the screen's objects by [GuardLayerView], and the hint after a plain tap.
 *
 * One layer per composed screen ([LocalGuards]), so another screen starts
 * with every control locked (E2); a control that leaves composition - its
 * panel closed - takes its cover with it (E4); the panel going dark clears
 * it ([clear]).
 */
class GuardLayer {
    class Entry(val id: String) {
        var cover by mutableStateOf<Guard.Cover?>(null)
        /** The cover's outline on the screen, in project units. */
        var x = 0f
        var y = 0f
        var w = 0
        var h = 0
        var r = 0
        var travel = 20

        val unlocked: Boolean get() = cover?.unlocked == true
        fun changed() {
            cover = cover?.changed(SystemClock.uptimeMillis())
        }
    }

    val entries = mutableStateMapOf<String, Entry>()
    var hintUntil by mutableLongStateOf(0L)
    var frame by mutableLongStateOf(0L)
    /** Where the screen's own box stands in the window, px. */
    var root: Offset = Offset.Zero

    fun entry(id: String): Entry = entries.getOrPut(id) { Entry(id) }
    fun remove(id: String) {
        entries.remove(id)
    }

    fun clear() {
        entries.values.forEach { it.cover = null }
        hintUntil = 0L
    }

    fun tick(now: Long) {
        for (e in entries.values) {
            val c = e.cover ?: continue
            val next = c.tick(now)
            if (next != c) e.cover = next
        }
        if (hintUntil != 0L && now >= hintUntil) hintUntil = 0L
        frame = now
    }

    val busy: Boolean get() = hintUntil != 0L || entries.values.any { it.cover != null }
}

val LocalGuards = compositionLocalOf<GuardLayer?> { null }

/** Whether `obj` is guarded: `properties.guard` and the designer's geometry beside it. */
fun isGuarded(obj: ScreenObject): Boolean =
    obj.guard != null && (obj.properties["guard"] as? JsonPrimitive)?.booleanOrNull == true

/**
 * The control's entry in its screen's layer, kept where it stands on the
 * glass, gone with it - or null when it is not guarded.
 */
@Composable
fun rememberGuard(obj: ScreenObject): GuardLayer.Entry? {
    val layer = LocalGuards.current ?: return null
    if (!isGuarded(obj)) return null
    val entry = remember(layer, obj.id) { layer.entry(obj.id) }
    DisposableEffect(layer, obj.id) { onDispose { layer.remove(obj.id) } }
    return entry
}

/**
 * The guard's half of a control's touch, outermost on its box: a finger
 * down on a control not unlocked belongs to the cover - consumed on the
 * initial pass, so the control's own tap and the screen's swipe never see
 * it (decisions 5 and 8, E6, E8). Unlocked, the touch goes on to the control.
 */
fun Modifier.guarded(obj: ScreenObject, entry: GuardLayer.Entry?, layer: GuardLayer?, density: Float): Modifier {
    if (entry == null || layer == null) return this
    val g = obj.guard ?: return this
    return this
        .onGloballyPositioned { coords ->
            // The control's own corner on the screen, plus where the
            // designer put the cover relative to it.
            val at = coords.positionInRoot() - layer.root
            entry.x = at.x / density + (g.cover.x - obj.x).toFloat()
            entry.y = at.y / density + (g.cover.y - obj.y).toFloat()
            entry.w = g.cover.width.toInt()
            entry.h = g.cover.height.toInt()
            entry.r = g.cover.radius.toInt()
            entry.travel = g.travel.toInt()
        }
        .pointerInput(obj.id, entry) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (entry.unlocked) return@awaitEachGesture
                down.consume()
                entry.cover = Guard.down(SystemClock.uptimeMillis(), down.position.x / density, down.position.y / density)
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    // A second finger is ignored, and nothing else gets it either.
                    event.changes.forEach { it.consume() }
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val now = SystemClock.uptimeMillis()
                    val c = entry.cover ?: break
                    if (!change.pressed) {
                        val (next, hint) = c.up(now)
                        entry.cover = next
                        if (hint) layer.hintUntil = now + Guard.HINT_MS
                        break
                    }
                    entry.cover = c.move(now, change.position.x / density, change.position.y / density, entry.travel)
                }
            }
        }
}

private val solid = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }

/** Runs drawn in whole project pixels, scaled to the panel. */
fun drawGuardRuns(native: android.graphics.Canvas, runs: List<List<IntArray>>, x: Int, y: Int, color: Int, scale: Float, alpha: Float = 1f) {
    solid.color = color
    solid.alpha = (255 * alpha).toInt().coerceIn(0, 255)
    runs.forEachIndexed { row, list ->
        for (s in list) native.drawRect((x + s[0]) * scale, (y + row) * scale, (x + s[1]) * scale, (y + row + 1) * scale, solid)
    }
}

/** The lock, [size] square at x/y, in [color] (decision 4). */
fun drawGuardLock(native: android.graphics.Canvas, x: Int, y: Int, size: Int, color: Int, scale: Float) =
    drawGuardRuns(native, Guard.lockRuns(size), x, y, color, scale)

/** A Button Group's badge: the disc in the group's colour, the lock in its on-colour. */
fun drawGuardBadge(native: android.graphics.Canvas, obj: ScreenObject, fill: Int, ink: Int, scale: Float) {
    val b = obj.guard?.badge ?: return
    val ox = obj.x.toInt()
    val oy = obj.y.toInt()
    val r = b.r.toInt()
    val d = 2 * r
    val disc = Guard.roundRectSpans(d, d, r).map { s -> if (s[1] > s[0]) listOf(s) else emptyList() }
    drawGuardRuns(native, disc, b.cx.toInt() - ox - r, b.cy.toInt() - oy - r, fill, scale)
    drawGuardLock(native, b.lock.x.toInt() - ox, b.lock.y.toInt() - oy, b.lock.size.toInt(), ink, scale)
}

/** A Button's or Toggle Button's lock in its corner, in the face's ink. */
fun drawGuardCornerLock(native: android.graphics.Canvas, obj: ScreenObject, ink: Int, scale: Float) {
    val l = obj.guard?.lock ?: return
    drawGuardLock(native, l.x.toInt() - obj.x.toInt(), l.y.toInt() - obj.y.toInt(), l.size.toInt(), ink, scale)
}

private fun drawCover(native: android.graphics.Canvas, e: GuardLayer.Entry, c: Guard.Cover, now: Long, scale: Float) {
    val d = Guard.drawn(c, now, e.h)
    if (d.alpha <= 0f) return
    val x = e.x.toInt()
    val top = e.y.toInt() - d.lift
    val spans = Guard.roundRectSpans(e.w, e.h, e.r)
    val a = (255 * d.alpha).toInt().coerceIn(0, 255)
    spans.forEachIndexed { row, s ->
        var sx = s[0]
        while (sx < s[1]) {
            val second = Guard.stripeIsSecond(sx, row)
            var end = sx + 1
            while (end < s[1] && Guard.stripeIsSecond(end, row) == second) end++
            solid.color = if (second) Guard.YELLOW_2 else Guard.YELLOW
            solid.alpha = a
            native.drawRect((x + sx) * scale, (top + row) * scale, (x + end) * scale, (top + row + 1) * scale, solid)
            sx = end
        }
    }
    val m = Guard.coverMarks(x, e.y.toInt(), e.w, e.h)
    drawGuardRuns(native, Guard.lockRuns(m.size), m.lockX, m.lockY - d.lift, Guard.INK, scale, d.alpha)
    drawGuardRuns(native, Guard.arrowRuns(m.size), m.arrowsX, m.arrowsY - d.lift - d.bob, Guard.INK, scale, d.alpha)
}

/**
 * Every cover of the screen over its objects, and the hint centred at the
 * bottom (E5); moved on every frame while any is showing.
 */
@Composable
fun GuardLayerView(layer: GuardLayer, asleep: Boolean) {
    LaunchedEffect(asleep) { if (asleep) layer.clear() }
    LaunchedEffect(layer) {
        while (isActive) {
            if (layer.busy) withFrameMillis { layer.tick(SystemClock.uptimeMillis()) } else delay(50)
        }
    }
    val density = LocalDensity.current.density
    val frame = layer.frame
    Canvas(modifier = Modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION") frame
        val now = SystemClock.uptimeMillis()
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            for (e in layer.entries.values) {
                val c = e.cover ?: continue
                drawCover(native, e, c, now, density)
            }
            if (layer.hintUntil != 0L) {
                val text = Paint().apply {
                    isAntiAlias = true
                    color = android.graphics.Color.WHITE
                    textSize = 15f * density
                }
                val tw = text.measureText(Guard.HINT)
                val h = 32f * density
                val w = tw + 32f * density
                val left = (size.width - w) / 2
                val top = size.height - h - 24f * density
                val pill = Paint().apply { isAntiAlias = true; color = 0xE0202020.toInt() }
                native.drawRoundRect(RectF(left, top, left + w, top + h), h / 2, h / 2, pill)
                native.drawText(Guard.HINT, left + 16f * density, top + h / 2 + text.textSize / 3, text)
            }
        }
    }
}
