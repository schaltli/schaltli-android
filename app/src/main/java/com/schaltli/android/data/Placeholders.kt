package com.schaltli.android.data

/**
 * Placeholders in texts: `Küche {topic:home/kitchen/humidity:F0} %`
 * (schaltli-designer docs/2026-09-25-text-placeholders.md, the devices' side
 * docs/2026-10-05-placeholder-devices.md).
 *
 * A line-by-line port of the designer's `lib/placeholders.ts`, in the same
 * order, so a change there is easy to find here; the firmware has a third
 * (`src/project/Placeholders.cpp`). All three are held to the same cases:
 * `placeholder-vectors.json` in the test resources, a byte-for-byte copy of
 * the designer's `lib/placeholders/vectors.json` ([PlaceholdersTest]). The
 * comments that explain a rule live in the designer's file.
 */
object Placeholders {

    data class Separators(val decimal: String = ".", val thousands: String = "'")

    /** `F` fixed decimals, `N` the same with thousands grouped; 0-9 digits. */
    data class NumberFormat(val kind: Char, val digits: Int)

    /** [namespace] is `topic`, `device` or `project`; [path] a topic (with `#json.path`) or a field. */
    data class Reference(val namespace: String, val path: String)

    sealed class Segment {
        data class Literal(val text: String) : Segment()
        data class Placeholder(
            val source: String,
            val reference: Reference,
            val fallback: String?,
            val format: NumberFormat?,
        ) : Segment()
        /** Unknown, reserved or broken: shown exactly as written, braces included. */
        data class Raw(val source: String, val reason: String) : Segment()
    }

    // The fields v1 resolves. device:name and project:version are reserved
    // and, like any unknown field, shown as written.
    private val FIELDS = mapOf("device" to listOf("model", "id"), "project" to listOf("name"))
    private val NAMESPACES = listOf("topic", "device", "project")

    private fun isWhitespace(c: Char?) = c == ' ' || c == '\t' || c == '\n' || c == '\r'

    private fun isDigit(c: Char?) = c != null && c in '0'..'9'

    private fun asFormat(text: String): NumberFormat? {
        if (text.length != 2) return null
        if ((text[0] != 'F' && text[0] != 'N') || !isDigit(text[1])) return null
        return NumberFormat(text[0], text[1] - '0')
    }

    private fun parseBody(body: String, source: String): Segment {
        fun raw(reason: String) = Segment.Raw(source, reason)

        if (body.startsWith("(")) return raw("expressions are reserved for later")

        val colon = body.indexOf(':')
        val namespace = if (colon > 0) body.substring(0, colon) else null
        if (namespace == null || namespace !in NAMESPACES) return raw("unknown namespace")

        var end = colon + 1
        while (end < body.length && !isWhitespace(body[end])) end++
        var path = body.substring(colon + 1, end)
        val rest = body.substring(end)

        var fallback: String? = null
        var format: NumberFormat? = null

        if (rest.isNotEmpty()) {
            var i = 0
            while (i < rest.length && isWhitespace(rest[i])) i++
            if (!rest.startsWith("??", i) || i + 2 >= rest.length || !isWhitespace(rest[i + 2])) {
                return raw("operators are reserved for later")
            }
            i += 2
            while (i < rest.length && isWhitespace(rest[i])) i++
            if (rest.getOrNull(i) == '"') {
                val close = rest.indexOf('"', i + 1)
                if (close < 0) return raw("unterminated text")
                fallback = rest.substring(i + 1, close)
                i = close + 1
            } else {
                val start = i
                if (rest.getOrNull(i) == '-') i++
                while (i < rest.length && (isDigit(rest[i]) || rest[i] == '.')) i++
                fallback = rest.substring(start, i)
                if (fallback == "" || fallback == "-") return raw("a fallback is a number or quoted text")
            }
            val tail = rest.substring(i)
            if (tail != "") {
                if (tail[0] != ':') return raw("operators are reserved for later")
                format = asFormat(tail.substring(1)) ?: return raw("unknown format")
            }
        } else {
            val last = path.lastIndexOf(':')
            if (last > 0) {
                val candidate = asFormat(path.substring(last + 1))
                if (candidate != null) {
                    format = candidate
                    path = path.substring(0, last)
                }
            }
        }

        if (path == "") return raw("nothing after the namespace")
        if (namespace != "topic" && path !in FIELDS.getValue(namespace)) return raw("unknown field")

        return Segment.Placeholder(source, Reference(namespace, path), fallback, format)
    }

