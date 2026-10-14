package com.schaltli.android.test

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.PixelCopy
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The app's test interface: what the boards' TestInterfaceServer is to them
 * (designer repo, docs/device-contract.md §6, schaltli-designer#70). Until
 * 2026-10-14 the Android HIL drove the phone over adb - screencap, input,
 * am, run-as, logcat; now it asks the app, the way it asks a board, and adb
 * only installs.
 *
 * Off from the factory. Switched on in the app's settings, or over adb
 * (BrokerConfigReceiver) - both need the phone in someone's hands - with a
 * password every request carries: `Authorization: Bearer <password>`, or
 * `?pw=`. On port 8080, which an app may open; 80 it may not.
 *
 *   GET  /snapshot.png       the app's own window, as drawn (PixelCopy)
 *   POST /api/tap            x, y in window pixels
 *   POST /api/swipe          x1, y1, x2, y2, ms
 *   GET  /api/debug          id, version, size, density, awake, broker
 *   POST /api/restart        the app starts afresh, process and all
 *   POST /api/device-settings displayOffSeconds
 *   POST /api/mqtt           host, port, username, password
 *
 * A picture of its own window, not of the screen: a notification banner or
 * the status bar is not in it, which is why the HIL no longer silences them.
 */
class AppTestInterface(
    private val activity: Activity,
    private val password: String,
    private val debug: () -> Map<String, Any?>,
    private val saveDisplayOff: (Int) -> Unit,
    private val saveBroker: (host: String, port: Int, username: String, password: String) -> Unit,
    private val port: Int = PORT,
) {
    companion object {
        const val PORT = 8080
        private const val TAG = "AppTestInterface"
    }

    private var socket: ServerSocket? = null
    private val main = Handler(Looper.getMainLooper())

    fun start(): Boolean {
        if (socket != null) return true
        return try {
            val server = ServerSocket(port)
            socket = server
            thread(isDaemon = true, name = "test-interface") { accept(server) }
            Log.i(TAG, "listening on $port, with a password")
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not listen on $port", e)
            false
        }
    }

    fun stop() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    private fun accept(server: ServerSocket) {
        while (!server.isClosed) {
            try {
                val client = server.accept()
                thread(isDaemon = true, name = "test-interface-request") { client.use { respond(it) } }
            } catch (e: Exception) {
                if (server.isClosed) return
                Log.w(TAG, "accept failed", e)
            }
        }
    }

    private class Request(val method: String, val path: String, val params: Map<String, String>, val headers: Map<String, String>)

    private fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (bytes.size() == 0) null else bytes.toString("UTF-8")
            if (b == '\n'.code) return bytes.toString("UTF-8").trimEnd('\r')
            bytes.write(b)
            if (bytes.size() > 8192) return null
        }
    }

    private fun parseQuery(query: String, into: MutableMap<String, String>) {
        for (pair in query.split("&")) {
            if (pair.isEmpty()) continue
            val i = pair.indexOf('=')
            val key = URLDecoder.decode(if (i < 0) pair else pair.substring(0, i), "UTF-8")
            val value = if (i < 0) "" else URLDecoder.decode(pair.substring(i + 1), "UTF-8")
            into[key] = value
        }
    }

    private fun read(client: Socket): Request? {
        val input = client.getInputStream()
        val requestLine = readLine(input) ?: return null
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val parts = requestLine.split(" ")
        val target = parts.getOrNull(1) ?: "/"
        val params = mutableMapOf<String, String>()
        val q = target.indexOf('?')
        if (q >= 0) parseQuery(target.substring(q + 1), params)
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length in 1..65536 && headers["content-type"]?.startsWith("application/x-www-form-urlencoded") == true) {
            val body = ByteArray(length)
            var at = 0
            while (at < length) {
                val n = input.read(body, at, length - at)
                if (n <= 0) break
                at += n
            }
            parseQuery(String(body, 0, at, Charsets.UTF_8), params)
        }
        return Request(parts.getOrNull(0) ?: "", if (q >= 0) target.substring(0, q) else target, params, headers)
    }

    private fun authorized(request: Request): Boolean =
        password.isNotEmpty() && (request.headers["authorization"] == "Bearer $password" || request.params["pw"] == password)

    private fun send(client: Socket, status: Int, type: String, body: ByteArray) {
        val reason = mapOf(200 to "OK", 400 to "Bad Request", 401 to "Unauthorized", 404 to "Not Found", 500 to "Internal Server Error")[status] ?: "OK"
        val out = client.getOutputStream()
        out.write("HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    private fun json(client: Socket, status: Int, value: Map<String, Any?>) =
        send(client, status, "application/json", toJson(value).toByteArray())

    private fun respond(client: Socket) {
        val request = read(client) ?: return
        if (!authorized(request)) {
            send(client, 401, "text/plain", "The test interface needs its password".toByteArray())
            return
        }
        try {
            when ("${request.method} ${request.path}") {
                "GET /snapshot.png" -> {
                    val png = snapshot() ?: return json(client, 500, mapOf("success" to false, "message" to "The window could not be copied"))
                    send(client, 200, "image/png", png)
                }
                "POST /api/tap" -> {
                    val x = request.params["x"]?.toFloatOrNull()
                    val y = request.params["y"]?.toFloatOrNull()
                    if (x == null || y == null) return json(client, 400, mapOf("success" to false, "message" to "x and y, please"))
                    gesture(listOf(x to y, x to y), 60)
                    json(client, 200, mapOf("success" to true))
                }
                "POST /api/swipe" -> {
                    val p = listOf("x1", "y1", "x2", "y2").map { request.params[it]?.toFloatOrNull() }
                    val ms = request.params["ms"]?.toLongOrNull() ?: 250
                    if (p.any { it == null }) return json(client, 400, mapOf("success" to false, "message" to "x1, y1, x2, y2 and ms, please"))
                    gesture(listOf(p[0]!! to p[1]!!, p[2]!! to p[3]!!), ms)
                    json(client, 200, mapOf("success" to true))
                }
                "GET /api/debug" -> json(client, 200, debug() + mapOf(
                    "interactive" to (activity.getSystemService(PowerManager::class.java)?.isInteractive ?: true),
                ))
                "POST /api/restart" -> {
                    json(client, 200, mapOf("success" to true, "message" to "Restarting"))
                    main.postDelayed({ restart() }, 200)
                }
                "POST /api/device-settings" -> {
                    val seconds = request.params["displayOffSeconds"]?.toIntOrNull()
                        ?: return json(client, 400, mapOf("success" to false, "message" to "displayOffSeconds, please"))
                    saveDisplayOff(seconds)
                    json(client, 200, mapOf("success" to true))
                }
                "POST /api/mqtt" -> {
                    val host = request.params["host"]?.trim().orEmpty()
                    if (host.isEmpty()) return json(client, 400, mapOf("success" to false, "message" to "host, please"))
                    saveBroker(host, request.params["port"]?.toIntOrNull() ?: 1883, request.params["username"].orEmpty(), request.params["password"].orEmpty())
                    json(client, 200, mapOf("success" to true))
                }
                else -> send(client, 404, "text/plain", "Not here".toByteArray())
            }
        } catch (e: Exception) {
            Log.w(TAG, "request failed", e)
            json(client, 500, mapOf("success" to false, "message" to (e.message ?: e.javaClass.simpleName)))
        }
    }

    // The window as drawn: PixelCopy from Android 8.0 on, which copies what
    // the GPU put there; before it the view hierarchy drawn again into a
    // bitmap, which is what an Android 6 phone has.
    private fun snapshot(): ByteArray? {
        val window = activity.window
        val view = window.decorView
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var ok = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            main.post {
                try {
                    PixelCopy.request(window, bitmap, { result -> ok = result == PixelCopy.SUCCESS; done.countDown() }, main)
                } catch (e: Exception) {
                    Log.w(TAG, "PixelCopy failed", e)
                    done.countDown()
                }
            }
        } else {
            main.post {
                try {
                    view.draw(android.graphics.Canvas(bitmap))
                    ok = true
                } finally {
                    done.countDown()
                }
            }
        }
        if (!done.await(5, TimeUnit.SECONDS) || !ok) return null
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    // A finger on the window: down at the first point, moved to the last in
    // steps of about 10 ms, up there. Dispatched to the window itself, which
    // needs no permission an app could not have.
    private fun gesture(points: List<Pair<Float, Float>>, ms: Long) {
        val (x1, y1) = points.first()
        val (x2, y2) = points.last()
        val downAt = SystemClock.uptimeMillis()
        val steps = (ms / 10).coerceIn(1, 200).toInt()
        val finished = CountDownLatch(1)
        fun send(action: Int, x: Float, y: Float, at: Long) {
            val event = MotionEvent.obtain(downAt, at, action, x, y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            activity.window.decorView.dispatchTouchEvent(event)
            event.recycle()
        }
        main.post { send(MotionEvent.ACTION_DOWN, x1, y1, downAt) }
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val x = x1 + (x2 - x1) * t
            val y = y1 + (y2 - y1) * t
            main.postAtTime({ send(MotionEvent.ACTION_MOVE, x, y, SystemClock.uptimeMillis()) }, downAt + ms * i / steps)
        }
        main.postAtTime({
            send(MotionEvent.ACTION_UP, x2, y2, SystemClock.uptimeMillis())
            finished.countDown()
        }, downAt + ms + 1)
        finished.await(ms + 5000, TimeUnit.MILLISECONDS)
    }

    // Afresh, process and all: what kept nothing over a restart is the
    // question the local-topics check asks (contract §2.9).
    private fun restart() {
        val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName) ?: return
        activity.startActivity(Intent.makeRestartActivityTask(intent.component))
        Runtime.getRuntime().exit(0)
    }
}

/** A small JSON writer for flat maps of strings, numbers, booleans, nulls and nested maps. */
internal fun toJson(value: Any?): String = when (value) {
    null -> "null"
    is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    is Number, is Boolean -> value.toString()
    is Map<*, *> -> value.entries.joinToString(",", "{", "}") { "${toJson(it.key.toString())}:${toJson(it.value)}" }
    is List<*> -> value.joinToString(",", "[", "]") { toJson(it) }
    else -> toJson(value.toString())
}
