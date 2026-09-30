package com.schaltli.android.ui.objects

import android.graphics.Canvas as NativeCanvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.schaltli.android.data.FontEntry
import com.schaltli.android.data.Project
import com.schaltli.android.data.ScreenObject
import com.schaltli.android.render.LevelRect
import com.schaltli.android.render.LevelRole
import com.schaltli.android.render.LevelSegment
import com.schaltli.android.render.LevelTrackLook
import com.schaltli.android.render.PaintedPill
import com.schaltli.android.render.PillGlow
import com.schaltli.android.render.Rgb565
import com.schaltli.android.render.glowLevelFromDistance2
import com.schaltli.android.render.gradient565
import com.schaltli.android.render.levelEdgeFor
import com.schaltli.android.render.levelFillsFromEnd
import com.schaltli.android.render.levelGlowPx
import com.schaltli.android.render.segmentDistance2
import com.schaltli.android.render.toRgb565
import com.schaltli.android.render.PILL_SUBPIXEL_SCALE
import com.schaltli.android.render.PillBand
import com.schaltli.android.render.insidePillTip
import com.schaltli.android.render.levelIsSettableType
import com.schaltli.android.render.levelPointerBand
import com.schaltli.android.render.drawPills
import com.schaltli.android.render.fillRoundRect
import com.schaltli.android.render.handleColourFor
import com.schaltli.android.render.pillBitmap
import com.schaltli.android.render.levelEmptyTrack
import com.schaltli.android.render.levelFontSize
import com.schaltli.android.render.levelFrameInner
import com.schaltli.android.render.levelHandleRect
import com.schaltli.android.render.levelIsVertical
import com.schaltli.android.render.levelLayout
import com.schaltli.android.render.levelPercentFromPoint
import com.schaltli.android.render.levelSegments
import com.schaltli.android.render.levelTrackPaint
import com.schaltli.android.ui.LocalBundleInstallation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToInt

/**
 * Mirrors the designer's `calculateLevelIndicatorFill` (`render-level-indicator.ts`)
 * exactly. Not private - reused by MqttDataLineView for its own
 * value -> stroke-width-in-px calibration (same {value, barSizePercent}
 * shape and sort/clamp/linear-interpolate mechanism, `barSizePercent`
 * reinterpreted as a pixel width there - see that file's header comment).
 */
/**
 * The same interpolation read the other way: a finger has a position, which is
 * a percentage of the track, and what has to be published is the value that
 * percentage stands for (the designer's docs/2026-09-17-settable-level.md,
 * decision 5, mirroring levelValueFromFill()).
 *
 * A calibration whose percentages fall as the value rises inverts too; a
 * segment whose two percentages are equal has no value to give, so its lower
 * value stands rather than a division by zero.
 */
fun valueForFillPercent(fillPercent: Double, calibrationPoints: List<Pair<Double, Double>>): Double {
    if (calibrationPoints.isEmpty()) return 0.0
    val sorted = calibrationPoints.sortedBy { it.first }
    if (sorted.size == 1) return sorted[0].first

    val first = sorted.first()
    val last = sorted.last()
    val rising = last.second >= first.second
    if (if (rising) fillPercent <= first.second else fillPercent >= first.second) return first.first
    if (if (rising) fillPercent >= last.second else fillPercent <= last.second) return last.first

    for (i in 0 until sorted.size - 1) {
        val p1 = sorted[i]
        val p2 = sorted[i + 1]
        val low = minOf(p1.second, p2.second)
        val high = maxOf(p1.second, p2.second)
        if (fillPercent < low || fillPercent > high) continue
        if (p2.second == p1.second) return p1.first
        val ratio = (fillPercent - p1.second) / (p2.second - p1.second)
        return p1.first + ratio * (p2.first - p1.first)
    }
    return first.first
}

/** Snaps a value to a step, so a finger reports 35 and 40 rather than 37. */
fun snapToStep(value: Double, step: Double): Double =
    if (step <= 0) value else Math.round(value / step) * step

/** What a settable level publishes: whole numbers without a decimal point. */
fun formatSetValue(value: Double): String {
    if (kotlin.math.abs(value - Math.round(value)) < 0.001) return Math.round(value).toString()
    return value.toString().trimEnd('0').trimEnd('.')
}

