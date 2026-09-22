package com.screensmith.android.ui.objects

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
import androidx.compose.runtime.mutableIntStateOf
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
import com.screensmith.android.data.ButtonAction
import com.screensmith.android.data.FontEntry
import com.screensmith.android.data.Project
import com.screensmith.android.data.ScreenObject
import com.screensmith.android.render.SwitchForm
import com.screensmith.android.render.SwitchRect
import com.screensmith.android.render.fillRoundRect
import com.screensmith.android.render.fillRoundRectRing
import com.screensmith.android.render.fillRoundRectSides
import com.screensmith.android.render.onColorFor
import com.screensmith.android.render.switchContent
import com.screensmith.android.render.switchFontMetrics
import com.screensmith.android.render.switchForm
import com.screensmith.android.render.switchKnob
import com.screensmith.android.render.switchKnobLook
import com.screensmith.android.render.switchLabelBox
import com.screensmith.android.render.switchLook
import com.screensmith.android.render.switchSegmentAt
import com.screensmith.android.render.switchSegments
import com.screensmith.android.render.switchSlotAt
import com.screensmith.android.render.switchStateIsOn
import com.screensmith.android.render.switchTrack
import com.screensmith.android.ui.LocalBundleInstallation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * A Switch: a state control in one of two Material 3 forms, from the object's
 * own type.
 *
 *   "button-group" - a **connected button group**: one container, the states
 *                    side by side in it, the chosen one as its own pill.
 *                    Material has retired the segmented button in favour of
 *                    this.
 *   "switch"       - a **switch**: a track with a knob standing at one of n
 *                    positions, the state's icon on the knob, its label
 *                    beside it. n is normally 2, where a tap toggles.
 *
 * Read-bound in both: the active state is whichever state's readValue matches
 * the current value of `topic`, an exact trimmed-string match. No match means
 * no state is active - the group marks nothing, the switch shows no knob and
 * a "?" beside it, because standing somewhere would claim a state nobody has
 * reported.
 *
 * What was asked for and what is reported are two different things, and the
 * control shows both, exactly as a settable level does with its handle and
 * its fill: the confirmed state keeps its pill and the asked one gets a ring,
 * and the knob moves at once while the colour follows only when the value
 * comes back.
 *
 * Every rectangle and every colour comes from
 * [com.screensmith.android.render.SwitchShape], this repo's copy of the
 * designer's `lib/switch-shape.ts`, which a golden test holds to the
 * designer's own numbers. Nothing here has a background, a border, a corner
 * radius or a text colour of its own any more - all of that was the look the
 * designer retired on 2026-09-20 (docs/2026-09-20-switch-look.md).
 *
 * The icons arrive baked: `lib/android-export.ts` draws each state's icon at
 * the size this form uses, in the ink the state takes, with its own margin
 * trimmed away, and writes the two variants into the bundle as PNGs. They are
 * blitted here with filtering off, like every other bitmap in this app, so
 * what the phone shows and what the designer drew are the same pixels rather
 * than two rasterisations of one SVG.
 */
private data class SwitchStateEntry(
    val label: String,
    val readValue: String,
    val writeValue: String,
    /** This state's icon in the ink it takes when it is not the chosen one. */
    val path: String?,
    /** The same picture - or the author's second one - in the chosen ink. */
    val activePath: String?,
    /** Does this state count as "switched on"? Only the knob form asks. */
    val isOn: Boolean,
)

private fun parseStates(props: JsonObject): List<SwitchStateEntry> {
    val array = props["states"] as? JsonArray ?: return emptyList()
    return array.filterIsInstance<JsonObject>().map { entry ->
        SwitchStateEntry(
            label = entry.stringOrNull("label") ?: "",
            readValue = entry.stringOrNull("readValue") ?: "",
            writeValue = entry.stringOrNull("writeValue") ?: "",
            path = entry.stringOrNull("path"),
            activePath = entry.stringOrNull("activePath"),
            isOn = switchStateIsOn(entry),
        )
    }
}

/**
 * Which state a value selects, or -1 for none - mirrors
 * `getActiveSwitchStateIndex` on both other sides, trim included.
 */
private fun stateIndexFor(obj: ScreenObject, states: List<SwitchStateEntry>, value: String): Int {
    if (obj.properties.stringOrNull("topic").isNullOrEmpty()) return -1
    val trimmed = value.trim()
    // No value, no active state - not even one whose readValue is empty.
    if (trimmed.isEmpty()) return -1
    return states.indexOfFirst { it.readValue.trim() == trimmed }
}

/**
 * Which state a tap selects, mirroring the designer's `switchStateIndexForTap`
 * and the firmware's own dispatchTapAt.
 *
 * In the switch form with two states a tap anywhere toggles - which is what a
 * switch does, wherever it is hit. With more than two, a tap on the track
 * picks the slot under it and a tap beside the track advances by one, so a
 * finger on the label still does something predictable.
 */
