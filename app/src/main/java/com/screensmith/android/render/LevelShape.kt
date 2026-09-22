package com.screensmith.android.render

import com.screensmith.android.data.FontEntry
import com.screensmith.android.data.ScreenObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The geometry of a level indicator, as whole pixels - this repo's copy of
 * the designer's `lib/level-shape.ts`.
 *
 * Four renderers have to agree on it to the pixel: the designer, the
 * firmware's ColorScreenRenderer.cpp, the designer's reference page, and this
 * app. So it is written the way the other three are - integer arithmetic
 * only, and truncation toward zero wherever a fraction appears, because C++
 * integer division truncates that way and that is the side that cannot be
 * changed. Kotlin's `Int` division and `Double.toInt()` both truncate toward
 * zero, which is what `Math.trunc` does in the TypeScript original.
 *
 * Nothing here touches Android: no Canvas, no Color, no density. That is what
 * lets [com.screensmith.android.render.LevelShapeGoldenTest] hold it to the
 * designer's own numbers on any machine, in milliseconds, without a phone -
 * see `hil/android/fixtures/build-level-golden.js` in the designer repo for
 * how those numbers are recorded. [LevelIndicatorView] turns what is computed
 * here into pixels and nothing else.
 *
 * Why it looks like this at all: docs/2026-09-19-slider-look.md over there.
 */

/** A rectangle to fill, with the corner radius it is drawn with. */
data class LevelRect(val x: Int, val y: Int, val w: Int, val h: Int, val r: Int)

/** Which of the two colours a run of track takes. */
enum class LevelRole(val wire: String) {
    FILL("fill"),
    TRACK("track"),
}

/**
 * One run of track, which colour it takes, and which of its two ends is the
 * track's own outer end.
 *
 * Only the outer ends are rounded. An inner end - where the fill meets the
 * tinted part, or where the handle's gap cuts the run - is square. Rounding
 * both ends made two runs curve away from each other and left a notch in the
 * middle of a tank gauge that looked like a handle nobody could grab.
 */
data class LevelSegment(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val r: Int,
    val role: LevelRole,
    /** The low-coordinate end (left, or top) is the track's own end. */
    val roundStart: Boolean,
    /** The high-coordinate end (right, or bottom) is the track's own end. */
    val roundEnd: Boolean,
)

/** The vertical measure of the object's font: everything the header line is built from. */
data class LevelFontMetrics(
    /** Baseline to the top of the line. */
    val ascent: Int,
    /** Baseline to the bottom of the line - where descenders and brackets end. */
    val descent: Int,
    /** How tall a capital stands on the baseline. The icon is this tall. */
    val capHeight: Int,
)

/** Everything the object's rectangle is divided into. */
data class LevelLayout(
    /** The whole header line, or null when there is none. */
    val header: LevelRect?,
    /**
     * The row every piece of text stands on - name and numbers alike, whatever
     * size they are in - and the icon's foot.
     */
    val baseline: Int,
    /** Square, a capital's height, standing on the baseline. */
    val icon: LevelRect?,
    /** The header's text run: from after the icon to the object's right edge. */
    val text: LevelRect?,
    /** The number's own column beside a bar that has no header. */
    val value: LevelRect?,
    /** What is left over for the bar once the header or the number has its room. */
    val bar: LevelRect,
    /** The band of [bar] the bar actually takes, across it. */
    val slot: LevelRect,
    /** The track itself. */
    val track: LevelRect,
)

/** What the unfilled part of the track looks like; see [levelTrackLook]. */
data class LevelTrackLook(val track: String, val framed: Boolean)

/** Material's 16, when the author has set no thickness. */
const val LEVEL_DEFAULT_THICKNESS = 16

/**
 * The padding along the bar's own direction. Deliberately still the 4 this bar
 * has always had: it is what [levelPercentFromPoint] inverts, so widening it
 * would move every value's position and silently change what every existing
 * calibration means.
 */
const val LEVEL_PADDING_ALONG = 4

/** The slot between the header's parts, and between the bar and its number. */
const val LEVEL_GAP = 6

