package com.schaltli.android.mqtt

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A command tapped while the broker is away must be dropped, not delivered
 * whenever the broker comes back.
 *
 * Until 2026-09-24 it was delivered late, and worse: the client is built with
 * automatic reconnect, and a publish handed to it while it retries does not
 * return at all - it waits inside the library (MqttPublishFlowables.add) until
 * the broker is back, then goes out. Measured against the old publish on
 * 2026-09-24: blocked for as long as the broker was away, and the broker
 * received the command the moment it returned. On the phone the caller is the
 * UI thread, so a tap during an outage froze the app for the whole outage.
 * Against the old publish this test therefore does not fail but hang, which
 * is what the timeout below turns into a failure. The firmware has always
 * dropped such a command (its MqttClient::publish).
 *
 * The real repository and the real client, against a stand-in broker on
 * localhost: a few dozen lines of MQTT 3.1.1, enough to accept a connection
 * and write down every PUBLISH that reaches it.
 */
class CommandDropTest {

    @Test(timeout = 90_000)
    fun `a command made while the broker is away is dropped, and not sent when it is back`() {
        val port = freePort()
        val repository = MqttRepository()
        val dropped = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { repository.droppedCommands.collect { dropped += it } }

        try {
            repository.connect(BrokerConfig(host = "127.0.0.1", port = port), emptySet())
            // Long enough for the first attempt to fail, so the client is
            // retrying - the state in which it used to queue.
            Thread.sleep(1500)

            assertFalse(repository.publish("van/pump", "on"))
            eventually("the drop is reported") { dropped == listOf("van/pump") }

            StandInBroker(port).use { broker ->
                eventually("the repository reconnects", timeoutMs = 45_000) {
                    repository.connectionState.value == ConnectionState.CONNECTED
                }
                // Anything the client had kept would go out right after the
                // CONNACK; give it the time.
                Thread.sleep(1500)
                assertEquals(emptyList<String>(), broker.published.toList())

                // And a command made while connected still goes out.
                assertTrue(repository.publish("van/light", "on"))
                eventually("a connected command arrives") { broker.published.toList() == listOf("van/light") }
            }
        } finally {
            repository.disconnect()
            scope.cancel()
        }
    }

    @Test
    fun `without a connection no request is marked as asked`() {
        val repository = MqttRepository()
        assertFalse(repository.publish("van/dimmer", "40"))
        repository.noteAsked("van/dimmer/state", "40")
        assertEquals(emptyMap<String, String>(), repository.askedValues.value)
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun eventually(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting until $what")
            Thread.sleep(50)
        }
    }
}

/**
 * Just enough of an MQTT 3.1.1 broker: CONNACK, SUBACK and PINGRESP, and the
 * topic of every PUBLISH written down. QoS 0 only, which is all the app sends.
 */
private class StandInBroker(port: Int) : Closeable {
    val published = CopyOnWriteArrayList<String>()
    private val server = ServerSocket(port, 50, InetAddress.getLoopbackAddress())
    private val sockets = CopyOnWriteArrayList<Socket>()

    init {
        thread(isDaemon = true, name = "stand-in-broker") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: IOException) { break }
                sockets += socket
                thread(isDaemon = true) { serve(socket) }
            }
        }
    }

    private fun serve(socket: Socket) = try {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output = socket.getOutputStream()
        while (true) {
            val header = input.read()
            if (header < 0) break
            val body = ByteArray(remainingLength(input)).also { input.readFully(it) }
            when (header shr 4) {
                1 -> output.write(byteArrayOf(0x20, 0x02, 0x00, 0x00)) // CONNECT -> CONNACK, accepted
                3 -> published += String(body, 2, u16(body, 0), StandardCharsets.UTF_8) // PUBLISH
                8 -> { // SUBSCRIBE -> SUBACK granting QoS 0 to each filter
                    var i = 2
                    var filters = 0
                    while (i < body.size) { i += 2 + u16(body, i) + 1; filters++ }
                    output.write(byteArrayOf(0x90.toByte(), (2 + filters).toByte(), body[0], body[1]) + ByteArray(filters))
                }
                10 -> output.write(byteArrayOf(0xB0.toByte(), 0x02, body[0], body[1])) // UNSUBSCRIBE -> UNSUBACK
                12 -> output.write(byteArrayOf(0xD0.toByte(), 0x00)) // PINGREQ -> PINGRESP
                14 -> break // DISCONNECT
            }
            output.flush()
        }
    } catch (e: IOException) {
        // The client went away; nothing to record.
    } finally {
        socket.close()
    }

    private fun remainingLength(input: DataInputStream): Int {
        var value = 0
        var shift = 0
        while (true) {
            val byte = input.readUnsignedByte()
            value = value or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) return value
            shift += 7
        }
    }

    private fun u16(bytes: ByteArray, at: Int) = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

    override fun close() {
        server.close()
        sockets.forEach { it.close() }
    }
}
