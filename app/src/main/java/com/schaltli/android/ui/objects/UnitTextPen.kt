package com.schaltli.android.ui.objects

import android.graphics.Paint
import android.graphics.Typeface

/**
 * One piece of text on a control: what it is written in, and how big.
 *
 * Measured in project units and drawn in device pixels, which is why there
 * are two Paints. The designer measures with `ctx.measureText` at the font's
 * own pixel size and rounds the answer up to a whole unit; measuring here at
 * the scaled size and dividing back would put a density-dependent number into
 * an arithmetic whose whole purpose is to land on the same integers - and
 * where a label sits follows from how wide it is, so that number decides
 * pixels, not just text.
 *
 * Shared by the level indicator and the Switch, which lay their text out by
 * the same rules.
 */
internal class UnitTextPen(typeface: Typeface, sizeUnits: Int, scale: Float, argb: Int) {
    val draw = Paint().apply {
        isAntiAlias = true
        this.typeface = typeface
        textSize = sizeUnits * scale
        color = argb
        textAlign = Paint.Align.LEFT
    }
    private val measure = Paint().apply {
        isAntiAlias = true
        this.typeface = typeface
        textSize = sizeUnits.toFloat()
    }

    /** How wide this text is drawn, in whole project units. */
    fun widthOf(text: String): Int = kotlin.math.ceil(measure.measureText(text).toDouble()).toInt()
}