/**
 * The empty row between the header line and the bar. Without it a letter with
 * a descender stood on the handle.
 */
const val LEVEL_HEADER_GAP = 1

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/**
 * A property read as a number, the way the designer's `Number(...)` reads it:
 * a JSON number or a string holding one, and nothing else.
 */
private fun JsonObject.number(key: String): Double? = text(key)?.trim()?.toDoubleOrNull()

private fun JsonObject.nonBlank(key: String): String? = text(key)?.takeIf { it.trim().isNotEmpty() }

/** How thick the track is, across the bar, in pixels. */
fun levelThickness(obj: ScreenObject): Int {
    val t = obj.properties.number("barThickness")?.toInt() ?: return LEVEL_DEFAULT_THICKNESS
    return if (t > 0) t else LEVEL_DEFAULT_THICKNESS
}

/**
 * Whether the object can ever draw a handle: a finger can set it, or it
 * reports a target. The same two things the renderer draws one for.
 */
fun levelHasHandle(obj: ScreenObject): Boolean =
    obj.properties.nonBlank("writeTopic") != null || obj.properties.nonBlank("setpointTopic") != null

/** How long the handle is, across the bar: Material's 44 on its 16, kept as that ratio. */
fun levelHandleLength(thickness: Int): Int = thickness * 11 / 4

/** True for a bar whose long axis runs up and down. */
fun levelIsVertical(obj: ScreenObject): Boolean {
    val direction = levelDirection(obj)
    return direction == "bottom-to-top" || direction == "top-to-bottom"
}

/** True when the fill grows from the right or from the bottom. */
fun levelFillsFromEnd(obj: ScreenObject): Boolean {
    val direction = levelDirection(obj)
    return direction == "right-to-left" || direction == "bottom-to-top"
}

fun levelDirection(obj: ScreenObject): String =
    obj.properties.text("barDirection")?.takeIf { it.isNotEmpty() } ?: "left-to-right"

/**
 * The object's `fontSize`, for an object that has no project font to take its
 * measure from. Only a fallback: with a font chosen, every measurement comes
 * from that font ([levelFontMetrics]). The font picker does not touch
 * `fontSize`, so a project switched to a 24 px face still says 12 here.
 */
fun levelFontSize(obj: ScreenObject): Int {
    val size = obj.properties.number("fontSize")?.toInt() ?: return 14
    return if (size > 0) size else 14
}

/** The name shown on the header line, or "" when the object has none. */
fun levelName(obj: ScreenObject): String = obj.properties.text("label")?.trim() ?: ""

/** Whether an icon sits at the head of the line. */
fun levelHasIcon(obj: ScreenObject): Boolean = obj.properties.nonBlank("iconAssetId") != null

/** Whether a number is shown at all. */
fun levelShowsNumber(obj: ScreenObject): Boolean =
    (obj.properties.text("displayValue")?.takeIf { it.isNotEmpty() } ?: "value") != "none"

/**
 * Whether the object can ever show a *second*, measured number beside the
 * commanded one - which is to say, whether it has a commanded value at all.
 */
fun levelShowsSub(obj: ScreenObject): Boolean = levelShowsNumber(obj) && levelHasHandle(obj)

fun levelFontMetrics(obj: ScreenObject, fonts: List<FontEntry>?): LevelFontMetrics {
    val font = fonts?.firstOrNull { it.id == obj.properties.text("fontId") }
    return fontMetricsOf(font, levelFontSize(obj))
}

/**
 * The same, for a font already in hand; `fallbackSize` when there is none.
 *
 * The three branches are the designer's, with one substitution: over there a
 * font knows its own `format`, here a TTF is the entry that carries a file
 * ([FontEntry.path]) - the export writes a path for TTF entries and nothing
 * else (lib/android-export.ts).
 *
 * A TTF's ascent is deliberately NOT the DDF's declared `ascent`: the designer
 * places a TTF's baseline from what the browser measured when the font was
 * added (`baselineOffset`), and falls back to four fifths of the size when
 * nothing measured it. Reading the DDF's number here instead would put the
 * header's text one or two rows off what the reference image shows.
 */
