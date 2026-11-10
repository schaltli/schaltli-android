package com.schaltli.android.ui.objects

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.schaltli.android.data.ScreenObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.min
import kotlin.math.roundToInt

// The Camera (the designer's docs/2026-11-09-camera.md, device contract
// «camera»): one picture after another from a snapshot URL, the next asked for
// once the last is decoded, read to Content-Length, up to 256 KB, fitted whole
// and centred and never enlarged, «No live image» whenever none younger than a
// second is shown. The boards' CameraFeed does the same; the rules are the
// spec's, not this app's.
//
// Asked only while it is composed - on the screen shown, or in an open popup -
// and the app in front with the panel awake (decision 4). Nothing is logged:
// the URL can be all a camera needs, and the password never (decision 10).

/** True while ScreenSleep draws the panel dark: a Camera then asks nothing. */
val LocalPanelAsleep = compositionLocalOf { false }

/** What a test asks about the pictures (the test interface's /api/debug). */
object CameraStats {
    val requests = AtomicLong()
    val shown = AtomicLong()
    val failed = AtomicLong()

    @Volatile var lastFailure: String = ""

    /** No URL, user or password: what failed and how often, nothing else. */
    fun report(): Map<String, Any?> = mapOf(
        "requests" to requests.get(),
        "shown" to shown.get(),
        "failed" to failed.get(),
        "lastFailure" to lastFailure,
    )
}

/** The pure part of a Camera, tested in CameraViewTest. */
object CameraFetch {
    /** Larger is no picture (decision 13). */
    const val MAX_BYTES = 256 * 1024

    /** Older than this, and the object says «No live image» (decision 7). */
    const val LIVE_MS = 1000L

    // A camera that does not answer is «No live image» after a second anyway;
    // these only bound how long one request can hold the next.
    const val CONNECT_TIMEOUT_MS = 2000
    const val READ_TIMEOUT_MS = 2000

    /** After a failure, before asking again: a stopped camera is not hammered. */
    const val RETRY_MS = 200L

    const val MISSING = "No live image"

    @OptIn(ExperimentalEncodingApi::class)
    fun basicAuth(username: String, password: String): String? =
        if (username.isEmpty() && password.isEmpty()) null
        else "Basic " + Base64.encode("$username:$password".toByteArray(Charsets.UTF_8))

    /**
     * The body: to [contentLength] when the server says it (-1: it did not),
     * else to the end. Null when it is larger than [limit] or ends early.
     */
    fun readBody(input: InputStream, contentLength: Long, limit: Int = MAX_BYTES): ByteArray? {
        if (contentLength > limit) return null
        if (contentLength >= 0) {
            val body = ByteArray(contentLength.toInt())
            var at = 0
            while (at < body.size) {
                val n = input.read(body, at, body.size - at)
                if (n < 0) return null
                at += n
            }
            return body
        }
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            out.write(chunk, 0, n)
            if (out.size() > limit) return null
        }
        return out.toByteArray()
    }

    /** One picture's bytes, or null with [CameraStats.lastFailure] saying why. */
    fun fetch(url: String, authorization: String?): ByteArray? {
        CameraStats.requests.incrementAndGet()
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                instanceFollowRedirects = false
                setRequestProperty("Cache-Control", "no-cache")
                if (authorization != null) setRequestProperty("Authorization", authorization)
            }
            val status = connection.responseCode
            if (status != 200) {
                // Read and dropped, so the connection can be used again.
                connection.errorStream?.use { it.readBytes() }
                fail("HTTP $status")
            } else {
                // contentLength, not contentLengthLong: that one needs API 24 and
                // the app runs from 23 (lint, the v0.17.0 release); a picture is
                // at most 256 KB, and -1 says there is no Content-Length.
                val length = connection.contentLength.toLong()
                val body = connection.inputStream.use { readBody(it, length) }
                body ?: fail(if (length > MAX_BYTES) "over ${MAX_BYTES / 1024} KB" else "cut short or over ${MAX_BYTES / 1024} KB")
            }
        } catch (e: Exception) {
            connection?.disconnect()
            fail(e.javaClass.simpleName)
        }
    }

    private fun fail(why: String): ByteArray? {
        CameraStats.failed.incrementAndGet()
        CameraStats.lastFailure = why
        return null
    }

    /** x, y, width, height: the picture whole and centred in the box, never larger than it is. */
    fun fit(imageW: Int, imageH: Int, boxX: Int, boxY: Int, boxW: Int, boxH: Int): IntArray {
        if (imageW <= 0 || imageH <= 0) return intArrayOf(boxX, boxY, 0, 0)
        val scale = min(1.0, min(boxW.toDouble() / imageW, boxH.toDouble() / imageH))
        val w = (imageW * scale).roundToInt()
        val h = (imageH * scale).roundToInt()
        return intArrayOf(boxX + (boxW - w) / 2, boxY + (boxH - h) / 2, w, h)
    }

    /**
     * The decoder's inSampleSize: the largest power of two that still leaves
     * the picture at least the size it is drawn at, at [density] pixels a unit.
     */
    fun sampleSize(imageW: Int, imageH: Int, boxW: Int, boxH: Int, density: Float): Int {
        val fitted = fit(imageW, imageH, 0, 0, boxW, boxH)
        val needW = fitted[2] * density
        val needH = fitted[3] * density
        var size = 1
        while (imageW / (size * 2) >= needW && imageH / (size * 2) >= needH) size *= 2
        return size
    }

    fun isLive(shownAt: Long, now: Long): Boolean = shownAt > 0 && now - shownAt < LIVE_MS
}

