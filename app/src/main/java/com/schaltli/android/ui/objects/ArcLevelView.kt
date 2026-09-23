package com.schaltli.android.ui.objects

import android.graphics.Bitmap
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
import com.schaltli.android.data.FontEntry
import com.schaltli.android.data.Project
import com.schaltli.android.data.ScreenObject
import com.schaltli.android.render.ARC_ANGLE_SCALE
import com.schaltli.android.render.ARC_COVERAGE_MAX
import com.schaltli.android.render.ArcHandle
import com.schaltli.android.render.ArcRingGeometry
import com.schaltli.android.render.Rgb565
import com.schaltli.android.render.arcCaps
import com.schaltli.android.render.arcHandleBand
import com.schaltli.android.render.arcPixelBands
import com.schaltli.android.render.blendBands
import com.schaltli.android.render.handleColourFor
import com.schaltli.android.render.levelHasHandle
import com.schaltli.android.render.levelTrackLook
import com.schaltli.android.render.makeArcSector
import com.schaltli.android.render.rgb565ToArgb
import com.schaltli.android.render.toRgb565
import com.schaltli.android.ui.LocalBundleInstallation
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.truncate

/**
 * Arc level - the round-display counterpart to the level indicator bar,
 * which it shares its value mapping with ([calculateFillPercent]).
 *
 * Two topics, one ring. The filled arc is the reading; an optional second
 * topic (`setpointTopic`) puts a marker on the same track for a setpoint,
 * which is the pair of questions a thermostat is always asked at once -
 * where am I, where am I going. Without that second topic it is simply a
 * filled arc, which is what a water level wants.
 *
 * Every pixel comes from `com.schaltli.android.render`'s port of the
 * shared rasterizer, which also exists in the designer and in each firmware
 * in integer arithmetic chosen so the copies cannot disagree. This file only
 * turns object properties into that rasterizer's inputs.
 *
 * The ring is rasterized at *project* resolution (one bitmap pixel per
 * project unit) and then blitted with filtering and anti-aliasing both off,
 * exactly the way [com.schaltli.android.ui.ScreenRenderer] blits a
 * screen's flattened background PNG. That is not a shortcut - it is the only
 * treatment hil/android/orchestrator.js's `matchDeviceScaling` was built to
 * reproduce, so a ring drawn this way lands on the same device pixels as the
 * upscaled reference. Letting Skia scale the maths instead would put a
 * density-dependent number inside an algorithm whose entire purpose is to
 * produce density-independent integers.
 */
private const val ARC_DEFAULT_MIN_ANGLE = 225
private const val ARC_DEFAULT_MAX_ANGLE = 135

/**
 * The bar's own, since 2026-09-22: one thickness on both shapes unless the
 * author says otherwise. It was 22 while a ring was a thing of its own, and
 * that survived exactly as long as it took to put a dial and a slider on one
 * screen - at 22 against 16 they are not one control in two shapes.
 */
private const val ARC_DEFAULT_THICKNESS = 16

/** Between the commanded number and the measured one under it. */
private const val ARC_SUB_GAP = 2

private data class ArcSweep(val start64: Int, val sweep64: Int, val fillFromEnd: Boolean)

/**
 * Turns the object's min/max clock positions into a clockwise sector.
 *
 * Stored as two positions rather than a start and a length because that is
 * what someone setting one up means: here is zero, here is full scale. The
 * direction resolves which way round the dial that runs, and a counter
 * clockwise dial is expressed as the same clockwise sector filled from its
 * other end - so the rasterizer only ever deals with clockwise sweeps.
 *
 * Equal positions mean a full ring: a zero-length arc is not something
 * anyone builds on purpose, so the ambiguous case is given the useful
 * reading.
 */
private fun resolveArcSweep(obj: ScreenObject): ArcSweep {
    fun norm(deg: Double): Int = ((deg.roundToInt() % 360) + 360) % 360
    val minA = norm(obj.properties.double("minAngle", ARC_DEFAULT_MIN_ANGLE.toDouble()))
    val maxA = norm(obj.properties.double("maxAngle", ARC_DEFAULT_MAX_ANGLE.toDouble()))
    val counterClockwise = obj.properties.string("direction", "cw") == "ccw"

    val startDeg = if (counterClockwise) maxA else minA
    val spanDeg = if (counterClockwise) (minA - maxA + 360) % 360 else (maxA - minA + 360) % 360

    return ArcSweep(
        start64 = startDeg * ARC_ANGLE_SCALE,
        sweep64 = (if (spanDeg == 0) 360 else spanDeg) * ARC_ANGLE_SCALE,
        fillFromEnd = counterClockwise,
    )
}

