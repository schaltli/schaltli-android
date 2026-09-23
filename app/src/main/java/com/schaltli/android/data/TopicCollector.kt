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
        collectTopicsFromObjects(screen.objects, topics)
    }
    return topics
}

private fun collectTopicsFromObjects(objects: List<ScreenObject>, into: MutableSet<String>) {
    for (obj in objects) {
        for (key in READ_TOPIC_KEYS) {
            val topicRef = (obj.properties[key] as? JsonPrimitive)?.contentOrNull
            if (!topicRef.isNullOrEmpty()) {
                into.add(splitTopicPath(topicRef).topic)
            }
        }
        if (obj.children.isNotEmpty()) {
            collectTopicsFromObjects(obj.children, into)
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
