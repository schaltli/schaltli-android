package com.schaltli.android.data

import com.schaltli.android.mqtt.BrokerConfig
import com.schaltli.android.mqtt.MqttRepository
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local topics (the designer's docs/device-contract.md §2.9): which names
 * are local, and the repository keeping their values itself - written by a
 * publish that goes nowhere, kept over a reconnect, reset by a load.
 */
class LocalTopicsTest {

    private val topics = listOf(
        Topic(id = "v", topic = "local/view", local = true, initial = "flow"),
        Topic(id = "n", topic = "local/none", local = true),
        Topic(id = "r", topic = "van/light", examples = listOf("on")),
    )

    @Test
    fun `only a declared local topic is local, compared without its path`() {
        val local = LocalTopics(topics)
        assertTrue(local.isLocal("local/view"))
        assertTrue(local.isLocal("local/view#mode"))
        assertTrue(local.isLocal("local/none"))
        assertFalse(local.isLocal("van/light"))
        assertFalse(local.isLocal("local/undeclared"))
        assertFalse(LocalTopics.NONE.isLocal("local/view"))
    }

    @Test
    fun `the initial values are those of the local topics that have one`() {
        assertEquals(mapOf("local/view" to "flow"), LocalTopics(topics).initialValues)
    }

    @Test(timeout = 30_000)
    fun `a local write is applied at once, goes nowhere, survives a reconnect and is reset by a load`() {
        val repository = MqttRepository()
        val dropped = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { repository.droppedCommands.collect { dropped += it } }
        try {
            repository.setLocalTopics(LocalTopics(topics))
            assertEquals("flow", repository.topicValues.value["local/view"])
            assertNull(repository.topicValues.value["local/none"])

            // No broker at all: a local write still lands, and is no dropped command.
            assertTrue(repository.publish("local/view", "sankey"))
            assertEquals("sankey", repository.topicValues.value["local/view"])
            repository.noteAsked("local/view", "sankey")
            assertNull(repository.askedValues.value["local/view"])

            // A connection (to a port nobody listens on) empties the broker's values, not these.
            repository.connect(BrokerConfig(host = "127.0.0.1", port = freePort()), setOf("van/light"))
            assertEquals("sankey", repository.topicValues.value["local/view"])

            // A real topic goes to the broker - and without one is dropped.
            assertFalse(repository.publish("van/light", "on"))
            Thread.sleep(300)
            assertEquals(listOf("van/light"), dropped.toList())

            // A load: back to the initial value.
            repository.setLocalTopics(LocalTopics(topics))
            assertEquals("flow", repository.topicValues.value["local/view"])
            // And a project without them leaves none behind.
            repository.setLocalTopics(LocalTopics.NONE)
            assertNull(repository.topicValues.value["local/view"])
        } finally {
            repository.disconnect()
            scope.cancel()
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
