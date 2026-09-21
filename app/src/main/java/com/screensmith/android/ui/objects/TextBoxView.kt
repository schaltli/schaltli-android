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
import com.screensmith.android.ui.LocalBundleInstallation
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Shared background/border/text rendering for anything that's fundamentally
 * a text box: static labels and MQTT data fields, same as the designer's
 * own `render-text-box.ts` is shared by `render-label.ts`/
 * `render-mqtt-field.ts` - the same reasoning applies here: the actual
 * pixels must come from one implementation, not two that can drift apart.
 *
 * Text is drawn via a native Canvas/Paint at an explicit baseline
 * (obj.y + fontAscent), not Compose's Text composable - Text manages its
 * own baseline from font metrics it derives internally, which measurably
 * drifted from both the designer's ctx.fillText(text, x, y + fontAscent)
 * and the firmware's u8g2 baseline draw (2026-07-27 HIL finding). Mirroring
 * the explicit-baseline approach keeps all three in lockstep the same way
 * getBoundingBoxHeight() already does for the box height.
 */
@Composable
fun TextBoxView(obj: ScreenObject, project: Project, text: String, assetFileOf: (String) -> java.io.File) {
    val props = obj.properties
    val backgroundColor = props.colorOrDefault("backgroundColor", Color.White)
    val borderColor = props.colorOrDefault("borderColor", Color(0xFFCCCCCC))
    val textColor = (props.stringOrNull("color") ?: props.stringOrNull("textColor"))
        ?.let(::parseHexColor) ?: Color.Black
    val textAlign = props.string("textAlign", "left")

    val fontId = (props["fontId"] as? JsonPrimitive)?.contentOrNull
    val fontMeta: FontEntry? = project.fonts.find { it.id == fontId }
    val fontSize = fontMeta?.size ?: 14
    val fontAscent = fontMeta?.ascent ?: (fontSize * 0.8f).toInt()
    val lineHeight = fontSize * 1.2f

    val typefacePath = fontMeta?.path?.let { assetFileOf(it) }
    val typeface = remember(typefacePath, LocalBundleInstallation.current) {
        typefacePath?.takeIf { it.exists() }?.let { Typeface.createFromFile(it) } ?: Typeface.DEFAULT
    }

    // Background/border/clip height comes from the resolved font's own size
    // (ascent+descent), NOT obj.height - a separate, independently-
    // resizable JSON field the designer's box bounds don't actually track
    // either. Mirrors render-text-box.ts's getBoundingBoxHeight() and
    // ScreenRenderer.cpp's drawTextBox() (both explicitly fixed 2026-07-20
    // after using the object's declared height produced a box a different
    // size than the designer's, HIL-visible as text/box drift) - this
    // Android view had drifted from both by still using obj.height
    // (2026-07-27 HIL finding).
    val boxHeightDp = (fontMeta?.size?.toFloat() ?: obj.height.toFloat()).dp

    val density = LocalDensity.current
    val textSizePx = with(density) { fontSize.dp.toPx() }
    val ascentPx = with(density) { fontAscent.dp.toPx() }
    val lineHeightPx = with(density) { lineHeight.dp.toPx() }
    // 1 project unit = 1dp everywhere else in this box (offset/size above),
    // so the border's declared width of 1 follows the same conversion.
    val borderWidthPx = with(density) { 1.dp.toPx() }

    Box(
        modifier = Modifier
            .offset(x = obj.x.dp, y = obj.y.dp)
            .size(width = obj.width.dp, height = boxHeightDp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawIntoCanvas { canvas ->
                val fillPaint = android.graphics.Paint().apply { isAntiAlias = false; style = android.graphics.Paint.Style.FILL }

                // Background + border as two nested fills (outer full rect in
                // border color, inner inset rect in background color on top),
                // not Compose's .background()/.border() modifiers - those
                // draw a *stroked* rect whose width in native pixels gets
                // rounded/anti-aliased by Compose's own border-drawing path,
                // which rendered visibly thicker than the reference on this
                // device (2026-07-27 HIL finding, same class of bug as the
                // static box's border - see render-box.ts / RenderBox.kt).
                // A plain, non-anti-aliased fillRect is always pixel-crisp on
                // an axis-aligned canvas, matching the designer's own
                // drawBoxBackground()/drawBoxBorder() in render-text-box.ts.
                if (borderColor != Color.Transparent) {
                    fillPaint.color = borderColor.toArgb()
                    canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, fillPaint)
                }
                if (backgroundColor != Color.Transparent) {
                    fillPaint.color = backgroundColor.toArgb()
                    val inset = if (borderColor != Color.Transparent) borderWidthPx else 0f
                    canvas.nativeCanvas.drawRect(inset, inset, size.width - inset, size.height - inset, fillPaint)
                }

                val textPaint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    this.typeface = typeface
                    this.textSize = textSizePx
                    color = textColor.toArgb()
                    this.textAlign = when (textAlign) {
                        "center" -> android.graphics.Paint.Align.CENTER
                        "right" -> android.graphics.Paint.Align.RIGHT
                        else -> android.graphics.Paint.Align.LEFT
                    }
                }
                val xPx = when (textAlign) {
                    "center" -> size.width / 2f
                    "right" -> size.width
                    else -> 0f
                }
                text.split("\n").forEachIndexed { index, line ->
                    canvas.nativeCanvas.drawText(line, xPx, ascentPx + index * lineHeightPx, textPaint)
                }
            }
        }
    }
}
