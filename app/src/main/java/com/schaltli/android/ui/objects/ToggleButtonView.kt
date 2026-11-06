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
import com.schaltli.android.data.ToggleLook
import com.schaltli.android.render.PaintedPill
import com.schaltli.android.render.PillBand
import com.schaltli.android.render.drawPills
import com.schaltli.android.render.fillRoundRect
import com.schaltli.android.render.fillRoundRectRing
import com.schaltli.android.render.pillBitmap
import com.schaltli.android.ui.LocalBundleInstallation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A Toggle Button, drawn from its looks (the designer's device contract,
 * «toggle-button»; docs/2026-11-06-toggle-button.md there): one tile in the
 * look of the state the topic reports, or `noneLook` with «?».
 *
 * Nothing about how it looks is decided here, as for a [SoftwareButtonView]:
 * the designer works out a button's faces per state, where icon and text go,
 * and for a named tile the second line and the disc behind the icon
 * (lib/toggle-button-shape.ts). This draws the disc, then the face - its
 * outline the 2-px ring while a request waits - the state's icon picture and
 * one or two lines. Dark is [com.schaltli.android.data.darkVariantOf]'s:
 * `looksDark` replaces `looks` whole.
 *
 * A tap sends the next state, after the last the first; with nothing reported
 * the first state that shows as on, else the first (toggleNextIndex).
 */
@Composable
fun ToggleButtonView(
    obj: ScreenObject,
    project: Project,
    currentValue: String,
    /** What a finger here asked for and nothing has confirmed yet. */
    askedValue: String = "",
    screenBackgroundColor: String? = null,
    assetFileOf: (String) -> java.io.File,
    onAction: (ButtonAction) -> Unit,
    onAsked: (readTopic: String, readValue: String) -> Unit = { _, _ -> },
) {
    val props = obj.properties
    val states = remember(props) { parseToggleStates(props) }
    val installation = LocalBundleInstallation.current
    val background = screenBackgroundColor ?: "#ffffff"
    val soft = project.colorDepth == "24bit"
    val w = obj.width.toInt()
    val h = obj.height.toInt()
    var pressed by remember(obj.id) { mutableStateOf(false) }

    val readTopic = props.stringOrNull("topic")
    val activeIndex = toggleStateIndex(readTopic, states, currentValue)
    val askedIndex = if (askedValue.isBlank()) -1 else toggleStateIndex(readTopic, states, askedValue)
    val look: ToggleLook? = obj.looks?.getOrNull(activeIndex) ?: obj.noneLook
    val ring = if (askedIndex >= 0 && askedIndex != activeIndex) obj.ring else null
    val face = look?.let { if (pressed) it.pressed else it.normal }

    val icons: Map<String, Bitmap> = remember(states, installation) {
        states.flatMap { listOfNotNull(it.iconPath, it.iconPathPressed) }.distinct().mapNotNull { path ->
            val file = assetFileOf(path)
            if (!file.exists()) return@mapNotNull null
            BitmapFactory.decodeFile(file.path)?.let { path to it }
        }.toMap()
    }
    fun typefaceOf(meta: FontEntry?): Typeface =
        meta?.path?.let { assetFileOf(it) }?.takeIf { it.exists() }
            ?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() } ?: Typeface.DEFAULT
    val fontMeta: FontEntry? = project.fonts.firstOrNull { it.id == props.stringOrNull("fontId") }
    val secondMeta: FontEntry? = look?.second?.let { s -> project.fonts.firstOrNull { it.id == s.fontId } } ?: fontMeta
    val typeface = remember(fontMeta?.path, installation) { typefaceOf(fontMeta) }
    val secondTypeface = remember(secondMeta?.path, installation) { typefaceOf(secondMeta) }

    val pills = if (!soft || look == null || face == null) null else remember(obj.id, w, h, look, pressed, ring, background) {
        pillBitmap(togglePills(w, h, look, face, pressed, ring), background)
    }

    val density = LocalDensity.current
    val writeTopic = props.stringOrNull("writeTopic")

    Box(
        modifier = Modifier
            .offset(x = obj.x.toInt().dp, y = obj.y.toInt().dp)
            .size(width = obj.width.dp, height = obj.height.dp)
            .pointerInput(obj.id, states, activeIndex, writeTopic) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = {
                        if (writeTopic.isNullOrEmpty()) return@detectTapGestures
                        val index = toggleNextIndex(states, activeIndex)
                        val state = states.getOrNull(index) ?: return@detectTapGestures
                        if (state.writeValue.isEmpty()) return@detectTapGestures
                        onAction(ButtonAction(type = "send-mqtt", mqttTopic = writeTopic, mqttMessage = state.writeValue, literal = true))
                        if (!readTopic.isNullOrEmpty() && state.readValue.isNotEmpty()) onAsked(readTopic, state.readValue)
                    },
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
                    // Whole pixels, back to front: the face (the ring cut out
                    // of its pill), then the disc over it.
                    val paint = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }
                    val edge = ring ?: f.outline
                    val t = if (ring != null) 2 else 1
                    if (edge != null) {
                        argbOf(edge)?.let {
                            paint.color = it
                            fillRoundRectRing(native, paint, 0, 0, w, h, f.r, t, scale)
                        }
                        f.fill?.let(::argbOf)?.let {
                            paint.color = it
                            fillRoundRect(native, paint, t, t, w - 2 * t, h - 2 * t, maxOf(0, f.r - t), scale)
                        }
                    } else {
                        f.fill?.let(::argbOf)?.let {
                            paint.color = it
                            fillRoundRect(native, paint, 0, 0, w, h, f.r, scale)
                        }
                    }
                    l.disc?.takeIf { it.r > 0 }?.let { d ->
                        val fill = if (pressed) d.pressed else d.normal
                        val discEdge = d.edge?.let { if (pressed) it.pressed else it.normal }
                        if (discEdge != null && d.r > 1) {
                            argbOf(discEdge)?.let {
                                paint.color = it
                                fillRoundRect(native, paint, d.cx - d.r, d.cy - d.r, 2 * d.r, 2 * d.r, d.r, scale)
                            }
                            argbOf(fill)?.let {
                                paint.color = it
                                fillRoundRect(native, paint, d.cx - d.r + 1, d.cy - d.r + 1, 2 * d.r - 2, 2 * d.r - 2, d.r - 1, scale)
                            }
                        } else {
                            argbOf(fill)?.let {
                                paint.color = it
                                fillRoundRect(native, paint, d.cx - d.r, d.cy - d.r, 2 * d.r, 2 * d.r, d.r, scale)
                            }
                        }
                    }
                }

                val box = l.icon
                val state = states.getOrNull(activeIndex)
                val iconPath = (if (pressed) state?.iconPathPressed else null) ?: state?.iconPath
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

                if (l.clipW > 0) {
                    val ink = argbOf(f.ink) ?: Color.Black.toArgb()
                    native.save()
                    native.clipRect(l.clipX * scale, 0f, (l.clipX + l.clipW) * scale, h * scale)
                    val second = l.second
                    if (second != null) {
                        val name = props.stringOrNull("text") ?: ""
                        val pen = UnitTextPen(typeface, fontMeta?.size ?: 14, scale, ink)
                        native.drawText(name, l.textX * scale, l.baseline * scale, pen.draw)
                        val small = UnitTextPen(secondTypeface, secondMeta?.size ?: 14, scale, ink)
                        native.drawText(l.label, second.textX * scale, second.baseline * scale, small.draw)
                    } else {
                        val pen = UnitTextPen(typeface, fontMeta?.size ?: 14, scale, ink)
                        native.drawText(l.label, l.textX * scale, l.baseline * scale, pen.draw)
                    }
                    native.restore()
                }
            }
        }
    }
}

