package com.schaltli.android.data

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The energy diagram's core (the designer's docs/2026-10-11-energy-diagram.md,
 * device contract §2.8): what each node gives or takes, the Sankey's bars,
 * slots and bands, the Flow's circles and lines, where names, lines and icons
 * stand, where a tap lands, the dots and the wave.
 *
 * A line-by-line port of the designer's `lib/energy-diagram.ts`, as the
 * firmware's EnergyDiagram.cpp is; all three are held to the same cases,
 * `energy-diagram-vectors.json` in the test resources. Places are doubles as
 * in the designer; a view rounds them where it draws.
 */
object EnergyDiagramLayout {

    enum class Role { SOURCE, SINK, STORAGE, REST, BUS }

    fun roleFrom(name: String?): Role = when (name) {
        "source" -> Role.SOURCE
        "sink" -> Role.SINK
        "storage" -> Role.STORAGE
        "bus" -> Role.BUS
        else -> Role.REST
    }

    data class Point(val x: Double, val y: Double)
    data class Box(val x: Double, val y: Double, val width: Double, val height: Double)
    data class Size(val width: Double, val height: Double)

    /** A node as the export gives it: what the core needs of it. */
    data class Node(val id: String, val role: Role, val x: Double, val y: Double, val reverseSign: Boolean = false, val lines: Int = 0)

    /** What a node's topics say now; null where nothing (or no number) arrived. */
    data class Value(val watts: Double? = null, val charge: Double? = null)

    data class Resolved(val id: String, val role: Role, val x: Double, val y: Double, val watts: Double, val gives: Boolean, val charge: Double? = null)

    const val MAX_NODE_LINES = 3

    /** JavaScript's Math.round: halves go up. */
    fun jsRound(v: Double): Double = floor(v + 0.5)

    /** The bus is not among the result. */
    fun resolve(nodes: List<Node>, values: Map<String, Value>): List<Resolved> {
        val out = mutableListOf<Resolved>()
        for (n in nodes) {
            if (n.role == Role.BUS) continue
            val raw = values[n.id]?.watts?.takeIf { it.isFinite() }
            val w = if (raw == null) 0.0 else if (n.reverseSign) -raw else raw
            out += when (n.role) {
                Role.STORAGE -> Resolved(n.id, n.role, n.x, n.y, abs(w), w < 0, values[n.id]?.charge)
                Role.SOURCE -> Resolved(n.id, n.role, n.x, n.y, max(0.0, w), true)
                Role.SINK -> Resolved(n.id, n.role, n.x, n.y, max(0.0, w), false)
                else -> Resolved(n.id, n.role, n.x, n.y, 0.0, false)
            }
        }
        val given = out.filter { it.gives }.sumOf { it.watts }
        val taken = out.filter { !it.gives && it.role != Role.REST }.sumOf { it.watts }
        val rest = out.indexOfFirst { it.role == Role.REST }
        if (rest >= 0) out[rest] = out[rest].copy(watts = max(0.0, given - taken))
        return out
    }

    /** `{watts}` put into a line: the watts whole, "-" when unknown. */
    fun withWatts(text: String, watts: Double?): String {
        if (!text.contains("{watts}")) return text
        return text.replace("{watts}", if (watts == null) "-" else jsRound(watts).toLong().toString())
    }

    // ---- Sankey (spec decision 8)
    const val SANKEY_LABEL_W = 170.0
    const val SANKEY_BAR_W = 12.0
    const val SANKEY_BUS_W = 16.0
    const val SANKEY_GAP = 14.0
    const val SANKEY_MIN_BAND = 3.0
    const val SANKEY_SLOT_MIN = 66.0
    const val SANKEY_UNDER_BAR = 4.0
    const val SANKEY_UNDER_BUS = 2.0
    const val SANKEY_SEGMENTS = 24

    enum class Side { LEFT, RIGHT }

    data class Bar(val id: String, val side: Side, val box: Box)
    data class Slot(val id: String, val side: Side, val y: Double, val height: Double)

    /** SEGMENTS + 1 points along the top, the same back along the bottom. */
    data class Band(val id: String, val polygon: List<Point>)
    data class SankeyLayout(val bars: List<Bar>, val slots: List<Slot>, val bands: List<Band>, val bus: Box)

