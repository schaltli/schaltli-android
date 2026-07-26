package com.screensmith.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.screensmith.android.data.Project
import com.screensmith.android.data.Screen
import com.screensmith.android.data.ScreenObject
import com.screensmith.android.data.ButtonAction
import com.screensmith.android.data.resolveTopicValue
import com.screensmith.android.ui.objects.LevelIndicatorView
import com.screensmith.android.ui.objects.MqttIconFieldView
import com.screensmith.android.ui.objects.SoftwareButtonView
import com.screensmith.android.ui.objects.TabControlView
import com.screensmith.android.ui.objects.TextBoxView
import com.screensmith.android.ui.objects.parseHexColor
import com.screensmith.android.ui.objects.stringOrNull
import java.io.File

/**
 * Renders one screen: the flattened background PNG (already has this
 * screen's static box/line/icon objects baked in - see
 * `lib/android-export.ts` in the designer repo) full-bleed, then the
 * screen's *dynamic* objects on top via [DynamicObjectView]. 1 project unit
 * = 1dp: the Android DDF's reference resolution (360x800) was deliberately
 * chosen to already be dp-scaled, so no separate fit/scale step is applied
 * here - a real device's own density handles the rest, same as any other
 * dp-based Compose layout.
 */
@Composable
fun ScreenRenderer(
    screen: Screen,
    project: Project,
    topicValues: Map<String, String>,
    assetFileOf: (String) -> File,
    onAction: (ButtonAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = screen.backgroundColor?.let(::parseHexColor) ?: Color.White

    Box(
        modifier = modifier
            .size(width = project.screenWidth.dp, height = project.screenHeight.dp)
            .background(backgroundColor),
    ) {
        screen.backgroundImage?.let { path ->
            AsyncImage(
                model = assetFileOf(path),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }

        for (obj in screen.objects.sortedBy { it.zIndex }) {
            DynamicObjectView(obj, project, topicValues, assetFileOf, onAction)
        }
    }
}

/**
 * Type-based dispatch for one object - the Compose equivalent of the
 * firmware's `ScreenRenderer::renderObject` switch (`ScreenRenderer.cpp:133`)
 * and the designer's `drawObject`. Static types (box/line/icon/a bare
 * "panel" outside a tab-control) are intentionally no-ops: they're already
 * part of the flattened background image drawn beneath this.
 */
@Composable
fun DynamicObjectView(
    obj: ScreenObject,
    project: Project,
    topicValues: Map<String, String>,
    assetFileOf: (String) -> File,
    onAction: (ButtonAction) -> Unit,
) {
    when (obj.type) {
        "label" -> {
            val text = obj.properties.stringOrNull("text") ?: ""
            TextBoxView(obj, project, text, assetFileOf)
        }
        "MqttDataField", "field" -> {
            val value = resolveTopicValue(obj.properties.stringOrNull("topic"), project, topicValues)
            TextBoxView(obj, project, value, assetFileOf)
        }
        "MQTTIconField" -> {
            val value = resolveTopicValue(obj.properties.stringOrNull("topic"), project, topicValues)
            MqttIconFieldView(obj, value, assetFileOf)
        }
        "level-indicator" -> {
            val value = resolveTopicValue(obj.properties.stringOrNull("topic"), project, topicValues)
            LevelIndicatorView(obj, project, value)
        }
        "SoftwareButton" -> SoftwareButtonView(obj, project, assetFileOf, onAction)
        "tab-control" -> TabControlView(obj, project, topicValues, assetFileOf, onAction)
        else -> Unit // box, line, icon, panel (outside a tab-control): already baked into the background.
    }
}
