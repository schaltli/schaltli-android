package com.screensmith.android.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode

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
) = fillRoundRectSides(canvas, paint, x, y, w, h, radius, radius, scale)

/**
 * The same rectangle with a radius per side - the designer's
 * `fillRoundRectSides` in render-box.ts.
 *
 * One radius cannot describe a button in a connected button group, which is
 * what a Switch in its group form is: Material 3 gives such a button a fully
 * round outer end and a small inner one, and a single radius forces a choice
 * between an outer end that does not follow its container and an inner end
 * that rounds away from its neighbour. Asked for either, a segment about as
 * tall as it is wide simply clamps to a circle.
 *
 * With both radii equal this is exactly the shape [fillRoundRect] always drew,
 * down to the pixel: the straight middle runs between the two arcs and each
 * end is the same Adafruit_GFX quarter-circle pair.
 */
fun fillRoundRectSides(
    canvas: Canvas,
    paint: Paint,
    x: Int,
    y: Int,
    w: Int,
    h: Int,
    rLeft: Int,
    rRight: Int,
    scale: Float,
) {
    if (w <= 0 || h <= 0) return
    val maxRadius = minOf(w, h) / 2
    val left = maxOf(0, minOf(maxRadius, rLeft))
    val right = maxOf(0, minOf(maxRadius, rRight))

    span(canvas, paint, x + left, y, w - left - right, h, scale)
    if (right > 0) fillCircleHelper(canvas, paint, x + w - right - 1, y + right, right, 1, h - 2 * right - 1, scale)
    if (left > 0) fillCircleHelper(canvas, paint, x + left, y + left, left, 2, h - 2 * left - 1, scale)
}

/**
 * A rounded-rectangle ring [thickness] units thick, cut out of a filled shape
 * rather than stroked - the designer's `fillRoundRectRing`.
 *
 * Stroked, a thin ring is anti-aliased and breaks up wherever the picture is
 * later cut to one bit; cut out of two integer-rasterised shapes it is whole
 * at any depth. Cut, not painted over, so whatever is behind the control
 * still shows inside it - which is why this needs a layer of its own.
 */
fun fillRoundRectRing(
    canvas: Canvas,
    paint: Paint,
    x: Int,
    y: Int,
    w: Int,
    h: Int,
    r: Int,
    thickness: Int,
    scale: Float,
    rRight: Int = r,
) {
    if (w <= 0 || h <= 0) return
    val t = maxOf(1, thickness)
    val layer = canvas.saveLayer(null, null)
    fillRoundRectSides(canvas, paint, x, y, w, h, r, rRight, scale)
    if (w > 2 * t && h > 2 * t) {
        val cut = Paint().apply {
            isAntiAlias = false
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        fillRoundRectSides(
            canvas, cut, x + t, y + t, w - 2 * t, h - 2 * t,
            maxOf(0, r - t), maxOf(0, rRight - t), scale,
        )
    }
    canvas.restoreToCount(layer)
}