    private fun bandEdge(x0: Double, y0: Double, x1: Double, y1: Double): List<Point> {
        val mx = (x0 + x1) / 2
        return (0..SANKEY_SEGMENTS).map { i ->
            val t = i.toDouble() / SANKEY_SEGMENTS
            val u = 1 - t
            Point(
                u * u * u * x0 + 3 * u * u * t * mx + 3 * u * t * t * mx + t * t * t * x1,
                u * u * u * y0 + 3 * u * u * t * y0 + 3 * u * t * t * y1 + t * t * t * y1,
            )
        }
    }

    private val order = compareBy<Resolved>({ it.y }, { it.x })

    fun sankeyLayout(nodes: List<Resolved>, size: Size, fullScale: Double): SankeyLayout {
        val flowing = nodes.filter { it.watts > 0 }
        val total = max(flowing.filter { it.gives }.sumOf { it.watts }, flowing.filter { !it.gives }.sumOf { it.watts })
        val still = total <= 0
        val shown = if (still) nodes else flowing
        val left = shown.filter { it.gives }.sortedWith(order)
        val right = shown.filter { !it.gives }.sortedWith(order)
        val room = size.height - SANKEY_GAP * 4
        val leftBarX = SANKEY_LABEL_W
        val rightBarX = size.width - SANKEY_LABEL_W - SANKEY_BAR_W
        val busX = jsRound(size.width / 2 - SANKEY_BUS_W / 2)
        var scale = if (still) 0.0 else (room * min(1.0, total / max(1.0, fullScale))) / total
        fun band(w: Double) = max(SANKEY_MIN_BAND, w * scale)
        fun fits(list: List<Resolved>) = list.sumOf { max(SANKEY_SLOT_MIN, band(it.watts)) } + SANKEY_GAP * max(0, list.size - 1)
        var i = 0
        while (i < 40 && max(fits(left), fits(right)) > size.height && scale * total > SANKEY_MIN_BAND) {
            scale *= 0.92
            i++
        }

        val bars = mutableListOf<Bar>()
        val slots = mutableListOf<Slot>()
        fun place(list: List<Resolved>, side: Side) {
            var y = (size.height - fits(list)) / 2
            for (n in list) {
                val h = band(n.watts)
                val slot = max(SANKEY_SLOT_MIN, h)
                slots += Slot(n.id, side, y, slot)
                bars += Bar(n.id, side, Box(if (side == Side.LEFT) leftBarX else rightBarX, y + (slot - h) / 2, SANKEY_BAR_W, h))
                y += slot + SANKEY_GAP
            }
        }
        place(left, Side.LEFT)
        place(right, Side.RIGHT)

        val busH = max(SANKEY_MIN_BAND, total * scale)
        val bus = Box(busX, (size.height - busH) / 2, SANKEY_BUS_W, busH)
        val bands = mutableListOf<Band>()
        fun stack(list: List<Resolved>, side: Side) {
            var by = bus.y
            for (n in list) {
                val bar = bars.first { it.id == n.id }.box
                val h = n.watts * scale
                val bh = max(1.5, h)
                val top: List<Point>
                val bottom: List<Point>
                if (side == Side.LEFT) {
                    val x0 = leftBarX + SANKEY_BAR_W - SANKEY_UNDER_BAR
                    val x1 = busX + SANKEY_UNDER_BUS
                    top = bandEdge(x0, bar.y, x1, by)
                    bottom = bandEdge(x0, bar.y + bar.height, x1, by + bh)
                } else {
                    val x0 = busX + SANKEY_BUS_W - SANKEY_UNDER_BUS
                    val x1 = rightBarX + SANKEY_UNDER_BAR
                    top = bandEdge(x0, by, x1, bar.y)
                    bottom = bandEdge(x0, by + bh, x1, bar.y + bar.height)
                }
                bands += Band(n.id, top + bottom.reversed())
                by += h
            }
        }
        if (!still) {
            stack(left, Side.LEFT)
            stack(right, Side.RIGHT)
        }
        return SankeyLayout(bars, slots, bands, bus)
    }

    // ---- Flow (spec decision 9)
    const val FLOW_NODE_R = 32.0
    const val FLOW_BUS_R = 14.0
    const val FLOW_LINE_GAP = 4.0
    const val FLOW_RING_GAP = 7.0
    const val FLOW_LABEL_GAP = 10.0
    private val LABEL_BESIDE_BELOW = cos(PI / 6)

    fun lineWidth(watts: Double): Double = if (!(watts > 0)) 3.0 else max(3.0, min(12.0, 2 + log10(watts + 1) * 3.4))

