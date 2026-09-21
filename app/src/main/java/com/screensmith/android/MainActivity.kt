package com.screensmith.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.screensmith.android.data.BrokerConfigStore
import com.screensmith.android.data.collectTopicNames
import com.screensmith.android.mqtt.BrokerConfig
import com.screensmith.android.mqtt.ButtonActionDispatcher
import com.screensmith.android.ddf.DdfBuilder
import com.screensmith.android.ddf.DdfServer
import com.screensmith.android.ddf.DeviceIdentity
import com.screensmith.android.mqtt.MqttRepository
import com.screensmith.android.ui.ImportScreen
import com.screensmith.android.ui.ScreenMenuOverlay
import com.screensmith.android.ui.ScreenRenderer
import com.screensmith.android.ui.SettingsScreen
import com.screensmith.android.ui.swipeNavigation
import com.screensmith.android.ui.theme.ScreensmithTheme
import kotlinx.coroutines.launch

/**
 * Kiosk mode: this is meant to run as an always-on wall display, not a
 * general-purpose app the user navigates away from - so it pins itself to
 * the foreground (Screen Pinning / Lock Task Mode, the standard non-device-
 * owner API - startLockTask() - rather than full Device Owner provisioning,
 * which would change much more about how the phone itself is managed and
 * isn't something to switch on for someone's everyday device without an
 * explicit, separate decision), keeps the screen on, and hides the system
 * bars. Screen Pinning still requires a one-time toggle in
 * Settings > Security > "Screen pinning" on stock Android (or the device
 * simply shows its own "pin this app?" confirmation the first time) - that
 * can't be flipped from app code without Device Owner privileges either.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        val app = application as ScreensmithApp
        setContent {
            ScreensmithTheme {
                ScreensmithRoot(app)
            }
        }

        startLockTask()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // A system surface (e.g. a permission prompt, or the one-time
        // Screen Pinning confirmation itself) taking focus un-hides the
        // bars - re-hide once this window has it back, the standard
        // pattern for sticky immersive mode.
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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
    // What a finger asked of a value: a settable level draws it as its marker
    // until the installation answers (the designer's
    // docs/2026-09-17-settable-level.md, decision 6c).
    val askedValues by mqttRepository.askedValues.collectAsStateWithLifecycle()

    var currentScreenId by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showScreenMenu by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    // A freshly-loaded (or just-imported) project always starts on its
    // first screen - only reset currentScreenId when the project identity
    // actually changes, not on every recomposition.
    LaunchedEffect(project) {
        currentScreenId = project?.screens?.firstOrNull()?.id
    }

    // This phone's own Device Description File: built from the screen it
    // actually has, served over HTTP, and pointed at from the retained MQTT
    // hello (docs/2026-09-21-android-self-announce.md in the designer repo).
    // Rebuilt whenever the room changes - a rotation, a fold opening - so
    // the announced hash and the served bytes cannot disagree.
    val configuration = LocalConfiguration.current
    val ddfServer = remember { DdfServer() }
    DisposableEffect(ddfServer) { onDispose { ddfServer.stop() } }

    LaunchedEffect(configuration.screenWidthDp, configuration.screenHeightDp) {
        val roboto = context.assets.open("Roboto.ttf").use { it.readBytes() }
        val ddf = DdfBuilder.build(
            deviceId = DeviceIdentity.deviceId(context),
            deviceName = DeviceIdentity.deviceName(context),
            widthDp = configuration.screenWidthDp,
            heightDp = configuration.screenHeightDp,
            robotoTtf = roboto,
        )
        ddfServer.serve(ddf.bytes)
        mqttRepository.setAnnouncement(
            MqttRepository.Announcement(
                deviceId = DeviceIdentity.deviceId(context),
                deviceName = DeviceIdentity.deviceName(context),
                appVersion = BuildConfig.VERSION_NAME,
                ddfHash = ddf.hash,
                url = ddfServer.url(),
            ),
        )
    }

    // (Re)connect whenever the loaded project or broker config changes -
    // covers a fresh import, a broker-settings change, and picks up the
    // right topic set for whichever project is currently loaded.
    //
    // A broker alone is enough: with no project there is nothing to
    // subscribe to, but the announcement still has to go out - a phone the
    // designer has never seen is exactly the one that has no project yet.
    LaunchedEffect(project, brokerConfig) {
        val activeProject = project
        android.util.Log.i("ScreensmithRoot", "LaunchedEffect fired: project=${activeProject?.name} brokerHost=${brokerConfig.host}")
        if (brokerConfig.host.isNotBlank()) {
            mqttRepository.connect(brokerConfig, activeProject?.collectTopicNames() ?: emptySet())
        } else {
            mqttRepository.disconnect()
        }
    }

    val dispatcher = remember(mqttRepository) {
        ButtonActionDispatcher(
            mqttRepository = mqttRepository,
            onNavigate = { screenId ->
                currentScreenId = screenId
                // A screen switch closes the menu that asked for it. Leaving
                // it up over the new screen would hide the very thing the
                // choice was about.
                showScreenMenu = false
            },
            onDeviceAction = { deviceActionId ->
                // The one device action this platform declares, matching the
                // Android DDF's own `deviceActions` list. An unknown id is
                // ignored rather than crashing: a project may have been built
                // against a device that offers more of them.
                if (deviceActionId == "showScreenMenu") showScreenMenu = true
            },
        )
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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // A swipe is looked up in this screen's own
                        // buttonActions and then dispatched like any other
                        // bound button - the app has no built-in idea that
                        // swiping left means "next". Which gestures do
                        // anything, and what, is the project's decision, made
                        // in the designer's Swipe Navigation panel.
                        .swipeNavigation { buttonId ->
                            screen.buttonActions[buttonId]
                                ?.let { dispatcher.dispatch(it, activeProject, screen.id) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    ScreenRenderer(
                        screen = screen,
                        project = activeProject,
                        topicValues = topicValues,
                        askedValues = askedValues,
                        assetFileOf = { path -> app.projectRepository.assetFile(path) },
                        onAction = { action -> dispatcher.dispatch(action, activeProject, screen.id) },
                        // A set level publishes its command and remembers what
                        // was asked, so the marker shows it at once instead of
                        // waiting for the installation's answer.
                        onSetLevel = { markerTopic, writeTopic, value ->
                            mqttRepository.noteAsked(markerTopic, value)
                            mqttRepository.publish(writeTopic, value)
                        },
                    )
                }
                if (showScreenMenu) {
                    ScreenMenuOverlay(
                        screens = activeProject.screens,
                        currentScreenId = screen.id,
                        onSelect = { screenId -> currentScreenId = screenId; showScreenMenu = false },
                        onDismiss = { showScreenMenu = false },
                    )
                }
            }
        }

        if (activeProject != null) {
            IconButton(
                onClick = { showSettings = !showSettings },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // enableEdgeToEdge() draws app content behind the status
                    // bar, so without this the icon's touch target sits
                    // entirely inside the status bar's own window and every
                    // tap is intercepted before it ever reaches the app
                    // (confirmed via `dumpsys window displays` - statusBars
                    // inset frame [0,0][*,113] fully contained the icon's
                    // [45,99] y-range; 2026-07-27 finding while wiring up a
                    // broker for the first time).
                    .statusBarsPadding()
                    .padding(8.dp),
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        }
    }
}
