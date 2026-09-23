package com.schaltli.android.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * JSONPath shorthand member/index syntax - a real *subset* of JSONPath, not
 * a look-alike invented syntax. Ported line-for-line from the designer's
 * `lib/json-path.ts` (and, on the firmware side, `ProjectLoader.cpp`) - all
 * three must parse a given path identically, or a value that looks right in
 * the designer's preview could resolve to something different here. See
 * that file's own header comment for the full rationale (why wildcards/
 * recursive-descent/filters are deliberately unsupported).
 */

private sealed interface PathSegment {
    data class Member(val name: String) : PathSegment
    data class Index(val index: Int) : PathSegment
}

private fun tokenizePath(path: String): List<PathSegment> {
    var s = path.trim()
    if (s.startsWith("$")) s = s.substring(1)

    val segments = mutableListOf<PathSegment>()
    var i = 0

    while (i < s.length) {
        when {
            s[i] == '.' -> {
                i++
                val name = StringBuilder()
                while (i < s.length && s[i] != '.' && s[i] != '[') {
                    name.append(s[i])
                    i++
                }
                if (name.isNotEmpty()) segments.add(PathSegment.Member(name.toString()))
            }
            s[i] == '[' -> {
                val closeIndex = s.indexOf(']', i)
                if (closeIndex == -1) break // malformed - stop parsing, resolve what we have so far
                val inner = s.substring(i + 1, closeIndex).trim()
                val quoted = (inner.startsWith("'") && inner.endsWith("'")) ||
                    (inner.startsWith("\"") && inner.endsWith("\""))
                if (quoted) {
                    segments.add(PathSegment.Member(inner.substring(1, inner.length - 1)))
                } else {
                    segments.add(PathSegment.Index(inner.toIntOrNull() ?: return segments))
                }
                i = closeIndex + 1
            }
            else -> {
                // Bare leading name with no "." or "$" prefix - the
                // ergonomic shorthand this app's paths have always used
                // ("temp" instead of requiring "$.temp" or ".temp").
                val name = StringBuilder()
                while (i < s.length && s[i] != '.' && s[i] != '[') {
                    name.append(s[i])
                    i++
                }
                if (name.isNotEmpty()) segments.add(PathSegment.Member(name.toString()))
            }
        }
    }

    return segments
}

/**
 * Walks [value] along [path]. Returns null if the path doesn't resolve
 * (missing field, index out of range, or indexing into a non-object/
 * non-array) rather than throwing - a malformed or stale path is a
 * configuration problem to surface as "no value", not a crash.
 */
fun getJsonPathValue(value: JsonElement, path: String): JsonElement? {
    if (path.isEmpty()) return value

    var current: JsonElement? = value

    for (segment in tokenizePath(path)) {
        if (current == null) return null

        current = when (segment) {
            is PathSegment.Member -> (current as? JsonObject)?.get(segment.name)
            is PathSegment.Index -> (current as? JsonArray)?.getOrNull(segment.index)
        }
    }

    return current
}

/**
 * Parses [jsonText] and extracts [path], formatting the result the same way
 * a real MQTT payload value is used elsewhere in the app: strings, numbers,
 * and booleans become their plain text representation; objects/arrays (a
 * path that stops partway through a nested structure) are JSON-stringified;
 * a parse failure or unresolved path returns null.
 */
fun extractJsonField(jsonText: String, path: String): String? {
    val parsed = try {
        Json.parseToJsonElement(jsonText)
    } catch (e: Exception) {
        return null
    }

    val value = getJsonPathValue(parsed, path) ?: return null
    return when {
        value is JsonObject || value is JsonArray -> value.toString()
        value is JsonPrimitive && value.isString -> value.content
        else -> value.jsonPrimitive.content
    }
}

/** `path` is "" when there's no "#" (a plain, non-JSON topic reference). */
data class TopicPath(val topic: String, val path: String)

fun splitTopicPath(composite: String): TopicPath {
    val hashIndex = composite.indexOf('#')
    if (hashIndex == -1) return TopicPath(composite, "")
    return TopicPath(composite.substring(0, hashIndex), composite.substring(hashIndex + 1))
}
