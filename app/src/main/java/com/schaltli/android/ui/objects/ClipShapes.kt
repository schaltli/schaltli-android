package com.schaltli.android.ui.objects

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.schaltli.android.data.ScreenObject
import com.schaltli.android.render.roundRectSpans
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

// A free area that clips what it holds to its round corners (device contract
// §2.1 `clip`; docs/2026-11-03-card-clip.md in the designer): each object it
// held carries the shapes, outermost first, in the object's own coordinate
// space. A pixel shows only where Adafruit_GFX::fillRoundRect would set one
// for every shape - the same runs [roundRectSpans] gives the box, so a
// clipped heading meets the card's corner pixel for pixel.

/** One clip shape, in project units. */
data class ClipShape(val x: Int, val y: Int, val width: Int, val height: Int, val radius: Int)

/** The shapes an object is clipped by; empty for almost every object. */
fun clipShapesOf(obj: ScreenObject): List<ClipShape> {
    val list = obj.properties["clip"] as? JsonArray ?: return emptyList()
    return list.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        fun n(key: String) = ((o[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0).toInt()
        ClipShape(n("x"), n("y"), n("width"), n("height"), n("radius")).takeIf { it.width > 0 && it.height > 0 }
    }
}

/** The shape as the union of its whole-pixel runs, each unit `density` device pixels. */
private class SpansShape(private val s: ClipShape) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val scale = density.density
        val path = Path()
        roundRectSpans(s.x, s.y, s.width, s.height, s.radius, s.radius) { x, y, w, h ->
            path.addRect(Rect(x * scale, y * scale, (x + w) * scale, (y + h) * scale))
        }
        return Outline.Generic(path)
    }
}

/** [content] clipped by every one of [shapes] (their intersection), in its parent's space. */
@Composable
fun ClippedTo(shapes: List<ClipShape>, content: @Composable () -> Unit) {
    var modifier: Modifier = Modifier.fillMaxSize()
    for (s in shapes) modifier = modifier.clip(SpansShape(s))
    Box(modifier) { content() }
}
