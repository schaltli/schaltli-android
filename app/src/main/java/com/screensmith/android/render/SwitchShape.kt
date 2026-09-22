package com.screensmith.android.render

import com.screensmith.android.data.FontEntry
import com.screensmith.android.data.ScreenObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The shape and the colours of a Switch, as whole pixels - this repo's copy
 * of the designer's `lib/switch-shape.ts`.
 *
 * Two forms, from the object's own type:
 *
 *   "button-group" -> a **connected button group** (Material 3): one
 *                     container with the states side by side in it, the
 *                     chosen one as its own pill. Material has retired the
 *                     segmented button in favour of this.
 *   "switch"       -> a **switch**: a track with a knob that stands at one of
 *                     n positions, the state's icon on the knob and its label
 *                     beside it. n is normally 2.
 *
 * Nothing here has a background, a border, a corner radius or a text colour
 * of its own: everything is derived from ONE colour the author sets and from
 * what the control stands on. That is why the colours are in this file rather
 * than in the view - they are as much a rule as the rectangles are, and a
 * port that gets a derivation wrong draws the right shapes in the wrong
 * colours, which no geometry check would see.
 *
 * Integer arithmetic only, truncating toward zero wherever a fraction
 * appears, because C++ integer division truncates that way and that is the
 * side that cannot be changed. [SwitchShapeGoldenTest] holds all of it to the
 * numbers the designer itself produces; see
 * `hil/android/fixtures/build-switch-golden.js` in the designer repo.
 *
 * Why it looks like this at all: docs/2026-09-20-switch-look.md over there.
 */

/** The inset between the container and the buttons in it. */
const val SWITCH_PAD = 2

/**
 * Material 3's connected button group: 2 between the buttons, and 8 at the
 * corners where two of them face each other. Both are the spec's own numbers
 * rather than anything chosen here.
 */
const val SWITCH_BUTTON_GAP = 2
const val SWITCH_INNER_CORNER = 8

/** Icon to label, and track to label. */
const val SWITCH_GAP = 8

/** How much bigger the knob gets while a finger is on it (Material grows it too). */
const val SWITCH_KNOB_PRESS = 2

enum class SwitchForm(val wire: String) {
    GROUP("group"),
    KNOB("knob"),
}

fun switchForm(obj: ScreenObject): SwitchForm =
    if (obj.type == "switch") SwitchForm.KNOB else SwitchForm.GROUP

/** How loud the chosen state is: the colour itself, or the colour halfway to the background. */
fun switchIsTonal(obj: ScreenObject): Boolean =
    (obj.properties["switchStyle"] as? JsonPrimitive)?.contentOrNull == "tonal"

/** The one colour the author sets; the palette's own when there is none. */
fun switchColorOf(obj: ScreenObject): String {
    val color = (obj.properties["switchColor"] as? JsonPrimitive)?.contentOrNull
    return if (color != null && color.trim().isNotEmpty()) color else CONTROL_FILL
}

/**
 * Whether this state counts as "switched on" - which is what makes the knob
 * form draw in colour rather than quietly.
 *
 * `showAsOn` was called `showMarker` when it decided whether the old marker
 * bar was drawn. There is no bar any more, but old projects are read as they
 * are - the van's are full of them - so the old name still counts.
 */
fun switchStateIsOn(state: JsonObject): Boolean {
    (state["showAsOn"] as? JsonPrimitive)?.booleanOrNull?.let { return it }
    (state["showMarker"] as? JsonPrimitive)?.booleanOrNull?.let { return it }
    return false
}

/**
 * The corner radius of a container or a button in it: a pill when it is
 * clearly wider than tall, a rounded square otherwise - a segment as tall as
 * it is wide would come out an oval, or a circle.
 */
fun switchCorner(w: Int, h: Int): Int {
    if (w <= 0 || h <= 0) return 0
    if (w >= h * 3 / 2) return h / 2
    return minOf(28, minOf(w, h) / 3)
}

data class SwitchRect(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    /** Corner radius; a pill where it is half the short side. */
    val r: Int,
    /**
     * The radius of the right-hand end, where it differs from [r] - a button
     * in a connected group is round on the outside and barely rounded where
     * it faces its neighbour. Null means both ends are [r].
     */
    val rRight: Int? = null,
)

