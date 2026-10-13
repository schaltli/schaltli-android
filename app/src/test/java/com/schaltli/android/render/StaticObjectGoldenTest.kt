package com.schaltli.android.render

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A line and a box, pixel for pixel as the designer draws them.
 *
 * The golden file is the designer's own render-line.ts and render-box.ts on a
 * white canvas (hil/android/fixtures/build-static-golden.js records it):
 * every pixel they leave non-white, with its colour. LineRaster and
 * BoxRaster paint the same canvas here, and the two must agree on every
 * pixel - both are whole-pixel algorithms on purpose, so there is no
 * tolerance to give. Since 2026-10-13 the app draws both itself; before, they
 * came baked into a background picture and there was nothing to compare.
 */
class StaticObjectGoldenTest {

    private fun cases(): List<JSONObject> {
        val stream = javaClass.classLoader!!.getResourceAsStream("static-object-golden.json")
            ?: error("static-object-golden.json missing - run hil/android/fixtures/build-static-golden.js in the designer")
        val array = JSONArray(stream.bufferedReader().readText())
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    private fun JSONObject.dbl(key: String, default: Double): Double = if (has(key) && !isNull(key)) optDouble(key, default) else default

    /** What the app paints, as #rrggbb by pixel, on a white canvas of the case's size. */
    private fun paint(case: JSONObject): Map<Pair<Int, Int>, String> {
        val width = case.getInt("width")
        val height = case.getInt("height")
        val obj = case.getJSONObject("obj")
        val props = obj.getJSONObject("properties")
        val canvas = HashMap<Pair<Int, Int>, String>()
        val set = { x: Int, y: Int, color: String ->
            if (x in 0 until width && y in 0 until height && color != "transparent") canvas[x to y] = color.lowercase()
        }
        if (obj.getString("type") == "box") {
            val strokeWidth = props.dbl("strokeWidth", 0.0).toInt()
            val stroke = props.optString("strokeColor", "")
            BoxRaster.plotBox(
                obj.getInt("x"), obj.getInt("y"), obj.getInt("width"), obj.getInt("height"),
                props.optString("fillColor", "").ifEmpty { "#e5e5e5" },
                stroke.ifEmpty { "#000000" },
                strokeWidth,
                props.dbl("cornerRadius", 0.0).toInt(),
                stroke.isNotEmpty() && strokeWidth > 0,
            ) { x, y, w, h, color ->
                for (py in y until y + h) for (px in x until x + w) set(px, py, color)
            }
        } else {
            val pts = props.getJSONArray("points")
            val points = (0 until pts.length()).map { LineRaster.Point(pts.getJSONObject(it).getDouble("x"), pts.getJSONObject(it).getDouble("y")) }
            val strokeWidth = props.dbl("strokeWidth", 0.0).takeIf { it != 0.0 } ?: 1.0
            LineRaster.plotLine(
                points, strokeWidth, props.dbl("filletRadius", 0.0),
                props.optBoolean("arrowStart", false), props.optBoolean("arrowEnd", false),
            ) { x, y -> set(x, y, props.optString("color", "#000000")) }
        }
        // White is the canvas: a pixel painted white reads as unpainted, as in the golden file.
        return canvas.filterValues { it != "#ffffff" }
    }

    @Test
    fun `every line and box paints exactly the designer's pixels`() {
        val failures = mutableListOf<String>()
        val all = cases()
        assertTrue("the golden file holds no cases", all.isNotEmpty())
        for (case in all) {
            val ink = case.getJSONArray("ink")
            val expected = HashMap<Pair<Int, Int>, String>()
            for (i in 0 until ink.length()) {
                val p = ink.getJSONArray(i)
                expected[p.getInt(0) to p.getInt(1)] = p.getString(2).lowercase()
            }
            val actual = paint(case)
            val missing = expected.keys - actual.keys
            val extra = actual.keys - expected.keys
            val wrongColour = expected.keys.intersect(actual.keys).filter { expected[it] != actual[it] }
            if (missing.isNotEmpty() || extra.isNotEmpty() || wrongColour.isNotEmpty()) {
                failures += "${case.getString("name")}: ${missing.size} missing (e.g. ${missing.take(4)}), " +
                    "${extra.size} extra (e.g. ${extra.take(4)}), ${wrongColour.size} in another colour (e.g. ${wrongColour.take(3).map { "$it ${expected[it]} vs ${actual[it]}" }})"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