/**
 * Where along the sector a value sits, in 1/64 degree units.
 *
 * Truncated rather than rounded, mirroring the bar's own
 * `truncate(innerWidth * fillPercent / 100)` and the C assignment to an int
 * that it in turn mirrors. Sixty-fourths of a degree are fine enough that
 * the fill edge still moves smoothly - a whole degree at radius 168 would be
 * nearly three pixels of arc, and the fill would visibly jump.
 */
private fun sweepForPercent(sweep64: Int, percent: Double): Int {
    val clamped = percent.coerceIn(0.0, 100.0)
    return truncate(sweep64 * clamped / 100.0).toInt()
}

/**
 * The band's thickness, under the name both shapes now use.
 *
 * `thickness` is the arc's own name and became the bar's too on 2026-09-22 -
 * one thing, one name. `barThickness` is still read, because the projects in
 * the van are full of it and nothing here rewrites a file someone else owns.
 */
private fun arcThickness(obj: ScreenObject): Double =
    obj.properties.doubleOrNull("thickness")
        ?: obj.properties.doubleOrNull("barThickness")
        ?: ARC_DEFAULT_THICKNESS.toDouble()

/**
 * Whether the object can ever have a handle at all: a write topic or a
 * setpoint topic - the same two things [levelHasHandle] asks about, and the
 * same two the room below is reserved for.
 */
private fun arcCanHaveHandle(obj: ScreenObject): Boolean = levelHasHandle(obj)

/**
 * How far the ring sits inside the object's edge: room for the handle.
 *
 * Half of what the handle is longer than the band it lies across, reserved
 * whenever the object can have one at all rather than while one is being
 * drawn - a ring that reserved the room only while a handle was showing would
 * shrink the moment a value arrived.
 *
 * Clamped so that a small object keeps a ring at all: the reservation gives
 * way before the band does.
 */
private fun arcInset(obj: ScreenObject, size: Int, thickness: Int): Int {
    if (!arcCanHaveHandle(obj)) return 0
    // (11/4 t - t) / 2, rounded up: half the handle's overhang. Written as
    // (7t + 7) / 8 because Kotlin's integer divide floors here and the
    // designer's Math.ceil does not.
    val wanted = (thickness * 7 + 7) / 8
    val room = size / 2 - thickness - 1
    return maxOf(0, minOf(wanted, room))
}

private fun buildGeometry(
    obj: ScreenObject,
    fillPercent: Double,
    setpointPercent: Double?,
    framed: Boolean,
): ArcRingGeometry {
    val size = min(obj.width, obj.height).roundToInt().coerceAtLeast(1)
    val thickness = min(arcThickness(obj).roundToInt().coerceAtLeast(1), size / 2)
    val sweep = resolveArcSweep(obj)
    val inset = arcInset(obj, size, thickness)

    val filled = sweepForPercent(sweep.sweep64, fillPercent)
    val fillStart64 = if (sweep.fillFromEnd) sweep.start64 + sweep.sweep64 - filled else sweep.start64
    val caps = arcCaps(size, thickness, inset, sweep.start64, sweep.sweep64)

    var handle: ArcHandle? = null
    if (setpointPercent != null) {
        val at = sweepForPercent(sweep.sweep64, setpointPercent)
        val angle64 = if (sweep.fillFromEnd) sweep.start64 + sweep.sweep64 - at else sweep.start64 + at
        handle = arcHandleBand(size, thickness, inset, angle64, sweep.sweep64)
    }

    return ArcRingGeometry(
        size = size,
        thickness = thickness,
        inset = inset,
        track = makeArcSector(sweep.start64, sweep.sweep64),
        fill = makeArcSector(fillStart64, filled),
        startCap = caps.startCap,
        endCap = caps.endCap,
        // A cap belongs to the fill when the fill actually reaches that end.
        startCapFilled = filled > 0 && fillStart64 == sweep.start64,
        endCapFilled = filled > 0 && fillStart64 + filled >= sweep.start64 + sweep.sweep64,
        handle = handle,
        framed = framed,
    )
}

