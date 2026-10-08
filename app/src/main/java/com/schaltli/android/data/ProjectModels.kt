package com.schaltli.android.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

// Mirrors the shape lib/android-export.ts actually writes into project.json
// in the designer repo (schaltli-designer) - field names and
// nesting were taken directly from a real exported bundle, not re-derived
// from the TypeScript types by hand, so they should stay in lockstep as
// long as this comment is kept true when either side changes.

@Serializable
data class Project(
    val platform: String = "android",
    val name: String,
    val screenWidth: Int,
    val screenHeight: Int,
    /**
     * How the device is meant to be mounted: 0, 90, 180 or 270 degrees from
     * its own native orientation, chosen in the designer and limited by what
     * the DDF's `allowedRotations` permits.
     *
     * [screenWidth]/[screenHeight] are already the post-rotation numbers, and
     * cannot stand in for this: a quarter turn swaps them, a half turn does
     * not, so 0 and 180 are the same pair. Absent in bundles exported before
     * 2026-09-21, which were all native.
     */
    val rotation: Int = 0,
    /**
     * What the panel can hold, as the designer's `settings.colorDepth` names
     * it: "24bit", "4bit" or "1bit".
     *
     * `lib/android-export.ts` writes no such field - it hard-codes 24 bit,
     * because a phone is a phone - so in practice this is always the default.
     * It is read rather than assumed all the same, because one rule depends on
     * it and has to be stated where it is read: anti-aliasing is for 24 bit
     * only. On a panel with two colours there is nothing to mix into, every
     * soft pixel snaps to black or white on the way to the glass, and the
     * firmware draws these shapes with whole pixels - so a soft edge here would
     * disagree with every other copy at once (the designer's
     * docs/2026-09-22-pill-raster.md, "Wo nicht geglättet wird").
     */
    val colorDepth: String = "24bit",
    /**
     * How a placeholder's F and N formats write a number (the designer's
     * docs/device-contract.md §2.4): one character each, the thousands one
     * may be empty. A bundle exported before 2026-10-05 has neither and gets
     * Switzerland's, the designer's default.
     */
    val decimalSeparator: String = ".",
    val thousandsSeparator: String = "'",
    val fonts: List<FontEntry> = emptyList(),
    val topics: List<Topic> = emptyList(),
    val screens: List<Screen> = emptyList(),
    /**
     * Screens opened over the current one by an "open-popup" action, apart
     * from [screens] so that nothing paging through those ever meets one (the
     * designer's docs/device-contract.md §2.5). Absent before 2026-10-06.
     */
    val popups: List<Screen> = emptyList(),
    /** Where every popup's window is; null when the project has no popups. */
    val popupFence: PopupFence? = null,
    val exportedAt: String? = null,
    val version: String? = null,
    /**
     * Yes/no values the app works out itself from other values (the
     * designer's docs/device-contract.md §2.6), written in evaluation order.
     * Read raw and parsed once into [combinedOrder]. Absent before 1.4.
     */
    val combinedTopics: JsonArray = JsonArray(emptyList()),
    /**
     * The navigators (the designer's docs/device-contract.md §2.7): a strip
     * along an edge with an entry per listed screen; a screen names the one
     * it shows ([Screen.navigatorId]). Absent before 1.5.
     */
    val navigators: List<Navigator> = emptyList(),
) {
    /** [combinedTopics] parsed, ordered again so a hand-edited file computes right; none where one is circular. */
    val combinedOrder: List<CombinedTopics.CombinedTopic> by lazy {
        CombinedTopics.evaluationOrder(CombinedTopics.parse(combinedTopics))
    }
}

