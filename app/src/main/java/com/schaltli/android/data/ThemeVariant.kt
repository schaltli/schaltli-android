package com.schaltli.android.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Light or dark for the whole installation, retained on the broker: `light`
 * or `dark`, and absent means light (the designer's
 * docs/device-contract.md §4, docs/2026-09-25-theme-topic.md). Set by the
 * VanPi bridge in answer to `schaltli/cmnd/theme`; this app only reads it.
 */
const val THEME_TOPIC = "schaltli/state/theme"

/** Whether a payload of [THEME_TOPIC] means dark. Anything else - empty, unknown - is light. */
fun isDarkTheme(payload: String?): Boolean = payload?.trim().equals("dark", ignoreCase = true)

/**
 * The project as it is drawn while the theme is dark: every field `X` that
 * has an `XDark` beside it takes that value, anywhere in the tree - colours
 * inside `properties`, a screen's `backgroundColor` and `backgroundImage`, a
 * button's `path` and `pressedPath`, a switch state's `path` and
 * `activePath`, a live icon's pairs, a tab-control's children.
 *
 * One transform over the raw JSON, before it is decoded, rather than a
 * variant flag in every view: the views read the light names as they always
 * have and draw the dark project without knowing it, and the typed fields
 * the parser would otherwise drop (`ignoreUnknownKeys`) arrive with their
 * dark values. The same rule as the designer's `darkVariantOf`
 * (lib/themes.ts) and the firmware's, held to the golden the designer
 * records (theme-variant-golden.json, docs/2026-09-26-device-switch.md):
 *
 *  - an `XDark` replaces its `X`, whole;
 *  - a lone `XDark` (no `X`) counts as `X`;
 *  - an empty `XDark` means "use `X`";
 *  - a key named just `Dark` is an ordinary key.
 */
fun darkVariantOf(element: JsonElement): JsonElement = when (element) {
    is JsonArray -> JsonArray(element.map { darkVariantOf(it) })
    is JsonObject -> {
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, value) in element) {
            if (key.length > DARK.length && key.endsWith(DARK)) {
                val base = key.dropLast(DARK.length)
                if (base !in element && !isEmptyString(value)) out[base] = value
                continue
            }
            val dark = element[key + DARK]
            out[key] = if (dark != null && !isEmptyString(dark)) dark else darkVariantOf(value)
        }
        JsonObject(out)
    }
    else -> element
}

private const val DARK = "Dark"

private fun isEmptyString(element: JsonElement): Boolean =
    element is JsonPrimitive && element.isString && element.content.isEmpty()