/** What a Switch is painted with. Everything follows from its one colour. */
data class SwitchLook(
    /** The container behind the group, and the track of a switch that is off. */
    val surface: String,
    /** An outline for that surface, where it would otherwise be invisible. */
    val surfaceOutline: String?,
    /** The chosen state's own pill, and the track of a switch that is on. */
    val chosen: String,
    /** Ink on the chosen one. */
    val onChosen: String,
    /** Ink on the surface. */
    val onSurface: String,
    /** The ring that says "asked for, not confirmed". */
    val ring: String,
)

fun switchLook(obj: ScreenObject, background: String?): SwitchLook {
    val color = switchColorOf(obj)
    val ground = if (background.isNullOrEmpty() || background == "transparent") "#ffffff" else background
    // The same tint the slider's empty track and a tonal button take, so the
    // three read as one family.
    val tint = levelTrackLook(color, ground)
    val surface = blendColors(ground, color, 25)
    val framed = surface.lowercase() == ground.lowercase()
    val quiet = if (tint.framed) color else tint.track
    val chosen = if (switchIsTonal(obj)) quiet else color
    return SwitchLook(
        surface = surface,
        surfaceOutline = if (framed) color else null,
        chosen = chosen,
        onChosen = onColorFor(chosen),
        onSurface = onColorFor(surface),
        ring = color,
    )
}

/** The track and the knob of the switch form, in the state it is currently in. */
data class SwitchKnobLook(
    val track: String,
    val trackOutline: String?,
    val knob: String,
    val onKnob: String,
)

fun switchKnobLook(obj: ScreenObject, background: String?, on: Boolean): SwitchKnobLook {
    val look = switchLook(obj, background)
    val color = switchColorOf(obj)
    val ground = if (background.isNullOrEmpty() || background == "transparent") "#ffffff" else background
    val tint = levelTrackLook(color, ground)
    val quiet = if (tint.framed) color else tint.track
    if (on) return SwitchKnobLook(look.chosen, null, look.onChosen, look.chosen)
    // Off is the quiet pair: a pale track with an outline, and the knob in
    // the tint - "grau", without a grey that no palette here has.
    return SwitchKnobLook(look.surface, quiet, quiet, onColorFor(quiet))
}

/** The container of a connected button group: the object's own rectangle, as a pill. */
fun switchContainer(obj: ScreenObject): SwitchRect {
    val x = obj.x.toInt()
    val y = obj.y.toInt()
    val w = maxOf(0, obj.width.toInt())
    val h = maxOf(0, obj.height.toInt())
    return SwitchRect(x, y, w, h, switchCorner(w, h))
}

/**
 * The buttons in the container, tiling it exactly: each starts where the last
 * one ended, so no seam appears and no segment is a pixel wider than its
 * neighbour by accident.
 *
 * The buttons stand 2 apart, the ends of the group are fully round, and where
 * two buttons face each other the corner is small. Each button deciding its
 * own radius from its own box was the fault reported on 2026-09-21: a
 * container 92 wide and 48 tall is a pill while each of its two buttons is
 * about 43 by 43 and so, by the same rule, a rounded square - a rectangle
 * sitting inside a pill, visibly squashed. Handing the button the container's
 * radius instead would be worse: at 43 by 43 it clamps to a circle. It is the
 * small inner corner that makes the round outer one safe.
 */
fun switchSegments(obj: ScreenObject, count: Int): List<SwitchRect> {
    val box = switchContainer(obj)
    val innerX = box.x + SWITCH_PAD
    val innerY = box.y + SWITCH_PAD
    val innerW = maxOf(0, box.w - 2 * SWITCH_PAD)
    val innerH = maxOf(0, box.h - 2 * SWITCH_PAD)
    val n = maxOf(1, count)
    val outer = maxOf(0, box.r - SWITCH_PAD)
    val inner = minOf(SWITCH_INNER_CORNER, innerH / 2)
    return (0 until n).map { i ->
        val from = innerX + innerW * i / n + if (i == 0) 0 else SWITCH_BUTTON_GAP
        val to = innerX + innerW * (i + 1) / n
        SwitchRect(
            x = from,
            y = innerY,
            w = maxOf(0, to - from),
            h = innerH,
            r = if (i == 0) outer else inner,
            rRight = if (i == n - 1) outer else inner,
        )
    }
}

/** Which segment a finger at [x] is on. */
fun switchSegmentAt(obj: ScreenObject, count: Int, x: Double): Int {
    val segments = switchSegments(obj, count)
    for (i in segments.indices) {
        val seg = segments[i]
        if (x < seg.x + seg.w || i == segments.size - 1) return i
    }
    return 0
}