fun calculateFillPercent(value: Double, calibrationPoints: List<Pair<Double, Double>>): Double {
    if (calibrationPoints.isEmpty()) return 0.0
    val sorted = calibrationPoints.sortedBy { it.first }

    if (value <= sorted.first().first) return sorted.first().second
    if (value >= sorted.last().first) return sorted.last().second

    for (i in 0 until sorted.size - 1) {
        val (v1, p1) = sorted[i]
        val (v2, p2) = sorted[i + 1]
        if (value in v1..v2) {
            val ratio = (value - v1) / (v2 - v1)
            return p1 + ratio * (p2 - p1)
        }
    }
    return 0.0
}

/**
 * Not private - ArcLevelView reads the same {value, barSizePercent}
 * calibration list, because a ring and a bar are the same reading shown
 * two ways and the designer maps them with one function too.
 */
fun parseCalibrationPoints(props: JsonObject): List<Pair<Double, Double>> {
    val array = props["calibrationPoints"] as? JsonArray ?: return listOf(0.0 to 0.0, 100.0 to 100.0)
    val points = array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val value = (obj["value"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        val barSizePercent = (obj["barSizePercent"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        value to barSizePercent
    }
    return points.ifEmpty { listOf(0.0 to 0.0, 100.0 to 100.0) }
}

/**
 * A level is settable when it has somewhere to write to. Mirrors
 * `isSettableLevel` in the designer's render-level-indicator.ts - and with it
 * the reason a bar that can be set rests its handle on its own value: with
 * nothing outstanding, what was last commanded IS what is reported, and a
 * handle sitting on the fill's edge is what says "you can move this".
 */
internal fun isSettableLevel(obj: ScreenObject): Boolean =
    // Only a slider or a dial - a bar or a gauge never rests a handle on its
    // value, whatever topics it still carries.
    (obj.type == "slider" || obj.type == "dial") &&
        !obj.properties.stringOrNull("writeTopic").isNullOrBlank()

/** The size the text is drawn at: a TTF's own, else the object's `fontSize`. */
internal fun levelTextSize(obj: ScreenObject, font: FontEntry?): Int =
    if (font?.path != null && font.size > 0) font.size else levelFontSize(obj)

/**
 * A level indicator: a tank gauge, and - with somewhere to write to - the
 * slider that sets one.
 *
 * Everything it is made of comes from
 * [com.schaltli.android.render.LevelShape], this repo's copy of the
 * designer's `lib/level-shape.ts`, which a golden test holds to the designer's
 * own numbers. This file turns those rectangles into pixels and does no
 * geometry of its own - which is the whole reason the port was worth doing:
 * the old version drew a bordered box with the number in the middle of it, a
 * look the designer retired on 2026-09-19 (docs/2026-09-19-slider-look.md),
 * and there was nowhere to put the difference except a HIL percentage.
 *
 * There is no header line: a Bar and a Slider lost their name and icon on
 * 2026-09-29 (designer 84f3fb7), and with them the bracketed measured value
 * that stood beside the commanded one. A label beside a bar is a Text object.
 */
@Composable
fun LevelIndicatorView(
    obj: ScreenObject,
    project: Project,
    rawValue: String,
    // What the marker shows: the installation's setpoint, or what a finger
    // asked of this value and the installation has not answered yet (decision
    // 6c). Empty means the handle rests on the value itself, on a bar that can
    // be set at all.
    rawSetpoint: String = "",
    // What the unfilled track is mixed into: the bar has no background of its
    // own any more, so the screen's is half of its colour (decision 12).
    screenBackgroundColor: String? = null,
    assetFileOf: (String) -> java.io.File = { java.io.File("") },
    // A tap sets the value at the point it landed, on a level with a write
    // topic. Nothing is drawn from here: the marker follows from rawSetpoint,
    // which the caller feeds from the asked value.
    onSetLevel: (markerTopic: String, writeTopic: String, value: String, final: Boolean) -> Unit = { _, _, _, _ -> },
) {
    val props = obj.properties
    val fonts = project.fonts
    val fillColor = props.string("fillColor", "#4CAF50")
    val fillArgb = (parseHexColor(fillColor) ?: Color(0xFF4CAF50)).toArgb()
    // What the control stands on: half of what the track's colour is mixed
    // from, and what the soft edges of every run are mixed into.
    val background = screenBackgroundColor ?: "#ffffff"
    val look = levelTrackPaint(props.stringOrNull("trackColor"), props.stringOrNull("trackEdgeColor"), fillColor, background)
    // Anti-aliased on 24 bit and nowhere else - see Project.colorDepth. Below
    // it the whole-pixel path below runs exactly as it always has.
    val soft = project.colorDepth == "24bit"
    val trackArgb = (parseHexColor(look.track) ?: Color.Transparent).toArgb()
    val edgeArgb = (parseHexColor(look.edge ?: fillColor) ?: Color(0xFF4CAF50)).toArgb()
    val textArgb = (props.stringOrNull("textColor")?.let(::parseHexColor) ?: Color.Black).toArgb()

    val calibration = parseCalibrationPoints(props)
    // Nothing reported yet: the track alone. It is the shape of the control,
    // the way a ring has always drawn itself without a value - while an empty
    // *fill* would claim an empty tank (docs/2026-09-15-live-data.md).
    val noValue = rawValue.isBlank()
    val fillPercent = calculateFillPercent(rawValue.toDoubleOrNull() ?: 0.0, calibration).coerceIn(0.0, 100.0)

    // A bar has no handle, only a pointer, and it points at a target the
    // installation reports - never at a request, which is a finger's, nor at
    // its own value (2026-09-28).
    val settable = levelIsSettableType(obj.type)
    val rawMarker = when {
        noValue -> ""
        !settable && props.stringOrNull("setpointTopic").isNullOrBlank() -> ""
        rawSetpoint.isNotBlank() -> rawSetpoint
        isSettableLevel(obj) -> rawValue
        else -> ""
    }
    val setpointPercent = rawMarker.takeIf { it.isNotBlank() }
        ?.let { calculateFillPercent(it.toDoubleOrNull() ?: 0.0, calibration).coerceIn(0.0, 100.0) }

    val writeTopic = props.stringOrNull("writeTopic") ?: ""
    val markerTopic = props.stringOrNull("setpointTopic")?.takeIf { it.isNotEmpty() }
        ?: props.stringOrNull("topic") ?: ""
    val step = (props["step"] as? JsonPrimitive)?.doubleOrNull ?: 1.0

    val displayValue = props.string("displayValue", "value")
    fun asText(raw: String, percent: Double): String =
        if (displayValue == "percentage") "${percent.roundToInt()}%" else raw
    val commanded = if (setpointPercent != null) asText(rawMarker, setpointPercent) else asText(rawValue, fillPercent)

    val ownFont: FontEntry? = fonts.firstOrNull { it.id == props.stringOrNull("fontId") }
    val installation = LocalBundleInstallation.current
    val ownTypeface = remember(ownFont?.path, installation) { typefaceOf(ownFont, assetFileOf) }

    val density = LocalDensity.current
    val layout = levelLayout(obj, fonts)
    val vertical = levelIsVertical(obj)
    val ox = obj.x.toInt()
    val oy = obj.y.toInt()

    // Only a slider has a handle. A bar shows the same target with a pointer
    // beside the track instead, which leaves the track whole.
    val handle = setpointPercent?.takeIf { settable }?.let { levelHandleRect(obj, it, fonts) }
    val pointer = setpointPercent?.takeIf { !settable }?.let { levelPointerBand(obj, it, fonts) }
    val pointerColor = props.stringOrNull("textColor") ?: "#000000"
    val segments = if (noValue) {
        listOf(levelEmptyTrack(obj, fonts))
    } else {
        levelSegments(obj, fillPercent, handle, fonts)
    }
    // Rasterized once and kept, the way ArcLevelView keeps its ring: the soft
    // path walks sixteen sub-samples of every pixel of the bar against every
    // run of it, and a slider is dragged. Keyed on everything the picture
    // depends on - the two values it shows, the object itself, and what it
    // stands on.
    val pills = if (!soft) null else remember(
        obj.id, obj.width, obj.height, props, fonts, rawValue, rawSetpoint, background,
    ) {
        val shading = levelShading(obj, layout.track, vertical, fillColor, look, if (noValue) null else fillPercent)
        pillBitmap(
            levelPills(obj, segments, handle, pointer, pointerColor, vertical, fillColor, look, shading?.colourAt),
            background,
            shading?.glow,
        )
    }

    Box(
        modifier = Modifier
            .offset(x = ox.dp, y = oy.dp)
            .size(width = obj.width.dp, height = obj.height.dp)
            .then(
                if (writeTopic.isEmpty()) {
                    Modifier
                } else {
                    // The finger sets the value where it is - on the touch,
                    // all through a drag, and on the lift (trackLevelFinger) -
                    // measured against the TRACK, not against the object, so
                    // the finger and the picture cannot drift apart now that the
                    // number takes room off the rectangle.
                    Modifier.pointerInput(obj.id, writeTopic, step, calibration, fonts) {
                        trackLevelFinger({ offset ->
                            val localX = offset.x / density.density
                            val localY = offset.y / density.density
                            val percent = levelPercentFromPoint(
                                obj,
                                ox + localX.toDouble(),
                                oy + localY.toDouble(),
                                fonts,
                            )
                            val value = snapToStep(valueForFillPercent(percent, calibration), step)
                            formatSetValue(value)
                        }) { value, final -> onSetLevel(markerTopic, writeTopic, value, final) }
                    }
                },
            ),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val scale = density.density
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                val paint = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }

                // One run of track or fill, drawn as a pill and then squared
                // off at the ends that are not the track's own, so two runs
                // meet instead of curving away from each other.
                fun run(seg: LevelSegment, argb: Int) {
                    paint.color = argb
                    fillRoundRect(native, paint, seg.x - ox, seg.y - oy, seg.w, seg.h, seg.r, scale)
                    val r = minOf(seg.r, minOf(seg.w, seg.h) / 2)
                    if (r <= 0) return
                    if (!seg.roundStart) {
                        if (vertical) fillUnits(native, paint, seg.x - ox, seg.y - oy, seg.w, r, scale)
                        else fillUnits(native, paint, seg.x - ox, seg.y - oy, r, seg.h, scale)
                    }
                    if (!seg.roundEnd) {
                        if (vertical) fillUnits(native, paint, seg.x - ox, seg.y - oy + seg.h - r, seg.w, r, scale)
                        else fillUnits(native, paint, seg.x - ox + seg.w - r, seg.y - oy, r, seg.h, scale)
                    }
                }

                // The unfilled track: a body in its mixed colour, or - where
                // that colour cannot be told from the background - an outline
                // in the bar's own colour, drawn as the run's outer pixel with
                // the background painted back over the inside.
                fun trackRun(seg: LevelSegment) {
                    if (!look.framed) return run(seg, trackArgb)
                    run(seg, edgeArgb)
                    levelFrameInner(seg, vertical, look.edgeWidth)?.let { run(it, trackArgb) }
                }

                if (soft) {
                    drawPills(native, pills, ox, oy, scale)
                } else {
                    for (seg in segments) {
                        if (seg.role == LevelRole.FILL) run(seg, fillArgb) else trackRun(seg)
                    }
                    if (handle != null) {
                        // The fill's own colour, and deliberately not a marker
                        // colour: handle and active track are one object that the
                        // gap separates.
                        paint.color = fillArgb
                        fillRoundRect(native, paint, handle.x - ox, handle.y - oy, handle.w, handle.h, handle.r, scale)
                    }
                    if (pointer != null) {
                        // Whole pixels, each one whose centre the triangle
                        // holds - the same test the soft path samples with.
                        paint.color = textArgb
                        val s = PILL_SUBPIXEL_SCALE
                        for (py in pointer.y until pointer.y + pointer.h) {
                            for (px in pointer.x until pointer.x + pointer.w) {
                                if (insidePillTip(pointer, px * s + s / 2, py * s + s / 2)) {
                                    fillUnits(native, paint, px - ox, py - oy, 1, 1, scale)
                                }
                            }
                        }
                    }
                }

                // A bar that has heard nothing shows no number: the track
                // alone says what it is.
                if (noValue) return@drawIntoCanvas

                // The number - never over the bar any more, and only one: the
                // commanded value, where the handle points. With Material's 16
                // unit track no number fits inside it, so the old two-pass trick
                // that straddled the fill's edge has nothing left to do.
                val column = layout.value ?: return@drawIntoCanvas
                if (column.w <= 0 || column.h <= 0) return@drawIntoCanvas
                val pen = UnitTextPen(ownTypeface, levelTextSize(obj, ownFont), scale, textArgb)
                native.save()
                native.clipRect(
                    (column.x - ox) * scale,
                    (column.y - oy) * scale,
                    (column.x - ox + column.w) * scale,
                    (column.y - oy + column.h) * scale,
                )
                val left = column.x + column.w - pen.widthOf(commanded)
                native.drawText(commanded, (left - ox) * scale, (layout.baseline - oy) * scale, pen.draw)
                native.restore()
            }
        }
    }
}