fun fontMetricsOf(font: FontEntry?, fallbackSize: Int): LevelFontMetrics {
    if (font == null) {
        // No project font: the canvas draws in a browser font at `fontSize`,
        // whose line is about the size and whose capitals are about 0.7 of it.
        val ascent = fallbackSize * 4 / 5
        return LevelFontMetrics(ascent, maxOf(1, fallbackSize - ascent), fallbackSize * 7 / 10)
    }
    val size = maxOf(1, font.size)
    if (font.path != null) {
        val measured = font.baselineOffset ?: (size * 4.0 / 5.0)
        val ascent = maxOf(1, Math.round(measured).toInt())
        return LevelFontMetrics(ascent, maxOf(1, size - ascent), ascent)
    }
    // A BDF entry. The designer reads CAP_HEIGHT out of the font file itself;
    // the export ships no BDF bytes to this platform (they have no browser
    // equivalent either), so a capital is as tall as the ascent - which is
    // what the designer's own `min(capHeight, ascent)` falls back to when the
    // property is missing.
    val ascent = font.ascent ?: (size * 4 / 5)
    val descent = font.descent ?: maxOf(1, size - ascent)
    return LevelFontMetrics(ascent, descent, ascent)
}

/** One line of the font: ascent plus descent. */
fun levelLineHeight(metrics: LevelFontMetrics): Int = metrics.ascent + metrics.descent

/**
 * One digit, as all four renderers agree to guess it: 0.62 of the line height.
 *
 * A guess rather than a measurement, and only where it decides where the bar
 * ends - the number column beside a bar with no header, which
 * [levelPercentFromPoint] has to know about to turn a finger into a value. On
 * the header line nothing is guessed: the numbers are measured there.
 */
fun levelDigitWidth(lineHeight: Int): Int {
    val w = lineHeight * 62 / 100
    return if (w < 1) 1 else w
}

/**
 * How tall the header line is - 0 when there is neither a name nor an icon.
 *
 * Exactly one line of the font, so a letter is never cut off at either end. The
 * object does not grow by itself: the rectangle is what the author drags.
 */
fun levelHeaderHeight(obj: ScreenObject, fonts: List<FontEntry>?): Int {
    if (levelName(obj).isEmpty() && !levelHasIcon(obj)) return 0
    val wanted = levelLineHeight(levelFontMetrics(obj, fonts))
    return maxOf(0, minOf(wanted, obj.height.toInt()))
}

/** The number column beside a bar with no header. Five digits, capped at 40 %. */
fun levelValueWidth(obj: ScreenObject, fonts: List<FontEntry>?): Int {
    if (!levelShowsNumber(obj)) return 0
    val wanted = levelDigitWidth(levelLineHeight(levelFontMetrics(obj, fonts))) * 5
    val cap = obj.width.toInt() * 2 / 5
    return maxOf(0, minOf(wanted, cap))
}

