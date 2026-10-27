package com.schaltli.android.ui.objects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.schaltli.android.data.ButtonAction
import com.schaltli.android.data.Project
import com.schaltli.android.data.ScreenObject
import com.schaltli.android.data.TimeField

/**
 * A time field (the designer's tasks/ha-time-plan.md): drawn as a text
 * field (FieldBox), its text the time - «--:--» while there is none it can
 * read - and, tapped, Android's own time picker in 24 hours. It knows no
 * minute step, so OK rounds what it took onto the field's (decision 3) and
 * sends it as Home Assistant writes a time, `HH:MM:00`. The field shows it
 * until its topic answers.
 */
@Composable
fun TimeFieldView(
    obj: ScreenObject,
    project: Project,
    value: String,
    askedValue: String,
    assetFileOf: (String) -> java.io.File,
    onAction: (ButtonAction) -> Unit,
    onAsked: (readTopic: String, readValue: String) -> Unit,
) {
    val props = obj.properties
    val writeTopic = props.stringOrNull("writeTopic")?.takeIf { it.isNotEmpty() }
    val readTopic = props.stringOrNull("topic")?.takeIf { it.isNotEmpty() } ?: writeTopic
    val step = TimeField.step(props.doubleOrNull("minuteStep"))
    val shown = TimeField.parse(askedValue.ifEmpty { value })
    var picking by remember(obj.id) { mutableStateOf(false) }

    FieldBox(obj, project, TimeField.format(shown), assetFileOf, "time-field-${obj.id}", onTap = writeTopic?.let { { picking = true } })

    if (picking && writeTopic != null) {
        TimeEntryDialog(
            initial = TimeField.onStep(shown ?: TimeField.TimeOfDay(0, 0), step),
            onCancel = { picking = false },
            onPick = { picked ->
                picking = false
                val payload = TimeField.payload(TimeField.onStep(picked, step))
                // Not retained: a command, as a button's message.
                onAction(ButtonAction(type = "send-mqtt", mqttTopic = writeTopic, mqttMessage = payload))
                if (readTopic != null) onAsked(readTopic, payload)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeEntryDialog(initial: TimeField.TimeOfDay, onCancel: () -> Unit, onPick: (TimeField.TimeOfDay) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    Dialog(onDismissRequest = onCancel) {
        Surface(shape = RoundedCornerShape(28.dp), modifier = Modifier.testTag("time-field-entry")) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Select time", modifier = Modifier.padding(bottom = 20.dp))
                TimePicker(state = state)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel, modifier = Modifier.testTag("time-field-cancel")) { Text("Cancel") }
                    TextButton(
                        onClick = { onPick(TimeField.TimeOfDay(state.hour, state.minute)) },
                        modifier = Modifier.testTag("time-field-ok"),
                    ) { Text("OK") }
                }
            }
        }
    }
}