    data class FlowNode(
        val id: String,
        val x: Double,
        val y: Double,
        val r: Double,
        val live: Boolean,
        val ringR: Double? = null,
        val ringFraction: Double = 0.0,
        /** Where its name's baseline starts; beside: left-aligned right of the circle. */
        val labelX: Double,
        val labelY: Double,
        val labelBeside: Boolean,
    )
    data class FlowLine(val id: String, val x0: Double, val y0: Double, val x1: Double, val y1: Double, val width: Double, val live: Boolean)
    data class FlowLayout(val busX: Double, val busY: Double, val busR: Double, val nodes: List<FlowNode>, val lines: List<FlowLine>)

    fun flowLayout(nodes: List<Node>, resolved: List<Resolved>): FlowLayout {
        val busNode = nodes.firstOrNull { it.role == Role.BUS }
        val bx = busNode?.x ?: 0.0
        val by = busNode?.y ?: 0.0
        val out = mutableListOf<FlowNode>()
        val lines = mutableListOf<FlowLine>()
        for (n in resolved) {
            val live = n.watts > 0
            val dx = bx - n.x
            val dy = by - n.y
            val len = hypot(dx, dy)
            val ux = if (len > 0) dx / len else 0.0
            val uy = if (len > 0) dy / len else 0.0
            val ringed = n.role == Role.STORAGE
            val outer = FLOW_NODE_R + if (ringed) FLOW_RING_GAP else 0.0
            val beside = uy > LABEL_BESIDE_BELOW
            out += FlowNode(
                id = n.id,
                x = n.x,
                y = n.y,
                r = FLOW_NODE_R,
                live = live,
                ringR = if (ringed) FLOW_NODE_R + FLOW_RING_GAP else null,
                ringFraction = if (ringed && n.charge != null) max(0.0, min(1.0, n.charge / 100)) else 0.0,
                labelX = if (beside) n.x + outer + FLOW_LABEL_GAP else n.x,
                labelY = if (beside) n.y - FLOW_NODE_R + 8 else n.y + FLOW_NODE_R + if (ringed) 26 else 20,
                labelBeside = beside,
            )
            if (len <= FLOW_NODE_R + FLOW_LINE_GAP + FLOW_BUS_R) continue
            lines += FlowLine(
                n.id,
                n.x + ux * (FLOW_NODE_R + FLOW_LINE_GAP),
                n.y + uy * (FLOW_NODE_R + FLOW_LINE_GAP),
                bx - ux * FLOW_BUS_R,
                by - uy * FLOW_BUS_R,
                lineWidth(n.watts),
                live,
            )
        }
        return FlowLayout(bx, by, FLOW_BUS_R, out, lines)
    }

    // ---- Names, lines and icons (energy-contract C1)
    const val ROW_LEADING = 1.2
    const val FLOW_LABEL_W = 160.0

    enum class Align { LEFT, CENTER, RIGHT }

    data class LabelMetrics(val fontSize: Double, val valueFontSize: Double)
    data class LabelBox(val x: Double, val y: Double, val width: Double, val align: Align)
    data class NodeBoxes(val id: String, val icon: Box, val label: LabelBox)
    data class DiagramBoxes(val bus: LabelBox?, val nodes: List<NodeBoxes>)

    fun rowStep(size: Double): Double = jsRound(size * ROW_LEADING)

    fun labelHeight(lines: Int, m: LabelMetrics): Double {
        val n = min(MAX_NODE_LINES, max(0, lines))
        return rowStep(m.fontSize) * (1 + max(0, n - 1)) + if (n > 0) rowStep(m.valueFontSize) else 0.0
    }

    fun sankeyBoxes(layout: SankeyLayout, nodes: List<Node>, size: Size, m: LabelMetrics): DiagramBoxes {
        val iconSize = rowStep(m.fontSize)
        val width = SANKEY_LABEL_W - 12 - iconSize - 6
        val out = layout.slots.map { slot ->
            val lines = min(MAX_NODE_LINES, nodes.firstOrNull { it.id == slot.id }?.lines ?: 0)
            val left = slot.side == Side.LEFT
            val top = jsRound(slot.y + (slot.height - labelHeight(lines, m)) / 2)
            val x = if (left) 0.0 else size.width - SANKEY_LABEL_W + 12
            val cx = if (left) SANKEY_LABEL_W - 12 - iconSize / 2 else x + iconSize / 2
            NodeBoxes(
                slot.id,
                Box(jsRound(cx - iconSize / 2), top, iconSize, iconSize),
                LabelBox(if (left) 0.0 else x + iconSize + 6, top, width, if (left) Align.RIGHT else Align.LEFT),
            )
        }
        val bus = if (layout.bars.isNotEmpty()) {
            LabelBox(jsRound(layout.bus.x - 60), max(0.0, jsRound(layout.bus.y - rowStep(m.fontSize) - 4)), 120 + SANKEY_BAR_W, Align.CENTER)
        } else {
            null
        }
        return DiagramBoxes(bus, out)
    }

