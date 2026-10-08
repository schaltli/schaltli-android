package com.schaltli.android.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Live values: whatever changes with one value, set as rules
 * (schaltli-designer docs/2026-10-07-live-values.md, device contract §2.6).
 *
 * A line-by-line port of the designer's `lib/live-value.ts`, as [Placeholders]
 * is of `lib/placeholders.ts`; the firmware has a third. All three are held to
 * the same cases, `live-value-vectors.json` in the test resources - a
 * byte-for-byte copy of the designer's `lib/live-value/vectors.json`. No
 * regular expression, no locale.
 */
object LiveValues {

    /** Where the one value comes from: `topic` (a `#json.path` included), `device`, `project` or `combined`. */
    data class Source(val namespace: String, val path: String)

    /** A text result's piece: literal text, or the value in its format ([isValue]). */
    data class Part(val isValue: Boolean, val text: String = "")

    sealed class Result {
        data class Text(val parts: List<Part>) : Result()
        /** [icon] is the asset id; [path] / [pathDark] what the export wrote for it. */
        data class Icon(val icon: String, val path: String = "", val pathDark: String = "") : Result()
    }

    /** [op] is one of == != < <= > >= yes no. */
    data class Rule(val op: String, val operand: String, val result: Result)

    sealed class Format {
        object AsIs : Format()
        data class Number(val decimals: Int, val grouped: Boolean) : Format()
        /** h:mm:ss, h:mm or m:ss */
        data class Duration(val pattern: String) : Format()
    }

    data class LiveValue(
        val id: String,
        val source: Source,
        val format: Format = Format.AsIs,
        val rules: List<Rule> = emptyList(),
        /** Absent: the value in its format. */
        val otherwise: Result? = null,
        /** Absent: nothing. */
        val noValueYet: Result? = null,
    )

    /** Which branch applies: a rule's index, Otherwise or No value yet; [result] null where it is left to the default. */
    sealed class Applies {
        abstract val result: Result?
        data class RuleBranch(val rule: Int, override val result: Result) : Applies()
        data class Otherwise(override val result: Result?) : Applies()
        data class NoValueYet(override val result: Result?) : Applies()
    }

    private fun isWhitespace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r'

    private fun isDigit(c: Char) = c in '0'..'9'

    private fun trimmed(value: String): String {
        var start = 0
        var end = value.length
        while (start < end && isWhitespace(value[start])) start++
        while (end > start && isWhitespace(value[end - 1])) end--
        return value.substring(start, end)
    }

    private fun lowerAscii(value: String): String =
        buildString { for (c in value) append(if (c in 'A'..'Z') c + 32 else c) }

    /** A plain decimal number once trimmed (sign, digits, point, digits), or null. */
    fun asNumber(value: String): Double? {
        val text = trimmed(value)
        var i = 0
        if (i < text.length && (text[i] == '+' || text[i] == '-')) i++
        var digits = 0
        while (i < text.length && isDigit(text[i])) { i++; digits++ }
        if (i < text.length && text[i] == '.') {
            i++
            while (i < text.length && isDigit(text[i])) { i++; digits++ }
        }
        if (i != text.length || digits == 0) return null
        // Checked to be a plain decimal above; Java reads "+1", "1." and ".5" as JS Number() does.
        return text.toDouble()
    }

    fun isYes(value: String): Boolean {
        val word = lowerAscii(trimmed(value))
        if (word == "true" || word == "on" || word == "yes") return true
        val number = asNumber(word)
        return number != null && number != 0.0
    }

    fun isNo(value: String): Boolean {
        val word = lowerAscii(trimmed(value))
        if (word.isEmpty() || word == "false" || word == "off" || word == "no") return true
        val number = asNumber(word)
        return number != null && number == 0.0
    }

    fun matches(value: String, op: String, operand: String): Boolean {
        when (op) {
            "yes" -> return isYes(value)
            "no" -> return isNo(value)
            "==" -> return trimmed(value) == trimmed(operand)
            "!=" -> return trimmed(value) != trimmed(operand)
        }
        val a = asNumber(value) ?: return false
        val b = asNumber(operand) ?: return false
        return when (op) {
            "<" -> a < b
            "<=" -> a <= b
            ">" -> a > b
            ">=" -> a >= b
            else -> false
        }
    }

    fun evaluate(liveValue: LiveValue, value: String?): Applies {
        if (value == null) return Applies.NoValueYet(liveValue.noValueYet)
        liveValue.rules.forEachIndexed { i, rule ->
            if (matches(value, rule.op, rule.operand)) return Applies.RuleBranch(i, rule.result)
        }
        return Applies.Otherwise(liveValue.otherwise)
    }

    private fun twoDigits(n: Long) = if (n < 10) "0$n" else "$n"