private fun stateIndexForTap(obj: ScreenObject, xUnits: Double, count: Int, activeIndex: Int): Int {
    if (count == 0) return -1
    if (switchForm(obj) == SwitchForm.KNOB) {
        if (count == 2) return if (activeIndex == 0) 1 else 0
        val slot = switchSlotAt(obj, count, xUnits)
        if (slot >= 0) return slot
        return if (activeIndex < 0) 0 else (activeIndex + 1) % count
    }
    return switchSegmentAt(obj, count, xUnits)
}

@Composable
fun SwitchView(
    obj: ScreenObject,
    project: Project,
    currentValue: String,
    /** What a finger here asked for and nothing has confirmed yet. */
    askedValue: String = "",
    /** What the control stands on: half of every colour it derives. */
    screenBackgroundColor: String? = null,
    assetFileOf: (String) -> java.io.File,
    onAction: (ButtonAction) -> Unit,
    /**
     * What this tap asked the installation for, so the ring can be drawn
     * until an answer arrives. Keyed by the topic the answer will come on -
     * the read topic - and carrying the state's READ value, because that is
     * what will come back; the write value is what goes out, and the two are
     * not always the same word.
     */
    onAsked: (readTopic: String, readValue: String) -> Unit = { _, _ -> },
) {
    val props = obj.properties
    val states = remember(props) { parseStates(props) }
    val fonts = project.fonts
    val background = screenBackgroundColor ?: "#ffffff"

    val activeIndex = stateIndexFor(obj, states, currentValue)
    val askedIndex = if (askedValue.isBlank()) -1 else stateIndexFor(obj, states, askedValue)
    // A finger held down on a button, or on the knob: the same ring the asked
    // state gets, and a knob that grows. Both say "this is not what is
    // reported - yet".
    var pressedIndex by remember(obj.id) { mutableIntStateOf(-1) }

    val knobForm = switchForm(obj) == SwitchForm.KNOB
    val on = activeIndex >= 0 && states[activeIndex].isOn
    val look = switchLook(obj, background)
    val knobLook = switchKnobLook(obj, background, on)
    val metrics = switchFontMetrics(obj, fonts)

    val fontMeta: FontEntry? = fonts.firstOrNull { it.id == props.stringOrNull("fontId") }
    val installation = LocalBundleInstallation.current
    val typeface = remember(fontMeta?.path, installation) {
        fontMeta?.path?.let { assetFileOf(it) }?.takeIf { it.exists() }
            ?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() } ?: Typeface.DEFAULT
    }
    // Decoded once per installation, keyed by the path the bundle gave: two
    // bundles have the same file names, so the path alone would hand back the
    // previous project's picture.
    val icons: Map<String, Bitmap> = remember(states, installation) {
        states.flatMap { listOfNotNull(it.path, it.activePath) }.distinct().mapNotNull { path ->
            val file = assetFileOf(path)
            if (!file.exists()) return@mapNotNull null
            BitmapFactory.decodeFile(file.path)?.let { path to it }
        }.toMap()
    }

    val density = LocalDensity.current
    val writeTopic = props.stringOrNull("writeTopic")
    val ox = obj.x.toInt()
    val oy = obj.y.toInt()

    Box(
        modifier = Modifier
            .offset(x = ox.dp, y = oy.dp)
            .size(width = obj.width.dp, height = obj.height.dp)
            .pointerInput(obj.id, states, activeIndex, writeTopic) {
                detectTapGestures(
                    onPress = { offset ->
                        val xUnits = ox + (offset.x / density.density).toDouble()
                        pressedIndex = stateIndexForTap(obj, xUnits, states.size, activeIndex)
                        tryAwaitRelease()
                        pressedIndex = -1
                    },
                    onTap = { offset ->
                        if (writeTopic.isNullOrEmpty()) return@detectTapGestures
                        val xUnits = ox + (offset.x / density.density).toDouble()
                        val index = stateIndexForTap(obj, xUnits, states.size, activeIndex)
                        val value = states.getOrNull(index)?.writeValue
                        if (value.isNullOrEmpty()) return@detectTapGestures
                        // Not retained: this is a command, not a state. The
                        // state comes back over the read topic and moves the
                        // pill, so the switch shows what actually happened
                        // rather than what it believed it was doing. Routed
                        // through the same send-mqtt action every
                        // SoftwareButton uses, so there is one publish path.
                        onAction(ButtonAction(type = "send-mqtt", mqttTopic = writeTopic, mqttMessage = value))
                        val readTopic = props.stringOrNull("topic")
                        val readValue = states.getOrNull(index)?.readValue
                        if (!readTopic.isNullOrEmpty() && !readValue.isNullOrEmpty()) onAsked(readTopic, readValue)
                    },
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val scale = density.density
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                val paint = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }
                val blit = Paint().apply { isAntiAlias = false; isFilterBitmap = false }

                fun fill(rect: SwitchRect, color: String) {
                    paint.color = argbOf(color) ?: return
                    fillRoundRectSides(
                        native, paint, rect.x - ox, rect.y - oy, rect.w, rect.h,
                        rect.r, rect.rRight ?: rect.r, scale,
                    )
                }

                fun ring(rect: SwitchRect, color: String, thickness: Int) {
                    paint.color = argbOf(color) ?: return
                    fillRoundRectRing(
                        native, paint, rect.x - ox, rect.y - oy, rect.w, rect.h,
                        rect.r, thickness, scale, rect.rRight ?: rect.r,
                    )
                }

                /** A baked icon, blitted at the rectangle the layout gave it. */
                fun icon(path: String?, rect: SwitchRect?) {
                    val bitmap = icons[path ?: return] ?: return
                    val box = rect ?: return
                    native.drawBitmap(
                        bitmap,
                        null,
                        Rect(
                            ((box.x - ox) * scale).toInt(),
                            ((box.y - oy) * scale).toInt(),
                            ((box.x - ox + box.w) * scale).toInt(),
                            ((box.y - oy + box.h) * scale).toInt(),
                        ),
                        blit,
                    )
                }

                fun label(pen: UnitTextPen, clip: SwitchRect, text: String, x: Int, baseline: Int) {
                    if (text.isEmpty() || clip.w <= 0 || clip.h <= 0) return
                    native.save()
                    native.clipRect(
                        (clip.x - ox) * scale,
                        (clip.y - oy) * scale,
                        (clip.x - ox + clip.w) * scale,
                        (clip.y - oy + clip.h) * scale,
                    )
                    native.drawText(text, (x - ox) * scale, (baseline - oy) * scale, pen.draw)
                    native.restore()
                }

                if (states.isEmpty()) return@drawIntoCanvas

                val size = fontMeta?.size ?: 14
                if (knobForm) {
                    val track = switchTrack(obj, states.size)
                    fill(track, knobLook.track)
                    knobLook.trackOutline?.let { ring(track, it, 2) }

                    val ink = onColorFor(background)
                    val pen = UnitTextPen(typeface, size, scale, argbOf(ink) ?: Color.Black.toArgb())
                    val box = switchLabelBox(obj, states.size)
                    val shown = if (askedIndex >= 0) askedIndex else activeIndex
                    if (shown < 0) {
                        // Nothing reported: no knob, because every position
                        // belongs to a state and standing somewhere would
                        // claim one nobody has reported.
                        val content = switchContent(box, metrics, false, pen.widthOf("?"))
                        label(pen, box, "?", content.textX, content.baseline)
                        return@drawIntoCanvas
                    }

                    val state = states[shown]
                    val knob = switchKnob(obj, states.size, shown, pressedIndex >= 0)
                    val d = knob.r * 2
                    fill(SwitchRect(knob.cx - knob.r, knob.cy - knob.r, d, d, knob.r), knobLook.knob)

                    val iconSize = maxOf(1, d * 3 / 5)
                    icon(
                        if (on) state.activePath ?: state.path else state.path,
                        SwitchRect(knob.cx - iconSize / 2, knob.cy - iconSize / 2, iconSize, iconSize, 0),
                    )

                    val content = switchContent(box, metrics, false, pen.widthOf(state.label))
                    label(pen, box, state.label, box.x, content.baseline)
                    return@drawIntoCanvas
                }

                val container = com.screensmith.android.render.switchContainer(obj)
                val outline = look.surfaceOutline
                if (outline != null) ring(container, outline, 1) else fill(container, look.surface)

                val segments = switchSegments(obj, states.size)
                segments.forEachIndexed { index, seg ->
                    val state = states[index]
                    val chosen = index == activeIndex
                    if (chosen) fill(seg, look.chosen)
                    if (index == askedIndex || index == pressedIndex) ring(seg, look.ring, 2)

                    val ink = if (chosen) look.onChosen else look.onSurface
                    val pen = UnitTextPen(typeface, size, scale, argbOf(ink) ?: Color.Black.toArgb())
                    val path = if (chosen) state.activePath ?: state.path else state.path
                    val content = switchContent(seg, metrics, icons.containsKey(path), pen.widthOf(state.label))
                    native.save()
                    native.clipRect(
                        (seg.x - ox) * scale,
                        (seg.y - oy) * scale,
                        (seg.x - ox + seg.w) * scale,
                        (seg.y - oy + seg.h) * scale,
                    )
                    icon(path, content.icon)
                    label(pen, seg, state.label, content.textX, content.baseline)
                    native.restore()
                }
            }
        }
    }
}

/** A "#rrggbb" from the shape rules as an ARGB int; null for "transparent". */
private fun argbOf(color: String): Int? = parseHexColor(color)?.takeIf { it != Color.Transparent }?.toArgb()