    /** A text as literals and placeholders, in order. */
    fun parse(text: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        val literal = StringBuilder()
        fun flush() {
            if (literal.isNotEmpty()) segments.add(Segment.Literal(literal.toString()))
            literal.setLength(0)
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '{' && text.getOrNull(i + 1) == '{') {
                literal.append('{')
                i += 2
                continue
            }
            if (c == '}' && text.getOrNull(i + 1) == '}') {
                literal.append('}')
                i += 2
                continue
            }
            if (c != '{') {
                literal.append(c)
                i++
                continue
            }
            var j = i + 1
            var quoted = false
            while (j < text.length && (quoted || text[j] != '}')) {
                if (text[j] == '"') quoted = !quoted
                j++
            }
            if (j >= text.length) {
                flush()
                segments.add(Segment.Raw(text.substring(i), "no closing }"))
                return segments
            }
            flush()
            segments.add(parseBody(text.substring(i + 1, j), text.substring(i, j + 1)))
            i = j + 1
        }
        flush()
        return segments
    }

    /**
     * A decimal payload rounded and written with the separators, or null if it
     * is not a plain decimal number (then it is shown as it is). Rounded on
     * the digits as written, half away from zero, never through a Double.
     */
    fun formatNumber(value: String, format: NumberFormat, separators: Separators): String? {
        // JavaScript's trim(), which the designer uses, takes these too.
        val text = value.trim { it.isWhitespace() || it == Char(0xFEFF) }
        var i = 0
        var negative = false
        if (text.getOrNull(i) == '+' || text.getOrNull(i) == '-') {
            negative = text[i] == '-'
            i++
        }
        val whole = StringBuilder()
        while (i < text.length && isDigit(text[i])) whole.append(text[i++])
        val fraction = StringBuilder()
        if (text.getOrNull(i) == '.') {
            i++
            while (i < text.length && isDigit(text[i])) fraction.append(text[i++])
        }
        if (i != text.length || (whole.isEmpty() && fraction.isEmpty())) return null

        val kept = fraction.toString().padEnd(format.digits, '0').substring(0, format.digits)
        var digits = (if (whole.isEmpty()) "0" else whole.toString()) + kept
        if ((fraction.getOrNull(format.digits) ?: '0') >= '5') {
            val out = digits.toCharArray()
            var k = out.size - 1
            while (k >= 0) {
                if (out[k] == '9') {
                    out[k] = '0'
                    k--
                } else {
                    out[k] = out[k] + 1
                    break
                }
            }
            digits = (if (k < 0) "1" else "") + String(out)
        }

        // Leading zeros off, but never the last digit (the designer's /^0+(?=\d)/).
        var intPart = digits.substring(0, digits.length - format.digits)
        var zeros = 0
        while (zeros + 1 < intPart.length && intPart[zeros] == '0') zeros++
        intPart = intPart.substring(zeros)
        if (intPart == "") intPart = "0"
        val fracPart = digits.substring(digits.length - format.digits)

        if (format.kind == 'N' && separators.thousands != "") {
            val grouped = StringBuilder()
            for (k in intPart.indices) {
                if (k > 0 && (intPart.length - k) % 3 == 0) grouped.append(separators.thousands)
                grouped.append(intPart[k])
            }
            intPart = grouped.toString()
        }

        val isZero = digits.all { it == '0' }
        val sign = if (negative && !isZero) "-" else ""
        return sign + intPart + (if (format.digits > 0) separators.decimal + fracPart else "")
    }

    /**
     * The text as it reads. [lookup] answers null for a value that never
     * arrived - the only case `??` applies to - and "" for an empty message
     * that did.
     */
    fun resolve(text: String, lookup: (Reference) -> String?, separators: Separators = Separators()): String {
        val out = StringBuilder()
        for (segment in parse(text)) {
            when (segment) {
                is Segment.Literal -> out.append(segment.text)
                is Segment.Raw -> out.append(segment.source)
                is Segment.Placeholder -> {
                    val value = lookup(segment.reference) ?: segment.fallback ?: ""
                    val formatted = segment.format?.let { formatNumber(value, it, separators) }
                    out.append(formatted ?: value)
                }
            }
        }
        return out.toString()
    }

    /** Every topic a text refers to, each once, `#json.path` included. */
    fun referencedTopics(text: String): List<String> =
        parse(text)
            .filterIsInstance<Segment.Placeholder>()
            .filter { it.reference.namespace == "topic" }
            .map { it.reference.path }
            .distinct()
}
