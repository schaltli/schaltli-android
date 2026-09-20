package com.screensmith.android.ui.objects

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.screensmith.android.data.ButtonAction
import com.screensmith.android.data.FontEntry
import com.screensmith.android.data.Project
import com.screensmith.android.data.ScreenObject
import com.screensmith.android.render.fillRoundRect
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Switch - a state control with one entry per `properties.states`, in one of
 * two modes:
 *
 *   "segmented" (default) - n segments side by side, the active one marked
 *   "single"              - one surface showing whichever state is active,
 *                           a tap advancing to the next state (wrapping)
 *
 * Read-bound in both: the active state is whichever state's `readValue`
 * matches the current value of `properties.topic` - exact trimmed-string
 * match, the same comparison [evaluateCondition]'s "==" makes. No match
 * (no topic bound, or no retained value received yet) means no state is
 * active: segmented draws no marker, single draws "?" and nothing else.
 *
 * The marker is a bar along the top edge, never a filled segment. Two of the
 * three appearances the firmware's renderer can produce exist here:
 *
 *   absent  - a confirmed state that carries no marker
 *   solid   - a confirmed state that carries a marker
 *
 * The third, a hollow bar for "written and waiting for the retained value to
 * come back", is deliberately not implemented - not as a simplification, but
 * because it is unreachable on the Waveshare too. `ColorScreenRenderer`
 * plumbs `pendingSwitchId`/`pressedSwitchId` through every call, and
 * `main.cpp` never sets either, so every device draw uses the defaults. An
 * Android-only pending state would put this app *ahead* of the reference
 * rather than level with it, and would draw a bar the designer's render -
 * which every HIL comparison is made against - never draws.
 *
 * Geometry constants are whole numbers with no division behind them, and
 * every one also appears under the same name in `render-switch.ts` and in
 * `ColorScreenRenderer.cpp`. Stated values cannot drift; computed ones get
 * to disagree about rounding.
 */
private const val BAR_HEIGHT = 10
private const val BAR_TOP_INSET = 4
private const val BAR_SIDE_INSET = 6
private const val BAR_RADIUS = 3

/**
 * The band the bar occupies, reserved whether or not a bar is currently
 * drawn. Content lays out below it in both modes and in every state, so a
 * Switch does not visibly reflow its icon and label the moment it is
 * switched on.
 */
private const val BAR_BAND = BAR_TOP_INSET + BAR_HEIGHT