private class Shown(val bitmap: Bitmap, val sourceW: Int, val sourceH: Int, val at: Long)

private fun decode(bytes: ByteArray, boxW: Int, boxH: Int, density: Float, at: Long): Shown? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = CameraFetch.sampleSize(bounds.outWidth, bounds.outHeight, boxW, boxH, density)
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    return Shown(bitmap, bounds.outWidth, bounds.outHeight, at)
}

@Composable
fun CameraView(obj: ScreenObject) {
    val url = obj.properties.stringOrNull("url").orEmpty().trim()
    val authorization = CameraFetch.basicAuth(
        obj.properties.stringOrNull("username").orEmpty(),
        obj.properties.stringOrNull("password").orEmpty(),
    )
    val boxW = obj.width.toInt()
    val boxH = obj.height.toInt()

    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val asleep = LocalPanelAsleep.current
    val active = lifecycle.isAtLeast(Lifecycle.State.STARTED) && !asleep && url.startsWith("http://")

    var shown by remember(obj.id) { mutableStateOf<Shown?>(null) }
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density

    LaunchedEffect(url, authorization, active, boxW, boxH) {
        if (!active) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            while (isActive) {
                val bytes = CameraFetch.fetch(url, authorization)
                val picture = bytes?.let { decode(it, boxW, boxH, density, SystemClock.uptimeMillis()) }
                if (bytes != null && picture == null) {
                    CameraStats.failed.incrementAndGet()
                    CameraStats.lastFailure = "not a JPEG"
                }
                if (picture != null) {
                    shown = picture
                    CameraStats.shown.incrementAndGet()
                } else {
                    delay(CameraFetch.RETRY_MS)
                }
            }
        }
    }
    // The clock that turns an old picture into «No live image» (criterion 3:
    // within 1.5 s of the camera stopping).
    LaunchedEffect(Unit) {
        while (true) {
            delay(200)
            now = SystemClock.uptimeMillis()
        }
    }

    val current = shown?.takeIf { CameraFetch.isLive(it.at, now) }
    val ground = remember { Paint().apply { style = Paint.Style.FILL } }
    val text = remember {
        Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.CENTER
        }
    }
    val picture = remember { Paint().apply { isFilterBitmap = true } }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val scale = density
        val box = RectF(obj.x.toFloat() * scale, obj.y.toFloat() * scale, (obj.x + obj.width).toFloat() * scale, (obj.y + obj.height).toFloat() * scale)
        drawIntoCanvas { canvas ->
            val c = canvas.nativeCanvas
            if (current == null) {
                // The boards' 0x2104 and white (ColorScreenRenderer::drawCameraMissing).
                ground.color = android.graphics.Color.rgb(33, 32, 33)
                c.drawRect(box, ground)
                text.textSize = min(obj.height.toFloat() * 0.12f, (obj.width.toFloat() - 8f) / 7f) * scale
                val baseline = box.centerY() - (text.ascent() + text.descent()) / 2
                c.drawText(CameraFetch.MISSING, box.centerX(), baseline, text)
            } else {
                ground.color = android.graphics.Color.BLACK
                c.drawRect(box, ground)
                val b = current.bitmap
                // A source pixel is a unit, as on a board: fitted at the size
                // the camera sent, whatever the decoder sampled it down to.
                val f = CameraFetch.fit(current.sourceW, current.sourceH, obj.x.toInt(), obj.y.toInt(), boxW, boxH)
                c.drawBitmap(b, Rect(0, 0, b.width, b.height), RectF(f[0] * scale, f[1] * scale, (f[0] + f[2]) * scale, (f[1] + f[3]) * scale), picture)
            }
        }
    }
}
