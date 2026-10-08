package com.schaltli.android.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Resolves a topic reference (possibly a composite "topic#jsonpath") to its
 * current display value: the live MQTT value if one has arrived yet
 * (extracting the jsonpath field for JSON topics), else "" - no value.
 *
 * Not the topic's first example any more (until 2026-09-15 it was): examples
 * are for designing a screen, and a panel that shows one before anything has
 * arrived shows a tank as full or a relay as on without saying so. Every
 * side - firmware, this app, the designer's live preview - now shows nothing
 * until a real value arrives, each type in its own way
 * (docs/2026-09-15-live-data.md in the designer repo, decision 6).
 */
/**
 * A text with its placeholders resolved (the designer's
 * docs/2026-10-05-placeholder-devices.md): `topic:` from the live values,
 * `device:` from what [deviceModel] and [deviceId] say, numbers in the
 * project's separators. A topic nothing has arrived on, or a JSON field its
 * payload lacks, is "never arrived" - the designer's preview says the same -
 * and only that takes a `??` fallback.
 */
fun resolvePlaceholders(
    text: String,
    project: Project,
    liveValues: Map<String, String>,
    deviceModel: String,
    deviceId: String,
): String {
    if (!text.contains('{')) return text
    return Placeholders.resolve(
        text,
        { reference ->
            when (reference.namespace) {
                "topic" -> {
                    val (topic, path) = splitTopicPath(reference.path)
                    val raw = liveValues[topic]
                    if (raw == null || path.isEmpty()) raw else extractJsonField(raw, path)
                }
                "device" -> if (reference.path == "model") deviceModel else deviceId
                // project: is written in by the export and never arrives here.
                else -> null
            }
        },
        Placeholders.Separators(project.decimalSeparator, project.thousandsSeparator),
    )
}

/** A topic reference's value, its JSON field picked out; null while nothing has arrived. */
private fun arrivedValue(topicRef: String, liveValues: Map<String, String>): String? {
    val (topic, path) = splitTopicPath(topicRef)
    val raw = liveValues[topic]
    return if (raw == null || path.isEmpty()) raw else extractJsonField(raw, path)
}

/** Every combined topic's value from the topics' current ones: "true", "false", or null (§2.6). */
fun combinedValues(project: Project, liveValues: Map<String, String>): Map<String, String?> =
    if (project.combinedOrder.isEmpty()) emptyMap()
    else CombinedTopics.compute(project.combinedOrder) { path -> arrivedValue(path, liveValues) }

/**
 * A live value's source as it reads now (the designer's
 * docs/device-contract.md §2.6): a topic's value, a combined topic's yes/no,
 * the device's model or id, the project's name - null where nothing has
 * arrived yet, which is No value yet.
 */
fun liveSourceValue(
    source: LiveValues.Source,
    project: Project,
    liveValues: Map<String, String>,
    deviceModel: String,
    deviceId: String,
): String? = when (source.namespace) {
    "topic" -> arrivedValue(source.path, liveValues)
    CombinedTopics.NAMESPACE -> combinedValues(project, liveValues)[source.path]
    "device" -> if (source.path == "model") deviceModel else deviceId
    "project" -> if (source.path == "name") project.name else null
    else -> null
}

/** A text's `liveText` with its `{live:<id>}` references read through `liveValues` (§2.6). */
fun resolveLiveText(
    liveText: String,
    properties: JsonObject,
    project: Project,
    liveValues: Map<String, String>,
    deviceModel: String,
    deviceId: String,
): String = LiveValues.resolveLiveText(
    liveText,
    LiveValues.parseLiveValues(properties["liveValues"]),
    { source -> liveSourceValue(source, project, liveValues, deviceModel, deviceId) },
    Placeholders.Separators(project.decimalSeparator, project.thousandsSeparator),
)

/**
 * The picture a live icon shows now: the `path` of the icon result that
 * applies (the export writes one per result, tinted, and under the dark theme
 * darkVariantOf has already put the dark one there), or null for none - a
 * branch that gives no icon draws nothing.
 */
fun liveIconPath(
    properties: JsonObject,
    project: Project,
    liveValues: Map<String, String>,
    deviceModel: String,
    deviceId: String,
): String? {
    val id = (properties["liveIconId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return null
    val liveValue = LiveValues.parseLiveValues(properties["liveValues"]).lastOrNull { it.id == id } ?: return null
    val result = LiveValues.evaluate(liveValue, liveSourceValue(liveValue.source, project, liveValues, deviceModel, deviceId)).result
    return (result as? LiveValues.Result.Icon)?.path?.takeIf { it.isNotEmpty() }
}

fun resolveTopicValue(topicRef: String?, project: Project, liveValues: Map<String, String>): String {
    if (topicRef.isNullOrEmpty()) return ""
    val (topic, path) = splitTopicPath(topicRef)

    val rawValue = liveValues[topic] ?: ""
    if (path.isEmpty()) return rawValue

    return extractJsonField(rawValue, path) ?: ""
}