private data class SwitchStateEntry(
    val label: String,
    val readValue: String,
    val writeValue: String,
    /** Bundle-relative path of this state's icon SVG, written by lib/android-export.ts. */
    val path: String?,
    /** A different picture while this state is active; null means "same either way". */
    val activePath: String?,
    val showMarker: Boolean,
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
            showMarker = (entry["showMarker"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }
}

/**
 * Which state the current topic value selects, or -1 for none - mirrors
 * `getActiveSwitchStateIndex` on both other sides, trim included.
 */
private fun activeStateIndex(
    obj: ScreenObject,
    states: List<SwitchStateEntry>,
    currentValue: String,
): Int {
    if (obj.properties.stringOrNull("topic").isNullOrEmpty()) return -1
    val trimmed = currentValue.trim()
    // No value, no active segment - not even one whose readValue is empty.
    if (trimmed.isEmpty()) return -1
    return states.indexOfFirst { it.readValue.trim() == trimmed }
}

/**
 * Which state a tap selects.
 *
 * The segmented branch is integer division, floored, because the firmware's
 * `dispatchTapAt` does the same with ints - a finger on a boundary has to
 * land on the same segment everywhere. In the knob form there are no segments
 * to aim at and a tap advances to the next state instead, wrapping; -1
 * (nothing matched yet) starts at the first state, because tapping a tile
 * showing "?" has to do something or it reads as broken.
 */
private fun stateIndexForTap(obj: ScreenObject, localXUnits: Double, count: Int, activeIndex: Int): Int {
    if (count == 0) return -1
    if (obj.type == "switch") {
        return if (activeIndex < 0) 0 else (activeIndex + 1) % count
    }
    val index = floor(localXUnits * count / obj.width).toInt()
    return index.coerceIn(0, count - 1)
}

@Composable
fun SwitchView(
    obj: ScreenObject,
    project: Project,
    currentValue: String,
    assetFileOf: (String) -> java.io.File,
    onAction: (ButtonAction) -> Unit,
) {
    val props = obj.properties
    val states = remember(props) { parseStates(props) }
    val backgroundColor = props.colorOrDefault("backgroundColor", Color.White)
    // Was the active segment's fill before the marker bar replaced it, and
    // deliberately not renamed on any side: the value in every saved project
    // is already the right colour for its new job.
    val markerColor = props.colorOrDefault("activeBackgroundColor", Color(0xFF2563EB))
    val borderColor = props.colorOrDefault("borderColor", Color(0xFFCCCCCC))
    val textColor = props.colorOrDefault("textColor", Color.Black)
    val cornerRadius = props.double("cornerRadius", 0.0).coerceAtLeast(0.0).toInt()
    // The form is the type since 2026-09-20: "switch" is the knob in a
    // track, "button-group" the strip of segments. It was a `mode`
    // property until then.
    val isSingle = obj.type == "switch"

    val activeIndex = activeStateIndex(obj, states, currentValue)

    val fontMeta: FontEntry? = project.fonts.find { it.id == props.stringOrNull("fontId") }
    val fontSize = fontMeta?.size ?: 14
    val typeface = remember(fontMeta?.path) {
        fontMeta?.path?.let { assetFileOf(it) }?.takeIf { it.exists() }
            ?.let { Typeface.createFromFile(it) } ?: Typeface.DEFAULT
    }

    val density = LocalDensity.current
    val writeTopic = props.stringOrNull("writeTopic")

    Box(
        modifier = Modifier
            .offset(x = obj.x.dp, y = obj.y.dp)
            .size(width = obj.width.dp, height = obj.height.dp)
            .pointerInput(obj.id, states, activeIndex, writeTopic) {
                detectTapGestures { offset ->
                    if (writeTopic.isNullOrEmpty()) return@detectTapGestures
                    val localXUnits = offset.x / density.density
                    val index = stateIndexForTap(obj, localXUnits.toDouble(), states.size, activeIndex)
                    val value = states.getOrNull(index)?.writeValue
                    if (value.isNullOrEmpty()) return@detectTapGestures
                    // Not retained: this is a command, not a state. The state
                    // comes back over the read topic and moves the marker, so
                    // the switch shows what actually happened rather than what
                    // it believed it was doing. Routed through the same
                    // send-mqtt action every SoftwareButton uses, so there is
                    // one publish path, not two.
                    onAction(ButtonAction(type = "send-mqtt", mqttTopic = writeTopic, mqttMessage = value))
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val scale = density.density
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = false
                    style = android.graphics.Paint.Style.FILL
                }

                // Draw order, and why it is this one:
                //
                //   1 background, once over the whole object
                //   2 outer border
                //   3 dividers
                //   4 marker bars
                //   5 icons and labels
                //
                // Filling once rather than per segment is what lets the border
                // survive: no segment has a fill of its own, so there is
                // nothing left to overpaint it with. Content comes last for the
                // same reason drawTextBox draws it last - a border stroked over
                // text erases glyph ink wherever a descender reaches the edge.
                val w = obj.width.roundToInt()
                val h = obj.height.roundToInt()
                val hasBorder = borderColor != Color.Transparent

                if (cornerRadius > 0) {
                    // A rounded border is not a stroke but a slightly larger
                    // filled shape with the background filled on top of it -
                    // the only way to get a rounded border out of the integer,
                    // un-anti-aliased primitive every side shares.
                    if (hasBorder) {
                        paint.color = borderColor.toArgb()
                        fillRoundRect(native, paint, 0, 0, w, h, cornerRadius, scale)
                        if (backgroundColor != Color.Transparent && w > 2 && h > 2) {
                            paint.color = backgroundColor.toArgb()
                            fillRoundRect(
                                native, paint, 1, 1, w - 2, h - 2,
                                (cornerRadius - 1).coerceAtLeast(0), scale,
                            )
                        }
                    } else if (backgroundColor != Color.Transparent) {
                        paint.color = backgroundColor.toArgb()
                        fillRoundRect(native, paint, 0, 0, w, h, cornerRadius, scale)
                    }
                } else {
                    if (backgroundColor != Color.Transparent) {
                        paint.color = backgroundColor.toArgb()
                        native.drawRect(0f, 0f, size.width, size.height, paint)
                    }
                    if (hasBorder) {
                        // Four one-unit spans rather than a stroked rect: a
                        // stroke straddles the path half in and half out and
                        // would bleed past the object's declared bounds, which
                        // is the same reason TextBoxView fills its border too.
                        paint.color = borderColor.toArgb()
                        native.drawRect(0f, 0f, size.width, scale, paint)
                        native.drawRect(0f, size.height - scale, size.width, size.height, paint)
                        native.drawRect(0f, 0f, scale, size.height, paint)
                        native.drawRect(size.width - scale, 0f, size.width, size.height, paint)
                    }
                }

                val textPaint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    this.typeface = typeface
                    this.textSize = fontSize * scale
                    color = textColor.toArgb()
                    textAlign = android.graphics.Paint.Align.CENTER
                }

                if (states.isEmpty()) {
                    // Mirrors the placeholder both other renderers draw - an
                    // empty or misconfigured Switch stays visible instead of
                    // becoming a blank box.
                    val placeholder = android.graphics.Paint().apply {
                        isAntiAlias = true
                        textSize = 11f * scale
                        color = android.graphics.Color.rgb(0x99, 0x99, 0x99)
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    val cy = size.height / 2f
                    native.drawText(
                        "No states defined",
                        size.width / 2f,
                        cy - (placeholder.ascent() + placeholder.descent()) / 2f,
                        placeholder,
                    )
                    return@drawIntoCanvas
                }

                val bandTop = BAR_BAND.toFloat()
                val bandHeight = obj.height.toFloat() - BAR_BAND

                if (isSingle) {
                    if (activeIndex < 0) {
                        // Nothing matched, so there is no state - and every
                        // label belongs to a state, so showing one would be
                        // claiming a state nobody reported. "?" alone, which
                        // after a boot is what every tile shows until its
                        // retained value arrives.
                        drawSegmentLabel(
                            native, textPaint, "?", size.width / 2f,
                            bandTop * scale, bandHeight * scale, null,
                        )
                        return@drawIntoCanvas
                    }
                    val state = states[activeIndex]
                    if (state.showMarker) {
                        paint.color = markerColor.toArgb()
                        drawBar(native, paint, 0.0, w.toDouble(), scale)
                    }
                    drawStateLabel(native, textPaint, state, state.path != null, 0.0, obj.width, obj.height, scale)
                    return@drawIntoCanvas
                }

                // Float division, matching the designer's own
                // `obj.width / states.length` - the firmware gives the last
                // segment the integer remainder instead, a difference too small
                // to matter on any real display.
                val segmentWidth = obj.width / states.size

                states.forEachIndexed { index, state ->
                    val segX = index * segmentWidth

                    // Divider between segments, never before the first one -
                    // the outer border already covers that edge. Full height
                    // even on a rounded body: a divider runs through the middle
                    // of the control, nowhere near a corner.
                    if (index > 0 && hasBorder) {
                        paint.color = borderColor.toArgb()
                        val dx = segX.roundToInt() * scale
                        native.drawRect(dx, 0f, dx + scale, size.height, paint)
                    }

                    // showMarker is a "single" mode concept: here the bar's
                    // whole job is to say which of the n segments is active,
                    // which is not a question the author gets to answer per
                    // state.
                    if (index == activeIndex) {
                        paint.color = markerColor.toArgb()
                        drawBar(native, paint, segX, segmentWidth, scale)
                    }

                    // Clip to this segment's own rect: a large font or a long
                    // label would otherwise bleed into the neighbouring segment
                    // or past the control's outer edge.
                    native.save()
                    native.clipRect(
                        (segX * scale).toFloat(), 0f,
                        ((segX + segmentWidth) * scale).toFloat(), size.height,
                    )
                    val hasIcon = ((index == activeIndex && state.activePath != null) || state.path != null)
                    drawStateLabel(native, textPaint, state, hasIcon, segX, segmentWidth, obj.height, scale)
                    native.restore()
                }
            }
        }

        // Icons ride above the Canvas rather than inside it: an SVG needs
        // Coil's decoder, which is a composable concern. They never overlap a
        // label - the label is laid out below the icon by the same geometry
        // the other two renderers use - so drawing them last changes nothing.
        if (isSingle) {
            val state = states.getOrNull(activeIndex)
            // No activePath in this mode: a state is only ever drawn while it
            // is the active one, so its own icon already is its active
            // picture. Reaching for the active variant would leave `path`
            // permanently unreachable.
            if (state?.path != null) {
                StateIcon(state.path, 0.0, obj.width, obj.height, assetFileOf)
            }
        } else if (states.isNotEmpty()) {
            val segmentWidth = obj.width / states.size
            states.forEachIndexed { index, state ->
                val path = (if (index == activeIndex) state.activePath else null) ?: state.path
                if (path != null) {
                    StateIcon(path, index * segmentWidth, segmentWidth, obj.height, assetFileOf)
                }
            }
        }
    }
}

