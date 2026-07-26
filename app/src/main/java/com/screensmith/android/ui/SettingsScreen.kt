package com.screensmith.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.screensmith.android.mqtt.BrokerConfig

@Composable
fun SettingsScreen(
    initialConfig: BrokerConfig,
    onSave: (BrokerConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    var host by remember(initialConfig) { mutableStateOf(initialConfig.host) }
    var port by remember(initialConfig) { mutableStateOf(initialConfig.port.toString()) }
    var username by remember(initialConfig) { mutableStateOf(initialConfig.username) }
    var password by remember(initialConfig) { mutableStateOf(initialConfig.password) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "MQTT Broker", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Host") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter(Char::isDigit) },
            label = { Text("Port") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password (optional)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                onSave(
                    BrokerConfig(
                        host = host.trim(),
                        port = port.toIntOrNull() ?: 1883,
                        username = username,
                        password = password,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save & Connect")
        }
    }
}
