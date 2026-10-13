package com.schaltli.android.render

/**
 * A box as the designer draws it (`render-box.ts` renderBox) and the boards
 * do (`ColorScreenRenderer::renderBox`): the whole box in the border colour,
 * then the inset one in the fill colour on top - two fills, never a stroke,
 * so the border is whole pixels inside the box's own edge. Rounded corners
 * are the Adafruit quarter circles ([roundRectSpans]).
 *
 * Emits the runs in drawing order with the colour each is painted in, as
 * the designer gives it ("transparent" included - a run in it paints
 * nothing). Drawn by the app itself since 2026-10-13; before, a box reached
 * it baked into the screen's background picture. Held to the designer's
 * pixels by StaticObjectGoldenTest.
 */
object BoxRaster {
    fun plotBox(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        fillColor: String,
        strokeColor: String,
        strokeWidth: Int,
        cornerRadius: Int,
        // The designer's own test: a border colour is given at all, and a width.
        hasBorder: Boolean,
        run: (x: Int, y: Int, w: Int, h: Int, color: String) -> Unit,
    ) {
        val radius = maxOf(0, cornerRadius)
        val outer = if (hasBorder) strokeColor else fillColor
        if (radius > 0) roundRectSpans(x, y, w, h, radius, radius) { sx, sy, sw, sh -> run(sx, sy, sw, sh, outer) }
        else run(x, y, w, h, outer)

        if (!hasBorder) return
        val innerW = w - 2 * strokeWidth
        val innerH = h - 2 * strokeWidth
        if (innerW <= 0 || innerH <= 0) return
        val innerRadius = maxOf(0, radius - strokeWidth)
        if (innerRadius > 0) {
            roundRectSpans(x + strokeWidth, y + strokeWidth, innerW, innerH, innerRadius, innerRadius) { sx, sy, sw, sh ->
                run(sx, sy, sw, sh, fillColor)
            }
        } else {
            run(x + strokeWidth, y + strokeWidth, innerW, innerH, fillColor)
        }
    }
}
