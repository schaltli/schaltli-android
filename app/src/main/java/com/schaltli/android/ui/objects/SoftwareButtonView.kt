package com.schaltli.android.ui.objects

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.schaltli.android.data.ButtonAction
import com.schaltli.android.data.ButtonFace
import com.schaltli.android.data.FontEntry
import com.schaltli.android.data.Project
import com.schaltli.android.data.ScreenObject
import com.schaltli.android.mqtt.actionOf
import com.schaltli.android.render.PaintedPill
import com.schaltli.android.render.PillBand
import com.schaltli.android.render.drawPills
import com.schaltli.android.render.fillRoundRect
import com.schaltli.android.render.fillRoundRectRing
import com.schaltli.android.render.pillBitmap
import com.schaltli.android.ui.LocalBundleInstallation

/**
 * A SoftwareButton, drawn from its look (the designer's device contract,
 * «button»; docs/2026-11-04-native-buttons.md there).
 *
 * Nothing about how it looks is decided here. The designer works out both
 * faces - radius, fill, ring, ink - and where icon and label go
 * (lib/button-shape.ts), and this draws exactly that, in the designer's order:
 * the pill through the Switch's rasteriser ([pillBitmap]: soft at 24 bit,
 * whole pixels below), or with a ring the inside run first - the fill, or a
 * hole - then the whole button in the ring's colour; the icon's square as the
 * designer drew it in this face; the label in the project's font, clipped to
 * the band the look names.
 *
 * Until 2026-11-04 the designer baked the whole button into two pictures and
 * this only blitted them. That was 85 % of a project on the 4" and did not
 * fit; the rasteriser the Switch shares with the designer and the firmware is
 * why the pill can now be drawn here without a second set of pixels.
 */
@Composable
fun SoftwareButtonView(
    obj: ScreenObject,
    project: Project,
    assetFileOf: (String) -> java.io.File,
    onAction: (ButtonAction) -> Unit,
    /** What the button stands on: what a soft edge mixes into. */
    screenBackgroundColor: String? = null,
) {
    var pressed by remember(obj.id) { mutableStateOf(false) }
    val installation = LocalBundleInstallation.current
    val look = obj.look
    val background = screenBackgroundColor ?: "#ffffff"
    val soft = project.colorDepth == "24bit"
    val w = obj.width.toInt()
    val h = obj.height.toInt()

    // Decoded once per installation, keyed by which installation the files
    // came from: two bundles have the same file names.
    val icons: Map<String, Bitmap> = remember(obj.iconPath, obj.iconPathPressed, installation) {
        listOfNotNull(obj.iconPath, obj.iconPathPressed).distinct().mapNotNull { path ->
            val file = assetFileOf(path)
            if (!file.exists()) return@mapNotNull null
            BitmapFactory.decodeFile(file.path)?.let { path to it }
        }.toMap()
    }
    val fontMeta: FontEntry? = project.fonts.firstOrNull { it.id == obj.properties.stringOrNull("fontId") }
    val typeface = remember(fontMeta?.path, installation) {
        fontMeta?.path?.let { assetFileOf(it) }?.takeIf { it.exists() }
            ?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() } ?: Typeface.DEFAULT
    }
    val face = look?.let { if (pressed) it.pressed else it.normal }
    // Rasterized once per face and kept, as the Switch keeps its pills.
    val pills = if (!soft || face == null) null else remember(obj.id, w, h, face, background) {
        pillBitmap(buttonPills(w, h, face), background)
    }

    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .offset(x = obj.x.toInt().dp, y = obj.y.toInt().dp)
            .size(width = obj.width.dp, height = obj.height.dp)
            .pointerInput(obj.id) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { obj.actionOf()?.let(onAction) },
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val l = look ?: return@Canvas
            val f = face ?: return@Canvas
            val scale = density.density
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                if (soft) {
                    drawPills(native, pills, 0, 0, scale)
                } else {
                    // Whole pixels, one shape over another, as the designer's
                    // hardPills: the ring cut out of its pill, the inside in
                    // the fill where there is one.
                    val paint = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }
                    if (f.outline != null) {
                        argbOf(f.outline)?.let {
                            paint.color = it
                            fillRoundRectRing(native, paint, 0, 0, w, h, f.r, 1, scale)
                        }
                        f.fill?.let(::argbOf)?.let {
                            paint.color = it
                            fillRoundRect(native, paint, 1, 1, w - 2, h - 2, maxOf(0, f.r - 1), scale)
                        }
                    } else {
                        f.fill?.let(::argbOf)?.let {
                            paint.color = it
                            fillRoundRect(native, paint, 0, 0, w, h, f.r, scale)
                        }
                    }
                }

                val box = l.icon
                val iconPath = (if (pressed) obj.iconPathPressed else null) ?: obj.iconPath
                val bitmap = iconPath?.let { icons[it] }
                if (box != null && bitmap != null) {
                    native.drawBitmap(
                        bitmap,
                        null,
                        Rect(
                            (box.x * scale).toInt(),
                            (box.y * scale).toInt(),
                            ((box.x + box.size) * scale).toInt(),
                            ((box.y + box.size) * scale).toInt(),
                        ),
                        Paint().apply { isAntiAlias = false; isFilterBitmap = false },
                    )
                }

                val text = obj.properties.stringOrNull("text")?.takeIf { it.isNotEmpty() } ?: "Button"
                if (l.clipW > 0) {
                    val pen = UnitTextPen(typeface, fontMeta?.size ?: 14, scale, argbOf(f.ink) ?: Color.Black.toArgb())
                    native.save()
                    native.clipRect(l.clipX * scale, 0f, (l.clipX + l.clipW) * scale, h * scale)
                    native.drawText(text, l.textX * scale, l.baseline * scale, pen.draw)
                    native.restore()
                }
            }
        }
    }
}

/**
 * A face as the rasteriser's runs, in priority order - the designer's
 * `buttonPills` (lib/button-shape.ts), relative to the button.
 */
internal fun buttonPills(w: Int, h: Int, face: ButtonFace): List<PaintedPill> {
    val outer = PillBand(0, 0, w, h, face.r, face.r)
    if (face.outline != null) {
        val r = maxOf(0, face.r - 1)
        return listOf(
            PaintedPill(PillBand(1, 1, w - 2, h - 2, r, r), face.fill),
            PaintedPill(outer, face.outline),
        )
    }
    return if (face.fill != null) listOf(PaintedPill(outer, face.fill)) else emptyList()
}

private fun argbOf(color: String): Int? = parseHexColor(color)?.takeIf { it != Color.Transparent }?.toArgb()
