package com.screensmith.android.render

import android.graphics.Canvas
import android.graphics.Paint

/**
 * Adafruit_GFX's rounded-rectangle fill, ported for the third time.
 *
 * The other two copies are the firmware's own `Adafruit_GFX::fillRoundRect`
 * (which a Switch's marker bar is drawn with) and the designer's
 * `fillRoundRect` in `components/canvas/renderers/render-box.ts`. Android
 * has `Canvas.drawRoundRect`, and using it here would be the obvious move
 * and the wrong one: it rasterizes a Bezier path with anti-aliased corners,
 * while the other two lay down whole pixels from an integer midpoint-circle
 * loop. A bar drawn both ways differs on the corner pixels of every marker
 * on the screen, which is a HIL diff on every Switch at once.
 *
 * Everything below works in *project units* - the designer's own pixel grid,
 * 1 unit = 1dp on this target - and is scaled to device pixels only at the
 * moment each span is drawn. Doing the arithmetic in device pixels instead
 * would put a density-dependent number into an algorithm whose whole purpose
 * is to produce the same integers everywhere.
 */

/**
 * Mirrors Adafruit_GFX::fillCircleHelper() exactly - same variable names,
 * same loop, same integer arithmetic. [corners] is a bitmask: 1 = right
 * half, 2 = left half, matching the library's own convention.
 */
private fun fillCircleHelper(
    canvas: Canvas,
    paint: Paint,
    x0: Int,
    y0: Int,
    r: Int,
    corners: Int,
    deltaIn: Int,
    scale: Float,
) {
    var f = 1 - r
    var ddFX = 1
    var ddFY = -2 * r
    var x = 0
    var y = r
    var px = x
    var py = y
    val delta = deltaIn + 1

    while (x < y) {
        if (f >= 0) {
            y--
            ddFY += 2
            f += ddFY
        }
        x++
        ddFX += 2
        f += ddFX
        if (x < y + 1) {
            if (corners and 1 != 0) span(canvas, paint, x0 + x, y0 - y, 1, 2 * y + delta, scale)
            if (corners and 2 != 0) span(canvas, paint, x0 - x, y0 - y, 1, 2 * y + delta, scale)
        }
        if (y != py) {
            if (corners and 1 != 0) span(canvas, paint, x0 + py, y0 - px, 1, 2 * px + delta, scale)
            if (corners and 2 != 0) span(canvas, paint, x0 - py, y0 - px, 1, 2 * px + delta, scale)
            py = y
        }
        px = x
    }
}

/** One axis-aligned run of pixels, in project units, drawn at [scale] device pixels per unit. */
private fun span(canvas: Canvas, paint: Paint, x: Int, y: Int, w: Int, h: Int, scale: Float) {
    canvas.drawRect(x * scale, y * scale, (x + w) * scale, (y + h) * scale, paint)
}

/**
 * Mirrors Adafruit_GFX::fillRoundRect(), including its own radius clamp
 * (r > maxRadius -> maxRadius) so an oversized radius self-corrects
 * identically on every side.
 *
 * [paint] must already carry the colour and be non-anti-aliased; it is used
 * as-is so a caller can reuse one Paint across a whole object.
 */
fun fillRoundRect(
    canvas: Canvas,
    paint: Paint,
    x: Int,
    y: Int,
    w: Int,
    h: Int,
    radius: Int,
    scale: Float,
) {
    val maxRadius = minOf(w, h) / 2
    var r = radius
    if (r > maxRadius) r = maxRadius
    if (r < 0) r = 0

    span(canvas, paint, x + r, y, w - 2 * r, h, scale)
    fillCircleHelper(canvas, paint, x + w - r - 1, y + r, r, 1, h - 2 * r - 1, scale)
    fillCircleHelper(canvas, paint, x + r, y + r, r, 2, h - 2 * r - 1, scale)
}
