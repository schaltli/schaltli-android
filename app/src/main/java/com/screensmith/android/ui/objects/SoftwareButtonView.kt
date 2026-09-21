package com.screensmith.android.ui.objects

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import coil.compose.AsyncImage
import com.screensmith.android.data.ButtonAction
import com.screensmith.android.data.Project
import com.screensmith.android.data.ScreenObject
import com.screensmith.android.mqtt.actionOf
import com.screensmith.android.ui.LocalBundleInstallation

/**
 * Mirrors render-software-button.ts's 3D-button geometry exactly: the
 * visible button occupies only the upper-left (width - 3, height - 3) of
 * the object's own bounds, with a semi-transparent black rect drawn 3dp
 * down-and-right of it filling the remainder - the "shadow" that makes the
 * button read as raised. Android's previous version filled the *entire*
 * object bounds with no shadow at all, both a missing-element and a
 * differently-sized-button mismatch (2026-07-27 HIL finding - "der button
 * hat im device keinen schatten").
 *
 * An icon, when the button has one, sits at the left with 8 units of padding
 * and takes at most 30% of the button's width; the label then centres in
 * whatever is left rather than in the whole button. Same numbers as
 * render-software-button.ts, which is where they came from. The icon arrives
 * as a bundle-relative SVG path on `obj.path` (written by
 * lib/android-export.ts, already tinted with the button's own iconColor) -
 * this view never sees an assetId, so there is no second copy of the tint
 * rules here to drift from the designer's.
 */
@Composable
fun SoftwareButtonView(
    obj: ScreenObject,
    project: Project,
    assetFileOf: (String) -> java.io.File,
    onAction: (ButtonAction) -> Unit,
) {
    val props = obj.properties
    val backgroundColor = props.colorOrDefault("backgroundColor", Color.White)
    val borderColor = props.colorOrDefault("borderColor", Color(0xFFCCCCCC))
    val borderWidth = props.double("borderWidth", 1.0)
    val cornerRadius = props.double("cornerRadius", 4.0)
    val textColor = props.colorOrDefault("textColor", Color.Black)
    val text = props.string("text", "Button")

    val fontId = props.stringOrNull("fontId")
    val fontMeta = project.fonts.find { it.id == fontId }
    val fontSize = fontMeta?.size ?: 14
    val typeface = remember(fontMeta?.path, LocalBundleInstallation.current) {
        fontMeta?.path?.let { assetFileOf(it) }?.takeIf { it.exists() }?.let { Typeface.createFromFile(it) } ?: Typeface.DEFAULT
    }

    val density = LocalDensity.current

    // "At most 30% of the width", measured against the visible button rather
    // than the object - the shadow is not part of the button's face.
    val iconPath = obj.path
    val iconSizeUnits = kotlin.math.min(obj.height - 3 - ICON_PADDING * 2, (obj.width - 3) * 0.3)
        .coerceAtLeast(0.0)
    val paddingPx = with(density) { ICON_PADDING.dp.toPx() }
    val iconSize = if (iconPath != null && iconSizeUnits > 0) {
        with(density) { iconSizeUnits.dp.toPx() }
    } else {
        null
    }

    Box(
        modifier = Modifier
            .offset(x = obj.x.dp, y = obj.y.dp)
            .size(width = obj.width.dp, height = obj.height.dp)
            .clickable {
                obj.actionOf()?.let(onAction)
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val shadowOffsetPx = with(density) { 3.dp.toPx() }
            val buttonW = size.width - shadowOffsetPx
            val buttonH = size.height - shadowOffsetPx
            val radiusPx = with(density) { cornerRadius.dp.toPx() }
            val borderWidthPx = with(density) { borderWidth.dp.toPx() }

            drawIntoCanvas { canvas -> canvas.nativeCanvas.let { native ->
                val paint = android.graphics.Paint().apply { isAntiAlias = true; style = android.graphics.Paint.Style.FILL }

                paint.color = android.graphics.Color.argb(77, 0, 0, 0) // rgba(0,0,0,0.3)
                native.drawRoundRect(
                    shadowOffsetPx, shadowOffsetPx, shadowOffsetPx + buttonW, shadowOffsetPx + buttonH,
                    radiusPx, radiusPx, paint,
                )

                if (backgroundColor != Color.Transparent) {
                    paint.color = backgroundColor.toArgb()
                    native.drawRoundRect(0f, 0f, buttonW, buttonH, radiusPx, radiusPx, paint)
                }

                if (borderColor != Color.Transparent && borderWidthPx > 0) {
                    val strokePaint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = borderWidthPx
                        color = borderColor.toArgb()
                    }
                    val half = borderWidthPx / 2f
                    native.drawRoundRect(half, half, buttonW - half, buttonH - half, radiusPx, radiusPx, strokePaint)
                }

                val textPaint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    this.typeface = typeface
                    this.textSize = with(density) { fontSize.dp.toPx() }
                    color = textColor.toArgb()
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                // With an icon present the label centres in what is left of
                // the button, not in the whole of it - contentWidth is
                // measured off the object's own width, matching the
                // designer's `obj.width - (contentStartX - obj.x) - padding`
                // rather than the shadow-shortened buttonW.
                val centerX = if (iconSize != null) {
                    val contentStartX = paddingPx + iconSize + paddingPx
                    contentStartX + (size.width - contentStartX - paddingPx) / 2f
                } else {
                    buttonW / 2f
                }
                val centerY = buttonH / 2f
                val baselineY = centerY - (textPaint.ascent() + textPaint.descent()) / 2f
                native.drawText(text, centerX, baselineY, textPaint)
            } }
        }

        // The icon rides above the Canvas because an SVG needs Coil's
        // decoder, which is a composable concern - the same split SwitchView
        // makes. It never overlaps the label: the label's own centre was
        // moved past it above.
        if (iconPath != null && iconSize != null) {
            AsyncImage(
                model = assetFileOf(iconPath),
                contentDescription = null,
                modifier = Modifier
                    .offset(x = ICON_PADDING.dp, y = ((obj.height - 3 - iconSizeUnits) / 2).dp)
                    .size(iconSizeUnits.dp),
            )
        }
    }
}

/** Padding between the button's edge, its icon, and its label - the designer's own `padding = 8`. */
private const val ICON_PADDING = 8
