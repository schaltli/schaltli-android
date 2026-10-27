package com.schaltli.android.data

/**
 * A text field: a topic's text in a field, and a new one typed and sent to
 * its write topic (the designer's tasks/ha-text-plan.md, an MQTT `text` of
 * Home Assistant).
 *
 * Ported line by line from the designer's lib/text-field.ts, as the boards'
 * TextField.h is: how long a text is, what a hidden one shows, where the
 * parts sit. The phone types on its own system keyboard; the length rules
 * are the same as on the boards.
 */
object TextField {
    /** Home Assistant's own bounds for an MQTT text: `min` 0, `max` 255. */
    const val MAX_LENGTH = 255

    /**
     * A text's length in characters - code points, as Python's `len()`
     * counts them, which is what Home Assistant checks `min` and `max`
     * against. Not UTF-16 units: an emoji is two of them and one character.
     */
    fun textLength(text: String): Int = text.codePointCount(0, text.length)

    /** The first [max] characters of [text], never half a surrogate pair. */
    fun clampText(text: String, max: Int): String =
        if (textLength(text) <= max) text else text.substring(0, text.offsetByCodePoints(0, max))

    data class Bounds(val minLength: Int, val maxLength: Int)

    /** A field's own bounds, kept inside 0-255, max at least 1, min never above max. */
    fun bounds(minLength: Double?, maxLength: Double?): Bounds {
        fun whole(v: Double?, fallback: Int) =
            if (v == null || v.isNaN() || v.isInfinite()) fallback else v.toInt().coerceIn(0, MAX_LENGTH)
        val max = whole(maxLength, MAX_LENGTH).coerceAtLeast(1)
        return Bounds(whole(minLength, 0).coerceAtMost(max), max)
    }

    /** Whether OK sends: the text is within the field's bounds. */
    fun canSend(text: String, bounds: Bounds): Boolean = textLength(text) in bounds.minLength..bounds.maxLength

    /** What a hidden text is drawn as, character for character. */
    const val HIDDEN_CHAR = "•"

    /** What the field draws for [text]: as it is, or one dot per character where it is hidden. */
    fun shownText(text: String, hidden: Boolean): String = if (!hidden) text else HIDDEN_CHAR.repeat(textLength(text))

    /**
     * Where the field's parts go, in whole pixels: the container a rounded
     * rectangle over the whole object, the corner a fifth of the height; a
     * 2-px line along the bottom between the corners; the text from [pad]
     * in, cut [pad] before the right edge.
     */
    data class Geometry(val radius: Int, val pad: Int, val lineY: Int, val lineHeight: Int)

    const val LINE = 2

    fun geometry(width: Int, height: Int): Geometry {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val radius = minOf(h / 5, w / 2)
        val pad = maxOf(radius, h / 4)
        val lineHeight = minOf(LINE, h)
        return Geometry(radius, pad, h - lineHeight, lineHeight)
    }

    /** The text's baseline relative to the object's top: its line centred in the height. */
    fun baseline(height: Int, ascent: Int, descent: Int): Int {
        val h = height.coerceAtLeast(1)
        return (h - (ascent + descent)) / 2 + ascent
    }
}