private fun rasterizeRing(
    geom: ArcRingGeometry,
    trackColour: Rgb565,
    fillColour: Rgb565,
    handleColour: Rgb565,
    mixInto: Rgb565,
): Bitmap {
    val size = geom.size
    val pixels = IntArray(size * size)

    for (py in 0 until size) {
        for (px in 0 until size) {
            val bands = arcPixelBands(geom, px, py)
            val covered = bands.fill + bands.track + bands.handle
            val at = py * size + px

            // Nothing but the ring is painted: the object has no background of
            // its own any more, so whatever is behind it - a screen colour, a
            // background image - shows through everywhere the band is not.
            if (covered == 0) continue

            pixels[at] = rgb565ToArgb(
                blendBands(
                    fillColour, bands.fill,
                    trackColour, bands.track,
                    handleColour, bands.handle,
                    mixInto, ARC_COVERAGE_MAX - covered,
                ),
            )
        }
    }

    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

@Composable
fun ArcLevelView(
    obj: ScreenObject,
    project: Project,
    rawValue: String,
    rawSetpoint: String?,
    screenBackgroundColor: String?,
    assetFileOf: (String) -> java.io.File,
    // A tap sets the value at the point it landed, on a ring with a write
    // topic - the same rule the bar has (the designer's
    // docs/2026-09-17-settable-level.md). The marker follows from rawSetpoint,
    // which the caller feeds from what was asked.
    onSetLevel: (markerTopic: String, writeTopic: String, value: String) -> Unit = { _, _, _ -> },
) {
    val props = obj.properties
    // No value yet: the track alone - no fill (not even what the calibration
    // makes of 0), no marker, no number. Same on the firmware and in the
    // designer, which both used to stand in "50" here.
    val noValue = rawValue.isBlank()
    val value = rawValue
    val numericValue = value.toDoubleOrNull() ?: 0.0
    val calibration = parseCalibrationPoints(props)
    val fillPercent = if (noValue) 0.0 else calculateFillPercent(numericValue, calibration)

    // What was asked for, if anything: a request a finger just made here
    // first, otherwise a setpoint topic's reported value - which is the order
    // the caller resolves them in. Nothing asked and nothing reported, no
    // handle: a water level has nothing to aim at.
    //
    // Only a ring that can HAVE a handle ever shows one, and deliberately
    // without the bar's fallback to the value itself: a ring that reserved no
    // room would otherwise draw a handle standing outside its own rectangle.
    val rawMarker = rawSetpoint?.takeIf { !noValue && it.isNotBlank() && arcCanHaveHandle(obj) } ?: ""
    val setpointPercent = rawMarker.takeIf { it.isNotBlank() }
        ?.let { calculateFillPercent(it.toDoubleOrNull() ?: 0.0, calibration) }

    val writeTopic = props.stringOrNull("writeTopic") ?: ""
    val markerTopic = props.stringOrNull("setpointTopic")?.takeIf { it.isNotEmpty() }
        ?: props.stringOrNull("topic") ?: ""
    val step = props.double("step", 1.0)

    // One colour the author sets; everything else follows from it and from
    // what the ring stands on - the same rule, and the same function, the bar
    // uses for its own track. The ring has no background, no track colour and
    // no marker colour of its own any more.
    val fillColor = props.string("fillColor", "#4CAF50")
    // What the anti-aliased edges mix into. Mixing into a declared colour
    // rather than sampling what is actually underneath is deliberate: the
    // designer cannot sample its own canvas at an arbitrary zoom, and an edge
    // that mixes into different things on the two sides is an edge that fails
    // the pixel comparison. Over a background *image* this is an
    // approximation - but it is the same approximation everywhere, so only the
    // eye can tell.
    val ground = screenBackgroundColor ?: "#ffffff"
    val look = levelTrackLook(fillColor, ground)

    val bitmap = remember(
        obj.id, obj.width, obj.height, props, fillPercent, setpointPercent, screenBackgroundColor,
    ) {
        val geom = buildGeometry(obj, fillPercent, setpointPercent, look.framed)
        rasterizeRing(
            geom = geom,
            // Where the mixed track cannot be told from the background, the
            // band is drawn as an outline in the bar's own colour instead of
            // a body in a colour nobody would see.
            trackColour = if (look.framed) toRgb565(fillColor) else toRgb565(look.track),
            fillColour = toRgb565(fillColor),
            handleColour = toRgb565(handleColourFor(obj, fillColor, look)),
            mixInto = toRgb565(ground),
        )
    }

    // The numbers, the way the bar says them: the big one is the COMMANDED
    // value - where the handle points, and what a finger just changed - and
    // the measured one only appears when it says something the big one does
    // not. A ring puts them one above the other rather than side by side: it
    // has the room, and it has no header line to lay them out on.
    val displayValue = props.string("displayValue", "value")
    fun asText(raw: String, percent: Double): String =
        if (displayValue == "percentage") "${percent.roundToInt()}%" else raw
    val measured = asText(value, fillPercent)
    val commanded = if (setpointPercent != null) asText(rawMarker, setpointPercent) else measured
    val displayText = if (noValue || displayValue == "none") null else commanded
    val subText = if (displayText != null && setpointPercent != null && measured != commanded) {
        "($measured)"
    } else {
        null
    }

    val textColor = (props.stringOrNull("textColor") ?: props.stringOrNull("color"))
        ?.let(::parseHexColor) ?: Color.White
    val fonts = project.fonts
    val ownFont: FontEntry? = fonts.find { it.id == props.stringOrNull("fontId") }
    val fontSize = levelTextSize(obj, ownFont)
    // The bracketed number is the SAME face at two thirds the size. The bar
    // does something else - it hunts the project for a smaller font - because
    // a BDF font cannot be scaled at all, so "smaller" there has to mean
    // "another file". A ring on this platform always renders through the TTF
    // branch, where scaling is free, and the designer's own TTF branch scales
    // the object's own face. Borrowing the bar's rule here would silently pick
    // a different typeface whenever the project happens to carry a smaller one.
    val subSize = maxOf(6, fontSize * 2 / 3)
    val installation = LocalBundleInstallation.current
    val typeface = remember(ownFont?.path, installation) { typefaceOf(ownFont, assetFileOf) }
    val subTypeface = typeface

    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .offset(x = obj.x.roundToInt().dp, y = obj.y.roundToInt().dp)
            .size(width = obj.width.dp, height = obj.height.dp)
            .then(
                if (writeTopic.isEmpty()) {
                    Modifier
                } else {
                    // The ring's own sector, read backwards: the finger's
                    // angle becomes a percentage of the scale, that becomes a
                    // value through the calibration, snapped to the step.
                    // Twelve o'clock is up and the degrees run clockwise, the
                    // orientation the ring is drawn in; a point in the gap at
                    // the bottom of a dial gets the nearer end.
                    Modifier.pointerInput(obj.id, writeTopic, step, calibration) {
                        detectTapGestures { offset ->
                            val side = min(obj.width, obj.height).coerceAtLeast(1.0)
                            val dx = offset.x / density.density - side / 2
                            val dy = offset.y / density.density - side / 2
                            if (dx != 0.0 || dy != 0.0) {
                                val minA = ((props.double("minAngle", 225.0).roundToInt() % 360) + 360) % 360
                                val maxA = ((props.double("maxAngle", 135.0).roundToInt() % 360) + 360) % 360
                                val counterClockwise = props.string("direction", "cw") == "ccw"
                                val startDeg = if (counterClockwise) maxA else minA
                                var spanDeg =
                                    if (counterClockwise) ((minA - maxA + 360) % 360) else ((maxA - minA + 360) % 360)
                                if (spanDeg == 0) spanDeg = 360

                                var deg = Math.toDegrees(kotlin.math.atan2(dx, -dy))
                                if (deg < 0) deg += 360.0
                                var rel = (deg - startDeg + 360.0) % 360.0
                                if (rel > spanDeg) {
                                    val pastEnd = rel - spanDeg
                                    val beforeStart = 360.0 - rel
                                    rel = if (pastEnd <= beforeStart) spanDeg.toDouble() else 0.0
                                }
                                var percent = rel / spanDeg * 100.0
                                if (counterClockwise) percent = 100.0 - percent
                                val value =
                                    snapToStep(valueForFillPercent(percent.coerceIn(0.0, 100.0), calibration), step)
                                onSetLevel(markerTopic, writeTopic, formatSetValue(value))
                            }
                        }
                    }
                },
            ),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val scale = density.density
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                val blit = android.graphics.Paint().apply {
                    isAntiAlias = false
                    isFilterBitmap = false
                }
                val dst = android.graphics.Rect(
                    0, 0,
                    (bitmap.width * scale).roundToInt(),
                    (bitmap.height * scale).roundToInt(),
                )
                native.drawBitmap(bitmap, null, dst, blit)

                if (displayText != null) {
                    // Centred the way render-arc-level.ts centres its own
                    // value, not the way the label engine centres text: the
                    // bar and the ring are siblings showing the same value the
                    // same way, and both are ported to the same routine.
                    fun penOf(face: Typeface, units: Int) = android.graphics.Paint().apply {
                        isAntiAlias = true
                        this.typeface = face
                        textSize = units * scale
                        color = textColor.toArgb()
                        textAlign = android.graphics.Paint.Align.CENTER
                    }

                    val textPaint = penOf(typeface, fontSize)
                    native.save()
                    native.clipRect(0f, 0f, size.width, size.height)
                    val middle = size.height / 2f
                    fun baselineFor(p: android.graphics.Paint, centre: Float) =
                        centre - (p.ascent() + p.descent()) / 2f

                    if (subText == null) {
                        native.drawText(displayText, size.width / 2f, baselineFor(textPaint, middle), textPaint)
                    } else {
                        // The pair is centred together, so the big number does
                        // not jump the moment a setpoint arrives.
                        val subPaint = penOf(subTypeface, subSize)
                        val block = (fontSize + ARC_SUB_GAP + subSize) * scale
                        native.drawText(
                            displayText,
                            size.width / 2f,
                            baselineFor(textPaint, middle - block / 2f + fontSize * scale / 2f),
                            textPaint,
                        )
                        native.drawText(
                            subText,
                            size.width / 2f,
                            baselineFor(subPaint, middle + block / 2f - subSize * scale / 2f),
                            subPaint,
                        )
                    }
                    native.restore()
                }
            }
        }
    }
}