/**
 * How big a state's icon is, in project units.
 *
 * One function rather than one expression per call site, because both the
 * icon and the label depend on it - the label sits directly below the icon,
 * so a size computed twice is a label placed somewhere the icon is not.
 *
 * `round`, not truncate: 0.62 of a 32-unit band is 19.84, a value where the
 * two disagree, and lib/asset-export.ts bakes the firmware's copy of this
 * bitmap at `Math.round` of the same expression. A one-unit difference on
 * every icon is what a HIL run reports as hundreds of differing pixels.
 */
private fun switchIconSize(segmentWidth: Double, objectHeight: Double): Double {
    val bandHeight = objectHeight - BAR_BAND
    return kotlin.math.max(1.0, kotlin.math.min(segmentWidth - 8, bandHeight * 0.62).roundToInt().toDouble())
}

/** One state's icon, sized and placed by the geometry every side shares. */
@Composable
private fun StateIcon(
    path: String,
    segX: Double,
    segmentWidth: Double,
    objectHeight: Double,
    assetFileOf: (String) -> java.io.File,
) {
    val iconSize = switchIconSize(segmentWidth, objectHeight)
    val centerX = segX + segmentWidth / 2
    val iconX = (centerX - iconSize / 2).roundToInt()
    val iconY = BAR_BAND + 2

    AsyncImage(
        model = assetFileOf(path),
        contentDescription = null,
        modifier = Modifier
            .offset(x = iconX.dp, y = iconY.dp)
            .size(iconSize.dp),
    )
}

