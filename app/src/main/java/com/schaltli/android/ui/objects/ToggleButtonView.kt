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
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A Toggle Button, drawn from its looks (the designer's device contract,
 * «toggle-button»; docs/2026-11-06-toggle-button.md and, since 2026-11-11,
 * docs/2026-11-11-led-look.md there): a calm tile and a light - Korry's
 * window or an LED strip - in the look of the state the topic reports, or
 * `noneLook` with «?».
 *
 * Nothing about how it looks is decided here, as for a [SoftwareButtonView]:
 * the designer works out the tile, the light, where icon and text go
 * (lib/toggle-button-shape.ts). While a request waits the asked state's look
 * is drawn with its light pulsing between `asked` and `askedDim` every
 * [TOGGLE_ASKED_HALF_MS]. Dark is [com.schaltli.android.data.darkVariantOf]'s:
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
    val asking = askedIndex >= 0 && askedIndex != activeIndex
    val shownIndex = if (asking) askedIndex else activeIndex
    val look: ToggleLook? = obj.looks?.getOrNull(shownIndex) ?: obj.noneLook
    val face = look?.let { if (pressed) it.pressed else it.normal }
    // The pulse's beat while asked (docs/2026-11-11-led-look.md, decision 4).
    var dimHalf by remember(obj.id) { mutableStateOf(false) }
    LaunchedEffect(obj.id, asking) {
        dimHalf = false
        while (asking) {
            delay(TOGGLE_ASKED_HALF_MS - (System.currentTimeMillis() % TOGGLE_ASKED_HALF_MS))
            dimHalf = (System.currentTimeMillis() / TOGGLE_ASKED_HALF_MS) % 2 == 1L
        }
    }
    val lightFill = look?.light?.let { if (!asking) it.fill else if (dimHalf) it.askedDim else it.asked }

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
    val secondMeta: FontEntry? = (look?.second?.fontId ?: look?.light?.label?.fontId)?.let { id -> project.fonts.firstOrNull { it.id == id } } ?: fontMeta
    val typeface = remember(fontMeta?.path, installation) { typefaceOf(fontMeta) }
    val secondTypeface = remember(secondMeta?.path, installation) { typefaceOf(secondMeta) }

    val pills = if (!soft || look == null || face == null) null else remember(obj.id, w, h, look, pressed, lightFill, background) {
        pillBitmap(togglePills(w, h, look, face, lightFill), background)
    }

    val density = LocalDensity.current
    val writeTopic = props.stringOrNull("writeTopic")
    // Guarded (docs/2026-11-15-guard.md in the designer): its cover takes a
    // touch until pushed up; then a tap switches and tops the wait up.
    val guard = rememberGuard(obj)
    val guards = LocalGuards.current

    Box(
        modifier = Modifier
            .offset(x = obj.x.toInt().dp, y = obj.y.toInt().dp)
            .size(width = obj.width.dp, height = obj.height.dp)
            .guarded(obj, guard, guards, density.density)
            .pointerInput(obj.id, states, activeIndex, writeTopic) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = {
                        if (writeTopic.isNullOrEmpty()) return@detectTapGestures
                        if (guard != null && !guard.unlocked) return@detectTapGestures
                        guard?.changed()
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
                    // Whole pixels, back to front: the tile, then the light.
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
                    l.light?.takeIf { it.w > 0 && it.h > 0 }?.let { light ->
                        val fill = lightFill ?: light.fill
                        if (light.outline != null && light.w > 2 && light.h > 2) {
                            argbOf(light.outline)?.let {
                                paint.color = it
                                fillRoundRect(native, paint, light.x, light.y, light.w, light.h, light.r, scale)
                            }
                            argbOf(fill)?.let {
                                paint.color = it
                                fillRoundRect(native, paint, light.x + 1, light.y + 1, light.w - 2, light.h - 2, maxOf(0, light.r - 1), scale)
                            }
                        } else {
                            argbOf(fill)?.let {
                                paint.color = it
                                fillRoundRect(native, paint, light.x, light.y, light.w, light.h, light.r, scale)
                            }
                        }
                    }
                }

                val box = l.icon
                val state = states.getOrNull(shownIndex)
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

                // The name - or, an LED without a name, the state's label in its place.
                val ink = argbOf(f.ink) ?: Color.Black.toArgb()
                val name = props.stringOrNull("text")?.takeIf { it.isNotBlank() }
                val word = l.light?.label
                val first = name ?: if (word == null) l.label else null
                if (l.clipW > 0 && first != null) {
                    native.save()
                    native.clipRect(l.clipX * scale, 0f, (l.clipX + l.clipW) * scale, h * scale)
                    val pen = UnitTextPen(typeface, fontMeta?.size ?: 14, scale, ink)
                    native.drawText(first, l.textX * scale, l.baseline * scale, pen.draw)
                    native.restore()
                }
                l.second?.let { second ->
                    val small = UnitTextPen(secondTypeface, secondMeta?.size ?: 14, scale, second.ink?.let(::argbOf) ?: ink)
                    native.drawText(l.label, second.textX * scale, second.baseline * scale, small.draw)
                }
                val light = l.light
                if (word != null && light != null) {
                    native.save()
                    native.clipRect(light.x * scale, light.y * scale, (light.x + light.w) * scale, (light.y + light.h) * scale)
                    val pen = UnitTextPen(secondTypeface, secondMeta?.size ?: 14, scale, argbOf(if (asking) word.askedInk else word.ink) ?: ink)
                    native.drawText(word.text, word.textX * scale, word.baseline * scale, pen.draw)
                    native.restore()
                }
                // Guarded: the lock in the top-right corner, in the ink (decision 4).
                if (guard != null) drawGuardCornerLock(native, obj, argbOf(f.ink) ?: Color.Black.toArgb(), scale)
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

/** How long each half of the asked pulse lasts, as the designer's ASKED_HALF_MS. */
internal const val TOGGLE_ASKED_HALF_MS = 500L

/**
 * The tile's runs in priority order, as the designer's toggleRuns
 * (render-toggle-button.ts): the light - inside a 1-px ring where it needs
 * one - then the tile, its edge and its fill.
 */
internal fun togglePills(w: Int, h: Int, look: ToggleLook, face: ButtonFace, lightFill: String?): List<PaintedPill> {
    val runs = mutableListOf<PaintedPill>()
    look.light?.takeIf { it.w > 0 && it.h > 0 }?.let { l ->
        val fill = lightFill ?: l.fill
        if (l.outline != null && l.w > 2 && l.h > 2) runs += PaintedPill(PillBand(l.x + 1, l.y + 1, l.w - 2, l.h - 2, maxOf(0, l.r - 1), maxOf(0, l.r - 1)), fill)
        runs += PaintedPill(PillBand(l.x, l.y, l.w, l.h, l.r, l.r), l.outline ?: fill)
    }
    if (face.outline != null) {
        val r = maxOf(0, face.r - 1)
        runs += PaintedPill(PillBand(1, 1, w - 2, h - 2, r, r), face.fill)
        runs += PaintedPill(PillBand(0, 0, w, h, face.r, face.r), face.outline)
    } else if (face.fill != null) {
        runs += PaintedPill(PillBand(0, 0, w, h, face.r, face.r), face.fill)
    }
    return runs
}

private fun argbOf(color: String): Int? = parseHexColor(color)?.takeIf { it != Color.Transparent }?.toArgb()
