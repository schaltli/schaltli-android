package com.screensmith.android.ddf

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * What this phone tells the designer about itself.
 *
 * These assertions used to live in the designer, against the checked-in
 * `public/ddf/android-phone.ddf.zip` (`e2e/android-export.spec.ts`'s "Android
 * DDF" block). Since 2026-09-21 there is no such file: the app builds its own
 * from the screen it actually has and announces it, so the claims belong
 * where the claims are made.
 */
class DdfBuilderTest {

    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                out[entry.name] = zis.readBytes()
            }
        }
        return out
    }

    private fun build(width: Int = 412, height: Int = 915) =
        DdfBuilder.build("android-a1b2c3d4", "Pixel 7", width, height, ByteArray(64) { it.toByte() })

    @Test
    fun `declares the screen this phone actually has`() {
        val manifest = JSONObject(String(entries(build().bytes)["device.json"]!!))
        val screen = manifest.getJSONObject("screen")
        assertEquals(412, screen.getInt("width"))
        assertEquals(915, screen.getInt("height"))
        assertEquals("24bit", screen.getString("colorDepth"))

        // The whole point of building this at runtime: a different phone says
        // something different. A checked-in file could only ever say one.
        val other = JSONObject(String(entries(build(360, 800).bytes)["device.json"]!!))
        assertEquals(360, other.getJSONObject("screen").getInt("width"))
    }

    @Test
    fun `carries whatever name the phone is known by`() {
        // DeviceIdentity picks that name - the vendor's marketing string
        // where there is one, the owner's own name for the device, or maker
        // and model - and the builder simply carries it. What matters here
        // is that it survives into the manifest intact, spaces and all: it
        // is what the designer's device picker shows.
        val manifest = JSONObject(
            String(
                entries(
                    DdfBuilder.build("android-a1b2c3d4", "HUAWEI P20 Pro", 360, 679, ByteArray(4)).bytes,
                )["device.json"]!!,
            ),
        )
        assertEquals("HUAWEI P20 Pro", manifest.getJSONObject("device").getString("name"))
    }

    @Test
    fun `is identified by this phone and named after it`() {
        val device = JSONObject(String(entries(build().bytes)["device.json"]!!)).getJSONObject("device")
        assertEquals("android-a1b2c3d4", device.getString("id"))
        assertEquals("Pixel 7", device.getString("name"))
        // The designer reads this to know a phone from a board - a deploy
        // dialog offers a project to boards only.
        assertEquals("android", device.getString("platform"))
    }

    @Test
    fun `declares the same controls and the screen menu the Waveshare does`() {
        val manifest = JSONObject(String(entries(build().bytes)["device.json"]!!))
        val types = manifest.getJSONArray("supportedObjectTypes")
        val list = (0 until types.length()).map { types.getString(it) }

        // Both are gated on this list alone: the toolbar disables a tool the
        // device does not list, and the deploy dialog refuses a project
        // placing one.
        assertTrue("gauge missing", list.contains("gauge"))
        assertTrue("button-group missing", list.contains("button-group"))
        assertTrue("slider missing", list.contains("slider"))
        assertTrue("dial missing", list.contains("dial"))

        // showScreenMenu is what a swipe binds to for the "which screen am I
        // on" overlay; without it the designer offers no such binding.
        val actions = manifest.getJSONArray("deviceActions")
        assertEquals("showScreenMenu", actions.getString(0))

        // Every target declares which system generation it speaks, and the
        // deploy dialog compares majors before uploading anything.
        assertEquals("1.0", manifest.getString("systemGeneration"))
    }

    @Test
    fun `draws a phone around the screen it declares`() {
        val svg = String(entries(build().bytes)["adornment.svg"]!!)
        // The designer finds the canvas by this rect, so its numbers have to
        // be the screen's own.
        assertTrue(svg.contains("""id="screen" x="16" y="60" width="412" height="915""""))
        // Body = screen plus the margins, or the screen hangs out of the phone.
        assertTrue(svg.contains("""width="444" height="1035""""))
    }

    @Test
    fun `hashes what it serves, and the same phone twice gives the same bytes`() {
        val first = build()
        val second = build()
        // Deterministic: fixed entry order and fixed timestamps, so the
        // designer's cached copy is not invalidated by the clock.
        assertEquals(first.hash, second.hash)
        assertTrue(first.bytes.contentEquals(second.bytes))

        // sha256, first 16 hex characters, lowercase - what the device
        // contract's ddfHash is, and what the designer checks the download
        // against.
        assertEquals(16, first.hash.length)
        assertTrue(first.hash.all { it.isDigit() || it in 'a'..'f' })

        // A different screen is a different device description.
        assertNotEquals(first.hash, build(360, 800).hash)
    }

    @Test
    fun `carries the font the designer will offer`() {
        val names = entries(build().bytes).keys
        assertTrue(names.contains("fonts/Roboto.ttf"))
        assertTrue(names.contains("device.json"))
        assertTrue(names.contains("adornment.svg"))
    }
}
