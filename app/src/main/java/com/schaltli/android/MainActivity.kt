package com.schaltli.android

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.app.Activity
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schaltli.android.data.BrokerConfigStore
import com.schaltli.android.data.collectTopicNames
import com.schaltli.android.mqtt.BrokerConfig
import com.schaltli.android.mqtt.ButtonActionDispatcher
import com.schaltli.android.ddf.DdfBuilder
import com.schaltli.android.ddf.DdfServer
import com.schaltli.android.ddf.DeviceIdentity
import com.schaltli.android.ddf.NativeScreen
import com.schaltli.android.ui.SetupHold
import com.schaltli.android.ui.objects.parseHexColor
import com.schaltli.android.mqtt.DeployReceiver
import com.schaltli.android.mqtt.MqttRepository
import com.schaltli.android.ui.ImportScreen
import com.schaltli.android.ui.LocalBundleInstallation
import com.schaltli.android.ui.ScreenMenuOverlay
import com.schaltli.android.ui.ScreenRenderer
import com.schaltli.android.ui.SettingsScreen
import com.schaltli.android.ui.FollowingScreens
import com.schaltli.android.ui.theme.SchaltliTheme
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

        val app = application as SchaltliApp
        setContent {
            SchaltliTheme {
                // Which installation of the bundle everything below is
                // drawing from. Provided here rather than passed down
                // because the composables that need it are leaves - a
                // typeface cache inside a button inside a tab-control - and
                // nothing in between has any business carrying it.
                val installation by app.projectRepository.installation
                    .collectAsStateWithLifecycle()
                CompositionLocalProvider(LocalBundleInstallation provides installation) {
                    SchaltliRoot(app)
                }
            }
        }

        // Not in a debug build. Without Device Owner provisioning Android puts
        // its own "Screen pinned" confirmation on top of the app, and that
        // dialog takes the focus: it swallows every gesture sent to the app,
        // and with the app no longer in front the screen stops being held
        // awake and the phone locks itself. On a wall display that is one tap
        // by the person who installed it; on a development phone it stands
        // between every build and every automated gesture - it blocked the
        // Android HIL suite after each install on 2026-09-21.
        //
        // The kiosk behaviour is a property of the deployed panel, so it
        // belongs in the build that gets deployed.
        if (!BuildConfig.DEBUG) startLockTask()
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
fun SchaltliRoot(app: SchaltliApp) {
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

    // Which way up this is, decided by the project and not by the way the
    // phone happens to be lying. Without this the activity follows the
    // sensor: a project drawn for 360x679 was turned on its side the moment
    // the phone was tipped over, and drawn into a screen of the other shape.
    //
    // A panel is mounted, not held, so there is nothing to follow. Every
    // device works this way - the project carries a rotation, the DDF's
    // `allowedRotations` says which ones this device may be mounted in, and a
    // board applies it to its own panel. This is that, for a device whose
    // panel is turned by the operating system instead.
    //
    // Read from the project's rotation rather than from whether it is wider
    // than it is tall, because a half turn leaves those numbers alone.
    val activity = context as? android.app.Activity
    LaunchedEffect(activity, project?.rotation) {
        activity?.requestedOrientation = when (project?.rotation ?: 0) {
            90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    var currentScreenId by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showScreenMenu by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    // A freshly-loaded (or just-installed) project always starts on its first
    // screen.
    //
    // Keyed on the installation as well as the project, because the project
    // alone cannot say that one happened: a StateFlow drops a value equal to
    // the one it holds, so installing a bundle whose project.json is
    // unchanged emits nothing and this never ran. Installing the same
    // project again then left whatever screen had been swiped to on the
    // glass, which is not what "install this project" means - found by the
    // HIL suite on 2026-09-21, which installs the fixture to get back to a
    // known screen and was measured against the wrong one for its trouble.
    LaunchedEffect(project, LocalBundleInstallation.current) {
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

    // The display's own position, which is not the same question as the
    // configuration's size: the size says what there is room for right now,
    // this says whether "right now" is the way up the device was built.
    val displayRotation = (
        context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
        ).defaultDisplay.rotation

    LaunchedEffect(configuration.screenWidthDp, configuration.screenHeightDp, displayRotation) {
        val roboto = context.assets.open("Roboto.ttf").use { it.readBytes() }
        // Announced in the device's native orientation, always. A project's
        // rotation is applied on top of this by whoever reads it, so
        // announcing a screen already turned would have it turned twice.
        val native = NativeScreen.resolve(
            context = context,
            widthDp = configuration.screenWidthDp,
            heightDp = configuration.screenHeightDp,
            rotation = displayRotation,
        )
        val ddf = DdfBuilder.build(
            deviceId = DeviceIdentity.deviceId(context),
            deviceName = DeviceIdentity.deviceName(context),
            widthDp = native.widthDp,
            heightDp = native.heightDp,
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

    // A project pushed from the designer, rather than picked as a file: it
    // lands in the repository the same way, so the screen simply becomes the
    // new one (docs/2026-09-21-android-self-announce.md).
    val deployReceiver = remember(mqttRepository) {
        DeployReceiver(
            projectRepository = app.projectRepository,
            publishStatus = { mqttRepository.publishDeployStatus(it) },
            scope = scope,
        )
    }
    LaunchedEffect(deployReceiver) {
        mqttRepository.onDeploy = { payload -> deployReceiver.onDeploy(payload) }
    }

    // Connect when the broker changes, and only then.
    //
    // A broker alone is enough: with no project there is nothing to
    // subscribe to, but the announcement still has to go out - a phone the
    // designer has never seen is exactly the one that has no project yet.
    //
    // The project used to be a key here too, so every install reconnected.
    // That is how a deploy came to stall at whatever percentage it had
    // reached: each reconnection left the one before it retrying under this
    // phone's own client identifier, the two took the connection from each
    // other about once a second, and the progress the app was publishing went
    // out on whichever one was dying. What a new project actually needs is a
    // different set of subscriptions, which is the effect below.
    LaunchedEffect(brokerConfig) {
        android.util.Log.i("SchaltliRoot", "broker changed: host=${brokerConfig.host}")
        if (brokerConfig.host.isNotBlank()) {
            mqttRepository.connect(brokerConfig, project?.collectTopicNames() ?: emptySet())
        } else {
            mqttRepository.disconnect()
        }
    }

    LaunchedEffect(project) {
        mqttRepository.setTopics(project?.collectTopicNames() ?: emptySet())
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
                // Someone who only wanted to look has to be able to leave.
                // The system's Back key is not that way out: a pinned kiosk
                // does not offer one.
                onCancel = { showSettings = false },
            )
            else -> {
                val screen = activeProject.screens.find { it.id == currentScreenId } ?: activeProject.screens.first()
                // The window under everything takes the screen's own colour.
                //
                // A phone is taller than the area it gives an app: the
                // hidden status bar and the gesture bar leave 43 and 25
                // units of window that no project covers, and they were
                // showing the app's own light theme - two white stripes
                // around a dark design (measured on the P20, 2026-09-22).
                // Nothing about the drawn screen changes, so the DDF still
                // announces the same size and no project moves; only what
                // surrounds it does.
                val activity = LocalContext.current as? Activity
                val surround = screen.backgroundColor?.let(::parseHexColor) ?: Color.White
                LaunchedEffect(activity, surround) {
                    activity?.window?.setBackgroundDrawable(ColorDrawable(surround.toArgb()))
                }
                // A swipe is looked up in this screen's own buttonActions and
                // then dispatched like any other bound button - the app has
                // no built-in idea that swiping left means "next". Which
                // gestures do anything, and what, is the project's decision,
                // made in the designer's Swipe Navigation panel.
                //
                // Where the binding does lead to another screen, the picture
                // travels under the finger while the gesture is being made,
                // and the same dispatch happens when it is let go.
                SetupHold(enabled = true, onEnterSetup = { showSettings = true }) {
                FollowingScreens(
                    screen = screen,
                    followTargetFor = { buttonId ->
                        screen.buttonActions[buttonId]
                            ?.let { ButtonActionDispatcher.navigationTarget(it, activeProject, screen.id) }
                            ?.let { targetId -> activeProject.screens.find { it.id == targetId } }
                    },
                    onSwipe = { buttonId ->
                        screen.buttonActions[buttonId]
                            ?.let { dispatcher.dispatch(it, activeProject, screen.id) }
                    },
                    modifier = Modifier.fillMaxSize(),
                ) { drawn ->
                    ScreenRenderer(
                        screen = drawn,
                        project = activeProject,
                        topicValues = topicValues,
                        askedValues = askedValues,
                        assetFileOf = { path -> app.projectRepository.assetFile(path) },
                        onAction = { action -> dispatcher.dispatch(action, activeProject, drawn.id) },
                        // A set level publishes its command and remembers what
                        // was asked, so the marker shows it at once instead of
                        // waiting for the installation's answer.
                        onSetLevel = { markerTopic, writeTopic, value ->
                            mqttRepository.noteAsked(markerTopic, value)
                            mqttRepository.publish(writeTopic, value)
                        },
                        // A Switch publishes through the action dispatcher
                        // like a button does, so only the remembering is
                        // left for it here.
                        onAsked = { readTopic, readValue ->
                            mqttRepository.noteAsked(readTopic, readValue)
                        },
                    )
                }
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

    }
}
