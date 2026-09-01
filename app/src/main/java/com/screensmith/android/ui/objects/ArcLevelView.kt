package com.screensmith.android.ui.objects

import android.graphics.Bitmap
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.screensmith.android.data.FontEntry
import com.screensmith.android.data.Project
import com.screensmith.android.data.ScreenObject
import com.screensmith.android.render.ARC_ANGLE_SCALE
import com.screensmith.android.render.ARC_COVERAGE_MAX
import com.screensmith.android.render.ArcRingGeometry
import com.screensmith.android.render.Rgb565
import com.screensmith.android.render.arcPixelBands
import com.screensmith.android.render.blendBands
import com.screensmith.android.render.makeArcSector
import com.screensmith.android.render.rgb565ToArgb
import com.screensmith.android.render.toRgb565
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
 * Every pixel comes from `com.screensmith.android.render`'s port of the
 * shared rasterizer, which also exists in the designer and in each firmware
 * in integer arithmetic chosen so the copies cannot disagree. This file only
 * turns object properties into that rasterizer's inputs.
 *
 * The ring is rasterized at *project* resolution (one bitmap pixel per
 * project unit) and then blitted with filtering and anti-aliasing both off,
 * exactly the way [com.screensmith.android.ui.ScreenRenderer] blits a
 * screen's flattened background PNG. That is not a shortcut - it is the only
 * treatment hil/android/orchestrator.js's `matchDeviceScaling` was built to
 * reproduce, so a ring drawn this way lands on the same device pixels as the
 * upscaled reference. Letting Skia scale the maths instead would put a
 * density-dependent number inside an algorithm whose entire purpose is to
 * produce density-independent integers.
 */
private const val ARC_DEFAULT_MIN_ANGLE = 225
private const val ARC_DEFAULT_MAX_ANGLE = 135
private const val ARC_DEFAULT_THICKNESS = 22
private const val ARC_DEFAULT_MARKER_WIDTH_DEGREES = 4

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

private fun buildGeometry(obj: ScreenObject, fillPercent: Double, setpointPercent: Double?): ArcRingGeometry {
    val size = min(obj.width, obj.height).roundToInt().coerceAtLeast(1)
    val thickness = obj.properties.double("thickness", ARC_DEFAULT_THICKNESS.toDouble())
        .roundToInt().coerceAtLeast(1)
    val sweep = resolveArcSweep(obj)

    val filled = sweepForPercent(sweep.sweep64, fillPercent)
    val fillStart64 = if (sweep.fillFromEnd) sweep.start64 + sweep.sweep64 - filled else sweep.start64

    var marker = makeArcSector(0, 0)
    if (setpointPercent != null) {
        val at = sweepForPercent(sweep.sweep64, setpointPercent)
        val centreAt = if (sweep.fillFromEnd) sweep.start64 + sweep.sweep64 - at else sweep.start64 + at
        val width64 = obj.properties.double("markerWidth", ARC_DEFAULT_MARKER_WIDTH_DEGREES.toDouble())
            .roundToInt().coerceAtLeast(1) * ARC_ANGLE_SCALE
        marker = makeArcSector(centreAt - width64 / 2, width64)
    }

    return ArcRingGeometry(
        size = size,
        thickness = min(thickness, size / 2),
        track = makeArcSector(sweep.start64, sweep.sweep64),
        fill = makeArcSector(fillStart64, filled),
        marker = marker,
    )
}

