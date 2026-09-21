package com.screensmith.android.ui.objects

import android.graphics.Canvas as NativeCanvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
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
import com.screensmith.android.data.FontEntry
import com.screensmith.android.data.Project
import com.screensmith.android.data.ScreenObject
import com.screensmith.android.render.LEVEL_GAP
import com.screensmith.android.render.LevelRect
import com.screensmith.android.render.LevelRole
import com.screensmith.android.render.LevelSegment
import com.screensmith.android.render.fillRoundRect
import com.screensmith.android.render.levelEmptyTrack
import com.screensmith.android.render.levelFontMetrics
import com.screensmith.android.render.levelFontSize
import com.screensmith.android.render.levelFrameInner
import com.screensmith.android.render.levelHandleRect
import com.screensmith.android.render.levelIsVertical
import com.screensmith.android.render.levelLayout
import com.screensmith.android.render.levelLineHeight
import com.screensmith.android.render.levelName
import com.screensmith.android.render.levelPercentFromPoint
import com.screensmith.android.render.levelSegments
import com.screensmith.android.render.levelShowsNumber
import com.screensmith.android.render.levelShowsSub
import com.screensmith.android.render.levelTrackLook
import com.screensmith.android.ui.LocalBundleInstallation
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
private fun isSettableLevel(obj: ScreenObject): Boolean =
    (obj.type == "bar" || obj.type == "slider" || obj.type == "gauge" || obj.type == "dial") &&
        !obj.properties.stringOrNull("writeTopic").isNullOrBlank()

/**
 * The smaller font the measured value is written in, out of the project's own
 * list - the designer's `levelSubFont`.
 *
 * A BDF font is a grid of bitmaps and cannot be scaled, so "smaller" has to
 * mean *another font*, which is why this is a choice rather than a number.
 * The same family first (`font-roboto-16` and `font-roboto-12` share
 * `font-roboto-`), because mixing another face into the line looks like a
 * mistake; then any font small enough; and if the project has nothing
 * smaller, the object's own, which merely looks unremarkable.
 */
private fun levelSubFont(fonts: List<FontEntry>, obj: ScreenObject): FontEntry? {
    if (fonts.isEmpty()) return null
    val own = fonts.firstOrNull { it.id == obj.properties.stringOrNull("fontId") }
    // Two thirds of the object's own line - not of `fontSize`, which the font
    // picker never updates.
    val wanted = levelLineHeight(levelFontMetrics(obj, fonts)) * 2 / 3
    fun family(id: String) = id.replace(Regex("[0-9]+$"), "")
    val ownFamily = own?.let { family(it.id) } ?: ""
    val smaller = fonts.filter { it.size <= wanted }
    fun best(list: List<FontEntry>) = list.maxByOrNull { it.size }
    return best(smaller.filter { family(it.id) == ownFamily }) ?: best(smaller) ?: own
}

/** The size the text is drawn at: a TTF's own, else the object's `fontSize`. */
private fun levelTextSize(obj: ScreenObject, font: FontEntry?): Int =
    if (font?.path != null && font.size > 0) font.size else levelFontSize(obj)

/**
 * The size the bracketed number is drawn at. A different font carries its own
 * size; the object's own font, when nothing smaller was found, is drawn at two
 * thirds.
 */
private fun levelSubTextSize(obj: ScreenObject, sub: FontEntry?, own: FontEntry?): Int {
    if (sub != null && sub !== own) return levelTextSize(obj, sub)
    return maxOf(6, levelTextSize(obj, own) * 2 / 3)
}

/**
 * One piece of text on a level indicator: what it is written in, and how big.
 *
 * Measured in project units and drawn in device pixels, which is why there are
 * two Paints. The designer measures with `ctx.measureText` at the font's own
 * pixel size and rounds the answer up to a whole unit; measuring here at the
 * scaled size and dividing back would put a density-dependent number into an
 * arithmetic whose whole purpose is to land on the same integers.
 */