/**
 * Everything the bar is made of, described rather than painted, and handed to
 * one rasterizer ([pillBitmap]) - the designer's `drawLevelShape`.
 *
 * The ORDER is the picture: a sub-sample belongs to the first run that contains
 * it and to no other. Painting the handle over the track would leave a ring of
 * track colour around it where the two meet - and the same along the value's
 * edge, which is the one place on a bar anybody looks at.
 */
private fun levelPills(
    obj: ScreenObject,
    segments: List<LevelSegment>,
    handle: LevelRect?,
    pointer: PillBand?,
    pointerColor: String,
    vertical: Boolean,
    fillColor: String,
    look: LevelTrackLook,
    /** The fill's gradient, where the theme gives it one ([levelShading]). */
    colourAt: ((Int, Int) -> Rgb565)? = null,
): List<PaintedPill> {
    // A run's two ends: rounded where the track itself ends, square where
    // something cut it - the fill's edge, or the handle's gap. That is
    // Material's 2 dp inner corner taken to its limit (LevelShape.kt).
    fun bandOf(seg: LevelSegment) = PillBand(
        x = seg.x,
        y = seg.y,
        w = seg.w,
        h = seg.h,
        rLow = if (seg.roundStart) seg.r else 0,
        rHigh = if (seg.roundEnd) seg.r else 0,
        vertical = vertical,
    )

    val painted = mutableListOf<PaintedPill>()
    // In the text's colour: the fill's belongs to what a finger can move.
    if (pointer != null) painted += PaintedPill(pointer, pointerColor)
    if (handle != null) {
        // It lies ACROSS the bar, so its own long axis is the other one. The
        // colour says whether a finger can move it ([handleColourFor]) - which
        // the whole-pixel path above does not yet ask, having been written
        // before that rule existed.
        // Where the fill runs from one colour to another, a handle a finger
        // moves takes the colour of the fill where it stands.
        val handleAt = colourAt?.takeIf { levelIsSettableType(obj.type) }?.let { at ->
            val colour = at(handle.x + handle.w / 2, handle.y + handle.h / 2)
            val fixed: (Int, Int) -> Rgb565 = { _, _ -> colour }
            fixed
        }
        painted += PaintedPill(
            PillBand(handle.x, handle.y, handle.w, handle.h, handle.r, handle.r, !vertical),
            handleColourFor(obj, fillColor, look),
            handleAt,
        )
    }
    for (seg in segments) {
        if (seg.role == LevelRole.FILL) {
            painted += PaintedPill(bandOf(seg), fillColor, colourAt)
            continue
        }
        // The unfilled track: a body in its mixed colour, or - where that
        // colour cannot be told from the background - an outline in the bar's
        // own colour. The outline is the run's outer pixel with its inside
        // taken back, not a ring behind it, so it ends straight where a run was
        // cut instead of in a rounded cap. The inside comes first, being the
        // one that wins the pixels it covers.
        if (!look.framed) {
            painted += PaintedPill(bandOf(seg), look.track)
            continue
        }
        levelFrameInner(seg, vertical, look.edgeWidth)?.let { painted += PaintedPill(bandOf(it), look.track) }
        painted += PaintedPill(bandOf(seg), look.edge ?: fillColor)
    }

    return painted
}

