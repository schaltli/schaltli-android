package com.screensmith.android.ui.objects

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** Small accessors over a ScreenObject's loose `properties` bag - mirrors how the designer's own renderers pull typed values out of `obj.properties: Record<string, any>`. */

fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

fun JsonObject.string(key: String, default: String): String = stringOrNull(key) ?: default

fun JsonObject.doubleOrNull(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

fun JsonObject.double(key: String, default: Double): Double = doubleOrNull(key) ?: default

/** Parses a "#rrggbb"/"#rgb" hex color; "transparent" -> Color.Transparent; anything unparseable -> [default]. */
fun JsonObject.colorOrDefault(key: String, default: Color): Color {
    val raw = stringOrNull(key) ?: return default
    return parseHexColor(raw) ?: default
}

fun parseHexColor(raw: String): Color? {
    if (raw == "transparent") return Color.Transparent
    if (!raw.startsWith("#")) return null
    return try {
        val hex = raw.removePrefix("#")
        val normalized = when (hex.length) {
            3 -> hex.map { "$it$it" }.joinToString("")
            6 -> hex
            else -> return null
        }
        Color(android.graphics.Color.parseColor("#$normalized"))
    } catch (e: IllegalArgumentException) {
        null
    }
}