private class LevelTextPen(typeface: Typeface, sizeUnits: Int, scale: Float, argb: Int) {
    val draw = Paint().apply {
        isAntiAlias = true
        this.typeface = typeface
        textSize = sizeUnits * scale
        color = argb
        textAlign = Paint.Align.LEFT
    }
    private val measure = Paint().apply {
        isAntiAlias = true
        this.typeface = typeface
        textSize = sizeUnits.toFloat()
    }

    /** How wide this text is drawn, in whole project units. */
    fun widthOf(text: String): Int = kotlin.math.ceil(measure.measureText(text).toDouble()).toInt()
}

/**
 * A level indicator: a tank gauge, and - with somewhere to write to - the
 * slider that sets one.
 *
 * Everything it is made of comes from
 * [com.screensmith.android.render.LevelShape], this repo's copy of the
 * designer's `lib/level-shape.ts`, which a golden test holds to the designer's
 * own numbers. This file turns those rectangles into pixels and does no
 * geometry of its own - which is the whole reason the port was worth doing:
 * the old version drew a bordered box with the number in the middle of it, a
 * look the designer retired on 2026-09-19 (docs/2026-09-19-slider-look.md),
 * and there was nowhere to put the difference except a HIL percentage.
 *
 * Not ported: the icon a header line can carry. The designer rasterises the
 * icon's *ink* onto the baseline (`rasterisedIconOnBaseline`), trimming the
 * SVG's own margin, and drawing it any other way would be a second set of
 * pixels that disagrees rather than a missing one. A bar with an `iconAssetId`
 * therefore shows its name and no picture here - and the export does not write
 * the file for a level object either, so there is nothing to draw with yet.
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
    onSetLevel: (markerTopic: String, writeTopic: String, value: String) -> Unit = { _, _, _ -> },
) {
    val props = obj.properties
    val fonts = project.fonts
    val fillColor = props.string("fillColor", "#4CAF50")
    val fillArgb = (parseHexColor(fillColor) ?: Color(0xFF4CAF50)).toArgb()
    val look = levelTrackLook(fillColor, screenBackgroundColor ?: "#ffffff")
    val trackArgb = (parseHexColor(look.track) ?: Color.Transparent).toArgb()
    val textArgb = (props.stringOrNull("textColor")?.let(::parseHexColor) ?: Color.Black).toArgb()

    val calibration = parseCalibrationPoints(props)
    // Nothing reported yet: the track alone. It is the shape of the control,
    // the way a ring has always drawn itself without a value - while an empty
    // *fill* would claim an empty tank (docs/2026-09-15-live-data.md).
    val noValue = rawValue.isBlank()
    val fillPercent = calculateFillPercent(rawValue.toDoubleOrNull() ?: 0.0, calibration).coerceIn(0.0, 100.0)

    val rawMarker = when {
        noValue -> ""
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
    val measured = asText(rawValue, fillPercent)
    val commanded = if (setpointPercent != null) asText(rawMarker, setpointPercent) else measured

    val ownFont: FontEntry? = fonts.firstOrNull { it.id == props.stringOrNull("fontId") }
    val subFont = levelSubFont(fonts, obj)
    val installation = LocalBundleInstallation.current
    val ownTypeface = remember(ownFont?.path, installation) { typefaceOf(ownFont, assetFileOf) }
    val subTypeface = remember(subFont?.path, installation) { typefaceOf(subFont, assetFileOf) }

    val density = LocalDensity.current
    val layout = levelLayout(obj, fonts)
    val vertical = levelIsVertical(obj)
    val ox = obj.x.toInt()
    val oy = obj.y.toInt()

    Box(
        modifier = Modifier
            .offset(x = ox.dp, y = oy.dp)
            .size(width = obj.width.dp, height = obj.height.dp)
            .then(
                if (writeTopic.isEmpty()) {
                    Modifier
                } else {
                    // A tap sets the value at the point it landed - measured
                    // against the TRACK, not against the object, so the finger
                    // and the picture cannot drift apart now that a header
                    // line and a number take room off the rectangle.
                    Modifier.pointerInput(obj.id, writeTopic, step, calibration, fonts) {
                        detectTapGestures { offset ->
                            val localX = offset.x / density.density
                            val localY = offset.y / density.density
                            val percent = levelPercentFromPoint(
                                obj,
                                ox + localX.toDouble(),
                                oy + localY.toDouble(),
                                fonts,
                            )
                            val value = snapToStep(valueForFillPercent(percent, calibration), step)
                            onSetLevel(markerTopic, writeTopic, formatSetValue(value))
                        }
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
                    run(seg, fillArgb)
                    levelFrameInner(seg, vertical)?.let { run(it, trackArgb) }
                }

                val handle = setpointPercent?.let { levelHandleRect(obj, it, fonts) }
                val segments = if (noValue) {
                    listOf(levelEmptyTrack(obj, fonts))
                } else {
                    levelSegments(obj, fillPercent, handle, fonts)
                }
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

                val pen = LevelTextPen(ownTypeface, levelTextSize(obj, ownFont), scale, textArgb)
                fun drawAt(p: LevelTextPen, clip: LevelRect, text: String, x: Int, baseline: Int) {
                    if (text.isEmpty() || clip.w <= 0 || clip.h <= 0) return
                    native.save()
                    native.clipRect(
                        (clip.x - ox) * scale,
                        (clip.y - oy) * scale,
                        (clip.x - ox + clip.w) * scale,
                        (clip.y - oy + clip.h) * scale,
                    )
                    native.drawText(text, (x - ox) * scale, (baseline - oy) * scale, p.draw)
                    native.restore()
                }

                /** Text whose right end is at [right]. Returns where its left end landed. */
                fun drawRightAligned(p: LevelTextPen, clip: LevelRect, text: String, right: Int): Int {
                    val left = right - p.widthOf(text)
                    drawAt(p, clip, text, left, layout.baseline)
                    return left
                }

                /** The name, from the start of the header's text run up to [right]. */
                fun drawHeaderName(right: Int) {
                    val name = levelName(obj)
                    val runRect = layout.text ?: return
                    if (name.isEmpty() || right <= runRect.x) return
                    drawAt(pen, runRect.copy(w = right - runRect.x), name, runRect.x, layout.baseline)
                }

                if (noValue) {
                    // The name, and neither fill nor number: a bar that has
                    // heard nothing still says what it is.
                    layout.text?.let { drawHeaderName(it.x + it.w) }
                    return@drawIntoCanvas
                }

                // The numbers - never over the bar any more. With Material's
                // 16 unit track no number fits inside it, so the old two-pass
                // trick that straddled the fill's edge has nothing left to do.
                // The big one is the commanded value, where the handle points;
                // the measured one only appears when it says something the big
                // one does not.
                val textRun = layout.text
                if (textRun != null) {
                    var right = textRun.x + textRun.w
                    if (levelShowsNumber(obj)) {
                        right = drawRightAligned(pen, textRun, commanded, right) - LEVEL_GAP
                        if (levelShowsSub(obj) && measured != commanded) {
                            // In brackets rather than behind a word: a bracket
                            // needs no language. Smaller too, and on the same
                            // baseline as the big one.
                            val subPen = LevelTextPen(
                                subTypeface,
                                levelSubTextSize(obj, subFont, ownFont),
                                scale,
                                textArgb,
                            )
                            right = drawRightAligned(subPen, textRun, "($measured)", right) - LEVEL_GAP
                        }
                    }
                    drawHeaderName(right)
                } else {
                    layout.value?.let { drawRightAligned(pen, it, commanded, it.x + it.w) }
                }
            }
        }
    }
}

/** One axis-aligned run of whole project units, drawn at [scale] device pixels per unit. */
private fun fillUnits(canvas: NativeCanvas, paint: Paint, x: Int, y: Int, w: Int, h: Int, scale: Float) {
    canvas.drawRect(x * scale, y * scale, (x + w) * scale, (y + h) * scale, paint)
}

/** The face a project font names, or the system's when it ships no file. */
private fun typefaceOf(font: FontEntry?, assetFileOf: (String) -> java.io.File): Typeface =
    font?.path?.let { assetFileOf(it) }?.takeIf { it.exists() }
        ?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
        ?: Typeface.DEFAULT