@Serializable
data class FontEntry(
    val id: String,
    val displayName: String,
    val size: Int,
    // Present for real TTF fonts (assets/fonts/<family>.ttf inside the
    // bundle); absent for a BDF-format entry, which an android-platform
    // device shouldn't ship anyway - see lib/android-export.ts.
    val path: String? = null,
    // Text baseline position (obj.y + ascent, see TextBoxView) - matches
    // the designer's own getFontAscent()/render-text-box.ts baseline math
    // and the firmware's drawTextBox(), both already pixel-verified. Without
    // this, Compose's Text composable manages its own internal baseline
    // from font metrics it derives itself, which measurably drifted from
    // both (2026-07-27 HIL finding).
    val ascent: Int? = null,
    val descent: Int? = null,
    // Where a TTF's capitals reach above the baseline, as the browser
    // measured them when the font was added (the designer's
    // add-ttf-font-dialog.tsx). A level indicator's number is placed from
    // this and nothing else, so without it the text would sit a row or two off
    // what the designer draws - see fontMetricsOf in LevelShape.kt. Absent for
    // a DDF-declared font, which nothing ever measured; four fifths of the
    // size stands in, on both sides.
    val baselineOffset: Double? = null,
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
    // A popup's frame colours (§2.5): the theme's outline for its edge, the
    // scrim's colour. Absent on a screen.
    val borderColor: String? = null,
    val scrimColor: String? = null,
    // The navigator this screen shows (§2.7); null: none.
    val navigatorId: String? = null,
    // Out of next/previous paging, still a goto target, never the start (§2.7).
    val hidden: Boolean = false,
)

/** A navigator as exported (§2.7): entries of ordinary objects, relative to the entry. */
@Serializable
data class Navigator(
    val id: String,
    val edge: String = "left",
    val thickness: Int = 0,
    val entryLength: Int = 0,
    val backgroundColor: String? = null,
    val entries: List<NavigatorEntry> = emptyList(),
)

@Serializable
data class NavigatorEntry(
    val screenId: String,
    val normal: List<ScreenObject> = emptyList(),
    val active: List<ScreenObject> = emptyList(),
)

/**
 * A popup's window (the designer's docs/device-contract.md §2.5): centred,
 * 80 % of the display's area, a circle on a round display given by its
 * bounding square. Project units.
 */
@Serializable
data class PopupFence(
    val shape: String = "rect",
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    val isCircle: Boolean get() = shape == "circle"

    /** The same test as the designer's insideFence (lib/popup.ts) and the boards' PopupFence::contains. */
    fun contains(px: Double, py: Double): Boolean {
        if (isCircle) {
            val r = width / 2.0
            val dx = px - x - r
            val dy = py - y - r
            return dx * dx + dy * dy <= r * r
        }
        return px >= x && px < x + width && py >= y && py < y + height
    }

    /**
     * The round close button on the fence's edge (contract §2.5): its centre
     * on the top right corner, on a circle where the diagonal to the top
     * right meets the rim. The same numbers as the designer's popupCloseBadge
     * (lib/popup.ts) and the boards' popupCloseBadgeFor.
     */
    fun closeBadge(radius: Int): PopupCloseBadge {
        val hitRadius = radius * 8 / 5
        if (isCircle) {
            val r = width / 2.0
            val off = r * 0.70710678
            return PopupCloseBadge(Math.round(x + r + off).toInt(), Math.round(y + r - off).toInt(), radius, hitRadius)
        }
        return PopupCloseBadge(x + width, y, radius, hitRadius)
    }
}

/** The close button the app draws on an open popup, in project units. */
data class PopupCloseBadge(val cx: Int, val cy: Int, val radius: Int, val hitRadius: Int) {
    fun contains(px: Double, py: Double): Boolean {
        val dx = px - cx
        val dy = py - cy
        return dx * dx + dy * dy <= hitRadius.toDouble() * hitRadius
    }
}

/**
 * The close button's radius in dp: 36 dp across, about 5.7 mm. The DDF says
 * it too (screen.popupCloseRadius), so the designer shows it where the app
 * draws it.
 */
const val POPUP_CLOSE_RADIUS = 18

@Serializable
data class ButtonAction(
    // "next-screen" | "previous-screen" | "goto-screen" | "send-mqtt" | "device-action"
    // | "open-popup" (targetScreenId: a popup's id) | "close-popup"
    val type: String,
    val targetScreenId: String? = null,
    val mqttTopic: String? = null,
    val mqttMessage: String? = null,
    // Only for type "device-action": which of the device's own capabilities to
    // invoke, matched against the DDF's `deviceActions` list. The only one
    // this platform declares is "showScreenMenu".
    val deviceActionId: String? = null,
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
    // Only meaningful on "icon" objects and live-icon's valueIconPairs
    // entries - path to the icon's SVG inside the bundle. On a "button" it is
    // something else: the whole button, baked as a bitmap by the designer
    // (lib/android-export.ts), because a Material button's pill, colours,
    // trimmed icon and measured label are rules that repo owns and a second
    // drawing of them here would be a second set of pixels to keep in step.
    val path: String? = null,
    /** A "button" held down - the other of its two baked states. */
    val pressedPath: String? = null,
)
