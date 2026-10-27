package com.schaltli.android.data

/**
 * A time field: a topic's time of day in a field, and a new one set on a
 * picker and sent to the write topic (the designer's tasks/ha-time-plan.md,
 * an MQTT `time` of Home Assistant).
 *
 * Ported line by line from the designer's lib/time-field.ts, as the boards'
 * TimeField.h is: what is read as a time, how it is shown, what is sent, how
 * a minute lands on the step. The field is drawn as a text field's.
 */
object TimeField {
    const val DEFAULT_STEP = 5
    const val NO_TIME = "--:--"

    data class TimeOfDay(val hour: Int, val minute: Int)

    /** One of 1, 5, 10, 15, 30; anything else is the default. */
    fun step(minuteStep: Double?): Int {
        val s = minuteStep?.takeIf { it == Math.floor(it) }?.toInt()
        return if (s in listOf(1, 5, 10, 15, 30)) s!! else DEFAULT_STEP
    }

    private fun Char?.digit() = this != null && this in '0'..'9'

    /** The digits at [i], at most [max]: their value and where they end; null for none. */
    private fun digitsAt(text: String, i: Int, max: Int): Pair<Int, Int>? {
        var end = i
        var value = 0
        while (end < text.length && end - i < max && text[end].digit()) value = value * 10 + (text[end++] - '0')
        return if (end > i) value to end else null
    }

    /**
     * The time of day in a reported value: H:MM or HH:MM, either with :SS
     * and a fraction after it, or an ISO date and time whose time is kept as
     * written. Blanks around it are ignored; anything else is no time.
     */
    fun parse(value: String?): TimeOfDay? {
        var text = value?.trim() ?: return null
        if (text.length > 11 && text[4] == '-' && text[7] == '-' && (text[10] == 'T' || text[10] == 't' || text[10] == ' ')) {
            for (i in intArrayOf(0, 1, 2, 3, 5, 6, 8, 9)) if (!text[i].digit()) return null
            text = text.substring(11)
            val cut = text.indexOfFirst { it == 'Z' || it == 'z' || it == '+' || it == '-' }
            if (cut >= 0) text = text.substring(0, cut)
        }
        val (hour, hourEnd) = digitsAt(text, 0, 2) ?: return null
        if (text.getOrNull(hourEnd) != ':') return null
        val (minute, minuteEnd) = digitsAt(text, hourEnd + 1, 2) ?: return null
        if (minuteEnd - hourEnd - 1 != 2) return null
        var end = minuteEnd
        if (text.getOrNull(end) == ':') {
            val (second, secondEnd) = digitsAt(text, end + 1, 2) ?: return null
            if (secondEnd - end - 1 != 2 || second > 59) return null
            end = secondEnd
            if (text.getOrNull(end) == '.') end = (digitsAt(text, end + 1, 9) ?: return null).second
        }
        if (end != text.length || hour > 23 || minute > 59) return null
        return TimeOfDay(hour, minute)
    }

    /** The field's text: 06:45, always 24 hours; «--:--» for no time. */
    fun format(time: TimeOfDay?): String =
        if (time == null) NO_TIME else "%02d:%02d".format(time.hour, time.minute)

    /** A time put on the step to the nearest, half a step up: 06:58 on 5 is 07:00, 23:58 is 00:00. */
    fun onStep(time: TimeOfDay, step: Int): TimeOfDay {
        val total = time.hour * 60 + time.minute
        val rounded = ((2 * total + step) / (2 * step)) * step % 1440
        return TimeOfDay(rounded / 60, rounded % 60)
    }

    /** What OK publishes: HH:MM:00, as Home Assistant's time.isoformat() writes it. */
    fun payload(time: TimeOfDay): String = format(time) + ":00"
}