    val FLOW_ICON: Double = jsRound(FLOW_NODE_R * 0.95)

    fun flowBoxes(layout: FlowLayout, m: LabelMetrics): DiagramBoxes = DiagramBoxes(
        LabelBox(layout.busX + layout.busR + 8, layout.busY - jsRound(m.fontSize * 0.6), 120.0, Align.LEFT),
        layout.nodes.map { fn ->
            val top = if (fn.labelBeside) {
                fn.y - FLOW_NODE_R
            } else {
                max(fn.labelY - jsRound(m.fontSize * 0.8), fn.y + (fn.ringR?.let { it + 4 } ?: (fn.r + 4)))
            }
            NodeBoxes(
                fn.id,
                Box(jsRound(fn.x - FLOW_ICON / 2), jsRound(fn.y - FLOW_ICON / 2), FLOW_ICON, FLOW_ICON),
                LabelBox(if (fn.labelBeside) fn.labelX else fn.labelX - FLOW_LABEL_W / 2, top, FLOW_LABEL_W, if (fn.labelBeside) Align.LEFT else Align.CENTER),
            )
        },
    )

    /** The node a tap at (x, y), relative to the diagram, lands on; never the bus. */
    fun nodeAtTap(sankey: Boolean, nodes: List<Node>, resolved: List<Resolved>, size: Size, fullScale: Double, x: Double, y: Double): String? {
        if (sankey) {
            val layout = sankeyLayout(resolved, size, fullScale)
            return layout.slots.firstOrNull { s ->
                y >= s.y && y < s.y + s.height &&
                    (if (s.side == Side.LEFT) x < SANKEY_LABEL_W + SANKEY_BAR_W else x >= size.width - SANKEY_LABEL_W - SANKEY_BAR_W)
            }?.id
        }
        return nodes.firstOrNull { it.role != Role.BUS && hypot(x - it.x, y - it.y) <= FLOW_NODE_R }?.id
    }

    // ---- Motion (spec decision 10)
    const val DOT_PACE = 40.0
    const val DOT_GAP_1W = 120.0
    const val DOT_GAP_FULL = 8.0
    const val DOT_R = 3.5

    fun dotGap(watts: Double): Double {
        val w = min(1000.0, max(1.0, abs(watts)))
        return DOT_GAP_1W * (DOT_GAP_FULL / DOT_GAP_1W).pow(log10(w) / 3)
    }

    private fun wrap(v: Double): Double = ((v % 1.0) + 1.0) % 1.0

    fun advanceDots(phase: Double, seconds: Double, watts: Double): Double = wrap(phase + (seconds * DOT_PACE) / dotGap(watts))

    fun dotsOnLine(line: FlowLine, phase: Double, gives: Boolean, watts: Double): List<Point> {
        val len = hypot(line.x1 - line.x0, line.y1 - line.y0)
        val gap = dotGap(watts)
        val out = mutableListOf<Point>()
        var d = phase * gap
        while (d < len) {
            val f = if (gives) d / len else 1 - d / len
            out += Point(line.x0 + (line.x1 - line.x0) * f, line.y0 + (line.y1 - line.y0) * f)
            d += gap
        }
        return out
    }

    const val WAVE_PX = 48.0
    const val WAVE_SECONDS = 4.0

    fun advanceWave(phase: Double, seconds: Double): Double = wrap(phase + seconds / WAVE_SECONDS)

    fun waveMix(x: Double, phase: Double): Double = 0.42 - 0.16 * cos((2 * PI * (x - phase * WAVE_PX)) / WAVE_PX)

    fun waveColour(band: IntArray, ground: IntArray, x: Double, phase: Double): IntArray {
        val t = waveMix(x, phase)
        return IntArray(3) { i -> jsRound(band[i] + (ground[i] - band[i]) * t).toInt() }
    }
}
