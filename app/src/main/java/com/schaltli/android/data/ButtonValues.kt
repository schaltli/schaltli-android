package com.schaltli.android.data

/**
 * A button's message sent with values, only when, then clearing (the
 * designer's tasks/ha-alarm-plan.md, decision 4; DDF feature
 * "button-values"): what an alarm panel's Disarm needs - the code typed in a
 * text field put into the command, sent only where it matches, and not left
 * standing in the field.
 *
 * A port of the designer's lib/button-values.ts, as the boards' is
 * (ButtonValues.h):
 * - the message may hold `{topic:…}`, replaced at the press by what that
 *   topic holds (nothing yet: empty). Only that is special: every other
 *   brace stands as written, a command being JSON so often;
 * - `onlyWhen`: a combined topic's name; the press sends nothing unless it
 *   is "true";
 * - `clears`: a local topic set empty after sending - the dispatcher's to do.
 */
object ButtonValues {
    private const val OPEN = "{topic:"

    /**
     * The message with each `{topic:path}` replaced by `valueOf(path)` -
     * empty where it has none - and everything else as written. An opening
     * without its closing brace stays as written too.
     */
    fun fillMessage(message: String, valueOf: (String) -> String?): String {
        val out = StringBuilder()
        var i = 0
        while (true) {
            val at = message.indexOf(OPEN, i)
            if (at < 0) break
            val close = message.indexOf('}', at)
            if (close < 0) break
            out.append(message, i, at).append(valueOf(message.substring(at + OPEN.length, close)) ?: "")
            i = close + 1
        }
        return out.append(message.substring(i)).toString()
    }

    /** What a press sends: the message filled in, or null where its «Only when» is not yes. */
    fun pressedMessage(message: String, onlyWhen: String?, combined: (String) -> String?, topic: (String) -> String?): String? {
        if (!onlyWhen.isNullOrEmpty() && combined(onlyWhen) != "true") return null
        return fillMessage(message, topic)
    }
}