fun levelLayout(obj: ScreenObject, fonts: List<FontEntry>?): LevelLayout {
    val x = obj.x.toInt()
    val y = obj.y.toInt()
    val w = obj.width.toInt()
    val h = obj.height.toInt()
    val vertical = levelIsVertical(obj)
    val metrics = levelFontMetrics(obj, fonts)
    val lineH = levelLineHeight(metrics)
    val headerH = levelHeaderHeight(obj, fonts)

    var header: LevelRect? = null
    var baseline = y + metrics.ascent
    var icon: LevelRect? = null
    var text: LevelRect? = null
    var value: LevelRect? = null
    val barX = x
    var barY = y
    var barW = w
    var barH = h

    if (headerH > 0) {
        header = LevelRect(x, y, w, headerH, 0)
        var left = x
        if (levelHasIcon(obj)) {
            // As tall as a capital and standing on the same baseline, so it
            // reads as a letter of the name rather than a picture beside it.
            val size = maxOf(1, minOf(metrics.capHeight, w / 4))
            icon = LevelRect(left, baseline - size, size, size, 0)
            left = icon.x + size + LEVEL_GAP
        }
        if (x + w - left > 0) text = LevelRect(left, y, x + w - left, headerH, 0)
        barY = y + headerH + LEVEL_HEADER_GAP
        barH = h - headerH - LEVEL_HEADER_GAP
    } else if (levelShowsNumber(obj)) {
        // No header: the number goes at the far end of the bar's own axis.
        // Right for a horizontal bar whichever way it fills, so that a column
        // of bars lines up regardless of their directions.
        val column: LevelRect
        if (vertical) {
            val rowH = minOf(lineH, h * 2 / 5)
            column = LevelRect(x, y + h - rowH, w, rowH, 0)
            barH = h - rowH - LEVEL_GAP
        } else {
            val valueW = levelValueWidth(obj, fonts)
            column = LevelRect(x + w - valueW, y, valueW, h, 0)
            barW = w - valueW - LEVEL_GAP
        }
        value = column
        // The line centred in its column.
        baseline = column.y + (column.h - lineH) / 2 + metrics.ascent
    }

    val bar = LevelRect(barX, barY, maxOf(0, barW), maxOf(0, barH), 0)
    val thickness = levelThickness(obj)
    val across = if (vertical) bar.w else bar.h
    val wanted = if (levelHasHandle(obj)) levelHandleLength(thickness) else thickness
    val size = maxOf(0, minOf(wanted, across))
    val offset = if (!vertical && header != null) 0 else (across - size) / 2
    val slot = if (vertical) {
        LevelRect(bar.x + offset, bar.y, size, bar.h, 0)
    } else {
        LevelRect(bar.x, bar.y + offset, bar.w, size, 0)
    }
    return LevelLayout(header, baseline, icon, text, value, bar, slot, trackInside(slot, vertical, thickness))
}

/** The track's own box: inset by 4 along the bar, and centred in the slot across it. */
fun levelTrackRect(obj: ScreenObject, fonts: List<FontEntry>?): LevelRect = levelLayout(obj, fonts).track

private fun trackInside(slot: LevelRect, vertical: Boolean, thickness: Int): LevelRect {
    val across = if (vertical) slot.w else slot.h
    val t = minOf(thickness, across)
    val padAcross = (across - t) / 2
    val padAlong = LEVEL_PADDING_ALONG
    val x = slot.x + if (vertical) padAcross else padAlong
    val y = slot.y + if (vertical) padAlong else padAcross
    val w = if (vertical) t else slot.w - 2 * padAlong
    val h = if (vertical) slot.h - 2 * padAlong else t
    // A pill: the radius is half the short side. fillRoundRect clamps it again
    // for a run shorter than it is thick, which is what makes a nearly empty
    // track end in a half-circle rather than a wedge.
    return LevelRect(x, y, w, h, minOf(w, h) / 2)
}

/** How wide the handle is: Material's 4 dp on a 44 dp row. */
fun levelHandleWidth(across: Int): Int {
    val w = across / 11
    return if (w < 3) 3 else w
}

/** The slot of background left free on each side of the handle: Material's 6 on 44. */
fun levelHandleGap(across: Int): Int {
    val gap = across * 3 / 22
    return if (gap < 2) 2 else gap
}

/**
 * Where along the track a percentage falls, in pixels - the one place the
 * value-to-position mapping lives. [levelPercentFromPoint] inverts exactly
 * this, so a finger and the picture cannot disagree.
 */
fun levelEdgeFor(track: LevelRect, vertical: Boolean, fromEnd: Boolean, percent: Double): Int {
    val span = if (vertical) track.h else track.w
    val along = (span * percent / 100.0).toInt()
    if (vertical) return if (fromEnd) track.y + track.h - along else track.y + along
    return if (fromEnd) track.x + track.w - along else track.x + along
}

/**
 * The handle: a pill across the slot, standing out of the track on both sides.
 * The overhang is what says "a thing lying on top".
 *
 * Clamped to stay inside the track's run rather than the track being inset to
 * make room: insetting would move every value's position and change what every
 * existing calibration means.
 */
