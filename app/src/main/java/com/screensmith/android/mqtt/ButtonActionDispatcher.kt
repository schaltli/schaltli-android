package com.screensmith.android.mqtt

import com.screensmith.android.data.ButtonAction
import com.screensmith.android.data.Project
import com.screensmith.android.data.ScreenObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Runs a [ButtonAction] the same way the firmware's own
 * `Application::dispatchButtonAction` (`Application.cpp:285`) does, minus
 * the hardware-button override-resolution step (screen-level
 * `buttonActions` overriding a device-wide `hardwareButtons[].defaultAction`)
 * - an android-platform device ships no physical buttons at all, so a
 * SoftwareButton's action comes straight from its own `properties.action`
 * (see [actionOf]), not from a buttonId lookup chain. Fires once per tap,
 * same as the firmware only firing on press.
 *
 * The one id-keyed lookup that does exist here is a swipe: `swipe-left`,
 * `swipe-right`, `swipe-up` and `swipe-down` are four firmware-invented
 * button ids the designer offers on every touch-capable device
 * (lib/device-description.ts), and a screen's own `buttonActions` map binds
 * them - so a swipe arrives as an ordinary [ButtonAction] and needs nothing
 * special once [com.screensmith.android.ui.swipeNavigation] has named it.
 */
class ButtonActionDispatcher(
    private val mqttRepository: MqttRepository,
    private val onNavigate: (screenId: String) -> Unit,
    // A "device-action" reaches whatever the platform itself provides rather
    // than anything in the project. This platform declares exactly one,
    // "showScreenMenu", matching the Waveshare's own DDF.
    private val onDeviceAction: (deviceActionId: String) -> Unit = {},
) {
    fun dispatch(action: ButtonAction, project: Project, currentScreenId: String) {
        when (action.type) {
            "next-screen", "previous-screen", "goto-screen" -> {
                val target = navigationTarget(action, project, currentScreenId) ?: return
                onNavigate(target)
            }
            "send-mqtt" -> {
                val topic = action.mqttTopic ?: return
                mqttRepository.publish(topic, action.mqttMessage ?: "")
            }
            "device-action" -> {
                val id = action.deviceActionId ?: return
                onDeviceAction(id)
            }
        }
    }

    companion object {
        /**
         * The screen this action would arrive at, or null if it is not a
         * navigation at all.
         *
         * Pulled out of [dispatch] so that the screen a swipe *slides
         * towards* and the screen it *lands on* cannot be worked out
         * differently. A follow-the-finger swipe has to draw the incoming
         * screen before it knows whether the gesture will be completed, and
         * a second copy of this arithmetic - the wrap-around in particular -
         * would eventually slide in one screen and land on another.
         */
        fun navigationTarget(action: ButtonAction, project: Project, currentScreenId: String): String? =
            when (action.type) {
                "next-screen", "previous-screen" -> {
                    val screens = project.screens
                    if (screens.isEmpty()) {
                        null
                    } else {
                        val currentIndex = screens.indexOfFirst { it.id == currentScreenId }
                        val delta = if (action.type == "next-screen") 1 else -1
                        val base = if (currentIndex == -1) 0 else currentIndex
                        screens[((base + delta) % screens.size + screens.size) % screens.size].id
                    }
                }
                "goto-screen" -> project.screens.find { it.id == action.targetScreenId }?.id
                else -> null
            }
    }
}

private val actionJson = Json { ignoreUnknownKeys = true }

/** Parses a SoftwareButton's `properties.action` (embedded JSON) into a [ButtonAction], or null if absent/malformed. */
fun ScreenObject.actionOf(): ButtonAction? {
    val actionElement = properties["action"] as? JsonObject ?: return null
    return try {
        actionJson.decodeFromJsonElement(ButtonAction.serializer(), actionElement)
    } catch (e: Exception) {
        null
    }
}
