package com.schaltli.android.render

import com.schaltli.android.data.FontEntry
import com.schaltli.android.data.ScreenObject
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds this repo's copy of the Switch's rules to the numbers the designer's
 * copy produces.
 *
 * The companion of [LevelShapeGoldenTest], for the same reason: the rules
 * exist twice over (`lib/switch-shape.ts` there, [SwitchShape.kt] here). A
 * HIL run notices a disagreement as a percentage of differing pixels, with a
 * phone connected; this names the number in milliseconds, on any machine.
 *
 * The colours are checked as closely as the rectangles, because on this
 * control they ARE a rule: everything is derived from one colour the author
 * sets and from what the control stands on, so a port that blends the wrong
 * way draws the right shapes in the wrong colours - which no geometry check
 * can see.
 *
 * Regenerate after any change to either side:
 *
 *   (designer) npm run dev
 *   (designer) node hil/android/fixtures/build-switch-golden.js
 */
class SwitchShapeGoldenTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val noFonts = JsonArray(emptyList())

    private fun loadGolden(): JsonObject {
        val stream = javaClass.classLoader!!.getResourceAsStream("switch-shape-golden.json")
            ?: error("switch-shape-golden.json is missing - regenerate it with build-switch-golden.js")
        return json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    @Test
    fun `every recorded case comes out the same shape and the same colours`() {
        val cases = loadGolden()["cases"]!!.jsonObject
        assertTrue("the golden file records no cases at all", cases.isNotEmpty())

        for ((name, entry) in cases) {
            val case = entry.jsonObject
            val input = case["input"]!!.jsonObject
            val want = case["shape"]!!.jsonObject

            // This platform's screens are 24 bit, so the designer's colour
            // quantiser is the identity and SwitchShape.kt has no copy of it.
            // A case recorded at another depth would be comparing against
            // quantised colours this port never applies.
            assertEquals(
                "$name: recorded at a colour depth this port does not implement",
                "24bit",
                input["colorDepth"]?.jsonPrimitive?.content ?: "24bit",
            )

            val obj = ScreenObject(
                id = "probe",
                type = input.str("type"),
                x = input.num("x"),
                y = input.num("y"),
                width = input.num("width"),
                height = input.num("height"),
                properties = input["properties"]!!.jsonObject,
            )
            val fonts: List<FontEntry> =
                json.decodeFromJsonElement(ListSerializer(FontEntry.serializer()), input["fonts"] ?: noFonts)
            val count = maxOf(1, input.int("stateCount"))
            val background = input.str("background")
            val activeIndex = input.int("activeIndex")
            val askedIndex = input.int("askedIndex")

            assertEquals("$name: form", want.str("form"), switchForm(obj).wire)
            assertEquals("$name: corner", want.int("corner"), switchCorner(obj.width.toInt(), obj.height.toInt()))

            val metrics = switchFontMetrics(obj, fonts)
            val wantMetrics = want["metrics"]!!.jsonObject
            assertEquals("$name: ascent", wantMetrics.int("ascent"), metrics.ascent)
            assertEquals("$name: descent", wantMetrics.int("descent"), metrics.descent)
            assertEquals("$name: capHeight", wantMetrics.int("capHeight"), metrics.capHeight)

            assertRect("$name: container", want["container"], switchContainer(obj))
            assertRect("$name: track", want["track"], switchTrack(obj, count))
            assertRect("$name: labelBox", want["labelBox"], switchLabelBox(obj, count))

            val segments = switchSegments(obj, count)
            val wantSegments = want["segments"]!!.jsonArray
            assertEquals("$name: number of segments", wantSegments.size, segments.size)
            for (i in segments.indices) assertRect("$name: segment $i", wantSegments[i], segments[i])

            // What a ring on each button would enclose. A ring is its outer
            // pill with the inside taken back, so the colour under it is
            // decided rather than inherited - and only a button that is at once
            // the reported state AND the one a finger asked for tells the right
            // reading (its own colour) from the wrong one (the container's).
            want["ringFills"]?.jsonArray?.let { wantFills ->
                val look = switchLook(obj, background)
                assertEquals("$name: number of ring fills", wantFills.size, count)
                for (i in 0 until count) {
                    assertEquals(
                        "$name: what a ring on button $i encloses",
                        (wantFills[i] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
                        switchRingFill(look, i == activeIndex),
                    )
                }
            }

            // The knob has three sizes since 2026-09-22, so its state has to be
            // known before any of them can be asked for.
            val states = (obj.properties["states"] as? JsonArray).orEmpty()
            val on = activeIndex >= 0 && activeIndex < states.size && switchStateIsOn(states[activeIndex].jsonObject)
            assertEquals("$name: on", want.bool("on"), on)

            // Every slot the knob can stand in, not only the one in use: a
            // port that gets the step wrong is right about the first slot.
            val wantKnobs = want["knobs"]!!.jsonArray
            assertEquals("$name: number of slots", wantKnobs.size, count)
            for (i in 0 until count) {
                assertKnob("$name: knob $i", wantKnobs[i].jsonObject, switchKnob(obj, count, i, on = on))
            }

            // And the quiet size, which the recorder writes for every case
            // whether it is on or not. Without this a port that knows only one
            // resting size passes every case that happens to be on.
            want["quietKnobs"]?.jsonArray?.let { wantQuiet ->
                for (i in 0 until minOf(count, wantQuiet.size)) {
                    assertKnob(
                        "$name: quiet knob $i",
                        wantQuiet[i].jsonObject,
                        switchKnob(obj, count, i, on = false),
                    )
                }
            }

            val shown = if (askedIndex >= 0) askedIndex else activeIndex
            assertEquals("$name: shownIndex", want.int("shownIndex"), shown)
            assertKnob(
                "$name: pressed knob",
                want["pressedKnob"]!!.jsonObject,
                switchKnob(obj, count, maxOf(0, shown), pressed = true, on = on),
            )

            // The content is laid out in whichever box the form puts it in -
            // the label box for a knob, the first button for a group - and
            // the text width is an input, so what is compared is the layout
            // rule rather than two font engines.
            val box = if (switchForm(obj) == SwitchForm.KNOB) switchLabelBox(obj, count) else segments[0]
            val content = switchContent(box, metrics, input.bool("hasIcon"), input.int("textWidth"))
            val wantContent = want["content"]!!.jsonObject
            assertRect("$name: content.icon", wantContent["icon"], content.icon)
            assertEquals("$name: content.textX", wantContent.int("textX"), content.textX)
            assertEquals("$name: content.baseline", wantContent.int("baseline"), content.baseline)
            assertEquals("$name: content.beside", wantContent.bool("beside"), content.beside)

            val look = switchLook(obj, background)
            val wantLook = want["look"]!!.jsonObject
            assertEquals("$name: surface", wantLook.str("surface"), look.surface)
            assertEquals("$name: surfaceOutline", wantLook.strOrNull("surfaceOutline"), look.surfaceOutline)
            assertEquals("$name: chosen", wantLook.str("chosen"), look.chosen)
            assertEquals("$name: onChosen", wantLook.str("onChosen"), look.onChosen)
            assertEquals("$name: onSurface", wantLook.str("onSurface"), look.onSurface)
            assertEquals("$name: ring", wantLook.str("ring"), look.ring)

            assertKnobLook("$name: knobLook", want["knobLook"]!!.jsonObject, switchKnobLook(obj, background, on))
            assertKnobLook("$name: knobLook off", want["knobLookOff"]!!.jsonObject, switchKnobLook(obj, background, false))
        }
    }

    /**
     * A finger has to select the same state here as in the designer and in
     * the firmware, or a tap on this app switches something else than the
     * same tap on a panel. Read off the recorded segments: a point inside a
     * button is that button, whichever way the arithmetic gets there.
     */
    @Test
    fun `a point on a button group selects the button under it`() {
        val cases = loadGolden()["cases"]!!.jsonObject
        for ((name, entry) in cases) {
            val case = entry.jsonObject
            val input = case["input"]!!.jsonObject
            if (input.str("type") == "switch") continue
            val obj = ScreenObject(
                id = "probe",
                type = input.str("type"),
                x = input.num("x"),
                y = input.num("y"),
                width = input.num("width"),
                height = input.num("height"),
                properties = input["properties"]!!.jsonObject,
            )
            val count = maxOf(1, input.int("stateCount"))
            val segments = switchSegments(obj, count)
            for (i in segments.indices) {
                val seg = segments[i]
                val middle = seg.x + seg.w / 2.0
                assertEquals("$name: the middle of button $i", i, switchSegmentAt(obj, count, middle))
            }
            // Past the right-hand end is the last button, not nothing: a
            // finger in the container's own padding still means something.
            assertEquals(
                "$name: past the last button",
                segments.size - 1,
                switchSegmentAt(obj, count, (obj.x + obj.width + 10)),
            )
        }
    }

    private fun assertRect(what: String, expected: JsonElement?, actual: SwitchRect?) {
        if (expected == null || expected is JsonNull) {
            assertNull("$what: expected nothing here", actual)
            return
        }
        val want = expected.jsonObject
        val got = checkNotNull(actual) { "$what: expected a rectangle, got nothing" }
        assertEquals("$what.x", want.int("x"), got.x)
        assertEquals("$what.y", want.int("y"), got.y)
        assertEquals("$what.w", want.int("w"), got.w)
        assertEquals("$what.h", want.int("h"), got.h)
        assertEquals("$what.r", want.int("r"), got.r)
        // Absent on the designer's side means "both ends alike", which is
        // what a null means here.
        assertEquals("$what.rRight", want.intOrNull("rRight") ?: want.int("r"), got.rRight ?: got.r)
    }

    private fun assertKnob(what: String, want: JsonObject, got: SwitchKnob) {
        assertEquals("$what.cx", want.int("cx"), got.cx)
        assertEquals("$what.cy", want.int("cy"), got.cy)
        assertEquals("$what.r", want.int("r"), got.r)
    }

    private fun assertKnobLook(what: String, want: JsonObject, got: SwitchKnobLook) {
        assertEquals("$what.track", want.str("track"), got.track)
        assertEquals("$what.trackOutline", want.strOrNull("trackOutline"), got.trackOutline)
        assertEquals("$what.knob", want.str("knob"), got.knob)
        assertEquals("$what.onKnob", want.str("onKnob"), got.onKnob)
    }

    private fun JsonObject.int(key: String): Int = (this[key] as JsonPrimitive).int
    private fun JsonObject.intOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.int
    private fun JsonObject.num(key: String): Double = (this[key] as JsonPrimitive).double
    private fun JsonObject.str(key: String): String = (this[key] as JsonPrimitive).content
    private fun JsonObject.strOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonObject.bool(key: String): Boolean = (this[key] as JsonPrimitive).boolean
}
