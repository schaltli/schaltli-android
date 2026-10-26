package com.schaltli.android.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Scaled values: a number times a fraction plus an offset, rounded to a number
 * of decimals - what a scaled topic reads and a write format sends
 * (schaltli-designer tasks/ha-templates-stage2-plan.md, decisions 1 and 3;
 * device contract §2.11). A port of the designer's `lib/scaled-value.ts`, line
 * by line, as the firmware's ScaledValue.cpp is; the cases are
 * `scaled-value-vectors.json`.
 *
 * No Double: every intermediate fits in a Long within the bounds below.
 */
object ScaledValue {

    /** times [numerator] / [denominator], plus [offset], rounded to [decimals]. */
    data class Scale(val numerator: Long = 1, val denominator: Long = 1, val offset: String = "0", val decimals: Int = 0)

    /** What a level sends: [text] with `{value}` in it, the value scaled first where [scale] is set. */
    data class WriteFormat(val text: String, val scale: Scale? = null)

    const val MAX_FACTOR = 1_000_000L
    const val MAX_SIGNIFICANT_DIGITS = 12
    const val MAX_FRACTION_DIGITS = 9
    const val MAX_OFFSET_DIGITS = 6
    const val MAX_DECIMALS = 6

    /** A result and an offset stay below this. */
    const val LIMIT = 1_000_000_000_000L

    data class Decimal(val units: Long, val places: Int)

    private fun pow10(n: Int): Long {
        var p = 1L
        repeat(n) { p *= 10 }
        return p
    }

    /**
     * A plain decimal - sign, digits, a point, digits, nothing else but spaces
     * around - as an integer and the places after its point; null where it is
     * not one, or has more digits than allowed.
     */
    fun parseDecimal(text: String, maxSignificant: Int, maxFraction: Int): Decimal? {
        var start = 0
        var end = text.length
        while (start < end && text[start] == ' ') start++
        while (end > start && text[end - 1] == ' ') end--
        var i = start
        var negative = false
        if (i < end && (text[i] == '+' || text[i] == '-')) {
            negative = text[i] == '-'
            i++
        }
        val digits = StringBuilder()
        var places = 0
        var seenDigit = false
        while (i < end && text[i] in '0'..'9') {
            digits.append(text[i++])
            seenDigit = true
        }
        if (i < end && text[i] == '.') {
            i++
            while (i < end && text[i] in '0'..'9') {
                digits.append(text[i++])
                places++
                seenDigit = true
            }
        }
        if (i != end || !seenDigit || places > maxFraction) return null
        var first = 0
        while (first < digits.length - 1 && digits[first] == '0') first++
        if (digits.length - first > maxSignificant) return null
        var units = 0L
        for (k in first until digits.length) units = units * 10 + (digits[k] - '0')
        return Decimal(if (negative) -units else units, places)
    }

    /** An integer count of 10^-decimals as a plain decimal: 2150 at 2 is "21.50"; never "-0". */
    private fun decimalText(scaled: Long, decimals: Int): String {
        val negative = scaled < 0
        var digits = (if (negative) -scaled else scaled).toString()
        while (digits.length <= decimals) digits = "0$digits"
        val whole = digits.substring(0, digits.length - decimals)
        val fraction = digits.substring(digits.length - decimals)
        return (if (negative) "-" else "") + whole + (if (decimals > 0) ".$fraction" else "")
    }

    private fun validScale(scale: Scale): Decimal? {
        if (scale.denominator < 1 || scale.denominator > MAX_FACTOR || scale.numerator < -MAX_FACTOR || scale.numerator > MAX_FACTOR) return null
        if (scale.decimals < 0 || scale.decimals > MAX_DECIMALS) return null
        val offset = parseDecimal(scale.offset, 18, MAX_OFFSET_DIGITS) ?: return null
        if (offset.places > scale.decimals) return null
        val whole = offset.units / pow10(offset.places)
        if (whole >= LIMIT || -whole >= LIMIT) return null
        return offset
    }

    /**
     * [value] × numerator / denominator + offset, rounded half away from zero
     * to decimals, as a plain decimal; null where the value is not a plain
     * decimal, the scale is not one this allows, or the result is out of bounds.
     */
    fun scale(value: String, scale: Scale): String? {
        val valid = validScale(scale) ?: return null
        val parsed = parseDecimal(value, MAX_SIGNIFICANT_DIGITS, MAX_FRACTION_DIGITS) ?: return null
        val k = scale.decimals

        // value × n / d = X / D: X below 10^18, D below 10^15.
        val x = parsed.units * scale.numerator
        val d = pow10(parsed.places) * scale.denominator
        val negative = x < 0
        val magnitude = if (negative) -x else x
        val whole = magnitude / d
        if (whole >= LIMIT) return null
        // |X| / D × 10^k as Q + r / D, digit by digit: r stays below D, 10r below 10^16.
        var q = whole
        var r = magnitude % d
        repeat(k) {
            r *= 10
            q = q * 10 + r / d
            r %= d
        }
        // The offset in the same units, then the whole as floor F plus rr / D, 0 ≤ rr < D.
        val offset = valid.units * pow10(k - valid.places)
        val f: Long
        val rr: Long
        if (!negative) {
            f = q + offset
            rr = r
        } else if (r == 0L) {
            f = offset - q
            rr = 0
        } else {
            f = offset - q - 1
            rr = d - r
        }
        // Half away from zero.
        val rounded = when {
            f >= 0 -> f + (if (2 * rr >= d) 1 else 0)
            rr == 0L -> f
            else -> -(-f - 1 + (if (2 * (d - rr) >= d) 1 else 0))
        }
        val limit = LIMIT * pow10(k)
        if (rounded >= limit || -rounded >= limit) return null
        return decimalText(rounded, k)
    }

    /**
     * What a level publishes for the value set: the write format's text with
     * the value - scaled first where the format scales - in place of
     * `{value}`. Only `{value}` is special. Null where the value cannot be
     * scaled: nothing is sent then.
     */
    fun formatWrite(value: String, format: WriteFormat?): String? {
        if (format == null) return value
        val put = if (format.scale != null) scale(value, format.scale) ?: return null else value
        val marker = "{value}"
        val out = StringBuilder()
        var i = 0
        while (i < format.text.length) {
            if (format.text.startsWith(marker, i)) {
                out.append(put)
                i += marker.length
            } else {
                out.append(format.text[i++])
            }
        }
        return out.toString()
    }

    /** A factor that is not a whole number leaves a scale no value passes: denominator 0. */
    fun parseScale(json: JsonObject): Scale {
        var broken = false
        fun factor(e: JsonElement?): Long {
            val n = (e as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            if (n == null) broken = true
            return n ?: 0
        }
        val numerator = if (json["numerator"] == null) 1 else factor(json["numerator"])
        val denominator = if (json["denominator"] == null) 1 else factor(json["denominator"])
        val offset = (json["offset"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "0"
        val decimalsElement = json["decimals"] as? JsonPrimitive
        val decimals = if (decimalsElement == null) 0 else decimalsElement.takeIf { !it.isString }?.intOrNull ?: -1
        return Scale(numerator, if (broken) 0 else denominator, offset, decimals)
    }

    /** A level's `writeFormat` as the export writes it, or null where it has none. */
    fun parseWriteFormat(json: JsonElement?): WriteFormat? {
        val o = json as? JsonObject ?: return null
        val text = (o["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
        val scaled = o["numerator"] != null || o["denominator"] != null || o["offset"] != null
        return WriteFormat(text, if (scaled) parseScale(o) else null)
    }
}
