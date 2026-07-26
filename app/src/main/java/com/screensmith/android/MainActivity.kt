package com.screensmith.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.screensmith.android.data.BrokerConfigStore
import com.screensmith.android.data.collectTopicNames
import com.screensmith.android.mqtt.BrokerConfig
import com.screensmith.android.mqtt.ButtonActionDispatcher
import com.screensmith.android.mqtt.MqttRepository
import com.screensmith.android.ui.ImportScreen
import com.screensmith.android.ui.ScreenRenderer
import com.screensmith.android.ui.SettingsScreen
import com.screensmith.android.ui.theme.ScreensmithTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as ScreensmithApp
        setContent {
            ScreensmithTheme {
                ScreensmithRoot(app)
            }
        }
    }
}

/**
 * Root screen: import a bundle if none is loaded yet, otherwise render the
 * current screen live over MQTT. A single [currentScreenId] is the one
 * source of truth for navigation (mirrors the firmware's
 * `currentScreenIndex_`), updated by [ButtonActionDispatcher] before
 * anything re-renders on top of it.
 */
@Composable
fun ScreensmithRoot(app: ScreensmithApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val project by app.projectRepository.project.collectAsStateWithLifecycle()
    val brokerConfigStore = remember { BrokerConfigStore(context) }
    val brokerConfig by brokerConfigStore.config.collectAsStateWithLifecycle(initialValue = BrokerConfig(host = ""))
    val mqttRepository = remember { MqttRepository() }
    val topicValues by mqttRepository.topicValues.collectAsStateWithLifecycle()

    var currentScreenId by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    // A freshly-loaded (or just-imported) project always starts on its
    // first screen - only reset currentScreenId when the project identity
    // actually changes, not on every recomposition.
    LaunchedEffect(project) {
        currentScreenId = project?.screens?.firstOrNull()?.id
    }

    // (Re)connect whenever the loaded project or broker config changes -
    // covers a fresh import, a broker-settings change, and picks up the
    // right topic set for whichever project is currently loaded.
    LaunchedEffect(project, brokerConfig) {
        val activeProject = project
        if (activeProject != null && brokerConfig.host.isNotBlank()) {
            mqttRepository.connect(brokerConfig, activeProject.collectTopicNames())
        } else {
            mqttRepository.disconnect()
        }
    }

    val dispatcher = remember(mqttRepository) {
        ButtonActionDispatcher(mqttRepository) { screenId -> currentScreenId = screenId }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val activeProject = project
        when {
            activeProject == null -> ImportScreen(
                errorMessage = importError,
                onBundleSelected = { uri ->
                    scope.launch {
                        app.projectRepository.importBundle(uri)
                            .onSuccess { importError = null }
                            .onFailure { importError = it.message ?: "Import failed" }
                    }
                },
            )
            showSettings -> SettingsScreen(
                initialConfig = brokerConfig,
                onSave = { newConfig ->
                    scope.launch {
                        brokerConfigStore.save(newConfig)
                        showSettings = false
                    }
                },
            )
            else -> {
                val screen = activeProject.screens.find { it.id == currentScreenId } ?: activeProject.screens.first()
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ScreenRenderer(
                        screen = screen,
                        project = activeProject,
                        topicValues = topicValues,
                        assetFileOf = { path -> app.projectRepository.assetFile(path) },
                        onAction = { action -> dispatcher.dispatch(action, activeProject, screen.id) },
                    )
                }
            }
        }

        if (activeProject != null) {
            IconButton(
                onClick = { showSettings = !showSettings },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        }
    }
}