private fun rasterizeRing(
    geom: ArcRingGeometry,
    trackColour: Rgb565,
    fillColour: Rgb565,
    markerColour: Rgb565,
    mixInto: Rgb565,
    opaqueBackground: Rgb565?,
): Bitmap {
    val size = geom.size
    val pixels = IntArray(size * size)
    val flatBackground = opaqueBackground?.let { rgb565ToArgb(it) }

    for (py in 0 until size) {
        for (px in 0 until size) {
            val bands = arcPixelBands(geom, px, py)
            val covered = bands.fill + bands.track + bands.marker
            val at = py * size + px

            if (covered == 0) {
                // Nothing of the ring here. With an opaque background the
                // object still owns its square; with a transparent one the
                // pixel is left alone, exactly as "transparent" means
                // everywhere else in this system.
                if (flatBackground != null) pixels[at] = flatBackground
                continue
            }

            pixels[at] = rgb565ToArgb(
                blendBands(
                    fillColour, bands.fill,
                    trackColour, bands.track,
                    markerColour, bands.marker,
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
) {
    val props = obj.properties
    // "50" as the stand-in for an unbound topic, same as the designer's
    // preview - a ring at zero looks like a broken ring, not an empty one.
    val value = rawValue.ifEmpty { "50" }
    val numericValue = value.toDoubleOrNull() ?: 0.0
    val calibration = parseCalibrationPoints(props)
    val fillPercent = calculateFillPercent(numericValue, calibration)

    // No setpoint topic, no marker - a water level has nothing to aim at.
    val setpointPercent = rawSetpoint
        ?.takeIf { it.isNotEmpty() && !props.stringOrNull("setpointTopic").isNullOrEmpty() }
        ?.let { calculateFillPercent(it.toDoubleOrNull() ?: 0.0, calibration) }

    val backgroundRaw = props.string("backgroundColor", "transparent")
    val backgroundIsTransparent = backgroundRaw.isEmpty() || backgroundRaw == "transparent"

    val bitmap = remember(
        obj.id, obj.width, obj.height, props, fillPercent, setpointPercent, screenBackgroundColor,
    ) {
        val geom = buildGeometry(obj, fillPercent, setpointPercent)
        // What the anti-aliased edges mix into where the object's own
        // background is transparent - which is the common case, since a ring
        // is meant to float on the screen rather than sit in a square of its
        // own colour. Mixing into a declared colour rather than sampling what
        // is actually underneath is deliberate: the designer cannot sample its
        // own canvas at an arbitrary zoom, and an edge that mixes into
        // different things on the two sides is an edge that fails the pixel
        // comparison. Over a background *image* this is an approximation - but
        // it is the same approximation everywhere, so only the eye can tell.
        val mixInto = toRgb565(
            if (backgroundIsTransparent) (screenBackgroundColor ?: "#000000") else backgroundRaw,
        )
        rasterizeRing(
            geom = geom,
            trackColour = toRgb565(props.string("trackColor", "#303030")),
            fillColour = toRgb565(props.string("fillColor", "#4CAF50")),
            markerColour = toRgb565(props.string("markerColor", "#ffffff")),
            mixInto = mixInto,
            opaqueBackground = if (backgroundIsTransparent) null else mixInto,
        )
    }

    val displayValue = props.string("displayValue", "value")
    val displayText = when (displayValue) {
        "none" -> null
        "percentage" -> "${fillPercent.roundToInt()}%"
        else -> value
    }

    val textColor = (props.stringOrNull("textColor") ?: props.stringOrNull("color"))
        ?.let(::parseHexColor) ?: Color.White
    val fontMeta: FontEntry? = project.fonts.find { it.id == props.stringOrNull("fontId") }
    val fontSize = fontMeta?.size ?: 14
    val typeface = remember(fontMeta?.path) {
        fontMeta?.path?.let { assetFileOf(it) }?.takeIf { it.exists() }
            ?.let { Typeface.createFromFile(it) } ?: Typeface.DEFAULT
    }

    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .offset(x = obj.x.roundToInt().dp, y = obj.y.roundToInt().dp)
            .size(width = obj.width.dp, height = obj.height.dp),
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
                    // Centred the way render-level-indicator.ts centres its
                    // own value, not the way the label engine centres text:
                    // the bar and the ring are siblings showing the same value
                    // the same way, and both are ported to the same routine.
                    val textPaint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        this.typeface = typeface
                        textSize = fontSize * scale
                        color = textColor.toArgb()
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    native.save()
                    native.clipRect(0f, 0f, size.width, size.height)
                    val centerY = size.height / 2f
                    native.drawText(
                        displayText,
                        size.width / 2f,
                        centerY - (textPaint.ascent() + textPaint.descent()) / 2f,
                        textPaint,
                    )
                    native.restore()
                }
            }
        }
    }
}
