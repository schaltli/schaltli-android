package com.screensmith.android.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Every distinct MQTT topic referenced anywhere in the project - not just
 * the top-level `topics` array (that's metadata/example values only) but
 * every object's own `properties.topic`, walking the full tree including
 * nested tab-control panels. Mirrors the firmware's
 * `Application::collectTopics` (`Application.cpp:486`) exactly, including
 * stripping an optional `"#jsonpath"` suffix before subscribing - you
 * subscribe to the real MQTT topic, the jsonpath is only for extracting a
 * field from that topic's own payload after it arrives.
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
        val topicRef = (obj.properties["topic"] as? JsonPrimitive)?.contentOrNull
        if (!topicRef.isNullOrEmpty()) {
            into.add(splitTopicPath(topicRef).topic)
        }
        if (obj.children.isNotEmpty()) {
            collectTopicsFromObjects(obj.children, into)
        }
    }
}
