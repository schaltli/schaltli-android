package com.schaltli.android.ui.objects

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.schaltli.android.data.ScreenObject
import com.schaltli.android.render.LineRaster
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

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
 * A flow-visualization line (2026-07-31, mirrors the designer's
 * render-mqtt-data-line.ts and the firmware's
 * ScreenRenderer::renderMqttDataLine() field-for-field): stroke width
 * reacts to the bound topic's magnitude via calibration points, and each
 * end's arrowhead shows independently based on its own operator+value
 * condition against that same topic's signed value (reuses
 * [evaluateCondition] from TabControlView.kt - same mechanism a tab-control
 * panel already uses to pick its active child).
 *
 * Fillets and arrows as on a plain line (LineRaster, since 2026-10-13).
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
    val points = linePoints(obj)

    // No value yet, no line - not the thinnest one, which would read as
    // "nothing flowing".
    if (rawValue.isBlank()) return

    val numericValue = rawValue.toDoubleOrNull() ?: 0.0
    val strokeWidthPx = max(
        1.0,
        calculateFillPercent(abs(numericValue), parseFlowCalibrationPoints(props)).roundToInt().toDouble(),
    ).toFloat()

    val showArrowStart = evaluateCondition(rawValue, props.string("arrowStartOperator", "<"), props.string("arrowStartValue", "0"))
    val showArrowEnd = evaluateCondition(rawValue, props.string("arrowEndOperator", ">"), props.string("arrowEndValue", "0"))

    if (points.size < 2) return

    // The designer's render-mqtt-data-line.ts: the same pixel-exact body,
    // fillets and arrows as a plain line (LineRaster), only its width and
    // which arrows show follow the value. Drawn through Android's own path
    // until 2026-10-13, which smoothed nothing but knew no fillet.
    val fillet = props.doubleOrNull("filletRadius")?.let { max(0.0, it) } ?: 0.0
    LinePixels(color) { plot -> LineRaster.plotLine(points, strokeWidthPx.toDouble(), fillet, showArrowStart, showArrowEnd, plot) }
}
