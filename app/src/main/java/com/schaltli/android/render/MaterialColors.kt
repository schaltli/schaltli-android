package com.schaltli.android.render

/**
 * The colour rules every Material-styled control shares - this repo's copy of
 * the designer's `lib/material-colors.ts`.
 *
 * One place there, one place here, because the button, the switch and the
 * slider have to agree: a tonal button beside a slider's empty track beside a
 * switch's track are the same colour, or they look like three unrelated
 * controls.
 *
 * Whole channels and integer arithmetic, like everything else a port has to
 * land on exactly. The designer runs its colour-depth quantiser over the
 * results; this platform declares 24 bit (DdfBuilder), where that step is the
 * identity, so there is nothing to quantise here.
 */

/** A colour as three whole channels, or null for anything that is not hex. */
fun colorChannels(color: String): IntArray? {
    val c = color.trim().lowercase()
    if (SHORT_HEX.matches(c)) {
        return intArrayOf(
            "${c[1]}${c[1]}".toInt(16),
            "${c[2]}${c[2]}".toInt(16),
            "${c[3]}${c[3]}".toInt(16),
        )
    }
    if (LONG_HEX.matches(c)) {
        return intArrayOf(c.substring(1, 3).toInt(16), c.substring(3, 5).toInt(16), c.substring(5, 7).toInt(16))
    }
    return null
}

private val SHORT_HEX = Regex("^#[0-9a-f]{3}$")
private val LONG_HEX = Regex("^#[0-9a-f]{6}([0-9a-f]{2})?$")

fun colorHex(channels: IntArray): String =
    "#" + channels.joinToString("") { it.coerceIn(0, 255).toString(16).padStart(2, '0') }

/**
 * [over] laid on [under] at [percent]. An unreadable colour leaves [under] as
 * it is, which is always a colour that exists rather than a guess.
 */
fun blendColors(under: String, over: String, percent: Int): String {
    val u = colorChannels(under) ?: return under
    val o = colorChannels(over) ?: return under
    return colorHex(IntArray(3) { i -> u[i] + Math.round(((o[i] - u[i]) * percent) / 100.0).toInt() })
}

/** WCAG's relative luminance. */
fun relativeLuminance(color: String): Double {
    val c = colorChannels(color) ?: return 1.0
    val linear = DoubleArray(3) { i ->
        val s = c[i] / 255.0
        if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]
}

/** A colour's tone: its CIELAB lightness L*, which is what Material picks by. */
fun colorTone(color: String): Double {
    val y = relativeLuminance(color)
    return if (y <= 216.0 / 24389.0) (y * 24389.0) / 27.0 else 116.0 * Math.cbrt(y) - 16.0
}

/**
 * White on a dark colour, black on a light one - Material's own rule
 * (DynamicColor.tonePrefersLightForeground): white below a tone of 60.
 *
 * Not "whichever has the higher WCAG contrast", which switches at tone 49 and
 * so puts black on Material's own blue, on a red and on a petrol - more
 * contrast by the number, less to the eye.
 */
fun onColorFor(color: String): String = if (Math.round(colorTone(color)) < 60) "#ffffff" else "#000000"

/**
 * The colour a control takes when its author set none - the designer's
 * `controlPalette(depth).fill`.
 *
 * Only the 24-bit entry, because that is what this platform's DDF declares.
 * A 1-bit panel's palette is a different set of literals over there, and a
 * port of this file to one would have to bring them.
 */
const val CONTROL_FILL = "#6750A4"
