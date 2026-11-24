package com.schaltli.android.render

import kotlinx.serialization.Serializable

/**
 * A styled instrument's digital readout - the designer's
 * lib/instrument-readout.ts, integer for integer (its device contract §2.15,
 * «The readout»). The designer bakes the characters a readout can show into a
 * strip of alpha (in the bundle a PNG, white, its alpha the strip's); the
 * value is written with them, character by character, in the style's colour.
 * The designer's lib/instrument-readout/vectors.json is the reference
 * (ReadoutGoldenTest).
 */
object Readout {

    /** The readout as the bundle carries it (`instrument.readout`). */
    @Serializable
    data class Description(
        /** Asset path of the strip (a PNG in the app's bundle). */
        val glyphs: String = "",
        val chars: String = "",
        /** Per character: where its cell starts in the strip, how wide the cell is, how far the pen moves. Pixels. */
        val cells: List<List<Int>> = emptyList(),
        /** A cell reaches this far left of the pen (the glow's room). */
        val pad: Int = 0,
        val height: Int = 0,
        /** Pixels, in the object's box: the right edge (right) or the middle (center) of the text, and its top. */
        val x: Int = 0,
        val y: Int = 0,
        /** "right" or "center". */
        val align: String = "right",
        /** RGB565, packed. */
        val color: Int = 0,
        val decimals: Int = 0,
    )

    /** The strip's alpha, one byte (0..255) a pixel, row by row. */
    class Strip(val width: Int, val height: Int, val alpha: IntArray)

    /**
     * A decimal number as the designer's Number() reads one after trim():
     * digits with an optional point and exponent, an optional sign. Anything
     * else (Kotlin's "1f", hex, "12abc") is no number.
     */
    private val DECIMAL = Regex("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")

    /**
     * The text a readout shows for a raw value: the number rounded half away
     * from nought to [decimals] places, a minus only where something is left
     * after rounding, "" for no value or not a number.
     * m = floor(|v| x 10^d + 0.5) in double precision, then whole part and
     * decimals of m.
     */
    fun format(raw: String?, decimals: Int): String {
        if (raw == null) return ""
        val s = raw.trim()
        if (s.isEmpty() || !DECIMAL.matches(s)) return ""
        val v = s.toDoubleOrNull() ?: return ""
        if (v.isNaN() || v.isInfinite()) return ""
        var p = 1.0
        for (i in 0 until decimals) p *= 10
        val m = Math.floor(Math.abs(v) * p + 0.5)
        val whole = Math.floor(m / p)
        val sb = StringBuilder(numberText(whole))
        if (decimals > 0) sb.append('.').append(numberText(m - whole * p).padStart(decimals, '0'))
        return if (v < 0 && m != 0.0) "-$sb" else sb.toString()
    }

    /** A whole, non-negative double as the designer's String() writes it below 1e21. */
    private fun numberText(d: Double): String =
        if (d < 9.007199254740992E15) d.toLong().toString() else java.math.BigDecimal(d).toBigInteger().toString()

    /** Where the text starts (the pen's first position), from its width and the alignment. */
    fun start(text: String, chars: String, cells: List<List<Int>>, x: Int, align: String): Int {
        var total = 0
        for (ch in text) {
            val i = chars.indexOf(ch)
            if (i >= 0) total += cells[i][2]
        }
        return if (align == "right") x - total else x - (total shr 1)
    }

    /**
     * Writes [text] into [t] in the description's colour, with the strip's
     * alpha, [opacity] out of 256 (256: lit). A character the strip lacks is
     * skipped.
     */
    fun draw(t: NeedleRaster.Target, r: Description, strip: Strip, text: String, opacity: Int = 256) {
        val col = Rgb565(r.color)
        var pen = start(text, r.chars, r.cells, r.x, r.align)
        for (ch in text) {
            val i = r.chars.indexOf(ch)
            if (i < 0) continue
            val cell = r.cells[i]
            val sx = cell[0]
            val w = cell[1]
            val advance = cell[2]
            val ox = pen - r.pad
            for (y in 0 until r.height) {
                val ty = r.y + y
                if (ty < 0 || ty >= t.height) continue
                for (x in 0 until w) {
                    val tx = ox + x
                    if (tx < 0 || tx >= t.width) continue
                    val idx = y * strip.width + sx + x
                    if (idx >= strip.alpha.size) continue // a strip shorter than its description: nothing there
                    val al = strip.alpha[idx]
                    val a = ((al + (al shr 7)) * opacity) shr 8
                    if (a <= 0) continue
                    t.set(tx, ty, blend565(Rgb565(t.get(tx, ty)), col, a).packed)
                }
            }
            pen += advance
        }
    }
}
