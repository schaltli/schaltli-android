package com.screensmith.android.ui.objects

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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToInt
import kotlin.math.truncate

/**
 * Mirrors the designer's `calculateLevelIndicatorFill` (`render-level-indicator.ts`)
 * exactly. Not private - reused by MqttDataLineView for its own
 * value -> stroke-width-in-px calibration (same {value, barSizePercent}
 * shape and sort/clamp/linear-interpolate mechanism, `barSizePercent`
 * reinterpreted as a pixel width there - see that file's header comment).
 */
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

private fun parseCalibrationPoints(props: JsonObject): List<Pair<Double, Double>> {
    val array = props["calibrationPoints"] as? JsonArray ?: return listOf(0.0 to 0.0, 100.0 to 100.0)
    val points = array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val value = (obj["value"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        val barSizePercent = (obj["barSizePercent"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        value to barSizePercent
    }
    return points.ifEmpty { listOf(0.0 to 0.0, 100.0 to 100.0) }
}

private data class FillRect(val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * Mirrors render-level-indicator.ts's computeBarFillRect() exactly: a 4dp
 * inset on every side (the designer's `padding = 4`, in project units = dp
 * here), then Math.trunc (toward-zero truncation, same as the firmware's
 * `int fillWidth = (innerWidth * fillPercent) / 100`) - a plain float
 * fraction produced a partial-pixel edge that got anti-aliased into a
 * non-pure-color row/column, a real HIL mismatch (2026-07-21 finding on the
 * designer side; this Android view had never implemented the padding inset
 * at all, so it drifted independently until this stress test caught it,
 * 2026-07-27).
 */
private fun computeBarFillRect(width: Float, height: Float, fillPercent: Double, barDirection: String, paddingPx: Float): FillRect {
    val padding = paddingPx
    val innerX = padding
    val innerY = padding
    val innerWidth = width - padding * 2
    val innerHeight = height - padding * 2

    return when (barDirection) {
        "right-to-left" -> {
            val fillWidth = truncate(innerWidth * fillPercent / 100.0).toFloat()
            FillRect(innerX + innerWidth - fillWidth, innerY, fillWidth, innerHeight)
        }
        "bottom-to-top" -> {
            val fillHeight = truncate(innerHeight * fillPercent / 100.0).toFloat()
            FillRect(innerX, innerY + innerHeight - fillHeight, innerWidth, fillHeight)
        }
        "top-to-bottom" -> {
            val fillHeight = truncate(innerHeight * fillPercent / 100.0).toFloat()
            FillRect(innerX, innerY, innerWidth, fillHeight)
        }
        else -> {
            val fillWidth = truncate(innerWidth * fillPercent / 100.0).toFloat()
            FillRect(innerX, innerY, fillWidth, innerHeight)
        }
    }
}

/**
 * Mirrors render-level-indicator.ts's drawTextBox background/border/text
 * pipeline: a native Canvas/Paint draw (not Compose's .background()/
 * .border() modifiers, which rendered a visibly thicker border than the
 * reference - same class of bug as TextBoxView's, 2026-07-27), and the
 * designer's two-pass text technique - the value/percentage text is drawn
 * once in the bar's own fillColor across the whole box, then drawn a
 * second time in the background color, clipped to just the bar's filled
 * rect, so it reads as background-colored ink *inside* the bar (visible
 * against the fill) and fillColor-colored ink *outside* it (visible
 * against the background) - never invisible against either. The previous
 * single fixed-black-text version was readable but a real color mismatch
 * against the designer at any fill level (2026-07-27 HIL finding).
 */
@Composable
fun LevelIndicatorView(obj: ScreenObject, project: Project, rawValue: String) {
    val props = obj.properties
    val backgroundColor = props.colorOrDefault("backgroundColor", Color.White)
    val borderColor = props.colorOrDefault("borderColor", Color(0xFFCCCCCC))
    val fillColor = props.colorOrDefault("fillColor", Color(0xFF4CAF50))
    val displayValue = props.string("displayValue", "value")
    val barDirection = props.string("barDirection", "left-to-right")

    val numericValue = rawValue.toDoubleOrNull() ?: 0.0
    val fillPercent = calculateFillPercent(numericValue, parseCalibrationPoints(props)).coerceIn(0.0, 100.0)

    val displayText = when (displayValue) {
        "none" -> null
        "percentage" -> "${fillPercent.roundToInt()}%"
        else -> rawValue
    }

    val fontId = (props["fontId"] as? JsonPrimitive)?.contentOrNull
    val fontMeta: FontEntry? = project.fonts.find { it.id == fontId }
    val fontSize = fontMeta?.size ?: 14
    val typeface = remember(fontMeta?.path) {
        // fontMeta.path is bundle-relative (e.g. "assets/fonts/Roboto.ttf");
        // resolving it needs assetFileOf, but this composable (unlike
        // TextBoxView) isn't handed that function - falls back to the
        // system default face rather than plumbing it through for a
        // property the designer itself only uses for size, not glyph shape
        // (render-level-indicator.ts's `isTtf ? levelFontMeta.size : ...`
        // never reads a family/path either in its own fallback text path).
        Typeface.DEFAULT
    }

    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .offset(x = obj.x.dp, y = obj.y.dp)
            .size(width = obj.width.dp, height = obj.height.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val borderWidthPx = with(density) { 1.dp.toPx() }
            val barPaddingPx = with(density) { 4.dp.toPx() }

            drawIntoCanvas { canvas ->
                val fillPaint = android.graphics.Paint().apply { isAntiAlias = false; style = android.graphics.Paint.Style.FILL }

                if (borderColor != Color.Transparent) {
                    fillPaint.color = borderColor.toArgb()
                    canvas.nativeCanvas.drawRect(0f, 0f, w, h, fillPaint)
                }
                if (backgroundColor != Color.Transparent) {
                    fillPaint.color = backgroundColor.toArgb()
                    val inset = if (borderColor != Color.Transparent) borderWidthPx else 0f
                    canvas.nativeCanvas.drawRect(inset, inset, w - inset, h - inset, fillPaint)
                }

                val bar = computeBarFillRect(w, h, fillPercent, barDirection, barPaddingPx)
                fillPaint.color = fillColor.toArgb()
                canvas.nativeCanvas.drawRect(bar.x, bar.y, bar.x + bar.w, bar.y + bar.h, fillPaint)

                if (displayText != null) {
                    val textPaint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        this.typeface = typeface
                        this.textSize = with(density) { fontSize.dp.toPx() }
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    val centerX = w / 2f
                    val centerY = h / 2f
                    // Standard Paint vertical-centering formula, equivalent
                    // to canvas's textBaseline = "middle" the designer's own
                    // fallback text path uses.
                    val baselineY = centerY - (textPaint.ascent() + textPaint.descent()) / 2f

                    textPaint.color = fillColor.toArgb()
                    canvas.nativeCanvas.drawText(displayText, centerX, baselineY, textPaint)

                    val clipBounds = android.graphics.Rect(
                        bar.x.roundToInt(), bar.y.roundToInt(),
                        (bar.x + bar.w).roundToInt(), (bar.y + bar.h).roundToInt(),
                    )
                    canvas.nativeCanvas.save()
                    canvas.nativeCanvas.clipRect(clipBounds)
                    textPaint.color = backgroundColor.toArgb()
                    canvas.nativeCanvas.drawText(displayText, centerX, baselineY, textPaint)
                    canvas.nativeCanvas.restore()
                }
            }
        }
    }
}
