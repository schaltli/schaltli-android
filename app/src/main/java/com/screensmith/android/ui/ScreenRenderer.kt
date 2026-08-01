package com.screensmith.android.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.screensmith.android.data.Project
import com.screensmith.android.data.Screen
import com.screensmith.android.data.ScreenObject
import com.screensmith.android.data.ButtonAction
import com.screensmith.android.data.resolveTopicValue
import com.screensmith.android.ui.objects.LevelIndicatorView
import com.screensmith.android.ui.objects.MqttDataLineView
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
            // The background is a pixel-exact rendering of box/line/icon
            // objects at the project's own 360x800 reference resolution -
            // it needs to reach the screen with zero resampling artifacts
            // when Compose scales it up to the device's real (much higher)
            // pixel density. Coil's AsyncImage got this most of the way
            // (filterQuality = FilterQuality.None killed the bilinear blur,
            // matching the web designer's own "imageRendering: pixelated"),
            // but its underlying Paint still draws with anti-aliasing on,
            // which softens the *edges* of the scaled bitmap independently
            // of filterQuality - visible as single stray dark pixels
            // bleeding a row past a hard black/white border at certain
            // fractional scale offsets (2026-07-27 HIL finding, confirmed
            // reproducible across repeated captures). Drawing the decoded
            // bitmap directly via a native Paint with isAntiAlias = false
            // (alongside isFilterBitmap = false, the native equivalent of
            // FilterQuality.None) removes both smoothing sources at once.
            val bitmap = remember(path) {
                BitmapFactory.decodeFile(assetFileOf(path).path)?.asImageBitmap()
            }
            bitmap?.let { image ->
                val androidBitmap = image.asAndroidBitmap()
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawIntoCanvas { canvas ->
                        val paint = android.graphics.Paint().apply {
                            isAntiAlias = false
                            isFilterBitmap = false
                        }
                        val dst = android.graphics.Rect(0, 0, size.width.toInt(), size.height.toInt())
                        canvas.nativeCanvas.drawBitmap(androidBitmap, null, dst, paint)
                    }
                }
            }
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
        "MqttDataLine" -> {
            val value = resolveTopicValue(obj.properties.stringOrNull("topic"), project, topicValues)
            MqttDataLineView(obj, value)
        }
        else -> Unit // box, line, icon, panel (outside a tab-control): already baked into the background.
    }
}