    private fun formatDuration(value: String, pattern: String): String? {
        val text = trimmed(value)
        if (asNumber(text) == null) return null
        var i = if (text.isNotEmpty() && text[0] == '+') 1 else 0
        if (i < text.length && text[i] == '-') return null
        var seconds = 0L
        while (i < text.length && isDigit(text[i])) seconds = seconds * 10 + (text[i++] - '0')
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when (pattern) {
            "h:mm:ss" -> "$h:${twoDigits(m)}:${twoDigits(s)}"
            "h:mm" -> "$h:${twoDigits(m)}"
            else -> "${seconds / 60}:${twoDigits(s)}"
        }
    }

    fun formatValue(value: String, format: Format, separators: Placeholders.Separators): String = when (format) {
        Format.AsIs -> value
        is Format.Number ->
            Placeholders.formatNumber(value, Placeholders.NumberFormat(if (format.grouped) 'N' else 'F', format.decimals), separators) ?: value
        is Format.Duration -> formatDuration(value, format.pattern) ?: value
    }

    /** What a live value reads as text; an icon result reads as nothing. */
    fun textOf(liveValue: LiveValue, value: String?, separators: Placeholders.Separators): String {
        val applies = evaluate(liveValue, value)
        val formatted = if (value != null) formatValue(value, liveValue.format, separators) else ""
        val result = applies.result ?: return if (applies is Applies.Otherwise) formatted else ""
        if (result !is Result.Text) return ""
        return buildString { for (part in result.parts) append(if (part.isValue) formatted else part.text) }
    }

    // The `<id>` of a `{live:<id>}` that starts at `at`, and the index after it.
    private fun liveReferenceAt(text: String, at: Int): Pair<String, Int>? {
        val head = "{live:"
        if (!text.startsWith(head, at)) return null
        val close = text.indexOf('}', at + head.length)
        if (close < 0) return null
        val id = text.substring(at + head.length, close)
        if (id.isEmpty() || id.any { it == '{' || isWhitespace(it) }) return null
        return id to close + 1
    }

    /** A text with `{live:<id>}` references as it reads. */
    fun resolveLiveText(
        text: String,
        liveValues: List<LiveValue>,
        lookup: (Source) -> String?,
        separators: Placeholders.Separators = Placeholders.Separators(),
    ): String = buildString {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if ((c == '{' || c == '}') && i + 1 < text.length && text[i + 1] == c) {
                append(c)
                i += 2
                continue
            }
            if (c == '{') {
                val reference = liveReferenceAt(text, i)
                val found = reference?.let { (id, _) -> liveValues.lastOrNull { it.id == id } }
                if (reference != null && found != null) {
                    append(textOf(found, lookup(found.source), separators))
                    i = reference.second
                    continue
                }
            }
            append(c)
            i++
        }
    }

    // --- reading the export (contract §2.6) ---

    private fun JsonElement?.str(default: String = ""): String =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: default

    fun parseSource(json: JsonElement?): Source {
        val o = json as? JsonObject ?: return Source("", "")
        return Source(o["namespace"].str(), o["path"].str())
    }

    private fun parseResult(json: JsonElement?): Result {
        val o = json as? JsonObject ?: return Result.Text(emptyList())
        if (o["kind"].str("text") == "icon") return Result.Icon(o["icon"].str(), o["path"].str(), o["pathDark"].str())
        val parts = (o["parts"] as? JsonArray).orEmpty().map { part ->
            if (part is JsonPrimitive && part.isString) Part(false, part.content) else Part(true)
        }
        return Result.Text(parts)
    }

    fun parseLiveValue(json: JsonElement?): LiveValue {
        val o = json as? JsonObject ?: return LiveValue("", Source("", ""))
        val f = o["format"] as? JsonObject
        val format = when (f?.get("kind").str("asIs")) {
            "number" -> Format.Number(
                (f?.get("decimals") as? JsonPrimitive)?.intOrNull ?: 0,
                (f?.get("grouped") as? JsonPrimitive)?.booleanOrNull ?: false,
            )
            "duration" -> Format.Duration(f?.get("pattern").str("h:mm:ss"))
            else -> Format.AsIs
        }
        val rules = (o["rules"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { rule ->
            Rule(rule["op"].str("=="), rule["operand"].str(), parseResult(rule["result"]))
        }
        return LiveValue(
            id = o["id"].str(),
            source = parseSource(o["source"]),
            format = format,
            rules = rules,
            otherwise = (o["otherwise"] as? JsonObject)?.let { parseResult(it) },
            noValueYet = (o["noValueYet"] as? JsonObject)?.let { parseResult(it) },
        )
    }

    fun parseLiveValues(json: JsonElement?): List<LiveValue> =
        (json as? JsonArray).orEmpty().map { parseLiveValue(it) }
}
