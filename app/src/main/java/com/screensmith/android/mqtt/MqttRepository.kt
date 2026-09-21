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

/** The prefix every ScreenBee topic sits under (the designer's lib/topic-prefix.ts). */
private const val TOPIC_PREFIX = "screenbee"

/** What this app can read of a project (the designer's lib/system-generation.ts). */
private const val SYSTEM_GENERATION = "1.0"

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

    /**
     * What a finger asked of a value, keyed by the topic the request is about
     * (the designer's docs/2026-09-17-settable-level.md, decision 6c): a
     * settable level draws it as its setpoint marker, while its fill keeps
     * showing what the installation reports - and the two coincide once the
     * command has landed.
     *
     * Keyed by topic rather than by object, because two bars on one dimmer
     * are one value and both show the request. Dropped the moment a message
     * arrives on that topic: from then on the installation's word stands,
     * whether it confirms the request or contradicts it. No timeout, because
     * the bridge asks again every two seconds, so a command that never landed
     * corrects itself.
     */
    private val _askedValues = MutableStateFlow<Map<String, String>>(emptyMap())
    val askedValues: StateFlow<Map<String, String>> = _askedValues.asStateFlow()

    fun noteAsked(topic: String, value: String) {
        if (topic.isEmpty()) return
        _askedValues.update { it + (topic to value) }
    }

    /**
     * What this phone announces to the designer while it is connected: its
     * own id, the DDF it serves and where (docs/2026-09-21-android-self-
     * announce.md in the designer repo). Null means "say nothing", which is
     * what a phone with no address to be reached at can honestly claim.
     */
    data class Announcement(
        val deviceId: String,
        val deviceName: String,
        val appVersion: String,
        val ddfHash: String,
        val url: String?,
    )

    private var announcement: Announcement? = null

    /**
     * Handed each retained `deploy` payload as it arrives, for the phone to
     * fetch and install (DeployReceiver). Set alongside the announcement:
     * without an id there is no topic to listen on.
     */
    var onDeploy: ((String) -> Unit)? = null

    /**
     * Sets what to announce on this and every later (re)connection.
     *
     * Retained, so the designer finds the phone whenever a browser tab is
     * opened on the startup gate rather than only while it happens to be
     * watching - the same reason every board retains its own hello. Under a
     * stable client id (DeviceIdentity), or each launch would leave another
     * retained message behind.
     */
    fun setAnnouncement(value: Announcement?) {
        announcement = value
        publishAnnouncement()
    }

    private fun publishAnnouncement() {
        val hello = announcement ?: return
        val active = client ?: return
        val payload = buildString {
            append("{")
            append("\"deviceId\":\"${escapeJson(hello.deviceId)}\"")
            append(",\"name\":\"${escapeJson(hello.deviceName)}\"")
            append(",\"firmwareVersion\":\"${escapeJson(hello.appVersion)}\"")
            append(",\"systemGeneration\":\"$SYSTEM_GENERATION\"")
            append(",\"ddfHash\":\"${escapeJson(hello.ddfHash)}\"")
            // A device that omits `url` is treated as "does not self-announce
            // its DDF" and skipped by the designer's discovery, which is the
            // right reading of a phone that has no address to be fetched at.
            if (hello.url != null) append(",\"url\":\"${escapeJson(hello.url)}\"")
            append("}")
        }
        val base = "$TOPIC_PREFIX/${hello.deviceId}"
        active.publishWith()
            .topic("$base/hello")
            .qos(MqttQos.AT_MOST_ONCE)
            .retain(true)
            .payload(payload.toByteArray(StandardCharsets.UTF_8))
            .send()
        active.publishWith()
            .topic("$base/status")
            .qos(MqttQos.AT_MOST_ONCE)
            .retain(true)
            .payload("online".toByteArray(StandardCharsets.UTF_8))
            .send()
        Log.i("MqttRepository", "announced $base -> ${hello.url}")
    }

    /**
     * Where this device's own topics live - the same `screenbee/<clientId>`
     * every board publishes under, with the stable id from DeviceIdentity.
     */
    private fun deviceBase(): String? = announcement?.let { "$TOPIC_PREFIX/${it.deviceId}" }

    /** Reports where a deploy has got to, non-retained, as the contract's §4 says. */
    fun publishDeployStatus(payload: String) {
        val base = deviceBase() ?: return
        client?.publishWith()
            ?.topic("$base/deploy-status")
            ?.qos(MqttQos.AT_MOST_ONCE)
            ?.payload(payload.toByteArray(StandardCharsets.UTF_8))
            ?.send()
    }

    private fun escapeJson(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"")

    /** (Re)connects to [config] and subscribes to every topic in [topics]. */
    fun connect(config: BrokerConfig, topics: Set<String>) {
        Log.i("MqttRepository", "connect() called: host=${config.host} port=${config.port} topics=$topics")
        disconnect()
        subscribedTopics = topics
        _topicValues.value = emptyMap()
        _askedValues.value = emptyMap()

        val builder = MqttClient.builder()
            .useMqttVersion3()
            // Stable, because the announcement below is retained under it:
            // a fresh id per launch would leave one more retained hello on
            // the broker every time the app started.
            .identifier(announcement?.deviceId ?: "screensmith-android")
            .serverHost(config.host)
            .serverPort(config.port)
            .automaticReconnect()
            .initialDelay(1, TimeUnit.SECONDS)
            .maxDelay(30, TimeUnit.SECONDS)
            .applyAutomaticReconnect()
            .addConnectedListener {
                resubscribeAll()
                // Every reconnection, not just the first: a broker restart
                // loses retained messages, and a phone that announced once
                // an hour ago would then be invisible.
                publishAnnouncement()
            }
            .addDisconnectedListener { _connectionState.value = ConnectionState.DISCONNECTED }

        val asyncClient = builder.buildAsync()
        client = asyncClient

        _connectionState.value = ConnectionState.CONNECTING
        var connectBuilder = asyncClient.connectWith()
        // The other half of the retained status: the broker says "offline"
        // for us when this connection drops, whether or not the app had a
        // chance to say anything itself.
        announcement?.let { hello ->
            connectBuilder = connectBuilder
                .willPublish()
                .topic("$TOPIC_PREFIX/${hello.deviceId}/status")
                .qos(MqttQos.AT_MOST_ONCE)
                .retain(true)
                .payload("offline".toByteArray(StandardCharsets.UTF_8))
                .applyWillPublish()
        }
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

        // The deploy topic is this device's own, not one of the project's -
        // and it is retained, so a deploy published while the phone was off
        // arrives the moment it comes back.
        deviceBase()?.let { base ->
            activeClient.subscribeWith()
                .topicFilter("$base/deploy")
                .qos(MqttQos.AT_MOST_ONCE)
                .callback { publish ->
                    val payload = String(publish.payloadAsBytes, StandardCharsets.UTF_8)
                    if (payload.isNotBlank()) onDeploy?.invoke(payload)
                }
                .send()
        }

        for (topic in subscribedTopics) {
            activeClient.subscribeWith()
                .topicFilter(topic)
                .qos(MqttQos.AT_MOST_ONCE)
                .callback { publish ->
                    val payload = String(publish.payloadAsBytes, StandardCharsets.UTF_8)
                    _topicValues.update { it + (topic to payload) }
                    // The installation has spoken about this value: whatever a
                    // finger asked of it is answered, and its marker goes.
                    _askedValues.update { if (it.containsKey(topic)) it - topic else it }
                }
                .send()
        }
    }
}
