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
import com.screensmith.android.ui.objects.ArcLevelView
import com.screensmith.android.ui.objects.LevelIndicatorView
import com.screensmith.android.ui.objects.MqttDataLineView
import com.screensmith.android.ui.objects.MqttIconFieldView
import com.screensmith.android.ui.objects.SoftwareButtonView
import com.screensmith.android.ui.objects.SwitchView
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
    // What a finger asked of a value, keyed by topic: a settable level draws
    // it as its marker until the installation answers (MqttRepository's
    // askedValues, the designer's docs/2026-09-17-settable-level.md).
    askedValues: Map<String, String> = emptyMap(),
    assetFileOf: (String) -> File,
    onAction: (ButtonAction) -> Unit,
    // A finger set a level: the value goes to its write topic, and the same
    // value is remembered for the marker topic so the marker shows it until
    // the installation answers (decision 6c).
    onSetLevel: (markerTopic: String, writeTopic: String, value: String) -> Unit = { _, _, _ -> },
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

        for (obj in screen.objects.sortedByZIndex()) {
            DynamicObjectView(
                obj,
                project,
                topicValues,
                assetFileOf,
                onAction,
                screen.backgroundColor,
                askedValues,
                onSetLevel,
            )
        }
    }
}

/**
 * Draw order, ties broken by object id.
 *
 * `sortedBy { it.zIndex }` alone is a stable sort, so two objects sharing a
 * zIndex came out in whatever order the JSON happened to list them. The
 * designer breaks the same tie with `a.zIndex - b.zIndex ||
 * a.id.localeCompare(b.id)` (lib/object-order.ts), which is a total order -
 * the same two objects can never come out in a different sequence twice.
 *
 * The firmware learned this the expensive way on 2026-08-25: its std::sort
 * was given only `zIndex <`, and the first HIL run that ever had two
 * equal-zIndex objects overlapping - a label and a Switch - reported 1533
 * differing pixels, with each side drawing the other one on top. Both were
 * "correct" by their own rule; only one of them had a rule. Adding Switch
 * support here is exactly what makes that overlap reachable on this target
 * too, so the tie-break comes with it.
 *
 * Kotlin's String.compareTo is a byte-order comparison, close enough to
 * localeCompare for object ids, which this app only ever generates out of
 * ASCII.
 */
fun List<ScreenObject>.sortedByZIndex(): List<ScreenObject> =
    sortedWith(compareBy({ it.zIndex }, { it.id }))

/**
 * Type-based dispatch for one object - the Compose equivalent of the
 * firmware's `ScreenRenderer::renderObject` switch (`ScreenRenderer.cpp:133`)
 * and the designer's `drawObject`. Static types (box/line/icon/a bare
 * "panel" outside a switcher) are intentionally no-ops: they're already
 * part of the flattened background image drawn beneath this.
 */
@Composable
fun DynamicObjectView(
    obj: ScreenObject,
    project: Project,
    topicValues: Map<String, String>,
    assetFileOf: (String) -> File,
    onAction: (ButtonAction) -> Unit,
    // Only an arc-level reads this, and only to decide what its
    // anti-aliased edges mix into where its own background is transparent -
    // see ArcLevelView. Passed down rather than looked up because a nested
    // object has no way back to the screen that owns it.
    screenBackgroundColor: String?,
    // What a finger asked of a value, and where a set value goes: a settable
    // level draws the request as its marker and publishes on a tap
    // (docs/2026-09-17-settable-level.md in the designer repo).
    askedValues: Map<String, String> = emptyMap(),
    onSetLevel: (markerTopic: String, writeTopic: String, value: String) -> Unit = { _, _, _ -> },
) {
    // The marker's value for a level object: what a finger asked of it, else
    // what the installation says its setpoint is. Keyed by topic, because two
    // bars on one dimmer are one value and both show the request.
    fun markerValueFor(o: ScreenObject): String {
        val setpointTopic = o.properties.stringOrNull("setpointTopic")?.takeIf { it.isNotEmpty() }
        val markerTopic = setpointTopic ?: o.properties.stringOrNull("topic") ?: ""
        askedValues[markerTopic]?.let { return it }
        return setpointTopic?.let { resolveTopicValue(it, project, topicValues) } ?: ""
    }

    when (obj.type) {
        "text" -> {
            val text = obj.properties.stringOrNull("text") ?: ""
            TextBoxView(obj, project, text, assetFileOf)
        }
        "live-text" -> {
            val value = resolveTopicValue(obj.properties.stringOrNull("topic"), project, topicValues)
            TextBoxView(obj, project, value, assetFileOf)
        }
        "live-icon" -> {
            val value = resolveTopicValue(obj.properties.stringOrNull("topic"), project, topicValues)
            MqttIconFieldView(obj, value, assetFileOf)
        }
        // A slider is a bar a finger can move: the same view, and it draws a
        // handle because the type says so (docs/2026-09-20-control-split.md
        // in the designer repo).
        "bar", "slider" -> {
            val value = resolveTopicValue(obj.properties.stringOrNull("topic"), project, topicValues)
            LevelIndicatorView(obj, project, value, markerValueFor(obj), onSetLevel)
        }
        "gauge", "dial" -> {
            val value = resolveTopicValue(obj.properties.stringOrNull("topic"), project, topicValues)
            // Resolved here rather than inside the view for the same reason
            // every other topic is: one place knows how a topic reference
            // (including its "#jsonpath" suffix) turns into a value.
            // What a finger asked of it wins over the installation's older
            // target: it is that setpoint the finger is setting.
            val setpoint = markerValueFor(obj).takeIf { it.isNotEmpty() }
                ?: obj.properties.stringOrNull("setpointTopic")?.let { resolveTopicValue(it, project, topicValues) }
            ArcLevelView(obj, project, value, setpoint, screenBackgroundColor, assetFileOf, onSetLevel)
        }
        "switch", "button-group" -> {
            val value = resolveTopicValue(obj.properties.stringOrNull("topic"), project, topicValues)
            SwitchView(obj, project, value, assetFileOf, onAction)
        }
        "button" -> SoftwareButtonView(obj, project, assetFileOf, onAction)
        "switcher" ->
            TabControlView(obj, project, topicValues, assetFileOf, onAction, screenBackgroundColor, askedValues, onSetLevel)
        "live-line" -> {
            val value = resolveTopicValue(obj.properties.stringOrNull("topic"), project, topicValues)
            MqttDataLineView(obj, value)
        }
        else -> Unit // box, line, icon, panel (outside a tab-control): already baked into the background.
    }
}