internal data class ToggleState(
    val readValue: String,
    val writeValue: String,
    val isOn: Boolean,
    val iconPath: String?,
    val iconPathPressed: String?,
)

internal fun parseToggleStates(props: JsonObject): List<ToggleState> {
    val array = props["states"] as? JsonArray ?: return emptyList()
    return array.filterIsInstance<JsonObject>().map { entry ->
        ToggleState(
            readValue = entry.stringOrNull("readValue") ?: "",
            writeValue = entry.stringOrNull("writeValue") ?: "",
            isOn = (entry["showAsOn"] ?: entry["showMarker"])?.jsonPrimitive?.booleanOrNull ?: false,
            iconPath = entry.stringOrNull("iconPath"),
            iconPathPressed = entry.stringOrNull("iconPathPressed"),
        )
    }
}

/** The state the topic reports; -1 with no topic, no value or no match. */
internal fun toggleStateIndex(readTopic: String?, states: List<ToggleState>, value: String): Int {
    if (readTopic.isNullOrEmpty()) return -1
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return -1
    return states.indexOfFirst { it.readValue.trim() == trimmed }
}

/** The designer's toggleNextIndex: the next state, from none the first that shows as on. */
internal fun toggleNextIndex(states: List<ToggleState>, activeIndex: Int): Int {
    if (states.isEmpty()) return -1
    if (activeIndex < 0 || activeIndex >= states.size) {
        val on = states.indexOfFirst { it.isOn }
        return if (on >= 0) on else 0
    }
    return (activeIndex + 1) % states.size
}

/**
 * The tile's runs in priority order, as the designer's toggleRuns
 * (render-toggle-button.ts): the disc - ringed where its tint is lost - then
 * the face, its outline the 2-px ring while a request waits.
 */
internal fun togglePills(w: Int, h: Int, look: ToggleLook, face: ButtonFace, pressed: Boolean, ring: String?): List<PaintedPill> {
    val runs = mutableListOf<PaintedPill>()
    look.disc?.takeIf { it.r > 0 }?.let { d ->
        val fill = if (pressed) d.pressed else d.normal
        val edge = d.edge?.let { if (pressed) it.pressed else it.normal }
        if (edge != null && d.r > 1) runs += PaintedPill(PillBand(d.cx - d.r + 1, d.cy - d.r + 1, 2 * d.r - 2, 2 * d.r - 2, d.r - 1, d.r - 1), fill)
        runs += PaintedPill(PillBand(d.cx - d.r, d.cy - d.r, 2 * d.r, 2 * d.r, d.r, d.r), edge ?: fill)
    }
    val edge = ring ?: face.outline
    val t = if (ring != null) 2 else 1
    if (edge != null) {
        val r = maxOf(0, face.r - t)
        runs += PaintedPill(PillBand(t, t, w - 2 * t, h - 2 * t, r, r), face.fill)
        runs += PaintedPill(PillBand(0, 0, w, h, face.r, face.r), edge)
    } else if (face.fill != null) {
        runs += PaintedPill(PillBand(0, 0, w, h, face.r, face.r), face.fill)
    }
    return runs
}

private fun argbOf(color: String): Int? = parseHexColor(color)?.takeIf { it != Color.Transparent }?.toArgb()
