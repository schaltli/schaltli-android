package com.schaltli.android.ui.objects

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import com.schaltli.android.data.ButtonAction
import com.schaltli.android.mqtt.actionOf
import com.schaltli.android.data.EnergyDiagramLayout as E
import com.schaltli.android.data.Project
import com.schaltli.android.data.ScreenObject
import com.schaltli.android.data.resolveLiveText
import com.schaltli.android.data.resolvePlaceholders
import com.schaltli.android.data.resolveTopicValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Where a diagram's motion stands: the wave's phase and each Flow line's
 * dots' (by node id). [STILL] is everything at phase 0.
 */
data class EnergyPhases(val wave: Double = 0.0, val dots: Map<String, Double> = emptyMap()) {
    companion object {
        val STILL = EnergyPhases()
    }
}

/** A node of the export (the designer's device contract §2.8). */
private data class EnergyNodeProps(val json: JsonObject) {
    val id = json.stringOrNull("id") ?: ""
    val role = json.stringOrNull("role") ?: "rest"
    val name = json.stringOrNull("name") ?: ""
    val topic = json.stringOrNull("topic")
    val chargeTopic = json.stringOrNull("chargeTopic")
    val color = json.stringOrNull("color")
    val icon = json["icon"] as? JsonObject
    val lines = (json["lines"] as? JsonArray)?.filterIsInstance<JsonObject>()?.take(E.MAX_NODE_LINES) ?: emptyList()
    val action = json["action"] as? JsonObject
}

/** A number from a topic's value, or null: nothing arrived, or not a number. */
private fun numberOf(value: String): Double? = value.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it.isFinite() }

