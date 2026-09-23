package com.schaltli.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.schaltli.android.mqtt.BrokerConfig
import com.schaltli.android.mqtt.probeBroker
import com.schaltli.android.mqtt.readableReason
import kotlinx.coroutines.launch

/**
 * Setup: the one thing this device has to configure, which is where its
 * broker is. The phone brings its own network, so there is no access point
 * to raise and nothing else to ask for.
 *
 * Reached by holding a finger for five seconds ([SetupHold]) - the gesture
 * every Schaltli device offers - and left again by Cancel, which is what
 * someone who only wanted to look needs. Without it the way back was the
 * system's own Back key, which a pinned kiosk does not offer at all.
 *
 * It paints its own background rather than letting whatever is behind show
 * through. What is behind is the project's screen colour since 2026-09-22,
 * and on a dark project that put this dark-on-light form onto a black
 * window: black text in black fields.
 */
@Composable
fun SettingsScreen(
    initialConfig: BrokerConfig,
    onSave: (BrokerConfig) -> Unit,
    onCancel: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var host by remember(initialConfig) { mutableStateOf(initialConfig.host) }
    var port by remember(initialConfig) { mutableStateOf(initialConfig.port.toString()) }
    var username by remember(initialConfig) { mutableStateOf(initialConfig.username) }
    var password by remember(initialConfig) { mutableStateOf(initialConfig.password) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun entered() = BrokerConfig(
        host = host.trim(),
        port = port.toIntOrNull() ?: 1883,
        username = username,
        password = password,
    )

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "MQTT Broker", style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = host,
                onValueChange = { host = it; testResult = null },
                label = { Text("Host") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit); testResult = null },
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it; testResult = null },
                label = { Text("Username (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; testResult = null },
                label = { Text("Password (optional)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            // What the broker said, where the question was asked. A form
            // filled in blind is answered by whether a screen ever shows a
            // value, which is a long way round to find out about a typo.
            testResult?.let { message ->
                Text(text = message, style = MaterialTheme.typography.bodyMedium)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        testing = true
                        testResult = "Testing..."
                        scope.launch {
                            val config = entered()
                            val result = probeBroker(config)
                            testing = false
                            testResult = result.fold(
                                onSuccess = { "Connected to ${config.host}:${config.port}" },
                                onFailure = { "No connection: ${it.readableReason()}" },
                            )
                        }
                    },
                    enabled = !testing,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Test")
                }
                Button(
                    onClick = { onSave(entered()) },
                    modifier = Modifier.weight(2f),
                ) {
                    Text("Save & Connect")
                }
            }

            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }
    }
}
