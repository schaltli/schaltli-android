package com.screensmith.android.ui.objects

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A settable level's setpoint marker: what was asked for, beside what is
 * measured (the designer's docs/2026-09-17-settable-level.md, decision 6c).
 *
 * Three shapes - a line across the track, a round knob on it, a triangle
 * pointing at it - each a set of whole pixels decided by integer arithmetic
 * and listed as rectangles. Word for word the same as markerShapeRects() in
 * the designer's render-level-indicator.ts and the block in the firmware's
 * ColorScreenRenderer, because conformance compares all three of them pixel
 * for pixel and a path rasterizer is the one thing none of them could copy
 * from the others.
 *
 * Everything here is in project units; the caller converts to pixels.
 */
data class MarkerRect(val x: Int, val y: Int, val w: Int, val h: Int)

object MarkerShape {
    const val PADDING = 4

    /** The line every shape is built around: on the fill's own edge. */
    fun line(
        objX: Int,
        objY: Int,
        objWidth: Int,
        objHeight: Int,
        barDirection: String,
        setpointPercent: Double,
        markerWidth: Int,
    ): MarkerRect {
        val innerX = objX + PADDING
        val innerY = objY + PADDING
        val innerWidth = objWidth - PADDING * 2
        val innerHeight = objHeight - PADDING * 2
        val thickness = max(1, markerWidth)
        val vertical = barDirection == "bottom-to-top" || barDirection == "top-to-bottom"

        if (vertical) {
            val filled = (innerHeight * setpointPercent / 100).toInt()
            val edge = if (barDirection == "bottom-to-top") innerY + innerHeight - filled else innerY + filled
            val y = min(innerY + innerHeight - thickness, max(innerY, edge - thickness / 2))
            return MarkerRect(innerX, y, innerWidth, min(thickness, innerHeight))
        }

        val filled = (innerWidth * setpointPercent / 100).toInt()
        val edge = if (barDirection == "right-to-left") innerX + innerWidth - filled else innerX + filled
        val x = min(innerX + innerWidth - thickness, max(innerX, edge - thickness / 2))
        return MarkerRect(x, innerY, min(thickness, innerWidth), innerHeight)
    }

    fun rects(
        objX: Int,
        objY: Int,
        objWidth: Int,
        objHeight: Int,
        barDirection: String,
        setpointPercent: Double,
        markerWidth: Int,
        style: String,
    ): List<MarkerRect> {
        val line = line(objX, objY, objWidth, objHeight, barDirection, setpointPercent, markerWidth)
        if (style != "round" && style != "triangle") return listOf(line)

        val thickness = max(1, markerWidth)
        val vertical = barDirection == "bottom-to-top" || barDirection == "top-to-bottom"
        val centreX = line.x + line.w / 2
        val centreY = line.y + line.h / 2

        if (style == "round") {
            val radius = max(2, thickness)
            val rects = mutableListOf<MarkerRect>()
            for (dy in -radius..radius) {
                val span = sqrt((radius * radius - dy * dy).toDouble()).toInt()
                if (span < 0) continue
                rects += MarkerRect(centreX - span, centreY + dy, span * 2 + 1, 1)
            }
            return rects
        }

        val available = if (vertical) line.w / 2 else line.h / 2
        val height = max(2, min(available, thickness * 2))
        val halfBase = thickness
        val rects = mutableListOf<MarkerRect>()
        for (i in 0 until height) {
            val half = halfBase * (height - 1 - i) / (height - 1)
            rects += if (vertical) {
                MarkerRect(line.x + i, centreY - half, 1, half * 2 + 1)
            } else {
                MarkerRect(centreX - half, line.y + i, half * 2 + 1, 1)
            }
        }
        return rects
    }
}
