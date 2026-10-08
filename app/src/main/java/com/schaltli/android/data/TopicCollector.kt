package com.schaltli.android.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Every distinct MQTT topic referenced anywhere in the project - not just
 * the top-level `topics` array (that's metadata/example values only) but
 * every topic-naming property an object carries ([READ_TOPIC_KEYS]), walking
 * the full tree including nested tab-control panels. Follows the firmware's
 * `Application::collectTopics` (`Application.cpp:486`), including stripping
 * an optional `"#jsonpath"` suffix before subscribing - you subscribe to the
 * real MQTT topic, the jsonpath is only for extracting a field from that
 * topic's own payload after it arrives.
 */
fun Project.collectTopicNames(): Set<String> {
    val topics = mutableSetOf<String>()
    for (screen in screens) {
        collectTopicsFromObjects(screen.objects, topics, combinedOrder)
    }
    // A popup's objects read their topics as a screen's do; walked from
    // 2026-10-06, when popups came - the knob had the same gap.
    for (popup in popups) {
        collectTopicsFromObjects(popup.objects, topics, combinedOrder)
    }
    return topics
}

private fun collectTopicsFromObjects(
    objects: List<ScreenObject>,
    into: MutableSet<String>,
    combined: List<CombinedTopics.CombinedTopic>,
) {
    for (obj in objects) {
        for (key in READ_TOPIC_KEYS) {
            val topicRef = (obj.properties[key] as? JsonPrimitive)?.contentOrNull
            if (!topicRef.isNullOrEmpty()) {
                into.add(splitTopicPath(topicRef).topic)
            }
        }
        // And every topic a text's placeholder names: a text has no binding,
        // so without this its topics were never subscribed and it showed `??`
        // for good (the designer's docs/2026-10-05-placeholder-devices.md).
        if (obj.type == "text") {
            val text = (obj.properties["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            for (reference in Placeholders.referencedTopics(text)) into.add(splitTopicPath(reference).topic)
        }
        // And every topic a live value reads - a combined topic's down to the
        // topics under it (the designer's docs/device-contract.md §2.6).
        for (liveValue in LiveValues.parseLiveValues(obj.properties["liveValues"])) {
            when (liveValue.source.namespace) {
                "topic" -> into.add(splitTopicPath(liveValue.source.path).topic)
                CombinedTopics.NAMESPACE -> into.addAll(CombinedTopics.inputTopics(combined, liveValue.source.path))
            }
        }
        if (obj.children.isNotEmpty()) {
            collectTopicsFromObjects(obj.children, into, combined)
        }
    }
}

/**
 * Every property naming a topic this app has to *read*.
 *
 * `topic` is the one every object type shares. `setpointTopic` is the
 * arc-level's second reading - the marker on the ring saying where the value
 * is headed - and it needs a subscription for the same reason the first one
 * does.
 *
 * The Waveshare firmware's own `collectTopics` (main.cpp:1995) still walks
 * only `properties.topic`, so an arc-level's setpoint marker moves there
 * only when some other object on the project happens to read the same topic.
 * Reproducing that here would be reproducing a bug rather than matching a
 * behaviour, so this list carries both.
 *
 * A Switch's `writeTopic` is deliberately absent: it is a command
 * destination, never read back. The state comes home over `topic`.
 */
private val READ_TOPIC_KEYS = listOf("topic", "setpointTopic")