/** A solid rounded bar, inset within [x, x + width), in project units. */
private fun drawBar(
    canvas: android.graphics.Canvas,
    paint: android.graphics.Paint,
    x: Double,
    width: Double,
    scale: Float,
) {
    // The clamp guards objects saved before the designer's resize minimums
    // existed: a five-state Switch 60 units wide gives each segment 12, and
    // 12 - 2*6 is zero - a control that silently says nothing about its own
    // state. One integer max() is cheaper than any amount of warning UI.
    val barWidth = kotlin.math.max(4, width.roundToInt() - 2 * BAR_SIDE_INSET)
    val barX = x.roundToInt() + BAR_SIDE_INSET
    fillRoundRect(canvas, paint, barX, BAR_TOP_INSET, barWidth, BAR_HEIGHT, BAR_RADIUS, scale)
}

/**
 * A state's label, below its icon when it has one and centred in the content
 * band when it does not - the two anchorings `render-switch.ts` chooses
 * between, expressed with Paint's own metrics because the designer's TTF
 * path uses canvas's "top"/"middle" baselines, not the DDF's declared
 * ascent/descent (its BDF path is the one that uses those, and an
 * android-platform DDF ships no BDF fonts).
 */
private fun drawStateLabel(
    canvas: android.graphics.Canvas,
    paint: android.graphics.Paint,
    state: SwitchStateEntry,
    hasIcon: Boolean,
    segX: Double,
    segmentWidth: Double,
    objectHeight: Double,
    scale: Float,
) {
    if (state.label.isEmpty()) return
    // Everything below is in project units until the last moment, so the
    // geometry stays the same numbers [StateIcon] and the other two
    // renderers use. Only the scaling to device pixels happens here.
    val anchorTop = if (hasIcon) {
        (BAR_BAND + 2) + switchIconSize(segmentWidth, objectHeight) + 2
    } else {
        null
    }
    drawSegmentLabel(
        canvas, paint, state.label,
        ((segX + segmentWidth / 2) * scale).toFloat(),
        BAR_BAND * scale,
        ((objectHeight - BAR_BAND) * scale).toFloat(),
        anchorTop?.let { (it * scale).toFloat() },
    )
}

/**
 * Draws [label] centred horizontally at [centerXPx]. [anchorTopPx] is either
 * a fixed top (text starts there, used below an icon) or null to centre
 * vertically within the content band instead.
 */
private fun drawSegmentLabel(
    canvas: android.graphics.Canvas,
    paint: android.graphics.Paint,
    label: String,
    centerXPx: Float,
    bandTopPx: Float,
    bandHeightPx: Float,
    anchorTopPx: Float?,
) {
    val baselineY = if (anchorTopPx != null) {
        // canvas's textBaseline = "top": the top of the text box sits at the
        // anchor, so the baseline is one ascent below it.
        anchorTopPx - paint.ascent()
    } else {
        // canvas's textBaseline = "middle".
        bandTopPx + bandHeightPx / 2f - (paint.ascent() + paint.descent()) / 2f
    }
    canvas.drawText(label, centerXPx, baselineY, paint)
}
