package com.schaltli.android.ui.objects

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.schaltli.android.data.ButtonAction
import com.schaltli.android.data.Project
import com.schaltli.android.data.ScreenObject
import com.schaltli.android.data.TextField
import com.schaltli.android.render.fillRoundRect
import com.schaltli.android.render.fontMetricsOf
import com.schaltli.android.ui.LocalBundleInstallation
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * A text field (the designer's tasks/ha-text-plan.md): the designer's
 * render-text-field.ts and the boards' renderTextField - a rounded container,
 * a 2-px line along its bottom, the text or its dots cut at the inner edge -
 * and, tapped, the phone's own keyboard: a line with what the field shows,
 * OK sending the text as it is, Cancel or back sending nothing. The keyboard's
 * layout is the phone's, which is why the app has no «Keyboard» setting of
 * its own (decision 3).
 *
 * What it shows: what was sent until the topic answers ([askedValue], as a
 * slider's handle), then the topic's text. Without a read topic it reads the
 * write topic - the export leaves `topic` empty then, as the boards' loader
 * fills it.
 */
@Composable
fun TextFieldView(
    obj: ScreenObject,
    project: Project,
    value: String,
    askedValue: String,
    assetFileOf: (String) -> java.io.File,
    onAction: (ButtonAction) -> Unit,
    onAsked: (readTopic: String, readValue: String) -> Unit,
) {
    val props = obj.properties
    val hidden = (props["hidden"] as? JsonPrimitive)?.booleanOrNull == true
    // «Digits» (button-values): the phone's number pad.
    val digits = props.stringOrNull("keyboard") == "digits"
    val writeTopic = props.stringOrNull("writeTopic")?.takeIf { it.isNotEmpty() }
    val readTopic = props.stringOrNull("topic")?.takeIf { it.isNotEmpty() } ?: writeTopic
    val bounds = TextField.bounds(props.doubleOrNull("minLength"), props.doubleOrNull("maxLength"))

    val shown = askedValue.ifEmpty { value }
    var editing by remember(obj.id) { mutableStateOf(false) }

    FieldBox(obj, project, TextField.shownText(shown, hidden), assetFileOf, "text-field-${obj.id}", onTap = writeTopic?.let { { editing = true } })

    if (editing && writeTopic != null) {
        TextEntryDialog(
            initial = TextField.clampText(shown, bounds.maxLength),
            hidden = hidden,
            digits = digits,
            bounds = bounds,
            onCancel = { editing = false },
            onSend = { text ->
                editing = false
                // Not retained: a command, as a button's message. The field
                // shows it until its topic answers.
                onAction(ButtonAction(type = "send-mqtt", mqttTopic = writeTopic, mqttMessage = text, literal = true))
                if (readTopic != null) onAsked(readTopic, text)
            },
        )
    }
}

/**
 * The field and [text] in it - a text field's, and a time field's
 * (TimeFieldView.kt), which shows its time the same way: the container, the
 * 2-px line along its bottom, the text cut at the inner edge. [onTap] null:
 * nothing to set, a tap passes through.
 */
@Composable
fun FieldBox(
    obj: ScreenObject,
    project: Project,
    text: String,
    assetFileOf: (String) -> java.io.File,
    tag: String,
    onTap: (() -> Unit)?,
) {
    val props = obj.properties
    val field = props.colorOrDefault("fieldColor", Color(0xFFE7E0EC))
    val line = props.colorOrDefault("lineColor", Color(0xFF6750A4))
    val ink = props.colorOrDefault("textColor", Color(0xFF1D1B20))
    val fontMeta = project.fonts.find { it.id == props.stringOrNull("fontId") }
    val fontSize = fontMeta?.size ?: 14
    // The designer's fontMetricsOf, as the level's number uses it: a TTF's
    // measured capital height is its ascent. Its raw ascent put the text a
    // dp lower than the designer's (the first HIL run, 2026-10-27).
    val metrics = fontMetricsOf(fontMeta, 14)
    val ascent = metrics.ascent
    val descent = metrics.descent
    val typefacePath = fontMeta?.path?.let { assetFileOf(it) }
    val typeface = remember(typefacePath, LocalBundleInstallation.current) {
        typefacePath?.takeIf { it.exists() }?.let { Typeface.createFromFile(it) } ?: Typeface.DEFAULT
    }

    val x = obj.x.toInt()
    val y = obj.y.toInt()
    val w = obj.width.toInt().coerceAtLeast(1)
    val h = obj.height.toInt().coerceAtLeast(1)
    val g = TextField.geometry(w, h)

    Box(
        modifier = Modifier
            .offset(x = x.dp, y = y.dp)
            .size(width = w.dp, height = h.dp)
            .testTag(tag)
            .pointerInput(obj.id, onTap != null) {
                if (onTap != null) detectTapGestures(onTap = { onTap() })
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val scale = density
            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint().apply { isAntiAlias = false; style = android.graphics.Paint.Style.FILL }
                paint.color = field.toArgb()
                fillRoundRect(canvas.nativeCanvas, paint, 0, 0, w, h, g.radius, scale)
                if (w > 2 * g.radius) {
                    paint.color = line.toArgb()
                    fillRoundRect(canvas.nativeCanvas, paint, g.radius, g.lineY, w - 2 * g.radius, g.lineHeight, 0, scale)
                }
                if (text.isEmpty()) return@drawIntoCanvas
                val textPaint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    this.typeface = typeface
                    textSize = fontSize * scale
                    color = ink.toArgb()
                }
                val native = canvas.nativeCanvas
                native.save()
                native.clipRect(g.pad * scale, 0f, (w - g.pad) * scale, h * scale)
                native.drawText(text, g.pad * scale, TextField.baseline(h, ascent, descent) * scale, textPaint)
                native.restore()
            }
        }
    }
}

/** The line the phone's keyboard types into, with the count, Cancel and OK. */
@Composable
private fun TextEntryDialog(
    initial: String,
    hidden: Boolean,
    digits: Boolean,
    bounds: TextField.Bounds,
    onCancel: () -> Unit,
    onSend: (String) -> Unit,
) {
    var entry by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    val sendable = TextField.canSend(entry.text, bounds)
    Dialog(onDismissRequest = onCancel) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), modifier = Modifier.testTag("text-field-entry")) {
            Column(modifier = Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = entry,
                    onValueChange = { next ->
                        // The keys stop at max, counted in characters.
                        val text = TextField.clampText(next.text, bounds.maxLength)
                        entry = if (text == next.text) next else TextFieldValue(text, TextRange(text.length))
                    },
                    singleLine = true,
                    visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = when {
                            digits && hidden -> KeyboardType.NumberPassword
                            digits -> KeyboardType.Number
                            hidden -> KeyboardType.Password
                            else -> KeyboardType.Text
                        },
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (sendable) onSend(entry.text) }),
                    supportingText = { Text("${TextField.textLength(entry.text)}/${bounds.maxLength}") },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("text-field-input"),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel, modifier = Modifier.testTag("text-field-cancel")) { Text("Cancel") }
                    Button(onClick = { onSend(entry.text) }, enabled = sendable, modifier = Modifier.testTag("text-field-ok")) { Text("OK") }
                }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}
