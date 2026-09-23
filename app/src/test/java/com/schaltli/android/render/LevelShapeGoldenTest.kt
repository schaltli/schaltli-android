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
 * Holds this repo's copy of the level indicator's rules to the numbers the
 * designer's copy produces.
 *
 * The rules exist twice over - `lib/level-shape.ts` in the designer and
 * [LevelShape.kt] here - and two copies of a rule are two chances to
 * disagree. A HIL run does notice: it is a percentage of differing pixels on
 * the phone's own screen. But only once a phone is connected, a bundle
 * installed and a gesture sent, and then only as a percentage. This says
 * which number is wrong, in milliseconds, on any machine.
 *
 * The golden file is recorded from the designer itself, through the real
 * functions in a browser. So this is not two readings of a specification
 * agreeing with each other; it is this implementation agreeing with the one
 * the reference image is actually drawn by.
 *
 * Regenerate after any change to either side:
 *
 *   (designer) npm run dev
 *   (designer) node hil/android/fixtures/build-level-golden.js
 */
class LevelShapeGoldenTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val noFonts = JsonArray(emptyList())

    /** The object a recorded case describes. */
    private fun objectOf(input: JsonObject) = ScreenObject(
        id = "probe",
        type = input.str("type"),
        x = input.num("x"),
        y = input.num("y"),
        width = input.num("width"),
        height = input.num("height"),
        properties = input["properties"]!!.jsonObject,
    )

    /** Its fonts, as the app's own project.json carries them. */
    private fun fontsOf(input: JsonObject): List<FontEntry> =
        json.decodeFromJsonElement(ListSerializer(FontEntry.serializer()), input["fonts"] ?: noFonts)

    private fun loadGolden(): JsonObject {
        val stream = javaClass.classLoader!!.getResourceAsStream("level-shape-golden.json")
            ?: error("level-shape-golden.json is missing - regenerate it with build-level-golden.js")
        val text = stream.bufferedReader().use { it.readText() }
        return json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun `every recorded case comes out the same shape`() {
        val cases = loadGolden()["cases"]!!.jsonObject
        assertTrue("the golden file records no cases at all", cases.isNotEmpty())

        for ((name, entry) in cases) {
            val case = entry.jsonObject
            val input = case["input"]!!.jsonObject
            val want = case["shape"]!!.jsonObject

            // This platform's screens are 24 bit, so the designer's colour
            // quantiser is the identity and LevelShape.kt has no copy of it.
            // A case recorded at another depth would be comparing against
            // quantised colours this port never applies - which would show up
            // as a wrong track colour and look like an arithmetic bug.
            assertEquals(
                "$name: recorded at a colour depth this port does not implement",
                "24bit",
                input["colorDepth"]?.jsonPrimitive?.content ?: "24bit",
            )

            val obj = objectOf(input)
            val fonts = fontsOf(input)

            val percent = input.num("percent")
            val setpoint = input.num("setpointPercent")

            assertEquals("$name: vertical", want.bool("vertical"), levelIsVertical(obj))
            assertEquals("$name: fillsFromEnd", want.bool("fillsFromEnd"), levelFillsFromEnd(obj))
            assertEquals("$name: thickness", want.int("thickness"), levelThickness(obj))
            assertEquals("$name: hasHandle", want.bool("hasHandle"), levelHasHandle(obj))
            assertEquals("$name: showsNumber", want.bool("showsNumber"), levelShowsNumber(obj))
            assertEquals("$name: headerHeight", want.int("headerHeight"), levelHeaderHeight(obj, fonts))
            assertEquals("$name: valueWidth", want.int("valueWidth"), levelValueWidth(obj, fonts))

            val metrics = levelFontMetrics(obj, fonts)
            val wantMetrics = want["metrics"]!!.jsonObject
            assertEquals("$name: ascent", wantMetrics.int("ascent"), metrics.ascent)
            assertEquals("$name: descent", wantMetrics.int("descent"), metrics.descent)
            assertEquals("$name: capHeight", wantMetrics.int("capHeight"), metrics.capHeight)

            val layout = levelLayout(obj, fonts)
            val wantLayout = want["layout"]!!.jsonObject
            assertRect("$name: layout.header", wantLayout["header"], layout.header)
            assertEquals("$name: layout.baseline", wantLayout.int("baseline"), layout.baseline)
            assertRect("$name: layout.icon", wantLayout["icon"], layout.icon)
            assertRect("$name: layout.text", wantLayout["text"], layout.text)
            assertRect("$name: layout.value", wantLayout["value"], layout.value)
            assertRect("$name: layout.bar", wantLayout["bar"], layout.bar)
            assertRect("$name: layout.slot", wantLayout["slot"], layout.slot)
            assertRect("$name: layout.track", wantLayout["track"], layout.track)
            assertRect("$name: track", want["track"], levelTrackRect(obj, fonts))

            assertSegment("$name: emptyTrack", want["emptyTrack"]!!.jsonObject, levelEmptyTrack(obj, fonts))

            // The handle is part of the segment arithmetic, not an
            // afterthought: a track with one is split around it, and the
            // designer's own hook computes it the same way round - the
            // commanded value where there is one, the measured value
            // otherwise.
            val handle = if (levelHasHandle(obj)) {
                levelHandleRect(obj, if (setpoint >= 0) setpoint else percent, fonts)
            } else {
                null
            }
            assertRect("$name: handle", want["handle"], handle)

            val segments = levelSegments(obj, percent, handle, fonts)
            val wantSegments = want["segments"]!!.jsonArray
            assertEquals("$name: number of segments", wantSegments.size, segments.size)
            for (i in segments.indices) {
                assertSegment("$name: segment $i", wantSegments[i].jsonObject, segments[i])
            }

            val wantLook = want["trackLook"]!!.jsonObject
            val look = levelTrackLook(
                obj.properties["fillColor"]?.jsonPrimitive?.content ?: "#4CAF50",
                input.str("background"),
            )
            assertEquals("$name: track colour", wantLook.str("track"), look.track)
            assertEquals("$name: framed", wantLook.bool("framed"), look.framed)
        }
    }

    /**
     * A finger's position has to mean the same value here as in the designer
     * and in the firmware, or a tap on this app sets something else than the
     * same tap on a panel. Read off the recorded track rather than a second
     * set of numbers: the two ends of the track are 0 and 100 by definition,
     * and the middle is the half-way point of the run between them.
     */
    @Test
    fun `a point on the track means the percentage the picture shows`() {
        val cases = loadGolden()["cases"]!!.jsonObject
        for ((name, entry) in cases) {
            val case = entry.jsonObject
            val input = case["input"]!!.jsonObject
            val obj = objectOf(input)
            val fonts = fontsOf(input)
            val track = levelTrackRect(obj, fonts)
            val vertical = levelIsVertical(obj)
            val fromEnd = levelFillsFromEnd(obj)

            fun at(fraction: Double): Double {
                val along = (if (vertical) track.y else track.x) +
                    (if (vertical) track.h else track.w) * fraction
                return if (vertical) {
                    levelPercentFromPoint(obj, track.x.toDouble(), along, fonts)
                } else {
                    levelPercentFromPoint(obj, along, track.y.toDouble(), fonts)
                }
            }

            assertEquals("$name: the track's near end", if (fromEnd) 100.0 else 0.0, at(0.0), 0.001)
            assertEquals("$name: the track's far end", if (fromEnd) 0.0 else 100.0, at(1.0), 0.001)
            assertEquals("$name: half way along", 50.0, at(0.5), 0.001)
            // Past either end is still a value, not a negative one: a finger
            // that slips off the bar sets its nearest end.
            assertEquals("$name: before the start", if (fromEnd) 100.0 else 0.0, at(-0.5), 0.001)
            assertEquals("$name: past the end", if (fromEnd) 0.0 else 100.0, at(1.5), 0.001)
        }
    }

    private fun assertRect(what: String, expected: JsonElement?, actual: LevelRect?) {
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
    }

    private fun assertSegment(what: String, want: JsonObject, actual: LevelSegment) {
        assertEquals("$what.x", want.int("x"), actual.x)
        assertEquals("$what.y", want.int("y"), actual.y)
        assertEquals("$what.w", want.int("w"), actual.w)
        assertEquals("$what.h", want.int("h"), actual.h)
        assertEquals("$what.r", want.int("r"), actual.r)
        assertEquals("$what.role", want.str("role"), actual.role.wire)
        assertEquals("$what.roundStart", want.bool("roundStart"), actual.roundStart)
        assertEquals("$what.roundEnd", want.bool("roundEnd"), actual.roundEnd)
    }

    private fun JsonObject.int(key: String): Int = (this[key] as JsonPrimitive).int
    private fun JsonObject.num(key: String): Double = (this[key] as JsonPrimitive).double
    private fun JsonObject.str(key: String): String = (this[key] as JsonPrimitive).content
    private fun JsonObject.bool(key: String): Boolean = (this[key] as JsonPrimitive).boolean
}