fun levelHandleRect(obj: ScreenObject, percent: Double, fonts: List<FontEntry>?): LevelRect {
    val vertical = levelIsVertical(obj)
    val layout = levelLayout(obj, fonts)
    val slot = layout.slot
    val track = layout.track
    val across = if (vertical) slot.w else slot.h
    // Never more than a third of the run it slides along. Without that, a bar
    // far wider than it is long gets a handle longer than its own track and the
    // clamp below has no room to work in.
    val span = if (vertical) track.h else track.w
    val thickness = maxOf(2, minOf(levelHandleWidth(across), span / 3))
    val edge = levelEdgeFor(track, vertical, levelFillsFromEnd(obj), percent)
    val r = thickness / 2

    if (vertical) {
        val y = clamp(edge - thickness / 2, track.y, track.y + track.h - thickness)
        return LevelRect(slot.x, y, slot.w, thickness, r)
    }
    val x = clamp(edge - thickness / 2, track.x, track.x + track.w - thickness)
    return LevelRect(x, slot.y, thickness, slot.h, r)
}

/**
 * The track, cut into the runs that actually get painted: filled up to the
 * value, tinted beyond it, and nothing at all where the handle and its gap sit.
 *
 * Runs of zero or negative length are dropped, so a bar at 0 % simply has no
 * filled run and a handle at the very end leaves no tail behind it.
 */
fun levelSegments(
    obj: ScreenObject,
    fillPercent: Double,
    handle: LevelRect?,
    fonts: List<FontEntry>?,
): List<LevelSegment> {
    val vertical = levelIsVertical(obj)
    val layout = levelLayout(obj, fonts)
    val slot = layout.slot
    val track = layout.track
    val fromEnd = levelFillsFromEnd(obj)
    val edge = levelEdgeFor(track, vertical, fromEnd, fillPercent)

    val start = if (vertical) track.y else track.x
    val end = start + if (vertical) track.h else track.w
    // Which side of `edge` is filled depends on which end the bar grows from.
    val runs = if (fromEnd) {
        listOf(Triple(start, edge, LevelRole.TRACK), Triple(edge, end, LevelRole.FILL))
    } else {
        listOf(Triple(start, edge, LevelRole.FILL), Triple(edge, end, LevelRole.TRACK))
    }

    val across = if (vertical) slot.w else slot.h
    val gap = levelHandleGap(across)
    val cutA = if (handle != null) (if (vertical) handle.y else handle.x) - gap else 0
    val cutB = if (handle != null) (if (vertical) handle.y + handle.h else handle.x + handle.w) + gap else 0

    val out = mutableListOf<LevelSegment>()
    fun push(a: Int, b: Int, role: LevelRole) {
        if (b - a <= 0) return
        val roundStart = a == start
        val roundEnd = b == end
        out += if (vertical) {
            LevelSegment(track.x, a, track.w, b - a, track.r, role, roundStart, roundEnd)
        } else {
            LevelSegment(a, track.y, b - a, track.h, track.r, role, roundStart, roundEnd)
        }
    }

    for ((a, b, role) in runs) {
        if (handle == null || cutB <= a || cutA >= b) {
            push(a, b, role)
            continue
        }
        push(a, minOf(b, cutA), role)
        push(maxOf(a, cutB), b, role)
    }
    return out
}

/**
 * The inside of a framed run of track: the run itself, taken in by one pixel.
 *
 * Across the bar on both sides, and along it only at an end that is the
 * track's own (a round one). At an end that was cut the frame is left open, so
 * it is one path that stops straight where the handle begins rather than a
 * string of small pills that each close themselves with a rounded cap.
 *
 * Null when the run is too short or too thin to have an inside.
 */
fun levelFrameInner(seg: LevelSegment, vertical: Boolean): LevelSegment? {
    val a0 = if (seg.roundStart) 1 else 0
    val a1 = if (seg.roundEnd) 1 else 0
    val inner = if (vertical) {
        seg.copy(x = seg.x + 1, y = seg.y + a0, w = seg.w - 2, h = seg.h - a0 - a1, r = maxOf(0, seg.r - 1))
    } else {
        seg.copy(x = seg.x + a0, y = seg.y + 1, w = seg.w - a0 - a1, h = seg.h - 2, r = maxOf(0, seg.r - 1))
    }
    return if (inner.w > 0 && inner.h > 0) inner else null
}

