package com.screensmith.android.ui.objects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.screensmith.android.data.Project
import com.screensmith.android.data.ScreenObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToInt

/** Mirrors the designer's `calculateLevelIndicatorFill` (`render-level-indicator.ts`) exactly. */
private fun calculateFillPercent(value: Double, calibrationPoints: List<Pair<Double, Double>>): Double {
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
        val value = (obj["value"] as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        val barSizePercent = (obj["barSizePercent"] as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        value to barSizePercent
    }
    return points.ifEmpty { listOf(0.0 to 0.0, 100.0 to 100.0) }
}

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

    Box(
        modifier = Modifier
            .offset(x = obj.x.dp, y = obj.y.dp)
            .size(width = obj.width.dp, height = obj.height.dp)
            .background(backgroundColor)
            .border(width = 1.dp, color = borderColor),
    ) {
        // fillPercent can be exactly 0, which a Compose fraction of 0f
        // handles fine (zero-size fill box), so no extra guard is needed.
        val fillFraction = (fillPercent / 100.0).toFloat()
        when (barDirection) {
            "right-to-left" -> Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .fillMaxWidth(fillFraction)
                    .background(fillColor),
            )
            "top-to-bottom" -> Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(fillFraction)
                    .background(fillColor),
            )
            "bottom-to-top" -> Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(fillFraction)
                    .background(fillColor),
            )
            else -> Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .fillMaxWidth(fillFraction)
                    .background(fillColor),
            )
        }

        if (displayText != null) {
            Text(
                text = displayText,
                fontSize = 14.sp,
                color = props.colorOrDefault("textColor", Color.Black),
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
