package com.schaltli.android.ddf

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * This phone's own Device Description File, built at runtime.
 *
 * Until 2026-09-21 the app's DDF was a file checked into the designer
 * (`public/ddf/android-phone.ddf.zip`) declaring a fixed 360x800. That is one
 * size for a class of devices whose sizes all differ, so on any phone with
 * more room than that the project simply did not reach the edges: the
 * renderer draws one project unit as one dp and applies no fit step at all
 * (ScreenRenderer's own note). A checked-in file cannot fix that, because it
 * can only ever hold one number.
 *
 * So the phone says its own size, the way every board does: this builds the
 * zip, DdfServer serves it, and MqttRepository announces where
 * (docs/2026-09-21-android-self-announce.md in the designer repo).
 *
 * The bytes are deterministic - fixed entry order, fixed timestamps - so the
 * same phone with the same screen produces the same zip and therefore the
 * same hash, and the designer's cache is not invalidated by the clock.
 */
object DdfBuilder {

    /** What the designer is told, and the bytes it will fetch. */
    data class Ddf(val bytes: ByteArray, val hash: String)

    /**
     * Everything the app can draw. Kept beside the renderer it describes: a
     * type listed here that the app cannot draw is a tool the designer
     * offers into a hole, and one it can draw but does not list is a tool
     * that stays greyed out for no reason. Both happened to the M5 Dial when
     * its DDF was hand-assembled.
     */
    private val SUPPORTED_TYPES = listOf(
        "live-text", "live-icon", "text", "icon",
        "bar", "slider", "gauge", "dial",
        "switch", "button-group", "button",
        "line", "live-line", "box", "switcher", "panel",
    )

    /**
     * The font sizes the designer may offer. Roboto is what Compose draws
     * with, so what the designer previews and what the phone shows are the
     * same typeface; the ascent/descent pairs are what the designer lays
     * text out with.
     */
    private data class FontSpec(val size: Int, val ascent: Int, val descent: Int)

    private val FONTS = listOf(
        FontSpec(12, 11, 3),
        FontSpec(16, 15, 4),
        FontSpec(20, 19, 5),
        FontSpec(24, 22, 6),
    )

    /** Margins of the drawn phone body around its screen, in project units. */
    private const val SIDE_MARGIN = 16
    private const val TOP_MARGIN = 60
    private const val BOTTOM_MARGIN = 60
    private const val CORNER_RADIUS = 28

    fun build(deviceId: String, deviceName: String, widthDp: Int, heightDp: Int, robotoTtf: ByteArray): Ddf {
        val width = widthDp.coerceAtLeast(1)
        val height = heightDp.coerceAtLeast(1)
        val entries = listOf(
            "device.json" to deviceJson(deviceId, deviceName, width, height).toByteArray(Charsets.UTF_8),
            "adornment.svg" to adornmentSvg(width, height).toByteArray(Charsets.UTF_8),
            "fonts/Roboto.ttf" to robotoTtf,
        )
        val bytes = zip(entries)
        return Ddf(bytes, sha256Short(bytes))
    }

    private fun deviceJson(deviceId: String, deviceName: String, width: Int, height: Int): String {
        val fonts = FONTS.joinToString(",\n") { f ->
            """
            |    {
            |      "id": "font-roboto-${f.size}",
            |      "displayName": "Roboto ${f.size}px",
            |      "internalName": "Roboto",
            |      "file": "fonts/Roboto.ttf",
            |      "size": ${f.size},
            |      "ascent": ${f.ascent},
            |      "descent": ${f.descent},
            |      "format": "ttf"
            |    }
            """.trimMargin()
        }
        val types = SUPPORTED_TYPES.joinToString(", ") { "\"$it\"" }
        return """
        |{
        |  "ddfVersion": "1.2",
        |  "device": {
        |    "id": "${escape(deviceId)}",
        |    "name": "${escape(deviceName)}",
        |    "platform": "android"
        |  },
        |  "screen": {
        |    "width": $width,
        |    "height": $height,
        |    "colorDepth": "24bit",
        |    "allowedRotations": [90, 180, 270]
        |  },
        |  "adornment": {
        |    "svgPath": "adornment.svg"
        |  },
        |  "fonts": [
        |$fonts
        |  ],
        |  "supportedObjectTypes": [$types],
        |  "systemGeneration": "1.1",
        |  "deviceActions": ["showScreenMenu"]
        |}
        |
        """.trimMargin()
    }

    /**
     * The phone the designer draws around the canvas. Every number in it
     * comes from the screen size, which is why it is generated rather than
     * shipped: a fixed body around a screen of any size would show the
     * screen hanging out of the phone.
     *
     * The body is one path with the screen punched out of it (`evenodd` over
     * two subpaths - SVG's way of saying Path > Difference), so the screen
     * area stays at true alpha 0 and the designer's canvas shows through.
     * The camera dot and the home bar are decoration, and say so.
     */
    private fun adornmentSvg(width: Int, height: Int): String {
        val bodyW = width + 2 * SIDE_MARGIN
        val bodyH = height + TOP_MARGIN + BOTTOM_MARGIN
        val r = CORNER_RADIUS
        val inset = 4
        val right = bodyW - inset
        val bottom = bodyH - inset
        val screenY = TOP_MARGIN
        val screenBottom = TOP_MARGIN + height
        val screenRight = SIDE_MARGIN + width
        return """
        |<svg xmlns="http://www.w3.org/2000/svg" width="$bodyW" height="$bodyH" viewBox="0 0 $bodyW $bodyH">
        |  <!-- Generated by DdfBuilder from this phone's own screen size. The body
        |       has the screen punched out of it (fill-rule="evenodd" over two
        |       subpaths), leaving that area at true alpha 0 so the designer's canvas
        |       shows through. A generic phone silhouette - not modelled on any
        |       specific device. -->
        |  <path
        |    fill-rule="evenodd"
        |    fill="#1a1a1a"
        |    stroke="#000000"
        |    stroke-width="2"
        |    d="M${inset + r},$inset L${right - r},$inset A$r,$r 0 0 1 $right,${inset + r} L$right,${bottom - r} A$r,$r 0 0 1 ${right - r},$bottom L${inset + r},$bottom A$r,$r 0 0 1 $inset,${bottom - r} L$inset,${inset + r} A$r,$r 0 0 1 ${inset + r},$inset Z
        |       M$SIDE_MARGIN,$screenY L$screenRight,$screenY L$screenRight,$screenBottom L$SIDE_MARGIN,$screenBottom Z"
        |  />
        |
        |  <!-- Screen area marker - the designer reads this rect to know where the
        |       canvas goes. Purely informational; the hole itself is cut above. -->
        |  <rect id="screen" x="$SIDE_MARGIN" y="$screenY" width="$width" height="$height" fill="none" stroke="none" />
        |
        |  <!-- Front camera - decorative. -->
        |  <circle cx="${bodyW / 2}" cy="${TOP_MARGIN / 2}" r="5" fill="#000000" stroke="#333333" stroke-width="1" />
        |
        |  <!-- Home indicator bar - decorative. -->
        |  <rect x="${(bodyW - 100) / 2}" y="${bodyH - 34}" width="100" height="6" rx="3" fill="#555555" />
        |</svg>
        |
        """.trimMargin()
    }

    private fun escape(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * A zip with fixed timestamps, so the same inputs give the same bytes and
     * therefore the same hash. Stored rather than deflated for the two small
     * text entries would save nothing worth the asymmetry; everything goes
     * through the same deflater.
     */
    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, content) in entries) {
                val entry = ZipEntry(name)
                // 1980-01-01, the earliest a zip can express: any real clock
                // would make the bytes differ between two builds of the same
                // thing.
                entry.time = 315532800000L
                entry.crc = CRC32().apply { update(content) }.value
                entry.size = content.size.toLong()
                zos.putNextEntry(entry)
                zos.write(content)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * The identity of exactly these bytes: sha256, first 16 hex characters,
     * lowercase - what the device contract's `ddfHash` is, and what the
     * designer checks the download against.
     */
    private fun sha256Short(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
            .take(16)
}