/** The whole track as one run, for a bar that has heard no value yet. */
fun levelEmptyTrack(obj: ScreenObject, fonts: List<FontEntry>?): LevelSegment {
    val track = levelLayout(obj, fonts).track
    return LevelSegment(track.x, track.y, track.w, track.h, track.r, LevelRole.TRACK, roundStart = true, roundEnd = true)
}

/**
 * Where a finger is, as a percentage of the bar.
 *
 * Measured against the TRACK, not the object, so the picture and the touch
 * cannot drift: the bar no longer fills the rectangle it is given - a header
 * line takes room off the top and the number takes room off the end.
 * Coordinates are the object's own, absolute ones.
 */
fun levelPercentFromPoint(obj: ScreenObject, x: Double, y: Double, fonts: List<FontEntry>?): Double {
    val track = levelTrackRect(obj, fonts)
    val w = maxOf(1, track.w).toDouble()
    val h = maxOf(1, track.h).toDouble()
    val along = when (levelDirection(obj)) {
        "right-to-left" -> (track.x + track.w - x) / w
        "bottom-to-top" -> (track.y + track.h - y) / h
        "top-to-bottom" -> (y - track.y) / h
        else -> (x - track.x) / w
    }
    return (along * 100).coerceIn(0.0, 100.0)
}

/**
 * What the unfilled part of the track looks like, worked out from the two
 * colours the author already has: the bar's colour and the screen's
 * background. The track is halfway between them, one channel at a time:
 *
 *     track = background + trunc((fill - background) / 2)
 *
 * `trunc` rather than `round` because C++'s integer division truncates toward
 * zero, and toward zero of the *difference* is toward the background - the
 * direction the author asked for ("bei Rundungen immer Richtung Hintergrund").
 *
 * `framed` says the track came out the same as the background. A track you
 * cannot see is not a track, so it gets an outline in the bar's colour
 * ([levelFrameInner]) instead of a body.
 *
 * The designer runs the screen's colour depth over each of the three colours;
 * this platform declares 24 bit (DdfBuilder), where that step is the identity,
 * so there is nothing to quantise here. A port of this file to a 1-bit panel
 * would have to put it back - which is the whole point of the black-on-white
 * case the designer's comment describes.
 */
fun levelTrackLook(fillColor: String, backgroundColor: String): LevelTrackLook {
    val f = colorChannels(fillColor)
    val b = colorChannels(backgroundColor)
    // A colour that cannot be read is not guessed at: the track becomes the
    // background, which puts up the outline and leaves the bar visible.
    if (f == null || b == null) return LevelTrackLook(backgroundColor, true)
    val mixed = intArrayOf(
        b[0] + (f[0] - b[0]) / 2,
        b[1] + (f[1] - b[1]) / 2,
        b[2] + (f[2] - b[2]) / 2,
    )
    val track = colorHex(mixed)
    val t = colorChannels(track)
    return LevelTrackLook(track, t != null && t[0] == b[0] && t[1] == b[1] && t[2] == b[2])
}

/**
 * What colour the handle takes - which is what says whether a finger can move
 * it (the designer's `handleColourFor`, docs/2026-09-22-arc-look.md).
 *
 * A handle a finger can move keeps the bar's own colour: handle and filled
 * track are one object that the gap separates. One that only reports the
 * installation's target takes the track's quiet colour and steps back - the
 * user, on the picture: "seine dimmed farbe sagt mir dass ich ihn nicht
 * bewegen kann".
 *
 * On a framed track there is no quiet colour to step back into, so every
 * handle stays solid there.
 */
fun handleColourFor(obj: ScreenObject, fillColor: String, look: LevelTrackLook): String {
    val settable = obj.properties.nonBlank("writeTopic") != null
    return if (settable || look.framed) fillColor else look.track
}

private fun clamp(v: Int, lo: Int, hi: Int): Int {
    if (hi < lo) return lo
    if (v < lo) return lo
    if (v > hi) return hi
    return v
}