/** A bar's gradient and the glow around its filled part. */
private class LevelShading(val colourAt: (Int, Int) -> Rgb565, val glow: PillGlow?)

/**
 * The theme's look where the fill is its accent (the designer's
 * lib/themes.ts): the fill runs from fillColor to fillEndColor along the
 * track, one step per pixel of the track, and a weak glow lies around the
 * filled part. Null for a flat fill - and where the two ends are one colour,
 * which is a flat fill drawn in the colour exactly rather than through 5/6/5.
 * [fillPercent] is null while no value has arrived: no glow then.
 */
private fun levelShading(
    obj: ScreenObject,
    track: LevelRect,
    vertical: Boolean,
    fillColor: String,
    look: LevelTrackLook,
    fillPercent: Double?,
): LevelShading? {
    val endHex = obj.properties.stringOrNull("fillEndColor")?.takeIf { it.isNotBlank() } ?: return null
    if (look.framed || endHex.equals(fillColor, ignoreCase = true)) return null
    val from = toRgb565(fillColor)
    val to = toRgb565(endHex)
    val fromEnd = levelFillsFromEnd(obj)
    val trackStart = if (vertical) track.y else track.x
    val trackLength = maxOf(1, if (vertical) track.h else track.w)
    fun stepAlong(along: Int): Int {
        val j = (along - trackStart).coerceIn(0, trackLength - 1)
        return if (fromEnd) trackLength - 1 - j else j
    }
    val colourAt: (Int, Int) -> Rgb565 = { px, py ->
        gradient565(from, to, stepAlong(if (vertical) py else px), trackLength)
    }
    val glowPx = levelGlowPx(obj)
    if (glowPx <= 0 || fillPercent == null || fillPercent <= 0.0) return LevelShading(colourAt, null)
    // Around the filled part: a capsule on the track's centre line from the
    // track's own end to the fill's edge, half the thickness wide.
    val edge = levelEdgeFor(track, vertical, fromEnd, fillPercent)
    val s = 8
    val half = (if (vertical) track.w else track.h) * s / 2
    val cross = if (vertical) track.x * s + track.w * s / 2 else track.y * s + track.h * s / 2
    val lo = (if (fromEnd) edge else trackStart) * s
    val hi = (if (fromEnd) trackStart + trackLength else edge) * s
    val a = minOf(lo + half, hi - half)
    val b = maxOf(lo + half, hi - half)
    val glow = PillGlow(
        levelAt = { px, py ->
            val x = px * s + s / 2
            val y = py * s + s / 2
            val d2 = if (vertical) segmentDistance2(x, y, cross, a, cross, b) else segmentDistance2(x, y, a, cross, b, cross)
            val level = glowLevelFromDistance2(d2, half)
            if (level > glowPx) 0 else level
        },
        colourAt = { px, py ->
            val along = if (vertical) py else px
            val clamped = if (fromEnd) maxOf(edge, along) else minOf(edge - 1, along)
            gradient565(from, to, stepAlong(clamped), trackLength)
        },
        boxX = obj.x.toInt(),
        boxY = obj.y.toInt(),
        boxW = obj.width.toInt(),
        boxH = obj.height.toInt(),
    )
    return LevelShading(colourAt, glow)
}

/** One axis-aligned run of whole project units, drawn at [scale] device pixels per unit. */
private fun fillUnits(canvas: NativeCanvas, paint: Paint, x: Int, y: Int, w: Int, h: Int, scale: Float) {
    canvas.drawRect(x * scale, y * scale, (x + w) * scale, (y + h) * scale, paint)
}

/** The face a project font names, or the system's when it ships no file. */
internal fun typefaceOf(font: FontEntry?, assetFileOf: (String) -> java.io.File): Typeface =
    font?.path?.let { assetFileOf(it) }?.takeIf { it.exists() }
        ?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
        ?: Typeface.DEFAULT