/** The core's nodes and what their topics say now. */
internal fun energyInputs(obj: ScreenObject, project: Project, topicValues: Map<String, String>): Pair<List<E.Node>, Map<String, E.Value>> {
    val nodes = (obj.properties["nodes"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()
    val core = nodes.map {
        E.Node(
            it.stringOrNull("id") ?: "",
            E.roleFrom(it.stringOrNull("role")),
            it.doubleOrNull("x") ?: 0.0,
            it.doubleOrNull("y") ?: 0.0,
            (it["reverseSign"] as? JsonPrimitive)?.booleanOrNull ?: false,
            ((it["lines"] as? JsonArray)?.size ?: 0),
        )
    }
    val values = nodes.associate { n ->
        val role = E.roleFrom(n.stringOrNull("role"))
        val watts = n.stringOrNull("topic")?.takeIf { role != E.Role.REST && role != E.Role.BUS }?.let { numberOf(resolveTopicValue(it, project, topicValues)) }
        val charge = n.stringOrNull("chargeTopic")?.takeIf { role == E.Role.STORAGE }?.let { numberOf(resolveTopicValue(it, project, topicValues)) }
        (n.stringOrNull("id") ?: "") to E.Value(watts, charge)
    }
    return core to values
}

/** The node a tap at (x, y) in the diagram's own units lands on, and its action. */
fun energyActionAt(obj: ScreenObject, project: Project, topicValues: Map<String, String>, x: Double, y: Double): JsonObject? {
    val (nodes, values) = energyInputs(obj, project, topicValues)
    val sankey = obj.properties.stringOrNull("mode") == "sankey"
    val id = E.nodeAtTap(sankey, nodes, E.resolve(nodes, values), E.Size(obj.width, obj.height), fullScaleOf(obj), x, y) ?: return null
    return (obj.properties["nodes"] as? JsonArray)?.filterIsInstance<JsonObject>()?.firstOrNull { it.stringOrNull("id") == id }?.get("action") as? JsonObject
}

private fun fullScaleOf(obj: ScreenObject) = obj.properties.doubleOrNull("fullScale")?.takeIf { it > 0 } ?: 1000.0

private fun rgbOf(c: Color) = intArrayOf((c.red * 255 + 0.5f).toInt(), (c.green * 255 + 0.5f).toInt(), (c.blue * 255 + 0.5f).toInt())

/**
 * The energy diagram (the designer's device contract §2.8), drawn from the
 * core's places as the designer's canvas draws it
 * (components/canvas/renderers/render-energy-diagram.ts): the Sankey's bands
 * column by column in their wave's colour, bars and bus; or the Flow's lines,
 * dots, bus and circles; then every name, line and icon at the core's boxes.
 * One project unit is one dp, as everywhere in the app.
 */
@Composable
fun EnergyDiagramView(
    obj: ScreenObject,
    project: Project,
    topicValues: Map<String, String>,
    screenBackgroundColor: Color,
    assetFileOf: (String) -> java.io.File,
    deviceName: String,
    deviceId: String,
    onAction: (ButtonAction) -> Unit = {},
) {
    val p = obj.properties
    val nodeProps = (p["nodes"] as? JsonArray)?.filterIsInstance<JsonObject>()?.map(::EnergyNodeProps) ?: emptyList()
    val byId = nodeProps.associateBy { it.id }
    val (nodes, values) = energyInputs(obj, project, topicValues)
    val resolved = E.resolve(nodes, values)
    val sankey = p.stringOrNull("mode") == "sankey"
    val size = E.Size(obj.width, obj.height)
    val fontId = p.stringOrNull("fontId")
    val valueFontId = p.stringOrNull("valueFontId") ?: fontId
    val fontSize = (p["fontSize"] as? JsonPrimitive)?.intOrNull ?: 16
    val valueFontSize = (p["valueFontSize"] as? JsonPrimitive)?.intOrNull ?: fontSize
    val metrics = E.LabelMetrics(fontSize.toDouble(), valueFontSize.toDouble())
    fun colour(key: String, default: String) = parseHexColor(p.stringOrNull(key) ?: default) ?: Color.Black
    val text = colour("textColor", "#1d1b20")
    val muted = colour("mutedColor", "#49454f")
    val bus = colour("busColor", "#6750a4")
    val idle = colour("idleColor", "#cac4d0")
    val charge = colour("chargeColor", "#2f9a52")
    val discharge = colour("dischargeColor", "#d0453f")
    fun colourOf(r: E.Resolved): Color = when {
        r.role == E.Role.STORAGE -> if (r.gives) discharge else charge
        else -> byId[r.id]?.color?.let(::parseHexColor) ?: text
    }

    val sankeyLayout = if (sankey) E.sankeyLayout(resolved, size, fullScaleOf(obj)) else null
    val flowLayout = if (!sankey) E.flowLayout(nodes, resolved) else null
    // Motion (spec decision 10): the phases move on about 30 times a second
    // while the diagram is shown and Motion is on. Only the drawing reads
    // them, so a step redraws the canvas and recomposes nothing else.
    val latestValues = rememberUpdatedState(topicValues)
    val motion = (p["motion"] as? JsonPrimitive)?.booleanOrNull != false
    val phaseState = remember(obj.id) { mutableStateOf(EnergyPhases.STILL) }
    val moving = rememberUpdatedState(resolved)
    if (motion) {
        LaunchedEffect(obj.id) {
            var last = 0L
            while (true) {
                withFrameNanos { now ->
                    if (last == 0L) last = now
                    val seconds = (now - last) / 1e9
                    if (seconds >= 1.0 / 30) {
                        last = now
                        val was = phaseState.value
                        phaseState.value = EnergyPhases(
                            E.advanceWave(was.wave, seconds),
                            moving.value.filter { it.watts > 0 }.associate { it.id to E.advanceDots(was.dots[it.id] ?: 0.0, seconds, it.watts) },
                        )
                    }
                }
            }
        }
    }

    val boxes = if (sankeyLayout != null) E.sankeyBoxes(sankeyLayout, nodes, size, metrics) else E.flowBoxes(flowLayout!!, metrics)

    Box(
        modifier = Modifier
            .offset(x = obj.x.dp, y = obj.y.dp)
            .size(width = obj.width.dp, height = obj.height.dp)
            .clipToBounds()
            // A tap on a node runs its action, the actions a button has
            // (decision 13); the bus and the space between run none. Nothing
            // is consumed: the diagram may cover the screen, and a swipe that
            // starts on it still pages (SwipeNavigation.kt reads a consumed
            // touch as a control's; detectTapGestures consumes the down).
            .pointerInput(obj.id) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var moved = false
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                        if (!change.pressed) {
                            if (!moved) {
                                val at = down.position
                                val action = energyActionAt(obj, project, latestValues.value, (at.x / density).toDouble(), (at.y / density).toDouble())
                                if (action != null) {
                                    ScreenObject("${obj.id}-tap", "button", 0.0, 0.0, 0.0, 0.0, properties = JsonObject(mapOf("action" to action))).actionOf()?.let(onAction)
                                }
                            }
                            break
                        }
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val unit = density
            val phases = if (motion) phaseState.value else EnergyPhases.STILL
            withTransform({ scale(unit, unit, Offset.Zero) }) {
                fun pt(x: Double, y: Double) = Offset(x.toFloat(), y.toFloat())
                if (sankeyLayout != null) {
                    val colours = resolved.associate { it.id to if (it.watts > 0) colourOf(it) else idle }
                    val ground = rgbOf(screenBackgroundColor)
                    for (band in sankeyLayout.bands) {
                        val path = Path().apply {
                            band.polygon.forEachIndexed { i, q -> if (i == 0) moveTo(q.x.toFloat(), q.y.toFloat()) else lineTo(q.x.toFloat(), q.y.toFloat()) }
                            close()
                        }
                        val base = rgbOf(colours[band.id] ?: text)
                        val x0 = band.polygon.minOf { it.x }.toInt()
                        val x1 = kotlin.math.ceil(band.polygon.maxOf { it.x }).toInt()
                        val y0 = band.polygon.minOf { it.y }.toFloat()
                        val y1 = band.polygon.maxOf { it.y }.toFloat()
                        clipPath(path) {
                            for (x in x0 until x1) {
                                val c = E.waveColour(base, ground, x.toDouble(), phases.wave)
                                drawRect(Color(c[0], c[1], c[2]), Offset(x.toFloat(), y0), Size(1f, y1 - y0))
                            }
                        }
                    }
                    val b = sankeyLayout.bus
                    drawRect(bus, pt(b.x, b.y), Size(b.width.toFloat(), b.height.toFloat()))
                    for (bar in sankeyLayout.bars) {
                        val r = CornerRadius(minOf(3.0, bar.box.height / 2).toFloat())
                        val left = bar.side == E.Side.LEFT
                        val rr = RoundRect(
                            bar.box.x.toFloat(), bar.box.y.toFloat(), (bar.box.x + bar.box.width).toFloat(), (bar.box.y + bar.box.height).toFloat(),
                            topLeftCornerRadius = if (left) r else CornerRadius.Zero,
                            topRightCornerRadius = if (left) CornerRadius.Zero else r,
                            bottomRightCornerRadius = if (left) CornerRadius.Zero else r,
                            bottomLeftCornerRadius = if (left) r else CornerRadius.Zero,
                        )
                        drawPath(Path().apply { addRoundRect(rr) }, colours[bar.id] ?: text)
                    }
                } else if (flowLayout != null) {
                    for (line in flowLayout.lines) {
                        val r = resolved.first { it.id == line.id }
                        val c = if (line.live) colourOf(r) else idle
                        drawLine(c, pt(line.x0, line.y0), pt(line.x1, line.y1), line.width.toFloat(), StrokeCap.Round, alpha = if (line.live) 0.5f else 1f)
                        if (!line.live) continue
                        for (d in E.dotsOnLine(line, phases.dots[line.id] ?: 0.0, r.gives, r.watts)) drawCircle(c, E.DOT_R.toFloat(), pt(d.x, d.y))
                    }
                    drawCircle(bus, flowLayout.busR.toFloat(), pt(flowLayout.busX, flowLayout.busY))
                    for (fn in flowLayout.nodes) {
                        val r = resolved.first { it.id == fn.id }
                        val c = if (fn.live) colourOf(r) else idle
                        val centre = pt(fn.x, fn.y)
                        fn.ringR?.let { ringR ->
                            drawCircle(idle, ringR.toFloat(), centre, style = Stroke(5f))
                            if (fn.ringFraction > 0) {
                                val rr = ringR.toFloat()
                                drawArc(
                                    colourOf(r), -90f, (fn.ringFraction * 360).toFloat(), false,
                                    Offset(centre.x - rr, centre.y - rr), Size(2 * rr, 2 * rr), style = Stroke(5f, cap = StrokeCap.Round),
                                )
                            }
                        }
                        drawCircle(screenBackgroundColor, fn.r.toFloat(), centre)
                        drawCircle(c, fn.r.toFloat(), centre, style = Stroke(3f))
                    }
                }
            }
        }

        // Names, lines and icons at the core's boxes, as texts and icons are.
        @Composable
        fun textAt(id: String, box: E.LabelBox, y: Double, value: String, font: String?, colour: String) {
            val props = buildMap<String, kotlinx.serialization.json.JsonElement> {
                put("textAlign", JsonPrimitive(box.align.name.lowercase()))
                put("backgroundColor", JsonPrimitive("transparent"))
                put("borderColor", JsonPrimitive("transparent"))
                put("color", JsonPrimitive(colour))
                if (font != null) put("fontId", JsonPrimitive(font))
            }
            TextBoxView(ScreenObject(id, "text", Math.round(box.x).toDouble(), Math.round(y).toDouble(), box.width, fontSize.toDouble(), 3, JsonObject(props)), project, value, assetFileOf)
        }
        val mutedHex = p.stringOrNull("mutedColor") ?: "#49454f"
        val textHex = p.stringOrNull("textColor") ?: "#1d1b20"
        boxes.bus?.let { box -> nodeProps.firstOrNull { it.role == "bus" }?.let { textAt("bus-name", box, box.y, it.name, fontId, mutedHex) } }
        for (b in boxes.nodes) {
            val node = byId[b.id] ?: continue
            val r = resolved.firstOrNull { it.id == b.id } ?: continue
            node.icon?.let { icon ->
                val look = if (r.watts > 0) (if (r.role == E.Role.STORAGE) (if (r.gives) "discharge" else "charge") else "live") else "muted"
                IconPathView(
                    ScreenObject("${b.id}-icon", "icon", b.icon.x, b.icon.y, b.icon.width, b.icon.height, 2),
                    icon.stringOrNull(look),
                    assetFileOf,
                )
            }
            textAt("${b.id}-name", b.label, b.label.y, node.name, fontId, mutedHex)
            // {watts}: the node's watts as worked out, nothing while its topic has not spoken.
            val known = r.role == E.Role.REST || values[r.id]?.watts != null
            val watts = if (!known) null else if (r.role == E.Role.STORAGE && r.gives) -r.watts else r.watts
            var y = b.label.y + E.rowStep(fontSize.toDouble())
            node.lines.forEachIndexed { i, line ->
                val first = i == 0
                val liveText = line.stringOrNull("liveText")
                val value = if (!liveText.isNullOrEmpty()) {
                    resolveLiveText(E.withWatts(liveText, watts), line, project, topicValues, deviceName, deviceId)
                } else {
                    resolvePlaceholders(E.withWatts(line.stringOrNull("text") ?: "", watts), project, topicValues, deviceName, deviceId)
                }
                textAt("${b.id}-line$i", b.label, y, value, if (first) valueFontId else fontId, if (first) textHex else mutedHex)
                y += E.rowStep((if (first) valueFontSize else fontSize).toDouble())
            }
        }
    }
}
