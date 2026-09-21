package com.screensmith.android.ddf

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Serves this phone's DDF zip at `http://<phone>:8080/ddf.zip`, which is what
 * the retained `hello` points the designer at.
 *
 * A whole HTTP library would be a lot of jar for one static file that is
 * already in memory, so this is a socket, a request line and a response. It
 * answers GET /ddf.zip and refuses everything else, which is the entire
 * contract (device-contract.md SS4: "serve its own DDF zip at `url`
 * unauthenticated").
 *
 * Unauthenticated, exactly as the boards do it, and for the same reason: the
 * designer has no credentials to offer and this is a local network. What is
 * behind the port is a description of a screen - no project data, no broker
 * credentials, nothing that is not already announced on the broker.
 */
class DdfServer(private val port: Int = DEFAULT_PORT) {

    companion object {
        const val DEFAULT_PORT = 8080
        private const val TAG = "DdfServer"
    }

    private var socket: ServerSocket? = null
    private var payload: ByteArray = ByteArray(0)

    /** The url to announce, or null while the phone has no address to be reached at. */
    fun url(): String? = lanAddress()?.let { "http://$it:$port/ddf.zip" }

    /**
     * Starts serving [bytes], replacing whatever was served before. Called
     * again whenever the screen size changes - a fold opening, or the phone
     * being rotated into a layout with different room - so the bytes and the
     * announced hash never disagree.
     */
    fun serve(bytes: ByteArray) {
        payload = bytes
        if (socket != null) return
        try {
            val server = ServerSocket(port)
            socket = server
            thread(isDaemon = true, name = "ddf-server") { accept(server) }
            Log.i(TAG, "serving ${bytes.size} bytes on port $port")
        } catch (e: Exception) {
            // A port already taken is worth saying once, and worth carrying
            // on without: the app's own job does not depend on it, only the
            // designer's ability to discover this phone.
            Log.w(TAG, "could not listen on $port", e)
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
                server.accept().use { respond(it) }
            } catch (e: Exception) {
                if (server.isClosed) return
                Log.w(TAG, "request failed", e)
            }
        }
    }

    private fun respond(client: Socket) {
        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        val requestLine = reader.readLine() ?: return
        // The rest of the headers are read and dropped: nothing here varies
        // by them, but leaving them unread makes some clients report a reset
        // instead of reading the response.
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }

        val parts = requestLine.split(" ")
        val method = parts.getOrNull(0) ?: ""
        val path = parts.getOrNull(1) ?: ""
        val out = client.getOutputStream()

        if (method != "GET" || !path.startsWith("/ddf.zip")) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            return
        }

        val body = payload
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/zip\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    /**
     * The address the designer can reach, which is the phone's own address on
     * whatever network it is on - not localhost, and not an IPv6 link-local
     * one, which is unroutable from the machine the designer runs on.
     */
    private fun lanAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is InetAddress && !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
                ?.hostAddress
        } catch (e: Exception) {
            Log.w(TAG, "no address", e)
            null
        }
    }
}
