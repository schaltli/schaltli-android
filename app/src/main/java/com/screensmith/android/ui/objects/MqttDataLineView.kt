package com.screensmith.android.ui.objects

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.screensmith.android.data.ScreenObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

private data class LinePoint(val x: Float, val y: Float)

/**
 * A line's real vertices (points, screen-absolute, same convention as the
 * designer's `getLinePoints()`/firmware's `obj.properties.points`) - falls
 * back to the object's own (x, y)-to-(x+width, y+height) bounding box for a
 * line with no points array, matching every other renderer's identical
 * fallback. No existing points parser to reuse in this codebase (unlike
 * calibrationPoints/comparisonOperator, which level-indicator/tab-control
 * already established) - `line` itself is static/baked-into-background here
 * and was never live-rendered, so this is genuinely new.
 */
private fun parsePoints(obj: ScreenObject): List<LinePoint> {
    val array = obj.properties["points"] as? JsonArray
    val parsed = array?.mapNotNull { element ->
        val o = element as? JsonObject ?: return@mapNotNull null
        val x = (o["x"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        val y = (o["y"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        LinePoint(x.toFloat(), y.toFloat())
    }
    if (parsed != null && parsed.size >= 2) return parsed
    return listOf(
        LinePoint(obj.x.toFloat(), obj.y.toFloat()),
        LinePoint((obj.x + obj.width).toFloat(), (obj.y + obj.height).toFloat()),
    )
}

/**
 * Same {value, barSizePercent} shape as level-indicator's calibrationPoints
 * (reusing [calculateFillPercent] from LevelIndicatorView.kt unchanged),
 * `barSizePercent` reinterpreted as a stroke width in px here rather than a
 * fill percentage - see render-mqtt-data-line.ts's header comment (designer
 * repo) for why the field name stays as-is instead of a parallel struct.
 * Default differs from level-indicator's (0/100 -> 1px/6px, not 0/100).
 */
private fun parseFlowCalibrationPoints(props: JsonObject): List<Pair<Double, Double>> {
    val array = props["calibrationPoints"] as? JsonArray ?: return listOf(0.0 to 1.0, 100.0 to 6.0)
    val points = array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val value = (obj["value"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        val strokeWidth = (obj["barSizePercent"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        value to strokeWidth
    }
    return points.ifEmpty { listOf(0.0 to 1.0, 100.0 to 6.0) }
}

/**
 * A filled-triangle arrowhead pointing from [from] toward [tip] (outward, in
 * the line's direction of travel at that end) - mirrors render-line.ts's
 * drawArrowhead() (designer) / ScreenRenderer::drawArrowhead() (firmware)
 * geometry (same arrowLength/arrowHalfWidth formula), drawn via a filled
 * android.graphics.Path rather than a hand-rolled pixel-exact scanline fill -
 * Android's rendering isn't held to the same byte-exact parity as designer/
 * firmware (tolerance-based HIL comparison, not strict), so there's no need
 * to reimplement Adafruit_GFX::fillTriangle()'s algorithm here.
 */
private fun drawArrowhead(canvas: android.graphics.Canvas, tip: LinePoint, from: LinePoint, strokeWidth: Float, paint: android.graphics.Paint) {
    val dx = tip.x - from.x
    val dy = tip.y - from.y
    val len = hypot(dx, dy)
    if (len == 0f) return

    val dirX = dx / len
    val dirY = dy / len
    val perpX = -dirY
    val perpY = dirX

    val arrowLength = max(6f, strokeWidth * 3f)
    val arrowHalfWidth = max(4f, strokeWidth * 2f)

    val backX = tip.x - dirX * arrowLength
    val backY = tip.y - dirY * arrowLength

    val path = android.graphics.Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(backX + perpX * arrowHalfWidth, backY + perpY * arrowHalfWidth)
        lineTo(backX - perpX * arrowHalfWidth, backY - perpY * arrowHalfWidth)
        close()
    }
    canvas.drawPath(path, paint)
}

/**
 * A flow-visualization line (2026-07-31, mirrors the designer's
 * render-mqtt-data-line.ts and the firmware's
 * ScreenRenderer::renderMqttDataLine() field-for-field): stroke width
 * reacts to the bound topic's magnitude via calibration points, and each
 * end's arrowhead shows independently based on its own operator+value
 * condition against that same topic's signed value (reuses
 * [evaluateCondition] from TabControlView.kt - same mechanism a tab-control
 * panel already uses to pick its active child).
 *
 * No fillet-radius support yet (straight segments only) - the designer/
 * firmware both support it, but a flow line's primary use case (a direct
 * run between two points, e.g. solar panel to battery) rarely needs
 * rounded corners, and porting the tangent/arc geometry isn't worth it
 * until a real project actually needs a filleted MqttDataLine on Android.
 *
 * Drawn as a full-screen-sized overlay in screen-absolute coordinates
 * (not the offset+size-to-obj.width/height Box pattern most other object
 * views use) - MqttDataLine's own bounding box can be much smaller than
 * its arrowheads' actual paint extent (e.g. height=1 for a horizontal
 * line), which a tightly-sized Canvas would silently clip.
 */
@Composable
fun MqttDataLineView(obj: ScreenObject, rawValue: String) {
    val props = obj.properties
    val color = props.colorOrDefault("color", Color.Black)
    val points = parsePoints(obj)

    val numericValue = rawValue.toDoubleOrNull() ?: 0.0
    val strokeWidthPx = max(
        1.0,
        calculateFillPercent(abs(numericValue), parseFlowCalibrationPoints(props)).roundToInt().toDouble(),
    ).toFloat()

    val showArrowStart = evaluateCondition(rawValue, props.string("arrowStartOperator", "<"), props.string("arrowStartValue", "0"))
    val showArrowEnd = evaluateCondition(rawValue, props.string("arrowEndOperator", ">"), props.string("arrowEndValue", "0"))

    if (points.size < 2) return

    Canvas(modifier = Modifier.fillMaxSize()) {
        drawIntoCanvas { composeCanvas ->
            val canvas = composeCanvas.nativeCanvas
            val strokePaint = android.graphics.Paint().apply {
                isAntiAlias = false
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = strokeWidthPx
                strokeCap = android.graphics.Paint.Cap.BUTT
                this.color = color.toArgb()
            }

            val path = android.graphics.Path().apply {
                moveTo(points[0].x, points[0].y)
                for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
            }
            canvas.drawPath(path, strokePaint)

            val fillPaint = android.graphics.Paint().apply {
                isAntiAlias = false
                style = android.graphics.Paint.Style.FILL
                this.color = color.toArgb()
            }
            if (showArrowStart) drawArrowhead(canvas, points[0], points[1], strokeWidthPx, fillPaint)
            if (showArrowEnd) drawArrowhead(canvas, points[points.size - 1], points[points.size - 2], strokeWidthPx, fillPaint)
        }
    }
}
