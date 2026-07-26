package com.screensmith.android.data

/**
 * Resolves a topic reference (possibly a composite "topic#jsonpath") to its
 * current display value: the live MQTT value if one has arrived yet
 * (extracting the jsonpath field for JSON topics), else the topic's own
 * first declared example - mirrors the designer's
 * `getPreviewValueFromTopic` (`lib/render-screen.ts`) so a screen looks the
 * same here before the first real message as it does in the designer
 * preview, instead of showing nothing.
 */
fun resolveTopicValue(topicRef: String?, project: Project, liveValues: Map<String, String>): String {
    if (topicRef.isNullOrEmpty()) return ""
    val (topic, path) = splitTopicPath(topicRef)

    val rawValue = liveValues[topic] ?: project.topics.find { it.topic == topic }?.examples?.firstOrNull() ?: ""
    if (path.isEmpty()) return rawValue

    return extractJsonField(rawValue, path) ?: ""
}
