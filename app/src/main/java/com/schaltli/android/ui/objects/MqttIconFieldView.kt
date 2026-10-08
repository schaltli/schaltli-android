package com.schaltli.android.ui.objects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.schaltli.android.data.ScreenObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * MQTTIconField: picks one of several icon SVGs based on the topic's
 * current value (exact string match against each `valueIconPairs` entry),
 * same selection rule the designer/firmware use.
 */
@Composable
fun MqttIconFieldView(obj: ScreenObject, currentValue: String, assetFileOf: (String) -> java.io.File) {
    val props = obj.properties
    val pairs = props["valueIconPairs"] as? JsonArray

    // No value yet, no icon - not even one paired with an empty value.
    val matchedPath = pairs
        ?.takeIf { currentValue.isNotBlank() }
        ?.filterIsInstance<JsonObject>()
        ?.find { it.stringOrNull("value") == currentValue }
        ?.stringOrNull("path")

    IconPathView(obj, matchedPath, assetFileOf)
}

/**
 * One icon picture at an object's place, over its background colour: a live
 * icon's (the designer's docs/device-contract.md §2.6) or a Live Icon's pair.
 * No [path], no picture.
 */
@Composable
fun IconPathView(obj: ScreenObject, path: String?, assetFileOf: (String) -> java.io.File) {
    val backgroundColor = obj.properties.colorOrDefault("backgroundColor", Color.Transparent)
    Box(
        modifier = Modifier
            .offset(x = obj.x.dp, y = obj.y.dp)
            .size(width = obj.width.dp, height = obj.height.dp)
            .background(backgroundColor),
    ) {
        if (path != null) {
            AsyncImage(
                model = assetFileOf(path),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
