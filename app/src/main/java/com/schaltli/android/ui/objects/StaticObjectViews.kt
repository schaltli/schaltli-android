package com.schaltli.android.ui.objects

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.schaltli.android.data.ScreenObject
import com.schaltli.android.render.BoxRaster
import com.schaltli.android.render.LineRaster
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

// Box and line, drawn by the app itself as every board draws them (device
// contract §2, «Static content»; tasks/android-draws-all-plan.md). Until
// 2026-10-13 both reached the app baked into a background picture per
// screen, which the export no longer makes - and which never held what sat
// in a switcher's panel, so a box there was simply missing.
//
// Both draw whole pixels in project units, each unit `density` device
// pixels, in a canvas over their parent: the screen, or a switcher's box for
// what its panel holds (coordinates there are the panel's own).

/** The designer's `|| default` on a property: a missing or empty value takes the default. */
private fun JsonObject.colorString(key: String, default: String): String =
    (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() } ?: default

@Composable
fun BoxView(obj: ScreenObject) {
    val props = obj.properties
    val fill = props.colorString("fillColor", "#e5e5e5")
    val stroke = props.colorString("strokeColor", "#000000")
    val strokeWidth = (props.doubleOrNull("strokeWidth") ?: 0.0).toInt()
    val hasBorder = !props.stringOrNull("strokeColor").isNullOrEmpty() && strokeWidth > 0
    val radius = (props.doubleOrNull("cornerRadius") ?: 0.0).toInt()
    val colors = HashMap<String, Int?>()
    Canvas(modifier = Modifier.fillMaxSize()) {
        val scale = density
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint().apply { isAntiAlias = false; style = android.graphics.Paint.Style.FILL }
            BoxRaster.plotBox(obj.x.toInt(), obj.y.toInt(), obj.width.toInt(), obj.height.toInt(), fill, stroke, strokeWidth, radius, hasBorder) { x, y, w, h, color ->
                val argb = colors.getOrPut(color) { parseHexColor(color)?.takeIf { it != Color.Transparent }?.toArgb() } ?: return@plotBox
                paint.color = argb
                canvas.nativeCanvas.drawRect(x * scale, y * scale, (x + w) * scale, (y + h) * scale, paint)
            }
        }
    }
}

/** A line's vertices: `points` when it has two or more, else its frame's diagonal (getLinePoints). */
fun linePoints(obj: ScreenObject): List<LineRaster.Point> {
    val raw = (obj.properties["points"] as? JsonArray)?.mapNotNull { p ->
        val o = p as? JsonObject ?: return@mapNotNull null
        val x = (o["x"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        val y = (o["y"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        LineRaster.Point(x, y)
    }
    if (raw != null && raw.size >= 2) return raw
    return listOf(LineRaster.Point(obj.x, obj.y), LineRaster.Point(obj.x + obj.width, obj.y + obj.height))
}

@Composable
fun LineView(obj: ScreenObject) {
    val props = obj.properties
    val color = parseHexColor(props.colorString("color", "#000000"))?.takeIf { it != Color.Transparent } ?: return
    val strokeWidth = props.doubleOrNull("strokeWidth")?.takeIf { it != 0.0 } ?: 1.0
    val fillet = props.doubleOrNull("filletRadius") ?: 0.0
    val arrowStart = (props["arrowStart"] as? JsonPrimitive)?.booleanOrNull == true
    val arrowEnd = (props["arrowEnd"] as? JsonPrimitive)?.booleanOrNull == true
    val points = linePoints(obj)
    LinePixels(color) { plot -> LineRaster.plotLine(points, strokeWidth, fillet, arrowStart, arrowEnd, plot) }
}

/** Whatever [draw] plots, one unit square per pixel, in [color]. */
@Composable
fun LinePixels(color: Color, draw: (plot: (Int, Int) -> Unit) -> Unit) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val scale = density
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint().apply {
                isAntiAlias = false
                style = android.graphics.Paint.Style.FILL
                this.color = color.toArgb()
            }
            draw { x, y -> canvas.nativeCanvas.drawRect(x * scale, y * scale, (x + 1) * scale, (y + 1) * scale, paint) }
        }
    }
}
