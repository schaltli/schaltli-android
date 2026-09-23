package com.schaltli.android.mqtt

import com.hivemq.client.mqtt.MqttClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Tries a broker once and says what happened, without touching the
 * connection the app is living on.
 *
 * Setup on a panel is a form you fill in blind: type an address, save, and
 * find out whether it was right by whether the screen ever shows a value.
 * This answers the question where it is asked - a wrong port or a typo in
 * the host says so in a second, and a broker that refuses the password says
 * that rather than nothing.
 *
 * Deliberately its own client, with its own random identifier: a broker
 * disconnects an existing session when a second one arrives under the same
 * id, so probing with the app's own id would kick the app off the broker it
 * is already talking to. And no automatic reconnect - a probe that keeps
 * retrying in the background is not a probe.
 */
suspend fun probeBroker(config: BrokerConfig, timeoutMs: Long = 6000): Result<Unit> =
    withContext(Dispatchers.IO) {
        val client = MqttClient.builder()
            .useMqttVersion3()
            .identifier("schaltli-probe-" + UUID.randomUUID().toString().take(8))
            .serverHost(config.host)
            .serverPort(config.port)
            .buildAsync()

        try {
            var connect = client.connectWith()
            if (config.username.isNotBlank()) {
                connect = connect
                    .simpleAuth()
                    .username(config.username)
                    .password(config.password.toByteArray(StandardCharsets.UTF_8))
                    .applySimpleAuth()
            }
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    connect.send().whenComplete { _, error ->
                        if (continuation.isActive) {
                            continuation.resume(if (error == null) Result.success(Unit) else Result.failure(error))
                        }
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            Result.failure(Exception("no answer within ${timeoutMs / 1000}s"))
        } catch (error: Throwable) {
            Result.failure(error)
        } finally {
            runCatching { client.disconnect() }
        }
    }

/**
 * Why a connection did not happen, in words someone standing at the device
 * can use.
 *
 * What the client hands over is a chain: a wrapper, then Netty's
 * `AnnotatedConnectException: Connection refused: /127.0.0.1:1883`, then a
 * plain `ConnectException: Connection refused`. The class names help nobody
 * holding a phone, and the one thing that would help - the address that was
 * refused - is carried by the middle link only.
 *
 * So the whole chain is read and the most specific line kept, rather than
 * the deepest. The deepest was the first rule here and it threw the address
 * away (seen on the device, 2026-09-22): "Connection refused", of what?
 */
fun Throwable.readableReason(): String {
    val classPrefix = Regex("^(?:[\\w.\$]+(?:Exception|Error|Throwable):\\s*)+")
    // "Connection refused: /127.0.0.1:1883" - the slash is how Java prints an
    // address it never resolved, not part of the address.
    val straySlash = Regex("(^|\\s)/")
    // A link whose whole message is its own class name says nothing at
    // all - and being the longest line in the chain, it would otherwise
    // win (the HiveMQ wrapper does exactly that).
    val bareClassName = Regex("^[\\w.\$]+(?:Exception|Error|Throwable)$")

    val said = mutableListOf<String>()
    var link: Throwable? = this
    val seen = mutableSetOf<Throwable>()
    while (link != null && seen.add(link)) {
        link.message?.trim()?.takeIf { it.isNotEmpty() }?.let { message ->
            said += straySlash.replace(classPrefix.replace(message, ""), "$1").trim()
        }
        link = link.cause
    }
    return said.filter { it.isNotEmpty() && !bareClassName.matches(it) }.maxByOrNull { it.length }
        ?: this::class.java.simpleName
}
