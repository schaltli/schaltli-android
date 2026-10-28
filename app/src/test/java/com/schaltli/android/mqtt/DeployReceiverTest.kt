package com.schaltli.android.mqtt

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.CoroutineContext

/**
 * What [DeployReceiver] answers before it fetches anything: the retained
 * trigger taken off the broker, and `busy` only for another deploy.
 *
 * The install itself never runs here - the scope holds on to what is
 * launched - so a deploy taken stays the one running.
 */
class DeployReceiverTest {
    private object Held : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {}
    }

    private class Phone {
        val states: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var cleared = 0
        val receiver = DeployReceiver(
            install = { Result.success(Unit) },
            publishStatus = { states += JSONObject(it).getString("deployId") + ":" + JSONObject(it).getString("state") },
            clearTrigger = { synchronized(this) { cleared++ } },
            scope = CoroutineScope(Held),
        )
    }

    private fun deploy(id: String) = """{"deployId":"$id","url":"http://192.0.2.1/bundle.zip","crc32":1}"""

    @Test
    fun aDeployIsTakenOffTheBroker() {
        val phone = Phone()
        phone.receiver.onDeploy(deploy("a"))
        assertEquals(1, phone.cleared)
        // The clearing comes back as an empty message: not cleared again.
        phone.receiver.onDeploy("")
        assertEquals(1, phone.cleared)
    }

    @Test
    fun busyOnlyForAnotherDeploy() {
        val phone = Phone()
        phone.receiver.onDeploy(deploy("a"))
        phone.receiver.onDeploy(deploy("a"))
        phone.receiver.onDeploy(deploy("b"))
        assertEquals(listOf("b:busy"), phone.states)
    }

    /** The same deploy delivered twice at once is never told busy. */
    @Test
    fun twoDeliveriesAtOnceAreOne() {
        repeat(300) { round ->
            val phone = Phone()
            val start = CountDownLatch(1)
            val threads = List(2) { Thread { start.await(); phone.receiver.onDeploy(deploy("r$round")) } }
            threads.forEach { it.start() }
            start.countDown()
            threads.forEach { it.join() }
            assertEquals("round $round", emptyList<String>(), phone.states)
        }
    }
}
