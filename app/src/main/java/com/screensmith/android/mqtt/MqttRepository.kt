package com.screensmith.android.mqtt

import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

data class BrokerConfig(
    val host: String,
    val port: Int = 1883,
    val username: String = "",
    val password: String = "",
)

/**
 * Broker connection + topic subscriptions, mirroring the firmware's
 * `MqttClient` wrapper (`MqttClient.h/.cpp`) closely enough that the same
 * exported project behaves the same way here: QoS 0 throughout (matches
 * `PubSubClient`, which has no other mode), non-retained publishes, and -
 * critically - automatic reconnect that re-subscribes to every topic on
 * every successful (re)connection, not just the first one
 * (`MqttClient.cpp:59-64`).
 */
class MqttRepository {
    private var client: Mqtt3AsyncClient? = null
    private var subscribedTopics: Set<String> = emptySet()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _topicValues = MutableStateFlow<Map<String, String>>(emptyMap())
    val topicValues: StateFlow<Map<String, String>> = _topicValues.asStateFlow()

    /** (Re)connects to [config] and subscribes to every topic in [topics]. */
    fun connect(config: BrokerConfig, topics: Set<String>) {
        Log.i("MqttRepository", "connect() called: host=${config.host} port=${config.port} topics=$topics")
        disconnect()
        subscribedTopics = topics
        _topicValues.value = emptyMap()

        val builder = MqttClient.builder()
            .useMqttVersion3()
            .identifier("screensmith-android-${System.currentTimeMillis()}")
            .serverHost(config.host)
            .serverPort(config.port)
            .automaticReconnect()
            .initialDelay(1, TimeUnit.SECONDS)
            .maxDelay(30, TimeUnit.SECONDS)
            .applyAutomaticReconnect()
            .addConnectedListener { resubscribeAll() }
            .addDisconnectedListener { _connectionState.value = ConnectionState.DISCONNECTED }

        val asyncClient = builder.buildAsync()
        client = asyncClient

        _connectionState.value = ConnectionState.CONNECTING
        var connectBuilder = asyncClient.connectWith()
        if (config.username.isNotBlank()) {
            connectBuilder = connectBuilder
                .simpleAuth()
                .username(config.username)
                .password(config.password.toByteArray(StandardCharsets.UTF_8))
                .applySimpleAuth()
        }
        Log.i("MqttRepository", "calling send()")
        val future = connectBuilder.send()
        Log.i("MqttRepository", "send() returned, future=$future")
        future.whenComplete { _, throwable ->
            if (throwable != null) {
                Log.e("MqttRepository", "connect failed", throwable)
            } else {
                Log.i("MqttRepository", "connect ack received")
            }
        }
    }

    fun disconnect() {
        client?.disconnect()
        client = null
        subscribedTopics = emptySet()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /** Publishes [message] to [topic] verbatim - QoS 0, non-retained, same as the firmware's send-mqtt. */
    fun publish(topic: String, message: String) {
        client?.publishWith()
            ?.topic(topic)
            ?.qos(MqttQos.AT_MOST_ONCE)
            ?.payload(message.toByteArray(StandardCharsets.UTF_8))
            ?.send()
    }

    private fun resubscribeAll() {
        val activeClient = client ?: return
        _connectionState.value = ConnectionState.CONNECTED
        for (topic in subscribedTopics) {
            activeClient.subscribeWith()
                .topicFilter(topic)
                .qos(MqttQos.AT_MOST_ONCE)
                .callback { publish ->
                    val payload = String(publish.payloadAsBytes, StandardCharsets.UTF_8)
                    _topicValues.update { it + (topic to payload) }
                }
                .send()
        }
    }
}