/** The track of the switch form: two thirds of the object's height, at its left edge. */
fun switchTrack(obj: ScreenObject, count: Int): SwitchRect {
    val x = obj.x.toInt()
    val y = obj.y.toInt()
    val h = maxOf(0, obj.height.toInt())
    val trackH = maxOf(6, h * 2 / 3)
    val pad = maxOf(1, trackH / 8)
    val knob = maxOf(2, trackH - 2 * pad)
    val n = maxOf(1, count)
    val wanted = 2 * pad + knob + (n - 1) * knob
    val w = minOf(wanted, maxOf(0, obj.width.toInt()))
    return SwitchRect(x, y + (h - trackH) / 2, w, trackH, trackH / 2)
}

/** The knob's circle, at slot [index]. */
data class SwitchKnob(val cx: Int, val cy: Int, val r: Int)

fun switchKnob(obj: ScreenObject, count: Int, index: Int, pressed: Boolean = false): SwitchKnob {
    val track = switchTrack(obj, count)
    val pad = maxOf(1, track.h / 8)
    val knob = maxOf(2, track.h - 2 * pad)
    val slot = maxOf(0, minOf(maxOf(1, count) - 1, index))
    return SwitchKnob(
        cx = track.x + pad + slot * knob + knob / 2,
        cy = track.y + track.h / 2,
        r = knob / 2 + if (pressed) SWITCH_KNOB_PRESS else 0,
    )
}

/** Which slot a finger at [x] means; -1 where it is past the track, on the label. */
fun switchSlotAt(obj: ScreenObject, count: Int, x: Double): Int {
    val track = switchTrack(obj, count)
    if (x < track.x || x > track.x + track.w) return -1
    val pad = maxOf(1, track.h / 8)
    val knob = maxOf(2, track.h - 2 * pad)
    val first = track.x + pad + knob / 2
    val slot = Math.round((x - first) / knob).toInt()
    return maxOf(0, minOf(maxOf(1, count) - 1, slot))
}

/** What is left of the object beside the track: where the state's label goes. */
fun switchLabelBox(obj: ScreenObject, count: Int): SwitchRect {
    val track = switchTrack(obj, count)
    val x = track.x + track.w + SWITCH_GAP
    val right = obj.x.toInt() + obj.width.toInt()
    return SwitchRect(x, obj.y.toInt(), maxOf(0, right - x), maxOf(0, obj.height.toInt()), 0)
}

/**
 * Where a state's icon and label go inside a rectangle: side by side when it
 * is wider than tall (a strip), the icon above the label when it is not (a
 * tile). The rectangle decides, so nothing has to be set per object.
 */
data class SwitchContent(
    val icon: SwitchRect?,
    val textX: Int,
    val baseline: Int,
    val beside: Boolean,
)

fun switchContent(
    rect: SwitchRect,
    metrics: LevelFontMetrics,
    hasIcon: Boolean,
    textWidth: Int,
): SwitchContent {
    val line = metrics.ascent + metrics.descent
    val iconSize = if (hasIcon) maxOf(1, metrics.capHeight) else 0
    if (rect.w >= rect.h) {
        val group = (if (hasIcon) iconSize + SWITCH_GAP else 0) + textWidth
        val left = rect.x + maxOf(0, (rect.w - group) / 2)
        val baseline = rect.y + (rect.h - line) / 2 + metrics.ascent
        return SwitchContent(
            icon = if (hasIcon) SwitchRect(left, baseline - iconSize, iconSize, iconSize, 0) else null,
            textX = if (hasIcon) left + iconSize + SWITCH_GAP else left,
            baseline = baseline,
            beside = true,
        )
    }
    val block = (if (hasIcon) iconSize + 6 else 0) + line
    val top = rect.y + maxOf(0, (rect.h - block) / 2)
    val baseline = top + (if (hasIcon) iconSize + 6 else 0) + metrics.ascent
    return SwitchContent(
        icon = if (hasIcon) SwitchRect(rect.x + (rect.w - iconSize) / 2, top, iconSize, iconSize, 0) else null,
        textX = rect.x + maxOf(0, (rect.w - textWidth) / 2),
        baseline = baseline,
        beside = false,
    )
}

/**
 * The object's font, for whatever has to be laid out from it.
 *
 * The fallback is a flat 14 rather than the object's own `fontSize` - unlike
 * a level indicator, a Switch has never had one.
 */
fun switchFontMetrics(obj: ScreenObject, fonts: List<FontEntry>?): LevelFontMetrics {
    val font = fonts?.firstOrNull { it.id == (obj.properties["fontId"] as? JsonPrimitive)?.contentOrNull }
    return fontMetricsOf(font, 14)
}
