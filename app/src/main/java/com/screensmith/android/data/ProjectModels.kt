package com.screensmith.android.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Mirrors the shape lib/android-export.ts actually writes into project.json
// in the designer repo (v0-screenman-editor-design) - field names and
// nesting were taken directly from a real exported bundle, not re-derived
// from the TypeScript types by hand, so they should stay in lockstep as
// long as this comment is kept true when either side changes.

@Serializable
data class Project(
    val platform: String = "android",
    val name: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val fonts: List<FontEntry> = emptyList(),
    val topics: List<Topic> = emptyList(),
    val screens: List<Screen> = emptyList(),
    val exportedAt: String? = null,
    val version: String? = null,
)

@Serializable
data class FontEntry(
    val id: String,
    val displayName: String,
    val size: Int,
    // Present for real TTF fonts (assets/fonts/<family>.ttf inside the
    // bundle); absent for a BDF-format entry, which an android-platform
    // device shouldn't ship anyway - see lib/android-export.ts.
    val path: String? = null,
)

@Serializable
data class Topic(
    val id: String,
    val topic: String,
    val type: String = "text", // "numeric" | "text" | "json"
    val examples: List<String> = emptyList(),
    val subtopics: List<JsonSubtopic> = emptyList(),
)

@Serializable
data class JsonSubtopic(
    val id: String,
    val path: String,
    val type: String = "text", // the type of the VALUE once extracted
    val label: String? = null,
)

@Serializable
data class Screen(
    val id: String,
    val name: String,
    val backgroundColor: String? = null,
    // Path (inside the bundle) of the flattened background PNG - already
    // has this screen's static box/line/icon objects baked in, see
    // ScreenRenderer (M4): only the *dynamic* entries in `objects` below
    // get drawn again on top of it.
    val backgroundImage: String? = null,
    val buttonActions: Map<String, ButtonAction> = emptyMap(),
    val objects: List<ScreenObject> = emptyList(),
)

@Serializable
data class ButtonAction(
    val type: String, // "next-screen" | "previous-screen" | "goto-screen" | "send-mqtt"
    val targetScreenId: String? = null,
    val mqttTopic: String? = null,
    val mqttMessage: String? = null,
)

// `properties` is intentionally a loose JsonObject, not a sealed hierarchy
// of per-type classes - the designer's own ScreenmanObject.properties is
// `Record<string, any>` for the same reason (wildly different shapes per
// object type, and the export doesn't validate/narrow it either). Per-type
// composables in M4 pull out exactly the keys they need.
@Serializable
data class ScreenObject(
    val id: String,
    val type: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    @SerialName("zIndex") val zIndex: Int = 0,
    val properties: JsonObject = JsonObject(emptyMap()),
    // Only tab-control/panel objects have children (panels, and each
    // panel's own contained objects respectively) - recursive, same as the
    // designer's ScreenmanObject.children.
    val children: List<ScreenObject> = emptyList(),
    // Only meaningful on "icon" objects and MQTTIconField's valueIconPairs
    // entries - path to the icon's SVG inside the bundle.
    val path: String? = null,
)
