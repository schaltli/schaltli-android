package com.schaltli.android.ui.objects

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.schaltli.android.data.ScreenObject
import com.schaltli.android.render.NeedleRaster
import com.schaltli.android.render.Readout
import com.schaltli.android.render.levelIsSettableType
import com.schaltli.android.render.toRgb565
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A styled gauge or bar (the designer's device contract §2.15,
 * docs/2026-11-23-instrument-styles.md there): its baked face from the
 * bundle, the needle drawn into it by [NeedleRaster] where the value puts it,
 * and a new value swung to with the style's spring - the designer's
 * lib/instrument-spring.ts step for step. At rest the picture is the
 * designer's, pixel for pixel; the swing is the app's own.
 */

/**
 * The face as pixels: ARGB, with what the face leaves transparent at alpha 0;
 * [readout] the readout's strip of characters, where the style has one and
 * it could be read.
 */
class InstrumentFace(val width: Int, val height: Int, val argb: IntArray, val readout: Readout.Strip? = null)

/** The face of a styled object, or null when it has none or it cannot be read - then it is drawn flat. */
@Composable
fun rememberInstrumentFace(obj: ScreenObject, assetFileOf: (String) -> java.io.File): InstrumentFace? {
    val inst = obj.instrument ?: return null
    if (levelIsSettableType(obj.type)) return null
    val glyphs = inst.readout?.glyphs
    return remember(inst.face, glyphs) {
        val bitmap = runCatching { BitmapFactory.decodeFile(assetFileOf(inst.face).path) }.getOrNull() ?: return@remember null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val strip = glyphs?.let { path -> runCatching { readStrip(assetFileOf(path)) }.getOrNull() }
        InstrumentFace(bitmap.width, bitmap.height, pixels, strip)
    }
}

/** The readout's strip from its PNG: white, the strip's alpha as its alpha channel - read unpremultiplied, so every alpha byte survives. */
private fun readStrip(file: java.io.File): Readout.Strip? {
    val opts = BitmapFactory.Options().apply { inPremultiplied = false }
    val bitmap = BitmapFactory.decodeFile(file.path, opts) ?: return null
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return Readout.Strip(bitmap.width, bitmap.height, IntArray(pixels.size) { pixels[it] ushr 24 })
}

/** Where a value lands along the scale, 0..1 - the designer's calibratedAt. */
fun instrumentAt(rawValue: String, obj: ScreenObject): Double {
    if (rawValue.isBlank()) return 0.0 // no value: the needle rests on its stop
    val value = rawValue.toDoubleOrNull() ?: 0.0
    return (calculateFillPercent(value, parseCalibrationPoints(obj.properties)) / 100.0).coerceIn(0.0, 1.0)
}

private const val PIN = 0.025

@Composable
fun InstrumentView(obj: ScreenObject, face: InstrumentFace, rawValue: String, screenBackgroundColor: String?) {
    val inst = obj.instrument!!
    val target = instrumentAt(rawValue, obj)
    var shown by remember(obj.id) { mutableStateOf(target) }

    // The swing: the designer's stepSpring() - four sub-steps a frame, a frame
    // longer than 50 ms taken as 50 ms, the pins keeping 30 % of the speed.
    LaunchedEffect(obj.id, target) {
        val spring = inst.spring
        if (spring == null) {
            shown = target
            return@LaunchedEffect
        }
        var at = shown
        var velocity = 0.0
        var last = 0L
        val w = 2 * Math.PI * spring.frequency
        while (true) {
            val done = withFrameNanos { now ->
                val dt = if (last == 0L) 0.0 else min(0.05, (now - last) / 1e9)
                last = now
                repeat(4) {
                    val h = dt / 4
                    velocity += (w * w * (target - at) - 2 * spring.damping * w * velocity) * h
                    at += velocity * h
                    if (at < -PIN) { at = -PIN; velocity = -velocity * 0.3 }
                    if (at > 1 + PIN) { at = 1 + PIN; velocity = -velocity * 0.3 }
                }
                val rest = abs(target - at) < 2e-4 && abs(velocity) < 2e-3
                shown = if (rest) target else at
                rest
            }
            if (done) break
        }
    }

    val level = NeedleRaster.levelOf(shown)
    val pose = if (inst.sweep != null) {
        NeedleRaster.Pose(angle64 = NeedleRaster.angle64Of(inst.sweep.from + (inst.sweep.to - inst.sweep.from) * shown), level = level)
    } else {
        // The designer's Math.round: floor(x + 0.5).
        NeedleRaster.Pose(
            shiftX = floor(shown * (inst.travel?.x ?: 0) + 0.5).toInt(),
            shiftY = floor(shown * (inst.travel?.y ?: 0) + 0.5).toInt(),
            level = level,
        )
    }
    // The readout shows the value itself at once, not the needle on its way.
    val readoutText = inst.readout?.let { Readout.format(rawValue, it.decimals) } ?: ""
    val ground = toRgb565(screenBackgroundColor ?: "#ffffff").packed
    val bitmap = remember(face, pose, ground, readoutText) {
        val px = face.argb.copyOf()
        val target565 = object : NeedleRaster.Target {
            override val width = face.width
            override val height = face.height
            override fun get(x: Int, y: Int): Int {
                val c = px[y * face.width + x]
                if ((c ushr 24) == 0) return ground
                return (((c shr 16) and 0xFF) shr 3 shl 11) or (((c shr 8) and 0xFF) shr 2 shl 5) or ((c and 0xFF) shr 3)
            }
            override fun set(x: Int, y: Int, c: Int) {
                val r = (c shr 11) and 31
                val g = (c shr 5) and 63
                val b = c and 31
                px[y * face.width + x] = (0xFF shl 24) or (((r shl 3) or (r shr 2)) shl 16) or (((g shl 2) or (g shr 4)) shl 8) or ((b shl 3) or (b shr 2))
            }
        }
        NeedleRaster.draw(target565, inst.needle, pose)
        val readout = inst.readout
        if (readout != null && face.readout != null && readoutText.isNotEmpty()) Readout.draw(target565, readout, face.readout, readoutText)
        Bitmap.createBitmap(px, face.width, face.height, Bitmap.Config.ARGB_8888)
    }

    Canvas(
        modifier = Modifier
            .offset(x = obj.x.roundToInt().dp, y = obj.y.roundToInt().dp)
            .size(width = face.width.dp, height = face.height.dp),
    ) {
        drawIntoCanvas { canvas ->
            val scale = density
            val blit = android.graphics.Paint().apply {
                isAntiAlias = false
                isFilterBitmap = false
            }
            val dst = android.graphics.Rect(0, 0, (bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt())
            canvas.nativeCanvas.drawBitmap(bitmap, null, dst, blit)
        }
    }
}
