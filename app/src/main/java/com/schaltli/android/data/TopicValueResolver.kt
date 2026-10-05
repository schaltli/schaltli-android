package com.schaltli.android.data

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

fun resolveTopicValue(topicRef: String?, project: Project, liveValues: Map<String, String>): String {
    if (topicRef.isNullOrEmpty()) return ""
    val (topic, path) = splitTopicPath(topicRef)

    val rawValue = liveValues[topic] ?: ""
    if (path.isEmpty()) return rawValue

    return extractJsonField(rawValue, path) ?: ""
}
